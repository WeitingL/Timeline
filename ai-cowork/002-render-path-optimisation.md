# Spec 002 — Render-path optimisation

- **Status:** draft
- **Date:** 2026-10-10
- **Baseline:** `v0.2.0`
- **Scope:** `app/src/main/java/com/weiting/timeline/scheduler/`

---

## Description

The scaffold already got the big structural win: scrolling touches neither composition
nor layout for the header and the grid, because both read the viewport inside draw
lambdas (spec 001 and the pages under `docs/` cover why). What is left is a different
class of cost — **work that is repeated every frame although its result only changes when
the viewport crosses a tick boundary** — plus one correctness bug found while auditing for
it.

This kind of waste does not show up as recomposition counts. It shows up as allocation
rate and, under memory pressure, as GC-induced jank. So the headline metric for this spec
is bytes allocated per second of continuous scrolling, not recomposition counts.

**Explicit non-goal: do not change anything before there is a baseline.** The audit below
says where the waste is, not that the app is currently janky — on the sample data it very
probably is not. Optimising without a measurement is how a codebase gets more complicated
and no faster.

---

## Measure first

Before any edit, capture a baseline on a real device (not the emulator — its frame pacing
is not representative):

1. **Recomposition counts.** Android Studio's Layout Inspector, recomposition column, over
   a 10-second continuous one-finger scroll. Expectation from the current design: zero for
   the lanes and the header, a handful for `SchedulerControls`. **If the lanes are
   recomposing, stop and fix that first** — it would mean a state read has leaked into
   composition and nothing else in this spec matters by comparison.
2. **Allocation rate.** Android Studio profiler, Java/Kotlin allocations, same 10-second
   scroll. This is the number this spec is about.
3. **Frame timing.** `adb shell dumpsys gfxinfo com.weiting.timeline framestats`, with
   `--reset` before each run. Record the count of frames over the device's refresh budget.
4. Repeat 2 and 3 at the two worst cases found in the audit: `MONTH` scale at
   `MIN_ZOOM` (most ticks on screen) and a sustained bar drag (the only path that
   recomposes per frame by design).

Record all four numbers in this document before touching code, and again after. A change
that does not move a number gets reverted.

---

## Current Logic

Every line reference below was checked against `v0.2.0`.

### Repeated per frame, changes only on a boundary crossing

| Where | Line | What happens every frame |
|---|---|---|
| `ui/TimelineHeader.kt` | 51–65 | `Canvas` draw lambda calls `scale.ticksIn(...)` |
| `ui/TimelineContent.kt` | 94–100 | `drawBehind` calls `scale.ticksIn(...)` **again**, for the same window the header just built |
| `TimeAxis.kt` | 78 | each call allocates an `ArrayList<Tick>`, plus one `Tick` and one formatted `String` per element |
| `ui/TimelineHeader.kt` | 105 | `measurer.measure(AnnotatedString(text), style)` per labelled tick (the measurer caches the layout, but the `AnnotatedString` is new each time) |
| `TimeAxis.kt` | 23–24 | `minutesOf` allocates a `Duration` per call — called once per tick per frame in both canvases, and once per bar per layout pass via `xOf` |

### Correctness, not just cost

| Where | Line | Problem |
|---|---|---|
| `ui/TimelineHeader.kt` | 114 | `LocalDateTime.now()` is read inside the draw lambda |
| `ui/TimelineContent.kt` | 336 | same |

The "now" marker therefore only moves when something *else* invalidates the draw. Leave
the app open and still, and the red line silently goes stale — it is showing the time of
the last scroll, not the current time. It also allocates two `LocalDateTime` objects per
frame for a value that changes once a minute.

### Smaller, still pure waste

