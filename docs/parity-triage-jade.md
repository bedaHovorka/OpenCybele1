# `parity-triage-jade.md` — the L3 triage log for the JADE port

> Issue [#39](https://github.com/bedaHovorka/OpenCybele1/issues/39) (`Phase1.md` 1-POST.3). Every
> diff between a JADE run and the goldens frozen by #24, its classification under the three-outcome
> rule, its evidence and its resolution.
>
> This file is a **deliverable, not paperwork**: [`Phase2.md` 2-PRE.1](Phase2.md) consumes it
> directly — every behaviour that a scenario *almost* missed, or that was caught only by L1/L2
> rather than L3, earns a new scenario in 2-PRE.

## 0. The rule

[`Phase1.md` L82](Phase1.md) permits exactly three outcomes and forbids the third:

| | Outcome | Action |
|---|---|---|
| (a) | **Port bug** | Fix the port. **The default assumption.** |
| (b) | **Normalizer / harness gap** | Fix the harness, then re-verify it against the OpenCybele reference. |
| (c) | "Accept the new behaviour" | **Not permitted.** Features are frozen. |

And [`Phase1.md` L7](Phase1.md): a golden may be re-recorded in Phase 1 **only** to fix a defect in
the harness itself, and any such re-record must be reproduced and verified against the reference
first.

**Nothing in this log is resolved as (c).** No golden was re-recorded, no contract level was
demoted, no width constant was changed, and no check was relaxed.

---

## 1. Verdict

**Twenty runs per scenario per implementation, machine idle, against the unmodified goldens:**

| scenario | OpenCybele reference | JADE port |
|---|---|---|
| `opencybele-lifecycle` | **20/20** | **20/20** |
| `opencybele-timers` | **20/20** | **20/20** |
| `opencybele-capacity` | **20/20** | 6/20, and 15/20 in a second corpus |
| `opencybele-congestion` | **20/20** | 3/20 |
| `opencybele-strict` | **20/20** | 0/20 |

End to end through Gradle, five consecutive full-suite runs, as a cross-check on the corpus:
`strict` 0/5, `congestion` 0/5, `capacity` 3/5, `lifecycle` 5/5, `timers` 5/5.

```
OpenCybele ref  all six characterizationIT scenarios PASSED (74 tests, 0 failures)
                parityGate opencybele-strict: 20 consecutive runs, ONE distinct trace
```

**Two of five scenarios of a 2008 Cybele application are reproduced byte-for-byte, twenty times out
of twenty, by an independent JADE implementation running against goldens frozen before the port
began.**

For the other three, the multiset of emitted lines is identical to the golden's in

* `congestion` — **20/20 runs**,
* `strict` — 7/20 runs; the other 13 differ by exactly two lines, one `VOTE.diff` straddling a
  quantiser bucket (T-07), and
* `capacity` — **20/20** in one corpus of twenty and 15/20 in another, where the five exceptions are
  a startup-latency outlier shifting the whole schedule against an absolute stop bound (T-12).

Outside those two accounted-for effects, **every difference is the placement of a burst boundary**.

`opencybele-strict`, `opencybele-congestion` and `opencybele-capacity` are **red**, and #39's
acceptance criterion "every scenario green and flake-free" is **not met**. §5 says why that is an
instrument limit rather than a port defect, and §6 records every repair that was measured and
rejected.

### 1.1 A correction to the first gate run

The first gate run (#36, one run per scenario) reported `capacity` green and `congestion` green.
Both are **flakes, not passes**: over twenty runs `capacity` is 6/20 and `congestion` 3/20, and a
single green run of either is luck at a 3 ms and 9 ms margin respectively (§4.4). The rate also
moves with machine load, in both directions and by a lot — a first corpus taken under concurrent
CPU load read `capacity` 17/20 and `congestion` 6/20 (§4.7). **A pass rate this
environment-sensitive is the symptom, not the noise**: it is what a contract looks like when its
margin is smaller than the run-to-run jitter.

---

## 2. Method

All numbers below come from raw captures taken through the harness' own adapters
(`OpenCybeleLauncher`, `JadeLauncher`) and normalized with the harness' own pipeline — the same
`DiagnosticFilter` + `CanonicalTraceNormalizer` chain `ScenarioRunner` uses, so nothing here is
measured against a normalizer the gate does not run.

* **Corpus**: 20 runs per scenario per implementation, 200 runs, plus a second 200-run corpus taken
  with the machine otherwise idle after the first was found to have been taken under
  self-inflicted CPU load (§4.7). Both are reported; the loaded one is labelled as such, because
  `COVERAGE.md` §12.2 makes load-sensitivity the *point* of the measurement rather than a nuisance.
* **Reference dist**: `/home/beda/work/wt/opencybele-ref/build/install/opencybele`, the frozen
  `opencybele-baseline` the goldens were recorded from.
* **JADE dist**: `build/install/opencybele` on `phase1/1-port`.
* Every comparison is against the **unmodified** files in `parity-tests/golden/`
  (`git diff origin/jade-develop -- parity-tests/golden` → 0 lines).

---

## 3. The triage table

| id | scenario | what differs | class | resolution |
|---|---|---|---|---|
| T-01 | all | ~240 simulated ms of shutdown chatter past the stop bound, from a racy `System.exit` | **(a)** | fixed in #36 |
| T-02 | all | drains reported AMS housekeeping as railway anomalies | **(a)** | fixed in #36 |
| T-03 | all | a relative `-Djade.file.dir` put a 25-line `FileNotFoundException` ahead of line 1 | **(a)** | fixed in #36 |
| T-04 | all | 9 lines of Cybele kernel boot banner frozen into the head of all five goldens | **(b)** | fixed in #36 — declared diagnostic by `JadeLauncher`, removed from **both** sides; no golden touched |
| T-05 | `strict` | one burst boundary placed differently: the port splits what the baseline merged | **(b)** | **not closable** — §5. Left red. |
| T-06 | `congestion` | one burst boundary placed differently, the other way round: the port merges what the baseline split | **(b)** | **not closable** — §5. Left red. |
| T-07 | `strict` | `VOTE.diff` for `(vl8, stH)` quantises to `<T~2000>` on the port and `<T~3000>` on the baseline | **(b)** | declared cost of `TIME_QUANTUM`; §4.3. Left. |
| T-08 | `capacity` | 6/20 on the port, on a 3 ms nearest gap-to-threshold margin | **(b)** | same defect as T-05/T-06, one scenario further down the margin table; §4.4 |
| T-09 | — | DEF-02 (the lost `START`) did not appear | — | "did not reproduce here", **not** "cannot happen"; §4.5 |
| T-10 | — | the port's message latency is ~4× the baseline's | **(a)** hypothesis, **investigated and rejected**; §4.1 |
| T-11 | — | the baseline's clock has 8 ms granularity, the port's has 1 ms | **(b)**, benign | §4.6 |
| T-12 | `capacity` | in 5 of 20 runs of one corpus the port started up to 1 s late, shifting the whole schedule against an absolute bound and changing which events fall inside it | environmental, **not reproduced**: 0/20 in a second corpus | §4.8 |

---

## 4. The entries

### 4.1 T-10 — the port's messages are four times slower, and that is not a defect

This is the root cause of T-05, T-06 and T-07, so it is triaged first. Under
`Phase1.md` L82 the default assumption is (a), so it was tested as (a).

Measured over 20 runs of `opencybele-strict` per implementation, 240 samples each, in **simulated**
milliseconds:

| | baseline | JADE port |
|---|---|---|
| `TRAVEL_END` → `TRAVEL_START` (the whole arrival-handover cascade, ~8 hops) | min 0, p50 **8**, p90 24, mean **13.0** | min 12, p50 **49**, p90 76, mean **52.3** |
| `PLAN_TRAIN` → `START` (the whole election) | p50 **80** | p50 **192** |
| `ENTER` → `ENTER_REPLY` (712/720 samples) | p50 **0**, p90 8 | p50 **10**, p90 29 |
| `PATH_FIND` → `PATH_FIND_REPLY` (279/280) | p50 0, p90 8, max 16 | p50 9, p90 19, max 39 |
| `VOTE_REQUEST` → `VOTE` (1837/1847) | p50 8, p90 16, max 64 | p50 8, p90 36, max 167 |

At pace 8 the port's ~52 simulated ms cascade is ~6.5 ms of wall clock over eight hops — under
1 ms per ACL round trip with a serialized content object. Three things account for it and **none of
them is a bug in the port**:

1. **#27's pinned wire form.** The port marshals with `setContentObject`; the baseline crossed a
   channel *by reference* under `Local;NoSerialization` ([#4](https://github.com/bedaHovorka/OpenCybele1/issues/4)).
   Serialization is the correct port of an ACL message, not an inefficiency to remove.
2. **The observer is no longer free.** On Cybele a second `openChannel` on a channel that already
   had a handler cost the first subscriber nothing (INVENTORY `SEM-03`) — genuine multicast. JADE
   has no such thing, so `TraceTopics` adds the channel's topic AID as a **second receiver** on
   every application message. **The goldens were recorded through a free observer and the port is
   judged through a costly one.** Part of the latency the goldens penalise is created by the
   measuring apparatus. This is logged for 2-PRE as an observer-effect asymmetry, not as a port
   defect: it is #27's decided mechanism and #36's wiring.
3. **The clock's granularity** — T-11.

**Resolution: (a) rejected on evidence.** And it does not matter for the outcome anyway: §5.3 shows
that even an exact latency match could not make `strict` green, because the boundary that decides it
is 4 ms wide on the baseline itself.

### 4.2 T-05 / T-06 — the two burst boundaries

Both scenarios' normalized traces are the **same multiset** as their goldens, at the same length
(`strict` 598 = 598, `congestion` 167 = 167). What differs is which lines share a burst, and
therefore how `burst-order` sorts them.

**`opencybele-strict`, at golden line 286.** The golden holds one 26-line burst containing the tail
of `vl3`'s arrival at `stH` *and* `vl0`'s arrival at `stC` — two causally independent cascades that
happened to fall 72 simulated ms apart when the golden was recorded. On the port they fall 223–227
ms apart, the burst splits, and the two cascades sort separately:

```
 286  G stC|<T>|STATION_INFO|stC|Main|<P>|occupied=<N>,capacity=2
      R stH|<T>|PATH_FIND_REPLY|Main|stH|<P>|target=stB,direction=tr2
```

**`opencybele-congestion`, at golden line 64.** The mirror image: the golden holds two bursts
448 ms apart (`vl0`'s arrival, then `vl1`'s departure); on the port the port's longer arrival
cascade ends later, the gap closes to 214 ms, the two bursts **merge**, and the sort interleaves
them.

One needs a width **below 214**; the other needs **227 or more**. They are mutually exclusive, and
§6 shows that no width, no contract level and no re-recording resolves them.

**The same boundary fails on the baseline.** In the loaded corpus, three of twenty baseline runs of
`opencybele-strict` fail their own golden — with an identical multiset, at **the same line, in the
same direction**:

```
 295  G stC|<T>|STATION_INFO|stC|Main|<P>|occupied=<N>,capacity=2
      R stH|<T>|PATH_FIND_REPLY|Main|stH|<P>|target=stB,direction=tr2
```

That is the decisive evidence for (b): the port is being failed by a boundary the implementation
that *produced the golden* cannot place reproducibly.

### 4.3 T-07 — the `diff` quantum straddle on `(vl8, stH)`

`VOTE.diff` is quantised to 1 000 simulated ms (`TIME_QUANTUM`). The raw values, 20 runs each:

| | range | quantises to |
|---|---|---|
| baseline | 3214 … 3254 | `<T~3000>`, every run |
| JADE port | 2809 … 3116 | `<T~2000>` in 10 runs, `<T~3000>` in 10 |

The port's value is systematically **~271 ms lower**, which is exactly T-10: the election takes
192 ms rather than 80, so the delay `stH` demands against a schedule-fixed timetable slot is that
much smaller. The value straddles a bucket boundary and the quantiser has no answer for that — it is
the cost `CanonicalTraceNormalizer` already states out loud ("values below one quantum are
indistinguishable from zero"), showing up at a boundary rather than at zero.

**Resolution: (b), left as it is.** Widening the quantum would hide real arithmetic; narrowing it
re-breaks the 15 varying values the 1 000 ms bucket was chosen to absorb (`trace-normalizer.md`
§2.2). Recorded so that a future `strict` failure on this line is recognised in one step, and so
2-PRE can decide whether a scenario should pin `diff` somewhere with more headroom.

### 4.4 T-08 — `opencybele-capacity` passes with a 3 ms margin

Nearest approach of any raw inter-line gap to the 220 ms segmentation width, min and median over 20
runs:

| scenario | baseline | JADE port |
|---|---|---|
| `opencybele-strict` | **4** / 12 | **0** / 10 |
| `opencybele-congestion` | 124 / 180 | **9** / 49 |
| `opencybele-capacity` | 56 / 60 | **3** / 16 |
| `opencybele-lifecycle` | 164 / 180 | 118 / 163 |

`capacity` was reported green by the first gate run. Over twenty runs it is **6/20**, and its
margin is 3 ms. It goes green at all only because a boundary can move without changing the output —
two lines either side of it may sort the same way whether they share a burst or not, which
`trace-normalizer.md` §3 already says about the segment count. **That is luck, not a property.**

The margin column predicts the pass rate exactly, in rank order and on both implementations:

| scenario | port's nearest margin | port's pass rate |
|---|---|---|
| `opencybele-lifecycle` | 118 ms | 20/20 |
| `opencybele-timers` | (no gap in [120, 400]) | 20/20 |
| `opencybele-capacity` | 3 ms | 6/20 and 15/20 |
| `opencybele-congestion` | 9 ms | 3/20 |
| `opencybele-strict` | 0 ms | 0/20 |

A scenario passes when its gaps are far from the threshold and fails when they are near it, and the
small-margin rows swing wildly between corpora while the large-margin rows do not move at all.
Nothing about the port's *behaviour* varies across that table: outside T-07's quantiser straddle and
T-12's non-reproducing startup outlier, every run of every scenario emits the same multiset as its
golden.

### 4.5 T-09 — DEF-02 did not reproduce

The predicted extra/dropped departures are absent at these seeds and bounds; the departure id set is
`[vl0 … vl8]` in every run of both implementations. Recorded as **"did not reproduce here"**, not
"cannot happen".

Note also what it is *not*: JADE **structurally cannot** lose a `START` the way the baseline's
channel race does. That is a known divergence, out of reach of any scenario at these bounds, and it
means a green `strict` would never have proved the port reproduces DEF-02 in the first place.

### 4.6 T-11 — the two clocks have different granularity

The baseline's `Cybele.getTime` advances in **8 ms** steps; the port's `SimClock` (#29) advances in
1 ms steps. Field 2 is erased by the normalizer, so this produces **no golden diff of its own** — but
it changes the gap statistics the segmentation reads. Over 20 `strict` runs, 11 740 inter-line gaps
each:

| | gaps of exactly 0 ms | gaps ≤ 8 ms | gaps > 220 ms |
|---|---|---|---|
| baseline | 10 159 | 11 166 | 424 |
| JADE port | 2 738 | 10 123 | 393 |

The baseline's coarse clock collapses most short gaps to zero. Benign, logged, and relevant to
anyone who later tries to reason about burst structure from tick values.

### 4.7 The measurement environment, and a retraction

The first 200-run corpus was taken while width sweeps were running on the same machine. Every
number in this file comes from the **second**, idle corpus unless it says otherwise; the loaded one
is kept because the comparison is itself a result.

| | baseline `strict` | port `capacity` | port `congestion` |
|---|---|---|---|
| under concurrent CPU load | **17/20** | 17/20 | 6/20 |
| machine idle | **20/20** | 6/20 then 15/20 | 3/20 |

Three things to take from it.

* **The baseline does not flake when idle.** 20/20 by the corpus and one distinct trace in 20 by
  `parityGate`. The earlier "3 in 20" was measured under load and is reported as such.
* **Under load the baseline fails its own golden at exactly the line the port fails it** — same
  multiset, same direction (§4.2). That is the single strongest piece of evidence that T-05 is (b).
* **The port's rates move by more than the effect being measured, and not in a consistent
  direction.** `capacity` reads 17/20 loaded, 6/20 idle, and 15/20 idle again twenty minutes later.
  A contract whose verdict swings that far on machine state is not measuring the program.

`COVERAGE.md` §12.2 is explicit that the dangerous gaps are the ones load moves and that gaps must be
scanned **under CPU load**. This is that scan, run against two implementations.

### 4.8 T-12 — a startup-latency outlier, chased down and cleared

The first quiet corpus of `opencybele-capacity` contained five runs that differ from their golden by
**multiset**, not by order — 14 to 17 lines, and the single `stB` slot (capacity 1) going to `vl2`
where the golden gives it to `vl1`. That is a behaviour difference on its face, and under L82 it is
the port's until proven otherwise. It was chased.

The five runs are runs 12–15 plus one more — **consecutive** — and they share one signature: the
tick of the first `STATION_INFO`, i.e. the instant the static objects finish registering.

| | first canonical tick, min … max over 20 runs |
|---|---|
| baseline, `capacity` | 504 … 560 (spread **56**) |
| port, `capacity`, corpus 1 | 103 … 1168 (spread **1065**) |
| port, `capacity`, corpus 2 | 85 … 500 (spread 415, one outlier) |

`vl0`'s first departure tracks it exactly (3374 … 5345 in corpus 1) and the *inter*-departure offsets
are identical to the millisecond in every run — 8 000, 5 000, 8 000 — so the schedule is **shifted,
not recomputed**. The elections, the routes and the arithmetic are the same; what changes is which
events fall inside `sim.stop.maxClockMs = 27700`.

**It did not reproduce.** A second corpus of twenty runs taken later the same session:
**all 20 multiset-identical**, 15/20 matching outright. So this is a machine transient — a cluster
of slow JVM starts — amplified into a content difference by a scenario whose bound leaves no margin
for startup jitter.

It is logged rather than dismissed for two reasons. It is the **only** difference in this whole run
that was not a reordering, so a future occurrence must not be re-investigated from scratch; and
`COVERAGE.md` §12.1's bound margin is measured from *last train creation* to the bound and models
nothing about how late the run starts. That is a gap in the scenario-authoring rule, and it is 2-PRE
work.

---

## 5. Why T-05 and T-06 are not closable

### 5.1 The golden is a fixed point, so the width is one-sided

`ScenarioRunner` normalizes the golden with the same normalizer before comparing — the mechanism
that let #21 land without re-recording. `CanonicalTraceNormalizer` detects an already-projected
trace (no parseable tick in field 2) and **skips every ordering rule** on it, for idempotence.

The consequence had not been written down, and it decides this issue. Measured:

```
normalize(<each of the five opencybele-*.txt goldens>) at w = 0, 8, 100, 220, 5000, 100000
  -> byte-identical to the file on disk, at every width, for all five
```

**The golden's line order is frozen at the width it was recorded under, and cannot be recomputed.**
`DEFAULT_SEGMENT_GAP_TICKS` therefore re-segments the *run only*. The comparison it defines is not
"do these two runs agree up to burst order" but "does the port reproduce the burst partition the
baseline recorded" — i.e. reproduce the baseline's **message latency**, not its behaviour.

### 5.2 The sweep, and why it bottoms out

20 `strict` captures on the port, swept over the width:

| w | 0 | 8 | 24 | 60 | 100 | 150 | **220** | 250 | 300 | 400 | 1000 | 2000 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| runs matching | 0 | 0 | 0 | 0 | 0 | 0 | **0** | 0 | 0 | 0 | 0 | 0 |
| smallest positional diff | 531 | 292 | 111 | 46 | 46 | 73 | **47** | 47 | 47 | 125 | 332 | 463 |
| segments in the run | 403–472 | 58–82 | 27–35 | 24–27 | 22–25 | 21–23 | **19–22** | 18–21 | 17–20 | 13–15 | 8 | 4–5 |

It never reaches zero, and it cannot: each width repairs some boundaries and breaks others, because
the golden's side of every boundary is frozen.

Per-scenario widths do not rescue it either — `congestion` peaks at 16/20 (w = 100) and `strict` is
0/20 at every width — so "give each scenario its own width" buys a partial repair of one scenario at
the cost of turning a contract constant into a per-scenario knob tuned against the implementation
under test.

### 5.3 No port change can close it

The boundary that decides `strict` sits **4 ms** from the threshold on the baseline (§4.4). Even a
port whose message latency matched the baseline exactly would be a coin toss there, and the ceiling
is the baseline's own reproducibility at that boundary. This is a property of the scenario's
margins, which `COVERAGE.md` §12.2 already flagged: `opencybele-strict`'s bound and golden predate
#23, its margins were never re-tuned, and its 28 ms nearest approach was "recorded rather than
fixed, so that a future flake there is diagnosable in one step".

This is that flake, and this log is that one step.

---

## 6. Repairs measured and rejected

Each of these was implemented and run against both corpora before being rejected. None is rejected
on taste.

| candidate | result | why rejected |
|---|---|---|
| **Widen / narrow `DEFAULT_SEGMENT_GAP_TICKS`** | `strict` 0/20 at every w in 0…2000 | §5.2 — the golden's side is frozen, so no width is two-sided |
| **Per-scenario widths** | `congestion` 16/20 at w=100; `strict` 0/20 at every w | partial at best, and turns a contract constant into a knob tuned per implementation |
| **Demote `strict`/`congestion` to `causal`** | `strict` **0/20**, `congestion` 6/20 — no better than `strict` contract | the reordered lines are `st*`/`tr*` subjects; the scenario's `entity.pattern` is `^(vl\d+)\|`, so they land in the unattributed bucket, which `causal` compares **in order**. The demotion would cost coverage and buy nothing. |
| **Content-derived epoch partition** (cut at `PLAN_TRAIN`, `START`, `TRAVEL_*`; sort within an epoch; applied to golden and run alike) | **not idempotent** for every non-empty cut set | sorting inside an epoch moves the cut lines, so a second pass partitions differently. `ScenarioRunner` normalizes the golden on every comparison, so a non-idempotent ordering rule can never match its own recording. |
| **Global sort of the body** (the only idempotent content-derived ordering) | baseline 20/20, port 20/20 on four scenarios, 8/20 on `strict` | pins **a multiset and no order at all** — the "lock that cannot fail" shape this project has rejected four times, guarded by `OpenCybeleStrictIT`'s segment-count floor |
| **Admitted resolution: boundaries within ±band of the width are undecided, and a run passes if *some* assignment reproduces the golden** | `strict` **0/20** at ±0, ±10, ±20, ±30, ±40, ±60, ±100, ±150; `congestion` climbs 6 → 19/20 but only at ±150, where 8 boundaries per `strict` run are undecided; at ±230 the whole trace is one undecided region | the discrepancies are not symmetric about the threshold: a gap of 448 ms on the baseline is 214 ms on the port, so the band needed to cover them is the degenerate one |
| **Per-adapter latency normalisation** (width scaled by the run's own reaction-latency percentile) | implied w ≈ 500 for the port; measured `strict` diff at w=500 is 184, **worse** than at 220 | rejected by measurement, before the question of whether it smuggles in (c) had to be answered |
| **Re-record the goldens under a repaired normalizer (L7)** | not requested | §7 |

---

## 7. The L7 question, answered explicitly

**No golden was re-recorded, and none is requested.** The case was considered and does not hold:

* L7 permits a re-record to fix a **normalizer defect**, and T-05/T-06 *are* a normalizer defect
  (§5.1). So the exception is available in principle.
* It would not help. A re-recorded golden still freezes **the baseline's** burst partition, because
  the partition is a function of the baseline's message latency and the recording is taken from the
  baseline. The port would fail the new golden for exactly the reason it fails this one.
* Re-recording from the *port* would be outcome (c) under another name, and is forbidden.
* Re-recording with raw ticks kept, so that both sides could be segmented at compare time, fails the
  same way: the golden's ticks are the baseline's ticks, and §4.2's 448-versus-214 pair has no
  common threshold.

`parity-tests/golden/**` is byte-identical to `origin/jade-develop`.

---

## 8. What did change

One thing, and it is additive: **the failure report now answers the first triage question itself.**
`DiffAnatomy` (`src/characterizationIT/.../golden/DiffAnatomy.java`) is appended to the details of a
failed golden comparison and states

* whether the run is the **same multiset** as the golden — i.e. whether this is a behaviour
  difference (a) or an ordering difference to be chased into the ordering rules (b) — and the
  surplus lines on each side when it is not; and
* the raw inter-line gaps **nearest the burst width actually in use**, with their margins and
  whether each was merged or split.

It is measurement only. Nothing it computes feeds back into `TraceComparator`; a run that failed
before still fails, with the same verdict. Deriving those two facts by hand is what this issue's
measurement campaign spent its first day on.

What a failing `opencybele-capacity` now prints, verbatim:

```
- SAME MULTISET: every line of the golden occurs in the run, with the same multiplicity. The run
  did the same things in a different order, so this is an ORDERING difference — look at the
  segmentation margins below before looking at the port.
- burst width in use: 220 simulated ms (burst-order). The gaps nearest it, on the RAW capture — a
  gap within a few ms of the width is a coin toss, and when it crosses, two bursts merge or split
  and the sort re-orders them (COVERAGE.md §12.2):
-   gap 219 ms (margin -1, merged) between  stF|10509|STATION_INFO|... | vl1|10728|TRAVEL_END|...
-   gap 209 ms (margin -11, merged) between stC|17760|STATION_INFO|... | vl6|17969|PLAN_TRAIN|...
-   gap 255 ms (margin +35, split)  between stG|15340|STATION_INFO|... | vl4|15595|PLAN_TRAIN|...
-   gap 255 ms (margin +35, split)  between vl6|18014|VOTE_RESULT|...  | vl7|18269|PLAN_TRAIN|...
-   gap 256 ms (margin +36, split)  between stH|13848|STATION_INFO|... | vl0|14104|TRAVEL_END|...
```

A boundary one millisecond from the threshold, named in the failure itself.

---

## 9. Reproducing this

```bash
# the port
./gradlew installDist
./gradlew characterizationIT -Pjade.dist=$PWD/build/install/opencybele

# the reference — must stay green, and does
./gradlew characterizationIT -Popencybele.dist=/abs/path/to/opencybele-ref/build/install/opencybele
./gradlew parityGate -Popencybele.dist=... -Dparity.gate.runs=20 -Dparity.gate.scenario=opencybele-strict
```

---

## 10. Handover to `Phase2.md` 2-PRE.1

1. **`opencybele-strict` and `opencybele-congestion` need their margins re-tuned before Phase 2**,
   under §12.2's rule and now against *two* implementations' gap distributions rather than one. A
   scenario whose nearest gap-to-threshold margin is 4 ms is not a contract, it is a coin toss with
   a golden attached.
2. **`opencybele-capacity` is green on a 3 ms margin** (T-08) — the next scenario in this class to
   go red, and it will look like a port bug when it does.
3. **The observer is not free on JADE and was free on Cybele** (T-10.2). Any Phase 2 scenario that
   depends on message timing is measuring the probe as much as the system. Jason (#43/#48) will have
   a third answer again.
4. **`VOTE.diff` sits on a quantum boundary in `strict`** (T-07). A scenario that means to pin the
   election's arithmetic should place the value where a 1 000 ms bucket has headroom.
5. **DEF-02 did not reproduce and structurally cannot on JADE** (T-09). If the migration is meant to
   demonstrate anything about the original's defects, it needs a scenario that observes them
   directly rather than a golden that happens to contain them.
6. **Two scenarios reproduce byte-for-byte across two independent agent kernels** — `lifecycle`
   and `timers`, 20/20 each. That is the result worth carrying forward, and it is worth saying that
   it was obtained with the goldens untouched.

   *(Corrected by [#41](https://github.com/bedaHovorka/OpenCybele1/issues/41): this line said
   **three**, which contradicts §1's "two of five" and could only have been counting `capacity` on
   its good corpus — which §1.1 and §4.4 call "luck, not a property". It is the sentence in this
   file most likely to be lifted into a DoD verdict, so it says two.)*
