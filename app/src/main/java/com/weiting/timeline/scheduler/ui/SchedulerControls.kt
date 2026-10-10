package com.weiting.timeline.scheduler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.weiting.timeline.scheduler.SchedulerState
import com.weiting.timeline.scheduler.model.TimeScale
import com.weiting.timeline.scheduler.model.TimelineConfig
import kotlinx.coroutines.launch

/**
 * Scale selector, period navigation, and the zoom knob.
 *
 * The arrows and "今天" animate the viewport rather than paging it: the timeline stays one
 * continuous, freely scrollable axis, and navigation is just a smooth jump to a known
 * instant.
 */
@Composable
fun SchedulerControls(
    state: SchedulerState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val config = state.config

    // The viewport changes every frame while scrolling. derivedStateOf means this only
    // triggers recomposition when the resulting *label* actually changes, not per frame.
    val windowLabel by remember(config.scale) {
        derivedStateOf {
            config.scale.windowLabel(config.scale.windowStart(state.viewportStart))
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
            TextButton(onClick = { scope.launch { state.goToToday() } }) { Text("今天") }
            TextButton(onClick = { scope.launch { state.stepBy(1) } }) { Text("▶") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = windowLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
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
                modifier = Modifier.width(140.dp).padding(start = 8.dp),
            )
        }
    }
}
