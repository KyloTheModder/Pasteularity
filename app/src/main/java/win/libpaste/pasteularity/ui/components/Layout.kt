package win.libpaste.pasteularity.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import win.libpaste.pasteularity.ui.theme.PstuColors
import win.libpaste.pasteularity.ui.theme.PstuShape
import win.libpaste.pasteularity.ui.theme.Spacing
import win.libpaste.pasteularity.ui.theme.TouchTarget

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(PstuShape)
            .background(PstuColors.Surface)
            .padding(Spacing.L),
        verticalArrangement = Arrangement.spacedBy(Spacing.M),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.titleLarge, modifier = modifier)
}

/** One setting: title, a one-line explanation, its current value, and its controls. */
@Composable
fun SettingRow(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    controls: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (value != null) {
                Text(text = value, style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = PstuColors.TextMuted,
        )
        controls()
    }
}

/** A caution that stays on screen next to the setting it applies to. */
@Composable
fun WarningNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = "Warning",
            tint = PstuColors.Danger,
            modifier = Modifier.size(22.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = PstuColors.Danger)
    }
}

/** A large tappable card for a single action. */
@Composable
fun ActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(PstuShape)
            .background(PstuColors.Surface)
            .clickable(enabled = !loading, onClick = onClick)
            .heightIn(min = 96.dp)
            .padding(horizontal = Spacing.L, vertical = Spacing.M),
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = PstuColors.TextMuted,
            )
        }
    }
}

/** Centered placeholder shared by empty, error and "not connected" states. */
@Composable
fun MessageState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize().padding(Spacing.XL), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = PstuColors.TextMuted)
            Text(text = title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = PstuColors.TextMuted,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Box(modifier = Modifier.padding(top = Spacing.S)) { action() }
            }
        }
    }
}

@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun PstuTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label, overflow = TextOverflow.Ellipsis, maxLines = 1) },
        modifier = modifier.heightIn(min = TouchTarget),
        singleLine = singleLine,
        enabled = enabled,
        isError = isError,
        textStyle = textStyle,
        shape = PstuShape,
        keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = PstuColors.Outline,
            focusedBorderColor = PstuColors.Accent,
            focusedLabelColor = PstuColors.Text,
            unfocusedLabelColor = PstuColors.TextMuted,
            cursorColor = PstuColors.Accent,
        ),
    )
}

/** Selection box with a full 56dp hit area, for controllers and hand tracking. */
@Composable
fun SelectBox(checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(TouchTarget)
            .clip(PstuShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(PstuShape)
                .background(if (checked) PstuColors.Accent else PstuColors.Outline),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "Selected",
                    tint = PstuColors.OnAccent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
