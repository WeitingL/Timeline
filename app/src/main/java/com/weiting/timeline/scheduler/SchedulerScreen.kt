package com.weiting.timeline.scheduler

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import com.weiting.timeline.scheduler.ui.SchedulerControls
import com.weiting.timeline.scheduler.ui.TaskLabelColumn
import com.weiting.timeline.scheduler.ui.TimelineContent
import com.weiting.timeline.scheduler.ui.TimelineHeader

/**
 * The whole scheduler screen.
 *
 * Layout shape:
 * ```
 * ┌──────────────────────────────────────────┐
 * │ [日][週][月][年]        ◀  今天  ▶       │  SchedulerControls
 * │ 2026/10/10 五                    縮放 ──  │
 * ├──────────┬───────────────────────────────┤
 * │          │ time-axis ticks               │  TimelineHeader
 * ├──────────┼───────────────────────────────┤
 * │ 任務名稱  │ ▓▓▓▓                          │  TaskLabelColumn + TimelineContent
 * │ 任務名稱  │      ▓▓▓▓▓▓                   │
 * └──────────┴───────────────────────────────┘
 * ```
 *
 * Horizontal scroll is *not* `Modifier.horizontalScroll`: that would need a child as wide
 * as the whole time range (hundreds of thousands of dp at the DAY scale). Instead a shared
 * [rememberScrollableState] feeds deltas into [SchedulerState], the content stays exactly
 * one viewport wide, and the axis is conceptually unbounded. Fling physics still come for
 * free from `scrollable`.
 *
 * Vertical scroll wraps the label column and the lanes together, so the two can never
 * drift out of sync.
 */
@Composable
fun SchedulerScreen(
    modifier: Modifier = Modifier,
    state: SchedulerState = rememberSchedulerState(),
) {
    val config = state.config
    val density = LocalDensity.current

    // One linear px-per-minute factor derived from scale (baseline) and zoom (multiplier).
    val pxPerMinute = with(density) {
        (config.tickWidth.toPx() / config.scale.nominalTickMinutes).toFloat()
    }
    val axis = remember(state.origin, pxPerMinute) {
        TimeAxis(origin = state.origin, pxPerMinute = pxPerMinute)
    }

    val horizontalScroll = rememberScrollableState { delta ->
        state.scrollByPx(delta, pxPerMinute)
    }
    val verticalScroll = rememberScrollState()

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            SchedulerControls(state)
            HorizontalDivider()

            Row(Modifier.fillMaxWidth().height(config.headerHeight)) {
                Spacer(Modifier.width(config.labelColumnWidth))
                VerticalDivider()
                TimelineHeader(
                    state = state,
                    axis = axis,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clipToBounds()
                        .scrollable(horizontalScroll, Orientation.Horizontal),
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(verticalScroll),
            ) {
                TaskLabelColumn(state, Modifier.width(config.labelColumnWidth))
                VerticalDivider()
                TimelineContent(
                    state = state,
                    axis = axis,
                    modifier = Modifier
                        .weight(1f)
                        .clipToBounds()
                        .scrollable(horizontalScroll, Orientation.Horizontal),
                )
            }
        }
    }
}
