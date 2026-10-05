package com.niki914.zafiro.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.niki914.logging.Logger
import com.niki914.zafiro.app.R

/**
 * 唤醒命中后的提示音。
 *
 * 没有反馈时用户无法判断「刚才有没有被听到」，只能反复喊 —— 这是唤醒体验里最容易
 * 被忽略、但对可用性影响最大的一环。
 *
 * 音源是自带的柔和「叮」（`res/raw/wake_tone.wav`：A5 正弦 + 少量谐音 + 指数衰减），
 * 而不是 [android.media.ToneGenerator] —— 后者只能发 DTMF/呼叫类音调，听起来像警报。
 * 走媒体流，与回复朗读共用同一条音量通道。
 */
class WakeTonePlayer(context: Context) {

    companion object {
        private const val TAG = "ZafiroWakeTone"
        private const val VOLUME = 0.9f
    }

    private var soundPool: SoundPool? = null
    private var toneId = 0

    @Volatile
    private var loaded = false

    init {
        runCatching {
            val pool = SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
            pool.setOnLoadCompleteListener { _, _, status ->
                loaded = status == 0
                if (!loaded) Logger.w(TAG, "wake tone load failed, status=$status")
            }
            toneId = pool.load(context.applicationContext, R.raw.wake_tone, 1)
            soundPool = pool
        }.onFailure { Logger.w(TAG, "SoundPool init failed: ${it.message}") }
    }

    /** 播一次提示音。未加载完或播放失败时静默，不阻断语音链路。 */
    @Synchronized
    fun play() {
        val pool = soundPool
        if (pool == null || !loaded) {
            Logger.w(TAG, "wake tone not ready (loaded=$loaded)")
            return
        }
        runCatching { pool.play(toneId, VOLUME, VOLUME, 1, 0, 1f) }
            .onFailure { Logger.w(TAG, "wake tone play failed: ${it.message}") }
    }

    @Synchronized
    fun release() {
        runCatching { soundPool?.release() }
        soundPool = null
        loaded = false
    }
}
