package moe.shizuku.manager.terminal

import android.os.ParcelFileDescriptor
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.Executors

/** 输出来自哪一路。 */
enum class TerminalStream { STDOUT, STDERR }

/**
 * 以 Shizuku 服务端身份运行的一个 shell 会话。
 *
 * 权限说明：进程由服务端 `Runtime.exec` 起，因此身份就是服务端自己 —— 用 ADB 启动
 * Shizuku 时是 `uid=2000 (shell)`，用 root 启动时是 `uid=0`。终端本身不做任何提权，
 * 也不申请 API 权限：manager 就是服务端所属的应用，`Service.checkCallerPermission`
 * 里对 manager 直接放行。
 *
 * 实现要点：
 * * 用 AIDL 生成的 [IShizukuService] 直接调用 `newProcess`，不手写事务码，也不依赖
 *   已被标记移除的 `Shizuku.newProcess`（那个是 private）。
 * * 服务端返回的 stdin/stdout/stderr 都是 [ParcelFileDescriptor]，用 AutoClose 流包装。
 * * 官方文档明确要求"读写流要在不同线程"，所以 stdout / stderr / 等待退出各用一条线程。
 * * 读流用 [InputStreamReader] 而不是自己 decode 字节数组：UTF-8 的多字节字符可能
 *   被 read 切断，交给 InputStreamReader 才不会把中文切成乱码。
 *
 * 本类持有一个常驻的 `sh`，所以 `cd`、`export` 这类会话状态是有效的。
 */
class TerminalSession(
    private val onOutput: (TerminalStream, String) -> Unit,
    private val onExit: (Int) -> Unit,
) {

    companion object {
        /** 启一个非交互 sh；它从管道读命令，所以不需要（也没有）tty。 */
        private val COMMAND = arrayOf("/system/bin/sh")

        private const val BUFFER_SIZE = 4096
    }

    private var remote: IRemoteProcess? = null
    private var stdin: OutputStream? = null
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "terminal-stdin").apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    /**
     * 启动会话。
     *
     * @throws Throwable 服务未运行、没有权限、服务端版本不匹配等，调用方应把消息显示给用户。
     */
    fun start() {
        val binder = Shizuku.getBinder() ?: throw IllegalStateException("Shizuku service is not running")

        val service = IShizukuService.Stub.asInterface(binder)
            ?: throw IllegalStateException("IShizukuService is null")

        // env 传 null = 继承服务端的环境变量。不要自己拼一份精简的 PATH：
        // Runtime.exec 的 env 是"整体替换"，漏掉 ANDROID_ROOT / LD_LIBRARY_PATH
        // 之类会让 am、pm 这些命令异常。
        val process = service.newProcess(COMMAND, null, null)
            ?: throw IllegalStateException("newProcess returned null")

        remote = process
        stdin = ParcelFileDescriptor.AutoCloseOutputStream(process.outputStream)

        startReader(process.inputStream, TerminalStream.STDOUT)
        startReader(process.errorStream, TerminalStream.STDERR)
        startWaiter(process)
    }

    /** 把一行命令写进 sh 的 stdin。 */
    fun execute(command: String) {
        val out = stdin ?: return
        writeExecutor.execute {
            try {
                out.write((command + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (t: Throwable) {
                // 进程已经结束（例如用户点了停止），忽略即可
            }
        }
    }

    /** 结束进程。 */
    fun kill() {
        try {
            remote?.destroy()
        } catch (t: Throwable) {
            // 已经退出
        }
    }

    /** 页面销毁时调用，避免泄漏。 */
    fun close() {
        closed = true
        try {
            stdin?.close()
        } catch (t: Throwable) {
        }
        kill()
        writeExecutor.shutdownNow()
    }

    private fun startReader(pfd: ParcelFileDescriptor, stream: TerminalStream) {
        val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val stripper = AnsiStripper()

        Thread({
            val reader = InputStreamReader(input, Charsets.UTF_8)
            val chars = CharArray(BUFFER_SIZE)
            try {
                while (!closed) {
                    val n = reader.read(chars)
                    if (n < 0) break
                    if (n == 0) continue
                    val text = stripper.strip(String(chars, 0, n))
                    if (text.isNotEmpty()) onOutput(stream, text)
                }
            } catch (t: Throwable) {
                // 管道关闭 / 进程被杀都会走到这里，属于正常收尾
            } finally {
                try {
                    reader.close()
                } catch (t: Throwable) {
                }
            }
        }, "terminal-read-${stream.name.lowercase()}").apply { isDaemon = true }.start()
    }

    private fun startWaiter(process: IRemoteProcess) {
        Thread({
            val code = try {
                process.waitFor()
            } catch (t: Throwable) {
                -1
            }
            if (!closed) onExit(code)
        }, "terminal-wait").apply { isDaemon = true }.start()
    }
}
