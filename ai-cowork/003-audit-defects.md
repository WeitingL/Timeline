# Spec 003 — Three defects found by audit

- **Status:** implemented 2026-10-11; one acceptance criterion deliberately unguarded (see Decisions)
- **Date:** 2026-10-11
- **Baseline:** `11518d0` plus the uncommitted documentation changes
- **Scope:** `app/src/main/java/com/weiting/timeline/scheduler/`

---

## Description

Three defects. What they have in common is more interesting than what they do: **none was
found by using the app.**

1. **A lane recomposes on every scroll frame.** This breaks an architecture commitment
   written down in `.claude/CLAUDE.md:124`, and both writeups list that commitment as
   something the project got right.
2. **The "now" marker reads the clock inside a draw lambda**, so it only moves when
   something else happens to invalidate the draw. Leave the app open and still and the red
   line silently shows the time of the last scroll.
3. **`sampleTasks()` sits in a default argument** and allocates six `Task` objects plus a
   list on every recomposition of `rememberSchedulerState`, which `remember` then discards.

Defects 2 and 3 were already known. They were findings of a render-path spec that was
written and withdrawn before any code shipped, and that spec's own commit message
classified the first of them as "a correctness bug, not a cost". Withdrawing the spec meant
they shipped. That is the part worth recording.

**Not in scope:** the rest of that withdrawn audit — tick caching, label pre-measurement,
the `ChronoUnit` change, the pointer-loop allocations. Those are optimisations still waiting
on a baseline measurement, and nothing here changes that. This spec takes only the two items
from it that are defects rather than inefficiencies, plus the new regression.

---

## How these were found

Recorded because the method is the point, not an aside.

Two read-only subagents cross-checked the documentation against two different sources of
truth: one against the source tree, one against the git history. Neither was asked to find
bugs. Both were asked whether the documents were still *true*.

- **Defect 1** came from the code audit noticing that a documented commitment was
  contradicted by the source — it was looking for stale claims, and found a stale claim
  whose cause was a live regression.
- **Defects 2 and 3** came from the history audit noticing that the writeup's "compromise"
  list omitted two findings which a withdrawn spec's own commit message had separated out
  as correctness problems. The audit was checking the writeup for honesty and found that the
  omission was hiding live bugs.

Both are cases of **auditing the documentation surfacing a defect in the code**, which is
not the direction that was expected. It only works because the documents make specific,
checkable claims with line references — a vaguer document would have had nothing to falsify.

Also worth recording: the audits were not taken at face value. Of the code audit's 34
findings, one was wrong (it claimed "18 Kotlin files" matched no count; `app/src/main`
contains exactly 18, and the enumeration had missed the three theme files). Each finding
below was re-verified by hand before being written up here.

---

## Current Logic

Verified against the working tree.

### Defect 1 — the lane recomposes

`ui/TimelineContent.kt:169-177`, in `TaskLane`'s composable body:

```kotlin
val metrics = rememberUpdatedState(
    LaneMetrics(
        barWidthPx = barWidthPx,
        handlePx = handlePx,
        laneOriginX = axis.xOf(start, state.viewportStartMinutes),   // line 173
        pxPerMinute = pxPerMinute,
        edgeThresholdPx = edgeThresholdPx,
    ),
)
```

`viewportStartMinutes` is `mutableDoubleStateOf` (`SchedulerState.kt:51`). Line 173 is an
argument expression, so it is evaluated **during composition** and the read is recorded
against `TaskLane`'s recompose scope. Every scroll frame therefore invalidates every lane,
one per task.

The two deferred readers are still correct — `drawBehind` at `:111-112` and the `offset { }`
lambda at `:206` — so the header and the grid are unaffected. The regression is confined to
this one line, introduced with the `LaneMetrics` holder in spec 002.

The comment immediately above it, `:166-168`, says `laneOriginX` "has to be read fresh each
frame rather than captured at composition". It *is* captured at composition. It only stays
fresh *because* of the recomposition the project forbids, so the comment documents the
intent and describes the opposite of the behaviour.

`laneOriginX` has exactly one consumer: `:286`, which converts the pointer position from
bar-relative to timeline-area-relative for edge auto-scroll.

### Defect 2 — the now marker is frozen between redraws

- `ui/TimelineHeader.kt:109-114` — `drawNowMarker(axis, viewportStartMinutes, color)`
  calls `LocalDateTime.now()` at `:114`, inside the draw lambda.
- `ui/TimelineContent.kt:466-471` — `drawNowLine(...)`, same shape, same call at `:471`.

Both are reached only from a draw scope, so the clock is sampled only when something else
invalidates the draw. With the app idle, nothing does.

Two consequences, and only the first is a bug: the marker is wrong, and two
`LocalDateTime` objects are allocated per frame for a value that changes once a minute.

