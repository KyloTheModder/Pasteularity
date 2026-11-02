package win.libpaste.pasteularity.features.display

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import win.libpaste.pasteularity.data.DisplayProfile
import win.libpaste.pasteularity.ui.components.ButtonStyle
import win.libpaste.pasteularity.ui.components.Choice
import win.libpaste.pasteularity.ui.components.ChoiceRow
import win.libpaste.pasteularity.ui.components.ConfirmDialog
import win.libpaste.pasteularity.ui.components.InputDialog
import win.libpaste.pasteularity.ui.components.Panel
import win.libpaste.pasteularity.ui.components.PstuButton
import win.libpaste.pasteularity.ui.components.PstuTextField
import win.libpaste.pasteularity.ui.components.SectionTitle
import win.libpaste.pasteularity.ui.components.SettingRow
import win.libpaste.pasteularity.ui.components.WarningNote
import win.libpaste.pasteularity.ui.theme.PstuColors
import win.libpaste.pasteularity.ui.theme.Spacing

private const val CLOCK_WARNING = "Raises clock speeds. Only use this if you know the optimal settings for your hardware."

class DisplayActions(
    val onSet: (String, Int) -> Unit,
    val onSetTexture: (Int, Int) -> Unit,
    val onCustomWidth: (String) -> Unit,
    val onCustomHeight: (String) -> Unit,
    val onReset: (Array<String>) -> Unit,
    val onResetAll: () -> Unit,
    val onSaveProfile: (String) -> Unit,
    val onApplyProfile: (DisplayProfile) -> Unit,
    val onDeleteProfile: (DisplayProfile) -> Unit,
)

@Composable
fun DisplayScreen(state: DisplayState, profiles: List<DisplayProfile>, actions: DisplayActions) {
    var confirmResetAll by remember { mutableStateOf(false) }
    var savingProfile by remember { mutableStateOf(false) }
    var deletingProfile by remember { mutableStateOf<DisplayProfile?>(null) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = Spacing.L, end = Spacing.L, bottom = Spacing.L),
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.M),
        ) {
            Text(
                text = "These values reset when the headset reboots. Apps read them at launch, so restart a game after changing them.",
                style = MaterialTheme.typography.bodyLarge,
                color = PstuColors.TextMuted,
            )
            WarningNote(
                "Only use these if you know the optimal settings for your hardware. " +
                    "Wrong values can cause overheating, throttling and crashes.",
            )
            LevelSetting(
                title = "Refresh rate",
                description = "How many times per second the display updates. Higher is smoother and uses more battery.",
                prop = PROP_REFRESH,
                options = state.limits.refreshRates,
                unit = " Hz",
                state = state,
                actions = actions,
            )
            TextureSetting(state, actions)
            LevelSetting(
                title = "Fixed foveated rendering",
                description = "Lowers detail at the edges of the view to save GPU power. 0 is off, 4 is strongest.",
                prop = PROP_FOVEATION,
                options = foveationLevels,
                unit = "",
                state = state,
                actions = actions,
            )
            LevelSetting(
                title = "CPU level",
                description = "The CPU clock floor. Higher levels run hotter and drain the battery faster.",
                prop = PROP_CPU,
                options = (0..state.limits.maxPerformanceLevel).toList(),
                unit = "",
                state = state,
                actions = actions,
                warning = CLOCK_WARNING,
            )
            LevelSetting(
                title = "GPU level",
                description = "The GPU clock floor. Higher levels run hotter and drain the battery faster.",
                prop = PROP_GPU,
                options = (0..state.limits.maxPerformanceLevel).toList(),
                unit = "",
                state = state,
                actions = actions,
                warning = CLOCK_WARNING,
            )
        }
        Panel(modifier = Modifier.width(320.dp).fillMaxHeight()) {
            SectionTitle("Profiles")
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.M),
            ) {
                if (profiles.isEmpty()) {
                    Text(
                        text = "No profiles yet. Set the values you want, then save them here to apply them in one step.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = PstuColors.TextMuted,
                    )
                }
                profiles.forEach { profile ->
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                        Text(text = profile.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = profileSummary(profile),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PstuColors.TextMuted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                            PstuButton(
                                text = "Apply",
                                style = ButtonStyle.Primary,
                                enabled = !state.busy,
                                onClick = { actions.onApplyProfile(profile) },
                            )
                            PstuButton(
                                text = "Delete",
                                style = ButtonStyle.Quiet,
                                onClick = { deletingProfile = profile },
                            )
                        }
                    }
                }
            }
            PstuButton(
                text = "Save current values",
                icon = Icons.Outlined.BookmarkAdd,
                onClick = { savingProfile = true },
                modifier = Modifier.fillMaxWidth(),
            )
            PstuButton(
                text = "Reset everything",
                icon = Icons.Outlined.RestartAlt,
                style = ButtonStyle.Danger,
                enabled = !state.busy,
                onClick = { confirmResetAll = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmResetAll) {
        ConfirmDialog(
            title = "Reset all display settings?",
            message = "Every value on this screen goes back to the system default.",
            confirmLabel = "Reset",
            onConfirm = actions.onResetAll,
            onDismiss = { confirmResetAll = false },
        )
    }
    if (savingProfile) {
        InputDialog(
            title = "Save profile",
            label = "Name",
            confirmLabel = "Save",
            onConfirm = actions.onSaveProfile,
            onDismiss = { savingProfile = false },
        )
    }
    deletingProfile?.let { profile ->
        ConfirmDialog(
            title = "Delete ${profile.name}?",
            message = "This only removes the saved profile. Current values do not change.",
            confirmLabel = "Delete",
            onConfirm = { actions.onDeleteProfile(profile) },
            onDismiss = { deletingProfile = null },
        )
    }
}

