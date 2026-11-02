package win.libpaste.pasteularity.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Velocity
import win.libpaste.pasteularity.ui.theme.Spacing

private const val MAX_LINE_CHARS = 600

/**
 * Scrolling monospace output for streams that never stop. It follows the newest line until the
 * user scrolls up. Selection tracks every visible line, which is too slow while lines are
 * arriving, so callers turn it on only when the output is at rest.
 */
@Composable
fun <T : Any> OutputList(
    lines: List<T>,
    lineKey: (T) -> Any,
    lineText: (T) -> String,
    lineStyle: (T) -> TextStyle,
    selectable: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var following by remember { mutableStateOf(true) }
    val scrollTracker = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f) following = false
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f) following = true
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (available.y < 0f) following = true
                return Velocity.Zero
            }
        }
    }

    val newest = lines.lastOrNull()?.let(lineKey)
    LaunchedEffect(newest, following) {
        if (following && lines.isNotEmpty()) listState.requestScrollToItem(lines.lastIndex)
    }

    Box(modifier = modifier.nestedScroll(scrollTracker)) {
        val list: @Composable () -> Unit = {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(Spacing.M)) {
                items(lines, key = lineKey, contentType = { 0 }) { line ->
                    BasicText(text = lineText(line).take(MAX_LINE_CHARS), style = lineStyle(line))
                }
            }
        }
        if (selectable) SelectionContainer(content = list) else list()
        if (!following) {
            PstuButton(
                text = "Jump to latest",
                icon = Icons.Outlined.ArrowDownward,
                onClick = { following = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.M),
            )
        }
    }
}
