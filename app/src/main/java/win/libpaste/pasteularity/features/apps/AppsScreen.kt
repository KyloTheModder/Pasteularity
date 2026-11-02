package win.libpaste.pasteularity.features.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import win.libpaste.pasteularity.ui.components.ButtonStyle
import win.libpaste.pasteularity.ui.components.ConfirmDialog
import win.libpaste.pasteularity.ui.components.LoadingState
import win.libpaste.pasteularity.ui.components.MessageState
import win.libpaste.pasteularity.ui.components.PstuButton
import win.libpaste.pasteularity.ui.components.PstuTextField
import win.libpaste.pasteularity.ui.theme.PstuColors
import win.libpaste.pasteularity.ui.theme.PstuShape
import win.libpaste.pasteularity.ui.theme.Spacing
import win.libpaste.pasteularity.ui.theme.TouchTarget

class AppsActions(
    val onQuery: (String) -> Unit,
    val onToggleSystem: () -> Unit,
    val onSelect: (String) -> Unit,
    val onInstall: () -> Unit,
    val onLaunch: (AppInfo) -> Unit,
    val onForceStop: (AppInfo) -> Unit,
    val onClearCache: (AppInfo) -> Unit,
    val onClearData: (AppInfo) -> Unit,
    val onUninstall: (AppInfo) -> Unit,
    val onOpenObb: (AppInfo) -> Unit,
    val onOpenData: (AppInfo) -> Unit,
    val onExport: (AppInfo) -> Unit,
    val onRetry: () -> Unit,
    val icons: AppIconSource,
)

private enum class AppDialog { ClearCache, ClearData, Uninstall }

@Composable
fun AppsScreen(state: AppsState, actions: AppsActions) {
    var dialog by remember { mutableStateOf<AppDialog?>(null) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = Spacing.L, end = Spacing.L, bottom = Spacing.L),
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PstuTextField(
                    value = state.query,
                    onValueChange = actions.onQuery,
                    label = "Search apps",
                    modifier = Modifier.weight(1f),
                )
                PstuButton(
                    text = "System apps",
                    icon = if (state.showSystem) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    style = ButtonStyle.Quiet,
                    onClick = actions.onToggleSystem,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(PstuShape)
                    .background(PstuColors.Surface),
            ) {
                val visible = remember(state.apps, state.query, state.showSystem) { state.visibleApps }
                when {
                    state.loading -> LoadingState("Reading installed apps")
                    state.error != null -> MessageState(
                        icon = Icons.Outlined.ErrorOutline,
                        title = "Cannot read installed apps",
                        message = state.error,
                        action = { PstuButton(text = "Try again", onClick = actions.onRetry) },
                    )
                    visible.isEmpty() -> MessageState(
                        icon = Icons.Outlined.SearchOff,
                        title = "No apps found",
                        message = if (state.query.isBlank()) {
                            "Install an APK, or turn on System apps to see the rest."
                        } else {
                            "Nothing matches \"${state.query}\"."
                        },
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(visible, key = { it.packageName }, contentType = { 0 }) { app ->
                            AppRow(
                                app = app,
                                selected = app.packageName == state.selected,
                                icons = actions.icons,
                                onClick = { actions.onSelect(app.packageName) },
                            )
                        }
                    }
                }
            }
            PstuButton(
                text = state.installStep ?: "Install APK and OBB",
                icon = Icons.Outlined.InstallMobile,
                style = ButtonStyle.Primary,
                loading = state.installStep != null,
                onClick = actions.onInstall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(
            modifier = Modifier
                .width(440.dp)
                .fillMaxHeight()
                .clip(PstuShape)
                .background(PstuColors.Surface),
        ) {
            val app = state.selectedApp
            if (app == null) {
                MessageState(
                    icon = Icons.Outlined.TouchApp,
                    title = "Select an app",
                    message = "Its actions show up here.",
                )
            } else {
                AppDetail(app, state.busyAction, actions, onDialog = { dialog = it })
            }
        }
    }

    val app = state.selectedApp
    if (app != null) {
        when (dialog) {
            AppDialog.ClearCache -> ConfirmDialog(
                title = "Clear cache for ${app.label}?",
                message = "This clears the app's external cache. Its private cache needs root, so it stays.",
                confirmLabel = "Clear cache",
                onConfirm = { actions.onClearCache(app) },
                onDismiss = { dialog = null },
            )
            AppDialog.ClearData -> ConfirmDialog(
                title = "Clear all data for ${app.label}?",
                message = "Saves, logins and settings stored by this app are deleted. This cannot be undone.",
                confirmLabel = "Clear data",
                onConfirm = { actions.onClearData(app) },
                onDismiss = { dialog = null },
            )
            AppDialog.Uninstall -> ConfirmDialog(
                title = "Uninstall ${app.label}?",
                message = "The app and its data are removed from this headset.",
                confirmLabel = "Uninstall",
                onConfirm = { actions.onUninstall(app) },
                onDismiss = { dialog = null },
            )
            null -> Unit
        }
    }
}

