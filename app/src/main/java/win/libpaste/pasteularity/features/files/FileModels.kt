package win.libpaste.pasteularity.features.files

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Immutable
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isLink: Boolean,
    val sizeBytes: Long,
    val modifiedEpochSeconds: Long,
)

enum class SortKey(val label: String) { Name("Name"), Size("Size"), Date("Date") }

const val INTERNAL_STORAGE = "/sdcard"
const val DOWNLOADS = "/sdcard/Download"
const val OBB_ROOT = "/sdcard/Android/obb"
const val DATA_ROOT = "/sdcard/Android/data"

private const val MODE_TYPE_MASK = 0xF000
private const val MODE_DIRECTORY = 0x4000
private const val MODE_LINK = 0xA000

private val repeatedSlashes = Regex("/+")
private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US).withZone(ZoneId.systemDefault())
private val obbNamePattern = Regex("""^(?:main|patch)\.\d+\.(.+)\.obb$""")
private val packagePattern = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$""")
private val protectedPaths = setOf(
    "/",
    "/sdcard",
    "/storage",
    "/storage/emulated",
    "/storage/emulated/0",
    "/sdcard/Android",
    "/storage/emulated/0/Android",
    OBB_ROOT,
    DATA_ROOT,
    "/storage/emulated/0/Android/obb",
    "/storage/emulated/0/Android/data",
)

/** Parses one line of `stat -c '%f|%s|%Y|%n'`. Returns null for error lines from find. */
fun parseStatLine(line: String): FileEntry? {
    val parts = line.split('|', limit = 4)
    if (parts.size != 4) return null
    val mode = parts[0].toIntOrNull(16) ?: return null
    val size = parts[1].toLongOrNull() ?: return null
    val modified = parts[2].toLongOrNull() ?: return null
    val path = if ("//" in parts[3]) parts[3].replace(repeatedSlashes, "/") else parts[3]
    val name = path.substringAfterLast('/')
    if (name.isEmpty()) return null
    val type = mode and MODE_TYPE_MASK
    return FileEntry(
        name = name,
        path = path,
        isDirectory = type == MODE_DIRECTORY,
        isLink = type == MODE_LINK,
        sizeBytes = size,
        modifiedEpochSeconds = modified,
    )
}

fun sortAndFilter(
    entries: List<FileEntry>,
    key: SortKey,
    ascending: Boolean,
    filter: String,
    showHidden: Boolean,
): List<FileEntry> {
    val byKey: Comparator<FileEntry> = when (key) {
        SortKey.Name -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        SortKey.Size -> compareBy { it.sizeBytes }
        SortKey.Date -> compareBy { it.modifiedEpochSeconds }
    }
    val comparator = compareByDescending<FileEntry> { it.isDirectory }
        .then(if (ascending) byKey else byKey.reversed())
    return entries
        .filter { showHidden || !it.name.startsWith(".") }
        .filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }
        .sortedWith(comparator)
}

fun parentPath(path: String): String? {
    if (path == "/") return null
    return path.substringBeforeLast('/', "").ifEmpty { "/" }
}

fun joinPath(directory: String, name: String): String =
    if (directory.endsWith("/")) directory + name else "$directory/$name"

fun breadcrumbs(path: String): List<Pair<String, String>> {
    val crumbs = mutableListOf("/" to "/")
    var current = ""
    path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
        current += "/$segment"
        crumbs += segment to current
    }
    return crumbs
}

/** Returns an error to show, or null if [name] is a safe single path component. */
fun validateFileName(name: String): String? = when {
    name.isBlank() -> "Enter a name."
    '/' in name -> "Names cannot contain a slash."
    name == "." || name == ".." -> "That name is reserved."
    name.length > 255 -> "That name is too long."
    else -> null
}

fun isProtectedPath(path: String): Boolean = path.trimEnd('/').ifEmpty { "/" } in protectedPaths

fun isValidPackageName(name: String): Boolean = packagePattern.matches(name)

fun packageFromObbName(fileName: String): String? = obbNamePattern.find(fileName)?.groupValues?.get(1)

/** The name used when a file is copied into the folder it is already in. */
fun copyName(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) "${name.substring(0, dot)} copy${name.substring(dot)}" else "$name copy"
}

fun formatSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

fun formatDate(epochSeconds: Long): String = dateFormat.format(Instant.ofEpochSecond(epochSeconds))
