package moe.shizuku.manager.terminal

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.ActivityTerminalBinding
import moe.shizuku.manager.shell.ShellTutorialActivity
import rikka.shizuku.Shizuku

/**
 * 内置命令控制台：以 Shizuku 服务端的身份执行命令。
 *
 * 权限来自服务端本身（ADB 启动 = shell，root 启动 = root），本页面不做任何提权，
 * 也不需要额外的 API 权限 —— manager 就是服务端所属的应用，服务端对 manager 直接放行。
 *
 * 注意这里**没有 pty**（Shizuku 不提供），所以 top / vi 这类全屏程序用不了；
 * 需要完整终端时走菜单里的 rish。
 */
class TerminalActivity : AppBarActivity() {

    companion object {
        /** 输出上限，超过就从最前面截断，避免长时间使用把内存吃光。 */
        private const val MAX_OUTPUT_CHARS = 200_000
        private const val MAX_HISTORY = 50
        private const val KEY_HISTORY = "terminal_history"
    }

    private lateinit var binding: ActivityTerminalBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    private var session: TerminalSession? = null
    private var sessionActive = false

    /** 终端正文。用 SpannableStringBuilder 以便给 stderr 上色。 */
    private val output = SpannableStringBuilder()

    /** 后台线程产生的输出先攒在这里，再由主线程批量刷进 UI。 */
    private val pendingChunks = ArrayDeque<Pair<TerminalStream, String>>()
    private val pendingLock = Any()
    private var refreshScheduled = false

    /** 命令历史，最新的在最后。 */
    private val history = mutableListOf<String>()

    /** 历史浏览位置；等于 history.size 表示当前不在浏览历史。 */
    private var historyIndex = 0

