# MANIFEST.md — the recording environment of the frozen goldens

> Produced for [#24](https://github.com/bedaHovorka/OpenCybele1/issues/24) (*Record and freeze the
> goldens; tag `pre-migration-baseline`*), which implements
> [`Phase1.md` 1-PRE.4](../../docs/Phase1.md).
>
> **This is the file that makes the goldens next to it reproducible.** A golden without its
> recording environment is a number without a unit. From the moment #24 closes,
> [`Phase1.md` L7](../../docs/Phase1.md) forbids re-recording any of them except to fix a defect in
> the harness itself — so if a replay disagrees with a golden, the first question is whether the
> replay was made in the environment described here, and the second is whether the port is wrong.
> "Re-record it" is not on the list.

## 0. Two corrections to the issue text, which predates the branch topology

Both are recorded here rather than silently worked around, because the issue body is what a later
reader will find first.

1. **The issue says "record goldens … against `develop`". That is wrong.** `develop` is the
   untouched 2008 original and carries none of the 1-PRE determinism work. The application the
   goldens were recorded from is **`opencybele-baseline`**, which carries #15 (seeded RNG), #16
   (kernel config), #17 (headless + bounded stop), #18 (configurable timing), #19 (iteration order)
   and #20 (the trace probe). The harness, the scenarios and the goldens live on **`jade-develop`**.
   The same correction applies to `Phase1.md` 1-PRE.4, which has been amended.
