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
 * 本身只负责「句切分 + 后端调度」，真正的发音交给 [ReplyVoice]：
 * 默认走端侧本地音色（[LocalTtsModels.DEFAULT_ID]），模型目录缺失或加载失败时自动回落系统 TTS。
 * 后端选择在实例创建时读取一次；切换设置由调用方重建本实例（见
 * `AgentRuntimeService.ACTION_SPEECH_BACKEND_CHANGED`）。
 */
class SpeechSpeaker(private val context: Context) {

    companion object {
        private const val TAG = "ZafiroSpeaker"

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

        private const val WORKER_NAME = "ZafiroReplyVoice"
    }

    private val systemVoice = SystemTtsVoice(context)
    private val localVoice = LocalTtsVoice(context, LocalTtsModels.byId(LocalTtsModels.DEFAULT_ID))

    /**
     * 引擎交互串行化：合成（重活）与后端选择都在这个线程上，既不卡主线程，
     * 也让多句天然按顺序播放、不重叠。
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, WORKER_NAME).apply { isDaemon = true }
    }

    /** 选定并预热过的后端；只在 [worker] 上读写。 */
    private var activeVoice: ReplyVoice? = null

    /** 已经喂进来的正文（累积快照），用来算增量。 */
    private var lastBody = ""

    /** 尚未切出去的正文尾巴。 */
    private val pending = StringBuilder()

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
            if (HARD_BREAKS.contains(text[i]) || isSentencePeriod(text, i)) {
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

    /**
     * ASCII 句点是否算句末。
     *
     * 中文回复靠 `。` 就能流式切句，英文回复只有 `.`，不额外认它就得等整段回复念完，
     * 流式朗读形同失效。但 `.` 同时出现在小数（`3.14`）、域名（`github.com`）里，
     * 所以只在「后面已跟空白、且前面不是数字或点」时才当句末；行尾的 `.` 由
     * [nextCut] 的 `force` 分支兜底。
     */
    private fun isSentencePeriod(text: CharSequence, i: Int): Boolean {
        if (text[i] != '.') return false
        val prev = text.getOrNull(i - 1) ?: return false
        if (prev.isDigit() || prev == '.') return false
        val next = text.getOrNull(i + 1) ?: return false
        return next.isWhitespace()
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
            Logger.i(TAG, "queued backend=${voice.name} textLength=${text.length}")
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