| Where | Line | What |
|---|---|---|
| `ui/TimelineContent.kt` | 309 | two formatted strings per lane per recomposition — for the dragged lane that is once per frame |
| `ui/SchedulerControls.kt` | 58, 67 | the `derivedStateOf` bodies format strings. `derivedStateOf` suppresses *recomposition* when the result is unchanged; it does not suppress the *computation*, which still runs on every read after invalidation, i.e. every frame of a scroll |
| `ui/TwoFingerSwipe.kt` | 43, 49 | `event.changes.filter { }` allocates a list per pointer event, and `fold` allocates an `Offset` per pointer |
| `SchedulerState.kt` | 243 | `tasks: List<Task> = sampleTasks()` is a default argument, so six `Task` objects and a list are allocated on **every** recomposition of `rememberSchedulerState` and then discarded, because `remember` ignores them after the first |

### How much is this, honestly

Visible tick counts are small. On a 411dp phone the timeline is about 303dp wide after the
108dp label column, so:

- `DAY` at zoom 1 — 64dp per tick — about **5** ticks on screen
- `MONTH` at `MIN_ZOOM` — 16dp per tick — about **19** ticks, the worst realistic case
- `YEAR` at `MIN_ZOOM` — 32dp per tick — about **10**

So the tick path is on the order of **100–150 short-lived allocations per frame** across
both canvases, not thousands. Worth removing because it is pure waste on a path that runs
at the display refresh rate; *not* evidence that the app is slow today. `MAX_TICKS = 600`
in `TimeAxis.kt:71` never triggers at any reachable zoom on any plausible screen — it is
dead code that exists as a guard.

---

## Acceptance Criteria

**Must not regress**

- [ ] Recomposition count for the lanes and the header during a scroll stays at zero.
- [ ] All 31 existing unit tests still pass, unchanged.
- [ ] Every preview in `ui/SchedulerPreviews.kt` still renders.
- [ ] Header ticks stay pixel-aligned with the content grid at every scale and zoom.

**Allocation**

- [ ] A sustained scroll allocates no `Tick`, no tick list, and no tick label `String`
      while the visible tick window is unchanged.
- [ ] `xOf` allocates nothing.
- [ ] `rememberSchedulerState` allocates no sample data after the first composition.
- [ ] A sustained two-finger swipe allocates no list per pointer event.
- [ ] Measured allocation rate during the 10-second `MONTH`/`MIN_ZOOM` scroll drops by at
      least half against the baseline. If it does not, the change was not worth making and
      is reverted.

**The now marker**

- [ ] With the app open and untouched, the marker advances on its own.
- [ ] It updates about once a minute, not once per frame.
- [ ] No `LocalDateTime.now()` call remains inside any draw lambda.

---

## Expected Code Change

### 1. A tick cache shared by the header and the grid

The result of `ticksIn` depends only on `(scale, firstVisibleTick, lastVisibleTick)`.
Within one tick cell of scrolling it is identical. Cache it, and let both canvases read
the same cache so the work happens once rather than twice.

Two ways to do that, and they trade off against each other — see the first question below.

**Option A — a mutable cache object, read inside the draw lambda:**

```kotlin
class TickCache {
    private var key: Triple<TimeScale, LocalDateTime, LocalDateTime>? = null
    private var ticks: List<Tick> = emptyList()

    fun get(scale: TimeScale, from: LocalDateTime, to: LocalDateTime): List<Tick> {
        val k = Triple(scale, scale.floorToTick(from), scale.floorToTick(to))
        if (k != key) { key = k; ticks = scale.ticksIn(from, to) }
        return ticks
    }
}
```

Held in `remember { TickCache() }`, hoisted to `SchedulerScreen` and passed to both
canvases. Zero recomposition, zero per-frame allocation once warm. The cost is that the
draw phase now mutates something, which is not how Compose wants draw to behave even
though a non-snapshot cache is safe there.

**Option B — a two-stage derived state, read during composition:**

```kotlin
// cheap and allocation-free: which tick cell the left edge sits in
val tickIndex by remember(scale, axis) {
    derivedStateOf { floor(state.viewportStartMinutes / scale.nominalTickMinutes).toInt() }
}
// rebuilt only when that index changes
val ticks = remember(scale, axis, tickIndex, widthPx) { scale.ticksIn(...) }
```

Idiomatic, no mutation in draw. The cost is that crossing a tick boundary now causes a
recomposition of the header and the content — roughly one per 64dp of scroll at the DAY
scale — where today there are none.

