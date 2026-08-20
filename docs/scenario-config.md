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
./gradlew run -Dsim.config=scenarios/short.properties -Dsim.station.voteWindowMs=8500
```

(That last override is also the difference between `short.properties` and
`short-arrivals-only.properties`. Overriding `sim.clock.pace` back down to 1 works the same way,
but it costs you most of the run: pace is what buys simulated seconds per wall second, so a 15 s
`short.properties` run at pace 1 covers ~15 s of simulated time instead of ~115 s and produces a
handful of departures rather than 24–30. The per-simulated-second rate is unchanged — see
[Is `sim.clock.pace` behaviour-neutral?](#is-simclockpace-behaviour-neutral) — there is simply
much less simulated time in the run.)

Precedence is `-D` > scenario file > built-in default. No YAML, no dependency: the file is
read by `java.util.Properties.load`.

`./gradlew run` forwards `-Dsim.*` from the Gradle command line into the forked application
JVM (`build.gradle.kts`). Without that forwarding the property would land on the Gradle
daemon and the simulation would silently run with defaults — a failure mode worth knowing
about, because it looks exactly like "the config did nothing".

Two build-side notes that belong with it:

- The forwarding block reads `System.getProperties()` at **configuration** time. Gradle's
  configuration cache is not enabled in this project, but if it ever is, `--configuration-cache`
  could serve a stale set of forwarded properties. Use the `installDist` start script with
  `OPENCYBELE_OPTS` if that ever matters — it reads the environment at launch.
- `build.gradle.kts` now pins `options.encoding = "UTF-8"` on all `JavaCompile` tasks. This is a
  build-behaviour change, listed here because it was not requested by #18: previously the source
  encoding was inherited from the Gradle daemon's platform default. JDK 18+ already defaults to
  UTF-8, so nothing changes on this machine — but a branch whose purpose is reproducibility
  should not have a build that depends on the ambient locale.

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
| `sim.headless` | `false` | nothing — the GUI was built unconditionally ([#17](https://github.com/bedaHovorka/OpenCybele1/issues/17)) |
| `sim.stop.maxTrains` | `0` (off) | nothing — there was no stop condition (#17) |
| `sim.stop.maxClockMs` | `0` (off) | nothing (#17) |
| `sim.stop.wallClockMs` | `0` (off) | nothing (#17) |
| `sim.stop.stallMs` | `0` (off) | nothing (#17) |
| `sim.trace.enabled` | `false` (off) | nothing — the application printed two lines and nothing else was observable ([#20](https://github.com/bedaHovorka/OpenCybele1/issues/20)), see [`docs/trace-format.md`](trace-format.md) |
| `sim.trace.trainLookahead` | `32` | nothing (#20) — how many train-name slots the probe subscribes ahead of the generator, bounded 1..10000; inert when the trace is off |
| `sim.random.masterSeed` | `random` | the single unseeded `new Random()` in `Generator`, which fed the generator's two draws **and** every `RoadAgent`'s travel jitter — now one seeded stream per agent, see [`docs/seeded-rng.md`](seeded-rng.md) |

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
$ OPENCYBELE_OPTS=-Dsim.station.capacities=stA=6,stB=5,stC=2,stD=2,stE=5,stF=2,stG=3 opencybele
Exception in thread "main" java.lang.IllegalArgumentException: sim.station.capacities: no entry for station 'stH' declared in sim.topology
	at cz.vutbr.fit.ags.xhovor07.ScenarioConfig.checkKeySet(ScenarioConfig.java)
	...
	at cz.vutbr.fit.ags.xhovor07.Main.main(Main.java)
$ echo $?
1
```

