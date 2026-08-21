# The clock abstraction — simulated time as an object, and the end of the `pauseClock` idiom

> Issue [#29](https://github.com/bedaHovorka/OpenCybele1/issues/29), the last piece of groundwork
> before the agent ports #30–#34. Companions: [`INVENTORY.md`](INVENTORY.md) `SEM-02` (what the
> kernel's clock actually does), [`headless-and-stop.md`](headless-and-stop.md) (the registration
> race and the barrier that clears it), [`defect-triage.md`](defect-triage.md) (what a port must be
> able to reproduce), [`probes/README.md`](probes/README.md) (how to re-run the experiments).

**Additive.** Nothing in `src/main/java` changed. The Cybele application still calls
`Cybele.createClock`, `Cybele.getTime`, `Activity.setTimer` and `Cybele.pauseClock` exactly as it
did, and the parity gate still measures the same binary. Rewiring the agents onto `SimClock` is
#30–#34, ticket by ticket, each with its own gate run.

---

## 0. What this decides

| # | Decision | Where |
|---|---|---|
| 1 | The `pauseClock`/`resumeClock` idiom is **deleted**, not replaced with a lock — because it excludes no *thread* and never did. What it *does* exclude is clock-derived events, system-wide, which the normalizer projects | §2, §2.4.1 |
| 2 | Simulated time becomes an **object** (`SimClock`), not a global static | §3.1 |
| 3 | Deadlines are stored as **absolute simulated milliseconds**, not relative delays | §3.2 |
| 4 | Wake-ups fire **on the agent's own thread**, via a per-agent granularity ticker | §3.3 |
| 5 | Pace stays **runtime-adjustable**, and rescaling pending deadlines costs nothing | §4 |
| 6 | A **virtual clock** ships, so the JADE port gets deterministic L2 integration tests | §5 |
| 7 | The kernel's uncounted pause stays **reproducible** behind `PauseSemantics.BOOLEAN_2008` | §6 |

---

## 1. The gap

Cybele has a first-class global simulated clock. JADE has nothing of the kind.

```java
Cybele.createClock(CLOCK_ID, Cybele.HOST, config.getClockStartMs(), config.getClockPace());
```
`RailwayMainAgent.java:111`. From that one call the whole application hangs:

* **All four timer sites are clock-relative**, not wall-clock —
  `Generator.java:50` (the first train), `Generator.java:65` (every train after it),
  `Planning.java:111` (the agreed departure), `RoadAgent.java:102` (the traversal).
  Every one is `Activity.setTimer(RailwayMainAgent.CLOCK_ID, delay, handler, method)`.
* **`Cybele.getTime` is the only time source in `src/`.** `System.currentTimeMillis`,
  `System.nanoTime` and `Math.random` appear nowhere. It is read at `Planning.java:81` and `:109`,
  `RoadAgent.java` (through `RoadQueue`) and `RailwayCanvas.java:92`.
* **Pace is adjustable at runtime**, `Cybele.setPace`, from the toolbar at `Gui.java:98`, presets
  `sim.gui.paces = Fast=8,Normal=1,Slow=0.3`. Changing it rescales every pending timer.
* **The clock can be paused**, and three sites do so around short critical sections.

JADE offers `WakerBehaviour` and `TickerBehaviour`. Both are wall-clock. Neither has a shared
notion of simulated time, a pace, or a pause. A `WakerBehaviour`'s deadline is fixed in real time
when the behaviour is added, so a pace change would mean removing and re-adding every pending
timer on every agent with a recomputed deadline, and a pause would not stop it arriving at all.

So the clock has to become something the port carries. That is this ticket.

---

## 2. The `pauseClock` idiom — measured, and the answer is neither "lock" nor "keep"

### 2.1 The claim, and why it could not be assumed

Three sites bracket work with a pause/resume pair:

```java
// Planning.java:108-112
Cybele.pauseClock(RailwayMainAgent.CLOCK_ID);
final long t = (requestTime+timeDiff)-Cybele.getTime(RailwayMainAgent.CLOCK_ID);
queue.put(new TrainPlan(train, from, requestTime+timeDiff));
Activity.setTimer(RailwayMainAgent.CLOCK_ID, t > 0 ? t : 0, this, "placeTrainIntoFirstStation");
Cybele.resumeClock(RailwayMainAgent.CLOCK_ID);
```
```java
// RoadAgent.java:132-134 (push) and :167-169 (pop)
Cybele.pauseClock(RailwayMainAgent.CLOCK_ID);
queue.offer(train, trainPosition, Cybele.getTime(RailwayMainAgent.CLOCK_ID));
Cybele.resumeClock(RailwayMainAgent.CLOCK_ID);
```

