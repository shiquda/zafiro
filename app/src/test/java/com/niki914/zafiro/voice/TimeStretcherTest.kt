package com.niki914.zafiro.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 保音高变速的客观验收：**时长按倍率变，音高不变**。
 *
 * 音高用单频正弦的 DFT 能量比来测 —— 这是区分「保音高变速」与「重采样」的关键：
 * 重采样把 440Hz 提到 528Hz（= 440×1.2），WSOLA 必须让 440Hz 仍是主峰。
 */
class TimeStretcherTest {

    private val rate = 16000

    private fun sine(freq: Double, seconds: Double): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n) { i -> sin(2.0 * PI * freq * i / rate).toFloat() * 0.5f }
    }

    /** 某个频率在某段区间上的能量（等价于带通滤波后的能量）。 */
    private fun bandEnergy(x: FloatArray, from: Int, count: Int, freq: Double): Double {
        var re = 0.0
        var im = 0.0
        val n = minOf(count, x.size - from)
        for (i in 0 until n) {
            val a = 2.0 * PI * freq * i / rate
            re += x[from + i] * cos(a)
            im += x[from + i] * sin(a)
        }
        return (re * re + im * im) / (n * n)
    }

    /** 一段时间内的样本能量总和。 */
    private fun energy(x: FloatArray, from: Int, count: Int): Double {
        var sum = 0.0
        val n = minOf(count, x.size - from).coerceAtLeast(0)
        for (i in 0 until n) sum += x[from + i].toDouble() * x[from + i]
        return sum
    }

    /** 某个频率上的 DFT 能量（Goertzel 式直接求和，样本量小，够用）。 */
    private fun energyAt(x: FloatArray, freq: Double, from: Int = 0, count: Int = 4096): Double {
        var re = 0.0
        var im = 0.0
        val n = minOf(count, x.size - from)
        for (i in 0 until n) {
            val phase = 2.0 * PI * freq * i / rate
            re += x[from + i] * kotlin.math.cos(phase)
            im += x[from + i] * sin(phase)
        }
        return re * re + im * im
    }

    @Test
    fun `倍率为 1 时原样返回`() {
        val input = sine(440.0, 0.5)
        assertTrue("不应复制", input === TimeStretcher.stretch(input, 1.0f))
    }

    @Test
    fun `时长按倍率缩短`() {
        val input = sine(440.0, 1.0)
        for (speed in listOf(1.2f, 1.4f)) {
            val out = TimeStretcher.stretch(input, speed)
            val expected = input.size / speed
            assertTrue(
                "speed=$speed 期望约 ${expected.toInt()} 样本，实际 ${out.size}",
                abs(out.size - expected) < input.size * 0.08f,
            )
        }
    }

    @Test
    fun `音高不变（不是重采样）`() {
        val input = sine(440.0, 1.0)
        val out = TimeStretcher.stretch(input, 1.2f)
        // 跳过首尾各一帧，避开窗函数边缘
        val from = 2048
        val base = energyAt(input, 440.0, from)
        val kept = energyAt(out, 440.0, from)
        val shifted = energyAt(out, 440.0 * 1.2, from)
        assertTrue("原音高能量应保留（kept=$kept, base=$base）", kept > base * 0.3)
        assertTrue("不应出现 1.2 倍音高（shifted=$shifted vs kept=$kept）", kept > shifted * 4)
    }

    @Test
    fun `输入内容必须按倍率完整映射到输出`() {
        // 音-静-音-静 四段，用窗口能量包络判定「输入第几段现在出现在输出的哪个位置」。
        // 纯音做 WSOLA 会有相位叠加误差，所以只看包络、不按频带判定。
        val seg = rate / 4
        val input = FloatArray(seg * 4) { i ->
            if ((i / seg) % 2 == 0) sin(2.0 * PI * 300.0 * i / rate).toFloat() else 0f
        }
        val win = (rate * 0.04).toInt()
        // 正确实现下输入比例与输出比例恒等（整段内容压进更短的输出里）；
        // 若输入被消费得比输出慢，映射会变成「输出 0.6 处其实来自输入 0.42 处」。
        fun winEnergyAtFraction(x: FloatArray, fraction: Double): Double {
            val from = (x.size * fraction).toInt().coerceIn(0, x.size - win)
            return energy(x, from, win) / win
        }
        for (speed in listOf(1.2f, 1.4f)) {
            val out = TimeStretcher.stretch(input, speed)
            val toneWin = winEnergyAtFraction(out, 0.60)
            val silenceWin = winEnergyAtFraction(out, 0.85)
            assertTrue(
                "speed=$speed 输入 0.6 处（第二段声音）的能量 $toneWin 应远大于 0.85 处（末段静音）的 $silenceWin",
                toneWin > silenceWin * 4 + 0.01,
            )
        }
    }

    @Test
    fun `尾部内容必须保留`() {
        // 前 0.8s 静音 + 后 0.2s 正弦：变速后末尾仍应有接近该正弦量级的声音
        val tone = sine(300.0, 0.2)
        val toneEnergy = energy(tone, 0, tone.size)
        val input = FloatArray(rate) { i -> if (i < rate * 8 / 10) 0f else tone[i - rate * 8 / 10] }
        for (speed in listOf(1.2f, 1.4f)) {
            val out = TimeStretcher.stretch(input, speed)
            val tailFrom = (out.size * 0.8).toInt()
            val e = energy(out, tailFrom, out.size - tailFrom)
            assertTrue(
                "speed=$speed 尾部能量 $e 应达到该正弦能量的 1/3（${toneEnergy / 3}）以上",
                e > toneEnergy / 3,
            )
        }
    }

    @Test
    fun `不丢尾巴也不出 NaN`() {
        // 用一段「有内容的尾巴」：前半静音、后半正弦
        val input = FloatArray(rate) { i -> if (i < rate / 2) 0f else sine(300.0, 0.5)[i - rate / 2] }
        val out = TimeStretcher.stretch(input, 1.2f)
        assertTrue("输出不该有 NaN", out.none { it.isNaN() })
        val tailEnergy = energyAt(out, 300.0, (out.size * 0.75).toInt(), 2048)
        assertTrue("尾巴内容应保留（energy=$tailEnergy）", tailEnergy > 1.0)
    }

    @Test
    fun `空输入与异常倍率不炸`() {
        assertEquals(0, TimeStretcher.stretch(FloatArray(0), 1.2f).size)
        val input = sine(440.0, 0.2)
        assertTrue(TimeStretcher.stretch(input, 0f) === input)
    }
}
