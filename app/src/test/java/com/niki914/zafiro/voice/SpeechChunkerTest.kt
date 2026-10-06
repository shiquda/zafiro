package com.niki914.zafiro.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式切分的接缝测试：正文是一小块一小块喂进来的（模型 token 流就是这样），
 * 增量边界可能正好落在 `- `、`10:05`、`| 项目 |`、`**加粗**` 的中间。
 *
 * 核心不变量：**只要正文本身在增长，切出来的句子按顺序拼起来必须等于
 * 一次性喂完整正文的结果** —— 不许丢字，也不许因为边界而多出垃圾。
 */
class SpeechChunkerTest {

    /** 用给定的切片大小模拟 token 流，返回切出来的句子。 */
    private fun stream(body: String, sliceSize: Int): List<String> {
        val chunker = SpeechChunker()
        chunker.reset()
        val out = mutableListOf<String>()
        var i = 0
        while (i < body.length) {
            i = minOf(i + sliceSize, body.length)
            out += chunker.feed(body.substring(0, i), isFinal = false).sentences
        }
        out += chunker.feed(body, isFinal = true).sentences
        return out
    }

    /** 一次性喂完整正文，作为对照基准。 */
    private fun oneShot(body: String): List<String> {
        val chunker = SpeechChunker()
        chunker.reset()
        return chunker.feed(body, isFinal = true).sentences
    }

    /**
     * 段与段之间的空白由分句边界承担（每句都是一次独立合成，边界天然是停顿），
     * 所以比对时忽略空白；**非空白字符必须逐字一致** —— 这才叫「不丢字」。
     */
    private fun content(text: String) = text.filterNot { it.isWhitespace() }

    private fun assertNoSeamLoss(body: String, name: String) {
        val expected = content(oneShot(body).joinToString(""))
        for (size in listOf(1, 2, 3, 5, 7, 13, 17, 40)) {
            val actual = content(stream(body, size).joinToString(""))
            assertEquals("$name：按 $size 字切片时接缝丢字", expected, actual)
        }
    }

    // 设备上真实产生过的回复

    private val tableReply = """
        | 水果 | 特点 |
        |------|------|
        | 苹果 | 口感脆甜，富含膳食纤维和维生素 C，耐储存，四季常见 |
        | 香蕉 | 软糯香甜，富含钾和镁，热量较高，成熟后不易久放 |
    """.trimIndent()

    private val boldListReply = """
        - **苹果**
        - **香蕉**
        - **橙子**
    """.trimIndent()

    private val mixedReply = """
        现在时间是 **10:05**，模型是 `deepseek-v4.1-flash`。

        ## 结论

        1. 价格是 ${'$'}0.15 / M tokens，耗时 0.0s。
        2. 参考 [文档](https://example.com/docs) 里的 `fc` 命令。

        ```bash
        echo hello
        ```
    """.trimIndent()

    @Test
    fun `表格回复接缝不丢字`() = assertNoSeamLoss(tableReply, "表格")

    @Test
    fun `加粗列表回复接缝不丢字`() = assertNoSeamLoss(boldListReply, "加粗列表")

    @Test
    fun `混排回复接缝不丢字`() = assertNoSeamLoss(mixedReply, "混排")

    @Test
    fun `表格竖线在接缝处也要变成逗号`() {
        // 逐字到达时单个 `|` 曾被当成垃圾删掉 → `水果 特点` 两词粘连、念起来吞字
        val chunks = stream(tableReply, 1).joinToString("")
        assertTrue("竖线残留：$chunks", !chunks.contains("|"))
        assertTrue("分隔行残留：$chunks", !chunks.contains("---"))
        assertTrue("单元格分隔丢了：$chunks", chunks.startsWith("水果，特点"))
    }

    @Test
    fun `数字与时间不被边界劈开后读错`() {
        // 时间必须整句归一化：`10:05` 不能被切成 `10` + `:05`
        val chunks = stream("现在是 10:05。", 3)
        assertEquals(listOf("现在是 10点05分。"), chunks)
    }

    @Test
    fun `列表符号被边界劈开也要删掉`() {
        // `-` 与它后面的空格分属两次增量；整句清洗必须把它删掉（不留残留的 `-`）
        val chunks = stream("- 苹果\n- 香蕉\n", 1)
        assertEquals("苹果\n香蕉", chunks.joinToString(""))
    }
}
