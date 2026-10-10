package com.weiting.timeline.scheduler.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Duration

/**
 * Every tunable knob in one immutable value, so "configurable" means a single `copy()`
 * rather than a scattering of parameters threaded through the composable tree.
 *
 * The four knobs the spec asks for are [scale], [zoom], [snapInterval] and [rowHeight].
 */
@Immutable
data class TimelineConfig(
    val scale: TimeScale = TimeScale.DAY,
    val zoom: Float = 1f,
    val snapInterval: Duration = Duration.ofMinutes(15),
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
    /** Rendered width of one tick cell: the scale's baseline, scaled by zoom. */
    val tickWidth: Dp get() = scale.baseTickWidth * zoom

    companion object {
        const val MIN_ZOOM = 0.4f
        const val MAX_ZOOM = 4f

        /** Snap options offered in the UI. */
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
