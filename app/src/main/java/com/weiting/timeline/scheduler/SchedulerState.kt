package com.weiting.timeline.scheduler

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.unit.Dp
import com.weiting.timeline.scheduler.model.Task
import com.weiting.timeline.scheduler.model.TimeScale
import com.weiting.timeline.scheduler.model.TimelineConfig
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToLong

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

    /** Minutes from [origin], clamped to the scrollable range. */
    private fun clampedMinutesOf(time: LocalDateTime): Double =
        Duration.between(origin, time).toMinutes().toDouble().coerceIn(minMinutes, maxMinutes)

    init {
        // Park on launch where "今天" would, so the first frame shows the current moment
        // rather than whatever happens to sit at the origin.
        viewportStartMinutes = clampedMinutesOf(initialConfig.scale.todayStart(LocalDateTime.now()))
    }

    val viewportStart: LocalDateTime get() = origin.plusMinutes(viewportStartMinutes.roundToLong())

    // --- configuration setters -------------------------------------------------------

    fun setScale(scale: TimeScale) {
        config = config.copy(scale = scale)
    }

    fun setZoom(zoom: Float) {
        config = config.copy(zoom = zoom.coerceIn(TimelineConfig.MIN_ZOOM, TimelineConfig.MAX_ZOOM))
    }

    fun setSnapInterval(interval: Duration) {
        config = config.copy(snapInterval = interval)
    }

    fun setSnapWhileDragging(enabled: Boolean) {
        config = config.copy(snapWhileDragging = enabled)
    }

    fun setRowHeight(height: Dp) {
        config = config.copy(rowHeight = height)
    }

    // --- dragging --------------------------------------------------------------------

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

    suspend fun animateViewportTo(target: LocalDateTime) {
        val from = viewportStartMinutes
        val to = clampedMinutesOf(target)
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(durationMillis = 320),
        ) { fraction, _ ->
            viewportStartMinutes = from + (to - from) * fraction
        }
    }

    /** Parks the viewport so the current moment sits just inside the left edge. */
    suspend fun goToToday() = animateViewportTo(config.scale.todayStart(LocalDateTime.now()))

    /** ◀ / ▶ — moves the viewport by exactly one unit of the current scale. */
    suspend fun stepBy(direction: Int) =
        animateViewportTo(config.scale.step(viewportStart, direction))
}

@Composable
fun rememberSchedulerState(
    tasks: List<Task> = sampleTasks(),
    config: TimelineConfig = TimelineConfig(),
    // Origin = start of today, so positions stay small and precise around the data we care
    // about, and "today" sits at exactly 0. Overridable so previews can pin a date.
    origin: LocalDateTime = LocalDate.now().atStartOfDay(),
): SchedulerState = remember { SchedulerState(origin, tasks, config) }
