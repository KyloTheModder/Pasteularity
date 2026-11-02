package win.libpaste.pasteularity.shell

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

const val DEFAULT_SHELL_TIMEOUT_MS = 15_000L

sealed interface ShellEvent {
    data class Line(val text: String) : ShellEvent
    data class Exit(val code: Int) : ShellEvent
}

/** The one way features reach elevated commands. Both ADB and ByteZuku run as uid 2000. */
interface ShellBackend {
    val isConnected: StateFlow<Boolean>

    /** Runs [cmd] on [kotlinx.coroutines.Dispatchers.IO]. A non-zero exit is a failure. */
    suspend fun exec(cmd: String, timeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS): Result<String>

    /** Runs [cmd] until it exits or the collector cancels, which also kills the command. */
    fun stream(cmd: String): Flow<ShellEvent>
}

open class ShellException(message: String) : Exception(message)

class CommandFailedException(val exitCode: Int, val output: String) :
    ShellException(output.ifBlank { "Command exited with code $exitCode." })

class ShellTimeoutException : ShellException("The command timed out.")

class NotConnectedException : ShellException("No shell connection. Open Settings to connect.")

class ConnectionClosedException : ShellException("The shell connection closed.")

class UnsupportedFirmwareException : ShellException(NOT_SUPPORTED_MESSAGE)
