package com.weiting.timeline.scheduler

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

/**
 * [TimeAxis] is pure and imports no Compose, so it is testable on the plain JVM. These
 * cover the conversions every gesture is built on.
 */
class TimeAxisTest {

    private val origin: LocalDateTime = LocalDateTime.of(2026, 10, 10, 0, 0)
    private val axis = TimeAxis(origin = origin, pxPerMinute = 2f)
    private val quarterHour: Duration = Duration.ofMinutes(15)

    @Test
    fun `snap rounds down when the input is nearer the earlier grid line`() {
        assertEquals(
            LocalDateTime.of(2026, 10, 10, 9, 0),
            axis.snap(LocalDateTime.of(2026, 10, 10, 9, 7), quarterHour),
        )
    }

    @Test
    fun `snap rounds up when the input is nearer the later grid line`() {
        assertEquals(
            LocalDateTime.of(2026, 10, 10, 9, 15),
            axis.snap(LocalDateTime.of(2026, 10, 10, 9, 8), quarterHour),
        )
    }

    @Test
    fun `snap is idempotent`() {
        val once = axis.snap(LocalDateTime.of(2026, 10, 10, 9, 7), quarterHour)
        assertEquals(once, axis.snap(once, quarterHour))
    }

    @Test
    fun `snap lands on a multiple of the interval measured from origin`() {
        val snapped = axis.snap(LocalDateTime.of(2026, 10, 11, 13, 41), quarterHour)
        val minutes = Duration.between(origin, snapped).toMinutes()
        assertEquals(0L, minutes % quarterHour.toMinutes())
    }

    @Test
    fun `two different inputs in the same cell snap to the same instant`() {
        // The property that makes independently dragged bars line up with each other.
        val a = axis.snap(LocalDateTime.of(2026, 10, 10, 9, 16), quarterHour)
        val b = axis.snap(LocalDateTime.of(2026, 10, 10, 9, 22), quarterHour)
        assertEquals(a, b)
    }

    @Test
    fun `snap with a non-positive interval is a no-op`() {
        val time = LocalDateTime.of(2026, 10, 10, 9, 7)
        assertEquals(time, axis.snap(time, Duration.ZERO))
    }

    @Test
    fun `x and time round trip`() {
        val viewportStart = 480.0
        val time = LocalDateTime.of(2026, 10, 10, 11, 23)
        val x = axis.xOf(time, viewportStart)
        assertEquals(time, axis.timeAt(x, viewportStart))
    }

    @Test
    fun `xOf is zero at the viewport's left edge`() {
        val viewportStart = 540.0
        val leftEdge = origin.plusMinutes(viewportStart.toLong())
        assertEquals(0f, axis.xOf(leftEdge, viewportStart), 0.001f)
    }

    @Test
    fun `widthOf scales linearly with duration`() {
        assertEquals(120f, axis.widthOf(Duration.ofHours(1)), 0.001f)
        assertEquals(240f, axis.widthOf(Duration.ofHours(2)), 0.001f)
    }

    @Test
    fun `snapToGrid is anchored to origin, not to the input`() {
        // Same wall-clock input, two different origins, two different results.
        val input = LocalDateTime.of(2026, 10, 10, 9, 20)
        val fromMidnight = snapToGrid(origin, input, Duration.ofHours(1))
        val fromHalfPast = snapToGrid(origin.plusMinutes(30), input, Duration.ofHours(1))
        assertEquals(LocalDateTime.of(2026, 10, 10, 9, 0), fromMidnight)
        assertEquals(LocalDateTime.of(2026, 10, 10, 9, 30), fromHalfPast)
    }
}
