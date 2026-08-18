# Assertion triage — baseline `-ea` run on OpenCybele

> Produced for [#14](https://github.com/bedaHovorka/OpenCybele1/issues/14) (*Reproducible baseline*).
> Consumed by [#22](https://github.com/bedaHovorka/OpenCybele1/issues/22) (assertions-on/off decision for
> golden recording) and [#24](https://github.com/bedaHovorka/OpenCybele1/issues/24) (run manifest).
>
> **Headline: no assertion fires.** All 33 `assert` statements were checked with
> `-ea` on. 25 of them are exercised by a normal run — collectively ~20 000
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

### Instrumentation pass (throwaway, not committed)

Every `assert EXPR;` was rewritten to `assert Diag.hit("<file>:<line>") && EXPR;`,
where `Diag.hit` counts and returns `true`. Because the injected call is folded
into the assert's own boolean expression:

- it only runs when `-ea` is on — exactly the condition under test;
- `&&` binds looser than every operator used in these expressions, and `hit()` is
  constantly `true`, so `true && EXPR` preserves the original truth value and the
  original short-circuit behaviour;
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
| `util/Util.java:42` | `!objects[i].getClass().isArray()` | `Util` varargs helper unused on the simulated paths |
| `util/Util.java:56` | `clazz != null` | `Util.assertAndCast` never called |
| `util/Util.java:57` | `clazz.isInstance(obj)` | same |
| `util/AbstractUnorientedGraph.java:41` | `o instanceof Integer` | code path unused by `HashMapGraph` as driven here |
| `util/AbstractUnorientedGraph.java:60` | `cause instanceof RuntimeException` | error path, not triggered |
| `util/HashMapGraph.java:121` | `h != null` | overload unused on these paths |

These 8 are **not** evidence of correctness — they are simply uncovered. Two are
supposed to be uncovered; the other six mark a coverage gap that scenario
extension (1-PRE.3) may or may not choose to close. None of them blocks the
baseline.

## Result 3 — how Cybele handles a firing assertion

This was verified directly, by temporarily planting `assert false` inside
`Train.entered` and running it. Behaviour:

```
Exception thrown by target ---
java.lang.AssertionError: PROBE synthetic failure in Train.entered
	at cz.vutbr.fit.ags.xhovor07.Train.entered(Train.java:88)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at com.iai.cybele.thmgmt.IAIAgentThread.run(IAIAgentThread.java:321)
	at java.base/java.lang.Thread.run(Thread.java:1583)
Thrown in Thread 'Thread-1'.
```

`com.iai.cybele.thmgmt.IAIAgentThread` invokes event handlers reflectively and
**catches, prints, and swallows** any `Throwable`. Consequences that matter for
the harness:

- A firing assertion **does not** terminate the process, change the exit status,
  or stop the simulation. The run continued generating trains after every synthetic failure.
- It aborts only the remainder of *that one handler invocation*. The agent's state
  is left partially updated — a firing assertion in this codebase silently corrupts
  the agent it fires in, rather than halting.
- Therefore a golden-master runner **must not** rely on exit status to detect
  assertion failures. It has to scan captured output for `AssertionError`, or the
  failure will pass as green.
- The probe also confirmed `-ea` genuinely reaches the JVM
  (`Main.class.desiredAssertionStatus() == true`), so the zero counts above are a
  real negative rather than a disabled-assertion artefact.

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
  so these runs sample the scenario space rather than cover it. Six runs and
  ~20 000 assertion evaluations is decent evidence, not proof. This triage should
  be re-run once #15 and #17 make runs deterministic and bounded.
- Counts come from a single instrumented run; the other five runs were only
  checked for `AssertionError`.
