package com.weiting.timeline.scheduler

import com.weiting.timeline.scheduler.model.Task
import com.weiting.timeline.scheduler.model.TimeScale
import com.weiting.timeline.scheduler.model.TimelineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime

/**
 * The acceptance criteria from ai-cowork/001-gesture-layer.md, as tests.
 *
 * [SchedulerState] is a plain class over Compose snapshot state, so it runs on the JVM
 * without an Android device or a Compose test rule.
 */
class TaskDragTest {

    /** A Monday, matching production: the derived grids all divide into a Monday week. */
    private val origin: LocalDateTime = LocalDateTime.of(2026, 10, 12, 0, 0)

    /** 2 px per minute, so one 15-minute grid cell is 30 px of finger travel. */
    private val pxPerMinute = 2f

    private val task = Task(
        id = "t1",
        title = "設計稿",
        start = origin.plusHours(9),
        duration = Duration.ofHours(2),
    )

    private fun state(
        scale: TimeScale = TimeScale.DAY,
        snapWhileDragging: Boolean = true,
        snapOverride: Duration? = null,
        minTaskDuration: Duration = Duration.ofMinutes(15),
    ) = SchedulerState(
        origin = origin,
        initialTasks = listOf(task),
        initialConfig = TimelineConfig(
            scale = scale,
            snapOverride = snapOverride,
            snapWhileDragging = snapWhileDragging,
            minTaskDuration = minTaskDuration,
        ),
    )

    // --- move ------------------------------------------------------------------------

    @Test
    fun `move changes start and leaves duration untouched`() {
        val s = state()
        s.beginDrag(task.id, DragMode.Move)
        s.dragBy(60f, pxPerMinute) // +30 min
        s.commitDrag()

        assertEquals(origin.plusHours(9).plusMinutes(30), s.tasks[0].start)
        assertEquals(Duration.ofHours(2), s.tasks[0].duration)
    }

    @Test
    fun `move snaps to the grid`() {
        val s = state()
        s.beginDrag(task.id, DragMode.Move)
        s.dragBy(37f, pxPerMinute) // +18.5 min -> nearest 15-min line is +15
        s.commitDrag()

        val offset = Duration.between(origin, s.tasks[0].start).toMinutes()
        assertEquals(0L, offset % 15L)
        assertEquals(origin.plusHours(9).plusMinutes(15), s.tasks[0].start)
    }

    @Test
    fun `a drag accumulates rather than resetting each frame`() {
        val s = state()
        s.beginDrag(task.id, DragMode.Move)
        repeat(6) { s.dragBy(10f, pxPerMinute) } // 60 px total -> +30 min
        s.commitDrag()

        assertEquals(origin.plusHours(9).plusMinutes(30), s.tasks[0].start)
    }

    @Test
    fun `sub-grid movement is not swallowed when snapping live`() {
        // Each frame moves less than one grid cell. If the draft were derived from the
        // previous (snapped) draft instead of from the original plus the accumulated
        // travel, every frame would round back to zero and the bar would never move.
        val s = state(snapWhileDragging = true)
        s.beginDrag(task.id, DragMode.Move)
        repeat(10) { s.dragBy(6f, pxPerMinute) } // 10 x 3 min = +30 min
        s.commitDrag()

        assertEquals(origin.plusHours(9).plusMinutes(30), s.tasks[0].start)
    }

    // --- resize ----------------------------------------------------------------------

    @Test
    fun `resizing the right edge changes duration only`() {
        val s = state()
        s.beginDrag(task.id, DragMode.ResizeEnd)
        s.dragBy(120f, pxPerMinute) // +60 min
        s.commitDrag()

        assertEquals(task.start, s.tasks[0].start)
        assertEquals(Duration.ofHours(3), s.tasks[0].duration)
    }

    @Test
    fun `resizing the left edge pins the end`() {
        val s = state()
        val originalEnd = task.end
        s.beginDrag(task.id, DragMode.ResizeStart)
        s.dragBy(-120f, pxPerMinute) // -60 min
        s.commitDrag()

        assertEquals(originalEnd, s.tasks[0].end)
        assertEquals(origin.plusHours(8), s.tasks[0].start)
        assertEquals(Duration.ofHours(3), s.tasks[0].duration)
    }

