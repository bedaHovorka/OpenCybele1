# The JADE port — mapping notes

> Phase 1, sub-phase 1-POST.4 ([`docs/Phase1.md`](../docs/Phase1.md) L96–L98) ·
> issue [#41](https://github.com/bedaHovorka/OpenCybele1/issues/41).
> Companion: the Phase-1 comparison log,
> [`docs/comparison-log-phase1.md`](../docs/comparison-log-phase1.md), which carries the LOC,
> the effort notes, what did not map cleanly and the Definition-of-Done verdict.

This file answers two questions and nothing else:

1. **Which Cybele activity became which JADE behaviour**, per agent — including the places where
   [`docs/CYBELLE_TO_JADE.md`](../docs/CYBELLE_TO_JADE.md)'s mapping table did not hold, measured
   during the port rather than predicted before it.
2. **What is different about the two systems that no golden can see.** §6 is the important part
   of this document. Two of the five parity scenarios reproduce the 2008 application byte for
   byte; that is a statement about a projected trace and it is *not* a statement that the two
   systems are the same.

---

## 0. There is no `/jade` module, and that is deliberate

`Phase1.md`'s overview allowed the port to land as *"branch `jade` (or module `/jade`)"*. It landed
as neither: the JADE implementation **replaces** the Cybele one, file for file, in
`src/main/java/cz/vutbr/fit/ags/xhovor07/`, on branch `phase1/1-port`.

The reason is `git log --follow`. Every ported class is rewritten *in place*, so `Station.java`'s
history runs continuously from the 2008 file to the JADE agent, and a reviewer can `git log -p` one
path to see what a decision replaced. A parallel `/jade/**` tree would have made every ported class a
new file with no ancestry, which for a migration study is the wrong trade.

Where the code lives:

| source set | what is in it | code LOC at `16193ad` |
|---|---|---|
| `src/main` | the JADE agents, the GUI, and the 1-PRE harness instrumentation | 2 720 |
| `src/jade` | the `jadeOntology` artifact: `Templates`, `Messages`, `RailwayOntology`, `ClockTickerBehaviour` — the only JADE-aware code outside `src/main` | 153 |
| `src/domain` | framework-free domain classes (#28/#29); no JADE import, enforced by the `domainPurity` task | 1 534 |
| `src/characterizationIT` | the shared parity harness. `JadeLauncher` is the only file in it that names JADE | 5 200 |

(Counts are non-blank, non-comment lines; see the comparison log §1 for the method and for the
raw-line figures.)

---

## 1. The port at a glance

Five Cybele agent/activity families became five JADE agents plus two plain classes hosted on one of
them. Two activities were **deleted outright** and one base class with them.

| 2008 (Cybele) | after the port | note |
|---|---|---|
| `RailwayMainAgent` (`Handler`) | `jade.core.Agent`, `implements RailwayView` | the social-knowledge hub |
| `Station` (`Handler`) | `jade.core.Agent` | ×8 |
| `RoadAgent` (`Handler`) | `jade.core.Agent` | ×7 |
| `Train` (`Handler`) | `jade.core.Agent` | created and destroyed at run time |
| `TraceProbe` (1-PRE) | `jade.core.Agent` | the instrument, on fifteen topics |
| `Planning` (`Activity`) | plain class; installs behaviours on the **`Main` agent** | not a separate AID — §6.3 |
| `Generator` (`Activity`) | plain class; one clock deadline, no behaviour of its own | |
| `PathFinding` (`Activity`) | **deleted** | §3 |
| `VoteCollecting` (`Activity`) | **deleted** | §3 |
| `RailwayObject` (base class) | **deleted** | its only remaining job was `getName()` from a Cybele agent id |
| `StaticRailwayObject` (base class) | reduced to four channel-name constants | inlining beat extraction; see `18944e9` |

---

## 2. Cybele activity → JADE behaviour, per agent

Every JADE agent in this port has the same shape: **one `Inbox`, one `Drain`**, both
`CyclicBehaviour`, over disjoint templates whose union is everything. That shape is not decoration —
a JADE agent has one message queue, `receive(template)` consumes only what matches, and a message
matching no active template sits in the queue forever. `Templates.unexpected(party)` is the exact
complement of `Templates.inbound(party)`, so an unrouted railway message is reported by name instead
of presenting as a hang (`Templates.java`, class comment; `UnexpectedMessage.java`).

| agent | behaviour | kind | what it replaces |
|---|---|---|---|
| `RailwayMainAgent` | `Inbox` | `CyclicBehaviour` | the hub's four Cybele channel handlers (`STATION_INFO`, `ROAD_STATE`, `TRAIN_STATE`, `PATH_FIND`) |
| | `Drain` | `CyclicBehaviour` | nothing — new; the complement template |
| | `StartupWatchdog` | `TickerBehaviour` | nothing — new; names a child agent that never reached `setup()` |
| | *(hosts `Planning`'s `ClockTickerBehaviour`)* | | |
| `Planning` (on `Main`) | `Inbox` | `CyclicBehaviour` | `PLAN_TRAIN` handler + `VoteCollecting`'s `VOTE` handler, merged |
| | `Watchdog` | `TickerBehaviour` | nothing — new; reports an election that never closed (DEF-22) without closing it |
| | `ClockTickerBehaviour` | `TickerBehaviour` | drains `AgentClock`; replaces `Activity.setTimer(CLOCK_ID, …)` for the departure timer |
| `Generator` (on `Main`) | *none* | — | `Activity.setTimer` ×2 → `clock.scheduleIn` on `Planning`'s shared `AgentClock` |
| `Station` | `Inbox` | `CyclicBehaviour` | five channel handlers (`ENTER`, `LEAVE`, `VOTE_REQUEST`, `VOTE_RESULT`, `PATH_FIND_REPLY`) |
| | `Drain` | `CyclicBehaviour` | new |
| | `Watchdog` | `TickerBehaviour` | new; names a `PATH_FIND` continuation still parked (DEF-23) |
| `RoadAgent` | `Inbox` | `CyclicBehaviour` | five channel handlers (`ENTER`, `LEAVE`, `VOTE_REQUEST`, `VOTE_RESULT`, `TRAVEL_START`) |
| | `Drain` | `CyclicBehaviour` | new |
| | `ClockTickerBehaviour` | `TickerBehaviour` | drains `AgentClock`; replaces `Activity.setTimer(CLOCK_ID, delay2, this, "travelEnd")` |
| `Train` | `Inbox` | `CyclicBehaviour` | three channel handlers (`START`, `ENTER_REPLY`, `TRAVEL_END`) |
| | `Drain` | `CyclicBehaviour` | new |
| `TraceProbe` | `Observe` | `CyclicBehaviour` | the 1-PRE probe's fifteen Cybele channel subscriptions |
| | `Drain` | `CyclicBehaviour` | new |

**Counted at `16193ad`, in `src/main` + `src/jade`:** 11 `CyclicBehaviour` subclasses, 4
`TickerBehaviour` subclasses (three watchdogs and `ClockTickerBehaviour`), and **zero**
`OneShotBehaviour`, `WakerBehaviour`, `FSMBehaviour`, `ParallelBehaviour` or `SequentialBehaviour`.
`WakerBehaviour` and `FSMBehaviour` appear only in Javadoc that argues against them (8 and 4
mentions, 0 uses).

### 2.1 Deliberate non-uses worth recording

* **`Train` is not an `FSMBehaviour`**, although #32's own issue text suggested one. An FSM state
  draws from the same single queue through a *narrower* template, so a `TRAVEL_END` arriving in the
  "at a station" state would sit in the mailbox — and in the baseline all three of a train's
  channels are live at all times, because Cybele has one handler method per channel. That is a
  behaviour change dressed as a refactoring (`5186978`, `Train.java` class comment).
* **The election is not a `ContractNetInitiator` and sets no `:protocol`.** It is a *degenerate*
  contract net: the `accept-proposal` goes to every voter carrying a value no voter proposed, there
  is no `refuse` and no deadline. Adding a `reply-by` would change behaviour under exactly the load
  where DEF-22 shows (`docs/message-ontology.md` §4.1; `c321118`).
* **No readiness handshake at startup.** JADE's `createNewAgent(...).start()` returns immediately,
  but so was `Cybele.createAgent` asynchronous — #15 measured **six distinct constructor orders in
  six runs** on the baseline — and the normalizer's `startup-block` rule sorts the opening run by
  agent name anyway. A handshake would buy a total order the normalizer immediately sorts away. What
  the port adds instead is the rule's *precondition*: the generator is armed by the fifteenth
  opener, so the opening run stays unbroken (`908ddc9`).

---

## 3. Two activities were deleted outright

`PathFinding` (22 code LOC) and `VoteCollecting` (26 code LOC) existed for one reason each, and
their own class comments say so: **both were a borrowed thread wrapped around an unusable
`Activity.sendAllBlock`.**

* **`PathFinding`** existed so that *some* thread other than the station's own could take the
  `PATH_FIND_REPLY` and `notify()` a `Station` blocked in `Object.wait()`. In the port
  `Station.getPathDirection`'s `sendAll(PATH_FIND); wait();` becomes `resolveDirection(target,
  resume)` — synchronous in-line on a cache hit, so the warm path emits 2008's messages in 2008's
  order; on a miss it parks the continuation in a per-target map and returns. `pathFindReply`
  resumes every continuation parked on that target, and misses on one target **coalesce into a
  single request**, because the 2008 station could not have a second request in flight and a
  duplicate `PATH_FIND` would be a trace line the golden never recorded (`0b0e6f0`).
* **`VoteCollecting`** existed so that *some* thread other than `Planning`'s could count the votes
  while `Planning` sat in `CountDownLatch.await()`. In the port the latch becomes `VoteRound` — a
  framework-free tally of ballots keyed by voter plus an outstanding count decremented per
  arrival — and the collector disappears (`c321118`).

Deleting them cost nothing wanted and two things not: `VoteCollecting` was the only second party to
`votes`/`trainCountDowns`, and it carried DEF-09. Deleting `PathFinding` removed the second party
the station's `synchronized` was guarding against, which is why no monitor survives in `Station`.

**What did *not* get deleted with them is the property they enforced.** See §3.1 of the comparison
log: `latch.await()` did not stall one train, it stalled the *channel*, and that seriality is part
of the contract.

---

## 4. Where the planning docs' mapping table was wrong

`docs/CYBELLE_TO_JADE.md` L117–L132 is the table the port was planned against. Measured against the
finished port, **one of its six behaviour-class rows has an instance.** Two rows were corrected in
the doc during the port (#27, #33); the rest are corrected here and in the doc's own Notes column as
of this ticket.

### 4.1 "Directory / community → DF + AMS" — the DF half does not apply at all (#27)

The **DF (yellow-pages) half is wrong for this application**: nothing here advertises a capability
and nothing searches for one. `INVENTORY.md` §4 shows all fifteen channels (`CH-01`…`CH-15`) with
**exactly one subscriber each**, addressed by literal name. `grep -rn "DFService\|DFAgentDescription"
src/` returns **0 hits**.

The **AMS (white-pages) half does apply**, and is what the port uses: `new AID(localName,
AID.ISLOCALNAME)` for 15 of 15 channels, resolved by the platform's white pages. Note the honest
scope of that: no *application* code calls `AMSService` either (`grep` → 0 hits); the port addresses
by a literal name and the AMS resolves it. "AMS" here means the platform's addressing, not an API
this code calls.

Two channels are many→one (`VOTE` from all fifteen static objects; `PATH_FIND` from all eight
stations); the other thirteen are strictly 1→1. **Zero fan-out anywhere.** #27's own first framing —
"messages reach every registered agent and the sender knows none of them" — described the `sendAll`
*API*, not what this codebase does with it, and was retracted.

**JADE topics survive, but for observability only.** `TraceProbe` needs to see traffic it is not
the named receiver of, so `TraceTopics` adds a per-channel-constant topic AID as a *second* receiver
on every application message: fifteen topics fixed at boot. The rejected per-instance alternative
was costed at 93 topics at boot plus four more per train forever. Topics are never used for
delivery.

### 4.2 "Periodic / timer activity → `TickerBehaviour`, near 1:1" — there are no repeating timers (#33)

The table's headline "near 1:1" mapping **has no instance in this codebase.** `INVENTORY.md` CNT-09
counts **four** timer sites and CNT-10 counts **zero** repeating ones:

| site | what it is |
|---|---|
| `Generator.java:50` | the bootstrap first fire |
| `Generator.java:65` | the re-arm — and it **redraws an exponential interval on every fire** (`round(-LAMBDA·ln U)`), so it is a self-rescheduling one-shot, not a period |
| `Planning.java:111` | one departure deadline per train |
| `RoadAgent.java:102` | one `travelEnd` per traversal |

`WakerBehaviour` — which #33's own issue text proposed as the replacement — was **also rejected**,
and by measurement rather than taste: a `WakerBehaviour` deadline is **wall clock and is fixed when
the behaviour is added**, so it would ignore `Gui.java:98`'s runtime pace change and keep arriving
across a `Cybele.pauseClock`. All four sites therefore go on `AgentClock`, whose deadlines are
absolute **simulated** instants, drained by `ClockTickerBehaviour`.

So `TickerBehaviour` is used four times in the whole port and **not once as a ported activity**:
three watchdogs (`RailwayMainAgent.StartupWatchdog`, `Station.Watchdog`, `Planning.Watchdog`, all
new diagnostics) and `ClockTickerBehaviour`, the clock drain. Two code sites construct one —
`Planning` on the `Main` agent, and each `RoadAgent`.

### 4.3 The four rows nobody audited

| row | table says | measured in the finished port |
|---|---|---|
| `One-shot activity → OneShotBehaviour` | "Near 1:1" | **0 instances**; the class is never imported |
| `Delayed / wake-after → WakerBehaviour` | "Near 1:1" | **0 instances**; 8 Javadoc mentions, all arguing against it (§4.2) |
| `Activity-transition → FSMBehaviour / SequentialBehaviour` | "Moderate; re-express transitions" | **0 instances**; explicitly refused for `Train` (§2.1) |
| `Concurrent activities → ParallelBehaviour` | "Direct" | **0 instances**; the one concurrent construct became a tally (§3) |

The row that held is `Message-handler activity → CyclicBehaviour + MessageTemplate`, "Direct; you
rewrite the dispatch loop" — 11 instances. What it understates is that the port needed a **second**
cyclic behaviour per agent (`Drain`) with no Cybele counterpart, for the reason in §2.

### 4.4 Two things the table has no row for at all

* **The clock.** Replacing Cybele's global, pace-adjustable simulated clock (CNT-17: 13 API sites)
  with `SimClock`/`PacedClock`/`AgentClock`/`VirtualClock` + `ClockTickerBehaviour` is the port's
  largest new subsystem, and the table mentions it only inside the timer row's correction.
* **The GUI.** Nothing maps `java.util.Observable`/`Observer` → Swing. It turned out to be the
  cheapest part of the port (§5).

### 4.5 The row that held exactly

`Cybele message → ACLMessage + performative` predicted, per channel, 4×`request`, 7×`inform` and one
each of `cfp`, `propose`, `accept-proposal`, `query-ref`. The shipped `Channel` enum is exactly that
tally. The predicted "payloads become named immutable records" also held: 21 record types in
`src/domain/java/…/domain/msg/`.

---

## 5. Scope: the GUI was **not** dropped

#41's issue text and #53's checklist both anticipate *"a dropped GUI"*. **There is none, and the LOC
in the comparison log must not be annotated for one.** #35 decided *"Port the Swing view, off by
default. Not headless-only."*

`Gui.java` and `RailwayCanvas.java` are reused **verbatim** behind a new three-method `RailwayView`
interface (19 code LOC), which is the point: if the 2008 view runs unchanged against the JADE hub,
that is direct evidence the GUI coupling really was three files wide. The window is suppressed by
`sim.headless=true` in every harness and CI run (and `Profile.GUI` is off for JADE's own admin GUI
as well); `./gradlew run` keeps it on.

Two GUI-visible differences that are **out of contract** (no golden runs with a GUI):

* Under Cybele's `Local;NoSerialization`, payloads crossed channels **by reference**, so
  `Station.Info` was a live alias the canvas read while `Station.enter`/`leave` mutated it in place.
  JADE's `setContentObject` deep-copies, so the hub holds snapshots and **the canvas shows slightly
  staler values** (#4).
* `java.util.Observable` (deprecated since Java 9, three sites) notified its observers **backwards**;
  `RailwayView.Listener` notifies in registration order (`908ddc9`).

---

## 6. Known non-observable differences — **out of contract**

Two of five scenarios reproduce the baseline byte for byte, and every remaining difference measured
across 400 captured runs is the placement of a burst boundary — with two accounted-for exceptions,
T-07 and T-12, both in `docs/comparison-log-phase1.md` §8.1. **None of that says the two systems are
the same.** The list below is what a golden cannot see, and it is
here so that "the goldens match" is not read as "the systems are identical".

### 6.1 Threading model

| | Cybele baseline | JADE port |
|---|---|---|
| unit of execution | an *activity*, dispatched **serially** from a kernel thread pool (SEM-04) | an *agent*, one Java thread each, `Agent$ActiveLifeCycle.execute()` running exactly one behaviour action per call |
| blocking a handler | starves that activity's **other channels and its timers** — this is what made `wait()`/`await()` usable at all | would starve that agent; the port blocks nowhere and no `synchronized` survives in `Station`, `RoadAgent`, `Train` or `Planning` |
| monitors | `synchronized` in four classes | one survives, in `RailwayMainAgent`, and only because #35 kept the Swing EDT reading the published maps and the `TableModel` |
| agents at boot | 1 hub + 8 stations + 7 roads (+ `TraceProbe` when tracing), dispatched from a shared kernel thread pool | the same 17, **plus a Java thread each**, plus JADE's own AMS and DF agents |

`Object.wait()` **releases** the monitor — which is precisely why `PathFinding.pathFindReply`'s
`synchronized (station)` could acquire it. The 2008 stall was therefore never monitor retention; it
was SEM-04 serial dispatch. #30's own issue body says the monitor was held; that is wrong, and
`INVENTORY.md` DEF-01 was corrected in `a7b284e` to say so. Full account: comparison log §3.2.

### 6.2 Internal scheduling

* The baseline had **one kernel timer service**; the port has **one `ClockTickerBehaviour` per
  arming agent**, each with its own phase fixed by when that `setup()` ran. Timer error is therefore
  **one-sided** (never early), **correlated within** an agent and **uncorrelated between** agents —
  three properties Cybele did not have (`725bc18`).
* Clock granularity differs: `Cybele.getTime` advances in **8 ms** steps, `SimClock` in **1 ms**
  steps. Field 2 is erased by the normalizer so this produces no golden diff of its own, but it
  changes the gap statistics the burst segmentation reads (`parity-triage-jade.md` §4.6).
* The **observer is no longer free.** A second `openChannel` on an already-handled Cybele channel
  cost the first subscriber nothing (SEM-03); JADE has no such thing, so the probe is a second
  receiver on every message. Part of the port's measured latency is created by the measuring
  apparatus (`parity-triage-jade.md` §4.1).

### 6.3 Naming, identity and the wire

* **Conversation ids.** The baseline has none — Cybele has no performatives, no conversation ids, no
  reply-to and no ontology. The port sets `:conversation-id` to the **trace subject** (field 1) and
  `:ontology` to the channel id. Both are inventions of the port; the goldens erase field 6 and
  never see either (`docs/message-ontology.md` §9).
* **Performatives.** Same: all fifteen are decisions this port invented, not translations. The
  normalizer erases field 6 before comparing, which is exactly what that field was reserved for.
* **`Planning` has no AID of its own.** It is a set of behaviours on the `Main` agent, because
  giving it a separate AID would change the sender/receiver fields of five channel families
  (`PLAN_TRAIN` fields 4 and 5; `VOTE_REQUEST`/`VOTE_RESULT`/`START` field 4; `VOTE` field 5). That
  is a *contract* difference avoided, not an out-of-contract one — recorded here because the
  identity map is otherwise 1:1 and this is the exception.
* **Payload identity.** Cybele passed untyped positional `Serializable[]` **by reference**; the port
  ships immutable records **deep-copied** by `setContentObject`. §5 above for the visible half.

### 6.4 Container topology and the socket

* The port runs **one JADE main container** in one JVM. There is no Cybele analogue: the baseline's
  kernel was configured `Local;NoSerialization` with no external comm.
* **The JICP listener binds a TCP socket where Cybele bound none.** `Profile.MTPS` is set empty,
  which removes the *external* message transport — it does **not** make the platform hermetic.
  `Profile.MAIN_PORT` is `0` so the port is ephemeral. Consequence: **a locked-down machine fails
  this application at boot.** That is a difference in the environment a run needs, not in the trace
  it produces (`Main.java`, class comment).
* JADE's AMS writes `APDescription.txt` into `getProperty("file-dir", "")`, i.e. the process working
  directory, unless told otherwise. Defaulted to `java.io.tmpdir` and overridable with
  `-Djade.file.dir`; the parity adapter points it at the run's scratch directory.
* The platform starts an **AMS and a DF agent** whose traffic the drains see. Four AMS delivery
  `FAILURE`s reached the trace on `opencybele-capacity` before #36 split the drains; platform
  housekeeping is now a `!!! ` diagnostic and a railway message with no handler is still a port bug.

### 6.5 The `TRAVEL_LEFT` / `TRAVEL_RIGHT` label is pinned by nothing

This is the sharpest case of "out of contract", because it is a difference the whole L3 suite is
**structurally blind to**, and it is the one a reimplementation is most likely to introduce.

`RoadAgent.acceptTrain` decides direction with `position.equals(leftStation)` and **replies with the
far end in both arms**:

```java
if (position.equals(leftStation)) { state = TRAVEL_RIGHT; sendEnterReply(train, rightStation); }
else { assert position.equals(rightStation); state = TRAVEL_LEFT;  sendEnterReply(train, leftStation); }
```

A port that assigns the two endpoints the other way round takes the *other* branch, sets the *other*
`State` constant, and sends **the same `next`**. Only `state` differs — and the normalizer's
`road-state` rule **erases** `state` outright (`grep -c "TRAVEL_LEFT\|TRAVEL_RIGHT"
parity-tests/golden/*.txt` → 0 in all six files). A port that sorts its endpoint pairs, which is the
most natural thing a reimplementation does, flips 5 of the 7 roads on the default topology and
produces byte-identical goldens.

*This port did not swap them* — `RailwayMainAgent` reproduces `net.allNodesWithEdge(road)`'s
declaration-order walk verbatim, comment and all — but nothing in the suite would have caught it if
it had. **Direction of travel is pinned** independently by `ENTER_REPLY.next`, which no rule touches
(36 lines in `opencybele-strict`, 13 in `-congestion`, 9 in `-capacity`, 15 in `-timers`, 6 in
`-lifecycle`), and since #72 is checked per hop per train by `OpenCybeleLifecycleIT`. It is the
**label** that is unpinnable, and the only thing pinning it today is `docs/probes/OrderLock.java`, a
probe run by hand. Full write-up: `parity-tests/scenarios/COVERAGE.md` §10.11.

### 6.6 Failure *shape*, where the failure itself is preserved

Several 2008 defects are deliberately preserved, but the way they surface differs and #39 was told
twice not to be told otherwise:

| | Cybele | JADE |
|---|---|---|
| an unguarded NPE in a handler (DEF-06) | escapes the handler; agent alive, heap corrupt | reaches `Agent.run()`'s `catch (Throwable)`: prints to stderr, **kills the whole agent**, and `clean(false)` writes two lines to **stdout**, which is the trace stream |
| a failed `assert` (goldens record and replay with `-ea`) | kills one handler | kills the whole agent — for `pathFind`'s `assert direction != null`, that is the whole `Main` agent |
| DEF-09's duplicate-vote deref | killed one handler | kills the whole `Main` agent |
| DEF-02, the lost `START` | a channel race drops the send | **structurally impossible**: an AID-addressed `ACLMessage` queues whether or not a behaviour is waiting. Recorded, not simulated |
| DEF-23's starvation half | a blocked handler starves the station's other channels | **cannot be reproduced**: it is a property of SEM-04 |

---

## 7. Where to look next

| question | file |
|---|---|
| LOC, effort, what did not map cleanly, the DoD verdict | [`docs/comparison-log-phase1.md`](../docs/comparison-log-phase1.md) |
| every L3 diff, its class and its evidence | [`docs/parity-triage-jade.md`](../docs/parity-triage-jade.md) |
| the fifteen channels, performatives, payload records, the `ENTER` asymmetry | [`docs/message-ontology.md`](../docs/message-ontology.md) |
| the clock, pace, pause and the four timer sites | [`docs/clock-abstraction.md`](../docs/clock-abstraction.md) |
| which defect is preserved, dropped or unreachable | [`docs/defect-triage.md`](../docs/defect-triage.md) |
| what each scenario covers and what it does not | [`parity-tests/scenarios/COVERAGE.md`](../parity-tests/scenarios/COVERAGE.md) |
| the CI lanes and how the JADE L3 lane is judged | [`docs/ci.md`](../docs/ci.md), `.github/parity/jade-status.expected` |
