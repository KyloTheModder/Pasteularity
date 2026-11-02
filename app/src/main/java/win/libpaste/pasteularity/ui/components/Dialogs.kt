package win.libpaste.pasteularity.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import win.libpaste.pasteularity.ui.theme.PstuColors
import win.libpaste.pasteularity.ui.theme.Spacing

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PstuColors.SurfaceRaised,
        tonalElevation = 0.dp,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text = message, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            PstuButton(
                text = confirmLabel,
                style = if (destructive) ButtonStyle.Danger else ButtonStyle.Primary,
                onClick = {
                    onDismiss()
                    onConfirm()
                },
            )
        },
        dismissButton = { PstuButton(text = "Cancel", style = ButtonStyle.Quiet, onClick = onDismiss) },
    )
}

/** Asks for one line of text. [validate] returns an error to show, or null when the value is fine. */
@Composable
fun InputDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    validate: (String) -> String? = { null },
) {
    var value by remember { mutableStateOf(initial) }
    val error = if (value.isBlank()) null else validate(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PstuColors.SurfaceRaised,
        tonalElevation = 0.dp,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                PstuTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = label,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(text = error, style = MaterialTheme.typography.bodyMedium, color = PstuColors.Danger)
                }
            }
        },
        confirmButton = {
            PstuButton(
                text = confirmLabel,
                style = ButtonStyle.Primary,
                enabled = value.isNotBlank() && error == null,
                onClick = {
                    onDismiss()
                    onConfirm(value.trim())
                },
            )
        },
        dismissButton = { PstuButton(text = "Cancel", style = ButtonStyle.Quiet, onClick = onDismiss) },
    )
}
