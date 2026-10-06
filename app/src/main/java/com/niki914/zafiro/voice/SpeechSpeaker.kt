package com.niki914.zafiro.voice

import android.content.Context
import com.niki914.logging.Logger
import com.niki914.zafiro.repo.XRepo
import java.util.concurrent.Executors

/**
 * 把 Agent 的回复流式念出来：正文每长出一句就排队合成，而不是等整个回合结束再统一朗读。
 *
 * 调用方在回合开始时 [beginReply]，然后每次正文有变化就 [feedReply]；
 * 文本流结束时传 `isFinal = true`，全部念完（或失败）后回调一次。
 *
 * 本身只负责「后端调度」，文本管线（增量比对 + 整句切分 + 归一化）在 [SpeechChunker]，
 * 真正的发音交给 [ReplyVoice]：
 * 默认走端侧本地音色（[LocalTtsModels.DEFAULT_ID]），模型目录缺失或加载失败时自动回落系统 TTS。
 * Markdown 正文改写成可念文本的规则见 [SpeechTextNormalizer]。
 * 后端选择在实例创建时读取一次；切换设置由调用方重建本实例（见
 * `AgentRuntimeService.ACTION_SPEECH_BACKEND_CHANGED`）。
 */
class SpeechSpeaker(private val context: Context) {

    companion object {
        private const val TAG = "ZafiroSpeaker"

        private const val WORKER_NAME = "ZafiroReplyVoice"
    }

    private val systemVoice = SystemTtsVoice(context)
    private val localVoice = LocalTtsVoice(context, LocalTtsModels.byId(LocalTtsModels.DEFAULT_ID))

    /**
     * 后端调度串行化：后端选择、入队都在这个线程上，不卡主线程。
     * 真正的合成/播放由后端自己的线程做（见 [LocalTtsVoice] 的流水线）。
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, WORKER_NAME).apply { isDaemon = true }
    }

    /** 选定并预热过的后端；只在 [worker] 上读写。 */
    private var activeVoice: ReplyVoice? = null

    /** 文本管线：增量比对 + 整句切分 + 归一化（纯逻辑，单测见 SpeechChunkerTest）。 */
    private val chunker = SpeechChunker()

    private var queueCount = 0
    private var streamClosed = false
    private var onAllDone: (() -> Unit)? = null

    /** 异步初始化引擎。可重复调用。 */
    @Synchronized
    fun initialize() {
        post { runCatching { resolveVoice() }.onFailure { Logger.w(TAG, "voice warm up failed: ${it.message}") } }
    }

    /** 开始一轮回复朗读：清掉上一轮残留的正文与队列状态。 */
    @Synchronized
    fun beginReply() {
        chunker.reset()
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
        val fed = chunker.feed(fullBody, isFinal)
        if (fed.rewritten) {
            Logger.w(TAG, "reply text rewritten, restarting from body length=${fullBody.length}")
        }
        fed.sentences.forEach(::enqueue)

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
        localVoice.stop()
        systemVoice.stop()
    }

    @Synchronized
    fun shutdown() {
        onAllDone = null
        localVoice.stop()
        systemVoice.stop()
        post {
            localVoice.shutdown()
            systemVoice.shutdown()
        }
        // 已排队的释放任务照常执行，之后线程退出
        worker.shutdown()
    }

    private fun enqueue(text: String) {
        queueCount++
        post {
            val voice = try {
                resolveVoice()
            } catch (e: Exception) {
                Logger.e(TAG, "voice unavailable: ${e.message}")
                null
            }
            if (voice == null) {
                // 保证每句都有回调，别让上层队列悬挂
                onUtteranceFinished()
                return@post
            }
            Logger.i(
                TAG,
                "queued backend=${voice.name} textLength=${text.length} " +
                    "text=${text.replace('\n', '⏎')}",
            )
            voice.speak(text) { onUtteranceFinished() }
        }
    }

    /**
     * 只在 [worker] 上调用：确定本轮后端并预热。
     * 后端 = 本地音色（[LocalTtsModels]），不可用/未配置时回落系统 TTS。
     */
    private fun resolveVoice(): ReplyVoice {
        activeVoice?.let { return it }

        val preferred = ReplyVoiceBackend.fromStored(XRepo.replyVoiceBackendSetting.value)
        val voice = when (preferred) {
            ReplyVoiceBackend.System -> systemVoice
            ReplyVoiceBackend.Local -> when (localVoice.prepare()) {
                ReplyVoiceAvailability.Ready -> localVoice
                ReplyVoiceAvailability.NotConfigured -> {
                    // 没配本地模型：不是故障，静默走系统 TTS
                    Logger.i(TAG, "local TTS model not configured, using system TTS")
                    systemVoice
                }

                ReplyVoiceAvailability.Failed -> {
                    Logger.w(TAG, "local TTS unavailable, fallback to system TTS")
                    systemVoice
                }
            }
        }
        voice.prepare()
        activeVoice = voice
        Logger.i(TAG, "reply voice backend=${voice.name} (preferred=${preferred.storageValue})")
        return voice
    }

    private fun post(task: () -> Unit) {
        if (worker.isShutdown) return
        runCatching { worker.execute(task) }
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

}
