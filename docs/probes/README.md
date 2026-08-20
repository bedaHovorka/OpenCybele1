# `docs/probes` — the Cybele runtime probes behind `INVENTORY.md` §12

Cybele ships as **source-less** 2002-era jars, so every runtime-semantics claim in
[`INVENTORY.md`](../INVENTORY.md) §12 (`SEM-01`…`SEM-06`) was settled by running these programs
against the real jars. They are committed so that #16, #20 and #29 can re-run them instead of
re-deriving the facts. Each probe is a standalone `main` in the **default package** and prints its
own verdict.

## Prerequisite: the vendor jars

The two IAI jars are untracked in git (`.gitignore: cybelle/*.jar`). A fresh clone must recover
them into the local Maven repository first, or nothing here runs:

```bash
scripts/bootstrap-vendor-jars.sh          # preferred; on opencybele-baseline, landed in #14
```

or by hand from the `withoutGradle` tag — see the repository `README.md`, "One-time setup".
`run.sh` checks for both artifacts and prints this same instruction if they are missing.

## Running

```bash
docs/probes/run.sh                 # compile everything, run nothing
docs/probes/run.sh ExpA            # compile, then run one probe
docs/probes/run.sh ExpA2 --control # ExpA2 with the SEM-01 positive-control config
docs/probes/run.sh Order           # iteration-order probe (no Cybele, util classes only)
docs/probes/run.sh --all           # every probe in turn
```

`run.sh` uses `$HOME`, not `~`, for the classpath entries: bash does **not** tilde-expand after a
`:` outside an assignment, so a hand-typed `-cp a:~/.m2/...` silently resolves to the literal
string and dies with `ClassNotFoundException: cybele.kernel.Cybele`.

It also copies `cybelle/cybele.prop` and `cybelle/ICS.prop` into a scratch `.work/cybelle/`
(git-ignored) before running. That copy is **not optional**: Cybele's kernel loads `/cybele.prop`
through `Properties.class.getResourceAsStream`, a `java.base` class, so since JPMS it resolves only
via `--patch-module java.base=<dir>` against a **plain directory on disk** — jar contents do not
work, and `docs/probes/` has no `cybelle/` of its own.

Environment used for the recorded results: OpenJDK 21.0.11 (Red Hat), Fedora 43, unmodified
`cybelle/cybele.prop` from `opencybele-baseline`. Cross-checked on OpenJDK 25.0.4 where noted.

## The probes

| File | Establishes | INVENTORY.md |
|---|---|---|
| `ExpA.java` | Event-queue discipline: 6 messages at mixed subscription priorities behind a blocking handler | `SEM-01`, `SEM-04` |
| `ExpA2.java` | Positive control for `SEM-01`: a TIMER event queued mid-burst, default vs. sorted config | `SEM-01` |
| `ExpB.java` | `pauseClock`/`resumeClock` return-value semantics; unknown-clock-id behaviour | `SEM-02` |
| `ExpB2.java` | Reentrancy: 2 pauses + 1 resume, HOST and LOCAL scope, 3 s settle window | `SEM-02` |
| `ExpB3.java` | Pause latency by polling — reports NEVER (a genuine lost registration, see below) | `SEM-02` |
| `ExpB4.java` | Pause latency bracketed with a fresh clock per trial; tight pause/resume loop | `SEM-02` |
| `ExpC.java` | Two simultaneous subscribers on one channel, three variants | `SEM-03` |
| `ExpE.java` | Negative / zero timer delays; `sendAll` to an unopened channel | `SEM-06` |
| `ExpF.java` | By-reference payload passing under `Local;NoSerialization` | `SEM-05` |
| `ExpG.java` | **Self-verdicting**: `Cybele.terminate()` calls `System.exit(0)` and never returns; a shutdown hook's `Runtime.halt(code)` still overrides that status; and the *second*, recoverable way a clock command is lost — see below. The three facts `RunControl` (#17) is built on. | `SEM-02`, `SEM-06`, issue #17 |
| `ExpH.java` | The two kernel facts #20's trace probe rests on beyond `SEM-03`: **H1** an earlier subscriber survives a later `openChannel` of the same name by another agent (so the probe can pre-open `TRAIN.STATE.<train>` before the train exists), **H2** `CybeleEvent.getClockTime()` is `-1` on a MESSAGE event and only populated for timer events (so a trace tick must come from `Cybele.getTime`), **H3** opening a channel nothing ever sends on is inert. | `SEM-03`, `SEM-05`, issue #20 |
| `Order.java` | Iteration order at the four hash-order sites that decide behaviour | `NDT-01`…`NDT-04` |
| `OrderLock.java` | **Self-verdicting** pin of those same orders: node/road creation order, every road's `(first, second)` endpoints, every station's candidate-edge order and all 56 origin/destination routes. Exits non-zero on any drift. Built against `ScenarioConfig.buildNet()`, so it locks the real production topology path. | `NDT-01`…`NDT-04`, issue #19 |

`Order.java` **dumps**, `OrderLock.java` **asserts**. The expected values in `OrderLock` were
recorded from the pre-#19 code, whose orders came from `HashMap`/`HashSet` iteration; #19 replaced
that with an explicit rule (`Util.stableOrder`). The probe is therefore the evidence that the new
rule reproduces the old order exactly rather than merely being deterministic — it passes on both
the pre-#19 and post-#19 trees, and fails on a naive `LinkedHashMap` conversion. See
`docs/iteration-order.md`.

