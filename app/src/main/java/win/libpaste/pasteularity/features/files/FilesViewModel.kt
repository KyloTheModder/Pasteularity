package win.libpaste.pasteularity.features.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import win.libpaste.pasteularity.AppContainer
import win.libpaste.pasteularity.shell.shellQuote
import win.libpaste.pasteularity.shell.userMessage

data class FileClipboard(val paths: List<String>, val isCut: Boolean)

data class FileOperation(val label: String, val progress: Float?)

data class FilesState(
    val path: String = INTERNAL_STORAGE,
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val selection: Set<String> = emptySet(),
    val sortKey: SortKey = SortKey.Name,
    val ascending: Boolean = true,
    val filter: String = "",
    val showHidden: Boolean = false,
    val clipboard: FileClipboard? = null,
    val operation: FileOperation? = null,
    val pasteConflicts: List<String> = emptyList(),
) {
    val visibleEntries: List<FileEntry>
        get() = sortAndFilter(entries, sortKey, ascending, filter, showHidden)
}

class FilesViewModel(private val container: AppContainer) : ViewModel() {
    private val repository = container.files
    private val messenger = container.messenger
    private val mutable = MutableStateFlow(FilesState())
    val state = mutable.asStateFlow()

    private var operationJob: Job? = null
    private var listJob: Job? = null

    init {
        viewModelScope.launch {
            container.shell.isConnected.collect { connected -> if (connected) refresh() }
        }
    }

    fun open(path: String) {
        mutable.update { it.copy(path = path, entries = emptyList(), selection = emptySet(), filter = "") }
        refresh()
    }

    fun openEntry(entry: FileEntry) {
        when {
            entry.isDirectory -> open(entry.path)
            entry.isLink -> viewModelScope.launch {
                if (repository.isDirectory(entry.path)) open(entry.path)
            }
        }
    }

    /** Returns false at the filesystem root, so the system back gesture can leave the screen. */
    fun goUp(): Boolean {
        val parent = parentPath(state.value.path) ?: return false
        open(parent)
        return true
    }

    fun refresh() {
        val path = state.value.path
        mutable.update { it.copy(loading = true, error = null) }
        // Cancels the previous read, so a slow folder cannot overwrite the one you moved on to.
        listJob?.cancel()
        listJob = viewModelScope.launch {
            repository.list(path)
                .onSuccess { entries ->
                    mutable.update { it.copy(entries = entries, loading = false, error = null) }
                }
                .onFailure { failure ->
                    mutable.update { it.copy(entries = emptyList(), loading = false, error = failure.userMessage()) }
                }
        }
    }

    fun toggleSelected(path: String) = mutable.update {
        it.copy(selection = if (path in it.selection) it.selection - path else it.selection + path)
    }

    fun clearSelection() = mutable.update { it.copy(selection = emptySet()) }

    fun setSort(key: SortKey) = mutable.update {
        if (it.sortKey == key) it.copy(ascending = !it.ascending) else it.copy(sortKey = key, ascending = true)
    }

    fun setFilter(value: String) = mutable.update { it.copy(filter = value) }

    fun toggleHidden() = mutable.update { it.copy(showHidden = !it.showHidden) }

    fun copySelection() = toClipboard(isCut = false)

    fun cutSelection() = toClipboard(isCut = true)

    private fun toClipboard(isCut: Boolean) = mutable.update {
        it.copy(clipboard = FileClipboard(it.selection.toList(), isCut), selection = emptySet())
    }

    fun clearClipboard() = mutable.update { it.copy(clipboard = null) }

    fun requestPaste() {
        val current = state.value
        val clipboard = current.clipboard ?: return
        if (clipboard.paths.any { current.path == it || current.path.startsWith("$it/") }) {
            messenger.error("A folder cannot be pasted into itself.")
            return
        }
        if (clipboard.isCut && clipboard.paths.all { parentPath(it) == current.path }) {
            messenger.error("Those items are already in this folder.")
            return
        }
        viewModelScope.launch {
            val targets = if (clipboard.isCut) {
                clipboard.paths.map { joinPath(current.path, it.substringAfterLast('/')) }
                    .filterNot { it in clipboard.paths }
            } else {
                repository.pasteTargets(clipboard.paths, current.path).map { it.second }
            }
            val conflicts = repository.existing(targets)
            if (conflicts.isEmpty()) paste() else mutable.update { it.copy(pasteConflicts = conflicts) }
        }
    }

    fun dismissConflicts() = mutable.update { it.copy(pasteConflicts = emptyList()) }

    fun paste() {
        val current = state.value
        val clipboard = current.clipboard ?: return
        mutable.update { it.copy(pasteConflicts = emptyList()) }
        runOperation(
            label = if (clipboard.isCut) "Moving" else "Copying",
            done = "Pasted ${itemCount(clipboard.paths.size)}.",
        ) { onProgress ->
            val result = if (clipboard.isCut) {
                repository.move(clipboard.paths.filterNot { parentPath(it) == current.path }, current.path)
            } else {
                repository.copy(clipboard.paths, current.path, onProgress)
            }
            if (result.isSuccess && clipboard.isCut) mutable.update { it.copy(clipboard = null) }
            result
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            repository.createFolder(state.value.path, name)
                .onSuccess { messenger.success("Folder created."); refresh() }
                .onFailure { messenger.error(it.userMessage()) }
        }
    }

    fun renameSelected(newName: String) {
        val path = state.value.selection.singleOrNull() ?: return
        viewModelScope.launch {
            repository.rename(path, newName)
                .onSuccess { messenger.success("Renamed."); clearSelection(); refresh() }
                .onFailure { messenger.error(it.userMessage()) }
        }
    }

    fun deleteSelected() {
        val paths = state.value.selection.toList()
        runOperation(label = "Deleting", done = "Deleted ${itemCount(paths.size)}.") {
            val result = repository.delete(paths)
            if (result.isSuccess) clearSelection()
            result
        }
    }

    fun importObb(sources: List<String>, packageName: String) {
        val folder = joinPath(OBB_ROOT, packageName)
        runOperation(label = "Importing OBB", done = "Imported to $folder.") { onProgress ->
            val created = container.shell.exec("mkdir -p -- ${shellQuote(folder)}")
            if (created.isFailure) return@runOperation created.map { }
            repository.copy(sources, folder, onProgress)
        }
    }

    fun cancelOperation() {
        operationJob?.cancel()
    }

    private fun runOperation(
        label: String,
        done: String,
        block: suspend (onProgress: (Float?) -> Unit) -> Result<Unit>,
    ) {
        mutable.update { it.copy(operation = FileOperation(label, null)) }
        operationJob = viewModelScope.launch {
            try {
                block { progress -> mutable.update { it.copy(operation = FileOperation(label, progress)) } }
                    .onSuccess { messenger.success(done) }
                    .onFailure { messenger.error(it.userMessage()) }
            } catch (e: CancellationException) {
                messenger.error("Cancelled. Files already written were kept.")
            } finally {
                mutable.update { it.copy(operation = null) }
                refresh()
            }
        }
    }

    private fun itemCount(count: Int) = if (count == 1) "1 item" else "$count items"
}
