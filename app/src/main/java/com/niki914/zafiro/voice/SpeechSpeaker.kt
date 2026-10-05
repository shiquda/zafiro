package com.niki914.zafiro.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.niki914.logging.Logger
import java.util.Locale

/**
 * 用系统 TTS 朗读 Agent 的回复。
 *
 * 走系统默认引擎（本机指向 sherpa-onnx 中文引擎），所以这里只管喂文本，
 * 不直接依赖具体引擎。
 */
class SpeechSpeaker(private val context: Context) {

    companion object {
        private const val TAG = "ZafiroSpeaker"
        private const val UTTERANCE_ID = "zafiro-reply"
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var onFinished: (() -> Unit)? = null

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
            if (language == TextToSpeech.LANG_MISSING_DATA ||
                language == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Logger.w(TAG, "Chinese unsupported, setLanguage=$language")
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = notifyFinished()

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = notifyFinished()

                override fun onError(utteranceId: String?, errorCode: Int) = notifyFinished()
            })
            ready = true
            Logger.i(TAG, "TTS ready, engine=${engine.defaultEngine}, language=$language")
        }
    }

    /**
     * 朗读 [text]，播完（或失败）后回调 [onDone]。
     * 引擎不可用时直接回调，保证调用方的状态机能继续走。
     */
    fun speak(text: String, onDone: () -> Unit) {
        val engine = tts
        if (engine == null || !ready) {
            Logger.w(TAG, "speak skipped: engine not ready")
            onDone()
            return
        }
        onFinished = onDone
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        Logger.i(TAG, "speaking textLength=${text.length}")
    }

    fun stop() {
        tts?.stop()
    }

    @Synchronized
    fun shutdown() {
        onFinished = null
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun notifyFinished() {
        val callback = onFinished
        onFinished = null
        Logger.i(TAG, "speech finished")
        callback?.invoke()
    }
}
