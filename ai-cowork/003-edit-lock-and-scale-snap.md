# Spec 003 — Edit lock, scale-derived snapping, centred on now

- **Status:** draft
- **Date:** 2026-10-10
- **Baseline:** `v0.2.0`
- **Scope:** `app/src/main/java/com/weiting/timeline/scheduler/`

---

## Description

Fix how horizontal panning and bar editing interact, and make the snap grid follow the
scale instead of standing apart from it. Four changes:

1. **Snapping follows the scale.** DAY snaps to 15 minutes, WEEK and MONTH to a day, YEAR
   to a week. Changing the scale changes the grid, with no separate setting to keep in
   sync.
2. **A touch on a bar is an edit from press to release.** For the whole duration of that
   touch, nothing else may move the timeline: no panning, no two-finger period switch, no
   vertical scrolling. Only the bar's position and length change. Releasing ends it.
3. **The timeline scrolls to make room while editing.** With panning locked, dragging a bar
   to the viewport edge would otherwise be a dead end, so holding near an edge scrolls the
   axis and keeps the bar under the finger.
4. **"Now" sits in the middle of the screen** on launch and whenever "今天" is pressed,
   instead of near the left edge.

Not in scope: persistent selection (an edit lasts exactly one touch), vertical reordering,
and render-path performance. (A spec for the latter was drafted and withdrawn; its audit is
in commit `2e0304b` if it is ever wanted. Number 002 stays retired.)

---

## Current Logic

Verified against `v0.2.0`.

### Snapping is a standalone setting

`model/TimelineConfig.kt:18` — `snapInterval: Duration = Duration.ofMinutes(15)`, with no
relationship to `scale`. Read at `SchedulerState.kt:162` and `:203`, set at `:75`,
surfaced as chips at `ui/SchedulerControls.kt:137`.

So at the YEAR scale, where one tick is a month, a drag still snaps to 15 minutes — a grid
roughly three thousand times finer than anything visible. The setting is configurable, but
it is configurable *independently of the thing that gives it meaning*.

### The lock only covers part of the touch

Two separate `pointerInput` blocks sit on each bar:

- `ui/TimelineContent.kt:191-197` — observation only. `awaitFirstDown` records which zone
  the finger landed on, `waitForUpOrCancellation()` clears it. It consumes nothing.
- `ui/TimelineContent.kt:199-216` — `detectHorizontalDragGestures`, which consumes the
  change at `:211`.

Consumption is what makes the ancestor `Modifier.scrollable` stand down, and consumption
only starts once the horizontal touch slop is exceeded. So the window between press and
slop is unclaimed, and a vertical-dominant drag is never claimed at all — it falls through
to `verticalScroll` by design.

There is a visible symptom of the two blocks not sharing a lifetime:
`waitForUpOrCancellation()` returns as soon as the drag detector consumes, so `pressedZone`
goes null at the handover. `ui/TimelineContent.kt:152` papers over it with
`draft?.mode ?: pressedZone`. That workaround is a hint that these should be one gesture,
not two.

`SchedulerScreen.kt:87` suppresses the two-finger swipe on `state.draft == null` — again,
`draft` only exists after slop, so a press that has not yet become a drag does not suppress
it.

### Nothing knows how wide the viewport is

`SchedulerState` has no notion of viewport width. `scrollByPx`
(`SchedulerState.kt:214`) takes a delta and `animateViewportTo` (`:221`) takes a target
instant; neither needs a width. Both of the remaining changes do:

- Centring on now needs half a viewport of lead-in.
- Edge auto-scroll needs to know where the edges are.

### "Now" is parked near the left edge

`model/TimeScale.kt:64` — `todayStart(now)` returns a fixed lead-in before now (one hour
at DAY, a day at WEEK, three days at MONTH, a month at YEAR), floored to a tick. Used by
the launch viewport (`SchedulerState.kt:59`) and by `goToToday()` (`:234`).

It was chosen because it needs no width. It cannot centre.

### The origin is today's midnight

`SchedulerState.kt:247` — `origin = LocalDate.now().atStartOfDay()`. Snapping is anchored
to it (`TimeAxis.snapToGrid`), which is an architecture commitment.

