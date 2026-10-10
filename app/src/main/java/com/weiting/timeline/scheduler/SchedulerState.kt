package com.weiting.timeline.scheduler

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.unit.Dp
import com.weiting.timeline.scheduler.model.Task
import com.weiting.timeline.scheduler.model.TimeScale
import com.weiting.timeline.scheduler.model.TimelineConfig
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sign

/**
 * Holder for all scheduler UI state.
 *
 * Deliberately a plain `@Stable` class rather than a ViewModel: this is pure UI state for
 * a single screen with no I/O and nothing worth surviving process death, so a ViewModel
 * would add a lifecycle ceremony that buys nothing here.
 *
 * The important design choice is that the scroll position lives in the **time domain**
 * ([viewportStartMinutes]) instead of as a pixel offset. Changing scale or zoom therefore
 * keeps the left edge pinned to the same instant for free, with no compensation maths.
 */
@Stable
class SchedulerState(
    val origin: LocalDateTime,
    initialTasks: List<Task>,
    initialConfig: TimelineConfig,
) {
    var config: TimelineConfig by mutableStateOf(initialConfig)
        private set

    val tasks: SnapshotStateList<Task> = initialTasks.toMutableStateList()

    /** Left edge of the viewport, in minutes from [origin]. */
    var viewportStartMinutes: Double by mutableDoubleStateOf(0.0)
        private set

    private val minMinutes = Duration.between(origin, origin.minusYears(2)).toMinutes().toDouble()
    private val maxMinutes = Duration.between(origin, origin.plusYears(2)).toMinutes().toDouble()

    private fun minutesFrom(time: LocalDateTime): Double =
        Duration.between(origin, time).toMinutes().toDouble()

    /** Minutes from [origin], clamped to the scrollable range. */
    private fun clampedMinutesOf(time: LocalDateTime): Double =
        minutesFrom(time).coerceIn(minMinutes, maxMinutes)

    /**
     * Width of the timeline area in pixels — the part that represents time, with the
     * label column excluded. Zero until the first layout pass.
     *
     * Needed by two things that cannot work without knowing where the viewport ends:
     * centring "now", and auto-scrolling when a dragged bar reaches an edge.
     */
    var viewportWidthPx: Float by mutableFloatStateOf(0f)
        private set

    /** True once [viewportWidthPx] is known and the launch position has been applied. */
    var hasCentredOnLaunch: Boolean by mutableStateOf(false)
        private set

    fun onViewportWidthChanged(widthPx: Float) {
        viewportWidthPx = widthPx
    }

    fun markCentredOnLaunch() {
        hasCentredOnLaunch = true
    }

    val viewportStart: LocalDateTime get() = origin.plusMinutes(viewportStartMinutes.roundToLong())

    // --- configuration setters -------------------------------------------------------

    fun setScale(scale: TimeScale) {
        config = config.copy(scale = scale)
    }

    fun setZoom(zoom: Float) {
        config = config.copy(zoom = zoom.coerceIn(TimelineConfig.MIN_ZOOM, TimelineConfig.MAX_ZOOM))
    }

    /** Null restores the scale-derived grid. */
    fun setSnapOverride(interval: Duration?) {
        config = config.copy(snapOverride = interval)
    }

    fun setSnapWhileDragging(enabled: Boolean) {
        config = config.copy(snapWhileDragging = enabled)
    }

    fun setRowHeight(height: Dp) {
        config = config.copy(rowHeight = height)
    }

    // --- dragging --------------------------------------------------------------------

    /**
     * The task under the finger, for as long as the finger is down. Set on touch-down,
     * before any movement, and cleared on release.
     *
     * Separate from [draft], which only exists once the gesture has committed to the
     * horizontal axis. The two-finger period swipe runs on `PointerEventPass.Initial` and
     * therefore sees events before a bar can consume them, so it needs this rather than
     * consumption to know to stand down.
     */
    var editingTaskId: String? by mutableStateOf(null)
        private set

    /** Which zone the finger is on, for the press highlight. */
    var editingZone: DragMode? by mutableStateOf(null)
        private set

    val isEditing: Boolean get() = editingTaskId != null

    /** The edit in flight, or null. Only the dragged lane reads this. */
    var draft: TaskDraft? by mutableStateOf(null)
        private set

    /**
     * Raw, unsnapped pixel total for the gesture in flight. Snapping is applied to the
     * derived time, never fed back into this accumulator.
     */
    private var dragAccumPx = 0f

    /**
     * Starts a gesture on the task with [taskId].
     *
     * Takes an id rather than a [Task] on purpose. A caller holding a Task can hold a
     * *stale* one — a `pointerInput` block only restarts when its keys change, so a bar
     * whose width did not change keeps the Task captured at its first composition, and a
     * second drag would then rewind to the pre-edit time. Resolving from [tasks] here
     * makes that bug unrepresentable rather than merely absent.
     */
    /** Touch-down on a bar. Locks the period swipe and lights the zone; moves nothing. */
    fun beginEdit(taskId: String, zone: DragMode) {
        editingTaskId = taskId
        editingZone = zone
    }

    /** Release or cancel. Always paired with [beginEdit], including on the tap path. */
    fun endEdit() {
        editingTaskId = null
        editingZone = null
    }

    fun beginDrag(taskId: String, mode: DragMode) {
        val task = tasks.firstOrNull { it.id == taskId } ?: return
        dragAccumPx = 0f
        draft = TaskDraft.of(task, mode)
    }

    /**
     * Advances the in-flight edit. Does not touch [tasks]; nothing is committed until
     * [commitDrag].
     */
    fun dragBy(deltaPx: Float, pxPerMinute: Float) {
        val current = draft ?: return
        if (pxPerMinute <= 0f) return

        dragAccumPx += deltaPx
        val deltaMinutes = (dragAccumPx / pxPerMinute).roundToLong()
        val live = config.snapWhileDragging
        val minDuration = config.minTaskDuration

        draft = when (current.mode) {
            DragMode.Move -> current.copy(
                start = maybeSnap(current.originalStart.plusMinutes(deltaMinutes), live),
                duration = current.originalDuration,
            )

            DragMode.ResizeEnd -> {
                val end = maybeSnap(current.originalEnd.plusMinutes(deltaMinutes), live)
                current.copy(
                    start = current.originalStart,
                    duration = Duration.between(current.originalStart, end)
                        .coerceAtLeast(minDuration),
                )
            }

            DragMode.ResizeStart -> {
                // The far edge is pinned, so the duration absorbs the whole movement.
                val end = current.originalEnd
                var start = maybeSnap(current.originalStart.plusMinutes(deltaMinutes), live)
                var duration = Duration.between(start, end)
                if (duration < minDuration) {
                    // Clamping beats snapping: the bar stops rather than inverting, even
                    // though the resulting start may sit off-grid.
                    duration = minDuration
                    start = end.minus(minDuration)
                }
                current.copy(start = start, duration = duration)
            }
        }
    }

    /** Writes the draft back into [tasks] and ends the gesture. */
    fun commitDrag() {
        val current = draft ?: return
        val index = tasks.indexOfFirst { it.id == current.taskId }
        if (index >= 0) {
            val interval = config.snapInterval
            val minDuration = config.minTaskDuration
            var start: LocalDateTime
            var duration: Duration

            // Snap unconditionally here: with snapWhileDragging off, this is the only snap.
            when (current.mode) {
                DragMode.Move -> {
                    start = snapToGrid(origin, current.start, interval)
                    duration = current.duration
                }

                DragMode.ResizeEnd -> {
                    start = current.start
                    val end = snapToGrid(origin, current.end, interval)
                    duration = Duration.between(start, end).coerceAtLeast(minDuration)
                }

                DragMode.ResizeStart -> {
                    val end = current.end
                    start = snapToGrid(origin, current.start, interval)
                    duration = Duration.between(start, end)
                    if (duration < minDuration) {
                        duration = minDuration
                        start = end.minus(minDuration)
                    }
                }
            }
            tasks[index] = tasks[index].copy(start = start, duration = duration)
        }
        draft = null
        dragAccumPx = 0f
    }

    /**
     * One step of edge auto-scroll, called per frame while a drag is in flight.
     *
     * [fingerX] is the pointer's position within the timeline area. When it sits within
     * [thresholdPx] of either end, the viewport scrolls and the draft advances by the same
     * amount, so the bar keeps its screen position while its time changes. Advancing only
     * the viewport would make the bar crawl out from under the finger.
     *
     * Returns the pixels actually scrolled, which is zero at the clamp — and because the
     * draft is advanced by that same return value, both stop together.
     */
    fun autoScrollStep(
        fingerX: Float,
        thresholdPx: Float,
        maxPxPerStep: Float,
        pxPerMinute: Float,
    ): Float {
        if (draft == null || viewportWidthPx <= 0f || thresholdPx <= 0f) return 0f

        val overLeft = thresholdPx - fingerX
        val overRight = fingerX - (viewportWidthPx - thresholdPx)
        val depth = when {
            overLeft > 0f -> -overLeft
            overRight > 0f -> overRight
            else -> return 0f
        }
        // Ramped, not constant: a fixed rate is either sluggish at the threshold edge or
        // uncontrollable deep inside it.
        val ramp = (abs(depth) / thresholdPx).coerceIn(0f, 1f)
        val requested = ramp * maxPxPerStep * depth.sign

        val consumed = scrollByPx(-requested, pxPerMinute)
        if (consumed != 0f) dragBy(-consumed, pxPerMinute)
        return -consumed
    }

    /** Discards the draft; [tasks] is left exactly as it was. */
    fun cancelDrag() {
        draft = null
        dragAccumPx = 0f
    }

    private fun maybeSnap(time: LocalDateTime, snapping: Boolean): LocalDateTime =
        if (snapping) snapToGrid(origin, time, config.snapInterval) else time

    // --- scrolling -------------------------------------------------------------------

    /**
     * Consumes a horizontal drag. Returns the pixels actually consumed so that
     * `Modifier.scrollable`'s fling decays correctly when it reaches an edge.
     *
     * A rightward drag arrives as a positive delta and should reveal *earlier* time, hence
     * the subtraction.
     */
    fun scrollByPx(deltaPx: Float, pxPerMinute: Float): Float {
        if (pxPerMinute <= 0f) return 0f
        val before = viewportStartMinutes
        viewportStartMinutes = (before - deltaPx / pxPerMinute).coerceIn(minMinutes, maxMinutes)
        return ((before - viewportStartMinutes) * pxPerMinute).toFloat()
    }

    suspend fun animateViewportTo(target: LocalDateTime) =
        animateViewportToMinutes(clampedMinutesOf(target))

    private suspend fun animateViewportToMinutes(to: Double) {
        val from = viewportStartMinutes
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(durationMillis = 320),
        ) { fraction, _ ->
            viewportStartMinutes = from + (to - from) * fraction
        }
    }

    /**
     * Viewport start that puts [time] in the middle of the timeline area.
     *
     * Clamped, so near the ends of the scrollable range this degrades to the closest
     * reachable position rather than leaving the marker off-screen.
     */
    fun centredViewportStart(time: LocalDateTime, pxPerMinute: Float): Double {
        if (pxPerMinute <= 0f || viewportWidthPx <= 0f) return clampedMinutesOf(time)
        val halfViewportMinutes = viewportWidthPx / pxPerMinute / 2.0
        return (minutesFrom(time) - halfViewportMinutes).coerceIn(minMinutes, maxMinutes)
    }

    /** Jumps, without animating, so the launch frame is already correct. */
    fun centreOnNow(pxPerMinute: Float) {
        viewportStartMinutes = centredViewportStart(LocalDateTime.now(), pxPerMinute)
    }

    /** Animates "now" to the middle of the timeline area. */
    suspend fun goToToday(pxPerMinute: Float) =
        animateViewportToMinutes(centredViewportStart(LocalDateTime.now(), pxPerMinute))

    /** ◀ / ▶ — moves the viewport by exactly one unit of the current scale. */
    suspend fun stepBy(direction: Int) =
        animateViewportTo(config.scale.step(viewportStart, direction))
}

@Composable
fun rememberSchedulerState(
    tasks: List<Task> = sampleTasks(),
    config: TimelineConfig = TimelineConfig(),
    /**
     * Origin = this week's Monday at midnight.
     *
     * Snapping is anchored to the origin, and the derived grids are 15 minutes, one day
     * and one week. All three divide evenly into a week that starts at a Monday midnight,
     * so every granularity lines up with the tick lines and with the others. Anchoring to
     * today's midnight instead would put the weekly grid on whatever weekday today
     * happens to be.
     *
     * Overridable so previews and tests can pin a date.
     */
    origin: LocalDateTime = LocalDate.now()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .atStartOfDay(),
): SchedulerState = remember { SchedulerState(origin, tasks, config) }
