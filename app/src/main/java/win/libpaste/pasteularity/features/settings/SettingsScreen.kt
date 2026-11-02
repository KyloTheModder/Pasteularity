package win.libpaste.pasteularity.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import win.libpaste.pasteularity.BuildConfig
import win.libpaste.pasteularity.shell.BackendPreference
import win.libpaste.pasteularity.shell.BytezukuState
import win.libpaste.pasteularity.shell.ConnectionStatus
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

class SettingsActions(
    val onPreference: (BackendPreference) -> Unit,
    val onReconnect: () -> Unit,
    val onSetup: () -> Unit,
    val onForget: () -> Unit,
    val onAllowBytezuku: () -> Unit,
)

@Composable
fun SettingsScreen(
    status: ConnectionStatus,
    preference: BackendPreference,
    paired: Boolean,
    bytezuku: BytezukuState,
    reconnecting: Boolean,
    actions: SettingsActions,
) {
    var confirmForget by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = Spacing.L, end = Spacing.L, bottom = Spacing.L),
        verticalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Panel(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("Connection")
            Text(text = statusText(status), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                PstuButton(
                    text = "Reconnect",
                    icon = Icons.Outlined.Refresh,
                    style = ButtonStyle.Primary,
                    loading = reconnecting,
                    onClick = actions.onReconnect,
                )
                PstuButton(text = "Open setup guide", icon = Icons.Outlined.Info, onClick = actions.onSetup)
            }
        }
        Panel(modifier = Modifier.fillMaxWidth()) {
            SettingRow(
                title = "Shell backend",
                description = "Automatic uses Wireless debugging when it is connected and ByteZuku otherwise.",
            ) {
                ChoiceRow(
                    choices = BackendPreference.entries.map { Choice(it, it.label) },
                    selected = preference,
                    onSelect = actions.onPreference,
                )
            }
        }
        Panel(modifier = Modifier.fillMaxWidth()) {
            SettingRow(
                title = "Wireless debugging pairing",
                description = if (paired) {
                    "This headset is paired. Forgetting makes you pair again. To fully revoke access, also remove it under Wireless debugging in Android Settings."
                } else {
                    "Not paired yet. The setup guide walks you through it."
                },
            ) {
                if (paired) {
                    PstuButton(
                        text = "Forget pairing",
                        icon = Icons.Outlined.LinkOff,
                        style = ButtonStyle.Danger,
                        onClick = { confirmForget = true },
                    )
                }
            }
        }
        Panel(modifier = Modifier.fillMaxWidth()) {
            SettingRow(
                title = "ByteZuku",
                description = when (bytezuku) {
                    BytezukuState.NotRunning -> "Not running. Start it from the ByteZuku app to use it here."
                    BytezukuState.NeedsPermission -> "Running, but Pasteularity does not have permission yet."
                    BytezukuState.Ready -> "Running, and Pasteularity has permission."
                },
            ) {
                if (bytezuku == BytezukuState.NeedsPermission) {
                    PstuButton(text = "Allow ByteZuku", onClick = actions.onAllowBytezuku)
                }
            }
        }
        Panel(modifier = Modifier.fillMaxWidth()) {
            SectionTitle("About")
            Text(
                text = "Pasteularity ${BuildConfig.VERSION_NAME}. Runs with shell permissions, without root.",
                style = MaterialTheme.typography.bodyLarge,
                color = PstuColors.TextMuted,
            )
        }
    }

    if (confirmForget) {
        ConfirmDialog(
            title = "Forget pairing?",
            message = "Pasteularity disconnects and you will need a new pairing code to reconnect.",
            confirmLabel = "Forget",
            onConfirm = actions.onForget,
            onDismiss = { confirmForget = false },
        )
    }
}

private fun statusText(status: ConnectionStatus): String = when (status) {
    is ConnectionStatus.Connected -> "Connected through ${status.kind.label}."
    ConnectionStatus.Connecting -> "Connecting."
    ConnectionStatus.NotPaired -> "Not paired. Run the setup guide to connect."
    ConnectionStatus.Disconnected -> "Disconnected. Check that Wireless debugging is on and the headset is on Wi-Fi."
}