To run it against the **pre-#19 tree**, compile this file unchanged against those sources: its one
reference to `Util.orderRank` (which does not exist there) goes through reflection and prints
`skip Util.orderRank absent` instead of failing to compile. Every order check still runs. That is
deliberate — a probe whose whole value is that it *can* fail is worth little if the claim "it also
passes before the change" cannot be re-run.

## Caveat 1 — the clock registration race (affects every `ExpB*`)

`Cybele.createClock` builds the `ContinuousClock` **synchronously** (so `getTime`/`isPaused` work at
once) but announces it with an **asynchronous** `Activity.sendAll("Cybele.TimerService.newClock",
…)`. Only when the kernel's `TimerAgent` handles that broadcast does it open the per-clock command
channel that `pauseClock`/`resumeClock`/`setClockPace`/`setTime` all funnel into.

If `createClock` runs **within ~3.4 ms of `Cybele.startUp()` returning**, the `TimerAgent` has not
yet subscribed, the announcement is silently dropped (`SEM-06`), and every subsequent
pause/resume/pace/setTime on that clock id is a **permanent silent no-op for the life of the JVM**,
while `getTime`/`isPaused` keep working — so the failure is invisible. Measured threshold: gap
≤ 3.4 ms lost in 19/23 runs; gap ≥ 3.5 ms landed in 66/66. Per-clock and permanent, identical on
JDK 21 and 25.

**Every `ExpB*` probe sits inside that window.** Treat any `ExpB*` result as valid only alongside a
measured `startUp` → `createClock` gap. To measure the *intended* semantics, sleep ≥ 5 ms after
`Cybele.startUp()` and before the first `createClock`. Do **not** "fix" `ExpB3`'s NEVER result by
changing its polling technique — the polling is not the cause.

`ExpB4` disagrees with `ExpB`/`ExpB3` for a reason that is not a semantic difference: evaluating the
runtime concatenation `"clk" + w` before its first `createClock` pays the
`invokedynamic`/`StringConcatFactory` bootstrap, worth about +3.5 ms, which pushes it just past the
window. The clock-name string itself is irrelevant. Both files are correct measurements of
different sides of the same 3.4 ms boundary.

The real application **was** immune, by accident: `new Gui(this); gui.setVisible(true)` three
lines above `RailwayMainAgent.java:111` put its gap at 159–384 ms (n = 40). Issue
[#17](https://github.com/bedaHovorka/OpenCybele1/issues/17)'s `sim.headless=true` removes exactly
that, and headless the gap measured **1.5–7 ms** across development builds, with the clock's
command channel dead in 8/8 runs of one build and 1/10 of another. On the *shipped* build with
the barrier deleted the gap is 2.9–30.9 ms and no failure was seen in 6 and 8 runs — enough class
loading now sits in front of `createClock` to hide it on this machine, which is the argument for
the barrier rather than against it: the gap moves by milliseconds for reasons as trivial as one
added `String`. The race still reproduces reliably at injected gaps of 3.0–5.0 ms. It is now
cleared deliberately, by a barrier in `Main` that waits for the timer service to demonstrate it
works, and checked afterwards on the simulation's own clock — a check that detected a poisoned
clock 14/14 under fault injection with 0 false passes in 40 randomised runs inside the danger zone
and 0 false fails in 22 runs under heavy load. See
[`../headless-and-stop.md`](../headless-and-stop.md).

**There is a second, distinct loss that is not this race, and it changes how an `ExpB*` result
should be read.** A command issued **immediately** after `createClock`, with no intervening
statement, is dropped **6/6 even for a clock created 400 ms after `startUp()`** — the per-clock
command channel is opened asynchronously as well, so the first command can fall into *that*
window. That loss is recoverable: re-sending the command lands it (measured, within ~25 ms),
whereas a lost *announcement* never recovers (400 re-sends over 2 s, still no). `ExpG` checks
both. So a probe reporting "the pause never landed" is not on its own evidence of the startup
race — check whether it re-sent.

## Caveat 2 — startup flakiness (`ExpA2`, `ExpE`)

Roughly **1 run in 6** dies during startup with

```
NullPointerException ... TimerAgent.register ... because "this.ag" is null
    at com.iai.cybele.timer.IAITimerService.newClock
```

This is the same startup race: a handler constructor calls `createClock` before the kernel's
timer-service agent is up. Re-run it; it is not a defect in the probe. A few ms of sleep after
`Cybele.startUp()` removes it (and closes the Caveat 1 race at the same time).

## Caveat 3 — the `SEM-01` control config line

The `ExpA2` positive control needs the event-queue comparator on the **agent** queue. That is
**neither** of the two commented-out lines in `cybele.prop`: line 66 puts `merge_sort` on the
*system* queue only, and the agent queue is what dispatches application messages. The exact line,
recorded verbatim so the control stays reproducible (`run.sh --control` appends it):

```
cybele.srv.evmgmt.app.param.iai = system_queue merge_sort staticpriority_comp;agent_queue merge_sort staticpriority_comp
```

## Caveat 4 — what `ExpE` does and does not show

`ExpE` shows only **that** a negative- or zero-delay timer fires within the observation window, not
how fast. The timing figures quoted in `INVENTORY.md` `TMR-04` (−1500 → 1–5 ms, 0 → 1–5 ms,
500 → 500–515 ms, 2000 → 2001–2010 ms) come from a separate timestamped measurement.
