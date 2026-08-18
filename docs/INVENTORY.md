# INVENTORY.md — Stage-0 inventory of OpenCybele1

Status: **authoritative**. This is the `agent → activities → events handled → state → messages`
table demanded by [`MIGRATION.md` §5 Step 0](MIGRATION.md#5-step-by-step-migration-process) and by
[`Phase1.md` L19](Phase1.md). It supersedes `MIGRATION.md` §4 (which was written without repo access
and was explicitly labelled ASSUMED) and the illustrative "before" snippets in `MIGRATION.md` §7.

Every row carries a stable id (`AG-`, `ACT-`, `CH-`, `EVT-`, `TMR-`, `ST-`, `CFG-`, `NDT-`,
`OUT-`, `GUI-`, `SEM-`, `DEF-`). Later issues and `COVERAGE.md` cite these ids; they do not
re-derive the facts. Every claim carries a `file:line` reference. Source comments are partly in
Czech; this document is in English.

Baseline: branch `jade-develop`, commit `9c24945`, tree `src/main/java/cz/vutbr/fit/ags/xhovor07/`.
All paths below are relative to that directory unless a `util/` prefix is shown.
All shell commands below were run from the repository root.

---

## 1. Counts — commands and outputs

Reproduced on 2026-08-18. Each count is recorded with the exact command so no later issue
re-derives it.

| id | Fact | Command | Output |
|---|---|---|---|
| C-01 | 19 Java files, 2 415 lines | `find src -name '*.java' \| wc -l` / `find src -name '*.java' \| xargs wc -l \| tail -1` | `19` / `2415 total` |
| C-02 | **15** `Activity.openChannel` call sites | `grep -r 'openChannel' src/ \| wc -l` | `15` |
| C-03 | **15** `Activity.sendAll` call sites | `grep -r 'sendAll' src/ \| wc -l` | `16` — the 16th is the Javadoc `{@link Activity#sendAllBlock}` at `PathFinding.java:20`; 16 − 1 = **15** |
| C-04 | **15** channel constants | see §4; cross-checks against C-02 and C-03 | 15 = 15 = 15 |
| C-05 | 17 `public static final String`, 18 `static final String` | `grep -rn 'static final String' src/ \| wc -l` | `18` — 17 public + the package-private `CHANNEL_TRAIN_STATE` (`RailwayMainAgent.java:67`). Neither number is the channel count: 3 of the publics (`CLOCK_ID`, `MAIN_AGENT_NAME`, `KILLED`) are **not** channels. **The channel count is 15.** |
| C-06 | Subscriber count per channel **name** = exactly 1 for all 15 | every constant appears in exactly one `openChannel` site (C-02) and channel names are `constant + uniqueOwnerName` | 1 subscriber each — see §4 |
| C-07 | **33** `assert` statements | `grep -rn 'assert ' src/ \| wc -l` | `34` — the 34th is the Javadoc line `util/Util.java:49` (`* assert and cast routine`); 34 − 1 = **33** |
| C-08 | asserts are **disabled** at runtime | `grep -n 'applicationDefaultJvmArgs' build.gradle.kts` | `applicationDefaultJvmArgs = listOf("--patch-module", "java.base=cybelle")` — no `-ea`, so all 33 are no-ops. Input to #14 |
| C-09 | **4** timer sites | `grep -rn 'setTimer' src/` | `Generator.java:50`, `Generator.java:65`, `Planning.java:111`, `RoadAgent.java:102` |
| C-10 | **0** repeating timers | `grep -rn 'setTimer(' src/ \| grep -E 'true\|false' \| wc -l` | `0` — the `setTimer(..., boolean repeating, ...)` overload is never used. `TickerBehaviour` is **not** the default JADE mapping. Input to #33 |
| C-11 | **0** channel/timer teardown | `grep -rn 'closeChannel\|clearTimer\|Cybele.terminate' src/` | one hit only: `Gui.java:69`, and it is **commented out**. Nothing is ever un-subscribed or cancelled |
| C-12 | **0** test code | `ls src/test` / `find . -name '*Test*.java'` | `No such file or directory` / (empty) |
| C-13 | **2** application `println` sites | `grep -rn 'System.out\|System.err\|printStackTrace' src/` | `Planning.java:125`, `Train.java:70`, plus `util/Util.java:69` `printStackTrace` — see OUT-03, unreachable |
| C-14 | **1** `Agent.die()` site | `grep -rn 'Agent.die' src/` | `Train.java:92` |
| C-15 | **3** files touch Swing/AWT/Observable | `grep -rln 'javax\.swing\|java\.awt\|java\.util\.Observ' src/` | `Gui.java`, `RailwayCanvas.java`, `RailwayMainAgent.java` |
| C-16 | **1** unseeded `Random`, static, JVM-wide | `grep -rn 'Random\|nextInt\|nextDouble\|nextGaussian' src/` | decl `Generator.java:42`; consumed `Generator.java:60`, `Generator.java:69`, `RoadAgent.java:101` |
| C-17 | **8** `Cybele` clock API sites | `grep -rn 'createClock\|pauseClock\|resumeClock\|setPace\|getTime(' src/` | `RailwayMainAgent.java:111,112`, `Planning.java:78,108,109,112`, `RoadAgent.java:132,134,167,169,210`, `Gui.java:98`, `RailwayCanvas.java:92` |

### Cybele's programming model (why "no compile-time checking anywhere")

`cybele.kernel.Handler` is a bare marker interface (`public interface Handler extends
java.io.Serializable {}` — verified by `javap -cp cybele-api-1.0.jar cybele.kernel.Handler`).
`Agent` and `Activity` are `final` container classes with only static entry points; there is no
base class to extend. An "agent" is any `Handler` instantiated **by class name via reflection**
(`Cybele.createAgent(String name, String className, Serializable[] args)`), and handler methods are
bound **by string method name** (`Activity.openChannel(channel, "methodName", handler)`).
Consequently the misspellings `recieveStationInfo` / `recieveRoadState` / `recieveTrainState`
(`RailwayMainAgent.java:155,166,178`) are load-bearing string literals, not typos that can be
fixed in isolation.

---

## 2. Agents (AG)

| id | Class | File | Instances | Created by | Dies? | Role |
|---|---|---|---|---|---|---|
| AG-01 | `RailwayMainAgent` | `RailwayMainAgent.java:41` | 1 (`"Main"`) | `Main.java:25` | never | Social-knowledge hub: topology, capacities, delays, global clock, GUI, path-finding oracle, GUI state aggregation |
| AG-02 | `Station` | `Station.java:29` | 8 — `stA`…`stH` | `RailwayMainAgent.java:120` (loop over `net.nodeSet()`, `:116`) | never | Capacity-limited queueing node with a timetable |
| AG-03 | `RoadAgent` | `RoadAgent.java:29` | 7 — `tr1`…`tr7` | `RailwayMainAgent.java:130` (loop over `net.values()`, `:123`) | never | Single-track segment, one direction at a time |
| AG-04 | `Train` | `Train.java:23` | dynamic, unbounded (`vl0`, `vl1`, …) | `Generator.java:61` | **yes**, `Agent.die()` at `Train.java:92` | Mobile agent walking station→road→station |

Class hierarchy: `RailwayObject` (`RailwayObject.java:20`, `implements Handler`) ← `StaticRailwayObject`
(`StaticRailwayObject.java:23`) ← {`Station`, `RoadAgent`}; `Train` extends `RailwayObject` directly.
`RailwayMainAgent` is outside this hierarchy — it `extends java.util.Observable implements Handler`
(`RailwayMainAgent.java:41`).

`RailwayObject.getName()` (`RailwayObject.java:27-31`) derives an agent's own name from the
**thread-context static** `Agent.getAgentId()`, taking the substring after the last `'.'`.
It is therefore only correct when called on a thread belonging to that agent — see DEF-12.

---

## 3. Activities (ACT)

| id | Class | File | Created at | Owner agent | Instances | Why it exists |
|---|---|---|---|---|---|---|
| ACT-01 | `Planning` | `Planning.java:36` | `RailwayMainAgent.java:133` | AG-01 | 1 | Runs the distributed voting/election protocol per new train |
| ACT-02 | `Generator` | `Generator.java:29` | `RailwayMainAgent.java:134` | AG-01 | 1 | Poisson arrival process creating `Train` agents |
| ACT-03 | `VoteCollecting` | `VoteCollecting.java:25` | `Planning.java:59` (child of ACT-01) | AG-01 | 1 | **Exists only to borrow a second thread** so `Planning.planTrain`'s `latch.await()` (`Planning.java:88`) does not deadlock its own event thread |
| ACT-04 | `PathFinding` | `PathFinding.java:24` | `Station.java:62` | AG-02, one per station | 8 | **Exists only to borrow a second thread** so `Station.getPathDirection`'s `wait()` (`Station.java:105`) does not deadlock its own event thread. `PathFinding.java:20` states this: *"Solution of problem with `Activity#sendAllBlock`"* |

Total explicit activities at steady state: 1 + 1 + 1 + 8 = **11**, plus each agent's implicit main
activity. ACT-03 and ACT-04 are pure workarounds for the fact (SEM-04) that a Cybele activity
dispatches its own events strictly serially: any blocking handler starves every other channel and
timer of that activity. Both disappear in a framework with non-blocking continuations.

---

## 4. Channels (CH) — all 15

Format of a channel **name**: for CH-01…CH-12 the name is `constant + targetAgentName` and the
receiver parses the owner back out with `lastIndexOf('.')` (`RailwayMainAgent.java:192-196`).
CH-13, CH-14, CH-15 are **bare global** names with no suffix appended. Payloads are untyped
positional `java.io.Serializable[]`. There are **no performatives, no conversation ids, no
reply-to, no ontology** — the only routing information is the channel name string.

| id | Constant | Literal | Declared | Opened by (subscriber) | Handler method | Sender site(s) | Payload `Serializable[]` | Runtime names | Subs per name |
|---|---|---|---|---|---|---|---|---|---|
| CH-01 | `VOTE_REQUEST` | `VOTE_REQUEST.` | `StaticRailwayObject.java:27` | `StaticRailwayObject.java:47` (ctor of every Station+Road) | `voteRequest` `:58`, reads `[0],[1]` at `:60-61` | `Planning.java:81` | `{String train, Long expectedTime}` | 15 | 1 |
| CH-02 | `VOTE_RESULT` | `VOTE_RESULT.` | `StaticRailwayObject.java:31` | `StaticRailwayObject.java:48` | `voteResult` `:71`, reads `[0],[1]` at `:73-74` | `Planning.java:101` | `{String train, Long plannedTime}` | 15 | 1 |
| CH-03 | `ENTER` | `ENTER.` | `StaticRailwayObject.java:37` | `StaticRailwayObject.java:49` | abstract `:109`; `Station.enter` `:87` reads **`[0],[2]`**; `RoadAgent.enter` `:118` reads **`[0],[1]`** | `Train.java:117` (`requestEnterToObject`, called `:74`, `:89`, `:106`) | `{String train, String currentPosition, String finalTarget}` | 15 | 1 |
| CH-04 | `LEAVE` | `LEAVE.` | `StaticRailwayObject.java:41` | `StaticRailwayObject.java:50` | abstract `:104`; `Station.leave` `:115`; `RoadAgent.leave` `:150`; both read `[0]` | `Train.java:112` (`leaveObject`, called `:82` and `:124`) | `{String train}` | 15 | 1 |
| CH-05 | `START` | `START.` | `Train.java:32` | `Train.java:54` | `start` `:69`, reads `[0]` | `Planning.java:126` | `{String station}` | 1 per Train | 1 |
| CH-06 | `ENTER_REPLY` | `ENTER_REPLY.` | `Train.java:36` | `Train.java:55` | `entered` `:81`, reads `[0],[1]` | `StaticRailwayObject.java:84` (`sendEnterReply`, called `Station.java:94`, `Station.java:122`, `RoadAgent.java:141`, `RoadAgent.java:145`) | `{String objectName, String nextPosition_or_null}` | 1 per Train | 1 |
| CH-07 | `TRAVEL_END` | `TRAVEL_END.` | `Train.java:40` | `Train.java:56` | `travelEnd` `:104` — **payload ignored** | `RoadAgent.java:115` | `{String road}` | 1 per Train | 1 |
| CH-08 | `TRAVEL_START` | `TRAVEL_START.` | `RoadAgent.java:34` | `RoadAgent.java:86` | `travelStart` `:98`, reads `[0]` | `Train.java:96` | `{String train}` | 7 | 1 |
| CH-09 | `PATH_FIND_REPLY` | `PATH_FIND_REPLY.` | `Station.java:34` | `PathFinding.java:35` (ACT-04, **not** the Station itself) | `pathFindReply` `:42`, reads `[0],[1]` | `RailwayMainAgent.java:148` | `{String target, String direction}` | 8 | 1 |
| CH-10 | `CHANNEL_STATION_INFO` | `STATION.INFO.` | `RailwayMainAgent.java:50` | `RailwayMainAgent.java:119` | `recieveStationInfo` `:155`, reads `[0]` | `Station.java:67` (`sendInfo`, called `:63`, `:96`, `:125`) | `{Station.Info}` — **by reference**, see SEM-05 / DEF-13 | 8 | 1 |
| CH-11 | `CHANNEL_ROAD_STATE` | `ROAD.STATE.` | `RailwayMainAgent.java:54` | `RailwayMainAgent.java:128` | `recieveRoadState` `:166`, reads `[0]` | `RoadAgent.java:91` (`sendState`, called `:87`, `:128`, `:163`) | `{RoadAgent.State}` (enum) | 7 | 1 |
| CH-12 | `CHANNEL_TRAIN_STATE` | `TRAIN.STATE.` | `RailwayMainAgent.java:67` — **package-private**, not `public` | `Generator.java:59` (ACT-02, handler object is AG-01) | `recieveTrainState` `:178`, reads `[0]` | `Train.java:62` (`sendStatusMessage`, called `:57`, `:85`, `:125`) | `{String humanReadableState}` or the sentinel `"KILL"` | 1 per Train | 1 |
| CH-13 | `PLAN_TRAIN` *(bare)* | `PLAN_TRAIN` | `Planning.java:40` | `Planning.java:58` | `planTrain` `:68`, reads `[0],[1],[2]` | `Generator.java:64` | `{String train, String from, String to}` | 1 global | 1 |
| CH-14 | `VOTE` *(bare)* | `VOTE` | `Planning.java:44` | `VoteCollecting.java:35` (ACT-03) | `vote` `:42`, reads `[0],[1],[2]` | `StaticRailwayObject.java:63` — **all 15** static objects | `{String voter, String train, Long diff}` | 1 global | 1 (15 senders) |
| CH-15 | `PATH_FIND` *(bare)* | `PATH_FIND.` — trailing dot is vestigial, nothing is appended | `RailwayMainAgent.java:58` | `RailwayMainAgent.java:114` | `pathFind` `:141`, reads `[0],[1]` | `Station.java:104` — **all 8** stations | `{String from, String to}` | 1 global | 1 (8 senders) |

**Not channels** (must not be mapped as such): `CLOCK_ID` = `"myClock"` (`RailwayMainAgent.java:46`),
`MAIN_AGENT_NAME` = `"Main"` (`:71`), `KILLED` = `"KILL"` (`Train.java:28`).

**Runtime channel-name population.** 15 constants expand to
`4×15 (CH-01..04) + 7 (CH-08) + 8 (CH-09) + 8 (CH-10) + 7 (CH-11) + 3 (CH-13..15) = 93`
permanently-open channel names, plus 3 per live `Train` (CH-05, CH-06, CH-07) and 1 per live
`Train` opened by ACT-02 on AG-01's behalf (CH-12) = **93 + 4·N_trains**.

**Fan-out.** Every channel name is opened exactly once, so every channel has exactly one
subscriber. Two are many-senders-to-one-receiver (CH-14, CH-15); the other thirteen are 1→1.
Despite the API name `sendAll`, **the application uses no fan-out anywhere** — input to #27.
The kernel itself *does* support fan-out; see SEM-03.

**Asymmetric payload read (CH-03).** `Train.java:117` always sends `{train, position, to}`.
`Station.enter` (`Station.java:87-95`) reads `[0]` and `[2]`; `RoadAgent.enter`
(`RoadAgent.java:118-121`) reads `[0]` and `[1]`. The same wire format means two different things
depending on who receives it. Any typed-message migration must split CH-03 into two message types.

---

## 5. Events handled, per agent/activity (EVT)

| id | Owner | Handler method | Bound at | Trigger | Effect summary |
|---|---|---|---|---|---|
| EVT-01 | AG-01 | `pathFind` `RailwayMainAgent.java:141` | CH-15 | Station asks for a direction | `Util.pathDirection(net, from, to)` → CH-09 |
| EVT-02 | AG-01 | `recieveStationInfo` `:155` | CH-10 | Station occupancy changed | `stationInfos.put`, `fireChange()` → GUI repaint |
| EVT-03 | AG-01 | `recieveRoadState` `:166` | CH-11 | Road direction/free changed | `roadAgentStates.put`, `fireChange()` |
| EVT-04 | AG-01 | `recieveTrainState` `:178` | CH-12 | Train status text or `"KILL"` | `trainStates.put`/`remove`, `fireChange()` |
| EVT-05 | ACT-01 | `planTrain` `Planning.java:68` | CH-13 | Generator created a train | Blocking election, see §7 |
| EVT-06 | ACT-01 | `placeTrainIntoFirstStation` `:120` | TMR-03 | Planned departure time reached | `queue.poll()`, `println`, CH-05 |
| EVT-07 | ACT-02 | `generateTrain` `Generator.java:57` | TMR-01 / TMR-02 | Poisson arrival | open CH-12, `createAgent`, CH-13, re-arm TMR-02 |
| EVT-08 | ACT-03 | `vote` `VoteCollecting.java:42` | CH-14 | A static object voted | `votes.put`, `latch.countDown()` |
| EVT-09 | ACT-04 | `pathFindReply` `PathFinding.java:42` | CH-09 | AG-01 answered | `pathDirs.put`, `station.notify()` |
| EVT-10 | AG-02/AG-03 | `voteRequest` `StaticRailwayObject.java:58` | CH-01 | Election started | `computeDifference` → CH-14 |
| EVT-11 | AG-02/AG-03 | `voteResult` `:71` | CH-02 | Election concluded | `addToPlan(train, time)` |
| EVT-12 | AG-02 | `enter` `Station.java:87` (`synchronized`) | CH-03 | Train requests entry | admit (`occupied++`, CH-06) or enqueue |
| EVT-13 | AG-02 | `leave` `Station.java:115` (`synchronized`) | CH-04 | Train left | `occupied--` or admit head of queue; `timetable.removeValue` |
| EVT-14 | AG-03 | `enter` `RoadAgent.java:118` (`synchronized`) | CH-03 | Train requests entry | `acceptTrain` (set direction, CH-06) or `push` |
| EVT-15 | AG-03 | `leave` `RoadAgent.java:150` (`synchronized`) | CH-04 | Train left the track | FREE or admit head of priority queue |
| EVT-16 | AG-03 | `travelStart` `RoadAgent.java:98` (`synchronized`) | CH-08 | Train started travelling | arm TMR-04 |
| EVT-17 | AG-03 | `travelEnd` `RoadAgent.java:113` (`synchronized`) | TMR-04 | Travel time elapsed | CH-07 |
| EVT-18 | AG-04 | `start` `Train.java:69` (`synchronized`) | CH-05 | Departure authorised | `println`, CH-03 to the origin station |
| EVT-19 | AG-04 | `entered` `Train.java:81` (`synchronized`) | CH-06 | Entry granted | CH-04 to the old object, then CH-03 or CH-08 or `Agent.die()` |
| EVT-20 | AG-04 | `travelEnd` `Train.java:104` (`synchronized`) | CH-07 | Road traversal finished | CH-03 to `nextPosition` |
| EVT-21 | AG-04 | `destroy` `Train.java:123` (`synchronized`) | Cybele destructor, bound by name | agent dying | CH-04 (again — DEF-08), CH-12 with `"KILL"` |

`destroy` (EVT-21) is a **lifecycle callback bound by convention, not by `openChannel`** — it is
the only handler in the codebase that has no channel. Any migration must preserve it as a
`takeDown()`-equivalent.

---

## 6. Timers (TMR) — 4 sites, 0 repeating

All four use `Activity.setTimer(clockId, delayMillis, handler, methodName)` — the **one-shot**
overload. The `repeating` overload is never used (C-10). All four are on the single global clock
`CLOCK_ID = "myClock"` (CFG-05).

| id | Site | Delay expression | Where the expression lives | Re-armed by |
|---|---|---|---|---|
| TMR-01 | `Generator.java:50` | `1000` (constant) | inline, in the `Generator` constructor | — (bootstrap only) |
| TMR-02 | `Generator.java:65` | `exp(LAMBDA)` = `Math.round(-8500 * Math.log(random.nextDouble()))` | `Generator.java:68-70`, `LAMBDA` at `:33` | itself, from `generateTrain` — self-rearming Poisson process |
| TMR-03 | `Planning.java:111` | `t > 0 ? t : 0`, where `t = (requestTime+timeDiff) - Cybele.getTime(CLOCK_ID)` | `Planning.java:109` | one per planned train |
| TMR-04 | `RoadAgent.java:102` | `delayInSeconds() + (long)(500*Generator.getRandom().nextGaussian())` = `delay*1000 + (long)(500*g)` | `RoadAgent.java:101`, `delayInSeconds()` at `:105-107` | one per road traversal |

**TMR-04 can be negative.** For `delay == 1` (roads `tr1`, `tr2` — CFG-04) the value is negative
whenever `g < -2`, i.e. ≈2.3 % of traversals. Measured behaviour (EXP-E): Cybele fires a
negative-delay timer **immediately**, so the effect is an instantaneous traversal, not a lost
train. A JADE `WakerBehaviour` with a negative delay behaves the same way, but this must be
asserted, not assumed.

---

## 7. The election protocol (the one non-trivial interaction)

Sequence for one train, all line refs in `Planning.java` unless noted:

1. `generateTrain` (`Generator.java:57`) opens CH-12, creates the `Train` agent (`:61`), and sends CH-13 (`:64`).
2. `planTrain` computes `path = Util.path(net, from, to)` (`:73`) — an **alternating list** `[node, edge, node, edge, …, node]`, so both stations and roads vote.
3. `latch = new CountDownLatch(path.size())` (`:74`), registered in `trainCountDowns` (`:76`).
4. CH-01 is broadcast to **every element of `path`** (`:80-86`), each with a running time estimate `disp` advanced by the road delays.
5. Each voter runs `computeDifference` (`Station.java:130` / `RoadAgent.java:175`) and replies on CH-14 (`StaticRailwayObject.java:63`).
6. ACT-03 stores each vote and counts the latch down (`VoteCollecting.java:51,54`) **on a different thread** — this is the whole reason ACT-03 exists.
7. `planTrain` blocks on `latch.await()` (`:88`), then takes `Collections.max(v)` (`:97`) — the protocol is *max of all requested delays*, i.e. the most constrained resource dictates the departure.
8. CH-02 is broadcast to every element of `path` with the agreed absolute times (`:100-106`).
9. TMR-03 is armed for the departure instant (`:111`), bracketed by `pauseClock`/`resumeClock` (`:108`, `:112`) — which, per SEM-02, does **not** actually freeze the clock across that bracket.
10. `placeTrainIntoFirstStation` (`:120`) polls the `PriorityBlockingQueue`, prints (OUT-01), and sends CH-05 (`:126`).

Note step 10 polls the **globally earliest** `TrainPlan`, not the one whose timer fired — with two
trains planned out of order the printed line and the started train belong to a different plan than
the expired timer. Observed in the live run: `vl6 in stA at 45760` was printed before
`vl5 in stC at 48760`.

---

## 8. Mutable state (ST)

| id | Owner | Field | Decl | Notes |
|---|---|---|---|---|
| ST-01 | AG-01 | `net : UnorientedGraph<String,String>` | `RailwayMainAgent.java:61` | topology; read-only after the constructor |
| ST-02 | AG-01 | `stationInfos : Map<String,Station.Info>` | `:62` | `synchronizedMap`; values are **live aliases** into the Station agents (DEF-13) |
| ST-03 | AG-01 | `roadAgentStates : Map<String,RoadAgent.State>` | `:63` | `synchronizedMap`; immutable enum values, safe |
| ST-04 | AG-01 | `trainStates : Map<String,String>` | `:64` | `synchronizedMap(LinkedHashMap)` — insertion-ordered, which is what the GUI table shows |
| ST-05 | AG-01 | `roadDelays : Map<String,Long>` | `:65` | plain `HashMap`; **read concurrently by ACT-01** at `Planning.java:82,102`. Safe only because it is never written after the constructor |
| ST-06 | AG-01 | `stationCapacities : Map<String,Integer>` | `:66` | plain `HashMap`; read only in the constructor |
| ST-07 | AG-01 | `trainTableModel : TableModel` | `:59` | `transient`; Swing `AbstractTableModel` + `Observer` |
| ST-08 | ACT-01 | `queue : PriorityBlockingQueue<TrainPlan>` | `Planning.java:47` | ordered by `TrainPlan.compareTo` (`:147`) |
| ST-09 | ACT-01 | `votes : UnorientedGraph<String,Long>` | `Planning.java:48` | keyed by the **unordered** `Doubleton(voter, train)` — DEF-05. Guarded by `synchronized(votes)` at `:91` and `VoteCollecting.java:50` |
| ST-10 | ACT-01 | `trainCountDowns : Map<String,CountDownLatch>` | `Planning.java:49` | `synchronizedMap` |
| ST-11 | ACT-02 | `openedChannels : Map<String,String>` | `Generator.java:37` | accumulates channel tickets forever; nothing ever calls `closeChannel` (C-11) → unbounded leak. The author's own `// EXTENSION jak delat ruseni?` at `:36` |
| ST-12 | ACT-02 | `index : int` | `Generator.java:41` | monotonic train counter (`"vl" + index`) |
| ST-13 | ACT-02 | `random : static final Random` | `Generator.java:42` | see NDT-05 |
| ST-14 | AG-02 | `roads : Collection<String>` | `Station.java:35` | injected via the constructor; never read except by the unused getter `:74` |
| ST-15 | AG-02 | `pathDirs : Map<String,String>` | `Station.java:36` | lazily-built routing cache; written from ACT-04 (`PathFinding.java:46`) under `synchronized(station)`, read from the station's own thread at `:101,106` |
| ST-16 | AG-02 | `queue : Queue<QueueItem>` | `Station.java:37` | FIFO of trains waiting for capacity |
| ST-17 | AG-02 | `timetable : TreeMultiMap<Long,String>` | `Station.java:38` | planned slots; `lastKey()` at `:135-136` throws `NoSuchElementException` on an empty map — reachable only if `capacity < 1`, so not live |
| ST-18 | AG-02 | `info : Info` | `Station.java:39` | non-static inner class `:44`; the very object shipped over CH-10 |
| ST-19 | AG-03 | `state : State` | `RoadAgent.java:39` | `FREE` / `TRAVEL_LEFT` / `TRAVEL_RIGHT` (`:47-73`) |
| ST-20 | AG-03 | `traveledTrain : String` | `RoadAgent.java:40` | single slot — a second `travelStart` before `travelEnd` overwrites it |
| ST-21 | AG-03 | `queue : PriorityQueue<OueueItem>` | `RoadAgent.java:38` | ordered by `OueueItem.compareTo` (`:208`) — DEF-03, DEF-04 |
| ST-22 | AG-03 | `timetable : SortedMap<Long,String>` / `invertedTimetable : Map<String,Long>` | `RoadAgent.java:42` / `:41` | two views that must stay in sync; `leave` (`:161-162`) removes from both |
| ST-23 | AG-03 | `leftStation` / `rightStation` / `delay` | `RoadAgent.java:35-37` | `final`; **which station is left vs right is decided by hash order** — NDT-03 |
| ST-24 | AG-04 | `from`, `to` (`final`), `position`, `nextPosition` | `Train.java:41-44` | `position == null` until the first `entered`; `nextPosition == null` is the "arrived" sentinel (DEF-01) |

---

## 9. Hardcoded configuration (CFG)

All configuration is Java source; there is **no external config file for the application**
(`cybelle/cybele.prop` and `cybelle/ICS.prop` configure the *kernel*, not the simulation).

| id | What | Site | Value |
|---|---|---|---|
| CFG-01 | Topology (7 edges, 8 nodes) | `RailwayMainAgent.java:80-86` | `stA–stH:tr1`, `stH–stG:tr2`, `stG–stE:tr3`, `stE–stD:tr4`, `stD–stB:tr5`, `stF–stE:tr6`, `stC–stF:tr7` |
| CFG-02 | Station capacities | `RailwayMainAgent.java:89-96` | A 6, B 5, C 2, D 2, E 5, F 2, G 3, H 2 |
| CFG-03 | Road delays (seconds) | `RailwayMainAgent.java:99-105` | tr1 1, tr2 1, tr3 5, tr4 2, tr5 3, tr6 4, tr7 3 |
| CFG-04 | Travel-time noise | `RoadAgent.java:101` | `500 * nextGaussian()` ms, added to `delay*1000` |
| CFG-05 | Global clock | `RailwayMainAgent.java:111-112` | `createClock("myClock", Cybele.HOST, startTime=0, pace=1.0)`; the `resumeClock` at `:112` is redundant (SEM-06) |
| CFG-06 | Arrival rate | `Generator.java:33` | `LAMBDA = 8500` (ms). Also used as a *planning horizon* in `Station.computeDifference` (`Station.java:131,132,137,139`) — one constant, two unrelated meanings |
| CFG-07 | Origin/destination pairs | `Generator.java:38-39` | `{stA,stB} {stA,stC} {stB,stA} {stB,stC} {stC,stB} {stC,stA}` — 6 pairs, uniform |
| CFG-08 | Bootstrap delay | `Generator.java:50` | 1000 ms |
| CFG-09 | GUI pace presets | `Gui.java:81-83` | Fast 8.0, Normal 1.0, Slow 0.3 (a "Very Fast" 40.0 preset is commented out at `:80`) |
| CFG-10 | GUI main-line layout | `RailwayCanvas.java:39` | `stA stH stG stE stD stB`; the two branch stations are drawn by hand at `:107-108` |
| CFG-11 | Kernel comm mode | `cybelle/cybele.prop:90` | `cybele.srv.comm.app.param.iai = Local;NoSerialization` → payloads pass **by reference** (SEM-05) |
| CFG-12 | Kernel thread pool | `cybelle/cybele.prop:74` | `cybele.srv.thmgmt.app.param.iai = 5 10 4000 3 5` |
| CFG-13 | Kernel event queues | `cybelle/cybele.prop:65-66` | **both commented out** → the effective default is FIFO, no sorting (SEM-01) |
| CFG-14 | Kernel services loaded | `cybelle/cybele.prop:26` | `exception;concurmgmt;evmgmt;thmgmt;intlevent;comm;timer` |

---

## 10. Nondeterminism (NDT)

Four hash-iteration sites decide **behaviour**, not merely display order. Captured today
(2026-08-18) so #19 can lock them in. Reproduction: compile `util/*.java` plus the driver in
`docs/probes/Order.java` and run `java -cp <classes> Order`.

| id | Site | What it decides | Observed order today |
|---|---|---|---|
| NDT-01 | `RailwayMainAgent.java:116` `net.nodeSet()` (`HashSet` built at `util/HashMapGraph.java:150`) | **Station agent creation order** | `[stB, stA, stD, stC, stF, stE, stH, stG]` |
| NDT-02 | `RailwayMainAgent.java:123` `net.values()` (`HashMap.values()`, `util/HashMapGraph.java:187`) | **RoadAgent creation order** | `[tr1, tr4, tr7, tr5, tr3, tr6, tr2]` |
| NDT-03 | `RailwayMainAgent.java:125` `net.allNodesWithEdge(road)` (`util/HashMapGraph.java:134-145`) → `:129-130` | **Which station is `leftStation` vs `rightStation`** (`RoadAgent.java:81`), hence the meaning of `TRAVEL_LEFT`/`TRAVEL_RIGHT` | tr1 L=stA R=stH · tr2 L=stH R=stG · tr3 L=stG R=stE · tr4 L=stE R=stD · tr5 L=stD R=stB · tr6 L=stF R=stE · tr7 L=stC R=stF |
| NDT-04 | `util/Util.java:106` + `:123` — `privatePath`'s local `nodesToEdges` `HashMap.entrySet()` | **Which route a train takes**, hence *who votes* and how long the journey is | stA→stB `[stA,tr1,stH,tr2,stG,tr3,stE,tr4,stD,tr5,stB]` · stA→stC `[stA,tr1,stH,tr2,stG,tr3,stE,tr6,stF,tr7,stC]` · stB→stA reverse of the first · stB→stC `[stB,tr5,stD,tr4,stE,tr6,stF,tr7,stC]` · stC→stB reverse · stC→stA `[stC,tr7,stF,tr6,stE,tr3,stG,tr2,stH,tr1,stA]` |
| NDT-05 | `Generator.java:42` — one **unseeded `private static final Random`**, static and shared JVM-wide | inter-arrival times, OD choice, per-road travel noise | consumed concurrently by ACT-02 (`nextInt` `:60`, `nextDouble` `:69`) and by **every** `RoadAgent` event thread (`nextGaussian` `RoadAgent.java:101`). `java.util.Random` is thread-safe but the *interleaving* is not reproducible, so seeding alone will not make runs deterministic — #19 needs per-agent generators |

**Stability of NDT-01…NDT-04.** `Doubleton.hashCode()` (`util/Doubleton.java:72-76`) sums two
`String` hash codes, and `String.hashCode` is specified by the JLS and never randomised, so the
resulting `HashMap`/`HashSet` orders are deterministic for a given JDK. **Verified:** 5 consecutive
runs on JDK 21 produced byte-identical output (`md5sum` `5d8a2f2fbedfc5134fe3d48b0192d962` ×5), and
the output is **identical on OpenJDK 21.0.11 and OpenJDK 25.0.4** (`diff` clean). So the cross-JDK
hazard is real in principle (`HashMap` iteration order is implementation-defined) but has **not**
been observed between JDK 21 and 25. It is a portability risk to pin, not a per-run flake.
`Doubleton("stA","stH").hashCode() == Doubleton("stH","stA").hashCode() == 228359` — the
commutativity the unordered-pair key relies on holds.

---

## 11. Observable output (OUT) and GUI coupling (GUI)

| id | Site | Output |
|---|---|---|
| OUT-01 | `Planning.java:125` | `System.out.println(poll)` → `TrainPlan.toString` (`:156-158`) → `"<train> in <station> at <departureMillis>"` |
| OUT-02 | `Train.java:70` | `System.out.println(getName() + " started")` |
| OUT-03 | `util/Util.java:69` | `e.printStackTrace()` — **unreachable**: `Util.sleep` has zero call sites (`grep -rn 'Util.sleep' src/` → none) |

Live 75-second run of `./build/install/opencybele/bin/opencybele` produced exactly OUT-01/OUT-02
lines and nothing else from the application (the rest of stdout is the Cybele kernel banner):

```
vl0 in stC at 1103      vl4 in stA at 40760
vl0 started             vl4 started
vl1 in stB at 13103     vl6 in stA at 45760
vl1 started             vl6 started
vl2 in stA at 21103     vl5 in stC at 48760
vl2 started             vl5 started
vl3 in stA at 26103     vl7 in stB at 60822 / vl8 in stB at 74027
vl3 started             vl7 started / vl8 started
```

This is the **entire** observable surface for a headless regression test today — input to #14/#33.

| id | GUI coupling |
|---|---|
| GUI-01 | Only `Gui.java`, `RailwayCanvas.java` and `RailwayMainAgent.java` touch Swing/AWT/`Observable` (C-15). Everything else is Swing-free |
| GUI-02 | `RailwayMainAgent extends java.util.Observable` (`:41`) and owns an inner `TableModel extends AbstractTableModel implements Observer` (`:253-297`) |
| GUI-03 | `RailwayMainAgent` constructs the `Gui` **unconditionally** at `:108-109`, *inside the agent constructor* and *before* the clock (`:111`) and all sub-agents (`:116-134`). There is no headless switch: on a headless JVM the constructor throws `HeadlessException` and the whole simulation fails to boot |
| GUI-04 | Repaints are driven by `fireChange()` (`:198-201`) from EVT-02/03/04; `RailwayCanvas.update` (`:168-170`) calls `repaint(100)` |
| GUI-05 | `RailwayCanvas` overrides `paint(Graphics)` (`:60`) rather than `paintComponent`, and draws with hand-computed `AffineTransform` translations (`:91-117`) — not layout-managed |
| GUI-06 | `Gui.PaceChangeAction` (`:87-100`) calls `Cybele.setPace(CLOCK_ID, pace)` — the only user input in the program |

---

## 12. Cybele runtime semantics — empirically determined (SEM)

Cybele ships as **source-less** jars (`com.iai:cybele-api:1.0`, `com.iai:cybele-impl:1.0`;
`cybelle/Cybele.jar`, `cybelle/CybeleImpl.jar`), so these were settled by running probe programs
against the real jars, not by reading documentation. Probe sources are committed under
`docs/probes/`; each is a standalone `main` and prints its own verdict.

Harness used for every probe (from the probe directory, with `cybele.prop`/`ICS.prop` in
`./cybelle/`):

```
javac -cp ~/.m2/repository/com/iai/cybele-api/1.0/cybele-api-1.0.jar -d classes src/probe/Exp*.java
java --patch-module java.base=cybelle \
     -cp classes:~/.m2/repository/com/iai/cybele-api/1.0/cybele-api-1.0.jar:\
~/.m2/repository/com/iai/cybele-impl/1.0/cybele-impl-1.0.jar probe.ExpX
```

Environment: OpenJDK 21.0.11 (Red Hat), Fedora 43, unmodified `cybelle/cybele.prop` from
`opencybele-baseline`.

---

### SEM-01 — Event-queue discipline with both `cybele.srv.evmgmt.app.param.iai` lines commented out

**Verdict: strict FIFO, no sorting, priorities ignored.** Input to #16. Confidence: **high**
(agreeing static and dynamic evidence, plus a positive control).

*Static evidence* (`javap -p -c` on `cybele-impl-1.0.jar`):

* `IAIEventManagement.start(sysParam, appParam)` — when `appParam` is `null` (both lines commented
  out) it falls through to the `else` at offset 526 and assigns
  `sysSortStrategy = agentQueueSortStrategy = defaultStrategy` and
  `sysComparator = agentQueueComparator = defaultComparatorId`.
* `addStrategy` / `addComparator` set `defaultStrategy` / `defaultComparatorId` to the **first**
  id registered. The registration order is the `cybele.srv.evmgmt.sys.param.iai` list
  (`cybele.prop:56`), whose first `_sort` token is `no_sort` and first `_comp` token is `no_comp`.
* ⇒ the effective default is exactly the *first* commented-out line,
  `system_queue no_sort no_comp; agent_queue no_sort no_comp`.
* `NoSortStrategy.sort(List, Comparator)` is `{ return; }` — a literal no-op. `IAIQueue.add`
  appends to a `LinkedList` tail; `AgentQueue.getNextEvent()` is `get(0)`, the head. ⇒ FIFO.

*Dynamic evidence* (`docs/probes/ExpA.java`): one agent opens four channels with Subscription
static priorities 1/5/9, a blocking handler holds the agent thread, six messages are sent in the
order mid, low, high, mid, high, low. Observed:

```
block-begin
all 6 sent, order 1..6 (mid,low,high,mid,high,low)
block-end
recv tag=P_MID  seq=1 prio=4
recv tag=P_LOW  seq=2 prio=4
recv tag=P_HIGH seq=3 prio=4
recv tag=P_MID  seq=4 prio=4
recv tag=P_HIGH seq=5 prio=4
recv tag=P_LOW  seq=6 prio=4
```

Send order preserved exactly. **Not** priority, **not** LIFO.

*Positive control* (`docs/probes/ExpA2.java`): the same probe with a TIMER event queued between
message 2 and message 3, run twice — once with the stock file, once with
`agent_queue merge_sort staticpriority_comp` enabled:

```
DEFAULT   : MSG 1, MSG 2, TIMER, MSG 3, MSG 4      <- FIFO position kept
CONTROL   : TIMER, MSG 1, MSG 2, MSG 3, MSG 4      <- timer jumps the queue
```

The probe is therefore capable of observing a re-ordering; the default simply does not re-order.

*Corollary — event priority is not application-controllable.* `CybeleEvent`'s priority field is
assigned by constant in the constructors (`javap -c cybele.kernel.CybeleEvent`): **4**
(`Cybele.DEFAULT_PRIORITY`) for MESSAGE events, **8** (`MAX_PRIORITY`) for TIMER and INTERNAL
events. There is no setter. The 4th argument of `Activity.openChannel(name, method, handler, int)`
is the *Subscription*'s `staticPriority`, which `IAIEventNode` ignores — it copies
`CybeleEvent.getPriority()` instead. So even if a priority comparator were switched on, all
application messages would tie at 4 and only timers would ever jump the queue.

*Also established here (SEM-04):* `block-end` always precedes the six queued deliveries ⇒ **an
activity dispatches its own events strictly serially**. A blocking handler starves every other
channel and timer belonging to that activity. This is exactly why ACT-03 and ACT-04 exist.

*Also established here:* `CybeleEvent.getTag()` returns the channel name the message was sent to
(observed `tag=P_MID` etc.), which is what `RailwayMainAgent.objectName` (`:192-196`) parses.

---

### SEM-02 — Are `Cybele.pauseClock` / `resumeClock` counted / reentrant?

**Verdict: NO — they are a plain boolean flag, not a counter. AND, for the `Cybele.HOST`-scope
clock the application uses, they are ASYNCHRONOUS: the clock is still running when `pauseClock()`
returns.** Input to #29. Confidence: **high**.

*Reentrancy* (`docs/probes/ExpB2.java`), with a 3 s settle window after each call:

```
##### scope HOST  (== RailwayMainAgent.java:111) #####
  running          isPaused=false delta(500ms)=500
  pauseClock #1 -> true
  after 1 pause    isPaused=true  delta(500ms)=0
  pauseClock #2 -> true
  after 2 pauses   isPaused=true  delta(500ms)=0
  resumeClock #1 ->true
  2 pause/1 resume isPaused=false delta(500ms)=500   <-- NOT counted
  resumeClock #2 ->true
  2 pause/2 resume isPaused=false delta(500ms)=500
##### scope LOCAL #####   (identical results)
```

Two pauses followed by **one** resume leaves the clock **running**. Confirmed statically:
`ContinuousClock.setPause()` begins `if (paused) return;` and `setResume()` begins
`if (!paused) return;` — a single `boolean paused` field, no depth counter.

*The boolean return value does not mean "state changed."* From `javap -c
com.iai.cybele.timer.IAITimerService.pauseClock`: it returns `false` only when the timer service is
not alive or the clock id is unknown, and `true` in every other case — including pausing an
already-paused clock. Measured (`docs/probes/ExpB.java`):

```
pauseClock on paused clock   true
pauseClock on unknown clock  false
resumeClock on unknown clock false
```

*Asynchrony.* `IAITimerService.pauseClock` branches on `clock.getType()`: only for `LOCAL` (type 1)
does it call `localPause` directly; for `HOST`/`NETWORK` it calls `TimerAgent.sendPause(id)`, which
is `Activity.sendAll(timerPrefix + clockId, {cmdPause})` — an ordinary asynchronous Cybele channel
message, applied later by `TimerAgent.receiveNetCmd`. Latency bracketed with a fresh clock per
trial (`docs/probes/ExpB4.java`):

```
wait=0ms    after pauseClock -> isPaused=false clockAdvance(400ms real)=1
wait=50ms   after pauseClock -> isPaused=true  clockAdvance(400ms real)=0
wait=100ms  ... 5000ms                         all isPaused=true, advance=0
```

**Consequence for the application.** `RoadAgent.push()` (`:132-134`), `RoadAgent.pop()`
(`:167-169`) and `Planning.planTrain` (`:108-112`) all use the shape
`pauseClock(); <a couple of statements>; resumeClock();`. Because the pause has not landed yet when
`resumeClock()` is called microseconds later, **the clock is not frozen across those critical
sections**. Measured over 50 back-to-back pause/resume pairs: 3 004 ms real elapsed vs 3 001 ms of
clock elapsed — a ~3 ms total loss, i.e. the construct is effectively a no-op. A migration that
implements pause/resume *synchronously* would change simulation behaviour; a migration that drops
them entirely would not.

*Known probe artefact:* `docs/probes/ExpB3.java` polls `Cybele.isPaused()` every 50 ms in a loop
and then never observes the pause land (10 s). The poll-free bracket in `ExpB4` is the
authoritative measurement; the polling variant is recorded because it shows the measurement is
perturbable and any future probe of this must avoid tight polling on the clock monitor.

---

### SEM-03 — Does a Cybele channel support TWO simultaneous subscribers?

**Verdict: YES — Cybele channels are genuine multicast. Every subscriber's handler fires on a
single `sendAll`.** #20's probe agent is viable. Confidence: **high**.

`docs/probes/ExpC.java` exercises three variants on three distinct channel names and counts handler
invocations:

```
=== ExpC deliveries (6 handler invocations, 6 = all fan-out works) ===
   SHARED_SAME_ACT  subscriber#1 (h1)            got msgSAME
   SHARED_SAME_ACT  subscriber#2 (h2)            got msgSAME
   SHARED_TWO_ACT   subscriber#1 (1st activity)  got msgACT
   SHARED_TWO_ACT   subscriber#2 (2nd activity)  got msgACT
   SHARED_TWO_AGENT subscriber#1 (agentA)        got msgAGENT
   SHARED_TWO_AGENT subscriber#2 (agentB)        got msgAGENT
```

All three work: two `openChannel` calls in the **same activity** with different callbacks; one in
each of **two activities of the same agent**; one in each of **two different agents**. A probe
agent can therefore tap any existing channel (CH-01…CH-15) without modifying the sender or the
existing receiver — the application's 1-subscriber-per-channel property (C-06) is its own choice,
not a kernel limitation.

---

### SEM-04 — Serial dispatch per activity

See SEM-01. An activity processes its own events one at a time; a blocking handler starves that
activity's other channels *and* its timers. Verified twice: `ExpA` (queued messages wait for
`block-end`) and the first draft of `ExpE`, whose blocking constructor prevented its own timers
from ever firing. This is the single most important structural constraint of the current design
and the sole reason ACT-03 and ACT-04 exist.

