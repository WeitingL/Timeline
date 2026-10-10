package com.weiting.timeline.scheduler.model

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * The unit of the time axis. Choosing a scale settles three things at once: how wide one
 * tick is before zoom is applied, where tick boundaries fall, and how far one press of the
 * prev/next arrows travels.
 *
 * Scale and zoom stay independent knobs: the rendered tick width is
 * [baseTickWidth] * zoom, so changing one never implies the other.
 */
enum class TimeScale(
    val displayName: String,
    val baseTickWidth: Dp,
    /**
     * Nominal length of one tick, used only to derive a single linear px-per-minute
     * factor for the whole axis. Real tick *positions* come from calendar arithmetic, so a
     * 28-day February genuinely renders narrower than a 31-day January — the axis stays
     * linear in minutes and the irregularity lives in the tick generator instead.
     */
    val nominalTickMinutes: Double,
) {
    DAY("日", 64.dp, 60.0),
    WEEK("週", 88.dp, 60.0 * 24),
    MONTH("月", 40.dp, 60.0 * 24),
    YEAR("年", 80.dp, 60.0 * 24 * 30.4375),
    ;

    /** Rounds [time] down to the tick boundary that contains it. */
    fun floorToTick(time: LocalDateTime): LocalDateTime = when (this) {
        DAY -> time.truncatedTo(ChronoUnit.HOURS)
        WEEK, MONTH -> time.truncatedTo(ChronoUnit.DAYS)
        YEAR -> time.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
    }

    fun nextTick(time: LocalDateTime): LocalDateTime = when (this) {
        DAY -> time.plusHours(1)
        WEEK, MONTH -> time.plusDays(1)
        YEAR -> time.plusMonths(1)
    }

    /** Ticks that deserve a heavier line: midnight, week start, new year. */
    fun isMajorTick(time: LocalDateTime): Boolean = when (this) {
        DAY -> time.hour == 0
        WEEK, MONTH -> time.dayOfWeek == DayOfWeek.MONDAY
        YEAR -> time.monthValue == 1
    }

    /** Start of the window containing [time] — where "今天" parks the viewport. */
    fun windowStart(time: LocalDateTime): LocalDateTime = when (this) {
        DAY -> time.truncatedTo(ChronoUnit.DAYS)
        WEEK -> time.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .truncatedTo(ChronoUnit.DAYS)
        MONTH -> time.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
        YEAR -> time.withDayOfYear(1).truncatedTo(ChronoUnit.DAYS)
    }

    /** One press of ◀ / ▶, in calendar units so month and year lengths stay honest. */
    fun step(time: LocalDateTime, direction: Int): LocalDateTime {
        val n = direction.toLong()
        return when (this) {
            DAY -> time.plusDays(n)
            WEEK -> time.plusWeeks(n)
            MONTH -> time.plusMonths(n)
            YEAR -> time.plusYears(n)
        }
    }

    fun tickLabel(time: LocalDateTime): String = when (this) {
        DAY -> if (time.hour == 0) FmtMonthDay.format(time) else FmtHourMinute.format(time)
        WEEK -> FmtDayWithWeekday.format(time)
        MONTH -> FmtMonthDay.format(time)
        YEAR -> if (time.monthValue == 1) FmtYearMonth.format(time) else FmtMonthOnly.format(time)
    }

    /** Human label for the window the viewport currently sits in. */
    fun windowLabel(windowStart: LocalDateTime): String = when (this) {
        DAY -> FmtFullDate.format(windowStart)
        WEEK -> "${FmtYearMonthDay.format(windowStart)} – ${FmtMonthDay.format(windowStart.plusDays(6))}"
        MONTH -> FmtYearMonth.format(windowStart)
        YEAR -> "${windowStart.year} 年"
    }

    private companion object {
        val FmtHourMinute: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.TAIWAN)
        val FmtMonthDay: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d", Locale.TAIWAN)
        val FmtYearMonthDay: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d", Locale.TAIWAN)
        val FmtDayWithWeekday: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d E", Locale.TAIWAN)
        val FmtMonthOnly: DateTimeFormatter = DateTimeFormatter.ofPattern("M 月", Locale.TAIWAN)
        val FmtYearMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy 年 M 月", Locale.TAIWAN)
        val FmtFullDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d E", Locale.TAIWAN)
    }
}