    @Test
    fun `resizing the right edge past the start clamps instead of inverting`() {
        val s = state()
        s.beginDrag(task.id, DragMode.ResizeEnd)
        s.dragBy(-10_000f, pxPerMinute) // far past the left edge
        s.commitDrag()

        assertEquals(task.start, s.tasks[0].start)
        assertEquals(Duration.ofMinutes(15), s.tasks[0].duration)
        assertTrue(s.tasks[0].duration > Duration.ZERO)
    }

    @Test
    fun `resizing the left edge past the end clamps and keeps the end pinned`() {
        val s = state()
        val originalEnd = task.end
        s.beginDrag(task.id, DragMode.ResizeStart)
        s.dragBy(10_000f, pxPerMinute)
        s.commitDrag()

        assertEquals(Duration.ofMinutes(15), s.tasks[0].duration)
        assertEquals(originalEnd, s.tasks[0].end)
    }

    @Test
    fun `the minimum duration is independent of the snap interval`() {
        // Switching snap to one day must not inflate a short task.
        val s = state(
            snapOverride = Duration.ofDays(1),
            minTaskDuration = Duration.ofMinutes(15),
        )
        s.beginDrag(task.id, DragMode.ResizeEnd)
        s.dragBy(-10_000f, pxPerMinute)
        s.commitDrag()

        assertEquals(Duration.ofMinutes(15), s.tasks[0].duration)
    }

    // --- snapping mode ---------------------------------------------------------------

    @Test
    fun `with live snapping off the draft tracks the finger exactly`() {
        val s = state(snapWhileDragging = false)
        s.beginDrag(task.id, DragMode.Move)
        s.dragBy(37f, pxPerMinute) // +18.5 min -> rounds to the nearest minute, not grid

        assertEquals(origin.plusHours(9).plusMinutes(19), s.draft?.start)
    }

    @Test
    fun `with live snapping off the commit still lands on the grid`() {
        val s = state(snapWhileDragging = false)
        s.beginDrag(task.id, DragMode.Move)
        s.dragBy(37f, pxPerMinute)
        s.commitDrag()

        assertEquals(origin.plusHours(9).plusMinutes(15), s.tasks[0].start)
    }

    @Test
    fun `with live snapping on the draft is already on the grid`() {
        val s = state(snapWhileDragging = true)
        s.beginDrag(task.id, DragMode.Move)
        s.dragBy(37f, pxPerMinute)

        assertEquals(origin.plusHours(9).plusMinutes(15), s.draft?.start)
    }

    // --- lifecycle -------------------------------------------------------------------

    @Test
    fun `cancelling a drag leaves the task untouched`() {
        val s = state()
        s.beginDrag(task.id, DragMode.Move)
        s.dragBy(600f, pxPerMinute)
        s.cancelDrag()

        assertNull(s.draft)
        assertEquals(task.start, s.tasks[0].start)
        assertEquals(task.duration, s.tasks[0].duration)
    }

    @Test
    fun `the draft is cleared after a commit`() {
        val s = state()
        s.beginDrag(task.id, DragMode.Move)
        s.dragBy(60f, pxPerMinute)
        s.commitDrag()

        assertNull(s.draft)
    }

    @Test
    fun `dragging without a begin is ignored`() {
        val s = state()
        s.dragBy(600f, pxPerMinute)
        s.commitDrag()

        assertNull(s.draft)
        assertEquals(task.start, s.tasks[0].start)
    }

