# The parity normalizer — every rule, the variance that justifies it, and the gate

> Produced for [#21](https://github.com/bedaHovorka/OpenCybele1/issues/21) (*TraceNormalizer and
> the zero-flake gate*), on `jade-develop`. Consumed by
> [#23](https://github.com/bedaHovorka/OpenCybele1/issues/23) (scenario coverage),
> [#24](https://github.com/bedaHovorka/OpenCybele1/issues/24) (recording the goldens),
> [#36](https://github.com/bedaHovorka/OpenCybele1/issues/36) / [#43](https://github.com/bedaHovorka/OpenCybele1/issues/43)
> (the JADE and Jason probes) and [#39](https://github.com/bedaHovorka/OpenCybele1/issues/39)
> (parity-diff triage).
>
> Input contract: `docs/trace-format.md` on `opencybele-baseline`. Pipeline position:
> `docs/parity-harness.md`. Defect classification: `docs/defect-triage.md`.
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

### 1.1 It is applied to the golden too, and that is why nothing was re-recorded

`ScenarioRunner` normalizes the **golden** with the same normalizer before comparing. So a golden
recorded under #12's placeholder — raw ticks, an unsorted startup block, interleaved `println`s —
is projected into exactly the form a fresh run reaches, and the file on disk never changes.
`Phase1.md` L7 forbids re-recording for anything but a harness defect; this design means landing the
normalizer does not even raise the question.

That works because `normalize` is **idempotent**: a trace whose canonical lines already carry `<T>`
in field 2 is recognised as projected and its order is left alone, and no projection can match its
own output. `TraceNormalizerIT.idempotent` asserts it, and the proof it matters is
`parity-tests/golden/opencybele-smoke.txt`: unchanged by this issue, and still matched by
`OpenCybeleSmokeIT`.

## 2. The rules

Eleven, each with an id, each switchable off with `CanonicalTraceNormalizer.without(id)`, each
carrying its justification in the code as `TraceRule.because()`.

### 2.1 Value projections

| id | What it rewrites | Observed variance source |
|---|---|---|
| `tick` | field 2 → `<T>` | The simulated clock advances with the wall clock, so the same event lands either side of a tick boundary run to run. `docs/trace-format.md` family 1. |
| `expected` | `VOTE_REQUEST` payload → `expected=<T>` | `Cybele.getTime` when the election opened. Family 2. |
| `planned` | `VOTE_RESULT` payload → `planned=<T>` | The agreed departure instant, same reading. Family 3. |
| `diff` | `VOTE` payload → `diff=<T>` | A delay computed against a timetable in absolute simulated time. Family 4. Verified here rather than assumed: two runs at one seed agree on 80 of 93 `diff` values and differ on 13 (`13920`/`13888`, `3254`/`3222`, `736`/`712`). |
| `departure` | `<train> in <station> at <n>` → `at <T>` | The application's own `println`, in the captured stream since before the probe existed. Family 5. |
| `occupied` | `STATION_INFO` payload → `occupied=<N>` | INVENTORY **DEF-13**: the payload crosses the channel by reference under `Local;NoSerialization` and the station keeps mutating it. Measured (#20): 6–12 lines differing across three runs at one pinned seed, counts trading in lockstep between adjacent values on the same station. "The value is a race, not a timestamp." |
| `road-state` | `ROAD_STATE` payload → `state=<S>` | **New here — see §2.3.** |
| `performative` | field 6 → `<P>` | Constant `-` on the baseline; populated on JADE (#27) and Jason (#48). Decided in #20. **Its variance is cross-branch, not run-to-run** — see §4.2. |

`capacity` is deliberately **not** projected: it is configuration a port must replicate.
`TRAIN_STATE`'s `state` is **not** projected either — it is a behaviour string, not a raced enum,
and it is one of the few payloads that says what the system decided.

### 2.2 Ordering rules

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

### 2.3 `ROAD_STATE.state` — a seventh run-varying family, and what it actually is

`docs/trace-format.md` recorded an unexplained residual: *"`ROAD_STATE` on `tr6` also varied … Its
payload is an immutable enum, so aliasing cannot be the cause; the likeliest explanation is ordinary
behavioural drift downstream of the departure nondeterminism."*

**That guess is wrong, and the evidence is now direct.** In the runs where it appears, *every other
line of the trace is byte-identical* — there is no behavioural drift to be downstream of. The
mechanism is visible in the ticks:

```
vl0|10656|LEAVE|vl0|tr6|-|train=vl0          tr6|10656|ROAD_STATE|tr6|Main|-|state=FREE
vl1|10688|ENTER|vl1|tr6|-|…                  tr6|10688|ROAD_STATE|tr6|Main|-|state=TRAVEL_LEFT
```

32 simulated milliseconds apart, two different threads. In 3 of 14 runs at one seed the **first**
line reads `TRAVEL_LEFT` as well: `RoadAgent` assigns `state` and publishes it as two steps, and the
entering train's assignment landed in between. That is a publish-after-mutation race — the **same
defect class as DEF-13**, on a different field, not an enum-immutability question at all.

So `state` is projected, and the cost is stated plainly rather than minimised: a port that reported
every track as `FREE` for ever would not fail on this family. It would still fail everywhere else,
because **direction of travel is pinned exactly** by `ENTER`'s `position=`, `ENTER_REPLY`'s `next=`
and the `TRAVEL_START`/`TRAVEL_END` pairs — `ROAD_STATE.state` is derived from those events and adds
no independent observable. The alternative, `docs/trace-format.md`'s "keep busy tracks out of a
strict contract", would mean no scenario in this topology could ever reach `strict`: `tr6` is on the
only route between `stC` and `stA`.

#39 should read a `ROAD_STATE` value diff as a **normalizer** matter, not a port bug — the same
verdict `docs/defect-triage.md` §8.3 already gives `STATION_INFO.occupied`.

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
runs with *identical content* still come out in a different order:

| gap (ms) | 100 | 120 | 150 | 180 | **200** | 220 | 250 | 300 | 500 | 1000 | 5000 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| extra orderings, 52 runs | 3 | 3 | 2 | 2 | **1** | 1 | 4 | 5 | 2 | 1 | 0 |
| bursts | 26 | 24 | 24 | 24 | **24** | 24 | 24 | 20 | 14 | 9 | 2 |

200 ms is the narrowest width at the observed minimum. Widening further does buy the last ordering
back — and 5 000 ms collapses the run into two bursts, at which point the golden pins a **multiset
and no order at all**. That is the "lock that cannot fail" shape this project has rejected four
times, so it is guarded rather than trusted: `CanonicalTraceNormalizer.segments(...)` exposes the
count and `OpenCybeleStrictIT` asserts a floor of 10, plus that the normalized trace is *not* in
sorted order.

**What the width costs, said once and plainly.** Within one burst the golden pins *which lines
occurred*, not *in what order*. Order across bursts is pinned exactly. That is the right trade
because within-burst order is not a property of the system: the probe is one serial observer of
fifteen channels, so even a single sender's messages reach it out of send order — measured, the
`VOTE_RESULT` fan-out arrives permuted in the golden itself.

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
| **Shutdown-window truncation.** At `sim.stop.maxClockMs = 25000` the trace's last event burst *begins* at simulated 25072 — 72 ms past the bound — so it is emitted while the run is already tearing down. Measured tail lengths: 623 lines ×26, 611 ×1, 609 ×3, 607 ×8. | 12 / 38 | **#23.** Move the bound into a gap in the *event* timeline, not just the arrival timeline. |
| **DEF-02 train drop.** A `START` published to a channel the `Train` had not opened. | 1 / 38 | Class (c), `docs/defect-triage.md`. |
| **`ROAD_STATE.state` race** (§2.3) | 3 / 14 before the `road-state` rule; **0** after | Was #21's; now projected. |

And the counterfactual, on the same corpus: **before this normalizer, 13 captures produced 13
distinct traces.** Nothing agreed with anything.

### 5.1 The one number that separates `strict` from `summary`

`opencybele-strict.yaml` is `opencybele-smoke.yaml` with `sim.stop.maxClockMs` moved from 25000 to
24000, which is inside the 1 952 ms gap between the burst ending at 23120 and the burst beginning at
25072 — 880 simulated ms clear on one side, 1 072 on the other, and 2 776 ms clear of the last train
creation (so it is *further* from the `Cybele.terminate()` NPE hazard than 25000, which is what
#13 sized 25000 against).

Result: **14 of 14 captures byte-identical**, and the shipped gate is 10 for 10.

`opencybele-smoke` is left exactly as #13 recorded it — same golden, same `summary` contract, same
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

### 6.1 What a wedged run does

Every repetition goes through `ScenarioRunner`, so the whole judging stack runs before a trace is
allowed to count: harness timeout, exit classification, the error scan on the raw stream, the
declared disposition, the liveness floors, then the golden comparison. DEF-22 hangs roughly one run
in 45 with a clean process and nothing in the stream; such a run is killed as `HARNESS_TIMEOUT` and
**fails** the gate. It can never be one of the N.

### 6.2 What ten consecutive runs establishes — and what it does not

This is the part the issue's own acceptance criterion gets wrong, and the gate says so on every
invocation.

Reproducibility here has a **session-level** component. `docs/kernel-config.md` measured 114
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

Two honest caveats:

* **The 4-hour threshold is a convention, not a measurement.** §8.2 says as much about "hours":
  nobody has measured the interval at which the mode flips. It is a constant in the code so that it
  can be argued with.
* **Put the ledger somewhere a clean build does not delete** if you want the comparison to survive
  one. The default lives under `build/` because that is the safe default for a repository, not
  because it is the useful one.

### 6.4 Measuring on `:0` is measuring nothing

`docs/kernel-config.md`: a sweep on the desktop's `:0` produced departure-id sets differing by 5–10
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

## 8. What could not be determined

* **Verification on both branches.** The acceptance criteria ask that every normalizer change be
  re-verified on `develop` *and* `jade`. **Only one implementation exists today.** Everything here
  was measured against `opencybele-baseline` through `OpenCybeleLauncher`; the JADE and Jason claims
  are the synthetic sample in §7 and nothing more, and they are labelled as such rather than
  presented as a two-branch verification.
* **Cross-occasion reproducibility of `opencybele-strict`.** The gate is 10 for 10 within one
  session, and the ledger contains one entry. The cross-occasion claim needs a second invocation
  after a reboot or hours later; §6.3 is the procedure and the gate refuses to fake it.
* **Whether 200 ms is right for a scenario other than this one.** It was measured on 52 runs of one
  topology at one pace. The width is a constructor parameter for that reason, and
  `OpenCybeleStrictIT`'s segment floor is what would notice if a future scenario made it degenerate.
* **The DEF-22 hang rate**, which `docs/kernel-config.md` already calls an estimate from a sample
  not designed to measure it. None of the 52 runs recorded here hung; at ~2 % that is ~1 expected,
  so their absence is evidence of nothing.
