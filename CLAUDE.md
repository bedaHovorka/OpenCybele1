# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OpenCybele1 (package `cz.vutbr.fit.ags.xhovor07`) is a 2007/08 school project for the AGS (Multi-Agent Systems) course at FIT VUT Brno. It is a Swing GUI simulation of trains moving through a small railway network, implemented as a set of agents on top of **Cybele**, a third-party multi-agent kernel from IAI shipped as prebuilt, source-less jars in `cybelle/` (`Cybele.jar` = kernel API, `CybeleImpl.jar` = IAI's implementation, `ICS.prop`/`cybele.prop` = Cybele runtime config). There is no build tool (no Maven/Gradle/Ant); it is plain `javac`/`java` against those jars.

## Build & Run

Compile (from repo root):
```bash
javac -d bin -cp "cybelle/Cybele.jar:cybelle/CybeleImpl.jar" $(find src -name "*.java")
```

Run (Linux/macOS):
```bash
./xhovor07.sh
```
Run (Windows):
```
xhovor07.bat
```

Both scripts just launch `cz.vutbr.fit.ags.xhovor07.Main` with classpath `bin:cybelle:cybelle/Cybele.jar:cybelle/CybeleImpl.jar` (Cybele reads `cybelle/ICS.prop`/`cybele.prop` from that classpath at startup). There is no automated test suite; verification is manual via the Swing GUI that pops up on launch.

`bin/` is git-ignored build output — regenerate it with the compile command above rather than editing anything under it.

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
