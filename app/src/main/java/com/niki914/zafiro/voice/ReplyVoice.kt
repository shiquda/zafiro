package com.niki914.zafiro.voice

/**
 * 回复朗读后端选择。落盘值见 [storageValue]，与 [com.niki914.zafiro.repo.XRepo] 的
 * `reply_voice_backend` 字段一一对应。
 */
enum class ReplyVoiceBackend(val storageValue: String) {
    /** 端侧本地音色（sherpa-onnx OfflineTts，具体模型见 [LocalTtsModels]）。 */
    Local("local"),

    /** 系统 TTS（本机默认引擎是 sherpa-onnx 中文引擎）。 */
    System("system"),
    ;

    companion object {
        val DEFAULT = Local

        /** 解析落盘值；未知/空值回默认后端。 */
        fun fromStored(value: String?): ReplyVoiceBackend =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}

/** [ReplyVoice.prepare] 的结果：可用 / 没配模型 / 配了但加载失败。 */
internal enum class ReplyVoiceAvailability {
    Ready,

    /** 本地后端没配置模型（例如 [LocalTtsModels.DEFAULT_ID] 为空）：静默回落系统 TTS。 */
    NotConfigured,

    /** 模型目录缺失或加载失败：回落系统 TTS 并告警。 */
    Failed,
}

/**
 * 回复朗读后端：一段文本进，合成+播放完成后回调一次。
 *
 * 约定：
 * - [prepare] / [speak] 可能阻塞，只能在后台线程调用；
 * - 多次 [speak] 必须按调用顺序播放，不重叠；
 * - [name] 只用于日志；
 * - [stop] 可从任意线程调用，用于打断当前播放；
 * - 每次 [speak] 必须恰好回调一次（失败时立刻回调，避免上层队列悬挂）。
 */
internal interface ReplyVoice {
    /** 日志用后端名。 */
    val name: String

    /** 阻塞式准备（加载模型/初始化引擎）。可重复调用。 */
    fun prepare(): ReplyVoiceAvailability

    /** 合成并播放 [text]；播放结束或失败后回调 [onDone]（可能在后台线程）。 */
    fun speak(text: String, onDone: () -> Unit)

    /** 打断当前播放并丢弃未播内容。 */
    fun stop()

    /** 释放底层资源。可重复调用。 */
    fun shutdown()
}
