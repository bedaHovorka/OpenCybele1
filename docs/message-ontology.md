# The railway message ontology — fifteen channels, fifteen `ACLMessage`s

> Produced for [#27](https://github.com/bedaHovorka/OpenCybele1/issues/27) on branch
> `jade-develop`. **Implemented from** by [#30](https://github.com/bedaHovorka/OpenCybele1/issues/30)–[#34](https://github.com/bedaHovorka/OpenCybele1/issues/34)
> (the agent ports, one ticket at a time), [#36](https://github.com/bedaHovorka/OpenCybele1/issues/36)
> (the JADE probe) and [#38](https://github.com/bedaHovorka/OpenCybele1/issues/38) (the queue-drain
> assertion). **Reused by** [#46](https://github.com/bedaHovorka/OpenCybele1/issues/46) on branch
> `jason`: everything in §5 is framework-free and is not re-decided there.
>
> The code this document describes is in two places, and the split is the subject of §10:
> `src/domain/java/cz/vutbr/fit/ags/railway/domain/msg/` (pure) and
> `src/jade/java/cz/vutbr/fit/ags/railway/jade/` (the `jade.lang.acl` binding). Neither is on
> the Cybele application's classpath. **This branch's agents deliberately still use Cybele
> channels**; rewiring them is #30–#34's job, ticket by ticket, each with its own parity gate run.

## 0. What this decides

Cybele has **no performatives, no conversation ids, no reply-to and no ontology**. The only
routing information in the entire application is a channel-name string (`docs/INVENTORY.md` §4),
and the canonical parity trace writes a literal `-` in field 6 for exactly that reason
(`docs/trace-format.md`, "Why `performative` is empty"). So every performative below is a
**decision**, not a fact recovered from the source, and #39 will compare traces in which the
normalizer erases field 6 precisely because the two frameworks disagree about it. Each one is
justified in §4; the justification is the deliverable, not the value.

Five decisions are taken here and are not open to #30–#34:

| # | Decision | §  |
|---|---|---|
| 1 | Addressing is **direct AID unicast** for all fifteen channels. **No DF.** | §2 |
| 2 | The performative assignment: 4 × `request`, 7 × `inform`, and one each of `cfp`, `propose`, `accept-proposal`, `query-ref`. | §4 |
| 3 | Payloads are **named immutable records**, one per message type, sent with `setContentObject`. | §5 |
| 4 | Topics are minted **per channel constant** — fifteen, fixed at boot — and are for the probe only. | §6 |
| 5 | The `:ontology` slot carries the channel identity, which is what makes the fifteen templates disjoint. | §7 |

## 1. The census this rests on — do not re-derive it

From [#9](https://github.com/bedaHovorka/OpenCybele1/issues/9) and the subscriber table on #27.
Every channel name is opened at **exactly one `Activity.openChannel` site in the simulation**,
and the per-agent name suffix makes collisions impossible. (`TraceProbe` is the sixteenth caller
and is not part of the simulation — see the note below.)

**All fifteen channels have exactly one _application_ subscriber.** Thirteen are strictly
1 → 1; `VOTE` and `PATH_FIND` are many-senders-to-one-receiver. **Despite the
`Activity.sendAll` API name there is no fan-out among the agents.**

> **The qualifier is load-bearing, and an earlier draft of this section dropped it.** With
> `sim.trace.enabled=true` there *is* a second subscriber on every one of the fifteen:
> `TraceProbe` opens its own handler on all of them — the three bare names, four per static
> object, two per station, two per track, and four per train slot. That is not a counterexample
> to §2; **it is the mechanism §6 exists to replace.** Cybele lets a second handler attach to a
> channel and JADE does not, which is exactly why the probe needs topics and normal delivery
> does not. Read §1 as "one subscriber that is part of the simulation", and §6 as "and here is
> what to do about the one that is not".

That — one *application* subscriber, always — settles §2 and most of §6.

## 2. Addressing — direct AID unicast, and no DF

```java
ACLMessage acl = new ACLMessage(ACLMessage.REQUEST);
acl.addReceiver(new AID("stA", AID.ISLOCALNAME));   // resolved by the AMS white pages
```

`docs/CYBELLE_TO_JADE.md`'s mapping row reads *"Directory / community → **DF** (yellow pages) +
**AMS**"*. **The AMS half is right and the DF half does not apply to this repo** — that
correction is already in that file and this document does not reopen it. Nothing in this
application advertises a capability, nothing searches for one, and every channel is addressed by
a literal agent name. A DF lookup would be a service discovery step in front of a name the sender
already knows.

`Messages.build` is where this lives, and `AclBindingTest.addressing_is_direct_aid_unicast`
asserts it for all fifteen: exactly one named receiver, resolved from a local name, on every
channel.

**Fan-in is not fan-out.** `VOTE` and `PATH_FIND` have many senders and one receiver. In JADE
that is fifteen (respectively eight) agents addressing one AID — still plain unicast, with no
topic and no directory involved.

## 3. The table

`id` is the `docs/INVENTORY.md` channel id; `event` is trace field 3. `record` is the class in
`cz.vutbr.fit.ags.railway.domain.msg`. Fields are listed in **trace field-7 key order**, which is
what `Channel.payloadKeys()` returns and what a golden holds.

**That is not always the baseline's slot count**, and the difference matters to a port. CH-10
`STATION_INFO` shows two keys, `occupied` and `capacity`, but the baseline sends **one** Cybele
slot — a `Station.Info` object the probe reads two fields out of. CH-11 `ROAD_STATE` likewise
sends one slot holding a `RoadAgent.State` enum. The other thirteen channels do have one key per
slot. Read the column as "what field 7 contains", never as "how many things were on the wire".

| id | Cybele channel | from → to | record | payload (trace field-7 key order) | performative |
|---|---|---|---|---|---|
| CH-01 | `VOTE_REQUEST.<obj>` | `Main` → station\|track | `VoteRequest` | `train:String`, `expected:long` | `cfp` |
| CH-02 | `VOTE_RESULT.<obj>` | `Main` → station\|track | `VoteResult` | `train:String`, `planned:long` | `accept-proposal` |
| CH-03 | `ENTER.<obj>` | train → station\|track | `EnterRequest` | `train:String`, `position:String?`, `target:String` | `request` |
| CH-04 | `LEAVE.<obj>` | train → station\|track | `LeaveNotice` | `train:String` | `inform` |
| CH-05 | `START.<train>` | `Main` → train | `StartCommand` | `station:String` | `request` |
| CH-06 | `ENTER_REPLY.<train>` | station\|track → train | `EnterReply` | `object:String`, `next:String?` | `inform` |
| CH-07 | `TRAVEL_END.<train>` | track → train | `TravelEnd` | `road:String` *(unread — §5.3)* | `inform` |
| CH-08 | `TRAVEL_START.<road>` | train → track | `TravelStart` | `train:String` | `request` |
| CH-09 | `PATH_FIND_REPLY.<st>` | `Main` → station | `PathFindReply` | `target:String`, `direction:String` | `inform` |
| CH-10 | `STATION.INFO.<st>` | station → `Main` | `StationInfo` | `occupied:int`, `capacity:int` | `inform` |
| CH-11 | `ROAD.STATE.<tr>` | track → `Main` | `RoadStateReport` | `state:RoadDirection` | `inform` |
| CH-12 | `TRAIN.STATE.<train>` | train → `Main` | `TrainState` | `state:String` | `inform` |
| CH-13 | `PLAN_TRAIN` | `Main` → `Main` | `PlanTrain` | `train:String`, `from:String`, `to:String` | `request` |
| CH-14 | `VOTE` | station\|track (×15) → `Main` | `Vote` | `voter:String`, `train:String`, `diff:long` | `propose` |
| CH-15 | `PATH_FIND.` | station (×8) → `Main` | `PathFindRequest` | `from:String`, `to:String` | `query-ref` |

And the JADE slots, per channel:

| id | `:ontology` **and** topic name | subject (trace field 1) comes from | `MessageTemplate` |
|---|---|---|---|
| CH-01 | `railway.VOTE_REQUEST` | payload `train` | `and(MatchOntology(id), MatchPerformative(CFP))` |
| CH-02 | `railway.VOTE_RESULT` | payload `train` | `… MatchPerformative(ACCEPT_PROPOSAL)` |
| CH-03 | `railway.ENTER` | payload `train` | `… MatchPerformative(REQUEST)` |
| CH-04 | `railway.LEAVE` | payload `train` | `… MatchPerformative(INFORM)` |
| CH-05 | `railway.START` | **receiver** AID | `… MatchPerformative(REQUEST)` |
| CH-06 | `railway.ENTER_REPLY` | **receiver** AID | `… MatchPerformative(INFORM)` |
| CH-07 | `railway.TRAVEL_END` | **receiver** AID | `… MatchPerformative(INFORM)` |
| CH-08 | `railway.TRAVEL_START` | payload `train` | `… MatchPerformative(REQUEST)` |
| CH-09 | `railway.PATH_FIND_REPLY` | **receiver** AID | `… MatchPerformative(INFORM)` |
| CH-10 | `railway.STATION_INFO` | **sender** AID | `… MatchPerformative(INFORM)` |
| CH-11 | `railway.ROAD_STATE` | **sender** AID | `… MatchPerformative(INFORM)` |
| CH-12 | `railway.TRAIN_STATE` | **sender** AID | `… MatchPerformative(INFORM)` |
| CH-13 | `railway.PLAN_TRAIN` | payload `train` | `… MatchPerformative(REQUEST)` |
| CH-14 | `railway.VOTE` | payload `train` | `… MatchPerformative(PROPOSE)` |
| CH-15 | `railway.PATH_FIND` | payload `from` | `… MatchPerformative(QUERY_REF)` |

Eight subjects come from the payload, three from the sender, four from the receiver. All three
sources are on a plain `ACLMessage`; §6 turns on that.

**`Main` is five channel families, not one.** `Generator`, `Planning` and `VoteCollecting` are
*activities* of `RailwayMainAgent`, not agents, so the frozen goldens carry the literal `Main`
wherever one of them is an endpoint:

| channel | field | who really sends/receives it |
|---|---|---|
| CH-13 `PLAN_TRAIN` | **4 _and_ 5** | `Generator` → `Planning` |
| CH-01 `VOTE_REQUEST` | 4 | `Planning` |
| CH-02 `VOTE_RESULT` | 4 | `Planning` |
| CH-05 `START` | 4 | `Planning` |
| CH-14 `VOTE` | 5 | `VoteCollecting` |

**If #34 takes the option §11 leaves open — separate AIDs for the three activities — all five
families diff, not just `PLAN_TRAIN`.** Whatever the internal split, the probe must write `Main`
in those fields. `Party.MAIN` in the channel table already covers all five; this is the sentence
that says so.

**`PATH_FIND` and `PATH_FIND_REPLY` are not on that list** and need no special handling:
`RailwayMainAgent` itself handles them, so its own name is already the right one.

**`PATH_FIND.` really does end in a dot and really is used bare.**
`RailwayMainAgent.PATH_FIND = "PATH_FIND."`, opened and sent to with nothing appended.
Reproduced, not tidied (`ChannelTableTest.cybele_channel_names_are_reproduced_verbatim`).

## 4. The performatives, and why each one

### 4.1 The election — `cfp` / `propose` / `accept-proposal`

`Planning` broadcasts `VOTE_REQUEST` to every member of a train's path, waits on a
`CountDownLatch` sized to the path, takes the **maximum** of the returned delays, and broadcasts
the agreed departure back to all of them. That is an auction over a single numeric attribute, and
FIPA's contract net is the idiom it is a degenerate case of. So:

* **CH-01 `VOTE_REQUEST` → `cfp`.** It opens the round and invites a value.
* **CH-14 `VOTE` → `propose`.** The voter answers with the delay it would need. It is a
  counter-offer, not an answer to a question, which is what rules out `inform`.
* **CH-02 `VOTE_RESULT` → `accept-proposal`.** The recipient's reaction is `addToPlan` — it
  commits the slot. That is what `accept-proposal` means: perform the proposed action.

**Three deviations from FIPA-CNP, all deliberate, all recorded here so #39 does not read them as
port bugs:**

1. **`accept-proposal` goes to every path member, and it carries a value that _no_ voter
   proposed — not even the one that set it.** The two legs are not in the same units. `VOTE`
   carries a **delay difference** (`computeDifference`, `StaticRailwayObject.java:63`), while
   `VOTE_RESULT` carries `planned.get(i)` — an **absolute accumulated departure instant**,
   re-derived by `DispatchTimeline.accumulate(path, roadDelays, requestTime + max(votes))`
   (`Planning.java:101-105`). So this is not "most voters get someone else's number": the
   number in the `accept-proposal` is the initiator's synthesis and was never on the table.
   The recipient still performs the action the round was for — `addToPlan`, commit the slot —
   which is why `accept-proposal` remains the closest act; but the departure from FIPA-CNP is
   larger than a first reading suggests, and there is **no `reject-proposal` anywhere in this
   application.**

   *(A count that was wrong here and is worth correcting rather than deleting: an earlier draft
   said "fourteen of fifteen path members". **15 is the number of static objects in the whole
   topology, not the size of a path.** On the default topology and the default OD pairs
   — `ScenarioConfig` `DEF_TOPOLOGY` / `DEF_ARRIVAL_PAIRS` — a path is **9 or 11** members:
   `stA↔stB` and `stA↔stC` are 11, `stB↔stC` is 9, and 11 is the longest the topology admits.
   The eleven `VOTE_REQUEST`/`VOTE`/`VOTE_RESULT` legs in `docs/trace-format.md`'s `vl3` sample
   are one `stA→stB` election, not a coincidence.)*
2. **There is no `refuse` and no `not-understood`.** A voter always answers. `computeDifference`
   cannot decline.
3. **There is no deadline.** `latch.await()` has no timeout — *the latch count **is** the
   protocol* — which is DEF-22 (class (c) in `docs/defect-triage.md`) and the reason a lost vote
   hangs the run rather than failing it.

> **Consequence for the port, and it is not optional: do not set `:protocol`, and do not use
> `jade.proto.ContractNetInitiator`/`ContractNetResponder`.** Those behaviours implement the FIPA
> state machine, which means a `reply-by` deadline and a `refuse` branch this election does not
> have. Adding either changes behaviour under load — exactly the load where DEF-22 shows —
> and a run that times out where the baseline hangs is not a parity run. `Messages.build` leaves
> `:protocol` unset and `AclBindingTest.slots_carry_the_channel_identity_and_the_performative`
> asserts it stays that way.

### 4.2 The queueing system — `request` / `inform`

* **CH-03 `ENTER` → `request`.** The train asks the object to perform an action: admit it.
* **CH-06 `ENTER_REPLY` → `inform`, not `agree`.** `agree` means "I will do it"; the object has
  **already** incremented `occupied` (or set its direction) before it replies, so the reply
  reports a completed fact. `inform` is the right act and it also avoids implying a later
  `inform` leg that never comes.
* **CH-04 `LEAVE` → `inform`.** A statement that the train is no longer there. The receiver does
  react — it admits the next queued train — but that is its own decision, not a request the
  train made, and nothing is sent back.
* **CH-08 `TRAVEL_START` → `request`**, **CH-07 `TRAVEL_END` → `inform`.** The only
  request/response pair in the application with real time between the legs: the track arms a
  timer and reports completion.
* **CH-05 `START` → `request`.** `Planning` commands the train to begin. There is no
  acknowledgement; the train's next act is an `ENTER` to a third party.
* **CH-13 `PLAN_TRAIN` → `request`.** "Run the election for this train." An action with an
  effect, not a question with an answer.

**There is no negative reply anywhere in the admission protocol.** A train that cannot be
admitted is silently queued and hears nothing — *the queue is the refusal*. A port must not
invent a `refuse` or a `failure`: a behaviour waiting for one would wait forever, and #38's
queue-drain assertion would be the first thing to notice.

### 4.3 The path lookup — `query-ref` / `inform`

* **CH-15 `PATH_FIND` → `query-ref`.** It asks for the referent of an expression: "the track from
  `from` towards `to`". That is exactly `query-ref`'s meaning and the only genuine question the
  application asks.
* **CH-09 `PATH_FIND_REPLY` → `inform`, not `inform-ref`.** `inform-ref` is a FIPA *macro act*
  with no standard content form, and JADE's own query helpers answer a `query-ref` with `inform`.
  Choosing the macro act would buy nothing and make the template harder to write.

What is missing relative to FIPA's query protocol: the `agree`/`refuse` leg. `Main` always
answers, and `Station.getPathDirection` blocks in `wait()` until it does, with no timeout.

### 4.4 The three state pushes — unsolicited `inform`

CH-10, CH-11 and CH-12 are pushed to `Main` on every change, and **nothing ever subscribed**.
FIPA would model that as `subscribe` followed by a stream of `inform`s; this application has no
`subscribe` step because `RailwayMainAgent` opens the channel itself, before the sender exists.
The port keeps the `inform`s and does not invent the subscription: adding one would add a message
to the trace that the baseline never sends.

### 4.5 What was considered and rejected

| Act | Where it was tempting | Why not |
|---|---|---|
| `agree` | CH-06 `ENTER_REPLY` | The action is already done when the reply is sent. `agree` would imply a later `inform` that never comes. |
| `inform-ref` | CH-09 `PATH_FIND_REPLY` | Macro act, no standard content form; JADE's own query helpers use `inform`. |
| `subscribe` | before CH-10/CH-11/CH-12 | Nothing subscribes. Adding the leg would add lines to the trace. |
| `reject-proposal` | CH-02 for non-winning voters | The application does not send it. Every voter gets the same `accept-proposal`. |
| `refuse` / `failure` | CH-06 when a train is queued | Nothing is sent when a train is queued. The silence is the protocol. |
| `propagate` / `proxy` | a "broadcast" reading of `sendAll` | There is no fan-out. See §1. |
| `request-when` | CH-08 `TRAVEL_START` | The track does not wait for a condition; it arms a fixed timer. |

### 4.6 Where field 6 goes

`TraceLine.render(..., TraceLine.JADE)` writes the FIPA act name in field 6 — `REQUEST`,
`INFORM`, `CFP`, `PROPOSE`, `ACCEPT-PROPOSAL`, `QUERY-REF`, hyphenated as JADE spells them.
`TraceLine.render(..., TraceLine.NONE)` writes the baseline's `-`. **#21's normalizer erases
field 6 before comparing, so the two renderings are the same line after projection and different
before it** — which is what the field was reserved for.
`TraceLineTest.field_six_is_the_only_difference_between_the_two_renderings` asserts exactly that.

## 5. Payload schemas

### 5.1 One immutable record per message type

The baseline sends untyped positional `Serializable[]` with no schema anywhere. Each type gets a
`record` in `cz.vutbr.fit.ags.railway.domain.msg`, listed in §3, carrying the same slots in the
same order.

**Nothing validates anything.** No null checks, no range checks, no normalisation. A negative
`expected`, a `null` `position`, a `road` nobody reads — those are behaviour to reproduce, not
input to sanitise.

### 5.2 The `ENTER` asymmetry — the reason the ontology exists

`Train.requestEnterToObject` always sends one shape:

```java
Activity.sendAll(StaticRailwayObject.ENTER + object,
        new Serializable[]{getName(), position, to});
```

and the two receivers read **different subsets** of it:

| receiver | reads | as | ignores |
|---|---|---|---|
| `Station.enter` | `message[0]`, `message[2]` | the train, and where it is ultimately going | `position` |
| `RoadAgent.enter` | `message[0]`, `message[1]` | the train, and which neighbour it arrives from | `target` |

Nothing in the baseline states this; the two index sets are three files apart, and swapping them
misroutes silently — a station would send the train back the way it came, a track would compute
the wrong direction of travel.

**The port sends one `EnterRequest` to both, and each receiver names its own fields:**

```java
EnterRequest e = Messages.contentOf(acl);
e.train();          // both
e.endStation();     // Station reads this   (== target,   baseline slot 2)
e.arrivingFrom();   // RoadAgent reads this (== position, baseline slot 1)
```

Same three slots on the wire in both directions — the probe records all three and a golden holds
them — and the confusion becomes a compile error rather than an index typo.
`EnterRequestAsymmetryTest` is six tests on exactly this.

### 5.3 Fields nobody reads, carried anyway

**CH-07 `TRAVEL_END`'s `road`.** `Train.travelEnd` never touches `ev.getMessage()`; it goes
straight to `nextPosition`. The field is carried because the probe records it and the golden
holds `road=tr1` — dropping it would change the trace even though it cannot change the
simulation.

### 5.4 Nulls that mean something

| where | value | meaning |
|---|---|---|
| CH-03 `position` | `null` on a train's first `ENTER` | `Train.position` is still unset when `start` calls `requestEnterToObject`. Correct, not a fault. The first `ENTER` always goes to a station, which does not read that slot. |
| CH-06 `next` | `null` on the last `ENTER_REPLY` | how a train is told it has arrived; it calls `Agent.die()`. |

Both render as the four characters `null` and parse back to a `null` slot. No agent in this
system is ever named `null`, so the ambiguity is unreachable.

### 5.5 `Station.Info` — pinned as a value snapshot

`cybele.prop` sets `cybele.srv.comm.app.param.iai = Local;NoSerialization`, so **a payload crosses
a channel by reference** (`docs/INVENTORY.md` SEM-05). The *same* `Station.Info` instance is
shipped on every `sendInfo()` and then mutated in place by `Station.enter` (`occupied++`) and
`Station.leave` (`occupied--`). What `Main` holds is a live alias, not a message. It is also a
**non-static inner class**, so under real serialization it would drag its enclosing `Station` —
queue, timetable, subscriber collection — onto the wire with it.

**#27 pins the wire form as an immutable value snapshot: the `StationInfo` record.** Two ints, no
enclosing instance, no mutation after send. Consequences, in order:

1. **The observable this removes was never contractual.** `STATION_INFO`'s `occupied` is *not a
   determinable value* even within one binary at one seed: measured across three probe-on runs of
   `short-bounded`, the only remaining content differences after projecting every clock-derived
   family were `STATION_INFO` lines whose `occupied` differed, trading in lockstep between
   adjacent values on the same station (`docs/trace-format.md`). It is a race, not a timestamp,
   and #21 must project it whatever the framework.
2. **Snapshot at the same point regardless.** On receipt, before doing anything else — which is
   what `TraceProbe.onStationInfo` already does. That is the difference between a port that
   drifts noisily and one that drifts systematically.
3. **The aliasing quirk itself is DEF-13**, cross-referenced in `docs/defect-triage.md` §4.3 and
   carried by [#22](https://github.com/bedaHovorka/OpenCybele1/issues/22) item 11. This document
   does not reclassify it; it records that the JADE wire form makes it structurally impossible to
   reproduce, which is why the projection is mandatory rather than a convenience.

`AclBindingTest.station_info_is_a_snapshot` asserts the record survives the ACL round trip as a
distinct, equal object, and that it has no enclosing class.

### 5.6 `RoadAgent.State`

Pinned as `RoadDirection` — a framework-free copy of the enum with the same three constant names
(`FREE`, `TRAVEL_LEFT`, `TRAVEL_RIGHT`) and the same GUI symbols (`""`, `"<"`, `">"`). The wire
form is the constant's `name()`, because that is what the trace renders and therefore what a
golden can contain. A copy rather than a reference because `RoadAgent.State` is a nested enum of a
Cybele agent class, invisible to `domain` and to branch `jason`.

Unlike CH-10 this payload was never aliased: an enum constant is immutable, so reference passing
and snapshot passing are the same thing.

### 5.7 `TRAIN_STATE` stays an opaque string

Every value except one is `from + " -> " + to + " : " + message`, and the exception is the
sentinel `KILL`. Modelling it as a structured record would be an improvement, and an improvement
is what a golden-master port must not make — the string is in the trace character for character,
spaces, `>` and `:` included.

The baseline selects the sentinel with `(mess == KILLED)`, reference equality on a `String`
(DEF-11, class (a)). It behaves identically to `.equals` because the only caller passing `KILLED`
passes that interned literal. A port may use `.equals` freely; the trace is byte-identical either
way.

### 5.8 Content encoding — `setContentObject`, not `jade.content`

The content is a Java-serialized record, carried by `ACLMessage.setContentObject`. It is **not** a
`jade.content.onto.Ontology` with concept/predicate/agent-action schemas and an SL codec. Two
reasons:

* The baseline's payloads are Java objects passed between agents in one JVM. Java-serialized
  records are the nearest faithful form; an SL string form would be a redesign, and a redesign is
  not measurable against a frozen golden.
* A `jade.content` ontology forces a FIPA-shaped reading of protocols this application does not
  have — see §4.1's three missing legs. The mapping would stop being reversible.

The word "ontology" is used throughout in the plain sense #27 uses it: a fixed vocabulary of
message types with a pinned schema per type. `AclBindingTest.the_payload_round_trips_through_an_acl_message`
covers all fifteen through a real `ACLMessage`.

## 6. Topic granularity — **per channel constant**, decided

**Decision: fifteen topics, one per channel *constant*, named `railway.<EVENT>`, minted at boot
and never again.** `RailwayOntology.topicName(Channel)`.

Topics exist here for **one** reason: #20 requires the probe to observe all fifteen channels
without editing agent logic, and JADE's answer to Cybele's "a second handler can attach to a
channel" is `jade.core.messaging.TopicManagementHelper`. **They are not used for delivery.**
Delivery is §2's unicast, on all fifteen.

### How the probe sees a message it is not addressed to

`Messages.build(message, from, to, topic)` adds the channel's topic AID as a **second receiver**
alongside the named agent. The named agent still receives exactly one copy; every agent registered
to that topic receives one as well. Passing `null` for the topic — which a port does whenever
tracing is off — leaves the `:receiver` set byte-identical to what it would be if the probe did
not exist, which is the same "additive, and off by default" property the Cybele `TraceProbe` has.

This is proved on a real platform, not asserted: `JadeDeliverySpikeTest` boots a main container
with the service active, sends through it, and checks that the named agent gets the message, that
a topic-registered probe that was never addressed gets a copy with the ontology slot and content
record intact, and that with the topic omitted the probe sees nothing.

### Why per constant

The probe needs to attribute every line to a **subject** (trace field 1) and to name field 5's
`to`. Per §3, the subject comes from one of three places — the payload (8 channels), the sender
AID (3), the receiver AID (4) — and the target is the named receiver AID. **All four are already
on the `ACLMessage`.** A topic named after the channel *instance* would encode, in the topic name,
exactly one thing the message already carries. It buys the probe nothing.

Against that, the instance count:

* 8 stations × 4 channels (`ENTER`, `LEAVE`, `VOTE_REQUEST`, `VOTE_RESULT`) = **32**
* 7 tracks × 5 (the same four plus `TRAVEL_START`) = **35**
* `STATION.INFO.<st>` per station and `ROAD.STATE.<tr>` per track = **15**
* `PATH_FIND_REPLY.<st>` per station = **8**
* the three unsuffixed channels (`PATH_FIND.`, `PLAN_TRAIN`, `VOTE`) = **3**

**93 at boot** on the default topology — the same 93 `docs/trace-format.md` counts as
`4×15 + 7 + 8 + 8 + 7 + 3` — **and four more per train, forever**: `START.<train>`,
`ENTER_REPLY.<train>` and `TRAVEL_END.<train>`, which the train opens, plus
`TRAIN.STATE.<train>`, which the generator opens for the hub. (#27's body says three, counting
only the train's own.) None is ever destroyed — the JADE mirror of the per-train channel leak,
DEF-10 / [#22](https://github.com/bedaHovorka/OpenCybele1/issues/22) item 6. It also revives a
problem the Cybele probe had to solve with a hack: `TraceProbe` pre-subscribes
`sim.trace.trainLookahead = 32` train-name slots ahead of the generator because
`START.<train>`, `ENTER_REPLY.<train>`, `TRAVEL_END.<train>` and `TRAIN.STATE.<train>` are named
after trains that do not exist yet. **With per-constant topics that lookahead is unnecessary** —
those four are one topic each, whatever the train is called. Removing a bounded guess from the
probe is worth more than any per-instance filtering would have been.

### The cost of the option rejected — stated, not waved away

Per-instance topics would have given a subscriber the ability to select traffic *for one object*
by subscribing rather than by filtering. Concretely, giving that up costs:

1. **A consumer that wants only `ENTER.stA` must filter in code.** One `MessageTemplate.and(...)`
   with `MatchReceiver`, or one `if` in the probe. Today nothing wants this: #36's probe wants all
   fifteen channels, and no agent registers to any topic at all.
2. **Every topic-registered observer sees every instance.** With one probe that is the desired
   behaviour. With many observers, each filtering the same firehose, it would eventually be a
   throughput argument — at this application's message rate it is not.
3. **JADE's wildcard does exist** — `TopicManagementHelper.TOPIC_TEMPLATE_WILDCARD` is `"*"`, and
   `TopicTable` matches registrations as a template tree, so a probe *could* register
   `ENTER.*` and cover an unbounded instance family in one call. That weakens objection (1)
   against per-instance topics but not the count: the **platform** still mints and tracks a topic
   AID per instance, and the growth is in the platform's tables, not in the probe's registration
   list. The unbounded half of the cost survives the wildcard; the inconvenient half does not.

Both remaining costs are cheap and neither is on the critical path. The growth the other option
carries is unbounded and mirrors a known defect.

`AclBindingTest.topics_are_per_channel_constant` asserts fifteen distinct names, none containing
an agent name; `JadeDeliverySpikeTest.one_topic_per_constant_covers_every_instance` shows one
registration observing `ENTER.stA` and `ENTER.stB`.

## 7. Template disjointness — the JADE-only hang, and the proof it cannot happen

**Why this is a design obligation and not a detail.** A JADE agent draws from **one shared message
queue**. `receive(template)` consumes only matching messages, and a message matching *no* active
template sits in the queue forever. `Behaviour.block()` blocks the **whole agent** until any
message arrives, not just that behaviour. The Cybele baseline structurally cannot have this
failure — every channel there has its own handler method — so it is a class of bug the port
introduces from nothing.

**The template is `and(MatchOntology(channel.id()), MatchPerformative(...))`.**

**Disjointness is structural.** An `ACLMessage` has exactly one `:ontology` slot and the fifteen
ids are distinct strings, so `MatchOntology(a)` and `MatchOntology(b)` cannot both hold for
`a ≠ b`. `TemplateDisjointnessTest.the_fifteen_templates_are_pairwise_disjoint` checks all 225
cells against the real `MessageTemplate` implementation.

**Performative alone would not be enough**, and the collisions all land inside a single agent:

| agent | inbound channels | performatives | collision |
|---|---|---|---|
| `Station` | `VOTE_REQUEST`, `VOTE_RESULT`, `ENTER`, `LEAVE`, `PATH_FIND_REPLY` | cfp, accept-proposal, request, **inform, inform** | `LEAVE` vs `PATH_FIND_REPLY` |
| `RoadAgent` | `VOTE_REQUEST`, `VOTE_RESULT`, `ENTER`, `LEAVE`, `TRAVEL_START` | cfp, accept-proposal, **request**, inform, **request** | `ENTER` vs `TRAVEL_START` |
| `Train` | `START`, `ENTER_REPLY`, `TRAVEL_END` | request, **inform, inform** | `ENTER_REPLY` vs `TRAVEL_END` |
| `Main` | `STATION_INFO`, `ROAD_STATE`, `TRAIN_STATE`, `PLAN_TRAIN`, `VOTE`, `PATH_FIND` | **inform ×3**, request, propose, query-ref | three-way |

The `:ontology` slot resolves all four. Why that slot and not another: `:protocol` would collide
with FIPA interaction-protocol names and with JADE's protocol behaviours (and §4.1 forbids setting
it); `:conversation-id` is per conversation, not per type, and is reserved for the subject (§9);
a user-defined slot has no first-class `MessageTemplate` matcher.

**Jointly exhaustive.** The four inbound sets above cover all fifteen channels
(`ChannelTableTest.inbound_sets_are_what_each_agent_must_consume`,
`TemplateDisjointnessTest.every_channel_is_consumed_by_some_agent`). `Channel.inboundFor(Party)`
is the generator for them, so a port cannot forget one by hand.

**The drain.** `Templates.unexpected(Party)` is `not(inbound(self))` — the exact complement. Every
agent should run a lowest-priority `CyclicBehaviour` on it that reports what it caught. With a
correct port it never fires; when it does, it names the bug immediately instead of presenting it
as a hang. This is what #38's "the queue drains" assertion is checkable against, and it is why the
performative is kept in the template even though the ontology slot alone would be disjoint: a
message with the right channel and the wrong act falls through to the drain rather than being
quietly accepted (`TemplateDisjointnessTest.a_tampered_performative_is_caught_rather_than_accepted`).

## 8. Container profile — activating the topic service

```java
Profile profile = new ProfileImpl();
profile.setParameter(Profile.SERVICES, "jade.core.messaging.TopicManagementService");
```

`TopicManagementHelper.SERVICE_NAME` is `"jade.core.messaging.TopicManagement"`; the service class
is that plus `Service`. It must be active on **every** container an agent that sends or observes
runs on. Without it, `getHelper(TopicManagementHelper.SERVICE_NAME)` throws `ServiceException` and
every topic is silently a dead letter — which would show up as a probe that emits nothing rather
than as a boot failure. Available since JADE 3.5 (Programmer's Guide §3.3.5); verified present in
`net.sf.ingenias:jade:4.3`.

The spike also sets, and a port should consider:

| parameter | value | why |
|---|---|---|
| `Profile.MTPS` | `""` | removes the **external** message transport (the HTTP MTP). |
| `Profile.MAIN_PORT` | `"0"` | makes the intra-platform IMTP port **ephemeral**, so a build machine never has a fixed port to free. |
| `"file-dir"` | a scratch directory, with a trailing separator | **JADE's AMS writes `APDescription.txt` into `getProperty("file-dir", "")` on startup** — i.e. the process working directory unless told otherwise. A golden-recording run must not drop a file into the directory it was launched from; a build must not leave an untracked one in the repo. |

> **Neither of the first two makes a JADE platform hermetic, and a port must not assume they
> do.** The JICP **IMTP** listener still binds even with no MTP: measured on the spike's own
> boot, `Listening for intra-platform commands on address: - jicp://172.17.0.1:38419` — a TCP
> listener on a routable interface. `MAIN_PORT="0"` makes the port ephemeral, not absent. Two
> consequences worth writing down before #36 inherits them: the spike is **not** hermetic, so a
> locked-down CI box fails it at *boot* rather than at an assertion; and a JADE golden-recording
> run opens a socket where the Cybele one does not, which is a difference in the *environment* a
> run needs, not in the trace it produces.

The `file-dir` row is not a convenience — see the note under it. A headless golden-recording run has the same requirement as the
Cybele one (`docs/headless-and-stop.md`) and should keep them.

## 9. Conversation ids, `reply-with`, and what must not leak into the trace

**`:conversation-id` is set to the trace subject** — field 1, the entity the line is about. The
probe then reads it in one call instead of inspecting the payload, and
`AclBindingTest.the_conversation_id_is_the_trace_subject` asserts the two agree on every channel.

Two constraints on it:

1. **It never appears in the trace.** The seven fields are fixed, so it is normalized out by
   construction rather than by a rule #21 has to add.
2. **Agents must never template on it.** It is probe metadata. Making a routing decision from it
   would be a behaviour the baseline does not have — the baseline has no conversation ids at all.

**`:reply-with` / `:in-reply-to` stay unset.** There is no request/reply correlation in the
baseline and a train has at most one outstanding `ENTER`. Setting them would tempt a port into a
blocking receive it does not need, and a blocking receive is how a JADE port acquires a deadlock
the original never had. `:language`, `:encoding` and `:reply-by` likewise stay unset — no content
language, no deadline (§4.1).

## 10. Where the code lives, and why the split falls there

| | `src/domain/java/.../domain/msg` | `src/jade/java/.../railway/jade` |
|---|---|---|
| contents | `Channel`, `Performative`, `Subject`, `Party`, `RailwayMessage` + the 15 records, `RoadDirection`, `Payloads`, `TraceLine` | `RailwayOntology`, `Messages`, `Templates` |
| imports | JDK only | `jade.core`, `jade.lang.acl`, `jade.core.messaging` |
| enforced by | the `domainPurity` Gradle task — `jade.` is a forbidden import prefix | its own source set, which cannot see `main` |
| reused by #46 | **yes, verbatim** | no — branch `jason` replaces it |

**The split is "does it need `jade.lang.acl`".** Everything that does not, does not get it. That
puts the performative *decision* in `domain` as a `String` FIPA act name and leaves only the
`ACLMessage.getInteger(...)` lookup on the JADE side, so the two cannot drift and branch `jason`
inherits the assignment rather than re-deciding it. `Channel` carries the trace event token, the
subject rule, the payload keys and the endpoint kinds for the same reason: #43's Jason probe needs
every one of them and must not import a JADE class to get them.

**Why `jade` is a source set and not part of `main`.** `main` is the Cybele application. This
branch's parity gate runs it, its goldens are frozen, and `./gradlew run`/`installDist` must keep
launching it with `--patch-module java.base=cybelle` and nothing else on the classpath. Putting
the JADE jar on `implementation` would drop `jade-4.3.jar` into `build/install/opencybele/lib` and
onto the start script's classpath — a change to the thing under measurement, for no benefit,
since the Cybele agents deliberately do not use this ontology yet. JADE is on the **test**
classpath as well, because a disjointness proof against a hand-rolled stub would only prove the
stub is disjoint.

The `jade` source set has no dependency on `main` and must never import
`cz.vutbr.fit.ags.xhovor07`.

## 11. What this leaves to #30–#34

* **Wiring.** No Cybele agent is rewired here. Each of #30–#34 takes one agent, replaces its
  channels with the templates and builders above, and runs the parity gate.
* **Behaviour shapes.** `TickerBehaviour` vs a self-rearming `WakerBehaviour` (this codebase has
  zero repeating timers — see `docs/CYBELLE_TO_JADE.md`), the `CountDownLatch` in `Planning`, the
  `wait()`/`notify()` in `Station.getPathDirection`. All out of scope here; all constrained by
  §4.1's "no protocol behaviours".
* **The drain behaviour.** §7 specifies the template; each agent has to register it.
* **`Main`'s internal split.** `Generator`, `Planning` and `VoteCollecting` are activities of one
  Cybele agent. Whether they become behaviours of one JADE agent or agents of their own is #34's
  call — **but if they become agents of their own, five channel families have to be told to keep
  writing `Main`, not one.** `PLAN_TRAIN` in trace fields 4 *and* 5; `VOTE_REQUEST`,
  `VOTE_RESULT` and `START` in field 4; `VOTE` in field 5. The table in §3 is the list. Getting
  this wrong does not fail a build — it produces a golden diff on every election in the run,
  which is the most expensive way to discover it.

## 12. Corrections and cross-references to other documents

| document | status |
|---|---|
| `docs/CYBELLE_TO_JADE.md` "Directory / community" row | **already corrected** (DF half does not apply, AMS half stands). This document is the detail behind it; the row now links here. |
| `docs/CYBELLE_TO_JADE.md` "Cybele message → `ACLMessage` + performative" row | now links here for the actual mapping. |
| `docs/MIGRATION.md` §"Directory / naming / community service" | already says this repo needs no directory. Unchanged. |
| `docs/MIGRATION.md` §3.1 (Jason performatives) | its "the full channel→performative ontology is owned by #27" pointer now resolves to this file. |
| `docs/trace-format.md` "Why `performative` is empty" | its instruction — *do not put a guessed mapping here, #27 owns it* — is discharged by §4. The trace format itself is unchanged. |
| `docs/defect-triage.md` DEF-13 / §4.3 | §5.5 records that the JADE wire form makes the aliasing structurally unreproducible, which is why projecting `occupied` is mandatory. No reclassification. |
| `docs/INVENTORY.md` §4 | the authority for the fifteen channels. Not re-counted here. |
