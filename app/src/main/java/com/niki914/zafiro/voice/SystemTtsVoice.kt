package com.niki914.zafiro.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.niki914.logging.Logger
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 系统 TTS 朗读后端（本机默认引擎是 sherpa-onnx 中文引擎）。
 *
 * 一次 [speak] 对应一条 utterance，播完/出错后回调一次；多次调用交给引擎排队，
 * 天然按顺序、不重叠。
 */
internal class SystemTtsVoice(context: Context) : ReplyVoice {

    companion object {
        private const val TAG = "ZafiroSpeaker"
        private const val UTTERANCE_PREFIX = "zafiro-reply-"

        /** 语速。1.0 是引擎默认，1.4 比默认快一档、又不到赶的程度。 */
        private const val SPEECH_RATE = 1.4f
    }

    private val appContext = context.applicationContext

    override val name = "system"

    private val lock = Any()
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    private val sequence = AtomicInteger(0)

    /** utteranceId → 该句的完成回调；用 remove 保证恰好回调一次。 */
    private val pending = ConcurrentHashMap<String, () -> Unit>()

    override fun prepare(): ReplyVoiceAvailability {
        synchronized(lock) {
            if (tts != null) return ReplyVoiceAvailability.Ready
            tts = TextToSpeech(appContext) { status -> onInit(status) }
            return ReplyVoiceAvailability.Ready
        }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        val engine = tts
        if (engine == null || !ready) {
            Logger.w(TAG, "skip speaking (ready=$ready): ${text.take(20)}…")
            onDone()
            return
        }
        val utteranceId = "$UTTERANCE_PREFIX${sequence.incrementAndGet()}"
        pending[utteranceId] = onDone
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        Logger.i(TAG, "queued textLength=${text.length}")
    }

    override fun stop() {
        tts?.stop()
        drainPending()
    }

    override fun shutdown() {
        synchronized(lock) {
            tts?.shutdown()
            tts = null
            ready = false
        }
        drainPending()
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Logger.e(TAG, "TTS init failed, status=$status")
            return
        }
        val engine = tts ?: return
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

            override fun onDone(utteranceId: String?) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
        })
        ready = true
        Logger.i(TAG, "TTS ready, engine=${engine.defaultEngine}, language=$language, rate=$SPEECH_RATE")
    }

    private fun finish(utteranceId: String?) {
        if (utteranceId == null) return
        pending.remove(utteranceId)?.invoke()
    }

    /** 停止/销毁后把还没等到回调的句子一次性收尾，避免上层队列悬挂。 */
    private fun drainPending() {
        pending.keys.toList().forEach { finish(it) }
    }
}