**This breaks the YEAR case.** A week-sized grid anchored to today's midnight puts grid
lines every seven days *from today*, so they land on whatever weekday today happens to be,
not on Mondays. The 15-minute, daily and weekly grids would not agree with each other
either.

---

## Acceptance Criteria

**Scale-derived snapping**

- [ ] At DAY, a drag lands on a 15-minute boundary; at WEEK and MONTH, on midnight; at
      YEAR, on a Monday midnight.
- [ ] Switching the scale mid-session changes the grid for the *next* gesture, with no
      restart and nothing else to set.
- [ ] Snap is still configurable: an explicit override replaces the derived value, and the
      control bar can select either the automatic behaviour or a fixed interval.
- [ ] `minTaskDuration` stays independent of the snap interval; switching to YEAR does not
      inflate a 15-minute task.

**The edit lock**

- [ ] Pressing a bar begins the edit immediately, before any movement, and lights the zone.
- [ ] Movement under the touch-slop buffer changes nothing; the first movement past it
      moves the bar by the full travel, not by the travel minus the slop.
- [ ] A horizontal drag that starts on a bar never pans the axis, at any point in the
      gesture.
- [ ] For the whole press, a two-finger swipe cannot change period.
- [ ] A vertical drag that starts on a bar scrolls the lanes and leaves the task
      untouched — vertical is ignored by the bar, not blocked.
- [ ] Once an axis is committed, the rest of the gesture stays on it: a diagonal drag does
      not flip between editing and scrolling.
- [ ] Releasing ends the edit, and panning works again on the next touch.
- [ ] A press with no movement, released, changes no task and leaves no state behind.
- [ ] Pressing empty space still pans exactly as it does today.
- [ ] The zone highlight stays lit for the whole press, including across the
      press-to-drag handover, without the `draft?.mode ?: pressedZone` workaround.

**Edge auto-scroll**

- [ ] Holding a dragged bar within the edge threshold scrolls the axis.
- [ ] The bar stays under the finger while that happens — its time changes, its screen
      position does not.
- [ ] Scrolling stops at the axis clamp (`origin ± 2 years`) without the bar detaching
      from the finger.
- [ ] Auto-scroll stops on release and does not continue.
- [ ] Moving back out of the threshold stops it.
- [ ] It applies to all three gestures, not only Move.

**Centred on now**

- [ ] On launch, the red marker is horizontally centred in the timeline area.
- [ ] "今天" centres it, at every scale and zoom.
- [ ] It is still centred after a rotation, and after a zoom change followed by "今天".
- [ ] Near the axis clamp, centring degrades to the closest reachable position rather than
      leaving the marker off-screen.

---

## Expected Code Change

### 1. Snap derived from the scale

```kotlin
// TimeScale — the editing granularity that matches each scale's visual grain
val defaultSnapInterval: Duration get() = when (this) {
    DAY -> Duration.ofMinutes(15)
    WEEK, MONTH -> Duration.ofDays(1)
    YEAR -> Duration.ofDays(7)
}

// TimelineConfig — derived by default, overridable
val snapOverride: Duration? = null
val snapInterval: Duration get() = snapOverride ?: scale.defaultSnapInterval
```

Keeping `snapOverride` is deliberate: the brief lists snap interval as something that must
be configurable, and a value derived from the scale with no way to set it would be a step
back from that. The default is the derived behaviour, so the dynamic conversion is what
happens unless someone asks otherwise. The chip row gains an "自動" entry, selected when
`snapOverride == null`.

Call sites read `config.snapInterval` unchanged, so `SchedulerState.kt:162` and `:203` need
no edit.

### 2. Move the origin to this week's Monday

```kotlin
origin = LocalDate.now()
    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    .atStartOfDay()
```

15 minutes, one day and seven days all divide evenly into a grid anchored at Monday
midnight, so every derived granularity lines up — with the tick lines and with each other.
This keeps snapping anchored to `origin`, so the architecture commitment stands; only the
definition of `origin` changes, from "today's midnight" to "this week's Monday".

The KDoc on `origin` currently says "today sits at exactly 0" and must be corrected.

### 3. One gesture per touch, with the axis deciding the owner

