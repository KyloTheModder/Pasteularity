package win.libpaste.pasteularity.shell

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class ScriptedShell(private val output: String) : ShellBackend {
    var command = ""
    override val isConnected: StateFlow<Boolean> = MutableStateFlow(true)

    override suspend fun exec(cmd: String, timeoutMs: Long): Result<String> {
        command = cmd
        return Result.success(output)
    }

    override fun stream(cmd: String): Flow<ShellEvent> = emptyFlow()
}

class ShellLogicTest {
    @Test
    fun getPropsMapsEachLineToItsProperty() = runTest {
        val shell = ScriptedShell("90\n\neureka")
        val values = shell.getProps(listOf("a", "b", "c")).getOrThrow()
        assertEquals(mapOf("a" to "90", "b" to "", "c" to "eureka"), values)
        assertEquals("getprop 'a'; getprop 'b'; getprop 'c'", shell.command)
    }

    @Test
    fun getPropsKeepsPropertiesWhoseValuesAreEmptyAtTheEnd() = runTest {
        val values = ScriptedShell("90").getProps(listOf("a", "b")).getOrThrow()
        assertEquals(mapOf("a" to "90", "b" to ""), values)
    }

    @Test
    fun quoteWrapsPlainValues() {
        assertEquals("'hello world'", shellQuote("hello world"))
    }

    @Test
    fun quoteNeutralizesInjectionAttempts() {
        assertEquals("'a'\\''; rm -rf /; echo '\\'''", shellQuote("a'; rm -rf /; echo '"))
        assertEquals("'\$(reboot)'", shellQuote("\$(reboot)"))
        assertEquals("'`reboot`'", shellQuote("`reboot`"))
    }

    @Test
    fun wrapQuotesTheCommandAndEchoesExitCode() {
        val wrapped = ShellProtocol.wrap("echo 'hi'")
        assertTrue(wrapped.startsWith("sh -c 'echo '\\''hi'\\''' 2>&1; echo "))
        assertTrue(wrapped.endsWith("${ShellProtocol.marker}\$?"))
    }

    @Test
    fun parseLineFindsExitOnItsOwnLine() {
        assertEquals(listOf(ShellEvent.Exit(3)), ShellProtocol.parseLine("${ShellProtocol.marker}3"))
    }

    @Test
    fun parseLineSplitsOutputWithNoTrailingNewline() {
        assertEquals(
            listOf(ShellEvent.Line("partial"), ShellEvent.Exit(0)),
            ShellProtocol.parseLine("partial${ShellProtocol.marker}0"),
        )
    }

    @Test
    fun parseLineLeavesOrdinaryOutputAlone() {
        assertEquals(listOf(ShellEvent.Line("__PSTU_EXIT__0")), ShellProtocol.parseLine("__PSTU_EXIT__0"))
    }

    @Test
    fun unsupportedOutputIsRecognized() {
        assertTrue(looksUnsupported("/system/bin/sh: screenrecord: inaccessible or not found"))
        assertTrue(looksUnsupported("java.lang.SecurityException: Permission Denial"))
        assertFalse(looksUnsupported("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"))
    }
}
