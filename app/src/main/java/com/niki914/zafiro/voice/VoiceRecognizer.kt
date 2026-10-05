package com.niki914.zafiro.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.niki914.logging.Logger
import java.io.File

/**
 * 一句话语音识别：录一段、用 Silero VAD 切句、SenseVoice 识别，返回文本。
 *
 * 模型不入包，放在应用私有目录 `files/models/sensevoice/`（SizeVoice int8 约 228MB，
 * 超出仓库单文件上限，也避免 APK 膨胀）。缺失时 [isModelReady] 为 false。
 *
 * 与 [WakeWordEngine] 共用麦克风，但不能同时持有：调用方需在开始前停掉唤醒监听，
 * 结束后再恢复。
 */
class VoiceRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "ZafiroVoiceAsr"
        private const val SAMPLE_RATE = 16000

        /** 模型目录（相对应用 filesDir）。 */
        const val MODEL_DIR = "models/sensevoice"

        private const val MODEL_FILE = "model.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val VAD_FILE = "silero_vad.onnx"

        /** 每轮 AudioRecord 读取的采样数（官方 VAD demo 的经验值）。 */
        private const val BUFFER_SAMPLES = 512

        /** 唤醒后等用户开口的上限。 */
        private const val SPEECH_START_TIMEOUT_MS = 8_000L

        /** 开口后整句的最长时限，防止卡死。 */
        private const val UTTERANCE_TIMEOUT_MS = 15_000L
    }

    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null

    private val modelDir: File get() = File(context.filesDir, MODEL_DIR)

    /** 模型文件是否就位。 */
    fun isModelReady(): Boolean {
        val dir = modelDir
        return File(dir, MODEL_FILE).let { it.isFile && it.length() > 0 } &&
            File(dir, TOKENS_FILE).isFile &&
            File(dir, VAD_FILE).isFile
    }

    /**
     * 录一句话并识别。阻塞调用，必须在后台线程执行。
     *
     * @return 识别到的文本；无语音、超时或模型不可用时为 null。
     */
    fun listenOnce(): String? {
        if (!hasPermission()) {
            Logger.w(TAG, "RECORD_AUDIO not granted, cannot recognize")
            return null
        }
        if (!isModelReady()) {
            Logger.e(TAG, "ASR model missing at ${modelDir.absolutePath}")
            return null
        }
        if (!ensureInitialized()) return null

        val sampleVad = vad ?: return null
        val sampleRecognizer = recognizer ?: return null

        val record = createAudioRecord()
        if (record == null) {
            Logger.e(TAG, "AudioRecord init failed")
            return null
        }

        sampleVad.reset()
        var text: String? = null
        var speechStarted = false
        val startWaitDeadline = System.currentTimeMillis() + SPEECH_START_TIMEOUT_MS
        var utteranceDeadline = Long.MAX_VALUE
        val buffer = ShortArray(BUFFER_SAMPLES)

        try {
            record.startRecording()
            Logger.i(TAG, "listening, recordingState=${record.recordingState}")

            loop@ while (true) {
                val now = System.currentTimeMillis()
                if (!speechStarted && now > startWaitDeadline) {
                    Logger.i(TAG, "no speech within ${SPEECH_START_TIMEOUT_MS}ms, give up")
                    break@loop
                }
                if (speechStarted && now > utteranceDeadline) {
                    Logger.i(TAG, "utterance exceeded ${UTTERANCE_TIMEOUT_MS}ms, stop")
                    break@loop
                }

                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                sampleVad.acceptWaveform(samples)

                if (!speechStarted && sampleVad.isSpeechDetected()) {
                    speechStarted = true
                    utteranceDeadline = now + UTTERANCE_TIMEOUT_MS
                    Logger.i(TAG, "speech detected")
                }

                while (!sampleVad.empty()) {
                    val segment = sampleVad.front()
                    sampleVad.pop()
                    val candidate = decode(sampleRecognizer, segment.samples)
                    if (candidate.isNotBlank()) {
                        text = candidate
                        break@loop
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "listen crashed: ${e.message}")
        } finally {
            runCatching { record.stop() }
            record.release()
            Logger.i(TAG, "listen finished textLength=${text?.length ?: 0}")
        }

        return text
    }

    /**
     * 预加载模型。SenseVoice int8 首次加载需 2-3s，提前在常驻启动时做完，
     * 唤醒后即可直接开始录音。可重复调用。
     */
    fun warmUp(): Boolean {
        if (!isModelReady()) {
            Logger.w(TAG, "warm up skipped: model missing at ${modelDir.absolutePath}")
            return false
        }
        return ensureInitialized()
    }

    /** 释放 native 资源。可重复调用。 */
    @Synchronized
    fun release() {
        vad?.release()
        vad = null
        recognizer?.release()
        recognizer = null
        Logger.i(TAG, "released")
    }

    @Synchronized
    private fun ensureInitialized(): Boolean {
        if (vad != null && recognizer != null) return true

        return try {
            val dir = modelDir
            val startedAt = System.currentTimeMillis()

            vad = Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = File(dir, VAD_FILE).absolutePath,
                        threshold = 0.5f,
                        // 说完整句后停顿多久算结束
                        minSilenceDuration = 0.8f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 15.0f,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                ),
            )

            recognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = File(dir, MODEL_FILE).absolutePath,
                            // 交给模型自动判语种：写死 "zh" 时中英混说的英文词会被硬翻成中文
                            language = "auto",
                            // 输出规范标点与阿拉伯数字
                            useInverseTextNormalization = true,
                        ),
                        tokens = File(dir, TOKENS_FILE).absolutePath,
                        numThreads = 2,
                        provider = "cpu",
                        debug = false,
                    ),
                ),
            )

            Logger.i(TAG, "models loaded in ${System.currentTimeMillis() - startedAt}ms")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "model init failed: ${e.message}")
            release()
            false
        }
    }

    private fun decode(engine: OfflineRecognizer, samples: FloatArray): String {
        val stream = engine.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            engine.decode(stream)
            engine.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun createAudioRecord(): AudioRecord? {
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) {
            Logger.e(TAG, "getMinBufferSize failed: $minBytes")
            return null
        }
        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBytes * 2,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Logger.e(TAG, "AudioRecord not initialized, state=${record.state}")
                record.release()
                null
            } else {
                record
            }
        } catch (e: Exception) {
            Logger.e(TAG, "AudioRecord init threw: ${e.message}")
            null
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
