# OpenCybele1

A Swing GUI simulation of trains moving through a small railway network, built as a set of communicating agents on top of **Cybele**, a multi-agent kernel from IAI. Written for the AGS (Multi-Agent Systems) course at FIT VUT Brno, 2007/08.

Stations and single-track road segments are simulated as agents that negotiate train departure times through a distributed voting protocol, then move trains along the network while respecting station capacity and track occupancy. A Swing canvas visualizes station occupancy and track state live, alongside a table of in-flight trains.

## Baseline environment

This is the reference environment the baseline is built and run in. Anything else is untested.

| | |
|---|---|
| **Build tool** | Gradle **8.10.2**, via the checked-in wrapper (`./gradlew`, `gradlew.bat`) — do not substitute a system Gradle |
| **Java** | Gradle **toolchain 21** (`build.gradle.kts`); verified on Temurin/OpenJDK 21 |
| **Required JVM flag** | `--patch-module java.base=cybelle` — mandatory, see [Why `--patch-module`](#why---patch-module) |
| **Required Cybele config** | `cybele.srv.comm.app.param.iai = Local;NoSerialization` in `cybelle/cybele.prop` — mandatory, see [Why `Local;NoSerialization`](#why-localnoserialization) |
| **Assertions** | `-ea` is on for `run`, and baked into the `installDist` start script and the Docker image — see [Assertions (`-ea`)](#assertions--ea) and [`docs/assertion-triage.md`](docs/assertion-triage.md) |
| **Vendor jars** | `com.iai:cybele-api:1.0`, `com.iai:cybele-impl:1.0` in the local Maven repository — see [One-time setup](#one-time-setup-install-the-vendor-jars) |
| **Scenario config** | `sim.*` system properties, defaults identical to the historical literals — see [Scenario configuration](#scenario-configuration-sim) and [`docs/scenario-config.md`](docs/scenario-config.md) |
| **Randomness** | per-agent streams from one master seed, `-Dsim.random.masterSeed`, defaulting to a drawn (printed) seed — see [Reproducing a run](#reproducing-a-run-simrandommasterseed) and [`docs/seeded-rng.md`](docs/seeded-rng.md) |
| **Tests** | no test suite; verification is manual through the Swing GUI, plus `./gradlew rngProof` for the RNG determinism check |

## Requirements

- JDK 21 and Gradle (a wrapper is included: `./gradlew`/`gradlew.bat`)
- The Cybele kernel jars, vendored in `cybelle/` (`Cybele.jar`, `CybeleImpl.jar`, plus its `ICS.prop`/`cybele.prop` runtime config) — not tracked in git (see below), installed to your local Maven repository (`~/.m2`) instead
- Maven (`mvn`) is **optional** — the bootstrap script below uses it when present and falls back to installing the jars itself when it isn't
- A running X server for the Swing GUI

## One-time setup: install the vendor jars

`Cybele.jar`/`CybeleImpl.jar` are 2008-era binaries from IAI with no public Maven repo, so they aren't committed to git. They're still recoverable from the `withoutGradle` tag and get installed into your local Maven repository (`~/.m2`), which Gradle then resolves them from like any other dependency.

That whole procedure is scripted:

```bash
scripts/bootstrap-vendor-jars.sh
```

The script:

- restores `cybelle/Cybele.jar` and `cybelle/CybeleImpl.jar` from the `withoutGradle` tag if they're not already in the working tree (and leaves them untracked, as `.gitignore` intends);
- installs them as `com.iai:cybele-api:1.0` and `com.iai:cybele-impl:1.0` into the local Maven repository;
- **does not require `mvn` on `PATH`.** If Maven is available it shells out to `mvn install:install-file`; if not, it writes the repository layout (jar + a minimal generated POM) directly. Both paths produce the same on-disk result, which `mavenLocal()` resolves identically;
- is **idempotent** — re-running it is a no-op once both artifacts are installed and match. `--force` reinstalls anyway; `--verify-only` checks that both artifacts are installed and exits, writing nothing and leaving the working tree untouched (it will *not* restore missing jars — the two flags are mutually exclusive);
- resolves the target repository from `MAVEN_REPO_LOCAL`, else `<localRepository>` in `~/.m2/settings.xml` (XML comments stripped first — Maven's own shipped `settings.xml` carries a commented-out `/path/to/local/repo` example that a naive grep picks up), else `~/.m2/repository`, and logs which one it chose. **`MAVEN_REPO_LOCAL` is a script-side override only**: Gradle's `mavenLocal()` does not read it, so if you point it somewhere non-default you must also pass a matching `-Dmaven.repo.local` to Gradle or the build will not find what was just installed;
- fails with an actionable message rather than a stack trace when the tag is missing, the clone has no `.git`, or the destination isn't writable.

It is also the entry point used by the [`Dockerfile`](Dockerfile) builder stage — no separate Maven install step is needed anywhere. It is designed to be the single call CI makes to prepare a build, but **no CI workflow exists in this repository yet**; wiring one up is [#25](https://github.com/bedaHovorka/OpenCybele1/issues/25).

## Build

```bash
./gradlew build
```

## Run

```bash
./gradlew run
```

This launches `cz.vutbr.fit.ags.xhovor07.Main` with the two jars above on the classpath (resolved from `~/.m2`) plus the JVM flags `-ea` and `--patch-module java.base=cybelle`, configured as `applicationDefaultJvmArgs` in `build.gradle.kts`; Cybele reads its `ICS.prop`/`cybele.prop` config from `cybelle/` via that flag. A Swing window opens on launch showing the railway network, live station/track state, and a table of trains currently in transit. There is no automated test suite — verification is manual, through the GUI.

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

`run` enables assertions (`-ea` in `applicationDefaultJvmArgs`). The 32 `assert` statements in `src/` are the codebase's only invariant checks, and they encode real preconditions — every path member having voted, a train arriving where it was routed, a path direction being resolvable. With assertions off, a violated invariant is silent corruption; with them on, it is a logged failure.

Note how Cybele treats one. An exception thrown out of an agent event handler is caught by `com.iai.cybele.thmgmt.IAIAgentThread` — but its catch list is five named types, *not* `Throwable`. An `AssertionError` survives only because `Method.invoke` wraps it in an `InvocationTargetException`, which is on that list. It is then printed by `com.iai.cybele.exception.IAIExceptionHandler` **to `System.err`** (twice per failure), and the simulation continues. So a firing assertion does *not* abort the process or change the exit status.

Three practical consequences, all measured: output diffing must **capture stderr** (nothing appears on stdout); the exit status is worthless as a pass/fail signal; and a throwable inside a timer handler such as `Generator.generateTrain` stops train generation **permanently and silently**, because the method re-arms its own timer as its last statement. Full mechanism, evidence and the rules a scenario runner must follow are in [`docs/assertion-triage.md`](docs/assertion-triage.md) § Result 3.

Full triage of all 32 assertion sites — which fire, which are merely never reached, and how many times each is evaluated in a normal run — is in [`docs/assertion-triage.md`](docs/assertion-triage.md). Summary: **none fires**; 24 sites are exercised and hold, 8 are never reached (2 of those deliberately). The triage was recorded against 33 sites before [#18](https://github.com/bedaHovorka/OpenCybele1/issues/18) removed `RailwayCanvas`'s `assert road != null`; see that document's amendment, which also gives the corrected `grep` exclusion list — a naive `grep -c 'assert '` now yields 33, not 32.

`applicationDefaultJvmArgs` is baked into the generated start script too (`build/install/opencybele/bin/opencybele`), so the Docker image runs with assertions on as well. That script appends `JAVA_OPTS` and `OPENCYBELE_OPTS` *after* `DEFAULT_JVM_OPTS`, so assertions can be turned off there without touching the build:

```bash
OPENCYBELE_OPTS=-da build/install/opencybele/bin/opencybele
```

`./gradlew run` has **no** such escape hatch — disabling assertions on that path requires editing `build.gradle.kts`. #22 should be aware of the asymmetry: it is exactly the sort of thing that produces a golden recorded under different assertion settings than the label claims.

**Enabling `-ea` is itself a behaviour change.** A path that previously continued silently past a broken invariant now throws instead. That is the desired trade for local development and CI, but it means the assertions-on/assertions-off setting is a property of the *scenario being recorded*, not a free-standing preference.

> **Scope boundary — deliberately not decided here.** This project enables `-ea` for `run`. It does **not** decide whether golden recordings are captured with assertions on or off. That decision belongs to [#22](https://github.com/bedaHovorka/OpenCybele1/issues/22) and must be written down in the [#24](https://github.com/bedaHovorka/OpenCybele1/issues/24) manifest alongside the other run parameters, so a recording can never be compared against a replay made under a different assertion setting.

### Why `Local;NoSerialization`

`cybelle/cybele.prop` sets `cybele.srv.comm.app.param.iai = Local;NoSerialization` so Cybele's comm service runs in local-only mode. Commented out — which is how the vendored file shipped — Cybele instead tries to reach an external `IAIDaemon`/license host that is not part of this vendored setup and never was reachable from here, and startup hangs or aborts with `Could not connect with IAIDaemon`. The setting is mandatory, not a tuning knob.

> `cybelle/ICS.prop` currently declares **two** `ICSBrowser` keys — an external IAI host (`63.122.105.110`) followed by `127.0.0.1`. Last-one-wins makes this harmless today, but reordering them would point startup at an external host. This is not intentional configuration; cleanup is tracked in [#16](https://github.com/bedaHovorka/OpenCybele1/issues/16).

### Why `--patch-module`

Cybele's kernel jar loads `cybele.prop` via `props.getClass().getResourceAsStream("/cybele.prop")`, where `props` is a `java.util.Properties` — a class loaded by the bootstrap class loader as part of the `java.base` platform module. Before the Java Platform Module System (Java 9+), that call fell back to searching the application classpath, which is how this 2002-era kernel found `cybele.prop` sitting in `cybelle/`. Since JPMS, that classpath fallback no longer happens for module-loaded classes, so the lookup returns `null` and Cybele aborts with `Cannot find cybele.prop at class path` — regardless of JDK version. `--patch-module java.base=cybelle` works around this by injecting `cybelle/`'s contents directly into the `java.base` module, so the same lookup succeeds again. No source or jar changes are needed. This only works against a plain directory on disk, not jar contents, which is why `cybele.prop`/`ICS.prop` stay as loose tracked files in `cybelle/` even though the two binary jars don't.

## Run with Docker

A `Dockerfile`/`docker-compose.yml` build and run the app in a container, forwarding the Swing GUI to an X server on the host. The image is a multi-stage build: a JDK 21 builder stage (runs `scripts/bootstrap-vendor-jars.sh`, then `./gradlew installDist`), then a slim JRE 21 runtime stage — Docker here is a convenience/reproducibility option, not a requirement for a working JVM.

Run `scripts/bootstrap-vendor-jars.sh` **on the host before building the image**. The build context excludes `.git` (see `.dockerignore`), so the script's recover-from-tag step cannot run inside the builder — the jars have to already be sitting in `cybelle/`. The builder still calls the same script (to install them into the image's local Maven repository), and it fails with exactly that instruction if they're absent.

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

## Documentation

- [`docs/scenario-config.md`](docs/scenario-config.md) — the `sim.*` parameter surface, the `LAMBDA` split, the canvas drift, and the short-scenario evidence
- [`docs/assertion-triage.md`](docs/assertion-triage.md) — all assertion sites, and what Cybele does when one fires
- [`docs/seeded-rng.md`](docs/seeded-rng.md) — the per-agent seeded RNG, the interleaving-independence proof, and what still blocks whole-run determinism
- [`docs/headless-and-stop.md`](docs/headless-and-stop.md) — headless mode, the bounded stop condition, the exit-code table, and the kernel clock-registration race that removing the GUI exposes

`dokumentace.pdf` and `prezentace.pdf` (in Czech) are the original project documentation and presentation submitted for the course.

## Author

Bedřich Hovorka (xhovor07@stud.fit.vutbr.cz)
