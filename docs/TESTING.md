# TESTING.md — Regression & Migration Test Strategy (OpenCybele → JADE/Jason)

Companion to `MIGRATION.md` and the JADE-first decision doc. Covers: (A) how JUnit works with JADE and what native support exists, (B) black-box characterization tests on the existing OpenCybele solution, (C) white-box/unit tests on JADE and on Jason.

---

## 1. Does JADE support JUnit? Short answer

**JADE core has no first-class JUnit integration.** There are three practical layers:

1. **The official JADE Test Suite add-on.** A dedicated testing framework built by the JADE team (and used by them to test JADE itself). It was *deliberately* built from scratch instead of on JUnit: the team's documented rationale is that JUnit-style frameworks stimulate objects by calling their methods, while agents expose no methods — you can only send them messages and they decide when/how to react. Structure: a `TesterAgent` runs a `TestGroup` composed of atomic `Test` classes; runnable from a GUI or in batch. Docs: https://jade-project.gitlab.io/docs/add-on/JADE_TestSuite.pdf . A real-world example combining it with Ant + JaCoCo coverage: https://github.com/bzafiris/jade-test-suite (Test Suite Framework v1.13 over JADE 4.4.0).
   - Verdict: good for *functional* test groups of a whole platform, but it is its own runner (not JUnit), aging, and awkward in modern Gradle/CI pipelines.

2. **The de-facto standard: boot JADE in-process inside JUnit.** JADE is just a library — you can start a main container inside a JUnit 5 test JVM, install your agents plus a *probe agent*, exchange ACL messages, and assert on what the probe observed. This is what most teams do today and what I recommend for your white-box integration layer (§4.2).

3. **Plain JUnit on your own classes.** JADE `Behaviour`s are POJOs: `action()`, `onTick()`, `handle*()` can be invoked directly in a unit test with a mocked `Agent`. Domain logic extracted into plain classes needs no JADE at all (§4.1).

For **Jason** the story is better: it ships JUnit support out of the box via the `jason.asunit` package (`TestAgent` designed to be driven from JUnit — used in Jason's own test suite, e.g. `src/test/java/test/asunit/TestNS.java` in the jason repo), plus an AgentSpeak-native test framework (test plans annotated `@[test]` with `!assert_true` / `!assert_false`, a bundled `tester_agent.asl`, run via the `test` command — see the Goal-Oriented TDD tutorial: https://jacamo-lang.github.io/jacamo/tutorials/tdd/readme.html). Details in §5.

---

## 2. Overall test architecture for the migration

Three layers, reused across all migration stages:

| Layer | Purpose | OpenCybele (now) | JADE (if that path) | Jason (target) |
|---|---|---|---|---|
| **L1 Unit (white-box)** | Logic in isolation, ms-fast | Extracted plain-Java domain classes | `Behaviour.action()` directly + Mockito | `jason.asunit.TestAgent` (JUnit) and/or `@[test]` ASL plans; internal actions & `Environment` as plain JUnit |
| **L2 Component/integration (white-box)** | One or few agents on a real runtime | Hard (Cybele kernel) — mostly skip | In-process container + probe agents | Small `.mas2j` launched in test, sync execution mode |
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

Two complementary mechanisms, both official:

### 5.1 JUnit-driven: `jason.asunit`
`jason.asunit.TestAgent` lets a JUnit test create an agent, feed it AgentSpeak code, fire goals/beliefs, and assert on results via the `jason.asunit.print` action (the pattern used in Jason's own repo tests, e.g. `test/asunit/TestNS.java`):

```java
public class ProducerPlanTest {
    TestAgent ag;

    @Before public void setup() {
        ag = new TestAgent("bob");
        ag.parseAScode(
            "count(0). " +
            "+!inc : count(N) <- N1 = N + 1; -+count(N1); " +
            "                    ?count(X); jason.asunit.print(X). ");
    }

    @Test(timeout = 2000) public void incrementsCounter() {
        ag.addGoal("inc");
        ag.assertPrint("1", 10);   // expect "1" within 10 reasoning cycles
    }
}
```

This is the sweet spot for **plan-level unit tests**: no environment, no other agents, runs inside plain Gradle `test`. (Check the asunit classes in your Jason version for the exact assert helpers — the package lives in the jason-interpreter sources.)

### 5.2 AgentSpeak-native: `@[test]` plans (Goal-Oriented TDD)
Tests are themselves agents: include the framework's `tester_agent.asl` plus the file under test, annotate test plans with `@[test]`, assert with `!assert_true/!assert_false` (and related assertions), run with the `test` command. Example shape (from the official GOTDD tutorial):

```
{ include("tester_agent.asl") }
{ include("producer.asl") }

@[test]
+!test_increment
   <- -+count(0);
      !inc;
      !assert_true(count(1)).
```

Use this for tests you want to keep *inside* the agent codebase and for TDD while writing new plans. (The tutorial lives under the JaCaMo docs but the mechanism is the Jason/JaCaMo tester-agent framework: https://jacamo-lang.github.io/jacamo/tutorials/tdd/readme.html .)

### 5.3 The Java parts stay ordinary JUnit
- **Internal actions** (`DefaultInternalAction.execute(...)`): construct with real `Term`s, call `execute`, assert on the `Unifier` — plain JUnit + no runtime needed.
- **Environment** (`executeAction`, `addPercept`): instantiate directly and unit test, or spin a minimal `.mas2j` for L2 tests.
- For deterministic multi-agent L2 tests, run the MAS in **synchronous execution mode** so all agents step together, then assert; switch back to asynchronous for production.

---

## 6. Wiring it together (Gradle + CI)

```
test               → L1: behaviour/plan/internal-action unit tests (parallel, <1s each)
integrationTest    → L2: in-process JADE container / small .mas2j (sequential)
characterizationIT → L3: golden-master scenarios (first vs. OpenCybele build, later vs. port)
```

Migration workflow per agent: (1) L3 goldens green on OpenCybele baseline → (2) port agent, write L1 tests for its logic → (3) L2 test for its messaging → (4) re-run L3 parity suite; a diff means behavior drifted — decide consciously whether it's a bug or an accepted improvement, and re-record the golden only in the latter case.

---

## 7. Pitfalls

- **Interleaving nondeterminism** is the #1 golden-test killer — invest in the normalizer/sorting before recording goldens, or your L3 suite will flake.
- **Timing-based assertions**: assert *ordering and counts*, not wall-clock durations; keep timer periods short and configurable in test config.
- **JADE singleton runtime** across tests → sequential integration suite or fresh JVM per class (`forkEvery = 1`).
- **Don't over-assert in characterization tests** (e.g., exact debug wording) — pin the boundary behavior, not incidental formatting, or every harmless change breaks goldens.
- **jason.asunit API surface is small and version-dependent** — skim the `jason.asunit` package of your Jason version before committing to helper names.
- The **JADE Test Suite** docs/add-on are old (JADE ~4.x era); expect friction on modern JDKs — prefer the JUnit in-process pattern unless you specifically need it.

## References
- JADE Test Suite user guide: https://jade-project.gitlab.io/docs/add-on/JADE_TestSuite.pdf
- Example JADE test suite project (Ant + JaCoCo): https://github.com/bzafiris/jade-test-suite
- Jason asunit usage in Jason's own tests: https://github.com/jason-lang/jason (see `src/test/java/test/asunit/`)
- Goal-Oriented TDD (`@[test]`, assertions, tester agent): https://jacamo-lang.github.io/jacamo/tutorials/tdd/readme.html
- Jason site/docs: https://jason-lang.github.io/
