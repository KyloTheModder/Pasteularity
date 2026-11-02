package win.libpaste.pasteularity.features.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import win.libpaste.pasteularity.features.apps.AppInfo
import win.libpaste.pasteularity.ui.components.ButtonStyle
import win.libpaste.pasteularity.ui.components.PstuButton
import win.libpaste.pasteularity.ui.components.PstuTextField
import win.libpaste.pasteularity.ui.theme.PstuColors
import win.libpaste.pasteularity.ui.theme.Spacing

/** Two steps: pick the files from Downloads, then confirm which app they belong to. */
@Composable
fun ObbImportDialog(
    files: FileRepository,
    apps: List<AppInfo>,
    onImport: (sources: List<String>, packageName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var sources by remember { mutableStateOf<List<String>?>(null) }
    var packageName by remember { mutableStateOf("") }

    val picked = sources
    if (picked == null) {
        FilePickerDialog(
            repository = files,
            title = "Choose OBB files",
            startPath = DOWNLOADS,
            mode = PickMode.Files(setOf("obb"), multiple = true),
            onPicked = { chosen ->
                sources = chosen
                packageName = chosen.firstNotNullOfOrNull { packageFromObbName(it.substringAfterLast('/')) }.orEmpty()
            },
            onDismiss = onDismiss,
        )
        return
    }

    val query = packageName.trim()
    val matches = if (query.isEmpty()) {
        emptyList()
    } else {
        apps.filter {
            it.packageName != query &&
                (it.packageName.contains(query, ignoreCase = true) || it.label.contains(query, ignoreCase = true))
        }.take(3)
    }
    val valid = isValidPackageName(query)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PstuColors.SurfaceRaised,
        tonalElevation = 0.dp,
        title = { Text(text = "Which app are these for?", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                Text(
                    text = "${picked.size} ${if (picked.size == 1) "file" else "files"} go to Android/obb/<package>.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                PstuTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = "Package name",
                    isError = query.isNotEmpty() && !valid,
                    modifier = Modifier.fillMaxWidth(),
                )
                matches.forEach { app ->
                    PstuButton(
                        text = app.label,
                        style = ButtonStyle.Quiet,
                        onClick = { packageName = app.packageName },
                    )
                }
            }
        },
        confirmButton = {
            PstuButton(
                text = "Import",
                style = ButtonStyle.Primary,
                enabled = valid,
                onClick = {
                    onDismiss()
                    onImport(picked, query)
                },
            )
        },
        dismissButton = { PstuButton(text = "Cancel", style = ButtonStyle.Quiet, onClick = onDismiss) },
    )
}
