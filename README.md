# Timeline — a Compose Gantt scheduler

A horizontally scrollable timeline scheduler built with Jetpack Compose. Task bars are laid
out against a time axis; they can be dragged along it, resized from either edge, and snap to
a grid that follows the current time scale. No third-party charting or Gantt library — the
layout, drawing and gesture handling are all hand-written against standard Compose and
AndroidX APIs.

One screen: `MainActivity` → `SchedulerScreen`.

---

## Running it

Open the project in a recent Android Studio and run the `app` configuration. Nothing else is
required — no API keys, no local config, no manual steps.

Two things are worth knowing before the first build:

- **Gradle provisions its own JDK.** `gradle/gradle-daemon-jvm.properties` pins the daemon
  to toolchain **25**, resolved through the Foojay toolchain plugin declared in
  `settings.gradle.kts`. The first build will download that JDK if the machine does not have
  it, which takes a minute and needs a network connection. It does not matter what JDK is on
  your `PATH` (this was developed with 17 on the path).
- **`local.properties` is not in the repo**, as usual. Android Studio writes it on first
  open; from the command line, set `sdk.dir` or `ANDROID_HOME`.

From the command line:

```bash
./gradlew :app:installDebug          # build and install on a connected device
./gradlew :app:testDebugUnitTest     # 56 unit tests, pure JVM, no device needed
./gradlew :app:assembleDebug         # APK only
```

| | |
|---|---|
| **minSdk** | 34 — high enough that `java.time` needs no desugaring |
| **compileSdk / targetSdk** | 37 |
| **AGP / Gradle / Kotlin** | 9.4.1 / 9.6.0 / 2.2.10 |
| **Compose BOM** | 2026.02.01 |

Developed and verified on a Pixel 9 Pro XL. The gesture behaviour needs a real touch screen
to judge, so the emulator is a poor way to evaluate it.

---

## Using it

| Where you touch | One finger, horizontal | One finger, vertical | Two fingers |
|---|---|---|---|
| A bar's left edge | Change its start | *(falls through to the lane list)* | Switch period |
| A bar's middle | Move it | *(falls through)* | Switch period |
| A bar's right edge | Change its length | *(falls through)* | Switch period |
| Empty lane space | Pan the timeline | Scroll the lanes | Switch period |
| The axis header | Pan the timeline | — | Switch period |

Pressing a bar starts an edit immediately: the bar takes a diagonal hatch, the zone under
your finger lights up, and **panning is locked for the whole press** so an edit can't drag
the view out from under itself. Dragging a bar to either edge of the viewport scrolls the
axis to make room. Releasing ends the edit.

The control row has the time scale (日 / 週 / 月 / 年), prev / today / next, a zoom slider,
and the snap interval — which defaults to **自動**, following the scale.

---

## What the brief asked for

| Requirement | Where |
|---|---|
| Time axis header with readable tick labels, aligned while scrolling | `ui/TimelineHeader.kt` |
| Horizontal scrolling, header and content in sync | `SchedulerScreen.kt` — one shared `ScrollableState` |
| Bars in rows, positioned and sized from start + duration | `ui/TimelineContent.kt` |
| Drag to move a bar, updating its start | `TaskDrag.kt`, `SchedulerState.kt` |
| Resize from either edge, updating start or duration | same |
| Snapping to a configurable grid | `TimeAxis.snapToGrid` |
| Configurable: scale, zoom, snap interval, row height | `model/TimelineConfig.kt:17, 18, 27, 43` |
| Sample data so the screen is populated | `SampleData.kt` — six tasks anchored to *today* |

Snapping follows the scale rather than standing apart from it: **15 minutes** at DAY, **a
day** at WEEK and MONTH, **a week** at YEAR. At the YEAR scale one tick is a month, so a
fixed 15-minute grid would have been some three thousand times finer than anything on
screen. `TimelineConfig.snapOverride` keeps it configurable — `null` follows the scale,
which is the default.

---

## Beyond the brief

Mentioned here because the brief asks for extras to be called out rather than left to be
noticed.

- **Two-finger swipe switches period.** Panning and switching period are the same motion on
  the same axis, so separating them by *where* you touch would put two outcomes of very
  different magnitude either side of an invisible boundary a few dp from a bar. The finger
  count is an unambiguous declaration of intent that costs no screen area.
- **Prev / today / next navigation**, stepping by one unit of the current scale in real
  calendar terms, so a month step honours that month's length.
- **"Now" marker** — a red rule that advances on its own, ticking on the minute boundary.
- **Centred on now** at launch and on 今天, in the timeline area rather than the window.
- **Pinned task-name column**, outside the horizontal scroller but inside the same vertical
  one, so the two cannot drift apart. It shows the draft time live while a bar is dragged.
- **Press-to-reveal hit zones** plus a finite discovery pulse on the resize grips — three
  times on first appearance, then it stops.
- **Edge auto-scroll** during an edit, with velocity ramped by how deep into the threshold
  the finger is.
- **Live / release snapping** is a toggle: live steps the bar in grid units and makes the
  grid visible, release-only tracks the finger 1:1 and settles. Both land on the same grid.
