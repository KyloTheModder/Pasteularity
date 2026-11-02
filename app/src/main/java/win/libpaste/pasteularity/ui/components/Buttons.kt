package win.libpaste.pasteularity.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import win.libpaste.pasteularity.ui.theme.PstuColors
import win.libpaste.pasteularity.ui.theme.PstuShape
import win.libpaste.pasteularity.ui.theme.Spacing
import win.libpaste.pasteularity.ui.theme.TouchTarget

/** [Outline] is for buttons on a raised surface like a dialog, where [Secondary] would disappear. */
enum class ButtonStyle { Primary, Secondary, Outline, Danger, Quiet }

@Composable
fun PstuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: ButtonStyle = ButtonStyle.Secondary,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val container = when (style) {
        ButtonStyle.Primary -> PstuColors.Accent
        ButtonStyle.Secondary -> PstuColors.SurfaceRaised
        ButtonStyle.Outline -> PstuColors.Background
        ButtonStyle.Danger, ButtonStyle.Quiet -> Color.Transparent
    }
    val content = when (style) {
        ButtonStyle.Primary -> PstuColors.OnAccent
        ButtonStyle.Danger -> PstuColors.Danger
        else -> PstuColors.Text
    }
    val border = when (style) {
        ButtonStyle.Danger -> BorderStroke(1.dp, PstuColors.Danger)
        ButtonStyle.Outline -> BorderStroke(1.dp, PstuColors.Outline)
        else -> null
    }
    val active = enabled && !loading

    Surface(
        onClick = onClick,
        enabled = active,
        modifier = modifier.heightIn(min = TouchTarget).widthIn(min = TouchTarget),
        shape = PstuShape,
        color = if (active || loading) container else container.copy(alpha = 0.4f),
        contentColor = if (active || loading) content else content.copy(alpha = 0.5f),
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(PaddingValues(horizontal = Spacing.M, vertical = Spacing.S)),
            horizontalArrangement = Arrangement.spacedBy(Spacing.S, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = content,
                    strokeWidth = 2.dp,
                )
                icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

data class Choice<T>(val value: T, val label: String)

/** A segmented picker. Used instead of sliders and switches, which are hard to hit from a distance. */
@Composable
fun <T> ChoiceRow(
    choices: List<Choice<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
        choices.forEach { choice ->
            val isSelected = choice.value == selected
            Box(
                modifier = Modifier
                    .heightIn(min = TouchTarget)
                    .widthIn(min = 72.dp)
                    .clip(PstuShape)
                    .background(if (isSelected) PstuColors.Accent else PstuColors.Background)
                    .border(1.dp, if (isSelected) PstuColors.Accent else PstuColors.Outline, PstuShape)
                    .clickable(enabled = enabled) { onSelect(choice.value) }
                    .padding(horizontal = Spacing.M),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = choice.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        isSelected -> PstuColors.OnAccent
                        enabled -> PstuColors.Text
                        else -> PstuColors.TextMuted
                    },
                )
            }
        }
    }
}
