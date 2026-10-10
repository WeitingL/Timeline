package com.weiting.timeline.scheduler.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.weiting.timeline.scheduler.DragMode
import com.weiting.timeline.scheduler.SchedulerState
import com.weiting.timeline.scheduler.TimeAxis
import com.weiting.timeline.scheduler.hitTestBar
import com.weiting.timeline.scheduler.model.Task
import com.weiting.timeline.scheduler.ticksIn
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bars never render thinner than this. It has to clear twice the edge-handle width with
 * room left over, or a short task would be nothing but two resize handles.
 */
private val MinBarWidth = 52.dp

/** Below this width the grip marks are dropped — they would be all you could see. */
private val MinGripWidth = 34.dp

private val BarVerticalInset = 9.dp

/** Discovery hint timing: a brief settle, then a staggered ripple down the lanes. */
private const val HintPulses = 3
private const val HintStartDelay = 450L
private const val HintStagger = 70L
private val BarShape = RoundedCornerShape(6.dp)

/**
 * The scrollable task area: grid lines behind, one lane per task in front.
 *
 * The grid reads the viewport in the draw phase, so a scroll gesture never recomposes
 * this subtree.
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
        state.tasks.forEachIndexed { index, task ->
            key(task.id) {
                TaskLane(state = state, axis = axis, task = task, laneIndex = index)
            }
        }
    }
}

@Composable
private fun TaskLane(
    state: SchedulerState,
    axis: TimeAxis,
    task: Task,
    laneIndex: Int,
) {
    val config = state.config
    val density = LocalDensity.current

    // Scoped per lane: for every task that is not being dragged this stays null, and
    // derivedStateOf compares structurally, so no invalidation reaches those lanes. A
    // bare `state.draft` read here would recompose all of them on every frame.
    val draft by rememberDraftFor(state, task.id)

    val start = draft?.start ?: task.start
    val duration = draft?.duration ?: task.duration
    val dragging = draft != null

    val minBarPx = with(density) { MinBarWidth.toPx() }
    val barWidthPx = axis.widthOf(duration).coerceAtLeast(minBarPx)
    val barWidth = with(density) { barWidthPx.toDp() }

    // Capped at a third of the bar so the middle always stays grabbable for a move.
    val handlePx = min(with(density) { config.edgeHandleWidth.toPx() }, barWidthPx / 3f)
    val handleWidth = with(density) { handlePx.toDp() }
    val pxPerMinute = axis.pxPerMinute
    val showGrips = barWidth >= MinGripWidth

    // Which zone the finger is on right now, for the press reaction. Falls back to the
    // in-flight drag so the highlight survives past the touch-slop handover.
    var pressedZone by remember(task.id) { mutableStateOf<DragMode?>(null) }
    val activeZone = draft?.mode ?: pressedZone

    // A short, finite discovery hint: the grips breathe a few times on first appearance
    // and then stop. An infinite transition would be a permanent distraction, and a
    // static grip is easy to miss as an affordance.
    val hint = remember { Animatable(0f) }
    LaunchedEffect(task.id) {
        delay(HintStartDelay + laneIndex * HintStagger)
        repeat(HintPulses) {
            hint.animateTo(1f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
            hint.animateTo(0f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(config.rowHeight),
    ) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                // Reading the viewport inside offset's lambda defers it to the layout
                // phase: plain scrolling re-places the bar without recomposing it.
                .offset { IntOffset(axis.xOf(start, state.viewportStartMinutes).roundToInt(), 0) }
                .padding(vertical = BarVerticalInset)
                .width(barWidth)
                .height(config.rowHeight - BarVerticalInset * 2)
                .clip(BarShape)
                .background(barColor(task.colorIndex))
                .then(
                    if (dragging) {
                        Modifier.border(1.5.dp, Color.White.copy(alpha = 0.9f), BarShape)
                    } else {
                        Modifier
                    },
                )
                // Observation only, and it consumes nothing: it reports which zone the
                // finger landed on so the bar can react before the drag even starts.
                .pointerInput(task.id, barWidthPx, handlePx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressedZone = hitTestBar(down.position.x, barWidthPx, handlePx)
                        waitForUpOrCancellation()
                        pressedZone = null
                    }
                }
                .pointerInput(task.id, pxPerMinute, barWidthPx, handlePx) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            // task.id, never the Task: a stale captured Task would rewind
                            // the edit. See SchedulerState.beginDrag.
                            state.beginDrag(task.id, hitTestBar(offset.x, barWidthPx, handlePx))
                        },
                        onDragEnd = { state.commitDrag() },
                        onDragCancel = { state.cancelDrag() },
                    ) { change, dragAmount ->
                        // Consuming the change is what keeps the ancestor
                        // Modifier.scrollable from panning the timeline at the same time.
                        change.consume()
                        state.dragBy(dragAmount, pxPerMinute)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // The zone the finger is on, lit up. This is the whole hint: press anywhere
            // and the bar tells you what that spot does.
            when (activeZone) {
                DragMode.ResizeStart -> ZoneHighlight(
                    Modifier.align(Alignment.CenterStart).width(handleWidth),
                )
                DragMode.ResizeEnd -> ZoneHighlight(
                    Modifier.align(Alignment.CenterEnd).width(handleWidth),
                )
                DragMode.Move -> ZoneHighlight(Modifier.fillMaxSize(), alpha = 0.12f)
                null -> Unit
            }
            Text(
                text = task.title,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = if (showGrips) 11.dp else 6.dp),
            )
            if (showGrips) {
                Grip(
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
                    emphasis = if (activeZone == DragMode.ResizeStart) 1f else hint.value,
                )
                Grip(
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                    emphasis = if (activeZone == DragMode.ResizeEnd) 1f else hint.value,
                )
            }
        }
    }
}

/** Translucent wash over whichever zone the finger is on. */
@Composable
private fun ZoneHighlight(modifier: Modifier, alpha: Float = 0.22f) {
    Box(modifier.fillMaxHeight().background(Color.White.copy(alpha = alpha)))
}