#29 calls this a **mutex** and hypothesises that, pause being neither reentrant nor counted, a
concurrent pause/resume pair from two agents can resume the clock early — and then uses that to
justify deleting the idiom. The implementation is inside the source-less `CybeleImpl.jar`, and
`pauseClock(String)` returns a `boolean` whose meaning is not observable from the API surface, so
the issue requires the claim to be **measured before it is acted on**. It is used to justify a
deletion; if it were false the justification would change.

What was already established, and is not re-derived here: `SEM-02` read
`ContinuousClock.setPause()`'s `if (paused) return;` out of the vendor bytecode and ran `ExpB2`
(two pauses, one resume, **one thread**, HOST and LOCAL scope) — not counted. That is a different
statement from the one #29 makes. It says nothing about two agents, and nothing about whether the
idiom excludes anything.

### 2.2 The experiment

[`probes/ExpI.java`](probes/ExpI.java), run against the real jars, in the application's own shape:
two **distinct Cybele agents**, each executing its bracket on its own handler thread — which is
what `Planning` (the main agent's activity thread) and `RoadAgent` (each road agent's own thread)
do. Agent A pauses, holds for 300 ms, resumes. Agent B pauses and resumes entirely inside A's
hold, 100 ms in, for 20 ms. Measured: **A's own `getTime` delta across its own bracket**.

The clock is created 20 ms after `startUp()` and its command channel is *proved live* before any
measurement — the `RunControl` technique, pause, poll `isPaused` with a re-send, resume — because
a clock inside the ~3.4 ms registration race answers every command with a silent no-op and would
make the whole probe a measurement of nothing. The gap is printed beside the results, as
[`probes/README.md`](probes/README.md) caveat 1 requires.

```
startUp -> createClock gap: 20.60119 ms   (race window is ~3.4 ms; see SEM-02)
clock=ovlClock scope=HOST pace=1  HOLD=300ms  B at +100ms for 20ms  settle=10ms  n=20 per arm

control arm: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
overlap arm: 190 191 189 189 190 189 190 190 190 190 190 190 189 191 189 190 189 190 189 190

ARM              A's own getTime delta across its bracket (ms)
  control (A alone)   min=0 max=0 median=0
  overlap (A and B)   min=189 max=191 median=190

VERDICT 1 (early resume): 20/20 overlap trials saw the clock advance more than 50 ms inside A's bracket  [predicted advance if not counted: ~190 ms]
VERDICT 1 control:        0/20 control trials did
VERDICT 2 (exclusion):    B ENTERED its body inside A's bracket 20/20, and RETURNED from it still inside A's bracket 20/20
  handler threads: A=Thread-3  B=Thread-2
```

**Both verdicts are 60/60 across three consecutive runs**, control 0/60. The predicted value if
pause is not counted is `HOLD + settle − overlapAt − bBracket = 300 + 10 − 100 − 20 = 190`;
measured 188–191.

Two things about verdict 2 that an earlier revision of this document got wrong and a review
caught. It used to be a single overwritten `boolean` printed once, so the *headline* verdict was
n=1 per run while the subordinate one was a genuine 20/20; it is now two counters over every
trial. And "B's body ran to completion inside A's bracket" used to be inferred from the geometry
of the sleeps; B now samples `aInsideBracket` at its **exit** as well as its entry, so completion
is recorded rather than reasoned.

The distinct handler threads are printed rather than inferred, and their being distinct is not
incidental. **If dispatch were single-threaded this probe would deadlock rather than report:** A
blocks in `aResult.put()` on a `SynchronousQueue` while the driver waits on `bDone` for the very
thread A is holding. So "verdict 2 true *and* the run terminated" is itself positive evidence
that the two brackets are genuinely concurrent.

### 2.3 Verdict 1 — the hypothesis is confirmed

Agent B's `resumeClock` restarts the clock while agent A still believes it is frozen. A's
"protected" 300 ms window is 110 ms of freeze and 190 ms of clock. Two agents' brackets do not
compose.

### 2.4 Verdict 2 — and it changes the conclusion rather than supporting it

**The idiom is not a mutex.** Pausing a clock stops simulated *time*; it does not stop a
*thread*. Agent B's handler entered its body (20/20, 60/60 over three runs) and returned from it
(same denominators) while agent A was inside its bracket. No thread was ever excluded from
anything.

