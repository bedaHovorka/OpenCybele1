# COVERAGE.md — Stage-0 inventory row → scenario id

> Produced for [#23](https://github.com/bedaHovorka/OpenCybele1/issues/23) (*Scenario coverage
> extension*), which implements [`Phase1.md` 1-PRE.3](../../docs/Phase1.md).
> Subject: [`docs/INVENTORY.md`](../../docs/INVENTORY.md) — every family it defines: CNT, AG, ACT,
> CH, EVT, TMR, ST, CFG, NDT, OUT, GUI, SEM and DEF.
>
> **Read this before adding or retuning a scenario.** [`Phase2.md` L28](../../docs/Phase2.md)
> freezes the scenario set after 2-PRE and [`Phase1.md` L7](../../docs/Phase1.md) forbids
> re-recording a golden for anything but a harness defect. A coverage gap found after the freeze
> cannot be closed without weakening the contract, so every gap below is either **named and
> justified by a measurement** or it is a mistake.

## The rule this file follows, and the two ways it was got wrong

Every inventory row is either

* **mapped** — at least one scenario produces trace output that changes if that row's behaviour
  changes; or
* **unmapped, with a written justification** — and the justification is a *measurement* or a
  citation of one, never "did not get to it".

"Mapped" is deliberately stronger than "the code ran". `Station.enter` runs in every scenario, and
before revision only one of its two branches did.

**Two claims in the first revision of this file were falsified in review, and both failures were of
the same kind: a claim about the implementation, checked only against the goldens.** Deleting lines
from a golden shows that the golden is compared; it does not show that the golden *pins the
behaviour you say it pins*. The instrument for that is an **implementation mutant** — patch the
baseline, rebuild, and see whether the suite still passes. Two did:

| implementation mutant | before revision | now |
|---|---|---|
| `Math.max(0, delayInSeconds() + (long)(500*nextGaussian()))` — a port that clamps DEF-16 | **suite PASSED** | still passes; **DEF-16 is now recorded as unmappable** (§10.9) |
| `Station.computeDifference`'s under-capacity arm returns `0` | **suite PASSED** | **fails** — `opencybele-capacity` raises the vote above the `diff` quantum (§10.10) |

and one claim about reachability was simply wrong (§10.1). The lesson is written into §12.3.

---

## 1. The scenario set

Five scenarios, all `strict`, all headless, all at seed `20080415`, all with the canonical trace
probe on and all twenty `sim.*` keys declared (`ScenarioCatalogIT` asserts that totality).

| id | shape | pace | bound | golden | what it is *for* |
|---|---|---|---|---|---|
| [`opencybele-strict`](opencybele-strict.yaml) | 8 stations, 7 tracks, 6 OD pairs, 9 trains | 8 | 24 000 | 607 lines | **the full-system scenario.** The shipped topology, unchanged: the only scenario with all six default routes, all seven tracks, and the `Station.computeDifference` over-capacity branch at scale. |
| [`opencybele-lifecycle`](opencybele-lifecycle.yaml) | 2 stations, 1 track, 2 trains | 8 | 40 000 | 84 lines | **agent lifecycle and startup/shutdown.** The smallest network the application accepts, so its golden is short enough to check by hand — the scenario a port is brought up against first. All fifteen channels; both trains run to their destination and die. |
| [`opencybele-timers`](opencybele-timers.yaml) | 3 stations, 2 tracks (**one with delay 0**), 3 trains | 8 | 23 500 | 183 lines | **the four one-shot timer sites**, as counts over a bounded window, plus the only place the travel-time noise (CFG-04) is observable on its own. It does **not** pin DEF-16 — §10.9. |
| [`opencybele-congestion`](opencybele-congestion.yaml) | 3 stations fed from **both ends**, 2 tracks, 4 trains | 8 | 26 900 | 176 lines | **the road queue and the deferred departure.** A train queued on a contended track in the *opposing* direction and handed over from inside `RoadAgent.leave`. |
| [`opencybele-capacity`](opencybele-capacity.yaml) | 3 stations, **middle one capacity 1 and an origin**, 2 tracks, 9 planned / 4 departed | **4** | 27 700 | 230 lines | **a full station refusing a train, and the cascade behind it.** Added in revision. The only scenario that reaches `Station.enter`'s `queue.offer` branch, the only one where a road queue reaches depth two (so `OueueItem.compareTo` runs at all), and the only one where `sim.station.voteWindowMs` differs from `sim.arrival.lambdaMs`. |

`smoke-stub` is not in this table: it drives the stub adapter, carries no application, and covers no
inventory row. It is the harness' own self-test.

### `opencybele-smoke` was folded into `opencybele-strict`

`opencybele-smoke.yaml` was `opencybele-strict.yaml` with `sim.stop.maxClockMs = 25000` instead of
`24000`, and both files asked #23 to fold them.
[`docs/trace-normalizer.md` §5.1](../../docs/trace-normalizer.md) has the measurement: at 25000 the
trace's last event burst *begins* 72 simulated ms **after** the bound, so 12 runs in 38 truncated —
which is why that file could only hold a `summary` contract, and a `summary` contract is where a
late DEF-22 wedge hides inside a tolerance. At 24000 the bound sits inside a measured 1952 ms quiet
window and 14 of 14 captures were byte-identical.

So the fold kept the stronger of the two. `opencybele-strict.txt` was **not** re-recorded; the
adapter assertions that lived in `OpenCybeleSmokeIT` were pointed at the surviving scenario, and
`ScenarioCatalogIT.smokeAndStrictStayFolded` fails if a near-duplicate is re-added.

---

## 2. Evidence

### 2.1 The flake gate — established today, one half of it

```
./gradlew parityGate -Popencybele.dist=<…>/build/install/opencybele -Dparity.gate.runs=10
```

| scenario | runs | distinct normalized traces | trace digest (first 16) | entity ids |
|---|---|---|---|---|
| `opencybele-strict` | 10 ×2 | **1** | `0410d72d933bbddb` | vl0…vl8 (5 of them depart) |
| `opencybele-lifecycle` | 10 ×2 | **1** | `acc212d84c66d69b` | vl0, vl1 |
| `opencybele-timers` | 10 ×2 | **1** | `7d79066aad6f62a4` | vl0…vl2 |
| `opencybele-congestion` | 10 ×2 | **1** | `48fbb42cc6da692c` | vl0…vl3 |
| `opencybele-capacity` | 10 ×2 | **1** | `1e056ae970eb4b27` | vl0…vl8 (4 of them depart) |

Recorded 2026-08-20, headless, no display involved. "Entity ids" is what
`entity.pattern: '^(vl\d+)\|'` collects — every train that appears in the trace, which is *not* the
same as the trains that depart; five of `opencybele-strict`'s nine and four of
`opencybele-capacity`'s nine reach a station and print.

**What is NOT established, and this matters more than the table.** Reproducibility here has a
**session-level** component: `docs/kernel-config.md` measured 114 consecutive runs agreeing and a
*later* session disagreeing 7 times in 9 with nothing changed, and could not identify what persists.
`ParityGate` keys its ledger on the Linux boot id for exactly that reason and refuses to count two
invocations minutes apart as two occasions.

Every gate invocation above is from **one session on one boot**. The **cross-occasion half is
therefore not established for any of the five scenarios**, including `opencybele-strict`, whose
earlier 14-of-14 result is also single-session. The digests are written down so that a later
invocation — after a reboot, hours later, or on another machine — can be compared against them by
hand as well as by the ledger. **Whoever records the frozen goldens for #24 must re-run this gate on
a separate occasion and append the result here.** Until they do, the honest claim is
"byte-reproducible within a session", not "reproducible".

