# Seeded per-agent RNG

> **Headline.** The one shared `static final Random` is gone. Every agent that draws
> random numbers now owns a stream seeded from a scenario-level master seed
> (`-Dsim.random.masterSeed`), and each stream is byte-identical under any thread
> interleaving — demonstrated directly, against a positive control, not inferred from two
> lucky runs. **This does not make a whole run reproducible yet**, and the last section
> says exactly what still blocks that.

Issue [#15](https://github.com/bedaHovorka/OpenCybele1/issues/15). Companion documents:
[`docs/scenario-config.md`](scenario-config.md) for how `sim.*` is transported and
validated, [`docs/assertion-triage.md`](assertion-triage.md) for the `-ea` baseline.

## The defect

```java
// Generator.java:42 (before)
private static final Random random = new Random();
// Generator.java:76 (before)
public static Random getRandom() { return random; }
```

Three consumers, on three different kinds of thread:

| Site | Draw | Thread |
|---|---|---|
| `Generator.java:66` | `nextInt(pairs.length)` — origin/destination choice | the generator activity |
| `Generator.java:75` | `nextDouble()` — exponential inter-arrival | the generator activity |
| `RoadAgent.java:101` | `500 * nextGaussian()` — travel jitter | **each** of the seven road agents' event threads |

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
* Arithmetic only — no `String.hashCode`, no `MessageDigest`, no JDK-version-dependent
  behaviour — so a seed printed by one run reproduces that run on any JVM.
* Seeds are derived **by name, not by creation order**. That matters here specifically:
  agent creation order is hash-order-dependent (`INVENTORY` NDT-01/NDT-02) and is *not*
  fixed on this branch ([#19](https://github.com/bedaHovorka/OpenCybele1/issues/19)). A
  scheme that seeded the *n*-th agent created would have silently inherited that
  nondeterminism.

Who owns which stream:

| Stream name | Owner | Draws |
|---|---|---|
| `Generator` | the `Generator` activity of `RailwayMainAgent` | `nextInt`, then `nextDouble`, once per generated train |
| `tr1` … `tr7` | each `RoadAgent`, keyed by `getName()` | one `nextGaussian` per traversal |

The generator's two draws share one stream deliberately: they are taken from the same
handler invocation, in a fixed order, and a Cybele activity dispatches its own events
strictly serially (`INVENTORY` SEM-04), so their interleaving is fixed by construction.

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
actually in force and never the word `random`.

## The negative `delay2` (issue #22 item 7)

`RoadAgent.travelStart` computes

```java
final long delay2 = delayInSeconds() + (long)(500*random.nextGaussian());
```

For `tr1` and `tr2` the base delay is 1 s, so a Gaussian draw below −2 σ makes `delay2`
negative — **≈2.3 % of traversals on those two roads** (P(Z < −2) = 0.0228).

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
2. **Event ordering is unpinned.** The kernel's queues and thread pool are untouched by this
   issue ([#16](https://github.com/bedaHovorka/OpenCybele1/issues/16)), and hash iteration
   order still decides agent creation order and route choice
   ([#19](https://github.com/bedaHovorka/OpenCybele1/issues/19)).
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
the same eight streams reproduced under four different concurrency regimes, 24 trials each,
4000 draws (`nextInt`, `nextDouble`, `nextGaussian`) per stream per trial.

```
[1] per-stream seed derivation
    Generator seed =  3168385922373400048  0x2bf85e35a66a35f0
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
```

96/96 trials identical, on a 24-core machine, at master seed 20080415. The **control** is
the load-bearing part: the identical harness driving the *old* design — one shared
`Random` — reproduced its first trial **0 times out of 95**. The check is not vacuous, and
it is wired into `check`, so `./gradlew build` runs it.

The `handoff` mode is the one that models the real system: each stream's draws are split
into eight chunks, each executed on a **brand-new thread**, serialised as Cybele serialises
one activity's events (SEM-04). Thread identity therefore demonstrably does not enter the
sequence — only the number of draws taken does.

### 2. The road agents really do use those streams

A temporary `System.err` probe in `RoadAgent`'s constructor (added, run, reverted — not part
of the commit) printed each road's stream name and seed at master seed 20080415:

```
PROBE roadStream name='tr4' seed=2751653924075954564
PROBE roadStream name='tr1' seed=6186417220773925723
PROBE roadStream name='tr3' seed=-384334807269073387
PROBE roadStream name='tr6' seed=-8044306867122679267
PROBE roadStream name='tr5' seed=8909082372280082495
PROBE roadStream name='tr2' seed=7912102813606516531
PROBE roadStream name='tr7' seed=-427033596665524117
```

Every seed equals the checker's table above, so the names really are `tr1`…`tr7` and the
running system's streams are the ones the proof exercises. Note the **creation order** in
that run — `tr4, tr1, tr3, tr6, tr5, tr2, tr7` — which is neither sorted nor the order
`INVENTORY` NDT-02 recorded. That is precisely why seeds are derived from names: creation
order is still unpinned, and seeding by it would have re-imported that nondeterminism.

### 3. Application level: what a fixed seed pins, over 150 s runs

`installDist` start script, `-ea --patch-module`, defaults except the seed, `timeout 150`,
stdout and stderr captured separately, runs executed sequentially on one machine.

| runs | seed | departures | train→origin sequence | stdout |
|---|---|---|---|---|
| A1–A4 | `20080415` | **16, 16, 16, 16** | identical across all four | 55 lines each; **39/55 lines byte-identical**, the 16 that differ are exactly the departure lines, and only in their `at <t>` field |
| B1–B2 | `987654321` | **18, 18** | identical to each other | same shape |
| A vs B | — | 16 vs 18 | **diverges at `vl2`** (`stA` vs `stC`) | different |

Departure timestamps at a fixed seed agree to **≤ 4 ms** (A-series) and **≤ 6 ms**
(B-series) once the process-start offset is removed, and to ≤ 16 ms in absolute terms; they
are never *equal* — that residual is limitation (1) above, the real-time clock, and nothing else.

Run-to-run spread is what makes this meaningful: the same binary with an *unseeded* RNG
gave 13–25 departures over 150 s (`docs/scenario-config.md`). Four runs landing on 16, 16,
16, 16 and two on 18, 18 is not a coincidence of sampling.

### 4. The streams predict the run, offline

`./gradlew rngProof --args="plan <seed> 20"` replays `Generator.generateTrain`'s two draws
without starting the kernel. Its train-by-train origin/destination list matched the observed
`Planning` output of **all six runs**, for the whole truncated prefix (16 and 18 trains):

```
seed 20080415  A1 A2 A3 A4 : matches offline prediction for the first 16 departures : True
seed 987654321 B1 B2       : matches offline prediction for the first 18 departures : True
```

An independently computed sequence agreeing with six live runs is stronger than the six runs
agreeing with each other: it rules out "all runs happened to schedule identically".

### 5. A defaulted seed can be replayed

A run with no `sim.random.masterSeed` printed `drew -4141027971352534932`; feeding that
number back reproduced the run's train-by-train origin sequence exactly (8/8 departures over
the shorter 60 s window), timestamps again differing by a few ms. That is the whole purpose
of defaulting to `random` *and* printing it.

### 6. Failure modes stay loud

| input | result |
|---|---|
| `-Dsim.random.masterSeed=notanumber` | exit **1**, `IllegalArgumentException: sim.random.masterSeed must be a signed 64-bit integer or 'random', got 'notanumber'`, `grep -c "Cybele version"` on stdout = **0** — it fails before the kernel starts |
| `-Dsim.random.seed=5` (mistyped key) | exit **1**, unknown-key message listing the known keys |
| `sim.random.masterSeed=42` in a `-Dsim.config` file | accepted, banner shows `42`, run proceeds |

### 7. Cleanliness

Across every captured run — 6 × 150 s, 2 × 60 s, `./gradlew run` for 120 s, `./gradlew run
-Dsim.random.masterSeed=…`, plus the validation runs — **zero** `AssertionError` and **zero**
`Exception in thread "` in stdout *or* stderr. `./gradlew run` still opens the GUI and
behaves as before; `./gradlew build` is clean with the proof wired into `check`.

## Answering the question #21 will ask

**Is a byte-identical stdout achievable at a fixed seed today? No.** What is achievable, and
now measured, is:

* every train, its origin, its destination and their order — **pinned by the seed**;
* the number of trains generated in a given wall-clock window — pinned, up to where the
  `timeout` cut falls;
* every departure timestamp — **not** pinned; ±4–6 ms of real-time noise.

The three things standing between here and a byte-exact golden are named above:
the real-time clock, kernel event/thread ordering
([#16](https://github.com/bedaHovorka/OpenCybele1/issues/16),
[#19](https://github.com/bedaHovorka/OpenCybele1/issues/19)) and the lost-message defects
(`INVENTORY` DEF-02/DEF-22, triaged in
[#22](https://github.com/bedaHovorka/OpenCybele1/issues/22)). Until they are addressed, a
golden recorded from this branch should either be taken over a **timestamp-free projection**
of stdout — the `<train> in <station>` pairs in order, which this document shows is stable
across four runs and predictable offline — or diff timestamps with a tolerance. Recording
raw stdout and calling it a golden will fail on the first replay, for reasons that have
nothing to do with the port.
