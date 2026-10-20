package com.example.mycar.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pan handling that turns the car's delta stream into injectable drags. These pin down the
 * behaviours that made drag-scroll misbehave on the car: overlapping swipes, a wandering anchor,
 * zero-length swipes that land as taps, and dropped movement at the edges.
 */
class PanControllerTest {

    private val width = 1000
    private val height = 1000
    private val centre = 500f

    /** A step from the screen centre by ([dx], [dy]) in phone pixels. */
    private fun scroll(dx: Float, dy: Float = 0f, at: Long = 1_000L) =
        PanController().let { pan -> pan.onScroll(dx, dy, 1f, width, height, at) }

    @Test
    fun `a drag starts at the last tap`() {
        val pan = PanController()
        pan.onTap(200f, 300f)

        val step = pan.onScroll(100f, 0f, 1f, width, height, 1_000L)

        assertNotNull(step)
        assertEquals(200f, step!!.startX, 0.01f)
        assertEquals(300f, step.startY, 0.01f)
        assertEquals(300f, step.endX, 0.01f)
        assertEquals(300f, step.endY, 0.01f)
    }

    @Test
    fun `without a tap a drag starts at the screen centre`() {
        val step = scroll(50f)
        assertNotNull(step)
        assertEquals(centre, step!!.startX, 0.01f)
        assertEquals(centre, step.startY, 0.01f)
        assertEquals(centre + 50f, step.endX, 0.01f)
    }

    @Test
    fun `only one swipe is in flight at a time`() {
        val pan = PanController()

        assertNotNull(pan.onScroll(100f, 0f, 1f, width, height, 1_000L))
        // A second batch while the first swipe runs must not start a competing gesture.
        assertNull(pan.onScroll(100f, 0f, 1f, width, height, 1_010L))
        // It is handed out once the first reports done.
        val next = pan.onGestureComplete()
        assertNotNull(next)
        assertEquals(centre, next!!.startX, 0.01f)
        assertEquals(centre + 100f, next.endX, 0.01f)
    }

    @Test
    fun `movement too small for a drag is held back, never injected as a tap`() {
        val pan = PanController()

        assertNull(pan.onScroll(10f, 0f, 1f, width, height, 1_000L))

        // It comes out once enough has accumulated for a real swipe.
        val step = pan.onScroll(20f, 0f, 1f, width, height, 1_010L)
        assertNotNull(step)
        assertEquals(centre, step!!.startX, 0.01f)
        assertEquals(centre + 30f, step.endX, 0.01f)
    }

    @Test
    fun `movement clamped at an edge is kept for the next step`() {
        val pan = PanController()

        val first = pan.onScroll(800f, 0f, 1f, width, height, 1_000L)
        assertNotNull(first)
        assertEquals(width.toFloat(), first!!.endX, 0.01f)

        // The 300 px the edge clamp held back is applied next, not lost.
        val second = pan.onGestureComplete()
        assertNotNull(second)
        assertEquals(centre + 300f, second!!.endX, 0.01f)
    }

    @Test
    fun `a fling ends the drag and drops the tail`() {
        val pan = PanController()
        assertNotNull(pan.onScroll(800f, 0f, 1f, width, height, 1_000L))

        pan.onFling(1_100L)

        assertNull(pan.onGestureComplete())
    }

    @Test
    fun `a long pause starts a fresh drag`() {
        val pan = PanController()
        assertNull(pan.onScroll(10f, 0f, 1f, width, height, 1_000L))

        // Past the idle window the leftover is dropped, so 10 px again is still too small.
        assertNull(pan.onScroll(10f, 0f, 1f, width, height, 1_500L))
    }

    @Test
    fun `surface movement is converted to phone pixels`() {
        val step = PanController().onScroll(200f, 0f, 2f, width, height, 1_000L)
        assertNotNull(step)
        assertEquals(centre, step!!.startX, 0.01f)
        assertEquals(centre + 100f, step.endX, 0.01f)
    }

    @Test
    fun `degenerate scale or size yields no step`() {
        val pan = PanController()
        assertNull(pan.onScroll(100f, 0f, 0f, width, height, 1_000L))
        assertNull(pan.onScroll(100f, 0f, 1f, 0, height, 1_000L))
        assertNull(pan.onScroll(100f, 0f, 1f, width, 0, 1_000L))
    }

    @Test
    fun `a supplied anchor is used as-is, without the edge inset`() {
        val pan = PanController()

        // 50 px would be pushed to the 80 px inset if it came from a tap; a floating window's own
        // centre must be used exactly so the drag actually lands on the window.
        val step = pan.onScroll(100f, 0f, 1f, width, height, 1_000L, anchorX = 50f, anchorY = 50f)

        assertNotNull(step)
        assertEquals(50f, step!!.startX, 0.01f)
        assertEquals(50f, step.startY, 0.01f)
        assertEquals(150f, step.endX, 0.01f)
    }

    @Test
    fun `a floating anchor wins over the last tap`() {
        val pan = PanController()
        pan.onTap(200f, 300f)

        val step = pan.onScroll(100f, 0f, 1f, width, height, 1_000L, anchorX = 700f, anchorY = 700f)

        assertNotNull(step)
        assertEquals(700f, step!!.startX, 0.01f)
        assertEquals(700f, step.startY, 0.01f)
    }

    @Test
    fun `a new drag is told apart from a pause inside one`() {
        val pan = PanController()
        pan.onScroll(100f, 0f, 1f, width, height, 1_000L)

        // A short gap is still the same drag; a longer one starts a fresh drag.
        assertFalse(pan.startsNewDrag(1_100L))
        assertTrue(pan.startsNewDrag(1_500L))

        pan.onFling(1_600L)
        assertFalse(pan.startsNewDrag(1_700L))
        assertTrue(pan.startsNewDrag(2_100L))
    }

    @Test
    fun `a floating anchor needs a bigger step to clear the window's slop`() {
        val pan = PanController()

        // 30 px clears the normal 24 px step, but not a floating window's 48 px.
        assertNull(pan.onScroll(30f, 0f, 1f, width, height, 1_000L, anchorX = centre, anchorY = centre))
        val step = pan.onScroll(20f, 0f, 1f, width, height, 1_010L, anchorX = centre, anchorY = centre)
        assertNotNull(step)
        assertEquals(centre, step!!.startX, 0.01f)
        assertEquals(centre + 50f, step.endX, 0.01f)
    }
}