/**
 * The visible affordance for an edge-resize handle. [emphasis] runs 0..1 and drives both
 * the opacity and the height, so the hint pulse reads as the grip growing rather than
 * merely brightening.
 */
@Composable
private fun Grip(modifier: Modifier, emphasis: Float) {
    val e = emphasis.coerceIn(0f, 1f)
    Box(
        modifier
            .width(2.dp)
            .height(11.dp + 5.dp * e)
            .clip(RoundedCornerShape(1.dp))
            .background(Color.White.copy(alpha = 0.5f + 0.45f * e)),
    )
}

/**
 * The pinned left column. It sits outside the horizontally scrollable area but inside the
 * same vertical scroller as the lanes, which keeps the two in sync without any manual
 * offset plumbing. While a bar is dragged it shows the draft time, so the snapped result
 * is readable as it happens.
 */
@Composable
fun TaskLabelColumn(
    state: SchedulerState,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        state.tasks.forEach { task ->
            key(task.id) {
                val draft by rememberDraftFor(state, task.id)
                val start = draft?.start ?: task.start
                val duration = draft?.duration ?: task.duration
                val dragging = draft != null

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
                            text = "${LaneTimeFormat.format(start)} · ${formatDuration(duration)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (dragging) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberDraftFor(state: SchedulerState, taskId: String) =
    remember(state, taskId) {
        derivedStateOf { state.draft?.takeIf { it.taskId == taskId } }
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

internal fun formatDuration(duration: Duration): String {
    val days = duration.toDays()
    val hours = duration.toHours() % 24
    val minutes = duration.toMinutes() % 60
    return when {
        days > 0L -> if (hours == 0L) "${days}d" else "${days}d ${hours}h"
        duration.toHours() > 0L -> if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

private val LaneTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M/d HH:mm", Locale.TAIWAN)
