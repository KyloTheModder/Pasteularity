package win.libpaste.pasteularity.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import win.libpaste.pasteularity.features.apps.parsePackagePaths
import win.libpaste.pasteularity.features.display.PROP_REFRESH
import win.libpaste.pasteularity.features.display.isValidPropValue
import win.libpaste.pasteularity.features.display.limitsFor
import win.libpaste.pasteularity.features.display.validateTextureSize
import win.libpaste.pasteularity.features.utilities.LogBuffer
import win.libpaste.pasteularity.features.utilities.LogLevel
import win.libpaste.pasteularity.features.utilities.batteryStatusLabel
import win.libpaste.pasteularity.features.utilities.formatBatteryTemperature
import win.libpaste.pasteularity.features.utilities.matchesLog
import win.libpaste.pasteularity.features.utilities.parseBatteryField
import win.libpaste.pasteularity.features.utilities.parseDfUsage
import win.libpaste.pasteularity.features.utilities.parseIpAddress
import win.libpaste.pasteularity.features.utilities.parseLogLine

class FeatureParsingTest {
    @Test
    fun batteryDumpIsRead() {
        val dump = "Current Battery Service state:\n  status: 2\n  level: 83\n  temperature: 312\n"
        assertEquals("83", parseBatteryField(dump, "level"))
        assertEquals("31.2 C", formatBatteryTemperature(parseBatteryField(dump, "temperature")))
        assertEquals("Charging", batteryStatusLabel(parseBatteryField(dump, "status")))
        assertNull(parseBatteryField(dump, "voltage"))
    }

    @Test
    fun dfOutputGivesUsedAndTotal() {
        val output = "Filesystem      Size  Used Avail Use% Mounted on\n/dev/fuse       108G   41G   67G  38% /storage/emulated\n"
        assertEquals("41G" to "108G", parseDfUsage(output))
        assertNull(parseDfUsage(""))
    }

    @Test
    fun ipAddressIsFound() {
        val output = "47: wlan0: <UP>\n    inet 192.168.1.42/24 brd 192.168.1.255 scope global wlan0\n"
        assertEquals("192.168.1.42", parseIpAddress(output))
        assertNull(parseIpAddress("Device \"wlan0\" does not exist."))
    }

    @Test
    fun logLinesParseAndFilter() {
        val entry = parseLogLine(1, "10-30 12:00:01.123  1234  1250 W VrApi   : FPS=72")
        assertEquals(LogLevel.Warning, entry.level)
        assertEquals("VrApi", entry.tag)
        assertTrue(matchesLog(entry, "vrapi", LogLevel.Info))
        assertFalse(matchesLog(entry, "other", LogLevel.Info))
        assertFalse(matchesLog(entry, "", LogLevel.Error))
    }

    @Test
    fun unparsedLogLinesStillShow() {
        val entry = parseLogLine(2, "--------- beginning of main")
        assertNull(entry.level)
        assertTrue(matchesLog(entry, "", LogLevel.Error))
    }

    @Test
    fun logParsingHandlesRealWorldShapes() {
        val padded = parseLogLine(3, "11-01 23:26:27.802  1544  3708 W ActivityTaskManager: Permission Denial: starting Intent")
        assertEquals(LogLevel.Warning, padded.level)
        assertEquals("ActivityTaskManager", padded.tag)
        val fatal = parseLogLine(4, "11-01 22:57:04.344 10645 10645 F libc    : Fatal signal 6")
        assertEquals(LogLevel.Error, fatal.level)
        assertEquals("libc", fatal.tag)
        val noMessage = parseLogLine(5, "11-01 22:57:04.344 10645 10645 I Tag:")
        assertEquals("Tag", noMessage.tag)
        assertNull(parseLogLine(6, "").level)
        assertNull(parseLogLine(7, "11-01 22:57:04.344").level)
    }

    private fun logLine(id: Long, level: Char, tag: String = "VrApi") =
        parseLogLine(id, "11-01 23:00:00.000  1000  1000 $level $tag: line $id")

    @Test
    fun logBufferDropsTheOldestLineAtCapacity() {
        val buffer = LogBuffer(capacity = 3)
        (1L..5L).forEach { buffer.add(logLine(it, 'I')) }
        assertEquals(listOf(3L, 4L, 5L), buffer.visibleLines().map { it.id })
    }

    @Test
    fun logBufferKeepsFilteredLinesForWhenTheFilterChanges() {
        val buffer = LogBuffer(capacity = 10)
        buffer.add(logLine(1, 'D'))
        buffer.add(logLine(2, 'E'))
        assertEquals(listOf(2L), buffer.visibleLines().map { it.id })
        buffer.setFilter("", LogLevel.Verbose)
        assertEquals(listOf(1L, 2L), buffer.visibleLines().map { it.id })
        buffer.setFilter("nomatch", LogLevel.Verbose)
        assertTrue(buffer.visibleLines().isEmpty())
    }

    @Test
    fun logBufferOnlyReportsChangesWhenAVisibleLineArrives() {
        val buffer = LogBuffer(capacity = 10)
        buffer.add(logLine(1, 'I'))
        assertEquals(1, buffer.takeChanges()?.size)
        assertNull(buffer.takeChanges())
        buffer.add(logLine(2, 'D'))
        assertNull(buffer.takeChanges())
        buffer.add(logLine(3, 'W'))
        assertEquals(2, buffer.takeChanges()?.size)
    }

    @Test
    fun logBufferKeepsVisibleLinesInStepWhenHiddenOnesAreDropped() {
        val buffer = LogBuffer(capacity = 2)
        buffer.add(logLine(1, 'D'))
        buffer.add(logLine(2, 'I'))
        buffer.add(logLine(3, 'I'))
        assertEquals(listOf(2L, 3L), buffer.visibleLines().map { it.id })
        buffer.add(logLine(4, 'D'))
        assertEquals(listOf(3L), buffer.visibleLines().map { it.id })
    }

    @Test
    fun packagePathsIncludeSplits() {
        assertEquals(
            listOf("/data/app/x/base.apk", "/data/app/x/split.apk"),
            parsePackagePaths("package:/data/app/x/base.apk\npackage:/data/app/x/split.apk\n"),
        )
    }

    @Test
    fun textureSizesAreRangeChecked() {
        assertNull(validateTextureSize("1440"))
        assertTrue(validateTextureSize("10") != null)
        assertTrue(validateTextureSize("99999") != null)
        assertTrue(validateTextureSize("") != null)
    }

    @Test
    fun savedProfilesCannotCarryNonNumericValues() {
        assertTrue(isValidPropValue(PROP_REFRESH, "90"))
        assertFalse(isValidPropValue(PROP_REFRESH, "90; reboot"))
        assertFalse(isValidPropValue("persist.sys.anything", "1"))
    }

    @Test
    fun refreshRatesFollowTheHeadset() {
        assertEquals(listOf(72, 90, 120), limitsFor("eureka").refreshRates)
        assertEquals(listOf(60, 72, 90, 120), limitsFor("unknown").refreshRates)
    }
}
