package com.weiting.timeline.scheduler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.weiting.timeline.scheduler.DragMode
import com.weiting.timeline.scheduler.SchedulerState
import com.weiting.timeline.scheduler.model.TimeScale
import com.weiting.timeline.scheduler.model.TimelineConfig
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Scale selector, period navigation, snap granularity, and the zoom knob.
 *
 * The arrows and "今天" animate the viewport rather than paging it: the timeline stays one
 * continuous, freely scrollable axis, and navigation is just a smooth jump to a known
 * instant.
 */
@Composable
fun SchedulerControls(
    state: SchedulerState,
    pxPerMinute: Float,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val config = state.config

    // The viewport changes every frame while scrolling. derivedStateOf means this only
    // triggers recomposition when the resulting *label* changes, not per frame.
    val windowLabel by remember(config.scale) {
        derivedStateOf {
            config.scale.windowLabel(config.scale.windowStart(state.viewportStart))
        }
    }

    // Same trick for the live drag readout: with snapping on, the string only changes as
    // the bar crosses a grid line, so the control bar recomposes a handful of times per
    // gesture rather than once per frame.
    val dragLabel by remember {
        derivedStateOf {
            state.draft?.let { draft ->
                val verb = when (draft.mode) {
                    DragMode.Move -> "移動"
                    DragMode.ResizeStart -> "調整起點"
                    DragMode.ResizeEnd -> "調整長度"
                }
                "$verb  ${HourMinute.format(draft.start)} → ${HourMinute.format(draft.end)}" +
                    "  (${formatDuration(draft.duration)})"
            }
        }
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TimeScale.entries.forEach { scale ->
                FilterChip(
                    selected = config.scale == scale,
                    onClick = { state.setScale(scale) },
                    label = { Text(scale.displayName) },
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { scope.launch { state.stepBy(-1) } }) { Text("◀") }
            TextButton(onClick = { scope.launch { state.goToToday(pxPerMinute) } }) { Text("今天") }
            TextButton(onClick = { scope.launch { state.stepBy(1) } }) { Text("▶") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = dragLabel ?: windowLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (dragLabel != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "縮放",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = config.zoom,
                onValueChange = state::setZoom,
                valueRange = TimelineConfig.MIN_ZOOM..TimelineConfig.MAX_ZOOM,
                modifier = Modifier.width(132.dp).padding(start = 8.dp),
            )
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "吸附",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 3.dp),
            )
            // "自動" is the default: the grid follows the scale. The manual entries are
            // the override the brief asks to remain configurable.
            MiniChip(
                text = "自動 (${TimelineConfig.snapLabel(config.scale.defaultSnapInterval)})",
                selected = config.snapOverride == null,
                onClick = { state.setSnapOverride(null) },
            )
            TimelineConfig.SnapOptions.forEach { interval ->
                MiniChip(
                    text = TimelineConfig.snapLabel(interval),
                    selected = config.snapOverride == interval,
                    onClick = { state.setSnapOverride(interval) },
                )
            }
            Spacer(Modifier.width(6.dp))
            MiniChip(
                text = "拖曳時即時吸附",
                selected = config.snapWhileDragging,
                onClick = { state.setSnapWhileDragging(!config.snapWhileDragging) },
            )
        }
    }
}

/**
 * A compact chip. Material's FilterChip is too tall to stack a third control row on a
 * phone, and these are secondary knobs.
 */
@Composable
private fun MiniChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .border(
                width = 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private val HourMinute: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M/d HH:mm", Locale.TAIWAN)
