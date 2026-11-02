package win.libpaste.pasteularity.features.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import win.libpaste.pasteularity.ui.components.ButtonStyle
import win.libpaste.pasteularity.ui.components.LoadingState
import win.libpaste.pasteularity.ui.components.MessageState
import win.libpaste.pasteularity.ui.components.PstuButton
import win.libpaste.pasteularity.ui.components.SelectBox
import win.libpaste.pasteularity.ui.theme.PstuColors
import win.libpaste.pasteularity.ui.theme.PstuShape
import win.libpaste.pasteularity.ui.theme.Spacing
import win.libpaste.pasteularity.ui.theme.TouchTarget

sealed interface PickMode {
    data class Files(val extensions: Set<String>, val multiple: Boolean) : PickMode
    data object Folder : PickMode
}

/** A shell-backed picker, because the system picker cannot open Android/obb or Android/data. */
@Composable
fun FilePickerDialog(
    repository: FileRepository,
    title: String,
    startPath: String,
    mode: PickMode,
    onPicked: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf(startPath) }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(path) {
        entries = null
        error = null
        repository.list(path)
            .onSuccess { entries = it }
            .onFailure { error = it.message }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .width(760.dp)
                .height(620.dp)
                .clip(PstuShape)
                .background(PstuColors.SurfaceRaised)
                .padding(Spacing.L),
            verticalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = path,
                style = MaterialTheme.typography.bodyMedium,
                color = PstuColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(PstuShape)
                    .background(PstuColors.Surface),
            ) {
                val shown = entries?.let { pickable(it, mode) }
                when {
                    error != null -> MessageState(Icons.Outlined.ErrorOutline, "Cannot open this folder", error.orEmpty())
                    shown == null -> LoadingState("Reading folder")
                    shown.isEmpty() -> MessageState(
                        Icons.Outlined.FolderOff,
                        "Nothing to pick here",
                        "Go up, or open a different folder.",
                    )
                    else -> LazyColumn {
                        items(shown, key = { it.path }) { entry ->
                            PickerRow(
                                entry = entry,
                                checked = entry.path in picked,
                                onOpen = { path = entry.path },
                                onToggle = {
                                    picked = when {
                                        entry.path in picked -> picked - entry.path
                                        mode is PickMode.Files && mode.multiple -> picked + entry.path
                                        else -> setOf(entry.path)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalAlignment = Alignment.CenterVertically) {
                PstuButton(
                    text = "Up",
                    style = ButtonStyle.Outline,
                    enabled = parentPath(path) != null,
                    onClick = { parentPath(path)?.let { path = it } },
                )
                Box(modifier = Modifier.weight(1f))
                PstuButton(text = "Cancel", style = ButtonStyle.Quiet, onClick = onDismiss)
                if (mode is PickMode.Folder) {
                    PstuButton(
                        text = "Use this folder",
                        style = ButtonStyle.Primary,
                        onClick = { onPicked(listOf(path)) },
                    )
                } else {
                    PstuButton(
                        text = if (picked.size > 1) "Choose ${picked.size} files" else "Choose",
                        style = ButtonStyle.Primary,
                        enabled = picked.isNotEmpty(),
                        onClick = { onPicked(picked.toList()) },
                    )
                }
            }
        }
    }
}

private fun pickable(entries: List<FileEntry>, mode: PickMode): List<FileEntry> {
    val allowed = when (mode) {
        is PickMode.Folder -> entries.filter { it.isDirectory || it.isLink }
        is PickMode.Files -> entries.filter {
            it.isDirectory || it.isLink || it.name.substringAfterLast('.', "").lowercase() in mode.extensions
        }
    }
    return sortAndFilter(allowed, SortKey.Name, ascending = true, filter = "", showHidden = false)
}

@Composable
private fun PickerRow(entry: FileEntry, checked: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    val navigable = entry.isDirectory || entry.isLink
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clickable(onClick = if (navigable) onOpen else onToggle)
            .padding(horizontal = Spacing.M),
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigable) {
            Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(24.dp), tint = PstuColors.TextMuted)
        } else {
            SelectBox(checked = checked, onToggle = onToggle)
            Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(24.dp), tint = PstuColors.TextMuted)
        }
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!navigable) {
            Text(text = formatSize(entry.sizeBytes), style = MaterialTheme.typography.bodyMedium, color = PstuColors.TextMuted)
        }
    }
}
