# Assertion triage — baseline `-ea` run on OpenCybele

> Produced for [#14](https://github.com/bedaHovorka/OpenCybele1/issues/14) (*Reproducible baseline*).
> Consumed by [#22](https://github.com/bedaHovorka/OpenCybele1/issues/22) (assertions-on/off decision for
> golden recording) and [#24](https://github.com/bedaHovorka/OpenCybele1/issues/24) (run manifest).
> **Result 3 below constrains [#13](https://github.com/bedaHovorka/OpenCybele1/issues/13) and
> [#21](https://github.com/bedaHovorka/OpenCybele1/issues/21) directly**: a scenario runner that checks the
> exit status, or captures only stdout, will report green through every failure mode described there.
> See also `docs/TESTING.md` §3.2.
>
> **Headline: no assertion fires** — across eight sampled, `timeout`-truncated runs on an
> unseeded RNG (see [Caveats](#what-this-does-and-does-not-decide); neither
> [#15](https://github.com/bedaHovorka/OpenCybele1/issues/15) nor
> [#17](https://github.com/bedaHovorka/OpenCybele1/issues/17) has landed, so this is
> sampled evidence, not proof). All 33 `assert` statements were checked with `-ea` on. 25 of them are exercised by a normal run — collectively ~20 000
> evaluations — and every one held. The remaining 8 are never reached. Two of
> those 8 are deliberate "this branch is impossible" markers, so not reaching
> them is the correct outcome.

## Method

Branch `phase1/14-reproducible-baseline`, JDK 21 (OpenJDK 21.0.11), Gradle 8.10.2 wrapper,
`-ea --patch-module java.base=cybelle`.

The simulation has no stop condition ([#17](https://github.com/bedaHovorka/OpenCybele1/issues/17)),
so every run is bounded with `timeout` and terminated mid-scenario. The RNG is
unseeded ([#15](https://github.com/bedaHovorka/OpenCybele1/issues/15)), so runs differ; hence several
runs rather than one.

Two questions had to be answered separately, because they fail in different ways:

1. **Does an assertion fire?** — observed from run output.
2. **Is the assertion even executed?** — *not* answerable from run output. "No
   `AssertionError` in the log" is worthless if the statement is dead code on
   this path. Answered with a throwaway instrumentation pass (below).

### Runs

| Run | Path | Duration | Trains started | `AssertionError` | Other throwables |
|---|---|---|---|---|---|
| control (no `-ea`) | `./gradlew run` | 150 s | 15 | n/a | 0 |
| 1 | `./gradlew run` | 320 s | 35 | **0** | 0 |
| 2 | `./gradlew run` | 400 s | ~37 | **0** | 0 |
| 3 | `./gradlew run` | ~300 s | ~40 | **0** | 0 |
| 4 | `docker compose up app` | 150 s | 17 | **0** | 0 |
| 5 | `docker compose up -d app` | ~180 s | ~20 | **0** | 0 |
| 6 | instrumented (below) | 330 s | 43 | **0** | 0 |
| 7 | `./gradlew run` (post-review rebuild) | 180 s | 15 | **0** | 0 |
| 8 | `docker compose up app` (post-review rebuild) | 90 s | 4 | **0** | 0 |

Runs 1–3 and 7 were captured with `./gradlew run`, which merges the child JVM's
stderr into the console stream; runs 4, 5 and 8 through `docker compose`, likewise.
The stdout/stderr split reported in Result 3 was measured separately, straight off
the `installDist` start script.

### Instrumentation pass (throwaway, not committed)

Every `assert EXPR;` was rewritten to `assert Diag.hit("<file>:<line>") && EXPR;`,
where `Diag.hit` counts and returns `true`. Because the injected call is folded
into the assert's own boolean expression:

- it only runs when `-ea` is on — exactly the condition under test;
- `hit()` is constantly `true`, so `true && EXPR` preserves the original truth value.
  This is **not** universally safe: `&&` binds *tighter* than `||` and `?:`, so
  `assert a || b` would regroup as `(hit() && a) || b`, and a top-level ternary would
  collide with the `assert cond : msg` form. It is sound here because all 33 sites were
  checked individually and **none contains a top-level `||` or `?:`** — verified per
  site, not assumed. Anyone reusing this recipe must re-check that, or parenthesise the
  original expression as `hit() && (EXPR)`;
- it is safe at `} else assert false;` ([`RailwayMainAgent.java:280`](../src/main/java/cz/vutbr/fit/ags/xhovor07/RailwayMainAgent.java#L280)),
  where inserting a *statement* before the assert would have silently changed control flow;
- it works with the `assert cond : msg;` form, since the rewrite touches only `cond`.

The transform found exactly 33 sites, correctly skipping the Javadoc false positive at
[`util/Util.java:49`](../src/main/java/cz/vutbr/fit/ags/xhovor07/util/Util.java#L49) — matching
the count in #14. A daemon thread dumped counters every 30 s. All of this was
reverted; only the numbers below survive.

## Result 1 — the three suspected assertions all hold

#14 flagged three as likely to fire. None did:

| Site | Assertion | Evaluated | Fired |
|---|---|---|---|
| [`Planning.java:96`](../src/main/java/cz/vutbr/fit/ags/xhovor07/Planning.java#L96) | `v.size() == path.size()` | 36 | **0** |
| [`Train.java:91`](../src/main/java/cz/vutbr/fit/ags/xhovor07/Train.java#L91) | `position.equals(to)` | 34 | **0** |
| [`RailwayMainAgent.java:147`](../src/main/java/cz/vutbr/fit/ags/xhovor07/RailwayMainAgent.java#L147) | `direction != null` | 21 | **0** |

Reading these:

- **`Planning.java:96`** — the voting protocol's completeness check: every member of
  the train's path replied before the `CountDownLatch` released. 36 complete
  voting rounds, no partial vote collection. The suspicion was that the
  `CountDownLatch` / `votes` multimap could desynchronise; it did not.
- **`Train.java:91`** — a train that ran out of `nextPosition` is standing at its
  declared destination. 34 arrivals against 35 starts (`Train.java:73`), the
  difference being trains still in transit when the run was cut off. **Trains do
  complete their journeys**, so this path is genuinely covered rather than
  vacuously passing.
- **`RailwayMainAgent.java:147`** — `Util.pathDirection` resolved a next hop for
  every `PATH_FIND` request. 21 requests, all resolvable. Consistent with
  `Util.java:85`'s own precondition also being evaluated 21 times and holding.

## Result 2 — full site inventory

Counts are from run 6 (330 s, 43 trains). Absolute values scale with run length;
what matters is zero vs non-zero, and that no site failed.

### Exercised and holding (25 sites)

| Site | Assertion | Evaluations |
|---|---|---|
| `util/Doubleton.java:38` | `state != null` | 9085 |
| `util/Doubleton.java:43` | `state != null` | 6398 |
| `RailwayCanvas.java:101` | `road != null` | 1430 |
| `RailwayMainAgent.java:181` | `message.length == 1` | 426 |
| `RailwayMainAgent.java:158` | `message.length == 1` | 398 |
| `VoteCollecting.java:53` | `scrutator.getTrainCountDowns().containsKey(train)` | 372 |
| `util/HashMapGraph.java:104` | `node != null` | 367 |
| `util/HashMapGraph.java:135` | `h != null` | 360 |
| `RailwayMainAgent.java:169` | `message.length == 1` | 328 |
| `RailwayCanvas.java:61` | `g instanceof Graphics2D` | 286 |
| `Train.java:95` | `position.startsWith("tr")` | 161 |
| `RoadAgent.java:114` | `traveledTrain != null` | 160 |
| `RoadAgent.java:151` | `state != State.FREE` | 160 |
| `RoadAgent.java:143` | `position.equals(rightStation)` | 77 |
| `Planning.java:96` | `v.size() == path.size()` | 36 |
| `Planning.java:124` | `clockTime >= departure` | 35 |
| `Train.java:73` | `station.equals(from)` | 35 |
| `Train.java:91` | `position.equals(to)` | 34 |
| `PathFinding.java:44` | `message.length == 2` | 21 |
| `RailwayMainAgent.java:143` | `message.length == 2` | 21 |
| `RailwayMainAgent.java:147` | `direction != null` | 21 |
| `util/Util.java:85` | `graph.nodeSet().contains(start) && … && !start.equals(target)` | 21 |
| `RailwayMainAgent.java:117` | `stationCapacities.containsKey(station)` | 8 |
| `RailwayMainAgent.java:124` | `roadDelays.containsKey(road)` | 7 |
| `RailwayMainAgent.java:126` | `allStationsWithRoad.size() == 2` | 7 |

`RailwayMainAgent.java:117/124/126` run 8 and 7 times — once per station and once
per track — because they are startup topology checks, not steady-state checks.

### Never reached (8 sites)

| Site | Assertion | Why |
|---|---|---|
| `RailwayMainAgent.java:280` | `} else assert false;` | **Intentional dead branch.** Reaching it *is* the bug. Correctly never reached. |
| `Station.java:108` | `assert false : e;` | **Intentional** — a swallowed-exception marker in a `catch`. No exception occurred. |
| `util/Util.java:42` | `!objects[i].getClass().isArray()` | inside `Util.toClass(Object[])`, reached only from `AbstractUnorientedGraph`'s reflective helper, itself unused here |
| `util/Util.java:56` | `clazz != null` | `Util.assertInstanceOf` never called |
| `util/Util.java:57` | `clazz.isInstance(obj)` | same |
| `util/AbstractUnorientedGraph.java:41` | `o instanceof Integer` | code path unused by `HashMapGraph` as driven here |
| `util/AbstractUnorientedGraph.java:60` | `cause instanceof RuntimeException` | error path, not triggered |
| `util/HashMapGraph.java:121` | `h != null` | overload unused on these paths |

These 8 are **not** evidence of correctness — they are simply uncovered. Two are
supposed to be uncovered; the other six mark a coverage gap that scenario
extension (1-PRE.3) may or may not choose to close. None of them blocks the
baseline.

## Result 3 — what actually happens when an assertion fires

Verified two ways: by decompiling the kernel (`javap -c` on `CybeleImpl.jar`), and by
planting synthetic failures at three different call sites and capturing **stdout and
stderr into separate files**.

### The catch is narrow, and it is not `Throwable`

`com.iai.cybele.thmgmt.IAIAgentThread.run` invokes each event handler reflectively.
Its exception table over the invoke region (bytecode 104–127, `Method.invoke` at 120) is:

| Caught type | Handler |
|---|---|
| `java.lang.IllegalArgumentException` | 130 |
| `java.lang.IllegalAccessException` | 193 |
| `java.lang.reflect.InvocationTargetException` | 256 |
| `java.lang.ClassCastException` | 325 |
| `java.lang.Exception` | 394 |

There is **no `catch (Throwable)`**. An `AssertionError` is an `Error`, not an
`Exception`, so it is not caught on its own merits — it survives only because
`Method.invoke` wraps whatever the handler threw in an `InvocationTargetException`,
and *that* is in the table. So the swallowing is a side effect of reflection, not a
deliberate policy.

The consequence: **"Cybele swallows any Throwable" is not a safe general rule.** An
`Error` raised outside the reflective-invoke region is not caught at all; it escapes
`run()` and kills that agent thread silently.

### It is printed to stderr, by a different class

The printer is `com.iai.cybele.exception.IAIExceptionHandler.print`, not
`IAIAgentThread`, and every branch of it writes to **`System.err`** (plus
`Throwable.printStackTrace()`, also stderr). Confirmed empirically — 4 synthetic
handler failures produced:

| Stream | `AssertionError` occurrences |
|---|---|
| stdout | **0** |
| stderr | **8** |

A runner doing `./gradlew run > out.log` and grepping `out.log` finds **nothing**.
[#21](https://github.com/bedaHovorka/OpenCybele1/issues/21) must capture stderr.

Note also that `IAIExceptionHandler` *does* call `System.exit()` on its higher
severity branches. The handler-failure path constructs a `CybeleWarning`, which takes
the non-exiting branch — which is why the process survives. Not every Cybele error
class is survivable.

### Each failure prints the assertion twice

Verbatim, one complete failure block (JDK 21):

```
*** Thread Mgmt Exception ->
cybele.exception.CybeleWarning: InvocationTargetException occured in entered of the class cz.vutbr.fit.ags.xhovor07.Train
	at com.iai.cybele.thmgmt.IAIAgentThread.run(IAIAgentThread.java:341)
	at java.base/java.lang.Thread.run(Thread.java:1583)
Originated from ---
java.lang.reflect.InvocationTargetException
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:118)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at com.iai.cybele.thmgmt.IAIAgentThread.run(IAIAgentThread.java:321)
	at java.base/java.lang.Thread.run(Thread.java:1583)
Caused by: java.lang.AssertionError: PROBE synthetic failure in Train.entered
	at cz.vutbr.fit.ags.xhovor07.Train.entered(Train.java:87)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
	... 3 more
Exception thrown by target ---
java.lang.AssertionError: PROBE synthetic failure in Train.entered
	at cz.vutbr.fit.ags.xhovor07.Train.entered(Train.java:87)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at com.iai.cybele.thmgmt.IAIAgentThread.run(IAIAgentThread.java:321)
	at java.base/java.lang.Thread.run(Thread.java:1583)
Thrown in Thread 'Thread-2'.
```

Cybele emits an `Originated from ---` block *and* an `Exception thrown by target ---`
block per failure, so the `AssertionError` line appears **twice**: 8 occurrences for
4 failures, measured. **A naive `grep -c AssertionError` double-counts.** Count
`Exception thrown by target ---`, or de-duplicate, if the number matters.

### Not every assertion goes through Cybele at all

Two of the 25 exercised sites — `RailwayCanvas.java:61` and `RailwayCanvas.java:101` —
fire on the **AWT event dispatch thread** inside `paint()`, never through
`IAIAgentThread`. That is 1 716 of the ~20 000 evaluations in run 6, i.e. a large
minority. They would be swallowed by AWT's uncaught-exception handler instead, with a
different banner and no Cybele framing.

This is the reason to key any detector on the **`AssertionError` text**, not on
Cybele's `*** Thread Mgmt Exception ->` banner: the banner covers neither the EDT
sites nor the `main`-thread case below.

### Three distinct failure modes, none visible in the exit status

| Where it throws | What is printed | Exit status | Simulation |
|---|---|---|---|
| Agent event handler (via `IAIAgentThread`) | Cybele banner + `AssertionError` ×2, stderr | unchanged | continues; that agent left partially updated |
| `Main.main` | `Exception in thread "main" java.lang.AssertionError`, stderr | unchanged | continues — 11 trains were generated *after* `main` died |
| Timer handler (`Generator.generateTrain`) | Cybele banner, stderr | unchanged | **stops permanently and silently** |

The third is the dangerous one. `Generator.generateTrain` re-arms its own timer with
`Activity.setTimer(...)` as its **last** statement
([`Generator.java:65`](../src/main/java/cz/vutbr/fit/ags/xhovor07/Generator.java#L65)).
Anything thrown at lines 58–64 skips that call, Cybele swallows the throwable, and the
generator is never scheduled again. Train generation ceases for the remainder of the
run. Nothing in the exit status reflects it, and after the initial stack trace nothing
further is logged — a scanner watching only for `AssertionError` sees one trace and a
plausible-looking, permanently dead simulation.

The `main` case matters too: it prints `Exception in thread "main"`, which does **not**
contain Cybele's banner. Cybele's non-daemon threads keep the JVM alive after `main`
dies, so this also does not change the exit status.

### What a runner must therefore do

- **Capture stderr.** The traces are not on stdout.
- **Never trust the exit status.** No failure location changes it.
- **Scan for both** `AssertionError` and `Exception in thread "` — the Cybele banner
  alone misses the EDT and `main` cases.
- **Do not equate occurrence count with failure count** (2 lines per handler failure).
- **Assert on liveness, not just absence of errors** — e.g. a minimum train count for
  the scenario's duration. It is the only way to catch the dead-generator mode.
- The probe also confirmed `-ea` genuinely reaches the JVM
  (`Main.class.desiredAssertionStatus() == true`), so the zero counts in Results 1–2
  are a real negative rather than a disabled-assertion artefact.

## What this does and does not decide

**Decided:** `-ea` is enabled for the `run` task (`build.gradle.kts`). It costs
nothing observable — no assertion fires — and converts future invariant breakage
into a visible stack trace.

**Deliberately not decided:** whether golden recordings are captured with
assertions on or off. Enabling `-ea` is itself a behaviour change: a path that
previously continued past a broken invariant now throws instead, and per Result 3
that throw mutates the surviving agent state. That choice belongs to
[#22](https://github.com/bedaHovorka/OpenCybele1/issues/22) and must be recorded
in the [#24](https://github.com/bedaHovorka/OpenCybele1/issues/24) manifest
alongside seed, timer periods and stop condition — a recording made with `-ea`
must never be compared against a replay made without it.

**Caveats.**

- No run reached a natural end, because there is no stop condition
  ([#17](https://github.com/bedaHovorka/OpenCybele1/issues/17)). Every result
  above is from a `timeout`-truncated run. "Runs to completion without an
  `AssertionError`" can only be claimed literally once #17 lands.
- The RNG is unseeded ([#15](https://github.com/bedaHovorka/OpenCybele1/issues/15)),
  so these runs sample the scenario space rather than cover it. Eight runs and
  ~20 000 assertion evaluations is decent evidence, not proof. This triage should
  be re-run once #15 and #17 make runs deterministic and bounded.
- Per-site counts come from a single instrumented run (run 6); the other seven runs
  were only checked for `AssertionError`.
- Result 3's failure modes were produced by *synthetic* faults deliberately planted at
  three call sites. They characterise how the kernel reacts to a failing assertion; they
  are not evidence about any real invariant in this codebase.

## Amendment — after #18 (scenario configuration)

Everything above was measured against the tree at commit `efa7711`. Two things about it are
now stale, and nothing else is.

**1. Line numbers — including this amendment's own.**
[#18](https://github.com/bedaHovorka/OpenCybele1/issues/18) externalised the hardcoded
simulation parameters, which shifted lines in `Generator`, `Gui`, `RailwayCanvas`,
`RailwayMainAgent`, `Main` and `Station`. The site tables above still name the pre-#18 lines;
the *assertions* they name are unchanged and still identify their sites unambiguously by
expression. Re-run the instrumentation pass before trusting the numbers.

> This bit the amendment itself: its first version cited the removal comment at
> `RailwayCanvas.java:121`, which is `g.translate(leftSpace, 250);` — the comment is at `:126`.
> Since `:121` contains no `assert` at all, excluding it was a no-op and the document's own
> instructions returned 33, not the 32 they were there to justify. Cite by **content** in this
> document, not by line, and re-check anything numeric against the working tree before relying
> on it.

**2. One site was removed: `assert road != null` in `RailwayCanvas.paint`** — at `:101` in the
pre-#18 tree this document measured, which is why the tables above list it there; it is gone
from the current tree, where `:101` is unrelated code. 1 430 evaluations in run 6.

It asserted that two stations drawn next to each other on the canvas have a track
between them. That was a real invariant while the canvas and the network were both hardcoded
constants; once `sim.topology` and `sim.gui.mainLine` are independently configurable it is a
property of the configuration instead, and an `AssertionError` was the wrong response to it —
it would have fired on the **AWT event dispatch thread**, which § "Not every assertion goes
through Cybele at all" above identifies as the case with no Cybele banner and no useful
framing. It is replaced by a red crossed `??` gap painted in place of the missing track, plus a
mismatch banner on stderr at startup and on the canvas. See
[`scenario-config.md`](scenario-config.md) § "The canvas topology duplication".

**The count is therefore 32 assertion sites, not 33** — 24 exercised and holding, 8 never
reached.

### Re-deriving the count: the exclusion list is now TWO entries, not one

The method above counts `assert` sites by grep, excluding one known Javadoc false positive.
**That method no longer yields 32 on this tree — it yields 33, and the difference is a second
false positive this very change introduced.** Anyone re-deriving the count with #14's stated
recipe will "confirm" 33, conclude the amendment is wrong, and be wrong themselves. Measured on
the post-#18 tree:

```
$ grep -rn 'assert ' src/ | wc -l
34
```

Both of these must be excluded. **They are identified by content, not by line number** — line
numbers in this document have already drifted once (see the warning above) and will drift again:

| File | The text that matches, and is not a site | Why |
|---|---|---|
| `util/Util.java` | `* assert and cast routine` | Javadoc prose. Excluded in #14 already. |
| `RailwayCanvas.java` | ``// No `assert road != null` any more: …`` | A **comment recording the removal below**. New in #18. |

`34 − 2 = 32`. Copy-pasteable, and free of both line numbers and a manual exclusion list:

```
$ grep -rn 'assert ' src/ | grep -v 'assert and cast routine' | grep -v 'any more' | wc -l
32
```

Better still, anchor on statement *position* rather than the bare word — this needs no exclusion
list at all, and is the recommended replacement for #14's recipe:

```
$ grep -rnE '^\s*(\} else )?assert ' src/main/java --include=*.java | wc -l
32
```

(The `} else assert false;` alternative is needed for the one site not at the start of its line,
in `RailwayMainAgent.TableModel.getValueAt`.) A set-difference of the assert *expressions*
between the pre- and post-#18 trees confirms exactly one removal and no additions.

### The narrower assertion that was considered and rejected

`assert road != null || !layoutWarnings.isEmpty();` would have kept the site, and with it this
document's 33/25 baseline, at no runtime cost. It was rejected: it introduces a **top-level
`||`**, which § "Instrumentation pass" above singles out as the one shape that silently breaks
that instrumentation recipe (`hit() && a || b` regroups as `(hit() && a) || b`). Trading a
one-line bookkeeping delta for a landmine in the tool that produced this document is a bad
trade. The removal stands, and this section is the compensation.

Nothing else moved: no assertion was added, weakened or strengthened, and the
`-ea` decision, the Result 3 mechanism and every caveat above stand unchanged. The new
`ScenarioConfig` deliberately uses **exceptions, not assertions**, for configuration validation,
and raises them from `Main.main` before the kernel starts — precisely because Result 3 shows
that an assertion inside an agent is swallowed. That path *does* change the exit status to 1,
which makes it the one failure mode in this codebase a runner can trust the exit status for.
