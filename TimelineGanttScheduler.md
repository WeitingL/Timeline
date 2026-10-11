# AI Usage Writeup

## 1. Setting up the AI workflow

I used **Claude Code (Opus 5)** in the terminal against the Android Studio project, plus
Gradle and a Pixel 9 Pro XL for verification. One tool, one long session, no copy-pasting
between chat windows.

My method is a loop:

```
plan  →  review  →  coding  →  review  →  (next round's plan)
```

**The point is that the two reviews are not the same activity and do not use the same
instrument.**

- **The review after planning** is me reading a spec, then looking at it drawn as diagrams,
  then deciding whether to let it start.
- **The review after coding is not me reading the diff.** I barely read the code it writes
  line by line. That half is delegated to **four mutually independent mechanisms**, each of
  which catches things the other three cannot see (section 3).

The loop is enforced as five gates, written into `.claude/CLAUDE.md` (which is loaded into
the agent's context at the start of every session):

```
1. SCOPE      I confirm what needs doing             ┐ plan
2. SPEC       ai-cowork/NNN-<slug>.md                ┘
3. DISCUSS    a visual doc under docs/, walked through  → review (the plan)
4. IMPLEMENT  code, build, tests                        → coding
5. COMMIT     only after I have run it on a device      → review (the result)
```

**This process did not exist at the start; it exists because the first few rounds went
badly.** I would give a loose instruction, and the agent would implement and commit in one
turn — design decisions made inside code I hadn't read, which I then had no way to review
after the fact. So I made it write the rules down and held it to them. The file says it
plainly: **"no objection" is not approval; silence is not approval.**

Gate 5 is the one that mattered most. A finished implementation is not the trigger for a
commit. A green build is not the trigger. **My word is.** That needed weight because
several behaviours here (pointer dispatch order, gesture arbitration, scroll direction)
can only be settled on hardware, so "it compiles and the tests pass" is a weaker claim in
this project than it sounds.

The same file fixes a few things I didn't want to re-explain each session: the language
split (terminal replies in Chinese, everything written to a file in English, UI strings in
Chinese); **verify, don't recall** (read the file before citing a line or an API; say so
explicitly when something cannot be confirmed without hardware); **correct the record**
(when a diagnosis turns out wrong, say which part, and don't leave the superseded claim
standing); and seven settled architecture commitments, where changing one is a gate-1
conversation rather than an implementation detail.

---

## 2. The development process: not planned in one pass

This cuts against the impression of writing one complete plan and then building it.

**I never drew the full blueprint.** Each round took one piece, got it running, looked at
what was wrong *on the device*, and only then decided what the next piece was. The specs
**grew along the way**; they were not derived from the brief in one sitting.

| Round | What was done | How the next step got decided |
|---|---|---|
| 1 | Brief vs. the empty Compose template | Build the foundation first, don't rush gestures |
| 2 | Scaffold: coordinate layer, axis header, static bars | Only once it runs can you see what gestures need |
| 3 | **spec 001** gesture layer: drag, resize, snap | A device run shows where the next pain is |
| 4 | A performance spec — **written and withdrawn** | The device proved the real problem wasn't performance |
| 5 | **spec 002** edit lock, scale-derived snapping, centring | The device found two more bugs, each its own round |
| 6 | **spec 003** three defects found by audit | They fell out of auditing the docs, not out of using the app |
| 7 | **spec 004** remaining render-path items — **written, not implemented** | No baseline measurement, not worth doing |

**Round 4 is the one that explains the process.** I assumed the next step was render
performance, and the agent produced a full spec: an audit of the work repeated every frame,
an **estimate** of roughly 100–150 short-lived allocations per frame, seven proposed
changes. But its own analysis also showed that only three of the seven were certain wins,
and that the magnitude was "pure waste, but not evidence of a slow app".

Meanwhile I'd found on the device that the genuinely unusable part was **editing fighting
panning** — dragging a bar dragged the whole view with it. That is an experience problem,
not a performance problem. So that spec **was withdrawn before a single line of code**.

**That withdrawal turned out to have a cost, and I think it is the most useful lesson
here.** Two of its seven findings were not optimisations but **defects** — the now marker
reading the clock inside a draw lambda (so an idle app shows the time of the last scroll),
and sample data in a default argument being allocated on every recomposition. Its own
commit message even classified the first as "a correctness bug, not a cost". **Withdrawing
the whole spec shipped both bugs.** They lived in the product for several rounds until
round 6 found and fixed them.

