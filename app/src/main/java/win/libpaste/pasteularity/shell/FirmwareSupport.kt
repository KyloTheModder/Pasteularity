package win.libpaste.pasteularity.shell

const val NOT_SUPPORTED_MESSAGE = "Not supported on this firmware."

private val unsupportedPatterns = listOf(
    "inaccessible or not found",
    "command not found",
    ": not found",
    "unknown command",
    "unknown option",
    "can't find service",
    "does not exist",
    "permission denied",
    "securityexception",
    "not allowed",
)

fun looksUnsupported(output: String): Boolean {
    val lower = output.lowercase()
    return unsupportedPatterns.any { it in lower }
}

/** Runs a community-known Quest command and turns a firmware refusal into one plain message. */
suspend fun ShellBackend.firmwareCommand(cmd: String, timeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS): Result<String> {
    val result = exec(cmd, timeoutMs)
    val failure = result.exceptionOrNull()
    return if (failure is CommandFailedException && looksUnsupported(failure.output)) {
        Result.failure(UnsupportedFirmwareException())
    } else {
        result
    }
}

suspend fun ShellBackend.getProp(name: String): Result<String> = exec("getprop ${shellQuote(name)}")

/** Reads several properties in one shell session. getprop prints one line per property, empty or not. */
suspend fun ShellBackend.getProps(names: List<String>): Result<Map<String, String>> =
    exec(names.joinToString("; ") { "getprop ${shellQuote(it)}" }).map { output ->
        val lines = output.split('\n')
        names.withIndex().associate { (index, name) -> name to lines.getOrElse(index) { "" } }
    }

/** Sets a property and reads it back, because setprop does not always report a refusal. */
suspend fun ShellBackend.setPropVerified(name: String, value: String): Result<Unit> {
    firmwareCommand("setprop ${shellQuote(name)} ${shellQuote(value)}").onFailure {
        return Result.failure(it)
    }
    val current = getProp(name).getOrElse { return Result.failure(it) }
    return if (current == value) Result.success(Unit) else Result.failure(UnsupportedFirmwareException())
}

fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