#### 2.1.1 The #24 re-run — appended as this section asked, and it does **not** close the gap

[#24](https://github.com/bedaHovorka/OpenCybele1/issues/24) re-ran the identical command against the
identical `opencybele-baseline` build (`f4c233c`) and against the committed goldens:

| scenario | runs | distinct normalized traces | trace digest (first 16) | vs §2.1 | entity ids |
|---|---|---|---|---|---|
| `opencybele-strict` | 10 | **1** | `0410d72d933bbddb` | **identical** | vl0…vl8 |
| `opencybele-lifecycle` | 10 | **1** | `acc212d84c66d69b` | **identical** | vl0, vl1 |
| `opencybele-timers` | 10 | **1** | `7d79066aad6f62a4` | **identical** | vl0…vl2 |
| `opencybele-congestion` | 10 | **1** | `48fbb42cc6da692c` | **identical** | vl0…vl3 |
| `opencybele-capacity` | 10 | **1** | `1e056ae970eb4b27` | **identical** | vl0…vl8 |

50 runs, 2026-08-20 09:40–09:43 UTC, headless, boot `862aa4f8-9952-4132-9567-f3371a898849`. Each run
was compared against its committed golden at `strict` as well as against its siblings, so this is
also the verification that **no golden had drifted**.

**It is still one boot.** The machine has not rebooted since 2026-08-13, and #23's own gate ran on the
same boot minutes before #24's, so the separation is neither "across a reboot" nor the four hours
`ParityGate.CROSS_OCCASION_HOURS` asks for — the gate itself reported *"no qualifying earlier run in
the ledger"*, #23's ledger having been written under `build/` in a worktree that no longer exists.
The digest agreement above is a **hand comparison of the kind this section provided for**, and it is a
second sample of the same session mode, not cross-occasion evidence.

What #24 did change is that the ledger now lives outside any build directory
(`/home/beda/work/parity-gate-ledger/`, and its five lines are reproduced verbatim in
[`../golden/MANIFEST.md`](../golden/MANIFEST.md) §11), so the next invocation — after a reboot, in
4+ hours, or on another machine — compares automatically instead of by eye. **The cross-occasion half
remains open**, and `MANIFEST.md` §11 states it as such rather than counting the re-run as closure.

### 2.2 Mutation results

**Golden mutants — 68 tried, 68 killed.** Independently in review: 15 event-family deletions across
each of the four scenarios then in the set, single-line deletions, the three same-line-count mutants
below, and an adjacent-line swap inside a burst. **The largest deletion that still passes is 0 lines,
for every scenario.** `TraceComparator.STRICT` is exact list equality, so #13's failure mode — a
`summary` contract passing with 490 of 623 lines gone — is structurally impossible here.

The ones worth naming are the mutants that change **no count and no multiset**, because those are
the shape an actual port defect takes:

| scenario | mutation | golden | result |
|---|---|---|---|
| `opencybele-strict` | change **one** `capacity=2` to `capacity=3` | 607 → 607 | killed |
| `opencybele-congestion` | delete **the one** queued `ENTER_REPLY` line | 176 → 175 | killed |
| `opencybele-congestion` | **no-queue port**: move that line into its `ENTER`'s burst | 176 → 176 | killed |
| `opencybele-congestion` | zero **one** non-zero `VOTE` `diff` | 176 → 176 | killed |
| `opencybele-timers` | move one `TRAVEL_END` across a burst boundary | 183 → 183 | killed |
| `opencybele-capacity` | delete **the one** refused station `ENTER` line | 230 → 229 | killed |

> **A label corrected.** The last row was originally called "clamp DEF-16 — what a port with
> `Math.max(0, delay2)` would emit". It is not: the only `TRAVEL_END` in that scenario that spans a
> burst boundary comes from a **positive** 294 ms draw, which a clamping port leaves exactly where
> it is. The mutant is real and it is killed; it represents a port whose travel timer fires early,
> not a clamping one. §10.9.

**Implementation mutants — the instrument that matters, and the one that was missing.** Patch the
baseline, rebuild the dist, run the suite:

All four were built against a copy of the reference application, confirmed in source, installed as a
dist and driven through the real harness.

| implementation mutant | result |
|---|---|
| `RoadAgent.travelStart` clamps the delay to 0 | **survives.** Unfixable — §10.9 |
| `Station.computeDifference` under-capacity arm returns `0` | **killed** by `opencybele-capacity` — §10.10 |
| `Station.enter` never takes the capacity branch (`if (false)`) | **killed** by `opencybele-capacity` — the golden holds 7 station `ENTER`s against 6 `ENTER_REPLY`s |
| a port that never queues on a road (`RoadAgent.enter` always accepts) | **killed** by `opencybele-congestion` and `opencybele-capacity` |

---

## 3. Counts (CNT) and GUI coupling (GUI)

`INVENTORY` §1 and §11 record facts about the **source**, each with the command that produced it —
not behaviours a run can exhibit. Most are therefore not scenario-mappable in the sense this file
uses, and the honest split is:

| rows | status | why |
|---|---|---|
| CNT-04, CNT-06 | **mapped** (S · L · T · C · K) | "15 channels, one subscriber each" is exactly what §4 below pins: all fifteen event tokens appear, and no channel is ever served twice. |
| CNT-09, CNT-10 | **mapped** (§5) | Four timer sites, zero repeating. `opencybele-timers` asserts the firing counts as equalities. |
| CNT-13, CNT-14 | **mapped** (OUT-01/02, EVT-21) | Two `println` sites and one `Agent.die()` site, all three in every golden. |
| CNT-08 | **superseded** | "asserts are disabled" was true of `./gradlew run`; `OpenCybeleLauncher` always passes `-ea`, so all 33 are live in every scenario. None fired. |
| CNT-01, CNT-02, CNT-03, CNT-05, CNT-07, CNT-11, CNT-12, CNT-15, CNT-16, CNT-17 | **unmapped — not behaviour** | File counts, call-site counts, declaration counts. They are re-derivable by `grep` on a checkout and a port will not have the same numbers by construction (a JADE port has different files, different call sites, and no `Cybele` clock API at all). They belong to #27's mapping table, not to a golden. CNT-11's *consequence* — nothing is ever unsubscribed — is DEF-10, §10.5. |
| GUI-01, GUI-02, GUI-04, GUI-05, GUI-06 | **unmapped — headless by design** | Every scenario sets `sim.headless=true`, so `Gui` and `RailwayCanvas` are never constructed. #36 produces no Swing GUI, so there is nothing on the other side of a comparison. Measuring with a window is separately forbidden: #16 found a variance sweep on the shared display `:0` showing 5–10 differing departure ids that collapsed to **zero** on a private display. See also §10.4. |
| GUI-03 | **resolved, not mapped** | The unconditional `Gui` construction — and the accidental clock-registration barrier it provided (DEF-15) — was replaced on `opencybele-baseline` by #17's explicit `RunControl.awaitTimerService()` plus `verifyClockControl()` (exit 5). Exit 5 means the run is void, which `ScenarioRunner` latches as suite-fatal. |

---

## 4. Agents (AG), activities (ACT) and channels (CH)

**No scenario runs one agent type in isolation, and none can.** `RailwayMainAgent` constructs one
`Station` per topology node and one `RoadAgent` per edge in its own constructor
(`RailwayMainAgent.java:116-131`), so a run with a station and no road is not a configuration the
application has. What `sim.topology` *can* do is shrink the network until one instance of each type
is all there is, and that is `opencybele-lifecycle`: two stations, one track, one live train at a
time. That is the isolation this system supports, and the issue's "where meaningful" is doing real
work.

Scenario keys below: **S** = strict, **L** = lifecycle, **T** = timers, **C** = congestion,
**K** = capacity.

| row | what | scenarios | note |
|---|---|---|---|
| AG-01 | `RailwayMainAgent` | S · L · T · C · K | Its three `recieve*` handlers are what put `STATION_INFO`, `ROAD_STATE` and `TRAIN_STATE` in the trace at all, and `pathFind` answers every `PATH_FIND`. |
| AG-02 | `Station` | S (8) · L (**2**) · T (3) · C (3) · K (3) | |
| AG-03 | `RoadAgent` | S (7) · L (**1**) · T (2) · C (2) · K (2) | |
| AG-04 | `Train` | S (9) · L (**2**) · T (3) · C (4) · K (9) | Only `Train` ever dies; see EVT-21. |
| ACT-01 | `Planning` | all five | `PLAN_TRAIN` → `VOTE_REQUEST` → `VOTE_RESULT` → `START`, per train. |
| ACT-02 | `Generator` | all five | |
| ACT-03 | `VoteCollecting` | all five | Emits nothing of its own. Pinned by the *ordering* it enforces: `VOTE_RESULT` for a train never precedes the full set of `VOTE`s for that train, in any golden. |
| ACT-04 | `PathFinding` | all five | Pinned by `PATH_FIND`/`PATH_FIND_REPLY`. L is the isolation case: two trains on one route, exactly **one** round trip, because `Station.pathDirs` caches. |
| CH-01 … CH-15 | all fifteen channels | **all five** | `OpenCybeleLifecycleIT.assertEveryChannelAppears` asserts it against the *smallest* golden rather than leaving it as prose here, so the claim cannot go stale silently. CH-03's asymmetric payload read (station reads `[0],[2]`, road reads `[0],[1]`) occurs in every scenario, since every train enters both kinds of object. CH-06 carries both `next=<object>` and the `next=null` arrival sentinel. CH-12 carries both a status string and the `"KILL"` sentinel. CH-14 and CH-15 are the two many-senders channels: 15 and 8 senders in S, 3 and 1 in L. |

---

## 5. Timers (TMR)

The issue's rule — *tick counts over a bounded window, never wall-clock durations* — is why
`opencybele-timers` exists and why `OpenCybeleTimersIT` asserts equalities on firing counts. There is
no wall-clock assertion anywhere in the scenario set.

| row | site | scenarios | note |
|---|---|---|---|
| TMR-01 | `Generator.java:50`, bootstrap, fires once | all five; **counted in T** | `sim.arrival.firstFireMs` differs from `sim.arrival.lambdaMs` in **all five** scenarios (S 200/2000, L 500/20000, T 1500/4000, C 1500/3500, K 3200/2000), so the bootstrap firing is separable everywhere. T is where the count is asserted as an equality rather than a floor. *(An earlier revision claimed T was the only scenario where they differ. That was simply false.)* |
| TMR-02 | `Generator.java:65`, self-re-arming Poisson | all five | 2 re-arms in T, 8 in K, 1 in L. |
| TMR-03 | `Planning.java:111`, one per planned train | zero delay: S · L · T · **non-zero delay: C · K** | In L and T every voter returns `diff=0`, so the timer is armed with 0 and `START` follows `VOTE_RESULT` in the same burst — the `t > 0 ? t : 0` branch. C defers vl1 by **8120 ms** and vl2 by **3072 ms**; K defers five of its nine trains past the bound entirely. Both branches covered, by different scenarios. |
| TMR-04 | `RoadAgent.java:102`, one per traversal | all five; **counted in T** | The *count* is pinned. The negative-delay branch (DEF-16) is **not** — §10.9. |

---

## 6. Events (EVT)

Each row maps to its channel or timer above; only the rows with a **split** are commented.

| row | handler | scenarios | note |
|---|---|---|---|
| EVT-01 … EVT-11 | `pathFind`, `recieveStationInfo`, `recieveRoadState`, `recieveTrainState`, `planTrain`, `placeTrainIntoFirstStation`, `generateTrain`, `vote`, `pathFindReply`, `voteRequest`, `voteResult` | all five | EVT-04 dispatches on ACT-02's thread while EVT-02/03 dispatch on AG-01's main activity; that concurrency is DEF-24, class (c), §10.4. |
| EVT-12 | `Station.enter` | admit branch: all five · **capacity branch: K** | `opencybele-capacity` reaches `queue.offer`. §10.1 records how, and how the first revision of this file got it wrong. |
| EVT-13 | `Station.leave` | `occupied--`: all five · **admit-from-queue branch: UNMAPPED** | The complement of EVT-12. In `opencybele-capacity` the queued train is *never* released inside the bound — nothing leaves `stB` again, because the road it would need is itself blocked. So `queue.poll()` is reached by no scenario. It needs a run in which the gridlock un-jams, and every configuration that un-jams is above the density at which DEF-02 drops trains (§10.2). Hand to #28 as an L1 test on the extracted `Station.leave`. |
| EVT-14 | `RoadAgent.enter` | `acceptTrain`: all five · **`push()`: C · K, and S at ~1 run in 8** | C reaches `push` on schedule and in the opposing-direction form; K reaches it as part of the cascade. An instrumented `opencybele-strict` run also reached it (`tr6`/`vl1`), consistent with the 1-in-8 rate `docs/trace-normalizer.md` §2.4 measured — so "C is the only scenario that reaches push" (first revision) was too strong; C is the only one that reaches it **reproducibly**, which is what matters for a golden. |
| EVT-15 | `RoadAgent.leave` | `FREE`: all five · **`pop()` → `acceptTrain`: C** | |
| EVT-16 … EVT-20 | `travelStart`, `travelEnd`, `Train.start`, `Train.entered`, `Train.travelEnd` | all five | EVT-19's three outcomes — `ENTER` the next object, `TRAVEL_START` on a road, `Agent.die()` on a `null` `next` — all occur in every scenario. |
| EVT-21 | `destroy` | all five | The one handler in the codebase with **no channel** — a lifecycle callback bound by name. `OpenCybeleLifecycleIT.assertOnlyTrainsDie` asserts both halves of AG-04's asymmetry: every train that arrives emits `state=KILL`, and nothing that is not a train ever does. A port that tears its static agents down at the bound fails there. |

---

## 7. Mutable state (ST)

State is mapped when a change to it changes the trace, not when the field exists.

| row | field | scenarios | note |
|---|---|---|---|
| ST-01 | `net` | all five | Pinned by the routes: `PATH_FIND_REPLY.direction` and every `ENTER_REPLY.next`. Five distinct topologies across the set. |
| ST-02 | `stationInfos` | all five (**value projected**) | The map is pinned by the `STATION_INFO` stream existing per station; the `occupied` value is not — DEF-13, §11. |
| ST-03 | `roadAgentStates` | all five (**value projected**) | |
| ST-04 | `trainStates` | all five | Every state string, including `KILL`, is pinned verbatim — `TRAIN_STATE.state` is deliberately *not* projected. |
| ST-05 | `roadDelays` | S · C · K | Read by ACT-01 when it builds each voter's expected arrival. The evidence is the **road `diff` values**, which the normalizer *quantises* rather than erases: S carries 13 non-zero road votes in 12 distinct buckets up to `<T~41000>`, C carries `<T~8000>` and `<T~3000>`, K carries buckets to `<T~47000>`. *(An earlier revision cited `VOTE_REQUEST.expected` spacing as evidence; the normalizer **erases** `expected` outright, so that half of the cell proved nothing and has been removed.)* |
| ST-06 | `stationCapacities` | all five | `capacity=` survives the normalizer on purpose — it is configuration a port must replicate. The single-field mutation in §2.2 is on exactly this. |
| ST-07 | `trainTableModel` | **UNMAPPED** | §10.4. |
| ST-08 | `Planning.queue` | S · C · K | A `PriorityBlockingQueue<TrainPlan>` holding more than one plan at a time needs a deferred departure; C has two, K has five. In L and T it never holds two. |
| ST-09, ST-10 | `votes`, `trainCountDowns` | all five | Pinned by the ordering ACT-03 enforces. |
| ST-11 | `openedChannels` | **UNMAPPED** | §10.5. |
| ST-12 | `index` | all five | The `vl<N>` naming and its monotonicity. |
| ST-13 | `random` | all five | Superseded by #15's per-agent seeded streams; every scenario pins `sim.random.masterSeed` and `ScenarioCatalogIT` fails one that does not. |
| ST-14 | `Station.roads` | **UNMAPPED** | §10.6 — dead field. |
| ST-15 | `pathDirs` | all five | The *cache* is pinned, not just the lookup: L has two trains on one route and exactly one `PATH_FIND` round trip. A port that resolved the direction per train would emit two and fail. |
| ST-16 | `Station.queue` | **K** | The queue is entered (§10.1); it is never drained inside any bound (EVT-13). |
| ST-17 | `Station.timetable` | over-capacity arm: **S · K** · under-capacity arm: **K only** | Corrected in revision. The over-capacity arm (`lastKey() ± voteWindow/3`) is genuinely pinned: S carries a station `diff` of raw 3246 → `<T~3000>`, K carries 21072, 27772, 39092 and 44784. The under-capacity arm (`plannedTrains*voteWindow/6`) was **not** pinned before K existed: at the 2000–3500 `voteWindow` of the other scenarios it yields 333, 583 and 666, every one of which renders `diff=<T~0>` — and each is dominated by a road `diff` in `Planning`'s `max`, so it does not leak through the departure instant either. An implementation mutant returning `0` from that arm passed the whole four-scenario suite. K sets `voteWindow` to 6000, so the same arithmetic yields **1000** and **2000**, which clear the quantum and kill the mutant. |
| ST-18 | `Station.Info` | all five (**`occupied` projected**) | |
| ST-19 | `RoadAgent.state` | S · C · K (**value projected**) | Direction of travel is pinned exactly by `ENTER.position` and `ENTER_REPLY.next`, not by the enum: S traverses tr4, tr5, tr6 and tr7 in **both** directions; C traverses tr2 in both. |
| ST-20 | `traveledTrain` | all five | The invariant is **"no road carries two trains at once"** — every `TRAVEL_START` on a road is followed by that road's `TRAVEL_END` before the next `TRAVEL_START` on it. *(An earlier revision wrote this as "every `TRAVEL_START` has exactly one matching `TRAVEL_END`", which is false in two goldens: S is 17/14 and C is 5/4, because trains are in flight when the bound fires. L is 2/2, T is 6/6, K is 3/3.)* |
| ST-21 | `RoadAgent.queue` | depth 1: **C** · **depth ≥ 2: K** | `java.util.PriorityQueue.offer` on an *empty* queue performs no comparison, so a road holding one queued train never calls `OueueItem.compareTo`. K reaches depth two; §10.2. |
| ST-22 | `timetable` / `invertedTimetable` | S · C · K | Pinned by the road `diff`s. |
| ST-23 | `leftStation` / `rightStation` | all five | The NDT-03 assignment, visible in every road `ENTER_REPLY.next`. |
| ST-24 | `Train.from/to/position/nextPosition` | all five | Including `position=null` on the first `ENTER` and the `next=null` arrival sentinel. |

---

## 8. Configuration (CFG)

| row | what | scenarios | note |
|---|---|---|---|
| CFG-01 | topology | all five | Five distinct networks; S is the shipped one. |
| CFG-02 | station capacities | all five | Including **1**, in K, which is what makes EVT-12's capacity branch reachable. |
| CFG-03 | road delays | all five | Including **0**, in T, which no other configuration in the project uses. |
| CFG-04 | travel-time noise `500*nextGaussian()` | **T** · (all five) | T is where it is isolated: with `tr1`'s nominal delay at 0 the noise *is* the whole delay, so both its magnitude and its **sign** decide burst membership. A port that dropped the Gaussian, or took its absolute value, fails there. On a 1 s road it is a small perturbation of a large constant and the burst structure swallows it. |
| CFG-05 | global clock | all five | The tick itself is projected; what survives is the burst structure derived from it. **K runs at pace 4 and the rest at pace 8**, which is not cosmetic — §12.2. |
| CFG-06 | the `LAMBDA` split (arrival rate vs planning horizon) | **K only** | Corrected in revision. #18 split one constant into `sim.arrival.lambdaMs` and `sim.station.voteWindowMs`, and `voteWindow` is read in exactly one place, `Station.computeDifference`. S, L, T and C all set the two keys **equal** (2000/2000, 20000/20000, 4000/4000, 3500/3500), so a port that never split the constant produces byte-identical goldens across all four. K sets 2000/6000, at a value where the resulting station votes clear the `diff` quantum — see ST-17. |
| CFG-07 | origin/destination pairs | S (all six defaults) · L · T · C · K | S is the only scenario with the shipped six. |
| CFG-08 | bootstrap delay | all five; **counted in T** | See TMR-01 — the earlier "only scenario" claim was false. |
| CFG-09, CFG-10 | GUI presets and layout | **UNMAPPED** | §10.4. |
| CFG-11 | kernel comm mode `Local;NoSerialization` | partially | Its one observable consequence is DEF-13's aliasing, which is projected. The kernel banner's `comm service` line is in every golden, so a port that failed to load the service fails; the *by-reference* semantics themselves are pinned by `docs/probes/ExpF.java`, not by a scenario. |
| CFG-12, CFG-13 | kernel thread pool, kernel event queues | **UNMAPPED** | §10.7. |
| CFG-14 | kernel services loaded | all five | The nine-line kernel banner is inside every golden and names all seven services in order. |

---

## 9. Nondeterminism (NDT) and observable output (OUT)

| row | what | scenarios | note |
|---|---|---|---|
| NDT-01, NDT-02 | station and road creation order | **deliberately not pinned** | Class (b). `Cybele.createAgent` is asynchronous — six distinct constructor orders in six runs — and the normalizer's `startup-block` rule **sorts** the opening state block by agent name. Pinning it would be pinning a race; a different startup order is never a port bug. |
| NDT-03 | which station is left vs right | all five | Pinned by `ENTER_REPLY.next` on every road. |
| NDT-04 | which route a train takes | S (all six default routes) · L · T · C · K | INVENTORY's landmine — that `Util.path` and the per-station `Util.pathDirection` chain must agree, or a train traverses objects that never voted on it — is pinned in every golden by construction: a divergence would put an `ENTER` on an object with no `VOTE_REQUEST` for that train. |
| NDT-05 | the single unseeded `Random` | all five | Removed by #15; every scenario pins the master seed. |
| OUT-01 | `Planning`'s `"<train> in <station> at <n>"` | all five | The instant is projected to `<T>`; the *order* of the stream is kept, because its producer is one serial activity. |
| OUT-02 | `Train`'s `"<train> started"` | all five | Sorted by train name by the `println-streams` rule — see §10.8. |
| OUT-03 | `Util.sleep`'s `printStackTrace` | **UNMAPPED** | §10.6 — `Util.sleep` has zero call sites. |

---

## 10. The unmapped rows, each with its measurement

### 10.1 `Station.enter`'s capacity branch — RETRACTED, and now mapped

**The first revision of this file recorded this branch as unreachable, with a table of failed
configurations and the claim that "the mechanism is structural, not a matter of finding the right
knob". Both halves were wrong.** The retraction is kept rather than quietly edited out, because the
way it was wrong is the useful part.

**The detector was broken.** It looked for a station `ENTER` whose `ENTER_REPLY` arrived *late*. A
queued train usually gets **no reply at all** — it sits in `Station.queue` until something leaves,
which inside a bounded run is often never — so the detector scored the branch as absent every time it
fired. Re-running the same captures with a corrected detector (`ENTER` with no matching
`ENTER_REPLY` anywhere) turns two of that table's "0" rows into **12** and **19**.

**The lever is the OD-pair set** — not density, not capacity, not `voteWindowMs` (swept 100–3500, no
effect). A capacity-1 station that only ever sees *through* traffic is protected by its own election:
every train that passes it is in its timetable, so `computeDifference` spaces them. Make the same
station an **origin** as well, so that the trains contending for it have *different road sets*, and
the protection breaks:

| topology `stA-stB:tr1,stB-stC:tr2`, `stB` capacity 1 | trains planned | station-queue events |
|---|---|---|
| pairs `stA>stC,stC>stA` — through traffic only | 155 | **0** |
| pairs `stC>stA,stB>stC,stB>stA,stA>stC` — `stB` is also an origin | 154 | **12** |

`opencybele-capacity` is the low-density, `strict`-able version of the second row: nine trains
planned, one station-queue event, gated 10/10. It took a bound scan plus a pace change to get there
(§12.2), which is the part of the reviewer's caveat — "the untuned candidate is not yet strict-able" —
that was real.

### 10.2 `OueueItem.compareTo` — scoped, and now mapped

`RoadAgent.queue` is a `java.util.PriorityQueue`, and `offer` on an **empty** queue performs no
comparison at all. So a road that only ever holds one queued train never calls `OueueItem.compareTo`,
and with it never reaches DEF-03's `int` narrowing or DEF-04's dead `frequency` tie-break.

Measured, maximum simultaneous pending `ENTER`s per road:

| configuration | max queue depth | `compareTo` calls |
|---|---|---|
| `opencybele-strict`, `-lifecycle`, `-timers`, `-congestion` | **1** | **0** (instrumented: `depthBefore = 0` on every push) |
| `opencybele-capacity` | **2**, on `tr1` | ≥1 |
| shipped topology, λ = 500, 120 s (244 planned) | 2 | 5–11 |

So the claim is now scoped: depth ≥ 2 is **not** structurally impossible, it is simply absent from
four of the five scenarios, and `opencybele-capacity` reaches it because its station queue blocks a
road and two trains then stack up behind it.

**This does not move DEF-03 or DEF-04 out of #28's hands.** `docs/defect-triage.md` §3.1 says DEF-03
is "unreachable in any golden scenario" — its inversion point is 2³¹ ms = **24.9 simulated days** —
and that DEF-04 "always returns 0". Both remain L1 unit tests on the extracted comparator. What K
adds is that the comparator is *executed* at all, so a port that throws inside it (DEF-06's unboxing
NPE, reachable exactly here) fails loudly rather than never being exercised.

### 10.3 A train whose origin equals its destination

The issue's third edge behaviour, `Station.java:100` — `if (target.equals(getName())) return null`.

**It cannot be configured, and it does not need to be.**

1. `ScenarioConfig` **rejects** `sim.arrival.pairs` entries with the same origin and destination
   (`ScenarioConfig.java:714`, exit 1). Since #18 there is no way to ask for such a train.
2. The line is not an origin-equals-destination special case in practice. It is the *arrival* test,
   taken by **every train in every scenario**: a train entering its destination gets `null` back,
   `Train.entered` reads that as "arrived", and calls `Agent.die()`. Every `ENTER_REPLY … next=null`
   in every golden is that branch.

A scenario pinning the *rejection* was considered and rejected: `run.expect: startup-error` would
work, but the artefact would be an exception with a stack trace naming
`cz.vutbr.fit.ags.xhovor07.ScenarioConfig`, which **no port can reproduce**. A parity golden no port
can match is worse than no golden. The rejection belongs in an L1 test on `ScenarioConfig`.

### 10.4 The GUI — ST-07, CFG-09, CFG-10, GUI-01/02/04/05/06, and DEF-24

Every scenario sets `sim.headless=true`, so `Gui` and `RailwayCanvas` are never constructed and the
three `sim.gui.*` keys are inert. They are still **declared** in every scenario, because
`docs/parity-harness.md` requires the property block to be total.

`ST-07`/`DEF-24` (`TableModel.update` iterating a `synchronizedMap` off-lock) is worth naming
separately, because `docs/defect-triage.md` §4.5 **corrected** the earlier belief that headless
excludes it: `trainTableModel` is registered as an `Observer` *above* the headless guard, and
`update` runs synchronously on the agent handler thread. The hazard is live in every scenario here.
It is class (c) — the fix would change the interleaving that produces DEF-13 — and the handling is the
recording gate: a `ConcurrentModificationException` from `TableModel.update` means **discard the
run**, never re-record around it. None was seen in any run made for this issue.

### 10.5 DEF-10 / ST-11 / CNT-11 — the channel leak

`Generator.openedChannels` accumulates one channel ticket per train forever; `closeChannel` is called
nowhere. Not a per-message behaviour, so there is nothing for a golden to pin. Per
`docs/defect-triage.md` §6.3 the decision is **bound the scenario, do not fix**, and the degradation
point is explicitly **unmeasured**. These scenarios are the smallest in the project (2–9 trains each,
against `short.properties`' 244), so they sit far inside whatever the unknown bound is.

### 10.6 Dead code — ST-14 and OUT-03

`Station.roads` is injected by the constructor and read only by a getter with no callers.
`Util.sleep` — whose `printStackTrace` is OUT-03 — has zero call sites. Neither can produce output.
Recorded so a port that "restores" either knows it is adding behaviour.

### 10.7 Kernel configuration — CFG-12, CFG-13, and SEM-01

`cybele.prop`'s thread pool and its commented-out event-queue lines are **kernel** configuration, not
`sim.*` configuration: no scenario can vary them and the kernel prints neither. They were established
by probe — `docs/probes/ExpA2.java` with a positive control for SEM-01's strict-FIFO verdict — and that
is the right instrument. What the goldens pin is the *consequence*: the line order inside a burst.
A port on JADE has neither file, so this is input to #27's mapping.

### 10.8 `println-streams` is still a low-power rule

The normalizer splits the two application `println` families into two streams and **sorts the
`started` stream by train name**, because its lines have one producer per train and race (three
orderings across six runs at one seed, measured by #21). The rule only *matters* when two trains tie
on a departure millisecond.

**None of these five scenarios produces a tie.** In all five goldens the sorted `started` stream
happens to equal the emission order, so removing the rule would change nothing. Forcing a tie needs
two trains planned to the same departure instant, which needs the arrival rate raised into the regime
where DEF-02 drops trains — trading a rule nobody can observe failing for a scenario that cannot hold
`strict`. Note also that a tie would exercise DEF-17, which `INVENTORY` itself says "no observation
can confirm or refute from the outside", so a tie scenario would pin something nobody could interpret.

**Unmapped, and recorded as a known weakness of the normalizer rather than of the scenarios.**

### 10.9 DEF-16 — unmappable by any golden, and the claim in `defect-triage.md` is wrong

`docs/defect-triage.md` §3.1 classes DEF-16 as (a), "PIN — do not clamp", and tells #39 that *"a port
that clamps to 0 produces a diff and THAT is the port bug"*. **It does not.** Measured:

> `RoadAgent.travelStart` was patched to
> `Math.max(0, delayInSeconds() + (long)(500*random.nextGaussian()))`, the clamp confirmed in
> bytecode, the dist rebuilt, and the whole suite run: **all four scenarios stayed byte-identical to
> their goldens and everything passed.**

The reason is structural and no amount of scenario tuning fixes it. `opencybele-timers` gives `tr1` a
nominal delay of 0 precisely to make negative draws common, and at this seed `tr1`'s three traversals
arm approximately **−703, +28 and +294 ms**. There is exactly one non-positive draw, and **Cybele
fires a `0` timer exactly as immediately as a `−703` one** (SEM-06: −1500 ms → callback in 1–5 ms;
0 ms → 1–5 ms). A clamping port therefore differs from the baseline only in a value no observer can
read.

Two smaller corrections fall out of the same measurement, and both were in the first revision:

* This file said `tr1`'s gaps of "8 ms, 24–32 ms, 296 ms" were "two on the fire-immediately branch,
  one ordinary". Only `vl0`'s is; `vl1`'s same-burst placement comes from a **positive 28 ms** draw.
* `OpenCybeleTimersIT` classified "immediate" as *elapsed ≤ 220 ms*, which a +28 ms ordinary draw
  satisfies — so the assertion could not distinguish the branches it named. It has been rewritten to
  assert what it can: that the noise produces **both** an at-or-under-boundary and an over-boundary
  traversal, which kills a port that drops the Gaussian (all zero) and a port that takes its absolute
  value (`vl0`'s −703 → +703, out of its burst).

**Verdict: DEF-16 is unmapped. It needs an L1 unit test on the extracted delay expression (#28), and
`docs/defect-triage.md` §3.1's guidance to #39 should be corrected when that issue is next touched.**

### 10.10 ST-17's under-capacity arm — was unpinned, now pinned

Recorded here rather than only in the ST table because it was found the same way DEF-16 was, and it
is the case where the fix worked. Patching `Station.computeDifference`'s under-capacity return to `0`
and rebuilding left **all four** pre-revision traces byte-identical: every station `diff` in that set
is sub-quantum (C emits 583 three times, S emits 333 and 666), so all of them render `diff=<T~0>`,
and each is dominated by a road `diff` in `Planning`'s `max` so none leaks through the departure
instant either.

`opencybele-capacity` sets `sim.station.voteWindowMs` to 6000 against an arrival rate of 2000, so
`plannedTrains*voteWindow/6` yields **1000** and **2000** — above
`CanonicalTraceNormalizer.TIME_QUANTUM` — and the mutant dies. The same setting is what makes CFG-06
mappable at all. `OpenCybeleCapacityIT.assertVoteWindowSurvivesTheQuantum` fails if a future retune
drops the station votes back under the quantum.

### 10.11 `ROAD_STATE.state` — erased, and the TRAVEL_LEFT/TRAVEL_RIGHT label is pinned by nothing

Recorded for [#72](https://github.com/bedaHovorka/OpenCybele1/issues/72), which asked for the gap to
be written down before #39 leans on this suite. `CanonicalTraceNormalizer`'s `road-state` projection
erases the field outright:

```
$ grep -c "TRAVEL_LEFT\|TRAVEL_RIGHT" parity-tests/golden/*.txt
opencybele-capacity.txt:0   opencybele-congestion.txt:0   opencybele-lifecycle.txt:0
opencybele-strict.txt:0     opencybele-timers.txt:0       smoke-stub.txt:0
$ grep -m1 ROAD_STATE parity-tests/golden/opencybele-congestion.txt
tr2|<T>|ROAD_STATE|tr2|Main|<P>|state=<S>
```

**Do not read that as "the goldens are blind to direction". They are not, and #72's opening
statement of the problem overstated it.** Direction of travel is pinned independently, by
`ENTER_REPLY.next`, which no rule touches and which every golden carries verbatim — 36 lines in
`opencybele-strict`, 13 in `-congestion`, 9 in `-capacity`, 15 in `-timers`, 6 in `-lifecycle`:

```
vl2|<T>|ENTER_REPLY|tr2|vl2|<P>|object=tr2,next=stB
```

`vl2` entered `tr2` from `stC`, so `next=stB` *is* the leftward traversal. A port that hands a train
to the wrong end of a track changes line **content**, diffs at `strict`, and is caught in all five
scenarios. Since #72 it is also checked directly, per hop and per train, by
`OpenCybeleLifecycleIT`, which compares every `ENTER_REPLY.next` against the route the train's own
`ENTER.position` payloads describe.

**What is genuinely unpinned is the LEFT/RIGHT *label*, and it is unpinnable by any golden.**
Derived from the source rather than measured, because the derivation is exact —
`RoadAgent.acceptTrain`:

```java
if (position.equals(leftStation)) { state = State.TRAVEL_RIGHT; sendEnterReply(train, rightStation); }
else { assert position.equals(rightStation); state = State.TRAVEL_LEFT; sendEnterReply(train, leftStation); }
```

Both arms reply with **the other end**. So a port that assigns the two endpoints the other way round
takes the *other* branch, sets the *other* `State` constant, and sends **the same `next`**. Only
`state` differs, and `state` is erased.

That is not hypothetical. `docs/iteration-order.md` claim 3 establishes that `leftStation` /
`rightStation` follow `sim.topology` **declaration order** (via `HashMapGraph.put`'s argument order
and `DoubletonIterator`'s `FIRST → SECOND` walk), deterministically on every JVM — and that
*"imposing a lexicographic rule would flip 5 of the 7 roads … and invert the `TRAVEL_LEFT` /
`TRAVEL_RIGHT` symbol published on `ROAD.STATE`, **which appears in every trace**"*. The last clause
is true of the RAW trace and **false of every golden** since #21 added the `road-state` rule; the two
documents are reconciled here rather than left to disagree. A port that sorts its endpoint pairs —
the most natural thing a reimplementation does — flips 5 of 7 roads on the default topology and
produces byte-identical goldens. The only thing that pins it today is `docs/probes/OrderLock.java`,
which is a probe run by hand, not part of the suite.

That is not a hole in coverage so much as a fact about the baseline: `TRAVEL_LEFT` and
`TRAVEL_RIGHT` are never compared to each other anywhere in the application. Exhaustive grep: the
enum is tested only against `FREE` (`RoadAgent.java:137`, `:165`, `RailwayCanvas.java:102`), and the
sole consumer of the distinction is `RailwayCanvas.paintRoad` drawing `state.getSymbol()` — `"<"` or
`">"` — on a canvas every scenario runs headless. The label is a GUI annotation, and an
endpoint-swapped port is behaviourally identical on everything a trace can see.

**Consequences a future scenario author must not get wrong:**

* the goldens **do** pin which way each train travels (`ENTER_REPLY.next`);
* the goldens **do not** pin which endpoint is called left, and no scenario can be tuned to make
  them. If #27/#36 want that pinned, it is an L1 test on the extracted graph/road classes (#28)
  asserting the endpoint assignment against `sim.topology` — the same verdict §10.9 reaches for
  DEF-16;
* the *relative* direction claim — that a train was queued behind one heading the **other** way,
  which is `acceptTrain`'s else-arm reached from a queue — is carried by **exactly one property
  assertion**, `OpenCybeleCongestionIT`, and by **no golden**. It is not re-checked by `parityGate`.
  A retune of `opencybele-congestion` that stops producing the opposing-direction queue loses that
  coverage entirely, and only that assertion will say so.

### 10.12 DEF-07 — correctly classified (a), and observable in exactly one scenario

The first revision of §11's DEF-07 row said the ordering "is in every golden, and
`OpenCybeleLifecycleIT` asserts it directly". **Both halves were wrong**, and #72 measured it.

*Not in every golden.* `burst-order` sorts within a burst, and on an uncontended hop the reply comes
back in the same tick, so DEF-07's two lines are in one burst and the sort decides their order —
alphabetically, not causally. `parity-tests/golden/opencybele-lifecycle.txt` ends `vl0`'s life:

```
41  vl0|<T>|ENTER_REPLY|stB|vl0|<P>|object=stB,next=null
42  vl0|<T>|ENTER|vl0|stB|<P>|train=vl0,position=tr1,target=stB
43  vl0|<T>|LEAVE|vl0|stB|<P>|train=vl0     <- the DESTRUCTOR's leave
44  vl0|<T>|LEAVE|vl0|tr1|<P>|train=vl0     <- the HOP leave, emitted FIRST
```

The golden lists them in the opposite order to the one the application emitted them in. Nothing in
that block is an emission order; it is `NATURAL_ORDER` on the whole line, and it is worth spelling
out which comparison decides which pair, because there are **three** and they are not the same one:

| pair | decided by | not by |
|---|---|---|
| 41 before 42 — `ENTER_REPLY\|stB` before `ENTER\|vl0` | field 3, `_` (0x5F) < `\|` (0x7C) after the common prefix `ENTER` | anything causal — the `ENTER` is what *caused* the reply |
| 41–42 before 43–44 — every `ENTER*` before every `LEAVE` | field 3, **`E` < `L`** | the DEF-07 ordering it is mistaken for |
| 43 before 44 — `LEAVE\|vl0\|stB` before `LEAVE\|vl0\|tr1` | field 5, `s` < `t` | emission order, which is `tr1` first |

The middle row is the one that matters here: an `ENTER_REPLY` appearing above a `LEAVE` in any
golden is `E` sorting before `L`, and it would look exactly the same for a port that emitted them
the other way round.

*Not assertable from the raw stream either.* The probe records its **handling** order
(`docs/trace-format.md`), and over 60 captures of `opencybele-lifecycle` 2 print an `ENTER_REPLY`
before the very `ENTER` it answers. The old checker walked the stream building a pairing queue, so
one inverted line put it permanently out of step and produced two "violations" from it. Full
measurement: [`docs/raw-assertion-audit.md`](../../docs/raw-assertion-audit.md) §2.

*The classification is right and stays.* DEF-07 is a claim about the **send** order inside
`Train.entered`, which is deterministic and which a port really can get wrong. It is the
*observable* that was wrong.

*Where it survives the projection.* On a **queued** hop the wait separates the two lines into
different bursts, and the golden then carries the ordering positionally.
`parity-tests/golden/opencybele-congestion.txt`, `vl2` queued on `tr2` behind `vl1`:

```
111 vl2|<T>|ENTER_REPLY|stC|vl2|<P>|object=stC,next=tr2
112 vl2|<T>|ENTER|vl2|tr2|<P>|train=vl2,position=stC,target=stB
      ... 13 lines and a burst boundary ...
126 vl2|<T>|ENTER_REPLY|tr2|vl2|<P>|object=tr2,next=stB
127 vl2|<T>|LEAVE|vl2|stC|<P>|train=vl2                <- released only AFTER admission
```

A port that released `stC` before asking `tr2` to admit it would emit line 127 up at 112's burst and
diff. `OpenCybeleCongestionIT.assertDef07SurvivesTheProjection` asserts that separation directly, so
a retune that closes the queue window fails loudly instead of quietly un-pinning DEF-07.

---

## 11. Defects (DEF-01 … DEF-24)

Mapping follows [`docs/defect-triage.md`](../../docs/defect-triage.md) §3. Class (c) rows are
**out-of-contract carve-outs**: unmapped *by decision*, and the decision is not this file's to revisit.

| row | class | scenarios | note |
|---|---|---|---|
| DEF-03 | (a) | **unmapped — L1 (#28)** | Inverts at 2³¹ ms = 24.9 simulated days; unreachable at scenario scale by the triage's own words. The comparator now at least *runs*, in K — §10.2. |
| DEF-04 | (a) | **unmapped — L1 (#28)** | Dead tie-break, always 0. Same. |
| DEF-07 | (a) | **C only — see §10.12** | The ordering is **not** in every golden: `burst-order` sorts the two lines together on an uncontended hop, and `opencybele-lifecycle.txt` in fact lists them reversed. It survives only on a QUEUED hop, where the wait crosses a burst boundary — `opencybele-congestion`, asserted by `OpenCybeleCongestionIT.assertDef07SurvivesTheProjection`. Class (a) is correct and unchanged; `OpenCybeleLifecycleIT` now asserts the hop PAIRING (route, `ENTER_REPLY.next`, one release per visited object) instead of an emission order the trace does not record. |
| DEF-08 | de-claimed | all five | Not a defect. What is pinned is the *shape*: the destructor's `LEAVE` to the destination station, which balances its `occupied++`. |
| DEF-11 | (a) | **unmapped — unmappable** | `==` on interned `String`s behaves identically to `.equals`; a port using either produces a byte-identical trace. |
| DEF-14 | (a) | all five | Pinned as the invariant "no road carries two trains at once" — ST-20. |
| **DEF-16** | (a) | **UNMAPPED — see §10.9** | A clamping port passes the whole suite. Reclassified here as unmappable by golden; `defect-triage.md` §3.1's guidance to #39 is wrong and should be corrected. |
| DEF-13 | (b) | mechanism mapped, **value projected** | Never a port bug. |
| clock families | (b) | mechanism mapped, **values projected** | `tick`, `expected`, `planned` and the departure instant are **erased**; `diff` is **quantised**, and §2.2's "zero one `diff`" mutant shows the quantum has teeth. |
| agent init order | (b) | **deliberately not pinned** | Sorted by the `startup-block` rule. |
| DEF-02 tail | (b) | **excluded by bound choice** | Every bound sits inside a measured quiet window, so no golden ends on a ragged departure. §12.1. |
| DEF-01 | (c) | **unmapped — carve-out** | Spurious-wakeup branch of an untimed `wait()`. Never observed. |
| DEF-02 | (c) | **unmapped — carve-out** | The `START` race. Suppressed here by low density — 2 to 9 trains per scenario — and visible only to the cross-occasion check in §2.1, which is not yet done. |
| DEF-05, DEF-09 | (c) | **unmapped — carve-out** | Duplicate-vote overwrite and late-vote NPE. Never observed in 36 and 372 evaluations. |
| DEF-06 | (c) | **unmapped — carve-out, but now at least reachable** | Unboxing NPE inside `compareTo`. Before K the comparator never ran at all, so the defect was doubly unreachable; it now runs in one scenario. Still never observed. |
| DEF-10 | (c) | **unmapped — by decision** | §10.5. |
| DEF-17 | (c) | **unmapped — uninterpretable** | §10.8. |
| DEF-22 | (c) | **unmapped — but the contract level is the instrument** | The wedge exits 0 with a clean stream and `sim.stop.stallMs` structurally cannot catch it. What catches it is the trace: the departure stream truncates. All five scenarios are `strict`, where a wedge is a **certain** failure; at `summary` a late one hides inside a tolerance. That is the strongest single argument for having folded `opencybele-smoke` away. |
| DEF-23 | (c) | **unmapped — carve-out** | A station that stops emitting `STATION_INFO` mid-run is a wedged baseline. |
| DEF-24 | (c) | **unmapped — carve-out** | §10.4. Headless does **not** exclude it. |
| DEF-12, DEF-19, DEF-20 | not defects | **n/a** | Invariants to record; no behaviour, no diff signature. |
| DEF-15 | resolved | **n/a** | Resolved by #17's barrier plus `verifyClockControl` (exit 5). Exit 5 means the run is void. |
| DEF-18 | invariant | all five | Enforced at startup since #18 (`capacity >= 1`); K sits on that floor at `stB=1`. |
| DEF-21 | resolved | all five | Every scenario runs the child with `-ea` (the adapter always adds it), so the 33 assertions are live. None fired in any run made for this issue. |

## 11b. Cybele runtime semantics (SEM)

| row | scenarios | note |
|---|---|---|
| SEM-01 | **unmapped as a scenario row** | §10.7; established by probe with a positive control. |
| SEM-02 | all five, and **C · K for the second bracket** | `Planning.planTrain` brackets its timer arming in `pauseClock`/`resumeClock` in every scenario; `RoadAgent.push`/`pop` add a **second** bracket that only C and K reach. INVENTORY's unresolved "pause/resume loss under artificial burst load" (2 of 40 runs at 50 back-to-back pairs) does not apply: these scenarios issue a handful of brackets in a whole run, inside the 0-of-1000 realistic-shape measurement. |
| SEM-03 | **unmapped — not used** | The kernel supports two simultaneous subscribers; the application opens every channel name exactly once. Input to #27, not a gap. |
| SEM-04 | all five, indirectly | Serial dispatch per activity is *why* ACT-03 and ACT-04 exist; their round trips are in every golden. |
| SEM-05 | mechanism mapped, **consequence projected** | Pass-by-reference; its observable effect is DEF-13. Pinned properly by `docs/probes/ExpF.java`. |
| SEM-06 | zero-delay timer: all five · **negative-delay timer: UNMAPPED (§10.9)** · silent drop: carve-out (DEF-02) | The three findings split three ways. The zero-delay case is `Planning.java:111`'s `t > 0 ? t : 0`, taken by every immediate departure. The negative case is observationally identical to the zero case, which is exactly why DEF-16 cannot be pinned. |

---

## 12. Method — what a future scenario author needs

### 12.1 A bound is load-bearing

**The `Cybele.terminate()` race.** A `Train` constructor in flight at the stop instant reaches
`Activity.openChannel` after `commReceiver` has been nulled; the kernel prints an NPE **after** the
stop banner and **the exit status is still 0**. A train is created at plan time, so the exposure is
the gap between the last train creation and the bound. Reference measurements: 3 failures in 8 under
load at ~5 ms of wall clock; 0 in 24 at ~480 ms.

| scenario | last train creation → bound | in wall clock |
|---|---|---|
| `opencybele-lifecycle` | 10 464 ms | **1308 ms** (pace 8) |
| `opencybele-capacity` | 4 000 ms | **1000 ms** (pace 4) |
| `opencybele-congestion` | 3 964 ms | **496 ms** (pace 8) |
| `opencybele-timers` | 3 876 ms | **485 ms** (pace 8) |
| `opencybele-strict` | 2 776 ms | 347 ms (pace 8; inherited, not re-tuned by #23) |

### 12.2 The burst-boundary margin, and which gaps move

`CanonicalTraceNormalizer` segments at tick gaps **> 220 ms** and sorts within a segment. A gap that
sits *near* 220 is a coin toss, and when it crosses, two bursts merge, the sort re-orders them, and a
`strict` golden fails with a diff that looks like a content change and is not. The first
`opencybele-congestion` (λ 3000, `firstFireMs` 200, bound 29000) had such a gap at simulated ~27 040
and **failed the zero-flake gate on its second run**.

**Which gaps are dangerous is not a matter of size alone — it is a matter of what produces them.**
Established while tuning `opencybele-capacity`:

* A gap from the **simulated schedule** — an `exp()` inter-arrival draw, a road delay, the Gaussian
  travel noise — is a simulated-time quantity. Same number at any pace, and it does not grow under
  CPU load.
* A gap from **message latency** — one agent reacting to another — is a **wall-clock** quantity that
  the pace converts into simulated time. At pace 8, ~25 ms of wall clock becomes ~200 simulated ms,
  which is the worst possible number; and under load the wall figure grows, so the gap grows with it.

Measured on `opencybele-capacity`, 6 captures each under an 8-way CPU load: at **pace 8** the reaction
gap around simulated 12 400 read 184, 192, 208, 208, 216 and 232 ms — it **crossed 220 inside one
sweep**. At **pace 4** the same gap read 156–168 ms. That is why K runs at pace 4 and the other four
do not: halving the pace halves every latency-derived gap and leaves the schedule-derived ones alone.

Observed gaps in the 150–380 ms band, per scenario (unloaded captures unless noted):

| scenario | in-band gaps | nearest approach to 220 |
|---|---|---|
| `opencybele-lifecycle` | none | — |
| `opencybele-congestion` | none | — |
| `opencybele-timers` | one, 288–296 ms | 68 ms |
| `opencybele-capacity` (pace 4, **under load**) | two, 156–168 and 280–352 ms | 52 ms |
| `opencybele-strict` | four, at 296–312, 272–280, 264–288 and 248–288 ms | 28 ms |

plus, in `opencybele-strict` only, a 192–200 ms gap between the opening state block and the first
event. That one is **not** a burst-order boundary: the `startup-block` rule extracts and sorts the
opening run of `STATION_INFO`/`ROAD_STATE` lines before `burst-order` sees anything, so where the
boundary falls there does not change the output.

`opencybele-strict`'s 248 ms gap is the tightest margin in the set. It is inherited — that scenario's
bound and golden predate #23 and `Phase1.md` L7 forbids re-recording — and it has now been green for
10/10 twice here and 14/14 in #21. It is recorded rather than fixed, so that a future flake there is
diagnosable in one step.

### 12.3 Adding a scenario

1. Shape it with `sim.*` (`docs/scenario-config.md` on `opencybele-baseline`); declare **all twenty**
   keys — `ScenarioCatalogIT` fails a scenario that leaves one implicit.
2. Choose the bound inside a quiet window, and record **both** margins from §12.1–12.2 in the file.
   Scan the gaps **under CPU load**, not only idle: the dangerous ones are the ones load moves.
3. Prefer `strict`. DEF-22 is a certain failure at `strict` and a hidden one at `summary`.
4. Give it a liveness rule that can actually fail, counting **distinct** entities where that is the
   point. A floor of 0 is rejected at parse time.
5. Record the golden, then mutation-test it — **and not only the golden.** Deleting lines proves the
   golden is compared; it does not prove the golden pins the behaviour you claim. **Patch the
   baseline, rebuild the dist, and check the suite goes red.** Two claims in this file's first
   revision survived every golden mutation and died to the first implementation mutant (§10.9,
   §10.10).
6. Gate it — `./gradlew parityGate -Dparity.gate.runs=10 -Dparity.gate.scenario=<id>` — **before**
   adding its row to this file.
7. Add it here, and add its unmapped rows with their measurements.
8. **Check your detector before you believe a negative result.** §10.1 is a whole retracted section
   that exists because a detector looked for a late reply where the real signature is no reply at all.
