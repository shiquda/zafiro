package com.niki914.zafiro.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 朗读文本归一化的回归用例。
 *
 * 样本来自设备上真实产生过的回复（`conversation_history.db`），以及用 matcha-icefall-zh-en
 * 合成 + SenseVoice 回听实测念错的符号组合——每条期望值背后都有一次「念错了」的实测：
 * `**` → "esttu cast"、`✅` → "with heavy check mark"、`10:05` → "tenanzero five"、
 * `×` → "time"、`=` → "close it"、`—` → "减"、`$` → "aler"、`/` → "slash"、
 * 表格竖线让 `麦克风` 变 `麦克更`、代码块被逐字念。
 */
class SpeechTextNormalizerTest {

    /** 走完整链路：围栏过滤 → 标记清洗 → 读法归一化。 */
    private fun speech(raw: String): String =
        SpeechTextNormalizer.normalizeForSpeech(
            SpeechTextNormalizer.cleanMarkup(SpeechTextNormalizer.stripFencedBlocks(raw)),
        )

    @Test
    fun `加粗时间要念成中文时间`() {
        assertEquals(
            "10点05分（2026年10月6日 周二，CST）。",
            speech("**10:05**（2026年10月6日 周二，CST）。"),
        )
    }

    @Test
    fun `代码块整块不念`() {
        val raw = "To fix the tool, run:\n```\nam force-stop com.niki914.zafiro\n```\nthen relaunch."
        assertEquals("To fix the tool, run:\nthen relaunch.", speech(raw))
    }

    @Test
    fun `代码块跨增量也要认出来`() {
        // 真实喂入是按增量来的：围栏可能被切在中间，必须按完整正文重算
        val partial = "说明：\n```\ncom.niki914"
        assertEquals("说明：\n\n", SpeechTextNormalizer.stripFencedBlocks(partial))
        val full = "说明：\n```\ncom.niki914.zafiro\n```\n完毕。"
        assertEquals("说明：\n完毕。", speech(full))
    }

    @Test
    fun `表格竖线换成逗号且丢掉分隔行`() {
        val raw = "| 项目 | 状态 |\n|---|---|\n| 麦克风 | 正常 |"
        assertEquals("项目，状态\n麦克风，正常", speech(raw))
    }

    @Test
    fun `emoji 不能念出英文名`() {
        assertEquals("已完成 稍后提醒你", speech("已完成 ✅ 稍后提醒你 🔔"))
    }

    @Test
    fun `列表符号与斜杠`() {
        val raw = "- Turn on/check something on the phone?\n- Look something up?"
        assertEquals("Turn on check something on the phone?\nLook something up?", speech(raw))
    }

    @Test
    fun `数学符号按中文念`() {
        assertEquals("12345 乘 678 等于 8,369,910", speech("12345 × 678 = **8,369,910**"))
    }

    @Test
    fun `破折号与货币符号`() {
        assertEquals(
            "The room is warm，dinner is ready. 价格是 0.15 M tokens，耗时 0.0s。",
            speech("The room is warm — dinner is ready. 价格是 $0.15/M tokens，耗时 0.0s。"),
        )
    }

    @Test
    fun `标识符里的运算符不要动`() {
        assertEquals("用 C++ 和 key=value 举例", speech("用 C++ 和 key=value 举例"))
    }

    @Test
    fun `裸链接与 html 标签不念`() {
        assertEquals(
            "见 与 标题",
            speech("见 https://example.com/a/b?c=1 与 <b>标题</b>"),
        )
    }

    @Test
    fun `跨增量被切开的列表符号由整句兜底`() {
        // 真实喂入是按增量来的：`-` 与它后面的空格可能分属两次增量，
        // 只按增量清洗会漏掉这个列表符号，所以整句上还要再过一遍结构清洗
        val delta1 = SpeechTextNormalizer.cleanMarkup("苹果\n-")
        val delta2 = SpeechTextNormalizer.cleanMarkup(" **香蕉**")
        assertEquals("苹果\n香蕉", speech(delta1 + delta2))
    }

    @Test
    fun `真实表格回复`() {
        // 设备上真实产生过的回复（10:23:34），日志里清洗后入队 50 + 12 字
        val raw = "| 水果 | 特点 |\n|------|------|\n| 苹果 | 口感脆甜，富含膳食纤维和维生素 C，耐储存，四季常见 |\n| 香蕉 | 软糯香甜，富含钾和镁，热量较高，成熟后不易久放 |"
        val cleaned = speech(raw)
        // 设备上同一条回复的入队长度是 50 + 12 = 62，与这里的规则结果逐字吻合
        assertEquals(62, cleaned.length)
        assertFalse("表格竖线未清干净", cleaned.contains("|"))
        assertFalse("分隔行未清掉", cleaned.contains("---"))
        assertEquals("水果，特点", cleaned.lineSequence().first())
    }

    @Test
    fun `重复句读收成一个`() {
        // 真实回复里的 `适合不同场景——减脂期`：两个破折号都变逗号，不能留 `，，`
        assertEquals(
            "各有特点，适合不同场景，减脂期可选西瓜。",
            speech("各有特点，适合不同场景——减脂期可选西瓜。"),
        )
    }

    @Test
    fun `残留标记清零`() {
        val cleaned = speech(
            "## 结论\n**麦克风**正常，`唤醒词`已生效。\n- 完毕",
        )
        assertEquals("结论\n麦克风正常，唤醒词已生效。\n完毕", cleaned)
        for (mark in listOf("**", "##", "`", "|", "✅", "×")) {
            assertFalse("残留标记: $mark", cleaned.contains(mark))
        }
    }
}
