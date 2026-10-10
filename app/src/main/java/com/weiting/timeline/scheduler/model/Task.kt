package com.weiting.timeline.scheduler.model

import java.time.Duration
import java.time.LocalDateTime

/**
 * A single schedulable item. [start] plus [duration] is the canonical representation;
 * [end] is derived so there is only ever one source of truth to update when a bar is
 * dragged or resized.
 */
data class Task(
    val id: String,
    val title: String,
    val start: LocalDateTime,
    val duration: Duration,
    val colorIndex: Int = 0,
) {
    val end: LocalDateTime get() = start.plus(duration)
}
