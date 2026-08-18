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
| **Assertions** | `-ea` is on for `run` — see [Assertions (`-ea`)](#assertions--ea) and [`docs/assertion-triage.md`](docs/assertion-triage.md) |
| **Vendor jars** | `com.iai:cybele-api:1.0`, `com.iai:cybele-impl:1.0` in the local Maven repository — see [One-time setup](#one-time-setup-install-the-vendor-jars) |
| **Tests** | none; verification is manual through the Swing GUI |

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
- is **idempotent** — re-running it is a no-op once both artifacts are installed and match. `--force` reinstalls anyway, `--verify-only` checks without writing;
- honours `MAVEN_REPO_LOCAL`, otherwise `<localRepository>` from `~/.m2/settings.xml`, otherwise `~/.m2/repository`;
- fails with an actionable message rather than a stack trace when the tag is missing, the clone has no `.git`, or the destination isn't writable.

It is also the entry point used by the [`Dockerfile`](Dockerfile) builder stage, and is the single call CI ([#25](https://github.com/bedaHovorka/OpenCybele1/issues/25)) should make to prepare a build — no separate Maven install step is needed anywhere.

## Build

```bash
./gradlew build
```

## Run

```bash
./gradlew run
```

This launches `cz.vutbr.fit.ags.xhovor07.Main` with the two jars above on the classpath (resolved from `~/.m2`) plus the JVM flags `-ea` and `--patch-module java.base=cybelle`, configured as `applicationDefaultJvmArgs` in `build.gradle.kts`; Cybele reads its `ICS.prop`/`cybele.prop` config from `cybelle/` via that flag. A Swing window opens on launch showing the railway network, live station/track state, and a table of trains currently in transit. There is no automated test suite — verification is manual, through the GUI.

The simulation has **no stop condition** — it generates trains until the process is killed (tracked in [#17](https://github.com/bedaHovorka/OpenCybele1/issues/17)). To run a bounded scenario, wrap it:

```bash
timeout 300 ./gradlew run --console=plain
```

### Assertions (`-ea`)

`run` enables assertions (`-ea` in `applicationDefaultJvmArgs`). The 33 `assert` statements in `src/` are the codebase's only invariant checks, and they encode real preconditions — every path member having voted, a train arriving where it was routed, a path direction being resolvable. With assertions off, a violated invariant is silent corruption; with them on, it is a logged failure.

Note how Cybele treats one: a `Throwable` raised inside an agent event handler is **caught, printed with a stack trace by `com.iai.cybele.thmgmt.IAIAgentThread`, and the simulation continues**. A firing assertion therefore does *not* abort the process or change the exit status — it aborts the remainder of that one handler invocation and leaves a trace in the log. This matters for anything that diffs run output.

Full triage of all 33 assertion sites — which fire, which are merely never reached, and how many times each is evaluated in a normal run — is in [`docs/assertion-triage.md`](docs/assertion-triage.md). Summary: **none fires**; 25 sites are exercised and hold, 8 are never reached (2 of those deliberately).

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

`dokumentace.pdf` and `prezentace.pdf` (in Czech) are the original project documentation and presentation submitted for the course.

## Author

Bedřich Hovorka (xhovor07@stud.fit.vutbr.cz)
