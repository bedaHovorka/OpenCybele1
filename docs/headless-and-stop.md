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
> **Headline: the default run's *message sequence* is unchanged.** `./gradlew run` still opens
> the window, still never stops on its own, and produces the same trains in the same order —
> measured, three runs per side at a fixed seed, identical under the normalisation named in
> "Evidence" below, and reproduced independently over 9/9 pairs. Every `sim.*` key here has to
> be asked for.
>
> **Two things are not conditional, and one of them is visible.** The kernel-readiness barrier
> and the clock-control check run on every start, in both modes, because they guard a failure
> that has no symptom rather than a configuration. Their cost — 12.7–274 ms for the barrier plus
> ~110–135 ms for the check — runs with the clock going, so **every absolute simulated-clock
> value the run prints shifts by roughly `sim.clock.pace × 100` ms** against the pre-#17
> baseline. Trace equality across this boundary therefore depends on #21's normalizer projecting
> timestamps away. See "Evidence: GUI mode is unchanged".

## The keys

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

`scenarios/short-bounded.properties` is a worked example: a pinned seed, a simulated-time bound,
a safety net and a stall guard, running headless. It ends on its own — no `timeout` in front of
it. It is deliberately **not** `short.properties` with a bound bolted on: at that density this
machine is above the load ceiling, and three runs of that file bounded at 115 s of simulated time
produced two distinct traces.

