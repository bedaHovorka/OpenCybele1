# Cybele kernel configuration: what is pinned, what was measured, what was left alone

Issue [#16](https://github.com/bedaHovorka/OpenCybele1/issues/16). Phase 1, `1-PRE`.

`cybelle/cybele.prop` and `cybelle/ICS.prop` are the last determinism levers that live outside
application code. This document records what the kernel actually does with them, what changing
them does to a run, and the four decisions that came out of that.

**Decisions, up front.**

| # | Lever | Decision | Why |
|---|---|---|---|
| 1 | Event-queue sort/compare | **keep the effective default (FIFO, no sorting)**, now written down explicitly instead of relying on an undocumented fallback | a sorted agent queue is a *behaviour change* (timers overtake messages) and still does not make the trace reproducible |
| 2 | Thread pool `5 10 4000 3 5` | **keep unchanged** | shrinking it does not reduce trace variance, and below 3 threads the simulation stops working at all |
| 3 | `ICS.prop` `ICSBrowser` | **external IAI host removed**, one entry left (`127.0.0.1`) | it was already the losing key; removing it closes a latent startup hang |
| 4 | `cybele.srv.comm.app.param.iai = Local;NoSerialization` | **must stay set** | without it startup aborts, verified |

No behaviour change is introduced by any of them, and the standing of that claim differs by
lever, which is worth being precise about rather than averaging into "measured":

* **Lever 1 is *proved*, in bytecode.** `IAIEventManagement.start` has an explicit else-branch for
  a null `appParam` that assigns `defaultStrategy`/`defaultComparatorId` into all four of
  `sysSortStrategy`, `sysComparator`, `agentQueueSortStrategy` and `agentQueueComparator`, and
  those defaults are whatever the first `addStrategy`/`addComparator` call set — the first `_sort`
  and `_comp` ids in the sys param, `no_sort` and `no_comp`. The committed line assigns those same
  four values. The runs are corroboration, not the argument.
* **Levers 2, 3 and 4 are *measured* or *observed*** — the pool comparison, the single-key
  `ICS.prop` start, and the abort with the comm line removed.

The re-verification after [#17](https://github.com/bedaHovorka/OpenCybele1/issues/17) landed
headless mode and the startup barrier is in [Re-verified under #17](#re-verified-under-17). Note
that its `p = 1.000` results are **not evidence of equivalence** — n = 12 per arm is underpowered
for that, and no reading of a non-significant p-value would be. They are consistency checks; the
no-op standing of lever 1 rests on the bytecode above.

References to `INVENTORY.md` (`SEM-…`, `DEF-…`, `GUI-…`, `CFG-…`) are to the inventory on the
`jade-develop` branch, which this baseline branch does not carry: `git show
origin/jade-develop:docs/INVENTORY.md`. The runnable probes it cites *are* on this branch, at
[`docs/probes/`](probes/).

---

## The operating point

Every number in this document comes from the same point, chosen so that it sits **below the load
ceiling** described in [`seeded-rng.md`](seeded-rng.md#the-load-ceiling) — above that ceiling the
`START` race (`INVENTORY` DEF-02) drops trains and no variance can be attributed to the kernel
config.

```
scenarios/short.properties         # pace 8, lambda 500 ms, voteWindow 500 ms
-Dsim.random.masterSeed=20080415   # #15; a fixed seed, otherwise you measure RNG spread
timeout 30                         # no stop condition exists on this branch
comparison window: simulated departure time <= 220 000 ms  (= 51 departures)
```

Two things about the harness matter more than they look.

**The comparison window is in *simulated* time, not lines.** A run is killed by `timeout` at an
arbitrary wall instant, so its last few lines are a coin toss. Truncating at a fixed simulated
instant removes that entirely and is why the tail does not contribute noise here.

**Runs were executed on a private, headless X display, not on `:0`.** The first sweep ran on the
desktop's `:0` and produced departure-id sets differing by 5–10 ids between runs of the *same*
config — which reads exactly like a large interleaving effect and is not one. `RailwayMainAgent`
constructs the `Gui` unconditionally (`INVENTORY` GUI-03), the frame is `EXIT_ON_CLOSE`, and
anything on the shared desktop that closes or disturbs it ends the run silently and without
changing the exit status. Repeating the identical sweep on an isolated display collapsed that
variance to **zero**. Any variance measurement of this program taken on a shared display should be
assumed contaminated.

```bash
weston --backend=headless --xwayland --socket=wl-probe --width=1280 --height=1024 &
#   -> "xserver listening on display :1"
DISPLAY=:1 OPENCYBELE_OPTS="-Dsim.config=scenarios/short.properties \
  -Dsim.random.masterSeed=20080415" timeout 30 build/install/opencybele/bin/opencybele
```

> **Superseded for new work.** This is how the sweeps below were taken, before #17 existed.
> #17 added a real headless mode — `-Dsim.headless=true -Djava.awt.headless=true` — which removes
> the frame rather than hiding it, so nothing needs `weston` any more. See
> [Re-verified under #17](#re-verified-under-17). The `:0` finding still stands for any GUI run.

`OPENCYBELE_OPTS=` is required — `-D` flags passed positionally to the start script are silently
ignored. And a throwable inside a Cybele handler is swallowed to stderr without changing the exit
status (`README.md`, "Assertions"), so every run below was judged by scanning **stdout and
stderr** for `AssertionError` / `Exception in thread "`, never by `$?`. Across all 146
measurement runs recorded for this issue — 101 pre-#17 and 45 after the rebase — that count was 0.
(Four further runs were deliberate misconfigurations, used to establish the exit-255 partial-edit
hazard in §1 and the exit-1 abort in §4; they are not part of any statistic here.)

### The operating point — what the departure sequence does, and what it does not

Pooled over all 90 complete runs of the main sweep, in nine kernel configurations including the
one this branch commits, there is exactly **one** departure-id sequence: the same 51 trains,
departing in the same order, every time. The same held for the 24 headless runs of the #17
re-verification: one sequence, 24/24.

> **Correction, and it is mine.** An earlier revision of this document read that as "the departure
> sequence is invariant at this operating point". **It is not, and the claim was over-reached from
> a large but single-session sample.** Re-running the identical binary against the identical files
> in a later session gave **two** sequences in 9 runs: 7 produced a variant departing
> `vl107, vl315, vl326, vl410` where the canonical departs `vl112, vl319, vl333`, and 2 reproduced
> the canonical exactly. The variant appears equally under the **unmodified pre-#16** `cybele.prop`,
> so it is neither caused by nor related to anything this branch changes.
>
> `vl107` against `vl112` is the *same* divergence [`seeded-rng.md`](seeded-rng.md#the-load-ceiling)
> names at 45 s and [`headless-and-stop.md`](headless-and-stop.md) names at 115 s. So this is the
> documented `INVENTORY` DEF-02 drop — `START` published to a channel the `Train` may not have
> opened yet — and not a new phenomenon.

The honest statement is therefore:

* **Within a session the outcome is strongly autocorrelated.** 114 consecutive runs resolved the
  race identically; a later session resolved it the other way 7 times in 9. Whatever fixes it is
  a machine-state property that persists for hours, not something that re-rolls per run.
* **Across sessions it is not reproducible.** A golden recorded at this operating point and
  replayed a day later can differ by three or four train ids with nothing at all having changed.
* **The comparisons in this document survive it, by construction.** Every sweep was round-robin,
  interleaving the arms run for run within one session, so a session-level bias is *shared* by the
  arms being compared and cannot manufacture a difference between them. This is the single reason
  the queue and pool conclusions still stand, and it is why the ordering is stated rather than
  assumed. What does **not** survive is any claim that this scenario is fit to record a golden
  against without repetition.

That last point is the one for #24, and it agrees with the recording procedure already proposed
there: repeat each recording and compare departure-id sets. One run is not a golden here.

Two failure modes were excluded from the variance statistics and counted separately:

| kind | how it looks | seen |
|---|---|---|
| **hang** | process lives the full 30 s but stops emitting departures partway | `base` 1/24, `pool_5_5` 1/6; 0 in the other six viable configs |
| **starvation** | never reaches a steady state at all | `pool_2_2` 4/6, `pool_1_1` 3/3 |

The hang is the pre-existing `INVENTORY` DEF-22 defect (`Planning`'s unbounded `latch.await()`),
not a config effect — it occurs at the stock configuration. Its measured rate here is **2 hangs
in 90 attempts across the eight viable configurations (~2 %)**, worth knowing before #24 records
goldens from this scenario: roughly one recording in forty-five will silently stop early, look
successful, and exit 0. #17's `sim.stop.stallMs` does **not** convert these into an exit 4 —
[see below](#the-def-22-hang-does-not-surface-as-exit-4--a-coverage-gap-for-24) for why, and for
the positive control showing the detector is nonetheless armed and working.

### Supersedes the load-ceiling claim in `headless-and-stop.md`

[`headless-and-stop.md`](headless-and-stop.md) still carried two statements that this measurement
contradicts, and both are now annotated there with a pointer back here:

* §`short-bounded.properties` — *"at that density this machine is above the load ceiling, and three
  runs of that file bounded at 115 s of simulated time produced two distinct traces"*;
* §Evidence — *"the baseline alone produced 12 distinct normalised traces over 26 runs"*.

Both were over-stated, but so was the counter-claim in the first revision of this section, and the
correction above is what settles it: **`short.properties` is not reproducible across sessions
either.** What is wrong in `headless-and-stop.md` is the *interpretation*, not the observation.
Two runs of that file genuinely can differ; what does not follow is that a **density ceiling** is
the reason, or that `lambda = 500` sits above one.

Three further effects inflate a *whole-trace* difference count without any ceiling being involved,
and each is a finding from this issue:

1. **`started`-line tie flips**, ~19 % per tie site over ~5 sites per run. A run with five sites
   flips at least one about half the time, so *whole-trace* comparison across a handful of runs
   almost always reports differences even when the simulation is bit-identical.
2. **Tail truncation.** `timeout` cuts at an arbitrary wall instant, so the last one or two lines
   are a coin toss. Comparing whole traces charges that to the simulation.
3. **`:0` window closes.** On a shared display a disturbed frame ends the run silently with an
   unchanged exit status, and the short run then reads as a clean one.

None of the three touches the departure-id sequence, which is why comparing **per train id** and
**headless** gives far fewer differences than comparing normalised traces on `:0`: 12 distinct
traces over 26 runs there, against 2 distinct id-sequences over 123 runs here.

A re-measurement by the coordinator, at ~69 departures, gave pairwise id-set differences of 0, 1
and 1 over three headless runs, the single difference being `vl550` missing from one run entirely
with the generator stream identical (same maximum id `vl663`). Combined with the session effect
above, the resulting picture is:

> **A stochastic DEF-02 drop with no cliff, whose outcome is stable for long stretches.** Density
> raises the rate; it does not switch anything on. Two runs minutes apart will usually agree; two
> runs hours apart may not.

Consequence for #17 and #24. `scenarios/short-bounded.properties` was detuned from `lambda = 500`
to `lambda = 2000` on the strength of a ceiling that does not exist as described — but the caution
was **not** misplaced, and this correction is not a licence to raise it back. A lower rate is a
genuinely lower drop probability, and the hazard the file must guard against is "a train can be
dropped at any density", which no arrival rate eliminates. The lever that actually closes it is
the one #24 already proposes: record more than once and compare departure-id sets. If anyone does
revisit the rate, the test is repeated runs **in separate sessions**, not three runs back to back
— that is precisely the sampling error corrected above.

---

## Re-verified under #17

Everything above was measured on the tree at `601a2f1`, before
[#17](https://github.com/bedaHovorka/OpenCybele1/issues/17) landed headless mode, the startup
barrier and the bounded stop condition. #17 changes startup materially, so the two conclusions
were re-taken on the rebased tree — headless, bounded, no `weston`, no `timeout` wrapper:

```bash
OPENCYBELE_OPTS="-Dsim.config=$PWD/scenarios/short.properties -Dsim.random.masterSeed=20080415 \
  -Dsim.headless=true -Djava.awt.headless=true \
  -Dsim.stop.maxClockMs=230000 -Dsim.stop.wallClockMs=60000 -Dsim.stop.stallMs=10000" \
  build/install/opencybele/bin/opencybele ; echo $?
```

n = 12 per arm, round-robin, comparing the **pre-#16** kernel config (both selector lines
commented out, two `ICSBrowser` keys) against the **committed** one. All 24 runs exited `0`, all
reached the bound, none produced an `AssertionError` or an `Exception in thread "`.

| | n | departure-id sequences | minority tie observations | runs with no flip |
|---|---|---|---|---|
| pre-#16 config | 12 | **1** | 10/48 = 20.8 % | 5/12 |
| committed config | 12 | **1** | 9/48 = 18.8 % | 6/12 |

Pooled across both arms there is exactly **one** departure-id sequence for these 24 runs, and the
two arms are indistinguishable on tie flips: Fisher two-sided **p = 1.000** site-level and
**p = 1.000** run-level. Both conclusions hold. (That single sequence is a within-session
statement; see [the correction above](#the-operating-point--what-the-departure-sequence-does-and-what-it-does-not).
It does not weaken the comparison, because the arms were interleaved run for run and any session
bias is shared.)

**#17 shifts the simulated timeline by ~960 ms and changes nothing else.** Comparing a pre-#17 GUI
run with a post-#17 headless run at the same seed: the same trains depart in the same order,
**0 mismatches across all 52 shared positions**, with every departure displaced by a median of
+960 simulated ms (range +640…+968). That is the barrier plus clock-settle cost — the smoke run
reported a 118.98 ms pause/resume round trip, and 118.98 ms × pace 8 ≈ 952 simulated ms. The only
consequence for the fixed 220 000 ms comparison window used above is that its last two departures,
`vl40` and `vl41`, now fall past the cut: 51 departures become 49, and because `vl40`/`vl41` were
one of the five tie pairs, five tie instants become four. Nothing about the simulation changed.

**The timestamp spread is run-to-run, not only across the #17 boundary.** The ~960 ms figure is
the *systematic* part; on top of it each run jitters. Pre-#17, across 23 `base` runs, every tie
instant moved over a 48 simulated-ms window (e.g. `vl12`/`vl13` at 77 336–77 384). Under #17 the
barrier adds its own variable cost and the window widens: review measured the same tie at
78 232–78 320, an 88 simulated-ms spread over six runs at an identical seed and configuration. So
a golden must project departure timestamps away rather than compare them, and must do so *within*
a build as well as across the #17 boundary.

`weston` is no longer needed. `-Dsim.headless=true` (which requires `-Djava.awt.headless=true`, or
startup exits `1`) removes the frame entirely, and with it the whole class of interference that
cost the first sweep. The `:0` warning above is kept because it explains why that sweep was
discarded and still applies to any GUI run.

### The DEF-22 hang does **not** surface as exit 4 — a coverage gap for #24

`sim.stop.stallMs` did not fire on any of the hangs, because **by construction it cannot see
them.** The chain, checked in the source rather than inferred:

* `lastTrainNanos` — the only clock the stall detector reads — is written in three startup places
  (`RunControl.java:168`, `:260`, `:358`) and in `RunControl.trainGenerated(long)` (`:443`).
* `trainGenerated` has exactly **one** caller in the tree: `Generator.java:84`.
* `Generator.generateTrain` publishes `PLAN_TRAIN` with a fire-and-forget `Activity.sendAll` and
  then re-arms its own timer. It never waits on `Planning`. (It re-arms *conditionally*, only if
  `trainGenerated` returns `true`, i.e. unless `sim.stop.maxTrains` has been reached — immaterial
  at `maxTrains = 0`, which is the recipe here, but a run that sets `maxTrains` ends by design at
  that point and the stall clock stops being stamped for a legitimate reason.)
* DEF-22 blocks `Planning`'s activity on an unbounded `latch.await()` — and `Planning` and
  `Generator` are **two activities of the same agent** (`RailwayMainAgent.java:122-123`), so the
  question is whether one can head-of-line-block the other. **`INVENTORY` SEM-04 does not answer
  this**: it establishes seriality *within* one activity, and #9 explicitly left the same-agent,
  two-activity case undetermined. Citing it here would be a false generalisation. The answer comes
  from two bytecode facts instead:
  * `Agent.createActivity(String, String, Object[])` — the overload `RailwayMainAgent` uses —
    delegates to the six-argument form with `iconst_4, iconst_0, iconst_0`, so the concurrency
    relation is `ConcurManagement.CONCURRENT` (`= 0`), not `SERIALIZED` (`= 1`).
  * `IAIConcurManagement.getRunnable` takes a `listIterator()` over the queued nodes and loops,
    returning the **first node whose `IAIActNode.isRunnable()` is true** and skipping the rest. It
    does not peek only at the head.

  So a blocked `Planning` node is passed over rather than blocking the queue, and `Generator`'s
  node is still dispatched. That, not SEM-04, is why generation survives a DEF-22 wedge.

So a DEF-22 wedge leaves train **generation** running at full rate. `lastTrainNanos` keeps being
re-stamped, `stallMs` never fires, the run reaches `maxClockMs` and **exits `0` with a silently
truncated departure stream** — the exact shape "a run that did not finish must never look like a
pass" is meant to exclude.

This is not a defect in #17: `headless-and-stop.md` states plainly that `stallMs` exists for a
throwable inside `Generator.generateTrain`, and it does that job. It is a **coverage gap**, and it
matters for #24 because the failure it misses is the one measured at ~2 % per run.

Evidence on both sides, so the claim is not one-sided:

* **Positive control — the detector does work when generation dies.** The `pool_2_2` configuration
  wedges the whole kernel for want of threads. Eight bounded headless runs: **6 wedged, 6/6 exited
  `4`** with `sim.stop.stallMs exceeded: no train generated for 10000 ms` after 3–44 trains; the
  other 2 ran clean to the bound with the same 52 departures and 457 trains generated as base. So
  the mechanism is armed and working, and when generation stops it is caught immediately.
* **Negative — no DEF-22 wedge was reproduced under #17.** 36 healthy-pool headless runs (12 + 12
  at `lambda = 500`, 12 more at a deliberately denser `lambda = 250`, all exit `0`) produced none.
  At the pre-#17 base rate of 2/90 that is ~0.8 expected, so **their absence here is evidence of
  nothing** and is reported only so the sample is not mistaken for a clean bill of health. The
  claim above rests on the code path, not on this sample.

What does catch it is comparing departure streams across repeated recordings — the procedure #24
already proposes. A cheap belt-and-braces alternative would be a *departure*-progress bound
alongside the generation one; that is #17's or #24's call, not this issue's.

---

## 1. Event-queue ordering

### The effective default (acceptance criterion 1) — verified, not re-derived

Both selector lines in `cybele.prop` are commented out:

```
#cybele.srv.evmgmt.app.param.iai = system_queue no_sort no_comp;agent_queue no_sort no_comp
#cybele.srv.evmgmt.app.param.iai = system_queue merge_sort staticpriority_comp;agent_queue no_sort no_comp
```

`INVENTORY.md` SEM-01 (issue #9) established empirically that the effective default is **strict
FIFO, no sorting, priorities ignored**. Re-run here on this branch, unchanged:

```
$ docs/probes/run.sh ExpA2            $ docs/probes/run.sh ExpA2 --control
  MSG 1                                 TIMER          <- jumps the queue
  MSG 2                                 MSG 1
  TIMER   <- holds FIFO position        MSG 2
  MSG 3                                 MSG 3
  MSG 4                                 MSG 4
```

The mechanism, re-checked in the bytecode — and stated precisely, because the imprecise version
hides a live hazard. `IAIEventManagement.start(sysParam, appParam)` branches on `appParam == null`.
With the selector lines commented out it takes the **else-branch, which explicitly assigns**
`defaultStrategy` and `defaultComparatorId` into all four of `sysSortStrategy`, `sysComparator`,
`agentQueueSortStrategy` and `agentQueueComparator`. They are *assigned*, not "left at" or "kept
as" anything, and those defaults are whatever the first `addStrategy`/`addComparator` call set —
the first `_sort` and `_comp` ids in `cybele.srv.evmgmt.sys.param.iai`, i.e. `no_sort` and
`no_comp`. `NoSortStrategy.sort(List, Comparator)` disassembles to a single `return`.

The distinction matters because the **other** branch assigns only the clauses actually named, and
leaves anything unnamed `null` — there is no per-field fallback. So a half-finished edit of a live
selector line does not quietly revert to the default; it aborts startup with
`CybeleRuntimeException: No default sorting strategy and comparator specified in the system
properties` and **exit 255**. Verified three ways here: dropping the `system_queue` clause, a
`_sort` with no `_comp`, and a `_comp` with no `_sort`. Now that `cybele.prop:65` is live, both
queues and both ids must always be named; the file says so at the line.

The positive control is the load-bearing half: it proves the probe *can* see a re-ordering, which
is what makes the negative result evidence rather than absence of evidence. The control line is
**neither** commented-out line — `cybele.prop:66` sorts the *system* queue, and application
messages go through the *agent* queue. It is recorded verbatim in
[`docs/probes/README.md`](probes/README.md).

### What a sorted queue can and cannot do

`CybeleEvent` sets `priority = 4` in its field initialiser and `8` in the two constructors that
build `TIMER` and `INTERNAL` events. There is a `getPriority()` and **no setter**;
`IAIEventNode`'s constructor assigns `staticPriority = event.getPriority()`, overwriting whatever
the subscription asked for, so the `int priority` argument to `Activity.openChannel` never reaches
the comparator.

Consequence: **every application message ties at priority 4.** `staticpriority_comp` can only
lift timers above messages; it cannot discriminate between two application messages, which is
where the interleaving actually lives. #9 verified this directly (ExpA's output is unchanged under
the control config).

### Measurement (acceptance criterion 2)

Two sorted configurations were measured against the stock one, at the operating point above.

| config | appended line | n complete |
|---|---|---|
| `base` | *(none — both selector lines commented out)* | 23 |
| `qsort_both` | `… = system_queue merge_sort staticpriority_comp;agent_queue merge_sort staticpriority_comp` | 24 |
| `qsort_sysonly` | `… = system_queue merge_sort staticpriority_comp;agent_queue no_sort no_comp` (this is `cybele.prop:66`) | 6 |

(Appending a duplicate key works because the kernel loads `cybele.prop` with
`java.util.Properties.load`, which is last-one-wins. The same technique is what `run.sh --control`
uses.)

**Result 1 — nothing moves the simulation.** All three configurations produce the same 51-train
departure sequence, and the same as every thread-pool configuration as well. Symmetric difference
of departure-id sets versus `base`: **0**, in every cell. These arms were interleaved run for run
inside one session, so this is a *between-config* result and is unaffected by the session-level
drift [corrected above](#the-operating-point--what-the-departure-sequence-does-and-what-it-does-not)
— that drift moves every arm together or not at all.

**Result 2 — the only thing that moves is the `<train> started` line at a departure-instant tie.**
This run has exactly five instants where two trains are planned for the same simulated
millisecond:

```
77344: vl12 vl13      87344: vl14 vl15      195344: vl34 vl35
200344: vl36 vl37     219344: vl40 vl41
```

`Planning.placeTrainIntoFirstStation` prints both `… in …` lines serially from one activity — that
order never varies — then sends each train its `START`; the two `Train.start` handlers run on two
different worker threads and print in whichever order the scheduler picks. This is the phenomenon
`seeded-rng.md` already documents; the tie sites give it a sharp, countable form.

| config | n | minority tie observations | runs with **no** flip |
|---|---|---|---|
| `base` | 23 | 22/115 = 19.1 % | 7/23 |
| `qsort_both` | 24 | 10/120 = **8.3 %** | 14/24 |
| `qsort_sysonly` | 6 | 5/30 = 16.7 % | 2/6 |

Sorting *both* queues roughly halves the flip rate — **in one half of the sample, and not in the
other.** The raw per-run flip counts, in run order, so any of this can be redone:

```
base        r1..r24 (r6 hung, excluded):  1 1 2 4 2 . 1 0 1 0 1 1 0 1 1 0 0 0 1 1 0 1 2 1
qsort_both  r1..r24:                      0 0 0 0 1 0 0 0 1 0 0 1 1 1 0 1 1 0 1 1 1 0 0 0
```

**Run ordering.** The two arms were interleaved **run for run**, never in blocks: every rep ran
one `base` then one `qsort_both` back to back, ~30 s apart. So a monotone drift in machine state
cannot produce a between-arm difference — it is shared by construction. That was true of the
whole original sweep, which was rep-major round-robin over all seven configs.

**But the sample is not homogeneous, and that turns out to matter more than the arm.** It was
taken in two phases with different inter-run spacing: `r1–r8` cycled through all seven configs
(~3.5 min between successive `base` runs), `r9–r24` cycled through only these two (~1 min). Split
on that boundary:

| phase | `base` mean flips | `qsort_both` mean flips | permutation p |
|---|---|---|---|
| `r1–r8` (7-config cycle) | 1.57 (n = 7) | 0.12 (n = 8) | **0.006** |
| `r9–r24` (2-config cycle) | 0.69 (n = 16) | 0.56 (n = 16) | **0.748** |
| pooled | 0.96 (n = 23) | 0.42 (n = 24) | 0.018 |

**The entire effect lives in the smaller, earlier phase, and disappears in the larger, tighter
one.** A pooled p of 0.018 over two regimes that disagree this sharply is not one effect measured
twice; it is Simpson's-paradox-shaped and should not be quoted on its own.

For completeness, the pooled tests on the counts — which use the run as the independent unit, as
they should, and lose less information than dichotomising into "any flip":

* permutation on the difference of means, 200 000 resamples: **p = 0.018**
* Mann-Whitney U (tie-corrected normal approximation): U = 373.0, z = −2.284, **p = 0.022**
* the earlier dichotomised version — 7/23 clean against 14/24, Fisher **p = 0.080** — is the same
  data with the counts thrown away, which is why it is weaker; it is kept here only because an
  earlier revision quoted it
* the site-level figure, 22/115 against 10/120, Fisher **p = 0.022**, treats the five tie sites in
  a run as five independent trials. They are not: they are five samples of one JVM under one
  scheduling regime, so its effective n is nearer the number of runs than the number of sites

**Multiplicity.** `base` is the reference arm for eight comparisons in this document — two queue
arms, five pool arms and the explicit-default arm. One nominal p ≈ 0.02 out of eight is close to
what pure noise produces; a Bonferroni threshold here would be 0.006. Combined with the phase
split above, the responsible reading is that **the halving may not exist at all.**

**The rejection does not rest on either p-value.** Suppose the effect is entirely real and the
halving reproduces exactly. It would still be the wrong thing to adopt, because **10 of 24 sorted
runs still flip at least one tie**: the trace is not reproducible under the sorted queue either, so
a golden still needs a normalizer that canonicalises order within an equal-departure group, and
that normalizer makes the remaining 8.3 % as irrelevant as the original 19.1 %. The setting buys a
smaller number of a thing that has to be handled anyway, and charges a behaviour change for it.
That argument holds whether p is 0.02, 0.08 or 1.00.

Sorting only the system queue (the line at `cybele.prop:66`) does nothing measurable, as expected:
application messages do not go through the system queue.

### Decision: keep FIFO — and write it down

Adopting `qsort_both` would buy a halving of a nuisance that a golden-master normalizer has to
handle anyway — ties survive it at 8.3 %, so it changes the frequency of a defect and not its
existence — and it would cost a **behaviour change**: under `staticpriority_comp` a queued `TIMER`
overtakes queued `MESSAGE`s, demonstrated by `ExpA2`. Under the [scope guard](Phase1.md#L7) that
has to be surfaced, not absorbed — and there is no benefit here worth surfacing it for. Rejected,
on the size and nature of the effect rather than on its statistical significance.

What *was* changed is the honesty of the file. The strategy is now selected explicitly:

```
cybele.srv.evmgmt.app.param.iai = system_queue no_sort no_comp;agent_queue no_sort no_comp
```

This is `cybele.prop:65` uncommented, and it names exactly the pair the fallback was already
choosing. It removes the dependence on an undocumented first-id fallback inside a source-less jar,
and it makes the intent readable at the point of configuration rather than only in this file.
It was measured like any other config change before adoption.

### Adopting the explicit default

`explicit_default` is `cybele.prop:65` uncommented, nothing else.

| config | n complete | departure-id sequence | minority tie observations | runs with no flip |
|---|---|---|---|---|
| `base` (both lines commented out) | 23 | *the* sequence | 22/115 = 19.1 % | 7/23 |
| `explicit_default` | 12 | identical | 9/60 = 15.0 % | 4/12 |
| `qsort_both` (rejected) | 24 | identical | 10/120 = 8.3 % | 14/24 |

**The adoption rests on the bytecode, not on this table.** The else-branch above assigns those
four fields the same four values this line assigns, so the change is a *provable* no-op; #9's
`ExpA2` run against a staged pre-#16 `cybele.prop` with both selectors commented out gives
**byte-identical output**, which is the direct confirmation. The runs below are a sanity check
that nothing else moved, and are reported as such: `base` vs `explicit_default` gives Fisher
two-sided **p = 0.54** site-level and **p = 1.00** run-level, with 12/12 runs complete and no
hang. A non-significant p is never evidence of equivalence and n = 12 could not establish it if it
were; what makes this safe is the branch, not the p-value. Adopted.

The change is annotated in `cybele.prop` itself, including the reason not to enable the sorted
agent queue, so the next person does not have to find this document to know why the line is where
it is.

Finally the *committed* `cybelle/` — both edited files together, comments and all — was run six
more times: 6/6 complete, 0 hangs, 0 `AssertionError` / `Exception in thread "`, and the same
departure-id sequence as every other configuration.

---

## 2. Thread pool (acceptance criterion 3)

```
cybele.srv.thmgmt.app.param.iai = 5 10 4000 3 5
```

From `IAIThreadManagement.start` and the `IAIThreadManager(…,I,I,J,I,I)` constructor, the five
tokens are: **minThreads=5, maxThreads=10, checkpoint cycle=4000 ms, down-delay=3, up-delay=5**.
The manager thread wakes every cycle, counts busy worker threads, and grows or shrinks the pool by
one — but only after the corresponding delay counter has run down, so the first growth cannot
happen before ~20 s of sustained saturation. Worker threads are real `Thread`s pulled from a
shared pool, so `maxThreads` is a hard ceiling on how many agent handlers run at once. That is the
lever the issue asks about. (A single Cybele *activity* still dispatches its own events strictly
serially — `INVENTORY` SEM-04 — so the pool only governs concurrency *across* agents.)

| config | pool | n complete | hangs/starvation | minority tie observations | departure sequence |
|---|---|---|---|---|---|
| `pool_1_1` | 1 1 4000 3 5 | 0/3 | **3/3 never start** | — | — |
| `pool_2_2` | 2 2 4000 3 5 | 2/6 | **4/6 starve** | 1/10 | identical |
| `pool_3_3` | 3 3 4000 3 5 | 6/6 | 0 | 4/30 = 13.3 % | identical |
| `pool_5_5` | 5 5 4000 3 5 | 5/6 | 1 hang | 5/25 = 20.0 % | identical |
| **`base`** | **5 10 4000 3 5** | 23/24 | 1 hang | 22/115 = 19.1 % | identical |
| `pool_10_10` | 10 10 4000 3 5 | 6/6 | 0 | 5/30 = 16.7 % | identical |

**Finding: a smaller pool does not reduce interleaving variance, and below three threads it
breaks the program.**

* Every viable pool size produces the **same** 51-train departure sequence. The pool is not what
  decides the simulation.
* The tie-flip rate is flat across 3, 5, 5–10 and 10 threads (13–20 %, all overlapping at these
  n). There is no monotone trend and no configuration that reaches zero. Serialising dispatch
  cannot fix the tie anyway: at a tie the two `Train.start` handlers belong to two different
  agents, and any pool with ≥ 2 workers can run them in either order.
* At **one** thread the program does not start at all — three runs printed `... Cybele started`
  and nothing further. `RailwayMainAgent`'s constructor builds the GUI, creates the clock and
  spawns fifteen sub-agents; with a single worker there is no second thread for any of them.
* At **two** threads it starts but stalls, in 4 of 6 runs, after 5–7 departures. This is the
  DEF-01/DEF-22/DEF-23 family of unbounded waits meeting a pool too small to supply the second
  thread those waits depend on — `PathFinding` and `VoteCollecting` exist precisely to borrow one.
  Note the direction: shrinking the pool made the run *less* predictable, not more.

**The floor is not the number 3.** Reading "pool >= 3" as a constant would be a mistake. What the
pool has to cover is the set of activities that can be *simultaneously blocked while borrowing a
thread*: `PathFinding`'s `wait()` for a `PATH_FIND_REPLY` (`Station.java:105`), `VoteCollecting`
feeding `Planning`'s `latch.await()` (`Planning.java:88`), and the blocked caller each of them
exists to unblock. Those activities exist *because* the kernel dispatches an activity's own events
serially (`INVENTORY` SEM-04) — they borrow a second thread so a blocking handler does not deadlock
itself. Three workers is what today's set happens to need. **Any future change that adds a
blocking activity, or that lets two of the existing ones block at once on a longer path, moves the
floor up**, and it will show up as this same starvation signature rather than as an error. A port
that removes the blocking waits — which is what a JADE behaviour model would do — moves it down.

Decision: **leave `5 10 4000 3 5` unchanged.** The stock value already sits comfortably above the
viability floor as it stands today, and nothing below it is better on any measured axis.

---

## 3. `ICS.prop` (acceptance criterion 4)

Before:

```
ICSBrowser = 63.122.105.110     # an IAI host, unreachable from here
ICSBrowser = 127.0.0.1
```

`ICSBrowser` and the string `ICS.prop` occur in exactly one class across both vendor jars:
`com.iai.cybele.comm.ics.daemon.IAIDaemon`. It loads the file with `java.util.Properties.load`
(**last one wins**, so `127.0.0.1` was already the effective value), merges it into the system
properties, and splits `ICSBrowser` on `;` to get the hosts to contact.

The application never runs an `IAIDaemon` — `cybele.srv.comm.app.param.iai = Local;NoSerialization`
keeps the comm service in-process — so the file is inert as configured. It is not inert on the
failure path, though, and that is the whole point: with that setting removed, `IAICommService`
constructs an `IAINetClient`, which **spawns an `IAIDaemon`** (its bytecode literally builds a
`java -classpath "…"` command line), and that daemon reads this file. So the external entry becomes
live exactly on the path §4 rules out. The observed failure there is fast — about a second, the
shape of a refused loopback connection. Reorder the file, or change the parser to take the first
key, and the same path would instead dial a host nobody controls; that step is inference from the
parse order rather than something this work measured, which is reason to remove the entry rather
than reason to go and confirm it.

The external entry is now commented out with the reasoning inline; one live `ICSBrowser` remains.
**Verified afterwards:** the application starts and runs normally with the cleaned file (38
departures in a 20 s run, no `AssertionError`, no `Exception in thread`).

---

## 4. `Local;NoSerialization` must stay (acceptance criterion 5)

```
cybele.srv.comm.app.param.iai = Local;NoSerialization
```

`IAICommService.start` splits this on `;` and compares the tokens to `Local` and
`NoSerialization`, passing the two resulting booleans to `IAINetClient`. With the line commented
out — which is how the file was vendored — startup aborts. Verified on this branch:

```
*** Loading comm service (expecting impl. of GSI ver. 1.0) ...
    version 1.1 from Intelligent Automation, Inc (www.i-a-i.com)
      implemented for GSI version 1.0 starting ... Could not connect with IAIDaemon -- Execution aborted!
$ echo $?
1
```

Zero departures, and this is one of the few failures on this codebase that *does* set a non-zero
exit status, because it is a kernel-level abort rather than a throwable inside a handler.

The setting is mandatory, not a tuning knob, and it is also load-bearing for `INVENTORY` SEM-05
(payloads are passed **by reference** under `NoSerialization`). Changing it would change both
startup and message semantics. Do not touch it.

---

## How to reproduce these numbers

There is no committed harness for this — the measurement is a loop around the ordinary launcher,
and the analysis is a comparison of two derived quantities. Both are short enough to state in full.

```bash
# 1. one config variant = one directory holding a cybelle/ for --patch-module.
#    Appending a duplicate key overrides it (Properties.load is last-one-wins).
REPO=$PWD
mkdir -p /tmp/v/cybelle && cp cybelle/ICS.prop /tmp/v/cybelle/
{ cat cybelle/cybele.prop
  echo 'cybele.srv.thmgmt.app.param.iai = 3 3 4000 3 5'; } > /tmp/v/cybelle/cybele.prop

# 2. run from that directory; --patch-module resolves cybelle/ relative to the CWD.
#    Headless and bounded (#17): no display, no `timeout`, and the exit code is meaningful.
(cd /tmp/v && OPENCYBELE_OPTS="-Dsim.config=$REPO/scenarios/short.properties \
   -Dsim.random.masterSeed=20080415 -Dsim.headless=true -Djava.awt.headless=true \
   -Dsim.stop.maxClockMs=230000 -Dsim.stop.wallClockMs=60000 -Dsim.stop.stallMs=10000" \
   $REPO/build/install/opencybele/bin/opencybele) > run.out 2> run.err ; echo $?
```

From `run.out`, two derived quantities:

* **departure-id sequence** — the `vl<N>` ids of every `vl<N> in <station> at <t>` line with
  `t <= 220000`, in print order. This is what must not change.
* **tie-site order** — for each instant where two `… in …` lines share a `t`, which of the two
  `vl<N> started` lines came first. This is what does change. (Four such instants inside the
  window under #17, five before it — the timeline shift moved the fifth past the cut.)

Discard any run that did not exit `0`, and any whose largest `t` is below 220000: it hung or was
cut short, and mixing it into the statistics manufactures a large apparent effect — that is
exactly how the contaminated `:0` sweep produced its 5–10 id differences. Note that exit `0` is
necessary but **not** sufficient: a DEF-22 wedge also exits `0`, so check the largest `t` as well.

---

## What this does not answer

* **What fixes the departure-race outcome for hours at a time.** 114 consecutive runs resolved
  the DEF-02 drop identically and a later session resolved it the other way 7 times in 9, with
  nothing changed. Something in the machine or JVM state persists across runs and biases the race;
  what, is not established here. Until it is, treat same-session agreement as weak evidence of
  reproducibility — this document over-claimed on exactly that point once already.
* **Whether a sorted agent queue would matter at a different operating point.** Everything here is
  one scenario, one seed, 30 s. The corollary above (all messages tie at priority 4) is
  structural and should hold anywhere, but the flip-rate numbers are not.
* **Whether sorting both queues halves the tie-flip rate at all. UNEXPLAINED, and quite possibly
  not a real effect.** Every message ties at priority 4 and merge sort is stable, so the dequeue
  *order* should be bit-for-bit unchanged — and the measurement agrees: the departure sequence is
  identical. The flip-rate difference is therefore unexplained on the mechanism side *and*
  unstable on the evidence side: it is p = 0.006 in the 15 runs of the first sweep phase and
  p = 0.748 in the 32 runs of the second (§"Measurement" above), which is the signature of a
  sampling or regime artifact rather than of a queue property. Two candidate explanations, in
  order of cost to rule out:
  1. **it is noise** — the cheap one, and the phase split plus the eight-comparison multiplicity
     both point at it;
  2. **the extra pass over the queue lengthens the dequeue critical section and narrows the race
     window** — plausible, untested, and requiring dequeue instrumentation.

  Rule out (1) before paying for (2). This is written as a question rather than a mechanism on
  purpose: an unverified explanation repeated twice becomes a fact the project then has to
  un-learn. It also does not bear on the committed configuration either way — the halving belongs
  to `qsort_both`, which is rejected.
* **The DEF-22 hang rate with any precision.** 2 in 90 is an estimate from a sample that was not
  designed to measure it.
* **Whether a DEF-22 wedge really exits `0` under #17, observationally.** The code path is
  unambiguous and is spelled out above, but no wedge was reproduced in 36 healthy-pool headless
  runs — at a ~2 % base rate that sample expects ~0.8, so it neither confirms nor refutes anything.
  Someone wanting the observation should inject the wedge rather than wait for it.
* **Which host the spawned `IAIDaemon` actually dialled** in the criterion-5 experiment. The abort
  came back in about a second, which is consistent with a refused loopback connection and not with
  a remote host, but that was not instrumented — the counterfactual in §3 is inference from the
  parse order, not a measurement.
