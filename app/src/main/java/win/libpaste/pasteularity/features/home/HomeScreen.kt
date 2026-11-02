package win.libpaste.pasteularity.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import win.libpaste.pasteularity.ui.components.ActionCard
import win.libpaste.pasteularity.ui.components.ButtonStyle
import win.libpaste.pasteularity.ui.components.Choice
import win.libpaste.pasteularity.ui.components.ChoiceRow
import win.libpaste.pasteularity.ui.components.ConfirmDialog
import win.libpaste.pasteularity.ui.components.Panel
import win.libpaste.pasteularity.ui.components.PstuButton
import win.libpaste.pasteularity.ui.components.SectionTitle
import win.libpaste.pasteularity.ui.components.SettingRow
import win.libpaste.pasteularity.ui.theme.PstuColors
import win.libpaste.pasteularity.ui.theme.Spacing

class HomeActions(
    val onOpenSettings: () -> Unit,
    val onOpenPage: (AndroidSettingsPage) -> Unit,
    val onDismissPages: () -> Unit,
    val onReboot: (RebootMode) -> Unit,
    val onProximity: (Boolean) -> Unit,
    val onToggleAdvanced: () -> Unit,
    val onDiscord: () -> Unit,
)

@Composable
fun HomeScreen(state: HomeState, connected: Boolean, actions: HomeActions) {
    var pendingReboot by remember { mutableStateOf<RebootMode?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = Spacing.L, end = Spacing.L, bottom = Spacing.L),
        verticalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
            ActionCard(
                icon = Icons.Outlined.Settings,
                title = "Open Android Settings",
                description = "The Android settings screens that Horizon OS hides.",
                onClick = actions.onOpenSettings,
                modifier = Modifier.weight(1f),
            )
            ActionCard(
                icon = Icons.Outlined.RestartAlt,
                title = "Reboot",
                description = if (connected) "Restart the headset." else "Connect first to reboot from here.",
                loading = state.rebooting,
                onClick = { if (connected) pendingReboot = RebootMode.Normal },
                modifier = Modifier.weight(1f),
            )
        }

        Panel(modifier = Modifier.fillMaxWidth()) {
            SettingRow(
                title = "Proximity sensor",
                description = "Turn it off to keep the display awake when the headset is not on your face. " +
                    "It turns back on after a reboot. Horizon OS does not report this setting, " +
                    "so this shows what Pasteularity last set.",
                value = if (state.proximityDisabled) "Off" else "On",
            ) {
                ChoiceRow(
                    choices = listOf(Choice(false, "On"), Choice(true, "Off")),
                    selected = state.proximityDisabled,
                    enabled = connected && !state.proximityBusy,
                    onSelect = actions.onProximity,
                )
            }
        }

        Panel(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("Advanced")
            PstuButton(
                text = if (state.advancedOpen) "Hide advanced reboot options" else "Show advanced reboot options",
                icon = if (state.advancedOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                style = ButtonStyle.Quiet,
                onClick = actions.onToggleAdvanced,
            )
            if (state.advancedOpen) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    PstuButton(
                        text = RebootMode.Bootloader.label,
                        style = ButtonStyle.Danger,
                        enabled = connected,
                        onClick = { pendingReboot = RebootMode.Bootloader },
                    )
                    PstuButton(
                        text = RebootMode.Recovery.label,
                        style = ButtonStyle.Danger,
                        enabled = connected,
                        onClick = { pendingReboot = RebootMode.Recovery },
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PstuButton(text = "Discord", icon = Icons.Outlined.Forum, onClick = actions.onDiscord)
        }
    }

    state.settingsPages?.let { pages ->
        AndroidSettingsDialog(pages, actions.onOpenPage, actions.onDismissPages)
    }

    pendingReboot?.let { mode ->
        ConfirmDialog(
            title = "${mode.label}?",
            message = if (mode == RebootMode.Normal) {
                "Anything open on the headset closes."
            } else {
                "The headset leaves Horizon OS. Use the volume and power buttons on the headset to get back."
            },
            confirmLabel = mode.label,
            onConfirm = { actions.onReboot(mode) },
            onDismiss = { pendingReboot = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AndroidSettingsDialog(
    pages: List<AndroidSettingsPage>,
    onOpen: (AndroidSettingsPage) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 760.dp),
        containerColor = PstuColors.SurfaceRaised,
        tonalElevation = 0.dp,
        title = { Text(text = "Android Settings", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.M),
            ) {
                Text(
                    text = "Meta locks the main Android Settings page on this firmware, so choose a section to open it directly.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                    verticalArrangement = Arrangement.spacedBy(Spacing.S),
                ) {
                    pages.forEach { page ->
                        PstuButton(text = page.label, style = ButtonStyle.Outline, onClick = { onOpen(page) })
                    }
                }
            }
        },
        confirmButton = { PstuButton(text = "Close", style = ButtonStyle.Quiet, onClick = onDismiss) },
    )
}
