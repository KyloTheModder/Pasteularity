package win.libpaste.pasteularity.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import win.libpaste.pasteularity.AppContainer
import win.libpaste.pasteularity.features.apps.AppsActions
import win.libpaste.pasteularity.features.apps.AppsScreen
import win.libpaste.pasteularity.features.apps.AppsViewModel
import win.libpaste.pasteularity.features.apps.InstallDialog
import win.libpaste.pasteularity.features.display.DisplayActions
import win.libpaste.pasteularity.features.display.DisplayScreen
import win.libpaste.pasteularity.features.display.DisplayViewModel
import win.libpaste.pasteularity.features.files.FilesActions
import win.libpaste.pasteularity.features.files.FilesScreen
import win.libpaste.pasteularity.features.files.FilesViewModel
import win.libpaste.pasteularity.features.files.ObbImportDialog
import win.libpaste.pasteularity.features.home.HomeActions
import win.libpaste.pasteularity.features.home.HomeScreen
import win.libpaste.pasteularity.features.home.HomeViewModel
import win.libpaste.pasteularity.features.onboarding.OnboardingScreen
import win.libpaste.pasteularity.features.onboarding.OnboardingViewModel
import win.libpaste.pasteularity.features.settings.SettingsActions
import win.libpaste.pasteularity.features.settings.SettingsScreen
import win.libpaste.pasteularity.features.settings.SettingsViewModel
import win.libpaste.pasteularity.features.terminal.TerminalActions
import win.libpaste.pasteularity.features.terminal.TerminalScreen
import win.libpaste.pasteularity.features.terminal.TerminalViewModel
import win.libpaste.pasteularity.features.utilities.UtilitiesActions
import win.libpaste.pasteularity.features.utilities.UtilitiesScreen
import win.libpaste.pasteularity.features.utilities.UtilitiesViewModel
import win.libpaste.pasteularity.shell.ConnectionStatus
import win.libpaste.pasteularity.shell.userMessage
import win.libpaste.pasteularity.ui.components.ButtonStyle
import win.libpaste.pasteularity.ui.components.MessageState
import win.libpaste.pasteularity.ui.components.PstuButton

@Composable
fun AppRoot(container: AppContainer) {
    val navigator = container.navigator
    val status by container.shell.status.collectAsStateWithLifecycle()
    val destination by navigator.destination.collectAsStateWithLifecycle()
    val showSetup by navigator.showSetup.collectAsStateWithLifecycle()
    val onboardingDone by container.settings.onboardingDone.collectAsStateWithLifecycle(initialValue = null)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        container.messenger.events.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch { snackbarHostState.showSnackbar(PstuSnackbarVisuals(message.text, message.isError)) }
        }
    }

    // Nothing is drawn until the saved flag loads, so Home never flashes before setup.
    val done = onboardingDone ?: return
    if (showSetup || !done) {
        SetupRoute(container)
        return
    }

    AppShell(
        destination = destination,
        status = status,
        onNavigate = navigator::go,
        onStatusClick = {
            when (status) {
                is ConnectionStatus.Connected -> navigator.go(Destination.Settings)
                ConnectionStatus.Connecting -> Unit
                ConnectionStatus.NotPaired -> navigator.openSetup()
                ConnectionStatus.Disconnected -> scope.launch {
                    container.shell.reconnect().onFailure { container.messenger.error(it.userMessage()) }
                }
            }
        },
        snackbarHostState = snackbarHostState,
    ) {
        if (destination.needsShell && status !is ConnectionStatus.Connected) {
            NotConnectedState(status, onSetup = navigator::openSetup, onReconnect = {
                scope.launch { container.shell.reconnect().onFailure { container.messenger.error(it.userMessage()) } }
            })
        } else {
            when (destination) {
                Destination.Home -> HomeRoute(container, status)
                Destination.Terminal -> TerminalRoute(container)
                Destination.Files -> FilesRoute(container)
                Destination.Display -> DisplayRoute(container)
                Destination.Apps -> AppsRoute(container)
                Destination.Utilities -> UtilitiesRoute(container)
                Destination.Settings -> SettingsRoute(container, status)
            }
        }
    }
}