### Defect 3 — sample data allocated per recomposition

`SchedulerState.kt:352-355`:

```kotlin
@Composable
fun rememberSchedulerState(
    tasks: List<Task> = sampleTasks(),
    ...
```

A default argument is evaluated on every call, so every recomposition of this composable
builds six `Task` objects and a list. `remember` then ignores them, because it only uses its
calculation on first composition. Harmless to behaviour, pure waste — and currently it
compounds with defect 1.

---

## Acceptance Criteria

**Defect 1 — the commitment holds again**

- [ ] No composable body in `TaskLane` reads `state.viewportStartMinutes`.
- [ ] A sustained scroll recomposes no lane. Verified with Layout Inspector's recomposition
      counts, not by reading the code — reading the code is what produced the regression.
- [ ] Edge auto-scroll still works: a dragged bar held near either edge still scrolls the
      axis and still keeps its screen position.
- [ ] `grep` for `viewportStartMinutes` in `TimelineContent.kt` returns only draw-phase and
      layout-phase readers, plus the gesture loop.
- [ ] The comment at `:166-168` describes what the code does.

**Defect 2 — the marker moves on its own**

- [ ] With the app open and untouched, the red marker advances.
- [ ] It advances about once a minute, not once per frame.
- [ ] No `LocalDateTime.now()` call remains inside any draw lambda.
- [ ] The marker stays correct across a scroll, a scale change and a zoom change.

**Defect 3**

- [ ] `rememberSchedulerState` allocates no sample data after first composition.
- [ ] Passing an explicit task list still works, and previews still render.

**No regressions**

- [ ] `./gradlew :app:assembleDebug :app:testDebugUnitTest` clean, no warnings.
- [ ] All existing tests pass unchanged.
- [ ] All 19 previews still render.

---

## Expected Code Change

### 1. Move `laneOriginX` out of composition

Drop it from `LaneMetrics` (`ui/TimelineContent.kt:350-356`) and compute it inside the drag
loop instead:

```kotlin
// in the drag loop, replacing `m.laneOriginX`
val barStart = state.draft?.start ?: task.start
val laneOriginX = axis.xOf(barStart, state.viewportStartMinutes)
state.autoScrollStep(
    fingerX = change.position.x + laneOriginX,
    ...
)
```

A snapshot read inside a `pointerInput` suspend block is not inside an observation scope, so
it subscribes nothing and invalidates nothing. It is also *more* correct than the current
value, because it reads the viewport and the draft as they are in that pointer event rather
than as they were at the last composition.

Then fix the comment to say what is actually true: the bar's left edge moves during the
gesture, so it is read inside the loop rather than captured at composition — which is the
sentence the old comment was reaching for and inverted.

### 2. Hoist the clock out of the draw phase

```kotlin
// SchedulerScreen
val now by produceState(LocalDateTime.now()) {
    while (true) {
        delay(millisUntilNextMinute())
        value = LocalDateTime.now()
    }
}
```

Pass `now` into `TimelineHeader` and `TimelineContent` as a parameter; `drawNowMarker` and
`drawNowLine` take it instead of calling the clock. Ticking on the minute boundary rather
than every 60 000 ms keeps the marker honest against the wall clock instead of drifting by
however long after launch the first tick happened to fall.

This read *is* in composition, and it should be: it changes once a minute, so it costs one
recomposition a minute, and it cannot be deferred to draw without reintroducing the bug.

### 3. Make the default argument lazy

```kotlin
fun rememberSchedulerState(
    tasks: () -> List<Task> = ::sampleTasks,
    ...
) = remember { SchedulerState(origin, tasks(), config) }
```

Call sites that pass a list become `tasks = { myList }`. There are two, both in
`ui/SchedulerPreviews.kt`.

### 4. Tests

Defect 1 is a Compose-phase property, so a JVM unit test cannot see it — the same blind spot
that let the stale-`Task` bug through in spec 001 and that the `positionChange()` contract
test did manage to close. Here there is no plain-class contract to pin, so the criterion is a
Layout Inspector observation, recorded in the Resolution rather than asserted in code.

Defects 2 and 3 are testable:

- `millisUntilNextMinute()` returns a value in `1..60_000` and lands on a minute boundary.
- Given a fixed `now`, the marker's x position equals `axis.xOf(now, viewport)` — so the
  marker is a function of the passed-in instant and not of the clock.
- `rememberSchedulerState`'s task lambda is invoked exactly once across repeated
  invocations. Testable with a counting lambda if the state holder is constructed directly.

---

## Decisions

Confirmed in discussion, 2026-10-11.

1. **The marker does not tick while the scheduler is off screen.** `produceState`'s
   coroutine is tied to the composition, so it stops on leaving and resumes on return:
   correct whenever visible, idle when not. No `LifecycleResumeEffect`. (The stated reason
   was also that nobody watches the marker while moving the timeline, which points the same
   way.)

