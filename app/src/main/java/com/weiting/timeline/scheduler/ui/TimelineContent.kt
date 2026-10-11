package com.weiting.timeline.scheduler.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import kotlin.math.abs
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

/**
 * How close to a viewport edge a dragged bar has to get before the axis starts scrolling.
 *
 * In dp, so it is a constant *distance* and a varying *duration*: about 45 minutes at
 * DAY/zoom 1 and about three weeks at YEAR/min zoom. That is the right way round — the
 * threshold describes a physical reach near the screen edge, and a finger does not scale
 * with the screen the way a fraction-of-viewport threshold would.
 */
private val EdgeAutoScrollThreshold = 48.dp

/** Edit hatching: wide enough to read as stripes, fine enough not to hide the title. */
private val StripeSpacing = 9.dp
private val StripeWidth = 2.5.dp

/** Ceiling for one frame of auto-scroll, reached only at the very edge. */
private const val AutoScrollMaxPxPerFrame = 14f

/** Discovery hint timing: a brief settle, then a staggered ripple down the lanes. */
private const val HintPulses = 3
private const val HintStartDelay = 450L
private const val HintStagger = 70L
private val BarShape = RoundedCornerShape(6.dp)

/**
 * The scrollable task area: grid lines behind, one lane per task in front.
 *
 * The grid reads the viewport in the draw phase and bar positions read it in the layout
 * phase, so a scroll gesture recomposes nothing here. [now] is passed in rather than read
 * from the clock in the draw lambda, which is what used to freeze the marker between
 * redraws.
 */
@Composable
fun TimelineContent(
    state: SchedulerState,
    axis: TimeAxis,
    now: LocalDateTime,
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
            drawNowLine(axis, viewportStart, now, nowColor)
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
    val editing = state.editingTaskId == task.id

    val minBarPx = with(density) { MinBarWidth.toPx() }
    val barWidthPx = axis.widthOf(duration).coerceAtLeast(minBarPx)
    val barWidth = with(density) { barWidthPx.toDp() }

    // Capped at a third of the bar so the middle always stays grabbable for a move.
    val handlePx = min(with(density) { config.edgeHandleWidth.toPx() }, barWidthPx / 3f)
    val handleWidth = with(density) { handlePx.toDp() }
    val pxPerMinute = axis.pxPerMinute
    val showGrips = barWidth >= MinGripWidth
    val edgeThresholdPx = with(density) { EdgeAutoScrollThreshold.toPx() }
    val metrics = rememberUpdatedState(
        LaneMetrics(
            barWidthPx = barWidthPx,
            handlePx = handlePx,
            pxPerMinute = pxPerMinute,
            edgeThresholdPx = edgeThresholdPx,
        ),
    )

    // One source of truth now that press and drag share a gesture: the state is set at
    // touch-down and cleared on release, so the highlight no longer needs the
    // `draft?.mode ?: pressedZone` fallback that bridged two separate handlers.
    val activeZone = if (state.editingTaskId == task.id) state.editingZone else null

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
                .then(if (editing) Modifier.editingStripes() else Modifier)
                .then(
                    if (editing) {
                        Modifier.border(2.dp, Color.White.copy(alpha = 0.95f), BarShape)
                    } else {
                        Modifier
                    },
                )
                // Keyed on the task id alone: every value that moves during a gesture
                // is read through `metrics`, so the block is never restarted mid-touch.
                .pointerInput(task.id) {
                    awaitEachGesture {
                        // Touch-down: the edit begins here, before any movement. This is
                        // what stops the two-finger period swipe, which runs on the
                        // Initial pass and so cannot rely on consumption, and what
                        // disables the ancestor horizontal scroller.
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val grabbed = metrics.value
                        val zone = hitTestBar(down.position.x, grabbed.barWidthPx, grabbed.handlePx)
                        state.beginEdit(task.id, zone)
                        try {
                            // Buffer. Accumulate until one axis crosses the platform touch
                            // slop, then commit to that axis for the rest of the gesture so
                            // a diagonal drag cannot flip between editing and scrolling.
                            var dx = 0f
                            var dy = 0f
                            var horizontal: Boolean? = null
                            while (horizontal == null) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: return@awaitEachGesture
                                if (!change.pressed) return@awaitEachGesture // a tap
                                dx += change.positionChange().x
                                dy += change.positionChange().y
                                horizontal = when {
                                    abs(dx) >= viewConfiguration.touchSlop -> {
                                        // Consume the crossing event itself, not only the
                                        // ones after it. The ancestor scrollable measures
                                        // the same slop against the same accumulated x, so
                                        // it crosses on this very event; leaving this one
                                        // unconsumed handed it the gesture and it panned
                                        // for the rest of the drag.
                                        change.consume()
                                        true
                                    }
                                    abs(dy) >= viewConfiguration.touchSlop -> false
                                    else -> null
                                }
                            }
                            // Vertical means nothing to a bar, so hand the gesture over by
                            // never consuming: the ancestor verticalScroll picks it up.
                            if (!horizontal) return@awaitEachGesture

                            state.beginDrag(task.id, zone)
                            // Spend the travel used up crossing the buffer, so the bar does
                            // not start one slop behind the finger.
                            state.dragBy(dx, metrics.value.pxPerMinute)

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                // Read the movement BEFORE consuming. positionChange()
                                // reports zero on a consumed change (see
                                // PointerChangeSemanticsTest), so consuming first made the
                                // drag advance by the slop travel alone — one snap step —
                                // and then sit still however far the finger went.
                                val deltaX = change.positionChange().x
                                change.consume()
                                if (!change.pressed) break
                                val m = metrics.value
                                state.dragBy(deltaX, m.pxPerMinute)
                                // The pointer arrives relative to the bar; auto-scroll
                                // needs it relative to the timeline area, and the bar's
                                // own left edge is that offset. Computed here rather than
                                // in the composable body: an argument expression there
                                // reads viewportStartMinutes during composition, which
                                // invalidated every lane on every scroll frame. A snapshot
                                // read inside a pointerInput block is in no observation
                                // scope, so it subscribes nothing — and it is the more
                                // correct value, being this event's viewport rather than
                                // the last composition's.
                                val laneOriginX = axis.xOf(
                                    state.draft?.start ?: task.start,
                                    state.viewportStartMinutes,
                                )
                                state.autoScrollStep(
                                    fingerX = change.position.x + laneOriginX,
                                    thresholdPx = m.edgeThresholdPx,
                                    maxPxPerStep = AutoScrollMaxPxPerFrame,
                                    pxPerMinute = m.pxPerMinute,
                                )
                            }
                            state.commitDrag()
                        } finally {
                            state.endEdit()
                        }
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
                    emphasis = when {
                        activeZone == DragMode.ResizeStart -> 1f
                        editing -> 0.8f
                        else -> hint.value
                    },
                )
                Grip(
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                    emphasis = when {
                        activeZone == DragMode.ResizeEnd -> 1f
                        editing -> 0.8f
                        else -> hint.value
                    },
                )
            }
        }
    }
}

