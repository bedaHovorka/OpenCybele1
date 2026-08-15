# OpenCybele1

A Swing GUI simulation of trains moving through a small railway network, built as a set of communicating agents on top of **Cybele**, a multi-agent kernel from IAI. Written for the AGS (Multi-Agent Systems) course at FIT VUT Brno, 2007/08.

Stations and single-track road segments are simulated as agents that negotiate train departure times through a distributed voting protocol, then move trains along the network while respecting station capacity and track occupancy. A Swing canvas visualizes station occupancy and track state live, alongside a table of in-flight trains.

## Requirements

- JDK (plain `javac`/`java` — no build tool is used)
- The Cybele kernel jars, vendored in `cybelle/` (`Cybele.jar`, `CybeleImpl.jar`, plus its `ICS.prop`/`cybele.prop` runtime config)

## Build

```bash
javac -d bin -cp "cybelle/Cybele.jar:cybelle/CybeleImpl.jar" $(find src -name "*.java")
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

Both scripts launch `cz.vutbr.fit.ags.xhovor07.Main` with classpath `bin:cybelle:cybelle/Cybele.jar:cybelle/CybeleImpl.jar`; Cybele reads its `ICS.prop`/`cybele.prop` config from `cybelle/` on that classpath. A Swing window opens on launch showing the railway network, live station/track state, and a table of trains currently in transit. There is no automated test suite — verification is manual, through the GUI.

## Documentation

`dokumentace.pdf` and `prezentace.pdf` (in Czech) are the original project documentation and presentation submitted for the course.

## Author

Bedřich Hovorka (xhovor07@stud.fit.vutbr.cz)