> **`OPENCYBELE_OPTS`, not a bare flag — this one is silent.** The example above deliberately
> uses the environment variable. Gradle's generated start script routes everything in `$@` to
> the **program's** arguments, so `opencybele -Dsim.foo=bar` does not set a system property: it
> hands `Main` an argument it ignores. Nothing warns. With a validation key the run simply
> proceeds on the default; with `-Dsim.random.masterSeed` it proceeds on a **freshly drawn
> seed** while appearing to have accepted the pin, which is precisely how a golden gets
> recorded under a seed nobody chose ([#24](https://github.com/bedaHovorka/OpenCybele1/issues/24)).
> `./gradlew run -Dsim.…` is fine — the build file forwards those explicitly.


Checked: numeric ranges; that capacities and delays name exactly the stations and tracks in
`sim.topology` (a typo is an error, not a silently ignored entry); that the topology is a
simple graph with unique track names; that every `sim.arrival.pairs` endpoint is a real station
and is actually **routable** — otherwise `Planning.planTrain` would fail deep inside an agent,
where it is invisible; and that **neither the scenario file nor the `-D` flags carry an
unrecognised `sim.*` key**.

That last one covers `-D` as well as files, which it did not in the first revision of this
change. A mistyped `-Dsim.arrival.lambdams=500` was silently ignored: the banner printed the
default, the run proceeded, nothing complained — the exact "it looks like the config did
nothing" failure this document warns about two paragraphs above. `-D` is the path #12/#13's
`ProcessBuilder` harness will drive and the path every example here uses, so an ignored typo
there means a golden recorded under a configuration nobody chose, from a run that looks clean.
It now exits 1 and names the offending flag.

`sim.config` set *inside* a scenario file gets its own message rather than "unknown key" — it is
a recognised key that simply cannot be set from the file it names.

The reachability check uses a **local BFS over the parsed topology**, not `Util.path`. `Util`'s
DFS forbids only the edge it just came down rather than the nodes it has visited, so on a graph
with a cycle it never terminates when the target is unreachable — it dies with a
`StackOverflowError` instead of naming the pair. The historical topology is a tree, so that
never mattered; `sim.topology` now accepts cycles, so a scenario author can reach it.
`util/Util.java` is left exactly as it is: it is legacy behaviour under golden, and a
configuration check has no business changing it.

**A cycle also changes what "the route" means, not just reachability.** On a tree there is exactly
one simple path between any two stations, so `Util.path`'s DFS has no choice to make. Add a cycle
and there are several, and the DFS returns the **first one it finds, not the shortest** — decided by
the order it walks the graph in. Since [#19](https://github.com/bedaHovorka/OpenCybele1/issues/19)
that order is deterministic and no longer JDK-dependent, but it is still an implementation detail,
not a documented routing policy: two sensible-looking cyclic topologies can route trains very
differently, and the chosen route is what `Planning` collects votes on. If you declare a cycle,
verify the routes you actually get rather than assuming shortest-path. See
[`iteration-order.md`](iteration-order.md) ("Claim 4 is *two* hash sites, not one").

`load()` then republishes every resolved value into the system properties, so a later
`ScenarioConfig.get()` on any thread resolves the identical values without re-reading the file.

`ScenarioConfig` (and its nested `Edge`/`Branch`) is **`Serializable`**. `cybele.kernel.Handler`
extends `Serializable`, and `RailwayMainAgent` — which holds a `ScenarioConfig` — is itself
handed to `Agent.createActivity` as a `Serializable[]` payload, so the baseline agent graph was
serializable end to end. A plain object field here would have quietly broken that. It is masked
today by `Local;NoSerialization` in `cybele.prop`, but this branch exists to prepare a JADE port
where agents really are serialized, and #16 may revisit that setting.

Serializable rather than `transient` plus a `get()` at each use site, deliberately: `transient`
would make a deserialized agent silently re-resolve its configuration from the *receiving* JVM's
system properties, which in the remote case are not set at all — so it would fall back to the
historical defaults without a word. Carrying the resolved values with the agent is the behaviour
that cannot produce a golden recorded under a configuration nobody chose. Verified by
round-tripping a `Serializable[]{config, branches, stationNames}` through
`ObjectOutputStream`/`ObjectInputStream` and comparing every accessor.

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

1. the mean of the generator's `Exp(1/λ)` inter-arrival draw (`Generator.generateTrain`'s
   closing `setTimer(..., exp(LAMBDA), ...)`);
2. the **voting window and penalty quantum** of `Station.computeDifference` — it counted trains
   already planned within `time ± LAMBDA`, penalised a full station by `±LAMBDA/3`, and an
   un-full one by `plannedTrains*LAMBDA/6` (all three in `Station.computeDifference`, at
   `Station.java:131-139` **in the pre-#18 tree**).

Job 2 is scheduling *policy*. So the obvious way to make a scenario finish in seconds —
shorten `LAMBDA` — silently rewrote the very behaviour a golden is supposed to pin. A harness
that recorded a golden at `LAMBDA=500` and compared a port against it would be comparing
against a policy that never ran in the interactive demo.

**Decision: split (option (a) in the issue).** `sim.arrival.lambdaMs` and
`sim.station.voteWindowMs` are independent, and **both default to 8500**, so with no
configuration every value is identical. `voteWindow` is a `long` where `LAMBDA` was an `int`,
which is worth being precise about because two of the three expressions were already in `long`
context and **one was not**:

| Expression | Before | After | Identical? |
|---|---|---|---|
| `time ± w` | `long ± int`, widened | `long ± long` | always — verified over 5 M sampled values |
| `±w/3` | `int` division, then widened on return | `long` division | always: `-8500/3 = -2833` and `8500/3 = 2833` in both |
| `plannedTrains*w/6` | **`int*int/int`**, widened only on the return | `int*long/long` | for all `plannedTrains ≤ 252 645` |

The third one is the one an earlier revision of this document got wrong by claiming everything
was already `long`. It was not: `plannedTrains * 8500` overflowed `int` at
`plannedTrains ≥ 252 646`. A station would need a quarter of a million trains in its timetable
inside one voting window to reach that, so it is unreachable here — but the correct statement is
that the widening is a **latent overflow fix**, identical below that bound rather than identical
by construction. Verified by bytecode-level diff of `Station.computeDifference`.

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
- `RailwayCanvas` paints the same list on the canvas in red; draws a main-line pair with no
  track between it as a red crossed `??` gap rather than an innocuous blank; and crosses out in
  red any station or track the layout names that `sim.topology` does not declare. That last case
  previously drew an ordinary circle reading `N/A`, which is indistinguishable from a real
  station whose first state message has not arrived yet.

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

One assertion was removed to make this possible: `assert road != null` in `RailwayCanvas.paint`
(`:101` in the pre-#18 tree), which `assertion-triage.md` records as exercised 1 430 times in
run 6.
With a hardcoded layout that was a genuine program invariant; with a configurable one it is a
property of the configuration, and it would have fired on the AWT event dispatch thread where —
per the same triage — it is swallowed with a different banner and no Cybele framing. The red
`??` gap replaces it and is strictly more visible. The count in `assertion-triage.md` therefore
drops from 33 assertion sites to 32; see the amendment at the end of that document.

## Short scenarios

Two levers, and they are not the same lever:

- **`sim.clock.pace`** compresses *wall-clock* time. It sets no simulated-time parameter, and
  measurement did not detect it changing simulated-time throughput either — but see
  [Is `sim.clock.pace` behaviour-neutral?](#is-simclockpace-behaviour-neutral) below before
  varying it between a recording and a replay. **Treat it as behaviourally significant and
  record it in the manifest.**
- **`sim.arrival.lambdaMs`** (and, if you say so, `sim.station.voteWindowMs`) compresses
  *simulated* time and does change what is simulated.

`scenarios/short.properties` uses both — pace 8, λ 500, window 500 — and is the answer to
"a short scenario must run in seconds":

```bash
OPENCYBELE_OPTS=-Dsim.config=scenarios/short.properties \
  timeout 15 build/install/opencybele/bin/opencybele
```

| Run | Wall clock | Simulated | Departures |
|---|---|---|---|
| default (no config) | 150 s | — | 18.5 mean over 6 runs (sd 2.95) |
| `short.properties` | **15 s** | ~115 s | **24–30**, one outlier at 9 |
| `short.properties` | 30 s | 227 s | 51 |

**More departures in 15 seconds than the default produced in 150** — a measured **13.0×** the
throughput per wall-clock second at 15 s, and **14.4×** at 30 s, against the best available
estimate of the default rate. (An earlier revision said "~17×"; that was computed against a
single lucky default run rather than the 6-run mean, and was optimistic.) The 24–30 spread is
the unseeded RNG; the 9-departure outlier is discussed under
[Unexplained outlier](#unexplained-outlier-a-slipping-simulated-clock).

> **"Trains generated" is deliberately absent from that table.** Only *departures* are printed —
> `Planning.placeTrainIntoFirstStation` fires when a train is released, and `Train.start` prints immediately after.
> Nothing prints at generation. An earlier revision quoted a "trains generated" column derived
> from the highest `vlN` index seen on stdout, which is not a count of generated trains at all,
> only of the highest-numbered train that **departed**. That mistake produced a wrong conclusion
> about pace (see [Is `sim.clock.pace` behaviour-neutral?](#is-simclockpace-behaviour-neutral)).
> Generation count is not observable from the trace; if a scenario needs it, that is a probe-agent
> job for 1-PRE.2, not something to infer from indices.

`scenarios/short-arrivals-only.properties` is the control: pace 8, λ 500, window left at its
8500 ms default. The LAMBDA split was verified real by 2×2 isolation of the two levers: the λ
lever moves mean departures 28.7 → 46.7 with the window untouched, and the window lever moves
46.7 → 54.7 at λ = 500. Both levers do something, and they do different things — which is the
whole point of separating them.

### The throughput ceiling is not the voting window

At λ = 500 ms the arrival rate demands **2.00 departures per simulated second**. Measured, over
a controlled simulated window (first 50 s of simulated time, so the two are compared over the
same span):

| | departures/simulated second | vs demanded |
|---|---|---|
| λ = 500, pace 1 | 0.250 | 12 % |
| λ = 500, pace 8 | 0.240 | 12 % |

So ~88 % of the demanded arrivals queue rather than depart, **at both paces**. The ceiling is
the `Planning`/kernel round-trip — `Planning.planTrain` blocks on a `CountDownLatch` for a full
voting round per train — not the voting window and not the pace. This is worth knowing before
designing a scenario: below roughly 0.25 departures per simulated second the arrival rate is the
binding constraint; above it, the kernel is, and raising λ further buys nothing but backlog.

### Is `sim.clock.pace` behaviour-neutral?

**Not established either way. Treat it as behaviourally significant.**

An earlier revision of this document claimed pace changes "no simulated-time parameter at all …
same schedule, ~8× the rate", and review challenged that with a single-run pair: 21 departures
over 196 s of simulated time at pace 1 (0.107/simulated second) against 28 over 233 s at pace 8
(0.120/simulated second), i.e. 12 % *more* per simulated second. That would mean pace changes
what is simulated, which would be a serious problem for #24.

It was measured properly — three runs per pace, defaults, counting departures within the **same**
simulated window (the first 110 s, the longest span every run covers):

| | departures in first 110 s of simulated time | mean (sd) |
|---|---|---|
| pace 1 | 14, 13, 16 | 14.33 (1.53) |
| pace 8 | 14, 11, 15 | 13.33 (2.08) |

**Welch t = 0.671, df 3.7 — indistinguishable.** The measured difference has the *opposite sign*
to the one review reported, and is well inside run-to-run noise: three pace-8 runs of the
identical configuration produced 105, 105 and 122 departures, a 16 % spread on their own. A
single run per side cannot resolve a 12 % effect against that.

Two traps produced the original disagreement, and both are worth naming because they will bite
again:

1. **Rates computed over windows of different simulated length are not comparable.** A short run
   is nearly all startup transient — the network is empty, so early trains depart without
   contention — and reads high. A pace-1 run bounded by wall clock covers ~8× less simulated
   time than a pace-8 one, so it is systematically more transient-inflated. Compare counts within
   a fixed simulated window, not rates over whatever each run happened to reach.
2. **"Trains generated" is not observable from stdout** — see the note under the scenario table.

So there is no measured evidence that pace perturbs simulated-time throughput. **That is not the
same as evidence that it does not**, and pace stays flagged for the manifest, for three reasons
that stand independently of the above: the original project documentation reports the
message-loss defect ([#22](https://github.com/bedaHovorka/OpenCybele1/issues/22)) is worst in
"Fast" mode; pace changes the wall-clock timing of everything *outside* the simulated clock
(Swing repaints, real thread scheduling, GC), which is where a message-loss defect would live;
and the outlier below is unexplained. #24 should record `sim.clock.pace` and refuse to compare a
recording against a replay made at a different pace.

### Unexplained outlier: a slipping simulated clock

One 15-second short-scenario run produced **9 departures where siblings produced 24–30, and its
simulated clock reached only 41 s where siblings reached ~115 s** — at pace 8, with clean stderr
and no `AssertionError`. A pace of 8 over 15 s of wall clock should yield ~120 s of simulated
time; 41 s means the simulated clock advanced at ~2.7×, not 8×.

This is recorded here rather than left in a PR comment because it is the kind of thing that will
otherwise be rediscovered as "flaky test" by #12/#13. It is **not** evidence for a pace mechanism
— that hypothesis is tested and unsupported above — but it is an unexplained departure from the
configured clock rate, it is the failure mode most likely to make a golden comparison flake, and
it should be re-examined once #15 and #17 make runs deterministic and bounded.

## What this does not do

- **A load ceiling above which a fixed seed no longer departs the same trains.** Measured on
  `scenarios/short.properties`: identical departure-id sets over three runs at 30 s, differing
  sets at 45 s, with the generator provably unperturbed (same trains generated, same origins).
  The cause is the pre-existing `START` race ([#22](https://github.com/bedaHovorka/OpenCybele1/issues/22)
  item 1 / `INVENTORY` DEF-02), which denser arrivals hit more often. A scenario intended for a
  golden must be validated below it — three runs at a fixed seed, diff the departure-id sets.
  See [`seeded-rng.md`](seeded-rng.md) § "The load ceiling".
- ~~**No stop condition**~~ — **landed since**, in
  [#17](https://github.com/bedaHovorka/OpenCybele1/issues/17); see
  [`headless-and-stop.md`](headless-and-stop.md). Every run measured *in this document* is still
  `timeout`-truncated, because it predates the bound and the numbers are left as they were
  recorded. New scenarios should declare `sim.stop.maxClockMs` (or `maxTrains`) and a
  `sim.stop.wallClockMs` safety net instead; `scenarios/short-bounded.properties` is the worked
  example. The five keys are in the table above and all five default to off — but note that #17
  is **not** entirely opt-in: its kernel-readiness barrier and clock-control check run
  unconditionally, and they cost 120–400 ms of startup with the clock running, which shifts every
  absolute `<train> in <station> at <t>` value by about `sim.clock.pace × 100` ms against the
  pre-#17 baseline. The message *sequence* is unchanged (measured); the timestamps are not. This
  document's own measurements predate that shift and are timing-distributional anyway, so none of
  its conclusions move — but a byte-level comparison across the #17 boundary needs #21's
  normalizer. See [`headless-and-stop.md`](headless-and-stop.md).
- **Seeded RNG landed separately.** [#15](https://github.com/bedaHovorka/OpenCybele1/issues/15)
  added `sim.random.masterSeed` to this same mechanism; see [`docs/seeded-rng.md`](seeded-rng.md).
  It fixes the *draw sequences*, not the run: departure timestamps are still read from a
  real-time clock, so the parity evidence for "defaults are unchanged" below stays
  distributional rather than byte-exact.
- ~~**No HashMap iteration-order fix**~~ — **landed since**, in
  [#19](https://github.com/bedaHovorka/OpenCybele1/issues/19); see
  [`iteration-order.md`](iteration-order.md). `sim.topology`'s declaration order is now what breaks
  ties in the graph's iteration order, so the "preserves the historical insertion order" property
  this file relies on became load-bearing rather than incidental. The orders themselves did not
  move. Note what #19 does *not* fix, measured while reviewing #15: `Cybele.createAgent` is
  asynchronous, so the order in which agent **constructors** actually run stays nondeterministic
  even though the loop that issues the create requests is now fixed.
- **`cybelle/ICS.prop` is not touched** ([#16](https://github.com/bedaHovorka/OpenCybele1/issues/16)).
- **The `RoadAgent` travel jitter** (`500 * nextGaussian()` in `RoadAgent.travelStart`) is *not*
  externalised as a `sim.*` magnitude. It is an RNG draw, not a timer period; #15 gave it a
  seeded per-road stream but left the `500` and the distribution exactly as they were.

## Evidence that the defaults are unchanged

**Exact comparison is impossible today.** When this was measured the RNG was still unseeded
([#15](https://github.com/bedaHovorka/OpenCybele1/issues/15)); it is seeded now, but that alone
does not make two runs equal — see [`docs/seeded-rng.md`](seeded-rng.md) — so two runs of the
*same* binary still differ — measured run-to-run spread on departure count is nearly 2× (13–25 over 150 s). Any
claim resting on a single run per side is therefore worthless, including the 21-vs-21 pair this
document reported in its first revision: that was luck, not evidence. The claim below is
distributional, over six runs per side, and is stated as such.

Method: `git archive` of the pre-change tree into a scratch directory, built and run with the
same JDK 21 / Gradle 8.10.2 / `-ea --patch-module` as this branch, `timeout 150` on the
`installDist` start script with stdout and stderr captured separately; then the same for this
branch with no `sim.*` property set. Six runs each side. The measured stream is
`Planning.placeTrainIntoFirstStation`'s `<train> in <station> at <t>` println.

| | runs | departures, mean (sd) | pooled inter-departure gap | origin distribution |
|---|---|---|---|---|
| before (baseline) | 6 | 18.33 (4.46) | — | — |
| after (defaults) | 6 | 18.50 (2.95) | 0.8 % apart | χ² = 4.06, df 2 (crit 5.99) |

Welch **t = −0.076** on departure count: the two sets are statistically indistinguishable. The
pooled inter-departure gap is 0.8 % apart and tracks the configured λ = 8500 ms plus voting
delay. The origin distribution over `stA`/`stB`/`stC` gives χ² = 4.06 against a critical 5.99 at
df 2 — no detectable shift.

> The `<station>` in that line is `TrainPlan.station`, which `Planning.planTrain` fills from the
> train's **`from`** — it is the **origin**, not the destination. An earlier revision of this
> document and of the PR body called it the destination. The conclusions are unaffected (it is
> the same field on both sides of the comparison), but the label was wrong.

Stronger, non-statistical checks were run independently and all came back exact:

- A JVM-level harness comparing the baseline's literal `net.put` sequence against
  `ScenarioConfig.buildNet()` found `nodeSet` order, `values` order, `allNodesWithEdge` order,
  capacity and delay key order, the pairs array, the GUI paces, the main line and both branch
  coordinates (170/50 and 290/150) **all equal**.
- A bytecode-level diff of `Station.computeDifference` confirms `-8500/3 = -2833` and
  `8500/3 = 2833` both ways, and `time ± w` identical over 5 M sampled values.
- stdout differs at exactly one line between the two trees; the 23-line Cybele startup banner is
  byte-identical and the divergence is the unseeded RNG. Baseline stderr is 0 bytes; this
  branch's stderr is the configuration banner and nothing else.
- 28/28 validation-failure cases exit 1 with `grep -c "Cybele version" stdout` = 0, proving the
  failure precedes `Cybele.startUp()`.
- The GUI was screenshotted on both trees and draws the identical layout.

This section was expected to be replaceable by a byte-exact diff once #15 landed. It is not:
#15 pins the draw sequences, but departure timestamps come from a real-time clock and the
event-queue/thread-pool ordering is untouched ([#16](https://github.com/bedaHovorka/OpenCybele1/issues/16),
[#19](https://github.com/bedaHovorka/OpenCybele1/issues/19)), so stdout still differs run to run
at a fixed seed. [`docs/seeded-rng.md`](seeded-rng.md) measures exactly which parts of stdout a
fixed seed *does* pin — the train-by-train origin sequence is one of them — and that is the
projection this comparison should be redone against. Until #16/#19 land it remains sampled
evidence, not proof — the same caveat `assertion-triage.md` carries.
