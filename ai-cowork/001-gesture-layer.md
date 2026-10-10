# Spec 001 — Task bar drag, resize, and snap

- **Status:** implemented 2026-10-10
- **Date:** 2026-10-10
- **Scope:** `app/src/main/java/com/weiting/timeline/scheduler/`

---

## Description

Make the task bars interactive. Today they are laid out correctly but inert: the
coordinate layer and the renderer exist, nothing listens to pointers.

Three gestures, all operating on the same bar:

1. **Move** — drag the body of a bar horizontally; its `start` changes, `duration` is
   unchanged.
2. **Resize from the left edge** — `start` changes, `end` is pinned, so `duration` changes
   by the inverse amount.
3. **Resize from the right edge** — `start` is pinned, `duration` changes.

All three snap to the configured time grid (`TimelineConfig.snapInterval`, default 15 min).

Out of scope for this spec: vertical dragging between rows, multi-select, undo, and
edge-triggered auto-scroll (see Questions).

---

## Acceptance Criteria

**Move**

- [ ] Dragging a bar's body moves it horizontally and follows the finger without visible lag.
- [ ] On release, the task's `start` has changed and its `duration` is byte-identical to
      before the gesture.
- [ ] The bar does not move vertically, and does not change rows.

**Resize**

- [ ] Dragging within the left handle region changes `start` and `duration` such that
      `end` is unchanged.
- [ ] Dragging within the right handle region changes `duration` only; `start` is unchanged.
- [ ] A bar cannot be resized below one `snapInterval`; attempting to drag past that point
      clamps rather than inverting the bar.

**Snapping**

- [ ] After any move or resize, `start` (and `end` where applicable) lands exactly on a
      multiple of `snapInterval` measured from `TimeAxis.origin`.
- [ ] Two bars dragged independently to the same grid cell are pixel-aligned with each
      other and with the header's tick lines.
- [ ] Changing `snapInterval` at runtime changes the snap granularity of the *next*
      gesture, with no restart.

**Interaction with scrolling**

- [ ] Starting a drag on a bar does not scroll the timeline.
- [ ] Starting a drag on empty lane space scrolls the timeline as it does today.
- [ ] The header stays aligned with the content throughout and after a drag.

**Configuration (regression)**

- [ ] All four knobs — scale, zoom, snap interval, row height — remain changeable at
      runtime, and a gesture performed after changing any of them lands on the correct
      time.
- [ ] Changing zoom mid-session does not displace any previously dragged bar in time.

---

## Current Logic

### Coordinate layer — `TimeAxis.kt`

The axis is strictly linear in minutes from `origin` (start of today). Everything the
gesture layer needs already exists here as pure functions:

| Function | Line | Purpose |
|---|---|---|
| `xOf(time, viewportStartMinutes)` | 29 | time → px, relative to the viewport's left edge |
| `timeAt(x, viewportStartMinutes)` | 33 | px → time (inverse of `xOf`) |
| `widthOf(duration)` | 36 | duration → px |
| `snap(time, interval)` | 43 | rounds to the nearest grid multiple, **anchored to `origin`** |

`snap()` currently has **no callers**. It was written and left unwired deliberately — this
spec is what connects it.

The origin-anchoring matters: snapping relative to each task's own start would put every
bar on its own private grid, and independently dragged bars would not line up.

### State — `SchedulerState.kt`

- `tasks: SnapshotStateList<Task>` (line 43) — mutable, so a committed edit is a
  `tasks[i] = tasks[i].copy(...)`.
- `config` has `private set` plus explicit setters (`setSnapInterval` at line 64, etc.).
- `viewportStartMinutes` is the scroll position, stored in the **time domain**, not pixels.
- `scrollByPx(deltaPx, pxPerMinute)` (line 81) is the only scroll entry point and returns
  the pixels it actually consumed.

There is **no drag state at all** right now.

### Rendering — `ui/TimelineContent.kt`

