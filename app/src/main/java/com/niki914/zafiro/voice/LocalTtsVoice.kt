package com.niki914.zafiro.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import com.niki914.logging.Logger
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 端侧本地音色朗读后端：sherpa-onnx `OfflineTts`，模型由 [LocalTtsModelSpec] 描述，
 * 可替换（VITS / MATCHA / KOKORO / ZIPVOICE 同一套 API，只是子配置不同）。
 *
 * 模型不入包，放在应用私有目录 `filesDir/models/<name>/`（与 ASR 的
 * [VoiceRecognizer.MODEL_DIR] 同款约定）。未配置、缺文件或加载失败时 [prepare] 返回
 * 非 Ready，由 [SpeechSpeaker] 回落到系统 TTS。
 *
 * 输出采样率不写死：一律用 `tts.sampleRate()` 驱动 AudioTrack。
 *
 * [speak] 阻塞（合成 + 播放），只在后台线程调用；由 [SpeechSpeaker] 保证串行，
 * 因此多句按顺序播放、不重叠。
 */
internal class LocalTtsVoice(
    context: Context,
    private val spec: LocalTtsModelSpec?,
) : ReplyVoice {

    companion object {
        private const val TAG = "ZafiroTts"

        private const val NUM_THREADS = 2

        /** ZipVoice 采样步数：4 是官方示例的速度/质量折中值。 */
        private const val ZIPVOICE_NUM_STEPS = 4

        /** 播放分片大小（采样数），约几十毫秒，作为 stop 的响应粒度。 */
        private const val PLAY_CHUNK_SAMPLES = 1024
    }

    private val appContext = context.applicationContext

    override val name: String = spec?.let { "local:${it.id}" } ?: "local:unconfigured"

    private val lock = Any()
    private val modelDir: File? get() = spec?.let { File(appContext.filesDir, it.dir) }

    private var tts: OfflineTts? = null
    private var referenceAudio: FloatArray? = null
    private var referenceSampleRate = 0
    private var referenceText = ""

    /** 缺配置/缺文件/加载失败后不再重试，避免每句都白白等一次加载。 */
    private var unavailable: ReplyVoiceAvailability? = null

    /** stop 用：代次号一变，正在播放的句子立刻收尾。 */
    private val stopEpoch = AtomicInteger(0)

    @Volatile
    private var currentTrack: AudioTrack? = null

    /** 模型文件是否就位。 */
    fun isModelReady(): Boolean {
        val dir = modelDir ?: return false
        val current = spec ?: return false
        fun file(name: String) = File(dir, name).let { it.isFile && it.length() > 0 }
        return current.requiredFiles().all(::file) &&
            current.requiredDirs().all { dir.resolve(it).isDirectory }
    }

    override fun prepare(): ReplyVoiceAvailability {
        synchronized(lock) {
            if (tts != null) return ReplyVoiceAvailability.Ready
            unavailable?.let { return it }
            val current = spec
            if (current == null) {
                // 没配模型（DEFAULT_ID 留空）：不是故障，静默回落系统 TTS
                unavailable = ReplyVoiceAvailability.NotConfigured
                return ReplyVoiceAvailability.NotConfigured
            }
            if (!isModelReady()) {
                Logger.w(TAG, "model missing at ${modelDir?.absolutePath}, fallback to system TTS")
                unavailable = ReplyVoiceAvailability.Failed
                return ReplyVoiceAvailability.Failed
            }
            return try {
                val dir = modelDir!!
                val startedAt = System.currentTimeMillis()
                val refer = if (current.referenceWav != null) parseReferWav() else null
                val referText = current.referenceText
                    ?.let { File(dir, it).readText().trim() }
                    .orEmpty()
                if (refer != null && (refer.samples.isEmpty() || referText.isEmpty())) {
                    throw IllegalStateException("reference audio/text empty")
                }
                val engine = OfflineTts(
                    assetManager = null,
                    config = buildConfig(current, dir),
                )
                tts = engine
                referenceAudio = refer?.samples
                referenceSampleRate = refer?.sampleRate ?: 0
                referenceText = referText
                Logger.i(
                    TAG,
                    "model loaded id=${current.id} kind=${current.kind} in " +
                        "${System.currentTimeMillis() - startedAt}ms, " +
                        "sampleRate=${engine.sampleRate()}, referSamples=${refer?.samples?.size ?: 0}" +
                        "@${refer?.sampleRate ?: 0}",
                )
                ReplyVoiceAvailability.Ready
            } catch (e: Exception) {
                Logger.e(TAG, "model load failed id=${current.id}: ${e.message}, fallback to system TTS")
                runCatching { tts?.release() }
                tts = null
                unavailable = ReplyVoiceAvailability.Failed
                ReplyVoiceAvailability.Failed
            }
        }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        val epoch = stopEpoch.get()
        if (prepare() != ReplyVoiceAvailability.Ready) {
            Logger.w(TAG, "engine unavailable, skip textLength=${text.length}")
            onDone()
            return
        }
        val engine = tts
        if (engine == null) {
            Logger.w(TAG, "engine not ready after prepare, skip textLength=${text.length}")
            onDone()
            return
        }

        val startedAt = System.currentTimeMillis()
        val audio = try {
            engine.generateWithConfig(
                text = text,
                config = GenerationConfig(
                    speed = 1.0f,
                    sid = spec?.sid ?: 0,
                    referenceAudio = referenceAudio ?: FloatArray(0),
                    referenceSampleRate = referenceSampleRate,
                    referenceText = referenceText,
                    numSteps = ZIPVOICE_NUM_STEPS,
                ),
            )
        } catch (e: Exception) {
            Logger.e(TAG, "synthesis failed: ${e.message}")
            onDone()
            return
        }

        val synthMs = System.currentTimeMillis() - startedAt
        val audioMs = audio.samples.size * 1000L / audio.sampleRate.coerceAtLeast(1)
        val rtf = if (audioMs > 0) synthMs.toDouble() / audioMs else 0.0
        Logger.i(
            TAG,
            "synth id=${spec?.id} textLength=${text.length} synthMs=$synthMs audioMs=$audioMs " +
                "rtf=${String.format(Locale.US, "%.3f", rtf)} sampleRate=${audio.sampleRate}",
        )

        try {
            play(audio.samples, audio.sampleRate, epoch)
        } catch (e: Exception) {
            Logger.e(TAG, "playback failed: ${e.message}")
        }
        onDone()
    }

    override fun stop() {
        stopEpoch.incrementAndGet()
    }

    override fun shutdown() {
        stopEpoch.incrementAndGet()
        synchronized(lock) {
            runCatching { tts?.release() }
            tts = null
            referenceAudio = null
            referenceSampleRate = 0
            referenceText = ""
            Logger.i(TAG, "released id=${spec?.id}")
        }
    }

    /** 按 [LocalTtsModelSpec.kind] 构建对应的 sherpa 子配置 + 规则 FST。 */
    private fun buildConfig(spec: LocalTtsModelSpec, dir: File): OfflineTtsConfig {
        fun path(name: String) = File(dir, name).absolutePath
        fun optional(name: String?) = name?.let(::path) ?: ""
        fun lexicons() = spec.lexiconFiles().joinToString(",") { path(it) }
        val ruleFsts = spec.ruleFsts.joinToString(",") { path(it) }

        val model = when (spec.kind) {
            LocalTtsModelKind.Vits -> OfflineTtsModelConfig(
                // 本版 --vits-dict-dir 未启用，不接 dictDir
                vits = OfflineTtsVitsModelConfig(
                    model = path(spec.model),
                    lexicon = lexicons(),
                    tokens = path(spec.tokens),
                    dataDir = optional(spec.dataDir),
                    lengthScale = spec.lengthScale,
                ),
                numThreads = NUM_THREADS,
                debug = false,
                provider = "cpu",
            )

            LocalTtsModelKind.Matcha -> OfflineTtsModelConfig(
                // 实测本版 matcha 必须有 lexicon（只给 data-dir 会报 Please provide lexicon.txt）
                matcha = OfflineTtsMatchaModelConfig(
                    acousticModel = path(spec.model),
                    vocoder = path(spec.vocoder ?: "vocoder.onnx"),
                    lexicon = lexicons(),
                    tokens = path(spec.tokens),
                    dataDir = optional(spec.dataDir),
                    lengthScale = spec.lengthScale,
                ),
                numThreads = NUM_THREADS,
                debug = false,
                provider = "cpu",
            )

            LocalTtsModelKind.Kokoro -> OfflineTtsModelConfig(
                // 本版 --kokoro-dict-dir 未启用，不接 dictDir
                kokoro = OfflineTtsKokoroModelConfig(
                    model = path(spec.model),
                    voices = path(spec.voices ?: "voices.bin"),
                    tokens = path(spec.tokens),
                    dataDir = optional(spec.dataDir),
                    lexicon = lexicons(),
                    lang = spec.lang ?: "",
                    lengthScale = spec.lengthScale,
                ),
                numThreads = NUM_THREADS,
                debug = false,
                provider = "cpu",
            )

            LocalTtsModelKind.ZipVoice -> OfflineTtsModelConfig(
                zipvoice = OfflineTtsZipVoiceModelConfig(
                    tokens = path(spec.tokens),
                    encoder = path(spec.encoder ?: "encoder.int8.onnx"),
                    decoder = path(spec.decoder ?: "decoder.int8.onnx"),
                    vocoder = path(spec.vocoder ?: "vocos_24khz.onnx"),
                    dataDir = optional(spec.dataDir),
                    lexicon = lexicons(),
                ),
                numThreads = NUM_THREADS,
                debug = false,
                provider = "cpu",
            )
        }
        return OfflineTtsConfig(model = model, ruleFsts = ruleFsts)
    }

    /** 播放整段合成结果；[epoch] 变化时提前收尾（stop 打断）。 */
    private fun play(samples: FloatArray, sampleRate: Int, epoch: Int) {
        if (samples.isEmpty()) return
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) throw IllegalStateException("getMinBufferSize=$minBytes")

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBytes * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        currentTrack = track
        try {
            track.play()
            var offset = 0
            while (offset < samples.size) {
                if (epoch != stopEpoch.get()) {
                    Logger.i(TAG, "playback interrupted at $offset/${samples.size}")
                    break
                }
                val count = minOf(PLAY_CHUNK_SAMPLES, samples.size - offset)
                val written = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) {
                    Logger.w(TAG, "AudioTrack write=$written, abort playback")
                    break
                }
                offset += written
            }
        } finally {
            currentTrack = null
            runCatching { track.stop() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }

    // ---- 参考音色 refer.wav 解析（仅 ZipVoice 用） ----------------------------

    private class ReferWav(val samples: FloatArray, val sampleRate: Int)

    /** 解析参考音色 wav（mono / PCM16 为主，兼容 PCM32F）。损坏或格式不支持时抛异常。 */
    private fun parseReferWav(): ReferWav {
        val dir = modelDir ?: throw IllegalStateException("no model dir")
        val name = spec?.referenceWav ?: throw IllegalStateException("no reference wav configured")
        val bytes = File(dir, name).readBytes()
        require(bytes.size > 44) { "refer.wav too small (${bytes.size} bytes)" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        require(readTag(buffer) == "RIFF") { "refer.wav: not RIFF" }
        buffer.int // riff size
        require(readTag(buffer) == "WAVE") { "refer.wav: not WAVE" }

        var format = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        while (buffer.remaining() >= 8) {
            val id = readTag(buffer)
            val size = buffer.int
            val chunkStart = buffer.position()
            when (id) {
                "fmt " -> {
                    format = buffer.short.toInt()
                    channels = buffer.short.toInt()
                    sampleRate = buffer.int
                    buffer.int // byteRate
                    buffer.short // blockAlign
                    bitsPerSample = buffer.short.toInt()
                }

                "data" -> {
                    dataOffset = chunkStart
                    dataSize = size
                    break
                }
            }
            // chunk 按偶数字节对齐；越界即认为后面没有需要的 chunk
            val skip = chunkStart + size + (size and 1)
            if (skip <= chunkStart || skip > bytes.size) break
            buffer.position(skip)
        }

        require(dataOffset >= 0 && dataSize > 0) { "refer.wav: no data chunk" }
        require(channels >= 1) { "refer.wav: invalid channels=$channels" }
        require(sampleRate > 0) { "refer.wav: invalid sampleRate=$sampleRate" }
        if (dataOffset + dataSize > bytes.size) {
            Logger.w(TAG, "refer.wav data chunk truncated, clamp to file length")
            dataSize = bytes.size - dataOffset
        }

        val bytesPerSample = bitsPerSample / 8
        require(bytesPerSample > 0) { "refer.wav: invalid bitsPerSample=$bitsPerSample" }
        val frameBytes = bytesPerSample * channels
        val frames = dataSize / frameBytes
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val offset = dataOffset + i * frameBytes + c * bytesPerSample
                sum += when {
                    format == 3 && bitsPerSample == 32 -> buffer.getFloat(offset)
                    bitsPerSample == 16 -> buffer.getShort(offset) / 32768.0f
                    bitsPerSample == 32 -> buffer.getInt(offset) / 2147483648.0f
                    bitsPerSample == 8 -> (bytes[offset].toInt() - 128) / 128.0f
                    else -> throw IllegalArgumentException(
                        "refer.wav: unsupported format=$format bits=$bitsPerSample",
                    )
                }
            }
            out[i] = sum / channels
        }
        return ReferWav(samples = out, sampleRate = sampleRate)
    }

    private fun readTag(buffer: ByteBuffer): String {
        val tag = ByteArray(4)
        buffer.get(tag)
        return String(tag, Charsets.US_ASCII)
    }
}
