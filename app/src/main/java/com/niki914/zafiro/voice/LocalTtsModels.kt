package com.niki914.zafiro.voice

/**
 * 本地音色模型种类：决定构建哪个 sherpa-onnx `OfflineTts*ModelConfig`。
 */
internal enum class LocalTtsModelKind {
    Vits,
    Matcha,
    Kokoro,
    ZipVoice,
}

/**
 * 一个可替换的本地音色模型：`kind`（架构）+ `dir`（模型目录）+ 文件集合。
 *
 * 模型不入包，放在应用私有目录 `filesDir/models/<id>/`（与 ASR 的
 * [VoiceRecognizer.MODEL_DIR] 同款约定）；文件名按 sherpa-onnx 官方解包结果，
 * 与 [LocalTtsModels] 里的登记保持一致。
 *
 * 播放层、队列语义、设置开关都跟模型无关，新增一个音色 = 在 [LocalTtsModels.ALL]
 * 里加一条并把 [LocalTtsModels.DEFAULT_ID] 指过去。
 *
 * 采样率不写死：一律用 `tts.sampleRate()` 驱动 AudioTrack（VITS 16k/MATCHA 16k/
 * KOKORO 24k/ZIPVOICE 24k）。
 */
internal data class LocalTtsModelSpec(
    /** 稳定标识：日志与默认值选择用。 */
    val id: String,
    val kind: LocalTtsModelKind,
    /** filesDir 下的模型目录。 */
    val dir: String,
    /** 主模型文件（VITS/KOKORO 模型，或 MATCHA 的声学模型）。 */
    val model: String = "model.onnx",
    val tokens: String = "tokens.txt",
    /** 发音词典；逗号分隔可给多个（Kokoro 中英双词典）。 */
    val lexicon: String? = "lexicon.txt",
    /** espeak-ng-data 目录名；null = 该模型不用 espeak。 */
    val dataDir: String? = null,
    /** MATCHA/ZIPVOICE 的声码器。 */
    val vocoder: String? = null,
    /** KOKORO 的音色表。 */
    val voices: String? = null,
    /** ZIPVOICE 的 encoder/decoder。 */
    val encoder: String? = null,
    val decoder: String? = null,
    /** KOKORO 的音色编号（中文女声从 3 起）。 */
    val sid: Int = 0,
    /** KOKORO 的语言标记；null = 交给引擎默认。 */
    val lang: String? = null,
    /** 文本正则化规则 FST（相对 dir），逗号串喂给 `OfflineTtsConfig.ruleFsts`。 */
    val ruleFsts: List<String> = emptyList(),
    /** 语速：lengthScale <1 更快，1.0 是引擎默认（VITS/MATCHA/KOKORO）。 */
    val lengthScale: Float = 1.0f,

    /**
     * 播放语速倍率：模型按自然速合成（[lengthScale] = 1），播放前用 [TimeStretcher]
     * 保音高变速到这个倍率。
     *
     * 为什么不用 [lengthScale] 提速：那是让模型把音素压短，实测压到 1.4x 就开始吞音连读
     * （`苹果居中` → "苹果粥"）。而 `AudioTrack` 自带的变速是纯重采样会变调。
     */
    val speechRate: Float = 1.0f,
    /** ZIPVOICE 零样本克隆用的参考音色。 */
    val referenceWav: String? = null,
    val referenceText: String? = null,
) {
    /** lexicon 展开成文件名列表（逗号分隔的多词典）。 */
    fun lexiconFiles(): List<String> =
        lexicon?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** 就位所需文件（相对 [dir]）；与 [buildModelConfig] 用到的路径必须一致。 */
    fun requiredFiles(): List<String> = when (kind) {
        LocalTtsModelKind.Vits -> listOf(model, tokens) + lexiconFiles() + ruleFsts
        LocalTtsModelKind.Matcha ->
            listOfNotNull(model, vocoder, tokens) + lexiconFiles() + ruleFsts

        LocalTtsModelKind.Kokoro ->
            listOfNotNull(model, voices, tokens) + lexiconFiles() + ruleFsts

        LocalTtsModelKind.ZipVoice ->
            listOfNotNull(encoder, decoder, vocoder, tokens) +
                lexiconFiles() + listOfNotNull(referenceWav, referenceText)
    }

    /** 就位所需目录（相对 [dir]）。 */
    fun requiredDirs(): List<String> = listOfNotNull(dataDir)
}

