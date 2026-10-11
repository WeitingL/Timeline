# Timeline — working agreement

## The workflow is gated. Do not skip a gate.

Four gates. Each one needs the user's word before the next begins. "No objection" is
not approval; silence is not approval.

```
1. SCOPE      user confirms what needs to be done
                        ↓  (user confirms)
2. SPEC       write ai-cowork/NNN-<slug>.md
                        ↓
3. DISCUSS    build/update the visual doc under docs/ and walk through it
                        ↓  (user grants permission)
4. IMPLEMENT  write the code, build it, run the tests
                        ↓  (user verifies, usually on a device)
5. COMMIT     only now: git commit, then push
```

### Gate 1 — scope

Do not start writing a spec from an under-specified request. Ask about the forks that
would change the work, state the assumptions for everything else, and get the scope
confirmed. Do not ask about things that have a conventional default — decide those and
say what was decided.

### Gate 2 — the spec

One file per piece of work: `ai-cowork/NNN-<slug>.md`. Keep the numbers **contiguous**: if
a spec is withdrawn before any code ships, renumber the ones after it so the folder reads
1, 2, 3 with no gaps. (Superseding an earlier rule that said never to reuse a number — a
gap turned out to look like a lost file, which is worse than a renumber.) Whenever a spec
is renumbered, its visual doc, both writeups and every cross-reference move with it.

Required sections, in this order:

- **Description** — what this does, and what it explicitly does not do
- **Acceptance Criteria** — checkable, grouped by concern
- **Current Logic** — the facts of the code it attaches to, with `file.kt:line`
  references **verified against the working tree**, never recalled
- **Expected Code Change** — the intended change with its reasoning, not just its shape
- **Questions** — the open decisions that are the user's to make, with a recommendation
  and the trade-off behind each

A spec is allowed to argue against itself. If part of the work is not worth doing, say so
in the Questions section rather than quietly building all of it.

When the work is finished, append a **Resolution** section recording how each question
was answered and what was deliberately left out.

### Gate 3 — the visual doc

The spec is for precision; the visual doc is for the conversation. Build or extend a page
under `docs/`, then walk the user through it. Do not move to implementation off the back
of the markdown alone.

Rules for these pages:

- **One self-contained `.html` file.** No CDN, no web fonts, no external requests of any
  kind — it has to open offline and survive being emailed as a single attachment. Verify
  this, do not assume it.
- **Diagrams carry the explanation, prose only captions it.** Inline SVG or CSS. If a
  section is a wall of text, it has not been designed yet.
- Draw the mechanism, not the feature list. The pages that earned their place so far are
  the ones showing *why* a design is the way it is: what each phase costs, what a wrong
  key captures, what two adjacent pixels do differently.
- Light and dark both defined through tokens on `:root`; readable at 320dp.
- One page per subject. Extend the existing page when the subject is the same; a third
  page about the same thing is sprawl.
- Run a structural check before showing it: every class used is styled, every CSS
  variable declared, every SVG marker referenced exists, tags balanced, zero external
  resources. A page with silently unstyled sections is a broken deliverable.

Current pages: `docs/architecture.html` (framework), `docs/gesture-layer.html` (gestures),
`docs/edit-lock.html` (edit lock and scale-derived snapping).

After implementation, update the same page so it describes what was actually built.

### Gate 4 — implement

Build and run the tests before reporting. `./gradlew :app:assembleDebug
:app:testDebugUnitTest`. Report real output: if something fails, show it; if a step was
skipped, say which.

### Gate 5 — commit

**Never commit or push before the user has verified the work and said to go ahead.**
Finishing the code is not the trigger. A green build is not the trigger. The user's word
is the trigger — usually after they have run it on a device.

When it is time:

- Split by concern, in dependency order, so every commit compiles on its own. Verify that
  with a worktree when the sequence is long.
- The message body carries the **reasoning**, not a restatement of the diff. The git log
  is a reviewed deliverable for this project; someone will read it to understand the
  decisions.
- Record what is still unverified rather than letting it hide inside a confident message.

---

## Project conventions

**Language.** Replies in the terminal: 繁體中文. Everything written to a file — code,
comments, KDoc, specs, commit messages, the HTML docs' prose: English. Exception: UI
strings and sample data are 繁體中文 because the app is.

**Verify, do not recall.** Before citing a line number, an API signature, or a dependency
version, read the file. Before claiming a behaviour, build it or test it. When something
cannot be verified without hardware — pointer dispatch order, gesture arbitration, scroll
direction — say so plainly in the spec, the commit message and the report, and do not let
it pass as settled.

**Correct the record.** When a diagnosis turns out to be wrong, say which part was wrong
and move on. Do not let a superseded claim stand in a doc or a commit message.

**Architecture commitments already made.** Changing one of these is a Gate 1
conversation, not an implementation detail:

- `TimeAxis` is pure and imports no Compose, so it is unit-testable on the JVM.
- Scroll position lives in the time domain (`viewportStartMinutes`), not in pixels.
- Horizontal scrolling uses `Modifier.scrollable` with viewport-sized content, never
  `Modifier.horizontalScroll`.
- Scrolling must not cause recomposition: the header and grid read the viewport in the
  draw phase, bar positions in the layout phase.
- Snapping is anchored to `origin`, never to the dragged task.
- `SchedulerState` is a plain `@Stable` class, not a ViewModel.
- An in-flight edit lives in a separate draft, never written into `tasks` per frame.

**Tests.** Anything expressible as a pure function gets a JVM unit test. Prefer making a
bug unrepresentable (change the signature) over making it absent (add a key). When a bug
slips past the suite, say what angle the suite could not see it from.

**Previews.** `ui/SchedulerPreviews.kt` covers the four scales, zoom extremes, the
in-flight drag states, and the awkward form factors (320dp, tablet, 1.5× font). New UI
states get a preview.
