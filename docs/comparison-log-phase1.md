# Phase-1 comparison log — OpenCybele (Cybele) → JADE

> Issue [#41](https://github.com/bedaHovorka/OpenCybele1/issues/41) ·
> [`Phase1.md`](Phase1.md) 1-POST.4 · this closes Phase 1.
> **Direct input to [`COMPARISON.md` (#53)](https://github.com/bedaHovorka/OpenCybele1/issues/53)
> and to Phase-2 planning.** Companion: [`../jade/README.md`](../jade/README.md) (per-agent
> mapping notes and the out-of-contract list).
>
> **Every number here carries its source** — a doc section, a commit, an issue, or a run recorded
> in §8. Where three corpora disagree, all three are given and the disagreement is treated as the
> finding. This project has twice caught itself quoting a rate from a single run
> (PR #80's *"four of five byte-for-byte"*) and once caught itself citing a tautology as evidence
> (mutation M11, withdrawn in `725bc18`); §9 keeps both.

**Contents.** §1 LOC · §2 port effort · §3 what did not map cleanly · §4 runtime observations ·
§5 the four hard equivalences · §6 test-writing experience · §7 the JADE distribution decision ·
§8 the Definition-of-Done verdict · §9 numbers that disagree · §10 handover to Phase 2.

---

## 1. Lines of code

Counted at `16193ad` on `phase1/1-port`. **"Code LOC" is non-blank, non-comment lines**;
raw-line counts are given beside them because the ratio between them is itself a result.

### 1.1 Implementation

| | 2008 original (`origin/develop`) | recordable baseline (`opencybele-baseline`) | JADE port (`16193ad`) |
|---|---|---|---|
| agents + activities | **778** | 814 | **1 352** |
| GUI (`Gui`, `RailwayCanvas`, `RailwayView`) | 185 | 251 | 269 |
| `util` / domain | 423 (in `src/main`) | 462 (in `src/main`) | 1 534 (`src/domain`) |
| JADE glue (`src/jade`) | — | — | 153 |
| 1-PRE harness instrumentation inside `src/main` | — | 1 285 | 1 099 |

The **like-for-like number is 778 → 1 352 + 153 = 1 505**, a factor of **1.93**, for the agent and
activity code alone. Adding the port's own new domain class (`VoteRound`, 97) gives **1 602**, ×2.06.

### 1.2 The growth is commentary, not program

`src/main` in raw lines went **4 200 → 7 295 (+74 %)**; in code lines **2 267 → 2 720 (+20 %)**.
Comment and blank lines went **1 933 → 4 575**. Two thirds of the diff is Javadoc recording why a
statement is what it is — which was a deliberate policy of this port, since the goldens cannot
carry an argument and the next migration has to be able to reconstruct one.

Per file, `src/main`, code LOC, baseline → port:

| | | | | |
|---|---|---|---|---|
| `Station` | 113 → **245** | `RoadAgent` | 155 → **218** | `Train` | 70 → **152** |
| `RailwayMainAgent` | 170 → **324** | `Planning` | 112 → **185** | `Generator` | 44 → **75** |
| `Main` | 53 → **146** | `StaticRailwayObject` | 38 → **7** | `RailwayView` | — → **19** |
| `Gui` | 70 → **71** | `RailwayCanvas` | 181 → **179** | `TraceProbe` | 349 → **201** |
| `RunControl` | 268 → **166** | `ScenarioConfig` | 631 → **637** | `SimRandom` | 37 → **37** |
| `PathFinding` | 22 → **deleted** | `VoteCollecting` | 26 → **deleted** | `RailwayObject` | 11 → **deleted** |

### 1.3 Tests

| source set | baseline (`origin/jade-develop`) | port (`16193ad`) | count |
|---|---|---|---|
| `src/test` (L1) | 2 318 | **5 845** | 321 tests, 0 skipped |
| `src/integrationTest` (L2) | 0 | **1 007** | 17 tests, 8 classes |
| `src/characterizationIT` (L3 harness) | 4 579 | 5 200 | 84 tests in the jade lane |

**Implementation : test ratio for the port is 1 505 : 6 852, i.e. 1 : 4.6** counting L1 + L2 only,
or 1 : 8.0 counting the shared harness. The baseline had **zero test code** (`INVENTORY.md` CNT-12).

### 1.4 What the numbers do **not** include, and one thing they must not be annotated for

* **There is no dropped GUI.** #41's own issue text and #53's checklist both anticipate one.
  **#35 decided the opposite** — *"Port the Swing view, off by default. Not headless-only."* —
  and `Gui.java` and `RailwayCanvas.java` are reused **verbatim** behind a 19-line `RailwayView`
  interface. That reuse was the *point*: it is the evidence that the GUI coupling really was three
  files wide. **Do not carry a "minus the GUI" caveat into `COMPARISON.md`.**
* **No generated code.** There is no `jade.content.onto.Ontology`, no SL codec, no schema
  generation. Content is a Java-serialized record via `setContentObject`
  (`message-ontology.md` §5.8). Nothing in the count is machine-written.
* **`src/domain` mostly predates the port.** 1 437 of its 1 534 code lines landed in #28/#29
  before 1-PORT began; the port added exactly one class, `VoteRound` (97 code LOC).
* **`src/characterizationIT` is a shared asset, not a JADE cost.** It is the Phase-1 harness and
  Phase 2 consumes it unchanged. Only `JadeLauncher` names JADE, and
  `JadeLauncherIT.no_harness_file_outside_the_jade_package_names_JADE` asserts it.
* **The 1-PRE instrumentation inside `src/main`** (`ScenarioConfig`, `RunControl`, `SimRandom`,
  `TraceProbe`, `TraceTopics`) is measurement apparatus, not application. It is 1 099 of the port's
  2 720 `src/main` code lines — **40 %** — and would not exist in a migration that did not have to
  prove itself.
* **`docs/` is excluded entirely.** It is larger than the program.

---

## 2. Port effort, and where it actually went

Thirteen commits, `0b0e6f0`…`16193ad`. Diff size is a poor proxy for effort here (see §1.2), so the
table gives both the diff and what the ticket's difficulty actually was.

| # | ticket | commit(s) | diff | tests after | where the effort went |
|---|---|---|---|---|---|
| 1 | `Station` (#30) | `0b0e6f0`, `1178996` | 1 020 + 414 | 168 | **The blocking `wait()` → continuation.** The design took the ticket; the *review* found a real bug (a null direction cached unconditionally, which both manufactured DEF-01 out of DEF-23 and broke the coalescing invariant the whole line-multiset argument rests on) and falsified premise 2 of the observability argument. |
| 2 | `RoadAgent` (#31) | `18944e9`, `725bc18` | 1 443 + 233 | 207 | **The timer onto the clock service**, then a review that found the ticker had been budgeted in *real* ms against a *simulated*-ms threshold — a fixed 10 ms period added U[0, 80] simulated ms to every reaction event against a 44 ms margin. |
| 3 | `Planning` + `VoteCollecting` (#34) | `c321118` | 1 622 | 231 | **The `CountDownLatch`.** Not the counting — the counting is 97 lines. The hard part was recognising that `await()` serialised the *channel*, and keeping that seriality without blocking (§3.1). |
| 4 | `RailwayMainAgent` + `Generator` (#33) | `908ddc9` | 2 493 | 265 | Largest diff, and mostly **startup ordering**: proving that no readiness handshake was needed and that the normalizer's `startup-block` precondition (an unbroken opening run) had a 25-real-millisecond budget for fifteen JADE agents. |
| 5 | `Train` (#32) | `5186978` | 1 311 | 286 | **The death sequence**, read off `jade-4.3.jar`'s bytecode rather than assumed: `die()` → `doDelete()` → `takeDown()` emits `LEAVE` then `KILL`, and three platform facts have to hold for that order. |
| 6 | `JadeLauncher` + probe (#36) | `a0464fb` | 1 934 | 286 | Making the port **executable by the harness**. Found and fixed three (a)-class port bugs (T-01…T-03). |
| 7 | L3 parity run (#39) | `af88b1b` | 905 | 286 | **400 captured runs**, a width sweep, and seven candidate repairs implemented and measured before rejection. |
| 8 | L1 audit (#37) | `88ae112` | 726 | **321** | An audit, not a green field: `TraceProbe` — the instrument every L3 comparison reads through — had **no unit test at all**, and **three existing tests could not fail**. |
| 9 | L2 layer (#38) | `c9a3dd7` | 2 068 | +17 IT | The wiring POJO tests cannot reach: 17 in-process container tests, one class per interaction pair. |
| 10 | CI (#40) | `8ca95b5`, `16193ad` | 1 968 | — | Deciding **what "green" may mean** for a lane whose honest verdict is not "five green" (§8.1). |

**The two the issue predicted would dominate did — but not for the predicted reason.** #41's issue
text expects `Planning`/`VoteCollecting` and `Station`. Both were indeed the hard tickets, and in
both cases the difficulty was *not* the data structure that replaced the blocking primitive. It was
**working out what the blocking primitive had been buying**, which in both cases was a property of
Cybele's dispatcher rather than of this application (§3.1, §3.2).

**The unpredicted cost centre was time.** No planning document has a row for the clock;
`CYBELLE_TO_JADE.md`'s mapping table mentions it only inside a correction to the timer row. It is
the port's largest new subsystem — 8 domain classes plus `ClockTickerBehaviour` — and the
measurement campaign behind it (probes `ExpI`, `ExpJ`) predates 1-PORT.

---

## 3. What did not map cleanly

### 3.1 The blocking `CountDownLatch` election — whose real job was serialising the *channel*

`Planning.planTrain` broadcast `VOTE_REQUEST` to every object on the path and then sat in
`latch.await()` while a second activity, `VoteCollecting`, counted the replies.

The tempting port is "collect asynchronously and stop blocking". It is wrong, and the reason is not
about the train:

> `latch.await()` did not stall one train, it stalled the **channel**. Cybele dispatches one
> activity's events serially (`INVENTORY.md` SEM-04), so the 2008 application ran **exactly one
> election at a time.** — `c321118`

That seriality is **contractual twice over**:

1. Every voter's `computeDifference` sees a timetable that already holds the previous train's
   `addToPlan`. Overlapping elections change departure times **on a healthy run**.
2. DEF-22's recorded observable *is* the seriality — *"no further train is planned for the rest of
   the run"*. **A planner that overlapped elections would have quietly repaired the most severe
   latent failure in the codebase** (`defect-triage.md` §3.3; measured at 2 hangs in 90 runs,
   ~1 in 45, exit 0, silent).

The port keeps the gate without blocking anything: while a round is open, `Planning.Inbox` narrows
its template to `VOTE` alone, and a `PLAN_TRAIN` stays in the agent's own mailbox in arrival
order — exactly as it stayed in the Cybele channel queue. `VoteRound` holds the election as a
*pair* of counters (ballots keyed by voter, plus an outstanding count decremented per **arrival**),
because the gap between those two is DEF-05 and DEF-05 is pinned, not repaired.

**Also not mapped, deliberately:** no `ContractNetInitiator` and no `:protocol`. The election is a
*degenerate* contract net — `accept-proposal` goes to every voter carrying a value **no voter
proposed**, there is no `refuse`, no `reject-proposal` and no deadline
(`message-ontology.md` §4.1). A `reply-by` would change behaviour under exactly the load where
DEF-22 shows, and **a run that times out where the baseline hangs is not a parity run**.

### 3.2 `wait()`/`notify()` inside a handler — and the correction that matters

`Station.getPathDirection` sent `PATH_FIND` and called `Object.wait()`; the `PathFinding` activity
existed solely to own a thread that could take the reply and `notify()` it.

**The correction, because the first reading of this was wrong and it changes the port's argument:**
`INVENTORY.md`'s DEF-01 row used to say the station stalled because the monitor was held. It does
not:

> the station stalls for the whole round trip — **not because the monitor is held**
> (`Object.wait()` **releases** it, which is exactly why `PathFinding.pathFindReply`'s
> `synchronized (station)` at `:45` can acquire it at all), **but because per SEM-04 the blocked
> handler occupies the station's own activity**, starving its
> `enter`/`leave`/`voteRequest`/`voteResult` channels until the reply arrives.
> — `INVENTORY.md` §13, DEF-01, corrected in `a7b284e`

So the stall was **SEM-04 dispatch, not monitor retention**, and two follow-on claims collapse with
it: the `notify()`-vs-`notifyAll()` complaint is vacuous (serial dispatch means there was never more
than one waiter), and "the station monitor is held while waiting" — which is how #30's own issue
body words it — is false.

The port replaces the wait with a continuation: synchronous in-line on a cache hit, so the warm path
emits 2008's messages in 2008's order; on a miss, parked in a per-target map, with misses on one
target **coalescing into a single request** because the 2008 station could not have a second request
in flight. Consequences that had to be decided rather than inherited:

* **One deliberate statement reordering.** `Station.leave` resolved the direction *before*
  `removeTrain`. Unobservable in 2008 — SEM-04 makes that window impermeable — but a continuation
  makes it permeable, and a `VOTE_REQUEST` landing inside it would vote against a departed train.
  `removeTrain` is hoisted. **Transliterating the statement order would have changed the behaviour.**
* **DEF-23's starvation half cannot be reproduced**, and the port says so rather than approximating
  it: it is a property of Cybele's serial per-activity dispatch, not of this application. The
  *untimed* half is reproduced exactly — no timeout, no fall-through, because resuming with a null
  would manufacture DEF-01 out of DEF-23.

### 3.3 The global pace-adjustable simulated clock

Cybele's clock is first-class and global: `Cybele.createClock`, one `myClock`, all four timer sites
armed on it, `Cybele.getTime` the only time source in `src/` (`System.currentTimeMillis`,
`System.nanoTime` and `Math.random` appear nowhere), pace adjustable at runtime from the toolbar,
and the clock can be paused. **JADE has nothing of the kind** (`clock-abstraction.md` §1).

`WakerBehaviour` and `TickerBehaviour` are both wall-clock, with a deadline fixed when the behaviour
is added. Three requirements they cannot meet: a **pace** change (which would mean removing and
re-adding every pending timer on every agent with a recomputed deadline, on every button press), a
**pause** (whose deadlines keep arriving), and a **shared** ordering of two agents' wake-ups.

The port carries the clock itself: `SimClock`/`PacedClock`/`VirtualClock`/`DeadlineQueue`/
`AgentClock` in the framework-free `domain` source set, plus one `ClockTickerBehaviour` per arming
agent. Deadlines are **absolute simulated instants**, so a pace change moves nothing and a pause
stops everything — Cybele's rescale semantics met by making the rescale a **no-op**.

Two things this changed that Cybele did not have, both recorded rather than discovered later
(`725bc18`): timer error is **one-sided** (never early), and it is **correlated within** an agent
but **uncorrelated between** agents, because each ticker's phase is fixed by when that `setup()`
ran. Cybele had one kernel timer service and no per-agent phase.

### 3.4 The `pauseClock` idiom — which cannot exclude anything, and does remove real drift

Three sites bracket a short critical section in `Cybele.pauseClock`/`resumeClock`. #29 called this a
**mutex** and proposed deleting it on the grounds that pause is not counted. Both halves of that
framing were tested before being acted on, and they came apart.

**`ExpI` — the bracket excludes no thread** (`clock-abstraction.md` §2.2–§2.4). Two distinct Cybele
agents on their own handler threads: A pauses, holds 300 ms, resumes; B pauses and resumes entirely
inside A's hold. Measured, A's own `getTime` delta across its own bracket:

```
control (A alone)   min=0   max=0   median=0
overlap (A and B)   min=189 max=191 median=190     [predicted if not counted: 300+10-100-20 = 190]
```

**20/20 per run, 60/60 across three runs; control 0/60.** And verdict 2, the one that matters:
B **entered** its body inside A's bracket 20/20 and **returned** from it still inside A's bracket
20/20. So the second agent's body ran inside the first's bracket. *"Pausing a clock stops simulated
time; it does not stop a thread. No thread was ever excluded from anything."*

The exact scope, which §2.4.1 insists on because "the idiom excludes nothing" is too strong to hand
to a port author: **excludes no thread; defers clock-derived events, system-wide, by the bracket
duration, which the normalizer projects.** That is the sentence to cite — a *bigger* blast radius
than a mutex, and a different one.

**`ExpJ` — and yet one of the three brackets is load-bearing** (§2.5). `Planning.java:109` reads the
clock to turn an absolute agreed departure into a *relative* delay; `setTimer` then measures that
delay from the kernel's **own, later** read. Three arms, `n = 10`, `D = 1000 ms`, gap `200 ms`:

```
baseline  min=1001 max=1002 median=1001   [control: no gap]
drift     min=1200 max=1202 median=1202   [gap, clock RUNNING]
bracketed min=1000 max=1001 median=1001   [gap, clock PAUSED across it -- the 2008 idiom]
```

**3/3 consecutive runs: `drift − baseline` = 201, 200, 200 ms against a 200 ms gap;
`bracketed − baseline` = 0 ms in all three.** The bracket removes the drift completely.

So the disposition is per site, and it is the opposite of a blanket ruling:

| site | verdict | port |
|---|---|---|
| `Planning.java:108-112` | **real**, and the mechanism is drift, not exclusion | bracket deleted **only after** the relative delay became `AgentClock.scheduleAt(requestTime + timeDiff, …)`, which reads no clock at all. Mutation M2 — keep the deletion, arm a relative delay — is killed |
| `RoadAgent.java:132-134` | protects nothing | deleted; `t` cancels algebraically and #28 already fixes one `comparisonTime` per heap operation |
| `RoadAgent.java:167-169` | protects nothing | deleted, same reason |

Two guard-rails from the same section, both of which a careless port would trip:
**"Delete the bracket" is not "delete the monitor"** — each site sits inside an ordinary Java
monitor that was never part of this analysis — and the `Planning` site **is not a concurrency
problem at all**: there is no second party, the thing racing the code is the clock.

### 3.5 Untyped positional `Serializable[]` payloads, and the `ENTER` slot asymmetry

Cybele has **no performatives, no conversation ids, no reply-to and no ontology**. The only routing
information in the whole application is a channel-name string; payloads are untyped positional
`java.io.Serializable[]` with **no schema anywhere** (`INVENTORY.md` §4).

The sharpest consequence is `ENTER`. One sender emits one shape, and the two receivers read
**disjoint index subsets** of it:

```java
Activity.sendAll(StaticRailwayObject.ENTER + object,
        new Serializable[]{ getName(), position, to });
```

| receiver | reads | as | ignores |
|---|---|---|---|
| `Station.enter` | `[0]`, `[2]` | the train, and where it is ultimately going | `position` |
| `RoadAgent.enter` | `[0]`, `[1]` | the train, and which neighbour it arrives from | `target` |

**Nothing in the baseline states this**; the two index sets are three files apart, and swapping them
misroutes silently — a station would send the train back the way it came, a track would compute the
wrong direction. It is also load-bearing for correctness in a way that is invisible from either
side: `position` is `null` on a train's first `ENTER`, which is safe **only** because the first
`ENTER` always goes to a station, and a station is the receiver that ignores that slot
(`message-ontology.md` §5.4).

The port sends one `EnterRequest` to both and each receiver names its own field
(`endStation()` == slot 2, `arrivingFrom()` == slot 1). Same three slots on the wire, so the golden
is unchanged; the confusion becomes a compile error rather than an index typo.
`EnterRequestAsymmetryTest` is six tests on exactly this.

`INVENTORY.md` §4 required something else — *"any typed-message migration must split CH-03 into two
message types"* — and the port **overruled it**, because a split would change the wire and therefore
the golden. Recorded here because `message-ontology.md` §12's corrections register never picked it
up.

A related trap for the LOC and for any future reader: the ontology table's payload column is *what
field 7 contains*, not *how many things were on the wire*. `STATION_INFO` shows two keys and the
baseline sends **one** slot (a `Station.Info` object the probe reads two fields out of); `ROAD_STATE`
likewise. Thirteen of fifteen are one key per slot.

### 3.6 String-name reflection dispatch — the hazard, and the one thing it bought

Cybele has **no `Agent` base class to extend**. `cybele.kernel.Handler` is a bare marker interface;
`Agent` and `Activity` are `final` static-only containers. An agent is any `Handler` instantiated
**by class name via reflection**, and handler methods are bound **by string method name**
(`Activity.openChannel(channel, "methodName", handler)`). **Nothing is checked at compile time.**
The misspellings `recieveStationInfo` / `recieveRoadState` / `recieveTrainState` are therefore
load-bearing string literals, not typos (DEF-20).

The port closes it structurally: dispatch is a `switch` over the `Channel` enum and the compiler
binds it. The misspellings are **kept** — they are the 2008 names — but nothing binds them by string
any more. This is the one place the port strictly improves a failure surface rather than preserving
it, and the improvement is free: `Channel` is an enum, so the compiler does the work
(`908ddc9`; `RailwayMainAgent.java`).

**What the string binding bought, and it is the reason there is one PR and not five.** All sixteen
classes in `src/main` referenced `cybele.*`, and there is no ordering of #30–#34 that keeps a
*runnable* application at every step, because the agents only work as a set. But the tree keeps
**compiling** at every step, precisely because Cybele spawns agents by class-name string and binds
handlers by method-name string — nothing resolves an agent's type at compile time. So the per-ticket
bar during 1-PORT was `./gradlew build` green, not the parity gate, which had nothing to run against
until #36 (#4, sequencing comment).

Note that every doc in this repo frames that mechanism as a *hazard* — "nothing checked at compile
time", "a rename fails silently at runtime" — and the ontology exists to end it. The
keeps-compiling property is an accident of the same absence, not a design affordance; it is recorded
because it is what made the port's ticket structure possible.

### 3.7 What mapped better than predicted

* **The messaging.** `CYBELLE_TO_JADE.md`'s closing sentence says the port is *"mechanical but not
  free — the control structures map cleanly, but object-rich Cybele messaging must be re-expressed
  as ACL content"*. **Both halves are backwards.** The messaging did not need re-expressing —
  `RailwayOntology` deliberately is *not* a `jade.content.onto.Ontology`; content stays a Java
  object under `setContentObject`, because an SL string form would be a redesign and a redesign is
  not measurable against a frozen golden. The control structures did **not** map cleanly: five of
  the table's six behaviour-class rows have **zero** instances in the finished port
  (`../jade/README.md` §4).
* **The GUI.** No planning document has a GUI mapping at all. It was the cheapest part of the port:
  two files reused verbatim behind a 19-line interface.
* **The domain logic.** `Phase1.md` 1-PORT asked for domain logic to be extracted into plain
  classes so 1-POST unit tests would be cheap and Phase 2 could reuse them. #28/#29 did that
  *before* the port; `domainPurity` enforces it; and the resulting 49-class, 1 534-line framework-free
  set is what made the L1 layer possible at 321 tests in 2.5 s.

---

## 4. Runtime observations

### 4.1 Message latency — the port is ~4× slower, and it is not a defect

20 runs of `opencybele-strict` per implementation, 240 samples each, **simulated** ms
(`parity-triage-jade.md` §4.1):

| | baseline | JADE port |
|---|---|---|
| `TRAVEL_END` → `TRAVEL_START` (the arrival cascade, ~8 hops) | min 0, p50 **8**, p90 24, mean **13.0** | min 12, p50 **49**, p90 76, mean **52.3** |
| `PLAN_TRAIN` → `START` (the whole election) | p50 **80** | p50 **192** |
| `ENTER` → `ENTER_REPLY` | p50 **0**, p90 8 | p50 **10**, p90 29 |
| `PATH_FIND` → `PATH_FIND_REPLY` | p50 0, p90 8, max 16 | p50 9, p90 19, max 39 |
| `VOTE_REQUEST` → `VOTE` | p50 8, p90 16, max 64 | p50 8, p90 36, max 167 |

At pace 8 the port's ~52 simulated ms cascade is **~6.5 ms of wall clock over eight hops — under
1 ms per ACL round trip with a serialized content object.** Three causes, none a port bug:
(1) the port marshals with `setContentObject` where the baseline crossed a channel **by reference**
under `Local;NoSerialization`; (2) **the observer is no longer free** — a second `openChannel` on an
already-handled Cybele channel cost the first subscriber nothing (SEM-03), JADE has no such thing,
so the probe is a second receiver on every message and *part of the latency the goldens penalise is
created by the measuring apparatus*; (3) clock granularity (§4.3). The hypothesis that this was an
(a)-class port bug was tested and **rejected on evidence** (T-10).

### 4.2 Startup

`parity-triage-jade.md` §4.8, tick of the first `STATION_INFO` — the instant the static objects
finish registering:

| | min … max over 20 runs |
|---|---|
| baseline, `capacity` | 504 … 560 (spread **56**) |
| port, `capacity`, corpus 1 | 103 … 1168 (spread **1065**) |
| port, `capacity`, corpus 2 | 85 … 500 (spread 415, one outlier) |

The port **finishes booting earlier** in simulated terms and is an order of magnitude **less
predictable** about it. That variance is not cosmetic: T-12 is five consecutive runs in which it
shifted the entire schedule against an absolute `sim.stop.maxClockMs = 27700` and changed which
events fell inside the bound — a *content* difference produced by startup jitter. Inter-departure
offsets were identical to the millisecond in every run (8 000, 5 000, 8 000), so the schedule was
**shifted, not recomputed**. It did not reproduce in a second corpus (0/20).

### 4.3 Clock granularity

`Cybele.getTime` advances in **8 ms** steps; the port's `SimClock` in **1 ms**. Field 2 is erased by
the normalizer so this produces no golden diff of its own, but it changes the gap statistics the
burst segmentation reads. Over 20 `strict` runs, 11 740 inter-line gaps each:

| | gaps of exactly 0 ms | gaps ≤ 8 ms | gaps > 220 ms |
|---|---|---|---|
| baseline | 10 159 | 11 166 | 424 |
| JADE port | 2 738 | 10 123 | 393 |

### 4.4 Wall time, measured for this ticket

Run **R1** (§8.6): `./gradlew characterizationIT -Pjade.dist=…`, one run per scenario, on a
24-core machine at load average 1.2.

| | wall |
|---|---|
| `opencybele-timers` | 3.54 s |
| `opencybele-strict` | 3.67 s |
| `opencybele-congestion` | 3.98 s |
| `opencybele-lifecycle` | 5.60 s |
| `opencybele-capacity` | 7.58 s |
| whole `characterizationIT` lane (84 tests, 5 child JVMs) | **36.6 s** |
| `./gradlew clean build` — 321 L1 tests | 21 s wall, **2.49 s** of test time |
| `./gradlew integrationTest` — 17 L2 tests, 8 forked JVMs | 27 s wall, **15.1 s** of test time |

Each scenario's wall time is dominated by its own simulated bound divided by its pace
(`strict`: 26 910 simulated ms at pace 8 ≈ 3.4 s), so the residue is child-JVM start plus platform
boot plus normalization — a few hundred milliseconds. Directly: five bare runs of `Main` bounded at
simulated 300 ms took **2.3, 3.4, 4.7, 3.0, 3.2 s** wall. *Five samples on a shared machine, not a
rate* — but it establishes that for a short run the JVM, the JADE platform, fifteen agent threads
and the shutdown dominate everything the simulation does.

Two lane facts worth carrying: the L2 lane's fixture is `maxParallelForks = 1` **and**
`forkEvery = 1` — one JVM per class — because `jade.core.Runtime` and `AID.platformID` are JVM
singletons and `TraceProbe`'s statics have no reset. And CI's `characterizationIT@jade` job measured
`build@this-ref` at 321 tests and `integrationTest@this-ref` at 17, both green on a hosted
`ubuntu-24.04` runner (run `32550380188`).

### 4.5 The lane has a CPU-throughput floor, and it is not a property of the port

Measured for #40 by varying only the CPU budget:

| CPU budget (developer machine, 24 cores) | verdicts vs `.github/parity/jade-status.expected` |
|---|---|
| 24 cores, quiet | green 3/3 |
| 4 dedicated cores (`taskset -c 0-3`), quiet | green 8/8 — the hosted-runner model |
| 4 cores at ~50 % steal | **red 3/3** |
| 24 cores at 100 % steal | **red 4/4** |

Below the floor the failure mode is **truncation**, not misordering: every scenario is bounded by an
absolute `sim.stop.maxClockMs`, so a starved machine reaches the bound having emitted fewer events
(`strict` 575 lines against 598; `congestion` 157 against 167). That looks exactly like a dropped
event and there is no fingerprint separating them, so it stays red — with a `HINT:` line naming the
truncation. **Under the same starvation the drift guard fails 3/3 too, with the frozen baseline no
longer matching its own goldens.** The floor belongs to the whole L3 lane, both arms, and predates
#40.

---

## 5. The four contract aspects that were hardest to keep equivalent

1. **Election seriality** (§3.1) — because the mechanism that enforced it (SEM-04) does not exist in
   JADE and the correct port had to reproduce a *scheduling property* with a message template.
2. **Statement order across a newly permeable window** (§3.2) — the one place where transliterating
   2008 would have *changed* behaviour rather than preserved it.
3. **Timer arming point** (§3.4) — a relative delay is 201 ms wrong; an absolute instant is right;
   and the difference is invisible until you measure which clock read `setTimer` uses.
4. **The projection.** Two independent subsystems ended at the same place: the clock's micro-freezes
   (`clock-abstraction.md` §2.6 — *"the reason it is safe is the projection, not the absence of an
   effect"*) and `STATION_INFO.occupied` (`message-ontology.md` §5.5 — the aliasing quirk is DEF-13,
   the JADE wire form makes it structurally impossible to reproduce, *"which is why the projection is
   mandatory rather than a convenience"*). **If this log has one headline about equivalence, it is
   that: the port is equivalent after projection, not before it.**

---

## 6. Test-writing experience

* **The baseline had zero tests** (CNT-12) and 33 `assert` statements, all disabled at runtime.
  Decision 1 of `defect-triage.md` §5 records goldens on with `-ea`, so an `AssertionError` aborts a
  recording; `JadeLauncher` replays with `-ea` for the same reason.
* **L1 is cheap because the domain was extracted first.** 321 tests in 2.49 s, framework-free,
  parallel. Behaviour-as-POJO worked: every `*InboxTest` drives `dispatch(acl)` with a hand-built
  message and captures `emit`.
* **Mutation testing caught what assertions did not.** Every ported ticket shipped a mutation set,
  and every survivor is either documented as by-design or withdrawn: #31 13 patches / 11 killed / 2
  by design; #34 12 / 11 / 1; #33 21 / 20 / 1 withdrawn; #32 27 / 26 / 1; #38 10 / 9 / 1 recorded.
* **The audit at #37 is the honest part.** `TraceProbe` — the instrument every L3 comparison reads
  through — **had no unit test at all**, and **three existing tests could not fail**: an assertion
  of `0` that an empty window also returns, a double-vs-integer case chosen where the two agree
  (its own comment admitted it), and a `StationQueue.size()` that `return 0;` satisfied. 286 → 321.
* **L2 proves what L1 and L3 cannot.** At L1 the inbox tests prove the handler and *assume* the
  wiring; at L3 a broken wiring is one diff several thousand lines in, or a hang on a deliberately
  untimed latch. #27's actual claim — direct AID unicast, no DF, fifteen per-constant topics, one
  queue per agent — is exercised only at L2.
* **One production seam was added for tests, and only one:** `TraceProbe.awaitReady(long)`,
  package-private. A `resetForTests()` was refused: it would let a production caller re-arm a
  barrier meant to be crossed once, which is wider than letting a caller choose its own bound.

---

## 7. The JADE distribution decision (#26), stated honestly

**Chosen: `net.sf.ingenias:jade:4.3`** — JADE 4.3.3, rev 6726 of **2014-12-09**, 2.6 MB, from Maven
Central.

* **Why:** Jason 3.3.0 (`io.github.jason-lang:jason-interpreter:3.3.0`) declares exactly this
  artifact as its only JADE dependency. Picking it now means no version conflict in Phase 2.
* **Maintenance status, honestly:** it is three minor versions behind the last official release and
  **eleven years old**. `com.tilab` is not on Maven Central. **The EnFlexIT fork *is*** —
  `de.enflexit.jade` 4.6.5, last updated 2025-03-31 — and #26's original comparison table said it
  was not. That claim was **wrong and retracted**: *"the rejection reason stated above was false,
  and anyone revisiting this decision should weigh the real trade-off — a 2014 JADE 4.3.3 that Phase
  2 aligns with, against a 2025-maintained 4.6.5 that Phase 2 would then have to exclude
  transitively."* The decision stands; the *stated reason* did not.
* **JDK 21 friction: none.** Class file major version 49 (Java 5). Clean boot on
  `openjdk 21.0.11`, **no `--add-opens`, no `--add-exports`, no reflection warnings**. The
  `--add-opens` speculation in #10's body is empirically false.
* **A second retraction, and it matters more than the first.** #26's caveat about
  `Thread.stop()`/`SecurityManager` was speculation carried forward without checking:
  disassembling all 1 815 classes finds **zero** call sites of `Thread.stop()`, `suspend()`,
  `resume()` and **zero** `SecurityManager` references. It *over*-stated the risk while leaving the
  real residual risks unnamed — **container shutdown ordering, JICP socket teardown, and
  `System.exit` in `jade.Boot`**, all three of which the port then actually hit (`a0464fb`'s
  `stop()` work). And the smoke test that supposedly proved messaging was **malformed**:
  `-container:false` parsed as a property named `container:false` that swallowed the agent
  specification, so **no agent was ever created**. Re-run correctly, agent creation, ACL self-send,
  `doDelete()` and `takeDown()` all succeed on 21.0.11 with no flags.
* **Consumption:** for most of 1-PRE, JADE was on test-side configurations only and the shipped dist
  held no JADE jar. **#30 changed that** and `build.gradle.kts` carries the correction in place —
  `implementation("net.sf.ingenias:jade:4.3")` — because from #30 onward `src/main` *is* a JADE
  application. The cost the old comment predicted (JADE landing in `build/install/opencybele/lib`)
  is real and is no longer a change to the thing under measurement: per #4's sequencing decision the
  application stops running under Cybele at #30, and the drift guard measures the frozen baseline
  dist in a separate worktree. **`message-ontology.md` §10 still argues the superseded position;**
  that is corrected as part of this ticket.
* **One residual oddity, recorded not fixed:** the two 2008 Cybele vendor jars are still declared on
  `implementation` and still land in the port's `installDist`, although `src/main` has zero
  `cybele.*` references. Dropping them is a 1-POST cleanup and was deliberately **not** taken by
  #40, because the child JVM's `-cp` is part of the command line every measurement in
  `parity-triage-jade.md` was taken under, and this lane's margins are 3–9 ms wide.

---

## 8. The Definition-of-Done verdict

[`Phase1.md`](Phase1.md) § "Exit criteria Phase 1 (Definition of Done)", four items. Verdict per
item, plainly. The same verdict is recorded in `Phase1.md` itself, under "The verdict".

### 8.1 DoD 1 — `characterizationIT` green on **both** implementations against identical goldens, flake-free (≥10 runs)

> ## **NOT MET.**

The `develop`/baseline arm is green: 20/20 on all five scenarios, machine idle; 74 tests, 0 failures;
`parityGate opencybele-strict` 20 consecutive runs, **one distinct trace**. The `jade` arm is not:

| scenario | baseline (idle, 20 runs) | JADE port |
|---|---|---|
| `opencybele-lifecycle` | 20/20 | **20/20** |
| `opencybele-timers` | 20/20 | **20/20** |
| `opencybele-capacity` | 20/20 | 6/20, and 15/20 in a second corpus, and 12/12 in a third (§9.1) |
| `opencybele-congestion` | 20/20 | 3/20 (7/12 in the third corpus) |
| `opencybele-strict` | 20/20 | **0/20, 0/5, 0/12 — 0/N in every corpus ever taken** |

**Why it is not met, precisely.** The residual is classified **(b) — normalizer/harness gap** on
evidence, and it is **not closable**:

* A golden is a **fixed point of the normalizer at every width.** `ScenarioRunner` normalizes the
  golden before comparing and `CanonicalTraceNormalizer` skips every ordering rule on an
  already-projected trace, so `DEFAULT_SEGMENT_GAP_TICKS = 220` **re-segments the run only.**
  Measured: `normalize(golden)` at w = 0, 8, 100, 220, 5000, 100000 is byte-identical to the file on
  disk, for all five. **The comparison therefore asks the port to reproduce the baseline's message
  latency, not its behaviour.**
* The two boundaries have **mutually exclusive** requirements: `strict` needs a width ≥ 227,
  `congestion` needs one < 214. A sweep of w = 0…2000 is 0/20 at every width and bottoms out at 46
  differing lines.
* The margin against the 220 ms width **predicts the pass rate in exact rank order, on both
  implementations**: lifecycle 118 ms → 20/20; timers (no gap in [120, 400]) → 20/20;
  **capacity 3 ms → 6/20**; congestion 9 ms → 3/20; strict 0 ms → 0/20.
* **The instrument does this to the reference implementation too.** Under concurrent load the
  baseline fails its own `strict` golden at the **same line, in the same direction, with an
  identical multiset** — **17/20 pass, i.e. 3 failures in 20** (§9.2 on why that must not be written
  as "3/20"). *"The port is being failed by a boundary the implementation that produced the golden
  cannot place reproducibly."*
* **Seven candidate repairs were implemented and measured before rejection**, none on taste: widen
  or narrow the width (0/20 at every w); per-scenario widths (`congestion` 16/20 at w = 100, `strict`
  still 0/20); demote to `causal` (0/20 — the reordered lines are `st*`/`tr*` subjects and land in
  the unattributed bucket, which `causal` compares in order); a content-derived epoch partition (not
  idempotent, so it could never match its own recording); a global sort of the body (pins a multiset
  and no order at all — the "lock that cannot fail"); an admitted-resolution band (the discrepancies
  are not symmetric about the threshold: 448 ms on one side is 214 ms on the other); per-adapter
  latency scaling (`strict` diff **worse** at the implied w = 500, 184 against 220's 47).
  An eighth option, re-recording under L7, was **not requested** and is argued away in §7 of the
  triage log.
* **Nothing was resolved as (c).** No golden re-recorded, no contract level demoted, no width
  changed, no check relaxed. `git diff origin/jade-develop -- parity-tests/golden` is **0 lines**.

**The strongest honest claim is this, and it is not "the goldens match":**

> **The JADE run emits the same event multiset as the 2008 application in all five scenarios, and
> every remaining difference is the placement of a burst boundary.**

**Two carve-outs must travel with that sentence.** Written without them — as `docs/ci.md` §10.2 and
`.github/parity/jade-status.expected` both did, the latter adding *"every run"* — it over-reaches;
both were corrected as part of this ticket (§9.3).

1. **T-07, the quantiser straddle.** `opencybele-strict`'s `VOTE.diff` for `(vl8, stH)` is
   3214…3254 on the baseline (`<T~3000>` every run) and 2809…3116 on the port (`<T~2000>` in 10 runs
   of 20), so it straddles the normalizer's own 1 000 ms `TIME_QUANTUM` bucket. That is **one line,
   in one scenario, on one payload field, and it is a declared cost of the quantiser** — not an
   event the port invented or dropped. At multiset level it means `strict` is identical in 7 runs of
   20; the other 13 differ by exactly two lines, which cancel. The CI judge encodes precisely this
   as the `QUANTUM` verdict class and grants it only when every surplus line carries a `diff=<T~`
   and the two sides cancel exactly.
2. **T-12, and it is the only genuine multiset difference this port ever produced.** In five
   *consecutive* runs of one 20-run `capacity` corpus, the run differed from its golden by **14 to
   17 lines**, and the single `stB` slot (capacity 1) went to `vl2` where the golden gives it to
   `vl1`. That is a behaviour difference on its face and it was chased as one. Cause: the port's
   first-`STATION_INFO` tick spread 103…1168 in that corpus against the baseline's 504…560, and
   `sim.stop.maxClockMs = 27700` is absolute — so a slow JVM start shifts the whole schedule against
   a fixed bound and changes which events fall inside it. **The schedule was shifted, not
   recomputed**: inter-departure offsets were identical to the millisecond in every run (8 000,
   5 000, 8 000). It **did not reproduce** — a second corpus of twenty was all 20 multiset-identical.
   Logged rather than dismissed, because a recurrence must not be re-investigated from scratch, and
   because `COVERAGE.md` §12.1's bound-margin rule is measured from *last train creation* and models
   nothing about how late a run starts. That is a gap in the scenario-authoring rule and is 2-PRE
   work.

**Flake-freedom.** ≥10 runs was met on `lifecycle` and `timers` (20/20, plus 12/12 in the third
corpus — **32/32 combined**). It is not met on the other three, and their pass rates are not merely
flaky but **load-dependent by 2–4× in both directions**, which is the diagnosis rather than the
noise (§9.1).

**Also still open, and it predates the port:** the goldens' **cross-occasion** reproducibility has
never been established. Every gate invocation is from one boot; `COVERAGE.md` §2.1.1 declines to
count two runs minutes apart as two occasions, and the honest claim there remains *"byte-reproducible
within a session"*.

### 8.2 DoD 2 — L1 + L2 suites green in CI on `jade`

> ## **MET.**

CI run **`32550380188`** on `phase1/1-port`, all four jobs green:

```
build@this-ref              OK: 321 test(s) (floor 321), 0 opt-in skipped
integrationTest@this-ref    OK:  17 test(s) (floor  17), 0 opt-in skipped
characterizationIT@jade     attempt 1 of 2, no retry, 0 warning annotations
                            strict=ORDER capacity=MATCH congestion=MATCH
                            lifecycle=MATCH timers=MATCH -- all inside status
characterizationIT@opencybele  OK: 84 test(s), 10 opt-in skipped, 0 failed
```

Reproduced locally for this ticket at `16193ad` — run R1, §8.4.

**One caveat that belongs with the "green", stated because #40 states it:** the
`characterizationIT@jade` lane **cannot honestly demand five green scenarios** and does not. It
asserts the recorded per-scenario **verdict class** — the axis the measurement showed to be stable —
rather than a pass rate that swings 6/20 → 15/20 → 12/12 with machine load. Three properties keep it
a check rather than a rubber stamp: `BEHAVIOUR` (a dropped or invented event) is permitted nowhere
**and cannot be permitted**, on any scenario, whatever the expectations file says; a change in
**either** direction is red, so a `strict` that suddenly passes fails the job; and `QUANTUM` is
granted only when every surplus line carries a `diff=<T~` and the two sides cancel exactly. Its cost
is stated in its own header: **it pins today's measurement.**

**One genuine gap survives**, and it is #75's, not #40's: the nightly drift guard on
`opencybele-baseline` lives on a workflow file that is not on the default branch, so its `cron` has
never fired. #40 shrank the hole — the drift guard now runs alongside the port guard on every push
and PR — leaving exactly one uncovered event: **a push straight to `opencybele-baseline` with no PR
anywhere.**

### 8.3 DoD 3 — coverage checklist shows every inventory row exercised by L3 and every behaviour covered by L1

> ## **MET for L1. PARTIALLY MET for L3, and the shortfall is documented rather than discovered.**

**L1: met.** `parity-tests/scenarios/COVERAGE.md` maps inventory row → scenario id with a written,
measurement-backed justification for every unmapped row. Every ported behaviour class has both a
happy-path and a boundary test; #37 closed the last hole (`TraceProbe`, 16 tests) and made three
tests that could not fail able to fail.

**L3: three named rows are exercised by no golden**, and all three were measured and written down
before this ticket rather than found by it:

* **`ROAD_STATE.state` / the `TRAVEL_LEFT`–`TRAVEL_RIGHT` label** (COVERAGE §10.11) is erased by the
  `road-state` projection, and because `acceptTrain` replies with the far end in *both* arms, an
  endpoint-swapped port produces **byte-identical goldens** while flipping 5 of the 7 roads. This is
  the one class of divergence the whole L3 suite is structurally blind to. Direction of travel *is*
  pinned, by `ENTER_REPLY.next`; the label is pinned only by `docs/probes/OrderLock.java`, a probe
  run by hand.
* **DEF-16** (negative travel delays firing immediately, ~2.26 % of draws on a 1 s road) is pinned by
  no golden: a clamping port passes the whole suite. COVERAGE §10.9 measured it and assigned it to
  L1. *`defect-triage.md` §3.1 told #39 the opposite; that guidance is corrected as part of this
  ticket (§9.4).*
* **DEF-07's ordering** is contractual only on a *queued* hop; within a burst the golden carries the
  sort, not the emission order.

**Not a coverage gap but the same class of limit:** DEF-02 (the lost `START`) is in no golden at
these seeds and bounds, and JADE **structurally cannot** reproduce it. A green `strict` would never
have proved the port reproduces it.

### 8.4 DoD 4 — comparison log written; harness untouched by JADE-specific hacks

> ## **MET.**

**First half:** this file, plus [`../jade/README.md`](../jade/README.md).

**Second half, and it is worth stating loudly because it was the easiest thing to lose:**
everything JADE-specific lives in `JadeLauncher`, and
`JadeLauncherIT.no_harness_file_outside_the_jade_package_names_JADE` asserts it as a test rather than
a convention. `classifyExit` is deliberately **not** overridden — two rows of the exit table are
documented as unreachable instead. #39 changed nothing in the harness but additive diagnostics
(`DiffAnatomy` computes; nothing it computes feeds back into `TraceComparator`). #40 added no
retry-until-green: the JADE lane's retry is true **only** when every out-of-status scenario came out
`BEHAVIOUR`, so a retry there can only ever *discover* a red.

### 8.5 The verdict in one line

**Phase 1 is not done on its own definition.** DoD 2 and DoD 4 are met, DoD 3 is met for L1 and
partially for L3 with the shortfall measured and assigned, and **DoD 1 is not met and cannot be met
by any work on the port** — the residual is an instrument limit, classified (b) on evidence, with
seven repairs measured and rejected and the same boundary failing the reference implementation under
load. What Phase 1 *did* establish is on the record and is not diminished by saying so:
**two of five scenarios of a 2008 Cybele application are reproduced byte for byte, twenty times out
of twenty, by an independent JADE implementation, against goldens frozen before the port began and
never touched since; and outside T-07's quantiser straddle and T-12's non-reproducing startup
transient — both accounted for above — every remaining difference between the two systems, on every
corpus, is the placement of a burst boundary and not an event the port invented or dropped.**

That is deliberately weaker than *"no run has ever emitted an event the golden did not record"*,
which is the form this claim wants to collapse into and which T-12 falsifies. The strong form is
true **going forward** rather than historically, and it is true because it is enforced: the CI
judge grants the `BEHAVIOUR` verdict to no scenario, whatever the expectations file says, so a
recurrence of T-12 is a red build rather than a footnote.


### 8.6 The runs this ticket took

Recorded so the verdict above is reproducible rather than quoted. Machine: 24 cores, load average
1.2, otherwise idle; worktree `phase1/1-port` at `16193ad`; **`parity-tests/golden/**` unmodified**.

| id | command | result |
|---|---|---|
| **R0** | `./gradlew clean build` | BUILD SUCCESSFUL, **321 tests, 0 failures, 0 skipped**; `domainPurity` and `rngProof` green |
| **R0** | `./gradlew integrationTest` | BUILD SUCCESSFUL, **17 tests, 0 failures, 0 skipped**, 8 classes |
| **R1** | `./gradlew installDist` then `./gradlew characterizationIT -Pjade.dist=$PWD/build/install/opencybele` | 84 tests. `JadeParityIT`: `lifecycle` PASS, `timers` PASS, `capacity` PASS, `congestion` FAIL, `strict` FAIL. Both failures carry their anatomy: `congestion` **SAME MULTISET** (an ordering difference, nearest gaps 145/61/55/51/33 ms, all merged); `strict` **one line each way**, `vl8\|<T>\|VOTE\|stH\|Main\|<P>\|voter=stH,train=vl8,diff=<T~3000>` in the golden against `<T~2000>` in the run — T-07 exactly, the two sides cancelling |
| **R2** | the CI judge run against R1's report: `parity-junit.sh jade-status build/test-results/characterizationIT .github/parity/jade-status.expected`, with the `characterizationIT@jade` job's `PARITY_REQUIRED_CLASS`/`PARITY_OPT_IN_CLASSES` | **exit 0.** `strict` QUANTUM, `capacity` MATCH, `congestion` ORDER, `lifecycle` MATCH, `timers` MATCH — every verdict inside its recorded status, **no scenario differed from its golden by multiset**, 11 opt-in tests skipped |

R1 is a **single run per scenario**, which is exactly what #39 §1.1 and PR #80 both warn cannot
establish a rate — `capacity` passing here is the 3 ms margin coming up heads, not evidence against
6/20. What R1 and R2 *do* establish, and it is what they are cited for, is that the verdict classes
recorded in `.github/parity/jade-status.expected` reproduce on a second machine state, and that the
`BEHAVIOUR` bar — no dropped event, no invented one — held again.

---

## 9. Numbers that disagree, and numbers that were wrong

This section exists because the project has already caught itself twice, and a reader of
`COMPARISON.md` must not re-import a retracted figure.

### 9.1 `opencybele-capacity`: 6/20, 15/20 and 12/12 — the disagreement is the finding

| figure | source | conditions |
|---|---|---|
| **17/20** | `parity-triage-jade.md` §1.1, §4.7 | first 200-run corpus, taken **under concurrent CPU load** (width sweeps on the same machine) |
| **6/20** | `parity-triage-jade.md` §1, §4.4 | second corpus, **machine idle** |
| **15/20** | same sections, and §4.7 | **idle again, twenty minutes later**, same build |
| **12/12** | `docs/ci.md` §10.2 and `.github/parity/jade-status.expected`, both citing **PR #80's measurement comments** | a 12-run idle corpus |
| **3/5** | `parity-triage-jade.md` §1 | five consecutive **full-suite** runs end to end through Gradle |

`congestion` disagrees the same way — **3/20** idle, **6/20** loaded, **7/12** in the third corpus,
**0/5** through Gradle — and PR #80 says the two measurements *"do not agree, and neither is a
mistake"*. **Do not average them and do not pick one.** What every corpus agrees on is the rank
order, which the margin table predicts exactly, and that `strict` is **0/N everywhere**. The correct
summary is the one `.github/parity/jade-status.expected` gives: *"quoting any one of them as 'the'
rate would itself be misleading"* — the stable axis is the **verdict class**, not the rate.

Note also that `lifecycle` and `timers` do not move at all: 20/20, 5/5, 12/12. **They are solid
because they have margin, not because they are immune.**

### 9.2 "the baseline fails its own `strict` golden 3/20" is a **failure count**

It means **17/20 pass, 3 failures**, under concurrent load; idle, the baseline is 20/20. Two lines
away sits the port's `congestion` **pass** rate of 3/20, and the two glyphs collide. Write it as
*"17/20 pass, 3 failures under load"*. A separate and distinct measurement — do not merge them —
is the CPU-starvation case, where the frozen baseline stops matching its own goldens **3/3 runs** by
**truncation**, not misordering.

### 9.3 Two claims corrected by this ticket

* **`parity-triage-jade.md` §10.6 said "three scenarios reproduce byte-for-byte".** §1 of the same
  file says **two** (`lifecycle`, `timers`), and §1.1 and §4.4 call `capacity`'s good corpus *"luck,
  not a property"*. Corrected to two.
* **`docs/ci.md` §10.2 attributed a 118 ms margin to `capacity`.** 118 ms is **`lifecycle`'s**;
  `capacity`'s is **3 ms**. The sentence claimed the margins "rank the rates exactly" and, with 118
  against 6/20, refuted itself. Corrected, along with the same confusion in
  `.github/parity/jade-status.expected`'s table cell (comment text only; no expectation row changed).

### 9.4 Guidance that was wrong and is fixed here

* **`defect-triage.md` §3.1** told #39 that *"a port that clamps DEF-16 to 0 produces a diff and
  THAT is the port bug"*. It does not: COVERAGE §10.9 measured a clamping port producing five
  byte-identical goldens. §3.1's own cell already carried the falsified prediction struck through;
  the guidance sentence is corrected now, as COVERAGE §10.9 asked.
* **`defect-triage.md` §3.3's DEF-24 row** says *"JADE ports have no Swing `TableModel` at all"*.
  The port has one — #35 ported the view. The conclusion survives; the reason does not, and
  `RailwayMainAgent`'s Javadoc already warns *"#39 should not quote the stated reason"*.
* **`CYBELLE_TO_JADE.md`'s four unaudited behaviour rows** (`OneShotBehaviour`, `WakerBehaviour`,
  `FSMBehaviour`, `ParallelBehaviour`) still read "Near 1:1" / "Moderate" / "Direct" and have **zero
  instances**. The `WakerBehaviour` row directly contradicts the correction two rows above it. All
  four now carry the measurement.
* **`Phase2.md`'s entry criterion** — *"Phase 1 DoD met (goldens green on `develop` and `jade`)"* —
  is unmeetable as written, since Phase 1 ends with three scenarios deliberately and permanently
  red. Restated against §8.5's verdict.
* **PR #80's own retraction stands and is repeated here** so it cannot be re-imported: the first
  gate run's *"four of five scenarios reproduced byte-for-byte"* was **from single runs and was
  wrong** — three of those four are flakes.
* **Mutation M11 was a tautology and was withdrawn, not re-run** (`725bc18`):
  `(p_a − t) − (p_b − t) == p_a − p_b` for every `t` in two's-complement `long`, so its survival was
  guaranteed a priori and carried zero information.

---

## 10. Handover to Phase 2

`Phase2.md` 2-PRE.1 currently carries none of this; the six items below are
`parity-triage-jade.md` §10's, restated with the corrections above applied, and they are written
into `Phase2.md` as part of this ticket.

1. **`opencybele-strict` and `opencybele-congestion` need their margins re-tuned before Phase 2**,
   now against *two* implementations' gap distributions rather than one. A scenario whose nearest
   gap-to-threshold margin is 4 ms (baseline) or 0 ms (port) is not a contract; it is a coin toss
   with a golden attached.
2. **`opencybele-capacity` sits on a 3 ms margin** and is the next scenario in this class to go red.
   It will look like a port bug when it does.
3. **The observer is not free on JADE and was free on Cybele.** Any Phase-2 scenario that depends on
   message timing is measuring the probe as much as the system. Jason will give a third answer again.
4. **`VOTE.diff` sits on a `TIME_QUANTUM` boundary in `strict`.** A scenario that means to pin the
   election's arithmetic should place the value where a 1 000 ms bucket has headroom.
5. **DEF-02 did not reproduce and structurally cannot on JADE.** If the migration is meant to
   demonstrate anything about the original's defects, it needs a scenario that observes them
   directly rather than a golden that happens to contain them.
6. **Two scenarios reproduce byte-for-byte across two independent agent kernels**, with the goldens
   untouched. That is the result worth carrying forward. *(§10.6 of the triage log said three; §9.3.)*

Three more that Phase 2 should not discover the hard way:

7. **The `Serializable[]` → ACL step was free; the `Serializable[]` → ground-terms step will not be.**
   `MIGRATION.md` §10/§12 treat "arbitrary Java-object messages" as a symmetric limitation of both
   targets. It is not: JADE let the port keep Java objects under `setContentObject`, and AgentSpeak
   content genuinely is logical terms. This is the one predicted limitation that did **not** bite in
   Phase 1 and will bite in Phase 2.
8. **`Phase2.md`'s "one tester plan per JADE behaviour" is the wrong unit.** The port has eleven
   `CyclicBehaviour` classes — an `Inbox`/`Drain` pair per agent, and half of those are drains with
   no behaviour of their own to test — against fifteen channels and four activities. The Phase-2
   unit is the **channel** (`CH-01`…`CH-15`) or the handler, not the behaviour.
9. **The cross-occasion reproducibility of the goldens is still open** (COVERAGE §2.1.1). Phase 2
   inherits it, and it is cheap to close: one `parityGate` run after a reboot, appended to §2.1.
