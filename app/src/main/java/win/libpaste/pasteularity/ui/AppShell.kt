package win.libpaste.pasteularity.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import win.libpaste.pasteularity.shell.ConnectionStatus
import win.libpaste.pasteularity.ui.components.StatusChip
import win.libpaste.pasteularity.ui.theme.PstuColors
import win.libpaste.pasteularity.ui.theme.PstuShape
import win.libpaste.pasteularity.ui.theme.Spacing

@Composable
fun AppShell(
    destination: Destination,
    status: ConnectionStatus,
    onNavigate: (Destination) -> Unit,
    onStatusClick: () -> Unit,
    snackbarHostState: SnackbarHostState,
    content: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        AppRail(destination, onNavigate)
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.L, end = Spacing.L, top = Spacing.M, bottom = Spacing.S),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(status = status, onClick = onStatusClick)
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(Spacing.L),
            ) { data ->
                val isError = (data.visuals as? PstuSnackbarVisuals)?.isError == true
                Snackbar(
                    containerColor = PstuColors.SurfaceRaised,
                    contentColor = if (isError) PstuColors.Danger else PstuColors.Text,
                    shape = PstuShape,
                ) {
                    Text(text = data.visuals.message, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

class PstuSnackbarVisuals(override val message: String, val isError: Boolean) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration = SnackbarDuration.Short
}

@Composable
private fun AppRail(selected: Destination, onNavigate: (Destination) -> Unit) {
    Column(
        modifier = Modifier
            .width(112.dp)
            .fillMaxHeight()
            .background(PstuColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.S, vertical = Spacing.M),
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "PSTU",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = Spacing.S),
        )
        Destination.entries.forEach { item ->
            val isSelected = item == selected
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(PstuShape)
                    .background(if (isSelected) PstuColors.SurfaceRaised else Color.Transparent)
                    .clickable { onNavigate(item) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = if (isSelected) PstuColors.Accent else PstuColors.TextMuted,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) PstuColors.Text else PstuColors.TextMuted,
                )
            }
        }
    }
}
