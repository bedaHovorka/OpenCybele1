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
- [ ] Make timer periods configurable (shortened in tests); bounded stop condition per scenario (max ticks / max messages / timeout). *Timer periods and the rest of the parameter surface: done in [#18](https://github.com/bedaHovorka/OpenCybele1/issues/18), see [`scenario-config.md`](scenario-config.md). Stop condition: still open, [#17](https://github.com/bedaHovorka/OpenCybele1/issues/17).*
- [ ] If message traffic is not observable in logs: add the **probe agent** (T§3.3) — additive only (new agent + config entry), emitting canonical lines `agent|tick|event|from|to|performative|payload`.
- [ ] Re-run each scenario ≥5×; iterate on `TraceNormalizer` (strip TS/thread/ids, sort within tick) until byte-identical output. **Gate:** zero flake across ≥10 consecutive runs per scenario.

### 1-PRE.3 Scenario coverage extension
Extend scenarios until every row of the Stage-0 inventory is exercised at least once:
- [x] One scenario per agent type in isolation (where meaningful) — `opencybele-lifecycle`; see COVERAGE.md §4 for why true isolation is not a configuration this system has.
- [x] One scenario per message type / interaction pair — all fifteen channels appear in all five scenarios, asserted by `OpenCybeleLifecycleIT` rather than claimed.
- [x] One scenario per timer/periodic activity (assert tick counts over a bounded window, not durations — T§7) — `opencybele-timers`, which asserts the firing counts of all four sites as equalities. It does **not** pin DEF-16: a clamping port passes the whole suite, and COVERAGE.md §10.9 hands that defect to #28 with the measurement.
- [x] Startup/shutdown scenario (agent lifecycle order, initial messages) — `opencybele-lifecycle`, including the asymmetry that only `Train` ever dies.
- [x] At least one "full system" scenario combining everything — `opencybele-strict`, the shipped topology unchanged.
- [x] Edge behaviors worth pinning — only if the current system has defined observable behavior for them. Of the three named in #23, **two are covered**: a road occupied in the opposing direction (`opencybele-congestion`) and a station at capacity (`opencybele-capacity`, added in revision after the first claim that this branch was unreachable was falsified — COVERAGE.md §10.1 keeps the retraction). The third, origin-equals-destination, is **rejected by `ScenarioConfig` at startup** and its code path is taken by every arrival instead; COVERAGE.md §10.3.
- [x] **Coverage checklist committed: [`parity-tests/scenarios/COVERAGE.md`](../parity-tests/scenarios/COVERAGE.md)** — inventory row → scenario id, with a written justification, backed by a measurement, for every unmapped row. It also carries the gate and mutation results per scenario, and the two bound-margin rules a future scenario author needs (§12).

### 1-PRE.4 Record & freeze goldens
> **Handed over by 1-PRE.3:** the five scenarios are byte-reproducible across 10 consecutive runs
> **within one session** (`parity-tests/scenarios/COVERAGE.md` §2.1, with the trace digests written
> down). The **cross-occasion half is not established** for any of them — every gate invocation so
> far is from one boot on one day, and reproducibility here has a measured session-level component.
> Re-running `parityGate` on a separate occasion and appending the result to COVERAGE.md §2.1 is
> part of this sub-phase, not an optional extra.
- [x] Record goldens **against `opencybele-baseline`** — *not* `develop`, which the issue text and
  an earlier revision of this line both said. `develop` is the untouched 2008 original and carries
  none of #15/#16/#17/#18/#19/#20; the recordable application is `opencybele-baseline`, driven from
  the harness on `jade-develop` via `-Popencybele.dist=`. The five goldens were recorded
  incrementally by #13, #21 and #23 as each scenario was gated; #24 **verified** that every one of
  them is exactly what the current baseline produces (50 gate runs, 5 × 10, all `strict`, zero
  divergence) rather than re-recording them, which L7 would have forbidden.
- [x] Tag the repo (`pre-migration-baseline`) — at **`f4c233c` on `opencybele-baseline`**, the
  application commit the goldens are a measurement of. The goldens themselves live on `jade-develop`,
  so the tag alone identifies only one half of the pair; the other half is recorded in the manifest.
  *(Created and verified locally by #24; pushing it is the maintainer's call.)*
- [x] **Recording environment captured**: [`parity-tests/golden/MANIFEST.md`](../parity-tests/golden/MANIFEST.md)
  — seed, JDK build, Gradle version, the full resolved twenty-key `sim.*` configuration per scenario,
  the #16 Cybele event-queue and thread-pool settings, the `-ea` decision from #22 (**on**, with an
  `AssertionError` aborting the recording), each golden's line count, digest, departure/entity-id
  set, expected final tick, and the recording occasion (boot id and timestamp).
- [ ] **Cross-occasion re-run: still open.** #24 re-ran `parityGate` (COVERAGE.md §2.1.1) and all five
  digests matched, but on the same boot minutes after #23's run — so it does not qualify as a separate
  occasion. `MANIFEST.md` §11 records what would, and the ledger now survives a clean build.
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
- [x] Run the full 1-PRE scenario suite via `JadeLauncher` against the same goldens. **Two of five
  reproduce byte-for-byte, 20 runs out of 20** (`lifecycle`, `timers`); `capacity` is 6/20,
  `congestion` 3/20 and `strict` 0/20, and every one of their diffs is a pure reordering of an
  identical multiset at an identical length.
- [x] Every diff triaged: **[`parity-triage-jade.md`](parity-triage-jade.md)**. Nothing resolved as
  (c); no golden re-recorded, no contract level demoted, no width changed.
- [ ] **`strict`, `congestion` and `capacity` are red and stay red.** The residual is classified
  (b) and is not closable: a golden is a fixed point of the normalizer at every width, so
  `burst-order`'s width re-segments the run only, and the comparison therefore asks the port to
  reproduce the baseline's *message latency* rather than its behaviour. Seven candidate repairs were
  measured and rejected (`parity-triage-jade.md` §6). The three scenarios' margins against the
  segmentation width — 0, 9 and 3 ms — are 2-PRE work.
- [ ] CI: `characterizationIT@jade` job, same suite, must be green.

### 1-POST.4 Wrap-up artifacts
- [x] [`jade/README.md`](../jade/README.md): mapping notes (which activity became which behaviour),
  known non-observable differences explicitly listed as out-of-contract — §6 lists threading model,
  internal scheduling, conversation ids, container topology, the JICP listener that binds a socket
  where Cybele bound none, and the `TRAVEL_LEFT`/`TRAVEL_RIGHT` label, which is pinned by no golden
  and cannot be. It also records where this document's own companion mapping table was wrong: **five
  of `CYBELLE_TO_JADE.md`'s six behaviour-class rows have zero instances in the finished port.**
- [x] Comparison log: [`comparison-log-phase1.md`](comparison-log-phase1.md) — LOC (implementation
  vs test, and what the number does not include), per-agent effort, what did not map cleanly,
  runtime observations, the JADE distribution decision with both of #26's retractions, and §9, the
  numbers that disagree with each other.

**Exit criteria Phase 1 (Definition of Done):**
1. `characterizationIT` green on **both** `develop` and `jade` against identical goldens, flake-free (≥10 runs).
2. L1 + L2 suites green in CI on `jade`.
3. Coverage checklist shows every inventory row exercised by L3 and every behaviour covered by L1.
4. Comparison log written. Harness untouched by JADE-specific hacks (anything JADE-specific lives in the `JadeLauncher` adapter only).

### The verdict, %s

Full working: [`comparison-log-phase1.md`](comparison-log-phase1.md) §8.

| | criterion | verdict |
|---|---|---|
| 1 | goldens green on both, flake-free | **NOT MET** |
| 2 | L1 + L2 green in CI on `jade` | **MET** — CI run `32550380188`, four jobs, 321 + 17 tests |
| 3 | coverage checklist complete | **MET for L1; partially met for L3** — three rows are exercised by no golden, all three measured and assigned before this ticket |
| 4 | comparison log written; harness un-hacked | **MET** — and the second half is asserted by a test, not a convention |

**Criterion 1 is not met, and no work on the port can meet it.** `strict`, `congestion` and
`capacity` are red; the residual is classified **(b)** on evidence and is not closable, because a
golden is a fixed point of the normalizer at every width, so `burst-order`'s width re-segments the
**run only** and the comparison therefore asks the port to reproduce the baseline's *message
latency* rather than its behaviour. `strict` needs a width ≥ 227 and `congestion` one < 214 —
mutually exclusive. Seven candidate repairs were implemented and measured before being rejected. The
margins against the 220 ms width rank the pass rates exactly on both implementations (118 ms →
20/20, 3 ms → 6/20, 9 ms → 3/20, 0 ms → 0/20), and **under concurrent load the reference
implementation fails its own `strict` golden in 3 runs of 20, at the same line and in the same
direction.** No golden was re-recorded, no contract level demoted, no width changed, no check
relaxed; `git diff origin/jade-develop -- parity-tests/golden` is 0 lines.

**The strongest honest claim Phase 1 supports** is therefore not "the goldens match" but: *the JADE
run emits the same event multiset as the 2008 application in all five scenarios, every remaining
difference is burst placement, and two of the five reproduce byte for byte 20 times out of 20.*

**Two carve-outs travel with that sentence** and it must not be quoted without them
([`comparison-log-phase1.md`](comparison-log-phase1.md) §8.1):

* **T-07** — `strict`'s `VOTE.diff` for `(vl8, stH)` straddles the normalizer's own 1 000 ms
  quantiser bucket in 10 runs of 20. One line, one payload field, a declared cost of the quantiser,
  and the two sides cancel; the CI judge's `QUANTUM` class exists for exactly it.
* **T-12** — the only genuine multiset difference this port ever produced: five consecutive runs of
  one `capacity` corpus differed by 14–17 lines because a slow JVM start shifted the whole schedule
  against an *absolute* `sim.stop.maxClockMs`. Inter-departure offsets were identical to the
  millisecond, so the schedule was shifted rather than recomputed, and it did not reproduce (a
  second corpus of twenty was all 20 multiset-identical). It is a gap in `COVERAGE.md` §12.1's
  bound-margin rule, which measures from *last train creation* and models nothing about how late a
  run starts — 2-PRE work.