---

### SEM-05 — Payloads pass by reference (`Local;NoSerialization`)

With `cybele.prop:90` = `Local;NoSerialization`, the receiver gets the **same object identity** the
sender passed, and sees later mutations. `docs/probes/ExpF.java`:

```
identical object at receiver = true
receiver sees post-send mutation: received.value = 42 (42 => live alias)
```

`Station.Info` (`Station.java:44`) is a **non-static inner class** (so it also holds an implicit
reference to its `Station`), and `Station.sendInfo` (`:67`) ships the *live* instance over CH-10.
`RailwayMainAgent.stationInfos` (ST-02) therefore aliases eight live Station fields that
`Station.enter`/`leave` mutate from the stations' own threads (`:93`, `:119`) while
`RailwayCanvas.paintStation` (`RailwayCanvas.java:67-69`) reads them from the EDT. See DEF-13.

---

### SEM-06 — Miscellaneous timer/channel semantics (`docs/probes/ExpE.java`)

```
negative-delay (-1500) timer fired = true
zero-delay     (    0) timer fired = true
positive-delay (  500) timer fired = true
sendAll BEFORE openChannel delivered = false   (silently dropped)
sendAll AFTER  openChannel delivered = true    (control)
```

* A **negative** `setTimer` delay fires immediately — relevant to TMR-04 (`RoadAgent.java:102`).
* A **zero** delay fires — relevant to TMR-03 (`Planning.java:111`, `t > 0 ? t : 0`).
* A `sendAll` to a channel nobody has opened yet is **silently dropped**; there is no buffering,
  no queueing, no error. This is the mechanism behind the author's own
  `//BUG ne vzdy se doruci` at `Planning.java:126` (DEF-02).
