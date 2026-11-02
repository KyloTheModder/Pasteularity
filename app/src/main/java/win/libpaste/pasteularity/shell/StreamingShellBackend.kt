package win.libpaste.pasteularity.shell

import java.io.Closeable
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ShellSession(val input: InputStream, private val onClose: () -> Unit) : Closeable {
    override fun close() = onClose()
}

/** Shared exec and stream logic. A backend only has to open a byte stream for a command. */
abstract class StreamingShellBackend : ShellBackend {

    protected abstract fun openSession(command: String): ShellSession

    protected open fun onConnectionLost() = Unit

    override fun stream(cmd: String): Flow<ShellEvent> = callbackFlow {
        val session = runInterruptible(Dispatchers.IO) { openSession(ShellProtocol.wrap(cmd)) }
        launch(Dispatchers.IO) {
            var exited = false
            val failure = runCatching {
                runInterruptible {
                    val reader = session.input.bufferedReader()
                    while (!exited) {
                        val line = reader.readLine() ?: break
                        ShellProtocol.parseLine(line).forEach { event ->
                            trySendBlocking(event)
                            if (event is ShellEvent.Exit) exited = true
                        }
                    }
                }
            }.exceptionOrNull()
            if (!exited && failure !is CancellationException) onConnectionLost()
            close(if (exited || failure is CancellationException) null else ConnectionClosedException())
        }
        awaitClose { session.close() }
    }

    override suspend fun exec(cmd: String, timeoutMs: Long): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val output = StringBuilder()
                var exitCode: Int? = null
                withTimeout(timeoutMs) {
                    stream(cmd).collect { event ->
                        when (event) {
                            is ShellEvent.Line -> output.appendLine(event.text)
                            is ShellEvent.Exit -> exitCode = event.code
                        }
                    }
                }
                val text = output.toString().trimEnd()
                when (val code = exitCode) {
                    0 -> Result.success(text)
                    null -> Result.failure(ConnectionClosedException())
                    else -> Result.failure(CommandFailedException(code, text))
                }
            } catch (e: TimeoutCancellationException) {
                Result.failure(ShellTimeoutException())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
