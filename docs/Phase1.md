# Phase1.md — OpenCybele → JADE

> Phase 1 of the two-phase experimental migration. Source of truth for behavior: the **frozen** OpenCybele solution on `develop`. Deliverable: a behaviorally-equivalent JADE implementation on branch `jade` (or module `/jade`), plus the shared parity harness that Phase 2 will reuse unchanged.
>
> Companion docs: `MIGRATION.md` (concept mappings), the JADE-first decision doc (§ Cybele→JADE behaviour table), `TESTING.md` (test techniques referenced below as T§x).

**Scope guard:** logic / system / features / behaviour are NOT extended in any phase. Every golden diff in this phase = bug in the port. Goldens are never re-recorded in Phase 1 except to fix a defect in the harness itself (normalizer bug), and any such re-record must be reproduced and verified against `develop` first.

---

## Phase overview

| Sub-phase | Name | Works on | Output |
|---|---|---|---|
| **1-PRE** | Test extension *before* porting | `develop` (OpenCybele) + shared harness | Frozen behavioral contract: scenario specs + goldens + runner |
| 1-PORT | The port itself | `jade` | JADE implementation |
| **1-POST** | Test extension *after* porting | `jade` + shared harness | JADE white-box suites + green parity run |

Entry criteria for Phase 1: repo inventory done (agents, activities, timers, message types, config — Stage-0 table from MIGRATION.md); OpenCybele solution builds and runs a full scenario locally.

---

## Sub-phase 1-PRE — extend tests BEFORE the port (on OpenCybele)

Goal: pin current behavior so completely that the JADE port can be judged mechanically.

### 1-PRE.1 Parity harness skeleton (shared asset)
- [ ] Create the harness as an implementation-agnostic module/repo: `parity-tests/` containing `scenarios/*.yaml`, `golden/*.txt`, `TraceNormalizer`, `ScenarioRunner`.
- [ ] `ScenarioRunner` takes a **launcher adapter** (command line + env) so the same scenario runs against any implementation. Adapter #1: `OpenCybeleLauncher` (ProcessBuilder wrapping the current main class + `.def` config — T§3.2).
- [ ] Decide branch strategy for the harness now: separate repo / published test artifact / monorepo module — so `jade` and later `jason` consume it without copy-paste.

### 1-PRE.2 Determinism work on `develop` (allowed, minimal, non-behavioral)
- [ ] Fix random seeds via config/system property.
- [ ] Make timer periods configurable (shortened in tests); bounded stop condition per scenario (max ticks / max messages / timeout).
- [ ] If message traffic is not observable in logs: add the **probe agent** (T§3.3) — additive only (new agent + config entry), emitting canonical lines `agent|tick|event|from|to|performative|payload`.
- [ ] Re-run each scenario ≥5×; iterate on `TraceNormalizer` (strip TS/thread/ids, sort within tick) until byte-identical output. **Gate:** zero flake across ≥10 consecutive runs per scenario.

### 1-PRE.3 Scenario coverage extension
Extend scenarios until every row of the Stage-0 inventory is exercised at least once:
- [x] One scenario per agent type in isolation (where meaningful) — `opencybele-lifecycle`; see COVERAGE.md §3 for why true isolation is not a configuration this system has.
- [x] One scenario per message type / interaction pair — all fifteen channels appear in all four scenarios, asserted by `OpenCybeleLifecycleIT` rather than claimed.
- [x] One scenario per timer/periodic activity (assert tick counts over a bounded window, not durations — T§7) — `opencybele-timers`.
- [x] Startup/shutdown scenario (agent lifecycle order, initial messages) — `opencybele-lifecycle`, including the asymmetry that only `Train` ever dies.
- [x] At least one "full system" scenario combining everything — `opencybele-strict`, the shipped topology unchanged.
- [x] Edge behaviors worth pinning — only if the current system has defined observable behavior for them. Of the three named in #23, **one is covered** (a road occupied in the opposing direction, `opencybele-congestion`) and **two are not, with measurements**: a station at capacity was not reachable at any configuration tried, and origin-equals-destination is rejected by `ScenarioConfig` at startup. COVERAGE.md §10.1 and §10.3.
- [x] **Coverage checklist committed: [`parity-tests/scenarios/COVERAGE.md`](../parity-tests/scenarios/COVERAGE.md)** — inventory row → scenario id, with a written justification, backed by a measurement, for every unmapped row. It also carries the gate and mutation results per scenario, and the two bound-margin rules a future scenario author needs (§12).

