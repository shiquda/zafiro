package com.niki914.zafiro.voice

/**
 * 把 Agent 的 Markdown 正文改写成「能念」的纯文本。
 *
 * 端侧 TTS 是照字面发音的。用 matcha-icefall-zh-en 合成、SenseVoice 回听实测到的破坏：
 * - `**` / `##` 会被念成垃圾音节（`**麦克风**` → "esttu cast麦克风"）；
 * - emoji 会被念出英文名（`✅` → "with heavy check mark"）；
 * - `10:05` → "tenanzero five"，`2026/10/6` → "slash"；
 * - `×` → "time"，`=` → "close it"，`—` → "减"，`$` → "aler"；
 * - 表格竖线被吃掉后把两侧的词粘成一个不存在的词（`| 麦克风 | 正常 |` → "麦克更正常"）；
 * - 代码块会被逐字念出来（`am force-stop com.niki914.zafiro` → "and do co Niky 90040"）。
 *
 * 因此分两层：
 * - [stripFencedBlocks] + [cleanMarkup] 处理 Markdown 结构，按增量调用；
 *   围栏要按「完整正文」重算，因为一次增量可能正好切在围栏行或代码行中间。
 * - [normalizeForSpeech] 处理读法，按切好的整句调用，避免增量边界把 `10:05` 拆成两半。
 */
internal object SpeechTextNormalizer {

    /** 行首围栏：Markdown 代码块的 ``` 或 ~~~。 */
    private val FENCE_PREFIXES = listOf("```", "~~~")

    private val IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val BARE_URL = Regex("(?:https?://|www\\.)\\S+")
    private val HTML_TAG = Regex("</?[a-zA-Z][^>]*>")

    // 注意：这些是 MULTILINE 正则，字符类里只能用 [ \t] 不能用 \s ——
    // `\s` 含换行，`^\s*` 会跨行吞掉整行，把相邻两行并成一行。

    /** 表格分隔行（`|---|---|`）与分隔线（`---` / `***` / `===`）。 */
    private val TABLE_SEPARATOR = Regex("^[ \\t]*\\|?[ \\t:|-]+\\|[ \\t]*$", RegexOption.MULTILINE)
    private val THEMATIC_BREAK = Regex("^[ \\t]*[-*_=]{3,}[ \\t]*$", RegexOption.MULTILINE)

    /** 表格行首/行尾的竖线是表格边界，不是单元格分隔，直接去掉。 */
    private val TABLE_LEADING_PIPE = Regex("^[ \\t]*[|｜]", RegexOption.MULTILINE)
    private val TABLE_TRAILING_PIPE = Regex("[|｜][ \\t]*$", RegexOption.MULTILINE)
    private val PIPE = Regex("[|｜]")

    /** 行首列表符号：`-` `*` `+` `•` 与 `1.` `1)` `1、`。 */
    private val LIST_MARKER = Regex("^[ \\t]{0,3}(?:[-*+•·▪◦]|\\d{1,3}[.)、])[ \\t]+", RegexOption.MULTILINE)

    /** 行内标记：强调、标题、引用、删除线、行内代码。 */
    private val INLINE_MARKUP = Regex("[`*_#>~^]+")

    /** emoji 与图形符号：引擎会念出它们的英文名。 */
    private val PICTOGRAPH = Regex(
        "[\\x{1F000}-\\x{1FAFF}\\x{2300}-\\x{23FF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}" +
            "\\x{25A0}-\\x{25FF}\\x{2190}-\\x{21FF}\\x{FE0F}\\x{200D}]+",
    )
    private val BULLET_DECORATION = Regex("[•·▪◦]")

