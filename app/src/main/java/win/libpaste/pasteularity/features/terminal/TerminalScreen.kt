package win.libpaste.pasteularity.features.terminal

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import win.libpaste.pasteularity.data.Snippet
import win.libpaste.pasteularity.ui.components.ButtonStyle
import win.libpaste.pasteularity.ui.components.InputDialog
import win.libpaste.pasteularity.ui.components.MessageState
import win.libpaste.pasteularity.ui.components.OutputList
import win.libpaste.pasteularity.ui.components.PstuButton
import win.libpaste.pasteularity.ui.theme.MonoStyle
import win.libpaste.pasteularity.ui.theme.PstuColors
import win.libpaste.pasteularity.ui.theme.PstuShape
import win.libpaste.pasteularity.ui.theme.Spacing
import win.libpaste.pasteularity.ui.theme.TouchTarget

private val outputStyle = MonoStyle.copy(color = PstuColors.Text)
private val commandStyle = outputStyle.copy(fontWeight = FontWeight.Bold)
private val statusStyle = MonoStyle.copy(color = PstuColors.TextMuted)

private fun lineStyle(line: TerminalLine): TextStyle = when (line.kind) {
    LineKind.Command -> commandStyle
    LineKind.Output -> outputStyle
    LineKind.Status -> statusStyle
}

class TerminalActions(
    val onInput: (String) -> Unit,
    val onRun: () -> Unit,
    val onStop: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onCopy: () -> Unit,
    val onClear: () -> Unit,
    val onRunSnippet: (String) -> Unit,
    val onAddSnippet: (String, String) -> Unit,
    val onRemoveSnippet: (Snippet) -> Unit,
    val onToggleEditing: () -> Unit,
)

@Composable
fun TerminalScreen(state: TerminalState, snippets: List<Snippet>, actions: TerminalActions) {
    var savingCommand by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = Spacing.L, end = Spacing.L, bottom = Spacing.L),
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Commands run as shell (uid 2000), not root.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PstuColors.TextMuted,
                    modifier = Modifier.weight(1f),
                )
                PstuButton(text = "Copy", icon = Icons.Outlined.ContentCopy, style = ButtonStyle.Quiet, onClick = actions.onCopy)
                PstuButton(text = "Clear", icon = Icons.Outlined.DeleteSweep, style = ButtonStyle.Quiet, onClick = actions.onClear)
            }
            OutputPane(state.lines, state.running, modifier = Modifier.weight(1f).fillMaxWidth())
            InputRow(state.input, state.cwd, state.running, actions)
        }
        SnippetPane(
            snippets = snippets,
            editing = state.editingSnippets,
            running = state.running,
            canSave = state.input.isNotBlank(),
            onRun = actions.onRunSnippet,
            onSave = { savingCommand = state.input.trim() },
            onRemove = actions.onRemoveSnippet,
            onToggleEditing = actions.onToggleEditing,
            modifier = Modifier.width(280.dp).fillMaxHeight(),
        )
    }

    savingCommand?.let { command ->
        InputDialog(
            title = "Save snippet",
            label = "Name",
            confirmLabel = "Save",
            onConfirm = { actions.onAddSnippet(it, command) },
            onDismiss = { savingCommand = null },
        )
    }
}

@Composable
private fun OutputPane(lines: List<TerminalLine>, running: Boolean, modifier: Modifier) {
    Box(modifier = modifier.clip(PstuShape).background(PstuColors.Surface)) {
        if (lines.isEmpty()) {
            MessageState(
                icon = Icons.Outlined.Terminal,
                title = "No output yet",
                message = "Type a command below or pick a snippet.",
            )
        } else {
            OutputList(
                lines = lines,
                lineKey = TerminalLine::id,
                lineText = TerminalLine::text,
                lineStyle = ::lineStyle,
                selectable = !running,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun InputRow(input: String, cwd: String, running: Boolean, actions: TerminalActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = input,
            onValueChange = actions.onInput,
            label = { Text(cwd, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            textStyle = MonoStyle,
            shape = PstuShape,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = PstuColors.Outline,
                focusedBorderColor = PstuColors.Accent,
                focusedLabelColor = PstuColors.Text,
                unfocusedLabelColor = PstuColors.TextMuted,
                cursorColor = PstuColors.Accent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { actions.onRun() }),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = TouchTarget)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> { actions.onPrevious(); true }
                        Key.DirectionDown -> { actions.onNext(); true }
                        Key.Enter -> { actions.onRun(); true }
                        else -> false
                    }
                },
        )
        PstuButton(text = "Previous", icon = Icons.Outlined.ArrowUpward, onClick = actions.onPrevious)
        PstuButton(text = "Next", icon = Icons.Outlined.ArrowDownward, onClick = actions.onNext)
        if (running) {
            PstuButton(text = "Stop", icon = Icons.Outlined.Stop, style = ButtonStyle.Danger, onClick = actions.onStop)
        } else {
            PstuButton(
                text = "Run",
                icon = Icons.Outlined.PlayArrow,
                style = ButtonStyle.Primary,
                enabled = input.isNotBlank(),
                onClick = actions.onRun,
            )
        }
    }
}

@Composable
private fun SnippetPane(
    snippets: List<Snippet>,
    editing: Boolean,
    running: Boolean,
    canSave: Boolean,
    onRun: (String) -> Unit,
    onSave: () -> Unit,
    onRemove: (Snippet) -> Unit,
    onToggleEditing: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.clip(PstuShape).background(PstuColors.Surface).padding(Spacing.M),
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Snippets", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            PstuButton(
                text = if (editing) "Done" else "Edit",
                style = ButtonStyle.Quiet,
                onClick = onToggleEditing,
            )
        }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
            items(snippets.distinct(), key = { it.label + "\n" + it.command }) { snippet ->
                if (editing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = snippet.label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        PstuButton(text = "Remove", style = ButtonStyle.Danger, onClick = { onRemove(snippet) })
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = TouchTarget)
                            .clip(PstuShape)
                            .background(PstuColors.SurfaceRaised)
                            .clickable(enabled = !running) { onRun(snippet.command) }
                            .padding(horizontal = Spacing.M, vertical = Spacing.S),
                    ) {
                        Text(text = snippet.label, style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = snippet.command,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PstuColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        PstuButton(
            text = "Save current command",
            icon = Icons.Outlined.BookmarkAdd,
            enabled = canSave,
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
