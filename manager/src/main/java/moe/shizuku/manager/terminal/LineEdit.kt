package moe.shizuku.manager.terminal

/** 一次行编辑的结果：新文本 + 新光标位置。 */
data class LineState(val text: String, val cursor: Int)

/**
 * 命令行编辑动作，供软按键（Ctrl / Alt + 方向键）使用。
 *
 * 为什么需要这个：Shizuku 不提供 pty，所以 Ctrl 组合键**无法**像真终端那样把控制字符
 * 发给前台进程。但这台设备上真正难用的是"在手机键盘上编辑一长串命令"，所以这里把
 * Ctrl/Alt 实现成**行编辑动作**——这是没有 pty 时仍然成立、而且确实有用的那部分语义。
 *
 * 纯 Kotlin、零 Android 依赖，因此可以在 JVM 上直接跑断言（见 tools/test-lineedit.sh）。
 */
object LineEdit {

    /** shell 习惯：以空白分隔的词。 */
    private fun isSpace(c: Char) = c == ' ' || c == '\t'

    private fun clamp(text: String, cursor: Int) = cursor.coerceIn(0, text.length)

    /** Ctrl+A：光标到行首 */
    fun home(text: String, cursor: Int): LineState = LineState(text, 0)

    /** Ctrl+E：光标到行尾 */
    fun end(text: String, cursor: Int): LineState = LineState(text, text.length)

    /** 左右方向键：按字符移动 */
    fun moveChar(text: String, cursor: Int, delta: Int): LineState =
        LineState(text, (clamp(text, cursor) + delta).coerceIn(0, text.length))

    /**
     * 按词移动（Ctrl + 左右，或 Alt + 左右配合不同调用）。
     *
     * @param direction -1 向左、+1 向右
     */
    fun moveWord(text: String, cursor: Int, direction: Int): LineState {
        var i = clamp(text, cursor)
        if (direction < 0) {
            while (i > 0 && isSpace(text[i - 1])) i--
            while (i > 0 && !isSpace(text[i - 1])) i--
        } else {
            while (i < text.length && !isSpace(text[i])) i++
            while (i < text.length && isSpace(text[i])) i++
        }
        return LineState(text, i)
    }

    /** Ctrl+K：删除光标到行尾 */
    fun killToEnd(text: String, cursor: Int): LineState {
        val c = clamp(text, cursor)
        return LineState(text.substring(0, c), c)
    }

    /**
     * Ctrl+W：删除光标前的一个词，连同它前面的空白（readline 行为）。
     */
    fun deleteWordBefore(text: String, cursor: Int): LineState {
        var i = clamp(text, cursor)
        var end = i
        while (i > 0 && isSpace(text[i - 1])) i--
        while (i > 0 && !isSpace(text[i - 1])) i--
        if (i == end) return LineState(text, end)
        return LineState(text.substring(0, i) + text.substring(end), i)
    }
}