2. **The goldens were not recorded from scratch by #24.** They were recorded incrementally by
   [#13](https://github.com/bedaHovorka/OpenCybele1/issues/13),
   [#21](https://github.com/bedaHovorka/OpenCybele1/issues/21) and
   [#23](https://github.com/bedaHovorka/OpenCybele1/issues/23) as each scenario was built and
   gated. #24's work was therefore to **verify that what is committed is exactly what the current
   baseline produces**, to capture this manifest, to create the tag, and to prove a fresh clone
   reproduces. Nothing in `parity-tests/golden/` was rewritten, and `-Dgolden.record=true` was not
   run at any point — see §9.

## 1. What was recorded from what

The goldens and the application that produced them are on **different branches**, so no single
commit identifies both. The pair is what must be recovered, and both halves are written down here.

| | branch | commit | role |
|---|---|---|---|
| **application** | `opencybele-baseline` | **`f4c233c081bac341af61cfe1d8317154443454dc`** (`f4c233c`, *Probe agent emitting the canonical parity trace (#20) (#63)*) | the binary whose behaviour the goldens are | 
| **harness + scenarios + goldens** | `jade-develop` | `49e3feea22b7a7cb11040d6ba8cd7515e4a8f503` (`49e3fee`, *Scenario coverage extension and COVERAGE.md (#23) (#68)*), plus the commit that adds this file | the instrument, the specs, and the goldens |
| **vendor jars** | `withoutGradle` tag | `Cybele.jar`, `CybeleImpl.jar` | not in git on either branch; restored by [`scripts/bootstrap-vendor-jars.sh`](https://github.com/bedaHovorka/OpenCybele1/blob/opencybele-baseline/scripts/bootstrap-vendor-jars.sh) (on `opencybele-baseline`) |

### Why `pre-migration-baseline` points at the application commit

The tag points at **`f4c233c` on `opencybele-baseline`**, not at a `jade-develop` commit.

A golden is a *measurement of an application*. Everything on `jade-develop` — normalizer rules,
scenario YAML, this manifest — is the instrument and the record; the thing that would change a
golden's content if it changed is the **binary**. So the tag names the specimen, and the manifest
names the instrument. Tagging the harness commit instead would freeze the description and leave the
described thing unpinned, which is the failure mode the tag exists to prevent.

The consequence is that the tag alone is **not** sufficient to replay the suite, and this is
deliberate rather than an oversight: replaying needs both refs, and the second one is in the table
above. The repository already uses a tag this way — `withoutGradle` names the state that carries the
vendor jars, not a state anyone builds from.

Reproduction therefore always names two refs:

```bash
# the application under test
git checkout pre-migration-baseline           # == f4c233c, branch opencybele-baseline
scripts/bootstrap-vendor-jars.sh && ./gradlew installDist

# the harness, the scenarios and the goldens
git checkout jade-develop                     # the commit that added this manifest, or later
./gradlew characterizationIT -Popencybele.dist=<abs path>/build/install/opencybele
```

## 2. Summary — the five goldens


| scenario | golden lines | golden SHA-256 (first 16) | trace digest (first 16) | trains generated | departures | final tick (observed) |
|---|---|---|---|---|---|---|
| `opencybele-strict` | 607 | `84b51584a40b9614` | `0410d72d933bbddb` | 9 | 5 | 24024 ms |
| `opencybele-lifecycle` | 84 | `2e7d34242acebcc9` | `acc212d84c66d69b` | 2 | 2 | 40032 ms |
| `opencybele-timers` | 183 | `f6db6287660b38e0` | `7d79066aad6f62a4` | 3 | 3 | 23520 ms |
| `opencybele-congestion` | 176 | `3b33b4f0788db91b` | `48fbb42cc6da692c` | 4 | 4 | 26952 ms |
| `opencybele-capacity` | 230 | `5112f36586ca03a1` | `1e056ae970eb4b27` | 9 | 4 | 27728 ms |

`opencybele-strict` generates nine trains of which five depart, and `opencybele-capacity` nine of
which four do; in both cases the rest are deferred past the bound by the election itself, which
[`../scenarios/COVERAGE.md`](../scenarios/COVERAGE.md) §2 documents and
[`../scenarios/opencybele-capacity.yaml`](../scenarios/opencybele-capacity.yaml) derives train by
train. That is not a DEF-22 wedge: every generated train completes an election.

## 3. Toolchain and machine

| field | value |
|---|---|
| JDK (Gradle toolchain **and** the child JVM the harness launches) | **Red Hat, Inc. OpenJDK 21.0.11+10**, `openjdk version "21.0.11" 2026-04-21`, build `Red_Hat-21.0.11.0.10-2`, at `/usr/lib/jvm/java-21-openjdk` |
| Gradle | **8.10.2** (Kotlin DSL, `build.gradle.kts`), launcher JVM 21.0.11 |
| Java language level | 21 (`java.toolchain.languageVersion = 21` in both `build.gradle.kts` files) |
| OS | Fedora release 43, kernel `7.1.7-100.fc43.x86_64`, x86-64 |
| CPU | 24 logical processors (Intel Arrow Lake) |
| boot id at verification | `862aa4f8-9952-4132-9567-f3371a898849` (booted 2026-08-13 05:38:16 local) |

The child JVM is **not** configurable per scenario: `OpenCybeleLauncher` resolves it as
`System.getProperty("java.home") + /bin/java` of the test JVM unless `-Popencybele.java=…` overrides
it. So "the JDK the goldens were recorded on" and "the JDK Gradle chose for the harness" are the
same fact, and it is the row above.

## 4. `-ea` — enabled, and an `AssertionError` aborts the recording

**Assertions were ENABLED for every recording and every verification run.** This is
[`docs/defect-triage.md`](../../docs/defect-triage.md) §5, Decision 1, and it is a first-class
manifest field because it changes observable behaviour: with `-ea` off, a failed invariant inside a
Cybele handler continues silently; with it on, an `AssertionError` is thrown, swallowed by
`IAIAgentThread`, printed to stderr — and, because stderr is merged into the captured stream, it
would otherwise be *frozen into a golden*.

The decision has three parts, and only the first is a flag:

> 1. Goldens are recorded with assertions **enabled** (`-ea`).
> 2. An `AssertionError` during a golden run **aborts the recording**. The run is discarded, never
>    normalised, never compared; the condition is triaged as a new defect. It is **never** pinned
>    into a golden.
> 3. `-ea` is a manifest field. A recording made with assertions on must never be compared against a
>    replay made without them, or the reverse.

Why enabled rather than off:

* **It matches the runtime the parity runs use.** `OpenCybeleLauncher` bakes `-ea` into the command
  line unconditionally (`OpenCybeleLauncher.launch`, asserted by `OpenCybeleLauncherIT`), and #14
  enabled it for `./gradlew run`. Recording *off* and replaying *on* would turn every future
  assertion firing into a spurious golden diff attributed to the port.
* **The measured cost is zero.** All 33 assertion sites were checked with `-ea` on
  (`docs/assertion-triage.md` on `opencybele-baseline`); 25 are exercised by a normal run, ~20 000
  evaluations collectively, **0 fires** — and 0 fires across every run recorded in this manifest.

Enforcement is structural rather than procedural: there is no code path in the harness that launches
this application without `-ea`, and `OpenCybeleLauncherIT` fails if one appears.

## 5. Cybele kernel configuration (#16)

The kernel's own levers live outside application code, in `cybelle/cybele.prop` and
`cybelle/ICS.prop` on `opencybele-baseline`. They are part of the recording environment in exactly
the way the JDK is.

| lever | value as recorded | standing |
|---|---|---|
| event-queue sort/compare | `cybele.srv.evmgmt.app.param.iai = system_queue no_sort no_comp;agent_queue no_sort no_comp` | #16 decision 1 — the effective default (FIFO, no sorting), now **written down explicitly** instead of relying on an undocumented fallback. Proved a no-op in `IAIEventManagement.start`'s bytecode, not merely measured. A sorted agent queue is a **behaviour change** (a queued TIMER overtakes queued MESSAGEs). |
| thread pool | `cybele.srv.thmgmt.app.param.iai = 5 10 4000 3 5` | #16 decision 2 — vendored value, kept. Every viable pool size gave the same departure sequence and the same ~13–20 % tie-flip rate; below 3 threads the simulation stops working at all. |
| comm service | `cybele.srv.comm.app.param.iai = Local;NoSerialization` | #16 decision 4 — **must stay set**. Without it `IAICommService` builds an `IAINetClient`, spawns an `IAIDaemon` and startup aborts. It is also why a channel payload crosses **by reference** (SEM-05), which is what makes `STATION_INFO.occupied` a race rather than a value (DEF-13) and why the normalizer projects it. |
| services loaded | `cybele.services = exception;concurmgmt;evmgmt;thmgmt;intlevent;comm;timer` | vendored |
| `ICS.prop` | `ICSBrowser = 127.0.0.1` (single key) | #16 decision 3 — the external IAI host was already the losing key under last-one-wins; removing it closes a latent startup hang. Inert while the comm service is `Local`. |

These files reach the child through `--patch-module java.base=cybelle`: Cybele loads `cybele.prop`
with `Properties.class.getResourceAsStream("/cybele.prop")`, a `java.base`-loaded class, which since
JPMS no longer falls back to the application classpath. The flag only works against a **directory**,
which is why the two `.prop` files are tracked loose while the vendor jars are not.

## 6. The exact command line the goldens are a measurement of

`OpenCybeleLauncher.launch` assembles it explicitly — nothing is inherited, and the order is fixed
so that two runs of the same scenario produce a byte-identical command line:

```
<java.home>/bin/java -ea --patch-module java.base=cybelle -cp <dist/lib/*.jar>
    -Dsim.<key>=<value> …          # the scenario's twenty keys, in declaration order
    cz.vutbr.fit.ags.xhovor07.Main
```

with the child's **working directory** set to the harness scratch directory, into which `cybelle/`
is staged first (the `--patch-module` path is resolved against the child's CWD). `JAVA_TOOL_OPTIONS`
and friends are **refused** rather than tolerated — `rejectLeakingEnvironment()` throws if one is
set, because the JVM announces such flags on stderr and stderr is inside the captured trace.

**One honest note about "headless".** The harness passes `-Dsim.headless=true`; it does **not** pass
`-Djava.awt.headless=true`. These are not the same switch, and the one that matters here is the
first: under `sim.headless=true` the application never constructs `Gui` or `RailwayCanvas`
(`RailwayMainAgent:87`), so no window exists to be touched by whatever is on `DISPLAY`, and the
contamination `docs/kernel-config.md` measured on the shared display `:0` — 5–10 differing departure
ids that collapsed to zero on a private display — cannot arise. `ScenarioCatalogIT` fails any
scenario that does not set it. The recording shell did have `DISPLAY=:0` exported and it made no
difference for that reason; a future recorder who wants belt and braces can export `DISPLAY=` before
the run, which changes nothing in the trace.

Note also that `sim.headless=true` does **not** remove `TableModel.update` from the run — DEF-24 is
present in exactly the configuration the goldens were recorded in (`docs/defect-triage.md` §4.5).
That is why a `ConcurrentModificationException` from that site is a **discard condition** (§7) rather
than something headless mode excludes.

## 7. The recording gate that was applied

**Exit 0 is necessary but not sufficient.** DEF-22 wedges `Planning` at the unbounded
`latch.await()` while `Generator` keeps generating, so a wedged run reaches its simulated-time bound
and exits **0** with a clean stream and a truncated departure stream — roughly **1 run in 45**, and
`sim.stop.stallMs` structurally cannot catch it (it measures the gap between *generated* trains, and
generation is the half that keeps working).

So a run is accepted only if **both** of the following hold, read from the raw stop banner — which
the normalizer strips (`--- ` and `!!! ` prefixed lines are diagnostics) and which therefore cannot
be recovered from the golden itself:

1. **It reached its expected departure count** (per scenario in §10). This is the half that catches
   DEF-22: a wedge truncates the departure stream, and nothing else in the accept path sees it.
2. **It stopped because it reached its bound** — `reason = sim.stop.maxClockMs reached`, exit **0**,
   and `simulated clock` **≥ `sim.stop.maxClockMs`**.

**The exact tick is *not* an invariant, and must not be used as one.** `RunControl` polls the clock
and stops on the first observation of `>= maxClockMs`, so the banner's `simulated clock` is the tick
the poll happened to see, quantised to the 8 ms tick — measured across repeat runs at
`opencybele-strict`: 24024, 24040, 24064, 24072, and at `opencybele-capacity`: 27712, 27728, 27732
(found in review of #24, and reproduced independently — two further `opencybele-strict` runs gave
24072 and 24040 with 9 trains generated and 5 departures in both, the recorded values).
Departure count, trains generated, exit status and the discard scan were identical in every one of
those runs, and the golden is untouched by the jitter because the banner never reaches the trace. A
future recorder who reads §10's figure as an equality would discard healthy runs, or conclude the
baseline had drifted when it had not. The figures in §10 are therefore labelled **observed**, and the
criterion is the inequality above.

```
--- simulation stop ---
  reason        = sim.stop.maxClockMs reached: simulated clock at 24016 ms past sim.clock.startMs
  exit code     = 0
  trains generated = 9
  simulated clock  = 24024 ms
```

And, because **no throwable in this application changes the exit status**, stderr carries discard
conditions of its own (`docs/defect-triage.md` §8.2):

| signal | why it voids the recording | observed in any run behind this manifest |
|---|---|---|
| `AssertionError`, at any site | §4 — abort and classify, never pin | **0** |
| `ConcurrentModificationException` from `TableModel.update` | DEF-24 — present headless, invisible to exit status | **0** |
| `NullPointerException` from `OueueItem.diff` | DEF-06 — leaves the road's heap undefined | **0** |
| `NullPointerException` from `VoteCollecting.vote` | DEF-09 — a lost `countDown()`, i.e. DEF-22 armed | **0** |
| exit **5** | DEF-15 — the clock never answered its command channel; the run is void | **0** |
| exit **3** or **4** | the wall-clock net or the generation-stall net fired; not a completed scenario | **0** |
| exit **2** | window closed before the bound (only possible with a GUI) | **0** — not reachable headless |

`ErrorScanner` matches all of these on the **raw** stream, before normalisation, and
`ScenarioRunner` runs it before the golden comparison so that a failure report leads with the
throwable rather than with a trace diff.

**A scenario's bound is load-bearing and was not touched.** `Cybele.terminate()` races agent
construction, and a bound landing near an arrival produces an `Activity.init` NPE *after* the stop
banner, at exit 0. Each shipped bound is already tuned against that — `opencybele-strict`'s 24000 sits
880 simulated ms after the previous burst and 1072 before the next, and 2776 clear of the last
arrival; `opencybele-capacity` runs at pace 4 rather than 8 for the same class of reason. Retuning
any of them would mean re-recording, which `Phase1.md` L7 forbids.

## 8. Master seed

Every scenario pins `sim.random.masterSeed = 20080415`. Two traps, both measured, both worth
carrying here because either one silently produces a golden nobody chose:

* `scenarios/default.properties` on `opencybele-baseline` does not list the key, so copying that file
  for a new scenario yields a **drawn** seed. The five scenarios here do not use `launcher.config` at
  all — every key is written out in the YAML, and `ScenarioCatalogIT` fails a scenario that leaves any
  of the twenty implicit.
* Passing `-Dsim.random.masterSeed=…` **positionally** to `build/install/opencybele/bin/opencybele`
  is silently ignored (Gradle start scripts route `$@` to program args); `OPENCYBELE_OPTS=` is
  required. The harness does not use the start script at all — it invokes `java` directly (§6), so
  the trap cannot be hit through it.

What a fixed seed buys, and what it does not: train ids, origins, destinations, departure order and
departure count are reproducible; **which** generated trains successfully depart is not guaranteed,
because DEF-02 can silently drop a `START` published to a channel the `Train` has not opened yet. A
dropped train is *absent*, not reordered, so no normalisation recovers it. That is the mechanism
behind §11's cross-occasion caveat.

## 9. What #24 verified, and how

**No golden was re-recorded.** `-Dgolden.record=true` was never passed. Every run below compared
against the committed bytes.

| check | command | result |
|---|---|---|
| full suite against the committed goldens | `./gradlew characterizationIT -Popencybele.dist=…` | **69 tests, 0 failures**, 5 skipped (`ParityGateIT`, opt-in — 64 executed). All five `OpenCybele*IT` scenarios passed at `strict` |
| 10 consecutive runs per scenario, each compared against its golden | `./gradlew parityGate -Popencybele.dist=… -Dparity.gate.runs=10 -Dparity.gate.ledger=…` | **5 × 10 = 50 runs, 1 distinct trace per scenario, 0 divergences, 0 golden mismatches** |
| digests against the ones #23 wrote into `COVERAGE.md` §2.1 | by hand | **all five identical** — `0410d72d933bbddb`, `acc212d84c66d69b`, `7d79066aad6f62a4`, `48fbb42cc6da692c`, `1e056ae970eb4b27` |
| line counts against the committed golden files | `wc -l` | **identical** — 607 / 84 / 183 / 176 / 230 |
| departure count and stop reason per scenario | one raw run per scenario, stop banner read directly | **all five reached their expected departure count, exited 0 and stopped on `sim.stop.maxClockMs reached` with the clock past the bound**; see §7 for why the exact tick is not part of the criterion, and §10 for the figures |
| fresh clone at the tag | see §12 | **reproduced byte-for-byte** |

`ScenarioRunner` compares at the declared contract level on every run, and all five scenarios are
`strict` — `TraceComparator.STRICT` is exact list equality with no tolerance of any kind. So "the
gate passed" and "every one of those 50 runs equals the committed golden line for line" are the same
statement.

**Finding: none.** Every committed golden is exactly what the current `opencybele-baseline` build
produces. Had any differed, this section would say so and the golden would have been left alone —
`Phase1.md` L7 makes a silent re-record the one outcome this issue must not produce.

## 10. Per scenario

### `opencybele-strict`

| field | value |
|---|---|
| golden | [`opencybele-strict.txt`](./opencybele-strict.txt) |
| contract | `strict` — exact list equality, no tolerance |
| golden lines | **607** |
| golden file SHA-256 | `84b51584a40b961474d9e6184c2fde4f68c6ea8a466ae2fa638ef07e392ec9fd` |
| normalized-trace digest (SHA-256 of the joined lines, as `ParityGate` computes it) | `0410d72d933bbddbe1f81b07c919144061b61f943448028dd5d4cd21d90b0c3e` |
| entity-id set (`^(vl\d+)\|`) | `vl0, vl1, vl2, vl3, vl4, vl5, vl6, vl7, vl8` (9) |
| departure set (`<train> started`) | `vl0, vl1, vl2, vl3, vl4` (5) |
| arrival lines (`<train> in <station> at <T>`) | 5 |
| expected trains generated (stop banner) | **9** |
| final tick (stop banner `simulated clock`) — **observed sample, not an invariant** | 24024 ms observed; 24040–24072 across later runs; the exact value is poll jitter (§7). The criterion is `reason = sim.stop.maxClockMs reached` **and** clock ≥ `24000` |
| verification occasion | boot `862aa4f8-9952-4132-9567-f3371a898849`, `2026-08-20T09:42:58.847035451Z` |
| master seed | `20080415` |
| unspecified `sim.*` keys | none — all twenty are declared |

Resolved configuration — every one of the twenty keys, verbatim from
[`../scenarios/opencybele-strict.yaml`](../scenarios/opencybele-strict.yaml):

```properties
sim.arrival.lambdaMs = 2000
sim.arrival.firstFireMs = 200
sim.arrival.pairs = stA>stB,stA>stC,stB>stA,stB>stC,stC>stB,stC>stA
sim.station.voteWindowMs = 2000
sim.topology = stA-stH:tr1,stH-stG:tr2,stG-stE:tr3,stE-stD:tr4,stD-stB:tr5,stF-stE:tr6,stC-stF:tr7
sim.station.capacities = stA=6,stB=5,stC=2,stD=2,stE=5,stF=2,stG=3,stH=2
sim.road.delaysSec = tr1=1,tr2=1,tr3=5,tr4=2,tr5=3,tr6=4,tr7=3
sim.clock.startMs = 0
sim.clock.pace = 8
sim.gui.paces = Fast=8,Normal=1,Slow=0.3
sim.gui.mainLine = stA,stH,stG,stE,stD,stB
sim.gui.branches = stC:tr7,stF:tr6
sim.random.masterSeed = 20080415
sim.headless = true
sim.trace.enabled = true
sim.trace.trainLookahead = 32
sim.stop.maxTrains = 0
sim.stop.maxClockMs = 24000
sim.stop.wallClockMs = 60000
sim.stop.stallMs = 10000
```

### `opencybele-lifecycle`

| field | value |
|---|---|
| golden | [`opencybele-lifecycle.txt`](./opencybele-lifecycle.txt) |
| contract | `strict` — exact list equality, no tolerance |
| golden lines | **84** |
| golden file SHA-256 | `2e7d34242acebcc9452d620260b0d3e451dd22ae7c0f0287ea0b81fe393cf3e2` |
| normalized-trace digest (SHA-256 of the joined lines, as `ParityGate` computes it) | `acc212d84c66d69bddbd4f71859ab930f9634c58d5f60c480003168ec79579b4` |
| entity-id set (`^(vl\d+)\|`) | `vl0, vl1` (2) |
| departure set (`<train> started`) | `vl0, vl1` (2) |
| arrival lines (`<train> in <station> at <T>`) | 2 |
| expected trains generated (stop banner) | **2** |
| final tick (stop banner `simulated clock`) — **observed sample, not an invariant** | 40032 ms observed; the exact value is poll jitter (§7). The criterion is `reason = sim.stop.maxClockMs reached` **and** clock ≥ `40000` |
| verification occasion | boot `862aa4f8-9952-4132-9567-f3371a898849`, `2026-08-20T09:42:05.439531476Z` |
| master seed | `20080415` |
| unspecified `sim.*` keys | none — all twenty are declared |

Resolved configuration — every one of the twenty keys, verbatim from
[`../scenarios/opencybele-lifecycle.yaml`](../scenarios/opencybele-lifecycle.yaml):

```properties
sim.arrival.lambdaMs = 20000
sim.arrival.firstFireMs = 500
sim.arrival.pairs = stA>stB,stB>stA
sim.station.voteWindowMs = 20000
sim.topology = stA-stB:tr1
sim.station.capacities = stA=2,stB=2
sim.road.delaysSec = tr1=3
sim.clock.startMs = 0
sim.clock.pace = 8
sim.gui.paces = Fast=8,Normal=1,Slow=0.3
sim.gui.mainLine = stA,stB
sim.gui.branches = 
sim.random.masterSeed = 20080415
sim.headless = true
sim.trace.enabled = true
sim.trace.trainLookahead = 32
sim.stop.maxTrains = 0
sim.stop.maxClockMs = 40000
sim.stop.wallClockMs = 60000
sim.stop.stallMs = 20000
```

### `opencybele-timers`

| field | value |
|---|---|
| golden | [`opencybele-timers.txt`](./opencybele-timers.txt) |
| contract | `strict` — exact list equality, no tolerance |
| golden lines | **183** |
| golden file SHA-256 | `f6db6287660b38e0b17805011fdcde823a512b80fa3e6346267ad4e692d3816f` |
| normalized-trace digest (SHA-256 of the joined lines, as `ParityGate` computes it) | `7d79066aad6f62a4d0daff9de8d6d4b48ac63cc148fc6eba64cb1e93f19612cb` |
| entity-id set (`^(vl\d+)\|`) | `vl0, vl1, vl2` (3) |
| departure set (`<train> started`) | `vl0, vl1, vl2` (3) |
| arrival lines (`<train> in <station> at <T>`) | 3 |
| expected trains generated (stop banner) | **3** |
| final tick (stop banner `simulated clock`) — **observed sample, not an invariant** | 23520 ms observed; the exact value is poll jitter (§7). The criterion is `reason = sim.stop.maxClockMs reached` **and** clock ≥ `23500` |
| verification occasion | boot `862aa4f8-9952-4132-9567-f3371a898849`, `2026-08-20T09:43:32.161743745Z` |
| master seed | `20080415` |
| unspecified `sim.*` keys | none — all twenty are declared |

Resolved configuration — every one of the twenty keys, verbatim from
[`../scenarios/opencybele-timers.yaml`](../scenarios/opencybele-timers.yaml):

```properties
sim.arrival.lambdaMs = 4000
sim.arrival.firstFireMs = 1500
sim.arrival.pairs = stA>stC
sim.station.voteWindowMs = 4000
sim.topology = stA-stB:tr1,stB-stC:tr2
sim.station.capacities = stA=4,stB=4,stC=4
sim.road.delaysSec = tr1=0,tr2=2
sim.clock.startMs = 0
sim.clock.pace = 8
sim.gui.paces = Fast=8,Normal=1,Slow=0.3
sim.gui.mainLine = stA,stB,stC
sim.gui.branches = 
sim.random.masterSeed = 20080415
sim.headless = true
sim.trace.enabled = true
sim.trace.trainLookahead = 32
sim.stop.maxTrains = 0
sim.stop.maxClockMs = 23500
sim.stop.wallClockMs = 60000
sim.stop.stallMs = 20000
```

### `opencybele-congestion`

| field | value |
|---|---|
| golden | [`opencybele-congestion.txt`](./opencybele-congestion.txt) |
| contract | `strict` — exact list equality, no tolerance |
| golden lines | **176** |
| golden file SHA-256 | `3b33b4f0788db91b7e4ee57ea0b12fa451c8a986ab81a3b75dff11b12ca8400e` |
| normalized-trace digest (SHA-256 of the joined lines, as `ParityGate` computes it) | `48fbb42cc6da692cfea71c87b500a9e04084d97bfc9094e02356c7faf7b8913e` |
| entity-id set (`^(vl\d+)\|`) | `vl0, vl1, vl2, vl3` (4) |
| departure set (`<train> started`) | `vl0, vl1, vl2, vl3` (4) |
| arrival lines (`<train> in <station> at <T>`) | 4 |
| expected trains generated (stop banner) | **4** |
| final tick (stop banner `simulated clock`) — **observed sample, not an invariant** | 26952 ms observed; the exact value is poll jitter (§7). The criterion is `reason = sim.stop.maxClockMs reached` **and** clock ≥ `26900` |
| verification occasion | boot `862aa4f8-9952-4132-9567-f3371a898849`, `2026-08-20T09:41:27.810071653Z` |
| master seed | `20080415` |
| unspecified `sim.*` keys | none — all twenty are declared |

Resolved configuration — every one of the twenty keys, verbatim from
[`../scenarios/opencybele-congestion.yaml`](../scenarios/opencybele-congestion.yaml):

```properties
sim.arrival.lambdaMs = 3500
sim.arrival.firstFireMs = 1500
sim.arrival.pairs = stA>stC,stB>stC,stC>stB
sim.station.voteWindowMs = 3500
sim.topology = stA-stB:tr1,stB-stC:tr2
sim.station.capacities = stA=3,stB=2,stC=3
sim.road.delaysSec = tr1=8,tr2=5
sim.clock.startMs = 0
sim.clock.pace = 8
sim.gui.paces = Fast=8,Normal=1,Slow=0.3
sim.gui.mainLine = stA,stB,stC
sim.gui.branches = 
sim.random.masterSeed = 20080415
sim.headless = true
sim.trace.enabled = true
sim.trace.trainLookahead = 32
sim.stop.maxTrains = 0
sim.stop.maxClockMs = 26900
sim.stop.wallClockMs = 60000
sim.stop.stallMs = 20000
```

### `opencybele-capacity`

| field | value |
|---|---|
| golden | [`opencybele-capacity.txt`](./opencybele-capacity.txt) |
| contract | `strict` — exact list equality, no tolerance |
| golden lines | **230** |
| golden file SHA-256 | `5112f36586ca03a186b99f1bf3e0cdb7ee821094dbc005c2f498a9c5830e2620` |
| normalized-trace digest (SHA-256 of the joined lines, as `ParityGate` computes it) | `1e056ae970eb4b27bc16ebb4cc392eebb76510fbf37fecbce18e23e8731de3e1` |
| entity-id set (`^(vl\d+)\|`) | `vl0, vl1, vl2, vl3, vl4, vl5, vl6, vl7, vl8` (9) |
| departure set (`<train> started`) | `vl0, vl1, vl2, vl3` (4) |
| arrival lines (`<train> in <station> at <T>`) | 4 |
| expected trains generated (stop banner) | **9** |
| final tick (stop banner `simulated clock`) — **observed sample, not an invariant** | 27728 ms observed; 27712 and 27732 across later runs; the exact value is poll jitter (§7). The criterion is `reason = sim.stop.maxClockMs reached` **and** clock ≥ `27700` |
| verification occasion | boot `862aa4f8-9952-4132-9567-f3371a898849`, `2026-08-20T09:40:15.716761683Z` |
| master seed | `20080415` |
| unspecified `sim.*` keys | none — all twenty are declared |

Resolved configuration — every one of the twenty keys, verbatim from
[`../scenarios/opencybele-capacity.yaml`](../scenarios/opencybele-capacity.yaml):

```properties
sim.arrival.lambdaMs = 2000
sim.arrival.firstFireMs = 3200
sim.arrival.pairs = stC>stA,stB>stC,stB>stA,stA>stC
sim.station.voteWindowMs = 6000
sim.topology = stA-stB:tr1,stB-stC:tr2
sim.station.capacities = stA=5,stB=1,stC=5
sim.road.delaysSec = tr1=5,tr2=8
sim.clock.startMs = 0
sim.clock.pace = 4
sim.gui.paces = Fast=8,Normal=1,Slow=0.3
sim.gui.mainLine = stA,stB,stC
sim.gui.branches = 
sim.random.masterSeed = 20080415
sim.headless = true
sim.trace.enabled = true
sim.trace.trainLookahead = 32
sim.stop.maxTrains = 0
sim.stop.maxClockMs = 27700
sim.stop.wallClockMs = 60000
sim.stop.stallMs = 20000
```


## 11. Cross-occasion: **not established**, and this is the honest state

[`Phase1.md` 1-PRE.4](../../docs/Phase1.md) handed #24 the half of the flake gate that #23 could not
close: every gate invocation behind `COVERAGE.md` §2.1 came from **one session on one boot**, and
reproducibility in this system has a measured **session-level** component —
`docs/kernel-config.md` (on `opencybele-baseline`) recorded 114 consecutive runs resolving the DEF-02
departure race identically and a *later* session resolving it the other way 7 times in 9, with
nothing changed, and could not identify what persists.

**The gate was re-run for #24, and it does not qualify as a distinct occasion.** Stated plainly:

| | |
|---|---|
| this invocation | 2026-08-20 09:40–09:43 UTC, boot `862aa4f8-9952-4132-9567-f3371a898849` |
| #23's invocation | same day, same boot (the machine has not rebooted since 2026-08-13 05:38 local, and #23 merged at 09:34 UTC — its gate runs precede that by minutes, not hours) |
| separation | **same boot, well under the 4-hour convention** → `ParityGate.Result.crossOccasionEvidence()` would reject it even if the ledger had survived |
| #23's ledger | **did not survive.** It was written to `build/parity-gate/` in a worktree that no longer exists, so this invocation read an empty ledger and reported *"no qualifying earlier run in the ledger (0 entries total)"*. |

What *was* possible was the hand comparison `COVERAGE.md` §2.1 explicitly provided for: #23 wrote its
five trace digests down so that a later invocation could be checked against them without the ledger.
**All five match** (§9). That is worth having and it is **not** cross-occasion evidence — it is a
second within-session sample of the same mode.

So the standing claim about these goldens is: **byte-reproducible within a session, on this machine,
with cross-occasion stability unmeasured.** A golden set in that state is an honest thing to freeze,
provided the state is written down — which is what this section is for. It is not a claim that a
replay tomorrow will match.

### What would establish it, exactly

The ledger is now at a path a clean build does not delete:

```
/home/beda/work/parity-gate-ledger/<scenario>.tsv
```

and this invocation's five entries are reproduced verbatim below, so the comparison survives even if
that directory does not. Any **one** of the following qualifies, and each is one command:

1. **After a reboot** (different boot id — the discriminator `ParityGate` actually keys on):
   ```bash
   ./gradlew parityGate \
       -Popencybele.dist=<abs>/build/install/opencybele \
       -Dparity.gate.runs=10 \
       -Dparity.gate.ledger=/home/beda/work/parity-gate-ledger
   ```
2. **At least 4 hours after 2026-08-20T09:43Z**, same command, same ledger. Four hours is
   `ParityGate.CROSS_OCCASION_HOURS`, and it is a **convention, not a measurement** — nobody has
   measured the interval at which the mode flips; `kernel-config.md`'s "hours" is an observation.
3. **On another machine** pointed at a copy of the same ledger directory. That is a different axis —
   evidence for portability, weaker evidence for the persists-for-hours property — and `ParityGate`
   does not distinguish the two, so do not read a green from it as answering (1).

The gate compares **entity-id sets**, not digests, because a cross-occasion digest difference would
be dominated by things the session mode does not control (`docs/defect-triage.md` §8.2). A
disagreement is not a licence to re-record: §8.2's rule is *retune the scenario, do not re-record
until it agrees*, and `Phase1.md` L7 stands.

### The ledger entries this invocation wrote

```
862aa4f8-9952-4132-9567-f3371a898849	2026-08-20T09:40:15.716761683Z	opencybele-capacity	1e056ae970eb4b27bc16ebb4cc392eebb76510fbf37fecbce18e23e8731de3e1	230	vl0,vl1,vl2,vl3,vl4,vl5,vl6,vl7,vl8
862aa4f8-9952-4132-9567-f3371a898849	2026-08-20T09:41:27.810071653Z	opencybele-congestion	48fbb42cc6da692cfea71c87b500a9e04084d97bfc9094e02356c7faf7b8913e	176	vl0,vl1,vl2,vl3
862aa4f8-9952-4132-9567-f3371a898849	2026-08-20T09:42:05.439531476Z	opencybele-lifecycle	acc212d84c66d69bddbd4f71859ab930f9634c58d5f60c480003168ec79579b4	84	vl0,vl1
862aa4f8-9952-4132-9567-f3371a898849	2026-08-20T09:42:58.847035451Z	opencybele-strict	0410d72d933bbddbe1f81b07c919144061b61f943448028dd5d4cd21d90b0c3e	607	vl0,vl1,vl2,vl3,vl4,vl5,vl6,vl7,vl8
862aa4f8-9952-4132-9567-f3371a898849	2026-08-20T09:43:32.161743745Z	opencybele-timers	7d79066aad6f62a4d0daff9de8d6d4b48ac63cc148fc6eba64cb1e93f19612cb	183	vl0,vl1,vl2
```

Columns: boot id · instant · scenario · normalized-trace digest · lines · entity ids.

## 12. Reproducing from a fresh clone

The acceptance criterion of #24 is that **a fresh clone at the tag reproduces every golden
byte-for-byte**. It was executed, not asserted. Two clones (the application at the tag, the harness
at this branch), an **empty** Maven repository so the vendor-jar recovery is exercised rather than
inherited, and the suite run against the committed bytes:

```bash
S=<scratch>
git clone <repo> $S/app && cd $S/app
git checkout pre-migration-baseline
MAVEN_REPO_LOCAL=$S/m2 ./scripts/bootstrap-vendor-jars.sh
./gradlew -Dmaven.repo.local=$S/m2 installDist

git clone <repo> $S/harness && cd $S/harness
git checkout jade-develop
./gradlew characterizationIT -Popencybele.dist=$S/app/build/install/opencybele
```

Actual output, abridged only by dropping the harness' own unit-level tests:

```
== 1. clone and check out the tag ==
f4c233c Probe agent emitting the canonical parity trace (#20) (#63)
pre-migration-baseline
== 2. bootstrap the vendor jars into an EMPTY local repository ==
[bootstrap-vendor-jars] installing com.iai:cybele-api:1.0 from cybelle/Cybele.jar into <scratch>/m2 (no mvn on PATH, copying)
[bootstrap-vendor-jars] installing com.iai:cybele-impl:1.0 from cybelle/CybeleImpl.jar into <scratch>/m2 (no mvn on PATH, copying)
[bootstrap-vendor-jars] OK — com.iai:cybele-api:1.0 and com.iai:cybele-impl:1.0 available in <scratch>/m2 (2 installed, 0 already current)
== 3. build the application ==
cybele-api-1.0.jar  cybele-impl-1.0.jar  opencybele.jar
== 4. clone the harness + goldens ==
5112f36586ca03a1…  parity-tests/golden/opencybele-capacity.txt
3b33b4f0788db91b…  parity-tests/golden/opencybele-congestion.txt
2e7d34242acebcc9…  parity-tests/golden/opencybele-lifecycle.txt
84b51584a40b9614…  parity-tests/golden/opencybele-strict.txt
f6db6287660b38e0…  parity-tests/golden/opencybele-timers.txt
== 5. run the suite against the committed goldens ==
OpenCybeleCapacityIT   > a full station refuses a train, a road queue reaches depth two, and voteWindow is visible PASSED
OpenCybeleCongestionIT > a train is queued on a contended track in the opposing direction, and the golden sees it PASSED
OpenCybeleLifecycleIT  > the smallest network exercises all fifteen channels and one full train lifecycle PASSED
OpenCybeleSmokeIT      > the real application runs end to end through OpenCybeleLauncher and matches its golden PASSED
OpenCybeleStrictIT     > the real application matches its golden LINE FOR LINE at a strict contract PASSED
OpenCybeleTimersIT     > the four timer sites fire the counts the scenario is sized for, and the noise decides burst membership PASSED
ParityGateIT           > … SKIPPED   (opt-in; -Dparity.gate.runs was not passed)
BUILD SUCCESSFUL in 38s
```

The five file digests printed by the clone are the ones in §10, so the clone's goldens are the
committed bytes and the runs matched them at `strict` — exact list equality, no tolerance.

Three details that make this a real reproduction rather than a re-run in disguise:

* **The Maven repository was empty.** `bootstrap-vendor-jars.sh` restored both 2008 IAI jars from the
  `withoutGradle` tag and installed them without `mvn` on `PATH`; Gradle then resolved them through
  `mavenLocal()` with `-Dmaven.repo.local` pointed at that same directory. Nothing came from the
  developer's `~/.m2`.
* **The application was built from the tag**, in a detached checkout of `f4c233c`, not from the
  worktree the verification runs used.
* **What was *not* isolated**: the Gradle distribution and its dependency cache (`~/.gradle`), so
  JUnit and SnakeYAML came from a warm cache rather than from the network, and the JDK is the one in
  §3. A machine with neither would need network access for the harness' two test-scope dependencies;
  the application itself has none beyond the two vendor jars.

*(The clone in the transcript above was taken at the commit that introduced this file, before this
section was filled in with the output. The two trees differ in this file alone — no golden, no
scenario, no harness source — which `git diff --stat` shows and which no test reads.)*

## 13. What this manifest does **not** establish

Said at the same volume as §9, because a manifest that prints only its green half is how "10
consecutive runs" came to be mistaken for reproducibility in the first place.

* **That a replay on another occasion matches.** §11. Unmeasured, with a measured reason to doubt it.
* **That a replay on another machine, JDK build or OS matches.** Everything here is one machine, one
  JDK (Red Hat 21.0.11+10), one kernel. The vendor jars are 2008 bytecode and the application is
  plain Java, so there is no known reason for a JDK-build dependence — "no known reason" is not a
  measurement.
* **That the *baseline* is correct.** The goldens pin behaviour including its defects. DEF-02 drops
  trains, DEF-16 lets a travel timer fire early, DEF-24 is present in the recorded configuration and
  has simply never fired. `docs/defect-triage.md` classifies each one; a port that "fixes" any of
  them produces a diff, and that diff is a *port change*, not a golden defect.
* **That every inventory row is pinned.** `COVERAGE.md` §10.9 records DEF-16 as **unmappable**: an
  implementation mutant that clamps the Gaussian travel delay to zero **survives** the whole suite.
  Three other implementation mutants were killed.
* **That the exact final tick reproduces.** An earlier revision of §7 made "reaches its expected
  final tick" an acceptance criterion and §10 pinned exact figures for it. Review measured the
  figure jittering by tens of milliseconds across healthy repeat runs (24024/24040/24064/24072 and
  27712/27728/27732) while everything the criterion is *for* stayed identical, so the claim was
  stated more confidently than its evidence supported. It is now an inequality against the bound
  plus the stop reason, and §10's figures are labelled observed. Nothing else in this manifest
  depended on it, and no golden did.
* **That §12's recipe is executable by anyone yet.** The reproduction was run from a clone of the
  **local** repository, because `pre-migration-baseline` has not been pushed — `git ls-remote --tags
  origin` returns `withoutGradle` alone. Until the maintainer runs §14's command, the recipe reads as
  generally executable and is not.
* **That the harness ref is mechanically tied to the tag.** Nothing binds them: if `jade-develop`'s
  normalizer changed, the tag would still point at the right binary while the goldens quietly meant
  something else. That coupling is `characterizationIT@opencybele`'s job — it runs the full suite on
  every push and is green — not the tag's.

* **That the goldens could not have been recorded from a wedged run** — by the gate alone. The gate
  compares runs to each other and to the golden and structurally cannot see that the golden itself
  was truncated. What excludes it here is the separate departure-count and stop-reason check in §7,
  taken from the raw stop banner of a run per scenario, plus the fact that every scenario's
  generated-train count equals its planned-election count.

### A follow-up worth doing, and deliberately not done here

`ParityGate`'s **default** ledger path is still `build/parity-gate`, which is what killed #23's
ledger, and the durable path used here (§11) is a machine-local absolute passed by hand — it is in no
repository and in no CI job. The workflow runs `characterizationIT` only, and `ParityGateIT` asserts
`crossOccasionDisagreements().isEmpty()`, which an **empty ledger satisfies vacuously** — so the
cross-occasion half can never turn red by omission. Reproducing the ledger lines in §11 mitigates the
loss but is manual, and the next runner is likely to repeat the mistake.

**Recommended:** change the default ledger path to somewhere a clean build does not delete. That is a
change to the *harness*, which `Phase1.md` L7 permits after the freeze — it touches no golden, no
scenario and no application source. It is left out of #24 on purpose: this issue's job was to freeze,
not to alter the instrument while freezing it.

## 14. The tag command

The tag exists locally and has been verified; **pushing it is an outward-facing action and is the
manager's call**, so #24 did not push it. The exact command:

```bash
git push origin pre-migration-baseline
```

To verify before pushing:

```bash
git tag -l --format='%(refname:short) %(objecttype) -> %(*objectname)' pre-migration-baseline
# pre-migration-baseline tag -> f4c233c081bac341af61cfe1d8317154443454dc
git branch -a --contains f4c233c   # opencybele-baseline, remotes/origin/opencybele-baseline
```

It is an **annotated** tag (not lightweight): it carries the tagger, the date and the two-ref
recovery note, which is what makes it self-describing three phases later. `withoutGradle`, the
repository's other load-bearing tag, is the same shape.
