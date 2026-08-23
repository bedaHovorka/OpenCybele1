# Phase2.md — → Jason (AgentSpeak/BDI)

> Phase 2 of the two-phase experimental migration. Source of truth: the same frozen behavioral contract recorded in Phase 1 (scenario specs + goldens). Deliverable: a behaviorally-equivalent Jason implementation on branch `jason` (or module `/jason`), plus Jason-native white-box suites, closing with a three-way comparison (OpenCybele vs JADE vs Jason).
>
> Companion docs: `MIGRATION.md` (§5 step-by-step, §7 examples), `TESTING.md` (T§5 Jason testing), `Phase1.md` (harness + goldens produced there).

> **Upstream verification note — verified 2026-08-18.** Versions observed:
> Jason `io.github.jason-lang:jason-interpreter` **3.3.0** (latest release per Maven Central
> [`maven-metadata.xml`](https://repo1.maven.org/maven2/io/github/jason-lang/jason-interpreter/maven-metadata.xml);
> `3.3.3` is the undated in-development heading of `release-notes.adoc` on `main`, not a release),
> Jason prose docs from branch `main`; JADE `net.sf.ingenias:jade:4.3` = **JADE 4.3.3 rev 6726 of
> 2014/12/09**; JDK **OpenJDK 21.0.11**. Full note and reachability caveats:
> [`CYBELLE_TO_JADE.md`](CYBELLE_TO_JADE.md#upstream-verification).


**Scope guard unchanged:** no feature/behavior extensions. Golden diffs = port bugs. The only allowed golden change is a Phase-1-style normalizer fix, re-verified against `develop` **and** `jade` before merging.

---

## Phase overview

| Sub-phase | Name | Works on | Output |
|---|---|---|---|
| **2-PRE** | Test extension *before* porting | harness + `jade` learnings + Jason skeleton | Jason-ready harness adapter, BDI-level test scaffolding, gap-filled scenarios |
| 2-PORT | The port itself | `jason` | Jason implementation (.asl + env + internal actions) |
| **2-POST** | Test extension *after* porting | `jason` + harness | Jason white-box suites + green parity + 3-way report |

Entry criteria: ~~Phase 1 DoD met (goldens green on `develop` and `jade`)~~ ⚠️ **Restated by [#41](https://github.com/bedaHovorka/OpenCybele1/issues/41) —
as written this criterion is unmeetable and Phase 2 would never start.** Phase 1 ended with
`opencybele-strict`, `-congestion` and `-capacity` **red and deliberately staying red**: the
residual is classified **(b)**, is not closable, and seven candidate repairs were measured and
rejected (`Phase1.md` "The verdict"; `parity-triage-jade.md` §5–§7). The criterion that is actually
available, and that carries the same guarantee, is:

* **the L3 suite runs against both implementations with the goldens untouched**, and no scenario on
  either arm has ever differed from its golden by **multiset** — no dropped event, no invented one.
  That is what `characterizationIT@jade` asserts on every push, and `BEHAVIOUR` is a verdict class
  the judge grants nowhere;
* **`lifecycle` and `timers` are byte-identical on both**, 20/20 and 12/12;
* **the three red scenarios are red for a measured, recorded reason**, and re-tuning their margins
  is 2-PRE work (2-PRE.1 below) rather than a blocker on entry.

Plus: Phase-1 comparison log available ([`comparison-log-phase1.md`](comparison-log-phase1.md));
Jason 3.x toolchain installed (`jason app create` works, JDK 21).

---

## Sub-phase 2-PRE — extend tests BEFORE the port

Goal: make the harness Jason-ready and pre-build the BDI-level test scaffolding so porting can be test-driven.

### 2-PRE.1 Gap analysis from Phase 1

> **What Phase 1 actually handed over.** These nine items were written down by
> `parity-triage-jade.md` §10 and `comparison-log-phase1.md` §10 and were missing from this
> checklist until [#41](https://github.com/bedaHovorka/OpenCybele1/issues/41). They are the concrete content of the two generic bullets below.
>
> 1. **`opencybele-strict` and `opencybele-congestion` need their margins re-tuned**, now against
>    *two* implementations' gap distributions rather than one. A scenario whose nearest
>    gap-to-threshold margin is 4 ms (baseline) or 0 ms (port) is not a contract; it is a coin toss
>    with a golden attached. This is the single highest-value 2-PRE task, because it is what would
>    let Phase 2's own DoD ("all three green on identical goldens") mean anything.
> 2. **`opencybele-capacity` sits on a 3 ms margin** and is the next scenario in this class to go
>    red. It will look like a port bug when it does.
> 3. **The observer is not free on JADE and was free on Cybele** (SEM-03 multicast has no JADE
>    analogue, so the probe is a second receiver on every message). Any Phase-2 scenario that
>    depends on message timing is measuring the probe as much as the system, and **Jason will give
>    a third answer again**.
> 4. **`VOTE.diff` sits on a `TIME_QUANTUM` boundary in `strict`** (T-07). A scenario that means to
>    pin the election's arithmetic must place the value where a 1 000 ms bucket has headroom.
> 5. **DEF-02 did not reproduce and structurally cannot on JADE.** If the migration is meant to
>    demonstrate anything about the original's defects, it needs a scenario that observes them
>    directly rather than a golden that happens to contain them.
> 6. **Two scenarios reproduce byte-for-byte across two independent agent kernels**, goldens
>    untouched. That is the result worth carrying forward.
> 7. **The `Serializable[]` → ACL step was free; the `Serializable[]` → ground-terms step will not
>    be.** `MIGRATION.md` §10/§12 treat "arbitrary Java-object messages" as a limitation of both
>    targets. It is not symmetric: JADE let the port keep Java objects under `setContentObject`
>    (`message-ontology.md` §5.8), and AgentSpeak content genuinely is logical terms. **This is the
>    one predicted limitation that did not bite in Phase 1 and will bite in Phase 2.**
> 8. **The `TRAVEL_LEFT`/`TRAVEL_RIGHT` label is pinned by no golden and cannot be** (COVERAGE
>    §10.11). A port that sorts its endpoint pairs — the most natural thing a reimplementation does
>    — flips 5 of 7 roads and still produces byte-identical goldens. If Phase 2 wants it pinned it
>    is an L1 test on the extracted graph, not a scenario.
> 9. **Cross-occasion reproducibility of the goldens is still open** (COVERAGE §2.1.1): every gate
>    invocation so far is from one boot. Cheap to close — one `parityGate` run after a reboot,
>    appended to §2.1.

- [ ] Review Phase-1 triage log: every behavior that was *almost* missed by a scenario (caught late, or only by L1/L2) gets a new L3 scenario now — recorded against `develop`, verified green on `jade` too, then frozen. This is the only window where scenarios are added.
- [ ] **Re-tune the burst-order margins for `strict`, `congestion` and `capacity`** (items 1–2 above). Phase-1 margins against the 220 ms width: `lifecycle` 118 ms, `timers` no gap in [120, 400], `capacity` 3 ms, `congestion` 9 ms, `strict` 0 ms — and the margin predicts the pass rate in exact rank order on both implementations.
- [ ] Mark scenarios sensitive to fine-grained interleaving: Jason's reasoning cycle will interleave differently than Cybele threads / JADE behaviours. For each, confirm the golden asserts **ordering/counts within the contract** (per-agent causal order, totals) and not incidental cross-agent interleaving; strengthen the normalizer's tick-bucket sorting where needed. Re-verify on `develop` + `jade` after every normalizer change.

### 2-PRE.2 Harness adapter for Jason
- [ ] Adapter #3: `JasonLauncher` — runs `jason scenario.mas2j` (or Gradle run) with scenario parameters passed as initial beliefs / env `init` args.
- [ ] Probe re-implemented Jason-side with identical canonical trace format. Two options, pick one and document: (a) a probe **agent** with `+!kqml_received(...)`-level and `+belief[source(...)]` logging plans; (b) trace emission from the custom `AgArch`/`Environment` (cleaner: intercepts all `act`/message traffic without touching agent code). Option (b) recommended.
- [ ] Decide execution mode per scenario. ⚠️ **Rewritten — the previous text ("default asynchronous; run flaky scenarios in synchronous mode") was factually wrong.** There is no global sync/async switch: execution mode is written on the **`infrastructure:` line** of the `.mas2j`, the default `infrastructure: Local` is *one thread per agent running sense/deliberate/act once each per cycle*, and "asynchronous" (`asynch`, `asynch_shared`) is **opt-in**. The documented forms are `Local(threaded, S, D, A)`, `Local(pool, N [, C])`, `Local(pool, N, S, D, A [, C])`, `Local(synch_scheduled, N)` / `(N, C)` / `(N, S, D, A)`, `Local(asynch_shared, N, S, D, A)`, `Local(asynch, ST, DT, AT [, S, D, A])`, plus per-agent `ag [cycles_sense=…, cycles_deliberate=…, cycles_act=…]` or `ag [cycles=N]`. Throughout, `N` = thread count, `S`/`D`/`A` = per-stage cycle counts, and `C` = reasoning cycles per turn (default 5) — ⚠️ **except under `synch_scheduled`**, where upstream states verbatim that *"`[NUMBER-REASONING-CYCLES]` is not a parameter for this configuration"*: there the second argument is a per-stage cycle count, so `Local(synch_scheduled,4,5)` means "each stage at most 5 times", **not** "5 reasoning cycles". Three consequences for this checklist:
  - **`Local`-only.** Upstream: *"Different concurrency configurations can be set for the **Local** infrastructure in Jason."* Choosing `infrastructure: Jade` forfeits the whole family ([#45](https://github.com/bedaHovorka/OpenCybele1/issues/45)).
  - **Upstream claims no determinism or reproducibility** from any of these settings. Do not write "run it synchronously for reproducibility" into the launcher rationale ([#43](https://github.com/bedaHovorka/OpenCybele1/issues/43)).
  - **Single-threaded comes from `N = 1`** (e.g. `Local(pool,1)`), not from the keyword `synch_scheduled`, which controls stage granularity, not thread count.

  Source: <https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/concurrency.adoc>
- [ ] Smoke test: hello-world `.mas2j` runs through `ScenarioRunner` end-to-end and produces a normalizable trace.

### 2-PRE.3 BDI test scaffolding (test-first prep)
- [ ] ~~Set up `jason.asunit` in the `jason` module's `test` source set (JUnit); one proof-of-life test compiling and running.~~ **STRUCK — unsatisfiable.** `jason.asunit` **does not exist in Jason 3.3.0**: it is absent from the published Javadoc package list (<https://jason-lang.github.io/api/element-list>) and from `jason-interpreter` `3.3.0`/`3.2.1`/`3.2.0`, and `src/test/java/test/asunit/` 404s at tags `v3.3.0` and `main`. Its real history is **published → demoted → deleted**: it shipped in the main jar of `net.sf.jason:jason` **1.4.1** and **1.4.2** (2015), was gone from the published jar by **2.0**, survived only in Jason's own test tree through tag `v2.6`, and is absent from the entire 3.x line. All **ten** published artifacts were checked; full evidence table in [`TESTING.md`](TESTING.md) §5. Leaving this item in would have made the Phase-2 DoD unmeetable — and no supported version brings it back.
- [ ] **Set up the (single, official) AgentSpeak-native test mechanism** — tester agents in `src/test/jason/asl`, each including `{ include("$jasonJar/test/jason/inc/tester_agent.asl") }`, with `@[test]`-annotated plan labels and assertions from `test_assert.asl` (`!assert_equals(Expected, Actual [, Tolerance])`, `!assert_true`, `!assert_false`). One dummy `@[test]` plan running end to end (T§5.1). Source: <https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/unit-tests.adoc>
- [ ] ⚠️ **Author the Gradle test task and the project-level `unit_tests.mas2j` — they are not generated.** The template `jason app add-gradle` writes defines exactly `run`, `runJade` and `shadowJar` (verified by extracting `templates/build.gradle` from `jason-interpreter-3.3.0.jar`); `:testJason` is a task in *Jason's own* repository build. This is [#44](https://github.com/bedaHovorka/OpenCybele1/issues/44); budget it, do not assume it. Note also that `src/test/jason/asl` is the **default** test folder, not a fixed one — the directory is a string argument to `create_tester_agents(Dir, Pattern)`. We keep the default deliberately.
- [ ] From the Phase-1 inventory, pre-write **failing test skeletons** for each planned plan/rule: ~~for every JADE behaviour ported in Phase 1~~ — ⚠️ **wrong unit, corrected by [#41](https://github.com/bedaHovorka/OpenCybele1/issues/41).** The JADE port has eleven `CyclicBehaviour` classes — an `Inbox`/`Drain` pair per agent, and a `Drain` has no behaviour of its own to test — for fifteen channels and four activities, so "one tester plan per behaviour" counts the wrong thing in both directions. The Phase-2 unit is the **channel** (`CH-01`…`CH-15`, `message-ontology.md` §3) or the handler method. For each, a corresponding `@[test]` tester-agent plan naming the target plan and expected belief/print outcome. These drive 2-PORT test-first.
- [ ] Plain-JUnit skeletons for planned internal actions and the `Environment` (`executeAction` dispatch table from the inventory's action list).

**Exit criteria 2-PRE:** harness runs a trivial Jason MAS; all three launchers green on their respective baselines; scenario set frozen (final); test skeletons committed (red).

---

## Sub-phase 2-PORT — the port (summary; details MIGRATION.md §5)

- [ ] Reuse Phase-1's extracted plain-Java domain classes as-is → wrap as **internal actions** (pure computation) or **environment actions** (world effects). Their Phase-1 unit tests carry over unchanged.
- [ ] Per agent: fields → beliefs, activities → plans, `sendMessage`/ACL → `.send` + triggered plans, timers → `.wait` loop or env-driven `tick` percepts (prefer env-driven if the golden pins cross-agent timing).
- [ ] Port agent-by-agent in the same order as Phase 1; after each agent, run its L3 scenarios + turn its 2-PRE skeletons green.
- [ ] **Use the concurrency constructs the planning docs used to omit** — they change the port design; full treatment in [`MIGRATION.md`](MIGRATION.md) §3.2, source <https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/concurrency.adoc>:
  - **`!!g`** spawns a *new intention* — the following action runs after the intention is created, not after `g` is achieved.
  - **`!g1 |&| !g2`** (fork-join-and) is the direct analogue of `CountDownLatch.await()` over the vote replies. ⚠️ It is a plan-**body** operator, so its arity is fixed in the source, while the vote set is dynamic — use the recursive idiom `+!ask_all([H|T]) <- !ask_vote(H) |&| !ask_all(T).` ([#49](https://github.com/bedaHovorka/OpenCybele1/issues/49)). ⚠️ On sub-goal failure with **no `-!` plan**, upstream: fork-join-and *"drops also the other subgoal and handles the failure in the achievement of goal `gb` above as usual"* — one unhandled vote failure cancels all the siblings and fails the parent.
  - **`!g1 ||| !g2`** (fork-join-xor) continues after the *first* completes and drops the other. Precedence: `|&|` > `|||` > `;`.
  - **`@[atomic]`** replaces the codebase's pervasive `synchronized` handlers, and propagates to subgoals ("If an atomic plan runs, all its subgoals will be also atomic"). ⚠️ **Never wrap a plan that can suspend** (`.wait`, `.suspend`, synchronous 4-argument `askOne`): atomic blocks *every* other intention until the plan finishes, so a suspension parks the whole agent. Upstream states the reactivity cost, not this rule — it is derived, and it is binding for [#48](https://github.com/bedaHovorka/OpenCybele1/issues/48).
- [ ] **Performative choice is not free**: `signal`, not `tell`, for the repeatedly re-sent state channels (`CH-10` `STATION.INFO`, `CH-11` `ROAD.STATE`) — `tell` raises no event when the belief already holds, and those payloads are frequently identical. See [`MIGRATION.md`](MIGRATION.md) §3.1.
- [ ] Config: scenario ~~`.def`~~ parameters → `.mas2j` + initial beliefs. *([#41](https://github.com/bedaHovorka/OpenCybele1/issues/41): there are no `.def` files and never were — that is assumption-era terminology from `MIGRATION.md`. Phase 1's parameter surface is the twenty `sim.*` keys resolved by `ScenarioConfig` from `-D` flags and the scenario YAML; see [`scenario-config.md`](scenario-config.md).)*

---

## Sub-phase 2-POST — extend tests AFTER the port (on Jason)

### 2-POST.1 Unit layer (L1)
- [ ] All 2-PRE **`@[test]` tester-agent skeletons** green: each plan has ≥1 test firing its trigger and asserting resulting beliefs/prints; include ≥1 context-fails case per guarded plan (plan not applicable → alternative/failure path). *(Previously "asunit skeletons" — struck, see 2-PRE.3.)*
- [ ] All `@[test]` plans green via `./gradlew test` (the task authored in 2-PRE.3, [#44](https://github.com/bedaHovorka/OpenCybele1/issues/44)); CI runs it. Remember the upstream constraints: `src/test/jason/asl` must contain **only** tester agents or statistics break, shared includes go in `src/test/jason/inc`, and two identical assertions in one plan count once.
- [ ] Internal actions: plain JUnit on `execute(...)` with real `Term`s, asserting unification results.
- [ ] Environment: unit tests on `executeAction` dispatch + `addPercept` effects (instantiate directly, no MAS boot).

### 2-POST.2 Integration layer (L2)
- [ ] Minimal `.mas2j` per interaction pair (2–3 agents + env), run in synchronous mode, assert final beliefs (via test agent or trace).
- [ ] One test per communication performative actually used, drawn from the **complete** set `tell, untell, achieve, unachieve, askOne, askAll, askHow, tellHow, untellHow, signal` (<https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/performatives.adoc>): sender + receiver pair, assert receiver-side belief/goal with correct `source(...)` annotation. ⚠️ `broadcast` is **not** a performative — `.broadcast` is an internal action that *carries* one. ⚠️ **`signal` must be covered**: the state-publication channels re-send identical payloads (`CH-10`/`CH-11` in [`INVENTORY.md`](INVENTORY.md)), and only `signal` re-raises an event for an identical message — *"Different of `tell`, if an agent sends the same signal twice, the receiver will have two events."* A `tell`-based test would pass while the real traffic silently vanished.
- [ ] Failure-handling test: one deliberately failing plan (`-!g`) path if the ported logic has any error handling pinned by goldens.

### 2-POST.3 Parity run (L3)
- [ ] Full scenario suite via `JasonLauncher` against the frozen goldens.
- [ ] Triage identical to Phase 1: port bug → fix `.asl`/env; normalizer gap (Jason-specific ids, cycle artifacts) → fix + re-verify on `develop` and `jade`; never accept behavioral drift.
- [ ] CI: `characterizationIT@jason` job green; keep `@develop` and `@jade` jobs running — final state is **all three green on identical goldens**.

### 2-POST.4 Three-way comparison report (the experiment's payoff)
- [ ] `COMPARISON.md`: per implementation — LOC (impl vs test), port effort, concept-mapping friction (what was awkward in each), runtime observations (startup, scenario wall time, agent count scaling if tried), test-writing experience (JADE POJO+Mockito vs Jason `@[test]` tester agents — note that the third option this line used to name, `jason.asunit`, does not exist; see 2-PRE.3), and which contract aspects were hardest to keep equivalent (timing, interleaving, message payloads).
- [ ] **Three axes Phase 1 says are missing from that list** (%s, [`comparison-log-phase1.md`](comparison-log-phase1.md)); without them the payoff section under-reports the experiment:
  - **the timing model** — Cybele's global, pace-adjustable, pausable simulated clock has no analogue in either target; carrying it was the JADE port's largest new subsystem, and no planning document had a row for it;
  - **the cost of observability** — the probe was free on Cybele (SEM-03 multicast) and is not on JADE; each framework charges differently, and it perturbs the very measurement the goldens take;
  - **which defects survive the port** — DEF-02 is structurally unreproducible on JADE, DEF-23's starvation half is a property of Cybele's dispatcher and not of this application, and several preserved defects diverge in *shape* (Cybele kills one handler, JADE kills the whole agent).
- [ ] Note explicitly what the goldens do NOT pin so readers don't over-interpret equivalence. Phase 1's list, ready to reuse and extend, is [`jade/README.md`](../jade/README.md) §6: **threading model, internal scheduling, conversation ids and performatives, container topology, the JICP listener that binds a socket where Cybele bound none, GUI freshness under snapshot-vs-alias semantics, failure *shape*, and the `TRAVEL_LEFT`/`TRAVEL_RIGHT` label — which is unpinnable by any golden**, because `acceptTrain` replies with the far end in both arms, so an endpoint-swapped port is byte-identical on everything a trace can see.

**Exit criteria Phase 2 (Definition of Done):**
1. L3 suite green and flake-free on **all three** implementations against identical goldens.
   ⚠️ **Phase 1 failed exactly this criterion on two implementations** and the reason was the
   instrument, not the port (`Phase1.md` "The verdict"). Either 2-PRE.1's margin re-tune closes it
   for all three — which is the intended path and is why that task is listed — or this criterion
   must be restated the same way the entry criterion above was, in terms of the **multiset**
   guarantee plus the per-scenario verdict class. Do not carry it forward unexamined: as written it
   was already unmeetable at the end of Phase 1.
2. L1 + L2 Jason suites green in CI; every plan covered; every internal action & env action covered.
3. Scenario set unchanged since the 2-PRE freeze (or every change re-verified on all three).
4. `COMPARISON.md` published; harness free of Jason-specific hacks outside `JasonLauncher`.
