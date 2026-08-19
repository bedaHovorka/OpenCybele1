# Headless mode, bounded runs, and exit codes

> Produced for [#17](https://github.com/bedaHovorka/OpenCybele1/issues/17)
> (*Headless mode: decouple the GUI from RailwayMainAgent, bounded stop, clean shutdown*),
> on the `opencybele-baseline` branch, which is where the goldens of
> [#24](https://github.com/bedaHovorka/OpenCybele1/issues/24) will be recorded.
> Consumed by [#12](https://github.com/bedaHovorka/OpenCybele1/issues/12)/[#13](https://github.com/bedaHovorka/OpenCybele1/issues/13)
> (the parity harness runs the simulation as a child process and reads its exit status),
> [#21](https://github.com/bedaHovorka/OpenCybele1/issues/21) (normalizer — the run now
> ends at a declared point rather than under `timeout`) and #24.
>
> **Headline: the default run is unchanged.** `./gradlew run` still opens the window, still
> never stops on its own, and still produces the same message sequence — measured, three runs
> per side at a fixed seed, byte-identical after normalisation. Everything below has to be
> asked for.

## The three keys

Added to the `sim.*` mechanism of [#18](https://github.com/bedaHovorka/OpenCybele1/issues/18);
`-D` beats a `-Dsim.config=<file>` entry beats the default, unknown `sim.*` keys are rejected at
startup, and the resolved values are printed to stderr. See [`scenario-config.md`](scenario-config.md).

| Key | Default | Meaning |
|---|---|---|
| `sim.headless` | `false` | skip the Swing window entirely |
| `sim.stop.maxTrains` | `0` (off) | **bound** — stop after this many trains have been *generated* |
| `sim.stop.maxClockMs` | `0` (off) | **bound** — stop this many simulated ms past `sim.clock.startMs` |
| `sim.stop.wallClockMs` | `0` (off) | **failure** — safety net, in real ms |
| `sim.stop.stallMs` | `0` (off) | **failure** — no train generated for this many real ms |

`scenarios/short-bounded.properties` is a worked example: `short.properties` plus a pinned seed,
a simulated-time bound, a safety net and a stall guard, running headless. It ends on its own —
no `timeout` in front of it.

```bash
OPENCYBELE_OPTS=-Dsim.config=scenarios/short-bounded.properties \
  build/install/opencybele/bin/opencybele ; echo $?
```

## Exit codes

Every one of these was verified by observing `$?`, not by reading the source.

| Code | Meaning | How it was produced |
|---|---|---|
| `0` | a declared bound was reached, or the GUI window was closed | `-Dsim.stop.maxTrains=3`; `-Dsim.stop.maxClockMs=3000`; closing the window |
| `1` | configuration or startup error, raised on the main thread before the kernel starts | `-Dsim.stop.typo=5`; `-Djava.awt.headless=true` without `-Dsim.headless=true` |
| `3` | `sim.stop.wallClockMs` fired — **the run did not finish** | `-Dsim.stop.wallClockMs=4000` on an unbounded scenario |
| `4` | `sim.stop.stallMs` fired — train generation stopped | `-Dsim.stop.stallMs=500` with the default 1000 ms first fire |
| `5` | the simulation clock does not answer its command channel | reproduced by removing the barrier (see below), 8/8 runs in one build |

**A timeout must never look like a pass**, which is the whole reason 3 and 4 exist. It is also why
they had to be engineered rather than returned:

* **A throwable inside a Cybele handler does not fail the run.** The kernel invokes agent
  constructors and handlers reflectively, wraps what they throw in an
  `InvocationTargetException`, prints it to stderr and leaves the exit status alone
  ([`assertion-triage.md`](assertion-triage.md), Result 3). Code that detects a failure inside an
  agent has to end the process itself.
* **`Cybele.terminate()` calls `System.exit(0)` and never returns.** Measured: a probe that
  printed a line immediately after `terminate()` never printed it, and the process exited `0`.
  So an exit code cannot be set after terminating the kernel.
* Therefore `RunControl` installs a **shutdown hook** that calls `Runtime.halt(code)` with the
  intended status. It runs while the JVM is already shutting down, so it overrides the kernel's
  `0`. Verified end to end: the same probe with the hook in place exited `42`.

`Gui`'s `EXIT_ON_CLOSE` is unchanged, but the `WindowListener` that was commented out in 2008 is
now live and routes through the same shutdown path, so closing the window flushes the trace and
terminates the kernel instead of dropping the process on the floor. The status stays `0`.

## Why a wall-clock timeout is not the only failure worth its own code

`sim.stop.stallMs` exists because of a specific, measured hazard: a throwable inside
`Generator.generateTrain` *before* `Activity.setTimer` re-arms the generator stops train
generation **permanently**, while the process stays alive, keeps its clock, keeps painting, and
exits green. `maxClockMs` would still be reached; `wallClockMs` would never fire. Only "nothing
has been generated for a while" catches it, and it must be a failure rather than a clean stop.

Note the stall window is measured from kernel start until the first train, so it must exceed
`sim.arrival.firstFireMs` converted to real time (divide by `sim.clock.pace`) plus a few
inter-arrival times. `scenarios/short-bounded.properties` uses 10 s against a ~62 ms expected
spacing.

## The landmine: a headless run creates its clock inside a kernel race

This is the part of #17 that is not about the GUI at all.

`Cybele.createClock` builds the `ContinuousClock` **synchronously** — `getTime()` and
`isPaused()` work immediately — but announces it with an **asynchronous**
`Activity.sendAll("Cybele.TimerService.newClock", …)`. Only when the kernel's `TimerAgent`
handles that broadcast does it open the per-clock command channel that `pauseClock`,
`resumeClock`, `setPace` and `setTime` all funnel into. A `sendAll` to a channel nobody has
subscribed to yet is **silently dropped** (`INVENTORY` SEM-06).

`RailwayMainAgent` built the Swing window three lines above its `createClock`, and that put the
`startUp()` → `createClock` gap at **159–384 ms** — 45x to 110x the ~3.4 ms race window. The
application was immune **by accident**, and `sim.headless=true` removes exactly the thing that
was clearing the race.

### Measured, on this tree

`startUp()` → `createClock` gap, headless, with the barrier removed:

| build | gap | outcome |
|---|---|---|
| headless, no barrier, no instrumentation in front of the check | ~1.5–2 ms (inferred) | clock control dead **8/8** |
| headless, no barrier, gap string built before the check | 3.11–5.69 ms | clock control dead **1/10** |
| headless, no barrier, final build (`RunControl` also on the startup path) | 2.92–30.9 ms | dead **0/6** |
| headless, **with** the barrier | 27.7–307 ms | dead **0/12**, and 0/9 in a second series |
| GUI, with the barrier | 156–458 ms | dead **0/40** |

The three no-barrier rows are the point: the *only* difference between them is how much
unrelated code happens to sit on the startup path — one extra `String` concatenation moved the
failure rate from 8/8 to 1/10, and loading one more class moved the gap to 2.9–30.9 ms and hid it
entirely in six runs. A margin that a `String` can move is not a margin, and "it passes on my
machine today" is not evidence about it.

Two distinct failure modes were separated, both fatal, neither loud:

* **gap ≈ 1 ms** — `createClock` itself throws
  `NullPointerException … TimerAgent.register … because "this.ag" is null` (6/6 runs). The timer
  *service* object exists; its agent does not. In `RailwayMainAgent`'s constructor that throwable
  is swallowed by the kernel, so the run continues with **no clock at all**.
* **gap ≈ 3 ms** — no throw, but the announcement is lost. `pauseClock` returns `true`,
  `isPaused()` stays `false`, and the clock keeps advancing, for the life of the JVM. Re-sending
  does not recover it: 400 re-sends over 2 s, still `false`. `Gui`'s pace toolbar goes through
  the same channel, so in GUI mode a lost registration would silently break the pace buttons too.

### The barrier: wait for the thing, do not sleep

`Thread.sleep(1)` was measured sufficient. It is also a constant tuned on one machine for a race
whose whole nature is that it is invisible when it loses. `RunControl.awaitTimerService()`, called
from `Main` between `Cybele.startUp()` and `Cybele.createAgent`, instead waits for the timer
service to *demonstrate* that it works:

1. create a **throwaway** clock;
2. ask it to pause, and wait for `isPaused()` to agree;
3. if it never agrees within 250 ms, discard that clock id and try a fresh one — a clock whose
   registration was lost is permanently dead, and re-creating it under the **same id** does not
   help (measured: a clock whose first `createClock` threw was still dead when re-created 16 ms
   later, and 40 fresh ids were needed in one variant before one landed);
4. `createClock` throwing the `TimerAgent.register` NPE is caught and retried after 2 ms rather
   than propagated — that throw *is* the "not ready yet" signal.

Cost: **12.8–13.6 ms and one probe clock in 10 of 12 runs**; two runs needed a second probe clock
and 265 ms. The resulting gap for the real clock is 27.7–307 ms, 8x to 90x clear of the window.

`isPaused()` is the verdict rather than "did the clock stop advancing", because it is the one
signal measured to separate the two cases: `pauseClock` returns `true` whether or not the command
landed, and an advancement test needs a window long enough for the configured `sim.clock.pace` to
be visible.

### One more thing the probes had not separated

A `pauseClock` issued **immediately** after `createClock`, with no intervening statement, is lost
**6/6 even for a clock created 400 ms after `startUp()`**, where no startup race exists at all.
The same call with a single `println` in front of it landed 6/6 within ~25 ms. The per-clock
command channel is opened asynchronously too, so the *first* command can fall into that window
and be dropped on its own — a second, distinct loss from the announcement loss above, and a
recoverable one.

So the check re-sends the command while it waits. Re-sending is safe: pause and resume are a
boolean flag, not a counter (`INVENTORY` SEM-02), so N pauses are undone by one resume — and a
burst of 5 pauses followed by one resume left the clock running for 3 s, 3/3.

This one is worth reading twice by anyone re-running the `ExpB*` probes, because it means an
`ExpB*`-style "the pause never landed" result can be produced with no race present at all.

## The regression check

The registration is **per clock**, so clearing the barrier is necessary but not sufficient.
`RunControl.verifyClockControl(CLOCK_ID, …)` is called in `RailwayMainAgent`'s constructor
**between `createClock` and the `resumeClock` that was already there**:

```java
Cybele.createClock(CLOCK_ID, Cybele.HOST, config.getClockStartMs(), config.getClockPace());
RunControl.verifyClockControl(CLOCK_ID, config.getClockStartMs());   // pause -> confirm -> resume -> confirm -> settle
Cybele.resumeClock(CLOCK_ID);                                        // unchanged
```

It pauses the clock, waits for `isPaused()` to confirm, resumes it, waits for that to confirm, and
then watches for 100 ms that it *stays* running. At that point the clock carries no timers and no
other agent exists, so pausing it is inert; the `resumeClock` the code already performed is the
other half of the round trip. On failure it prints the gap, the diagnosis and exits **5** — it
cannot throw, because the kernel would swallow that.

It runs in **both** GUI and headless mode, deliberately: the same code path, the same cost, no
mode-specific behaviour to reason about. Total added startup cost, measured: ~10–16 ms for the
round trip plus the 100 ms settle window.

Without this check a lost registration is invisible. `getTime()` keeps advancing, trains keep
being generated, the canvas keeps painting — while `Planning`'s pause/resume bracket, every
`setTimer` and the toolbar are all no-ops. Every golden recorded afterwards would be wrong with no
symptom, which is the one failure a golden-master harness cannot survive.

## What headless mode does and does not change

`RailwayMainAgent` still `extends Observable`, still registers its inner `TableModel` in the
constructor, and still calls `notifyObservers()` on every state change. In headless mode
`RailwayCanvas` is simply never constructed, so nothing else subscribes. No observer wiring was
removed; there is one less observer.

The eager check in `Main` is the other half: a JVM that is headless (`-Djava.awt.headless=true`,
or no display) while `sim.headless=false` is **rejected before the kernel starts**, with a message
naming the flag. Left alone, `new Gui(…)` would throw `HeadlessException` inside the agent
constructor, the kernel would swallow it, and the process would sit there with no main agent, no
clock and a perfectly clean exit status.

An unbounded headless run — no window to close, no `sim.stop.*` — prints a warning to stderr. It
is a legitimate thing to ask for, but it is a run with no way to end itself.

## Evidence: GUI mode is unchanged

Acceptance criterion 5 asks for a before/after trace comparison. With `sim.random.masterSeed`
([#15](https://github.com/bedaHovorka/OpenCybele1/issues/15)) this is finally an *equality* test
rather than a distributional one — subject to the load ceiling, which is a property of the
machine as much as of the scenario.

**Sub-ceiling comparison** (`-Dsim.arrival.lambdaMs=2000 -Dsim.station.voteWindowMs=2000
-Dsim.arrival.firstFireMs=200 -Dsim.clock.pace=8 -Dsim.random.masterSeed=20080415`, GUI mode,
15 s, 3 runs per side, `601a2f1` against this tree), normalised per
[`seeded-rng.md`](seeded-rng.md) — timestamps projected away, departures and starts compared as
two independent streams, compared over the common prefix:

* each side produced **exactly one** distinct normalised trace over its three runs;
* the two sides' traces are **identical**: 20 departures and 20 starts, same trains, same
  stations, same order.

**At `short.properties` density on this machine** the run sits at the ceiling — the *baseline*
alone produced 12 distinct normalised traces over 26 runs — so equality is not available there
and neither side has it. What is available, and matches:

| | baseline (`601a2f1`) | this tree |
|---|---|---|
| GUI runs, 15 s, seed `20080415` | 26 | 37 |
| departures | 20–27, median 27 | 22–27, median 27 |
| interleaved before/after pairs (8) | 22, 26, 26, 27, 24, 24, 27, 27 | 23, 26, 27, 27, 23, 26, 26, 27 |
| under saturating CPU load (3 pairs) | 27, 27, 26 | 26, 26, 26 |
| hard stalls | 0 | 0 |
| one 30 s pair, normalised | 54 departures + 54 starts | **byte-identical to the baseline** |

Three early runs in this series looked like hard stalls at 4 departures. They were not: the stop
banner recorded `reason = GUI window closed` at 2.3 s, i.e. the desktop closed the window. The
diagnosis is only possible *because* of the banner this issue added — before it, those runs were
indistinguishable from a hung simulation, and the old `EXIT_ON_CLOSE` path would have truncated
the trace just as silently.

## Where the probes are

`docs/probes/ExpG.java` — `RunControl`'s three load-bearing kernel facts, self-verdicting:
`terminate()` exits 0 and never returns; a shutdown hook can still force the status; and the two
command-loss modes above. Run it with `docs/probes/run.sh ExpG`. Every `ExpB*` caveat in
[`probes/README.md`](probes/README.md) applies.