### 1-PRE.4 Record & freeze goldens
> **Handed over by 1-PRE.3:** the four scenarios are byte-reproducible across 10 consecutive runs
> **within one session** (`parity-tests/scenarios/COVERAGE.md` §2.1, with the trace digests written
> down). The **cross-occasion half is not established** for any of them — every gate invocation so
> far is from one boot on one day, and reproducibility here has a measured session-level component.
> Re-running `parityGate` on a separate occasion and appending the result to COVERAGE.md §2.1 is
> part of this sub-phase, not an optional extra.
- [ ] Record goldens with `-Dgolden.record=true` against `develop`; commit.
- [ ] Tag the repo (`pre-migration-baseline`) — the exact commit goldens were recorded from.
- [ ] CI job `characterizationIT@opencybele` runs the full suite on `develop` on every push; must stay green for the rest of the project (guards against accidental behavior drift in the "frozen" baseline).

**Exit criteria 1-PRE:** all scenarios green and flake-free on `develop`; coverage checklist complete; harness consumable from another branch/module.

---

## Sub-phase 1-PORT — the port (summary; details in decision doc §mapping table)

- [ ] Per agent: Cybele activity → behaviour (`PeriodicActivity`→`TickerBehaviour`, one-shot→`OneShotBehaviour`, message handler→`CyclicBehaviour`+`MessageTemplate`), messages → `ACLMessage`, directory/community → DF/AMS, config → container/boot code.
- [ ] **Extract domain logic into plain classes** while porting (pricing, counting, decision rules) — this is what makes 1-POST unit tests cheap and lets Phase 2 reuse the same logic classes.
- [ ] Port order: simplest agent first, run its scenarios, then next agent (agent-by-agent, parity-checked increments).
- [ ] Add adapter #2 to the harness: `JadeLauncher` (boots main container + agents from scenario config, same probe agent concept re-implemented as a JADE agent emitting the identical canonical trace format).

---

## Sub-phase 1-POST — extend tests AFTER the port (on JADE)

Goal: white-box confidence inside the new implementation + black-box proof of equivalence.

### 1-POST.1 Unit layer (L1, `test` source set — T§4.1)
- [ ] Unit tests for every extracted plain-Java domain class (framework-free).
- [ ] Behaviour-as-POJO tests for each behaviour: call `action()`/`onTick()` with mocked `Agent`, capture `send(...)` with ArgumentCaptor, assert performative + content.
- [ ] Target: every behaviour has ≥1 happy-path and ≥1 boundary test; runs <1s each, parallel.

### 1-POST.2 Integration layer (L2, `integrationTest` source set — T§4.2)
- [ ] In-process container tests per interaction pair: boot main container (GUI off, random port), start agent under test + probe agent, inject stimulus, `poll(timeout)` assertions.
- [ ] Sequential execution (`maxParallelForks=1` or `forkEvery=1`) — JADE Runtime is a JVM singleton.
- [ ] One lifecycle test: agents register/deregister with DF as expected (if DF is used).

### 1-POST.3 Parity run (L3)
- [ ] Run the full 1-PRE scenario suite via `JadeLauncher` against the same goldens.
- [ ] Every diff triaged: (a) port bug → fix port; (b) normalizer gap (e.g., JADE-specific ids leaking) → fix normalizer, re-verify it still passes on `develop`; (c) never "accept new behavior" — features are frozen.
- [ ] CI: `characterizationIT@jade` job, same suite, must be green.

### 1-POST.4 Wrap-up artifacts
- [ ] `jade/README.md`: mapping notes (which activity became which behaviour), known non-observable differences (threading, internal timing) explicitly listed as out-of-contract.
- [ ] Short comparison log for the experiment: LOC, port effort notes, anything that didn't map cleanly (feeds Phase 2 planning).

**Exit criteria Phase 1 (Definition of Done):**
1. `characterizationIT` green on **both** `develop` and `jade` against identical goldens, flake-free (≥10 runs).
2. L1 + L2 suites green in CI on `jade`.
3. Coverage checklist shows every inventory row exercised by L3 and every behaviour covered by L1.
4. Comparison log written. Harness untouched by JADE-specific hacks (anything JADE-specific lives in the `JadeLauncher` adapter only).