The lesson isn't "don't withdraw" — withdrawing was right. It's that **when you withdraw,
separate the defects out first**, and at the time I wasn't distinguishing "optimisation"
from "defect" at all.

**There were also several loops inside one round.** After spec 002 shipped: install, drag a
bar → the whole view pans → report → fix; install, drag continuously → the bar moves 15
minutes and stops → report → fix; install → good, now commit. Each one a full
diagnose → explain → fix → re-run tests → wait for my verification.

### Where I overrode it

**I rejected its first answer on gesture arbitration.** It proposed separating "pan" from
"switch period" by swipe *velocity* — flick to page, slow drag to pan. I said the intent
should come from *where* I touch, not how fast. It then proposed using the blank area
inside a row to switch period. I said that felt wrong but couldn't say why. It worked out
the why and told me: the blank area is pixel-adjacent to the bars with no visual boundary,
so a few dp decides between "nudge a task by 15 minutes" and "jump the view by a day", and
the mis-touch costs are asymmetric — one is getting lost, the other is editing data. We
landed on two fingers for period switching: the finger count *is* the intent.

**My instinct caught the bad design; its analysis explained it; neither of us had it
alone.**

**I cut scope repeatedly.** Persistent selection, vertical reordering, a persistence layer,
the whole performance spec — all proposed by it, all cut by me.

**I insisted on a touch-slop buffer** when it wanted a bar to react to the first pixel. Its
argument (snapping quantises the result anyway) wasn't wrong, but at the YEAR scale one
grid step is a *week*, and I didn't want an unsteady tap moving a task by a week.

**I asked for the edit styling.** It had shipped an invisible edit state. It chose diagonal
hatching over a colour change, with a reason I accepted: a bar's colour is its identity in
the chart.

**I overrode a rule it had written itself.** It wrote "spec numbers increase, never
reused". Withdrawing the performance spec left a hole at 002, and I had 003 renumbered to
002 — **a gap looks like a lost file, which is worse than a renumber**. (Note for anyone
reading the log: the "Spec 003" in commit messages from 2026-10-10 is the file now named
`002-edit-lock-and-scale-snap.md`.)

**I declined a test harness.** Properly guarding the recomposition regression in spec 003
needs an instrumented test with recomposition counting. I said no — it's awkward to verify
anyway. The cost is recorded in the spec rather than passed over: **this class of
regression is invisible to CI.**

---

## 3. How I plan, and how I review

### Plan: the spec template

Every spec has the same five sections, in order:

| Section | Purpose |
|---|---|
| **Description** | What this does, and what it explicitly does *not* do |
| **Acceptance Criteria** | Checkable, grouped by concern |
| **Current Logic** | The facts of the code it attaches to, with `file.kt:line`, **verified against the working tree** |
| **Expected Code Change** | The intended change *with its reasoning*, not just its shape |
| **Questions** | The open decisions that are mine, each with a recommendation and its trade-off |

(Spec 002 calls the fifth section Decisions rather than Questions, because they were all
settled before implementation.)

**Current Logic is the section that pays.** It forces the agent to go read the code and
cite line numbers before proposing anything, which is where several "this is already
handled" and "this is already broken" findings came from.

**Questions forces decisions into the open** instead of being silently picked. I also told
it explicitly: **a spec is allowed to argue against itself.** The performance spec's
withdrawal is that rule working.

When the work is done, the spec gets a **Resolution**: how each question was answered, what
was deliberately left out, and **what remains unverified**.

### Review of the plan: the visual doc

Each piece of work gets a self-contained HTML page of diagrams, numbered to match its spec:

```
docs/architecture.html    Scaffold (predates the spec process, unnumbered)
docs/gesture-layer.html   001
docs/edit-lock.html       002
docs/audit-defects.html   003
```

The rules: **one file, zero external requests** (no CDN, no web fonts) so it opens offline
and survives being sent as a single attachment; **diagrams carry the explanation, prose only
captions them**; light and dark through `:root` tokens; readable at 320dp.

**This started as documentation and became a design tool.** Drawing "which render phase
reads the scroll position", or "where two adjacent pixels do different things", forces out
vagueness that prose hides.

