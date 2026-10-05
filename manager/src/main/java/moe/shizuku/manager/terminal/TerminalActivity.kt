package moe.shizuku.manager.terminal

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
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
import rikka.core.util.ResourceUtils
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

    /** 当前这条命令是否已经产生过输出（用来判断要不要补一行状态）。 */
    private var hadOutputSinceCommand = false

    /** 历史浏览位置；等于 history.size 表示当前不在浏览历史。 */
    private var historyIndex = 0

    /** 软按键 Ctrl / Alt 的粘滞状态（按一次生效一次）。 */
    private var ctrlArmed = false
    private var altArmed = false

    /** 我们自己改输入框文本时，不要再触发 TextWatcher 里的 Ctrl 处理。 */
    private var editingInternally = false

    private var ctrlIdleColor = 0
    private var altIdleColor = 0

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

        setupSoftKeys()

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

        // 会话开始时打印横幅；当前用户不用拼，直接跑 id 让系统自己说
        appendHint(getString(R.string.terminal_banner, shizukuVersion()) + "\n")
        appendHint(getString(R.string.terminal_no_pty_hint) + "\n\n")

        val s = TerminalSession(
            onOutput = { stream, text -> onSessionOutput(stream, text) },
            onCommandFinished = { code, cwd -> onCommandFinished(code, cwd) },
            onExit = { code -> onSessionExit(code) },
        )
        session = s

        try {
            s.start()
            sessionActive = true
            printCurrentUser()
        } catch (t: Throwable) {
            appendError(getString(R.string.terminal_start_failed, t.message ?: t.javaClass.simpleName) + "\n")
        }
        updateButtons()
    }

    private fun shizukuVersion(): String = try {
        Shizuku.getVersion().toString()
    } catch (t: Throwable) {
        "?"
    }

    /**
     * 进终端就立即打出当前身份。
     *
     * 这里真去跑 `id`，而不是自己拼一行显示 uid：`id` 会同时给出 uid/gid/groups 和
     * SELinux 上下文，信息更全，而且用户看到的是命令的真实输出。
     */
    private fun printCurrentUser() {
        appendEcho("id")
        session?.execute("id")
    }

    private fun updateButtons() {
        binding.stop.setText(if (sessionActive) R.string.terminal_stop else R.string.terminal_restart)
    }

    private fun submit() {
        val text = binding.input.text?.toString().orEmpty()
        val commands = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (commands.isEmpty()) return

        clearModifiers()
        binding.input.setText("")
        hadOutputSinceCommand = false

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
     * 一条命令跑完了（由 shell 的探针回显触发）。
     *
     * 这里解决的是"命令没有输出就没有反馈"的问题：只要没有产生任何输出，或者退出码非 0，
     * 就补一行状态；并且无论如何都保证换行收尾，这样下一条命令的回显不会粘在上一行输出后面。
     */
    private fun onCommandFinished(exitCode: Int, cwd: String) {
        mainHandler.post {
            ensureTrailingNewline()
            if (!hadOutputSinceCommand || exitCode != 0) {
                appendHint(getString(R.string.terminal_command_exit, exitCode) + "\n")
            }
            hadOutputSinceCommand = false
            binding.cwd.text = cwd
        }
    }

    /** 强制让输出以换行结尾（`printf hello` 这类命令不会自己换行）。 */
    private fun ensureTrailingNewline() {
        if (output.isEmpty() || output[output.length - 1] != '\n') {
            appendSystem("\n")
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
        hadOutputSinceCommand = true

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
        showHistoryIndex(next)
    }

    private fun showHistoryIndex(index: Int) {
        historyIndex = index.coerceIn(0, history.size)
        val text = if (historyIndex == history.size) "" else history[historyIndex]
        editingInternally = true
        binding.input.setText(text)
        binding.input.setSelection(text.length)
        editingInternally = false
    }

    // -------------------------------------------------------------- 软按键

    /**
     * 手机键盘上没有 Ctrl / Alt / 方向键，这里补一排。
     *
     * 关于 Ctrl / Alt 的语义：Shizuku 不提供 pty，所以**没法**把控制字符送给前台进程
     * （例如 Ctrl+C 无法中断运行中的命令）。因此这两个键实现成"粘滞修饰键 + 行编辑
     * 动作"——这是没有 pty 时依然成立、而且在手机上确实解决痛点（改一长串命令很难）
     * 的那部分语义：
     *
     * * Ctrl + ← / → ：按词移动光标
     * * Ctrl + ↑ / ↓ ：跳到最早 / 最新一条历史
     * * Ctrl + 字母  ：readline 风格的行编辑（见 [handleCtrlLetter]）
     * * Alt  + ← / → ：跳到行首 / 行尾
     */
    private fun setupSoftKeys() {
        binding.keyCtrl.setOnClickListener {
            ctrlArmed = !ctrlArmed
            if (ctrlArmed) altArmed = false
            updateModifierButtons()
        }
        binding.keyAlt.setOnClickListener {
            altArmed = !altArmed
            if (altArmed) ctrlArmed = false
            updateModifierButtons()
        }

        binding.keyLeft.setOnClickListener { onSoftArrow(dx = -1, dy = 0) }
        binding.keyRight.setOnClickListener { onSoftArrow(dx = 1, dy = 0) }
        binding.keyUp.setOnClickListener { onSoftArrow(dx = 0, dy = -1) }
        binding.keyDown.setOnClickListener { onSoftArrow(dx = 0, dy = 1) }

        // Ctrl + 字母：输入框里拦下这个字母，改成执行行编辑动作
        binding.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (editingInternally || s == null) return

                if (ctrlArmed && count == 1 && before == 0 && handleCtrlLetter(s[start])) {
                    // 撤销这次插入：Ctrl 组合键不应该把字母真的打进命令里
                    editingInternally = true
                    binding.input.text?.delete(start, start + 1)
                    editingInternally = false
                }
                if (ctrlArmed || altArmed) clearModifiers()
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        applyKeyColors()
    }

    /**
     * 给整排软按键上色。
     *
     * 之前只改了 Ctrl / Alt，而且只在切换修饰键时才 setTextColor —— 结果一进终端根本
     * 没应用过，箭头键也一直是主题的 primary（深色模式下是靛蓝而不是白）。现在六个键
     * 统一在进入时上色。
     */
    private fun applyKeyColors() {
        val idle = softKeyIdleColor()
        ctrlIdleColor = idle
        altIdleColor = idle

        for (key in listOf(
            binding.keyCtrl, binding.keyAlt,
            binding.keyLeft, binding.keyRight, binding.keyUp, binding.keyDown,
        )) {
            key.setTextColor(idle)
        }
        // 若此刻正好有修饰键处于按下状态，保持它的高亮
        updateModifierButtons()
    }

    private fun onSoftArrow(dx: Int, dy: Int) {
        if (dy != 0) {
            if (ctrlArmed) {
                showHistoryIndex(if (dy < 0) 0 else history.size)
            } else {
                browseHistory(dy)
            }
        } else {
            val text = binding.input.text?.toString().orEmpty()
            val cursor = binding.input.selectionStart.coerceAtLeast(0).coerceAtMost(text.length)
            val state = when {
                ctrlArmed -> LineEdit.moveWord(text, cursor, dx)
                altArmed -> if (dx < 0) LineEdit.home(text, cursor) else LineEdit.end(text, cursor)
                else -> LineEdit.moveChar(text, cursor, dx)
            }
            applyLineState(state)
        }
        clearModifiers()
    }

    /** @return true 表示这个组合键已被处理 */
    private fun handleCtrlLetter(ch: Char): Boolean {
        val text = binding.input.text?.toString().orEmpty()
        val cursor = binding.input.selectionStart.coerceAtLeast(0).coerceAtMost(text.length)

        return when (ch.lowercaseChar()) {
            'a' -> { applyLineState(LineEdit.home(text, cursor)); true }
            'e' -> { applyLineState(LineEdit.end(text, cursor)); true }
            'u' -> { applyLineState(LineState("", 0)); true }
            'k' -> { applyLineState(LineEdit.killToEnd(text, cursor)); true }
            'w' -> { applyLineState(LineEdit.deleteWordBefore(text, cursor)); true }
            'l' -> { output.clear(); render(); true }
            'c' -> {
                // 没有 pty，中断不了运行中的命令；这里清空当前输入并说明原因
                applyLineState(LineState("", 0))
                appendHint(getString(R.string.terminal_cannot_interrupt) + "\n")
                true
            }
            'd' -> {
                // 管道模型下 EOF 是有效的：关掉 stdin，sh 就会退出
                session?.closeStdin()
                true
            }
            else -> false
        }
    }

    private fun applyLineState(state: LineState) {
        editingInternally = true
        binding.input.setText(state.text)
        binding.input.setSelection(state.cursor.coerceIn(0, state.text.length))
        editingInternally = false
    }

    private fun clearModifiers() {
        if (!ctrlArmed && !altArmed) return
        ctrlArmed = false
        altArmed = false
        updateModifierButtons()
    }

    private fun updateModifierButtons() {
        binding.keyCtrl.setTextColor(if (ctrlArmed) echoColor else ctrlIdleColor)
        binding.keyAlt.setTextColor(if (altArmed) echoColor else altIdleColor)
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

    /** 深色模式用白色，浅色模式用黑色。 */
    private fun softKeyIdleColor(): Int =
        if (ResourceUtils.isNightMode(resources.configuration)) Color.WHITE else Color.BLACK
}