    private val echoColor by lazy { themeColor(com.google.android.material.R.attr.colorPrimary, 0xFF3F51B5.toInt()) }
    private val errorColor by lazy { themeColor(com.google.android.material.R.attr.colorError, 0xFFD32F2F.toInt()) }
    private val hintColor by lazy { themeColor(android.R.attr.textColorSecondary, 0xFF888888.toInt()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadHistory()

        binding.run.setOnClickListener { submit() }
        binding.clear.setOnClickListener {
            output.clear()
            render()
        }
        binding.stop.setOnClickListener {
            if (sessionActive) session?.kill() else startSession()
        }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            when (actionId) {
                EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_GO -> {
                    submit()
                    true
                }
                else -> false
            }
        }
        // 没有 pty，所以上下键只能用来翻本地历史
        binding.input.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    browseHistory(-1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    browseHistory(1)
                    true
                }
                else -> false
            }
        }

        startSession()
        updateButtons()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
    }

    // ------------------------------------------------------------------ 会话

    private fun startSession() {
        session?.close()
        session = null
        sessionActive = false
        updateButtons()

        if (!Shizuku.pingBinder()) {
            appendSystem(getString(R.string.terminal_service_not_running) + "\n")
            return
        }

        // 身份只在会话开始时显示一次
        appendIdentity()

        val s = TerminalSession(
            onOutput = { stream, text -> onSessionOutput(stream, text) },
            onExit = { code -> onSessionExit(code) },
        )
        session = s

        try {
            s.start()
            sessionActive = true
        } catch (t: Throwable) {
            appendError(getString(R.string.terminal_start_failed, t.message ?: t.javaClass.simpleName) + "\n")
        }
        updateButtons()
    }

    private fun appendIdentity() {
        val uid = Shizuku.getUid()
        val version = try {
            Shizuku.getVersion().toString()
        } catch (t: Throwable) {
            "?"
        }
        val label = when (uid) {
            0 -> getString(R.string.terminal_uid_root)
            2000 -> getString(R.string.terminal_uid_shell)
            else -> null
        }
        val line = if (label != null) {
            getString(R.string.terminal_identity, uid, label, version)
        } else {
            getString(R.string.terminal_identity_other, uid, version)
        }
        appendSystem(line + "\n")
        appendHint(getString(R.string.terminal_no_pty_hint) + "\n\n")
    }

    private fun updateButtons() {
        binding.stop.setText(if (sessionActive) R.string.terminal_stop else R.string.terminal_restart)
    }

    private fun submit() {
        val text = binding.input.text?.toString().orEmpty()
        val commands = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (commands.isEmpty()) return

        binding.input.setText("")

        if (!sessionActive) {
            appendSystem(getString(R.string.terminal_service_not_running) + "\n")
            return
        }

        for (command in commands) {
            // sh 在管道里不会回显，所以由本地回显
            appendEcho(command)
            session?.execute(command)
            addHistory(command)
        }
    }

    // ------------------------------------------------------------ 输出与刷新

    private fun onSessionOutput(stream: TerminalStream, text: String) {
        val post = synchronized(pendingLock) {
            pendingChunks.addLast(stream to text)
            if (refreshScheduled) {
                false
            } else {
                refreshScheduled = true
                true
            }
        }
        if (post) mainHandler.post(::drainPending)
    }

    private fun onSessionExit(code: Int) {
        mainHandler.post {
            appendHint("\n" + getString(R.string.terminal_session_exited, code) + "\n")
            sessionActive = false
            updateButtons()
        }
    }

    /**
     * 把攒下的输出一次性写进 UI。
     *
     * 不逐块刷新是因为一条命令可能瞬间产生成百上千个块，逐个 post 会把主线程刷爆。
     */
    private fun drainPending() {
        val chunks = synchronized(pendingLock) {
            val copy = pendingChunks.toList()
            pendingChunks.clear()
            refreshScheduled = false
            copy
        }
        if (chunks.isEmpty()) return

        // 记录刷新前是否贴在底部：是的话刷新后继续自动滚到底
        val wasAtBottom = isScrolledToBottom()

        var appended = false
        for ((stream, text) in chunks) {
            val start = output.length
            output.append(text)
            if (stream == TerminalStream.STDERR && output.length > start) {
                output.setSpan(
                    ForegroundColorSpan(errorColor),
                    start,
                    output.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            appended = true
        }
        if (!appended) return

        trimOutput()
        render()
        if (wasAtBottom) {
            binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun trimOutput() {
        if (output.length <= MAX_OUTPUT_CHARS) return
        output.delete(0, output.length - MAX_OUTPUT_CHARS)
    }

    private fun render() {
        binding.output.text = output
    }

    private fun isScrolledToBottom(): Boolean {
        val child = binding.scroll.getChildAt(0) ?: return true
        return child.bottom - binding.scroll.height - binding.scroll.scrollY <= 48
    }

    private fun append(text: String, color: Int?) {
        val start = output.length
        output.append(text)
        if (color != null && output.length > start) {
            output.setSpan(
                ForegroundColorSpan(color),
                start,
                output.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        trimOutput()
    }

    private fun appendSystem(text: String) = append(text, null)

    private fun appendHint(text: String) = append(text, hintColor)

    private fun appendError(text: String) = append(text, errorColor)

    private fun appendEcho(command: String) {
        append("\$ $command\n", echoColor)
    }

    // ------------------------------------------------------------------ 历史

    private fun loadHistory() {
        val stored = ShizukuSettings.getPreferences()
            ?.getString(KEY_HISTORY, null)
            .orEmpty()
        history.clear()
        history.addAll(stored.split('\n').filter { it.isNotEmpty() }.takeLast(MAX_HISTORY))
        historyIndex = history.size
    }

    private fun addHistory(command: String) {
        if (command.isBlank()) return
        history.remove(command)
        history.add(command)
        while (history.size > MAX_HISTORY) history.removeAt(0)
        historyIndex = history.size

        ShizukuSettings.getPreferences()
            ?.edit()
            ?.putString(KEY_HISTORY, history.joinToString("\n"))
            ?.apply()
    }

    private fun browseHistory(delta: Int) {
        if (history.isEmpty()) return

        val next = (historyIndex + delta).coerceIn(0, history.size)
        if (next == historyIndex) return

        historyIndex = next
        val text = if (historyIndex == history.size) "" else history[historyIndex]
        binding.input.setText(text)
        binding.input.setSelection(text.length)
    }

    // ------------------------------------------------------------------ 菜单

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.terminal, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_restart -> {
                startSession()
                true
            }
            R.id.action_rish -> {
                startActivity(Intent(this, ShellTutorialActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(attr, value, true)) value.data else fallback
    }
}