@Composable
fun NotConnectedState(status: ConnectionStatus, onSetup: () -> Unit, onReconnect: () -> Unit) {
    val paired = status != ConnectionStatus.NotPaired
    MessageState(
        icon = Icons.Outlined.LinkOff,
        title = if (paired) "Not connected" else "Not paired yet",
        message = if (paired) {
            "Check that Wireless debugging is on and the headset is on Wi-Fi."
        } else {
            "This screen needs shell access. Pairing takes about two minutes."
        },
        action = {
            if (paired) {
                PstuButton(text = "Reconnect", style = ButtonStyle.Primary, onClick = onReconnect)
            } else {
                PstuButton(text = "Start setup", style = ButtonStyle.Primary, onClick = onSetup)
            }
        },
    )
}

@Composable
private fun SetupRoute(container: AppContainer) {
    val vm = viewModel { OnboardingViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.watchPairingPort() }
    OnboardingScreen(
        state = state,
        onStep = vm::setStep,
        onCode = vm::setCode,
        onPort = vm::setPort,
        onOpenDeveloperOptions = vm::openDeveloperOptions,
        onPair = vm::pair,
        onUseBytezuku = vm::useBytezuku,
        onSkip = vm::finish,
    )
}

@Composable
private fun HomeRoute(container: AppContainer, status: ConnectionStatus) {
    val context = LocalContext.current
    val vm = viewModel { HomeViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = remember(vm) {
        HomeActions(
            onOpenSettings = vm::openAndroidSettings,
            onOpenPage = vm::openSettingsPage,
            onDismissPages = vm::dismissSettingsPages,
            onReboot = vm::reboot,
            onProximity = vm::setProximityDisabled,
            onToggleAdvanced = vm::toggleAdvanced,
            onDiscord = { vm.openDiscord(context) },
        )
    }
    HomeScreen(state = state, connected = status is ConnectionStatus.Connected, actions = actions)
}

@Composable
private fun TerminalRoute(container: AppContainer) {
    val context = LocalContext.current
    val vm = viewModel { TerminalViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snippets by vm.snippets.collectAsStateWithLifecycle()
    val actions = remember(vm) {
        TerminalActions(
            onInput = vm::setInput,
            onRun = { vm.run() },
            onStop = vm::stop,
            onPrevious = vm::historyPrevious,
            onNext = vm::historyNext,
            onCopy = {
                context.copyToClipboard(vm.outputText())
                container.messenger.success("Output copied.")
            },
            onClear = vm::clear,
            onRunSnippet = vm::run,
            onAddSnippet = vm::addSnippet,
            onRemoveSnippet = vm::removeSnippet,
            onToggleEditing = vm::toggleEditingSnippets,
        )
    }
    TerminalScreen(state = state, snippets = snippets, actions = actions)
}

@Composable
private fun FilesRoute(container: AppContainer) {
    val vm = viewModel { FilesViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val requested by container.navigator.requestedFilesPath.collectAsStateWithLifecycle()
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(requested) {
        requested?.let {
            vm.open(it)
            container.navigator.consumeFilesRequest()
        }
    }
    BackHandler(enabled = state.path != "/") { vm.goUp() }

    val actions = remember(vm) {
        FilesActions(
            onOpenPath = vm::open,
            onOpenEntry = vm::openEntry,
            onRefresh = vm::refresh,
            onToggleSelected = vm::toggleSelected,
            onClearSelection = vm::clearSelection,
            onSort = vm::setSort,
            onFilter = vm::setFilter,
            onToggleHidden = vm::toggleHidden,
            onCopy = vm::copySelection,
            onCut = vm::cutSelection,
            onRequestPaste = vm::requestPaste,
            onConfirmPaste = vm::paste,
            onDismissConflicts = vm::dismissConflicts,
            onClearClipboard = vm::clearClipboard,
            onCreateFolder = vm::createFolder,
            onRename = vm::renameSelected,
            onDelete = vm::deleteSelected,
            onImportObb = { importing = true },
            onCancelOperation = vm::cancelOperation,
        )
    }
    FilesScreen(state = state, actions = actions)

    if (importing) {
        val appsVm = viewModel { AppsViewModel(container) }
        val appsState by appsVm.state.collectAsStateWithLifecycle()
        ObbImportDialog(
            files = container.files,
            apps = appsState.apps,
            onImport = vm::importObb,
            onDismiss = { importing = false },
        )
    }
}

@Composable
private fun DisplayRoute(container: AppContainer) {
    val vm = viewModel { DisplayViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val actions = remember(vm) {
        DisplayActions(
            onSet = vm::set,
            onSetTexture = vm::setTexture,
            onCustomWidth = vm::setCustomWidth,
            onCustomHeight = vm::setCustomHeight,
            onReset = { vm.reset(*it) },
            onResetAll = vm::resetAll,
            onSaveProfile = vm::saveProfile,
            onApplyProfile = vm::applyProfile,
            onDeleteProfile = vm::deleteProfile,
        )
    }
    DisplayScreen(state = state, profiles = profiles, actions = actions)
}

@Composable
private fun AppsRoute(container: AppContainer) {
    val vm = viewModel { AppsViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var installing by remember { mutableStateOf(false) }
    val actions = remember(vm) {
        AppsActions(
            onQuery = vm::setQuery,
            onToggleSystem = vm::toggleSystem,
            onSelect = vm::select,
            onInstall = { installing = true },
            onLaunch = vm::launch,
            onForceStop = vm::forceStop,
            onClearCache = vm::clearCache,
            onClearData = vm::clearData,
            onUninstall = vm::uninstall,
            onOpenObb = vm::openObbFolder,
            onOpenData = vm::openDataFolder,
            onExport = vm::exportApk,
            onRetry = vm::refresh,
            icons = container.apps,
        )
    }
    AppsScreen(state = state, actions = actions)
    if (installing) {
        InstallDialog(
            files = container.files,
            onInstall = vm::install,
            onDismiss = { installing = false },
        )
    }
}

@Composable
private fun UtilitiesRoute(container: AppContainer) {
    val context = LocalContext.current
    val vm = viewModel { UtilitiesViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val logControls = vm.logControls.collectAsStateWithLifecycle()
    val logLines = vm.logLines.collectAsStateWithLifecycle()
    val actions = remember(vm) {
        UtilitiesActions(
            onTab = vm::selectTab,
            onRefreshDevice = vm::loadDevice,
            onScreenshot = vm::takeScreenshot,
            onRecordSeconds = vm::setRecordSeconds,
            onStartRecording = vm::startRecording,
            onStopRecording = vm::stopRecording,
            onOpenCaptures = vm::openCaptures,
            onStartLog = vm::startLogcat,
            onStopLog = vm::stopLogcat,
            onTogglePause = vm::togglePause,
            onClearLog = vm::clearLog,
            onLogTag = vm::setLogTag,
            onLogLevel = vm::setLogLevel,
            onExportLog = { vm.exportLog(context) },
            onRefreshWifi = vm::loadWifi,
            onCopy = {
                context.copyToClipboard(it)
                container.messenger.success("Copied.")
            },
            onApplyTweak = vm::applyTweak,
            onTrimCaches = vm::trimCaches,
        )
    }
    UtilitiesScreen(state = state, logControls = logControls, logLines = logLines, actions = actions)
}

@Composable
private fun SettingsRoute(container: AppContainer, status: ConnectionStatus) {
    val vm = viewModel { SettingsViewModel(container) }
    val preference by vm.preference.collectAsStateWithLifecycle()
    val paired by vm.paired.collectAsStateWithLifecycle()
    val bytezuku by vm.bytezuku.collectAsStateWithLifecycle()
    val reconnecting by vm.reconnecting.collectAsStateWithLifecycle()
    val actions = remember(vm) {
        SettingsActions(
            onPreference = vm::setPreference,
            onReconnect = vm::reconnect,
            onSetup = vm::openSetup,
            onForget = vm::forgetPairing,
            onAllowBytezuku = vm::allowBytezuku,
        )
    }
    SettingsScreen(
        status = status,
        preference = preference,
        paired = paired,
        bytezuku = bytezuku,
        reconnecting = reconnecting,
        actions = actions,
    )
}

private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Pasteularity", text))
}
