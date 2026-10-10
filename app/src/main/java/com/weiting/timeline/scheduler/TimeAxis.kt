package com.weiting.timeline.scheduler

import com.weiting.timeline.scheduler.model.TimeScale
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.roundToLong

/**
 * The single coordinate-conversion layer between wall-clock time and horizontal pixels.
 * Everything else in the scheduler — header ticks, bar placement, drag, resize, snapping —
 * is expressed in terms of these few functions, which is why they are pure and
 * independently testable.
 *
 * The axis is strictly linear in minutes from [origin]. Calendar irregularities are not
 * baked into the axis; they surface only in [ticksIn], which walks real calendar
 * boundaries.
 */
class TimeAxis(
    val origin: LocalDateTime,
    val pxPerMinute: Float,
) {
    /** Minutes from [origin]. Doubles because the viewport scrolls sub-minute smoothly. */
    fun minutesOf(time: LocalDateTime): Double =
        Duration.between(origin, time).toMinutes().toDouble()

    fun timeOf(minutes: Double): LocalDateTime = origin.plusMinutes(minutes.roundToLong())

    /** X position of [time] relative to a viewport whose left edge is [viewportStartMinutes]. */
    fun xOf(time: LocalDateTime, viewportStartMinutes: Double): Float =
        ((minutesOf(time) - viewportStartMinutes) * pxPerMinute).toFloat()

    /** Inverse of [xOf]. */
    fun timeAt(x: Float, viewportStartMinutes: Double): LocalDateTime =
        timeOf(viewportStartMinutes + x / pxPerMinute)

    fun widthOf(duration: Duration): Float = duration.toMinutes() * pxPerMinute

    /**
     * Rounds [time] to the nearest multiple of [interval], measured from [origin].
     * Anchoring to the origin (rather than to the dragged task's own start) is what makes
     * independently dragged bars line up with each other and with the grid.
     */
    fun snap(time: LocalDateTime, interval: Duration): LocalDateTime {
        val step = interval.toMinutes()
        if (step <= 0L) return time
        val snapped = (minutesOf(time) / step).roundToLong() * step
        return origin.plusMinutes(snapped)
    }
}

/** One tick on the time axis. */
data class Tick(
    val time: LocalDateTime,
    val label: String,
    val isMajor: Boolean,
)

/**
 * Hard cap so a pathological zoom-out can never generate an unbounded tick list.
 * At the widest sensible viewport this is far more ticks than can be drawn.
 */
private const val MAX_TICKS = 600

/**
 * Ticks covering [from]..[to], starting at the boundary at or before [from] so the
 * left-most partially visible tick still gets drawn.
 */
fun TimeScale.ticksIn(from: LocalDateTime, to: LocalDateTime): List<Tick> {
    val ticks = ArrayList<Tick>(64)
    var cursor = floorToTick(from)
    while (cursor <= to && ticks.size < MAX_TICKS) {
        ticks += Tick(cursor, tickLabel(cursor), isMajorTick(cursor))
        cursor = nextTick(cursor)
    }
    return ticks
}
