# Phase2.md — → Jason (AgentSpeak/BDI)

> Phase 2 of the two-phase experimental migration. Source of truth: the same frozen behavioral contract recorded in Phase 1 (scenario specs + goldens). Deliverable: a behaviorally-equivalent Jason implementation on branch `jason` (or module `/jason`), plus Jason-native white-box suites, closing with a three-way comparison (OpenCybele vs JADE vs Jason).
>
> Companion docs: `MIGRATION.md` (§5 step-by-step, §7 examples), `TESTING.md` (T§5 Jason testing), `Phase1.md` (harness + goldens produced there).

**Scope guard unchanged:** no feature/behavior extensions. Golden diffs = port bugs. The only allowed golden change is a Phase-1-style normalizer fix, re-verified against `develop` **and** `jade` before merging.

---

## Phase overview

| Sub-phase | Name | Works on | Output |
|---|---|---|---|
| **2-PRE** | Test extension *before* porting | harness + `jade` learnings + Jason skeleton | Jason-ready harness adapter, BDI-level test scaffolding, gap-filled scenarios |
| 2-PORT | The port itself | `jason` | Jason implementation (.asl + env + internal actions) |
| **2-POST** | Test extension *after* porting | `jason` + harness | Jason white-box suites + green parity + 3-way report |

Entry criteria: Phase 1 DoD met (goldens green on `develop` and `jade`); Phase-1 comparison log available; Jason 3.x toolchain installed (`jason app create` works, JDK 21).

---

## Sub-phase 2-PRE — extend tests BEFORE the port

Goal: make the harness Jason-ready and pre-build the BDI-level test scaffolding so porting can be test-driven.

### 2-PRE.1 Gap analysis from Phase 1
- [ ] Review Phase-1 triage log: every behavior that was *almost* missed by a scenario (caught late, or only by L1/L2) gets a new L3 scenario now — recorded against `develop`, verified green on `jade` too, then frozen. This is the only window where scenarios are added.
- [ ] Mark scenarios sensitive to fine-grained interleaving: Jason's reasoning cycle will interleave differently than Cybele threads / JADE behaviours. For each, confirm the golden asserts **ordering/counts within the contract** (per-agent causal order, totals) and not incidental cross-agent interleaving; strengthen the normalizer's tick-bucket sorting where needed. Re-verify on `develop` + `jade` after every normalizer change.

### 2-PRE.2 Harness adapter for Jason
- [ ] Adapter #3: `JasonLauncher` — runs `jason scenario.mas2j` (or Gradle run) with scenario parameters passed as initial beliefs / env `init` args.
- [ ] Probe re-implemented Jason-side with identical canonical trace format. Two options, pick one and document: (a) a probe **agent** with `+!kqml_received(...)`-level and `+belief[source(...)]` logging plans; (b) trace emission from the custom `AgArch`/`Environment` (cleaner: intercepts all `act`/message traffic without touching agent code). Option (b) recommended.
- [ ] Decide execution mode per scenario: default asynchronous; flag scenarios that only stabilize in **synchronous** mode and run those with the sync setting in the launcher (document that production default stays async).
- [ ] Smoke test: hello-world `.mas2j` runs through `ScenarioRunner` end-to-end and produces a normalizable trace.

### 2-PRE.3 BDI test scaffolding (test-first prep)
- [ ] Set up `jason.asunit` in the `jason` module's `test` source set (JUnit); one proof-of-life test compiling and running (T§5.1 pattern: `TestAgent`, `parseAScode`, `assertPrint`).
- [ ] Set up the AgentSpeak-native framework: `tester_agent.asl` include, one dummy `@[test]` plan running via the `test` command (T§5.2).
- [ ] From the Phase-1 inventory, pre-write **failing test skeletons** for each planned plan/rule: for every JADE behaviour ported in Phase 1, a corresponding asunit or `@[test]` skeleton naming the target plan and expected belief/print outcome. These drive 2-PORT test-first.
- [ ] Plain-JUnit skeletons for planned internal actions and the `Environment` (`executeAction` dispatch table from the inventory's action list).

**Exit criteria 2-PRE:** harness runs a trivial Jason MAS; all three launchers green on their respective baselines; scenario set frozen (final); test skeletons committed (red).

---

## Sub-phase 2-PORT — the port (summary; details MIGRATION.md §5)

- [ ] Reuse Phase-1's extracted plain-Java domain classes as-is → wrap as **internal actions** (pure computation) or **environment actions** (world effects). Their Phase-1 unit tests carry over unchanged.
- [ ] Per agent: fields → beliefs, activities → plans, `sendMessage`/ACL → `.send` + triggered plans, timers → `.wait` loop or env-driven `tick` percepts (prefer env-driven if the golden pins cross-agent timing).
- [ ] Port agent-by-agent in the same order as Phase 1; after each agent, run its L3 scenarios + turn its 2-PRE skeletons green.
- [ ] Config: scenario `.def` parameters → `.mas2j` + initial beliefs.

---

## Sub-phase 2-POST — extend tests AFTER the port (on Jason)

### 2-POST.1 Unit layer (L1)
- [ ] All 2-PRE asunit skeletons green: each plan has ≥1 test firing its trigger and asserting resulting beliefs/prints; include ≥1 context-fails case per guarded plan (plan not applicable → alternative/failure path).
- [ ] All `@[test]` ASL plans green via the `test` command; wire into Gradle so CI runs them.
- [ ] Internal actions: plain JUnit on `execute(...)` with real `Term`s, asserting unification results.
- [ ] Environment: unit tests on `executeAction` dispatch + `addPercept` effects (instantiate directly, no MAS boot).

### 2-POST.2 Integration layer (L2)
- [ ] Minimal `.mas2j` per interaction pair (2–3 agents + env), run in synchronous mode, assert final beliefs (via test agent or trace).
- [ ] One test per communication performative actually used (`tell`, `achieve`, `askOne`, broadcast): sender + receiver pair, assert receiver-side belief/goal with correct `source(...)` annotation.
- [ ] Failure-handling test: one deliberately failing plan (`-!g`) path if the ported logic has any error handling pinned by goldens.

### 2-POST.3 Parity run (L3)
- [ ] Full scenario suite via `JasonLauncher` against the frozen goldens.
- [ ] Triage identical to Phase 1: port bug → fix `.asl`/env; normalizer gap (Jason-specific ids, cycle artifacts) → fix + re-verify on `develop` and `jade`; never accept behavioral drift.
- [ ] CI: `characterizationIT@jason` job green; keep `@develop` and `@jade` jobs running — final state is **all three green on identical goldens**.

### 2-POST.4 Three-way comparison report (the experiment's payoff)
- [ ] `COMPARISON.md`: per implementation — LOC (impl vs test), port effort, concept-mapping friction (what was awkward in each), runtime observations (startup, scenario wall time, agent count scaling if tried), test-writing experience (POJO+Mockito vs asunit vs `@[test]`), and which contract aspects were hardest to keep equivalent (timing, interleaving, message payloads).
- [ ] Note explicitly what the goldens do NOT pin (threading model, internal scheduling) so readers don't over-interpret equivalence.

**Exit criteria Phase 2 (Definition of Done):**
1. L3 suite green and flake-free on **all three** implementations against identical goldens.
2. L1 + L2 Jason suites green in CI; every plan covered; every internal action & env action covered.
3. Scenario set unchanged since the 2-PRE freeze (or every change re-verified on all three).
4. `COMPARISON.md` published; harness free of Jason-specific hacks outside `JasonLauncher`.
