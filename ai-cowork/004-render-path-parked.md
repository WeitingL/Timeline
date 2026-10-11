# Spec 004 — Render-path optimisation (parked, not for implementation)

- **Status:** **parked by decision, 2026-10-11.** Written to be findable, not to be built.
- **Baseline:** working tree after spec 003
- **Scope:** `app/src/main/java/com/weiting/timeline/scheduler/`

---

## Why this exists and why it is not being built

A render-path audit was written once before, as spec 002, and withdrawn before any code
shipped because the interaction was the real problem. Withdrawing it had a cost: two of its
findings were defects rather than inefficiencies, and they shipped. Spec 003 fixed those two.

What is left is genuinely optimisation, and it is parked for one reason: **there is still no
baseline measurement.** Every item below is a plausible improvement to a path that runs at
display refresh rate; none of them is backed by a number from a device. The earlier
withdrawal happened because the spec could not justify most of itself, and nothing has
changed about that.

So this file is deliberately a *record*, not a plan. It exists so the next person — or the
next session — does not have to re-derive the audit from scratch, and so that "we looked at
this and chose not to" is visible rather than implied by silence. The previous attempt's
only copy lived in a deleted file recoverable from `2e0304b`, which is not findable by
anyone who does not already know it is there. That is the mistake this file corrects.

**Do not implement any of this without first capturing the baseline in the next section.**

---

## The measurement that would unpark it

On a real device, not the emulator — its frame pacing is not representative.

| Metric | Tool | What would justify acting |
|---|---|---|
| Recomposition counts | Layout Inspector, 10 s continuous scroll | Lanes and header should be **0** after spec 003. If they are not, that is a spec 003 regression and nothing here matters until it is fixed. |
| Allocation rate | Profiler, Java/Kotlin allocations, same scroll | This is the number the whole file is about. No target: capture it, then decide. |
| Frame timing | `adb shell dumpsys gfxinfo com.weiting.timeline framestats`, `--reset` between runs | Frames over the refresh budget. If zero, stop here. |
| Worst case | Both of the above at MONTH / `MIN_ZOOM`, plus a sustained drag | The drag is the only path that recomposes per frame by design. |

Spec 003's acceptance criteria already require a Layout Inspector session for the
recomposition check, so the first row comes for free the next time anyone opens it. That is
the cheapest moment to also take rows 2 and 3.

---

## Current Logic

Verified against the working tree after spec 003.

### Work repeated every frame, result changes only on a boundary crossing

| Where | Line | Per frame |
|---|---|---|
| `ui/TimelineHeader.kt` | 55 | `scale.ticksIn(...)` inside the `Canvas` draw lambda |
| `ui/TimelineContent.kt` | 116 | `scale.ticksIn(...)` again, inside `drawBehind`, for the same window the header just built |
| `TimeAxis.kt` | 78-83 | each call allocates an `ArrayList<Tick>` plus one `Tick` and one formatted `String` per element |
| `ui/TimelineHeader.kt` | 106 | `measurer.measure(AnnotatedString(text), style)` per labelled tick — the measurer caches the layout, the `AnnotatedString` is new each time |
| `TimeAxis.kt` | 24, 56 | `Duration.between(...).toMinutes()` allocates a `Duration` per call; reached once per tick per frame in both canvases, and once per bar per layout pass |

### Smaller

| Where | Line | What |
|---|---|---|
| `ui/TimelineContent.kt` | 454 | two formatted strings per lane per recomposition — once per frame for the dragged lane |
| `ui/SchedulerControls.kt` | 59, 68 | the `derivedStateOf` bodies format strings. `derivedStateOf` suppresses *recomposition* when the result is unchanged; it does not suppress the *computation*, which still runs on every read after invalidation |
| `ui/TwoFingerSwipe.kt` | 43, 49 | `event.changes.filter { }` allocates a list per pointer event; `fold` allocates an `Offset` per pointer |

### Magnitude, recalculated