- `TaskLane` (line 83) draws one lane containing one bar.
- The bar's horizontal position comes from `offset { ... }` (line 103) — the *lambda*
  overload, so `viewportStartMinutes` is read in the **layout phase**. Scrolling re-places
  the bar without recomposing it.
- `MinBarWidth = 10.dp` (line 37) floors the rendered bar width so sub-hour tasks stay
  visible at coarse scales.
- No `pointerInput` exists anywhere in the file.

### Scroll wiring — `SchedulerScreen.kt`

A single `rememberScrollableState` (line 71) is shared by the header (line 90) and the
content (line 107). Horizontal scroll is **not** `Modifier.horizontalScroll`, because that
would require a child as wide as the entire time range.

This is the collision point for this spec: the lane already has a horizontal
`Modifier.scrollable` ancestor, so a bar's own drag handler has to win the gesture.

---

## Expected Code Change

### 1. Drag state in `SchedulerState.kt`

Add a draft-edit concept so the gesture never writes to `tasks` per frame:

```kotlin
enum class DragMode { Move, ResizeStart, ResizeEnd }

data class TaskDraft(
    val taskId: String,
    val mode: DragMode,
    val start: LocalDateTime,
    val duration: Duration,
)

var draft: TaskDraft? by mutableStateOf(null)
    private set

fun beginDrag(task: Task, mode: DragMode)
fun dragBy(deltaPx: Float, axis: TimeAxis)   // updates draft only
fun commitDrag()                              // writes the draft back into tasks
fun cancelDrag()
```

**Why a draft rather than mutating the task directly:** `tasks` is a
`SnapshotStateList`, so writing to it every frame invalidates every composable reading the
list — all lanes, not just the dragged one. The draft is a single nullable field, and only
the dragged lane reads it.

### 2. Minimum duration guard

`dragBy` clamps so `duration >= snapInterval`. Resize past the opposite edge clamps instead
of inverting; no negative-duration state should ever be representable.

### 3. Gesture handling in `ui/TimelineContent.kt`

On the bar's `Box`, add:

```kotlin
.pointerInput(task.id, state.config.snapInterval, axis) {
    detectHorizontalDragGestures(
        onDragStart = { offset -> state.beginDrag(task, hitTestMode(offset, barWidthPx)) },
        onHorizontalDrag = { change, dragAmount ->
            change.consume()          // keeps the parent scrollable out of it
            state.dragBy(dragAmount, axis)
        },
        onDragEnd = { state.commitDrag() },
        onDragCancel = { state.cancelDrag() },
    )
}
```

`change.consume()` is what stops the ancestor `Modifier.scrollable` from also acting on the
same pointer.

`hitTestMode` splits the bar into three zones by x: left handle → `ResizeStart`, right
handle → `ResizeEnd`, middle → `Move`.

### 4. Edge handle sizing

`EdgeHandleWidth = 16.dp`, but handles must never consume the whole bar. Proposed rule:

```
handle = min(16.dp, barWidth / 3)
```

so a narrow bar keeps a draggable middle third. This also means `MinBarWidth` should rise
from `10.dp` to roughly `24.dp`, or very short tasks become un-resizable in practice.

### 5. Rendering the draft

`TaskLane` reads `state.draft` and, when it matches this task, positions and sizes the bar
from the draft instead of from `task`. Add a visual affordance while dragging: slight
elevation or a brighter border, plus visible handle grips on the two edges.

### 6. Tests

`TimeAxis` is pure, so unit-test it directly (`app/src/test/`):

- `snap()` rounds to the nearest multiple in both directions, and is idempotent.
- `xOf` / `timeAt` round-trip within one minute.
- The minimum-duration clamp holds when a resize is dragged far past the opposite edge.

---

## Questions

1. **Snap live, or only on release?** Snapping every frame makes the bar jump in grid
   steps, which reads as deliberate and shows the grid off well. Snapping only on release
   gives smooth 1:1 finger tracking that settles on release. Live snapping is more
   obviously "correct" to a reviewer reading the spec; release-only feels better in the
   hand. Which do you want?