So "replace the `pauseClock`/`resumeClock` mutex idiom with a real lock" answers a question the
code never asked, and the counting semantics are beside the point: even a perfectly counted,
perfectly reentrant pause would exclude exactly as many *threads* as this one does, which is none.

This belongs to the category the project keeps running into — synchronisation that cannot exclude
anything — but it is a new species of it. The others are locks on the wrong object, or guards a
second party never takes. This one is not a lock at all: no call in the idiom can ever block, so
there is no critical section to be mutually excluded from. Which is why "replace it with a real
lock" is the wrong repair, and why the repair that *is* right (§2.5) is not a synchronisation
change.

#### 2.4.1 What a freeze *does* exclude — state this precisely

"The idiom excludes nothing" is too strong, and #30–#34 will read this section as licence, so the
exact scope matters.

A freeze is **not inert**. All four `Activity.setTimer` sites are armed on the *one* global
`myClock` (`INVENTORY.md` CNT-09, TMR-01…TMR-04), so for the duration of any bracket, anywhere:

* **no timer anywhere in the simulation can fire** — not `Generator`'s next arrival, not
  `Planning`'s departure, not any road's traversal end;
* **every agent's `Cybele.getTime` stamp is frozen**, including the ones the trace records.

The exclusion domain is therefore **clock-derived events, system-wide** — which is a much bigger
blast radius than a mutex would have had, and a completely different one from "the code between
these two lines runs alone".

It does not change the disposition, for three reasons worth writing down rather than
re-deriving:

1. **Deferred, not dropped.** A frozen deadline is still pending; it arrives once time moves.
2. **Relative order is preserved**, because every pending timer shifts by the same amount.
3. **The tick is family 1 of #21's projection** ([`trace-format.md`](trace-format.md)), so the
   absolute shift is projected away by the normalizer before any golden comparison.

`PauseIdiomTest.a_bracket_defers_every_pending_wakeup_by_its_duration` pins all three.

So: **excludes no thread; defers clock-derived events by the bracket duration, which the
normalizer projects.** That is the sentence to cite.

### 2.5 So what *is* it, and is the sequence really non-atomic?

The issue's own framing — "the idiom exists to make a non-atomic sequence look atomic" — is right
about the shape and needs the sequence named. It is a **read-then-arm atomicity device against
clock drift**, and it is only load-bearing at one of the three sites.

**`Planning.java:108-112` — real, and this is the sequence.** Line `:109` reads the clock and
converts the agreed absolute departure `requestTime + timeDiff` into a *relative* delay `t`. Line
`:111` hands `t` to `setTimer`, which measures it from the **kernel's own, later** clock read
inside the timer service. Two reads of a moving clock, and the departure lands late by whatever
elapsed between them. Freezing the clock across the two statements makes that interval zero.

That last claim used to be the one unmeasured link in this argument — the site graded "real
protection" rested on reasoning while the two graded "protects nothing" had 60 trials each.
`INVENTORY.md` records only that the four sites take a relative `delayMillis` (TMR-01…TMR-04);
`SEM-06` covers negative and zero delays, not the read point. So it was measured:
[`probes/ExpJ.java`](probes/ExpJ.java), three arms, `n = 10` each, `D = 1000 ms`, gap `= 200 ms`.

```
baseline  arm: 1002 1001 1001 1001 1001 1001 1001 1001 1001 1001
drift     arm: 1202 1202 1202 1201 1202 1201 1201 1202 1200 1201
bracketed arm: 1001 1001 1001 1001 1001 1000 1001 1000 1001 1000

ARM         fire - t0 (simulated ms), t0 taken BEFORE the gap
  baseline    min=1001 max=1002 median=1001   [control: no gap]
  drift       min=1200 max=1202 median=1202   [gap, clock RUNNING]
  bracketed   min=1000 max=1001 median=1001   [gap, clock PAUSED across it -- the 2008 idiom]

VERDICT 1: setTimer's delay is measured from the KERNEL's OWN, LATER read  [drift - baseline = 201 ms against a 200 ms gap]
VERDICT 2: the pauseClock bracket REMOVES that drift  [bracketed - baseline = 0 ms]
```

**3/3 consecutive runs, 30 trials per arm in total**, `drift − baseline` = 201, 200, 200 ms
against a 200 ms gap, and `bracketed − baseline` = 0 ms in all three. So:

* `setTimer` **does** re-read the clock at the call. The drift is real and is exactly the elapsed
  simulated time, to within a millisecond.