On a 411dp phone the timeline is about 303dp after the 108dp label column:

| Scale | zoom 1 | `MIN_ZOOM` |
|---|---|---|
| DAY | 4.7 ticks | 11.8 |
| WEEK | 3.4 | 8.6 |
| MONTH | 7.6 | **18.9** ← worst realistic case |
| YEAR | 3.8 | 9.5 |

So the tick path is on the order of **100–150 short-lived allocations per frame** across both
canvases at the worst case. That figure is **derived from these counts, not measured** — the
distinction matters, and the earlier spec's own text was clearer about it than the project's
writeups later were.

`MAX_TICKS = 600` (`TimeAxis.kt:71`) does not trigger at any reachable zoom on any plausible
screen. It is a guard, and effectively dead code.

---

## What would be done, if it were unparked

Recorded at the level of a design sketch. Not acceptance criteria — this spec has none,
because it is not to be built.

### 1. A tick cache shared by the header and the grid

`ticksIn`'s result depends only on `(scale, firstVisibleTick, lastVisibleTick)` and is
identical within one tick cell of scrolling. Two shapes, and they trade off:

**A — a mutable cache read inside the draw lambda.** Held in `remember { TickCache() }`,
hoisted to `SchedulerScreen`, passed to both canvases. Zero recomposition, zero per-frame
allocation once warm. Cost: the draw phase mutates something, which is safe for
non-snapshot state but reads as a smell.

**B — a two-stage derived state read during composition.** A cheap allocation-free `Int`
(which tick cell the left edge is in) through `derivedStateOf`, then a `remember` keyed on
it. Idiomatic. Cost: crossing a tick boundary now recomposes — roughly once per 64dp of
scroll at DAY — where today there are none.

Spec 002's discussion leaned towards **A**, on the grounds that "scrolling causes no
recomposition" is the property that was hardest to get and easiest to lose. Spec 003 is
evidence for that view: the property *was* lost, silently, by a single argument expression.

### 2. Pre-measured tick labels

Measure once when the tick list is rebuilt and cache the `TextLayoutResult` alongside each
`Tick`. The draw lambda then only calls `drawText(layout, topLeft)`. Depends entirely on
item 1 — there is no point caching labels without caching the list that holds them.

### 3. Drop `Duration` from the hot path

```kotlin
// before — allocates a Duration, then discards it
Duration.between(origin, time).toMinutes().toDouble()
// after — no intermediate object
ChronoUnit.MINUTES.between(origin, time).toDouble()
```

Both truncate toward zero on sub-minute remainders, so `TimeAxisTest` should pass unchanged
— which is the point of having it. **Caveat worth keeping:** `ChronoUnit.MINUTES.between` on
`LocalDateTime` may still allocate internally. That needs allocation tracking on the device
to confirm, not an assumption; the earlier spec recorded the same caveat and it was never
resolved.

### 4. The small ones

- `ui/TwoFingerSwipe.kt` — iterate `event.changes` by index and accumulate into local
  floats instead of `filter` + `fold`.
- `ui/SchedulerControls.kt` — stage the derived state: derive a cheap key (the window-start
  instant) first, format only when it changes.
- `ui/TimelineContent.kt:454` — rebuild the label strings only when the displayed minute
  changes, not on every frame of a drag.

---

## Decision, and what would change it

**Parked.** Four plausible optimisations, zero measurements, and a path that is not
currently known to be slow. Spec 003 already took the two items from this audit that were
defects; what remains is efficiency.

It should be unparked if any of these becomes true:

- The Layout Inspector session required by spec 003 shows lanes recomposing during a scroll
  (that is a correctness regression, not an optimisation).
- `framestats` shows frames over budget during a scroll or a drag on a real device.
- The task list grows enough that `Column` has to become `LazyColumn` — at which point the
  per-lane costs here multiply and are worth revisiting together with that change.

It should stay parked if the measurement says the allocation rate is uninteresting, which
is the outcome the magnitude table above suggests is likely.
