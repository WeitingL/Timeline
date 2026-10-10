package com.weiting.timeline.scheduler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.weiting.timeline.scheduler.SchedulerState
import com.weiting.timeline.scheduler.TimeAxis
import com.weiting.timeline.scheduler.model.Task
import com.weiting.timeline.scheduler.ticksIn
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Narrow bars must stay visible and (later) grabbable, so they never render thinner than this. */
private val MinBarWidth = 10.dp
private val BarVerticalInset = 9.dp

/**
 * The scrollable task area: grid lines behind, one lane per task in front.
 *
 * Both the grid and the bar positions read the viewport in the draw / layout phase rather
 * than during composition, so a scroll gesture never recomposes this subtree.
 */
@Composable
fun TimelineContent(
    state: SchedulerState,
    axis: TimeAxis,
    modifier: Modifier = Modifier,
) {
    val scale = state.config.scale
    val minorLineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val majorLineColor = MaterialTheme.colorScheme.outlineVariant
    val nowColor = MaterialTheme.colorScheme.error

    Column(
        modifier.drawBehind {
            val viewportStart = state.viewportStartMinutes
            val ticks = scale.ticksIn(
                from = axis.timeOf(viewportStart),
                to = axis.timeOf(viewportStart + size.width / axis.pxPerMinute),
            )
            ticks.forEach { tick ->
                val x = axis.xOf(tick.time, viewportStart)
                drawLine(
                    color = if (tick.isMajor) majorLineColor else minorLineColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = if (tick.isMajor) 1.5.dp.toPx() else 1.dp.toPx(),
                )
            }
            drawNowLine(axis, viewportStart, nowColor)
        },
    ) {
        state.tasks.forEach { task ->
            TaskLane(state = state, axis = axis, task = task)
        }
    }
}

@Composable
private fun TaskLane(
    state: SchedulerState,
    axis: TimeAxis,
    task: Task,
) {
    val rowHeight = state.config.rowHeight
    val barWidth = with(LocalDensity.current) {
        axis.widthOf(task.duration).toDp()
    }.coerceAtLeast(MinBarWidth)

    Box(
        Modifier
            .fillMaxWidth()
            .height(rowHeight),
    ) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                // Reading the viewport inside offset's lambda defers it to the layout
                // phase: scrolling re-places the bar without recomposing it.
                .offset { IntOffset(axis.xOf(task.start, state.viewportStartMinutes).roundToInt(), 0) }
                .padding(vertical = BarVerticalInset)
                .width(barWidth)
                .height(rowHeight - BarVerticalInset * 2)
                .clip(RoundedCornerShape(6.dp))
                .background(barColor(task.colorIndex)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

/**
 * The pinned left column. It sits outside the horizontally scrollable area but inside the
 * same vertical scroller as the lanes, which is what keeps the two vertically in sync
 * without any manual offset plumbing.
 */
@Composable
fun TaskLabelColumn(
    state: SchedulerState,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        state.tasks.forEach { task ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(state.config.rowHeight)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = task.start.format(LaneTimeFormat),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawNowLine(
    axis: TimeAxis,
    viewportStartMinutes: Double,
    color: Color,
) {
    val x = axis.xOf(LocalDateTime.now(), viewportStartMinutes)
    if (x < 0f || x > size.width) return
    drawLine(
        color = color.copy(alpha = 0.7f),
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = 1.5.dp.toPx(),
    )
}

private val LaneTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M/d HH:mm", Locale.TAIWAN)
