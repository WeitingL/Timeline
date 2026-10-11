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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import com.weiting.timeline.scheduler.ui.SchedulerControls
import com.weiting.timeline.scheduler.ui.TaskLabelColumn
import com.weiting.timeline.scheduler.ui.TimelineContent
import com.weiting.timeline.scheduler.ui.TimelineHeader
import com.weiting.timeline.scheduler.ui.twoFingerHorizontalSwipe

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
    val scope = rememberCoroutineScope()

    // Ticks once a minute. Passed down so neither canvas reads the clock in its draw
    // lambda, where it was only sampled when something else invalidated the draw.
    val now by rememberNow()

    // The launch position needs the viewport width, which only exists after the first
    // layout pass, so it cannot be set in the state's constructor.
    LaunchedEffect(state.viewportWidthPx, pxPerMinute) {
        if (!state.hasCentredOnLaunch && state.viewportWidthPx > 0f && pxPerMinute > 0f) {
            state.centreOnNow(pxPerMinute)
            state.markCentredOnLaunch()
        }
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                // Two fingers switch period; one finger keeps panning and editing. The
                // modifier watches the Initial pass, so it claims the gesture ahead of
                // both the bars and the scroller. Suppressed mid-drag so a second finger
                // landing during an edit cannot hijack it.
                // editingTaskId, not draft: the lock has to hold from touch-down, and a
                // draft only exists once the gesture has committed to the horizontal axis.
                .twoFingerHorizontalSwipe(enabled = { !state.isEditing }) { direction ->
                    scope.launch { state.stepBy(direction) }
                },
        ) {
            SchedulerControls(state, pxPerMinute)
            HorizontalDivider()

            Row(Modifier.fillMaxWidth().height(config.headerHeight)) {
                Spacer(Modifier.width(config.labelColumnWidth))
                VerticalDivider()
                TimelineHeader(
                    state = state,
                    axis = axis,
                    now = now,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clipToBounds()
                        .scrollable(
                            state = horizontalScroll,
                            orientation = Orientation.Horizontal,
                            enabled = !state.isEditing,
                        ),
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
                    now = now,
                    modifier = Modifier
                        .weight(1f)
                        // The timeline area's width, with the label column excluded: this
                        // is the span that represents time, so it is what "centre" and the
                        // edge threshold are measured against.
                        .onSizeChanged { state.onViewportWidthChanged(it.width.toFloat()) }
                        .clipToBounds()
                        // Declarative half of the edit lock. Consuming the pointer events
                        // is the other half, and on its own it proved too subtle to rely
                        // on: the bar and this modifier cross the same touch slop on the
                        // same event, so a single unconsumed change is enough to hand the
                        // pan away for the rest of the gesture. Disabling it outright from
                        // touch-down removes the race instead of trying to win it.
                        .scrollable(
                            state = horizontalScroll,
                            orientation = Orientation.Horizontal,
                            enabled = !state.isEditing,
                        ),
                )
            }
        }
    }
}
