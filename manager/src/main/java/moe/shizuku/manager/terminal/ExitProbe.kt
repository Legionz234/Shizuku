package moe.shizuku.manager.terminal

/** 一条命令执行完之后，shell 回显的状态。 */
data class ProbeResult(val exitCode: Int, val cwd: String)

/** 一次处理的结果：可以显示的正文 + 解析出的状态。 */
data class ProbeFeed(val text: String, val results: List<ProbeResult>)

/**
 * 从命令输出里挑出「命令执行完了没 / 退出码多少 / 当前在哪个目录」。
 *
 * 为什么要这么做：终端是个常驻的 `sh`，没有作业控制，所以我们**无法直接知道**
 * 某条命令什么时候跑完 —— 也因此，一条不产生任何输出的命令（例如 `cd /data`）
 * 在界面上会像什么都没发生。解决办法是每条命令之后追加一条探针命令：
 *
 * ```
 * __shizuku_status=$?; echo "<标记>exit=$__shizuku_status cwd=$PWD<标记>"
 * ```
 *
 * `$?` 在 `echo` 之前就取好了，`$PWD` 由 shell 自己维护（`cd` 之后随之改变）。
 * 标记用控制字符 `\u0001` 包起来：正常输出里几乎不可能出现，而且即使出现在界面上
 * 也会被 [AnsiStripper] 丢掉。
 *
 * 与 [AnsiStripper] 同理，解析必须是**有状态的**——标记完全可能被输出分块切成两半。
 *
 * 纯 Kotlin、零 Android 依赖，可在 JVM 上直接跑断言（见 tools/test-exitprobe.sh）。
 */
class ExitProbe {

    companion object {
        const val MARK = '\u0001'

        /** 记录退出码的临时变量名，取个不太可能与用户变量撞车的名字。 */
        private const val STATUS_VAR = "__shizuku_status"

        /** 未完成标记的缓冲上限，防止垃圾数据把内存吃掉。 */
        private const val MAX_PENDING = 512

        /** 追加在每条用户命令之后的探针命令。 */
        fun command(): String =
            "$STATUS_VAR=\$?; echo \"$MARK" + "exit=\$$STATUS_VAR cwd=\$PWD$MARK\""

        private val PATTERN = Regex("""exit=(-?\d+)\s+cwd=(.*)""", RegexOption.DOT_MATCHES_ALL)
    }

    private val pending = StringBuilder()

    /** 处理一块原始输出（未经 ANSI 处理）。 */
    fun feed(chunk: String): ProbeFeed {
        if (chunk.isEmpty() && pending.isEmpty()) return ProbeFeed("", emptyList())

        pending.append(chunk)
        val text = StringBuilder()
        val results = mutableListOf<ProbeResult>()
        var index = 0

        while (index < pending.length) {
            val start = pending.indexOf(MARK, index)
            if (start < 0) {
                text.append(pending, index, pending.length)
                index = pending.length
                break
            }
            // 标记之前的正文先原样吐出去
            text.append(pending, index, start)

            val end = pending.indexOf(MARK, start + 1)
            if (end < 0) {
                // 标记不完整，留着等下一块
                index = start
                break
            }

            val body = pending.substring(start + 1, end)
            val parsed = parse(body)
            if (parsed != null) {
                results.add(parsed)
            } else {
                // 认不出来就当作普通文本，至少不丢内容
                text.append(body)
            }
            index = end + 1
        }

        pending.delete(0, index)
        if (pending.length > MAX_PENDING) {
            // 正常的探针标记只有几十字节，超过上限只可能是垃圾数据（例如设备吐了一个
            // 没有闭合的标记）。这里直接丢弃而不是倒进输出——倒出去会把垃圾显示给用户，
            // 而丢失"真实但长达 512 字节还没闭合的标记"这种事不会发生。
            pending.setLength(0)
        }

        return ProbeFeed(text.toString(), results)
    }

    fun reset() {
        pending.setLength(0)
    }

    private fun parse(body: String): ProbeResult? {
        val match = PATTERN.matchEntire(body) ?: return null
        val code = match.groupValues[1].toIntOrNull() ?: return null
        return ProbeResult(code, match.groupValues[2])
    }
}
