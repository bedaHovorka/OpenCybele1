# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OpenCybele1 (package `cz.vutbr.fit.ags.xhovor07`) is a 2007/08 school project for the AGS (Multi-Agent Systems) course at FIT VUT Brno. It is a Swing GUI simulation of trains moving through a small railway network, implemented as a set of agents on top of **Cybele**, a third-party multi-agent kernel from IAI shipped as prebuilt, source-less jars (`Cybele.jar` = kernel API, `CybeleImpl.jar` = IAI's implementation). The jars live in `cybelle/` on disk but are not tracked in git — they're installed into the local Maven repository (`~/.m2`) and consumed via Gradle Kotlin DSL (`build.gradle.kts`). `cybelle/ICS.prop`/`cybele.prop` (Cybele runtime config) do stay tracked in git, as loose files.

## Build & Run

```bash
./gradlew build          # compiles every source set, runs the L1 domain unit tests,
                         # rngProof and domainPurity
./gradlew run
./gradlew test           # just the L1 unit tests for src/domain/java
./gradlew domainPurity   # just the "no framework imports in the domain" check
```

Before the first build, run `scripts/bootstrap-vendor-jars.sh`. It recovers `cybelle/Cybele.jar`/`CybeleImpl.jar` from the `withoutGradle` git tag if absent and installs them into `~/.m2` as `com.iai:cybele-api:1.0`/`com.iai:cybele-impl:1.0`. It is idempotent and does **not** need `mvn` on `PATH` — without Maven it writes the local-repository layout (jar + generated POM) itself. Same script is called by the `Dockerfile` builder stage and is the intended CI entry point, though no CI workflow exists in this repo yet (#25).

All simulation parameters are externalised as `sim.*` Java system properties with defaults identical to the original hardcoded literals (`ScenarioConfig`, validated eagerly in `Main.main` before the kernel starts; resolved values printed to stderr). Ready-made scenario files live in `scenarios/`; see `docs/scenario-config.md` for the key table, the `LAMBDA` split (`sim.arrival.lambdaMs` vs `sim.station.voteWindowMs`, both 8500 by default) and the canvas topology drift.

```bash
./gradlew run -Dsim.config=scenarios/short.properties   # short scenario, ~13x throughput
./gradlew run -Dsim.arrival.lambdaMs=2000               # single override
```

By default the run opens the Swing window and never stops on its own. `-Dsim.headless=true` skips the GUI, and the `sim.stop.*` keys give it a bound; a run that ends on its safety timeout exits non-zero so it can never be mistaken for a completed scenario. See `docs/headless-and-stop.md` — it also documents the kernel clock-registration race that removing the GUI exposes, which is why `Main` waits for the timer service before creating the main agent.

`./gradlew run` launches `cz.vutbr.fit.ags.xhovor07.Main` with the two jars (resolved from `mavenLocal()`) on the classpath, plus the JVM flags `-ea` and `--patch-module java.base=cybelle` set via `applicationDefaultJvmArgs` in `build.gradle.kts` (Cybele reads `cybelle/ICS.prop`/`cybele.prop` via that flag at startup). There is no automated test suite; verification is manual via the Swing GUI that pops up on launch. The simulation has no stop condition, so bound it with `timeout` when capturing output.

Assertions are **on** for `run` — and, because `applicationDefaultJvmArgs` is baked into the generated start script, for `installDist` and the Docker image too. Triage of all 32 sites is in `docs/assertion-triage.md`: none fires; 24 exercised and holding, 8 never reached. (It was 33/25 until #18 removed `RailwayCanvas`'s `assert road != null`; note that a naive `grep -c 'assert '` now returns 33 — two false positives must be excluded, see that document's amendment.)

`com.iai.cybele.thmgmt.IAIAgentThread` catches five named exception types around its reflective handler invoke — **not** `Throwable`. An `AssertionError` survives only because `Method.invoke` wraps it in `InvocationTargetException`. It is printed by `com.iai.cybele.exception.IAIExceptionHandler` **to `System.err`**, twice per failure, and the simulation continues. Three things follow, and all three have bitten: capture **stderr** or you see nothing; the process **exit status never changes**, so it is useless as a pass/fail signal; and a throwable in a timer handler like `Generator.generateTrain` **permanently stops** train generation, because the method re-arms its own timer as its last statement. See `docs/assertion-triage.md` § Result 3 before writing anything that diffs run output.

To disable assertions without editing the build, use the start script's env hook: `OPENCYBELE_OPTS=-da build/install/opencybele/bin/opencybele`. `./gradlew run` has no equivalent hook. Enabling `-ea` is itself a behaviour change; the assertions-on/off decision *for golden recording* is deliberately left to issues #22/#24 and must not be settled here.

`build/` is Gradle's git-ignored build output.

`cybelle/cybele.prop` has `cybele.srv.comm.app.param.iai = Local;NoSerialization` set so the comm service stays local-only; commented out (the vendored default), Cybele instead tries to reach an external `IAIDaemon`/license host and the app aborts at startup with "Could not connect with IAIDaemon -- Execution aborted!" (exit 1). The other two kernel knobs were pinned under #16 after measurement and are annotated in the file itself: the event queue is explicitly `no_sort no_comp` on both queues (strict FIFO — a sorted agent queue lets TIMERs overtake MESSAGEs, which is a behaviour change), and the thread pool stays `5 10 4000 3 5` (below 3 threads the simulation starves). `cybelle/ICS.prop` now has exactly one `ICSBrowser` key. See `docs/kernel-config.md`.

Cybele's kernel loads `cybele.prop` via `props.getClass().getResourceAsStream("/cybele.prop")` on a `java.util.Properties` instance — a `java.base`-loaded class. Since JPMS (Java 9+), `Class.getResourceAsStream()` on a module-loaded class no longer falls back to the application classpath the way it did pre-JPMS, so without `--patch-module java.base=cybelle` (which injects `cybelle/`'s contents into `java.base`) startup fails immediately with "Cannot find cybele.prop at class path", on any JDK version. This required no changes to the vendored jars or app source, only the added launch flag — the flag only works against a plain directory on disk, not jar contents, which is why `cybele.prop`/`ICS.prop` stay as loose tracked files even though the two binary jars don't.

### Docker (X11 GUI passthrough)

`Dockerfile` + `docker-compose.yml` build and run the app in a container, forwarding the Swing GUI to an X server on the host (bind-mounted `/tmp/.X11-unix` + `.Xauthority`, UID/GID-matched non-root user), the same X11-passthrough pattern used by the sibling `interlockSim` project. The `Dockerfile` is a multi-stage build: a `eclipse-temurin:21-jdk-noble` builder stage (runs `scripts/bootstrap-vendor-jars.sh`, then `./gradlew installDist`; no Maven is installed in the image), then a slim `eclipse-temurin:21-jre-noble` runtime stage — Docker is a convenience/reproducibility option here, not a requirement for a working JVM. Since the jars aren't in git and `.dockerignore` excludes `.git`, `scripts/bootstrap-vendor-jars.sh` must be run **on the host** before `docker compose build` so the jars are present in the build context.

```bash
xhost +local:docker && docker compose up app   # Linux/macOS
docker compose run app                         # Windows, after starting VcXsrv
```

See `README.md` for full prerequisites and platform-specific setup.

## Architecture

Everything is an **agent** or **activity** in the Cybele kernel, communicating exclusively through named pub/sub **channels** (`Activity.openChannel`/`Activity.sendAll`) — there are no direct method calls between agents. `Main` boots the Cybele kernel and creates a single `RailwayMainAgent`.

- **`ScenarioConfig`** — every simulation parameter that used to be a literal, resolved from `sim.*` system properties (optionally pre-filled from a `-Dsim.config=<file>` properties file) with the historical literals as defaults. Loaded and validated by `Main` on the main thread before `Cybele.startUp()`, because a failure inside an agent would be swallowed by the kernel.
- **`RunControl`** — not an agent: plain static state driving the run's lifecycle. Waits for the kernel's timer service before the main agent is created, verifies that the simulation clock answers its command channel, counts generated trains against `sim.stop.maxTrains`, runs a watchdog thread for the other bounds, and ends the process with an exit code that says which bound (or which failure) stopped it. It has to end the process itself: Cybele swallows handler throwables without touching the exit status, and `Cybele.terminate()` calls `System.exit(0)` and never returns, so the status is forced from a shutdown hook.
- **`RailwayMainAgent`** — the "social knowledge" hub. Takes the railway topology from `ScenarioConfig` (`net`, an `UnorientedGraph` of stations `stA..stH` and tracks `tr1..tr7`), per-station capacities, and per-track travel delays. Owns the global `Cybele` clock, opens channels to collect station/road/train state for the GUI (`Observable`/`Observer` -> Swing `TableModel`), spawns one `Station` agent per node and one `RoadAgent` per edge, and runs two long-lived activities: `Planning` and `Generator`.
- **`Generator`** — periodically (exponential inter-arrival time, mean `sim.arrival.lambdaMs`, default 8500 — the former `Generator.LAMBDA`, which no longer exists) creates a new `Train` agent for a random origin/destination pair and asks `Planning` to schedule it.
- **`Planning`** — for each new train, runs a **distributed voting/election protocol**: broadcasts `VOTE_REQUEST` to every `Station`/`RoadAgent` along the path, waits (via `CountDownLatch`, collected by the `VoteCollecting` activity) for all of them to reply with how much they'd need to delay the train, takes the max, and broadcasts the agreed `VOTE_RESULT` departure time back to all of them before telling the `Train` to start.
- **`Train`** — a mobile agent representing one train; walks the path station→road→station via `ENTER`/`LEAVE` channel exchanges with whichever `StaticRailwayObject` it's currently entering/leaving, and self-destructs (`Agent.die()`) on reaching its destination.
- **`StaticRailwayObject`** (abstract base of `Station` and `RoadAgent`) — implements the classic queueing-system `enter`/`leave` protocol plus the voting side (`voteRequest`/`voteResult`, delegating capacity-aware scheduling to abstract `computeDifference`/`addToPlan`).
  - **`Station`** — has a capacity and an internal timetable (`TreeMultiMap`); computes path direction toward a target station lazily via a `PATH_FIND`/`PATH_FIND_REPLY` round-trip to `RailwayMainAgent` (handled through the `PathFinding` activity to avoid deadlocking the station's own event thread).
  - **`RoadAgent`** — single-track segment between two stations; tracks direction of current travel and a queue of waiting trains ordered primarily by planned timetable slot.
- **`RailwayObject`** — shared base (`Handler`) that just resolves an agent's own name from its Cybele agent ID.
- **GUI (`Gui`, `RailwayCanvas`)** — Swing frame with a toolbar to change simulation pace (`Cybele.setPace`), a hand-drawn `RailwayCanvas` (custom `paint`, not layout-managed) showing live station occupancy/road state, and a `JTable` of train states. Repaints are driven by `Observer` notifications from `RailwayMainAgent`.

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

- **`domain.msg` package** — the message ontology (#27): `Channel` (all fifteen Cybele channels as data — event token, payload keys, endpoint kinds, subject rule, FIPA performative), `RailwayMessage` and the fifteen immutable payload records, `Payloads` (trace field-7 rendering and its inverse), `TraceLine` (the seven-field trace line), plus `Performative`/`Subject`/`Party`/`RoadDirection`. Pure data, no framework: branch `jason` (#46) reuses it verbatim. See [`docs/message-ontology.md`](docs/message-ontology.md).

**These classes are a transcription, not a cleanup.** Pinned defects (`DEF-03` `long`→`int` narrowing, `DEF-04` dead `frequency` tie-break, `DEF-06` unguarded unboxing NPE, `DEF-16` unclamped negative travel delay, the `compareTo(null) == -1` contract deviation, and the first-path-not-shortest DFS) are reproduced deliberately, each with a `DEF-nn` comment and a unit test in `src/test/java` named for what it preserves. See `docs/defect-triage.md` §3.1/§3.1.1 before changing any of them.

Comments and some Javadoc in the code are in Czech; class/method names and public APIs are in English.

### The `jadeOntology` source set (`src/jade/java`, package `cz.vutbr.fit.ags.railway.jade`)

The one layer of the message ontology that cannot be framework-free: `RailwayOntology` (the
`:ontology` slot and topic name per channel, and the FIPA act name → JADE `int` lookup),
`Messages` (build/read an `ACLMessage` — direct AID unicast, plus the optional probe topic as a
second receiver) and `Templates` (the fifteen `MessageTemplate`s, the per-agent-kind unions, and
the `not(inbound)` drain).

It is **not** on the Cybele application's classpath: JADE is on `jadeOntologyImplementation` and
on `testImplementation`, never on `implementation`, so `./gradlew run` and `installDist` are the
Cybele launch they always were. The Cybele agents deliberately do not use the ontology yet —
rewiring them is #30–#34, ticket by ticket, each with its own parity gate run.

```bash
./gradlew compileJadeOntologyJava   # just this source set
```

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
