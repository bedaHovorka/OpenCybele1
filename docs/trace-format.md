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
`VOTE_REQUEST`/`VOTE`/`VOTE_RESULT` legs of the election are one line per path member):

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
line. The next obvious one, "the sender", makes field 1 a copy of field 4 on nine of the
fifteen channels. So field 1 is the **subject**: the entity whose story this line belongs
to.

For twelve of the fifteen channels that is a train, which is what makes the harness'
`causal` contract work at all — `entity.pattern: '^(vl\d+)\|'`, group 1, projects the trace
per train with no scanning of the payload. The remaining three (`PATH_FIND`,
`PATH_FIND_REPLY`, `STATION_INFO`/`ROAD_STATE`) concern no train and carry the station or
track instead; under `causal` those lines land in the unattributed bucket, which is compared
in order and excluded from the tolerance budget (`docs/parity-harness.md`).

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
and equal-tick lines are already something #21 has to canonicalise (see "What a golden can
and cannot pin"). A port that *can* stamp at send time is free to, and will be closer to the
truth; the two differ only inside a tick.

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

### What the payload of CH-10 is and is not

The kernel runs `Local;NoSerialization` (`cybelle/cybele.prop`), so **a payload crosses a
channel by reference** (`docs/INVENTORY.md` SEM-05). `Station.Info` is a *non-static inner
class* of the `Station` that keeps mutating it: `Station.enter` increments `occupied`,
`Station.leave` decrements it, both on the station's own thread, both after the object has
already been shipped. What arrives at a subscriber is a live alias, not a message.

A probe that formatted `info.occupied` lazily — inside the string concatenation, after doing
anything else first — would record whatever value the station had reached by then, and would
diff against a JADE port that copies its message for reasons that have nothing to do with
behaviour. `TraceProbe.onStationInfo` therefore reads both fields into `int` locals as its
**first two statements** and formats from those.

That is the earliest capture available without modifying `Station`, and it is honest to say
what it is **not**: it is a snapshot at *handling* time, not at *send* time, and the read is
not synchronised against the station's own mutation (neither is `RailwayCanvas`'s — DEF-13).
`occupied` can therefore be one ahead of what the sender intended when the station is busy.
**A port must snapshot at the same point** — on receipt, before anything else — rather than
"fixing" it by copying at send time, or the two traces will differ on exactly the busy
stations.

`ROAD_STATE`'s payload is an enum constant and carries no such hazard, and the `RoadAgent`
state that *is* mutable — its priority queue, its timetable, the train in transit — never
crosses a channel. `Station.QueueItem` and `RoadAgent.OueueItem` are likewise internal.
Every other payload in the system is a `String` or a `Long`.

## Prefix, and living with the harness

The parity harness separates diagnostics from trace by **line prefix**, and its
`DiagnosticFilter` drops `--- `, `---`, `!!! ` and any line starting with two spaces
(`docs/parity-harness.md`). Trace lines begin with an agent name — `vl3`, `stA`, `tr1`,
`Main` — so they are never stripped, and no diagnostic can be mistaken for one.

Collision with the application's own two `println`s is impossible in both directions: the
canonical line's second character group is always `|`, and the liveness patterns
`^vl\d+ started$` and `^(vl\d+) in st[A-H] at \d+$` are anchored. A pattern that matches a
canonical line matches nothing else.

Two consequences worth writing down before someone is surprised by them:

* **The trace and the two `println`s share stdout, interleaved.** That is deliberate — the
  `println`s are the only record of the *departure* decision and stay part of the golden.
  `PrintStream.println(String)` is atomic per line, so lines never interleave *within*
  themselves.
* **`ScenarioConfig.describe()`'s body is not indented**, so its `sim.* = value` lines
  survive the default prefix set and land in the normalized trace. That predates this issue
  (#18 wrote the banner, #12 chose the prefixes) and is #21's or #13's to settle — either
  the banner gets the two-space body every other banner has, or the adapter declares the
  prefix. It is noted here because a golden recorded before it is settled contains the
  resolved configuration as trace lines.

## Enabling it

```properties
sim.trace.enabled = false      # default: no probe agent, no extra channels, stdout unchanged
sim.trace.trainLookahead = 32  # train-name slots kept subscribed ahead of the generator
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

A window narrower than a generation burst would silently lose lines, so it does not fail
silently: observing a train beyond the window top is reported as a probe failure (below).

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

It counts every event token, fails on a missing one, checks the four request/reply
round-trips plus `TRAVEL_START`/`TRAVEL_END`, and asserts that no line carries a
performative or an unrecognised event. Exit 0 is PASS, 1 is FAIL.

Two of the five relations are deliberately inequalities, and both are behaviour rather than
slack:

* `ENTER ≤ ENTER_REPLY` — a station that is full enqueues the train and admits it later from
  `Station.leave`, which sends an `ENTER_REPLY` with no `ENTER` in front of it. At
  `short-bounded`'s density nothing ever queues and the two are equal, 202/202; at
  `short.properties`' density they are 137/137 with real queuing in between. A run where
  replies *exceeded* requests is the fingerprint of queued admission, not of a bug.
* `TRAVEL_START ≥ TRAVEL_END` — trains still on a track when the bound fires never finish.

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

## Does it change the simulation?

It adds a second subscriber to hot channels, so the question has to be measured, not
asserted. It was, headless and bounded — never on a display, where variance is contaminated.

**Method.** `scenarios/short-bounded.properties` (pinned seed 20080415, `sim.headless=true`,
`-Djava.awt.headless=true`, bounded at 115 000 simulated ms), **n = 10 runs per arm**,
probe-off versus probe-on, comparing the two output families the application itself prints
and which therefore exist in both arms.

**Result.** In all 20 runs: exit 0, 66 trains generated, 21 departures, and the **departure
id set identical** — `vl0…vl19` plus `vl39`, every run, both arms. With the departure
timestamp projected away (it is wall-clock-derived and varies run to run in *both* arms) the
`"<train> in <station> at …"` stream is **byte-identical across all 20 runs**, order
included.

The one difference: in 1 probe-on run of 10, `vl14` and `vl15` are transposed in the
`"<train> started"` stream. They depart at the **same simulated instant** (90 048 ms), which
is the equal-departure-instant tie `scenarios/short-bounded.properties` already documents on
the probe-off baseline for `vl12`/`vl13`, and which `docs/parity-harness.md` requires #21's
normalizer to canonicalise regardless of this issue. The departure *set* did not move, no id
was gained or lost, and DEF-02's dropped-train residual (~1 train per few hundred
departures) did not appear in either arm at this scenario size.

**Read this as a measurement, not a guarantee.** The probe is not free — it is one more
subscriber the kernel dispatches to — and a denser scenario, above the load ceiling
`docs/seeded-rng.md` describes, may well have a different answer. The test is cheap: run
both arms three times at a fixed seed and diff the departure id sets.