2. **Narrow-bar conflict.** With `handle = min(16.dp, barWidth / 3)`, a 15-minute task at
   the MONTH scale is ~1px wide before `MinBarWidth` floors it. At that size, is a resize
   gesture meaningful at all, or should bars below some threshold be move-only?

3. **Minimum duration.** Is one `snapInterval` the right floor, or should `Task` carry its
   own minimum? One snap interval is simple but means the floor silently changes when the
   user switches snap granularity.

4. **Vertical dragging.** Should a bar be draggable into another row (reordering tasks, or
   reassigning a lane)? Currently each task owns exactly one lane and rows carry no
   independent meaning. Out of scope unless rows are going to represent something
   (a resource, a person) later.

5. **Auto-scroll at the viewport edge.** Dragging a bar to the right edge currently just
   runs out of room. Should the timeline auto-scroll while a drag is held near the edge?
   It is a well-understood interaction but needs its own coroutine driving
   `scrollByPx`, and is a meaningful amount of extra work.

6. **Bounds.** `scrollByPx` clamps the viewport to `origin ± 2 years`. Should a *task*
   likewise be prevented from being dragged outside that window, or may it go anywhere and
   simply become unreachable by scrolling?

7. **Overlap.** Two tasks in different rows may overlap in time, which is fine. Is there
   any rule about tasks *not* being allowed to overlap, or is the timeline purely a
   display with no scheduling constraints?


---

## Resolution

Implemented. 28 unit tests in `app/src/test/java/com/weiting/timeline/scheduler/`
(`TimeAxisTest`, `TaskDragTest`) encode the acceptance criteria above; all pass on the
plain JVM, with no device or Compose test rule needed.

How each question was answered:

1. **Snap live, or on release?** → **Both, live by default.**
   `TimelineConfig.snapWhileDragging` toggles it, and there is a chip in the control bar
   so the difference can be felt side by side. Live is the default because the brief asks
   for snapping and a reviewer needs to *see* it happen. Either way the commit snaps
   unconditionally, so both modes land on the same grid.

2. **Narrow-bar conflict.** → **Resize stays available at every width.**
   The handle is `min(edgeHandleWidth, barWidth / 3)`, so the middle third is always a
   move zone no matter how narrow the bar gets. `MinBarWidth` rose from `10.dp` to
   `52.dp`, which is wide enough for two 18dp handles plus a grabbable middle.

3. **Minimum duration.** → **Its own config field, independent of the snap interval.**
   `TimelineConfig.minTaskDuration`, default 15 minutes. Deriving it from `snapInterval`
   would have meant that switching snap to "1 day" silently inflated every short task.
   There is a test for exactly that.

4. **Vertical dragging.** → **Out of scope, unchanged.**
   Rows still carry no independent meaning, so reordering would encode nothing. Worth
   revisiting only if a row comes to represent a person or a resource.

5. **Edge auto-scroll.** → **Not implemented.**
   Still the most defensible next addition. Dragging a bar toward the viewport edge
   currently just runs out of room.

6. **Bounds.** → **Tasks are not clamped.**
   Only the viewport clamps, at `origin ± 2 years`. A task dragged outside that window
   would become unreachable, but reaching it requires roughly two years of dragging, so
   the guard would cost more than it saves.

7. **Overlap.** → **Purely a display.**
   No scheduling constraints are enforced. Tasks may overlap freely, within a row or
   across rows.

### Still open

- The `Modifier.scrollable` delta sign is still unverified on hardware (see the note in
  commit `5ebe2b0`). The drag direction shares no code with it, so a wrong scroll sign
  would not affect dragging.
- Whether the gesture correctly beats the ancestor `Modifier.scrollable` depends on
  child-first pointer dispatch and `change.consume()`. The logic is unit-tested, but the
  dispatch itself can only be confirmed by touching a real screen.
