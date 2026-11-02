package win.libpaste.pasteularity.features.onboarding

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import win.libpaste.pasteularity.AppContainer
import win.libpaste.pasteularity.shell.BytezukuState
import win.libpaste.pasteularity.shell.ConnectionStatus
import win.libpaste.pasteularity.shell.userMessage

data class OnboardingState(
    val step: Int = 0,
    val pairingCode: String = "",
    val port: String = "",
    val portDetected: Boolean = false,
    val pairing: Boolean = false,
    val error: String? = null,
    val bytezuku: BytezukuState = BytezukuState.NotRunning,
    val supportsWirelessDebugging: Boolean = true,
) {
    val canPair: Boolean
        get() = pairingCode.length == PAIRING_CODE_LENGTH && port.toIntOrNull() != null && !pairing
}

const val PAIRING_CODE_LENGTH = 6
const val LAST_ONBOARDING_STEP = 2

class OnboardingViewModel(private val container: AppContainer) : ViewModel() {
    private val mutable = MutableStateFlow(
        OnboardingState(supportsWirelessDebugging = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R),
    )
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            container.shell.bytezuku.bytezukuState.collect { value ->
                mutable.update { it.copy(bytezuku = value) }
            }
        }
        // Skips the current value so the guide can still be reopened while connected.
        viewModelScope.launch {
            container.shell.status.drop(1).collect { status ->
                if (status is ConnectionStatus.Connected) finish()
            }
        }
    }

    /** Runs only while the setup screen is showing, because mDNS discovery uses battery. */
    suspend fun watchPairingPort() {
        container.shell.adb.pairingPort().collect { port ->
            mutable.update { current ->
                when {
                    port != null -> current.copy(port = port.toString(), portDetected = true)
                    current.portDetected -> current.copy(port = "", portDetected = false)
                    else -> current
                }
            }
        }
    }

    fun setStep(step: Int) = mutable.update { it.copy(step = step.coerceIn(0, LAST_ONBOARDING_STEP), error = null) }

    fun setCode(value: String) = mutable.update {
        it.copy(pairingCode = value.filter(Char::isDigit).take(PAIRING_CODE_LENGTH), error = null)
    }

    fun setPort(value: String) = mutable.update {
        it.copy(port = value.filter(Char::isDigit).take(5), portDetected = false, error = null)
    }

    fun openDeveloperOptions() {
        if (!container.androidSettings.openDeveloperOptions()) {
            mutable.update { it.copy(error = "Could not open Developer options.") }
        }
    }

    fun pair() {
        val current = state.value
        val port = current.port.toIntOrNull() ?: return
        mutable.update { it.copy(pairing = true, error = null) }
        viewModelScope.launch {
            container.shell.pair(port, current.pairingCode)
                .onSuccess { container.messenger.success("Paired with this headset.") }
                .onFailure { failure -> mutable.update { it.copy(error = failure.userMessage()) } }
            mutable.update { it.copy(pairing = false) }
        }
    }

    fun useBytezuku() {
        when (state.value.bytezuku) {
            BytezukuState.Ready -> container.shell.bytezuku.connect()
            BytezukuState.NeedsPermission -> container.shell.bytezuku.requestPermission()
            BytezukuState.NotRunning -> Unit
        }
    }

    fun finish() {
        viewModelScope.launch {
            container.settings.setOnboardingDone(true)
            container.navigator.closeSetup()
        }
    }
}
