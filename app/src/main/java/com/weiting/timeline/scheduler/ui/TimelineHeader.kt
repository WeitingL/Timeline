package com.weiting.timeline.scheduler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.weiting.timeline.scheduler.SchedulerState
import com.weiting.timeline.scheduler.TimeAxis
import com.weiting.timeline.scheduler.ticksIn
import java.time.LocalDateTime
import kotlin.math.ceil

/**
 * The time-axis header.
 *
 * Drawn on a [Canvas] on purpose: the viewport position is read inside the draw lambda, so
 * scrolling invalidates only the draw phase. No recomposition and no re-layout happen per
 * frame, which is what keeps the header glued to the content without jitter.
 */
@Composable
fun TimelineHeader(
    state: SchedulerState,
    axis: TimeAxis,
    modifier: Modifier = Modifier,
) {
    val scale = state.config.scale
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val majorLabelStyle = labelStyle.copy(
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val minorLineColor = MaterialTheme.colorScheme.outlineVariant
    val majorLineColor = MaterialTheme.colorScheme.outline
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val nowColor = MaterialTheme.colorScheme.error
    val tickWidthDp = state.config.tickWidth

    Canvas(modifier) {
        val viewportStart = state.viewportStartMinutes
        val visibleMinutes = size.width / axis.pxPerMinute
        val ticks = scale.ticksIn(
            from = axis.timeOf(viewportStart),
            to = axis.timeOf(viewportStart + visibleMinutes),
        )

        // Labels are skipped rather than overlapped once ticks get narrow. Major ticks are
        // always labelled so the user never loses the date while zoomed out.
        val tickWidthPx = tickWidthDp.toPx()
        val stride = if (tickWidthPx <= 0f) 1
        else ceil(MIN_LABEL_WIDTH_DP.dp.toPx() / tickWidthPx).toInt().coerceAtLeast(1)

        ticks.forEachIndexed { index, tick ->
            val x = axis.xOf(tick.time, viewportStart)
            drawLine(
                color = if (tick.isMajor) majorLineColor else minorLineColor,
                start = Offset(x, size.height * if (tick.isMajor) 0.35f else 0.62f),
                end = Offset(x, size.height),
                strokeWidth = if (tick.isMajor) 1.5.dp.toPx() else 1.dp.toPx(),
            )
            if (tick.isMajor || index % stride == 0) {
                drawTickLabel(
                    measurer = measurer,
                    text = tick.label,
                    style = if (tick.isMajor) majorLabelStyle else labelStyle,
                    x = x + 4.dp.toPx(),
                    y = 6.dp.toPx(),
                )
            }
        }

        // Baseline separating header from content.
        drawLine(
            color = baselineColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx(),
        )

        drawNowMarker(axis, viewportStart, nowColor)
    }
}

private fun DrawScope.drawTickLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    x: Float,
    y: Float,
) {
    // TextMeasurer caches by (text, style, constraints); the tick labels repeat heavily as
    // you scroll, so measuring in the draw phase stays cheap.
    val layout = measurer.measure(AnnotatedString(text), style)
    drawText(layout, topLeft = Offset(x, y))
}

private fun DrawScope.drawNowMarker(
    axis: TimeAxis,
    viewportStartMinutes: Double,
    color: androidx.compose.ui.graphics.Color,
) {
    val x = axis.xOf(LocalDateTime.now(), viewportStartMinutes)
    if (x < 0f || x > size.width) return
    drawLine(
        color = color,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = 1.5.dp.toPx(),
    )
    drawCircle(color = color, radius = 3.dp.toPx(), center = Offset(x, size.height))
}

private const val MIN_LABEL_WIDTH_DP = 44
