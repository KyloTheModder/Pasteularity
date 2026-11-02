package win.libpaste.pasteularity.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import win.libpaste.pasteularity.AppContainer
import win.libpaste.pasteularity.shell.BackendPreference
import win.libpaste.pasteularity.shell.userMessage

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val busy = MutableStateFlow(false)
    val reconnecting = busy.asStateFlow()
    val preference = container.settings.backendPreference
        .stateIn(viewModelScope, SharingStarted.Eagerly, BackendPreference.Auto)
    val paired = container.settings.adbPaired.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val bytezuku = container.shell.bytezuku.bytezukuState

    fun setPreference(value: BackendPreference) {
        viewModelScope.launch {
            container.settings.setBackendPreference(value)
            container.shell.reconnect()
        }
    }

    fun reconnect() {
        busy.update { true }
        viewModelScope.launch {
            container.shell.reconnect()
                .onSuccess { container.messenger.success("Connected.") }
                .onFailure { container.messenger.error(it.userMessage()) }
            busy.update { false }
        }
    }

    fun forgetPairing() {
        viewModelScope.launch {
            container.shell.forgetPairing()
            container.messenger.success("Pairing forgotten.")
        }
    }

    fun openSetup() = container.navigator.openSetup()

    fun allowBytezuku() = container.shell.bytezuku.requestPermission()
}
