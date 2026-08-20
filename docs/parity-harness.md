# The parity harness — scenario format, contract levels, and how a run is judged

> Produced for [#12](https://github.com/bedaHovorka/OpenCybele1/issues/12) (*Parity harness
> skeleton*), on `jade-develop`, which is where [#11](https://github.com/bedaHovorka/OpenCybele1/issues/11)
> placed the harness. Consumed by [#13](https://github.com/bedaHovorka/OpenCybele1/issues/13)
> (`OpenCybeleLauncher`), [#21](https://github.com/bedaHovorka/OpenCybele1/issues/21) (the
> normalizer), [#23](https://github.com/bedaHovorka/OpenCybele1/issues/23) (scenario coverage) and
> [#24](https://github.com/bedaHovorka/OpenCybele1/issues/24) (recording the goldens), and later by
> `JadeLauncher` (#36) and `JasonLauncher` (#43).
>
> **What exists today is the skeleton, adapter 1, and the normalizer.** The SPI, the runner, the
> spec format, the golden handling and the contract levels are complete and exercised end to end
> against a stub implementation and — since #13 — against the real OpenCybele application in a child
> JVM. **#21 has landed**: `CanonicalTraceNormalizer` replaces the placeholder, and
> `opencybele-strict` is a real scenario compared at `strict`. Its rules, its measurements and the
> zero-flake gate are [`trace-normalizer.md`](trace-normalizer.md); what is written here about the
> normalizer is only where it sits in the pipeline.

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
  normalize/          DiagnosticFilter (per adapter) + CanonicalTraceNormalizer (#21)
  stub/               a stand-in implementation, so the SPI can be driven with no application present
  opencybele/         adapter 1 (#13): the real application, in a child JVM, reached by path
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
| `normalizer()` | what is recorded, and what the golden is read through. Defaults to `DiagnosticFilter(diagnosticPrefixes()).andThen(new CanonicalTraceNormalizer())`. |

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
    # NOT a place to blank this out. A Gradle start script routes everything in $@ to the
    # PROGRAM's arguments, so `opencybele -Dsim.foo=bar` sets no system property and warns about
    # nothing. #13's adapter must render launcher.properties into OPENCYBELE_OPTS, not into argv.
    OPENCYBELE_OPTS: "-Dsim.headless=true -Dsim.random.masterSeed=20080415"
  args: []                          # optional program arguments

run:
  harnessTimeoutMs: 120000          # hard backstop; killing the child is never a pass
  expect: bound-reached             # bound-reached | startup-error — and nothing else

liveness:                           # at least one rule is MANDATORY
  - pattern: '^vl\d+ started$'
    atLeast: 40                     # must be >= 1; a floor of 0 asserts nothing
  - pattern: '^(vl\d+) in st[A-H] at \d+$'
    distinctGroup: 1                # count distinct group(1) values, not matches
    atLeast: 40
    atMost: 80                      # optional

entity:                             # required unless contract is strict
  pattern: '^(vl\d+)\b'
  group: 1                          # default 1
  tolerance:                        # honoured under causal and summary; rejected under strict,
                                    # even when every value in it is zero
    missing: 0                      # ids in the golden that this run did not produce
    extra: 0                        # ids this run produced that the golden does not have

summary:                            # only under contract: summary; rejected elsewhere
  - label: departures
    pattern: '^vl\d+ started$'
    tolerance: 3

allowErrorLines:                    # optional error-scan exemptions; must be NARROW, and every
  - '^Exception in thread "main" java\.lang\.IllegalArgumentException: sim\.'   # use is announced
```

Three rules exist because each of them was a hole someone could fall into. A `liveness.atLeast`
of `0` satisfies the mandate while asserting nothing, so it is rejected. An `entity.tolerance`
block under `strict` reads as a licence it is not, so it is rejected even when its values are all
zero. And an `allowErrorLines` pattern is a hole in the error scan: a bare `.` or `.*` silences
every future throwable, and such a pattern was reproduced swallowing a real `AssertionError` and
recording a golden with it. Patterns are probed against ordinary output at parse time and rejected
if they match it, and every exemption a run actually uses is printed — count and lines — whether the
run passes or fails.

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

`causal` is also the level that survives the two interleaving residuals #21 handled. When two
entities act in the same simulated millisecond their `println`s race — three orderings measured
across six runs at one seed — so comparison has to be possible per entity id rather than per line
position. Lines that carry no entity id form one bucket compared in order among themselves **and
excluded from the tolerance budget**, so a startup block still has to match; it just does not have
to interleave the way it did on the day the golden was recorded. That exclusion is not a detail:
while the bucket counted as an entity, a replay that dropped the *entire* startup block passed at
`tolerance.missing: 1`, because losing every unattributed line cost exactly one missing "entity" —
and all of #21's startup-block work lands in that bucket.

Since #21, the interleaving residuals are gone from *both* levels — the normalizer canonicalises
order before either comparison sees it — so the choice between `strict` and `causal` is now purely
about whether the scenario's entity *set* is stable. Where it is, prefer `strict`: `causal`'s
tolerance budget applies only to which ids exist and buys nothing when none go missing.

## How a run is judged — and why in this order

`ScenarioRunner` runs six steps. The order is the design, not an implementation detail.

1. **Suite latch.** If an earlier scenario hit a suite-fatal condition, nothing is launched.
2. **Launch and capture** the merged stdout+stderr stream (`redirectErrorStream(true)`, per
   TESTING.md §3.2), bounded by `run.harnessTimeoutMs`. A child the harness had to kill is
   `HARNESS_TIMEOUT` and can never pass; so is a capture that never reached EOF.
3. **Exit classification, and the suite latch.**
4. **Error scan, on the raw stream, before any normalization**, followed by the
   expected-disposition check.
5. **Liveness**, also on the raw stream.
6. **Normalize** — refusing an empty result — then record or compare at the declared contract level.

Steps 3 and 4 are in that order for a reason that was got wrong first time round. The latch used to
sit *after* the scan, so a run that exited 5 **and** printed a throwable — the overwhelmingly likely
shape, since a dead clock channel is usually preceded by a swallowed throwable — failed as an
ordinary scenario and never latched. Every later scenario then launched into the same poisoned
environment and, in record mode, froze goldens against a dead clock. Classifying first costs
nothing: the failure report still leads with the throwable.

### 3 — "reached the bound" and "gave up" are different outcomes

The exit codes that do exist and are meaningful, from [`headless-and-stop.md`](https://github.com/bedaHovorka/OpenCybele1/blob/opencybele-baseline/docs/headless-and-stop.md) (on `opencybele-baseline`):

| Code | Disposition | Meaning |
|---|---|---|
| 0 | `BOUND_REACHED` | a declared stop bound was reached |
| 1 | `STARTUP_ERROR` | config/startup error, before the kernel |
| 2 | `WINDOW_CLOSED_EARLY` | the GUI window was closed while a bound was armed and unreached — **the run did not finish** |
| 3 | `WALL_CLOCK_TIMEOUT` | the safety net fired — **the run did not finish** |
| 4 | `STALL` | the stall detector fired — generation died |
| 5 | `CLOCK_COMMAND_DEAD` | the clock stopped answering its command channel |
| 255 | `AGENT_CONSTRUCTION_THROWABLE` | a throwable escaped an agent **constructor**: the JVM dies in ~0.26 s, stack on stderr, no stop banner, no code the application set |
| — | `HARNESS_TIMEOUT` | the harness killed the child |

Code 255 is worth reading twice by anyone tempted to state a general rule about the kernel. The
handler path swallows; the **construction** path does not. "Cybele swallows every throwable" is
false, and the harness classifies the two paths separately rather than assuming either.

Only `bound-reached` and `startup-error` can be *declared* by a scenario; `ExpectedOutcome` rejects
the others at parse time, because a timeout must never look like a pass.

**Exit 5 is fatal to the suite, not to one scenario.** A run whose clock registration was lost keeps
its clock advancing, keeps generating trains and exits green while every pause, resume and
`setTimer` is a no-op — a golden recorded against it would be wrong with no symptom — and there is
no reason to think the next scenario in the same environment fares better. The first occurrence
latches: `ScenarioRunner` refuses to launch anything afterwards and says why. JUnit has no portable
"abort the remaining tests", and the harness must be drivable from Jason's runner too, so latching
plus a loud failure is the mechanism that works everywhere.

### 4 — judge a run by its output, never by its exit status

A throwable inside a Cybele handler is handed to the kernel's exception handler, printed to stderr,
and **does not change the exit status**. No throwable location was found that does — including an
uncaught one in `Main.main`, after which the simulation kept generating trains
([`assertion-triage.md`](https://github.com/bedaHovorka/OpenCybele1/blob/opencybele-baseline/docs/assertion-triage.md) on `opencybele-baseline`, Result 3). A harness that
judged a run by `waitFor()` would pass every one of those.

#### The signature list, and how an earlier version of this section was wrong

This section previously said the runner greps for `AssertionError` and `Exception in thread "`, and
that **"two signatures cover it"**. They do not. `assertion-triage.md`, the cited evidence, only ever
measured *planted `AssertionError`s*; its recommendation was correct for assertions and was then
generalised into a claim about all throwables. For a **non-assertion** throwable swallowed in a
handler — an NPE, an `IllegalStateException`, an `IndexOutOfBoundsException`, i.e. the shapes a real
port bug actually produces — the captured stream contains **neither** string. Such a run was
reproduced passing the scan and being **recorded as a golden**, with the NPE and its stack frames
baked in as expected output.

The list is now read out of the vendored kernel rather than inferred. `javap -c` on
`com/iai/cybele/exception/IAIExceptionHandler` shows `print(header, throwable)` writing
`"\n***" + header + " ->"` to `System.err` before anything else, then — by stack-trace option —
`toString()` or `printStackTrace()`, plus `"Originated from --- "`,
`"Exception thrown by target ---"`, `"Unsupported stack trace option"` and `"Thrown in Thread '"`.
The same on `com/iai/cybele/thmgmt/IAIAgentThread` shows every reflective-dispatch failure routed
through `handleException("Thread Mgmt Exception", …)` carrying one of
`"InvocationTargetException occured in"`, `"ClassCastException occured in"`,
`"General Exception occured in"` or `"Failed to invoke the method"` — the kernel's own spelling of
"occured" included.

| Group | Signatures |
|---|---|
| JVM | `AssertionError`, `Exception in thread "`, `OutOfMemoryError`, `StackOverflowError`, `^# A fatal error has been detected`, `java.lang.reflect.InvocationTargetException` |
| `IAIExceptionHandler.print` | `^\*\*\*.* ->`, `Originated from --- `, `Exception thrown by target ---`, `Thrown in Thread '`, `Unsupported stack trace option` |
| `IAIAgentThread` dispatch | `Thread Mgmt Exception`, `Failed to invoke the method`, `(InvocationTargetException\|ClassCastException\|General Exception) occured in` |

The banner line is emitted for **every** swallowed throwable regardless of its type or of the
configured trace option, which is what makes it load-bearing rather than the type names. A
self-check drives a stub that emits exactly the kernel's byte sequence around an NPE and asserts
both that the scan catches it and that those bytes contain neither of the two original signatures.

Two further consequences:

* **Occurrence count is not failure count.** `AssertionError` appears twice per firing assertion —
  the wrapper message and the cause line — and the kernel path adds a banner and a stack trace on
  top. The scanner reports hits and never converts them to a number of failures.
* **The scan runs before the normalizer.** With the streams merged, a firing assertion's stack
  trace lands *inside* the captured trace. Normalizing first would either scrub the evidence into
  invisibility or bake it into a golden as expected output. Fail fast, then normalize.

A scenario that deliberately pins a failure (say, configuration rejection) exempts its own signature
with `allowErrorLines` — narrowly, and never silently: see the rules under the format above.

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
did not have to be re-authored when #21 landed — the liveness rules in `opencybele-smoke` still
carry the numeric `tick` field that the normalizer now projects away, and still match.

#### Which lines a pattern sees — an asymmetry worth knowing

`liveness` and `allowErrorLines` match **raw** captured lines. `entity` and `summary` match
**normalized** ones. That is deliberate — the first pair has to work before the normalizer runs,
the second pair describes what a golden contains — but it means a pattern copied from one to the
other silently stopped matching once #21 projected the `at <t>` field away — it did, in this
repository, and the fix and its guard are under "`opencybele-smoke`" below. The harness fails a
`causal` or `summary` run whose `entity.pattern` matches none of the normalized lines, rather than
letting every line fall into the unattributed bucket and comparing nothing per entity.

### 6 — recording is last, on purpose

`-Dgolden.record=true` (TESTING.md §3.2) writes the golden instead of comparing. Because recording
is the last step, a run can only be frozen into the baseline after it has passed the error scan, the
exit classification and the liveness check. A broken run is not recordable, and **each** of those
checks has its own self-check asserting that no golden file appears — the argument rests on the
order, so the order cannot be held in place by a comment alone. (It was: a mutation that moved the
liveness check after `record(…)` left the whole suite green, because the test only asserted on the
failure message.)

An **empty** normalized trace is refused on both sides, recording and comparing. A 0-byte golden
matches an empty run and nothing else can ever fail against it — the "lock that cannot fail" shape
this project has already rejected twice — and it is reachable by accident: the default diagnostic
prefixes include two spaces, so a target whose lines happen to be indented normalizes to nothing.
The refusal names the normalizer that ate the trace.

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

That is not hypothetical. The baseline's resolved-configuration banner carries **none** of the three
shapes — it is ~20 unindented `sim.<key> = <value>` lines — so `OpenCybeleLauncher` declares a
fourth prefix for it rather than the application being changed to suit a filter it cannot see. See
"Adapter 1: `OpenCybeleLauncher`" below.

## Adapter 1: `OpenCybeleLauncher`

Added by [#13](https://github.com/bedaHovorka/OpenCybele1/issues/13). It is the first adapter that
drives a real implementation, and it drives it **by path**: the application lives on
`opencybele-baseline` and the adapter names exactly one application symbol —
`"cz.vutbr.fit.ags.xhovor07.Main"` — as a string.

**A correction to how that separation has been described.** "This branch carries no Cybele, no
application source and no vendor jar" is false and has been repeated: `jade-develop` still holds
the 19 application sources under `src/main/java/cz/vutbr/fit/ags/xhovor07/` and still declares
`com.iai:cybele-api`/`cybele-impl`, so `./gradlew build` here needs those jars in `~/.m2`. What is
true — and what is *enforced* — is narrower and is the half that matters: the **harness source
set** has no link to any implementation. `characterizationIT` does not extend `main`'s
configurations, nothing named `com.iai` resolves on `characterizationITRuntimeClasspath`, and
`HarnessSelfCheckIT` fails the build on an application import. Claim that, not the wider version.

```bash
cd ../wt/opencybele-ref && ./gradlew installDist
./gradlew characterizationIT -Popencybele.dist=/abs/path/wt/opencybele-ref/build/install/opencybele
./gradlew characterizationIT -Popencybele.dist=… -Dgolden.record=true   # record instead
```

Two directories matter and they are not the same one. The **dist** (`build/install/opencybele`)
supplies the jars, `opencybele.jar` plus the two vendor jars `installDist` copied out of `~/.m2`.
The **application home** — the project directory above it — supplies `cybelle/*.prop` and the
`scenarios/*.properties` files, neither of which is part of a dist; it is found by walking up from
the dist until a `cybelle/cybele.prop` appears, and can be pinned with `-Popencybele.home=…`.
Without `-Popencybele.dist` the end-to-end test **skips** rather than fails: this branch must stay
buildable and testable with no implementation checked out anywhere.

### The command line, and why it is `java` and not `bin/opencybele`

```
<java.home>/bin/java -ea --patch-module java.base=cybelle -cp <dist/lib/*.jar, sorted> \
    [-Dsim.config=<absolute>] -D<k>=<v>… cz.vutbr.fit.ags.xhovor07.Main <args>
```

Nothing is inherited. The classpath is exactly the dist's jars. `--patch-module java.base=cybelle`
is required — Cybele reads `cybele.prop` through `Properties.class.getResourceAsStream`, which
under JPMS no longer falls back to the classpath — and it resolves **relative to the child's
working directory**, so the adapter stages `cybelle/*.prop` into the harness' per-run scratch
directory and runs there. Only `*.prop` is staged, because `--patch-module` folds the *whole*
directory into `java.base` and that checkout's `cybelle/` has historically also held the untracked
vendor jars. A `launcher.config` is resolved against the application home and passed **absolute**,
because the child's working directory is the scratch dir rather than the checkout.

The start script is bypassed on purpose. A Gradle start script routes everything in `$@` to the
*program's* arguments, so a positional `-Dsim.random.masterSeed=20080415` sets no system property
and warns about nothing — re-measured while writing this: the positional form drew
`4696735245841119171` while the same value in `OPENCYBELE_OPTS` pinned it. `OPENCYBELE_OPTS` works;
assembling the `java` line directly is better still, because every element is explicit in the
failure report, and because a launcher that forks instead of `exec`ing leaves the inherited stdout
open after the child exits — which `ScenarioRunner` refuses as a truncated capture.

**That the properties arrived is measured, not assumed — and the check is total.**
`OpenCybeleSmokeIT` iterates `spec.launcher().properties()` and reads **every declared key** back
out of the child's own resolved-configuration banner, matched whole-line and anchored.

Both halves of that sentence were earned. An earlier revision checked six hard-coded keys out of
ten, and dropping the unchecked `sim.station.voteWindowMs` alone moves the trace by exactly 2 lines
per affected family — inside every tolerance, with the distinct-entity count and every train-level
count unchanged, so it passes in both directions and the golden would freeze a configuration the
scenario says it is not using. And `startsWith` accepted `sim.clock.pace = 80` as proof of `= 8`.
Driving the loop from the spec also keeps the claim true the next time the scenario gains a knob.

The all-or-nothing argument still holds as a second line of defence — with no `sim.stop.*` an
ignored command line produces a run with no bound, which the harness kills — but it only ever
covered a *wholesale* drop.

`classifyExit` is deliberately **not** overridden — the default table in `LauncherAdapter` is the
one measured on this very application — and `OpenCybeleLauncherIT` asserts all seven codes through
the adapter rather than trusting that, including the two that postdate #12: `2`
(`WINDOW_CLOSED_EARLY`, a run that did *not* finish) and `255`
(`AGENT_CONSTRUCTION_THROWABLE`, a status the application never sets). That test asserts the
**mapping only**. That the application actually emits each status was measured in
[`headless-and-stop.md`](https://github.com/bedaHovorka/OpenCybele1/blob/opencybele-baseline/docs/headless-and-stop.md)
by observing `$?`, and is not re-verified here — a green IT is not end-to-end proof that exit 4
ever happens.

### The configuration banner: declared, not indented

`ScenarioConfig.describe()` writes ~20 `sim.<key> = <value>` lines to stderr with **no prefix and no
indentation**, and `redirectErrorStream(true)` makes stderr part of the trace. None of the default
prefixes matched them, so every one of those lines would have been recorded as behaviour — and with
`sim.random.masterSeed` unpinned the banner republishes a *drawn* number, so such a golden could
never match. Worse, adding an unrelated `sim.*` key (#20 added two) would change every golden in the
suite for a reason that has nothing to do with behaviour. Configuration is a manifest recorded
*alongside* a golden (#24), never inside it.

Of the three candidate fixes, #13 took the third: `OpenCybeleLauncher.diagnosticPrefixes()` declares
`"sim."` in addition to the defaults. Indenting or prefixing the banner in the application would
work too, but #12 chose deliberately that a **new diagnostic shape is declared by the adapter that
knows about it**, so the normalizer stays implementation-agnostic and the application stays unaware
that a harness exists; indenting would additionally couple the application's output formatting to a
filter rule it cannot see, and the next unindented line would reopen the hole silently.

The prefix cannot collide with trace: field 1 of a canonical line is a station, track or train name
(`stA`, `tr1`, `vl3` — never `Main`), the application's own two `println` families both begin
`vl<n>`, and every key `ScenarioConfig` accepts begins `sim.`. Measured on a captured run: 0 of 623
normalized lines start with `sim.`. #15's randomness manifest was **confirmed rather than assumed** —
its rules start `--- `/`---` and its body lines with two spaces, so the default prefixes already
remove all eleven of them.

### `opencybele-smoke`, and what it can honestly claim

`summary`, not `strict` or `causal` — and **since #21 landed the reason is no longer the
normalizer.** The projection is sufficient: over 52 headless captures there is not one pair of runs
with the same content in a different order, where before it 13 captures gave 13 distinct traces.
What blocks this scenario is one number in its own file. At its seed the last event burst *begins*
at simulated 25072, 72 ms past the `sim.stop.maxClockMs = 25000` bound, so it is emitted inside the
shutdown window: 26 of 38 runs printed all 623 lines, the other 12 printed 611, 609 or 607. Missing
lines are not projectable, and `causal` is no easier than `strict` here — it compares each entity's
projection exactly and the unattributed bucket exactly and with no tolerance, and the truncated
burst is full of unattributed lines. Both fail at the same 25 of 38.

`opencybele-strict` is the same configuration with the bound moved to 24000, inside the 1952 ms gap
between bursts: 14 of 14 captures byte-identical, the gate 10 for 10, contract `strict`. #23 should
fold the two. See [`trace-normalizer.md`](trace-normalizer.md) §5.

**This golden was not re-recorded.** `ScenarioRunner` normalizes the golden with the same normalizer
before comparing, and `CanonicalTraceNormalizer` is idempotent, so a golden recorded under the
placeholder still matches — which is exactly what `Phase1.md` L7 requires.

**The level is not the rule set, and the rule set is what a replay is actually held to.** The first
version of this scenario declared six summary rules covering `PLAN_TRAIN`, `TRAIN_STATE`, `ENTER`,
`STATION_INFO` and the two `println`s — **154 of 623 lines**. Nothing touched `VOTE`,
`VOTE_REQUEST`, `VOTE_RESULT`, `ENTER_REPLY`, `LEAVE`, `TRAVEL_*`, `PATH_FIND*` or `START`.
Measured against the real application: deleting all 279 VOTE-family lines from the golden still
passed, and so did a golden cut down to 133 lines. Comparison is symmetric, so **a port that never
emitted a single `VOTE` line would have passed identically** — and the distributed election is the
system's core algorithm and precisely what #34 is expected to restructure.

There is now one rule per channel, plus the two `println`s and the kernel banner: **623 of 623
golden lines are inside a rule, none double-counted.** Two groups behave differently and the
tolerances say which: the election families and `PLAN_TRAIN`/`START` are identical across all 17
measured runs (93/93/93, 9, 5) because they complete long before the bound, while the movement and
state families lose exactly 2 lines under CPU saturation, every time, because the run is halted
promptly once the bound fires. Tolerances are 2 and 3 accordingly — the earlier 6 was slack nobody
had measured, and unmeasured slack is exactly what let a dropped property hide.

It remains a **count** contract: a rule proves a family exists at the right cardinality, not that
any line's payload is right. That is the honest ceiling of `summary`; `opencybele-strict` is where
content is pinned.

One trap #21 exposed and closed: **summary and entity patterns match NORMALIZED lines while
liveness patterns match RAW ones**, so a pattern copied between them can stop matching the moment a
field is projected away — `^vl\d+ in st[A-H] at \d+$` became `at <T>` and silently began counting
0 against 0, passing whatever the run did. `TraceComparator` now **fails** a summary rule that
matches nothing in the golden, rather than letting it read as green.

Two claims about this scenario were also overstated in the first revision and are now true rather
than softened. It declares **all twenty** `sim.*` keys, including the ten that sit at a current
application default — the topology, the station capacities and the road delays are exactly what a
port must replicate, and leaving them implicit means a change to a baseline default silently
invalidates this golden. Declaring them is behaviour-neutral: three runs with the ten added produce
byte-identical event counts to the run the golden was recorded from, so **no re-recording was
needed**.

One thing the scenario had to design around, and it is a **baseline defect rather than a harness
one**: `Cybele.terminate()` tears the comm service down while agent threads are still running, so a
train agent whose constructor is in flight at the stop instant reaches `Activity.openChannel` after
`commReceiver` has been nulled, and the kernel prints `*** Activity.init  ->` with an NPE *after*
the stop banner. The error scan is right to fail on that, so the scenario does not exempt it — it
places its bound inside a 6.1 s gap in the arrival sequence instead, ~480 ms of wall clock clear of
the last train creation. Measured: a bound of 30000 (0–40 simulated ms after an arrival) failed once
in the first full-suite run and 3 times in 8 runs under an 8-way CPU load; the shipped bound of
25000 gave 0 in 14 runs under the same load, 0 in 10 unloaded harness runs, and identical counts
throughout. **Moving that number is not a cosmetic edit.**

## Deliberately left to other issues

* **#21 — the normalizer. Landed.** `DiagnosticFilter` still drops the declared diagnostic
  prefixes, and `CanonicalTraceNormalizer` now runs after it: eight value projections, a
  startup-block sort by agent name, burst-local ordering, and the two application `println`s split
  into independent streams. It is also applied to the **golden** before comparison, which is why no
  golden was re-recorded. Rules, measurements, mutation results and the zero-flake gate are in
  [`trace-normalizer.md`](trace-normalizer.md).
* **#23 / #24 — scenarios and goldens.** There are two scenarios: `smoke-stub`, whose golden is a
  stub's output, and `opencybele-smoke`, which is a smoke test for adapter 1 rather than coverage
  of the application. Real coverage is recorded against the OpenCybele branch, and every scenario
  proposed for `strict` should first be run three times at a fixed seed with its entity-id sets
  diffed.
* **#11 — how the harness is shared.** Everything here is addressed through `ParityLayout` so the
  eventual mechanism costs no edits to scenarios, goldens or adapters.
