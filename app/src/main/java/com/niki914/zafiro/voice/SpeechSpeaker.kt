package com.niki914.zafiro.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.niki914.logging.Logger
import java.util.Locale

/**
 * 把 Agent 的回复流式念出来：正文每长出一句就排队合成，而不是等整个回合结束再统一朗读。
 *
 * 调用方在回合开始时 [beginReply]，然后每次正文有变化就 [feedReply]；
 * 文本流结束时传 `isFinal = true`，全部念完（或失败）后回调一次。
 *
 * 走系统 TTS（本机默认引擎是 sherpa-onnx 中文引擎）。
 */
class SpeechSpeaker(private val context: Context) {

    companion object {
        private const val TAG = "ZafiroSpeaker"
        private const val UTTERANCE_PREFIX = "zafiro-reply-"

        /** 句末标点：一到就切，保证「说一句、播一句」的低延迟。 */
        private const val HARD_BREAKS = "。！？!?；;"

        /** 软切点：只在已经攒够字数时才切。换行/逗号一到就切会把句子打得很碎，反而卡顿。 */
        private const val SOFT_BREAKS = "，,\n"

        /** 少于这个字数不切，避免「好。」这类碎句单独占一次合成。 */
        private const val MIN_CHUNK = 8

        /** 攒到这个字数后，允许在软切点断句。 */
        private const val SOFT_CHUNK = 60

        /** 无论如何都不断超过这个字数。 */
        private const val MAX_CHUNK = 120

        /** 语速。1.0 是引擎默认，稍快一点听着不拖沓。 */
        private const val SPEECH_RATE = 1.5f
    }

    private var tts: TextToSpeech? = null
    private var ready = false

    /** 已经喂进来的正文（累积快照），用来算增量。 */
    private var lastBody = ""

    /** 尚未切出去的正文尾巴。 */
    private val pending = StringBuilder()

    private var sequence = 0
    private var queueCount = 0
    private var streamClosed = false
    private var onAllDone: (() -> Unit)? = null

    /** 异步初始化引擎。可重复调用。 */
    @Synchronized
    fun initialize() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Logger.e(TAG, "TTS init failed, status=$status")
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            val language = engine.setLanguage(Locale.CHINA)
            // 必须等引擎就绪后再设，之后所有 speak 都按这个语速走
            engine.setSpeechRate(SPEECH_RATE)
            if (language == TextToSpeech.LANG_MISSING_DATA ||
                language == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Logger.w(TAG, "Chinese unsupported, setLanguage=$language")
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = onUtteranceFinished()

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = onUtteranceFinished()

                override fun onError(utteranceId: String?, errorCode: Int) = onUtteranceFinished()
            })
            ready = true
            Logger.i(TAG, "TTS ready, engine=${engine.defaultEngine}, language=$language, rate=$SPEECH_RATE")
        }
    }

    /** 开始一轮回复朗读：清掉上一轮残留的正文与队列状态。 */
    @Synchronized
    fun beginReply() {
        lastBody = ""
        pending.setLength(0)
        streamClosed = false
        onAllDone = null
        Logger.i(TAG, "reply stream begin, queue=$queueCount")
    }

    /**
     * 喂入当前完整正文（累积快照）。
     *
     * @param isFinal 文本流是否已结束。为 true 时把剩余尾巴也切出去，
     *   并在队列念完后回调 [onDone]。
     */
    @Synchronized
    fun feedReply(fullBody: String, isFinal: Boolean, onDone: () -> Unit = {}) {
        val delta = if (fullBody.startsWith(lastBody)) {
            fullBody.substring(lastBody.length)
        } else {
            // 正文被重写（换块/重试）：已念出去的收不回，从当前文本重新接上
            Logger.w(TAG, "reply text rewritten (${lastBody.length} -> ${fullBody.length})")
            pending.setLength(0)
            fullBody
        }
        lastBody = fullBody
        if (delta.isNotEmpty()) pending.append(cleanForSpeech(delta))

        cutReady(force = isFinal)

        if (isFinal) {
            streamClosed = true
            onAllDone = onDone
            if (queueCount == 0) {
                // 没有可念的内容：立刻收尾，别让调用方一直等
                drainAll()
            }
        }
    }

    fun stop() {
        tts?.stop()
    }

    @Synchronized
    fun shutdown() {
        onAllDone = null
        tts?.shutdown()
        tts = null
        ready = false
    }

    /** 把 [pending] 里够完整的句子切出去排队。 */
    private fun cutReady(force: Boolean) {
        while (true) {
            val cut = nextCut(force) ?: break
            val sentence = pending.substring(0, cut).trim()
            pending.delete(0, cut)
            if (sentence.isNotEmpty()) enqueue(sentence)
        }
    }

    /** 返回可切位置（标点之后）；null 表示还攒得不够。 */
    private fun nextCut(force: Boolean): Int? {
        val text = pending
        if (text.isEmpty()) return null

        for (i in text.indices) {
            if (HARD_BREAKS.contains(text[i])) {
                // 很短的碎句也切（「你好。」），但不切单独一个标点
                return if (i + 1 >= 3) i + 1 else continue
            }
        }

        if (force) return text.length

        if (text.length >= SOFT_CHUNK) {
            for (i in text.length - 1 downTo SOFT_CHUNK - 1) {
                if (SOFT_BREAKS.contains(text[i])) return i + 1
            }
        }

        if (text.length >= MAX_CHUNK) return MAX_CHUNK
        return null
    }

    private fun enqueue(text: String) {
        val engine = tts
        if (engine == null || !ready) {
            Logger.w(TAG, "skip speaking (engine ready=$ready): ${text.take(20)}…")
            return
        }
        queueCount++
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "$UTTERANCE_PREFIX${sequence++}")
        Logger.i(TAG, "queued textLength=${text.length} queue=$queueCount")
    }

    private fun onUtteranceFinished() {
        val shouldDrain = synchronized(this) {
            if (queueCount > 0) queueCount--
            streamClosed && queueCount <= 0
        }
        if (shouldDrain) drainAll()
    }

    private fun drainAll() {
        val callback = synchronized(this) {
            val cb = onAllDone
            onAllDone = null
            streamClosed = false
            cb
        }
        Logger.i(TAG, "reply stream finished")
        callback?.invoke()
    }

    /**
     * 去掉 markdown 标记与列表符号,避免 TTS 把 `**`、反引号念出来,
     * 或把 `•` 念成「点」。数字、单位、百分号留给引擎处理。
     */
    private fun cleanForSpeech(text: String): String = text
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("`+"), "")
        .replace(Regex("[*_#>~|]"), "")
        .replace(Regex("[•·▪◦]"), " ")
}
