package com.weiting.timeline.scheduler

import androidx.compose.runtime.Immutable
import com.weiting.timeline.scheduler.model.Task
import java.time.Duration
import java.time.LocalDateTime

/** Which part of a bar the finger grabbed. */
enum class DragMode { Move, ResizeStart, ResizeEnd }

/**
 * One in-flight edit.
 *
 * Held apart from [SchedulerState.tasks] deliberately: that list is a
 * `SnapshotStateList`, so writing to it on every frame of a drag would invalidate every
 * lane that reads the list, not just the one being dragged. A single nullable field is
 * read by exactly one lane instead.
 *
 * [originalStart] and [originalDuration] pin the gesture to the state it began in. Each
 * frame derives the draft from the original plus the accumulated finger travel, never
 * from the previous frame's draft — otherwise, with snapping on, every movement smaller
 * than one grid step would be swallowed and the bar would stop following the finger.
 */
@Immutable
data class TaskDraft(
    val taskId: String,
    val mode: DragMode,
    val originalStart: LocalDateTime,
    val originalDuration: Duration,
    val start: LocalDateTime,
    val duration: Duration,
) {
    val end: LocalDateTime get() = start.plus(duration)
    val originalEnd: LocalDateTime get() = originalStart.plus(originalDuration)

    companion object {
        fun of(task: Task, mode: DragMode): TaskDraft = TaskDraft(
            taskId = task.id,
            mode = mode,
            originalStart = task.start,
            originalDuration = task.duration,
            start = task.start,
            duration = task.duration,
        )
    }
}

/**
 * Picks the gesture from where inside the bar the finger landed.
 *
 * [handlePx] is already capped to a third of [barWidthPx] by the caller, so the middle
 * zone never disappears however narrow the bar gets.
 */
fun hitTestBar(x: Float, barWidthPx: Float, handlePx: Float): DragMode = when {
    x <= handlePx -> DragMode.ResizeStart
    x >= barWidthPx - handlePx -> DragMode.ResizeEnd
    else -> DragMode.Move
}
