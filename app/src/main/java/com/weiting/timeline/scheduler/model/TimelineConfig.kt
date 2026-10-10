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
    val rowHeight: Dp = 56.dp,
    val labelColumnWidth: Dp = 108.dp,
    val headerHeight: Dp = 44.dp,
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
    }
}
