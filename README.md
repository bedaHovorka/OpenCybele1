# OpenCybele1

A Swing GUI simulation of trains moving through a small railway network, built as a set of communicating agents on top of **Cybele**, a multi-agent kernel from IAI. Written for the AGS (Multi-Agent Systems) course at FIT VUT Brno, 2007/08.

Stations and single-track road segments are simulated as agents that negotiate train departure times through a distributed voting protocol, then move trains along the network while respecting station capacity and track occupancy. A Swing canvas visualizes station occupancy and track state live, alongside a table of in-flight trains.

## Requirements

- JDK 21 (plain `javac`/`java` — no build tool is used)
- The Cybele kernel jars, vendored in `cybelle/` (`Cybele.jar`, `CybeleImpl.jar`, plus its `ICS.prop`/`cybele.prop` runtime config)

## Build

```bash
javac -d bin -cp "cybelle/Cybele.jar:cybelle/CybeleImpl.jar" $(find src/main -name "*.java")
```

## Run

Linux/macOS:
```bash
./xhovor07.sh
```

Windows:
```
xhovor07.bat
```

Both scripts launch `cz.vutbr.fit.ags.xhovor07.Main` with classpath `bin:cybelle:cybelle/Cybele.jar:cybelle/CybeleImpl.jar` plus the JVM flag `--patch-module java.base=cybelle`; Cybele reads its `ICS.prop`/`cybele.prop` config from `cybelle/` via that flag. A Swing window opens on launch showing the railway network, live station/track state, and a table of trains currently in transit. There is no automated test suite — verification is manual, through the GUI.

`cybelle/cybele.prop` sets `cybele.srv.comm.app.param.iai = Local;NoSerialization` so Cybele's comm service runs in local-only mode; without it, startup hangs/aborts trying to reach an external `IAIDaemon`/license host that isn't part of this vendored setup.

### Why `--patch-module`

Cybele's kernel jar loads `cybele.prop` via `props.getClass().getResourceAsStream("/cybele.prop")`, where `props` is a `java.util.Properties` — a class loaded by the bootstrap class loader as part of the `java.base` platform module. Before the Java Platform Module System (Java 9+), that call fell back to searching the application classpath, which is how this 2002-era kernel found `cybele.prop` sitting in `cybelle/`. Since JPMS, that classpath fallback no longer happens for module-loaded classes, so the lookup returns `null` and Cybele aborts with `Cannot find cybele.prop at class path` — regardless of JDK version. `--patch-module java.base=cybelle` works around this by injecting `cybelle/`'s contents directly into the `java.base` module, so the same lookup succeeds again. No source or jar changes are needed.

## Run with Docker

A `Dockerfile`/`docker-compose.yml` (on the `develop` branch) build and run the app in a container, forwarding the Swing GUI to an X server on the host, on the same `eclipse-temurin:21-jdk-noble` (Ubuntu 24.04 LTS) base as a native JDK 21 install — Docker here is a convenience/reproducibility option, not a requirement for a working JVM.

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
