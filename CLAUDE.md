# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OpenCybele1 (package `cz.vutbr.fit.ags.xhovor07`) is a 2007/08 school project for the AGS (Multi-Agent Systems) course at FIT VUT Brno. It is a Swing GUI simulation of trains moving through a small railway network, implemented as a set of agents on top of **Cybele**, a third-party multi-agent kernel from IAI shipped as prebuilt, source-less jars (`Cybele.jar` = kernel API, `CybeleImpl.jar` = IAI's implementation). The jars live in `cybelle/` on disk but are not tracked in git — they're installed into the local Maven repository (`~/.m2`) and consumed via Gradle Kotlin DSL (`build.gradle.kts`). `cybelle/ICS.prop`/`cybele.prop` (Cybele runtime config) do stay tracked in git, as loose files.

## Build & Run

```bash
./gradlew build
./gradlew run
```

Before the first build, install the vendor jars into `~/.m2` (they're recoverable from the `withoutGradle` git tag — see README.md's "One-time setup" section for the exact `git checkout`/`mvn install:install-file` commands).

`./gradlew run` launches `cz.vutbr.fit.ags.xhovor07.Main` with the two jars (resolved from `mavenLocal()`) on the classpath, plus the JVM flag `--patch-module java.base=cybelle` set via `applicationDefaultJvmArgs` in `build.gradle.kts` (Cybele reads `cybelle/ICS.prop`/`cybele.prop` via that flag at startup). There is no automated test suite; verification is manual via the Swing GUI that pops up on launch.

`build/` is Gradle's git-ignored build output.

`cybelle/cybele.prop` has `cybele.srv.comm.app.param.iai = Local;NoSerialization` set so the comm service stays local-only; commented out (the vendored default), Cybele instead tries to reach an external `IAIDaemon`/license host and the app hangs or aborts at startup with "Could not connect with IAIDaemon".

Cybele's kernel loads `cybele.prop` via `props.getClass().getResourceAsStream("/cybele.prop")` on a `java.util.Properties` instance — a `java.base`-loaded class. Since JPMS (Java 9+), `Class.getResourceAsStream()` on a module-loaded class no longer falls back to the application classpath the way it did pre-JPMS, so without `--patch-module java.base=cybelle` (which injects `cybelle/`'s contents into `java.base`) startup fails immediately with "Cannot find cybele.prop at class path", on any JDK version. This required no changes to the vendored jars or app source, only the added launch flag — the flag only works against a plain directory on disk, not jar contents, which is why `cybele.prop`/`ICS.prop` stay as loose tracked files even though the two binary jars don't.

### Docker (X11 GUI passthrough)

On the `develop` branch, `Dockerfile` + `docker-compose.yml` build and run the app in a container, forwarding the Swing GUI to an X server on the host (bind-mounted `/tmp/.X11-unix` + `.Xauthority`, UID/GID-matched non-root user), the same X11-passthrough pattern used by the sibling `interlockSim` project. The `Dockerfile` is a multi-stage build: a `eclipse-temurin:21-jdk-noble` builder stage (installs the vendor jars via Maven, then `./gradlew installDist`), then a slim `eclipse-temurin:21-jre-noble` runtime stage — Docker is a convenience/reproducibility option here, not a requirement for a working JVM. Since the jars aren't in git, the `withoutGradle` recovery step (see README.md) must be run before `docker compose build` so they're present in the build context.

```bash
xhost +local:docker && docker compose up app   # Linux/macOS
docker compose run app                         # Windows, after starting VcXsrv
```

See `README.md` for full prerequisites and platform-specific setup.

## Architecture

Everything is an **agent** or **activity** in the Cybele kernel, communicating exclusively through named pub/sub **channels** (`Activity.openChannel`/`Activity.sendAll`) — there are no direct method calls between agents. `Main` boots the Cybele kernel and creates a single `RailwayMainAgent`.

- **`RailwayMainAgent`** — the "social knowledge" hub. Hardcodes the railway topology (`net`, an `UnorientedGraph` of stations `stA..stH` and tracks `tr1..tr7`), per-station capacities, and per-track travel delays. Owns the global `Cybele` clock, opens channels to collect station/road/train state for the GUI (`Observable`/`Observer` -> Swing `TableModel`), spawns one `Station` agent per node and one `RoadAgent` per edge, and runs two long-lived activities: `Planning` and `Generator`.
- **`Generator`** — periodically (exponential inter-arrival time, `LAMBDA` ms) creates a new `Train` agent for a random origin/destination pair and asks `Planning` to schedule it.
- **`Planning`** — for each new train, runs a **distributed voting/election protocol**: broadcasts `VOTE_REQUEST` to every `Station`/`RoadAgent` along the path, waits (via `CountDownLatch`, collected by the `VoteCollecting` activity) for all of them to reply with how much they'd need to delay the train, takes the max, and broadcasts the agreed `VOTE_RESULT` departure time back to all of them before telling the `Train` to start.
- **`Train`** — a mobile agent representing one train; walks the path station→road→station via `ENTER`/`LEAVE` channel exchanges with whichever `StaticRailwayObject` it's currently entering/leaving, and self-destructs (`Agent.die()`) on reaching its destination.
- **`StaticRailwayObject`** (abstract base of `Station` and `RoadAgent`) — implements the classic queueing-system `enter`/`leave` protocol plus the voting side (`voteRequest`/`voteResult`, delegating capacity-aware scheduling to abstract `computeDifference`/`addToPlan`).
  - **`Station`** — has a capacity and an internal timetable (`TreeMultiMap`); computes path direction toward a target station lazily via a `PATH_FIND`/`PATH_FIND_REPLY` round-trip to `RailwayMainAgent` (handled through the `PathFinding` activity to avoid deadlocking the station's own event thread).
  - **`RoadAgent`** — single-track segment between two stations; tracks direction of current travel and a queue of waiting trains ordered primarily by planned timetable slot.
- **`RailwayObject`** — shared base (`Handler`) that just resolves an agent's own name from its Cybele agent ID.
- **GUI (`Gui`, `RailwayCanvas`)** — Swing frame with a toolbar to change simulation pace (`Cybele.setPace`), a hand-drawn `RailwayCanvas` (custom `paint`, not layout-managed) showing live station occupancy/road state, and a `JTable` of train states. Repaints are driven by `Observer` notifications from `RailwayMainAgent`.
- **`util` package** — generic data structures used by the simulation logic: `UnorientedGraph`/`HashMapGraph` (graph of stations/tracks, plus `Util.path`/`Util.pathDirection` for pathfinding), `TreeMultiMap` (sorted multi-map used for station/road timetables), `Doubleton` (unordered pair, used as graph edge key).

Comments and some Javadoc in the code are in Czech; class/method names and public APIs are in English.