* `Cybele.createClock` leaves the clock **running** (`isPaused=false`, time advancing immediately
  after the call), so `RailwayMainAgent.java:112`'s `resumeClock` is redundant.

---

## 13. Defects and hazards (DEF)

Recorded here so nothing is lost; **full triage is #22**. Each has a `file:line`.

| id | Site | Defect |
|---|---|---|
| DEF-01 | `Station.java:105` + `PathFinding.java:47` | **Untimed `wait()` with no predicate loop**, woken by `notify()` (not `notifyAll`) from a *different* activity. On a spurious/mis-targeted wakeup `pathDirs.get(target)` (`Station.java:106`) returns `null`; that `null` travels as `nextPosition` in CH-06; `Train.entered` (`Train.java:87-93`) treats a `null` `nextPosition` as "arrived" and calls `Agent.die()` **mid-route**. Also: `wait()` is called while holding the `synchronized` `Station` monitor from `enter`/`leave`, so the whole station stalls |
| DEF-02 | `Planning.java:126` | The author's own `//BUG ne vzdy se doruci` ("not always delivered"). CH-05 may be sent before the `Train` agent has executed `Activity.openChannel(START+…)` at `Train.java:54`; per SEM-06 the message is then silently dropped and the train never starts |
| DEF-03 | `RoadAgent.java:211` | `(int) (diff(time) - o.diff(time))` — narrowing a millisecond `long` to `int`. *Note:* `diff(t) - o.diff(t)` algebraically cancels `t`, so reading the live clock at `:210` does **not** make the ordering unstable; the truncation is the real defect. Same pattern at `Planning.java:148` |
| DEF-04 | `RoadAgent.java:222` (in `frequency`, `:219-225`) | `i.equals(position)` compares an `OueueItem` to a `String`. `OueueItem` does not override `equals`, so this is **always false** and `frequency` always returns 0 — the entire secondary tie-break at `:213` is dead code |
| DEF-05 | `Planning.java:48`, `:92`, `:96` | `votes` is keyed by the **unordered** `Doubleton(voter, train)` (`VoteCollecting.java:51`). A duplicate vote from the same voter **overwrites** the map entry while `latch.countDown()` (`VoteCollecting.java:54`) still fires, so the `assert v.size() == path.size()` at `:96` can fail (silently, since asserts are off — C-08) and `Collections.max` at `:97` then runs over a short list |
| DEF-06 | `RoadAgent.java:204` | `invertedTimetable.get(train) - time` — unboxing NPE if the train is not in the inverted timetable (e.g. it entered the queue before its `VOTE_RESULT` was applied, or after `leave` removed it at `:162`) |
| DEF-07 | `Train.java:82-84` | `leaveObject(position)` (CH-04) is sent from `entered`, i.e. **after** the new object has already incremented its occupancy (`Station.java:93`). Peak occupancy is over-counted for the duration of the overlap, and admission decisions are made against the inflated number |
| DEF-08 | `Train.java:123-126` | `destroy()` calls `leaveObject(position)` again, so a **second** CH-04 is sent for the final station after `Train.entered` already sent one at `:82`. `Station.leave` then decrements `occupied` twice or wrongly admits a queued train |
| DEF-09 | `VoteCollecting.java:53-54` | `scrutator.getTrainCountDowns().get(train).countDown()` — a vote arriving after `Planning.java:89` removed the latch NPEs. The `assert` at `:53` that would have caught it is disabled |
| DEF-10 | `Generator.java:37`, `:62` + C-11 | `openedChannels` accumulates one channel ticket per train forever; `Activity.closeChannel` is never called anywhere in the codebase. CH-12 channels leak for every train ever created. The author flagged it: `// EXTENSION jak delat ruseni?` at `:36` |
| DEF-11 | `Train.java:61` | `(mess == KILLED)` — reference comparison of `String`s. Works only because both sides are compile-time constants; any refactor that computes the value breaks the `KILL` sentinel silently |
| DEF-12 | `RailwayObject.java:27-31`, called from `PathFinding.java:35` | `getName()` reads the **thread-context** `Agent.getAgentId()`, so `station.getName()` returns the name of *the calling thread's* agent, not the receiver's. It happens to be correct today only because ACT-04 is an activity of the same agent. Any cross-agent call to `getName()` silently returns the wrong name |
| DEF-13 | `Station.java:39,44,67` + `RailwayMainAgent.java:62` (SEM-05) | `Station.Info` is a non-static inner class shipped by reference over CH-10. `RailwayMainAgent.stationInfos` values are **live aliases** mutated by `Station.enter`/`leave` (`:93`, `:119`) on the station threads while the Swing EDT reads them in `RailwayCanvas.paintStation` (`RailwayCanvas.java:67-69`) — an unsynchronised cross-thread read of non-volatile `int` fields |
| DEF-14 | `RoadAgent.java:100` | `traveledTrain` is a single slot overwritten by every `travelStart`; `travelEnd` (`:113-116`) notifies whatever is in it. Guarded today only by the road being single-occupancy |
| DEF-15 | `RailwayMainAgent.java:108-109` (GUI-03) | The `Gui` is constructed unconditionally inside the agent constructor; no headless mode exists |
| DEF-16 | `RoadAgent.java:102` (TMR-04) | Travel delay can be negative for `delay == 1` roads (`tr1`, `tr2`), ≈2.3 % of traversals. Cybele fires such a timer immediately (SEM-06) ⇒ instantaneous traversal |
| DEF-17 | `Planning.java:121` vs `:111` | `placeTrainIntoFirstStation` polls the globally earliest `TrainPlan` rather than the plan whose timer fired, so the printed line and the started train can belong to a different plan than the expired timer. Observed live (§11) |
| DEF-18 | `Station.java:135-136` | `timetable.lastKey()` on an empty `TreeMultiMap` throws `NoSuchElementException`; unreachable only because every capacity in CFG-02 is ≥ 2 |
| DEF-19 | `RoadAgent.java:190` | Class name typo `OueueItem` (should be `QueueItem`); harmless but load-bearing for any grep-based refactor |
| DEF-20 | `RailwayMainAgent.java:155,166,178` | Handler names are misspelled (`recieve…`) and bound by **string literal** at `:119`, `:128`, `Generator.java:59`. Renaming the methods without renaming the literals fails silently at runtime |
| DEF-21 | C-08 | All 33 `assert`s are disabled — the codebase's only invariant checks never run. Input to #14 |

---

## 14. What could **not** be determined

* **The exact set of Cybele versions/build this jar corresponds to.** `Cybele.jar` /
  `CybeleImpl.jar` are source-less, dated 2002, and carry no upstream identifier beyond the banner
  ("version 1.0 … from Intelligent Automation, inc"). All kernel semantics above are behaviour of
  *these* jars.
* **Whether two activities of the same agent execute concurrently.** SEM-04 establishes serial
  dispatch *within* one activity; the probes did not isolate whether two activities of one agent
  can run handlers simultaneously (the thread-pool config CFG-12 suggests yes, and ACT-03/ACT-04
  only make sense if yes, but this was not measured directly).
* **The upper bound on the `pauseClock` asynchrony window under load.** Measured < 50 ms on an idle
  JVM; not measured with 15 agents and a live event storm.
* **Whether `Doubleton`-driven iteration order is stable on non-HotSpot JVMs or on JDK < 21.** Only
  OpenJDK 21.0.11 and 25.0.4 were tested (both identical).
* **The behaviour of `Agent.die()` with respect to the dying agent's open channels.** Not probed;
  DEF-10 concerns only CH-12, which is owned by ACT-02 on AG-01 and therefore certainly leaks.
