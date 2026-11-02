package win.libpaste.pasteularity.features.utilities

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import win.libpaste.pasteularity.ui.components.ButtonStyle
import win.libpaste.pasteularity.ui.components.Choice
import win.libpaste.pasteularity.ui.components.ChoiceRow
import win.libpaste.pasteularity.ui.components.LoadingState
import win.libpaste.pasteularity.ui.components.MessageState
import win.libpaste.pasteularity.ui.components.OutputList
import win.libpaste.pasteularity.ui.components.Panel
import win.libpaste.pasteularity.ui.components.PstuButton
import win.libpaste.pasteularity.ui.components.PstuTextField
import win.libpaste.pasteularity.ui.components.SectionTitle
import win.libpaste.pasteularity.ui.components.SettingRow
import win.libpaste.pasteularity.ui.theme.MonoStyle
import win.libpaste.pasteularity.ui.theme.PstuColors
import win.libpaste.pasteularity.ui.theme.PstuShape
import win.libpaste.pasteularity.ui.theme.Spacing

class UtilitiesActions(
    val onTab: (UtilityTab) -> Unit,
    val onRefreshDevice: () -> Unit,
    val onScreenshot: () -> Unit,
    val onRecordSeconds: (Int) -> Unit,
    val onStartRecording: () -> Unit,
    val onStopRecording: () -> Unit,
    val onOpenCaptures: () -> Unit,
    val onStartLog: () -> Unit,
    val onStopLog: () -> Unit,
    val onTogglePause: () -> Unit,
    val onClearLog: () -> Unit,
    val onLogTag: (String) -> Unit,
    val onLogLevel: (LogLevel) -> Unit,
    val onExportLog: () -> Unit,
    val onRefreshWifi: () -> Unit,
    val onCopy: (String) -> Unit,
    val onApplyTweak: (Tweak, TweakOption) -> Unit,
    val onTrimCaches: () -> Unit,
)

private val logStyle = MonoStyle.copy(color = PstuColors.Text)
private val logErrorStyle = MonoStyle.copy(color = PstuColors.Danger)

private fun logLineStyle(entry: LogEntry): TextStyle = if (entry.level == LogLevel.Error) logErrorStyle else logStyle

/** The log arrives as [State] so that only the log tab reads it, not this whole screen. */
@Composable
fun UtilitiesScreen(
    state: UtilitiesState,
    logControls: State<LogcatControls>,
    logLines: State<List<LogEntry>>,
    actions: UtilitiesActions,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = Spacing.L, end = Spacing.L, bottom = Spacing.L),
        verticalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        ChoiceRow(
            choices = UtilityTab.entries.map { Choice(it, it.label) },
            selected = state.tab,
            onSelect = actions.onTab,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (state.tab) {
                UtilityTab.Device -> DeviceTab(state.device, actions)
                UtilityTab.Capture -> CaptureTab(state.capture, actions)
                UtilityTab.Logcat -> LogcatTab(logControls.value, logLines, actions)
                UtilityTab.WifiAdb -> WifiAdbTab(state.wifi, actions)
                UtilityTab.Tweaks -> TweaksTab(state, actions)
            }
        }
    }
}

@Composable
private fun DeviceTab(device: DeviceInfo?, actions: UtilitiesActions) {
    if (device == null) {
        LoadingState("Reading device info")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
            InfoPanel(
                title = "Headset",
                rows = listOf(
                    "Model" to device.model,
                    "Horizon OS" to device.horizonVersion,
                    "Android" to device.androidVersion,
                    "IP address" to device.ipAddress,
                ),
                modifier = Modifier.weight(1f),
            )
            InfoPanel(
                title = "Power and storage",
                rows = listOf(
                    "Battery" to device.batteryLevel,
                    "Status" to device.batteryStatus,
                    "Temperature" to device.batteryTemperature,
                    "Storage used" to listOf(device.storageUsed, device.storageTotal)
                        .filter { it.isNotEmpty() }.joinToString(" of "),
                ),
                modifier = Modifier.weight(1f),
            )
        }
        PstuButton(text = "Refresh", icon = Icons.Outlined.Refresh, onClick = actions.onRefreshDevice)
    }
}