> **Correction (#16) — the observation stands, the *ceiling* reading does not.** Two runs of
> `short.properties` genuinely can differ; what does not follow is that a density threshold is the
> reason. Measured headless: 114 consecutive runs at the same seed gave **one** departure-id
> sequence, and a later session gave a second sequence in 7 of 9 runs — the same binary, the same
> files, differing by `vl107/vl315/vl326` against `vl112/vl319/vl333`. So the drop is stochastic
> and strongly autocorrelated within a session, with no cliff in density; a re-measurement at ~69
> departures gave pairwise id-set differences of 0, 1 and 1. Three further effects inflate a
> *whole-trace* difference count with no ceiling involved: ~19 %-per-site `started`-line tie flips,
> `timeout` cutting the tail at an arbitrary wall instant, and `:0` window closes that read as
> clean short runs. **Keep `lambda = 2000`** — a lower rate is a genuinely lower drop probability —
> but note that no arrival rate removes the hazard, and the real guard is repeating a recording
> and comparing departure-id sets. See
> [`kernel-config.md`](kernel-config.md#supersedes-the-load-ceiling-claim-in-headless-and-stopmd). The shipped file runs at `lambda = 2000` instead, and three runs of
it produce exit 0, 21 departures, 66 trains generated, and a **byte-identical departure stream**
(the `started` stream differs by one transposition of two trains departing at the same instant —
the pre-existing tie artifact, see "Evidence" below).

```bash
OPENCYBELE_OPTS=-Dsim.config=scenarios/short-bounded.properties \
  build/install/opencybele/bin/opencybele ; echo $?
```

## Exit codes

Every one of these was verified by observing `$?`, not by reading the source.

| Code | Meaning | How it was produced |
|---|---|---|
| `0` | a declared bound was reached, or the GUI window of an **unbounded** run was closed | `-Dsim.stop.maxTrains=3`; `-Dsim.stop.maxClockMs=3000`; `wmctrl -c` on a run with no `sim.stop.*` |
| `1` | configuration or startup error, raised on the main thread before the kernel starts | `-Dsim.stop.typo=5`; `-Djava.awt.headless=true` without `-Dsim.headless=true` |
| `2` | the GUI window was closed while a `sim.stop.*` bound was armed and unreached — **the run did not finish** | `wmctrl -c` on a run with `-Dsim.stop.maxClockMs=900000` |
| `3` | `sim.stop.wallClockMs` fired — **the run did not finish** | `-Dsim.stop.wallClockMs=4000` on an unbounded scenario |
| `4` | `sim.stop.stallMs` fired — train generation stopped | `-Dsim.stop.stallMs=500` with the default 1000 ms first fire |
| `5` | the simulation clock does not answer its command channel | barrier removed and a controlled gap injected; also 14/14 under two independent clock-poisoning injections |
| `255` | **not this code's** — a throwable escaping `RailwayMainAgent`'s constructor | injecting a throw at its `createClock` line: exits 255 in ~0.26 s, 3/3, stack on stderr, no stop banner |

**A run that did not finish must never look like a pass**, which is the whole reason 2, 3 and 4
exist. Code `2` is the late addition: `RunControl.stop(EXIT_OK, "GUI window closed")` was
unconditional, so a GUI run that declared `maxClockMs` and was closed at 2.3 s by a desktop, a
session manager or a stray click exited `0` — "a declared bound was reached". That is exactly the
principle codes 3 and 4 are engineered around, broken by the new path itself, and it is not
hypothetical: the three window-closed runs recorded further down all exited `0`, and it happened
again for real during review when a parallel agent closed the wrong window. Closing the window of
an **unbounded** interactive run is still `0` — that is how you end one.

These had to be engineered rather than returned:

* **A throwable inside a Cybele handler does not fail the run.** The kernel invokes handlers
  reflectively, wraps what they throw in an `InvocationTargetException`, prints it to stderr and
  leaves the exit status alone ([`assertion-triage.md`](assertion-triage.md), Result 3). Code that
  detects a failure inside an agent has to end the process itself.
* **Agent *construction* is a different path, and it is loud.** An earlier revision of this
  document said the `createClock` NPE inside `RailwayMainAgent`'s constructor "is swallowed, so
  the run continues with no clock at all". That is **wrong**. Injecting a throw at that exact line
  exits the JVM with **255** in about 0.26 s — 3/3 here, four configurations independently — with
  the stack trace on stderr, no stop banner, and this class' exit code never set. The direction is
  benign; the status is not in anyone's table, so [#12](https://github.com/bedaHovorka/OpenCybele1/issues/12)/[#13](https://github.com/bedaHovorka/OpenCybele1/issues/13)
  need it here. `assertion-triage.md` already warns that "Cybele swallows any Throwable" is not a
  safe general rule; this is a concrete instance of that warning.
* **`Cybele.terminate()` calls `System.exit(0)` and never returns.** Measured: a probe that
  printed a line immediately after `terminate()` never printed it, and the process exited `0`.
  So an exit code cannot be set after terminating the kernel.
* Therefore `RunControl` installs a **shutdown hook** that calls `Runtime.halt(code)` with the
  intended status. It runs while the JVM is already shutting down, so it overrides the kernel's
  `0`. Verified end to end: the same probe with the hook in place exited `42`.

`Gui`'s `EXIT_ON_CLOSE` is unchanged, but the `WindowListener` that was commented out in 2008 is
now live and routes through the same shutdown path, so closing the window prints a stop banner,
terminates the kernel, and produces `2` rather than `0` when a bound was armed.

It does **not** exist to flush the trace, and an earlier revision of this document claiming the
old path "would have truncated the trace just as silently" was wrong: every trace line is a
`System.out.println` on an autoflushing stream, so `System.exit` never truncated anything. The
gains are the banner, the kernel terminate, and the honest exit status.

## What a bound pins, and what it does not

| bound | exactness | measured |
|---|---|---|
| `sim.stop.maxTrains` | **exact** — enforced inside `Generator`, which simply does not re-arm its timer | N = 1, 3, 5, 7, 12 all produced exactly N generated |
| `sim.stop.maxClockMs` | **at or just past** — the watchdog polls every 10 ms, so it fires at the bound plus up to one poll × pace | +40 to +72 simulated ms at pace 8 over three runs of `short-bounded` |

**Neither bound pins the departure tail, and neither is a trace-length bound.** `Generator` calls
`RunControl.trainGenerated` *after* it has already published the train to `PLAN_TRAIN`, and `stop`
then ends the process promptly, so trains that are planned but have not yet departed simply never
print. `-Dsim.stop.maxTrains=5` on `short.properties` generated exactly 5 trains and printed **2**
departures, 3/3; `scenarios/short-bounded.properties` generates 66 and departs 21. "Exit 0 with
exactly N departures" is a **race, not a property** — an earlier report of this PR claimed it and
was wrong. A golden must compare the departure stream per train id and tolerate the last line or
two, which is what [#21](https://github.com/bedaHovorka/OpenCybele1/issues/21)'s normalizer is for.

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
| headless, no barrier, early development build | ~1.5–2 ms (inferred) | clock control dead **8/8** |
| headless, no barrier, one `String` concatenation added ahead of the check | 3.11–5.69 ms | dead **1/10** |
| headless, no barrier, **the shipped build** with `awaitTimerService()` deleted | 2.92–30.9 ms / 3.04–7.59 ms | dead **0/6** / **0/8** |
| headless, **with** the barrier | 27.7–307 ms | dead **0/12**, and 0/9 in a second series |
| GUI, with the barrier | 156–458 ms | dead **0/40** |

**Read the first row as history, not as the headline.** It was produced by a build that no longer
exists. On the **shipped build with the barrier deleted**, two independent series — 0/6 and 0/8 —
saw no failure at all: enough class loading now sits between `startUp()` and `createClock` to push
the gap past the window most of the time on this machine. To reproduce the race on the shipped
build at all, the tester had to inject a controlled gap; it then reproduces reliably at gaps of
3.0–5.0 ms.

That is not an argument for removing the barrier, it is the argument for keeping it. The *only*
difference between those three no-barrier rows is how much unrelated code happens to sit on the
startup path: one added `String` concatenation moved the failure rate from 8/8 to 1/10, and one
more loaded class hid it entirely. A margin that a `String` can move is not a margin, and "it
passes on my machine today" is not evidence about it — the next commit to `Main` is a coin flip.

The check itself was attacked directly rather than incidentally, and held:

* **14/14** detections across two independent fault injections, including a harness that poisons
  the clock id *inside* the registration race so the real `RailwayMainAgent` path gets a genuinely
  dead clock — 6/6 exit 5 there.
* **0 false passes in 40 randomised runs** with the gap forced into the 2.8–7.8 ms danger zone,
  against an independent post-verdict oracle that would have halted 90 had the check passed on a
  dead clock.
* **0 false fails in 22 runs under heavy load** (32× and 96× `yes`, load average peaking 40.9):
  neither the 250 ms probe budget nor the 100 ms settle window tripped.
* Re-sending does not mask a dead clock: ~400 `pauseClock` commands over the full 2000 ms budget
  to a genuinely dead clock still exited 5.

Two distinct failure modes were separated, both fatal, neither loud:

* **gap ≈ 1 ms** — `createClock` itself throws
  `NullPointerException … TimerAgent.register … because "this.ag" is null` (6/6 runs). The timer
  *service* object exists; its agent does not. In `RailwayMainAgent`'s constructor this does **not**
  leave a running simulation behind: measured, the JVM exits **255** with the stack trace on stderr
  and no stop banner. Loud, but with a status no table named until this one. Note the consequence
  for the check below: it is the statement immediately *after* `createClock`, so **it structurally
  cannot catch this mode** — it never runs.
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
   later);
4. any throwable out of `createClock` is caught — **wholesale, not matched on type or message**,
   which survives a kernel that fails differently — and retried after 2 ms, because that throw
   *is* the "not ready yet" signal. The last one caught is kept and printed with its stack if the
   barrier gives up, so a failure that is *not* the known startup NPE cannot be spent silently and
   then misattributed;
5. the whole loop is bounded by a **30 s deadline**, not by an attempt count. An attempt count has
   to be set to the worst case somebody once saw on one machine — which is the same mistake as
   sleeping a constant, and `EXIT_CLOCK_CONTROL_DEAD` is the one code that must never cry wolf,
   since #24 reads it as "every golden after this is worthless". An earlier revision used 40
   attempts, and justified it with "40 fresh ids were needed in one variant" — that observation
   was an artifact of a since-fixed bug in which the probe sent its `pauseClock` exactly once, so
   all 40 attempts burnt their full 250 ms budget (≈10 s) without ever being able to succeed. It
   was never evidence that 40 probes are needed.

Cost, measured unloaded over two series of 12 runs: **12.7–274.2 ms**, one probe clock in 7–8 of
12 runs, two in 4, three in 1. Under load: 16.5–308 ms and up to four probes. So roughly a third
of runs pay ~265–275 ms rather than ~13 ms — same shape as first reported, worse numbers. The
resulting gap for the real clock is 27.7–307 ms, 8x to 90x clear of the window.

`isPaused()` is the verdict rather than "did the clock stop advancing", because it is the one
signal measured to separate the two cases: `pauseClock` returns `true` whether or not the command
landed, and an advancement test needs a window long enough for the configured `sim.clock.pace` to
be visible.

Two facts settled from the vendor bytecode during review, both load-bearing:

* **`ContinuousClock.<init>` sets `paused = false`.** A clock is created *running*. Had clocks
  started paused, `pauseLands` would have returned `true` on its first poll and both the barrier
  and the check would have been silent no-ops that always passed. The whole design rests on this.
* **No class in either Cybele jar calls `addShutdownHook`.** So `Runtime.halt` in our hook skips
  nothing of the kernel's own — see the note for #21/#24 at the end of this document.

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

**It covers the silent mode only.** An earlier revision of this document presented it as guarding
the invisible-clock class in general; it cannot. It is the statement immediately after
`createClock` in the same constructor, so the throwing mode never reaches it — that one is covered
by the JVM exiting 255, which needs no check because it is impossible to miss.

**And "nothing extra is sent to the clock the simulation runs on" — also an earlier claim here —
is false.** At a 5 ms poll and a 107–135 ms round trip it issues roughly **20–27 `pauseClock`
commands and a `resumeClock`** to the real clock, then watches it for 100 ms. What is true is that
this is *inert*: criterion 5 below measures no change to the message sequence, and the
`pauseLands` Javadoc in `RunControl` explains why re-sending is both necessary and safe.

It runs in **both** GUI and headless mode, deliberately: the same code path, the same cost, no
mode-specific behaviour to reason about. Total added startup cost, measured: ~10–16 ms for the
round trip plus the 100 ms settle window, on top of the barrier's 12.7–274 ms.

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
constructor and the JVM would exit 255 — loud, but with a status no harness has a row for, and a
message about AWT rather than about the flag that fixes it.

An unbounded headless run — no window to close, no `sim.stop.*` — prints a warning to stderr. It
is a legitimate thing to ask for, but it is a run with no way to end itself.

The GUI-topology mismatch banner from [#18](https://github.com/bedaHovorka/OpenCybele1/issues/18)
is now suppressed in headless mode: "the canvas cannot draw this network" is noise when there is
no canvas, and stderr is part of the captured trace (see the note to #21 below).

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
  stations, same order;
* reproduced independently in review: 9/9 baseline × new pairs identical over the common prefix.

**Say which normalisation that depends on.** Projecting the `at <t>` field away is *not* enough on
its own, and the claim above is not a claim about raw stdout. Two trains planned for the same
simulated millisecond print their `<train> started` lines in whichever order the scheduler picks;
with timestamp-projection alone the tester saw two distinct traces on the new side (`vl12`/`vl13`
transposed). That artifact is pre-existing — it is present on the baseline too and
[`seeded-rng.md`](seeded-rng.md) documents it — but the equality above holds under the
normalisation it names: **departures and starts split into two independent streams, timestamps
projected, compared over the common prefix**, which is the shape #21 has to build anyway.

**And "the default run is unchanged" is a claim about the message *sequence*, not about every
byte.** The barrier and the clock check are **unconditional** — they run with no `sim.*` key set,
in GUI mode, on the default scenario. `settlesRunning` always burns its full 100 ms with the clock
*running*, before the `Generator` activity exists, so every absolute simulated-clock value printed
by `Planning.java:125` shifts by roughly `sim.clock.pace × 100` ms against the pre-#17 baseline —
about 800 ms at pace 8. The equality evidence above normalises timestamps away, which is precisely
the field this moves. So: **the message sequence is unchanged; raw departure timestamps are not,
and trace equality across the #17 boundary depends on #21's normalizer projecting them.** That is
a dependency, not a headline.

**At `short.properties` density on this machine** the run sits at the ceiling — the *baseline*
alone produced 12 distinct normalised traces over 26 runs — so equality is not available there
and neither side has it.

> **Correction (#16).** Those 26 runs were taken on the shared display `:0`, where a window close
> ends a run silently with an unchanged exit status, and were compared as whole normalised traces
> rather than per train id. Both inflate the count. Compared per departure id and headless,
> `short.properties` gives 2 distinct sequences over 123 runs rather than 12 over 26 — still not
> reproducible enough to record a golden against unrepeated, but the 12 are mostly `started`-line
> tie flips and tail truncation, not distinct simulations. See
> [`kernel-config.md`](kernel-config.md#supersedes-the-load-ceiling-claim-in-headless-and-stopmd). What is available, and matches:

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
indistinguishable from a hung simulation. (They would not have been *truncated*, though: an
earlier revision said the old `EXIT_ON_CLOSE` path "would have truncated the trace just as
silently", which is wrong — `System.exit` does not truncate an autoflushing `PrintStream`.) All
three exited **0**. Under the exit table above they would now exit **2**, which is the whole
reason that code exists.

## Notes for #21 and #24

* **stderr is part of the trace.** [`TESTING.md`](TESTING.md) has the harness capture the child
  with `redirectErrorStream(true)`, and this issue adds the first stderr lines that *vary per run*:
  the barrier's elapsed time and probe count, the clock check's round-trip and gap, and the stop
  banner. Every one of them is prefixed `--- ` or `!!! `, and `Main`'s existing configuration and
  randomness banners already use `--- `. **Dropping lines that begin with `--- ` or `!!! ` removes
  all of it**, and leaves the simulation trace (`<train> in <station> at <t>` and
  `<train> started`) untouched, since no trace line has a prefix.
* **`Runtime.halt` is only safe while ours is the only shutdown hook.** It skips every other hook
  and every remaining buffer. Today that is fine: no class in either Cybele jar calls
  `addShutdownHook` (checked in the bytecode), the trace is `System.out.println` on an autoflushing
  stream, and the hook flushes both streams before halting. If #21 or #24 later adds a file-backed
  trace writer with its own shutdown hook, `halt` will truncate it — flush that writer from
  `RunControl.stop` before `terminateKernel()`, or make the hook ordering explicit.
* **Do not compare `timeout`-truncated runs across the #17 boundary.** The barrier plus the settle
  window cost ~120–400 ms of startup, which costs about one departure in a wall-clock-truncated
  window (21 against 22, 3/3). There is no message-sequence change, but the offset is systematic.
  One more reason to bound a golden by simulated time or train count rather than by wall clock.
* **`sim.stop.stallMs` is a wall-clock budget guarding a pace-scaled spacing.** Size it against
  `sim.arrival.lambdaMs / sim.clock.pace`. It is meant for headless runs: `Gui`'s toolbar can drop
  the pace to 0.3 mid-run, multiplying real inter-arrival spacing by 3.3× (~28 s between trains at
  the default lambda), and any `stallMs` sized for the configured pace then fires spuriously. The
  barrier's own cost is *not* charged to it — `lastTrainNanos` is re-stamped when the barrier
  clears and again when the clock check passes.

## Where the probes are

`docs/probes/ExpG.java` — `RunControl`'s three load-bearing kernel facts, self-verdicting:
`terminate()` exits 0 and never returns; a shutdown hook can still force the status; and the two
command-loss modes above. Run it with `docs/probes/run.sh ExpG`. Every `ExpB*` caveat in
[`probes/README.md`](probes/README.md) applies.