* The 2008 bracket **does** remove it, completely. `Planning`'s bracket is load-bearing, and
  deleting it *without* replacing the relative delay with an absolute instant would be a
  behaviour change.

Note the disposition does not depend on the result — `AgentClock.scheduleAt` reads no clock at
all, so it is at least as good either way, and had `setTimer` turned out not to re-read, the
bracket would simply have joined `RoadAgent`'s two in the "protects nothing" column. It is the
*reasoning* that depended on it, and reasoning is what #30–#34 will cite.

The site is also **not a concurrency problem**, which is why calling it a mutex sent the analysis
the wrong way. There is no second party. A single-threaded program would need the same bracket,
because the thing racing the code is the clock.

**`RoadAgent.java:132-134` and `:167-169` — protecting nothing.** The bracket holds the clock
still across one heap operation so every comparison inside it sees the same `t`. Two independent
reasons that is inert:

1. **#28 already surfaced the read.** `RoadQueue.offer`/`poll` take `now` as a parameter and hold
   it for the whole heap operation, so every comparison sees one value *by construction*. The 2008
   code read the live clock per comparison; the extracted class cannot.
2. **`t` cancels algebraically anyway.** `(plan(a) − t) − (plan(b) − t) == plan(a) − plan(b)`, and
   it cancels in two's-complement `long` arithmetic too, so the identity survives overflow.
   `defect-triage.md` §4.1 retracted the "unstable comparator" claim on exactly this ground, and
   `RoadQueueOrderingTest.the_surfaced_time_cancels_out_of_the_comparison` asserts it.

So the ordering never depended on when the clock was read. The interval those two brackets protect
is protecting nothing, and both are **deletions with no replacement**.

### 2.6 What #30–#34 must do