@Composable
private fun InfoPanel(title: String, rows: List<Pair<String, String>>, modifier: Modifier) {
    Panel(modifier = modifier) {
        SectionTitle(title)
        rows.forEach { (label, value) ->
            Row {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = PstuColors.TextMuted,
                    modifier = Modifier.width(176.dp),
                )
                Text(
                    text = value.ifEmpty { "Not available" },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CaptureTab(capture: CaptureState, actions: UtilitiesActions) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Panel(modifier = Modifier.fillMaxWidth()) {
            SettingRow(
                title = "Screenshot",
                description = "Saved to $OUTPUT_ROOT/screenshots. Some firmware only captures 2D panels.",
            ) {
                PstuButton(
                    text = "Take screenshot",
                    icon = Icons.Outlined.CameraAlt,
                    style = ButtonStyle.Primary,
                    loading = capture.screenshotBusy,
                    onClick = actions.onScreenshot,
                )
            }
        }
        Panel(modifier = Modifier.fillMaxWidth()) {
            SettingRow(
                title = "Screen recording",
                description = "Saved to $OUTPUT_ROOT/recordings. Android caps a single clip at three minutes.",
            ) {
                ChoiceRow(
                    choices = listOf(Choice(30, "30 s"), Choice(60, "1 min"), Choice(180, "3 min")),
                    selected = capture.recordSeconds,
                    enabled = !capture.recording,
                    onSelect = actions.onRecordSeconds,
                )
                if (capture.recording) {
                    PstuButton(
                        text = "Stop recording",
                        icon = Icons.Outlined.Stop,
                        style = ButtonStyle.Danger,
                        onClick = actions.onStopRecording,
                    )
                } else {
                    PstuButton(
                        text = "Start recording",
                        icon = Icons.Outlined.Videocam,
                        style = ButtonStyle.Primary,
                        onClick = actions.onStartRecording,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PstuButton(text = "Open in Files", icon = Icons.Outlined.Folder, onClick = actions.onOpenCaptures)
            capture.lastSaved?.let {
                Text(text = "Last saved: $it", style = MaterialTheme.typography.bodyMedium, color = PstuColors.TextMuted)
            }
        }
    }
}

@Composable
private fun LogcatTab(log: LogcatControls, lines: State<List<LogEntry>>, actions: UtilitiesActions) {
    val hasLines by remember(lines) { derivedStateOf { lines.value.isNotEmpty() } }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalAlignment = Alignment.CenterVertically) {
            if (log.running) {
                PstuButton(text = "Stop", icon = Icons.Outlined.Stop, style = ButtonStyle.Danger, onClick = actions.onStopLog)
                PstuButton(
                    text = if (log.paused) "Resume" else "Pause",
                    icon = if (log.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    onClick = actions.onTogglePause,
                )
            } else {
                PstuButton(text = "Start", icon = Icons.Outlined.PlayArrow, style = ButtonStyle.Primary, onClick = actions.onStartLog)
            }
            PstuTextField(
                value = log.tagFilter,
                onValueChange = actions.onLogTag,
                label = "Filter by tag",
                modifier = Modifier.weight(1f),
            )
            PstuButton(text = "Clear", icon = Icons.Outlined.DeleteSweep, style = ButtonStyle.Quiet, onClick = actions.onClearLog)
            PstuButton(
                text = "Export",
                icon = Icons.Outlined.FileUpload,
                enabled = hasLines,
                loading = log.exporting,
                onClick = actions.onExportLog,
            )
        }
        ChoiceRow(
            choices = LogLevel.entries.map { Choice(it, it.label) },
            selected = log.level,
            onSelect = actions.onLogLevel,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(PstuShape)
                .background(PstuColors.Surface),
        ) {
            if (hasLines) {
                LogLines(lines, selectable = !log.running || log.paused)
            } else {
                MessageState(
                    icon = Icons.AutoMirrored.Outlined.Subject,
                    title = if (log.running) "Waiting for matching lines" else "Logcat is stopped",
                    message = if (log.running) "Lower the level or clear the filter to see more." else "Select Start to read the system log.",
                )
            }
        }
    }
}

@Composable
private fun LogLines(lines: State<List<LogEntry>>, selectable: Boolean) {
    OutputList(
        lines = lines.value,
        lineKey = LogEntry::id,
        lineText = LogEntry::raw,
        lineStyle = ::logLineStyle,
        selectable = selectable,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun WifiAdbTab(wifi: WifiAdbState, actions: UtilitiesActions) {
    when {
        wifi.loading -> LoadingState("Reading network info")
        wifi.address.isEmpty() -> MessageState(
            icon = Icons.Outlined.WifiOff,
            title = "Wi-Fi ADB is not available",
            message = "Connect to Wi-Fi and turn on Wireless debugging in Android Settings.",
            action = { PstuButton(text = "Check again", icon = Icons.Outlined.Refresh, onClick = actions.onRefreshWifi) },
        )
        else -> Panel(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("Connect from a PC")
            Text(
                text = "Pair the PC first if it has never connected to this headset, then run this command on it.",
                style = MaterialTheme.typography.bodyLarge,
                color = PstuColors.TextMuted,
            )
            Text(text = "adb connect ${wifi.address}", style = MonoStyle)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                PstuButton(
                    text = "Copy command",
                    icon = Icons.Outlined.ContentCopy,
                    onClick = { actions.onCopy("adb connect ${wifi.address}") },
                )
                PstuButton(text = "Refresh", icon = Icons.Outlined.Refresh, style = ButtonStyle.Quiet, onClick = actions.onRefreshWifi)
            }
        }
    }
}

@Composable
private fun TweaksTab(state: UtilitiesState, actions: UtilitiesActions) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        tweaks.forEach { tweak ->
            val current = state.tweakValues[tweak.title]
            val selected = current?.let { value -> tweak.options.firstOrNull { it.matches(value) } }
            Panel(modifier = Modifier.fillMaxWidth()) {
                SettingRow(title = tweak.title, description = tweak.description, value = selected?.label) {
                    ChoiceRow(
                        choices = tweak.options.map { Choice(it, it.label) },
                        selected = selected,
                        enabled = !state.tweaksBusy,
                        onSelect = { actions.onApplyTweak(tweak, it) },
                    )
                }
            }
        }
        Panel(modifier = Modifier.fillMaxWidth()) {
            SettingRow(
                title = "Trim app caches",
                description = "Asks Android to free cache space across all apps. No app data is removed.",
            ) {
                PstuButton(
                    text = "Trim caches",
                    icon = Icons.Outlined.CleaningServices,
                    loading = state.trimmingCaches,
                    onClick = actions.onTrimCaches,
                )
            }
        }
    }
}