/**
 * Everything the gesture reads that changes while the gesture is running.
 *
 * It exists so the `pointerInput` block can be keyed on the task id alone. Keying it on
 * these values instead restarts the suspend block when any of them changes — and
 * `barWidthPx` changes on the very first frame of a resize, which cancelled the gesture
 * that was setting it.
 */
private data class LaneMetrics(
    val barWidthPx: Float,
    val handlePx: Float,
    val pxPerMinute: Float,
    val edgeThresholdPx: Float,
)

/**
 * Diagonal hatching for a bar under edit.
 *
 * A stripe pattern rather than a colour change: the bar's colour is its identity in the
 * chart, so repainting it would read as "a different task" rather than "this task is being
 * edited". Hatching reads as a state over the top, and it survives the bar being any of
 * the six palette colours.
 *
 * Drawn after `clip(BarShape)` in the chain, so it is clipped to the rounded corners.
 */
private fun Modifier.editingStripes(): Modifier = drawWithContent {
    drawContent()
    val spacing = StripeSpacing.toPx()
    val stroke = StripeWidth.toPx()
    val h = size.height
    // 45 degrees: shifting the start by the height gives a constant-slope diagonal.
    var x = -h
    while (x < size.width + h) {
        drawLine(
            color = Color.White.copy(alpha = 0.32f),
            start = Offset(x, h),
            end = Offset(x + h, 0f),
            strokeWidth = stroke,
        )
        x += spacing
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
    now: LocalDateTime,
    color: Color,
) {
    val x = axis.xOf(now, viewportStartMinutes)
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