### 2. Drop `Duration` from the hot path

```kotlin
// before: allocates a Duration, then discards it
fun minutesOf(time: LocalDateTime): Double =
    Duration.between(origin, time).toMinutes().toDouble()

// after: no intermediate object
fun minutesOf(time: LocalDateTime): Double =
    ChronoUnit.MINUTES.between(origin, time).toDouble()
```

Same for `snapToGrid` (`TimeAxis.kt:56`). Behaviour is identical — both truncate toward
zero on sub-minute remainders — so the existing `TimeAxisTest` cases should pass unchanged,
which is the point of having them.

### 3. Hoist `now` into state

```kotlin
val now by produceState(LocalDateTime.now()) {
    while (true) {
        delay(millisUntilNextMinute())
        value = LocalDateTime.now()
    }
}
```

Pass it into both canvases as a parameter. This fixes the staleness bug and removes the
per-frame allocation in one change. Ticking on the minute boundary rather than every
60 000 ms keeps the marker honest against the clock.

### 4. Pre-measure the tick labels

Measure each label once when the tick list is rebuilt, and cache `TextLayoutResult`
alongside the `Tick`. The draw lambda then only calls `drawText(layout, topLeft)`.

### 5. The small ones

- `TwoFingerSwipe.kt` — iterate `event.changes` with an index and accumulate into local
  floats instead of `filter` + `fold`.
- `SchedulerState.kt:243` — take `tasks: () -> List<Task> = ::sampleTasks` and invoke it
  inside `remember`, so nothing is built after the first composition.
- `SchedulerControls.kt` — stage the derived state the same way as option B above: derive a
  cheap key (the window-start instant) first, and format the string only when that key
  changes.
- `TimelineContent.kt:309` — only rebuild the label strings when the displayed minute
  changes, not on every frame of a drag.

### 6. Tests

The acceptance criteria above are mostly measurements rather than assertions, but two are
testable on the JVM:

- `TickCache` returns the identical list instance for two calls inside the same tick cell,
  and a new one across a boundary.
- `minutesOf` and `snapToGrid` produce identical results before and after the `ChronoUnit`
  change, including negative offsets (times before the origin) and sub-minute remainders —
  the case where a careless rewrite would change rounding direction.

---

## Questions

1. **Cache in draw, or derive in composition?** Option A keeps the zero-recomposition
   property that the whole scaffold was designed around, at the cost of mutating a cache
   inside a draw lambda. Option B is idiomatic Compose but reintroduces a recomposition
   every tick boundary — about one per 64dp of scroll. My instinct is A, because the
   property it protects is the one that was hard to get and easy to lose. But A is the
   kind of thing a reviewer may read as a smell, so it needs a comment that earns it.

2. **Is this worth doing at all right now?** Six tasks and about nineteen ticks is a tiny
   workload. The honest answer may be that only the `now` bug (item 3), the `Duration`
   allocation (item 2) and the `sampleTasks` default argument (item 5) are worth it, and
   that the tick cache should wait for a measurement that justifies it. I would rather fix
   three certain things than seven speculative ones.

3. **Which scale should the baseline be measured at?** `MONTH`/`MIN_ZOOM` is the worst
   case, but `DAY`/zoom 1 is what a user actually sees. Optimising for the worst case can
   mean carrying complexity nobody benefits from.

4. **Does the tick cache need to survive a scale change?** Keying on scale means switching
   day→week→day rebuilds twice. A two-entry cache would avoid that, for more complexity
   than the saving is probably worth.

5. **Do we want a frame-rate overlay in debug builds?** A small always-on counter would
   make regressions obvious during development without reaching for the profiler. It is
   also one more thing to maintain, and it is not a deliverable.

6. **How far does "no allocation" need to go?** `ChronoUnit.MINUTES.between` on
   `LocalDateTime` may still allocate internally depending on the implementation. Verifying
   that needs allocation tracking on the specific device, not an assumption — and it is
   worth checking before claiming the criterion is met.
