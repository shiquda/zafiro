package com.niki914.zafiro.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.niki914.logging.Logger

/**
 * 常驻唤醒词监听：单条 16kHz 麦克风流持续喂给 sherpa-onnx 的 [KeywordSpotter]，
 * 命中关键词时回调并重置流继续监听。
 *
 * 模型从 assets 读（[ASSET_DIR]），无需外部文件；关键词由 [keywords] 在启动时传入。
 *
 * 线程模型：[start] 建一个采集线程跑 [process]，[stop] 置位并 join。
 * 所有 sherpa native 对象只在该线程创建与释放。
 */
class WakeWordEngine(
    private val context: Context,
    private val keywords: String,
    private val onKeyword: (String) -> Unit,
) {

    companion object {
        private const val TAG = "ZafiroWakeWord"
        private const val SAMPLE_RATE = 16000
        private const val ASSET_DIR = "kws-en"
        private const val ENCODER = "$ASSET_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val DECODER = "$ASSET_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val JOINER = "$ASSET_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val TOKENS = "$ASSET_DIR/tokens.txt"
        private const val KEYWORDS = "$ASSET_DIR/keywords.txt"

        /** 每轮读取的音频时长（秒）。100ms 是 sherpa 官方 demo 的经验值。 */
        private const val READ_INTERVAL_SEC = 0.1
    }

    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    /** 是否具备录音权限。 */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 启动常驻监听。返回是否真正起来（权限缺失或麦克风占用时返回 false）。
     */
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            Logger.w(TAG, "RECORD_AUDIO not granted, cannot start wake word engine")
            return false
        }

        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) {
            Logger.e(TAG, "AudioRecord.getMinBufferSize failed: $minBytes")
            return false
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBytes * 2,
            )
        } catch (e: Exception) {
            Logger.e(TAG, "AudioRecord init failed: ${e.message}")
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Logger.e(TAG, "AudioRecord not initialized, state=${record.state}")
            record.release()
            return false
        }

        audioRecord = record
        running = true
        worker = Thread({ process(record) }, "zafiro-wake-word").apply { start() }
        Logger.i(TAG, "Wake word engine started, keywords=$keywords")
        return true
    }

    /** 停止监听并释放麦克风与 native 资源。可重复调用。 */
    fun stop() {
        if (!running && worker == null) return
        running = false
        worker?.join(2000)
        worker = null
        audioRecord?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioRecord = null
        Logger.i(TAG, "Wake word engine stopped")
    }

    private fun process(record: AudioRecord) {
        var spotter: KeywordSpotter? = null
        var stream: OnlineStream? = null
        try {
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = ENCODER,
                        decoder = DECODER,
                        joiner = JOINER,
                    ),
                    tokens = TOKENS,
                    modelType = "zipformer2",
                ),
                keywordsFile = KEYWORDS,
            )
            spotter = KeywordSpotter(assetManager = context.assets, config = config)
            stream = spotter.createStream(keywords)
            if (stream.ptr == 0L) {
                Logger.e(TAG, "Failed to create stream for keywords=$keywords")
                return
            }

            record.startRecording()
            Logger.i(TAG, "AudioRecord started, recordingState=${record.recordingState}")
            val buffer = ShortArray((READ_INTERVAL_SEC * SAMPLE_RATE).toInt())
            var zeroReads = 0

            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    zeroReads++
                    if (zeroReads == 1 || zeroReads % 100 == 0) {
                        Logger.w(TAG, "AudioRecord.read returned $read (count=$zeroReads)")
                    }
                    continue
                }
                zeroReads = 0
                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)

                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val keyword = spotter.getResult(stream).keyword
                    if (keyword.isNotBlank()) {
                        // 命中后立即重置，否则同一段音频会反复命中。
                        spotter.reset(stream)
                        Logger.i(TAG, "Wake word detected: $keyword")
                        runCatching { onKeyword(keyword) }
                            .onFailure { Logger.e(TAG, "onKeyword threw: ${it.message}") }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Wake word loop crashed: ${e.message}")
        } finally {
            stream?.release()
            spotter?.release()
            Logger.i(TAG, "Wake word loop exited")
        }
    }
}
