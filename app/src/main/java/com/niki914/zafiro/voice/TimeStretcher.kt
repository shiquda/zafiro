package com.niki914.zafiro.voice

import kotlin.math.PI
import kotlin.math.cos

/**
 * 保音高变速（WSOLA：波形相似叠加）。
 *
 * 为什么需要它：端侧 TTS 的 `lengthScale` 是让**模型把音素压短**，实测压到 1/1.4 后模型
 * 开始吞音连读（`苹果居中` → "苹果粥"、`清爽水润，甜而多汁` → "清爽润甜汁"），听感就是
 * 「段间轻微缺字」。而 `AudioTrack` 自带的变速在 AOSP 里是纯重采样（`AudioTrack.cpp`：
 * `// pitch is emulated by adjusting speed and sampleRate`），提速会变调。
 *
 * 所以让模型按自然速合成，再在这里变速：以固定合成跳距重叠相加，每个新帧在名义位置附近
 * 搜索与上一帧尾巴最相似的对齐点，从而在**不改变音高**的前提下改变时长。
 *
 * 实测（ffmpeg `atempo` 参考实现）：自然速合成后变速到 1.2x，SenseVoice 回听与自然速一致。
 */
internal object TimeStretcher {

    /** 帧长（样本）：16 kHz 下 64ms，足够覆盖一个音素的周期结构。 */
    private const val FRAME = 1024

    /** 合成跳距：16ms。决定重叠量（FRAME - SYNTH_HOP = 768 样本重叠）。 */
    private const val SYNTH_HOP = 256

    /** 对齐搜索半径（样本）：±4ms，再大收益很小但开销线性增长。 */
    private const val SEARCH = 64

    /** 搜索步长：每 2 个样本试一次，省一半计算量。 */
    private const val SEARCH_STEP = 2

    /** 认为「不需要变速」的阈值。 */
    private const val IDENTITY_EPSILON = 0.001f

    private val window = FloatArray(FRAME) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (FRAME - 1))).toFloat()
    }

    /**
     * 把 [samples] 变速到 [speed] 倍（> 1 变快，音高不变）。
     *
     * @return 变速后的样本；[speed] ≈ 1 时原样返回入参（不复制）。
     */
    fun stretch(samples: FloatArray, speed: Float): FloatArray {
        if (samples.isEmpty() || speed <= IDENTITY_EPSILON) return samples
        if (kotlin.math.abs(speed - 1f) < IDENTITY_EPSILON) return samples

        val overlap = FRAME - SYNTH_HOP
        val analysisHop = SYNTH_HOP / speed
        // 末尾补一帧零，保证循环总能写满，再按目标长度截断（否则会丢掉尾巴）
        val padded = samples.copyOf(samples.size + FRAME)
        val target = (samples.size / speed).toInt()
        val out = FloatArray(target)
        val norm = FloatArray(target)
        var prevTail: FloatArray? = null
        var outPos = 0
        var nominal = 0.0

        while (outPos + FRAME <= out.size) {
            val base = nominal.toInt()
            if (base + FRAME > padded.size) break
            val at = if (prevTail == null) base else bestMatch(padded, base, prevTail!!)
            for (i in 0 until FRAME) {
                out[outPos + i] += padded[at + i] * window[i]
                norm[outPos + i] += window[i]
            }
            // 下一帧要与之对齐的部分：本帧输出里与下一帧重叠的那段
            prevTail = padded.copyOfRange(at + SYNTH_HOP, at + FRAME)
            outPos += SYNTH_HOP
            // 名义位置按分析跳距推进，不受搜索偏移影响（否则会漂移/重复）
            nominal += analysisHop
        }

        for (i in out.indices) {
            if (norm[i] > 1e-6f) out[i] /= norm[i]
        }
        if (outPos < out.size) {
            // 收尾不足一帧：把剩余输入按同样比例补上，避免末尾被切
            val srcStart = (outPos * speed).toInt().coerceIn(0, samples.size)
            var o = outPos
            var s = srcStart
            while (o < out.size && s < samples.size) {
                out[o] = samples[s]
                o++
                s++
            }
        }
        return out
    }

    /** 在 [base] ± [SEARCH] 内找与 [tail] 最相似的起点（最大化内积）。 */
    private fun bestMatch(padded: FloatArray, base: Int, tail: FloatArray): Int {
        val lo = (base - SEARCH).coerceAtLeast(0)
        val hi = (base + SEARCH).coerceAtMost(padded.size - FRAME)
        if (hi <= lo) return base.coerceIn(0, (padded.size - FRAME).coerceAtLeast(0))
        var best = base
        var bestScore = Float.NEGATIVE_INFINITY
        var candidate = lo
        while (candidate <= hi) {
            var score = 0f
            var i = 0
            while (i < tail.size) {
                score += padded[candidate + i] * tail[i]
                i++
            }
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
            candidate += SEARCH_STEP
        }
        return best
    }
}
