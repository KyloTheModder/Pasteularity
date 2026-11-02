package win.libpaste.pasteularity.features.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import win.libpaste.pasteularity.ui.components.ButtonStyle
import win.libpaste.pasteularity.ui.components.ConfirmDialog
import win.libpaste.pasteularity.ui.components.InputDialog
import win.libpaste.pasteularity.ui.components.LoadingState
import win.libpaste.pasteularity.ui.components.MessageState
import win.libpaste.pasteularity.ui.components.PstuButton
import win.libpaste.pasteularity.ui.components.PstuTextField
import win.libpaste.pasteularity.ui.components.SelectBox
import win.libpaste.pasteularity.ui.theme.PstuColors
import win.libpaste.pasteularity.ui.theme.PstuShape
import win.libpaste.pasteularity.ui.theme.Spacing
import win.libpaste.pasteularity.ui.theme.TouchTarget

private data class Place(val label: String, val path: String, val icon: ImageVector)

private val places = listOf(
    Place("Internal storage", INTERNAL_STORAGE, Icons.Outlined.SdStorage),
    Place("Downloads", DOWNLOADS, Icons.Outlined.Download),
    Place("OBB", OBB_ROOT, Icons.Outlined.Inventory2),
    Place("App data", DATA_ROOT, Icons.Outlined.Storage),
)

class FilesActions(
    val onOpenPath: (String) -> Unit,
    val onOpenEntry: (FileEntry) -> Unit,
    val onRefresh: () -> Unit,
    val onToggleSelected: (String) -> Unit,
    val onClearSelection: () -> Unit,
    val onSort: (SortKey) -> Unit,
    val onFilter: (String) -> Unit,
    val onToggleHidden: () -> Unit,
    val onCopy: () -> Unit,
    val onCut: () -> Unit,
    val onRequestPaste: () -> Unit,
    val onConfirmPaste: () -> Unit,
    val onDismissConflicts: () -> Unit,
    val onClearClipboard: () -> Unit,
    val onCreateFolder: (String) -> Unit,
    val onRename: (String) -> Unit,
    val onDelete: () -> Unit,
    val onImportObb: () -> Unit,
    val onCancelOperation: () -> Unit,
)

private enum class FileDialog { NewFolder, Rename, Delete }

@Composable
fun FilesScreen(state: FilesState, actions: FilesActions) {
    var dialog by remember { mutableStateOf<FileDialog?>(null) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = Spacing.L, end = Spacing.L, bottom = Spacing.L),
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Column(
            modifier = Modifier
                .width(224.dp)
                .fillMaxHeight()
                .clip(PstuShape)
                .background(PstuColors.Surface)
                .padding(Spacing.S),
            verticalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            places.forEach { place ->
                PlaceItem(place, selected = state.path == place.path, onClick = { actions.onOpenPath(place.path) })
            }
            Box(modifier = Modifier.weight(1f))
            PstuButton(
                text = "Import OBB",
                icon = Icons.Outlined.Inventory2,
                style = ButtonStyle.Primary,
                onClick = actions.onImportObb,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
            Breadcrumbs(state.path, actions.onOpenPath)
            if (state.selection.isEmpty()) {
                BrowseBar(state, actions, onNewFolder = { dialog = FileDialog.NewFolder })
            } else {
                SelectionBar(
                    state = state,
                    actions = actions,
                    onRename = { dialog = FileDialog.Rename },
                    onDelete = { dialog = FileDialog.Delete },
                )
            }
            state.clipboard?.let { ClipboardBar(it, state.operation == null, actions) }
            state.operation?.let { OperationBar(it, actions.onCancelOperation) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(PstuShape)
                    .background(PstuColors.Surface),
            ) {
                FileList(state, actions)
            }
        }
    }

    when (dialog) {
        FileDialog.NewFolder -> InputDialog(
            title = "New folder",
            label = "Folder name",
            confirmLabel = "Create",
            validate = ::validateFileName,
            onConfirm = actions.onCreateFolder,
            onDismiss = { dialog = null },
        )
        FileDialog.Rename -> InputDialog(
            title = "Rename",
            label = "New name",
            confirmLabel = "Rename",
            initial = state.selection.singleOrNull()?.substringAfterLast('/').orEmpty(),
            validate = ::validateFileName,
            onConfirm = actions.onRename,
            onDismiss = { dialog = null },
        )
        FileDialog.Delete -> ConfirmDialog(
            title = "Delete ${state.selection.size} ${if (state.selection.size == 1) "item" else "items"}?",
            message = state.selection.take(5).joinToString("\n") { it.substringAfterLast('/') } +
                (if (state.selection.size > 5) "\nand ${state.selection.size - 5} more" else "") +
                "\n\nThis cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = actions.onDelete,
            onDismiss = { dialog = null },
        )
        null -> Unit
    }

    if (state.pasteConflicts.isNotEmpty()) {
        ConfirmDialog(
            title = "Replace ${state.pasteConflicts.size} ${if (state.pasteConflicts.size == 1) "item" else "items"}?",
            message = state.pasteConflicts.take(5).joinToString("\n") { it.substringAfterLast('/') } +
                "\n\nThese already exist in this folder.",
            confirmLabel = "Replace",
            onConfirm = actions.onConfirmPaste,
            onDismiss = actions.onDismissConflicts,
        )
    }
}