2. **A flat one-minute tick.** Matches the marker's visual precision at the DAY scale,
   where a minute is about 1dp. Tying the interval to `pxPerMinute` would be exact and more
   machinery than the problem is worth.

3. **No test harness for defect 1 — accepted as an unguarded risk.** Guarding it needs an
   instrumented test with `ComposeTestRule` and recomposition counting: a new dependency, a
   new source set, and an assertion that is awkward to write and awkward to trust.

   The consequence is stated here rather than left implied: **this class of regression is
   invisible to CI and can only be caught by review or by a Layout Inspector session.** It
   has already happened once — a single argument expression in spec 002 silently broke a
   written-down architecture commitment, and it survived a full implementation round, a
   device test and a commit before a documentation audit found it. If it happens again it
   will be found the same way, or not at all.

4. **The remaining render-path items become spec 004, parked and not implemented.** Written
   up rather than left in a deleted file, so "we looked at this and chose not to" is
   findable. See `ai-cowork/004-render-path-parked.md`.

---

## Resolution

All three defects fixed. 56 JVM unit tests (up from 51), clean build, no warnings.

**Defect 1.** `laneOriginX` is gone from `LaneMetrics` and computed inside the drag loop
from `state.draft?.start ?: task.start` and `state.viewportStartMinutes`. A snapshot read
inside a `pointerInput` block is in no observation scope, so it subscribes nothing — and the
value is strictly better than before, being this pointer event's viewport rather than the
last composition's. `TimelineContent.kt` now reads `viewportStartMinutes` in exactly three
places: `drawBehind` (draw), the `offset { }` lambda (layout), and the gesture loop (neither).

The comment that described the opposite of the behaviour is replaced by one that explains
why the read sits where it does.

**Defect 2.** `NowTicker.kt` adds `rememberNow()` over `produceState`, plus a pure
`millisUntilNextMinute(now)` so the boundary arithmetic is testable. `now` is threaded from
`SchedulerScreen` into both canvases as a parameter; `drawNowMarker` and `drawNowLine` take
it. No `LocalDateTime.now()` remains under `scheduler/ui/`.

Five tests cover the interval: a whole minute, mid-minute, sub-second precision, the
`1..60_000` bound across every second (zero would spin the coroutine, over a minute would
skip one), and that waiting the returned interval lands on a boundary.

**Defect 3.** `rememberSchedulerState` takes `tasks: () -> List<Task> = ::sampleTasks` and
invokes it inside `remember`. One call site updated, in the previews.

### Verified

- No composition-phase read of `viewportStartMinutes` in `TaskLane` — by inspection of all
  six matches in the file.
- No `LocalDateTime.now()` under `scheduler/ui/` — by grep.
- 56 tests pass; the 51 pre-existing ones unchanged.

### Not verified

- **That lanes no longer recompose during a scroll.** This is the criterion that matters
  most and it needs Layout Inspector. The code change is sound by construction — the read
  moved out of composition — but "sound by construction" is exactly what was believed about
  the pointer-dispatch assumption in spec 001, and that was wrong. Treat it as unverified
  until someone watches the counter.
- That the marker advances on an idle screen over several minutes. Mechanically it must,
  but nobody has sat and watched it.

## Questions


1. **Should the now marker tick when the app is backgrounded?** `produceState`'s coroutine
   is tied to the composition, so it stops when the composable leaves and resumes on return
   — which means the marker is correct whenever it is visible and does no work when it is
   not. I think that is exactly right and needs no `LifecycleResumeEffect`, but it is worth
   stating rather than discovering.

2. **One minute, or finer?** A minute matches the marker's visual precision at the DAY
   scale (15 min grid, 64dp per hour — a minute is about 1dp). At YEAR/min zoom a minute is
   invisible, so the tick is pure waste there; at a hypothetical much deeper zoom it would
   be too coarse. Tying the interval to `pxPerMinute` would be exact and more machinery than
   this is worth. **I would keep a flat minute.**

3. **Is defect 1 worth a test harness?** Properly guarding it needs an instrumented test
   with `ComposeTestRule` plus recomposition counting, which is a new dependency and a new
   source set for one assertion. The alternative is that this regression class stays
   invisible to CI and relies on review. Given it has now happened once, I lean toward
   adding the harness — but it is a real cost and it is spec 004's worth of work, not this
   spec's.

4. **Should the withdrawn render-path spec come back for the rest?** This spec deliberately
   takes only the two defects. The remaining four items are optimisations with no baseline.
   I would leave them withdrawn, and only revisit after the Layout Inspector session that
   criterion 1 requires anyway — at which point the baseline exists for free.
