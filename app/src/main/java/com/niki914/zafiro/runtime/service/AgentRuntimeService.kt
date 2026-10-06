package com.niki914.zafiro.runtime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import androidx.core.net.toUri
import com.niki914.logging.Logger
import com.niki914.store.HostApp
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.store.XIpcStoreRepository

import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.TurnFailureCode
import com.niki914.zafiro.app.MainActivity
import com.niki914.zafiro.chat.ToolStatusLabels
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.runtime.ipc.IAgentRuntimeService
import com.niki914.zafiro.runtime.ipc.IAgentStoreService
import com.niki914.zafiro.runtime.ipc.IRenderFrameCallback
import com.niki914.zafiro.runtime.ipc.RenderFrame
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import com.niki914.zafiro.app.R as AppR

import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.app.notification.ResidentNotificationBuilder
import com.niki914.zafiro.app.notification.ResidentNotificationManager
import com.niki914.zafiro.business.notification.NotificationChannelManager
import com.niki914.zafiro.voice.SpeechSpeaker
import com.niki914.zafiro.voice.VoiceRecognizer
import com.niki914.zafiro.voice.WakeTonePlayer
import com.niki914.zafiro.voice.WakeWordEngine

class AgentRuntimeService : Service() {

    /** 唤醒词引擎：随「常驻」开关启停。 */
    private var wakeWordEngine: WakeWordEngine? = null

    /** 语音识别引擎：首次唤醒时加载 SenseVoice 模型，之后常驻复用。 */
    private var voiceRecognizer: VoiceRecognizer? = null

    /** 当前「录音 → 识别 → 触发回合」的作业，避免唤醒叠加。 */
    private var voiceTurnJob: Job? = null

    /** 回复朗读器：语音回合结束后把 Agent 的答复念出来。 */
    private var speechSpeaker: SpeechSpeaker? = null

    /** 唤醒提示音：命中后立刻给用户一个「听到了」的反馈。 */
    private var wakeTonePlayer: WakeTonePlayer? = null

    /** 语音回合的朗读是否已开始（用于只让出一次麦克风）。 */
    @Volatile
    private var replyStreamStarted = false