- **56 JVM unit tests and 19 Compose previews**, including the three in-flight drag states,
  which are otherwise only reachable with a finger on a device.

---

## How it is built

Four decisions carry the rest. Each is drawn out in the pages under `docs/`.

**`TimeAxis` is pure and imports no Compose.** Time ↔ pixel conversion, snapping and tick
generation are plain functions, so they are unit-testable on the JVM with no device and no
Compose test rule. The axis is strictly linear in minutes from an origin; calendar
irregularity lives only in the tick generator, which walks real `LocalDateTime` boundaries —
so February renders narrower than January and leap years come out right without the
coordinate layer knowing months exist.

**Scroll position lives in the time domain**, as minutes from the origin rather than a pixel
offset. Changing the scale or the zoom then keeps the viewport's left edge pinned to the same
instant for free, with no compensation arithmetic anywhere.

**The timeline does not use `Modifier.horizontalScroll`.** That needs a child as wide as the
whole time range — roughly 553,000px at the DAY scale over four months, for a viewport a few
hundred px wide. A shared `Modifier.scrollable` keeps the content exactly one viewport wide
and leaves the axis conceptually unbounded; fling physics still come for free.

**Scrolling does not recompose.** Ticks and grid lines read the viewport inside draw
lambdas, bar positions inside `offset { }` in the layout phase. (See the limitations below:
this was silently broken for a while and the fix is not yet measured.)

Snapping is anchored to the origin, never to the dragged bar, so independently dragged bars
agree with each other and with the tick lines. The origin is **this week's Monday at
midnight**, which is what makes the 15-minute, daily and weekly grids all divide evenly into
one grid.

An in-flight edit lives in a separate draft rather than being written into the task list per
frame, because that list is a `SnapshotStateList` and a per-frame write would invalidate
every lane rather than the one being dragged.

```
app/src/main/java/com/weiting/timeline/
├── MainActivity.kt
└── scheduler/
    ├── model/{Task, TimeScale, TimelineConfig}.kt
    ├── TimeAxis.kt          time ↔ px, snapping, tick generation — pure
    ├── TaskDrag.kt          drag modes, the draft, bar hit-testing
    ├── SchedulerState.kt    @Stable holder: tasks, viewport, edit bracket, auto-scroll
    ├── NowTicker.kt         the minute clock, kept out of the draw phase
    ├── SampleData.kt
    ├── SchedulerScreen.kt   assembly, scroll wiring, the edit lock
    └── ui/                  header, content, controls, previews, two-finger swipe
```

`SchedulerState` is a plain `@Stable` class rather than a ViewModel. One screen, pure UI
state, no I/O — and it being an ordinary class is what lets the unit tests drive it directly
and what lets the previews pose a mid-drag state.

---

## Documentation

`docs/` holds four self-contained HTML pages — no CDN, no web fonts, no external requests at
all, so they open offline from the filesystem. They are diagram-led, and they explain *why*
each design is the way it is rather than listing features.

| Page | Spec |
|---|---|
| `docs/architecture.html` | the scaffold stage (predates the spec process) |
| `docs/gesture-layer.html` | `ai-cowork/001-gesture-layer.md` |
| `docs/edit-lock.html` | `ai-cowork/002-edit-lock-and-scale-snap.md` |
| `docs/audit-defects.html` | `ai-cowork/003-audit-defects.md` |

`ai-cowork/` holds the specs each piece of work was built from. Each has acceptance criteria,
the state of the code it attached to with line references, the intended change *with its
reasoning*, and the open questions that were mine to answer — plus a Resolution recording how
each was settled and what was deliberately left out. `004-render-path-parked.md` is written
and deliberately **not** implemented.

The AI usage writeup is at the end of `TimelineGanttScheduler.md`, as that document asks.
A Traditional Chinese version of it is in `ai-cowork/`.

---

## Known limitations

Listed because they are real, not because they are hypothetical.

- **Edits do not survive Activity recreation.** Rotate the device and you are back to the
  sample data. `rememberSaveable` with a custom `Saver` would fix it; it was scoped out.
- **"Scrolling does not recompose" is fixed but unmeasured.** A single argument expression
  silently broke it for several rounds; it was found by auditing the documentation against
  the source, not by using the app. The fix is sound by construction, but confirming it needs
  Layout Inspector's recomposition counter and nobody has watched it yet. There is also no CI
  guard for that class of regression — see `ai-cowork/003-audit-defects.md`.
- **Lanes are a `Column`, not a `LazyColumn`**, and every bar is composed regardless of
  horizontal visibility. Fine for six tasks, wrong for six hundred.
- **The tick list is rebuilt every frame, in both canvases**, although it only changes when
  the viewport crosses a tick boundary. Estimated as small and deliberately left alone
  pending a real measurement — `ai-cowork/004-render-path-parked.md` records what would
  change that.
- **No vertical reordering.** Cut twice on purpose: rows carry no independent meaning yet, so
  an order would encode nothing. A vertical drag on a bar falls through to the lane scroller.
- **`fingerX` during edge auto-scroll is unverified when a bar is clipped outside the
  viewport.** The arithmetic is right by construction; that path was never exercised.
