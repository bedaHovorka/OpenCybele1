# Seeded per-agent RNG

> **Headline.** The one shared `static final Random` is gone. Every agent that draws
> random numbers now owns a stream seeded from a scenario-level master seed
> (`-Dsim.random.masterSeed`), and each stream is byte-identical under any thread
> interleaving — demonstrated directly, against a positive control, not inferred from two
> lucky runs. A fixed seed pins which trains run, from where, and in what order, over twelve
> measured runs.
>
> **It does not make a whole run reproducible**, and two specific things it does not pin —
> departure timestamps, and the order of stdout lines when two trains depart in the same
> millisecond — decide what
> [#21](https://github.com/bedaHovorka/OpenCybele1/issues/21)'s comparison has to normalise.
> The last two sections give the measurements and the resulting requirement.

Issue [#15](https://github.com/bedaHovorka/OpenCybele1/issues/15). Companion documents:
[`docs/scenario-config.md`](scenario-config.md) for how `sim.*` is transported and
validated, [`docs/assertion-triage.md`](assertion-triage.md) for the `-ea` baseline,
[`docs/iteration-order.md`](iteration-order.md) for what #19 pinned.

> **`INVENTORY` ids** (`NDT-xx`, `SEM-xx`, `DEF-xx`) cited below come from
> `docs/INVENTORY.md`, which lives on the **`jade-develop`** branch and is not present on
> this one: `git show origin/jade-develop:docs/INVENTORY.md`.

## The defect

```java
// Generator (before)
private static final Random random = new Random();
public static Random getRandom() { return random; }
```

Three consumers, on three different kinds of thread. (Sites are given as
*class.method*, not line numbers: this project has been bitten three times by line
citations drifting between branches, and #18 and #19 both moved these files.)

| Site | Draw | Thread |
|---|---|---|
| `Generator.generateTrain` | `nextInt(pairs.length)` — origin/destination choice | the generator activity |
| `Generator.exp` | `nextDouble()` — exponential inter-arrival | the generator activity |
| `RoadAgent.travelStart` | `500 * nextGaussian()` — travel jitter | **each** of the seven road agents' event threads |

`java.util.Random` is thread-safe, so nothing was ever corrupted. But thread-safety is not
reproducibility: the *order* in which eight threads consume one sequence is the scheduler's
decision, so seeding that object would have fixed only the **multiset** of numbers the run
draws, never which number reaches which call site. That is why "fix random seeds via
system property" ([`Phase1.md` 1-PRE.2](Phase1.md)) needed an issue of its own.

## The fix

One stream per agent, seeded by a pure function of the master seed and the agent's name
(`SimRandom.java`):

```
seed(agent) = splitmix64_finalizer( fnv1a64( utf8(agentName), basis = FNV_BASIS ^ masterSeed ) )
```

* **FNV-1a 64** over the UTF-8 bytes of the name, with the master seed folded into the
  offset basis, then the **SplitMix64 finalizer** as an avalanche step so that names one
  character apart (`tr1`/`tr2`) start far apart in seed space.
Independently measured on the derivation itself (review of this change, not by its author):
over 10 000 master seeds the Hamming distance between `seedFor(m,"tr1")` and
`seedFor(m,"tr2")` averages **31.93** bits of 64 (ideal 32); **zero** collisions across
400 000 master seeds × 8 streams; and the probability that two sibling streams agree on
their first `nextInt(6)` is **0.1663** (ideal 0.16667). One-character-apart names do not
produce correlated streams.

* Arithmetic only — no `String.hashCode`, no `MessageDigest`, no JDK-version-dependent
  behaviour — so **the derived seeds and the draw sequences are portable**: verified
  bit-identical for all streams on JDK 21 and JDK 25, and re-derived from scratch in an
  independent Python implementation.
  <br>What that does *not* claim is bit-identical *simulation* across JVMs.
  `Generator.exp` is `Math.round(-mean * Math.log(nextDouble()))`, and `Math.log` is
  specified to within 1 ulp and explicitly not required to agree with `StrictMath`. A 1-ulp
  difference would almost never survive `Math.round`, but "almost never" is not "never", and
  the JLS gives no guarantee here. The *streams* are portable; a millisecond gap computed
  from them is portable in practice, not by specification.
* Seeds are derived **by name, not by creation order**. This is the decision the whole
  change rests on, and it survived a question that was open when it was made — see
  [Creation order: the question is now answered](#creation-order-the-question-is-now-answered)
  below. Briefly: #19 pinned the order in which create *requests* are issued, but
  `Cybele.createAgent` is asynchronous and the order in which agent *constructors* actually
  run remains nondeterministic — measured as six distinct orders in six runs. Seeding the
  *n*-th agent created would have made every stream nondeterministic, and this change
  worthless. Keying by name is also what keeps the seeds stable when `sim.topology` gains an
  edge: by ordinal, one added edge would silently move all seven road seeds.

Who owns which stream:

| Stream name | Owner | Draws |
|---|---|---|
| `Generator.od` | the `Generator` activity of `RailwayMainAgent` | one `nextInt(pairs.length)` per generated train |
| `Generator.interarrival` | the same activity | one `nextDouble()` per generated train |
| `tr1` … `tr7` | each `RoadAgent`, keyed by `getName()` | one `nextGaussian` per traversal |

**The generator's two draws get one stream each, not a shared one.** They *could* have
shared: both are taken inside a single `generateTrain` invocation in an order fixed by the
source text, and a Cybele activity dispatches its own events strictly serially
(`INVENTORY` SEM-04, on `jade-develop`), so a shared stream would have been
interleaving-independent too — that was the first implementation, and review confirmed the
premise holds. It was split anyway, because a shared stream couples *alignment* to the
number and order of draws in that handler: add a third draw, or reorder the two, and every
later value of both series shifts. Against a recorded golden that shows up as a wholesale
behaviour diff produced by an edit that changed no behaviour. Separate streams cost one
extra `Random` and make each series a function of its own call site only. Both call sites
now carry a comment saying so.

A stream is still ordered by *its own* draw count, which is why the same warning appears at
the road sites: this removes cross-site coupling, not the dependence of a stream on how
many times its own call site has run.

**Distributions and call sites are untouched.** The expressions still read
`hhh[random.nextInt(hhh.length)]`, `Math.round(-mean * Math.log(random.nextDouble()))` and
`delayInSeconds() + (long)(500*random.nextGaussian())`, in the same order, with the same
magnitudes; only the receiver changed from a shared static to a per-agent field.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `sim.random.masterSeed` | `random` | signed 64-bit integer, or the literal `random` to draw one for this run |

Same transport and precedence as every other parameter (`-D` > scenario file > default),
same eager validation in `Main` before `Cybele.startUp()` — a bad seed exits 1 with a
message, rather than being swallowed by Cybele's reflective dispatch
(`docs/assertion-triage.md`, Result 3).

**The default is deliberately `random`**, so an ordinary interactive run keeps varying as
it always did. A defaulted seed is therefore printed twice on **stderr** (stdout belongs to
the golden trace): once in the configuration banner and once as an explicit replay hint.

```
sim.random.masterSeed = 5605042205657107861    # drawn for this run; pass it back to replay
------------------------------
--- no sim.random.masterSeed given; drew 5605042205657107861. Replay this run's random streams with:
---   -Dsim.random.masterSeed=5605042205657107861
```

An explicitly-given seed is echoed in the banner as `# default: random`, i.e. "this is not
the default, the default is to draw one".

Whichever way the seed was obtained, the **resolved number** is republished into the system
properties, so anything that reads `sim.random.masterSeed` later in the JVM sees the value
actually in force and never the word `random`. `Main` then prints the **stream table** —
every stream name with the seed it will run on — so a captured stderr is a complete manifest
of a run's randomness for [#24](https://github.com/bedaHovorka/OpenCybele1/issues/24):

```
--- random streams ---
  Generator.od = -947576953628979
  Generator.interarrival = -8007800441624140388
  tr1 = 6186417220773925723
  ...
----------------------
```

Two warnings that belong with the key:

* `sim.random.masterSeed` is the **one configuration value that is not idempotent**.
  Constructing the configuration twice with the property unset draws two different seeds.
  Within a JVM that cannot happen — the instance is memoised and the number republished — but
  a **second JVM** (a remote Cybele container today, a second JADE container in phase 2)
  resolves its own configuration and would draw its own seed. Any port that distributes
  agents must transport the seed explicitly. Recorded in the Javadoc of
  `ScenarioConfig.get()` and `SimRandom`.
* Passing the flag positionally to the `installDist` start script
  (`opencybele -Dsim.random.masterSeed=…`) is **silently ignored** — Gradle start scripts route
  `$@` to program arguments, so the run draws a fresh seed while looking pinned. Use
  `OPENCYBELE_OPTS=`. `./gradlew run -D…` is forwarded correctly.

## The negative `delay2` (issue #22 item 7)

`RoadAgent.travelStart` computes

```java
final long delay2 = delayInSeconds() + (long)(500*random.nextGaussian());
```

For `tr1` and `tr2` the base delay is 1 s, so a Gaussian draw below −2 σ makes `delay2`
negative — **≈2.3 % of traversals on those two roads** (P(Z < −2) = 0.0228; the rate over the
actual seeded streams was measured at **0.02263**, so the streams behave as the analytic
figure predicts).

**It is not clamped here, and must not be.** Clamping would change behaviour, and this
branch freezes behaviour. What happens instead is measured, not assumed:
[#9](https://github.com/bedaHovorka/OpenCybele1/issues/9) timed Cybele's timer service with a
negative delay and found that **it fires immediately** — `delay = -1500` produced a
**1–5 ms** callback against **2001–2010 ms** for `delay = 2000`. So a negative draw is an
*instantaneous traversal*, not a lost train, not an error, and not a silently dropped timer.
Recorded as `INVENTORY` DEF-16 / SEM-06.

Consequences for the port and for #22:

* The behaviour to reproduce is "travel time = max(0, base + jitter) as observed", realised
  by an immediate callback. A JADE `WakerBehaviour` with a negative period must be checked
  for the same semantics rather than assumed to match — JADE's `WakerBehaviour` also fires
  at once for a past deadline, but that is the sort of thing a golden should pin, not the
  port's optimism.
* Seeding does **not** remove the case: the same seed reproduces the same negative draws.
  It makes them reproducible, which is the point — a golden recorded at a fixed seed will
  contain them at fixed positions in each road's stream instead of at random.
* Classification for #22 is therefore **pin, not fix**: instantaneous traversals are part of
  the frozen baseline behaviour.

## What a fixed seed does **not** buy (read this before citing it)

Per-agent stream determinism is a property of each *sequence*. A run is more than its
sequences, and three things still float:

1. **The clock is real time.** `Cybele.createClock(..., startMs, pace)` advances with the
   wall clock, so every timestamp the simulation prints — `TrainPlan.departure`, hence every
   `<train> in <station> at <t>` line — carries scheduling and JIT noise. Two runs at one
   seed produce the same *trains* but not the same *numbers*.
2. **Event, thread and print ordering are unpinned.** The kernel's queues and thread pool
   are untouched by this issue ([#16](https://github.com/bedaHovorka/OpenCybele1/issues/16)).
   [#19](https://github.com/bedaHovorka/OpenCybele1/issues/19) has landed and fixed the
   graph-iteration half of the problem, but *agent-initialisation* order stays
   nondeterministic (`Cybele.createAgent` is asynchronous — measured, see §"Creation order"
   below), and two printlns issued from different agent threads at the same simulated
   millisecond come out in either order. §3 measures that happening.
3. **Messages are still lost.** `INVENTORY` DEF-02/DEF-22: a `START` sent before the train
   opens its channel is dropped, and a lost vote hangs planning permanently. When that
   happens the run diverges no matter how well-seeded it is.

There is a subtler consequence of (2) and (3) worth stating explicitly, because it is the
thing that will bite whoever records the golden. **A stream is pinned by its own draw
count, not by simulated time.** `tr3`'s fifth `nextGaussian` is always the same number — but
*which train* takes it depends on how many trains have crossed `tr3` by then. Lose one train
to DEF-02 and every later draw on that road attaches to a different traversal, even though
the sequence itself never changed. Per-agent streams remove the *cross-agent* coupling
(`tr3` no longer depends on what `tr5` did); they cannot remove the *within-agent*
dependency on the run's own history.

## Evidence

### 1. Interleaving-independence, measured directly (`./gradlew rngProof`)

A run-to-run comparison of the application cannot establish this criterion — the app's own
scheduling might simply be stable today. `tools/java/SeedInterleavingCheck.java` therefore
drives the streams themselves: one single-threaded **reference** sequence per stream, then
the same streams reproduced under four different concurrency regimes, comparing every value.

```
$ ./gradlew rngProof -Prng.full
  masterSeed = 20080415
  streams    = 9 [Generator.od, Generator.interarrival, tr1, tr2, tr3, tr4, tr5, tr6, tr7]
  trials     = 24 per mode, draws = 4000 (x3 values) per stream
  cores      = 24

[1] per-stream seed derivation
    Generator.od seed =     -947576953628979  0xfffca22f0c4816cd
    Generator.interarrival seed = -8007800441624140388  0x90de93ececc6e59c
    tr1       seed =  6186417220773925723  0x55da91af6c96d35b
    tr2       seed =  7912102813606516531  0x6dcd6f96bf629733
    tr3       seed =  -384334807269073387  0xfaaa918771e89e15
    tr4       seed =  2751653924075954564  0x262fd6aa415b6184
    tr5       seed =  8909082372280082495  0x7ba36b371a72e03f
    tr6       seed = -8044306867122679267  0x905ce18660ead21d
    tr7       seed =  -427033596665524117  0xfa12df34d398106b

[2] per-agent streams under concurrent interleavings
    barrier         24/24 trials byte-identical to the single-threaded reference
    jitter          24/24 trials byte-identical to the single-threaded reference
    handoff         24/24 trials byte-identical to the single-threaded reference
    oversubscribed  24/24 trials byte-identical to the single-threaded reference

[3] positive control: one shared Random (the pre-#15 design), same harness
     0/95 trials reproduced the first trial's per-stream sequences (expected: few or none)
    => the harness does detect interleaving-dependent streams, so [2] is a real result

RESULT: PASS
```

96/96 trials identical, on a 24-core machine. The **control** is the load-bearing part: the
identical harness driving the *old* design — one shared `Random` — reproduced its first
trial **0 times out of 95**, and does so **0/11 in each of the four regimes separately**, not
merely in aggregate. A subtler mutant that shares one `Random` between `tr1` and `tr2` only
is caught 0/11 as well, with the failure message naming exactly those two streams. The
harness also refuses to pass vacuously: `rngProof --args="check 20080415 0 10"` **fails the
build** with *"the control never diverged … its PASS above is worthless"*.

The `handoff` mode is the one that models the real system: each stream's draws are split into
eight chunks, each executed on a **brand-new thread** (verified: eight distinct thread
identities per stream), serialised as Cybele serialises one activity's events (SEM-04).
Thread identity therefore demonstrably does not enter the sequence — only the number of draws
taken does. All four regimes were confirmed to produce genuinely different global draw
traces (12/12 distinct), so they are four experiments rather than one repeated.

**Cost.** The sweep above is the `-Prng.full` one. It is *not* what `build` runs: the check
wired into `check` is `6` trials × `500` draws, because the sensitivity is not in the volume —
the control is caught 0/47 even at **10** draws, and 6 × 500 still reports control 0/23. The
difference matters because this task is on the branch that gates golden recording:

| | wall time |
|---|---|
| incremental `./gradlew build`, before this was tuned | **14.21 s** |
| incremental `./gradlew build`, `rngProof` UP-TO-DATE | **0.55 s** |
| `rngProof` when it does re-run (6 × 500) | ~1 s |
| `./gradlew rngProof -Prng.full` (24 × 4000) | 14.6 s |

The task now declares its inputs (the compiled tool and application classes, the argument
list, and any forwarded `sim.*` properties) and an output file, so an unchanged tree skips it
entirely; a real change to either source set re-runs it (both verified). It used to declare
neither, so it re-ran in full on every incremental build.

### 2. The road agents really do use those streams

Two mechanisms make this checkable from a committed build rather than from a probe that was
reverted:

* **`Main` prints the stream table to stderr** — `Generator.od`, `Generator.interarrival`
  and one line per configured track, with the seed each will run on. Derived from the
  configuration alone, before the kernel starts, so a run's stderr is a complete manifest of
  its randomness for [#24](https://github.com/bedaHovorka/OpenCybele1/issues/24).
* **`RoadAgent`'s constructor asserts its own name is a configured track**
  (`assert ScenarioConfig.get().getRoadNames().contains(getName())`). That is the link the
  table cannot prove by itself: it ties the name the agent actually keys its stream by to
  the names the table was computed from. With `-ea` on — which is the default here
  ([#14](https://github.com/bedaHovorka/OpenCybele1/issues/14)) — a mismatch is loud instead
  of silently producing a table that is a fiction. This is a **33rd** `assert` site;
  `docs/assertion-triage.md` counted 32.

Before those existed, a temporary `System.err` probe in the constructor (added, run,
reverted) printed each road's stream name and seed, and every one matched the derived table;
review independently confirmed all seven roads, seven distinct `Random` instances with no
sharing, and constructors running on five different kernel threads.

### Creation order: the question is now answered

That probe printed the roads in the order `tr4, tr1, tr3, tr6, tr5, tr2, tr7` — neither
sorted nor the `[tr1, tr4, tr7, tr5, tr3, tr6, tr2]` that `INVENTORY` NDT-02 recorded, which
looked like it might contradict [#19](https://github.com/bedaHovorka/OpenCybele1/issues/19).
It does not. Instrumenting the loop and the constructors in one process, over six runs:

| observed | result |
|---|---|
| the loop that iterates the network (`net.values()`) | `[tr1, tr4, tr7, tr5, tr3, tr6, tr2]` — **identical in all six runs** |
| the order `Cybele.createAgent` is *called* in | identical in all six runs |
| the order road **constructors actually run** | **six different orders in six runs** |

`Cybele.createAgent` is asynchronous. #19's measurement is correct and #19 fixes the loop
order; the probe was sampling something else — the initialisation order, which #19 does not
and cannot pin. **Agent-initialisation order remains nondeterministic after #19**, and the
same holds for stations.

This is the load-bearing vindication of the derivation scheme. Had the streams been seeded
by the *n*-th agent created, they would have been nondeterministic in every run and this
entire change would have been worthless. Keying by name is what saved it.

### 3. Application level: what a fixed seed pins — and what it does not

`installDist` start script, `-ea --patch-module`, `timeout`, stdout and stderr captured
separately, runs executed sequentially on one machine. Twelve runs on the rebased branch:
four per configuration.

| runs | configuration | departures | departure sequence (train, origin, order) |
|---|---|---|---|
| A1–A4 | defaults, seed `20080415`, 150 s | **17, 17, 17, 17** | identical across all four |
| B1–B4 | defaults, seed `987654321`, 150 s | **15, 15, 15, 15** | identical across all four |
| S1–S4 | `scenarios/short.properties`, seed `20080415`, 30 s | **54, 54, 54, 54** | identical across all four |

Against the unseeded 13–25 spread this branch used to show over 150 s
(`docs/scenario-config.md`), four runs landing on exactly 17 and four on exactly 15 is not
sampling luck. The two seeds also differ from each other, as they must.

**Raw stdout is never identical, and — this is the part that matters for
[#21](https://github.com/bedaHovorka/OpenCybele1/issues/21) — stripping timestamps is not
always enough.**

| comparison | A-series (4 runs) | B-series (4 runs) | S-series (4 runs) |
|---|---|---|---|
| raw stdout equal | no | no | no |
| timestamp-stripped stdout equal | **yes**, 3/3 pairs | **yes**, 3/3 pairs | **no** — 2 distinct orderings across 4 runs |
| train-line *order* equal | yes | yes | **no** |
| departure timestamps, max abs difference | 12 ms | 16 ms | 120 ms |
| max difference after removing the process-start offset | 2 ms | 3 ms | 104 ms |
| departure-instant ties in the run | 2 | 0 | 5 |

The S-series divergence, in full:

```
S1                          S2
vl14 in stA at 87400        vl14 in stA at 87368
vl15 in stC at 87400        vl15 in stC at 87368
vl14 started                vl15 started      <-- swapped
vl15 started                vl14 started
```

`vl14` and `vl15` are planned for the **same simulated millisecond**.
`Planning.placeTrainIntoFirstStation` prints both `in` lines from one activity, serially, then
sends each train its `START`; the two `Train.start` handlers then run on two different train
threads and print `<train> started` in whichever order the scheduler picks. Review observed
the neighbouring variant at another seed — a *departure* line overtaking a *started* line
(`vl5 started` / `vl6 in stC` swapping, three arrangements across six runs) — so both the
`started`/`started` and the `in`/`started` orderings can move. The A-series had two tie
instants and did not flip in four runs; the S-series, with five, flipped once in four. **The
ordering is unpinned whenever two trains share a departure instant, not reliably wrong.**

An earlier revision of this document claimed, from two runs at one seed, that
timestamp-stripped stdout is identical. At that seed it is; as a general claim about the
branch it is false, and the correction is the reason §"Answering the question #21 will ask"
below now specifies a normalizer rather than a `sed`.

### 4. The streams predict the run, offline

`./gradlew rngProof --args="plan <seed> N"` replays `Generator.generateTrain`'s two draws
without starting the kernel, from the two generator streams.

Two things it prints, with very different standing:

* the **origin** and **destination** of each train, in order — of which stdout carries the
  origin only (`TrainPlan.toString` prints `<train> in <station> at <t>`, and that station is
  the train's `from`). The origin half is verified below against live runs; the destination
  half is **not observable from stdout** and is therefore unverified by these runs, though it
  comes from the same array element as the origin;
* a `nominalAtMs` column, which is `firstFireMs` plus the running sum of the drawn gaps —
  what the generator *asks* the timer for. It is a **nominal timeline**, not a prediction of
  observed departures: delivery latency and the voting delay both move the real ones, so it is
  neither validated here nor validatable from stdout. (It also depends on `Math.log`; see the
  portability caveat above.)

Checked per train **id**, not per position: in all **12** runs, every departing train's origin
equals the offline prediction for that id. Position and id coincide only when the network
keeps up — in the S-series they do not (54 departures whose ids run to `vl445`, because
`Planning` is the throughput ceiling under `short.properties` and departures leave the
generator's order behind), which is itself a useful reminder for #24 that "the *n*-th line"
and "train *n*" are different things.

The seed pins the trains across *scenarios*, too: A1 and S1 share a seed but differ in
`sim.arrival.lambdaMs` (8500 vs 500) and `sim.clock.pace` (1 vs 8), and the 17 train ids they
have in common carry **identical origins**.

An independently computed sequence agreeing with twelve live runs is stronger than the runs
agreeing with each other: it rules out "every run happened to schedule identically".

### 5. A defaulted seed can be replayed

A run with no `sim.random.masterSeed` printed `drew -4141027971352534932`; feeding that number
back reproduced the run's train-by-train origin sequence exactly (8/8 departures over a 60 s
window), timestamps again differing by a few ms. Review reproduced the round trip 3/3 from
inside the JVM. That is the whole purpose of defaulting to `random` *and* printing it.

### 6. Failure modes stay loud

| input | result |
|---|---|
| `-Dsim.random.masterSeed=notanumber` | exit **1**, `IllegalArgumentException: sim.random.masterSeed must be a signed 64-bit integer or 'random', got 'notanumber'`, `grep -c "Cybele version"` on stdout = **0** — it fails before the kernel starts |
| `-Dsim.random.seed=5` (mistyped key) | exit **1**, unknown-key message listing the known keys |
| `sim.random.masterSeed=42` in a `-Dsim.config` file | accepted, banner shows `42`, run proceeds |
| `sim.config=scenarios/default.properties` | accepted; the file now carries the key explicitly |
| a bare `-Dsim.random.masterSeed=…` argument to the `installDist` script | **silently ignored** — see the warning in `docs/scenario-config.md`; it is a pre-existing property of Gradle start scripts, not of this change, and it is the most dangerous way to record a golden at the wrong seed |

### 7. Cleanliness

Across every captured run — the 12 above, plus 8 × 150 s and 2 × 60 s from the first
measurement round, `./gradlew run` for 120 s, a `-D`-forwarded `./gradlew run`, and the
validation runs — **zero** `AssertionError` (including the new road-name assertion, exercised
seven times per run) and **zero** `Exception in thread "` in stdout *or* stderr. `./gradlew
run` still opens the GUI and behaves as before; `./gradlew build` is clean.

## Answering the question #21 will ask

**Is a byte-identical stdout achievable at a fixed seed today? No.** What is achievable, and
now measured over twelve runs in three configurations, is:

* every train, its id, its origin, its destination and the **order departures occur in** —
  **pinned by the seed**, and predictable offline before the run starts;
* the number of trains generated and departed in a given window — pinned, up to where the
  `timeout` cut falls;
* every departure timestamp — **not** pinned. ±3 ms after removing the process-start offset
  at default settings (±8 ms in an independent measurement round; take ≤8 ms as the bound),
  ±16 ms absolute, and ±104 ms under `short.properties` at pace 8;
* the **order of stdout lines** — **not** pinned whenever two trains share a departure
  instant. Both `started`/`started` and `in`/`started` transpositions have been observed.

### The normalizer #21 has to build

Projecting away the `at <t>` field is **not sufficient** — that was this document's earlier
claim and the S-series disproves it. A golden comparison on this branch needs, in addition:

1. **Canonical line order within an equal-departure group.** Either sort the lines belonging
   to one departure instant, or — cleaner — split stdout into two independent streams,
   `<train> in <station>` and `<train> started`, and compare each in its own order. The
   causal edge `in(vlN)` → `started(vlN)` for the *same* train is enforced by the code and
   may be asserted; nothing orders lines belonging to *different* trains at the same instant.
2. **Timestamp comparison with a tolerance**, or by rank rather than value. ≤8 ms at default
   settings; do not carry that number to `short.properties`, where it is an order of
   magnitude larger.
3. **Per-train-id comparison, not per-line-position.** Under load, departures are not in id
   order (S-series: 54 departures with ids up to `vl445`).

A golden that pins raw stdout, or that normalises only timestamps, will flake at any seed
that produces a departure tie — and it will look like a port bug when it does.

The three things standing between here and a byte-exact golden are named above: the real-time
clock, kernel event/thread ordering
([#16](https://github.com/bedaHovorka/OpenCybele1/issues/16); note that
[#19](https://github.com/bedaHovorka/OpenCybele1/issues/19) has landed and fixed the
iteration-order half, while agent-*initialisation* order remains nondeterministic) and the
lost-message defects (`INVENTORY` DEF-02/DEF-22, triaged in
[#22](https://github.com/bedaHovorka/OpenCybele1/issues/22)).