    /** 当前前台服务是否已拿到 microphone 类型（决定退到后台后还能否持有麦克风）。 */
    @Volatile
    private var foregroundMicType = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundWithMicFallback()
        observeStatus()
        scope.launch { restoreResidentFromSettings() }
    }

    /**
     * 常驻状态以持久化开关为准。
     *
     * 服务可能是被系统在后台拉起来的（开机、被回收后重启），此时 App 进程里的观察者
     * 可能还没跑完甚至根本没跑，不能只依赖它 —— 否则服务起来后没人叫它常驻，
     * 一个 STOP 或一次 unBind 就没了。
     */
    private suspend fun restoreResidentFromSettings() {
        // 先对齐一次设置：进程内的响应式 flow 初值是猜的默认值，没人读盘就一直是错的
        // （朗读后端、悬浮球等都在读 flow）。App 侧要等水合才敢动常驻开关，也是靠这一步。
        runCatching { XRepo.hydrateSettings() }
            .onFailure { Logger.w(LOG_TAG, "hydrate settings failed: ${it.message}") }
        val enabled = runCatching { XRepo.residentNotificationEnabled() }.getOrDefault(false)
        if (!enabled || isResidentRequested) return
        Logger.i(LOG_TAG, "resident enabled in settings, resume resident mode")
        isResidentRequested = true
        updateResidentNotification()
        startWakeWordListening()
    }

    private fun buildResidentNotification(): Notification = ResidentNotificationBuilder.build(
        context = this,
        channelManager = notificationChannelManager,
        status = agentControl.status.value,
    )

    /**
     * 前台服务类型必须显式指定。
     *
     * 无参 [startForeground] 会套用 manifest 上的全部类型（`specialUse|microphone`），
     * 而 Android 14 起**从后台**启动的 FGS 不允许使用 while-in-use 类型（microphone）：
     * 服务被系统在后台重启（开机、被回收后重启）时会抛 SecurityException，
     * onCreate 直接崩 —— 实测崩溃两次后 AM 放弃，常驻服务再也起不来。
     *
     * 所以先按 `specialUse|microphone` 试，被拒就退到只有 `specialUse`：服务一定活着，
     * 麦克风类型等用户到前台再升级（见 [upgradeMicForegroundType]）。
     */
    private fun startForegroundWithMicFallback() {
        val id = ResidentNotificationBuilder.NOTIFICATION_ID
        val notification = buildResidentNotification()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // API 28 及以下没有前台服务类型门槛，前台服务即可录音
            startForeground(id, notification)
            foregroundMicType = true
            return
        }
        val micTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(id, notification, micTypes)
            foregroundMicType = true
            Logger.i(LOG_TAG, "foreground type=specialUse|microphone")
        } catch (e: SecurityException) {
            Logger.w(
                LOG_TAG,
                "microphone FGS type rejected (${e.message?.take(120)}), use specialUse only",
            )
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            foregroundMicType = false
        }
    }

    /**
     * 已经在用户交互路径上（点常驻开关、语音回合）时把前台类型升级到含 microphone，
     * 之后即使退到后台也能继续持有麦克风。
     *
     * @return 本次调用是否真的补上了 microphone 类型（供调用方判断要不要重开唤醒引擎）。
     */
    private fun upgradeMicForegroundType(): Boolean {
        if (foregroundMicType || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val micTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(ResidentNotificationBuilder.NOTIFICATION_ID, buildResidentNotification(), micTypes)
            foregroundMicType = true
            Logger.i(LOG_TAG, "foreground type upgraded to specialUse|microphone")
            return true
        } catch (e: SecurityException) {
            Logger.w(LOG_TAG, "cannot upgrade to microphone FGS type: ${e.message?.take(120)}")
            return false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ResidentNotificationBuilder.ACTION_STOP -> {
                Logger.i(LOG_TAG, "Resident notification: Stop clicked")
                agentControl.stop()
            }
            ResidentNotificationBuilder.ACTION_APPROVE -> {
                Logger.i(LOG_TAG, "Resident notification: Approve clicked")
                ResidentNotificationManager.resolveApproval(ApprovalDecision.Allow)
            }
            ResidentNotificationBuilder.ACTION_DECLINE -> {
                Logger.i(LOG_TAG, "Resident notification: Decline clicked")
                ResidentNotificationManager.resolveApproval(ApprovalDecision.Deny)
            }
            ACTION_VOICE_INPUT -> {
                Logger.i(LOG_TAG, "ACTION_VOICE_INPUT -> voice capture from UI")
                // 与唤醒命中同一条链路：切到 scope 再停 KWS，避免自己 join 自己
                scope.launch { captureVoiceTurn() }
            }
            ACTION_SPEECH_BACKEND_CHANGED -> {
                Logger.i(LOG_TAG, "ACTION_SPEECH_BACKEND_CHANGED -> rebuild reply speaker")
                speechSpeaker?.shutdown()
                speechSpeaker = null
            }
            ACTION_START_RESIDENT -> {
                isResidentRequested = true
                updateResidentNotification()
                startWakeWordListening()
            }
            ACTION_STOP_RESIDENT -> {
                isResidentRequested = false
                stopWakeWordListening()
                if (boundClientsCount == 0 && activeTurn.get() == null) {
                    // 不能在这里无条件 stopForeground：若紧接着又收到 START（开关快速切回开），
                    // 前台状态已被撤掉，服务会被 AM 当作闲置服务回收（am_stop_idle_service）。
                    // 用 startId 版本的 stopSelf —— 只有没有更新的 start 时才真正停。
                    Logger.i(LOG_TAG, "ACTION_STOP_RESIDENT -> stopSelf(startId=$startId)")
                    stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (!validateCaller()) return null
        boundClientsCount++
        return StubImpl()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClientsCount = (boundClientsCount - 1).coerceAtLeast(0)
        Logger.i(
            LOG_TAG,
            "onUnbind clients=$boundClientsCount resident=$isResidentRequested turn=${activeTurn.get() != null}",
        )
        if (boundClientsCount == 0 && !isResidentRequested && activeTurn.get() == null) {
            Logger.i(LOG_TAG, "onUnbind -> stopSelf (no clients, not resident)")
            // 同理：不用 stopForeground 抢先撤前台状态，交给 stopSelf 收尾。
            stopSelf()
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Logger.i(LOG_TAG, "onDestroy clients=$boundClientsCount resident=$isResidentRequested")
        instance = null
        stopWakeWordListening()
        voiceTurnJob?.cancel()
        voiceTurnJob = null
        voiceRecognizer?.release()
        voiceRecognizer = null
        speechSpeaker?.shutdown()
        speechSpeaker = null
        wakeTonePlayer?.release()
        wakeTonePlayer = null
        statusJob?.cancel()
        activeTurn.getAndSet(null)?.job?.cancel()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * 启动常驻唤醒词监听。已在跑或无录音权限时静默跳过，
     * 权限申请由 UI 侧负责（service 不能弹运行时权限）。
     */
    @Synchronized
    private fun startWakeWordListening() {
        // 走到这里都是用户交互路径（常驻开关、语音回合、开机恢复常驻）：
        // 先把前台类型升到含 microphone —— 系统只允许处于前台/可见状态的进程拿这个类型。
        val typeUpgraded = upgradeMicForegroundType()
        if (wakeWordEngine != null) {
            // 已有引擎时类型补齐了必须重开一次：否则它会挂在拿不到麦克风的 AudioRecord 上，
            // 看着在跑其实听不见（开机后进前台就是这个状态）。
            if (!typeUpgraded) return
            Logger.i(LOG_TAG, "mic FGS type granted, restarting wake word engine")
            stopWakeWordListening()
        }
        if (!foregroundMicType) {
            // 拿不到 microphone 类型就别起引擎：后台录音会被 appops 直接拒掉，
            // 起一个听不见的引擎只会把问题藏起来。等下次进前台（升级成功后）再起。
            Logger.w(LOG_TAG, "no microphone FGS type yet, defer wake listening until foreground")
            return
        }
        val engine = WakeWordEngine(
            context = applicationContext,
            keywords = WAKE_WORD,
            onKeyword = ::onWakeWord,
        )
        if (engine.start()) {
            wakeWordEngine = engine
            warmUpVoiceRecognizer()
        } else {
            Logger.w(LOG_TAG, "Wake word engine failed to start, check RECORD_AUDIO permission")
        }
    }

    /**
     * 后台预加载 ASR 模型。SenseVoice int8 首次加载要 2-3s，
     * 若等到唤醒后才开始加载，用户开头那句话会直接丢掉。
     */
    private fun warmUpVoiceRecognizer() {
        scope.launch(Dispatchers.IO) {
            try {
                obtainVoiceRecognizer().warmUp()
                obtainSpeechSpeaker()
            } catch (e: Exception) {
                Logger.w(LOG_TAG, "voice warm up failed: ${e.message}")
            }
        }
    }

    /** 语音识别引擎单例；模型加载较贵，只在进程内建一次。 */
    @Synchronized
    private fun obtainVoiceRecognizer(): VoiceRecognizer =
        voiceRecognizer ?: VoiceRecognizer(applicationContext).also { voiceRecognizer = it }

    @Synchronized
    private fun stopWakeWordListening() {
        wakeWordEngine?.stop()
        wakeWordEngine = null
    }

    /**
     * 把当前回合的正文增量喂给朗读器：正文长出一句就播一句，
     * 而不是等整个回合结束再统一朗读（工具调用之间的文字也就不会攒到最后）。
     *
     * 朗读期间让出麦克风，全部念完再接回唤醒监听。
     */
    private fun feedReplyText(isFinal: Boolean) {
        val speaker = obtainSpeechSpeaker()
        if (!replyStreamStarted) {
            replyStreamStarted = true
            stopWakeWordListening()
            speaker.beginReply()
        }
        speaker.feedReply(currentReplyBody(), isFinal) {
            replyStreamStarted = false
            if (isResidentRequested) startWakeWordListening()
        }
    }

    /** 当前回合的正文：只取 Text 块，跳过思考与工具状态标记。 */
    private fun currentReplyBody(): String {
        val turn = agent.conversation.value.turns.lastOrNull() ?: return ""
        val text = turn.blocks
            .filterIsInstance<TurnBlock.Text>()
            .joinToString("\n") { it.text }
            .trim()
        if (text.isNotEmpty()) return text
        // 回合失败时正文是空的，这时至少把错误念出来
        return turn.blocks
            .filterIsInstance<TurnBlock.Failure>()
            .firstNotNullOfOrNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()
    }

    /** 朗读器单例：引擎初始化是异步的，尽早建好。 */
    @Synchronized
    private fun obtainSpeechSpeaker(): SpeechSpeaker =
        speechSpeaker ?: SpeechSpeaker(applicationContext).also {
            it.initialize()
            speechSpeaker = it
        }

    /** 提示音播放器单例。 */
    @Synchronized
    private fun obtainWakeTonePlayer(): WakeTonePlayer =
        wakeTonePlayer ?: WakeTonePlayer(applicationContext).also { wakeTonePlayer = it }

    /** 唤醒命中入口：让出麦克风 → 录一句 → ASR → 触发一轮 Agent。 */
    private fun onWakeWord(keyword: String) {
        Logger.i(LOG_TAG, "Wake word fired: $keyword")
        // 回调跑在唤醒引擎的采集线程上，切到 scope 再停引擎，避免自己 join 自己
        scope.launch { captureVoiceTurn() }
    }

    /**
     * 唤醒后录一句话并识别，用识别文本触发一轮 Agent。
     *
     * 唤醒监听与识别不能同时占用麦克风，所以先停 KWS，识别结束（无论成败）再恢复。
     */
    @Synchronized
    private fun captureVoiceTurn() {
        if (voiceTurnJob?.isActive == true) {
            Logger.w(LOG_TAG, "voice turn already running, ignore wake")
            return
        }
        stopWakeWordListening()

        voiceTurnJob = scope.launch {
            try {
                // 先给反馈再开录：用户听到提示音才知道该说话了
                obtainWakeTonePlayer().play()
                delay(TONE_SETTLE_MS)
                val recognizer = obtainVoiceRecognizer()
                val text = withContext(Dispatchers.IO) { recognizer.listenOnce() }
                if (text.isNullOrBlank()) {
                    Logger.w(LOG_TAG, "voice turn: nothing recognized")
                } else {
                    Logger.i(LOG_TAG, "voice turn recognized textLength=${text.length}")
                    triggerTurnFromWake(text)
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG, "voice turn failed: ${e.message}")
            } finally {
                if (isResidentRequested) startWakeWordListening()
            }
        }
    }

    /**
     * 用 [query] 触发一轮 Agent。走的是与 IPC `submit` 相同的内核
     * （updateDraft + stream + executeTurn），frame 收在本地 callback 里
     * （P8 会在这里接 TTS 朗读）。
     */
    private fun triggerTurnFromWake(query: String) {
        val callback = object : IRenderFrameCallback.Stub() {
            override fun onFrame(frame: RenderFrame?) {
                val f = frame ?: return
                if (f.isFinal) {
                    Logger.i(LOG_TAG, "voice turn final textLength=${f.text.length}")
                }
                feedReplyText(f.isFinal)
            }
        }

        agent.updateDraft { it.copy(text = query, images = emptyList(), files = emptyList()) }
        when (agent.stream()) {
            TurnStart.Started -> {
                val job = scope.launch { executeTurn(callback) }
                activeTurn.set(ActiveTurn(callback, job))
                Logger.i(LOG_TAG, "voice turn started queryLength=${query.length}")
            }

            TurnStart.Busy -> Logger.w(LOG_TAG, "voice turn ignored: another turn in progress")
            TurnStart.DraftEmpty -> Logger.w(LOG_TAG, "voice turn ignored: draft empty")
        }
    }

    private val agent: Agent get() = requireService()
    private val agentControl: AgentControl get() = requireService()
    private val notificationChannelManager: NotificationChannelManager get() = requireService()
    private val permissionManager: PermissionManager get() = requireService()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeTurn = AtomicReference<ActiveTurn?>(null)
    private var statusJob: Job? = null
    private var boundClientsCount = 0
    private var isResidentRequested = false

    private data class ActiveTurn(
        val callback: IRenderFrameCallback,
        val job: Job,
    )

    companion object {
        private const val LOG_TAG = "niki914_zafiro_AgentRuntimeService"

        /**
         * KWS 关键词，格式为「模型词表里的 token 序列 + `@原文`」，多个词用 `/` 分隔。
         * 英文模型（gigaspeech）是 BPE 词表，`▁` 表示词首边界；
         * 当前值对应 "hey jimmy"，由模型自带 bpe.model 编码得到。
         */
        private const val WAKE_WORD = "▁HE Y ▁ J IM M Y"

        /**
         * 提示音播完到开始录音之间的等待。提示音若被识别引擎拾取，
         * 会变成一个莫名其妙的 query，所以留一点静默间隔。
         */
        private const val TONE_SETTLE_MS = 400L

        const val ACTION_START_RESIDENT = "com.niki914.zafiro.action.START_RESIDENT"
        const val ACTION_STOP_RESIDENT = "com.niki914.zafiro.action.STOP_RESIDENT"

        /** 对话页语音按钮：等效于喊一次唤醒词，直接走「提示音 → 录音 → ASR → 一轮 Agent」。 */
        const val ACTION_VOICE_INPUT = "com.niki914.zafiro.action.VOICE_INPUT"

        /** 朗读后端设置变化：丢弃缓存的朗读器，下次朗读按新后端重建。 */
        const val ACTION_SPEECH_BACKEND_CHANGED =
            "com.niki914.zafiro.action.SPEECH_BACKEND_CHANGED"
        private const val MAX_QUERY_LENGTH = 8192
        private const val STORE_CHANNEL_ID = "nexus_xservice_default_channel"
        private const val STORE_CHANNEL_NAME = "Zafiro"

        private var instance: AgentRuntimeService? = null

        fun notifyUpdate() {
            instance?.updateResidentNotification()
        }

        /** 服务是否在跑。设置变更通知前先查，避免把没在跑的服务拉起来。 */
        fun isRunning(): Boolean = instance != null
    }

    fun updateResidentNotification() {
        val status = agentControl.status.value
        val notification = ResidentNotificationBuilder.build(
            context = this,
            channelManager = notificationChannelManager,
            status = status,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(ResidentNotificationBuilder.NOTIFICATION_ID, notification)
    }

    private fun observeStatus() {
        statusJob = scope.launch {
            agentControl.status.collect {
                updateResidentNotification()
            }
        }
    }

    private inner class StubImpl : IAgentRuntimeService.Stub() {
        private val storeStub = StoreStubImpl()

        override fun getStoreBinder(): IBinder? {
            if (!validateCaller()) return null
            return storeStub
        }

        override fun submit(query: String?, callback: IRenderFrameCallback?) {
            val q = query ?: return
            val cb = callback ?: return

            if (q.isBlank() || q.length > MAX_QUERY_LENGTH) {
                Logger.w(
                    LOG_TAG,
                    "submit rejected queryLength=${q.length} maxLength=$MAX_QUERY_LENGTH " +
                            "reason=${if (q.isBlank()) "blank" else "tooLong"}"
                )
                sendError(
                    cb, "Query is blank or exceeds maximum length of $MAX_QUERY_LENGTH characters",
                )
                return
            }
            Logger.i(LOG_TAG, "submit accepted queryLength=${q.length}")

            try {
                cb.asBinder().linkToDeath(deathRecipient, 0)
            } catch (e: Exception) {
                Logger.e(LOG_TAG, "submit failed linkToDeath error=${e.message}")
                return
            }

            agent.updateDraft { it.copy(text = q, images = emptyList(), files = emptyList()) }
            when (val startResult = agent.stream()) {
                TurnStart.Busy -> {
                    try {
                        cb.asBinder().unlinkToDeath(deathRecipient, 0)
                    } catch (_: Exception) {
                    }
                    Logger.w(LOG_TAG, "submit rejected activeTurnBusy=true")
                    sendError(cb, "Another turn is already in progress")
                    return
                }
                TurnStart.DraftEmpty -> {
                    try {
                        cb.asBinder().unlinkToDeath(deathRecipient, 0)
                    } catch (_: Exception) {
                    }
                    sendError(cb, "Query is blank")
                    return
                }
                TurnStart.Started -> {
                    Logger.i(LOG_TAG, "agent.stream started successfully")
                }
            }

            val job = scope.launch { executeTurn(cb) }
            val turn = ActiveTurn(cb, job)
            activeTurn.set(turn)
            Logger.i(LOG_TAG, "turn registered callbackLinked=true")
        }

        override fun cancel() {
            val turn = activeTurn.getAndSet(null)
            if (turn == null) {
                Logger.i(LOG_TAG, "cancel ignored noActiveTurn=true")
                return
            }
            turn.job.cancel()
            Logger.i(LOG_TAG, "cancel requested")
            scope.launch {
                try {
                    agent.stop()
                    Logger.i(LOG_TAG, "cancel done agent.stop completed")
                } catch (_: Exception) {
                }
            }
        }

        override fun resetConversation() {
            Logger.i(LOG_TAG, "reset conversation requested by host (ignored to protect shared conversation)")
        }
    }

    private inner class StoreStubImpl : IAgentStoreService.Stub() {
        override fun readStore(storeId: String?): String? {
            if (!validateCaller()) return null
            val id = storeId ?: return null
            val startedAtMs = System.currentTimeMillis()
            val json = runBlocking {
                XIpcStoreRepository.readJson(this@AgentRuntimeService, id)
            }
            Logger.d(
                LOG_TAG,
                "StoreStub.readStore storeId=$id result=${json != null} " +
                        "jsonLength=${json?.length ?: 0} elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            return json
        }

        override fun writeStore(storeId: String?, json: String?) {
            if (!validateCaller()) return
            val id = storeId ?: return
            val j = json ?: return
            val startedAtMs = System.currentTimeMillis()
            runBlocking {
                XIpcStoreRepository.writeJson(this@AgentRuntimeService, id, j)
            }
            Logger.i(
                LOG_TAG,
                "StoreStub.writeStore storeId=$id jsonLength=${j.length} " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }

        override fun mutateStore(storeId: String?, path: String?, valueJson: String?): String? {
            if (!validateCaller()) return null
            val id = storeId ?: return null
            val p = path ?: return null
            val v = valueJson ?: return null
            val startedAtMs = System.currentTimeMillis()
            val updatedJson = runBlocking {
                XIpcStoreRepository.mutateJson(this@AgentRuntimeService, id, p, v)
            }
            Logger.i(
                LOG_TAG,
                "StoreStub.mutateStore storeId=$id path=$p result=${updatedJson != null} " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            return updatedJson
        }

        override fun postNotification(title: String?, content: String?, uri: String?) {
            if (!validateCaller()) return
            val t = title ?: return
            val c = content ?: return
            postNotificationImpl(t, c, createContentIntent(uri))
        }



        private fun postNotificationImpl(
            title: String,
            content: String,
            contentIntent: PendingIntent?
        ) {
            // 只读查询只经过 PermissionManager 服务；业务方禁止直连原生权限 API（单测扫描兜底）
            if (permissionManager.status(Permission.NOTIFICATION) != PermissionState.GRANTED) return
            ensureNotificationChannel()

            val builder = NotificationCompat.Builder(this@AgentRuntimeService, STORE_CHANNEL_ID)
                .setSmallIcon(resolveSmallIcon())
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)

            contentIntent?.let { builder.setContentIntent(it) }
            NotificationManagerCompat.from(this@AgentRuntimeService).notify(
                notificationId(title, content),
                builder.build()
            )
        }

        private fun ensureNotificationChannel() {
            val manager =
                this@AgentRuntimeService.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                STORE_CHANNEL_ID,
                STORE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        // MainActivity is launchMode=singleTask; NEW_TASK reuses the existing task
        // and routes through onNewIntent, so no duplicate activity is created
        private fun createAppLaunchPendingIntent(): PendingIntent? {
            val intent = Intent(this@AgentRuntimeService, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                this@AgentRuntimeService,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createContentIntent(uri: String?): PendingIntent? {
            if (uri.isNullOrBlank()) {
                return null
            }
            val intent = Intent(Intent.ACTION_VIEW, uri.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val resolved =
                this@AgentRuntimeService.packageManager.resolveActivity(intent, 0) ?: return null
            val pendingIntentFlags =
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getActivity(
                this@AgentRuntimeService,
                resolved.activityInfo.packageName.hashCode(),
                intent,
                pendingIntentFlags
            )
        }

        private fun resolveSmallIcon(): Int {
            return this@AgentRuntimeService.applicationInfo.icon.takeIf { it != 0 }
                ?: android.R.drawable.ic_dialog_info
        }

        private fun notificationId(title: String, content: String): Int {
            var result = title.hashCode()
            result = 31 * result + content.hashCode()
            return result
        }
    }

    private suspend fun executeTurn(callback: IRenderFrameCallback) {
        val startedAtMs = System.currentTimeMillis()
        Logger.i(LOG_TAG, "turn started")
        var firstFrameSent = false
        var lastRenderedText: String? = null
        var frameIndex = 0

        val labels = ToolStatusLabels(
            called = getString(AppR.string.ui_tool_status_called),
            running = getString(AppR.string.ui_tool_status_running),
            success = getString(AppR.string.ui_tool_status_success),
            failed = getString(AppR.string.ui_tool_status_failed),
        )

        fun resolveErrorMessage(code: TurnFailureCode?): String = when (code) {
            TurnFailureCode.ConfigRequired -> getString(AppR.string.ui_home_error_config_required_title)
            TurnFailureCode.IdleTimeout -> getString(AppR.string.ui_home_error_idle_timeout_title)
            TurnFailureCode.OutputTruncated ->
                getString(AppR.string.ui_home_error_output_truncated_title)
            else -> getString(AppR.string.runtime_error_internal)
        }

        val targetTurnId = agent.conversation.value.turns.lastOrNull()?.id
        Logger.i(LOG_TAG, "executeTurn targetTurnId=$targetTurnId")

        try {
            coroutineScope {
                val conversationJob = launch {
                    agent.conversation.collect { conv ->
                        val turn = conv.turns.find { it.id == targetTurnId } ?: conv.turns.lastOrNull()
                        if (turn != null) {
                            val text = HostConversationProjector.render(turn, labels, ::resolveErrorMessage)
                            if (text != lastRenderedText || !firstFrameSent) {
                                frameIndex++
                                lastRenderedText = text
                                val isFirst = !firstFrameSent
                                firstFrameSent = true
                                if (isFirst) {
                                    Logger.i(
                                        LOG_TAG,
                                        "first render frame elapsedMs=${System.currentTimeMillis() - startedAtMs} textLength=${text.length}"
                                    )
                                }
                                sendFrame(
                                    callback,
                                    RenderFrame(
                                        text = text,
                                        isFirst = isFirst,
                                        isFinal = false,
                                    ),
                                )
                            }
                        }
                    }
                }

                agent.status.first { it is AgentState.Idle }
                conversationJob.cancel()
            }

            val finalTurn = agent.conversation.value.turns.find { it.id == targetTurnId }
                ?: agent.conversation.value.turns.lastOrNull()
            val finalText = if (finalTurn != null) {
                HostConversationProjector.render(finalTurn, labels, ::resolveErrorMessage)
            } else {
                lastRenderedText.orEmpty()
            }
            Logger.i(
                LOG_TAG,
                "final render frame elapsedMs=${System.currentTimeMillis() - startedAtMs} textLength=${finalText.length}"
            )
            sendFrame(
                callback,
                RenderFrame(
                    text = finalText,
                    isFirst = !firstFrameSent,
                    isFinal = true,
                ),
            )
            Logger.i(
                LOG_TAG,
                "turn completed elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        } catch (e: CancellationException) {
            Logger.i(
                LOG_TAG,
                "turn cancelled elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            throw e
        } catch (e: Exception) {
            Logger.e(
                LOG_TAG,
                "turn failed elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                        "errorType=${e::class.simpleName} message=${e.message}"
            )
            sendFrame(
                callback,
                RenderFrame(
                    text = e.message ?: getString(AppR.string.runtime_error_internal),
                    isFirst = !firstFrameSent,
                    isFinal = true,
                ),
            )
        } finally {
            try {
                callback.asBinder().unlinkToDeath(deathRecipient, 0)
            } catch (_: Exception) {
            }
            if (activeTurn.get()?.callback === callback) {
                activeTurn.set(null)
            }
        }
    }

    private fun sendFrame(callback: IRenderFrameCallback, frame: RenderFrame) {
        try {
            callback.onFrame(frame)
        } catch (e: DeadObjectException) {
            handleBinderDeath()
        }
    }

    private fun sendError(callback: IRenderFrameCallback, message: String) {
        try {
            callback.onFrame(RenderFrame(text = message, isFirst = true, isFinal = true))
        } catch (_: DeadObjectException) {
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        handleBinderDeath()
    }

    private fun handleBinderDeath() {
        val turn = activeTurn.getAndSet(null) ?: return
        turn.job.cancel()
        // TODO: 宿主（如 Breeno）进程被系统强杀时，AMS 解绑导致后台 Service 失去绑定上下文，
        // 进而引发网络套接字受限或级联 Stream interrupted。此问题涉及跨进程服务生命周期与系统级保活架构，后续专门迭代处理。
        Logger.i(LOG_TAG, "binder died, cancelled host frame collector without stopping agent")
    }

    private fun validateCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(callingUid) ?: return false
        val allowedPackages = setOf(packageName) + HostApp.packageNames.toSet()
        return packages.any { it in allowedPackages }
    }
}