The clearest case: that "blank area switches period" design was killed **at the diagram
stage**. As a paragraph I only felt it was wrong; drawn as "bar and blank space flush
against each other, no boundary, outcomes two orders of magnitude apart", the problem was
obvious. Diagrams also force a sense of magnitude: why `horizontalScroll` can't be used
reads as "the child would be wide" in prose, but once drawn and costed — about 553,000px at
the DAY scale over four months, for a viewport a few hundred px wide — the decision needed
no further discussion.

### Review of the result: four independent mechanisms

**I barely read the code it writes, line by line.** This is the part I'd most want to share
— not out of laziness, but because reading the diff does not catch the expensive bugs in
this task. They compile, they pass, and they read sensibly. I used four instruments
instead, each seeing what the others cannot:

| Mechanism | What it catches | What it cannot see |
|---|---|---|
| **JVM unit tests** | Pure functions, the state machine, snapping and clamping maths | Compose lifecycle and phase semantics |
| **Device testing** | Gesture arbitration, feel, visible behaviour | Anything needing minutes of idling; performance |
| **Worktree compile check** | Whether every commit in the history builds on its own | Everything at run time |
| **Auditing the documentation** | **Divergence between the code and its own docs** | Anything the docs never claimed |

The first three were added as I went. The fourth emerged last and was the **surprise** of
the project.

**How auditing docs became a code review:** I ran two read-only subagents, one checking the
documents against the **source**, one against the **git history**. Neither was asked to
find bugs. Both were asked whether the documents were still *true*. The result:

- The code audit found that a written-down architecture commitment was contradicted by the
  source — it was hunting stale claims, and found one whose cause was a live regression.
- The history audit found that the writeup's "compromise" list omitted two items — it was
  checking the writeup for honesty, and the omission turned out to be covering two shipped
  bugs.

**Both times a code defect fell out of auditing the documentation — the opposite direction
from what I expected.** It works because the claims in the documents are **specific and
falsifiable**: line numbers, constants, "this many allocations per frame". A vague document
has nothing to refute, and auditing it would have produced nothing.

**I also didn't take the reports at face value.** Across 48 findings, one was wrong (it
claimed "18 Kotlin files" matched no count; `app/src/main` held exactly 18 at the time — it
had missed the three theme files). Every finding was re-verified by hand before being acted
on.

---

## 4. Where the AI went wrong

Grouped by **which review mechanism caught it**, because that illustrates the table above
better than severity would.

### Caught on the device

**4.1 It asserted a pointer-dispatch behaviour twice, and it was wrong.** In **two separate
specs** it claimed that Compose's child-first pointer dispatch would let a task bar win a
horizontal gesture over the ancestor `Modifier.scrollable`, so dragging a bar could never
pan the timeline. It labelled this "cannot be verified without hardware" both times — which
I now read as the tell: **labelling an assumption is not the same as not building on it.**
It built two features on it.

I installed it and dragged a bar. The whole view panned. The cause was not the dispatch
order: the bar and the ancestor scrollable measure the same touch slop against the same
accumulated x, so they cross on the *same* pointer event — and the loop left that crossing
event unconsumed while it went off to await the next one. One unconsumed change was enough
to hand over the gesture for the rest of the drag.

Fixed in two layers: consume the crossing event (the cause), plus disable the ancestor
scroller while editing (removing the race instead of winning it).

**4.2 A drag moved one grid step and stopped.** On the device, dragging continuously moved
the bar 15 minutes and no further. `positionChange()` reports `Offset.Zero` once the change
is consumed, and the loop consumed before reading.

I liked how this one was handled: when I pointed at the symptom, it said it recognised the
API behaviour but wasn't certain of it, and wrote a test to establish the contract *before*
changing anything — which is the "verify, don't recall" rule doing its job.
`PointerInputChange` turns out to be a plain Kotlin class with no Android dependencies, so
it can be constructed in a JVM test.

### I reported the symptom; its first diagnosis was wrong

**4.3** I reported that editing a bar a second time reverted it to its original time. Its
first answer was Activity recreation — rotation, `remember` not surviving. Confident,
detailed, and **not what I had described**; I hadn't rotated anything.

I pushed back with the precise symptom and it found the real cause: `pointerInput` was keyed
on `(task.id, pxPerMinute, barWidthPx, handlePx)` with the `Task` captured by the lambda but
**not in the key list**. A move doesn't change duration → the bar's width doesn't change →
no key changes → the suspend block never restarts → it keeps the `Task` captured at first
composition.

