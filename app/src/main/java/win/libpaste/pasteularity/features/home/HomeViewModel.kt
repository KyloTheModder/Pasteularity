package win.libpaste.pasteularity.features.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import win.libpaste.pasteularity.AppContainer
import win.libpaste.pasteularity.data.ProximityRecord
import win.libpaste.pasteularity.shell.ConnectionClosedException
import win.libpaste.pasteularity.shell.NOT_SUPPORTED_MESSAGE
import win.libpaste.pasteularity.shell.firmwareCommand
import win.libpaste.pasteularity.shell.userMessage

enum class RebootMode(val label: String, val command: String) {
    Normal("Reboot", "reboot"),
    Bootloader("Reboot to bootloader", "reboot bootloader"),
    Recovery("Reboot to recovery", "reboot recovery"),
}

data class HomeState(
    val settingsPages: List<AndroidSettingsPage>? = null,
    val rebooting: Boolean = false,
    val proximityBusy: Boolean = false,
    val proximityDisabled: Boolean = false,
    val advancedOpen: Boolean = false,
)

private const val DISCORD_URL = "https://dsc.gg/libpaste"
private const val PROX_DISABLE = "am broadcast -a com.oculus.vrpowermanager.prox_close"
private const val PROX_ENABLE = "am broadcast -a com.oculus.vrpowermanager.automation_disable"
private const val BOOT_ID = "cat /proc/sys/kernel/random/boot_id"

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val shell = container.shell
    private val messenger = container.messenger
    private val mutable = MutableStateFlow(HomeState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            container.shell.isConnected.collect { connected -> if (connected) loadProximity() }
        }
    }

    /** Horizon OS does not report this sensor state, so the last command is kept per boot. */
    private suspend fun loadProximity() {
        val record = container.settings.proximity.first() ?: return
        val bootId = shell.exec(BOOT_ID).getOrNull() ?: return
        mutable.update { it.copy(proximityDisabled = record.disabled && record.bootId == bootId) }
    }

    fun toggleAdvanced() = mutable.update { it.copy(advancedOpen = !it.advancedOpen) }

    /** Opens the Settings home page where the firmware allows it, otherwise offers its sections. */
    fun openAndroidSettings() {
        val settings = container.androidSettings
        if (settings.hasHomePage && settings.openHomePage()) return
        if (settings.availablePages.isEmpty()) {
            messenger.error(NOT_SUPPORTED_MESSAGE)
        } else {
            mutable.update { it.copy(settingsPages = settings.availablePages) }
        }
    }

    fun openSettingsPage(page: AndroidSettingsPage) {
        dismissSettingsPages()
        if (!container.androidSettings.open(page)) messenger.error("Could not open ${page.label}.")
    }

    fun dismissSettingsPages() = mutable.update { it.copy(settingsPages = null) }

    fun openDiscord(context: Context) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { messenger.error("Could not open a browser. Go to dsc.gg/libpaste.") }
    }

    fun reboot(mode: RebootMode) {
        mutable.update { it.copy(rebooting = true) }
        viewModelScope.launch {
            shell.firmwareCommand(mode.command)
                .onSuccess { messenger.success("Rebooting.") }
                .onFailure { failure ->
                    // adbd dies with the system, so a dropped connection means the reboot started.
                    if (failure is ConnectionClosedException) messenger.success("Rebooting.")
                    else messenger.error(failure.userMessage())
                }
            mutable.update { it.copy(rebooting = false) }
        }
    }

    fun setProximityDisabled(disabled: Boolean) {
        mutable.update { it.copy(proximityBusy = true) }
        viewModelScope.launch {
            shell.firmwareCommand(if (disabled) PROX_DISABLE else PROX_ENABLE)
                .onSuccess {
                    val bootId = shell.exec(BOOT_ID).getOrDefault("")
                    container.settings.setProximity(ProximityRecord(disabled, bootId))
                    mutable.update { it.copy(proximityDisabled = disabled) }
                    messenger.success(if (disabled) "Proximity sensor disabled." else "Proximity sensor enabled.")
                }
                .onFailure { messenger.error(it.userMessage()) }
            mutable.update { it.copy(proximityBusy = false) }
        }
    }
}
