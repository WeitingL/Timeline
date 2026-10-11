package com.weiting.timeline.scheduler.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.weiting.timeline.scheduler.DragMode
import com.weiting.timeline.scheduler.SchedulerScreen
import com.weiting.timeline.scheduler.SchedulerState
import com.weiting.timeline.scheduler.rememberSchedulerState
import com.weiting.timeline.scheduler.sampleTasks
import com.weiting.timeline.scheduler.model.TimeScale
import com.weiting.timeline.scheduler.model.TimelineConfig
import com.weiting.timeline.ui.theme.TimelineTheme

/**
 * Previews for tracking UI behaviour from the IDE.
 *
 * Two things make them worth having here rather than only running the app:
 *
 * - The four scales and the zoom extremes are the cases where tick density, label
 *   stride and the minimum bar width all interact, and flipping between them in the app
 *   loses the comparison.
 * - The drag states are otherwise only reachable with a finger on a device. They are set
 *   up by driving [SchedulerState] directly, which is possible precisely because the
 *   state holder is a plain class.
 *
 * The hint pulse on the grips runs on a delay, so a freshly refreshed preview catches it
 * mid-animation; the drag-state previews below pin the grips lit instead.
 */

// ---------------------------------------------------------------- whole screen

@Preview(name = "01 日 · light", showBackground = true, device = PHONE)
@Composable
private fun PreviewDay() = Framed {
    SchedulerScreen(state = previewState(TimeScale.DAY))
}

@Preview(name = "02 日 · dark", showBackground = true, device = PHONE, uiMode = NIGHT)
@Composable
private fun PreviewDayDark() = Framed {
    SchedulerScreen(state = previewState(TimeScale.DAY))
}

@Preview(name = "03 週", showBackground = true, device = PHONE)
@Composable
private fun PreviewWeek() = Framed {
    SchedulerScreen(state = previewState(TimeScale.WEEK))
}

@Preview(name = "04 月", showBackground = true, device = PHONE)
@Composable
private fun PreviewMonth() = Framed {
    SchedulerScreen(state = previewState(TimeScale.MONTH))
}

@Preview(name = "05 年", showBackground = true, device = PHONE)
@Composable
private fun PreviewYear() = Framed {
    SchedulerScreen(state = previewState(TimeScale.YEAR))
}

// ---------------------------------------------------------------- zoom extremes

@Preview(name = "06 日 · zoom 最小", showBackground = true, device = PHONE)
@Composable
private fun PreviewZoomedOut() = Framed {
    SchedulerScreen(state = previewState(TimeScale.DAY, zoom = TimelineConfig.MIN_ZOOM))
}

@Preview(name = "07 日 · zoom 最大", showBackground = true, device = PHONE)
@Composable
private fun PreviewZoomedIn() = Framed {
    SchedulerScreen(state = previewState(TimeScale.DAY, zoom = TimelineConfig.MAX_ZOOM))
}

@Preview(name = "08 月 · zoom 最小（bar 觸底）", showBackground = true, device = PHONE)
@Composable
private fun PreviewMinBarWidth() = Framed {
    // Every bar is clamped to MinBarWidth here, which is the case that decides whether
    // edge resize is still usable.
    SchedulerScreen(state = previewState(TimeScale.MONTH, zoom = TimelineConfig.MIN_ZOOM))
}

// ---------------------------------------------------------------- drag states

@Preview(name = "09 拖曳中 · 移動", showBackground = true, device = PHONE)
@Composable
private fun PreviewDraggingMove() = Framed {
    SchedulerScreen(state = previewState(TimeScale.DAY, drag = DragMode.Move))
}

@Preview(name = "10 拖曳中 · 調整起點", showBackground = true, device = PHONE)
@Composable
private fun PreviewDraggingStart() = Framed {
    SchedulerScreen(state = previewState(TimeScale.DAY, drag = DragMode.ResizeStart))
}

@Preview(name = "11 拖曳中 · 調整長度", showBackground = true, device = PHONE)
@Composable
private fun PreviewDraggingEnd() = Framed {
    SchedulerScreen(state = previewState(TimeScale.DAY, drag = DragMode.ResizeEnd))
}

// ---------------------------------------------------------------- pieces

@Preview(name = "14 控制列", showBackground = true, widthDp = 400)
@Composable
private fun PreviewControls() = Framed {
    SchedulerControls(previewState(TimeScale.DAY), pxPerMinute = 2f)
}

@Preview(name = "15 控制列 · 拖曳讀數", showBackground = true, widthDp = 400)
@Composable
private fun PreviewControlsDragging() = Framed {
    SchedulerControls(previewState(TimeScale.DAY, drag = DragMode.ResizeEnd), pxPerMinute = 2f)
}

@Preview(name = "16 左欄", showBackground = true, widthDp = 160)
@Composable
private fun PreviewLabelColumn() = Framed {
    TaskLabelColumn(previewState(TimeScale.DAY))
}

@Preview(name = "12 編輯中 · 週（吸附=天）", showBackground = true, device = PHONE)
@Composable
private fun PreviewEditingWeek() = Framed {
    SchedulerScreen(state = previewState(TimeScale.WEEK, drag = DragMode.ResizeEnd))
}

@Preview(name = "13 編輯中 · 年（吸附=週）", showBackground = true, device = PHONE)
@Composable
private fun PreviewEditingYear() = Framed {
    SchedulerScreen(state = previewState(TimeScale.YEAR, drag = DragMode.Move))
}

// ---------------------------------------------------------------- form factors

@Preview(name = "17 窄螢幕 320dp", showBackground = true, widthDp = 320, heightDp = 640)
@Composable
private fun PreviewNarrow() = Framed {
    SchedulerScreen(state = previewState(TimeScale.DAY))
}

@Preview(name = "18 平板", showBackground = true, device = TABLET)
@Composable
private fun PreviewTablet() = Framed {
    SchedulerScreen(state = previewState(TimeScale.WEEK))
}

@Preview(name = "19 字體放大 1.5x", showBackground = true, device = PHONE, fontScale = 1.5f)
@Composable
private fun PreviewLargeFont() = Framed {
    SchedulerScreen(state = previewState(TimeScale.DAY))
}

// ---------------------------------------------------------------- helpers

private const val PHONE = "spec:width=411dp,height=891dp"
private const val TABLET = "spec:width=1280dp,height=800dp"
private const val NIGHT = android.content.res.Configuration.UI_MODE_NIGHT_YES

@Composable
private fun Framed(content: @Composable () -> Unit) {
    TimelineTheme { Box(Modifier.fillMaxSize()) { content() } }
}

/**
 * A scheduler pinned to one configuration, optionally mid-gesture.
 *
 * Driving the state holder directly is only possible because it is a plain class rather
 * than a ViewModel — which is also what lets the unit tests cover the drag maths.
 */
@Composable
private fun previewState(
    scale: TimeScale,
    zoom: Float = 1f,
    drag: DragMode? = null,
): SchedulerState {
    val state = rememberSchedulerState(
        tasks = ::sampleTasks,
        config = TimelineConfig(scale = scale, zoom = zoom),
    )
    remember(state, drag) {
        if (drag != null) {
            // "設計稿" is the 3-hour task, wide enough to show all three zones.
            state.beginEdit("t2", drag)
            state.beginDrag("t2", drag)
            state.dragBy(0f, 1f)
        }
        Unit
    }
    return state
}
