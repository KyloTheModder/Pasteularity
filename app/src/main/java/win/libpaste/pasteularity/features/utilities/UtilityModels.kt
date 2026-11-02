package win.libpaste.pasteularity.features.utilities

import androidx.compose.runtime.Immutable

data class DeviceInfo(
    val model: String = "",
    val horizonVersion: String = "",
    val androidVersion: String = "",
    val batteryLevel: String = "",
    val batteryTemperature: String = "",
    val batteryStatus: String = "",
    val storageUsed: String = "",
    val storageTotal: String = "",
    val ipAddress: String = "",
)

enum class LogLevel(val code: Char, val label: String) {
    Verbose('V', "Verbose"),
    Debug('D', "Debug"),
    Info('I', "Info"),
    Warning('W', "Warning"),
    Error('E', "Error"),
}

@Immutable
data class LogEntry(val id: Long, val raw: String, val level: LogLevel?, val tag: String)

private fun skipSpaces(line: String, from: Int): Int {
    var i = from
    while (i < line.length && line[i] == ' ') i++
    return i
}

private fun skipWord(line: String, from: Int): Int {
    var i = from
    while (i < line.length && line[i] != ' ') i++
    return i
}

/**
 * Parses `logcat -v threadtime`: `MM-DD HH:MM:SS.mmm  PID  TID L TAG: message`. It scans by hand
 * because this runs for every line. Lines that do not match still show, without a tag.
 */
fun parseLogLine(id: Long, line: String): LogEntry {
    val unparsed = LogEntry(id, line, null, "")
    if (line.length < 3 || line[2] != '-' || !line[0].isDigit()) return unparsed
    var i = 0
    repeat(4) { i = skipSpaces(line, skipWord(line, i)) }
    if (i + 1 >= line.length || line[i + 1] != ' ') return unparsed
    val level = when (val code = line[i]) {
        'F' -> LogLevel.Error
        else -> LogLevel.entries.firstOrNull { it.code == code } ?: return unparsed
    }
    val tagStart = skipSpaces(line, i + 1)
    val separator = line.indexOf(": ", tagStart).takeIf { it >= 0 } ?: line.indexOf(':', tagStart)
    val tag = if (separator >= 0) line.substring(tagStart, separator).trimEnd() else ""
    return LogEntry(id, line, level, tag)
}

fun matchesLog(entry: LogEntry, tagFilter: String, minimum: LogLevel): Boolean {
    val levelOk = entry.level == null || entry.level.ordinal >= minimum.ordinal
    val tagOk = tagFilter.isBlank() || entry.tag.contains(tagFilter.trim(), ignoreCase = true)
    return levelOk && tagOk
}

/**
 * Recent log lines plus the subset that passes the current filter. New lines filter in constant
 * time, and the screen only hears about a change when a visible line actually arrived.
 */
class LogBuffer(private val capacity: Int) {
    private val all = ArrayDeque<LogEntry>()
    private val visible = ArrayDeque<LogEntry>()
    private var tagFilter = ""
    private var minimum = LogLevel.Info
    private var changed = false

    @Synchronized
    fun add(entry: LogEntry) {
        if (all.size == capacity && all.removeFirst() === visible.firstOrNull()) {
            visible.removeFirst()
            changed = true
        }
        all.addLast(entry)
        if (matchesLog(entry, tagFilter, minimum)) {
            visible.addLast(entry)
            changed = true
        }
    }

    @Synchronized
    fun setFilter(tag: String, level: LogLevel) {
        tagFilter = tag
        minimum = level
        visible.clear()
        all.filterTo(visible) { matchesLog(it, tag, level) }
        changed = true
    }

    @Synchronized
    fun clear() {
        all.clear()
        visible.clear()
        changed = true
    }

    /** The visible lines, or null when nothing changed since the last call. */
    @Synchronized
    fun takeChanges(): List<LogEntry>? {
        if (!changed) return null
        changed = false
        return visible.toList()
    }

    @Synchronized
    fun visibleLines(): List<LogEntry> = visible.toList()
}

/** Pulls `level:` style values out of `dumpsys battery`. */
fun parseBatteryField(dump: String, field: String): String? =
    dump.lines().firstOrNull { it.trim().startsWith("$field:") }?.substringAfter(':')?.trim()

fun formatBatteryTemperature(tenths: String?): String =
    tenths?.toIntOrNull()?.let { "${it / 10}.${it % 10} C" }.orEmpty()

fun batteryStatusLabel(code: String?): String = when (code) {
    "2" -> "Charging"
    "3" -> "Discharging"
    "4" -> "Not charging"
    "5" -> "Full"
    else -> ""
}

/** Reads the data row of `df -h <path>`: filesystem, size, used, available, percent, mount. */
fun parseDfUsage(output: String): Pair<String, String>? {
    val parts = output.lines().lastOrNull { it.isNotBlank() }?.trim()?.split(Regex("\\s+")) ?: return null
    if (parts.size < 4) return null
    return parts[2] to parts[1]
}

/** Reads the first IPv4 address from `ip -4 addr show wlan0`. */
fun parseIpAddress(output: String): String? =
    Regex("""inet (\d+\.\d+\.\d+\.\d+)""").find(output)?.groupValues?.get(1)
