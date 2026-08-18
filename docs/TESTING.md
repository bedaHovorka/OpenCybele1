# TESTING.md — Regression & Migration Test Strategy (OpenCybele → JADE/Jason)

> **Upstream verification note — verified 2026-08-18.** Versions observed:
> Jason `io.github.jason-lang:jason-interpreter` **3.3.0** (latest release per Maven Central
> [`maven-metadata.xml`](https://repo1.maven.org/maven2/io/github/jason-lang/jason-interpreter/maven-metadata.xml);
> `3.3.3` is the undated in-development heading of `release-notes.adoc` on `main`, not a release),
> Jason prose docs from branch `main`; JADE `net.sf.ingenias:jade:4.3` = **JADE 4.3.3 rev 6726 of
> 2014/12/09**; JDK **OpenJDK 21.0.11**. Full note and reachability caveats:
> [`CYBELLE_TO_JADE.md`](CYBELLE_TO_JADE.md#upstream-verification).

Companion to `MIGRATION.md` and the JADE-first decision doc. Covers: (A) how JUnit works with JADE and what native support exists, (B) black-box characterization tests on the existing OpenCybele solution, (C) white-box/unit tests on JADE and on Jason.

---

## 1. Does JADE support JUnit? Short answer

**JADE core has no first-class JUnit integration.** There are three practical layers:

1. **The official JADE Test Suite add-on.** A dedicated testing framework built by the JADE team (and used by them to test JADE itself). It was *deliberately* built from scratch instead of on JUnit: the team's documented rationale is that JUnit-style frameworks stimulate objects by calling their methods, while agents expose no methods — you can only send them messages and they decide when/how to react. Structure: a `TesterAgent` runs a `TestGroup` composed of atomic `Test` classes; runnable from a GUI or in batch. Docs: https://jade-project.gitlab.io/docs/add-on/JADE_TestSuite.pdf . A real-world example combining it with Ant + JaCoCo coverage: https://github.com/bzafiris/jade-test-suite (Test Suite Framework v1.13 over JADE 4.4.0).
   - Verdict: good for *functional* test groups of a whole platform, but it is its own runner (not JUnit), aging, and awkward in modern Gradle/CI pipelines.

2. **The de-facto standard: boot JADE in-process inside JUnit.** JADE is just a library — you can start a main container inside a JUnit 5 test JVM, install your agents plus a *probe agent*, exchange ACL messages, and assert on what the probe observed. This is what most teams do today and what I recommend for your white-box integration layer (§4.2).

3. **Plain JUnit on your own classes.** JADE `Behaviour`s are POJOs: `action()`, `onTick()`, `handle*()` can be invoked directly in a unit test with a mocked `Agent`. Domain logic extracted into plain classes needs no JADE at all (§4.1).

For **Jason** there is exactly **one** official mechanism, and it is AgentSpeak-native, not JUnit: **tester agents** that include `$jasonJar/test/jason/inc/tester_agent.asl`, whose plan labels carry the `@[test]` annotation and whose assertions come from `test_assert.asl` (`!assert_equals`, `!assert_true`, `!assert_false`). ⚠️ **There is no `jason.asunit` package in Jason 3.3.0** — this document previously claimed there was; the evidence and the correction are in §5. Source: <https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/unit-tests.adoc>. Details in §5.

---

## 2. Overall test architecture for the migration

Three layers, reused across all migration stages:

| Layer | Purpose | OpenCybele (now) | JADE (if that path) | Jason (target) |
|---|---|---|---|---|
| **L1 Unit (white-box)** | Logic in isolation, ms-fast | Extracted plain-Java domain classes | `Behaviour.action()` directly + Mockito | **`@[test]` tester agents** (the only Jason-native mechanism — §5.1); internal actions & `Environment` as plain JUnit |
| **L2 Component/integration (white-box)** | One or few agents on a real runtime | Hard (Cybele kernel) — mostly skip | In-process container + probe agents | Small `.mas2j` launched in test; execution mode chosen on the `infrastructure:` line (§5.4) |
| **L3 System (black-box)** | Whole MAS, observable behavior only | **Characterization / golden-master suite (§3)** | Same scenario suite re-run | Same scenario suite re-run |

The L3 suite is the **parity harness**: written once against OpenCybele, it becomes the acceptance criterion for the port. L1/L2 are written fresh per target framework.

---

## 3. Part 1 — Black-box regression tests on the OpenCybele solution

You are writing **characterization tests** (a.k.a. Golden Master): they don't assert what the system *should* do, they pin down what it *currently does*, so any behavioral change during migration is detected.

### 3.1 Principles
- **Do not refactor the Cybele code first.** Wrap it as-is; the tests must capture today's behavior including quirks.
- **Test only observable boundaries:** stdout/log output, files/DB written, messages that cross an observable boundary, final agent state if it is dumped anywhere.
- **Make runs deterministic before recording goldens:** fix random seeds, replace wall-clock timers with configurable/shortened periods if the code allows, run a bounded scenario (N ticks / M messages / fixed duration then shutdown).

### 3.2 Harness pattern (JUnit 5 + ProcessBuilder)

Launch the whole OpenCybele application as a child JVM, capture output, normalize, compare to a stored golden file:

```java
class OpenCybeleCharacterizationIT {

    @ParameterizedTest
    @ValueSource(strings = {"scenario-ping-pong", "scenario-producer-3-consumers"})
    void scenarioMatchesGolden(String scenario) throws Exception {
        Process p = new ProcessBuilder(
                "java", "-cp", "libs/*:build/classes",
                "app.Main", "config/" + scenario + ".def",
                "--seed=42", "--maxTicks=200")
            .redirectErrorStream(true)
            .start();

        String raw = new String(p.getInputStream().readAllBytes(), UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "run timed out");

        String actual   = TraceNormalizer.normalize(raw);
        Path golden     = Path.of("src/test/resources/golden", scenario + ".txt");
        // First run: record.  Later runs: compare.
        if (Boolean.getBoolean("golden.record")) Files.writeString(golden, actual);
        assertEquals(Files.readString(golden), actual);
    }
}
```

`TraceNormalizer` is the critical piece — it must strip everything nondeterministic:
- timestamps → `<TS>`; thread names/ids → `<THREAD>`; message ids/UUIDs → `<ID>`; absolute paths, ports, hostnames;
- optionally **sort or bucket interleaved lines** (e.g. group by agent name, or sort within a tick) because concurrent agents interleave output differently per run. A robust trick: have each log line carry `agent|tick|event|payload`, then sort lines with equal tick.

Libraries that help: **ApprovalTests-java** (golden-file management with diff-on-failure), **Awaitility** (if you assert on files/DB appearing instead of process exit), plain JUnit 5 otherwise.

### 3.3 If pure log capture isn't enough: probe agent inside the Cybele community
If the interesting behavior is *messages between agents* and it isn't logged, add one **observer/probe agent** to the Cybele configuration (a minimal agent whose only activity is: on every message it receives — or every broadcast/topic it can subscribe to — append a canonical line `from|to|performative|content` to a trace file). This is a small, additive change to the legacy system (new agent + config entry, no edits to existing agents), and the same probe concept ports 1:1 to JADE and Jason later, so traces stay comparable across stages.

### 3.4 Scenario specs as data
Keep each scenario as a data file (YAML/properties): initial config, stimuli (what the driver injects and when), stop condition, and the golden trace name. The same specs are replayed against the JADE/Jason implementation — only the launcher command changes. That's the whole parity harness.

---

## 4. Part 2a — White-box tests on JADE

### 4.1 True unit tests: behaviours as POJOs (no container)

`Behaviour` subclasses are ordinary objects. Test them directly; mock the owning `Agent` where the behaviour calls `myAgent.send(...)` / `receive(...)`:

```java
class PriceUpdateBehaviourTest {

    @Test
    void repliesWithProposeOnCfp() {
        Agent agent = mock(Agent.class);
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        cfp.setContent("book(dune)");
        cfp.setSender(new AID("buyer", AID.ISLOCALNAME));
        when(agent.receive(any(MessageTemplate.class))).thenReturn(cfp, (ACLMessage) null);

        PriceUpdateBehaviour b = new PriceUpdateBehaviour(new Catalog(Map.of("dune", 30)));
        b.setAgent(agent);
        b.action();

        ArgumentCaptor<ACLMessage> sent = ArgumentCaptor.forClass(ACLMessage.class);
        verify(agent).send(sent.capture());
        assertEquals(ACLMessage.PROPOSE, sent.getValue().getPerformative());
        assertEquals("30", sent.getValue().getContent());
    }
}
```

Guidelines:
- **Extract domain logic out of behaviours** into plain classes (`Catalog`, `PricingPolicy`, …) — those get classic unit tests with zero JADE imports. Keep behaviours as thin glue.
- `OneShotBehaviour`/`CyclicBehaviour`: call `action()` yourself. `TickerBehaviour`: either call the protected `onTick()` via a test subclass that exposes it, or test the class you delegate to.
- Mockito can mock `Agent`; don't mock `ACLMessage`/`MessageTemplate` (cheap real objects).
- This is where most of your migrated OpenCybele activity logic should be verified — same tests survive a later JADE→Jason move if the logic lives in plain classes.

### 4.2 Integration tests: in-process JADE platform + probe agent

```java
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SellerAgentIT {
    private AgentContainer container;

    @BeforeAll void boot() throws Exception {
        Profile p = new ProfileImpl(null, 1200 + new Random().nextInt(5000), null);
        p.setParameter(Profile.GUI, "false");
        container = jade.core.Runtime.instance().createMainContainer(p);
        container.createNewAgent("seller", "app.SellerAgent",
                new Object[]{"dune=30"}).start();
    }

    @Test void sellerProposesPriceOnCfp() throws Exception {
        BlockingQueue<ACLMessage> inbox = new LinkedBlockingQueue<>();
        // Probe: forwards everything it receives into the queue
        container.acceptNewAgent("probe", new Agent() {
            @Override protected void setup() {
                addBehaviour(new CyclicBehaviour() {
                    @Override public void action() {
                        ACLMessage m = receive();
                        if (m != null) inbox.add(m); else block();
                    }});
                ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
                cfp.addReceiver(new AID("seller", AID.ISLOCALNAME));
                cfp.setContent("book(dune)");
                send(cfp);
            }}).start();

        ACLMessage reply = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(reply, "no reply within 5s");
        assertEquals(ACLMessage.PROPOSE, reply.getPerformative());
        assertEquals("30", reply.getContent());
    }

    @AfterAll void shutdown() throws Exception {
        container.kill();
    }
}
```

Practical notes:
- **`jade.core.Runtime` is a JVM-wide singleton** — run these tests sequentially (Gradle: `maxParallelForks = 1` for the integration suite, or one container per test-class with unique local port and `Profile.LOCAL_PORT`).
- Everything is **asynchronous**: never `Thread.sleep`-and-hope; use bounded `poll(timeout)` / `blockingReceive(template, timeout)` / Awaitility.
- Other test↔agent channels besides a probe: the **O2A channel** (`setEnabledO2ACommunication(true)`, `AgentController.putO2AObject(...)`) to inject objects into an agent, and **`jade.wrapper.gateway.JadeGateway`** for synchronous request/reply from non-agent test code.
- Separate Gradle source set: `test` (unit, parallel, fast) vs `integrationTest` (container-based, sequential).

### 4.3 When to use the JADE Test Suite add-on
Only if you build a large long-lived JADE layer and want protocol-level functional groups with its GUI/batch runner. For a migration way-station, layers 4.1 + 4.2 in JUnit are usually sufficient and CI-friendlier.

---

## 5. Part 2b — White-box tests directly on Jason

> **⚠️ This section was substantially wrong and has been rewritten (issue [#10](https://github.com/bedaHovorka/OpenCybele1/issues/10)).**
> It previously claimed Jason "ships JUnit support out of the box via the `jason.asunit` package"
> and showed a `jason.asunit.TestAgent` snippet. **`jason.asunit` does not exist in Jason 3.3.0**,
> and the snippet has been deleted rather than hedged. Evidence, all gathered 2026-08-18:
>
> | Check | Result |
> |---|---|
> | Published Javadoc package list <https://jason-lang.github.io/api/element-list> | 24 packages, `jason` … `jason.util`. **No `jason.asunit`.** |
> | `unzip -l jason-interpreter-3.3.0.jar \| grep -i asunit` | **0 matches** |
> | Same for `jason-interpreter-3.2.1.jar` and `3.2.0.jar` | **0 matches each** |
> | Same for the older coordinate `net.sf.jason:jason:2.3` | **0 matches** |
> | GitHub tree `src/test/java/test` at tags `main` / `v3.3.0` | **404 — the directory is gone** |
> | GitHub tree `src/test/java/jason/asunit` at tag `v2.6` | **Exists**: `TestAgent.java`, `TestArch.java`, `Condition.java`, `print.java` |
>
> So the package was real in Jason 2.x — which is where the old text came from — but it lived under
> **`src/test/java/`**, i.e. it was part of Jason's *own test sources* and was **never published in any
> Jason artifact on Maven Central** (checked: 2.3, 3.2.0, 3.2.1, 3.3.0). A downstream project could not
> have depended on it even in 2.x. In 3.x it is gone from the repository entirely.
> **The `@[test]` tester-agent path is the only one.**

The single official mechanism, per
<https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/unit-tests.adoc> (read 2026-08-18).

### 5.1 AgentSpeak-native `@[test]` tester agents — the only official mechanism

Tests *are* agents. Upstream's four steps, verbatim in substance:

1. Create a tester agent in the folder `src/test/jason/asl`.
2. Make it a tester agent by including the skeleton:
   ```
   { include("$jasonJar/test/jason/inc/tester_agent.asl") }
   ```
3. Include the agent under test: `{ include("station.asl") }`.
4. Run `./gradlew test`.

"All plans in which the label has the annotation `test` will be automatically launched by the test task."
Assertions come from `test_assert.asl`: `!assert_equals(Expected, Actual)`,
`!assert_equals(Expected, Actual, Tolerance)`, `!assert_true`, `!assert_false`.

```
{ include("$jasonJar/test/jason/inc/tester_agent.asl") }
{ include("bob.asl") }

@[test]
+!test_sum :
    true
    <-
    ?sum(1.3,2.6,R);
    !assert_equals(3.9,R,0.1);
.
```

Failure output (`./gradlew test`), for calibration:

```
[test_bob] assert_equals on event 'test_sum' starting at line 1 FAILED! Expected 3.9+/-0.1, but had 2.9000000000000004
[test_manager] #1 plans executed, #0 passed and #1 FAILED.
[test_manager] End of Jason unit tests: FAILED!
> Task :testJason FAILED
```

Upstream notes worth keeping: failures are **severe** messages and print with or without `--info`;
`--info` additionally shows `.print` output; the Jason test task is **not cacheable**, so it always
re-runs and needs no `clean`; a non-zero exit status is what tells CI a test failed.
Mocking is done by `.add_plan({ +!do_inc : … }, self, begin)` — a plan with the same functor pushed to
the **front** of the library shadows the real one.

### 5.2 Three things about this mechanism that are easy to get wrong

**(a) `src/test/jason/asl` is a *default*, not a fixed path.** Upstream calls it "the **default** test
folder for asl files". The directory is a **string argument** to `create_tester_agents(Dir, Pattern)`
in the test project's `.mas2j`, resolved together with that project's `aslSourcePath`. Verified by
extracting `test/jason/unit_tests_project.mas2j` from `jason-interpreter-3.3.0.jar`:

```
agents:
    test_manager [
        goals="create_tester_agents(\"./src/test/jason\",\".*.asl\")",
        beliefs="shutdown_delay(600)"
    ];

aslSourcePath:  "src/test/jason/inc"; "src/test/jason/asl"; "src/asl"; "src/agt";
                "inc"; "$jasonJar/test/jason/inc";
```

(The same jar's `unit_tests_2.mas2j` passes `"./asl"` instead, proving it is configurable.)
**We keep the default** — but as a choice, not because it is forced.

**(b) ⚠️ `:testJason` is a task in *Jason's own repository build*, not something a generated project
gets for free.** The `> Task :testJason` line in upstream's example output comes from running the test
engine inside the Jason repo. The Gradle template that `jason app add-gradle` writes into a user project
defines exactly three tasks — verified by extracting `templates/build.gradle` from
`jason-interpreter-3.3.0.jar`:

| Task in `templates/build.gradle` | Main class |
|---|---|
| `run` | `jason.infra.local.RunLocalMAS` |
| `runJade` | `jason.infra.jade.RunJadeMAS` |
| `shadowJar` | — |

There is **no `test` and no `testJason` task**. A user project must author the task itself *and* a
project-level `unit_tests.mas2j` that launches `test_manager` with `create_tester_agents(...)`.
Budget that work — it is [#44](https://github.com/bedaHovorka/OpenCybele1/issues/44), not a freebie.

**(c) Statistics constraints.** "The default test folder `src/test/jason/asl` should **only** have
tester agents, otherwise statistics will fail." Shared includes go in `src/test/jason/inc`, which is not
counted. "Two similar assertions done in the same plan will not be counted since they would have same
signatures" — so N identical `!assert_equals` calls in one plan register as one.

### 5.3 Library-only mode (no test engine)

If you want assertions without the management engine, include only the assertion library into any agent:

```
{ include("$jasonJar/test/jason/inc/test_assert.asl") }
```

"The results for assertions will be displayed as ordinary printed messages generated by the agent."
Useful for ad-hoc probing; it gives you no pass/fail exit status, so it is not a CI mechanism.

### 5.4 The Java parts stay ordinary JUnit
- **Internal actions** (`DefaultInternalAction.execute(...)`): construct with real `Term`s, call `execute`, assert on the `Unifier` — plain JUnit + no runtime needed.
- **Environment** (`executeAction`, `addPercept`): instantiate directly and unit test, or spin a minimal `.mas2j` for L2 tests.
- For L2 tests, pick an execution mode **on the `infrastructure:` line** — e.g. `Local(pool,1)` for a single worker thread. ⚠️ See [`MIGRATION.md`](MIGRATION.md) §9.5: there is no global sync/async switch, "asynchronous" is not the default, these forms are **`Local`-only**, and upstream makes **no determinism or reproducibility claim** about any of them. Determinism has to come from the harness (fixed seeds, simulated clock, normalizer), not from a keyword. Source: <https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/concurrency.adoc>.

---

## 6. Wiring it together (Gradle + CI)

```
test               → L1: behaviour/plan/internal-action unit tests (parallel, <1s each)
integrationTest    → L2: in-process JADE container / small .mas2j (sequential)
characterizationIT → L3: golden-master scenarios (first vs. OpenCybele build, later vs. port)
```

Migration workflow per agent: (1) L3 goldens green on OpenCybele baseline → (2) port agent, write L1 tests
for its logic → (3) L2 test for its messaging → (4) re-run L3 parity suite.

**⚠️ A golden diff is a port bug. There is no "accepted improvement" branch.**

> This paragraph previously read *"decide consciously whether it's a bug or an accepted improvement, and
> re-record the golden only in the latter case"*. That directly contradicted the phase scope guards, and
> since §6 is the doc an engineer actually reads for the per-agent workflow, the contradiction was live.
> It is resolved **in favour of the scope guard** ([#10](https://github.com/bedaHovorka/OpenCybele1/issues/10)).
> Binding text, unchanged, from the phase docs:
>
> - [`Phase1.md`](Phase1.md) L7 — *"logic / system / features / behaviour are NOT extended in any phase.
>   Every golden diff in this phase = bug in the port. Goldens are never re-recorded in Phase 1 except to
>   fix a defect in the harness itself (normalizer bug), and any such re-record must be reproduced and
>   verified against `develop` first."*
> - [`Phase1.md`](Phase1.md) L82 / [`Phase2.md`](Phase2.md) L71 triage rule — *"(c) never 'accept new
>   behavior' — features are frozen."*
> - [`Phase2.md`](Phase2.md) L7 — *"The only allowed golden change is a Phase-1-style normalizer fix,
>   re-verified against `develop` **and** `jade` before merging."*

The triage on a diff is therefore **two-way, not three-way**:

| Cause | Action |
|---|---|
| **(a) Port bug** | Fix the port. Golden untouched. |
| **(b) Normalizer / harness gap** (target-specific ids, cycle artifacts, interleaving the normalizer fails to bucket) | Fix the **normalizer**, then re-record — and re-verify the re-recorded golden against **every** baseline already ported (`develop`, and `jade` once it exists) before merging. |
| ~~(c) Accepted improvement~~ | **Does not exist.** If the port genuinely behaves better, that is still a diff from the frozen contract: leave it out of the port, or raise it as a separate post-migration issue. |

Note that (b) is not a loophole: it requires a demonstrated defect *in the harness*, reproduced on the
original baseline. "The new trace looks more correct" is case (a) until proven otherwise.

---

## 7. Pitfalls

- **Interleaving nondeterminism** is the #1 golden-test killer — invest in the normalizer/sorting before recording goldens, or your L3 suite will flake.
- **Timing-based assertions**: assert *ordering and counts*, not wall-clock durations; keep timer periods short and configurable in test config.
- **JADE singleton runtime** across tests → sequential integration suite or fresh JVM per class (`forkEvery = 1`).
- **Don't over-assert in characterization tests** (e.g., exact debug wording) — pin the boundary behavior, not incidental formatting, or every harmless change breaks goldens.
- **Do not plan around `jason.asunit`** — it does not exist in Jason 3.3.0 and was never published in any Jason Maven artifact (§5). Any design that assumes JUnit-driven AgentSpeak plan tests has to be re-thought around `@[test]` tester agents.
- **The Jason test task is not generated for you** — `jason app add-gradle` writes `run`, `runJade` and `shadowJar` only; the `test`/`testJason` wiring and a project-level `unit_tests.mas2j` are yours to author (§5.2b, [#44](https://github.com/bedaHovorka/OpenCybele1/issues/44)).
- **`src/test/jason/asl` must contain only tester agents**, or the test manager's statistics break; put shared includes in `src/test/jason/inc` (§5.2c).
- The **JADE Test Suite** docs/add-on are old (JADE ~4.x era); expect friction on modern JDKs — prefer the JUnit in-process pattern unless you specifically need it.

## References
- JADE Test Suite user guide: https://jade-project.gitlab.io/docs/add-on/JADE_TestSuite.pdf
- Example JADE test suite project (Ant + JaCoCo): https://github.com/bzafiris/jade-test-suite
- **Jason unit tests (authoritative)**: https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/unit-tests.adoc
- **Jason concurrency / execution modes**: https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/concurrency.adoc
- **Jason performatives**: https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/performatives.adoc
- **Jason release notes**: https://raw.githubusercontent.com/jason-lang/jason/main/doc/release-notes.adoc
- **Jason published Javadoc package list** (the `jason.asunit` disproof): https://jason-lang.github.io/api/element-list
- **Jason Maven Central metadata**: https://repo1.maven.org/maven2/io/github/jason-lang/jason-interpreter/maven-metadata.xml
- ~~Jason asunit usage in Jason's own tests~~ — **removed**: `src/test/java/test/asunit/` does not exist at `v3.3.0` or `main` (404), and `jason.asunit` was never published in a Jason artifact. See §5.
- Goal-Oriented TDD tutorial (JaCaMo docs; useful background, but `unit-tests.adoc` above is authoritative): https://jacamo-lang.github.io/jacamo/tutorials/tdd/readme.html
- **JADE Programmer's Guide** — `jade.tilab.com` was unreachable from the verifying machine (TLS chain); read from the mirror https://jade-project.gitlab.io/docs/programmersguide.pdf
- Jason site/docs: https://jason-lang.github.io/
