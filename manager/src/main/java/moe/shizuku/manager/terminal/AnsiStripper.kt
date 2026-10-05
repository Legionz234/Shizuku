package moe.shizuku.manager.terminal

/**
 * 去掉 ANSI 转义序列，只留下可以显示的文本。
 *
 * 为什么需要它：命令虽然跑在没有 tty 的管道里、大多不会输出颜色，但仍有一批命令
 * （`ls --color`、`cmd`、部分 `dumpsys`）会带上 ANSI 序列。不处理的话终端里会出现
 * 一堆 `\u001b[0;32m` 之类的乱码。
 *
 * 为什么是有状态的：进程输出是按块到达的，一个转义序列完全可能被切断，例如
 * 先收到 `"\u001b[3"`、下一块才是 `"1m"`。本类会把未完成的序列留在内部，
 * 等后续数据拼上再处理，绝不把半个序列当正文吐出去。
 *
 * 纯 Kotlin、零 Android 依赖 —— 这样可以在 JVM 上直接跑断言（见 tools/test-ansi.sh）。
 */
class AnsiStripper {

    companion object {
        /**
         * 未完成序列的缓冲上限。正常的 CSI/OSC 都很短，一旦超过这个长度说明遇到的是
         * 垃圾数据（例如设备吐了一个没有终止符的 OSC），直接丢弃，避免无限增长。
         */
        private const val MAX_PENDING = 256

        private const val ESC = '\u001b'
        private const val BEL = '\u0007'
    }

    private val pending = StringBuilder()

    /** 处理一块输出，返回其中可直接显示的文本。 */
    fun strip(chunk: String): String {
        if (chunk.isEmpty() && pending.isEmpty()) return ""

        pending.append(chunk)
        val out = StringBuilder(pending.length)
        var index = 0

        while (index < pending.length) {
            val c = pending[index]
            when {
                c == ESC -> {
                    val consumed = matchEscape(pending, index)
                    if (consumed < 0) break // 序列不完整，留给下一块
                    index += consumed
                }
                // 回车在本实现里不模拟（没有终端仿真），直接丢掉，否则 TextView 会画出怪字符
                c == '\r' -> index++
                c == '\n' || c == '\t' || c >= ' ' -> {
                    out.append(c)
                    index++
                }
                // 其它 C0 控制字符（响铃、退格等）一律丢弃
                else -> index++
            }
        }

        pending.delete(0, index)
        if (pending.length > MAX_PENDING) {
            pending.setLength(0)
        }
        return out.toString()
    }

    /** 丢弃未完成的序列（例如会话重启时）。 */
    fun reset() {
        pending.setLength(0)
    }

    /**
     * 尝试匹配从 [start] 处开始的转义序列。
     *
     * @return 消耗的字符数；返回 -1 表示数据还不完整，需要等下一块。
     */
    private fun matchEscape(s: CharSequence, start: Int): Int {
        var i = start + 1
        if (i >= s.length) return -1 // 只有一个 ESC

        when (s[i]) {
            '[' -> { // CSI：ESC [ 参数 中间字节 终止字节
                i++
                while (i < s.length) {
                    val x = s[i]
                    i++
                    if (x in '\u0040'..'\u007e') return i - start
                }
                return -1
            }
            ']' -> { // OSC：ESC ] ... BEL 或 ESC \
                i++
                while (i < s.length) {
                    val x = s[i]
                    if (x == BEL) return i - start + 1
                    if (x == ESC) {
                        if (i + 1 >= s.length) return -1
                        if (s[i + 1] == '\\') return i - start + 2
                    }
                    i++
                }
                return -1
            }
            in '\u0020'..'\u002f' -> { // 带中间字节的序列，例如 ESC ( B
                i++
                while (i < s.length) {
                    val x = s[i]
                    i++
                    if (x in '\u0030'..'\u007e') return i - start
                }
                return -1
            }
            else -> return 2 // 两字符序列（ESC 7、ESC = 等），或无法识别的，按两字符吃掉
        }
    }
}
