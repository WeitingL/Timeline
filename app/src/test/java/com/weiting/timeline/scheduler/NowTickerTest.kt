package com.weiting.timeline.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * The marker ticks on the minute boundary rather than on a fixed 60-second interval. A
 * fixed interval would drift by however far into a minute the first tick happened to fall
 * and stay there; these pin the boundary behaviour.
 */
class NowTickerTest {

    @Test
    fun `a whole minute waits the full interval`() {
        assertEquals(60_000L, millisUntilNextMinute(LocalTime.of(14, 30, 0)))
    }

    @Test
    fun `mid-minute waits only the remainder`() {
        assertEquals(30_000L, millisUntilNextMinute(LocalTime.of(14, 30, 30)))
        assertEquals(1_000L, millisUntilNextMinute(LocalTime.of(14, 30, 59)))
    }

    @Test
    fun `sub-second precision is accounted for`() {
        val almostOver = LocalTime.of(14, 30, 59).withNano(500_000_000)
        assertEquals(500L, millisUntilNextMinute(almostOver))
    }

    @Test
    fun `the interval is always inside one minute and never zero`() {
        // Zero would spin the coroutine; more than a minute would skip one.
        for (second in 0..59) {
            for (nano in listOf(0, 1, 999_999_999)) {
                val value = millisUntilNextMinute(LocalTime.of(0, 0, second).withNano(nano))
                assertTrue("second=$second nano=$nano gave $value", value in 1L..60_000L)
            }
        }
    }

    @Test
    fun `waiting the returned interval lands on a minute boundary`() {
        val start = LocalTime.of(9, 17, 23).withNano(456_000_000)
        val landed = start.plusNanos(millisUntilNextMinute(start) * 1_000_000L)
        assertEquals(0, landed.second)
        assertEquals(0, landed.nano)
        assertEquals(18, landed.minute)
    }
}
