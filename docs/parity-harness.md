# The parity harness — scenario format, contract levels, and how a run is judged

> Produced for [#12](https://github.com/bedaHovorka/OpenCybele1/issues/12) (*Parity harness
> skeleton*), on `jade-develop`, which is where [#11](https://github.com/bedaHovorka/OpenCybele1/issues/11)
> placed the harness. Consumed by [#13](https://github.com/bedaHovorka/OpenCybele1/issues/13)
> (`OpenCybeleLauncher`), [#21](https://github.com/bedaHovorka/OpenCybele1/issues/21) (the
> normalizer), [#23](https://github.com/bedaHovorka/OpenCybele1/issues/23) (scenario coverage) and
> [#24](https://github.com/bedaHovorka/OpenCybele1/issues/24) (recording the goldens), and later by
> `JadeLauncher` (#36) and `JasonLauncher` (#43).
>
> **What exists today is the skeleton.** The SPI, the runner, the spec format, the golden handling
> and the contract levels are complete and exercised end to end against a stub implementation. The
> normalizer is a placeholder by design (#21), and no adapter for a real implementation exists yet
> (#13).

## Where things live

```
parity-tests/
  scenarios/*.yaml    scenario specs, as data
  golden/*.txt        recorded traces
src/characterizationIT/java/cz/vutbr/fit/ags/parity/
  spec/               the scenario format and its parser
  spi/                LauncherAdapter, LaunchSpec, RunDisposition, TraceNormalizer
  run/                ScenarioRunner, ErrorScanner, the failure types
  golden/             GoldenStore, TraceComparator
  normalize/          the placeholder normalizers (#21 replaces these)
  stub/               a stand-in implementation, so the SPI can be driven with no application present
  it/                 the JUnit entry points
```

`parity-tests/` is addressed through `ParityLayout` and relocatable with `-Dparity.root=…`, so the
day #11's sharing mechanism settles — included build, published test artifact, whatever — no
scenario, golden or adapter changes.

```bash
./gradlew characterizationIT                      # compare against the goldens
./gradlew characterizationIT -Dgolden.record=true # record them
./gradlew build                                   # compiles the harness, does not run it
```

`characterizationIT` is its own lane (TESTING.md §6) and is deliberately **not** wired into
`check`: it will later need a built implementation to point at
(`-Popencybele.dist=../wt/opencybele-ref/build/install/opencybele`, #13).

## The seam: an adapter builds a command line, nothing more

`jade-develop` carries no Cybele dependency and never will. The harness therefore has **no
compile-time link to any implementation**: `LauncherAdapter` returns a `LaunchSpec` — argv,
environment, working directory — and `ScenarioRunner` starts a child JVM with it. The reference
implementation lives on another branch and is reached by path. Phase 2 adds a third adapter with
nothing else moving.

That invariant is asserted, not just stated: `HarnessSelfCheckIT` walks the harness source and
fails if any file imports `cz.vutbr.fit.ags.xhovor07`.

An adapter supplies four things, three of them with a working default:

| | |
|---|---|
| `launch(spec, scratch)` | the command line. The only required method. |
| `classifyExit(int)` | exit status → `RunDisposition`. Defaults to the measured table below. |
| `diagnosticPrefixes()` | which captured lines are diagnostics rather than trace. |
| `normalizer()` | what is recorded. Defaults to "drop the diagnostics"; #21 replaces it. |

## The scenario format

One YAML file per scenario in `parity-tests/scenarios/`, whose name stem must equal the `id` it
declares. **Unknown keys are rejected**, at parse time, with the list of keys that were allowed —
the same rule the simulation applies to its own `sim.*` keys, and for the same reason: a spec that
looks fully specified while quietly running on a default is how a golden gets recorded under a
configuration nobody chose.

```yaml
id: smoke-stub                      # required; must match the file name
description: >                      # optional, free text
  Bounded 12-train run at a pinned seed.

contract: strict                    # required: strict | causal | summary
golden: smoke-stub.txt              # optional; defaults to "<id>.txt"

launcher:                           # everything here is implementation-NEUTRAL
  config: scenarios/short-bounded.properties   # optional, resolved by the adapter
  properties:                       # config keys; on a JVM target these become -D flags
    sim.random.masterSeed: "20080415"
    sim.stop.maxClockMs: "115000"
    sim.stop.wallClockMs: "60000"
    sim.stop.stallMs: "10000"
    sim.headless: "true"
  env:                              # optional environment additions
    OPENCYBELE_OPTS: ""
  args: []                          # optional program arguments

run:
  harnessTimeoutMs: 120000          # hard backstop; killing the child is never a pass
  expect: bound-reached             # bound-reached | startup-error — and nothing else

liveness:                           # at least one rule is MANDATORY
  - pattern: '^vl\d+ started$'
    atLeast: 40
  - pattern: '^(vl\d+) in st[A-H] at \d+$'
    distinctGroup: 1                # count distinct group(1) values, not matches
    atLeast: 40
    atMost: 80                      # optional

entity:                             # required unless contract is strict
  pattern: '^(vl\d+)\b'
  group: 1                          # default 1
  tolerance:                        # honoured under causal and summary; rejected under strict
    missing: 0                      # ids in the golden that this run did not produce
    extra: 0                        # ids this run produced that the golden does not have

summary:                            # only under contract: summary; rejected elsewhere
  - label: departures
    pattern: '^vl\d+ started$'
    tolerance: 3

allowErrorLines:                    # optional error-scan exemptions
  - '^Exception in thread "main" java\.lang\.IllegalArgumentException: sim\.'
```

The live example is [`parity-tests/scenarios/smoke-stub.yaml`](../parity-tests/scenarios/smoke-stub.yaml).

Replaying a scenario against another implementation changes **only the adapter**. Nothing in the
format names a framework; `launcher.properties` are opaque key/value pairs the harness never
interprets, and their declaration order is preserved so the command line is byte-stable run to run.

### Why YAML, and which parser

SnakeYAML 2.4, loaded through `SafeConstructor` with duplicate keys rejected, on the
`characterizationIT` configuration only.

* It is **one jar with no transitive dependencies** — the branch stays as clean as it would be with
  a hand-rolled parser, and the harness' whole dependency set is JUnit 5 plus this.
* `SafeConstructor` restricts loading to plain maps, lists and scalars, so none of YAML's
  arbitrary-object machinery is reachable.
* The alternative, a restricted subset parsed by hand, trades a 340 KB test-scope jar for a parser
  that would need its own test suite before anyone should trust it. **Silently mis-parsing a
  scenario is the exact failure class this project keeps getting burned by** — a positional
  `-Dsim.random.masterSeed` that is ignored, a `default.properties` that omits the seed it claims
  to document. A parser that quietly reads `atLeast: 40` as a string would be one more of those.
* Strictness is added on top rather than taken from the parser: every key is checked against the
  schema, cross-field rules are enforced (an entity rule is required unless the contract is strict;
  summary rules are refused under any other contract; a tolerance is refused under strict), so a
  malformed scenario fails in milliseconds instead of after a two-minute run.

## Contract levels

A contract level says **how strongly this scenario's golden is compared**. It exists because of a
residual nondeterminism that no normalizer can remove: above a configuration-dependent load ceiling
the START race (INVENTORY DEF-02, the author's own `//BUG ne vzdy se doruci` at `Planning.java:126`)
drops trains, so the *set* of trains that departs varies run to run. Measured at one configuration:
30 s gives 54 departures reproducing exactly across three runs, 45 s gives 80 whose id sets differ
by 2, 10 and 12 ids. A lost train is **absent**, not reordered, and no normalisation invents a line
the run never printed.

| Level | What must match |
|---|---|
| `strict` | the normalized trace, line for line |
| `causal` | for every entity id present in **both** golden and run, that entity's ordered projection of the trace; the entity sets themselves may differ by at most `entity.tolerance` |
| `summary` | the number of distinct entity ids and one count per `summary` rule, each within its own tolerance |

Two properties are deliberate.

**The recorded artefact is identical at every level.** Recording always writes the full normalized
trace; the level governs comparison only. A scenario can therefore be tightened or relaxed later
without re-recording — which matters, because `Phase1.md` L7 forbids re-recording a golden for
anything but a defect in the harness itself.

**A tolerance is a measurement, not a knob.** `entity.tolerance: {missing: 2}` is a claim that this
scenario was measured to drift by no more than two ids. The cheaper answer is almost always a
shorter or less dense scenario: per #23, any scenario proposed for `strict` should be run three
times at a fixed seed with its entity-id sets diffed, and one that fails gets a **weaker contract
level**, never a re-recorded golden.

`causal` is also the level that survives the two interleaving residuals #21 has to handle. When two
entities act in the same simulated millisecond their `println`s race — three orderings measured
across six runs at one seed — so comparison has to be possible per entity id rather than per line
position. Lines that carry no entity id form one bucket compared in order among themselves, so a
startup block still has to match; it just does not have to interleave the way it did on the day the
golden was recorded.

## How a run is judged — and why in this order

`ScenarioRunner` runs six steps. The order is the design, not an implementation detail.

1. **Suite latch.** If an earlier scenario hit a suite-fatal condition, nothing is launched.
2. **Launch and capture** the merged stdout+stderr stream (`redirectErrorStream(true)`, per
   TESTING.md §3.2), bounded by `run.harnessTimeoutMs`. A child the harness had to kill is
   `HARNESS_TIMEOUT` and can never pass.
3. **Error scan, on the raw stream, before any normalization.**
4. **Exit classification.**
5. **Liveness**, also on the raw stream.
6. **Normalize**, then record or compare at the declared contract level.

### 3 — judge a run by its output, never by its exit status

A throwable inside a Cybele handler is wrapped in an `InvocationTargetException`, swallowed,
printed to stderr, and **does not change the exit status**. No throwable location was found that
does — including an uncaught one in `Main.main`, after which the simulation kept generating trains
(`docs/assertion-triage.md`, Result 3). A harness that judged a run by `waitFor()` would pass every
one of those.

So the runner greps the captured stream for `AssertionError` and for `Exception in thread "`.
Two consequences are worth stating in the code and are:

* **Occurrence count is not failure count.** `AssertionError` appears twice per firing assertion —
  the wrapper message and the cause line. The scanner reports hits and never converts them to a
  number of failures.
* **The scan runs before the normalizer.** With the streams merged, a firing assertion's stack
  trace lands *inside* the captured trace. Normalizing first would either scrub the evidence into
  invisibility or bake it into a golden as expected output. Fail fast, then normalize.

A scenario that deliberately pins a failure (say, configuration rejection) exempts its own
signature with `allowErrorLines`.

### 4 — "reached the bound" and "gave up" are different outcomes

The exit codes that do exist and are meaningful, from `docs/headless-and-stop.md`:

| Code | Disposition | Meaning |
|---|---|---|
| 0 | `BOUND_REACHED` | a declared stop bound was reached |
| 1 | `STARTUP_ERROR` | config/startup error, before the kernel |
| 3 | `WALL_CLOCK_TIMEOUT` | the safety net fired — **the run did not finish** |
| 4 | `STALL` | the stall detector fired — generation died |
| 5 | `CLOCK_COMMAND_DEAD` | the clock stopped answering its command channel |
| — | `HARNESS_TIMEOUT` | the harness killed the child |

Only `bound-reached` and `startup-error` can be *declared* by a scenario; `ExpectedOutcome` rejects
the others at parse time, because a timeout must never look like a pass.

**Exit 5 is fatal to the suite, not to one scenario.** A run whose clock registration was lost keeps
its clock advancing, keeps generating trains and exits green while every pause, resume and
`setTimer` is a no-op — a golden recorded against it would be wrong with no symptom — and there is
no reason to think the next scenario in the same environment fares better. The first occurrence
latches: `ScenarioRunner` refuses to launch anything afterwards and says why. JUnit has no portable
"abort the remaining tests", and the harness must be drivable from Jason's runner too, so latching
plus a loud failure is the mechanism that works everywhere.

### 5 — liveness, because a dead simulation is invisible to both checks above

There is a third failure mode that neither the exit status nor the error scan sees: a throwable in
`Generator.generateTrain` **before its timer re-arms** stops train generation permanently while the
process keeps its clock, keeps painting, reaches its simulated-time bound and exits 0 — with
nothing in the stream to grep for. Only a floor on how much the run produced catches it, and the
harness has to ask. That is why `liveness` is mandatory rather than optional: a scenario that
asserts nothing about how much it did cannot tell a healthy run from a dead one.

The child's own stall detector (exit 4) is the same hazard caught from the inside; these are two
independent halves and both are cheap.

Liveness is evaluated on the **raw** lines rather than the normalized ones, so a rule authored today
does not have to be re-authored when #21 lands.

### 6 — recording is last, on purpose

`-Dgolden.record=true` (TESTING.md §3.2) writes the golden instead of comparing. Because recording
is the last step, a run can only be frozen into the baseline after it has passed the error scan, the
exit classification and the liveness check. A broken run is not recordable.

## Diagnostics versus trace

`redirectErrorStream(true)` means stderr is part of the captured stream, and the implementations
print per-run-varying diagnostics there: a resolved-configuration banner, a randomness manifest,
the timer-service and clock round-trip timings, and a stop banner carrying wall-clock numbers. None
of that can go into a golden.

The discriminator is a **line prefix**, declared by the adapter, because the diagnostics arrive as
multi-line banners whose bodies carry no other marker. The default covers the OpenCybele baseline's
three shapes — `--- ` rules, `!!! ` warnings and failures, and two-space-indented banner bodies —
and trace lines are known never to start with any of them. An implementation that adds a new
diagnostic shape declares it in its adapter rather than teaching the normalizer about it.

## Deliberately left to other issues

* **#21 — the normalizer.** What ships here is `DiagnosticFilter`, which drops the declared
  diagnostic prefixes and nothing else. Timestamps, the startup block's nondeterministic emission
  order (`Cybele.createAgent` is asynchronous — six different orders measured in six runs, so the
  block must be sorted by agent name and there is no tick boundary to sort within) and
  equal-millisecond line interleaving are all untouched. The `TraceNormalizer` seam and its
  position in the pipeline are what this issue fixes; the projection itself is #21's.
* **#13 — `OpenCybeleLauncher`.** No adapter for a real implementation exists here. #13 is blocked
  by this issue, so requiring its smoke test here would be circular; the stub adapter proves the SPI
  instead, and differs from a real one only in what it points at.
* **#23 / #24 — scenarios and goldens.** `smoke-stub` is the only scenario, and its golden is a
  stub's output. Real scenarios are recorded against the OpenCybele branch, and every one proposed
  for `strict` should first be run three times at a fixed seed with its entity-id sets diffed.
* **#11 — how the harness is shared.** Everything here is addressed through `ParityLayout` so the
  eventual mechanism costs no edits to scenarios, goldens or adapters.