    private val DATE = Regex("(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})")
    private val TIME_HMS = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})")
    private val TIME_HM = Regex("(\\d{1,2}):(\\d{2})")
    private val EM_DASH = Regex("[—–－]")
    private val CURRENCY = Regex("[$￥€£]")
    private val PLUS_OPERATOR = Regex("(?<=\\s)\\+(?=\\s)")
    private val EQUALS_OPERATOR = Regex("(?<=\\s)=(?=\\s)")
    private val MULTI_SPACE = Regex("[ \\t]+")

    /** 全角标点两侧的空格（只吃空格/制表符，换行要留给切句用）。 */
    private val SPACED_CJK_PUNCT = Regex("[ \\t]*([，。！？；：、])[ \\t]*")
    private val TRAILING_SPACE = Regex("[ \\t]+(?=\\n)")
    private val LEADING_SPACE = Regex("\\n[ \\t]+")
    private val BLANK_LINES = Regex("\\n{2,}")

    /**
     * 丢掉 ``` / ~~~ 围栏内的整块内容（含围栏行本身），保留围栏外的正文。
     *
     * 传完整正文而不是增量：一次增量可能正好落在围栏行或代码行中间，
     * 只看增量会认不出围栏，把代码念出来。
     */
    fun stripFencedBlocks(body: String): String {
        if (FENCE_PREFIXES.none { body.contains(it) }) return body
        val out = StringBuilder(body.length)
        var inFence = false
        body.split('\n').forEachIndexed { index, line ->
            if (index > 0) out.append('\n')
            val head = line.trimStart()
            when {
                FENCE_PREFIXES.any { head.startsWith(it) } -> inFence = !inFence
                !inFence -> out.append(line)
            }
        }
        return out.toString()
    }

    /** 剥掉 Markdown 标记，只留可念的文字。 */
    fun cleanMarkup(text: String): String = text
        // 图片整体丢掉；链接只保留可见文字
        .replace(IMAGE, " ")
        .replace(LINK, "$1")
        // 裸链接与 HTML 标签念出来只是噪音
        .replace(BARE_URL, " ")
        .replace(HTML_TAG, " ")
        // 表格：分隔行与分隔线丢掉，边界竖线去掉，单元格之间的竖线换逗号
        .replace(TABLE_SEPARATOR, " ")
        .replace(THEMATIC_BREAK, " ")
        .replace(TABLE_LEADING_PIPE, "")
        .replace(TABLE_TRAILING_PIPE, "")
        .replace(PIPE, "，")
        // 行首列表符号：删掉但保留换行，让切句处自然停顿
        .replace(LIST_MARKER, "")
        .replace(INLINE_MARKUP, "")
        // emoji 与装饰符号
        .replace(PICTOGRAPH, " ")
        .replace(BULLET_DECORATION, " ")

    /**
     * 句子级读法归一化：把引擎念不好的字面量改写成中文写法。
     *
     * 按切好的整句调用（不是增量），否则 `10:05` 可能正好被增量边界拆开，规则就匹配不到了。
     */
    fun normalizeForSpeech(chunk: String): String = chunk
        // 日期/时间先写成中文写法，再交给引擎（配合 number-zh.fst 会念成中文数字）
        .replace(DATE, "$1年$2月$3日")
        .replace(TIME_HMS, "$1点$2分$3秒")
        .replace(TIME_HM, "$1点$2分")
        // 剩下的斜杠只当停顿（念成 "slash" 很出戏）
        .replace("/", " ")
        // 破折号/连接号会被念成「减」
        .replace(EM_DASH, "，")
        // 货币符号念不出来
        .replace(CURRENCY, "")
        // 运算符：只有两侧带空格的才当运算符，避免动到 C++ / key=value 这类写法
        .replace(PLUS_OPERATOR, "加")
        .replace(EQUALS_OPERATOR, "等于")
        .replace("×", "乘")
        .replace("÷", "除以")
        // 全角标点两侧不留空格：`warm ， dinner` 念起来会多出停顿
        .replace(SPACED_CJK_PUNCT, "$1")
        // 收尾：合并空白、去掉行尾空格与连续空行
        .replace(MULTI_SPACE, " ")
        .replace(TRAILING_SPACE, "")
        .replace(LEADING_SPACE, "\n")
        .replace(BLANK_LINES, "\n")
        .trim()
}
