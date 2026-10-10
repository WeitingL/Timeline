package com.weiting.timeline.scheduler

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins down the Compose behaviour that caused a drag to advance exactly one grid step and
 * then stop: `positionChange()` reports **zero** once the change has been consumed.
 *
 * `PointerInputChange` is a plain class with no Android dependencies, so the contract the
 * gesture code relies on can be asserted here rather than taken on trust.
 */
class PointerChangeSemanticsTest {

    private fun change(dx: Float) = PointerInputChange(
        id = PointerId(1L),
        uptimeMillis = 16L,
        position = Offset(dx, 0f),
        pressed = true,
        pressure = 1f,
        previousUptimeMillis = 0L,
        previousPosition = Offset.Zero,
        previousPressed = true,
        isInitiallyConsumed = false,
    )

    @Test
    fun `positionChange reports the movement while the change is unconsumed`() {
        assertEquals(30f, change(30f).positionChange().x, 0.001f)
    }

    @Test
    fun `positionChange reports zero once the change is consumed`() {
        // The trap. Consuming first and reading after yields no movement at all, so a
        // drag loop written in that order moves the bar only by whatever was accumulated
        // before the first consume - one snap step - and then never again.
        val c = change(30f)
        c.consume()
        assertEquals(0f, c.positionChange().x, 0.001f)
    }

    @Test
    fun `positionChangeIgnoreConsumed still reports the movement after consuming`() {
        val c = change(30f)
        c.consume()
        assertEquals(30f, c.positionChangeIgnoreConsumed().x, 0.001f)
    }
}
