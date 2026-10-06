package com.niki914.zafiro.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.color.DynamicColors
import com.niki914.logging.Logger
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.app.conversation.ConversationPersister
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.app.notification.ResidentNotificationManager
import com.niki914.zafiro.app.overlay.FloatingBallOverlayManager
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.chat.agentic.python.PyRuntime
import com.niki914.zafiro.repo.UpdateCheckHolder
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.runtime.createAppRuntimeBridge
import com.niki914.zafiro.runtime.service.AgentRuntimeService
import com.niki914.zafiro.service.requireService
import com.niki914.zafiro.settings.RuntimeEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File

class App : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 日志 debug 门控：release 构建 DEBUG/VERBOSE 全停，仅 INFO+ 输出
        Logger.setDebugProvider { BuildConfig.DEBUG }
        // 非主进程（目前只有 `:python`）不初始化主进程状态：上下文与持久化只属于主进程
        //（否则 ContextProvider 从未 provide，PyRuntime.warmUp 会永远挂起）
        if (!isMainProcess()) return
        ContextProvider.provide(applicationContext)
        XRepo.init(this.applicationContext)
        ConversationRepo.init(this.applicationContext)
        // T3：消息级增量持久化器（观察 LLMController 当前会话快照流，
        // 独立于 UI 生命周期——回合可能在宿主后台跑，ViewModel 已销毁时仍落盘）
        ConversationPersister.start(applicationScope)
        RuntimeEnvironment.install(createAppRuntimeBridge())
        // 依赖装配只发生在 AppServices（主进程组合根）
        AppServices.install(this)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        DynamicColors.applyToActivitiesIfAvailable(this)
        applicationScope.launch {
            UpdateCheckHolder.runOnce(BuildConfig.VERSION_NAME)
        }
        applicationScope.launch {
            XRepo.tryPutDefaultSettings()
        }
        applicationScope.launch {
            XRepo.skills.seedDefaults()
        }
        applicationScope.launch {
            XRepo.seedPyTools()
        }
        applicationScope.launch {
            PyRuntime.warmUp()
        }

        observeFloatingBall()
        observeResidentNotification()
        observeSpeechBackend()
    }

    private fun observeResidentNotification() = applicationScope.launch {
        // flow 的初值是猜的默认值（false），设置读盘完成前收集会被当成「用户把常驻关了」——
        // 于是给服务发一个 STOP：服务被从后台拉起来又立刻自停，进程还会被判成 cached-empty
        // 冻住（开机后常驻就是这么没的）。等水合完成再开始观察。
        XRepo.awaitSettingsHydrated()
        launchFeatureFlagObserver(
            enabledFlow = XRepo.residentNotificationEnabledSetting,
            permission = Permission.NOTIFICATION,
            onPermissionMissing = { XRepo.setResidentNotificationEnabled(false) },
        ) { enabled ->
            if (enabled) ResidentNotificationManager.start(this@App) else ResidentNotificationManager.stop(this@App)
        }
    }

    private fun observeFloatingBall() = launchFeatureFlagObserver(
        enabledFlow = XRepo.floatingBallEnabledSetting,
        permission = Permission.OVERLAY,
        onPermissionMissing = { XRepo.setFloatingBallEnabled(false) },
    ) { enabled ->
        if (enabled) FloatingBallOverlayManager.show(this) else FloatingBallOverlayManager.dismiss()
    }

    /**
     * 朗读后端变化：服务里的 SpeechSpeaker 已经缓存了旧后端，通知它丢掉缓存，
     * 下一次朗读按新后端重建（无需重启进程）。服务没在跑时无需通知 —— 重建时自然读新值。
     */
    private fun observeSpeechBackend() {
        applicationScope.launch {
            XRepo.replyVoiceBackendSetting.drop(1).collect { backend ->
                if (!AgentRuntimeService.isRunning()) return@collect
                Logger.i(TAG, "reply voice backend changed -> $backend, notify runtime service")
                val intent = Intent(this@App, AgentRuntimeService::class.java).apply {
                    action = AgentRuntimeService.ACTION_SPEECH_BACKEND_CHANGED
                }
                runCatching { ContextCompat.startForegroundService(this@App, intent) }
                    .onFailure { Logger.w(TAG, "notify speech backend failed: ${it.message}") }
            }
        }
    }

    /**
     * 开关类功能的统一门禁：开关被打开但缺权限时回滚开关（不静默无效），
     * 其余情况交回 [onChange] 处理。
     */
    private fun launchFeatureFlagObserver(
        enabledFlow: Flow<Boolean>,
        permission: Permission,
        onPermissionMissing: suspend () -> Unit,
        onChange: (Boolean) -> Unit,
    ) {
        applicationScope.launch {
            enabledFlow.collect { enabled ->
                if (enabled && requireService<PermissionManager>().status(permission) != PermissionState.GRANTED) {
                    onPermissionMissing()
                } else {
                    onChange(enabled)
                }
            }
        }
    }

    private companion object {
        private const val TAG = "niki914_zafiro_App"
    }

    /**
     * 是否主进程。进程名优先读 `/proc/self/cmdline`（内核直接给出命令行，
     * 不依赖框架侧的内存状态），`getMyMemoryState` 仅作兜底；
     * 两者都取不到进程名时按主进程处理——宁可多初始化，不能让主进程缺初始化。
     */
    private fun isMainProcess(): Boolean {
        val fromProc = runCatching {
            File("/proc/self/cmdline").readBytes()
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .decodeToString()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        val name = fromProc ?: ActivityManager.RunningAppProcessInfo().also {
            ActivityManager.getMyMemoryState(it)
        }.processName?.takeIf { it.isNotEmpty() }
        return name == null || name == packageName
    }

}
