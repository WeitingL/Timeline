package com.weiting.timeline.scheduler.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Duration

/**
 * Every tunable knob in one immutable value, so "configurable" means a single `copy()`
 * rather than a scattering of parameters threaded through the composable tree.
 *
 * The four knobs the brief asks for are [scale], [zoom], [snapInterval] and [rowHeight].
 * The snap interval is derived from the scale unless [snapOverride] says otherwise.
 */
@Immutable
data class TimelineConfig(
    val scale: TimeScale = TimeScale.DAY,
    val zoom: Float = 1f,
    /**
     * Explicit snap interval, or null to follow [scale].
     *
     * Derived is the default so that changing the scale changes the grid with nothing else
     * to keep in sync. The override exists because the brief lists the snap interval as
     * something that must be configurable, and a value with no way to set it would be a
     * step back from that.
     */
    val snapOverride: Duration? = null,
    /**
     * Whether a drag snaps on every frame or only when the finger lifts.
     *
     * Live snapping steps the bar in grid units, which makes the grid visible while
     * dragging; release-only tracks the finger 1:1 and settles on release. Both land on
     * exactly the same grid — only the feel differs — so this is a taste knob, not a
     * correctness one.
     */
    val snapWhileDragging: Boolean = true,
    /**
     * Floor for a resize. Deliberately independent of [snapInterval]: tying the two
     * together would silently change a task's minimum length whenever the user switched
     * snap granularity, so picking "1 day" would inflate every short task.
     */
    val minTaskDuration: Duration = Duration.ofMinutes(15),
    val rowHeight: Dp = 56.dp,
    val labelColumnWidth: Dp = 108.dp,
    val headerHeight: Dp = 44.dp,
    /**
     * Width of the grab zone at each end of a bar. Capped at a third of the bar's width
     * at run time, so a narrow bar always keeps a draggable middle.
     */
    val edgeHandleWidth: Dp = 18.dp,
) {
    /** The grid in force: the override when set, otherwise the scale's own granularity. */
    val snapInterval: Duration get() = snapOverride ?: scale.defaultSnapInterval

    /** Rendered width of one tick cell: the scale's baseline, scaled by zoom. */
    val tickWidth: Dp get() = scale.baseTickWidth * zoom

    companion object {
        const val MIN_ZOOM = 0.4f
        const val MAX_ZOOM = 4f

        /** Manual snap options offered in the UI, alongside an automatic entry. */
        val SnapOptions: List<Duration> = listOf(
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofMinutes(30),
            Duration.ofHours(1),
            Duration.ofDays(1),
        )

        fun snapLabel(interval: Duration): String = when {
            interval.toMinutes() < 60 -> "${interval.toMinutes()} 分"
            interval.toHours() < 24 -> "${interval.toHours()} 時"
            else -> "${interval.toDays()} 日"
        }
    }
}
