package win.libpaste.pasteularity.shell

import java.util.UUID

/**
 * adbd's plain shell service does not report exit codes, so every command echoes its own
 * after a marker. The marker is random per process so command output cannot fake it.
 */
object ShellProtocol {
    val marker: String = "__PSTU_${UUID.randomUUID().toString().replace("-", "")}__"

    fun wrap(cmd: String): String = "sh -c ${shellQuote(cmd)} 2>&1; echo $marker\$?"

    fun parseLine(line: String): List<ShellEvent> {
        val index = line.lastIndexOf(marker)
        if (index < 0) return listOf(ShellEvent.Line(line))
        val code = line.substring(index + marker.length).trim().toIntOrNull()
            ?: return listOf(ShellEvent.Line(line))
        return if (index == 0) {
            listOf(ShellEvent.Exit(code))
        } else {
            listOf(ShellEvent.Line(line.substring(0, index)), ShellEvent.Exit(code))
        }
    }
}
