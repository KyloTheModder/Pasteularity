package win.libpaste.pasteularity.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import win.libpaste.pasteularity.ui.theme.PstuColors

private const val FOLLOW_RATE = 0.2f
private const val FADE_RATE = 0.12f
private const val PULL = 0.14f

/** A jittered grid, so the field looks scattered but never clumps. Stable for a given size. */
internal fun generateDots(width: Float, height: Float, spacing: Float, seed: Int = 7): List<Offset> {
    val random = Random(seed)
    val columns = (width / spacing).toInt() + 1
    val rows = (height / spacing).toInt() + 1
    return List(columns * rows) { index ->
        val jitterX = (random.nextFloat() - 0.5f) * spacing * 0.7f
        val jitterY = (random.nextFloat() - 0.5f) * spacing * 0.7f
        Offset(
            ((index % columns + 0.5f) * spacing + jitterX).coerceIn(0f, width),
            ((index / columns + 0.5f) * spacing + jitterY).coerceIn(0f, height),
        )
    }
}

/** The eased pointer. It only needs frames while it is catching up or fading. */
@Stable
class DotWebState(initialPointer: Offset? = null) {
    private var target by mutableStateOf(initialPointer)

    var position by mutableStateOf(initialPointer ?: Offset.Zero)
        private set
    var strength by mutableFloatStateOf(if (initialPointer != null) 1f else 0f)
        private set

    val settled: Boolean
        get() {
            val goal = target
            return if (goal == null) strength == 0f else strength == 1f && position == goal
        }

    fun moveTo(pointer: Offset?) {
        if (pointer != null && strength == 0f) position = pointer
        target = pointer
    }

    fun step() {
        val goal = target
        if (goal != null) {
            val next = position + (goal - position) * FOLLOW_RATE
            position = if ((goal - next).getDistance() < 0.5f) goal else next
        }
        val wanted = if (goal != null) 1f else 0f
        val nextStrength = strength + (wanted - strength) * FADE_RATE
        strength = if (abs(wanted - nextStrength) < 0.01f) wanted else nextStrength
    }
}

/**
 * The app background: a field of dots that link up into a web around the pointer. It watches
 * pointer events without consuming them and draws nothing between moves, so an idle screen
 * costs no frames on the headset. The web has its own layer, so moving the pointer redraws the
 * web alone and not the whole screen on top of it.
 */
@Composable
fun DotWebBackdrop(
    state: DotWebState = remember { DotWebState() },
    content: @Composable BoxScope.() -> Unit,
) {
    LaunchedEffect(state) {
        while (true) {
            snapshotFlow { state.settled }.first { !it }
            while (!state.settled) withFrameNanos { state.step() }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PstuColors.Background)
            .pointerInput(state) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        val fingerLifted = event.type == PointerEventType.Release && change.type == PointerType.Touch
                        state.moveTo(if (event.type == PointerEventType.Exit || fingerLifted) null else change.position)
                    }
                }
            },
    ) {
        Spacer(modifier = Modifier.fillMaxSize().graphicsLayer { }.drawWithCache {
            val dots = generateDots(size.width, size.height, 56.dp.toPx())
            val nearX = FloatArray(dots.size)
            val nearY = FloatArray(dots.size)
            val nearCloseness = FloatArray(dots.size)
            val reach = 176.dp.toPx()
            val linkRange = 96.dp.toPx()
            val dotRadius = 1.5.dp.toPx()
            val lineWidth = 1.dp.toPx()
            onDrawBehind {
                val pointer = state.position
                val strength = state.strength
                var nearCount = 0
                for (dot in dots) {
                    val closeness = (1f - (dot - pointer).getDistance() / reach).coerceAtLeast(0f) * strength
                    if (closeness == 0f) {
                        drawCircle(color = PstuColors.Outline, radius = dotRadius, center = dot)
                    } else {
                        val placed = dot + (pointer - dot) * (closeness * PULL)
                        drawCircle(
                            color = lerp(PstuColors.Outline, PstuColors.Accent, closeness),
                            radius = dotRadius * (1f + closeness),
                            center = placed,
                        )
                        nearX[nearCount] = placed.x
                        nearY[nearCount] = placed.y
                        nearCloseness[nearCount] = closeness
                        nearCount++
                    }
                }
                for (from in 0 until nearCount) {
                    val start = Offset(nearX[from], nearY[from])
                    drawLine(PstuColors.Accent.copy(alpha = nearCloseness[from] * 0.45f), pointer, start, lineWidth)
                    for (to in from + 1 until nearCount) {
                        val end = Offset(nearX[to], nearY[to])
                        val gap = (start - end).getDistance()
                        if (gap < linkRange) {
                            val alpha = minOf(nearCloseness[from], nearCloseness[to]) * (1f - gap / linkRange)
                            drawLine(PstuColors.Accent.copy(alpha = alpha), start, end, lineWidth)
                        }
                    }
                }
            }
        })
        content()
    }
}
