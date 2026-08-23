# OpenCybele1 (JADE port)

A Swing GUI simulation of trains moving through a small railway network, built as a set of
communicating agents. Written for the AGS (Multi-Agent Systems) course at FIT VUT Brno, 2007/08,
originally on the **Cybele** kernel; **this branch is the JADE port** of that application
([Phase 1](docs/Phase1.md), mapping notes in [`jade/README.md`](jade/README.md)).

> **Branch split (#82).** Compare implementations *between branches*, not inside one tree:
>
> | Branch | What it carries |
> |---|---|
> | `opencybele-baseline` | the frozen OpenCybele / Cybele application (and its vendor jars) |
> | `jade-develop` (this branch) | the JADE application, the shared domain, and the parity harness |
>
> The harness still drives the baseline as a **child JVM** via `OpenCybeleLauncher` +
> `-Popencybele.dist` so the goldens stay guarded. It does **not** compile or ship Cybele here.

Stations and single-track road segments are simulated as agents that negotiate train departure times
through a distributed voting protocol, then move trains along the network while respecting station
capacity and track occupancy. A Swing canvas visualizes station occupancy and track state live,
alongside a table of in-flight trains.

## Environment

| | |
|---|---|
| **Build tool** | Gradle **8.10.2**, via the checked-in wrapper (`./gradlew`, `gradlew.bat`) — do not substitute a system Gradle |
| **Java** | Gradle **toolchain 21** (`build.gradle.kts`); verified on Temurin/OpenJDK 21 |
| **Agent framework** | JADE **4.3.3** via `net.sf.ingenias:jade:4.3` (decision [#26](https://github.com/bedaHovorka/OpenCybele1/issues/26)) from Maven Central — no vendor-jar bootstrap |
| **Assertions** | `-ea` is on for `run`, and baked into the `installDist` start script and the Docker image — see [Assertions (`-ea`)](#assertions--ea) |
| **Scenario config** | `sim.*` system properties, defaults identical to the historical literals — see [Scenario configuration](#scenario-configuration-sim) and [`docs/scenario-config.md`](docs/scenario-config.md) |
| **Randomness** | per-agent streams from one master seed, `-Dsim.random.masterSeed`, defaulting to a drawn (printed) seed — see [Reproducing a run](#reproducing-a-run-simrandommasterseed) and [`docs/seeded-rng.md`](docs/seeded-rng.md) |
| **Parity trace** | off by default; `-Dsim.trace.enabled=true` adds one passive probe agent that prints `agent\|tick\|event\|from\|to\|performative\|payload` for all 15 channels — see [Parity trace](#parity-trace-simtraceenabled) and [`docs/trace-format.md`](docs/trace-format.md) |
| **Tests** | L1 unit tests (`./gradlew test`), L2 in-process JADE containers (`./gradlew integrationTest`), L3 parity harness (`./gradlew characterizationIT -Pjade.dist=…`) |

## Requirements

- JDK 21 and Gradle (a wrapper is included: `./gradlew`/`gradlew.bat`)
- Network access to Maven Central (for JADE and JUnit)
- A running X server for the Swing GUI (not needed for headless / CI runs)

## Build

```bash
./gradlew build
```

No one-time vendor-jar setup. JADE resolves from Maven Central.

## Run

```bash
./gradlew run
```

This launches `cz.vutbr.fit.ags.xhovor07.Main`, which boots a JADE main container (with the topic
management service) and starts `RailwayMainAgent` + the optional `TraceProbe`. `-ea` is set via
`applicationDefaultJvmArgs` in `build.gradle.kts`. A Swing window opens on launch showing the
railway network, live station/track state, and a table of trains currently in transit.

### Source layout

| Source set | Directory | What it is | Depends on |
|---|---|---|---|
| `domain` | `src/domain/java` | The railway rules as plain Java — timetables, voting rules, queue ordering, graph, pathfinding, clock, message records. Package root `cz.vutbr.fit.ags.railway.domain`. | **nothing** (JDK only) |
| `jadeOntology` | `src/jade/java` | ACL binding: `Messages`, `Templates`, `RailwayOntology`, `ClockTickerBehaviour`. | `domain`, JADE |
| `main` | `src/main/java` | The JADE application: agents, GUI, configuration, trace probe. | `domain`, `jadeOntology`, JADE |
| `test` | `src/test/java` | L1 unit tests (domain + behaviour-as-POJO). Runs in `./gradlew build`. | `domain`, `jadeOntology`, JADE |
| `integrationTest` | `src/integrationTest/java` | L2 in-process JADE container tests. | `main` |
| `tools` | `tools/java` | Verification drivers (`SeedInterleavingCheck`, `TraceCheck`) — never on the simulation's classpath. | `main` |
| `characterizationIT` | `src/characterizationIT/java` | The L3 parity harness. Drives an implementation as a **child process**; has no compile-time link to one. | nothing in this repo |

The `domain` source set's dependency configuration is deliberately **empty**, so an accidental
framework import there fails the compiler rather than a review. `./gradlew domainPurity`
additionally rejects the JDK imports a classpath cannot exclude (`javax.swing`, `java.awt`,
`java.lang.reflect`) and any import of the application or the harness. The output is a jar of its
own, `opencybele-domain.jar`, which branch `jason` (#46) consumes unchanged — see
[issue #28](https://github.com/bedaHovorka/OpenCybele1/issues/28).

**The domain classes reproduce the 2008 behaviour, defects and all.** Each pinned quirk carries a
`DEF-nn` comment pointing at [`docs/defect-triage.md`](docs/defect-triage.md) §3.1 and is locked in
by a unit test named for what it preserves. Do not "fix" one — under the Phase-1 scope guard a
behaviour change is a port bug, and several of these quirks are unreachable at scenario scale, so no
golden would catch it.

End-to-end verification is the L3 parity suite; interactive verification is manual through the GUI.
Per-agent mapping notes: [`jade/README.md`](jade/README.md).

### Headless and bounded runs

By default the run is exactly as it always was: a window opens and it never stops on its own.
Three additions, all opt-in:

```bash
# no window, no display needed
OPENCYBELE_OPTS="-Djava.awt.headless=true -Dsim.headless=true -Dsim.stop.maxClockMs=115000" \
  build/install/opencybele/bin/opencybele ; echo $?

# a ready-made bounded, headless, seed-pinned scenario -- no `timeout` in front of it
OPENCYBELE_OPTS=-Dsim.config=scenarios/short-bounded.properties \
  build/install/opencybele/bin/opencybele ; echo $?
```

| Key | Default | Meaning |
|---|---|---|
| `sim.headless` | `false` | skip the Swing window |
| `sim.stop.maxTrains` | `0` (off) | **bound** — stop after this many trains generated (exact) |
| `sim.stop.maxClockMs` | `0` (off) | **bound** — stop this far past `sim.clock.startMs`, in simulated ms |
| `sim.stop.wallClockMs` | `0` (off) | **failure** — wall-clock safety net |
| `sim.stop.stallMs` | `0` (off) | **failure** — no train generated for this long |

Exit codes: `0` a declared bound was reached (or the window of an *unbounded* run was closed),
`1` configuration/startup error, `2` the window was closed while a bound was armed and unreached,
`3` the wall-clock safety net fired, `4` train generation stalled, `5` the simulation clock does
not answer its command channel. Plus `255`, which is not this mechanism's: a throwable escaping
`RailwayMainAgent`'s constructor exits the JVM with it, loudly, before any banner. **A run that
did not finish never exits 0** — it must not look like a pass.

`sim.stop.maxTrains` is exact; `sim.stop.maxClockMs` stops at or just past its bound; **neither is
a trace-length bound** — trains already planned but not yet departed simply never print. Details,
the exit-code mechanics (`Cybele.terminate()` exits 0 and never returns, so the status is forced
from a shutdown hook) and the clock-registration race that headless mode exposes:
[`docs/headless-and-stop.md`](docs/headless-and-stop.md).

Without any `sim.stop.*` key the process still runs until it is killed, so wrapping it works as
before:

```bash
timeout 300 ./gradlew run --console=plain
```

### Parity trace (`sim.trace.enabled`)

The application itself prints two lines — `"<train> in <station> at <n>"` and
`"<train> started"`. Everything else it does reaches the Swing canvas through
`Observable`/`Observer` and never touches a stream, so none of it can be compared against a
port. `-Dsim.trace.enabled=true` adds **one passive agent** that subscribes to all fifteen
application channels and appends one canonical line per message to stdout:

```
agent|tick|event|from|to|performative|payload
```

```bash
OPENCYBELE_OPTS="-Djava.awt.headless=true \
  -Dsim.config=scenarios/short-bounded.properties \
  -Dsim.trace.enabled=true" build/install/opencybele/bin/opencybele
```

```
vl3|13064|START|Main|vl3|-|station=stA
vl3|13072|ENTER|vl3|stA|-|train=vl3,position=null,target=stB
vl3|13080|ENTER_REPLY|stA|vl3|-|object=stA,next=tr1
```

It is additive — it sends nothing, sets no timer and touches no application state — and it is
**off by default**, so `./gradlew run` is unchanged. `tick` is the simulated clock, never wall
time. `performative` is the literal `-` on this branch and stays that way: the baseline has no
performatives at all, and the channel-to-`ACLMessage` mapping belongs to the JADE port.

```bash
./gradlew traceCheck     # run a short bounded simulation and verdict the trace it produced
```

verifies that all fifteen channels appear, that the round-trips balance, and — the part that
matters — that the trace is **complete**: train ids contiguous, every announced train with its
`generated` line, every station and track with its opening state.

**A golden cannot be recorded from this trace verbatim.** Five numeric families derive from a
wall-clock-driven simulated clock and change on every run; they are named in the doc, for
[#21](https://github.com/bedaHovorka/OpenCybele1/issues/21) to project away.

The format is a **contract**: it is re-emitted by the JADE and Jason ports and has to produce
byte-identical lines for equivalent behaviour. Field semantics, the per-channel table, the
`Station.Info` aliasing hazard and the probe-on/probe-off perturbation measurement:
[`docs/trace-format.md`](docs/trace-format.md).

### Scenario configuration (`sim.*`)

Every simulation parameter that used to be a hardcoded literal — arrival rate, station
capacities, track delays, network topology, clock start and pace, the GUI's pace buttons — is
read from a `sim.*` Java system property whose **default is that literal**. A run with nothing
set behaves exactly as it did before. Full key table, rationale and evidence:
[`docs/scenario-config.md`](docs/scenario-config.md).

```bash
./gradlew run -Dsim.arrival.lambdaMs=2000                     # one knob
./gradlew run -Dsim.config=scenarios/short.properties         # a scenario file
./gradlew run -Dsim.config=scenarios/short.properties -Dsim.clock.pace=1   # file + override
```

Precedence is `-D` > scenario file > built-in default; `./gradlew run` forwards `-Dsim.*` into
the forked application JVM. Ready-made scenarios are in [`scenarios/`](scenarios).
`build/install/opencybele/bin/opencybele` takes the same flags through `OPENCYBELE_OPTS`, and
so does `docker compose up app`.

The configuration is parsed and validated in `Main.main` **before the kernel starts**, so a bad
value is an ordinary uncaught exception with **exit status 1** — the one failure mode in this
codebase where the exit status can be trusted (contrast the assertion behaviour below). The
resolved configuration is printed to **stderr** at startup, marking anything non-default;
stdout stays byte-for-byte as it was, because that is where the trace a golden diffs lives.

Two couplings are worth knowing about before changing anything:

- **`LAMBDA` had two jobs** — the generator's mean inter-arrival time *and* the voting window
  and penalty quantum of `Station.computeDifference`. They are now
  `sim.arrival.lambdaMs` and `sim.station.voteWindowMs`, independent, both defaulting to 8500 ms.
  Shortening a scenario no longer silently retunes the station scheduling policy — but changing
  the window on purpose still does, so both belong in the [#24](https://github.com/bedaHovorka/OpenCybele1/issues/24)
  manifest.
- **The canvas states the topology a second time** and can only draw one straight main line plus
  branch stubs. That drift is documented rather than fixed (the GUI is outside the behavioural
  contract), but a configured topology the canvas cannot represent produces a loud stderr banner
  at startup and a red mismatch list plus red `??` gaps on the canvas — never a plausible-looking
  wrong picture.

### Reproducing a run (`sim.random.masterSeed`)

Every random draw comes from a per-agent stream derived from one master seed. The default is
to **draw** a seed, so an ordinary run keeps varying — but the drawn seed is printed to stderr
with the command to replay it:

```
--- no sim.random.masterSeed given; drew 5605042205657107861. Replay this run's random streams with:
---   -Dsim.random.masterSeed=5605042205657107861
```

```bash
./gradlew run -Dsim.random.masterSeed=20080415        # pin the streams
./gradlew rngProof                                    # determinism check, ~1 s (also runs in `build`)
./gradlew rngProof -Prng.full                         # the exhaustive sweep, ~15 s
./gradlew rngProof --args="plan 20080415 20"          # predict that seed's trains, offline
```

Startup also prints a **stream table** to stderr — every stream name with the seed it will
run on — so a captured run carries a complete manifest of its randomness.

> `build/install/opencybele/bin/opencybele -Dsim.random.masterSeed=…` **does not work and does
> not complain**: the generated start script passes `$@` to the program, not to the JVM, so the
> run draws a fresh seed while looking pinned. Use `OPENCYBELE_OPTS=-Dsim.random.masterSeed=…`.
> `./gradlew run -D…` is fine.

Same seed ⇒ same per-agent draw sequences, on any thread interleaving. It does **not** yet
mean the same stdout: departure timestamps come from a real-time clock, event ordering is
still unpinned ([#16](https://github.com/bedaHovorka/OpenCybele1/issues/16)), and above a
measured arrival-density ceiling the same seed departs a different *subset* of the same
generated trains — the pre-existing `START` race, not the RNG. What a fixed seed does and
does not pin, and how to keep a scenario below that ceiling, is measured in
[`docs/seeded-rng.md`](docs/seeded-rng.md).

### Assertions (`-ea`)

`run` enables assertions (`-ea` in `applicationDefaultJvmArgs`). The `assert` statements in `src/`
are the codebase's only invariant checks, and they encode real preconditions — every path member
having voted, a train arriving where it was routed, a path direction being resolvable. With
assertions off, a violated invariant is silent corruption; with them on, it is a failure.

On this JADE port a throwable out of a behaviour kills **that agent** (JADE prints a banner and two
stdout lines and the platform keeps running with a hole in it). That is different from the Cybele
baseline, where an `AssertionError` was swallowed by the kernel and the process exit status never
changed — see [`docs/assertion-triage.md`](docs/assertion-triage.md) for the baseline measurement
and [`jade/README.md`](jade/README.md) for what the port does instead. Goldens were recorded with
`-ea` on ([`parity-tests/golden/MANIFEST.md`](parity-tests/golden/MANIFEST.md)).

`applicationDefaultJvmArgs` is baked into the generated start script too
(`build/install/opencybele/bin/opencybele`), so the Docker image runs with assertions on as well.
That script appends `JAVA_OPTS` and `OPENCYBELE_OPTS` *after* `DEFAULT_JVM_OPTS`, so assertions can
be turned off there without touching the build:

```bash
OPENCYBELE_OPTS=-da build/install/opencybele/bin/opencybele
```

`./gradlew run` has **no** such escape hatch — disabling assertions on that path requires editing
`build.gradle.kts`.

**Enabling `-ea` is itself a behaviour change.** A path that previously continued silently past a
broken invariant now throws instead. That is the desired trade for local development and CI, but it
means the assertions-on/assertions-off setting is a property of the *scenario being recorded*, not a
free-standing preference.

> Cybele-only launch concerns (`--patch-module java.base=cybelle`, `Local;NoSerialization`, the
> vendor-jar bootstrap) live on branch `opencybele-baseline` and in
> [`docs/kernel-config.md`](docs/kernel-config.md). They are not part of this branch (#82).

## Run with Docker

A `Dockerfile`/`docker-compose.yml` build and run the app in a container, forwarding the Swing GUI
to an X server on the host. The image is a multi-stage build: a JDK 21 builder stage
(`./gradlew installDist` — JADE comes from Maven Central), then a slim JRE 21 runtime stage.
Docker here is a convenience/reproducibility option, not a requirement for a working JVM.

**Prerequisites**: Docker + Docker Compose, and an X server (native on Linux; [XQuartz](https://www.xquartz.org/) on macOS; [VcXsrv](https://sourceforge.net/projects/vcxsrv/) or Xming on Windows).

**Linux/macOS**:
```bash
xhost +local:docker
docker compose up app
xhost -local:docker   # revoke access again once done
```

**Windows**:
1. Start VcXsrv via XLaunch: "Multiple windows", display number `0`, "Start no client", check "Disable access control".
2. Allow VcXsrv through the Windows Firewall.
3. Run:
   ```
   docker compose run app
   ```
   The container defaults `DISPLAY` to `host.docker.internal:0`, routing X11 over TCP to VcXsrv on the Windows host.

## Continuous integration

`.github/workflows/ci.yml` (named `characterization-opencybele.yml` until #40) runs **four jobs** on every push to, and pull request against, `develop`/`jade-develop`:

| Job | What it does |
|---|---|
| `build@this-ref` | `./gradlew build` on the triggering ref: compilation, the 321-test L1 lane, `domainPurity` |
| `integrationTest@this-ref` | the 17-test L2 lane — real in-process JADE containers, one JVM per class |
| `characterizationIT@opencybele` | the **drift guard**: checks out this branch (the parity harness) *and* `opencybele-baseline` (the frozen OpenCybele application), recovers and installs the vendored Cybele jars **from that checkout's** `scripts/bootstrap-vendor-jars.sh`, builds the application with `installDist`, and runs the L3 golden-master suite against it via `-Popencybele.dist` |
| `characterizationIT@jade` | the **port guard**: the same suite and the same untouched goldens, against the JADE application built from this ref (`installDist`, `-Pjade.dist`) — no Cybele bootstrap |

Every run is headless and needs no display server. The JADE lane is judged against a *recorded measurement* rather than against "all five scenarios green" — `opencybele-strict` fails its golden deterministically, for a reason #39 measured and classified as a normalizer gap that cannot be closed. See [`docs/ci.md`](docs/ci.md) §10 for why, what that costs, and what re-measuring takes.

Two things about it are worth knowing before reading the YAML. Without `-Popencybele.dist` the end-to-end test is *skipped* and Gradle still exits 0, so the job asserts out of the JUnit XML that the scenario really ran rather than trusting the exit status. And the baseline carries a measured ~1-in-45 nondeterministic wedge (`DEF-22`) whose failure is indistinguishable from real behavioural drift except by re-running, so the end-to-end class — and only that class — gets one loudly announced retry.

Full write-up, including how to reproduce the job locally: [`docs/ci.md`](docs/ci.md).

## Documentation

- [`docs/scenario-config.md`](docs/scenario-config.md) — the `sim.*` parameter surface, the `LAMBDA` split, the canvas drift, and the short-scenario evidence
- [`docs/assertion-triage.md`](docs/assertion-triage.md) — all assertion sites, and what Cybele does when one fires
- [`docs/seeded-rng.md`](docs/seeded-rng.md) — the per-agent seeded RNG, the interleaving-independence proof, and what still blocks whole-run determinism
- [`docs/headless-and-stop.md`](docs/headless-and-stop.md) — headless mode, the bounded stop condition, the exit-code table, and the kernel clock-registration race that removing the GUI exposes
- [`docs/trace-format.md`](docs/trace-format.md) — the canonical parity trace: the line format as a cross-framework contract, the fifteen channels, the aliased-payload hazard, and the probe-on/probe-off perturbation measurement
- [`docs/message-ontology.md`](docs/message-ontology.md) — the JADE message ontology: all fifteen channels mapped to an addressing mode, a FIPA performative, a payload record and a `MessageTemplate`; the topic-granularity decision; the `ENTER` payload asymmetry
- [`docs/clock-abstraction.md`](docs/clock-abstraction.md) — simulated time as an object: the `SimClock`/`AgentClock` design, absolute simulated deadlines, the pace decision, the virtual clock, and the measurement that showed the `pauseClock`/`resumeClock` idiom is not a mutex
- [`docs/parity-triage-jade.md`](docs/parity-triage-jade.md) — the L3 triage log for the JADE port: every diff, its classification, the burst-boundary evidence, and the seven candidate repairs that were measured and rejected

### The Phase-1 wrap-up

- [`jade/README.md`](jade/README.md) — **the JADE port's mapping notes**: which Cybele activity became which JADE behaviour, per agent; where the planning docs' mapping table was wrong; and the explicit list of differences the goldens cannot see (threading model, internal scheduling, conversation ids, container topology, the JICP socket, and the `TRAVEL_LEFT`/`TRAVEL_RIGHT` label that no golden can pin)
- [`docs/comparison-log-phase1.md`](docs/comparison-log-phase1.md) — **the Phase-1 comparison log**: LOC (implementation vs test, and what it does not include), per-agent port effort, what did not map cleanly, runtime observations, the JADE distribution decision with its two retractions, the Definition-of-Done verdict, and the numbers that disagree with each other

`dokumentace.pdf` and `prezentace.pdf` (in Czech) are the original project documentation and presentation submitted for the course.

## Author

Bedřich Hovorka (xhovor07@stud.fit.vutbr.cz)
