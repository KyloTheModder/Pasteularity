package win.libpaste.pasteularity.shell

/** Single-quotes [value] so the shell treats it as one literal word, whatever it contains. */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