    @Test
    fun `beginDrag resolves the task from the live list, so a stale reference cannot rewind`() {
        // The bug this guards: a pointerInput block only restarts when its keys change,
        // so a bar whose width did not change kept the Task captured at first composition
        // and a second move rewound to the pre-edit time. beginDrag takes an id, so the
        // caller has no Task to hold on to.
        val s = state()
        val staleTask = s.tasks[0]

        s.beginDrag(staleTask.id, DragMode.Move)
        s.dragBy(60f, pxPerMinute)
        s.commitDrag()
        val afterFirst = s.tasks[0].start

        // Same id, deliberately passed from the pre-edit object.
        s.beginDrag(staleTask.id, DragMode.Move)
        assertEquals(afterFirst, s.draft?.originalStart)
        s.dragBy(60f, pxPerMinute)
        s.commitDrag()

        assertEquals(afterFirst.plusMinutes(30), s.tasks[0].start)
        assertEquals(origin.plusHours(10), s.tasks[0].start)
    }

    @Test
    fun `beginDrag with an unknown id is ignored`() {
        val s = state()
        s.beginDrag("nope", DragMode.Move)
        assertNull(s.draft)
        s.dragBy(600f, pxPerMinute)
        assertEquals(task.start, s.tasks[0].start)
    }

    @Test
    fun `a second drag starts from the committed position`() {
        val s = state()
        s.beginDrag(task.id, DragMode.Move)
        s.dragBy(60f, pxPerMinute)
        s.commitDrag()

        s.beginDrag(task.id, DragMode.Move)
        s.dragBy(60f, pxPerMinute)
        s.commitDrag()

        assertEquals(origin.plusHours(10), s.tasks[0].start)
    }

    // --- scale-derived snapping ------------------------------------------------------

    @Test
    fun `DAY snaps to a quarter hour`() {
        val s = state(scale = TimeScale.DAY)
        s.beginDrag(task.id, DragMode.Move)
        s.dragBy(2f * 37f, 2f) // +37 min -> nearest quarter hour is +30
        s.commitDrag()

        assertEquals(origin.plusHours(9).plusMinutes(30), s.tasks[0].start)
    }

    @Test
    fun `WEEK and MONTH snap to midnight`() {
        for (scale in listOf(TimeScale.WEEK, TimeScale.MONTH)) {
            val s = state(scale = scale)
            s.beginDrag(task.id, DragMode.Move)
            s.dragBy(2f * 60f * 20f, 2f) // +20 h
            s.commitDrag()

            val start = s.tasks[0].start
            assertEquals("$scale should land at midnight", 0, start.hour)
            assertEquals("$scale should land at midnight", 0, start.minute)
        }
    }

    @Test
    fun `YEAR snaps to a Monday midnight`() {
        // The case that would fail silently if the origin were today's midnight instead
        // of this week's Monday: a seven-day grid anchored to a Wednesday lands on
        // Wednesdays.
        val s = state(scale = TimeScale.YEAR)
        s.beginDrag(task.id, DragMode.Move)
        s.dragBy(2f * 60f * 24f * 10f, 2f) // +10 days
        s.commitDrag()

        val start = s.tasks[0].start
        assertEquals(DayOfWeek.MONDAY, start.dayOfWeek)
        assertEquals(0, start.hour)
        assertEquals(0, start.minute)
    }

    @Test
    fun `an override replaces the derived interval`() {
        val s = state(scale = TimeScale.YEAR, snapOverride = Duration.ofMinutes(15))
        assertEquals(Duration.ofMinutes(15), s.config.snapInterval)

        s.setSnapOverride(null)
        assertEquals(Duration.ofDays(7), s.config.snapInterval)
    }

    @Test
    fun `changing the scale changes the grid with nothing else to set`() {
        val s = state(scale = TimeScale.DAY)
        assertEquals(Duration.ofMinutes(15), s.config.snapInterval)
        s.setScale(TimeScale.WEEK)
        assertEquals(Duration.ofDays(1), s.config.snapInterval)
        s.setScale(TimeScale.YEAR)
        assertEquals(Duration.ofDays(7), s.config.snapInterval)
    }

    @Test
    fun `the minimum duration is unaffected by the scale`() {
        val s = state(scale = TimeScale.YEAR, minTaskDuration = Duration.ofMinutes(15))
        s.beginDrag(task.id, DragMode.ResizeEnd)
        s.dragBy(-10_000f, 2f)
        s.commitDrag()

        assertEquals(Duration.ofMinutes(15), s.tasks[0].duration)
    }

