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

    fun setRowHeight(height: Dp) {
        config = config.copy(rowHeight = height)
    }

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
        val to = Duration.between(origin, target).toMinutes().toDouble()
            .coerceIn(minMinutes, maxMinutes)
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(durationMillis = 320),
        ) { fraction, _ ->
            viewportStartMinutes = from + (to - from) * fraction
        }
    }

    /** Parks the viewport at the start of the window containing now. */
    suspend fun goToToday() = animateViewportTo(config.scale.windowStart(LocalDateTime.now()))

    /** ◀ / ▶ — moves the viewport by exactly one unit of the current scale. */
    suspend fun stepBy(direction: Int) =
        animateViewportTo(config.scale.step(viewportStart, direction))
}

@Composable
fun rememberSchedulerState(
    tasks: List<Task> = sampleTasks(),
    config: TimelineConfig = TimelineConfig(),
): SchedulerState {
    // Origin = start of today, so positions stay small and precise around the data we care
    // about, and "today" sits at exactly 0.
    val origin = remember { LocalDate.now().atStartOfDay() }
    return remember { SchedulerState(origin, tasks, config) }
}
