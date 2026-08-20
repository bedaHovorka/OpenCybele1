# The canonical parity trace — format, field semantics, and what it is a contract for

> Produced for [#20](https://github.com/bedaHovorka/OpenCybele1/issues/20) (*Probe agent
> emitting the canonical parity trace*), on `opencybele-baseline`. Consumed by
> [#21](https://github.com/bedaHovorka/OpenCybele1/issues/21) (the normalizer),
> [#23](https://github.com/bedaHovorka/OpenCybele1/issues/23) (scenario coverage) and
> [#24](https://github.com/bedaHovorka/OpenCybele1/issues/24) (recording the goldens), and
> **re-implemented** by [#36](https://github.com/bedaHovorka/OpenCybele1/issues/36) (a JADE
> agent) and [#43](https://github.com/bedaHovorka/OpenCybele1/issues/43) (a Jason
> `AgArch`/`Environment`). Those two must produce byte-identical lines for equivalent
> behaviour, so **this document, not `TraceProbe.java`, is the contract.**
>
> **Byte-identical *after* #21's projection, never before it.** Five numeric families in this
> trace derive from a wall-clock-driven simulated clock and differ between two runs of the
> same binary at the same seed; a sixth value, `STATION_INFO`'s `occupied`, is not
> determinable at all. Both are named and measured below, under "Five families that change on
> every run" and "What the payload of CH-10 is and is not". A golden recorded from a raw
> trace matches nothing, including itself.

## Why there is a probe at all

The whole 2 400-line application prints two lines: `"<train> in <station> at <departure>"`
from `Planning`, once per planned train at departure time, and `"<train> started"` from
`Train`. Everything else it does — every vote, every entry, every path lookup, every change
of station occupancy — reaches the Swing canvas through `Observable`/`Observer` and never
touches a stream. A port cannot be shown to preserve behaviour that was never observable,
so `docs/TESTING.md` §3.3's probe is not optional here.

It is **additive**. `TraceProbe` is a new `Handler`; no existing agent knows it exists, no
existing agent's logic changed, and with `sim.trace.enabled=false` — the default — no probe
agent is created, no extra channel is opened and stdout is the two `println`s it always was.

## The line

```
agent|tick|event|from|to|performative|payload
```

Seven `|`-separated fields, one line per observed message, on **stdout**. A real sample,
`vl3` end to end at `scenarios/short-bounded.properties` (abridged — the eleven
`VOTE_REQUEST`/`VOTE`/`VOTE_RESULT` legs of the election are one line per path member).

**This is one run's output, not the format's.** Both the numbers and the line *order* are
run-specific: the tick values move with the wall clock (see "Five families that change on
every run"), and lines that share a tick are emitted in whatever order the kernel delivered
them. Read it as a shape, never as an expected trace.

```
vl3|13016|PLAN_TRAIN|Main|Main|-|train=vl3,from=stA,to=stB
vl3|13024|VOTE_REQUEST|Main|stA|-|train=vl3,expected=13024
vl3|13024|VOTE_REQUEST|Main|tr1|-|train=vl3,expected=13024
vl3|13024|TRAIN_STATE|vl3|Main|-|state=stA -> stB : vl3 generated
vl3|13040|VOTE|stB|Main|-|voter=stB,train=vl3,diff=0
vl3|13048|VOTE|stA|Main|-|voter=stA,train=vl3,diff=0
vl3|13056|VOTE_RESULT|Main|stA|-|train=vl3,planned=13024
vl3|13064|START|Main|vl3|-|station=stA
vl3|13072|ENTER|vl3|stA|-|train=vl3,position=null,target=stB
vl3|13080|ENTER_REPLY|stA|vl3|-|object=stA,next=tr1
vl3|13080|TRAIN_STATE|vl3|Main|-|state=stA -> stB : entered to stA
vl3|13080|ENTER|vl3|tr1|-|train=vl3,position=stA,target=stB
vl3|13080|ENTER_REPLY|tr1|vl3|-|object=tr1,next=stH
vl3|13080|LEAVE|vl3|stA|-|train=vl3
vl3|13080|TRAVEL_START|vl3|tr1|-|train=vl3
vl3|14112|TRAVEL_END|tr1|vl3|-|road=tr1
...
vl3|26576|ENTER_REPLY|stB|vl3|-|object=stB,next=null
vl3|26584|LEAVE|vl3|tr5|-|train=vl3
vl3|26584|TRAIN_STATE|vl3|Main|-|state=stA -> stB : entered to stB
vl3|26584|LEAVE|vl3|stB|-|train=vl3
vl3|26584|TRAIN_STATE|vl3|Main|-|state=KILL
```

Two behaviours the baseline is known to have and which this trace makes visible rather than
smoothing over, both from `docs/INVENTORY.md`: `LEAVE` for the old object arrives **after**
`ENTER_REPLY` from the new one (DEF-07 — occupancy is over-counted for the overlap), and the
final station gets **two** `LEAVE`s, one from `Train.entered` and one from the destructor
(DEF-08). #32's acceptance criterion — *"`LEAVE`-before-`ENTER` ordering preserved"* — is
checkable against these lines and against nothing else in the system.

### The fields

| # | Field | Meaning |
|---|---|---|
| 1 | `agent` | **The entity this line is about**, not the entity that logged it. The train, when the message concerns one; otherwise the station or track. See below. |
| 2 | `tick` | The simulated clock, `Cybele.getTime("myClock")`. Never wall time. |
| 3 | `event` | One of fifteen fixed tokens, one per channel. The full list is the table below. |
| 4 | `from` | The agent that sent the message. |
| 5 | `to` | The agent the message was addressed to — for a suffixed channel `<CONST>.<name>`, that is `<name>`. |
| 6 | `performative` | The literal `-`. See "Why `performative` is empty". |
| 7 | `payload` | `key=value` pairs, comma-separated, one per slot of the `Serializable[]`, in wire order, with the names fixed by the table below. |

#### `agent` — the subject, deliberately

The obvious choice, "the agent that emitted the line", is useless: one probe emits every
line. The next obvious one, "the sender", makes field 1 a copy of field 4 on seven of the
fifteen channels. So field 1 is the **subject**: the entity whose story this line belongs
to.

For **eleven** of the fifteen channels that is a train, so
`entity.pattern: '^(vl\d+)\|'`, group 1, projects the trace per train with no scanning of
the payload. The other **four** — `PATH_FIND`, `PATH_FIND_REPLY`, `STATION_INFO` and
`ROAD_STATE` — concern no train and carry the station or track instead.

**That is necessary and nowhere near sufficient, and the unattributed bucket is not a relief
valve.** Measured by compiling `jade-develop`'s real `TraceComparator` and running it over a
normalized `short-bounded` trace: of 3732 normalized lines, **529 carry no train id** — 228
`STATION_INFO`, 188 `ROAD_STATE`, 21 `PATH_FIND`, 21 `PATH_FIND_REPLY`, 42 application
`println`s, 20 surviving configuration-banner lines and 9 Cybele kernel banner lines. In
`TraceComparator.causal` that bucket is compared with `!Objects.equals(...)` on the **ordered
list**, with no tolerance of any kind — it is the strictest comparison in the harness, and
the 458 station/track lines in it are exactly the race-prone ones, since the eight opening
`STATION_INFO` lines are emitted in a different order every run. The per-entity comparison
is no softer: `!actualLines.equals(...)`, exact, per train. The `entity.tolerance` budget
applies **only to which train ids exist**, never to what any of them did.

So an entity pattern is table stakes. Whether `causal` can pass at all is #21's problem, and
the two things it has to solve are named below: the five run-varying numeric families, and
line order within a shared tick.

The subject of each channel is **fixed by the table below**, not derived from where the port
happens to hook the message. That is what makes byte-identity across three frameworks
achievable: a JADE `Behaviour` logging at send time and a Jason `AgArch` logging at receive
time must both compute field 1 from the channel identity and the payload, and will then
agree.

#### `tick` — simulated, and read at handling time

`Cybele.getTime(RailwayMainAgent.CLOCK_ID)`, so the field survives a move to a framework
with a different scheduler. **It is the clock as the probe reads it when it handles the
message, not when the sender sent it.** Cybele offers no send-time stamp on a message:
`CybeleEvent.getClockTime()` returns `-1` for `getEventType() == MESSAGE` and is populated
only for timer events — measured, `docs/probes/ExpH.java` (H2). The gap is the probe's own
dispatch latency; at the paces these scenarios use it is under one simulated millisecond,
and equal-tick lines are already something #21 has to canonicalise (see "Five families that
change on every run").

**A port must not stamp at send time instead**, even though it can and even though a
send-time stamp is closer to the truth. Handling-time reads from one serial activity make
field 2 **monotonically non-decreasing over the whole trace** — measured, 0 inversions over
3661 lines in each of three runs — which is what lets a normalizer sort or bucket by tick at
all. Send-time stamping from N concurrent agents does not have that property, and would
force #21 to reconstruct an order the trace no longer contains. The gain in fidelity is
sub-tick; the loss is the only ordering invariant this format has.

`-1` in this field means the clock did not exist or did not answer. It is not a legal
simulated time and should be read as a fault.

#### Why `performative` is empty

The baseline has **no performatives**, no conversation ids, no reply-to and no ontology:
the only routing information in the entire system is the channel-name string
(`docs/INVENTORY.md` §4). There is nothing to record.

The field exists anyway, holding the literal `-`, for one reason: JADE (#27, #36) and Jason
(#48) *will* populate it, and #21's normalizer erases field 6 before comparison. Without a
placeholder that is present-but-empty on every branch, either every single line diffs
cross-branch, or the field has to be added later — which is a format change to a recorded
golden. **Do not put a guessed mapping here.** #27 owns the channel → `ACLMessage`
performative mapping, and a value invented in this file would silently become the thing #27
is measured against.

#### `payload` — and what escaping there is

`key=value,key=value`, in the order of the `Serializable[]` slots, with the key names fixed
by the table. A `null` slot renders as the four characters `null`; no agent in this system
is ever named that.

Three characters are percent-escaped inside any field: `|` → `%7C`, LF → `%0A`, CR → `%0D`,
with `%` → `%25` applied first so the transformation is reversible. No value this
application produces contains any of them — the escape exists so that a future payload
cannot corrupt a golden by splitting one line into two. **A port must apply the same
escaping**, or a value that is legal in one framework and not the other becomes a diff.

`,` and `=` are **not** escaped, so field 7 is only splittable when no value contains them.
None does today — every payload value is an agent name, an integer, `null`, or the
`TRAIN_STATE` string, which contains spaces, `>` and `:` but neither `,` nor `=`. A port that
introduces a value containing one of them changes the format and must say so here first;
field 7 is deliberately specified as opaque text with a documented shape, not as a parseable
map, so that a reader is never tempted to round-trip it.

### The fifteen events

`id` is the `docs/INVENTORY.md` channel id. `subject` is field 1.

| id | `event` | Channel | subject (field 1) | `from` | `to` | `payload` |
|---|---|---|---|---|---|---|
| CH-01 | `VOTE_REQUEST` | `VOTE_REQUEST.<obj>` | the train | `Main` | `<obj>` | `train=,expected=` |
| CH-02 | `VOTE_RESULT` | `VOTE_RESULT.<obj>` | the train | `Main` | `<obj>` | `train=,planned=` |
| CH-03 | `ENTER` | `ENTER.<obj>` | the train | the train | `<obj>` | `train=,position=,target=` |
| CH-04 | `LEAVE` | `LEAVE.<obj>` | the train | the train | `<obj>` | `train=` |
| CH-05 | `START` | `START.<train>` | the train | `Main` | the train | `station=` |
| CH-06 | `ENTER_REPLY` | `ENTER_REPLY.<train>` | the train | the admitting object | the train | `object=,next=` |
| CH-07 | `TRAVEL_END` | `TRAVEL_END.<train>` | the train | the track | the train | `road=` |
| CH-08 | `TRAVEL_START` | `TRAVEL_START.<road>` | the train | the train | `<road>` | `train=` |
| CH-09 | `PATH_FIND_REPLY` | `PATH_FIND_REPLY.<st>` | the station | `Main` | `<st>` | `target=,direction=` |
| CH-10 | `STATION_INFO` | `STATION.INFO.<st>` | the station | `<st>` | `Main` | `occupied=,capacity=` |
| CH-11 | `ROAD_STATE` | `ROAD.STATE.<tr>` | the track | `<tr>` | `Main` | `state=` |
| CH-12 | `TRAIN_STATE` | `TRAIN.STATE.<train>` | the train | the train | `Main` | `state=` |
| CH-13 | `PLAN_TRAIN` | `PLAN_TRAIN` | the train | `Main` | `Main` | `train=,from=,to=` |
| CH-14 | `VOTE` | `VOTE` | the train | the voter | `Main` | `voter=,train=,diff=` |
| CH-15 | `PATH_FIND` | `PATH_FIND.` | the asking station | the asking station | `Main` | `from=,to=` |

Notes that are easy to get wrong on a port:

* **`PLAN_TRAIN` is `Main` → `Main`.** It crosses two *activities* of the same agent —
  `Generator` to `Planning` (`docs/INVENTORY.md` ACT-02, ACT-01) — not two agents. Field 4
  and field 5 name agents, so both are `Main`. A framework that gives those two their own
  agent identity must still write `Main` here, or every `PLAN_TRAIN` line diffs.
* **`VOTE` is many-to-one** — all fifteen static objects send on the one bare channel — and
  so is `PATH_FIND`, from all eight stations. `from` is the sender read out of the payload
  (`voter`, `from`), which is the only place the sender's identity exists.
* **`ENTER`'s payload is read asymmetrically by its two receivers.** `Train` always sends
  `{train, position, target}`; a `Station` reads slots 0 and 2, a `RoadAgent` reads slots 0
  and 1 (`docs/INVENTORY.md`, "Asymmetric payload read"). All three slots are recorded, so
  the asymmetry stays visible to #27 rather than being resolved here.
* **`position=null`** on a train's first `ENTER` is correct, not a fault: `Train.position`
  is still unset when `start` calls `requestEnterToObject`.
* **`next=null`** on the last `ENTER_REPLY` is how a train is told it has arrived.
* **`state=KILL`** is `TRAIN_STATE`'s sentinel, sent from `Train`'s Cybele destructor. Every
  other `TRAIN_STATE` value is the human-readable string the GUI table shows, recorded
  verbatim.

## Five families that change on every run

**This trace is not reproducible against itself, and a golden recorded from it verbatim
cannot match at any contract level.** Measured with `jade-develop`'s real `TraceComparator`
over two probe-on runs of `short-bounded` — same pinned seed, same machine, same session,
back to back, 3732 normalized lines each:

```
== STRICT  match=false   (differs at line 30)
== CAUSAL  match=false   failures=67
```

Nothing here is a defect in the probe. The values come from a clock that advances with the
wall clock, and the same simulated event lands a few milliseconds either side of a tick
boundary from one run to the next. **#21 must project all five families before comparing.**

| # | Where | What varies |
|---|---|---|
| 1 | field 2, `tick`, on **every** line | the simulated clock at handling time |
| 2 | `VOTE_REQUEST` payload, `expected=` | `Cybele.getTime` at the moment the election opened, plus the running road-delay estimate |
| 3 | `VOTE_RESULT` payload, `planned=` | the agreed departure instant, derived from the same reading |
| 4 | `VOTE` payload, `diff=` | the delay a voter asks for, computed against its timetable in absolute simulated time |
| 5 | **not the probe's** — the application's own `"<train> in <station> at <n>"` `println` | the departure instant, same origin |

Family 5 is on the list because it is in the captured stream and will be in the golden. It
predates this issue and is `Planning.java`'s `TrainPlan.toString()`; the other `println`,
`"<train> started"`, carries no number and is stable.

Projecting all five takes the positional line differences between two runs from **3601 down
to 1185** — an order of magnitude, and not to zero. What remains is two things, both #21's:

* **Order within a shared tick.** After projection, 57–63 of the 66 per-train projections
  still differ across a run pair, purely by line order. Equal-tick lines are emitted in
  whatever order the kernel delivered them.
* **`occupied` on `STATION_INFO`**, which is not a determinable value at all — see the next
  section.

Two things that are *not* on the list, and are worth knowing because they might look like
candidates: field 1, 3, 4, 5 and 6 never vary, and the `ENTER`/`ENTER_REPLY`/`LEAVE`/
`TRAVEL_*`/`PATH_FIND*`/`PLAN_TRAIN` payloads are all agent names, so they are stable.

### What the payload of CH-10 is and is not

The kernel runs `Local;NoSerialization` (`cybelle/cybele.prop`), so **a payload crosses a
channel by reference** (`docs/INVENTORY.md` SEM-05). `Station.Info` is a *non-static inner
class* of the `Station` that keeps mutating it: `Station.enter` increments `occupied`,
`Station.leave` decrements it, both on the station's own thread, both after the object has
already been shipped. What arrives at a subscriber is a live alias, not a message.

`TraceProbe.onStationInfo` therefore reads both fields into `int` locals as its **first two
statements** and formats from those. Formatting lazily — inside the string concatenation,
after doing anything else first — would record whatever value the station had reached by
then, which is strictly worse.

**But do not read that as "snapshot at the same point and the traces will agree", because
they will not.** `occupied` is not a determinable field. Measured across three probe-on runs
of `short-bounded` at one pinned seed, after projecting every clock-derived family away: the
only remaining content differences are `STATION_INFO` lines whose `occupied` differs — 10, 12
and 6 lines across the three pairings — and the counts trade in lockstep between adjacent
values on the same station:

```
stA|<T>|STATION_INFO|stA|Main|-|occupied=0,capacity=6   seen 14, 13, 13 times
stA|<T>|STATION_INFO|stA|Main|-|occupied=1,capacity=6   seen 11, 12, 12 times
stG|<T>|STATION_INFO|stG|Main|-|occupied=0,capacity=3   seen 13, 11, 10 times
stG|<T>|STATION_INFO|stG|Main|-|occupied=1,capacity=3   seen 10, 11, 12 times
```

Same binary, same seed, same machine: the same physical send is recorded with a different
occupancy depending on whether the station's own thread got to its `occupied++` first. No
capture point removes that — the value is a race, not a timestamp.

So the instruction to #27/#33 is, in order:

1. **Project `occupied`, or keep busy stations out of a strict contract.** It is a sixth
   run-varying family in everything but origin, and #21 should treat it as one. A scenario
   that must compare station occupancy exactly has to be quiet enough that no station is ever
   entered and left inside one dispatch.
2. **Then** snapshot at the same point — on receipt, before anything else. This is secondary
   and it is still worth doing: it keeps a port from drifting *systematically* rather than
   just noisily, and it is the difference between a value that is occasionally one out and
   one that is reliably wrong.

One residual is *not* explained by this and is recorded rather than hand-waved: `ROAD_STATE`
on `tr6` also varied, by 2 lines in two of the three pairings (`FREE` 13/14/13 against
`TRAVEL_LEFT` 9/8/9, total constant). Its payload is an immutable enum, so aliasing cannot be
the cause; the likeliest explanation is ordinary behavioural drift downstream of the
departure nondeterminism described under "Does it change the simulation?". It is small,
it is on one track, and #23 should expect station and track state lines to be the least
pinnable content in the trace.

For completeness on the aliasing question itself: `ROAD_STATE`'s payload is an enum constant
and carries no such hazard, the `RoadAgent` state that *is* mutable — its priority queue, its
timetable, the train in transit — never crosses a channel, `Station.QueueItem` and
`RoadAgent.OueueItem` are likewise internal, and every other payload in the system is a
`String` or a `Long`. `Station.Info` is the only aliased payload there is.

## Prefix, and living with the harness

The parity harness separates diagnostics from trace by **line prefix**, and its
`DiagnosticFilter` drops `--- `, `---`, `!!! ` and any line starting with two spaces
(`docs/parity-harness.md`). Trace lines begin with a station, track or train name — `stA`,
`tr1`, `vl3` — so they are never stripped, and no diagnostic can be mistaken for one.
(`Main` appears in fields 4 and 5 but never in field 1: no channel has the main agent as its
subject.)

Measured against the real `DiagnosticFilter` on a captured `short-bounded` run of 3769 raw
lines: **3661 of 3661 canonical lines kept**, and **all 37 lines carrying a declared prefix
stripped**, leaving 3732. The claim is exactly "every *prefixed* diagnostic is stripped" and
no more than that: 29 further lines are diagnostics by intent but carry no declared prefix
and survive — see "What else survives the filter" below.

Collision with the application's own two `println`s is impossible in both directions: the
canonical line's second field boundary is always `|`, and the liveness patterns
`^vl\d+ started$` and `^(vl\d+) in st[A-H] at \d+$` are anchored. A pattern that matches a
canonical line matches nothing else.

### What else survives the filter

The normalized trace is **not** only canonical lines plus the two `println` families. Three
other groups reach it, and #21 or #13 has to decide about each. Counted on one
`short-bounded` run, 3732 normalized lines:

| Lines | What | Deterministic? |
|---|---|---|
| 3661 | canonical trace | see "Five families that change on every run" |
| 42 | the application's two `println` families | `started` yes; `in … at <n>` no, family 5 |
| 9 | **Cybele kernel banner on stdout** — `Cybele version 1.2 starting ...`, seven `*** Loading … service …` lines, `... Cybele started` | yes, byte-identical every run |
| 20 | **`ScenarioConfig.describe()`'s body** — `sim.* = value`, one per key | yes, given a pinned seed |

The kernel banner is harmless: it is fixed text emitted before anything else. The
configuration banner is not stripped because `describe()` writes its body **unindented**,
unlike every other banner in the codebase — that predates this issue (#18 wrote the banner,
#12 chose the prefixes) and is #21's or #13's to settle, either by giving it the two-space
body its neighbours have or by declaring the prefix in the adapter. **This issue widens it by
two lines** (`sim.trace.enabled`, `sim.trace.trainLookahead`); both are deterministic, so no
new nondeterminism enters the trace, but #13 now has a 20-line banner rather than an 18-line
one. Note also that with `sim.random.masterSeed` unpinned the banner prints a drawn number
and that line alone makes every run differ — a scenario must pin the seed, which
`docs/parity-harness.md` already requires for other reasons.

One more consequence worth writing down: **the trace and the two `println`s share stdout,
interleaved.** That is deliberate — the `println`s are the only record of the *departure*
decision and stay part of the golden. `PrintStream.println(String)` is atomic per line, so
lines never interleave *within* themselves.

## Enabling it

```properties
sim.trace.enabled = false      # default: no probe agent, no extra channels, stdout unchanged
sim.trace.trainLookahead = 32  # train-name slots kept subscribed ahead of the generator, 1..10000
```

Both are ordinary `sim.*` keys: resolved and validated in `Main.main` **before**
`Cybele.startUp()`, rejected if malformed, and printed in the resolved-configuration banner
like every other key (`docs/scenario-config.md`). A typo is an error, not a silent default.

```bash
OPENCYBELE_OPTS="-Djava.awt.headless=true \
  -Dsim.config=scenarios/short-bounded.properties \
  -Dsim.trace.enabled=true" build/install/opencybele/bin/opencybele
```

`./gradlew run` with no flags creates no probe and is byte-for-byte the run it was before.

### How the probe subscribes, and why the order matters

`Main` creates the probe agent **before** `RailwayMainAgent` and then blocks in
`TraceProbe.awaitReady()` until it has opened every static channel. Both halves are load
bearing. `Cybele.createAgent` is asynchronous, and `RailwayMainAgent`'s constructor creates
the stations, each of which sends its first `STATION.INFO` **from its own constructor** — so
without the barrier the opening lines of the run are a coin flip. With it, no message of the
run can predate the trace.

Ninety-three channel names can be computed from `ScenarioConfig` alone
(`4×15 + 7 + 8 + 8 + 7 + 3`, matching `docs/INVENTORY.md`'s count). Four cannot:
`START.<train>`, `ENTER_REPLY.<train>`, `TRAVEL_END.<train>` and `TRAIN.STATE.<train>` are
named after trains that do not exist yet.

Subscribing to those **on demand is impossible**, not merely awkward: `Train`'s constructor
opens its own channels and immediately sends `TRAIN.STATE.<train>`, so by the time any
message announces the train, its first message is already gone. The probe instead exploits
the fact that train names are not arbitrary — `Generator` names them `"vl" + index` with
`index` starting at 0 and incrementing by one per train — and keeps a **sliding look-ahead
window** of `sim.trace.trainLookahead` name slots pre-opened, extending it to `index +
lookahead` every time it observes a train on `PLAN_TRAIN` or `TRAIN_STATE`. It is therefore
permanently ahead of the generator; measured on `short-bounded`, all 66 generated trains
have their `generated` line, 66/66.

Two kernel facts make that legal, both measured in `docs/probes/ExpH.java`:

* **H1** — a subscriber that opens a channel *before* another agent opens the same name
  still receives; the later `openChannel` does not displace it. (SEM-03 established that two
  subscribers work; H1 establishes that the *order* does not matter.)
* **H3** — opening a channel name that nothing ever sends on is inert. Most of the window is
  always for trains that a bounded run never generates.

A window narrower than the probe's own lag would lose lines, so it is checked — but **not**
by asking whether a train's index is past the window top. That was the first version's
check and it could not fire: the same method extends the window to `index + lookahead`,
redefining the top relative to the index just seen, so for monotonic names the condition is
false by construction. It read as a guard and asserted nothing.

The two checks that do work, both in `TraceProbe`:

* **The first `TRAIN_STATE` of a train must be its `generated` line.** `Train`'s constructor
  sends that before the train can do anything else, so a first line reading `entered to …`
  or `KILL` means the subscription was late and the opening of that train's story is gone.
* **Every train announced on `PLAN_TRAIN` must produce a `TRAIN_STATE`**, within a grace of
  eight later trains. `PLAN_TRAIN` is a bare global channel opened in the probe's
  constructor and can never be missed, so it is the reliable census; the per-train channels
  are the ones that can be. The grace covers the race between the generator's `PLAN_TRAIN`
  and the train constructor's own first message, which run on different threads.

Both were exercised by blocking stdout until the probe's activity fell behind the generator:
with `sim.trace.trainLookahead=1` the run reported three gaps naming `vl21`, `vl22` and the
handler fault that followed. Three ordinary runs report none, and the aggregate is
66 announced / 66 generated in each.

That test also found a real defect in the reporter, now fixed: `report` used to call
`System.out.flush()` first, so with stdout blocked — the very condition that makes the probe
fall behind — the report deadlocked inside itself and only its header reached stderr. Nothing
that reports a fault may depend on the stream that might be the fault.

The window is bounded at both ends by configuration, 1 to 10 000. It used to be clamped to
`Integer.MAX_VALUE`, which accepted `100000000` and turned it into 400 million
`openChannel` calls inside an agent constructor: a hang, not the error this document
promised.

## Checking a trace

"All fifteen channels appear, and the round-trips are matched pairs" is the acceptance
criterion this issue is judged on, and it is not something reading the probe's source can
settle. `tools/java/TraceCheck.java` settles it mechanically. It lives in the `tools` source
set next to `SeedInterleavingCheck` and is never on the application's classpath.

```bash
./gradlew installDist
OPENCYBELE_OPTS="-Djava.awt.headless=true \
  -Dsim.config=scenarios/short-bounded.properties \
  -Dsim.trace.enabled=true" build/install/opencybele/bin/opencybele > trace.txt
java -cp build/classes/java/tools TraceCheck trace.txt
```

Exit 0 is PASS, 1 is FAIL. Five sections:

1. all fifteen event tokens present;
2. the aggregate round-trip relations;
3. **completeness** — structural, and the section that actually does the work;
4. per-train `ENTER` → `ENTER_REPLY` → `LEAVE` ordering, which is #32's criterion;
5. format invariants — seven fields, numeric tick, `performative` always `-`, no unknown
   event token.

Two of the round-trip relations are deliberately inequalities, and both are behaviour rather
than slack. `ENTER ≤ ENTER_REPLY`: a full station enqueues the train and admits it later from
`Station.leave`, which sends a reply with no request in front of it — 202/202 at
`short-bounded`'s density where nothing queues, 137/137 at `short.properties`' density with
real queuing in between. `TRAVEL_START ≥ TRAVEL_END`: trains still on a track when the bound
fires never finish. `LEAVE ≤ ENTER_REPLY` covers the third leg of the issue's chain, which
nothing else did.

### Why section 3 exists — the counts are a lock that cannot fail

The first version of this checker was the aggregate relations and nothing else, and every one
of them is blind to the failure it was written to catch: an incomplete trace loses lines from
**both** sides of a relation, so the relation still holds. Measured on a real 3661-line
trace, by mutation:

| Mutation | Aggregate relations | With section 3 |
|---|---|---|
| drop every line of 20 trains (3661 → 3062) | **PASS** | FAIL — ids missing from the `PLAN_TRAIN` sequence |
| drop the 15 opening `STATION_INFO`/`ROAD_STATE` lines, i.e. the subscription barrier failing | **PASS** | FAIL — first state line for all 15 objects is not the initial one |
| drop `START`/`ENTER_REPLY`/`TRAVEL_END`/`TRAIN_STATE` for 6 trains, i.e. the look-ahead failure | **PASS** | FAIL — 6 announced but never `generated` |
| a genuinely truncated trace from a stalled probe | **PASS** | FAIL — 9 announced but never `generated` |

Section 3 is structural rather than aggregate, and each check is chosen because a balanced
count cannot satisfy it:

* **Train ids are contiguous from `vl0`.** `Generator` names trains `"vl" + index` with no
  gaps, so a lost train is a hole in the sequence — the one thing a balanced count cannot
  hide.
* **Every announced train has a `generated` line.** `PLAN_TRAIN` is the census that cannot be
  missed; the per-train channels are the ones that can.
* **Every static object's first state line is its initial state** — `occupied=0` for a
  station, `state=FREE` for a track, both sent from the constructor before any train exists.
  A first line saying anything else means the opening of the run is missing, which is exactly
  what a failed subscription barrier looks like.

A port's own trace can be checked with the same tool, and should be: these are properties of
the *simulation*, not of Cybele.

## When the probe itself breaks

A throwable inside a Cybele handler is wrapped in an `InvocationTargetException`, printed to
stderr and swallowed, **with the exit status unchanged** (`docs/assertion-triage.md`). A
probe that died that way would leave a truncated trace that looks exactly like a short,
clean run — and, in record mode, would be frozen into a golden.

So every handler catches `Throwable`, and reports it on stderr as:

```
!!! PROBE FAILURE (1): the trace probe failed while handling STATION_INFO on channel '…'. …
Exception in thread "…" java.lang.ClassCastException: …
	at …
```

The `!!! ` line is a diagnostic and the harness drops it before comparing, which is right for
a human-readable explanation. The stack trace under it carries the JVM's own
uncaught-exception header, `Exception in thread "`, which is one of the signatures the
harness scans the **raw** stream for before it normalizes anything
(`docs/parity-harness.md` §4). The combination is deliberate: the explanation stays readable,
and **a run whose probe failed can never be recorded as a golden or pass a scenario**, even
though its exit status is 0. Reports are capped at 20 so a systematic fault cannot bury the
trace it broke.

Rethrowing instead would also be caught by the scan — Cybele's own handler prints a scanned
banner — but it would lose the explanation and leave the reader to work out that the probe,
and not the simulation, is what broke.

**A trace gap is reported the same way**, even though nothing threw. The two checks described
under "How the probe subscribes" emit `!!! PROBE FAILURE (n): TRACE GAP: …` with the same
stack-trace footer, because the consequence is identical: the trace is incomplete and the run
must not be recordable, whatever its exit status.

One rule the reporter learned the hard way: **it does not flush `System.out` first.** It used
to. With stdout blocked — a full pipe, which is precisely the condition that makes the probe
fall behind and lose a train in the first place — the flush blocked inside the reporter and
only the `!!! ` header ever reached stderr, with the stack trace and its scanned signature
lost. Nothing that reports a fault may depend on the stream that might be the fault. stderr is
unbuffered and the harness scans raw lines wherever they land, so the ordering the flush
bought was worth nothing.

## Does it change the simulation?

It adds a second subscriber to hot channels, so the question has to be measured, not
asserted. It was, headless and bounded — never on a display, where variance is contaminated.

**Method.** `scenarios/short-bounded.properties` (pinned seed 20080415, `sim.headless=true`,
`-Djava.awt.headless=true`, bounded at 115 000 simulated ms), **n = 10 runs per arm, all
within one session**, probe-off versus probe-on, comparing the two output families the
application itself prints and which therefore exist in both arms.

**Result.** In all 20 runs: exit 0, 66 trains generated, 21 departures, the same departure id
set, and — with the departure timestamp projected away (family 5) — the
`"<train> in <station> at …"` stream byte-identical across all 20, order included. One
difference in 20: one probe-on run transposed `vl14` and `vl15` in the `"<train> started"`
stream. They depart at the **same simulated instant** (90 048 ms), which is the
equal-departure-instant tie `scenarios/short-bounded.properties` already documents on the
probe-off baseline for `vl12`/`vl13`, and which `docs/parity-harness.md` requires #21's
normalizer to canonicalise regardless of this issue. Nothing distinguished the arms.

**The conclusion — the probe does not perturb — is what this measures. It is not evidence
that the scenario is reproducible, and it must not be read as such.**

*Within* this session the departure id set was constant. It is **not** constant across
sessions. An independent replication at n = 8 per arm saw two different id sets, differing in
the 21st and last departure (`vl19 in stB` against `vl50 in stC`), split 3-of-8 against
5-of-8 **in both arms** — arm-independent, therefore baseline nondeterminism at the
`maxClockMs` boundary and not the probe. There is also a **session-level** component: runs
within one JVM session share a mode and the mode flips between sessions, reproduced as three
runs uniformly on one branch and three later runs uniformly on the other. This session's set
was a third value again.

Three consequences, and #23/#24 need all three:

* **The last departure on `short-bounded` is not pinned.** A `strict` golden that includes it
  will flake. Either stop the scenario short of the boundary or drop the tail.
* **Consecutive runs cannot validate reproducibility.** They share a session and therefore a
  mode. `docs/parity-harness.md`'s "run it three times and diff the id sets" is necessary and
  not sufficient; the runs have to be separated.
* **A perturbation test must compare arms, not runs.** Which is what this one did, and why
  its conclusion survives the discovery above: an effect present in both arms in the same
  proportion is not caused by the thing that differs between them.

**Read the conclusion as a measurement, not a guarantee.** The probe is not free — it is one
more subscriber the kernel dispatches to — and a denser scenario, above the load ceiling
`docs/seeded-rng.md` describes, may well answer differently.
