# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OpenCybele1 (package `cz.vutbr.fit.ags.xhovor07`) is a 2007/08 school project for the AGS
(Multi-Agent Systems) course at FIT VUT Brno. It is a Swing GUI simulation of trains moving
through a small railway network.

**This branch (`jade-develop`) is the JADE port** of that application (Phase 1). The original
OpenCybele / Cybele solution lives on branch `opencybele-baseline`. Compare the two *between*
branches — this tree must not carry both solutions (#82). The only framework dependency on this
branch is `net.sf.ingenias:jade:4.3` from Maven Central. There is no `cybelle/` directory, no
vendor-jar bootstrap, and no `--patch-module`.

The parity harness still drives the baseline as a child JVM (`OpenCybeleLauncher` +
`-Popencybele.dist`) so the goldens stay guarded; that is a path-based comparison, not a second
implementation in this tree. Mapping notes: [`jade/README.md`](jade/README.md).

## Build & Run

```bash
./gradlew build              # compiles every source set, L1 unit tests, rngProof, domainPurity
./gradlew run
./gradlew test               # L1 unit tests
./gradlew integrationTest    # L2 in-process JADE container tests
./gradlew domainPurity       # "no framework imports in the domain" check
./gradlew installDist        # build/install/opencybele — what JadeLauncher drives
```

No one-time vendor-jar setup. JADE resolves from Maven Central.

All simulation parameters are externalised as `sim.*` Java system properties with defaults identical
to the original hardcoded literals (`ScenarioConfig`, validated eagerly in `Main.main` before the
platform starts; resolved values printed to stderr). Ready-made scenario files live in `scenarios/`;
see `docs/scenario-config.md`.

```bash
./gradlew run -Dsim.config=scenarios/short.properties   # short scenario, ~13x throughput
./gradlew run -Dsim.arrival.lambdaMs=2000               # single override
```

By default the run opens the Swing window and never stops on its own. `-Dsim.headless=true` skips
the GUI, and the `sim.stop.*` keys give it a bound; a run that ends on its safety timeout exits
non-zero so it can never be mistaken for a completed scenario. See `docs/headless-and-stop.md`.

`./gradlew run` launches `cz.vutbr.fit.ags.xhovor07.Main`, which boots a JADE main container (topic
management service on, external MTP off, ephemeral port) and starts `RailwayMainAgent` plus the
optional `TraceProbe`. `-ea` is set via `applicationDefaultJvmArgs`.

Assertions are **on** for `run` — and, because `applicationDefaultJvmArgs` is baked into the
generated start script, for `installDist` and the Docker image too. To disable without editing the
build: `OPENCYBELE_OPTS=-da build/install/opencybele/bin/opencybele`. Enabling `-ea` is itself a
behaviour change; the assertions-on/off decision *for golden recording* is deliberately left to
issues #22/#24 and must not be settled here.

`build/` is Gradle's git-ignored build output. `applicationName` stays `"opencybele"` so
`installDist` still lands at `build/install/opencybele`, which is the path `-Pjade.dist` and the
parity docs name.

### Docker (X11 GUI passthrough)

`Dockerfile` + `docker-compose.yml` build and run the app in a container, forwarding the Swing GUI
to an X server on the host. Multi-stage: JDK 21 builder (`./gradlew installDist`), slim JRE 21
runtime. No vendor-jar step.

```bash
xhost +local:docker && docker compose up app   # Linux/macOS
docker compose run app                         # Windows, after starting VcXsrv
```

See `README.md` for full prerequisites and platform-specific setup.

## Architecture

Everything is a **JADE agent** (or a plain class hosted on one), communicating through FIPA-ACL
messages built by `cz.vutbr.fit.ags.railway.jade.Messages` against the fifteen channels in
`domain.msg.Channel`. `Main` boots the JADE platform and starts `RailwayMainAgent` (and the probe
when tracing is on). Per-agent activity→behaviour mapping: [`jade/README.md`](jade/README.md).

- **`ScenarioConfig`** — every simulation parameter that used to be a literal, resolved from `sim.*`
  system properties (optionally pre-filled from a `-Dsim.config=<file>` properties file) with the
  historical literals as defaults. Loaded and validated by `Main` on the main thread before the
  platform starts.
- **`RunControl`** — not an agent: plain static state driving the run's lifecycle. Counts generated
  trains against `sim.stop.maxTrains`, runs a watchdog thread for the other bounds against the
  shared `SimClock`, and ends the process with an exit code that says which bound (or which failure)
  stopped it.
- **`RailwayMainAgent`** — the "social knowledge" hub (`jade.core.Agent`, implements `RailwayView`).
  Takes the railway topology from `ScenarioConfig`, spawns one `Station` agent per node and one
  `RoadAgent` per edge, hosts `Planning` and `Generator`, and feeds the GUI.
- **`Generator`** — plain class on the main agent; schedules the next arrival on the shared
  `AgentClock` (exponential inter-arrival, mean `sim.arrival.lambdaMs`).
- **`Planning`** — plain class on the main agent; runs the distributed voting/election protocol
  (tally of votes, max envelope) and the departure timer via `ClockTickerBehaviour`.
- **`Train`** — a mobile `jade.core.Agent`; walks the path station→road→station via `ENTER`/`LEAVE`
  exchanges and `doDelete()`s on reaching its destination.
- **`Station` / `RoadAgent`** — `jade.core.Agent`s implementing the queueing-system enter/leave
  protocol plus the voting side, with capacity-aware scheduling delegated to the domain classes.
- **`TraceProbe`** — passive `jade.core.Agent` subscribed to the fifteen topics; emits the canonical
  parity trace line.
- **GUI (`Gui`, `RailwayCanvas`, `RailwayView`)** — Swing frame with a toolbar to change simulation
  pace, a hand-drawn canvas, and a `JTable` of train states.

### The `domain` source set (`src/domain/java`, package `cz.vutbr.fit.ags.railway.domain`)

Since #28 the railway *rules* are plain Java in a source set of their own, and the agents above are glue over them. Its dependency configuration is deliberately **empty**, so a `cybele.kernel` import there does not compile; `./gradlew domainPurity` additionally rejects Swing/AWT/reflection and any import of the app or the harness. It builds to `opencybele-domain.jar`, which is what lets branches `jade` and `jason` (#37/#46) depend on the same rules unchanged.

- **`StationSchedule`** — the station timetable (`TreeMultiMap<Long,String>`) and its voting rule: window `time ± voteWindow`, one capacity slot held in reserve, `±voteWindow/3` when full, otherwise `plannedTrains·voteWindow/6`.
- **`StationQueue`** — the FIFO of trains a full station holds at its door.
- **`RoadSchedule`** — the track timetable plus its inverse (`TreeMap` + `HashMap`, kept in sync) and its voting rule: anything in `time ± delay` defers to `lastKey + delay - time`.
- **`RoadQueue` / `RoadQueueItem`** — the priority queue of trains waiting for a busy track and the comparator that orders them. Simulated time is a **parameter** of `offer`/`poll`, not a global clock read.
- **`TrainPlan`** — a scheduled departure and its ordering. Its `toString` is trace-visible.
- **`DispatchTimeline`** — the dispatch-time accumulation along a path (the two identical loops in `Planning`).
- **`VoteEnvelope`** — the *obálková metoda*: the election result is `max` of the votes.
- **`TravelDelay`** — the travel-time expression; the Gaussian draw stays with the agent.
- **`domain.util` package** — generic data structures: `UnorientedGraph`/`HashMapGraph` (graph of stations/tracks, plus `Util.path`/`Util.pathDirection` for pathfinding), `TreeMultiMap` (sorted multi-map used for station/road timetables), `Doubleton` (unordered pair, used as graph edge key). Moved here unchanged from `cz.vutbr.fit.ags.xhovor07.util`; `AbstractUnorientedGraph` was dead code and was deleted.

- **`domain.clock` package** — simulated time (#29): `SimClock` (the time source: `nowMs`, `pause`, `resume`, `setPace`), `PacedClock` (derived from real time at a pace), `VirtualClock` (advanced explicitly by a test — deterministic L2 tests, #38), `DeadlineQueue` (min-heap keyed on **absolute simulated ms**, so a pace change moves nothing) and `AgentClock` (one per agent; `runDue()` fires wake-ups **on the calling thread**, never on a timer thread, because one thread per agent is JADE's invariant). `PauseSemantics.BOOLEAN_2008` reproduces the kernel's uncounted pause; the default is counted. See [`docs/clock-abstraction.md`](docs/clock-abstraction.md) — including why the `pauseClock`/`resumeClock` idiom at `Planning.java:108-112` and `RoadAgent.java:132-134,167-169` is **deleted rather than replaced with a lock** (`docs/probes/ExpI.java`: it excludes no thread — what it does exclude is clock-derived events system-wide, which #21's normalizer projects; `docs/probes/ExpJ.java`: `setTimer` re-reads the clock, so only `Planning`'s bracket protected anything, and `scheduleAt` on an absolute instant is what replaces it).

- **`domain.msg` package** — the message ontology (#27): `Channel` (all fifteen Cybele channels as data — event token, payload keys, endpoint kinds, subject rule, FIPA performative), `RailwayMessage` and the fifteen immutable payload records, `Payloads` (trace field-7 rendering and its inverse), `TraceLine` (the seven-field trace line), plus `Performative`/`Subject`/`Party`/`RoadDirection`. Pure data, no framework: branch `jason` (#46) reuses it verbatim. See [`docs/message-ontology.md`](docs/message-ontology.md).

**These classes are a transcription, not a cleanup.** Pinned defects (`DEF-03` `long`→`int` narrowing, `DEF-04` dead `frequency` tie-break, `DEF-06` unguarded unboxing NPE, `DEF-16` unclamped negative travel delay, the `compareTo(null) == -1` contract deviation, and the first-path-not-shortest DFS) are reproduced deliberately, each with a `DEF-nn` comment and a unit test in `src/test/java` named for what it preserves. See `docs/defect-triage.md` §3.1/§3.1.1 before changing any of them.

Comments and some Javadoc in the code are in Czech; class/method names and public APIs are in English.

### The `jadeOntology` source set (`src/jade/java`, package `cz.vutbr.fit.ags.railway.jade`)

The one layer of the message ontology that cannot be framework-free: `RailwayOntology` (the
`:ontology` slot and topic name per channel, and the FIPA act name → JADE `int` lookup),
`Messages` (build/read an `ACLMessage` — direct AID unicast, plus the optional probe topic as a
second receiver) and `Templates` (the fifteen `MessageTemplate`s, the per-agent-kind unions, and
the `not(inbound)` drain). Plus `clock.ClockTickerBehaviour` (#29) — the one part of the
clock that needs `jade.core.behaviours`: a per-agent granularity `TickerBehaviour` whose
`onTick()` drains that agent's due simulated-time wake-ups on the agent's own thread.

It is **not** on the Cybele application's classpath: JADE is on `jadeOntologyImplementation` and
on `testImplementation`, never on `implementation`, so `./gradlew run` and `installDist` are the
Cybele launch they always were. The Cybele agents deliberately do not use the ontology yet —
rewiring them is #30–#34, ticket by ticket, each with its own parity gate run.

```bash
./gradlew compileJadeOntologyJava   # just this source set
```

## Integration layer (`integrationTest`)

`src/integrationTest/java` holds the L2 suite (issue #38, `docs/TESTING.md` §4.2): it boots a real
JADE main container **in-process** and starts the real `Station` / `RoadAgent` / `Train` /
`TraceProbe` inside it, then drives one interaction pair per test through a probe agent and asserts
with bounded `poll(timeout)`. It proves the messaging wiring — AID unicast, the fifteen
`railway.<EVENT>` topics, and the single message queue draining — which POJO tests cannot reach and
L3 cannot isolate.

```bash
./gradlew integrationTest
```

**The lane's fork policy is its fixture, not a tuning knob.** `maxParallelForks = 1` because
`jade.core.Runtime` and `jade.core.AID.platformID` are JVM-wide singletons; **`forkEvery = 1`**
because `TraceProbe.READY` (a latch counted down once, never reset), `TraceProbe.FAILURES` (a
monotone counter) and `RunControl`'s first-writer-wins clock have no reset hook, so a fresh JVM per
class is the only reset there is — that is what makes those statics testable at all. Inside a class
the container is shared and the agents are killed between methods (`ContainerFixture`). Not wired
into `check`, on the same precedent as `characterizationIT`; `check` compiles it.

## Parity harness (`characterizationIT`)

`src/characterizationIT/java` holds the L3 golden-master harness (issue #12): a `ScenarioRunner`
that starts an implementation as a **child JVM** through a `LauncherAdapter`, and judges the run by
its captured output rather than by its exit status. It has no compile-time link to any
implementation — that is what lets it live on this branch and later drive OpenCybele, JADE and
Jason unchanged — and it is a source set of its own, wired to nothing in `main`.

```bash
./gradlew characterizationIT                      # compare against parity-tests/golden/
./gradlew characterizationIT -Dgolden.record=true # record them
```

Scenario specs are YAML data under `parity-tests/scenarios/`. Format, contract levels and the order
in which a run is judged: [`docs/parity-harness.md`](docs/parity-harness.md).

The L3 run of this branch against the frozen goldens, and the classification of every diff
it produced, is [`docs/parity-triage-jade.md`](docs/parity-triage-jade.md): two of the five
scenarios reproduce byte-for-byte across 20 runs, and the three that do not are red on a
normalizer limit that the file measures rather than works around — every one of them emits the
same events, in the same multiset, at the same length.