    // --- the edit bracket ------------------------------------------------------------

    @Test
    fun `beginEdit marks the task before anything moves`() {
        val s = state()
        s.beginEdit(task.id, DragMode.ResizeStart)

        assertEquals(task.id, s.editingTaskId)
        assertEquals(DragMode.ResizeStart, s.editingZone)
        assertTrue(s.isEditing)
        assertNull("no draft until the gesture commits to an axis", s.draft)
        assertEquals(task.start, s.tasks[0].start)
    }

    @Test
    fun `a press with no movement leaves nothing behind`() {
        val s = state()
        s.beginEdit(task.id, DragMode.Move)
        s.endEdit()

        assertNull(s.editingTaskId)
        assertNull(s.editingZone)
        assertNull(s.draft)
        assertEquals(task.start, s.tasks[0].start)
        assertEquals(task.duration, s.tasks[0].duration)
    }

    @Test
    fun `endEdit after a committed drag keeps the edit`() {
        val s = state()
        s.beginEdit(task.id, DragMode.Move)
        s.beginDrag(task.id, DragMode.Move)
        s.dragBy(60f, 2f)
        s.commitDrag()
        s.endEdit()

        assertNull(s.editingTaskId)
        assertEquals(origin.plusHours(9).plusMinutes(30), s.tasks[0].start)
    }

    // --- auto-scroll -----------------------------------------------------------------

    @Test
    fun `auto-scroll moves the viewport and the draft by the same amount`() {
        // Snapping off, so the draft is the raw tracked position. This is the invariant
        // that keeps the bar under the finger: scroll the viewport by n minutes and the
        // bar's time must advance by n, or it crawls away from the finger.
        val s = state(snapWhileDragging = false)
        s.onViewportWidthChanged(1000f)
        s.beginEdit(task.id, DragMode.Move)
        s.beginDrag(task.id, DragMode.Move)

        val viewportBefore = s.viewportStartMinutes
        val draftBefore = s.draft!!.start

        // Finger hard against the right edge.
        val scrolled = s.autoScrollStep(
            fingerX = 1000f,
            thresholdPx = 100f,
            maxPxPerStep = 20f,
            pxPerMinute = 2f,
        )

        assertTrue("should have scrolled", scrolled != 0f)
        val viewportDelta = s.viewportStartMinutes - viewportBefore
        val draftDelta = Duration.between(draftBefore, s.draft!!.start).toMinutes().toDouble()
        assertEquals(viewportDelta, draftDelta, 0.001)
    }

    @Test
    fun `auto-scroll with live snapping drifts by less than one grid step`() {
        // With snapping on the bar steps in grid units rather than tracking continuously,
        // so the two cannot be equal. What must hold is that the gap never accumulates:
        // the draft is derived from the raw travel, and only its presentation is snapped.
        val s = state(snapWhileDragging = true)
        s.onViewportWidthChanged(1000f)
        s.beginEdit(task.id, DragMode.Move)
        s.beginDrag(task.id, DragMode.Move)

        val viewportBefore = s.viewportStartMinutes
        val draftBefore = s.draft!!.start
        repeat(40) { s.autoScrollStep(1000f, 100f, 20f, 2f) }

        val viewportDelta = s.viewportStartMinutes - viewportBefore
        val draftDelta = Duration.between(draftBefore, s.draft!!.start).toMinutes().toDouble()
        val gridMinutes = s.config.snapInterval.toMinutes().toDouble()

        assertTrue("viewport should have moved a long way", viewportDelta > 100.0)
        assertTrue(
            "drift was ${'$'}{abs(viewportDelta - draftDelta)} min, grid is $gridMinutes",
            abs(viewportDelta - draftDelta) < gridMinutes,
        )
    }

