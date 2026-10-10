package com.weiting.timeline.scheduler

import com.weiting.timeline.scheduler.model.Task
import com.weiting.timeline.scheduler.model.TimelineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

/**
 * The acceptance criteria from ai-cowork/001-gesture-layer.md, as tests.
 *
 * [SchedulerState] is a plain class over Compose snapshot state, so it runs on the JVM
 * without an Android device or a Compose test rule.
 */
class TaskDragTest {

    private val origin: LocalDateTime = LocalDateTime.of(2026, 10, 10, 0, 0)

    /** 2 px per minute, so one 15-minute grid cell is 30 px of finger travel. */
    private val pxPerMinute = 2f

    private val task = Task(
        id = "t1",
        title = "設計稿",
        start = origin.plusHours(9),
        duration = Duration.ofHours(2),
    )

    private fun state(
        snapWhileDragging: Boolean = true,
        snapInterval: Duration = Duration.ofMinutes(15),
        minTaskDuration: Duration = Duration.ofMinutes(15),
    ) = SchedulerState(
        origin = origin,
        initialTasks = listOf(task),
        initialConfig = TimelineConfig(
            snapInterval = snapInterval,
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
            snapInterval = Duration.ofDays(1),
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
