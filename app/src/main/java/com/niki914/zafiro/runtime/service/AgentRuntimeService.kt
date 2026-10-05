package com.niki914.zafiro.runtime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import com.niki914.zafiro.app.R as AppR

import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.app.notification.ResidentNotificationBuilder
import com.niki914.zafiro.app.notification.ResidentNotificationManager
import com.niki914.zafiro.business.notification.NotificationChannelManager
import com.niki914.zafiro.voice.SpeechSpeaker
import com.niki914.zafiro.voice.VoiceRecognizer
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        val initialNotification = ResidentNotificationBuilder.build(
            context = this,
            channelManager = notificationChannelManager,
            status = agentControl.status.value,
        )
        startForeground(ResidentNotificationBuilder.NOTIFICATION_ID, initialNotification)
        observeStatus()
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
        if (wakeWordEngine != null) return
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
     * 朗读 Agent 的回复。朗读期间让出麦克风，免得把自己的声音当成唤醒词再触发一次；
     * 念完（或失败）再把唤醒监听接回来。
     */
    private fun speakReply(text: String) {
        if (text.isBlank()) {
            Logger.w(LOG_TAG, "voice turn is empty, nothing to speak")
            if (isResidentRequested) startWakeWordListening()
            return
        }
        stopWakeWordListening()
        obtainSpeechSpeaker().speak(text) {
            if (isResidentRequested) startWakeWordListening()
        }
    }

    /** 朗读器单例：引擎初始化是异步的，尽早建好。 */
    @Synchronized
    private fun obtainSpeechSpeaker(): SpeechSpeaker =
        speechSpeaker ?: SpeechSpeaker(applicationContext).also {
            it.initialize()
            speechSpeaker = it
        }

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
                    speakReply(f.text)
                }
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

        const val ACTION_START_RESIDENT = "com.niki914.zafiro.action.START_RESIDENT"
        const val ACTION_STOP_RESIDENT = "com.niki914.zafiro.action.STOP_RESIDENT"
        private const val MAX_QUERY_LENGTH = 8192
        private const val STORE_CHANNEL_ID = "nexus_xservice_default_channel"
        private const val STORE_CHANNEL_NAME = "Zafiro"

        private var instance: AgentRuntimeService? = null

        fun notifyUpdate() {
            instance?.updateResidentNotification()
        }
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
