# The parity normalizer — every rule, the variance that justifies it, and the gate

> Produced for [#21](https://github.com/bedaHovorka/OpenCybele1/issues/21) (*TraceNormalizer and
> the zero-flake gate*), on `jade-develop`. Consumed by
> [#23](https://github.com/bedaHovorka/OpenCybele1/issues/23) (scenario coverage),
> [#24](https://github.com/bedaHovorka/OpenCybele1/issues/24) (recording the goldens),
> [#36](https://github.com/bedaHovorka/OpenCybele1/issues/36) / [#43](https://github.com/bedaHovorka/OpenCybele1/issues/43)
> (the JADE and Jason probes) and [#39](https://github.com/bedaHovorka/OpenCybele1/issues/39)
> (parity-diff triage).
>
> Input contract: [`docs/trace-format.md`](https://github.com/bedaHovorka/OpenCybele1/blob/opencybele-baseline/docs/trace-format.md)
> (**branch `opencybele-baseline`**). Pipeline position: `docs/parity-harness.md`. Defect
> classification: `docs/defect-triage.md`. Session-level reproducibility:
> [`docs/kernel-config.md`](https://github.com/bedaHovorka/OpenCybele1/blob/opencybele-baseline/docs/kernel-config.md)
> (**also `opencybele-baseline`** — it is not on this branch, and it is load-bearing evidence for
> §6, so every citation below names the branch).
>
> **The headline, so it is not buried.** The normalizer removes *all* of the ordering variance this
> application produces: across **52 captured runs** there is not one pair with the same content in a
> different order. What is left is *content* — which lines a run printed at all — and that is not a
> normalizer problem: it is DEF-02 and the shutdown window, and it is settled by choosing the
> scenario, not by projecting harder.

## 1. What the code is

Two stages, chained, in `LauncherAdapter.normalizer()`:

```
DiagnosticFilter(adapter's declared prefixes)  ->  CanonicalTraceNormalizer
```

The split is the layering #12 chose and #13 relied on. *Which lines are diagnostics* is a property
of the target and is declared by its adapter — `OpenCybeleLauncher` adds `sim.` for the
resolved-configuration banner. *Which values are run-varying, and which orderings are not properties
of the system* is a property of the **trace format**, which all three branches share, so
`CanonicalTraceNormalizer` names no framework and JADE and Jason inherit it without an edit.

Everything below is in
`src/characterizationIT/java/cz/vutbr/fit/ags/parity/normalize/`, tested in
`it/TraceNormalizerIT.java`.

### 1.1 It is applied to the golden too, so no pre-existing golden was re-recorded

`ScenarioRunner` normalizes the **golden** with the same normalizer before comparing. So a golden
recorded under #12's placeholder — raw ticks, an unsorted startup block, interleaved `println`s — is
projected into exactly the form a fresh run reaches, and the file on disk never changes.
`Phase1.md` L7 forbids re-recording for anything but a harness defect; this design means landing the
normalizer does not even raise the question. (**#23 folded `opencybele-smoke` into
`opencybele-strict`** and deleted its golden — see `parity-tests/scenarios/COVERAGE.md` §1. The
argument below stands for the goldens that remain; §5.1's own recommendation is what was acted on.)
`parity-tests/golden/opencybele-smoke.txt` and
`smoke-stub.txt` are untouched by this work and still match.

**One golden here *is* recorded, and it is this issue's own:**
`parity-tests/golden/opencybele-strict.txt` was created with the scenario it belongs to and
re-recorded within this branch each time a rule changed. That is not the case L7 is about — there is
no earlier behaviour being papered over, and the file has never been merged or relied on. It is
called out so that "no golden was re-recorded" is never read as broader than it is.

That works because `normalize` is **idempotent**: a trace whose canonical lines already carry `<T>`
in field 2 is recognised as projected, and its ordering rules — the burst sort *and the startup-block
scan* — are skipped. Both halves matter. The first revision guarded only the burst sort, which was a
latent trap: pass one's sort can leave a `STATION_INFO` or `ROAD_STATE` line at the head of the first
burst, and an unguarded pass two would absorb it into the startup block and re-sort. Today's
`opencybele-strict` does not have that shape, so nothing failed — but because the golden is
normalized on **every** comparison, the first golden that did have it (a different topology, a
different pace, a JADE probe with different emission timing) could never have matched its own
recording, and would have been reported as *"a port bug until proven a harness defect"*.
`TraceNormalizerIT.idempotentWhenABurstStartsWithAStateLine` is that counterexample, and it fails
against the unguarded version.

## 2. The rules

Eleven, each with an id, each switchable off with `CanonicalTraceNormalizer.without(id)`, each
carrying its justification in the code as `TraceRule.because()`.

### 2.1 Value projections

| id | What it rewrites | Observed variance source |
|---|---|---|
| `tick` | field 2 → `<T>` | Erased. The simulated clock advances with the wall clock, so the same event lands either side of a tick boundary run to run. `trace-format.md` family 1. |
| `expected` | `VOTE_REQUEST` payload → `expected=<T>` | Erased. `Cybele.getTime` when the election opened. Family 2. **Not quantised — see §2.2.** |
| `planned` | `VOTE_RESULT` payload → `planned=<T>` | Erased. The agreed departure instant, same reading. Family 3. **Not quantised — see §2.2.** |
| `departure` | `<train> in <station> at <n>` → `at <T>` | Erased. The application's own `println`, in the captured stream since before the probe existed. Family 5. **Not quantised — see §2.2.** |
| `diff` | `VOTE` payload → `diff=<T~41000>` | **Quantised**, floor to 1 000 ms — see §2.2. |
| `occupied` | `STATION_INFO` payload → `occupied=<N>` | Erased. INVENTORY **DEF-13**: the payload crosses the channel by reference under `Local;NoSerialization` and the station keeps mutating it. Measured (#20): 6–12 lines differing across three runs at one pinned seed, counts trading in lockstep between adjacent values on the same station. Values are `0..capacity`, so there is no resolution below the jitter to quantise to. |
| `road-state` | `ROAD_STATE` payload → `state=<S>` | Erased. **Not a race on the value — see §2.3.** |
| `performative` | field 6 → `<P>` | Erased. Constant `-` on the baseline; populated on JADE (#27) and Jason (#48). Decided in #20. **Its variance is cross-branch, not run-to-run** — see §7. |

### 2.2 `diff` is quantised; the three absolute instants are not

Erasing a value is the last resort, not the default. It was the default in the first revision of this
work, and it cost more than it looked like:

> **Measured, and this is the finding that forced the change:** rewriting every `diff=` in a real
> capture to `0` passed the strict golden **byte-identically**. `diff` is the output of
> `computeDifference` — the delay each station and road demands — and it is the *only* payload
> carrying the election's arithmetic. #28 extracts that method, #34 restructures the protocol around
> it, and #36/#43 must reimplement it. Erased, a JADE port that returned zero from every vote, or
> that left road delay out of the computation, produced a byte-identical trace.

So `diff` keeps its magnitude at a declared resolution: `diff=41904` → `diff=<T~41000>`.

**Why 1 000 ms.** Aligning all 93 votes by `(train, voter)` across the scenario's 14 captures: 78
values are identical in every run, 15 vary, and every varying value moves inside a band of 24–56 ms
on magnitudes up to 41 920. Floored to 1 000, **none of the 15 bands crosses a bucket boundary**.

**The honest margin, which is not the headline arithmetic.** "24 ms of jitter against 41 896 of
signal" suggests ~40× headroom. The number that matters is the distance from a jitter band to the
nearest bucket edge, and the tightest is **80 ms against a 56 ms span** — 1.4×, on
`(vl8, tr1) = 4864…4920`, which sits just under 5 000. Three keys share that shape, because road
delays are whole seconds and the data therefore clusters *just below* multiples of 1 000. Rounding
to nearest instead of flooring was tried and is worse here, not better: it moves the boundary to the
half-second and the tightest margin drops from 80 ms to 60 ms.

So this constant is defensible but not comfortable, and the escape hatch is recorded rather than
discovered later: at a quantum of **4 000** the tightest margin is 248 ms and no band straddles in
either corpus, at the cost of no longer resolving a 1 s road delay. `TIME_QUANTUM` is a constant in
one place for that reason.

**Why `expected`, `planned` and `departure` are *not* quantised — measured, not assumed.** The same
change was applied to all four, and it took the strict scenario from **14/14 byte-identical to 5
distinct traces in 14**. The cause is structural and worth carrying to #23 and #36:

* They are **absolute clock instants**, and the base jitters far more than a duration does. vl2's
  election opened at 9840 / 9928 / 9944 / 10032 across four runs — a ~200 ms spread, not 24 ms.
  vl3's departure `println` read 12944 / 13032 / 13056 / 13144, which crosses 13 000 on its own.
* Every leg of one election is **base + k·1000**, because road delays are whole seconds. So a 1 000
  ms bucket is *resonant* with the data: when the base crosses a boundary, **all eleven legs of that
  election move together**. It is the worst possible quantum for this field, and a coarser one only
  lowers the probability — at 8 000 ms it is still ~2.5 % per train, ~20 % per run, which no
  zero-flake gate survives.

`diff` escapes this because it is a **duration** — a difference of two absolute times, so the
jittering base cancels.

**What is therefore still unpinned, said plainly.** The golden does not pin the absolute schedule: a
port that computed every `diff` correctly and then departed every train at *t*=0 would match on
these fields. It would not match overall — the burst structure is derived from the tick spacing, so
collapsing every election onto one instant merges bursts and changes the golden's line order
wholesale. The claim is "unpinned by the payload, caught by the shape", not "caught by nothing".

`capacity` is deliberately **not** projected: it is configuration a port must replicate.
`TRAIN_STATE`'s `state` is **not** projected either — it is a behaviour string, not a raced enum,
and it is one of the few payloads that says what the system decided.

### 2.3 Ordering rules

| id | What it does | Observed variance source |
|---|---|---|
| `startup-block` | Sorts the maximal opening run of `STATION_INFO`/`ROAD_STATE` lines by agent name | `Cybele.createAgent` is asynchronous: **six distinct constructor orders in six runs** (`docs/seeded-rng.md`), and `RoadAgent`'s constructor ends in `sendState()`, `Station`'s in `sendInfo()`. |
| `burst-order` | Segments the rest at tick gaps > 200 ms and sorts within a segment | Tick jitter — see §3. |
| `println-streams` | Splits the two application `println` families into two streams appended after the trace | They come from different threads (`Planning`'s activity thread and each `Train`'s own) and carry no tick: **three orderings across six runs at one seed** (#21). |

`println-streams` in detail, because it is the one that gives something up. The `in … at` stream
keeps **emission order** — its producer is one serial activity, so departure order is real and
survives. The `started` stream is **sorted by train name** — it has one producer per train, so its
cross-train order is arbitrary, and the line carries nothing the `in … at` stream and the canonical
`START` lines do not already pin.

### 2.4 `ROAD_STATE.state` — what it actually is, and what #39 must not conclude

`docs/trace-format.md` recorded an unexplained residual: *"`ROAD_STATE` on `tr6` also varied … Its
payload is an immutable enum, so aliasing cannot be the cause; the likeliest explanation is ordinary
behavioural drift downstream of the departure nondeterminism."*

**An earlier revision of this document claimed that guess was wrong and called the mechanism
publish-after-mutation, "the same defect class as DEF-13". That claim was itself wrong, and it is
retracted.** It is not merely unproven — it is impossible. In `RoadAgent`, `enter` and `leave` are
both `synchronized` on the road, each mutates `state` and calls `sendState()` *inside the same
lock*, and `sendState` copies the enum reference into a fresh array immediately. Nothing can land in
between and there is no alias to inherit.

**What actually varies is which code path the road took**, and the trace shows it:

```
7 of 8 runs                              1 of 8
  vl0|LEAVE|tr6                            vl1|ENTER|tr6          <- ENTER arrives FIRST
  tr6|ROAD_STATE|state=FREE                vl0|LEAVE|tr6
  vl1|ENTER|tr6                            tr6|ROAD_STATE|state=TRAVEL_LEFT
  vl1|ENTER_REPLY|tr6|vl1                  vl1|ENTER_REPLY|tr6    <- REPLY comes AFTER the LEAVE
```

In the ordinary run `leave()` finds `queue.size() == 0`, sets `FREE` and publishes it; the later
`enter()` finds the road free and takes `acceptTrain` directly. In the anomalous run `vl1`'s `ENTER`
arrives while the road is still busy, so it goes down `push()` → `queue.offer` and publishes the
*unchanged* `TRAVEL_LEFT`; `leave()` then takes the `queue.size() != 0` branch → `pop()` →
`acceptTrain`, and publishes `TRAVEL_LEFT` again. Hence `TRAVEL_LEFT, TRAVEL_LEFT` instead of
`FREE, TRAVEL_LEFT`. `ENTER_REPLY` moving across the `LEAVE` is the visible signature of the queued
path, because in that path the reply is sent from inside `leave()`.

So this field is the trace's **only witness to "did the road hand a queued train over directly, or
go idle and then accept?"** — genuinely different code through `push`/`pop`, `OueueItem`'s
comparator and the road's timetable. That is exactly the behavioural difference `trace-format.md`
suspected.

It is still projected, for the reason the baseline forces: which path is taken is not reproducible
here (1 run in 8 at this seed) and without the rule `strict` flakes at that rate. But the cost is
larger than the earlier revision admitted, and it is not "direction of travel is safe, so nothing is
lost" — direction *is* still pinned exactly by `ENTER`'s `position=`, `ENTER_REPLY`'s `next=` and the
`TRAVEL_START`/`TRAVEL_END` pairs, but the queue path is not.

> **Guidance for #39, corrected.** The earlier text said to read a `ROAD_STATE` diff as "a normalizer
> matter, not a port bug". **Do not use that rule — it was derived from the wrong mechanism and it
> would dismiss a real port bug**, because a port that never queues produces the ordinary shape every
> time and passes. The rule is: `ROAD_STATE.state` is *unpinnable in the baseline*, so a difference in
> that field alone is not by itself a port bug — but the code path it hides is real, so **check
> `ENTER_REPLY`'s position relative to the matching `LEAVE`** before dismissing it. A port whose
> `ENTER_REPLY` never follows a `LEAVE` on a contended track has lost the queue path, and that is a
> port bug this projection cannot see.

One more correction to the earlier revision: it claimed that in the anomalous runs "every other line
of the trace is byte-identical". That was true only of the *normalized* trace. In the raw capture the
`ENTER_REPLY` sits in a different position and the downstream ticks differ; those differences are
absorbed by `burst-order` and `tick`, not absent.

## 3. The burst width — a tuning constant, and it is admitted to be one

`Phase1.md` L36 proposed "sort within tick". **It is not sufficient, and the reason is that the tick
itself moves.** The same event lands on 1336 in one run and 1344 in the next, so two lines that
shared a tick when the golden was recorded do not share one on replay, and the sort never sees them
together. Measured over 13 runs of `opencybele-smoke`: equal-tick sorting left **190–220 of 623
lines displaced** between any two full-length runs, every one of them a pure reordering of an
identical multiset.

The fix is to sort within a **burst** — a maximal run of consecutive canonical lines separated by no
more than `DEFAULT_SEGMENT_GAP_TICKS` simulated milliseconds.

**There is no clean valley to put that threshold in.** A three-run sample suggested one (largest
intra-burst gap 104 ms, smallest inter-burst gap 208 ms), and pooling 52 runs dissolves it: 26 848
gaps of 0 ms, 2 497 of 8 ms, then a thin continuous tail with no empty band anywhere between 16 ms
and 400 ms. The threshold was therefore chosen by measuring the thing that matters — how often two
runs with *identical content* still come out in a different order — over all 52 captures:

| gap (ms) | 100 | 120 | 150 | 180 | **200** | **220** | 250 | 300 | 400 | 500 | 1000 | 5000 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| extra orderings | 2 | 2 | 1 | 1 | **0** | **0** | 3 | 4 | 5 | 1 | 0 | 0 |
| segment counts seen | 23–33 | 23–29 | 23–28 | 22–25 | **22–24** | **22–24** | 20–23 | 18–22 | 15–19 | 12–15 | 8–9 | 1 |

(An earlier revision printed a different row here from the one in the code, because the two were
measured against different rule sets. There is now one measurement, taken with the shipped rules,
and it is in `CanonicalTraceNormalizer.DEFAULT_SEGMENT_GAP_TICKS` as well as here.)

200 and 220 are the only narrow widths at zero. **220 is shipped**, on an independent 8-capture sweep
that found the *segment count* stable at 220–250 and jittering at 200 — and because 220 is no worse
than 200 anywhere in the table above.

**The segment count jitters at 220 too**, in this corpus: 22 or 23 across 14 captures of
`opencybele-strict`, exactly as at 200. That is not a contradiction of the sweep that chose 220. A
boundary can move without changing the output, because two lines either side of it may sort the same
way whether they share a burst or not — which is why the count is a health check asserted with a
*floor*, never an equality, and why `extra orderings` rather than `segment counts` is the column that
selected the constant.

Widening further buys the last ordering back — and 5 000 ms collapses the run into two bursts, at
which point the golden pins a **multiset and no order at all**. That is the "lock that cannot fail"
shape this project has rejected four times, so it is guarded rather than trusted:
`CanonicalTraceNormalizer.segments(...)` exposes the count and `OpenCybeleStrictIT` asserts a floor
of 10 — **on the normalizer the adapter is actually using**, found with
`CanonicalTraceNormalizer.findIn(...)`. Asserting it against a freshly constructed default was the
first version and it guarded the constant rather than the pipeline: widening the adapter's width to
5 000 left the floor measuring 220 and passing green while the comparison had collapsed to one
segment. The companion "the trace is not globally sorted" assertion does not catch that either,
because a single sorted burst preceded by a sorted startup block is not globally sorted.

**What the width costs, said once and plainly.** Within one burst the golden pins *which lines
occurred*, not *in what order*. Order across bursts is pinned exactly. That is the right trade
because within-burst order is not a property of the system: the probe is one serial observer of
fifteen channels, so even a single sender's messages reach it out of send order — measured, the
`VOTE_RESULT` fan-out arrives permuted in the golden itself.

### 3.1 The width is one-sided, and that is what decides a cross-implementation run

`ScenarioRunner` normalizes the **golden** with the same normalizer before comparing it — the
mechanism that let #21 land without re-recording anything. A golden read back has no parseable tick
in field 2, so it is recognised as projected and every ordering rule is skipped on it (see
"Idempotence" above). Measured on the shipped file:

```
normalize(each of the five parity-tests/golden/opencybele-*.txt) at w = 0, 8, 100, 220, 5000, 100000
  -> byte-identical to the file on disk, at every width, for all five
```

**A golden is a fixed point of this normalizer at every width.** Its line order is frozen at the
width it was recorded under, and cannot be recomputed from the file. `DEFAULT_SEGMENT_GAP_TICKS`
therefore re-segments the *run* only.

On the implementation the golden was recorded from that is invisible and harmless. Across
implementations it is the whole story: the comparison is not "do these two runs agree up to burst
order" but "does this run reproduce the burst partition the baseline recorded" — and a burst
partition is a function of the implementation's **message latency**, which is exactly the quantity
§3 set out to stop asserting. Measured on the JADE port (#39, `docs/parity-triage-jade.md` §4.1):
the arrival-handover cascade takes 13 ms of simulated time on the baseline and 52 ms on the port,
so a gap that reads 448 ms on one reads 214 ms on the other. There is no threshold, one-sided or
two-sided, that puts both on the same side of it.

The practical rule that follows, for a scenario author: **the margin between a scenario's gaps and
the width is the scenario's real tolerance to a port**, and `parity-tests/scenarios/COVERAGE.md`
§12.2 is where it is recorded. `opencybele-strict`'s is 4 ms.

## 4. What is deliberately not normalized

### 4.1 A missing entity

DEF-02 drops trains, so the *set* of departures varies. A dropped train is **absent, not
reordered**, and no normalisation invents a line a run never printed — nor deletes one, which would
be the same sin facing the other way. `TraceNormalizerIT.aDroppedTrainIsStillADiff` locks it, and
`TraceNormalizerIT.lineCountIsPreserved` locks the general property: the projection rewrites and
moves, it never adds or removes. This is a scenario-selection problem (#23).

### 4.2 Conversation ids, reply-to, agent-local ids

The issue lists these as strips. Two of the three have nothing to strip and one belongs elsewhere,
and adding a rule that matches nothing would be exactly the rule-that-cannot-fail this document
keeps warning about:

* **Conversation ids and reply-to have no field.** `docs/trace-format.md` fixes seven fields and
  fixes the payload keys per channel. If #27 wants a conversation id recorded, that is a *format*
  change and must be made in `trace-format.md` first — not smuggled into a payload and then
  stripped here.
* **Cybele agent ids never reach the trace.** Fields 1, 4 and 5 are agent *names*
  (`stA`, `tr1`, `vl3`, `Main`).
* **A JADE AID suffix (`@host:port/JADE`) would** — and it must not be emitted. Fields 4 and 5 are
  specified as the agent, and #36's probe is required to write the bare name, exactly as the
  baseline probe computes field 1 from the channel identity rather than from where it hooked. That
  keeps the layering: a port's naming is the port's problem, and the normalizer stays a statement
  about the format.

### 4.3 Lines emitted after a run's stop bound

They are a shutdown race, not a value — see §5 — and the normalizer has no way to know what the
bound was. Handing it one would make it implementation-aware for a problem that a scenario solves by
choosing a better number.

## 5. Where the residual variance actually is

52 headless runs of the reference implementation at seed `20080415`, two bounds, all through the
shipped normalizer. **Zero ordering differences.** Every remaining difference is content:

| Cause | Rate | Whose |
|---|---|---|
| **Shutdown-window truncation.** At `sim.stop.maxClockMs = 25000` the trace's last event burst *begins* at simulated 25072 — 72 ms past the bound — so it is emitted while the run is already tearing down. Measured tail lengths: 623 lines ×25, 611 ×1, 609 ×3, 607 ×8, plus one 623-line run that now differs on a quantised `diff` (§2.2) rather than on length. | 13 / 38 | **#23.** Move the bound into a gap in the *event* timeline, not just the arrival timeline. |
| **DEF-02 train drop.** A `START` published to a channel the `Train` had not opened. | 1 / 38 | Class (c), `docs/defect-triage.md`. |
| **`ROAD_STATE.state` path** (§2.4) | 1 / 8 (independently), 3 / 14 here, before the rule; **0** after | Unpinnable in the baseline; projected, with the #39 caveat in §2.4. |

And the counterfactual, on the same corpus: **before this normalizer, 13 captures produced 13
distinct traces.** Nothing agreed with anything.

### 5.1 The one number that separates `strict` from `summary`

`opencybele-strict.yaml` is `opencybele-smoke.yaml` with `sim.stop.maxClockMs` moved from 25000 to
24000, which is inside the 1 952 ms gap between the burst ending at 23120 and the burst beginning at
25072 — 880 simulated ms clear on one side, 1 072 on the other, and 2 776 ms clear of the last train
creation (so it is *further* from the `Cybele.terminate()` NPE hazard than 25000, which is what
#13 sized 25000 against).

Result: **14 of 14 captures byte-identical**, and the shipped gate is 10 for 10.

`opencybele-smoke` is left exactly as #13 recorded it (**#23 has since folded that scenario into `opencybele-strict` and deleted its golden; nothing was re-recorded — see `parity-tests/scenarios/COVERAGE.md` §1**) — same golden, same `summary` contract, same
bound — because tightening it means re-recording its golden, and that is a decision for the issue
that owns the scenario. **The recommendation to #23 is to fold the two by moving the smoke bound to
24000**; the two files are otherwise identical and carrying both is a cost, not a design.

## 6. The gate

```bash
./gradlew parityGate \
    -Popencybele.dist=/abs/path/wt/opencybele-ref/build/install/opencybele \
    -Dparity.gate.runs=10
```

One command. It is a Gradle lane of its own rather than a flag, because ten repetitions of a real
scenario is ~40 s and a gate that makes every unrelated change pay for it is a gate people switch
off. Without `-Dparity.gate.runs` it **skips and prints the command**, rather than passing
vacuously.

### 6.1 What a wedged run does — corrected

Every repetition goes through `ScenarioRunner`, so the whole judging stack runs before a trace is
allowed to count: harness timeout, exit classification, the error scan on the raw stream, the
declared disposition, the liveness floors, then the golden comparison.

**An earlier revision of this document, of `ParityGate`, of the strict scenario and of the PR body
all said that a DEF-22 wedge "is killed as `HARNESS_TIMEOUT` and can never be one of the N". That is
wrong**, it contradicts this repository's own `docs/ci.md` and `docs/defect-triage.md`, and it is
retracted here in all four places.

What actually happens: the latch wedges `Planning`, but `Generator` is fire-and-forget and the two
activities do not head-of-line block each other (`Agent.createActivity` passes
`ConcurManagement.CONCURRENT`, and `IAIConcurManagement.getRunnable` skips a blocked node — proved in
bytecode, #16). So generation continues, the simulated-time bound still fires, and **the process
exits 0**, with zero `AssertionError` and zero `Exception in thread "` across 146 measurement runs.
`sim.stop.stallMs` structurally cannot catch it either: it measures the gap between *generated*
trains, and generation is the half that keeps working. `run.harnessTimeoutMs` is a real backstop —
for a child that genuinely never exits, which is a different failure.

So a wedged run passes the exit classification and the error scan. What it cannot pass is the
**trace**: its departure stream truncates mid-run, so it fails a liveness floor if the wedge came
early enough, and otherwise it fails the golden comparison.

**The true story is better than the false one.** It means the *contract level* is what makes a wedge
catchable at all: at `strict` a wedge is a certain failure, while at `summary` a late one can hide
inside a tolerance. That is an argument for tightening a scenario, not for a bigger timeout, and it
is one more reason for #23 to fold `opencybele-smoke` into `opencybele-strict`.

Two consequences to carry:

* A wedge fails as a **golden mismatch**, and `ScenarioRunner` reports a golden mismatch as *"a port
  bug until proven a harness defect; re-recording is not a triage option"*. On the baseline that
  message is maximally misdirecting. Check the departure count before believing it.
* `docs/defect-triage.md` §6 tells #24 that a wedged **recording** must be discarded on departure
  count, not on exit status. **The harness does not do that for it.** This gate compares runs to each
  other and to a golden; it cannot see that the golden itself was recorded from a wedged run.

### 6.2 What ten consecutive runs establishes — and what it does not

This is the part the issue's own acceptance criterion gets wrong, and the gate says so on every
invocation.

Reproducibility here has a **session-level** component. `docs/kernel-config.md` (branch `opencybele-baseline`) measured 114
consecutive runs resolving the DEF-02 race identically, and a *later session* resolving it the other
way 7 times in 9, with nothing changed — and, under "What this does not answer", records that
**what** persists "is not established here". There is no machine-state property to query. The gate
does not invent one.

So:

* **Established by N consecutive runs:** within one session, the scenario is byte-reproducible.
  Everything that re-rolls per run — thread interleaving, tick jitter, the startup race, the
  `println`s' race, the aliased payloads — is absorbed. That is the whole of what this normalizer is
  responsible for, and it is worth having.
* **Not established:** that a golden recorded now matches a run later. Consecutive runs sample one
  mode. `docs/defect-triage.md` §8.2: *"What proves nothing: any number of consecutive runs."*

### 6.3 The cross-occasion half, made executable

Every invocation appends a line to `build/parity-gate/<scenario>.tsv` (or
`-Dparity.gate.ledger=…`): boot id, timestamp, trace digest, line count, entity-id set. It then
compares against every earlier line for that scenario, and counts one as **cross-occasion evidence**
only if it came from a **different boot id** (`/proc/sys/kernel/random/boot_id`) or is at least
**4 hours** old. A run from this boot ten minutes ago is reported as *not* being evidence.

That closes §8.2's stated trap — *"a script that loops twice satisfies the letter while delivering
exactly the worthless consecutive-run evidence"* — because looping cannot produce a qualifying
entry. The comparison itself is the one §8.2 asks for: **entity-id sets**, not line counts and not
orderings. A qualifying earlier occasion with a different id set **fails** the gate, and the message
says to retune the scenario rather than re-record the golden.

Three residuals in the mechanism, named rather than left to be found:

* **`ParityGate.run` throws only on within-session divergence.** The cross-occasion disagreement is
  *computed* there and *asserted* in `ParityGateIT`, so "a disagreement fails the gate" is true via
  the opt-in test, not via the method. Anything else embedding `run` must check
  `Result.crossOccasionDisagreements()` itself.
* **The ledger stores `digest` and `lines` but compares only `entityIds`.** That is deliberate —
  §8.2 asks for departure-id sets, not line counts and not orderings — but it means a cross-occasion
  entry with the same ids and a different trace is recorded and not judged. The two extra columns
  are there for a human to look at.
* **A different boot id means a different boot *or a different machine*,** and those are not the same
  axis. §8.2 is about a property that persists for hours on one machine; a second machine is separate
  evidence — arguably weaker for that specific question and stronger for portability. The gate does
  not distinguish them and must not be read as having done so.

Two honest caveats:

* **The 4-hour threshold is a convention, not a measurement.** §8.2 says as much about "hours":
  nobody has measured the interval at which the mode flips. It is a constant in the code so that it
  can be argued with.
* **Put the ledger somewhere a clean build does not delete** if you want the comparison to survive
  one. The default lives under `build/` because that is the safe default for a repository, not
  because it is the useful one.

### 6.4 Measuring on `:0` is measuring nothing

`docs/kernel-config.md` (branch `opencybele-baseline`): a sweep on the desktop's `:0` produced departure-id sets differing by 5–10
ids between runs of the *same* configuration, and the identical sweep on an isolated display
collapsed that to zero. Every scenario here sets `sim.headless=true`, and every measurement in this
document was taken headless. Any variance number for this program taken on a shared display should
be assumed contaminated.

## 7. Mutation results — each rule shown to be able to fail

Two levels, because they answer different questions.

**Unit, automated, every rule, every build.** `TraceNormalizerIT.everyRuleIsLoadBearing` builds a
normalizer with exactly one rule removed and asserts that two inputs differing *only* in that rule's
variance stop agreeing. A rule whose removal changes nothing fails the build. Each rule's fixture is
written out separately, so a failure names one cause.

**Scenario-level, on the real corpus.** Fourteen captures of `opencybele-strict`, each rule removed
in turn, counting distinct normalized traces:

| rule removed | distinct traces / 14 |
|---|---|
| *(none)* | **1** |
| `tick` | 14 |
| `expected` | 14 |
| `planned` | 14 |
| `diff` | 14 |
| `departure` | 14 |
| `startup-block` | 14 |
| `burst-order` | 14 |
| `occupied` | 4 |
| `road-state` | 2 |
| `println-streams` | 2 |
| `performative` | **1** |

`performative` is 1 **and that is correct**: the baseline emits a constant `-`, so its variance is
not run-to-run at all — it is cross-branch, and it appears the day #27 populates the field. That is
exactly why the acceptance criterion demands a **synthetic three-branch sample** instead, and
`TraceNormalizerIT.Performative.threeBranchSampleConverges` supplies one: a Cybele line, a
JADE-shaped line and a Jason-shaped line describing the same message must normalize to one line, and
must diverge into three when the rule is removed.

**Two rules are low-power at this seed, and the score should not be read as strength.**

* **`println-streams`** scores 2/14 here and 1/8 in an independent sweep. It is only load-bearing
  when two trains tie on a departure millisecond, which this seed almost never produces; the measured
  three-orderings-in-six-runs came from seed `987654321`. The unit fixture uses those measured
  orderings, so it is not a dead lock — but **#23 should add a scenario that actually ties**, or this
  rule goes untested against a real run for the rest of Phase 1.
* **`occupied`** scores 4/14, and the scope is narrower than DEF-13 sounds: of the eight stations,
  **five have a perfectly stable occupancy sequence across all 14 captures** and only `stA`, `stE` and
  `stG` vary (an independent 8-capture sweep found only `stA`). Rewriting every `occupied=` to a
  constant passes byte-identically, so the field pins nothing today. The rule stays because DEF-13 is
  structural — the alias exists on every station and which one is caught depends on load — but #23
  should know it is buying insurance against three stations, not eight.

## 8. What could not be determined

* **Verification on both branches.** The acceptance criteria ask that every normalizer change be
  re-verified on `develop` *and* `jade`. **Only one implementation exists today.** Everything here
  was measured against `opencybele-baseline` through `OpenCybeleLauncher`; the JADE and Jason claims
  are the synthetic sample in §7 and nothing more, and they are labelled as such rather than
  presented as a two-branch verification.
* **Cross-occasion reproducibility of `opencybele-strict`.** The gate is 10 for 10 within one
  session, and the ledger contains one entry. The cross-occasion claim needs a second invocation
  after a reboot or hours later; §6.3 is the procedure and the gate refuses to fake it.
* **Whether 220 ms is right for a scenario other than this one.** It was measured on 52 runs of one
  topology at one pace. The width is a constructor parameter for that reason, and
  `OpenCybeleStrictIT`'s segment floor is what would notice if a future scenario made it degenerate.
* **Whether the 1 000 ms `diff` quantum survives a heavier scenario.** The tightest measured margin
  is 80 ms against a 56 ms jitter band (§2.2) — 1.4×, not the 40× the headline ratio suggests — and
  the jitter grows with load: the same corpus at a different bound shows `(vl8, tr1)` spanning 568 ms
  once behaviourally divergent runs are included. Within one behavioural class nothing straddles, in
  either corpus. If it ever does, the recorded answer is a quantum of 4 000 (248 ms margin, no
  straddles anywhere), at the price of no longer resolving a 1 s road delay.
* **Whether `expected`/`planned`/`departure` can be pinned at all.** Erasing them is measured as
  necessary (§2.2), not preferred. No quantum works, because the base jitter is ~200 ms and the legs
  are spaced at whole seconds; something other than quantisation — a per-election *relative* encoding,
  which the line-local rule model here cannot express — would be needed, and that is a change to
  `trace-format.md` rather than to this normalizer.
* **The DEF-22 hang rate**, which `docs/kernel-config.md` (branch `opencybele-baseline`) already
  calls an estimate from a sample not designed to measure it. None of the 52 runs recorded here
  wedged; at ~2 % that is ~1 expected, so their absence is evidence of nothing. Note also that this
  document previously mis-stated how a wedge is *detected*; §6.1 carries the correction.
* **Whether the queue path `road-state` hides is exercised at all in a given run.** It appeared once
  in eight captures, on one track. §2.4 tells #39 to check `ENTER_REPLY` against `LEAVE`, but nothing
  in the suite asserts that the path is taken, so a port could lose it silently until #23 writes a
  scenario that forces contention on a single track.
