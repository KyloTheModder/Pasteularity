package win.libpaste.pasteularity.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import win.libpaste.pasteularity.shell.BytezukuState
import win.libpaste.pasteularity.ui.components.ButtonStyle
import win.libpaste.pasteularity.ui.components.Panel
import win.libpaste.pasteularity.ui.components.PstuButton
import win.libpaste.pasteularity.ui.components.PstuTextField
import win.libpaste.pasteularity.ui.theme.PstuColors
import win.libpaste.pasteularity.ui.theme.PstuShape
import win.libpaste.pasteularity.ui.theme.Spacing
import win.libpaste.pasteularity.ui.theme.TouchTarget

private val stepTitles = listOf("Developer Mode", "Wireless debugging", "Pair this headset")

@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onStep: (Int) -> Unit,
    onCode: (String) -> Unit,
    onPort: (String) -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onPair: () -> Unit,
    onUseBytezuku: () -> Unit,
    onSkip: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.XL),
        horizontalArrangement = Arrangement.spacedBy(Spacing.XL),
    ) {
        Column(modifier = Modifier.width(320.dp).fillMaxHeight()) {
            Text(text = "Set up Pasteularity", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "It needs shell access to change system settings. This takes about two minutes and you only do it once.",
                style = MaterialTheme.typography.bodyLarge,
                color = PstuColors.TextMuted,
                modifier = Modifier.padding(top = Spacing.S, bottom = Spacing.L),
            )
            stepTitles.forEachIndexed { index, title ->
                StepItem(index, title, selected = state.step == index, onClick = { onStep(index) })
            }
            Box(modifier = Modifier.weight(1f))
            PstuButton(text = "Skip for now", style = ButtonStyle.Quiet, onClick = onSkip)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.M),
        ) {
            when (state.step) {
                0 -> DeveloperModeStep(onNext = { onStep(1) })
                1 -> WirelessDebuggingStep(state, onOpenDeveloperOptions, onNext = { onStep(2) })
                else -> PairStep(state, onCode, onPort, onPair)
            }
            BytezukuCard(state.bytezuku, onUseBytezuku)
        }
    }
}

@Composable
private fun StepItem(index: Int, title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clip(PstuShape)
            .background(if (selected) PstuColors.Surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.M),
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(PstuShape)
                .background(if (selected) PstuColors.Accent else PstuColors.SurfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) PstuColors.OnAccent else PstuColors.Text,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun Instructions(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        lines.forEachIndexed { index, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = PstuColors.TextMuted,
                    modifier = Modifier.width(24.dp),
                )
                Text(text = line, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun DeveloperModeStep(onNext: () -> Unit) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Text(text = "Turn on Developer Mode", style = MaterialTheme.typography.titleLarge)
        Instructions(
            listOf(
                "On your phone, open the Meta Horizon app and go to your headset's settings.",
                "Open Developer Mode and turn it on. Meta asks you to register as a developer the first time.",
                "Restart the headset if the option does not appear in the next step.",
            ),
        )
        PstuButton(text = "Next", style = ButtonStyle.Primary, onClick = onNext)
    }
}

@Composable
private fun WirelessDebuggingStep(state: OnboardingState, onOpenDeveloperOptions: () -> Unit, onNext: () -> Unit) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Text(text = "Turn on Wireless debugging", style = MaterialTheme.typography.titleLarge)
        if (!state.supportsWirelessDebugging) {
            Text(
                text = "This headset runs a version of Android older than 11, which has no Wireless debugging. Use ByteZuku below.",
                style = MaterialTheme.typography.bodyLarge,
                color = PstuColors.Danger,
            )
        }
        Instructions(
            listOf(
                "Open Developer options with the button below. Move it next to this window.",
                "If it says they are unavailable, check Developer Mode in the phone app and restart the headset.",
                "Turn on Wireless debugging. The headset must be on Wi-Fi.",
                "Select Wireless debugging, then Pair device with pairing code.",
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            PstuButton(text = "Open Developer options", icon = Icons.Outlined.Settings, onClick = onOpenDeveloperOptions)
            PstuButton(text = "Next", style = ButtonStyle.Primary, onClick = onNext)
        }
        state.error?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = PstuColors.Danger)
        }
    }
}

@Composable
private fun PairStep(
    state: OnboardingState,
    onCode: (String) -> Unit,
    onPort: (String) -> Unit,
    onPair: () -> Unit,
) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Text(text = "Enter the pairing code", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Keep the pairing dialog open in Developer options while you type. The code expires when it closes.",
            style = MaterialTheme.typography.bodyLarge,
            color = PstuColors.TextMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
            PstuTextField(
                value = state.pairingCode,
                onValueChange = onCode,
                label = "Six-digit code",
                modifier = Modifier.weight(1f),
                enabled = !state.pairing,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            PstuTextField(
                value = state.port,
                onValueChange = onPort,
                label = if (state.portDetected) "Port (found automatically)" else "Port",
                modifier = Modifier.weight(1f),
                enabled = !state.pairing,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        if (!state.portDetected) {
            Text(
                text = "The port is the number after the colon under IP address & Port in the pairing dialog.",
                style = MaterialTheme.typography.bodyMedium,
                color = PstuColors.TextMuted,
            )
        }
        state.error?.let {
            Text(text = it, style = MaterialTheme.typography.bodyLarge, color = PstuColors.Danger)
        }
        PstuButton(
            text = if (state.pairing) "Pairing" else "Pair",
            style = ButtonStyle.Primary,
            loading = state.pairing,
            enabled = state.canPair,
            onClick = onPair,
        )
    }
}

@Composable
private fun BytezukuCard(state: BytezukuState, onUseBytezuku: () -> Unit) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Text(text = "Already use ByteZuku?", style = MaterialTheme.typography.titleMedium)
        Text(
            text = when (state) {
                BytezukuState.NotRunning -> "ByteZuku is not running. Start it from its app and this option becomes available."
                BytezukuState.NeedsPermission -> "ByteZuku is running. Allow Pasteularity to use it and you can skip pairing."
                BytezukuState.Ready -> "ByteZuku is running and Pasteularity has permission. You can skip pairing."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = PstuColors.TextMuted,
        )
        if (state != BytezukuState.NotRunning) {
            PstuButton(
                text = if (state == BytezukuState.Ready) "Use ByteZuku" else "Allow ByteZuku",
                onClick = onUseBytezuku,
            )
        }
    }
}
