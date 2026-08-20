# COVERAGE.md — Stage-0 inventory row → scenario id

> Produced for [#23](https://github.com/bedaHovorka/OpenCybele1/issues/23) (*Scenario coverage
> extension*), which implements [`Phase1.md` 1-PRE.3](../../docs/Phase1.md).
> Subject: [`docs/INVENTORY.md`](../../docs/INVENTORY.md) — AG, ACT, CH, EVT, TMR, ST, CFG, NDT,
> OUT, SEM and DEF.
>
> **Read this before adding or retuning a scenario.** [`Phase2.md` L28](../../docs/Phase2.md)
> freezes the scenario set after 2-PRE and [`Phase1.md` L7](../../docs/Phase1.md) forbids
> re-recording a golden for anything but a harness defect. A coverage gap found after the freeze
> cannot be closed without weakening the contract, so every gap below is either **named and
> justified** or it is a mistake.

## The rule this file follows

Every inventory row is either

* **mapped** — at least one scenario produces trace output that changes if that row's behaviour
  changes; or
* **unmapped, with a written justification** — and the justification is a *measurement* or a
  citation of one, never "did not get to it".

"Mapped" is deliberately stronger than "the code ran". `Station.enter` runs in every scenario, but
its capacity branch does not, so [EVT-12](#6-events-evt) is split into a mapped half and an unmapped
one rather than ticked whole.

---

## 1. The scenario set

Four scenarios, all `strict`, all headless, all at seed `20080415`, all with the canonical trace
probe on and all twenty `sim.*` keys declared (`ScenarioCatalogIT` asserts that totality).

| id | shape | bound | golden | what it is *for* |
|---|---|---|---|---|
| [`opencybele-strict`](opencybele-strict.yaml) | 8 stations, 7 tracks, 6 OD pairs, 9 trains | 24 000 ms | 607 lines | **the full-system scenario.** The shipped topology, unchanged: the only scenario in which all six default routes, all seven tracks and the `Station.computeDifference` over-capacity branch occur. |
| [`opencybele-lifecycle`](opencybele-lifecycle.yaml) | 2 stations, 1 track, 2 trains | 40 000 ms | 84 lines | **agent lifecycle and startup/shutdown.** The smallest network the application accepts, so its golden is short enough to check by hand — the scenario a port is brought up against first. All fifteen channels; both trains run to their destination and die. |
| [`opencybele-timers`](opencybele-timers.yaml) | 3 stations, 2 tracks (**one with delay 0**), 3 trains | 23 500 ms | 183 lines | **the four one-shot timer sites**, as counts over a bounded window. The zero-delay track makes DEF-16 — the negative delay Cybele fires immediately — happen twice in three traversals instead of on 2.26 % of them. |
| [`opencybele-congestion`](opencybele-congestion.yaml) | 3 stations fed from **both ends**, 2 tracks, 4 trains | 26 900 ms | 176 lines | **the road queue and the deferred departure.** The only scenario in which a train is queued on a contended track in the *opposing* direction and handed over from inside `RoadAgent.leave`, and the only one outside `opencybele-strict` with non-zero vote arithmetic. |

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
`ScenarioCatalogIT.smokeAndStrictStayFolded` fails if a near-duplicate is ever re-added.

---

## 2. Evidence

### 2.1 The flake gate — established today, one half of it

```
./gradlew parityGate -Popencybele.dist=<…>/build/install/opencybele -Dparity.gate.runs=10
```

| scenario | runs | distinct normalized traces | trace digest (first 16) | departure ids |
|---|---|---|---|---|
| `opencybele-strict` | 10 | **1** | `0410d72d933bbddb` | vl0…vl8 |
| `opencybele-lifecycle` | 10 | **1** | `acc212d84c66d69b` | vl0, vl1 |
| `opencybele-timers` | 10 | **1** | `7d79066aad6f62a4` | vl0, vl1, vl2 |
| `opencybele-congestion` | 10 | **1** | `48fbb42cc6da692c` | vl0…vl3 |

Recorded 2026-08-20, headless, no display involved. Each scenario was gated **twice** — two separate
`parityGate` invocations 12–20 minutes apart, 20 runs per scenario in total — and both invocations
produced the digest above. Both are from the same session and the same boot (`862aa4f8-…`), so
`ParityGate` correctly declines to count the second as a separate occasion; it is recorded here as
what it is, a second within-session sample.

**What is NOT established, and this matters more than the table.** Reproducibility here has a
**session-level** component: `docs/kernel-config.md` measured 114 consecutive runs agreeing and a
*later* session disagreeing 7 times in 9 with nothing changed, and could not identify what persists.
`ParityGate` keys its ledger on the Linux boot id for exactly that reason and refuses to count two
invocations minutes apart as two occasions.

All four gate runs above are from **one session**. The **cross-occasion half is therefore not
established for any of the four scenarios**, including `opencybele-strict`, whose earlier 14-of-14
result is also single-session. The digests are written down above so that a later invocation — after
a reboot, hours later, or on another machine — can be compared against them by hand as well as by the
ledger. **Whoever records the frozen goldens for #24 must re-run this gate on a separate occasion and
append the result here.** Until they do, the honest claim is "byte-reproducible within a session",
not "reproducible".

### 2.2 Mutation results — every scenario shown to be able to fail

A scenario that passes with most of its golden deleted is not coverage; #13 shipped one that passed
with 490 of its 623 lines removed. Each scenario was therefore run against a *mutated* golden and had
to fail. All ten mutants were killed.

| scenario | mutation | golden | result |
|---|---|---|---|
| `opencybele-strict` | drop every `VOTE` line | 607 → 514 | killed |
| `opencybele-strict` | change **one** `capacity=2` to `capacity=3` | 607 → 607 | killed |
| `opencybele-lifecycle` | drop every `VOTE` line | 84 → 78 | killed |
| `opencybele-lifecycle` | delete both `state=KILL` lines | 84 → 82 | killed |
| `opencybele-timers` | drop every `TRAVEL_END` line | 183 → 177 | killed |
| `opencybele-timers` | **clamp DEF-16**: move one `TRAVEL_END` into its `TRAVEL_START`'s burst, i.e. emit exactly what a port with `Math.max(0, delay2)` would | 183 → 183 | killed |
| `opencybele-congestion` | drop every `ROAD_STATE` line | 176 → 165 | killed |
| `opencybele-congestion` | delete **the one** queued `ENTER_REPLY` line | 176 → 175 | killed |
| `opencybele-congestion` | **no-queue port**: move that line into its `ENTER`'s burst, i.e. emit exactly what a port that never queues would | 176 → 176 | killed |
| `opencybele-congestion` | zero **one** non-zero `VOTE` `diff` | 176 → 176 | killed |

The three same-line-count mutations are the load-bearing ones: they change no count, no family and no
multiset, only a line's burst or a single field, and they are the shapes an actual port defect takes.

---

## 3. Agents (AG) and activities (ACT)

**No scenario runs one agent type in isolation, and none can.** `RailwayMainAgent` constructs one
`Station` per topology node and one `RoadAgent` per edge in its own constructor
(`RailwayMainAgent.java:116-131`), so a run with a station and no road, or a road and no train, is
not a configuration the application has. What `sim.topology` *can* do is shrink the network until one
instance of each type is all there is, and that is what `opencybele-lifecycle` does: two stations, one
track, one live train at a time. That is the isolation this system supports, and the issue's "where
meaningful" is doing real work.

| row | what | scenarios | note |
|---|---|---|---|
| AG-01 | `RailwayMainAgent` | S · L · T · C | Its three `recieve*` handlers are what put `STATION_INFO`, `ROAD_STATE` and `TRAIN_STATE` in the trace at all, and `pathFind` answers every `PATH_FIND`. |
| AG-02 | `Station` | S (8) · L (**2**) · T (3) · C (3) | |
| AG-03 | `RoadAgent` | S (7) · L (**1**) · T (2) · C (2) | |
| AG-04 | `Train` | S (9) · L (**2**) · T (3) · C (4) | Only `Train` ever dies; see EVT-21. |
| ACT-01 | `Planning` | S · L · T · C | `PLAN_TRAIN` → `VOTE_REQUEST` → `VOTE_RESULT` → `START`, per train. |
| ACT-02 | `Generator` | S · L · T · C | T separates its two timer sites; see TMR-01/02. |
| ACT-03 | `VoteCollecting` | S · L · T · C | Not directly observable — it emits nothing. It is pinned by the *ordering* it enforces: `VOTE_RESULT` for a train never precedes the full set of `VOTE`s for that train, in any golden. A port that dropped the latch would break that order. |
| ACT-04 | `PathFinding` | S · L · T · C | Pinned by `PATH_FIND`/`PATH_FIND_REPLY`. L is the isolation case: two trains, one round trip, because `Station.pathDirs` caches. |

---

## 4. Channels (CH-01 … CH-15)

**All fifteen channels appear in all four goldens.** `OpenCybeleLifecycleIT.assertEveryChannelAppears`
asserts it against the smallest one rather than leaving it as prose here, so this row cannot go stale
silently.

| row | channel | scenarios | note |
|---|---|---|---|
| CH-01 | `VOTE_REQUEST` | S · L · T · C | |
| CH-02 | `VOTE_RESULT` | S · L · T · C | |
| CH-03 | `ENTER` | S · L · T · C | Both payload readings (INVENTORY's "asymmetric payload read": `Station.enter` reads `[0],[2]`, `RoadAgent.enter` reads `[0],[1]`) occur in every scenario, since every train enters both kinds of object. |
| CH-04 | `LEAVE` | S · L · T · C | Including the destructor's, which balances the destination station — see EVT-21 and `docs/defect-triage.md` §4.2. |
| CH-05 | `START` | S · L · T · C | |
| CH-06 | `ENTER_REPLY` | S · L · T · C | Both `next=<object>` and the `next=null` arrival sentinel. |
| CH-07 | `TRAVEL_END` | S · L · T · C | |
| CH-08 | `TRAVEL_START` | S · L · T · C | |
| CH-09 | `PATH_FIND_REPLY` | S · L · T · C | |
| CH-10 | `CHANNEL_STATION_INFO` | S · L · T · C | `capacity` is pinned; `occupied` is projected — DEF-13, and see §11. |
| CH-11 | `CHANNEL_ROAD_STATE` | S · L · T · C | `state` is projected; the *code path* behind it is covered by C — see §11 and `docs/trace-normalizer.md` §2.4. |
| CH-12 | `CHANNEL_TRAIN_STATE` | S · L · T · C | Including the `"KILL"` sentinel. |
| CH-13 | `PLAN_TRAIN` | S · L · T · C | |
| CH-14 | `VOTE` | S · L · T · C | Many-senders-to-one-receiver; 15 senders in S, 3 in L. |
| CH-15 | `PATH_FIND` | S · L · T · C | Many-senders-to-one-receiver; 8 senders in S, 1 in L. |

---

## 5. Timers (TMR)

The issue's rule — *tick counts over a bounded window, never wall-clock durations* — is why
`opencybele-timers` exists and why `OpenCybeleTimersIT` asserts equalities on firing counts rather
than on elapsed time. There is no wall-clock assertion anywhere in the scenario set.

| row | site | scenarios | note |
|---|---|---|---|
| TMR-01 | `Generator.java:50`, bootstrap, fires once | **T** · (S · L · C) | T is the only scenario where `sim.arrival.firstFireMs` (1500) differs from `sim.arrival.lambdaMs` (4000), so TMR-01 is distinguishable from TMR-02. Elsewhere a port that dropped the bootstrap timer and armed the Poisson one immediately would produce the same golden. |
| TMR-02 | `Generator.java:65`, self-re-arming Poisson | S · L · T · C | T pins the count at 2 re-arms; C at 3; L at 1. |
| TMR-03 | `Planning.java:111`, one per planned train | zero delay: S · L · T · **non-zero delay: C** | In L and T every voter returns `diff=0`, so the timer is armed with 0 and `START` follows `VOTE_RESULT` in the same burst — which is the `t > 0 ? t : 0` branch. C defers vl1 by **8120 ms** and vl2 by **3072 ms**, each into a burst of its own. Both branches are therefore covered, and by different scenarios. |
| TMR-04 | `RoadAgent.java:102`, one per traversal | S · L · T · C; **immediate-fire branch: T** | See DEF-16 in §11. |

---

## 6. Events (EVT)

Each row maps to its channel or timer above; only the rows with a **split** or a gap are commented.

| row | handler | scenarios | note |
|---|---|---|---|
| EVT-01 | `pathFind` | S · L · T · C | |
| EVT-02 | `recieveStationInfo` | S · L · T · C | |
| EVT-03 | `recieveRoadState` | S · L · T · C | |
| EVT-04 | `recieveTrainState` | S · L · T · C | Dispatched on ACT-02's thread, unlike EVT-02/03 — that concurrency is DEF-24, class (c), §11. |
| EVT-05 | `planTrain` | S · L · T · C | |
| EVT-06 | `placeTrainIntoFirstStation` | S · L · T · C | |
| EVT-07 | `generateTrain` | S · L · T · C | |
| EVT-08 | `vote` | S · L · T · C | |
| EVT-09 | `pathFindReply` | S · L · T · C | |
| EVT-10 | `voteRequest` | S · L · T · C | |
| EVT-11 | `voteResult` | S · L · T · C | |
| EVT-12 | `Station.enter` | admit branch: S · L · T · C · **capacity branch: UNMAPPED** | See §10.1. |
| EVT-13 | `Station.leave` | `occupied--` branch: S · L · T · C · **admit-from-queue branch: UNMAPPED** | Same cause as EVT-12: the branch is only reachable if EVT-12 ever queued. §10.1. |
| EVT-14 | `RoadAgent.enter` | `acceptTrain`: S · L · T · C · **`push()`: C** | C is the only scenario that reaches `push`, and it reaches it in the opposing-direction form. |
| EVT-15 | `RoadAgent.leave` | `FREE`: S · L · T · C · **`pop()` → `acceptTrain`: C** | |
| EVT-16 | `travelStart` | S · L · T · C | |
| EVT-17 | `travelEnd` | S · L · T · C | |
| EVT-18 | `Train.start` | S · L · T · C | |
| EVT-19 | `Train.entered` | S · L · T · C | All three of its outcomes: `ENTER` the next object, `TRAVEL_START` on a road, and `Agent.die()` on a `null` `next`. |
| EVT-20 | `Train.travelEnd` | S · L · T · C | |
| EVT-21 | `destroy` | S · L · T · C | The one handler in the codebase with **no channel** — a lifecycle callback bound by name. `OpenCybeleLifecycleIT.assertOnlyTrainsDie` asserts both halves of AG-04's asymmetry: every train emits `state=KILL`, and nothing that is not a train ever does. A port that tears its static agents down at the bound fails there. |

---

## 7. Mutable state (ST)

State is mapped when a change to it changes the trace, not when the field exists.

| row | field | scenarios | note |
|---|---|---|---|
| ST-01 | `net` | S · L · T · C | Pinned by the routes: `PATH_FIND_REPLY.direction` and every `ENTER_REPLY.next`. Four distinct topologies across the set. |
| ST-02 | `stationInfos` | S · L · T · C (**value projected**) | The map is pinned by the `STATION_INFO` stream existing per station; the `occupied` value is not — DEF-13, §11. |
| ST-03 | `roadAgentStates` | S · L · T · C (**value projected**) | `state=<S>`; the code path behind it is C's. |
| ST-04 | `trainStates` | S · L · T · C | Every state string, including `KILL`, is pinned verbatim — `TRAIN_STATE.state` is deliberately *not* projected. |
| ST-05 | `roadDelays` | S · L · T · C | Read by ACT-01 to build each voter's expected arrival; the arithmetic is visible in `VOTE_REQUEST.expected` spacing and in the non-zero `diff`s of S and C. |
| ST-06 | `stationCapacities` | S · L · T · C | `capacity=` survives the normalizer on purpose — it is configuration a port must replicate. The single-field mutation in §2.2 is on exactly this. |
| ST-07 | `trainTableModel` | **UNMAPPED** | §10.4. |
| ST-08 | `Planning.queue` | S · **C** | A `PriorityBlockingQueue<TrainPlan>` holding more than one plan at a time needs a deferred departure; C has two (8120 ms and 3072 ms). In L and T the queue never holds two. |
| ST-09 | `votes` | S · L · T · C | Pinned by the ordering ACT-03 enforces (see ACT-03). |
| ST-10 | `trainCountDowns` | S · L · T · C | Same. |
| ST-11 | `openedChannels` | **UNMAPPED** | §10.5. |
| ST-12 | `index` | S · L · T · C | The `vl<N>` naming and its monotonicity. |
| ST-13 | `random` | S · L · T · C | Superseded by #15's per-agent seeded streams; every scenario pins `sim.random.masterSeed`. |
| ST-14 | `Station.roads` | **UNMAPPED** | §10.6. |
| ST-15 | `pathDirs` | S · L · T · C | The *cache* is pinned, not just the lookup: L has two trains on one route and exactly **one** `PATH_FIND` round trip. A port that resolved the direction per train would emit two and fail. |
| ST-16 | `Station.queue` | **UNMAPPED** | §10.1. |
| ST-17 | `Station.timetable` | S · C | Both branches of `computeDifference`, and by different scenarios: C reaches the **under-capacity** branch (`plannedTrains*voteWindow/6` = 583 with `voteWindow` 3500), S reaches the **over-capacity** branch (a station `diff` of 3000 at `voteWindow` 2000, which is not a multiple of `voteWindow/6` and can only come from the `lastKey() ± voteWindow/3` arm). |
| ST-18 | `Station.Info` | S · L · T · C (**`occupied` projected**) | |
| ST-19 | `RoadAgent.state` | S · C (**value projected**) | Direction of travel is pinned exactly, by `ENTER.position` and `ENTER_REPLY.next`, not by the enum: S traverses tr4, tr5, tr6 and tr7 in **both** directions; C traverses tr2 in both. |
| ST-20 | `traveledTrain` | S · L · T · C | Pinned by every `TRAVEL_START` having exactly one matching `TRAVEL_END` on the same road — DEF-14's "one train per road segment" invariant. |
| ST-21 | `RoadAgent.queue` | **C, at depth 1 only** — depth ≥ 2 **UNMAPPED** | §10.2. |
| ST-22 | `timetable` / `invertedTimetable` | S · C | Pinned by the road `diff`s: C returns 8112 and 3072 for tr2, S returns thirteen distinct non-zero buckets up to `<T~41000>`. |
| ST-23 | `leftStation` / `rightStation` | S · L · T · C | The NDT-03 assignment, visible in every road `ENTER_REPLY.next`. |
| ST-24 | `Train.from/to/position/nextPosition` | S · L · T · C | Including `position=null` on the first `ENTER` and the `next=null` arrival sentinel. |

---

## 8. Configuration (CFG)

| row | what | scenarios | note |
|---|---|---|---|
| CFG-01 | topology | S · L · T · C | Four distinct networks; S is the shipped one. |
| CFG-02 | station capacities | S · L · T · C | |
| CFG-03 | road delays | S · L · T · C | Including **0**, in T, which no other configuration in the project uses. |
| CFG-04 | travel-time noise `500*nextGaussian()` | **T** · (S · L · C) | T is where it is isolated: with `tr1`'s nominal delay at 0 the noise *is* the whole delay, and both of its signs are visible. |
| CFG-05 | global clock | S · L · T · C | The tick itself is projected; what survives, and what the goldens pin, is the burst structure derived from it. |
| CFG-06 | the `LAMBDA` split (arrival rate vs planning horizon) | **T · C** | #18 split one constant into `sim.arrival.lambdaMs` and `sim.station.voteWindowMs`. T sets them to 4000/4000 with `firstFireMs` 1500; C sets 3500/3500 and its station `diff` of 583 is `voteWindowMs/6` — a value that changes if a port re-merges the two. |
| CFG-07 | origin/destination pairs | S (all six defaults) · L · T · C | S is the only scenario with the shipped six; the other three use purpose-built sets. |
| CFG-08 | bootstrap delay | **T** | The only scenario where `firstFireMs` is distinguishable from the arrival rate. |
| CFG-09 | GUI pace presets | **UNMAPPED** | §10.4. |
| CFG-10 | GUI main-line layout | **UNMAPPED** | §10.4. |
| CFG-11 | kernel comm mode `Local;NoSerialization` | partially — see note | Its one observable consequence is DEF-13's aliasing, which is projected (§11). The kernel banner's `comm service` line is in every golden, so a port that failed to load the service fails; the *by-reference* semantics themselves are pinned by `docs/probes/ExpF.java`, not by a scenario. |
| CFG-12 | kernel thread pool | **UNMAPPED** | §10.7. |
| CFG-13 | kernel event queues (FIFO) | **UNMAPPED** | §10.7. |
| CFG-14 | kernel services loaded | S · L · T · C | The nine-line kernel banner is inside every golden at tolerance zero and names all seven services in order. |

---

## 9. Nondeterminism (NDT) and observable output (OUT)

| row | what | scenarios | note |
|---|---|---|---|
| NDT-01 | station creation order | **deliberately not pinned** | Class (b). `Cybele.createAgent` is asynchronous — six distinct constructor orders in six runs — and `docs/trace-normalizer.md`'s `startup-block` rule **sorts** the opening state block by agent name. Pinning it would be pinning a race. A different startup order is never a port bug. |
| NDT-02 | road creation order | **deliberately not pinned** | Same rule, same reason. |
| NDT-03 | which station is left vs right | S · L · T · C | Pinned by `ENTER_REPLY.next` on every road. |
| NDT-04 | which route a train takes | S (all six default routes) · L · T · C | S is the only scenario that pins the shipped routing. INVENTORY's landmine — that `Util.path` and the per-station `Util.pathDirection` chain must agree, or a train traverses objects that never voted on it — is pinned in every golden by construction: a divergence would put an `ENTER` on an object with no `VOTE_REQUEST` for that train. |
| NDT-05 | the single unseeded `Random` | S · L · T · C | Removed by #15; every scenario pins `sim.random.masterSeed` and `ScenarioCatalogIT` fails a scenario that does not. |
| OUT-01 | `Planning`'s `"<train> in <station> at <n>"` | S · L · T · C | The instant is projected to `<T>`; the *order* of the stream is kept, because its producer is one serial activity. |
| OUT-02 | `Train`'s `"<train> started"` | S · L · T · C | Sorted by train name by the `println-streams` rule — see §10.8. |
| OUT-03 | `Util.sleep`'s `printStackTrace` | **UNMAPPED** | §10.6 — `Util.sleep` has zero call sites. |

---

## 10. The unmapped rows, each with its measurement

### 10.1 `Station.enter`'s capacity branch — EVT-12, EVT-13, ST-16

**The one edge behaviour the issue names that turns out not to be reachable.**

The issue asks for "station at capacity (queueing, `Station.java:90-91`)". That branch is taken when
`info.occupied == info.capacity` at the moment an `ENTER` arrives. It was **not reached at any
configuration tried**, and the attempts were not timid:

| configuration | trains planned | departures | road-queue events | **station-queue events** |
|---|---|---|---|---|
| shipped topology, λ = 500, 120 s, capacities 2–6 | 244 | 27 | 34 (waits up to 6.3 s) | **0** |
| shipped topology, λ = 500, 120 s, **every capacity = 1** | 244 | 27 | 1 | **0** |
| 3-station line, λ = 2000–3500, capacity 1 on the merge station, 60 s | 20–32 | 6–11 | 2–7 | **0** |

The mechanism is structural, not a matter of finding the right knob. A station's dwell is
approximately zero — a train that enters requests the next object in the same tick — *unless* its
onward road is busy, in which case it waits at the station for the length of the road-queue wait. That
wait is jitter-scale (measured maximum 6.3 s at 244-train density, and 0.5 s at any density that
reproduces), because `Planning` models nominal road delays exactly and `RoadAgent.computeDifference`
spaces entries on a road by that road's own delay. So for a station to fill, trains must arrive at it
faster than the road-queue wait — and the election is precisely what prevents that. Lowering the
capacity to 1 makes it *less* likely, not more: `Station.computeDifference` defers on
`plannedTrains > capacity-1`, so at capacity 1 it defers on **any** planned train in the window, which
is why the second row above has fewer road-queue events than the first.

**Verdict: unmapped, and no scenario should be written around it.** It is a defensive branch that the
election makes unreachable at every configuration measured. What *is* mapped is the observable
behaviour of the same feature — a station voting for a delay because it is near capacity, which is
ST-17's over-capacity branch, covered by `opencybele-strict`.

Consequence for #36/#43: a JADE or Jason port must still *have* the branch (an unreachable branch that
is deleted becomes reachable the moment the timing changes), but no golden will catch its absence.
This should be an L1 unit test on the extracted `Station.enter`, in the same family as #28's DEF-03
and DEF-04 tests.

### 10.2 `OueueItem.compareTo` — ST-21 at depth ≥ 2, and therefore DEF-03 and DEF-04

`RoadAgent.queue` is a `java.util.PriorityQueue`, and `PriorityQueue.offer` on an **empty** queue
performs no comparison at all. `opencybele-congestion` queues exactly one train at a time, so the
comparator — with it DEF-03's `int` narrowing and DEF-04's dead `frequency` tie-break — **never runs**.

Measured: maximum simultaneous pending `ENTER`s per road, over whole runs.

| configuration | max queue depth |
|---|---|
| `opencybele-congestion`'s topology, all bounds tried | **1** on both tracks |
| 3-station line at λ = 3000, 60 s | **1** on both tracks |
| shipped topology, λ = 500, 120 s (244 trains planned, 27 departed) | **2** on tr3, tr4, tr6 and tr7 |

So depth 2 exists, but only at a density where DEF-02 drops trains and the departure set stops
reproducing — the density `docs/defect-triage.md` §3.3 tells scenario authors to stay under. Extending
`opencybele-congestion`'s bound to reach its own later, longer queue waits was tried and rejected: its
fourth queue event has a wait of 216 ms against the normalizer's 220 ms burst boundary (§12), which is
a flake by construction.

**Verdict: unmapped, deliberately.** This is not a loss: `docs/defect-triage.md` §3.1 already says
DEF-03 is "unreachable in any golden scenario" (its inversion point is 24.9 simulated **days**) and
DEF-04 "always returns 0", and pins both by **L1 unit test (#28)** on the extracted comparator rather
than by scenario luck. This section is the measurement backing that decision.

### 10.3 A train whose origin equals its destination

The issue's third edge behaviour, `Station.java:100` — `if (target.equals(getName())) return null`.

**It cannot be configured, and it does not need to be.** Two separate findings:

1. `ScenarioConfig` **rejects** `sim.arrival.pairs` entries with the same origin and destination
   (`ScenarioConfig.java:714`, `"… has the same origin and destination"`, exit 1). Since #18 there is
   no way to ask the application for such a train.
2. The line the issue points at is **not** an origin-equals-destination special case in practice. It is
   the *arrival* test, and it is taken by **every train in every scenario**: a train entering its
   destination station gets `null` back, `Train.entered` reads `null` `nextPosition` as "arrived", and
   calls `Agent.die()`. Every `ENTER_REPLY … next=null` in every golden is that branch.

A scenario pinning the *rejection* was considered and rejected. `run.expect: startup-error` exists and
would work, but the artefact would be an exception with a stack trace naming
`cz.vutbr.fit.ags.xhovor07.ScenarioConfig` — which **no port can reproduce**, since a JADE port has
different classes and different line numbers. A parity golden that no port can match is worse than no
golden. The rejection belongs in an L1 test on `ScenarioConfig`, and #14's assertion work is where it
should live.

**Verdict: the code path is mapped (S · L · T · C); the degenerate configuration is unreachable by
construction and is not worth a scenario.**

### 10.4 The GUI — ST-07, CFG-09, CFG-10, and DEF-24

Every scenario sets `sim.headless=true`, so `Gui` and `RailwayCanvas` are never constructed and
`sim.gui.paces` / `sim.gui.mainLine` / `sim.gui.branches` are inert. They are still **declared** in
every scenario, because `docs/parity-harness.md` requires the property block to be total, and
`ScenarioCatalogIT` enforces that — but they change nothing.

This is deliberate and it is not a coverage gap for the port: #36 produces no Swing GUI, so there is
nothing on the other side for a GUI golden to be compared against. Measuring with a window is also
forbidden for a second reason — #16 found that a variance sweep taken on the shared display `:0`
showed 5–10 differing departure ids that collapsed to **zero** on a private display.

`ST-07`/`DEF-24` (`TableModel.update` iterating a `synchronizedMap` off-lock) is a special case worth
naming, because `docs/defect-triage.md` §4.5 **corrected** the earlier belief that headless excludes
it: `trainTableModel` is registered as an `Observer` *above* the headless guard, and `update` runs
synchronously on the agent handler thread. So the hazard is live in every scenario here. It is class
(c) — the fix would change the interleaving that produces DEF-13 — and the handling is the recording
gate: a `ConcurrentModificationException` from `TableModel.update` means **discard the run**, never
re-record around it. None was seen in any run made for this issue.

### 10.5 DEF-10 / ST-11 — the channel leak

`Generator.openedChannels` accumulates one channel ticket per train forever; `closeChannel` is called
nowhere. This is not a per-message behaviour, so there is nothing for a golden to pin. Per
`docs/defect-triage.md` §6.3 the decision is **bound the scenario, do not fix**, and the degradation
point is explicitly **unmeasured**. The scenarios here are the smallest in the project (2–9 trains
each, against `short.properties`' 244), so they sit far inside whatever the unknown bound is.
**Unmapped, by the triage's own decision.**

### 10.6 Dead code — ST-14 and OUT-03

`Station.roads` is injected by the constructor and read only by a getter with no callers.
`Util.sleep` — whose `printStackTrace` is OUT-03 — has zero call sites (`grep -rn 'Util.sleep' src/`
returns nothing). Neither can produce output; neither is mappable. Recorded so a port that "restores"
either knows it is adding behaviour.

### 10.7 Kernel configuration — CFG-12, CFG-13, and SEM-01

`cybele.prop`'s thread pool (`5 10 4000 3 5`) and its commented-out event-queue lines are **kernel**
configuration, not `sim.*` configuration: no scenario can vary them, and the kernel prints neither.
They were established by probe — `docs/probes/ExpA2.java` with a positive control for SEM-01's strict
FIFO verdict — and that is the right instrument for them. What the goldens pin is the *consequence*:
the line order inside a burst, which is what FIFO dispatch produces.

**Unmapped as scenario rows; covered by `docs/probes/`.** A port on JADE has neither file, so this is
input to #27's mapping rather than something a golden can compare.

### 10.8 `println-streams` is still a low-power rule — and this is the honest version

`docs/trace-normalizer.md` §2.3 splits the two application `println` families into two streams and
**sorts the `started` stream by train name**, because its lines have one producer per train and race.
#21 recorded that the rule only *matters* when two trains tie on a departure millisecond, which the
current seed almost never produces, and asked whether a scenario could produce a tie.

**None of these four does.** No scenario in the set has two departures in the same simulated
millisecond, so in all four goldens the sorted `started` stream happens to equal the emission order,
and removing the rule would change nothing. Attempts to force a tie were not made part of the set: a
tie needs two trains planned to the same departure instant, which needs the arrival rate raised into
the regime where DEF-02 drops trains (§10.2's third row) — trading a rule nobody can observe failing
for a scenario that cannot hold `strict`.

**Verdict: unmapped, and recorded as a known weakness of the normalizer rather than of the scenarios.**
The rule stays because the race it absorbs is real (three orderings across six runs at one seed,
measured by #21); it is simply not *exercised* by this set. Note also that a tie would exercise
DEF-17, which `INVENTORY` itself says "no observation can confirm or refute from the outside" — so a
tie scenario would be pinning something nobody could interpret.

---

## 11. Defects (DEF-01 … DEF-24)

Mapping follows [`docs/defect-triage.md`](../../docs/defect-triage.md) §3. Class (c) rows are
**out-of-contract carve-outs**: they are unmapped *by decision*, and the decision is not this file's to
revisit.

| row | class | scenarios | note |
|---|---|---|---|
| DEF-03 | (a) | **unmapped — L1 (#28)** | Inverts at 2³¹ ms = 24.9 simulated days. Unreachable at scenario scale by the triage's own words; and see §10.2 — the comparator does not run at all in this set. |
| DEF-04 | (a) | **unmapped — L1 (#28)** | Dead tie-break, always 0. §10.2. |
| DEF-07 | (a) | S · L · T · C | The ordering — `ENTER_REPLY` from the new object before `LEAVE` to the old — is in every golden, and `OpenCybeleLifecycleIT` asserts it directly, **special-casing the first hop** exactly as the triage cell warns (a train's first `ENTER_REPLY` has no paired `LEAVE`, because `leaveObject` is a no-op while `position == null`). |
| DEF-08 | de-claimed | S · L · T · C | Not a defect. What is pinned is the *shape*: the destructor's `LEAVE` to the destination station, which balances its `occupied++`. A port that drops it leaks occupancy forever, and `OpenCybeleLifecycleIT` fails if it is missing. |
| DEF-11 | (a) | **unmapped — unmappable** | `==` on interned `String`s behaves identically to `.equals` here; a port using either produces a byte-identical trace. |
| DEF-14 | (a) | S · L · T · C | Pinned as the invariant "one train per road segment": every `TRAVEL_START` has exactly one matching `TRAVEL_END` on that road. |
| **DEF-16** | (a) | **T** | *"PIN — do not clamp … a port that clamps to 0 produces a diff and THAT is the port bug."* Until #23 nothing made it happen: at the shipped 1 s delays it fires on **2.2645 %** of traversals. `opencybele-timers` gives `tr1` a nominal delay of **0**, so the armed value is `500*nextGaussian()` and roughly half the draws are at or below zero. Measured, four captures: `tr1`'s three traversals take 8 ms, 24–32 ms and 296 ms — two on the fire-immediately branch, one ordinary. The golden sees it through burst structure (an instantaneous traversal keeps `TRAVEL_END` inside its `TRAVEL_START`'s burst); the "clamp" mutation in §2.2 confirms a clamping port fails. |
| DEF-13 | (b) | mechanism mapped, **value projected** | `STATION_INFO.occupied` is a live alias mutated after the send. The stream is pinned, the number is not. Never a port bug. |
| clock families | (b) | mechanism mapped, **values projected** | `tick`, `expected`, `planned`, the departure `println` instant; `diff` is **quantised, not erased**, and §2.2's "zero one `diff`" mutation shows the quantum has teeth. |
| agent init order | (b) | **deliberately not pinned** | NDT-01/02; sorted by the `startup-block` rule. |
| DEF-02 tail | (b) | **excluded by bound choice** | Every bound in the set sits inside a measured quiet window, so no scenario's golden ends on a ragged departure. §12. |
| DEF-01 | (c) | **unmapped — carve-out** | Spurious-wakeup branch of an untimed `wait()`. Never observed; unreproducible on demand. A `null` `next=` on an `ENTER_REPLY` mid-route is out-of-contract. |
| DEF-02 | (c) | **unmapped — carve-out** | The `START` race. A dropped train is *absent*, not reordered; no normalisation recovers it. Suppressed here by low density — 2 to 9 trains per scenario — and by the cross-occasion check in §2.1, which is the only instrument that sees it. |
| DEF-05 | (c) | **unmapped — carve-out** | Duplicate-vote overwrite. Never observed in 36 rounds. Under `-ea` it aborts the run. |
| DEF-06 | (c) | **unmapped — carve-out** | Unboxing NPE inside `compareTo`. §10.2 makes this doubly unmapped: the comparator does not run in this set at all. |
| DEF-09 | (c) | **unmapped — carve-out** | Late-vote NPE. Never observed in 372 evaluations. |
| DEF-10 | (c) | **unmapped — by decision** | §10.5. |
| DEF-17 | (c) | **unmapped — uninterpretable** | *"No observation can confirm or refute this from the outside."* §10.8. |
| DEF-22 | (c) | **unmapped — but the contract level is the instrument** | The wedge exits 0 with a clean stream, and `sim.stop.stallMs` structurally cannot catch it. What catches it is the trace: the departure stream truncates. All four scenarios are `strict`, where a wedge is a **certain** failure; at `summary` a late one hides inside a tolerance. That is the strongest single argument for having folded `opencybele-smoke` away. |
| DEF-23 | (c) | **unmapped — carve-out** | The no-wakeup branch. A station that stops emitting `STATION_INFO` mid-run is a wedged baseline. |
| DEF-24 | (c) | **unmapped — carve-out** | §10.4. Headless does **not** exclude it. |
| DEF-12, DEF-19, DEF-20 | not defects | **n/a** | Invariants to record, no behaviour, no diff signature. |
| DEF-15 | resolved | **n/a** | Resolved on `opencybele-baseline` by #17's barrier plus `verifyClockControl` (exit 5). Exit 5 means the run is void. |
| DEF-18 | invariant | S · L · T · C | Enforced at startup since #18 (`capacity >= 1`); every scenario's capacities satisfy it. |
| DEF-21 | resolved | S · L · T · C | Every scenario runs the child with `-ea` (the adapter always adds it), so the 33 assertions are live. None fired in any run made for this issue. |

## 11b. Cybele runtime semantics (SEM)

| row | scenarios | note |
|---|---|---|
| SEM-01 | **unmapped as a scenario row** | §10.7. Established by probe with a positive control. |
| SEM-02 | S · L · T · C, and **C for the second bracket** | `Planning.planTrain` brackets its timer arming in `pauseClock`/`resumeClock` in every scenario; `RoadAgent.push`/`pop` add a **second** bracket that only C reaches. INVENTORY's unresolved "pause/resume loss under artificial burst load" (2 of 40 runs at 50 back-to-back pairs) does not apply: C issues two brackets in a whole run, well inside the 0-of-1000 realistic-shape measurement. |
| SEM-03 | **unmapped — not used** | The kernel supports two simultaneous subscribers; the application opens every channel name exactly once, so there is no fan-out anywhere to exercise. Input to #27, not a gap. |
| SEM-04 | S · L · T · C, indirectly | Serial dispatch per activity is *why* ACT-03 and ACT-04 exist; their round trips are in every golden. A port with non-blocking continuations will not need them, which is a design difference #34 has to declare, not a golden diff. |
| SEM-05 | mechanism mapped, **consequence projected** | Pass-by-reference; its observable effect is DEF-13. Pinned properly by `docs/probes/ExpF.java`. |
| SEM-06 | negative timer: **T** · zero-delay timer: S · L · T · C · silent drop: **carve-out (DEF-02)** | The three findings split three ways. The zero-delay case is `Planning.java:111`'s `t > 0 ? t : 0`, taken by every immediate departure. |

---

## 12. Method — two things a future scenario author needs

### 12.1 A bound is load-bearing, twice over

Every scenario file records its bound's margins, because two different races are sized against them.

**The `Cybele.terminate()` race.** A `Train` constructor in flight at the stop instant reaches
`Activity.openChannel` after `commReceiver` has been nulled; the kernel prints an NPE **after** the
stop banner and **the exit status is still 0**. A train is created at plan time, so the exposure is the
gap between the last train creation and the bound. Reference measurements: 3 failures in 8 under load
at ~5 ms of wall clock; 0 in 24 at ~480 ms.

| scenario | last train creation → bound | in wall clock at pace 8 |
|---|---|---|
| `opencybele-lifecycle` | 10 464 ms | **1308 ms** |
| `opencybele-timers` | 3 876 ms | **485 ms** |
| `opencybele-congestion` | 3 964 ms | **496 ms** |
| `opencybele-strict` | 2 776 ms | 347 ms (inherited; not re-tuned by #23) |

**The burst-boundary race — this one cost a gate failure and is the new finding.**
`CanonicalTraceNormalizer` segments at tick gaps **> 220 ms** and sorts within a segment. A gap that
sits *near* 220 is a coin toss: the same structural gap was measured reading **192 ms in one capture
and 224 ms in another**, and when it crosses, two bursts merge, the sort re-orders them, and a
`strict` golden fails with a diff that looks like a content change and is not.

The first `opencybele-congestion` (λ 3000, `firstFireMs` 200, bound 29000) had exactly such a gap at
simulated ~27 040 and **failed the zero-flake gate on its second run**. It was retuned by scanning
every consecutive-tick gap in the run and requiring that none fall in **150–380 ms**:

| scenario | tick gaps in 150–380 ms |
|---|---|
| `opencybele-lifecycle` | **none** |
| `opencybele-congestion` | **none** (measured over three captures) |
| `opencybele-timers` | one, at 296 ms — 76 ms clear, and it *is* the DEF-16 signal |
| `opencybele-strict` | four (208, 272, 288, 304 ms) — inherited, and 10/10 green |

`sim.arrival.firstFireMs` is 1500 in two scenarios for this reason alone: at 200 the first
`PLAN_TRAIN` lands ~200 ms after the opening state block, and that boundary sat on the threshold in
every configuration tried.

**So: run the gap scan before recording, not after the gate fails.**

### 12.2 Adding a scenario

1. Shape it with `sim.*` (`docs/scenario-config.md` on `opencybele-baseline`); declare **all twenty**
   keys — `ScenarioCatalogIT` fails a scenario that leaves one implicit.
2. Choose the bound inside a quiet window, and record **both** margins from §12.1 in the file.
3. Prefer `strict`. `docs/trace-normalizer.md` showed the blocker is usually the bound, not the
   projection, and DEF-22 is a certain failure at `strict` and a hidden one at `summary`.
4. Give it a liveness rule that can actually fail, counting **distinct** entities where that is the
   point. A floor of 0 is rejected at parse time.
5. Record the golden, then **mutation-test it**: delete a family, and separately move one line into an
   adjacent burst. Both must fail. A scenario that survives either is not coverage.
6. Gate it — `./gradlew parityGate -Dparity.gate.runs=10 -Dparity.gate.scenario=<id>` — **before**
   adding its row to this file.
7. Add it here, and add its unmapped rows with their measurements.