    @Test
    fun `auto-scroll stops the viewport and the draft together at the clamp`() {
        val s = state(snapWhileDragging = false)
        s.onViewportWidthChanged(1000f)
        s.beginEdit(task.id, DragMode.Move)
        s.beginDrag(task.id, DragMode.Move)

        // Drive it into the backward clamp. The range is origin +/- 2 years, so at
        // 2 px/min this needs well over a million minutes of travel.
        repeat(2_000) { s.autoScrollStep(0f, 100f, 4_000f, 2f) }
        val viewportAtClamp = s.viewportStartMinutes
        val draftAtClamp = s.draft!!.start

        val floor = Duration.between(origin, origin.minusYears(2)).toMinutes().toDouble()
        assertEquals("should be sitting on the clamp", floor, viewportAtClamp, 0.001)

        // Nothing further moves, and neither one drifts past the other.
        assertEquals(0f, s.autoScrollStep(0f, 100f, 4_000f, 2f), 0.001f)
        assertEquals(viewportAtClamp, s.viewportStartMinutes, 0.001)
        assertEquals(draftAtClamp, s.draft!!.start)
    }

    @Test
    fun `auto-scroll does nothing away from the edges`() {
        val s = state()
        s.onViewportWidthChanged(1000f)
        s.beginEdit(task.id, DragMode.Move)
        s.beginDrag(task.id, DragMode.Move)

        val before = s.viewportStartMinutes
        val scrolled = s.autoScrollStep(500f, 100f, 20f, 2f)

        assertEquals(0f, scrolled, 0.001f)
        assertEquals(before, s.viewportStartMinutes, 0.001)
    }

    @Test
    fun `auto-scroll does nothing without a draft`() {
        val s = state()
        s.onViewportWidthChanged(1000f)
        val before = s.viewportStartMinutes

        assertEquals(0f, s.autoScrollStep(1000f, 100f, 20f, 2f), 0.001f)
        assertEquals(before, s.viewportStartMinutes, 0.001)
    }

    // --- centring --------------------------------------------------------------------

    @Test
    fun `centring puts the target in the middle of the timeline area`() {
        val s = state()
        s.onViewportWidthChanged(1200f)
        val target = origin.plusDays(2).plusHours(14)

        val start = s.centredViewportStart(target, pxPerMinute = 2f)
        val targetMinutes = Duration.between(origin, target).toMinutes().toDouble()

        // half of 1200px at 2px/min is 300 minutes
        assertEquals(targetMinutes - 300.0, start, 0.001)
    }

    @Test
    fun `centring degrades rather than leaving the marker off-screen`() {
        val s = state()
        s.onViewportWidthChanged(1200f)
        // Far past the clamp at origin - 2 years.
        val start = s.centredViewportStart(origin.minusYears(5), pxPerMinute = 2f)
        val floor = Duration.between(origin, origin.minusYears(2)).toMinutes().toDouble()

        assertEquals(floor, start, 0.001)
    }

    @Test
    fun `centring with no width yet falls back to the target itself`() {
        val s = state()
        val target = origin.plusHours(9)
        assertEquals(
            Duration.between(origin, target).toMinutes().toDouble(),
            s.centredViewportStart(target, pxPerMinute = 2f),
            0.001,
        )
    }

    // --- hit testing -----------------------------------------------------------------

    @Test
    fun `the hit test splits a bar into resize, move, resize`() {
        val width = 120f
        val handle = 18f
        assertEquals(DragMode.ResizeStart, hitTestBar(4f, width, handle))
        assertEquals(DragMode.Move, hitTestBar(60f, width, handle))
        assertEquals(DragMode.ResizeEnd, hitTestBar(116f, width, handle))
    }

    @Test
    fun `a narrow bar keeps a grabbable middle`() {
        // The caller caps the handle at a third of the width, so the middle third always
        // survives however narrow the bar is.
        val width = 30f
        val handle = minOf(18f, width / 3f)
        assertEquals(DragMode.ResizeStart, hitTestBar(2f, width, handle))
        assertEquals(DragMode.Move, hitTestBar(15f, width, handle))
        assertEquals(DragMode.ResizeEnd, hitTestBar(28f, width, handle))
    }
}