@Composable
private fun PlaceItem(place: Place, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clip(PstuShape)
            .background(if (selected) PstuColors.SurfaceRaised else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.M),
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            place.icon,
            contentDescription = null,
            tint = if (selected) PstuColors.Accent else PstuColors.TextMuted,
            modifier = Modifier.size(24.dp),
        )
        Text(text = place.label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Breadcrumbs(path: String, onOpen: (String) -> Unit) {
    val crumbs = breadcrumbs(path)
    val listState = rememberLazyListState()
    LaunchedEffect(path) { listState.scrollToItem(crumbs.lastIndex) }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(crumbs, key = { it.second }) { (label, target) ->
            val isLast = target == crumbs.last().second
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .heightIn(min = TouchTarget)
                        .clip(PstuShape)
                        .clickable { onOpen(target) }
                        .padding(horizontal = Spacing.M),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = if (isLast) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                        color = if (isLast) PstuColors.Text else PstuColors.TextMuted,
                    )
                }
                if (!isLast && label != "/") {
                    Text(text = "/", style = MaterialTheme.typography.labelLarge, color = PstuColors.TextMuted)
                }
            }
        }
    }
}

@Composable
private fun BrowseBar(state: FilesState, actions: FilesActions, onNewFolder: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalAlignment = Alignment.CenterVertically) {
        PstuTextField(
            value = state.filter,
            onValueChange = actions.onFilter,
            label = "Filter this folder",
            modifier = Modifier.weight(1f),
        )
        SortKey.entries.forEach { key ->
            val active = state.sortKey == key
            PstuButton(
                text = key.label,
                icon = when {
                    !active -> null
                    state.ascending -> Icons.Outlined.ArrowUpward
                    else -> Icons.Outlined.ArrowDownward
                },
                style = if (active) ButtonStyle.Secondary else ButtonStyle.Quiet,
                onClick = { actions.onSort(key) },
            )
        }
        PstuButton(
            text = "Hidden",
            icon = if (state.showHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            style = ButtonStyle.Quiet,
            onClick = actions.onToggleHidden,
        )
        PstuButton(text = "New folder", icon = Icons.Outlined.CreateNewFolder, onClick = onNewFolder)
    }
}

@Composable
private fun SelectionBar(state: FilesState, actions: FilesActions, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "${state.selection.size} selected",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(start = Spacing.M),
        )
        PstuButton(text = "Copy", icon = Icons.Outlined.ContentCopy, onClick = actions.onCopy)
        PstuButton(text = "Cut", icon = Icons.Outlined.ContentCut, onClick = actions.onCut)
        PstuButton(
            text = "Rename",
            icon = Icons.Outlined.DriveFileRenameOutline,
            enabled = state.selection.size == 1,
            onClick = onRename,
        )
        PstuButton(text = "Delete", icon = Icons.Outlined.Delete, style = ButtonStyle.Danger, onClick = onDelete)
        PstuButton(text = "Clear", icon = Icons.Outlined.Close, style = ButtonStyle.Quiet, onClick = actions.onClearSelection)
    }
}

