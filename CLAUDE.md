# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OpenCybele1 (package `cz.vutbr.fit.ags.xhovor07`) is a 2007/08 school project for the AGS (Multi-Agent Systems) course at FIT VUT Brno. It is a Swing GUI simulation of trains moving through a small railway network, implemented as a set of agents on top of **Cybele**, a third-party multi-agent kernel from IAI shipped as prebuilt, source-less jars in `cybelle/` (`Cybele.jar` = kernel API, `CybeleImpl.jar` = IAI's implementation, `ICS.prop`/`cybele.prop` = Cybele runtime config). There is no build tool (no Maven/Gradle/Ant); it is plain `javac`/`java` against those jars.

## Build & Run

Compile (from repo root):
```bash
javac -d bin -cp "cybelle/Cybele.jar:cybelle/CybeleImpl.jar" $(find src/main -name "*.java")
```

Run (Linux/macOS):
```bash
./xhovor07.sh
```
Run (Windows):
```
xhovor07.bat
```

Both scripts just launch `cz.vutbr.fit.ags.xhovor07.Main` with classpath `bin:cybelle:cybelle/Cybele.jar:cybelle/CybeleImpl.jar` and the JVM flag `--patch-module java.base=cybelle` (Cybele reads `cybelle/ICS.prop`/`cybele.prop` via that flag at startup). There is no automated test suite; verification is manual via the Swing GUI that pops up on launch.

`bin/` is git-ignored build output — regenerate it with the compile command above rather than editing anything under it.

`cybelle/cybele.prop` has `cybele.srv.comm.app.param.iai = Local;NoSerialization` set so the comm service stays local-only; commented out (the vendored default), Cybele instead tries to reach an external `IAIDaemon`/license host and the app hangs or aborts at startup with "Could not connect with IAIDaemon".

Cybele's kernel loads `cybele.prop` via `props.getClass().getResourceAsStream("/cybele.prop")` on a `java.util.Properties` instance — a `java.base`-loaded class. Since JPMS (Java 9+), `Class.getResourceAsStream()` on a module-loaded class no longer falls back to the application classpath the way it did pre-JPMS, so without `--patch-module java.base=cybelle` (which injects `cybelle/`'s contents into `java.base`) startup fails immediately with "Cannot find cybele.prop at class path", on any JDK version. This required no changes to the vendored jars or app source, only the added launch flag.

### Docker (X11 GUI passthrough)

On the `develop` branch, `Dockerfile` + `docker-compose.yml` build and run the app in a container, forwarding the Swing GUI to an X server on the host (bind-mounted `/tmp/.X11-unix` + `.Xauthority`, UID/GID-matched non-root user), the same X11-passthrough pattern used by the sibling `interlockSim` project. The image is based on `eclipse-temurin:21-jdk-noble` (Ubuntu 24.04 LTS), the same JDK 21 used for local builds — Docker is a convenience/reproducibility option here, not a requirement for a working JVM.

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
