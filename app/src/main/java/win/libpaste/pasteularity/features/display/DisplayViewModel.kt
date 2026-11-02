package win.libpaste.pasteularity.features.display

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import win.libpaste.pasteularity.AppContainer
import win.libpaste.pasteularity.data.DisplayProfile
import win.libpaste.pasteularity.shell.getProps
import win.libpaste.pasteularity.shell.setPropVerified
import win.libpaste.pasteularity.shell.userMessage

data class DisplayState(
    val values: Map<String, String> = emptyMap(),
    val limits: HeadsetLimits = limitsFor(""),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val customWidth: String = "",
    val customHeight: String = "",
)

private const val PROP_DEVICE = "ro.product.device"

class DisplayViewModel(private val container: AppContainer) : ViewModel() {
    private val shell = container.shell
    private val messenger = container.messenger
    private val mutable = MutableStateFlow(DisplayState())
    val state = mutable.asStateFlow()
    val profiles = container.settings.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            shell.isConnected.collect { connected -> if (connected) load() }
        }
    }

    fun load() {
        viewModelScope.launch {
            mutable.update { it.copy(loading = true) }
            val all = shell.getProps(allDisplayProps + PROP_DEVICE).getOrDefault(emptyMap())
            mutable.update {
                it.copy(
                    values = allDisplayProps.associateWith { prop -> all[prop].orEmpty() },
                    limits = limitsFor(all[PROP_DEVICE].orEmpty()),
                    loading = false,
                )
            }
        }
    }

    fun setCustomWidth(value: String) = mutable.update { it.copy(customWidth = value.filter(Char::isDigit).take(5)) }

    fun setCustomHeight(value: String) = mutable.update { it.copy(customHeight = value.filter(Char::isDigit).take(5)) }

    fun set(prop: String, value: Int) = apply(mapOf(prop to value.toString()), "Applied.")

    fun setTexture(width: Int, height: Int) = apply(
        mapOf(PROP_TEXTURE_WIDTH to width.toString(), PROP_TEXTURE_HEIGHT to height.toString()),
        "Texture size applied.",
    )

    fun reset(vararg props: String) = apply(props.associateWith { "" }, "Reset to default.")

    fun resetAll() = apply(allDisplayProps.associateWith { "" }, "All display settings reset.")

    private fun apply(changes: Map<String, String>, done: String) {
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            var failure: Throwable? = null
            changes.forEach { (prop, value) ->
                shell.setPropVerified(prop, value)
                    .onSuccess { mutable.update { it.copy(values = it.values + (prop to value)) } }
                    .onFailure { failure = it }
            }
            val error = failure
            if (error == null) messenger.success(done) else messenger.error(error.userMessage())
            mutable.update { it.copy(busy = false) }
        }
    }

    fun saveProfile(name: String) {
        val values = state.value.values.filterValues { it.isNotEmpty() }
        viewModelScope.launch {
            val others = profiles.value.filterNot { it.name == name }
            container.settings.setProfiles(others + DisplayProfile(name, values))
            messenger.success("Profile saved.")
        }
    }

    fun deleteProfile(profile: DisplayProfile) {
        viewModelScope.launch { container.settings.setProfiles(profiles.value - profile) }
    }

    /** Clears anything the profile does not set, so applying one always gives the same result. */
    fun applyProfile(profile: DisplayProfile) {
        val safe = profile.values.filter { (prop, value) -> isValidPropValue(prop, value) }
        apply(allDisplayProps.associateWith { safe[it].orEmpty() }, "Applied ${profile.name}.")
    }
}
