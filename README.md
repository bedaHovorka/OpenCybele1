# OpenCybele1

A Swing GUI simulation of trains moving through a small railway network, built as a set of communicating agents on top of **Cybele**, a multi-agent kernel from IAI. Written for the AGS (Multi-Agent Systems) course at FIT VUT Brno, 2007/08.

Stations and single-track road segments are simulated as agents that negotiate train departure times through a distributed voting protocol, then move trains along the network while respecting station capacity and track occupancy. A Swing canvas visualizes station occupancy and track state live, alongside a table of in-flight trains.

## Requirements

- JDK 21 and Gradle (a wrapper is included: `./gradlew`/`gradlew.bat`)
- The Cybele kernel jars, vendored in `cybelle/` (`Cybele.jar`, `CybeleImpl.jar`, plus its `ICS.prop`/`cybele.prop` runtime config) — not tracked in git (see below), installed to your local Maven repository (`~/.m2`) instead
- Maven (`mvn`), only for the one-time jar install step below

## One-time setup: install the vendor jars

`Cybele.jar`/`CybeleImpl.jar` are 2008-era binaries from IAI with no public Maven repo, so they aren't committed to git. They're still recoverable from the `withoutGradle` tag and get installed into your local Maven repository (`~/.m2`), which Gradle then resolves them from like any other dependency:

```bash
git checkout withoutGradle -- cybelle/Cybele.jar cybelle/CybeleImpl.jar

mvn install:install-file -Dfile=cybelle/Cybele.jar     -DgroupId=com.iai -DartifactId=cybele-api  -Dversion=1.0 -Dpackaging=jar
mvn install:install-file -Dfile=cybelle/CybeleImpl.jar -DgroupId=com.iai -DartifactId=cybele-impl -Dversion=1.0 -Dpackaging=jar
```

## Build

```bash
./gradlew build
```

## Run

```bash
./gradlew run
```

This launches `cz.vutbr.fit.ags.xhovor07.Main` with the two jars above on the classpath (resolved from `~/.m2`) plus the JVM flag `--patch-module java.base=cybelle`, configured as `applicationDefaultJvmArgs` in `build.gradle.kts`; Cybele reads its `ICS.prop`/`cybele.prop` config from `cybelle/` via that flag. A Swing window opens on launch showing the railway network, live station/track state, and a table of trains currently in transit. There is no automated test suite — verification is manual, through the GUI.

`cybelle/cybele.prop` sets `cybele.srv.comm.app.param.iai = Local;NoSerialization` so Cybele's comm service runs in local-only mode; without it, startup hangs/aborts trying to reach an external `IAIDaemon`/license host that isn't part of this vendored setup.

### Why `--patch-module`

Cybele's kernel jar loads `cybele.prop` via `props.getClass().getResourceAsStream("/cybele.prop")`, where `props` is a `java.util.Properties` — a class loaded by the bootstrap class loader as part of the `java.base` platform module. Before the Java Platform Module System (Java 9+), that call fell back to searching the application classpath, which is how this 2002-era kernel found `cybele.prop` sitting in `cybelle/`. Since JPMS, that classpath fallback no longer happens for module-loaded classes, so the lookup returns `null` and Cybele aborts with `Cannot find cybele.prop at class path` — regardless of JDK version. `--patch-module java.base=cybelle` works around this by injecting `cybelle/`'s contents directly into the `java.base` module, so the same lookup succeeds again. No source or jar changes are needed. This only works against a plain directory on disk, not jar contents, which is why `cybele.prop`/`ICS.prop` stay as loose tracked files in `cybelle/` even though the two binary jars don't.

## Run with Docker

A `Dockerfile`/`docker-compose.yml` (on the `develop` branch) build and run the app in a container, forwarding the Swing GUI to an X server on the host. The image is a multi-stage build: a JDK 21 + Maven + Gradle builder stage (installs the vendor jars and runs `./gradlew installDist`), then a slim JRE 21 runtime stage — Docker here is a convenience/reproducibility option, not a requirement for a working JVM. Since the vendor jars aren't in git, run the `git checkout withoutGradle -- ...` step above before building the image, so they're present in the build context for the builder stage to install.

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

`.github/workflows/characterization-opencybele.yml` runs the job **`characterizationIT@opencybele`** on every push to, and pull request against, `develop`/`jade-develop`, plus nightly. It checks out this branch (the parity harness) *and* `opencybele-baseline` (the frozen OpenCybele application), recovers and installs the vendored Cybele jars with `scripts/bootstrap-vendor-jars.sh`, builds the application with `installDist`, and runs the L3 golden-master suite against it via `-Popencybele.dist`. The run is headless and needs no display server.

Two things about it are worth knowing before reading the YAML. Without `-Popencybele.dist` the end-to-end test is *skipped* and Gradle still exits 0, so the job asserts out of the JUnit XML that the scenario really ran rather than trusting the exit status. And the baseline carries a measured ~1-in-45 nondeterministic wedge (`DEF-22`) whose failure is indistinguishable from real behavioural drift except by re-running, so the end-to-end class — and only that class — gets one loudly announced retry.

Full write-up, including how to reproduce the job locally: [`docs/ci.md`](docs/ci.md).

## Documentation

`dokumentace.pdf` and `prezentace.pdf` (in Czech) are the original project documentation and presentation submitted for the course.

## Author

Bedřich Hovorka (xhovor07@stud.fit.vutbr.cz)