It then made a point I've kept: rather than adding `task` to the keys, change `beginDrag` to
take a `taskId` and resolve from the live list — making the bug **unrepresentable** rather
than merely absent. Worth noting too: the suite had a green test named "a second drag starts
from the committed position", green only because it re-read the list each time. The bug
lived in Compose's key semantics, which that test couldn't see from its angle.

### Caught by a unit test

**4.4 A test that asserted the wrong thing.** Its test asserted that edge auto-scroll moves
the viewport and the bar by equal amounts. It failed: 10 minutes against 15. The
*implementation* was right — with live snapping the bar steps in grid units and cannot track
continuously. The test was wrong. It became three tests pinning three properties: with
snapping off the deltas are exactly equal, with snapping on the drift stays under one grid
step, and at the clamp both stop together.

A win, not a miss — a failing test caught a bad assertion, which is what they're for. But it
is a reminder that **generated tests can encode a generated misunderstanding.**

### Caught by the worktree compile check

**4.5 A split into seven commits, most of which didn't compile.** It split one change by
concern. A worktree check (which its own rules require) showed most of them didn't build:
the API changes formed a mutual-dependency cluster across the package, and no file-level
split compiles step by step. It reset and reflowed into one compiling commit.

> To be precise: one of the seven was a docs commit, so it was **six code commits reflowed
> into one** (landing as two on `main` with the docs commit). And those seven were reset
> away — they exist only in my local reflog, which **neither a push nor a bundle carries**,
> so a reviewer cannot find them, and `32cfca4`'s message explains only *why one commit*,
> not this episode. This account is a record of the process, not something verifiable from
> the repository.

### Caught by the agent reviewing its own work

**4.6 A gesture that cancelled itself.** Found while fixing 4.2. `pointerInput` was keyed on
`barWidthPx`, which changes on the very first frame of a resize — restarting the suspend
block and killing the gesture that was setting it. The panning bug had been masking it. Same
root cause as 4.3: **values that move during a gesture do not belong in the key list.**

### Caught by the structural validators

