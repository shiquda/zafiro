package com.niki914.zafiro.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
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