Replace the two `pointerInput` blocks (`ui/TimelineContent.kt:191-216`) with one
`awaitEachGesture` that owns the touch from press, and that decides at the slop boundary
whether the bar or the lane scroller gets it:

```kotlin
awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    state.beginEdit(task.id, hitTestBar(down.position.x, barWidthPx, handlePx))
    try {
        // Buffer: accumulate until one axis crosses the platform touch slop, then commit
        // to that axis for the rest of the gesture.
        var dx = 0f
        var dy = 0f
        var owner: Axis? = null
        while (owner == null) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture   // a tap: nothing to commit
            dx += change.positionChange().x
            dy += change.positionChange().y
            owner = when {
                abs(dx) >= viewConfiguration.touchSlop -> Axis.Horizontal
                abs(dy) >= viewConfiguration.touchSlop -> Axis.Vertical
                else -> null
            }
        }
        // Vertical is meaningless to a bar, so hand the gesture to the lane scroller by
        // returning without ever consuming.
        if (owner == Axis.Vertical) return@awaitEachGesture

        state.beginDrag(task.id, state.editingZone!!)
        // Seed with the travel already spent crossing the slop, so the bar does not lag
        // by one slop distance behind the finger.
        state.dragBy(dx, pxPerMinute)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()           // claims the gesture; ancestors stand down
            if (!change.pressed) break
            state.dragBy(change.positionChange().x, pxPerMinute)
        }
        state.commitDrag()
    } finally {
        state.endEdit()
    }
}
```

Why the axis decides rather than consuming the down outright, which was this spec's first
draft: consuming at press would also close off vertical lane scrolling, because the
ancestor `verticalScroll` checks for consumption too. With six sample tasks that is
invisible, but as soon as the task list outgrows the screen a user whose finger happens to
land on a bar would find the list stuck. "Only horizontal movement is meaningful" is
satisfied exactly by ignoring vertical, not by blocking it.

Panning still cannot win from a touch that starts on a bar: the bar and the ancestor
`scrollable` wait on the same slop, and pointer events reach the child first in the main
pass, so the bar consumes first on any horizontal gesture.

`beginEdit` / `endEdit` bracket the whole touch, which is what the two-finger swipe needs
(`SchedulerScreen.kt:87` becomes `state.editingTaskId == null`). It runs on
`PointerEventPass.Initial` and therefore sees events before this block consumes anything,
so it cannot rely on consumption.

The buffer is the platform `viewConfiguration.touchSlop` rather than a hand-picked
constant, so a bar behaves like every other draggable thing on the device. The travel
spent crossing it is handed to the first `dragBy` so the bar does not start one slop
distance behind the finger.

`editingZone` replaces `pressedZone`, so `ui/TimelineContent.kt:152`'s
`draft?.mode ?: pressedZone` workaround goes away — that workaround existed only because
the press and drag blocks did not share a lifetime.

### 4. Viewport width in the state

```kotlin
var viewportWidthPx: Float by mutableFloatStateOf(0f)
    private set
fun onViewportWidthChanged(px: Float) { viewportWidthPx = px }
```

Fed from `Modifier.onSizeChanged` on the timeline content in `SchedulerScreen`. Both of the
remaining changes read it.

### 5. Edge auto-scroll while editing

```kotlin
// in SchedulerState
suspend fun autoScrollWhileEditing(pxPerMinute: Float) { ... }
```

A coroutine started on edit begin and cancelled on edit end. Each frame, if the dragged
edge sits within `EdgeAutoScrollThreshold = 48.dp` of a viewport edge, it scrolls by a
velocity ramped from how deep into the threshold the finger is, and advances the draft by
the same amount so the bar stays under the finger.

The threshold is in dp, so it is a constant *distance* and a varying *duration*: 48dp is
about 45 minutes at DAY/zoom 1 and about three weeks at YEAR/min zoom. That is the right
trade — the threshold exists to describe a physical reach near the screen edge, and a
fraction-of-viewport threshold would instead vary with screen size, which is the dimension
a finger does not scale with.

The sign relationship is the part to get right and to test: scrolling the viewport forward
by *n* minutes must advance the draft by *n* minutes, or the bar will crawl away from the
finger. Clamping at `origin ± 2 years` must stop both together, not just the scroll.

