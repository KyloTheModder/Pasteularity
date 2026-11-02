package win.libpaste.pasteularity.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import win.libpaste.pasteularity.shell.ConnectionStatus
import win.libpaste.pasteularity.ui.theme.PstuColors
import win.libpaste.pasteularity.ui.theme.PstuShape
import win.libpaste.pasteularity.ui.theme.Spacing
import win.libpaste.pasteularity.ui.theme.TouchTarget

@Composable
fun StatusChip(status: ConnectionStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val connected = status is ConnectionStatus.Connected
    val icon = when (status) {
        is ConnectionStatus.Connected -> Icons.Outlined.Link
        ConnectionStatus.Connecting -> Icons.Outlined.Sync
        ConnectionStatus.NotPaired, ConnectionStatus.Disconnected -> Icons.Outlined.LinkOff
    }
    val label = when (status) {
        is ConnectionStatus.Connected -> "Connected"
        ConnectionStatus.Connecting -> "Connecting"
        ConnectionStatus.NotPaired -> "Not paired"
        ConnectionStatus.Disconnected -> "Disconnected"
    }
    val content = if (connected) PstuColors.OnAccent else PstuColors.Text
    Row(
        modifier = modifier
            .heightIn(min = TouchTarget)
            .clip(PstuShape)
            .background(if (connected) PstuColors.Accent else PstuColors.Surface)
            .border(1.dp, if (connected) PstuColors.Accent else PstuColors.Outline, PstuShape)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.M),
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}
