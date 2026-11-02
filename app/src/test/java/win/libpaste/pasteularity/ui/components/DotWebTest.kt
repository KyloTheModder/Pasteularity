package win.libpaste.pasteularity.ui.components

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DotWebTest {
    @Test
    fun dotsStayOnScreenAndAreStableBetweenRuns() {
        val dots = generateDots(1280f, 800f, 56f)
        assertTrue(dots.isNotEmpty())
        assertTrue(dots.all { it.x in 0f..1280f && it.y in 0f..800f })
        assertEquals(dots, generateDots(1280f, 800f, 56f))
    }

    @Test
    fun anIdleWebNeedsNoFrames() {
        assertTrue(DotWebState().settled)
        assertTrue(DotWebState(Offset(10f, 10f)).settled)
    }

    @Test
    fun theWebCatchesUpToThePointerThenStops() {
        val state = DotWebState()
        state.moveTo(Offset(100f, 100f))
        assertFalse(state.settled)
        repeat(200) { state.step() }
        assertEquals(Offset(100f, 100f), state.position)
        assertEquals(1f, state.strength)
        assertTrue(state.settled)
    }

    @Test
    fun theWebFadesOutWhenThePointerLeaves() {
        val state = DotWebState(Offset(100f, 100f))
        state.moveTo(null)
        assertFalse(state.settled)
        repeat(200) { state.step() }
        assertEquals(0f, state.strength)
        assertTrue(state.settled)
    }
}
