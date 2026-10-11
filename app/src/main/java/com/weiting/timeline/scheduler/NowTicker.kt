package com.weiting.timeline.scheduler

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Milliseconds from [now] until the next whole minute. Always in `1..60_000`.
 *
 * Ticking on the minute boundary rather than every 60 000 ms keeps the marker honest
 * against the wall clock: a fixed interval would drift by however far into a minute the
 * first tick happened to fall, and stay there.
 *
 * Takes its input so it can be tested; call it with no argument in production.
 */
internal fun millisUntilNextMinute(now: LocalTime = LocalTime.now()): Long {
    val elapsed = now.second * 1_000L + now.nano / 1_000_000L
    return 60_000L - elapsed
}

/**
 * The current minute, as state.
 *
 * The "now" marker used to call `LocalDateTime.now()` inside the draw lambdas, which meant
 * it was sampled only when something else invalidated the draw — leave the app open and
 * still and the red line silently showed the time of the last scroll. This read *is* in
 * the composition phase, and belongs there: it changes once a minute, so it costs one
 * recomposition a minute, and deferring it to draw is precisely what caused the bug.
 *
 * The coroutine is tied to the composition, so it stops when the scheduler leaves the
 * screen and resumes on return: correct whenever visible, idle when not.
 */
@Composable
fun rememberNow(): State<LocalDateTime> = produceState(LocalDateTime.now()) {
    while (true) {
        delay(millisUntilNextMinute())
        value = LocalDateTime.now()
    }
}