/**
 * 本地音色模型清单（候选模型由设备解包结果登记）。
 *
 * [DEFAULT_ID] 是唯一的默认值开关：benchmark + 听感结论出来后只改这一处；
 * 留空 = 本地后端视为未配置，朗读自动回落系统 TTS（不崩、不刷告警）。
 */
internal object LocalTtsModels {
    const val DEFAULT_ID = "matcha-icefall-zh-en"

    val VITS_PIPER_ZH_XIAO_YA = LocalTtsModelSpec(
        id = "vits-piper-zh_CN-xiao_ya-medium-int8",
        kind = LocalTtsModelKind.Vits,
        dir = "models/vits-piper-zh_CN-xiao_ya-medium-int8",
        model = "zh_CN-xiao_ya-medium.onnx",
        lexicon = "lexicon.txt",
        ruleFsts = listOf("date.fst", "number.fst", "phone.fst"),
    )

    val VITS_ZH_HF_FANCHEN_C = LocalTtsModelSpec(
        id = "vits-zh-hf-fanchen-C",
        kind = LocalTtsModelKind.Vits,
        dir = "models/vits-zh-hf-fanchen-C",
        model = "vits-zh-hf-fanchen-C.onnx",
        lexicon = "lexicon.txt",
        ruleFsts = listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst"),
    )

    /** 注意：同目录的 `model.int8.onnx` 是 0.1KB 坏桩，必须用 fp32 的 `model.onnx`。 */
    val VITS_MELO_TTS_ZH_EN = LocalTtsModelSpec(
        id = "vits-melo-tts-zh_en",
        kind = LocalTtsModelKind.Vits,
        dir = "models/vits-melo-tts-zh_en",
        model = "model.onnx",
        lexicon = "lexicon.txt",
        ruleFsts = listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst"),
    )

    /** 默认音色：客观校验（SenseVoice 回读）+ RTF 两项都过，101MB 声学 + 54MB 声码器。 */
    val MATCHA_ICEFALL_ZH_EN = LocalTtsModelSpec(
        id = "matcha-icefall-zh-en",
        kind = LocalTtsModelKind.Matcha,
        dir = "models/matcha-icefall-zh-en",
        model = "model-steps-3.onnx",
        vocoder = "vocos-16khz-univ.onnx",
        lexicon = "lexicon.txt",
        dataDir = "espeak-ng-data",
        ruleFsts = listOf("date-zh.fst", "number-zh.fst"),
        // 模型按自然速合成（自然速 ≈4.4 字/秒 ≈265 字/分），语速由 speechRate 在播放前做保音高变速。
        // 实测：模型自己压时长到 1.2x 起就开始吞音（`清爽水润，甜而多汁` → "甜多汁"）；
        // 而「自然速合成 + 保音高变速」到 1.4x 的回听仍与自然速一致（ffmpeg atempo 参考实现 + 本仓库 WSOLA 对比验证）。
        lengthScale = 1.0f,
        speechRate = 1.4f,
    )

    /** 中文女声从 sid=3（`zf_001`）起。 */
    val KOKORO_INT8_MULTI_LANG = LocalTtsModelSpec(
        id = "kokoro-int8-multi-lang-v1_1",
        kind = LocalTtsModelKind.Kokoro,
        dir = "models/kokoro-int8-multi-lang-v1_1",
        model = "model.int8.onnx",
        voices = "voices.bin",
        lexicon = "lexicon-zh.txt,lexicon-us-en.txt",
        dataDir = "espeak-ng-data",
        sid = 3,
        ruleFsts = listOf("date-zh.fst", "number-zh.fst", "phone-zh.fst"),
    )

    /** 设备上已有的离线兜底：ZipVoice 零样本克隆（Pixel 5 上 RTF 偏高，仅作可选项）。 */
    val ZIP_VOICE = LocalTtsModelSpec(
        id = "zipvoice",
        kind = LocalTtsModelKind.ZipVoice,
        dir = "models/zipvoice",
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        vocoder = "vocos_24khz.onnx",
        lexicon = "lexicon.txt",
        dataDir = "espeak-ng-data",
        referenceWav = "refer.wav",
        referenceText = "refer.txt",
    )

    val ALL: List<LocalTtsModelSpec> = listOf(
        VITS_PIPER_ZH_XIAO_YA,
        VITS_ZH_HF_FANCHEN_C,
        VITS_MELO_TTS_ZH_EN,
        MATCHA_ICEFALL_ZH_EN,
        KOKORO_INT8_MULTI_LANG,
        ZIP_VOICE,
    )

    fun byId(id: String): LocalTtsModelSpec? = ALL.firstOrNull { it.id == id }
}