@Composable
private fun AppRow(app: AppInfo, selected: Boolean, icons: AppIconSource, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(if (selected) PstuColors.SurfaceRaised else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.M, vertical = Spacing.S),
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, 40.dp, icons)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodyMedium,
                color = PstuColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AppIcon(packageName: String, size: Dp, icons: AppIconSource) {
    val icon by produceState(icons.cachedIcon(packageName), packageName) { value = icons.loadIcon(packageName) }
    val bitmap = icon
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(size).clip(PstuShape),
        )
    } else {
        Box(
            modifier = Modifier.size(size).clip(PstuShape).background(PstuColors.SurfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Android, contentDescription = null, tint = PstuColors.TextMuted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppDetail(
    app: AppInfo,
    busy: String?,
    actions: AppsActions,
    onDialog: (AppDialog) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.L),
        verticalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M), verticalAlignment = Alignment.CenterVertically) {
            key(app.packageName) { AppIcon(app.packageName, TouchTarget, actions.icons) }
            Column {
                Text(text = app.label, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = if (app.versionName.isEmpty()) app.packageName else "${app.packageName}\nVersion ${app.versionName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PstuColors.TextMuted,
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            verticalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            PstuButton(
                text = "Launch",
                icon = Icons.Outlined.PlayArrow,
                style = ButtonStyle.Primary,
                loading = busy == "launch",
                onClick = { actions.onLaunch(app) },
            )
            PstuButton(text = "Force stop", icon = Icons.Outlined.Stop, loading = busy == "stop", onClick = { actions.onForceStop(app) })
            PstuButton(text = "OBB folder", icon = Icons.Outlined.Inventory2, onClick = { actions.onOpenObb(app) })
            PstuButton(text = "Data folder", icon = Icons.Outlined.Folder, onClick = { actions.onOpenData(app) })
            PstuButton(text = "Export APK", icon = Icons.Outlined.FileUpload, loading = busy == "export", onClick = { actions.onExport(app) })
        }
        Text(text = "Remove", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.S))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            verticalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            PstuButton(
                text = "Clear cache",
                icon = Icons.Outlined.CleaningServices,
                style = ButtonStyle.Danger,
                loading = busy == "cache",
                onClick = { onDialog(AppDialog.ClearCache) },
            )
            PstuButton(
                text = "Clear data",
                icon = Icons.Outlined.DeleteSweep,
                style = ButtonStyle.Danger,
                loading = busy == "data",
                onClick = { onDialog(AppDialog.ClearData) },
            )
            PstuButton(
                text = "Uninstall",
                icon = Icons.Outlined.DeleteForever,
                style = ButtonStyle.Danger,
                loading = busy == "uninstall",
                onClick = { onDialog(AppDialog.Uninstall) },
            )
        }
    }
}
