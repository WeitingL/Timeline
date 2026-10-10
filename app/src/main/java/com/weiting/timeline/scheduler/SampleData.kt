package com.weiting.timeline.scheduler

import com.weiting.timeline.scheduler.model.Task
import java.time.Duration
import java.time.LocalDate

/**
 * Sample data anchored to *today* so the screen is populated whenever the app is run,
 * rather than on a hard-coded date that would drift out of view over time.
 *
 * Deliberately mixed: overlapping bars, a sub-hour task to exercise the minimum-bar-width
 * path, and one multi-day task that only makes sense at the WEEK / MONTH scales.
 */
fun sampleTasks(today: LocalDate = LocalDate.now()): List<Task> {
    val day = today.atStartOfDay()
    return listOf(
        Task(
            id = "t1",
            title = "需求確認",
            start = day.plusHours(9),
            duration = Duration.ofMinutes(90),
            colorIndex = 0,
        ),
        Task(
            id = "t2",
            title = "設計稿",
            start = day.plusHours(10).plusMinutes(30),
            duration = Duration.ofHours(3),
            colorIndex = 1,
        ),
        Task(
            id = "t3",
            title = "API 開發",
            start = day.plusHours(13),
            duration = Duration.ofHours(5),
            colorIndex = 2,
        ),
        Task(
            id = "t4",
            title = "站立會議",
            start = day.plusHours(9).plusMinutes(30),
            duration = Duration.ofMinutes(15),
            colorIndex = 3,
        ),
        Task(
            id = "t5",
            title = "整合測試",
            start = day.plusDays(1).plusHours(10),
            duration = Duration.ofHours(6),
            colorIndex = 4,
        ),
        Task(
            id = "t6",
            title = "回歸測試週",
            start = day.plusDays(2),
            duration = Duration.ofDays(4),
            colorIndex = 5,
        ),
    )
}