private fun profileSummary(profile: DisplayProfile): String {
    val parts = listOfNotNull(
        profile.values[PROP_REFRESH]?.let { "$it Hz" },
        profile.values[PROP_TEXTURE_WIDTH]?.let { "${it}x${profile.values[PROP_TEXTURE_HEIGHT].orEmpty()}" },
        profile.values[PROP_FOVEATION]?.let { "FFR $it" },
        profile.values[PROP_CPU]?.let { "CPU $it" },
        profile.values[PROP_GPU]?.let { "GPU $it" },
    )
    return parts.joinToString(", ").ifEmpty { "All defaults" }
}

@Composable
private fun LevelSetting(
    title: String,
    description: String,
    prop: String,
    options: List<Int>,
    unit: String,
    state: DisplayState,
    actions: DisplayActions,
    warning: String? = null,
) {
    val current = state.values[prop].orEmpty()
    Panel(modifier = Modifier.fillMaxWidth()) {
        SettingRow(
            title = title,
            description = description,
            value = if (current.isEmpty()) "Default" else "$current$unit",
        ) {
            warning?.let { WarningNote(it) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChoiceRow(
                    choices = options.map { Choice(it, "$it$unit") },
                    selected = current.toIntOrNull(),
                    enabled = !state.busy,
                    onSelect = { actions.onSet(prop, it) },
                    modifier = Modifier.weight(1f),
                )
                PstuButton(
                    text = "Reset",
                    style = ButtonStyle.Quiet,
                    enabled = !state.busy && current.isNotEmpty(),
                    onClick = { actions.onReset(arrayOf(prop)) },
                )
            }
        }
    }
}

@Composable
private fun TextureSetting(state: DisplayState, actions: DisplayActions) {
    val width = state.values[PROP_TEXTURE_WIDTH].orEmpty()
    val height = state.values[PROP_TEXTURE_HEIGHT].orEmpty()
    Panel(modifier = Modifier.fillMaxWidth()) {
        SettingRow(
            title = "Texture resolution",
            description = "The size apps render each eye at. Sharper at higher sizes, but heavier on the GPU.",
            value = if (width.isEmpty() && height.isEmpty()) "Default" else "$width x $height",
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChoiceRow(
                    choices = texturePresets.map { Choice(it, it.toString()) },
                    selected = width.toIntOrNull()?.takeIf { width == height },
                    enabled = !state.busy,
                    onSelect = { actions.onSetTexture(it, it) },
                    modifier = Modifier.weight(1f),
                )
                PstuButton(
                    text = "Reset",
                    style = ButtonStyle.Quiet,
                    enabled = !state.busy && (width.isNotEmpty() || height.isNotEmpty()),
                    onClick = { actions.onReset(arrayOf(PROP_TEXTURE_WIDTH, PROP_TEXTURE_HEIGHT)) },
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PstuTextField(
                    value = state.customWidth,
                    onValueChange = actions.onCustomWidth,
                    label = "Custom width",
                    modifier = Modifier.weight(1f),
                    isError = state.customWidth.isNotEmpty() && validateTextureSize(state.customWidth) != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                PstuTextField(
                    value = state.customHeight,
                    onValueChange = actions.onCustomHeight,
                    label = "Custom height",
                    modifier = Modifier.weight(1f),
                    isError = state.customHeight.isNotEmpty() && validateTextureSize(state.customHeight) != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                PstuButton(
                    text = "Apply size",
                    enabled = !state.busy &&
                        validateTextureSize(state.customWidth) == null &&
                        validateTextureSize(state.customHeight) == null,
                    onClick = { actions.onSetTexture(state.customWidth.toInt(), state.customHeight.toInt()) },
                )
            }
        }
    }
}
