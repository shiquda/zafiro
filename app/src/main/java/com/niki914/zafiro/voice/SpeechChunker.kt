package com.niki914.zafiro.voice

/**
 * 流式朗读的文本管线：把「累积正文快照」切成一句一句可直接合成的可念文本。
 *
 * 纯逻辑、不碰 Android —— 切分与清洗在**增量边界**上的缺陷（丢字、重复、残留标记）
 * 只有在这里才能被单测钉死，见 `SpeechChunkerTest`。
 *
 * 契约：
 * - [feed] 收的是**累积快照**（不是增量），返回**本次新切出的句子**；
 * - 只要正文本身在增长，返回的句子按顺序拼起来就是正文的可念投影，**中间不得丢字**；
 * - 围栏过滤按完整正文重算（一次增量可能正好切在围栏行中间）；
 * - 结构清洗与读法归一化只在**整句**上做一次（增量边界可能把 `- `、`10:05`、
 *   `| 项目 |` 这类结构劈成两半，按增量清洗必然出错）。
 */
internal class SpeechChunker {

    companion object {
        /** 句末标点：一到就切，保证「说一句、播一句」的低延迟。 */
        private const val HARD_BREAKS = "。！？!?；;"

        /** 软切点：只在已经攒够字数时才切。换行/逗号一到就切会把句子打得很碎，反而卡顿。 */
        private const val SOFT_BREAKS = "，,\n"

        /** 少于这个字数不切，避免「好。」这类碎句单独占一次合成。 */
        private const val MIN_CHUNK = 3

        /** 攒到这个字数后，允许在软切点断句。 */
        private const val SOFT_CHUNK = 60

        /** 无论如何都不断超过这个字数。 */
        private const val MAX_CHUNK = 120
    }

    /** 已经喂进来的**可念**正文（围栏过滤后），用来算增量。 */
    private var lastSpeakable = ""

    /** 尚未切出去的正文尾巴（原文，清洗留到切句时）。 */
    private val pending = StringBuilder()

    /** 开始新一轮：清掉上一轮残留。 */
    fun reset() {
        lastSpeakable = ""
        pending.setLength(0)
    }

    /**
     * 喂入当前完整正文（累积快照），返回本次新切出的句子。
     *
     * @param isFinal 文本流是否已结束。为 true 时把剩余尾巴也切出去。
     */
    fun feed(fullBody: String, isFinal: Boolean): Fed {
        // 先按围栏过滤：代码块里的内容不念（逐字念 shell/代码没有意义）。
        // 按完整正文重算，因为一次增量可能正好切在围栏行或代码行中间。
        var rewritten = false
        val speakable = SpeechTextNormalizer.stripFencedBlocks(fullBody, flush = isFinal)
        val delta = if (speakable.startsWith(lastSpeakable)) {
            speakable.substring(lastSpeakable.length)
        } else {
            // 正文被重写（换块/重试）：已念出去的收不回，从当前文本重新接上。
            // 围栏过滤保证单调后这里不该再触发，真触发了说明上游换了正文。
            rewritten = true
            pending.setLength(0)
            speakable
        }
        lastSpeakable = speakable
        // 只按增量攒**原文**，清洗一律留到整句：增量边界会落在 `| 项目 |`、`- `、
        // `10:05` 中间，按增量清洗会把表格竖线当垃圾删掉（`水果 特点` 粘成一句）。
        if (delta.isNotEmpty()) pending.append(delta)

        return Fed(cutReady(force = isFinal), rewritten)
    }

    /** [feed] 的结果：本次切出的句子 + 正文是否被重写过（换块/重试，调用方记日志）。 */
    internal data class Fed(val sentences: List<String>, val rewritten: Boolean)

    /** 把 [pending] 里够完整的句子切出去。 */
    private fun cutReady(force: Boolean): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val cut = nextCut(force) ?: break
            // 整句上再过一遍结构清洗：行首列表符号可能正好被增量边界切成两半
            // （`-` 和它后面的空格分属两次增量），只按增量清洗会漏掉它。
            // 读法归一化同样只能在这里做：增量边界可能正好切在 `10:05` 中间。
            val sentence = SpeechTextNormalizer.normalizeForSpeech(
                SpeechTextNormalizer.cleanMarkup(pending.substring(0, cut).trim()),
            )
            pending.delete(0, cut)
            if (sentence.isNotEmpty()) out.add(sentence)
        }
        return out
    }

    /** 返回可切位置（标点之后）；null 表示还攒得不够。 */
    private fun nextCut(force: Boolean): Int? {
        val text = pending
        if (text.isEmpty()) return null

        for (i in text.indices) {
            if (HARD_BREAKS.contains(text[i]) || isSentencePeriod(text, i)) {
                // 很短的碎句也切（「你好。」），但不切单独一个标点
                return if (i + 1 >= MIN_CHUNK) i + 1 else continue
            }
        }

        if (force) return text.length

        if (text.length >= SOFT_CHUNK) {
            for (i in text.length - 1 downTo SOFT_CHUNK - 1) {
                if (SOFT_BREAKS.contains(text[i])) return i + 1
            }
        }

        if (text.length >= MAX_CHUNK) return MAX_CHUNK
        return null
    }

    /**
     * ASCII 句点是否算句末。
     *
     * 中文回复靠 `。` 就能流式切句，英文回复只有 `.`，不额外认它就得等整段回复念完，
     * 流式朗读形同失效。但 `.` 同时出现在小数（`3.14`）、域名（`github.com`）里，
     * 所以只在「后面已跟空白、且前面不是数字或点」时才当句末；行尾的 `.` 由
     * [nextCut] 的 `force` 分支兜底。
     */
    private fun isSentencePeriod(text: CharSequence, i: Int): Boolean {
        if (text[i] != '.') return false
        val prev = text.getOrNull(i - 1) ?: return false
        if (prev.isDigit() || prev == '.') return false
        val next = text.getOrNull(i + 1) ?: return false
        return next.isWhitespace()
    }
}