| Site | Verdict | Replacement |
|---|---|---|
| `Planning.java:108-112` | The bracket is real, and the sequence is real — `ExpJ`, 3/3 runs | **Delete the bracket** and call `AgentClock.scheduleAt(requestTime + timeDiff, …)`. Passing the absolute instant removes the second clock read, so there is no drift left to freeze against. Do **not** delete it while keeping a relative delay: that reintroduces the 200 ms-class drift `ExpJ` measured. §3.2 |
| `RoadAgent.java:132-134` | Protects nothing (#28 + algebra) | **Delete.** `roadQueue.offer(train, position, agentClock.now())` |
| `RoadAgent.java:167-169` | Protects nothing (#28 + algebra) | **Delete.** `roadQueue.poll(agentClock.now())` |

`SimClock.pause()`/`resume()` still exist — the GUI may want them, a scenario may want to freeze a
run, and `defect-triage.md` requires a port to be *able* to reproduce a 2008 behaviour even where
it chooses not to. They are simply not what these three sites should use.

> **"Delete the bracket" is not "delete the monitor".** Each of the three sites sits inside an
> ordinary Java monitor that is *separate* from the pause pair and was never part of this
> analysis: `synchronized (this)` at `Planning.java:97` enclosing the whole envelope method, and
> `synchronized` on `RoadAgent.enter`/`leave` (`:118`, `:150`) enclosing `push`/`pop`. Those are
> real locks over real shared state and they do exclude. They become *redundant* under JADE's
> one-thread-per-agent dispatch — which is a separate argument, made per agent, in #30–#34 — but
> they are not redundant because of anything measured here. Remove the two `pauseClock` lines;
> leave the `synchronized` alone until the agent that owns it is ported.

**One thing the deletion does change, and it is worth stating rather than discovering in #39.**
Under Cybele the three brackets froze the clock for the duration of the bracketed work — always,
40/40 on the real application, `SEM-02`. That freeze was globally visible: every other agent's
`getTime` stopped too, for ~35 µs a time. Removing the brackets removes those micro-freezes. They
are invisible in the contract — the five clock-derived trace families are projected by the
normalizer (#21, [`trace-format.md`](trace-format.md)) — but "removing the brackets is what changes
behaviour" (`SEM-02`'s phrasing) is true, and the reason it is safe is the projection, not the
absence of an effect.

---

## 3. The design

### 3.1 Two halves, deliberately separate

```
cz.vutbr.fit.ags.railway.domain.clock      (domain source set — framework-free)
  SimClock          interface: nowMs, pause, resume, isPaused, pace, setPace
  PacedClock        simulated time derived from real time at a pace
  VirtualClock      simulated time advanced explicitly by a test
  PauseSemantics    COUNTED (default) | BOOLEAN_2008 (the kernel's)
  NanoSource        the one seam through which real time enters
  ManualNanoSource  a NanoSource a test drives
  DeadlineQueue     min-heap of absolute simulated deadlines
  AgentClock        one per agent: the shared SimClock + its own DeadlineQueue

cz.vutbr.fit.ags.railway.jade.clock        (jadeOntology source set)
  ClockTickerBehaviour   TickerBehaviour whose onTick() drains one AgentClock
```

The **time source** is shared, one instance, exactly as `myClock` was. The **scheduler** is
per-agent and private. That split is the whole design; everything below follows from it.

### 3.2 Deadlines are absolute simulated milliseconds

Cybele stores a real-time expiry and rescales every pending timer when the pace changes. This
stores the simulated instant. Three consequences, all of them good:

* **A pace change moves no deadline.** "Fires when simulated time reaches 42000" means the same at
  pace 8, 1 and 0.3; only how much *real* time it takes to get there changes. Same observable
  semantics as Cybele's rescale, with no rescale pass to get wrong, no rounding error accumulating
  across repeated pace changes, and no lock taken across the clock and seven agents' queues.
* **A pause stops every deadline at once**, because it stops the only thing they are compared
  against.
* **`Planning`'s bracket becomes unnecessary**, §2.5. `Planning` already computes the absolute
  departure and then throws it away by converting to a delay, because `Activity.setTimer` demands
  one. `AgentClock.scheduleAt(long dueSimMs, Runnable)` takes the instant.
  `AgentClockTest.scheduling_an_absolute_instant_does_not_drift` advances simulated time by 300 ms
  between the computation and the arming and asserts that the absolute form still fires at the
  instant that was computed while the relative form is late by exactly 300.

`AgentClock.scheduleIn(delayMs, …)` exists for the sites that genuinely are relative — `Generator`'s
exponential inter-arrival, `RoadAgent`'s Gaussian traversal. It is drift-free too: it reads the
clock once, at the call, and stores the absolute result.

### 3.3 Wake-ups fire on the agent's own thread

`AgentClock.runDue()` runs due tasks **on the calling thread**, and the caller is the host
framework's per-agent granularity ticker: `ClockTickerBehaviour` in JADE, an environment step in
Jason (#47). Nothing in the package starts a thread.

> **One thread per agent is JADE's invariant.** An agent's fields are unsynchronised because only
> its own thread touches them. A clock service that called back from a timer thread would break
> that for every agent that armed a timer — the port would compile, run, and be wrong in a way no
> golden could reliably catch.

The cost is resolution: a wake-up fires at the first tick at or after its deadline, so it is late
by up to `granularity × pace` simulated ms. Cybele is late too — a zero-delay timer fires in 1–5 ms
(`SEM-06`) — and the contract is *ordering*, not timestamp equality, because the normalizer
projects the five clock-derived families away (#21). Choose the granularity well below the
shortest simulated interval the simulation can distinguish; the railway's shortest is a 1 s road,
i.e. 1000 simulated ms.

Two details that matter more than they look:

* **The drain reads the clock once, at entry, and bounds the queue's arming sequence at entry.**
  A task that arms a fresh past-dated deadline is therefore *not* re-entered in the same drain; it
  fires at the next one. Without that bound a `scheduleIn(-1, …)` from inside a callback would spin
  the agent thread forever — and a negative delay is not hypothetical, it is 2.26 % of `RoadAgent`'s
  travel-time draws on a 1 s road (DEF-16).
* **`pollDue` hands back one task at a time and the drain runs it outside every lock.** So a
  wake-up that throws does not consume the wake-ups behind it, and a wake-up may arm, cancel, read
  the clock or pause it.
* **That bound is per drain, so do not call `runDue()` from inside a wake-up.** A callback that
  re-enters `runDue()` gets a fresh, higher sequence limit and picks up the deadline it just
  armed — measured, a self-rearming callback that drained itself fired five times inside one
  outer drain. The class contract still holds (the framework's drain terminates), but it is the
  spin the bound exists to prevent, re-created by hand. Arm from a wake-up freely; drain only
  from the agent's ticker.

### 3.4 What is *not* reproduced, and why

| Kernel behaviour | Reproduced? | Why |
|---|---|---|
| `pauseClock` is **asynchronous** (a `sendAll` to the `TimerAgent`, sub-2 ms) | No | Pause and resume traverse the same channel with the same latency, so a bracket is time-shifted, not lengthened (`SEM-02`, n=40). `SimClock.pause()` is synchronous, which `SEM-02` states outright is behaviour-preserving for the three sites. |
| The **~3.4 ms clock registration race** — `createClock` too soon after `startUp()` leaves clock control a permanent silent no-op | No | It is a startup defect that #17 spent a barrier (`RunControl.awaitTimerService`), a regression check and exit code 5 defending against. A `SimClock` is an object in the same JVM; there is no announcement to lose. The race does not bear on the design, only on the probe — which is why `ExpI` proves its clock live before measuring. |
| `pauseClock` returns `true` for "pausing an already-paused clock" and `false` only for an unknown clock id | No | There is no clock id and no lookup; `pause()` returns `void`. |
| `ContinuousClock.getInfo()` calls `setPause()` and **never resumes** | No | `SEM-02` found no caller in the app or in either jar, and any future `getInfo` path would stop the clock permanently. Explicitly not ported. `PacedClock.pauseDepth()` is the diagnostic that would expose such a leak. |
| A clock is created **running** (`ContinuousClock.<init>` sets `paused = false`) | **Yes** | #17's barrier rests on this fact; `PacedClockTest` asserts it. |
| A **negative or zero** delay fires immediately | **Yes** | `SEM-06`/`TMR-04`, and DEF-16 depends on it. `DeadlineQueueTest` asserts it. |
| Ties fire **FIFO** | **Yes** | [`kernel-config.md`](kernel-config.md) §1 measured the agent queue as FIFO and decided to keep it rather than enable `staticpriority_comp`. Insertion order is the closest analogue, and the only deterministic one. |
| Pause is **not counted** | **Available**, not the default | §6 |

---

## 4. Pace stays runtime-adjustable

**Decided: adjustable.** #35 decided the GUI *is* ported (off by default), and `Gui.java:98` is the
only writer of `Cybele.setPace` in the tree. Fixing the pace per scenario would delete a control
the port is committed to shipping, for a saving that is zero — see below.

`SimClock.setPace(double)` is therefore public, thread-safe, and callable from the GUI thread while
agent threads read `nowMs()`. Three points:

* **Rescaling pending deadlines is free.** §3.2. Cybele has to walk its pending timers; this walks
  nothing, because absolute simulated instants do not move. The requirement "pace must rescale
  pending deadlines, because Cybele's does" is met by making the rescale a no-op rather than by
  implementing it.
* **The clock rebases on the change**, so simulated time already elapsed keeps the old pace and
  `nowMs()` is continuous and monotone across it.
  `PacedClockTest.now_is_monotone_across_every_control_operation` walks pause, resume and five
  paces asserting monotonicity at every step.
* **Pace `0` is rejected.** A stopped clock is `pause()`. Letting a pace of zero mean the same
  thing would give one state two representations and make `isPaused()` a lie. Negative, `NaN` and
  infinite are rejected for the same reason a `ScenarioConfig` key is validated at startup: a bad
  value should fail where it is set, not where it is read.
* **And it is bounded**, `[PacedClock.MIN_PACE, MAX_PACE]` = `[1e-6, 1e6]`. Not defensive
  clutter: `epochSimMs + (long)(elapsedNanos * pace / 1e6)` wraps **negative** once the
  `double`→`long` cast saturates, and a subsequent `setPace` rebases onto the wrapped value.
  A review demonstrated it at `pace = 1e300`. It needs ~36 million simulated years at pace 8 and
  is unreachable from the toolbar's 8/1/0.3, but `setPace` is public API the agent ports call and
  monotonicity is the class's central promise — so it is enforced twice: the bound rejects the
  input, and `nowMs()` saturates at `Long.MAX_VALUE` rather than wrapping in any case.
  `PacedClockTest.an_extreme_pace_saturates_instead_of_wrapping` drives it to the top of the
  `long` range and asserts the clock never reads negative.

`sim.clock.pace` and `sim.clock.startMs` keep their meaning unchanged and become the two
constructor arguments of `PacedClock`.

---

## 5. The virtual clock — a capability the original cannot have

`VirtualClock` is a `PacedClock` whose real-time reference is a `ManualNanoSource`. Pause, resume
and pace are the **production code**, exercised by the same tests; what differs is that the test
owns time.

```java
VirtualClock clock = new VirtualClock(0, 1.0);
AgentClock agent = new AgentClock(clock);
agent.scheduleAt(8500, () -> generated.add("vl0"));

clock.advanceSimTo(8499);  agent.runDue();   assertEquals(List.of(), generated);
clock.advanceSimTo(8500);  agent.runDue();   assertEquals(List.of("vl0"), generated);
```

Cybele can never have this. Its clock lives inside a source-less kernel jar driven by a kernel
thread; there is no seam. Every Cybele-side timing statement in this repository is a sleep-and-hope
with a caveat attached — `probes/README.md` spends three of them on exactly that, and `SEM-02` has
to warn that an `ExpB*` result is meaningless without a measured startup gap beside it.

For **#38** this means the JADE L2 integration tests can assert *when*, exactly, with no wall-clock
dependence, no sleep, no flake and no sensitivity to how busy the build machine is. Two guards keep
that honest:

* `advanceSimTo`/`advanceSimBy` **throw while the clock is paused.** A frozen clock a test could
  nudge forward would silently defeat the pause tests, which are the ones this class exists to make
  trustworthy. `advanceRealMs` is allowed while paused, because "simulated time stays put while
  real time runs" is precisely what a pause test has to demonstrate.
* Simulated time cannot be rewound. `nowMs()` is monotone and a test must not be able to break the
  invariant every other test relies on.

**Headline item for #41's comparison log.** The port does not merely reimplement the clock, it
makes the clock testable. `VirtualClockTest.the_same_script_produces_the_same_timeline_every_time`
runs the same script twice and asserts byte-equal output — a statement no Cybele-side test in this
repository can make about anything time-dependent.

---

## 6. Reproducing the kernel's uncounted pause

`PauseSemantics.COUNTED` is the default: *n* pauses need *n* resumes. It is what makes a bracket
safe to nest, which the port needs the moment two ported behaviours on one agent both want one.

`PauseSemantics.BOOLEAN_2008` reproduces the kernel exactly — a second `pause()` is a no-op and one
`resume()` restarts the clock however many pauses are outstanding.

The default diverges from the kernel, and that is a deliberate choice with a stated basis:

* **No golden pins it.** The three brackets are a few statements long, measured at ~35 µs, and
  1000 brackets of that realistic shape produced 0 early resumes (`SEM-02`). The quirk is reachable
  in principle and was not observed in the application in practice.
* **The issue's own guidance**, in the resolution comment on #29: "prefer counted and document the
  divergence."
* **`defect-triage.md` requires the capability, not the default.** A port must be *able* to
  reproduce a 2008 behaviour even where it chooses not to. `PauseIdiomTest` pins both modes against
  `ExpI`'s measured numbers, so the capability is asserted rather than claimed — and if #39 ever
  triages a diff to this, one constructor argument reproduces the kernel.

`SEM-02` also recorded a third thing worth not inheriting: in 2 of 40 real-application runs, 50
back-to-back zero-gap pause/resume pairs left the clock **permanently paused** — likely
non-serialised dispatch of two `receiveNetCmd` events across the kernel's thread pool, since
`setPause`/`setResume` are order-sensitive boolean writes. `SimClock`'s controls are `synchronized`
on the clock, so ordering is not assumed; there is no channel and no pool.

---

## 7. What went where

| Class | Source set | Why |
|---|---|---|
| `SimClock`, `PacedClock`, `VirtualClock`, `PauseSemantics`, `NanoSource`, `ManualNanoSource`, `DeadlineQueue`, `AgentClock` | **`domain`** | Framework-free, and branch `jason` (#46, #47) consumes them unchanged. The `domain` source set's dependency configuration is empty, so a `cybele.kernel` or `jade.core` import there does not compile. |
| `ClockTickerBehaviour` | **`jadeOntology`** (`src/jade/java`) | The only part that needs `jade.core.behaviours`. Not on the Cybele application's classpath. |

`./gradlew domainPurity` — green, 48 files, no forbidden imports. No `java.awt`, no `jade.*`, no
`cybele.*`, no `java.lang.reflect`. Real time enters the domain through exactly one interface,
`NanoSource`, whose production implementation is a one-line `System::nanoTime` and whose test
implementation is a counter.

**`build.gradle.kts` was not touched.** The `domain` and `jadeOntology` source sets take their whole
directory trees, `domainPurity` reads `domain.java.asFileTree`, and `test` already compiles against
both outputs plus JADE. A new package needs no wiring.

### Tests

50 new tests, all L1, all in `src/test/java`; the suite goes 102 → **152**.

| Class | Pins |
|---|---|
| `PacedClockTest` | pace scaling, pause freezing, resume-continues-not-catches-up, counted vs boolean, monotonicity across every control op, pace validation **and bounds**, **saturation instead of wrap at the top of the `long` range**, **`setPace` while paused**, created-running |
| `VirtualClockTest` | exact advancement, pace through `advanceRealMs`, the paused-advance guard, monotonicity, byte-equal repeated timelines |
| `DeadlineQueueTest` | time order, FIFO ties, past deadlines firing (DEF-16), cancel, no self-re-entry within a drain, the older-entry-not-skipped case, `long` compare not DEF-03's `int` narrowing |
| `AgentClockTest` | **absolute scheduling does not drift** (§3.2), relative resolved at arming, unclamped negative delay, wake-ups on the caller's thread, paused clock fires nothing, pace change leaves deadlines put, throwing wake-up leaves the rest armed |
| `PauseIdiomTest` | `ExpI`'s two arms, **derived from `ExpI`'s four constants** rather than the literal 190, in both semantics; **that a bracket defers every pending wake-up by its duration and preserves their order** (§2.4.1); and the design requirement that `pause()` must never block a thread |
| `ClockTickerBehaviourTest` | one tick is one drain on the ticking thread; one behaviour serves every deadline the agent arms |

---

## 8. What #30–#34 inherit

One `SimClock` for the platform, one `AgentClock` and one `ClockTickerBehaviour` per agent:

```java
// once, at boot
SimClock sim = new PacedClock(config.getClockStartMs(), config.getClockPace());

// in each agent's setup()
AgentClock clock = new AgentClock(sim);
addBehaviour(new ClockTickerBehaviour(this, GRANULARITY_MS, clock));
```

The four timer sites and the three brackets, side by side:

| 2008 | Ported |
|---|---|
| `Cybele.getTime(CLOCK_ID)` | `clock.now()` |
| `Generator.java:50` `setTimer(CLOCK_ID, firstFireMs, …)` | `clock.scheduleIn(config.getArrivalFirstFireMs(), this::generateTrain)` |
| `Generator.java:65` `setTimer(CLOCK_ID, exp(lambda), …)` | `clock.scheduleIn(exp(lambda), this::generateTrain)` |
| `Planning.java:111` `setTimer(CLOCK_ID, t > 0 ? t : 0, …)` | `clock.scheduleAt(requestTime + timeDiff, …)` — and **delete the bracket at :108/:112** |
| `RoadAgent.java:102` `setTimer(CLOCK_ID, delay2, …)` | `clock.scheduleIn(TravelDelay.travelMs(...), this::travelEnd)` — **do not clamp**, DEF-16 |
| `RoadAgent.java:132-134`, `:167-169` brackets | **delete**; `queue.offer(train, position, clock.now())`, `queue.poll(clock.now())` |
| `Cybele.setPace(p)` from `Gui.java:98` | `sim.setPace(p)` |

Three things to carry across intact:

1. **The `t > 0 ? t : 0` clamp at `Planning.java:111` is the 2008 clamp** and belongs at that call
   site if it is ported at all. `AgentClock` does not clamp, on purpose: `RoadAgent`'s negative
   delay must stay unclamped. With `scheduleAt` the clamp is moot — a past instant is due at the
   next drain, which is what the clamp produced.
2. **Trace `tick` (#20) must keep meaning simulated time**, i.e. `clock.now()`, never
   `System.currentTimeMillis()`. `ExpH` established that `CybeleEvent.getClockTime()` is `-1` on a
   message event, which is why the Cybele probe reads `Cybele.getTime`; the JADE probe reads
   `SimClock.nowMs()` for the same reason.
3. **No agent may hold a static clock.** `SimClock` is constructor-injected. That is the acceptance
   criterion "no agent reads a global static clock", and it is what makes a Jason `Environment`
   able to own the same object.

---

## 9. Re-running the experiment

```bash
docs/probes/run.sh ExpI        # the idiom under two agents; exits 1 if clock control is dead
docs/probes/run.sh ExpJ        # from which read does setTimer's delay run? same exit contract
```

Read the result **only** alongside the `startUp -> createClock` gap it prints. A probe inside the
~3.4 ms registration race measures a clock whose every command is a silent no-op, and reports it in
no way at all. `ExpI` sleeps 20 ms and then proves control works before it measures anything, but
the gap is printed so a reader never has to take that on trust.

The unit tests need none of this:

```bash
./gradlew test --tests 'cz.vutbr.fit.ags.railway.domain.clock.*'
```
