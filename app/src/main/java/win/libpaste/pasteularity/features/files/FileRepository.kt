package win.libpaste.pasteularity.features.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import win.libpaste.pasteularity.shell.CommandFailedException
import win.libpaste.pasteularity.shell.ShellBackend
import win.libpaste.pasteularity.shell.ShellException
import win.libpaste.pasteularity.shell.shellQuote

private const val LIST_TIMEOUT_MS = 30_000L
private const val LONG_TIMEOUT_MS = 6 * 60 * 60 * 1000L
private const val PROGRESS_INTERVAL_MS = 750L

class ProtectedPathException : ShellException("That folder is protected and cannot be changed.")

class FileRepository(private val shell: ShellBackend) {

    /** find exits non-zero if any child is unreadable, so partial results still count. */
    suspend fun list(path: String): Result<List<FileEntry>> {
        val command = "find -H ${shellQuote("$path/")} -mindepth 1 -maxdepth 1 -exec stat -c '%f|%s|%Y|%n' {} +"
        val result = shell.exec(command, LIST_TIMEOUT_MS)
        val failure = result.exceptionOrNull()
        if (failure != null && failure !is CommandFailedException) return Result.failure(failure)
        val output = result.getOrNull() ?: (failure as CommandFailedException).output
        val entries = withContext(Dispatchers.Default) { output.lineSequence().mapNotNull(::parseStatLine).toList() }
        return if (failure != null && entries.isEmpty()) {
            Result.failure(ShellException("Shell does not have access to this folder."))
        } else {
            Result.success(entries)
        }
    }

    suspend fun isDirectory(path: String): Boolean = shell.exec("[ -d ${shellQuote(path)} ]").isSuccess

    suspend fun createFolder(parent: String, name: String): Result<Unit> =
        shell.exec("mkdir -p -- ${shellQuote(joinPath(parent, name))}").map { }

    suspend fun rename(path: String, newName: String): Result<Unit> {
        if (isProtectedPath(path)) return Result.failure(ProtectedPathException())
        val target = joinPath(parentPath(path) ?: "/", newName)
        return shell.exec("mv -- ${shellQuote(path)} ${shellQuote(target)}").map { }
    }

    suspend fun delete(paths: List<String>): Result<Unit> {
        if (paths.any(::isProtectedPath)) return Result.failure(ProtectedPathException())
        val quoted = paths.joinToString(" ", transform = ::shellQuote)
        return shell.exec("rm -rf -- $quoted", LONG_TIMEOUT_MS).map { }
    }

    /** Where each source lands when pasted into [destination]. */
    fun pasteTargets(sources: List<String>, destination: String): List<Pair<String, String>> =
        sources.map { source ->
            val name = source.substringAfterLast('/')
            val sameFolder = parentPath(source) == destination
            source to joinPath(destination, if (sameFolder) copyName(name) else name)
        }

    suspend fun existing(paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        val script = paths.joinToString("; ") { "[ -e ${shellQuote(it)} ] && echo ${shellQuote(it)}" } + "; true"
        return shell.exec(script).getOrDefault("").lines().filter { it.isNotBlank() }
    }

    suspend fun copy(
        sources: List<String>,
        destination: String,
        onProgress: (Float?) -> Unit,
    ): Result<Unit> = coroutineScope {
        val pairs = pasteTargets(sources, destination)
        val total = sizeInKb(sources)
        val poller = launch {
            while (true) {
                delay(PROGRESS_INTERVAL_MS)
                if (total > 0) onProgress((sizeInKb(pairs.map { it.second }).toFloat() / total).coerceIn(0f, 1f))
            }
        }
        val script = pairs.joinToString(" && ") { (from, to) ->
            "cp -rf -- ${shellQuote(from)} ${shellQuote(to)}"
        }
        val result = shell.exec(script, LONG_TIMEOUT_MS).map { }
        poller.cancel()
        result
    }

    suspend fun move(sources: List<String>, destination: String): Result<Unit> {
        if (sources.any(::isProtectedPath)) return Result.failure(ProtectedPathException())
        val script = sources.joinToString(" && ") { source ->
            "mv -f -- ${shellQuote(source)} ${shellQuote(joinPath(destination, source.substringAfterLast('/')))}"
        }
        return shell.exec(script, LONG_TIMEOUT_MS).map { }
    }

    private suspend fun sizeInKb(paths: List<String>): Long {
        val quoted = paths.joinToString(" ", transform = ::shellQuote)
        val output = shell.exec("du -sk -- $quoted 2>/dev/null; true").getOrDefault("")
        return output.lines().sumOf { it.substringBefore('\t').trim().toLongOrNull() ?: 0L }
    }
}