Ramped rather than constant velocity because a constant rate either feels sluggish at the
threshold edge or uncontrollable deep inside it.

### 6. Centre on now

```kotlin
// TimeScale.todayStart is replaced by a width-aware target
private fun centredOn(time: LocalDateTime, pxPerMinute: Float): Double {
    val halfViewportMinutes = (viewportWidthPx / pxPerMinute / 2.0)
    return clampedMinutesOf(time) - halfViewportMinutes
}
```

`viewportWidthPx` is the width of the **timeline area**, not the window: the 108dp label
column is excluded, so the marker lands in the middle of the part of the screen that
represents time. It is therefore not centred on the window.

`goToToday()` animates to that. Launch centring cannot run in `init` because the width is
not known yet, so it waits for the first non-zero width — a one-shot `LaunchedEffect` keyed
on "width became known", not an `init` assignment.

`TimeScale.todayStart` (`model/TimeScale.kt:64`) loses its only callers. Delete it rather
than leave it; `windowStart` stays for the period label.

Note the clamp interaction: at the far edge of the scrollable range, centring is
unreachable, and the criterion above says to degrade to the nearest reachable position
rather than silently leave the marker off-screen.

### 7. Tests

On the JVM, against `SchedulerState` and the pure helpers:

- Each scale's derived snap interval, and that an override replaces it.
- A drag at YEAR lands on a Monday midnight; at WEEK and MONTH on a midnight; at DAY on a
  quarter hour. These are the cases that would fail silently if the origin were left at
  today's midnight, so they are the regression guard for change 2.
- `minTaskDuration` still unaffected by the scale.
- `beginEdit` / `endEdit` bracket correctly, including the press-with-no-movement path
  leaving `tasks` untouched and `editingTaskId` null.
- Auto-scroll advances the draft and the viewport by equal amounts, and both stop together
  at the clamp.
- Centring puts `now` at the viewport midpoint for a given width and `pxPerMinute`, and
  degrades at the clamp.

Preview additions: a bar mid-edit at each scale, so the derived grid is visible.

---

## Decisions

Confirmed in discussion, 2026-10-10.

1. **A buffer before movement registers — yes.** The gesture waits for the platform
   `viewConfiguration.touchSlop` before anything moves, rather than reacting to the first
   pixel. Using the platform value rather than a chosen constant keeps a bar consistent
   with every other draggable surface on the device. The travel spent crossing the buffer
   is not discarded, so the bar does not start a slop behind the finger.

2. **Vertical on a bar is ignored, not blocked.** The axis that first crosses the buffer
   owns the rest of the gesture. Horizontal goes to the bar and locks panning; vertical is
   handed to the lane scroller by never consuming. This replaces the first draft's
   "consume the down", which would have blocked lane scrolling for any touch that happened
   to land on a bar.

3. **The edge threshold is in dp** — `48.dp` — accepting that it means a different amount
   of *time* at each scale. See change 5 for why that is the right way round.

4. **"Centre" means the timeline area**, excluding the label column.

5. **The snap chips stay**, with an "自動" entry selected whenever `snapOverride == null`.
   The brief lists snap interval as a required configurable, and being able to show it met
   is worth the control row.

6. **Changing the scale does not re-snap existing tasks.** Bars keep whatever grid they
   were last edited on. Re-snapping would look tidier and would rewrite data the user
   never asked to change.

7. **Edit begins at touch-down, with no time threshold.** "長按" in the original request
   meant press-and-keep-holding-while-dragging, not a 500ms long-press gate. One hold
   carries all three edits — extend, shrink, move — and releasing ends it. So no
   `awaitLongPressOrCancellation`, and the only thing standing between touch-down and
   movement is the buffer in decision 1.

### Cannot be settled without hardware

- Whether child-first pointer dispatch really does let the bar win a horizontal gesture
  over the ancestor `scrollable`. The reasoning is sound and unchanged from spec 001, but
  it has never been confirmed on a device.
- Whether the platform touch slop feels right as the buffer, or wants to be larger
  specifically for bars.
- The auto-scroll velocity ramp, which is pure feel.
