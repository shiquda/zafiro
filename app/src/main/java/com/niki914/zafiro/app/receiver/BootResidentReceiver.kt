package com.niki914.zafiro.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.niki914.logging.Logger
import com.niki914.zafiro.app.notification.ResidentNotificationManager
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机后把常驻 Agent 拉回来。
 *
 * 只靠 App 进程里的观察者不行：开机时它可能因为设置还没读盘而误判「开关是关的」，
 * 进程也随时会被判成 cached-empty 冻住。所以这里直接读持久化开关，为真就拉起常驻服务
 * ——服务自己也会再核对一遍开关（见 `AgentRuntimeService.restoreResidentFromSettings`）。
 */
class BootResidentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (XRepo.residentNotificationEnabled()) {
                    Logger.i(TAG, "boot completed, restoring resident service")
                    ResidentNotificationManager.start(appContext)
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "failed to restore resident service: ${t.message}", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        private const val TAG = "niki914_zafiro_BootResident"
    }
}
