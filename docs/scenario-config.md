# Scenario configuration

> Produced for [#18](https://github.com/bedaHovorka/OpenCybele1/issues/18) (*Externalise timing and
> topology configuration*), on the Cybele baseline branch.
> Consumed by [#12](https://github.com/bedaHovorka/OpenCybele1/issues/12)/[#13](https://github.com/bedaHovorka/OpenCybele1/issues/13)
> (parity harness — this is the surface `ScenarioRunner` drives),
> [#17](https://github.com/bedaHovorka/OpenCybele1/issues/17) (stop condition — the missing
> key in the table below) and [#24](https://github.com/bedaHovorka/OpenCybele1/issues/24)
> (run manifest — `describe()` prints exactly what belongs in it).
>
> **Headline: nothing about the default run changed.** Every parameter that used to be a
> literal is now a `sim.*` system property whose default is that literal. The one structural
> change is that `Generator.LAMBDA`'s **two jobs are now two parameters**, both defaulting to
> 8500 ms — see [The `LAMBDA` split](#the-lambda-split), which is the substance of #18.

## The surface

Transport is **Java system properties**, because the harness launches a child JVM through
`ProcessBuilder` and passing `-D` flags on the command line is the least machinery that can
work. A `java.util.Properties` file may pre-fill them:

```bash
# a scenario file
./gradlew run -Dsim.config=scenarios/short.properties

# one knob, no file
./gradlew run -Dsim.arrival.lambdaMs=2000

# a file plus an override; the -D wins
./gradlew run -Dsim.config=scenarios/short.properties -Dsim.clock.pace=1
```

Precedence is `-D` > scenario file > built-in default. No YAML, no dependency: the file is
read by `java.util.Properties.load`.

`./gradlew run` forwards `-Dsim.*` from the Gradle command line into the forked application
JVM (`build.gradle.kts`). Without that forwarding the property would land on the Gradle
daemon and the simulation would silently run with defaults — a failure mode worth knowing
about, because it looks exactly like "the config did nothing".

| Key | Default | What it replaced |
|---|---|---|
| `sim.arrival.lambdaMs` | `8500` | `Generator.LAMBDA` — mean inter-arrival time |
| `sim.arrival.firstFireMs` | `1000` | `Generator` ctor `setTimer(..., 1000, ...)` |
| `sim.arrival.pairs` | `stA>stB,stA>stC,stB>stA,stB>stC,stC>stB,stC>stA` | `Generator.hhh` |
| `sim.station.voteWindowMs` | `8500` | `Generator.LAMBDA` — **again**, in `Station.computeDifference` |
| `sim.topology` | `stA-stH:tr1,stH-stG:tr2,stG-stE:tr3,stE-stD:tr4,stD-stB:tr5,stF-stE:tr6,stC-stF:tr7` | `RailwayMainAgent` `net.put` calls |
| `sim.station.capacities` | `stA=6,stB=5,stC=2,stD=2,stE=5,stF=2,stG=3,stH=2` | `RailwayMainAgent.stationCapacities` |
| `sim.road.delaysSec` | `tr1=1,tr2=1,tr3=5,tr4=2,tr5=3,tr6=4,tr7=3` | `RailwayMainAgent.roadDelays` |
| `sim.clock.startMs` | `0` | `Cybele.createClock(..., 0, 1)` |
| `sim.clock.pace` | `1` | `Cybele.createClock(..., 0, 1)` |
| `sim.gui.paces` | `Fast=8,Normal=1,Slow=0.3` | `Gui.createBar` toolbar buttons |
| `sim.gui.mainLine` | `stA,stH,stG,stE,stD,stB` | `RailwayCanvas.mainRoads` |
| `sim.gui.branches` | `stC:tr7,stF:tr6` | `RailwayCanvas.paint` branch coordinates |

`sim.config` names the optional file. Ready-made scenarios live in [`scenarios/`](../scenarios):
`default.properties` (every default written out, meant to be copied),
`short.properties` and `short-arrivals-only.properties` (below).

Ordering is significant in three places and is preserved as written:
`sim.topology` is the graph **insertion** order, which the HashMap-backed iteration order
downstream inherits (see [#19](https://github.com/bedaHovorka/OpenCybele1/issues/19));
`sim.arrival.pairs` is **indexed by the RNG**, so reordering it changes which train goes where;
`sim.gui.paces` is display order.

### Validation happens in `main`, on purpose

`ScenarioConfig.load()` is called from `Main.main` **before** `Cybele.startUp()`, and it parses
and checks everything eagerly. That placement is not stylistic. Per
[`assertion-triage.md` § Result 3](assertion-triage.md), Cybele invokes agent constructors and
handlers reflectively and its `IAIAgentThread` swallows the resulting
`InvocationTargetException` to **stderr without changing the exit status** — a bad value
discovered inside `Station`'s constructor would produce a stack trace nobody's runner checks
and a simulation that limps on. Discovered in `main`, the same bad value is an ordinary
uncaught exception with **exit status 1**:

```
$ opencybele -Dsim.station.capacities=stA=6,stB=5,stC=2,stD=2,stE=5,stF=2,stG=3
Exception in thread "main" java.lang.IllegalArgumentException: sim.station.capacities: no entry for station 'stH' declared in sim.topology
	at cz.vutbr.fit.ags.xhovor07.ScenarioConfig.checkKeySet(ScenarioConfig.java:468)
	...
	at cz.vutbr.fit.ags.xhovor07.Main.main(Main.java:31)
$ echo $?
1
```

Checked: numeric ranges; that capacities and delays name exactly the stations and tracks in
`sim.topology` (a typo is an error, not a silently ignored entry); that the topology is a
simple graph with unique track names; that every `sim.arrival.pairs` endpoint is a real station
and is actually **routable** — otherwise `Planning.planTrain` would fail deep inside an agent,
where it is invisible; and that the file contains no unknown keys.

`load()` then republishes every resolved value into the system properties, so a later
`ScenarioConfig.get()` on any thread resolves the identical values without re-reading the file.

### The startup banner

`Main` prints the fully resolved configuration to **stderr**, one `key = value` line per
parameter, marking anything that differs from its default:

```
--- scenario configuration ---
sim.arrival.lambdaMs = 500    # default: 8500
sim.arrival.firstFireMs = 200    # default: 1000
...
------------------------------
```

Stderr, not stdout, deliberately: stdout carries the `Planning`/`Train` println stream that a
golden will be diffed against, and it stays byte-for-byte as it was. The banner is the natural
input to the #24 run manifest — it is the run's parameters in a form that can be pasted back
into a scenario file.

## The `LAMBDA` split

This is the part of #18 that is not plumbing.

`Generator.LAMBDA = 8500` had **two unrelated jobs**:

1. the mean of the generator's `Exp(1/λ)` inter-arrival draw (`Generator.java:65`);
2. the **voting window and penalty quantum** of `Station.computeDifference` — it counted trains
   already planned within `time ± LAMBDA`, penalised a full station by `±LAMBDA/3`, and an
   un-full one by `plannedTrains*LAMBDA/6` (`Station.java:131-139`).

Job 2 is scheduling *policy*. So the obvious way to make a scenario finish in seconds —
shorten `LAMBDA` — silently rewrote the very behaviour a golden is supposed to pin. A harness
that recorded a golden at `LAMBDA=500` and compared a port against it would be comparing
against a policy that never ran in the interactive demo.

**Decision: split (option (a) in the issue).** `sim.arrival.lambdaMs` and
`sim.station.voteWindowMs` are independent, and **both default to 8500**, so with no
configuration the arithmetic is identical — `voteWindow` is now a `long` where `LAMBDA` was an
`int`, and every expression involved (`±w/3`, `plannedTrains*w/6`, `time ± w`) was already
evaluated in `long` context at the same magnitudes, so no value changes.

Why split rather than pin-and-document:

- Pinning does not remove the hazard, it only names it. The whole point of 1-PRE is that a
  scenario should be able to run in seconds; with the two pinned, "run it faster" and "change
  the scheduling policy" remain the same edit, and the next person makes the same mistake.
- Split, the two short scenarios below become *different, statable* experiments rather than one
  ambiguous one. "Arrivals 17× denser, policy untouched" is a sentence you can write down and
  record a golden for. Before, it was not expressible at all.
- It costs nothing at the defaults: 8500 and 8500.

**What the split does not do** is make short scenarios free. Scaling the arrival rate without
the window still changes the *observed* schedule, because the network is more loaded. It just
makes the difference an explicit choice instead of a side effect. The rule for #22/#24: both
values belong in the manifest, and a recording made at one pair must never be compared against
a replay made at another.

## The canvas topology duplication

`RailwayCanvas` states the topology a second time. It draws one straight main line
(`sim.gui.mainLine`) plus branch stubs (`sim.gui.branches`) at hand-computed coordinates; the
n-th branch (1-based) sits at `x = leftSpace + n*(roadWidth+stationWidth)`,
`y = 50 + (n-1)*100`, which is exactly where the two hardcoded `paintSecondRoad` calls used to
put `stC`/`tr7` and `stF`/`tr6`.

**It cannot draw an arbitrary graph, and it is not going to learn how.** The GUI is outside the
behavioural contract — goldens come from the trace, not the screen — so per #18 documenting
this drift is acceptable. What is *not* acceptable is drawing a network that is not the one
being simulated. So:

- `ScenarioConfig` computes the mismatch set between `sim.topology` and what the layout covers:
  stations or tracks never drawn, canvas-adjacent stations with no track between them,
  anything drawn twice, anything named that does not exist.
- `Main` prints every mismatch to stderr at startup, under a `!!! GUI TOPOLOGY MISMATCH !!!`
  banner, and says the simulation is unaffected.
- `RailwayCanvas` paints the same list on the canvas in red, and draws a main-line pair with no
  track between it as a red crossed `??` gap rather than an innocuous blank.

Concretely, `-Dsim.gui.mainLine=stA,stG,stE,stD,stB` against the default topology gives:

```
!!! GUI TOPOLOGY MISMATCH - the canvas cannot draw the configured network !!!
!!!   sim.gui.mainLine: stA and stG are adjacent on the canvas but not connected in sim.topology
!!!   stations not drawn: [stH]
!!!   tracks not drawn: [tr1, tr2]
!!! The simulation is unaffected; the drawing is incomplete. See docs/scenario-config.md.
```

and a canvas showing `stA ✕✕ stG — stE — stD — stB` with the gap in red, while the simulation
below it keeps running the full eight-station network. Mismatches are **warnings, not errors**:
refusing to start because the drawing is imperfect would block exactly the scenario work this
change exists to enable.

One assertion was removed to make this possible: `RailwayCanvas.java:101`'s
`assert road != null`, which `assertion-triage.md` records as exercised 1 430 times in run 6.
With a hardcoded layout that was a genuine program invariant; with a configurable one it is a
property of the configuration, and it would have fired on the AWT event dispatch thread where —
per the same triage — it is swallowed with a different banner and no Cybele framing. The red
`??` gap replaces it and is strictly more visible. The count in `assertion-triage.md` therefore
drops from 33 assertion sites to 32; see the amendment at the end of that document.

## Short scenarios

Two levers, and they are not the same lever:

- **`sim.clock.pace`** compresses *wall-clock* time and changes no simulated-time parameter at
  all. `-Dsim.clock.pace=8` gave **28 departures in 30 s of wall clock, covering 233 s of
  simulated time**, against 21 departures in 200 s of wall clock for the untouched default.
  Same schedule, ~8× the rate. The catch: the original documentation reports the message-loss
  defect ([#22](https://github.com/bedaHovorka/OpenCybele1/issues/22)) is worst in "Fast" mode.
- **`sim.arrival.lambdaMs`** (and, if you say so, `sim.station.voteWindowMs`) compresses
  *simulated* time and does change what is simulated.

`scenarios/short.properties` uses both — pace 8, λ 500, window 500 — and is the answer to
"a short scenario must run in seconds":

```bash
OPENCYBELE_OPTS=-Dsim.config=scenarios/short.properties \
  timeout 15 build/install/opencybele/bin/opencybele
```

| Run | Wall clock | Simulated | Departures | Trains generated |
|---|---|---|---|---|
| default (no config) | 200 s | 196 s | 21 | 21 |
| default (no config) | 90 s | 81 s | 10 | 10 |
| `short.properties` | **15 s** | 114 s | **27** | ~253 |
| `short.properties` | 15 s (repeat) | — | 24 | ~195 |
| `short.properties` | 30 s | 227 s | 51 | ~390 |

**More departures in 15 seconds than the default produced in 200** — roughly 17× the throughput
per wall-clock second. Run-to-run spread (24 vs 27 over the same 15 s) is the unseeded RNG;
see [What this does not do](#what-this-does-not-do).

`scenarios/short-arrivals-only.properties` is the control: pace 8, λ 500, window left at its
8500 ms default. Measured against `short.properties` over 30 s: 45 departures versus 50, out of
~470 trains generated in both. At this arrival rate the throughput ceiling turns out to be the
`Planning`/kernel round-trip rather than the voting window — what the window moves is the
*shape* of the schedule, which is precisely what a golden pins. That the two files differ at
all, and that the difference can be stated, is the payoff of the split.

## What this does not do

- **No stop condition.** Every run above is `timeout`-truncated;
  [#17](https://github.com/bedaHovorka/OpenCybele1/issues/17) owns that, and until it lands
  a "short scenario" is short only in the sense that it does more per second.
- **No seeded RNG.** [#15](https://github.com/bedaHovorka/OpenCybele1/issues/15) owns it.
  Consequently the parity evidence for "defaults are unchanged" below is distributional, not
  byte-exact.
- **No HashMap iteration-order fix** ([#19](https://github.com/bedaHovorka/OpenCybele1/issues/19)).
  `sim.topology` preserves the historical insertion order so nothing moves, but the underlying
  order-dependence is untouched.
- **`cybelle/ICS.prop` is not touched** ([#16](https://github.com/bedaHovorka/OpenCybele1/issues/16)).
- **The `RoadAgent` travel jitter** (`500 * nextGaussian()`, `RoadAgent.java:101`) is *not*
  externalised. It is an RNG draw, not a timer period, and it belongs with #15.

## Evidence that the defaults are unchanged

**Exact comparison is impossible today.** The RNG is unseeded (#15), so two runs of the *same*
binary differ. The claim below is therefore distributional, and it is stated as such.

Method: `git archive HEAD` of the pre-change tree into a scratch directory, built and run with
the same JDK 21 / Gradle 8.10.2 / `-ea --patch-module` as this branch, `timeout 200` on the
`installDist` start script with stdout and stderr captured separately; then the same for this
branch with no `sim.*` property set. The measured stream is `Planning.java:125`'s
`<train> in <station> at <t>` println.

| | departures | first (ms) | last (ms) | mean gap (ms) | destinations |
|---|---|---|---|---|---|
| before (`HEAD`) | 21 | 1018 | 198 207 | 9 859 | stA 5, stB 8, stC 8 |
| after (defaults) | 21 | 1024 | 196 373 | 9 767 | stA 8, stB 6, stC 7 |

Same count over the same wall clock; mean inter-departure gap within 1 % of each other and of
the configured λ = 8500 ms (the excess over λ is the voting delay); first departure at
`firstFireMs` + first vote in both; destinations spread over the same three stations with the
sampling spread expected of 21 unseeded draws from six pairs. Both streams show the same
out-of-order artefact (`vl7` before `vl6`; `vl20` before `vl19`) — the `PriorityBlockingQueue`
in `Planning`, unchanged. Neither run produced an `AssertionError` or any stderr output beyond
the new banner. The GUI was screenshotted in both and draws the identical layout.

Once #15 lands, this table should be replaced by a byte-exact diff at a fixed seed. Until then
it is sampled evidence, not proof — the same caveat `assertion-triage.md` carries.
