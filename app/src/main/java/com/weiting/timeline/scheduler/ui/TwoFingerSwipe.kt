package com.weiting.timeline.scheduler.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Fires [onSwipe] once per gesture when two or more fingers travel [threshold] horizontally.
 *
 * Two fingers rather than a location, because switching period and panning the timeline
 * are the same motion along the same axis. Splitting them by *where* the finger lands
 * would put two outcomes of wildly different magnitude on either side of an invisible
 * boundary a few dp from a task bar; the finger count is an unambiguous declaration of
 * intent that costs no screen real estate.
 *
 * Runs on [PointerEventPass.Initial], so it sees the event before the bars and before the
 * ancestor `Modifier.scrollable` and can claim the gesture outright. A single finger is
 * never touched, which leaves one-finger panning and bar editing exactly as they were.
 *
 * @param direction passed to [onSwipe]: `+1` for a leftward swipe (forward in time),
 *   `-1` for a rightward swipe.
 */
fun Modifier.twoFingerHorizontalSwipe(
    threshold: Dp = 56.dp,
    enabled: () -> Boolean = { true },
    onSwipe: (direction: Int) -> Unit,
): Modifier = pointerInput(threshold, onSwipe) {
    val thresholdPx = threshold.toPx()
    awaitEachGesture {
        // requireUnconsumed = false: we are only observing until a second finger arrives.
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var travelX = 0f
        var fired = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            if (pressed.size >= 2 && enabled()) {
                // Average the pointers' movement rather than tracking one of them, so a
                // pinch (fingers moving oppositely) nets out to roughly zero pan.
                val pan = pressed.fold(Offset.Zero) { acc, change ->
                    acc + (change.position - change.previousPosition)
                } / pressed.size.toFloat()
                travelX += pan.x

                // Claim every change so neither the bars nor the scroller also acts.
                event.changes.forEach { it.consume() }

                if (!fired && abs(travelX) >= thresholdPx) {
                    fired = true
                    onSwipe(if (travelX < 0f) 1 else -1)
                }
            }
        }
    }
}
