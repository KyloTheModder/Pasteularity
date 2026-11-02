package win.libpaste.pasteularity.features.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import win.libpaste.pasteularity.AppContainer
import win.libpaste.pasteularity.features.files.DATA_ROOT
import win.libpaste.pasteularity.features.files.OBB_ROOT
import win.libpaste.pasteularity.features.files.joinPath
import win.libpaste.pasteularity.shell.shellQuote
import win.libpaste.pasteularity.shell.userMessage

data class AppsState(
    val apps: List<AppInfo> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val showSystem: Boolean = false,
    val selected: String? = null,
    val busyAction: String? = null,
    val installStep: String? = null,
) {
    val visibleApps: List<AppInfo>
        get() = apps
            .filter { showSystem || !it.isSystem }
            .filter {
                query.isBlank() ||
                    it.label.contains(query.trim(), ignoreCase = true) ||
                    it.packageName.contains(query.trim(), ignoreCase = true)
            }
    val selectedApp: AppInfo?
        get() = apps.firstOrNull { it.packageName == selected }
}

class AppsViewModel(private val container: AppContainer) : ViewModel() {
    private val repository = container.apps
    private val messenger = container.messenger
    private val mutable = MutableStateFlow(AppsState())
    val state = mutable.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.list()
                .onSuccess { apps ->
                    mutable.update { current ->
                        val selected = current.selected?.takeIf { name -> apps.any { it.packageName == name } }
                        current.copy(apps = apps, loading = false, error = null, selected = selected)
                    }
                }
                .onFailure { failure ->
                    mutable.update { it.copy(loading = false, error = failure.userMessage()) }
                }
        }
    }

    fun setQuery(value: String) = mutable.update { it.copy(query = value) }

    fun toggleSystem() = mutable.update { it.copy(showSystem = !it.showSystem) }

    fun select(packageName: String) = mutable.update { it.copy(selected = packageName) }

    fun launch(app: AppInfo) = run("launch", "Launched ${app.label}.") { repository.launch(app.packageName) }

    fun forceStop(app: AppInfo) = run("stop", "Stopped ${app.label}.") { repository.forceStop(app.packageName) }

    fun clearCache(app: AppInfo) = run("cache", "External cache cleared.") { repository.clearCache(app.packageName) }

    fun clearData(app: AppInfo) = run("data", "Data cleared for ${app.label}.") { repository.clearData(app.packageName) }

    fun uninstall(app: AppInfo) = run("uninstall", "Uninstalled ${app.label}.") {
        repository.uninstall(app.packageName).also { refresh() }
    }

    fun exportApk(app: AppInfo) {
        mutable.update { it.copy(busyAction = "export") }
        viewModelScope.launch {
            repository.exportApk(app.packageName)
                .onSuccess { messenger.success("Exported to $it.") }
                .onFailure { messenger.error(it.userMessage()) }
            mutable.update { it.copy(busyAction = null) }
        }
    }

    fun openObbFolder(app: AppInfo) = openFolder(joinPath(OBB_ROOT, app.packageName))

    fun openDataFolder(app: AppInfo) = openFolder(joinPath(DATA_ROOT, app.packageName))

    private fun openFolder(path: String) {
        viewModelScope.launch {
            if (container.shell.exec("[ -d ${shellQuote(path)} ]").isSuccess) {
                container.navigator.openInFiles(path)
            } else {
                messenger.error("This app has no folder at $path.")
            }
        }
    }

    fun install(apkPath: String, obbFolder: String?) {
        mutable.update { it.copy(installStep = "Starting") }
        viewModelScope.launch {
            repository.install(apkPath, obbFolder) { step -> mutable.update { it.copy(installStep = step) } }
                .onSuccess { messenger.success("Installed $it."); refresh() }
                .onFailure { messenger.error(it.userMessage()) }
            mutable.update { it.copy(installStep = null) }
        }
    }

    private fun run(action: String, done: String, block: suspend () -> Result<Unit>) {
        mutable.update { it.copy(busyAction = action) }
        viewModelScope.launch {
            block()
                .onSuccess { messenger.success(done) }
                .onFailure { messenger.error(it.userMessage()) }
            mutable.update { it.copy(busyAction = null) }
        }
    }
}
