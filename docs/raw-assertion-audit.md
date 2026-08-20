# Raw-line property assertions: the full enumeration, the mechanism, and what changed

Issue [#72](https://github.com/bedaHovorka/OpenCybele1/issues/72) · Phase 1, stage 1-PORT ·
harness-only. Companion to [`trace-normalizer.md`](trace-normalizer.md) (the eleven rules),
[`trace-format.md`](trace-format.md) (what field 2 is) and
[`../parity-tests/scenarios/COVERAGE.md`](../parity-tests/scenarios/COVERAGE.md) §10.11–10.12 (the
two coverage gaps this audit leaves standing).

## 0. The class of defect

A parity scenario is judged twice: by a **golden comparison** of the normalized trace, and by
**property assertions** in `src/characterizationIT/.../it/*IT.java`. Some of those properties are
about quantities `CanonicalTraceNormalizer` deliberately erases or reorders, so they have to read
the **raw** capture — `report.captured().lines()` — because the projected trace no longer carries
them.

That is legitimate and sometimes unavoidable. What is not legitimate is reading, off the raw
capture, something the raw capture does not actually record. **The order of the raw lines is such a
thing.** An assertion that derives a property from it fails a few percent of the time while every
golden comparison passes — which reads as a port bug to #39 and as flake to everybody else.

> Measured over 90 alternating full-suite runs before this issue: **0** golden divergences,
> `grep -l "differ at line"` matching 0 of 90 logs, and 4 property-assertion failures. The goldens
> were never the problem.

## 1. What the normalizer erases or reorders

The eleven rules, and what a raw-reading assertion may therefore *not* infer from the projected
trace — i.e. the list an assertion has to be checked against.

| # | rule | kind | quantity it removes |
|---|---|---|---|
| 1 | `tick` | erase | field 2, on **every** line |
| 2 | `expected` | erase | `VOTE_REQUEST.expected` |
| 3 | `planned` | erase | `VOTE_RESULT.planned` |
| 4 | `diff` | quantise (1000 ms) | `VOTE.diff` below one quantum |
| 5 | `departure` | erase | the departure instant in the application's own `println` |
| 6 | `occupied` | erase | `STATION_INFO.occupied` |
| 7 | `road-state` | erase | `ROAD_STATE.state` — `FREE` / `TRAVEL_LEFT` / `TRAVEL_RIGHT` |
| 8 | `performative` | erase | field 6 |
| 9 | `startup-block` | reorder | the opening `STATION_INFO`/`ROAD_STATE` run, sorted |
| 10 | `burst-order` | reorder | **line order within any burst** (tick gap ≤ 220 ms) |
| 11 | `println-streams` | reorder | the two `println` families, lifted out and re-grouped |

Rules 1, 7 and 10 are the ones this audit is about. Rule 10 is the dangerous one, because an
assertion can depend on line order without ever mentioning it.

## 2. The mechanism, measured

`docs/trace-format.md` already states it, in the section "`tick` — simulated, and read at handling
time":

> It is the clock as the probe reads it **when it handles the message**, not when the sender sent
> it. […] lines that share a tick are emitted in whatever order the kernel delivered them.

So the raw stream is the probe's *handling* order, and handling order is not send order. Two lines
whose causes are strictly ordered — an `ENTER_REPLY` cannot exist before the `ENTER` it answers —
can still be printed the other way round.

**Measured on this tree**, 150 standalone captures (the launcher's own command line, one child JVM
per capture, machine otherwise idle), counting captures containing at least one pair printed in the
opposite order to the one that caused it:

| scenario | captures | with ≥1 causal inversion | kinds seen |
|---|---|---|---|
| `opencybele-lifecycle` | 60 | **2** | `ENTER_REPLY` before its own `ENTER` ×2 |
| `opencybele-congestion` | 40 | **5** | `ENTER_REPLY` before its `ENTER` ×2; `LEAVE` before the `ENTER_REPLY` that caused it ×3 |
| `opencybele-capacity` | 25 | **1** | `LEAVE` before the causing `ENTER_REPLY` ×1 |
| `opencybele-timers` | 25 | **5** | `ENTER_REPLY` before its own `ENTER` ×5 |
| **total** | **150** | **13 (8.7 %)** | |

Every inverted pair shares a tick or is 8 ms apart. One example, `opencybele-lifecycle` at the
default seed, the four lines a train's first two hops produce — causally
`ENTER stA → ENTER_REPLY stA → ENTER tr1 → ENTER_REPLY tr1`:

```
vl1|29528|ENTER|vl1|stA|-|train=vl1,position=null,target=stB
vl1|29528|ENTER_REPLY|tr1|vl1|-|object=tr1,next=stB      <-- printed 4 positions early
vl1|29528|ENTER_REPLY|stA|vl1|-|object=stA,next=tr1
vl1|29528|TRAIN_STATE|vl1|Main|-|state=stA -> stB : entered to stA
vl1|29528|ENTER|vl1|tr1|-|train=vl1,position=stA,target=stB
```

One line out of place; two assertion failures, because the old DEF-07 checker's pairing queue was
built by walking the stream and never recovered.

**This is not a probe defect and must not be "fixed" in the probe.** Handling-time reads from one
serial activity are what make field 2 monotonically non-decreasing over the whole trace, which is
the only ordering invariant the format has and the reason a normalizer can bucket by tick at all
(`trace-format.md`, same section). Send-time stamping from N concurrent agents would lose it.

## 3. The enumeration

Every site in `src/characterizationIT` that reads the raw capture. "Reads" is the whole test: an
assertion is a member of this class if the value it decides on could change when the capture is
permuted within a burst, or when a tick moves by the 8–32 ms the probe's dispatch costs.

| # | site | reads | quantity | rule that removes it | verdict |
|---|---|---|---|---|---|
| 1 | `OpenCybeleCongestionIT.queuedHandovers` → the `opposingDirection` assertion | **line order across trains**, plus a cross-train `road → station` register mutated while walking | which way each train went down a contended track | 10 (`burst-order`); the direct witness `ROAD_STATE.state` is gone to 7 | **BROKEN — rewritten** |
| 2 | `OpenCybeleLifecycleIT.assertLeaveFollowsEnterReplyAfterTheFirstHop` | **line order per train** | DEF-07's `ENTER_REPLY`-before-`LEAVE` emission order | 10 | **BROKEN — replaced** |
| 3 | `OpenCybeleCapacityIT.assertARoadQueueReachedDepthTwo` | **line order**: push on `ENTER`, pop on `ENTER_REPLY` | road queue depth ≥ 2 | 10 | **BROKEN, silently — rewritten** |
| 4 | `OpenCybeleTimersIT.assertTheNoiseDecidesBurstMembership` | raw tick **difference** of a causally paired `TRAVEL_START`/`TRAVEL_END` | the Gaussian travel noise | 1 (`tick`) | **safe — documented, not changed** |
| 5 | `OpenCybeleCongestionIT.assertGoldenCanSeeIt` | raw tick **difference** of one train's `ENTER`/`ENTER_REPLY` on one road | that the queued wait crosses a burst boundary | 1 | **safe — kept** |
| 6 | `OpenCybeleStrictIT.assertOrderIsStillAsserted` | raw ticks, via `normalizer.segments(filtered)` | how many bursts the run has | 1 | **safe — floor of 10 against a measured 22–23** |
| 7 | `OpenCybeleCapacityIT.assertAStationRefusedATrain` | set difference over `(train, station)` keys | that some station `ENTER` went unanswered | — | **safe — order-free by construction** |
| 8 | `OpenCybeleTimersIT.assertFiringCounts` | counts of matching lines | timer firing counts | — | **safe** |
| 9 | `ScenarioAssertions.assertPropertiesReachedTheChild` / `assertNothingThrew`, reached from every scenario `*IT` including `OpenCybeleSmokeIT` | line content, matched anywhere in the stream | the config banner, throwables | — | **safe** |
| 10 | `ScenarioRunner`'s liveness rules (every scenario) | counts, and counts of distinct capture groups | liveness floors | — | **safe — counting, never correlating** |

Items 7–10 are listed so the sweep is total: a future reader must be able to see that they were
looked at and why they are not in the class, rather than that they were missed.

### 3.1 Why #3 is worse than #1 and #2

A flaky assertion announces itself. #3 did not: an `ENTER_REPLY` printed before its own `ENTER`
popped nothing, so the train stayed on the road's pending list for the rest of the run and the
**next** ordinary entry to that road satisfied "two trains at once". The claim then passes for a
reason that has nothing to do with a queue.

Measured, by running the old detector over captures of scenarios that provably never reach depth two
(one train at a time on the only road in `opencybele-lifecycle`):

| capture set | old, order-based detector claims depth two | rewritten, causal detector |
|---|---|---|
| `opencybele-capacity` × 25 (should be **yes**) | 25/25 | **25/25** |
| `opencybele-congestion` × 40 (should be **no**) | **1/40 — false positive** | 0/40 |
| `opencybele-lifecycle` × 40 (should be **no**) | **1/40 — false positive** | 0/40 |

Both false positives are in exactly the two captures §2's inversion detector flags.

## 4. What replaced them

All three rewrites use one helper, `TrainTrace`, which re-reads a capture as causal structure:
`(train, object)`-keyed payload facts, plus tick **intervals whose width is seconds**. No method in
it depends on the position of a line in the list.

* **Route** — chained from each train's own `ENTER.position` fields, starting at `position=null`.
  Shuffling the capture cannot change it.
* **Direction on a road** — `ENTER.position` (the end it came from) and `ENTER_REPLY.next` (the end
  it will be handed to). `RoadAgent.acceptTrain` replies `rightStation` going right and
  `leftStation` going left, so a reversed pair of traversals is exactly `from == other.to && to ==
  other.from`. Payload only.
* **"The road queued it"** — the branch condition itself. `RoadAgent.enter` pushes exactly when
  `state != FREE`, i.e. exactly when another train's traversal interval `[ENTER_REPLY, LEAVE)`
  contains the request's tick. And an `ENTER` a road **never answered** is queued by construction,
  since `acceptTrain` always replies inside the handler — no threshold needed for that arm.

| site | what it now asserts |
|---|---|
| 1 · congestion | the queued traversal and the traversal it was queued behind are exact reverses of one another, both read from payload; the wait still has to cross the burst boundary (item 5, unchanged) |
| 2 · lifecycle | the route reconstructed from `ENTER.position` is one unbroken chain to `target`; every visited object answered once with an `ENTER_REPLY.next` that **agrees with that route**, `null` only at the destination; every visited object released exactly once, the destination by the destructor |
| 3 · capacity | two queued requests on one road whose waits overlap |

Item 2's replacement is **strictly stronger than the surviving half of what it replaced**: the old
version never checked the route at all, and captured `ENTER_REPLY.next` in its regex only to discard
it. `next` is the field that would catch a port routing a train to the wrong end of a track, and it
is now compared against the route on every hop of every train.

Validated over the captures in §2 before any suite run: 100/100 clean on `route`/`leave`
conservation (60 lifecycle + 40 congestion), 40/40 on the congestion handover, 25/25 on capacity
depth two, with the specificity check in §3.1.

## 5. What this audit does **not** fix

Two gaps are left open deliberately and are recorded in `COVERAGE.md` rather than papered over:

1. **`ROAD_STATE.state`'s `TRAVEL_LEFT`/`TRAVEL_RIGHT` label is pinned by nothing** — not by a
   golden, and not by the assertion in item 1 either, which compares two traversals to each other
   and never names a side. `COVERAGE.md` §10.11 records the measurement, and why the label turns out
   to be behaviourally inert.
2. **DEF-07 is only visible on a queued hop.** `COVERAGE.md` §10.12 and `defect-triage.md` §3.1.

## 6. Writing a raw-reading assertion that will not do this again

1. Name the rule from §1 that removes your quantity from the projected trace. If none does, read the
   normalized trace instead — it is the artefact under contract.
2. Derive the property from **payload keyed by entity**, not from where lines fell. If permuting the
   capture within a burst changes your answer, you are reading the scheduler.
3. If you must compare ticks, compare a **difference between two causally paired lines**, and show
   the distribution has an empty band around your threshold. `assertGoldenCanSeeIt`'s 0–32 ms versus
   424–448 ms is what that looks like; a threshold in a continuum is a tuning constant and will
   cross.
4. Check your detector against a capture set where the answer should be **no**. §3.1 is the whole
   argument for step 4.