@Composable
private fun ClipboardBar(clipboard: FileClipboard, enabled: Boolean, actions: FilesActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PstuShape)
            .background(PstuColors.SurfaceRaised)
            .padding(horizontal = Spacing.M, vertical = Spacing.S),
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val verb = if (clipboard.isCut) "to move" else "to copy"
        val count = clipboard.paths.size
        Text(
            text = "$count ${if (count == 1) "item" else "items"} $verb. Open the destination, then paste.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        PstuButton(text = "Discard", style = ButtonStyle.Quiet, onClick = actions.onClearClipboard)
        PstuButton(
            text = "Paste here",
            icon = Icons.Outlined.ContentPaste,
            style = ButtonStyle.Primary,
            enabled = enabled,
            onClick = actions.onRequestPaste,
        )
    }
}

@Composable
private fun OperationBar(operation: FileOperation, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PstuShape)
            .background(PstuColors.SurfaceRaised)
            .padding(horizontal = Spacing.M, vertical = Spacing.S),
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val percent = operation.progress?.let { " ${(it * 100).toInt()}%" }.orEmpty()
        Text(text = operation.label + percent, style = MaterialTheme.typography.labelLarge)
        val progress = operation.progress
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f), drawStopIndicator = {})
        } else {
            LinearProgressIndicator(modifier = Modifier.weight(1f))
        }
        PstuButton(text = "Cancel", style = ButtonStyle.Quiet, onClick = onCancel)
    }
}

@Composable
private fun FileList(state: FilesState, actions: FilesActions) {
    val visible = remember(state.entries, state.sortKey, state.ascending, state.filter, state.showHidden) {
        state.visibleEntries
    }
    when {
        state.loading && state.entries.isEmpty() -> LoadingState("Reading folder")
        state.error != null -> MessageState(
            icon = Icons.Outlined.ErrorOutline,
            title = "Cannot open this folder",
            message = state.error,
            action = { PstuButton(text = "Try again", onClick = actions.onRefresh) },
        )
        visible.isEmpty() -> MessageState(
            icon = Icons.Outlined.FolderOff,
            title = if (state.filter.isBlank()) "This folder is empty" else "No matches",
            message = if (state.filter.isBlank()) {
                "Paste files here, or import an OBB."
            } else {
                "Nothing here matches \"${state.filter}\"."
            },
        )
        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(visible, key = { it.path }, contentType = { 0 }) { entry ->
                FileRow(
                    entry = entry,
                    selected = entry.path in state.selection,
                    selecting = state.selection.isNotEmpty(),
                    onOpen = { actions.onOpenEntry(entry) },
                    onToggle = { actions.onToggleSelected(entry.path) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(if (selected) PstuColors.SurfaceRaised else Color.Transparent)
            .combinedClickable(
                onClick = if (selecting) onToggle else onOpen,
                onLongClick = onToggle,
            )
            .padding(end = Spacing.L),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        SelectBox(checked = selected, onToggle = onToggle, modifier = Modifier.padding(start = Spacing.S))
        Icon(iconFor(entry), contentDescription = null, modifier = Modifier.size(24.dp), tint = PstuColors.TextMuted)
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = Spacing.S),
        )
        Text(
            text = remember(entry.isDirectory, entry.sizeBytes) {
                if (entry.isDirectory) "" else formatSize(entry.sizeBytes)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = PstuColors.TextMuted,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = remember(entry.modifiedEpochSeconds) { formatDate(entry.modifiedEpochSeconds) },
            style = MaterialTheme.typography.bodyMedium,
            color = PstuColors.TextMuted,
            modifier = Modifier.width(144.dp),
        )
    }
}

private fun iconFor(entry: FileEntry): ImageVector = when {
    entry.isDirectory -> Icons.Outlined.Folder
    entry.isLink -> Icons.Outlined.Link
    else -> when (entry.name.substringAfterLast('.', "").lowercase()) {
        "apk" -> Icons.Outlined.Android
        "obb" -> Icons.Outlined.Inventory2
        "png", "jpg", "jpeg", "webp" -> Icons.Outlined.Image
        "mp4", "mkv", "webm" -> Icons.Outlined.Movie
        else -> Icons.Outlined.Description
    }
}