**4.7** A full-width digit inside a hex colour (`#79839５`) that would have silently killed a
dark-mode token, and CSS classes used in a doc but never defined, which would have rendered
two sections unstyled. Both caught by validation scripts it ran before showing me the page —
and one of those scripts was itself wrong the first time, reporting a problem on the wrong
file, which it then corrected. (The hex digit was fixed pre-commit, so there is no git trace
of it; the CSS half is recorded in `2d0e1b8`'s message.)

### Caught by auditing the documentation

These three came from the last round, and this was the only one of the four mechanisms that
could have found them.

**4.8 One argument expression recomposing every lane, every frame.** Inside
`rememberUpdatedState(...)` an argument read
`laneOriginX = axis.xOf(start, state.viewportStartMinutes)` — arguments are evaluated
**during composition**, so every scroll frame invalidated every lane. It contradicts an
architecture commitment in `.claude/CLAUDE.md`, and both writeups list that commitment as
something the project got right.

The worst part is its own comment above it: "it has to be read fresh each frame rather than
captured at composition". It *is* captured at composition, and only stays fresh *because* of
the recomposition the project forbids. The comment refutes itself.

The irony: that holder existed to fix 4.6 — **fixing one Compose lifecycle problem created
another.**

**4.9 / 4.10 Two bugs that shipped because a spec was withdrawn.** The now marker's clock in
a draw lambda, and sample data in a default argument (section 2). Both were found by the
history audit noticing an omission in this writeup's own "compromise" list.

All three are fixed in spec 003. But **the criterion that matters most is still
unverified** — "lanes no longer recompose during a scroll" needs Layout Inspector. The
change is sound by construction, and "sound by construction" is exactly what was believed in
4.1.

### Where it struggled most, and why

**Gesture and pointer handling, by a wide margin.** 4.1, 4.2, 4.3, 4.6 and 4.8 are all the
same family.

My read: everything else in this task has a **cheaply checkable surface**. Layout and drawing
either look right or don't. Coordinate maths is pure functions and unit tests. But pointer
arbitration and Compose's phase semantics are a set of **runtime contracts between modifiers
that are siblings or ancestors of each other**: who consumes, on which pass, with which slop,
how long a `pointerInput` block lives, which expression lands in which phase. None of it
shows up in a type. None of it shows up in a unit test. It can write code that compiles,
passes 56 tests, reads sensibly, and is wrong — with no feedback signal telling it so.

That is also why its confidence was miscalibrated specifically here: appropriately hedged
about things it could check, quietly over-confident about the one area it couldn't.

**Which is exactly why I needed four review mechanisms rather than one.**

The second thing it struggled with was **knowing when to stop** (the performance spec, in
section 2).

---

## 5. My assessment of the final solution

Output: 32 commits, 19 Kotlin files (all of `app/src/main`), 56 JVM unit tests (one of them
the Android Studio template's), 19 Compose previews, 4 visual docs, 4 specs (one parked, one
further spec withdrawn earlier). The work was concentrated on 2026-10-10, about six hours in
a day, plus the audit and fixes on 10-11.

### What I think is good

- **`TimeAxis` is pure and imports no Compose.** Time↔pixel conversion, snapping and tick
  generation are plain functions, testable on the JVM with no device and no Compose test
  rule. Everything else hangs off them.
- **Scroll position lives in the time domain**, not as a pixel offset, so changing scale or
  zoom keeps the viewport's left edge pinned to the same instant for free.
- **The timeline doesn't use `Modifier.horizontalScroll`.** It would need a child as wide as
  the whole time range — about 553,000px at the DAY scale over four months. A shared
  `scrollable` keeps the content exactly one viewport wide. (The snap-chip row in the control
  bar does use `horizontalScroll`; that's a different thing.)
- **Snapping is anchored to the origin, and the origin is this week's Monday** — so the
  15-minute, daily and weekly grids all divide evenly into one grid.
- **Editing granularity follows visual granularity** — 15 minutes at DAY, a day at WEEK and
  MONTH, a week at YEAR.
- **The documentation is specific enough to be audited.** That wasn't the goal at the start,
  and it ended up finding three bugs.

### What is a compromise

- **"Scrolling causes no recomposition" was broken once, and the fix is not yet measured.**
  It's the design property I'd most want to point at, and a single argument expression
  silently broke it for several rounds. Fixed, but it needs Layout Inspector to count as
  verified.
- **There is no CI guard for that class of regression.** I declined the harness (the cost is
  recorded in spec 003): it is invisible to CI and can only be caught by review or audit. It
  has already happened once.
- **Edits don't survive Activity recreation.** Rotate and you're back to the sample data.
  `rememberSaveable` with a custom `Saver` would fix it, and I scoped it out. It is
  **deliberately absent from the list above**: in a project running sample data, scroll
  performance and interaction feel are what someone meets on every gesture, while rotation
  is occasional. For a real product with real data the priority would invert and this would
  come first.
- **Lanes are a `Column`, not a `LazyColumn`**, and every bar is composed regardless of
  horizontal visibility. Fine for six tasks, wrong for six hundred.
- **The tick list is rebuilt every frame, twice** (header and grid). Estimated as small, and
  deliberately left alone pending a real baseline — this is what spec 004 parks.
- **The edit lock uses two mechanisms where one should do.** Belt and braces, because the
  single-mechanism version is what failed on the device.
- **Spec 002 landed as one commit** rather than the six it deserved.

### What I'd do with more time

1. **Optimise scroll performance and close the remaining bugs.** This covers three things
   already written down but not done: open Layout Inspector and clear spec 003's unverified
   criterion that lanes no longer recompose during a scroll (the same session also yields
   spec 004's baseline for free); the four render-path items parked in spec 004 — the tick
   list rebuilt every frame in both canvases, labels re-measured, `Duration` allocated on
   the hot path; and verifying `fingerX` during edge auto-scroll when a bar is clipped
   outside the viewport, where the arithmetic is right by construction but the path was
   never exercised.

2. **Add animation.** Only two things animate today: navigation (the prev/today/next
   controls and the two-finger swipe move the viewport smoothly) and the discovery pulse on
   a bar's grips. Everything else is an instant jump — a bar snaps to the grid by teleporting
   onto it, changing scale replaces the whole view at once, zoom lands in one step. Those are
   the places a user touches on every interaction, and none of them has a transition. Snapping
   is the one I'd do first: letting a bar settle onto the grid line with a short spring reads
   as "it snapped" rather than "it jumped somewhere".

3. **Let the user reorder tasks by dragging vertically.** This was deliberately cut, not
   overlooked — spec 001's question 4 and spec 002 both ruled it out, on the grounds that
   rows carry no independent meaning so reordering would encode nothing. But that reason has
   a shelf life: as soon as a row stands for a person, a resource, or just the user's own
   priority, the order becomes data. The gesture slot is already free — a vertical drag on a
   bar currently falls through to the lane scroller, which is exactly the axis reordering
   would claim.
