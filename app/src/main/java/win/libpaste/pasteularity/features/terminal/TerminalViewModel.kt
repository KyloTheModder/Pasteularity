package win.libpaste.pasteularity.features.terminal

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import win.libpaste.pasteularity.AppContainer
import win.libpaste.pasteularity.data.Snippet
import win.libpaste.pasteularity.shell.ShellEvent
import win.libpaste.pasteularity.shell.shellQuote
import win.libpaste.pasteularity.shell.userMessage

enum class LineKind { Command, Output, Status }

@Immutable
data class TerminalLine(val id: Long, val text: String, val kind: LineKind)

data class TerminalState(
    val lines: List<TerminalLine> = emptyList(),
    val input: String = "",
    val cwd: String = "/sdcard",
    val running: Boolean = false,
    val editingSnippets: Boolean = false,
)

private const val MAX_LINES = 3_000
private const val MAX_HISTORY = 200
private const val FLUSH_INTERVAL_MS = 120L
private const val CWD_MARKER = "__PSTU_CWD__"

class TerminalViewModel(private val container: AppContainer) : ViewModel() {
    private val mutable = MutableStateFlow(TerminalState())
    val state = mutable.asStateFlow()
    val snippets = container.settings.snippets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var nextLineId = 0L
    private var runJob: Job? = null
    private var history = emptyList<String>()
    private var historyIndex = -1

    init {
        viewModelScope.launch { history = container.settings.commandHistory.first() }
    }

    fun setInput(value: String) {
        historyIndex = -1
        mutable.update { it.copy(input = value) }
    }

    fun historyPrevious() {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + 1).coerceAtMost(history.lastIndex)
        mutable.update { it.copy(input = history[historyIndex]) }
    }

    fun historyNext() {
        historyIndex = (historyIndex - 1).coerceAtLeast(-1)
        mutable.update { it.copy(input = history.getOrElse(historyIndex) { "" }) }
    }

    fun clear() = mutable.update { it.copy(lines = emptyList()) }

    fun outputText(): String = state.value.lines.joinToString("\n") { it.text }

    fun run(command: String = state.value.input) {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || state.value.running) return
        addToHistory(trimmed)
        val cwd = state.value.cwd
        mutable.update {
            it.copy(
                input = "",
                running = true,
                lines = (it.lines + line("$cwd \$ $trimmed", LineKind.Command)).takeLast(MAX_LINES),
            )
        }
        // Off the main thread: a chatty command sends hundreds of lines a second through here.
        runJob = viewModelScope.launch(Dispatchers.Default) {
            val pending = mutableListOf<TerminalLine>()
            val flusher = launch {
                while (true) {
                    delay(FLUSH_INTERVAL_MS)
                    flush(pending)
                }
            }
            // The user's command is deliberately unquoted: this is the one place raw shell is the point.
            val script = "cd ${shellQuote(cwd)} 2>/dev/null\n$trimmed\n" +
                "__pstu_rc=\$?\necho \"$CWD_MARKER\$(pwd)\"\nexit \$__pstu_rc"
            var exitCode: Int? = null
            try {
                container.shell.stream(script).collect { event ->
                    when (event) {
                        is ShellEvent.Exit -> exitCode = event.code
                        is ShellEvent.Line -> handleLine(event.text, pending)
                    }
                }
            } catch (e: CancellationException) {
                synchronized(pending) { pending += line("Stopped.", LineKind.Status) }
            } catch (e: Exception) {
                synchronized(pending) { pending += line(e.userMessage(), LineKind.Status) }
            } finally {
                flusher.cancel()
                exitCode?.takeIf { it != 0 }?.let { code ->
                    synchronized(pending) { pending += line("Exited with code $code.", LineKind.Status) }
                }
                flush(pending)
                mutable.update { it.copy(running = false) }
            }
        }
    }

    fun stop() {
        runJob?.cancel()
    }

    private fun handleLine(text: String, pending: MutableList<TerminalLine>) {
        if (text.startsWith(CWD_MARKER)) {
            val newCwd = text.removePrefix(CWD_MARKER)
            mutable.update { it.copy(cwd = newCwd) }
        } else {
            synchronized(pending) { pending += line(text, LineKind.Output) }
        }
    }

    private fun flush(pending: MutableList<TerminalLine>) {
        val batch = synchronized(pending) {
            if (pending.isEmpty()) return
            pending.toList().also { pending.clear() }
        }
        mutable.update { it.copy(lines = (it.lines + batch).takeLast(MAX_LINES)) }
    }

    @Synchronized
    private fun line(text: String, kind: LineKind) = TerminalLine(nextLineId++, text, kind)

    private fun addToHistory(command: String) {
        historyIndex = -1
        history = (listOf(command) + history.filterNot { it == command }).take(MAX_HISTORY)
        viewModelScope.launch { container.settings.setCommandHistory(history) }
    }

    fun toggleEditingSnippets() = mutable.update { it.copy(editingSnippets = !it.editingSnippets) }

    fun addSnippet(label: String, command: String) {
        viewModelScope.launch {
            container.settings.setSnippets((snippets.value + Snippet(label, command)).distinct())
            container.messenger.success("Snippet saved.")
        }
    }

    fun removeSnippet(snippet: Snippet) {
        viewModelScope.launch { container.settings.setSnippets(snippets.value - snippet) }
    }
}
