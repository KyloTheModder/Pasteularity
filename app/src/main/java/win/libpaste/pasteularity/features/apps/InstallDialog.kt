package win.libpaste.pasteularity.features.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import win.libpaste.pasteularity.features.files.DOWNLOADS
import win.libpaste.pasteularity.features.files.FilePickerDialog
import win.libpaste.pasteularity.features.files.FileRepository
import win.libpaste.pasteularity.features.files.PickMode
import win.libpaste.pasteularity.ui.components.ButtonStyle
import win.libpaste.pasteularity.ui.components.PstuButton
import win.libpaste.pasteularity.ui.theme.PstuColors
import win.libpaste.pasteularity.ui.theme.Spacing

private enum class InstallPick { Apk, Obb }

@Composable
fun InstallDialog(
    files: FileRepository,
    onInstall: (apk: String, obbFolder: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var apk by remember { mutableStateOf<String?>(null) }
    var obb by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<InstallPick?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PstuColors.SurfaceRaised,
        tonalElevation = 0.dp,
        title = { Text(text = "Install APK and OBB", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                PickedRow(label = "APK", value = apk, action = "Choose APK", onClick = { picking = InstallPick.Apk })
                PickedRow(
                    label = "OBB folder (optional)",
                    value = obb,
                    action = "Choose folder",
                    onClick = { picking = InstallPick.Obb },
                )
                Text(
                    text = "The OBB folder's contents are copied to Android/obb for this app after it installs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PstuColors.TextMuted,
                )
            }
        },
        confirmButton = {
            PstuButton(
                text = "Install",
                style = ButtonStyle.Primary,
                enabled = apk != null,
                onClick = {
                    apk?.let { onInstall(it, obb) }
                    onDismiss()
                },
            )
        },
        dismissButton = { PstuButton(text = "Cancel", style = ButtonStyle.Quiet, onClick = onDismiss) },
    )

    when (picking) {
        InstallPick.Apk -> FilePickerDialog(
            repository = files,
            title = "Choose an APK",
            startPath = DOWNLOADS,
            mode = PickMode.Files(setOf("apk"), multiple = false),
            onPicked = {
                apk = it.firstOrNull()
                picking = null
            },
            onDismiss = { picking = null },
        )
        InstallPick.Obb -> FilePickerDialog(
            repository = files,
            title = "Choose the OBB folder",
            startPath = DOWNLOADS,
            mode = PickMode.Folder,
            onPicked = {
                obb = it.firstOrNull()
                picking = null
            },
            onDismiss = { picking = null },
        )
        null -> Unit
    }
}

@Composable
private fun PickedRow(label: String, value: String?, action: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = value ?: "Not chosen",
                style = MaterialTheme.typography.bodyMedium,
                color = PstuColors.TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PstuButton(text = action, style = ButtonStyle.Outline, onClick = onClick)
    }
}
