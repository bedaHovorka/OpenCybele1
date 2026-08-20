package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
import cz.vutbr.fit.ags.parity.run.ErrorScanner;
import cz.vutbr.fit.ags.parity.run.RunReport;
import cz.vutbr.fit.ags.parity.run.ScenarioFailedException;
import cz.vutbr.fit.ags.parity.run.ScenarioRunner;
import cz.vutbr.fit.ags.parity.run.SuiteFatalError;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpecParser;
import cz.vutbr.fit.ags.parity.stub.StubLauncher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the harness can fail.
 *
 * <p>Every check the runner performs is exercised here against a stub run engineered to break it.
 * That is not decoration: this project has already rejected two behavioural "locks" that could not
 * fail, and a golden-master suite that cannot go red is worse than none, because it is believed.
 * Each test names the measured failure mode it stands for.
 */
class HarnessSelfCheckIT {

    // --- record / compare / corrupted golden ---------------------------------------------------

    @Test
    @DisplayName("record writes a golden, compare passes against it, a corrupted golden fails")
    void recordCompareAndDetectCorruption(@TempDir Path parityRoot) throws IOException {
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        ScenarioSpec spec = spec("strict-demo", "strict", "normal", 8, 8, "");

        RunReport recorded = withRecordMode(true, () -> runner.run(spec, new StubLauncher()));
        assertTrue(recorded.recorded(), "record mode must write rather than compare");
        Path golden = recorded.goldenFile();
        assertTrue(Files.exists(golden), "record mode must create " + golden);
        assertFalse(recorded.normalized().isEmpty());

        RunReport compared = withRecordMode(false, () -> runner.run(spec, new StubLauncher()));
        assertFalse(compared.recorded(), "compare mode must not write");
        assertEquals(recorded.normalized(), compared.normalized());

        List<String> corrupted = new ArrayList<>(Files.readAllLines(golden, StandardCharsets.UTF_8));
        corrupted.set(corrupted.size() - 1, corrupted.get(corrupted.size() - 1) + " (corrupted)");
        Files.write(golden, corrupted, StandardCharsets.UTF_8);

        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(false, () -> runner.run(spec, new StubLauncher())));
        assertTrue(failure.getMessage().contains("strict contract"),
                "the report must name the contract level that was violated: " + failure.getMessage());
    }

    // --- the three failure modes the exit status does not carry --------------------------------

    @Test
    @DisplayName("a throwable printed mid-run fails, although the process exits 0")
    void throwableIsCaughtDespiteCleanExit(@TempDir Path parityRoot) {
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        ScenarioSpec spec = spec("throwing", "strict", "assert-error", 8, 8, "");

        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(true, () -> runner.run(spec, new StubLauncher())));
        assertTrue(failure.getMessage().contains("-> BOUND_REACHED"),
                "the run really did exit 0 and looked healthy: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("AssertionError"));
        assertNoGolden(parityRoot, "throwing");
    }

    @Test
    @DisplayName("a silently dead simulation fails, although it exits 0 with nothing to grep for")
    void deadSimulationIsCaughtByLiveness(@TempDir Path parityRoot) {
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        ScenarioSpec spec = spec("dead", "strict", "silent-death", 8, 8, "");

        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(true, () -> runner.run(spec, new StubLauncher())));
        assertTrue(failure.getMessage().contains("liveness"), failure.getMessage());
        assertTrue(failure.getMessage().contains("-> BOUND_REACHED"), failure.getMessage());
        assertNoGolden(parityRoot, "dead");
    }

    @Test
    @DisplayName("a wall-clock timeout is not a pass")
    void wallClockTimeoutIsNotAPass(@TempDir Path parityRoot) {
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        ScenarioSpec spec = spec("timed-out", "strict", "wall-clock-timeout", 8, 8, "");

        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(true, () -> runner.run(spec, new StubLauncher())));
        assertTrue(failure.getMessage().contains("WALL_CLOCK_TIMEOUT"), failure.getMessage());
        assertTrue(failure.getMessage().contains("BOUND_REACHED"), failure.getMessage());
        assertNoGolden(parityRoot, "timed-out");
    }

    @Test
    @DisplayName("the harness kills a child that never ends, and fails")
    void harnessTimeoutIsNotAPass(@TempDir Path parityRoot) {
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        ScenarioSpec spec = spec("hanging", "strict", "hang", 8, 8, "", 2_000L);

        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(true, () -> runner.run(spec, new StubLauncher())));
        assertTrue(failure.getMessage().contains("HARNESS_TIMEOUT"), failure.getMessage());
        assertNoGolden(parityRoot, "hanging");
    }

    @Test
    @DisplayName("a dead clock command channel latches the whole suite, not one scenario")
    void deadClockChannelLatchesTheSuite(@TempDir Path parityRoot) {
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        ScenarioSpec fatal = spec("clock-dead", "strict", "clock-dead", 8, 1, "");
        ScenarioSpec healthy = spec("later", "strict", "normal", 8, 8, "");
        try {
            SuiteFatalError first = assertThrows(SuiteFatalError.class,
                    () -> withRecordMode(true, () -> runner.run(fatal, new StubLauncher())));
            assertTrue(first.getMessage().contains("Suite latched"), first.getMessage());

            SuiteFatalError later = assertThrows(SuiteFatalError.class,
                    () -> withRecordMode(true, () -> runner.run(healthy, new StubLauncher())));
            assertTrue(later.getMessage().contains("suite aborted before scenario 'later'"),
                    later.getMessage());
            assertTrue(ScenarioRunner.suiteFatalReason().isPresent());
        } finally {
            ScenarioRunner.resetSuiteLatch();
        }
    }

    // --- contract levels -----------------------------------------------------------------------

    @Test
    @DisplayName("causal compares per entity id and honours the declared tolerance")
    void causalContractHonoursTolerance(@TempDir Path parityRoot) {
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        String entityBlock = """
                entity:
                  pattern: '^(vl\\d+)\\b'
                  group: 1
                  tolerance:
                    missing: 2
                    extra: 0
                """;
        // Record with 12 trains, replay with 10: two ids simply never appear, exactly as a train
        // lost to the START race behaves. Strict could not survive it; causal is told how much
        // drift the scenario was measured to have.
        withRecordMode(true, () -> runner.run(
                spec("causal-demo", "causal", "normal", 12, 8, entityBlock), new StubLauncher()));

        RunReport tolerated = withRecordMode(false, () -> runner.run(
                spec("causal-demo", "causal", "normal", 10, 8, entityBlock), new StubLauncher()));
        assertFalse(tolerated.recorded());

        String tighter = entityBlock.replace("missing: 2", "missing: 1");
        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(false, () -> runner.run(
                        spec("causal-demo", "causal", "normal", 10, 8, tighter), new StubLauncher())));
        assertTrue(failure.getMessage().contains("causal contract"), failure.getMessage());
        assertTrue(failure.getMessage().contains("tolerance is 1"), failure.getMessage());
    }

    @Test
    @DisplayName("summary compares declared aggregates within their tolerances")
    void summaryContractComparesAggregates(@TempDir Path parityRoot) {
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        String tail = """
                entity:
                  pattern: '^(vl\\d+)\\b'
                  tolerance:
                    missing: 3
                    extra: 3
                summary:
                  - label: departures
                    pattern: '^vl\\d+ started$'
                    tolerance: 3
                """;
        withRecordMode(true, () -> runner.run(
                spec("summary-demo", "summary", "normal", 12, 8, tail), new StubLauncher()));

        withRecordMode(false, () -> runner.run(
                spec("summary-demo", "summary", "normal", 10, 8, tail), new StubLauncher()));

        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(false, () -> runner.run(
                        spec("summary-demo", "summary", "normal", 6, 6, tail), new StubLauncher())));
        assertTrue(failure.getMessage().contains("summary contract"), failure.getMessage());
    }

    // --- the spec format itself ------------------------------------------------------------------

    @Test
    @DisplayName("an unknown scenario key is rejected, not ignored")
    void unknownKeysAreRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ScenarioSpecParser.parse("""
                        id: x
                        contract: strict
                        maxTicks: 200
                        run:
                          harnessTimeoutMs: 1000
                          expect: bound-reached
                        liveness:
                          - pattern: 'a'
                            atLeast: 1
                        """, "<inline>"));
        assertTrue(failure.getMessage().contains("maxTicks"), failure.getMessage());
    }

    @Test
    @DisplayName("a timeout cannot be declared as a scenario's expected outcome")
    void timeoutCannotBeDeclared() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ScenarioSpecParser.parse("""
                        id: x
                        contract: strict
                        run:
                          harnessTimeoutMs: 1000
                          expect: wall-clock-timeout
                        liveness:
                          - pattern: 'a'
                            atLeast: 1
                        """, "<inline>"));
        assertTrue(failure.getMessage().contains("never a pass"), failure.getMessage());
    }

    @Test
    @DisplayName("a scenario without a liveness rule is rejected")
    void livenessIsMandatory() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ScenarioSpecParser.parse("""
                        id: x
                        contract: strict
                        run:
                          harnessTimeoutMs: 1000
                          expect: bound-reached
                        """, "<inline>"));
        assertTrue(failure.getMessage().contains("liveness"), failure.getMessage());
    }

    // --- the branch invariant ---------------------------------------------------------------------

    @Test
    @DisplayName("the harness does not reference any application class")
    void harnessHasNoApplicationImports() throws IOException {
        Path sourceRoot = Path.of("src/characterizationIT/java");
        assertTrue(Files.isDirectory(sourceRoot), "expected the harness source at " + sourceRoot);
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                for (String line : text.split("\n")) {
                    if (line.startsWith("import ") && line.contains("cz.vutbr.fit.ags.xhovor07")) {
                        offenders.add(file + ": " + line.trim());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "the harness must have no compile-time link to an implementation: " + offenders);
    }

    @Test
    @DisplayName("a swallowed NON-assertion throwable is caught in the kernel's own byte shape")
    void kernelSwallowedThrowableIsCaught(@TempDir Path parityRoot) {
        // The shape a real port bug produces: an NPE inside an agent handler, routed through
        // IAIAgentThread -> IAIExceptionHandler. The captured stream contains NEITHER
        // "AssertionError" NOR "Exception in thread \"", which is exactly why the first version of
        // the error scan passed this run and recorded it as a golden.
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        ScenarioSpec spec = spec("kernel-swallow", "strict", "kernel-swallow", 8, 8, "");

        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(true, () -> runner.run(spec, new StubLauncher())));
        assertTrue(failure.getMessage().contains("-> BOUND_REACHED"),
                "the run exited 0 and looked healthy: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("General Exception occured in"), failure.getMessage());
        assertNoGolden(parityRoot, "kernel-swallow");

        // And the load-bearing half, asserted on the lines rather than on the report: the very
        // bytes the kernel emits contain neither of the two signatures the first version scanned
        // for, yet are caught. Remove the kernel literals from ErrorScanner.SIGNATURES and this
        // goes red while every other test stays green.
        List<String> kernelLines = List.of(
                "",
                "***Thread Mgmt Exception -> cybele.exception.CybeleException:"
                        + " General Exception occured in enter of the class Station",
                "java.lang.NullPointerException: Cannot invoke \"java.util.List.size()\"",
                "\tat cz.vutbr.fit.ags.xhovor07.Station.enter(Station.java:118)");
        for (String line : kernelLines) {
            assertFalse(line.contains("AssertionError") || line.contains("Exception in thread \""),
                    "the kernel's shape must contain neither old signature: " + line);
        }
        assertFalse(ErrorScanner.scan(kernelLines, List.of()).clean(),
                "the kernel's swallowed-throwable shape must be caught by the scan");
    }

    @Test
    @DisplayName("exit 5 latches the suite even when the run also printed a throwable")
    void deadClockLatchesEvenWhenItAlsoThrew(@TempDir Path parityRoot) {
        // The overwhelmingly likely shape, and the one the latch exists for. The error scan used to
        // throw first, so this run failed as an ordinary scenario and every later scenario launched
        // into the same poisoned environment.
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        ScenarioSpec spec = ScenarioSpecParser.parse("""
                id: dead-clock-and-throwable
                contract: strict
                launcher:
                  properties:
                    sim.random.masterSeed: "20080415"
                    stub.mode: clock-dead
                    stub.throwable: "true"
                run:
                  harnessTimeoutMs: 60000
                  expect: bound-reached
                liveness:
                  - pattern: '^vl\\d+ started$'
                    atLeast: 1
                """, "<inline:dead-clock-and-throwable>");
        try {
            SuiteFatalError fatal = assertThrows(SuiteFatalError.class,
                    () -> withRecordMode(true, () -> runner.run(spec, new StubLauncher())));
            assertTrue(fatal.getMessage().contains("Suite latched"), fatal.getMessage());
            assertTrue(fatal.getMessage().contains("also printed a throwable"), fatal.getMessage());
            assertTrue(ScenarioRunner.suiteFatalReason().isPresent(),
                    "the latch must be set even though the scan found a throwable first");
        } finally {
            ScenarioRunner.resetSuiteLatch();
        }
    }

    @Test
    @DisplayName("distinctGroup counts distinct captures, not matches")
    void distinctGroupIsNotAMatchCount(@TempDir Path parityRoot) {
        // Without this, `return matches;` inside LivenessRule.count passes the whole suite: the only
        // other user is smoke-stub, whose 12 trains occupy 12 lines, so the two counts coincide.
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        String distinct = """
                liveness:
                  - pattern: '^(vl\\d+) started$'
                    distinctGroup: 1
                    atLeast: 5
                """;
        String plain = """
                liveness:
                  - pattern: '^(vl\\d+) started$'
                    atLeast: 5
                """;
        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(true, () -> runner.run(
                        duplicateSpec("distinct-demo", distinct), new StubLauncher())));
        assertTrue(failure.getMessage().contains("produced 1 distinct group(1) values"),
                failure.getMessage());

        // The same 12 lines, counted as matches instead: passes. The two rules disagree, which is
        // the whole point of the option.
        withRecordMode(true, () -> runner.run(duplicateSpec("plain-demo", plain), new StubLauncher()));
    }

    @Test
    @DisplayName("causal compares the unattributed lines unconditionally, outside the tolerance")
    void causalDoesNotSpendToleranceOnTheUnattributedBucket(@TempDir Path parityRoot) {
        // The startup block carries no entity id, so it lands in one bucket. Treating that bucket as
        // an entity let a replay that dropped the ENTIRE startup block pass at tolerance 1 — and all
        // of #21's startup-block work lands there.
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        String entityBlock = """
                entity:
                  pattern: '^(vl\\d+)\\b'
                  tolerance:
                    missing: 1
                    extra: 1
                """;
        withRecordMode(true, () -> runner.run(
                spec("startup-drop", "causal", "normal", 6, 6, entityBlock), new StubLauncher()));

        ScenarioSpec withoutStartup = ScenarioSpecParser.parse("""
                id: startup-drop
                contract: causal
                launcher:
                  properties:
                    sim.random.masterSeed: "20080415"
                    sim.stop.maxTrains: "6"
                    stub.mode: normal
                    stub.startup: "false"
                run:
                  harnessTimeoutMs: 60000
                  expect: bound-reached
                liveness:
                  - pattern: '^vl\\d+ started$'
                    atLeast: 6
                """ + entityBlock, "<inline:startup-drop>");

        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(false, () -> runner.run(withoutStartup, new StubLauncher())));
        assertTrue(failure.getMessage().contains("no entity id differ"), failure.getMessage());
    }

    @Test
    @DisplayName("an empty normalized trace is refused rather than recorded")
    void emptyNormalizedTraceIsRefused(@TempDir Path parityRoot) {
        // Reachable with the DEFAULT diagnostic prefixes on any target whose lines are indented:
        // "  " is one of them, so a fully indented stream normalizes to nothing, records a 0-byte
        // golden, and then strict-matches a completely different run.
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        ScenarioSpec spec = ScenarioSpecParser.parse("""
                id: all-indented
                contract: strict
                launcher:
                  properties:
                    sim.random.masterSeed: "20080415"
                    sim.stop.maxTrains: "6"
                    stub.mode: all-indented
                run:
                  harnessTimeoutMs: 60000
                  expect: bound-reached
                liveness:
                  - pattern: 'vl\\d+ started$'
                    atLeast: 6
                """, "<inline:all-indented>");

        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(true, () -> runner.run(spec, new StubLauncher())));
        assertTrue(failure.getMessage().contains("EMPTY"), failure.getMessage());
        assertTrue(failure.getMessage().contains("DiagnosticFilter"),
                "the report must name the normalizer that ate the trace: " + failure.getMessage());
        assertNoGolden(parityRoot, "all-indented");
    }

    @Test
    @DisplayName("a summary rule that matches nothing in the golden fails, rather than reading green")
    void summaryRuleMatchingNothingFails(@TempDir Path parityRoot) {
        // The sibling of the entity-pattern check above, and it was found the hard way: summary and
        // entity patterns match NORMALIZED lines while liveness patterns match RAW ones, so #21's
        // projection turned a working `at \d+` rule into one that counted 0 against 0 and passed
        // whatever the run did. Comparison is symmetric, so a zero-count rule is a lock that cannot
        // fail — the shape this file exists to rule out.
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        String rules = """
                entity:
                  pattern: '^(vl\\d+) '
                summary:
                  - label: departures
                    pattern: '^vl\\d+ started$'
                    tolerance: 0
                  - label: never-matches
                    pattern: '^vl\\d+ in st[A-H] at \\d+$'
                    tolerance: 0
                """;
        ScenarioSpec spec = spec("zero-count", "summary", "normal", 6, 6, rules);
        withRecordMode(true, () -> runner.run(spec, new StubLauncher()));

        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(false, () -> runner.run(spec, new StubLauncher())));
        assertTrue(failure.getMessage().contains("never-matches"), failure.getMessage());
        assertTrue(failure.getMessage().contains("matches NOTHING in the golden"), failure.getMessage());
        // And it must not fire on the rule that does match, or every summary scenario breaks.
        assertFalse(failure.getMessage().contains("'departures' (/"), failure.getMessage());
    }

    @Test
    @DisplayName("a causal entity pattern that matches no normalized line fails loudly")
    void entityPatternMatchingNothingFails(@TempDir Path parityRoot) {
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        String wrongEntity = """
                entity:
                  pattern: '^(zz\\d+)\\b'
                """;
        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(true, () -> runner.run(
                        spec("no-entity-match", "causal", "normal", 6, 6, wrongEntity), new StubLauncher())));
        assertTrue(failure.getMessage().contains("matched none of the"), failure.getMessage());
        assertNoGolden(parityRoot, "no-entity-match");
    }

    @Test
    @DisplayName("a truncated capture is refused rather than scanned and recorded")
    void truncatedCaptureIsRefused(@TempDir Path parityRoot) {
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        ScenarioSpec spec = ScenarioSpecParser.parse("""
                id: orphan
                contract: strict
                launcher:
                  properties:
                    sim.random.masterSeed: "20080415"
                    stub.mode: orphan
                run:
                  harnessTimeoutMs: 30000
                  expect: bound-reached
                liveness:
                  - pattern: '^vl\\d+ in st'
                    atLeast: 1
                """, "<inline:orphan>");

        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(true, () -> runner.run(spec, new StubLauncher())));
        assertTrue(failure.getMessage().contains("never reached EOF"), failure.getMessage());
        assertNoGolden(parityRoot, "orphan");
    }

    @Test
    @DisplayName("a liveness floor of zero is rejected")
    void zeroLivenessFloorIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ScenarioSpecParser.parse("""
                        id: x
                        contract: strict
                        run:
                          harnessTimeoutMs: 1000
                          expect: bound-reached
                        liveness:
                          - pattern: 'a'
                            atLeast: 0
                        """, "<inline>"));
        assertTrue(failure.getMessage().contains("at least 1"), failure.getMessage());
    }

    @Test
    @DisplayName("an over-broad allowErrorLines pattern is rejected")
    void broadErrorExemptionsAreRejected() {
        for (String broad : List.of("'.'", "'.*'", "'.*Assertion.*|.*'")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> ScenarioSpecParser.parse("""
                            id: x
                            contract: strict
                            run:
                              harnessTimeoutMs: 1000
                              expect: bound-reached
                            liveness:
                              - pattern: 'a'
                                atLeast: 1
                            allowErrorLines:
                              - %s
                            """.formatted(broad), "<inline>"),
                    "expected " + broad + " to be rejected");
            assertTrue(failure.getMessage().contains("ordinary output"), failure.getMessage());
        }
        // A narrow, anchored exemption is still allowed.
        ScenarioSpecParser.parse("""
                id: x
                contract: strict
                run:
                  harnessTimeoutMs: 1000
                  expect: bound-reached
                liveness:
                  - pattern: 'a'
                    atLeast: 1
                allowErrorLines:
                  - '^Exception in thread "main" java\\.lang\\.IllegalArgumentException: sim\\.'
                """, "<inline>");
    }

    @Test
    @DisplayName("an entity tolerance under a strict contract is rejected, even at zero")
    void toleranceUnderStrictIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ScenarioSpecParser.parse("""
                        id: x
                        contract: strict
                        run:
                          harnessTimeoutMs: 1000
                          expect: bound-reached
                        liveness:
                          - pattern: 'a'
                            atLeast: 1
                        entity:
                          pattern: '^(vl\\d+)'
                          tolerance:
                            missing: 0
                        """, "<inline>"));
        assertTrue(failure.getMessage().contains("all zero"), failure.getMessage());
    }

    @Test
    @DisplayName("a malformed node is a schema error, not a ClassCastException")
    void shapeErrorsStayInTheSchemaLayer() {
        IllegalArgumentException asList = assertThrows(IllegalArgumentException.class,
                () -> ScenarioSpecParser.parse("""
                        id: x
                        contract: strict
                        launcher: [a, b]
                        run:
                          harnessTimeoutMs: 1000
                          expect: bound-reached
                        liveness:
                          - pattern: 'a'
                            atLeast: 1
                        """, "<inline>"));
        assertTrue(asList.getMessage().contains("must be a mapping"), asList.getMessage());

        IllegalArgumentException nonStringKey = assertThrows(IllegalArgumentException.class,
                () -> ScenarioSpecParser.parse("""
                        id: x
                        contract: strict
                        1: two
                        run:
                          harnessTimeoutMs: 1000
                          expect: bound-reached
                        liveness:
                          - pattern: 'a'
                            atLeast: 1
                        """, "<inline>"));
        assertTrue(nonStringKey.getMessage().contains("unknown key"), nonStringKey.getMessage());
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Every check that rejects a run must also leave no golden behind; the order is the design. */
    private static void assertNoGolden(Path parityRoot, String id) {
        assertFalse(Files.exists(parityRoot.resolve("golden").resolve(id + ".txt")),
                "a run rejected by the harness must not be recordable as a golden (" + id + ".txt)");
    }

    private static ScenarioSpec duplicateSpec(String id, String livenessBlock) {
        return ScenarioSpecParser.parse("""
                id: %s
                contract: strict
                launcher:
                  properties:
                    sim.random.masterSeed: "20080415"
                    sim.stop.maxTrains: "12"
                    stub.mode: duplicate
                run:
                  harnessTimeoutMs: 60000
                  expect: bound-reached
                """.formatted(id) + livenessBlock, "<inline:" + id + ">");
    }

    private static ScenarioSpec spec(String id, String contract, String mode, int maxTrains,
            int livenessFloor, String extraYaml) {
        return spec(id, contract, mode, maxTrains, livenessFloor, extraYaml, 60_000L);
    }

    private static ScenarioSpec spec(String id, String contract, String mode, int maxTrains,
            int livenessFloor, String extraYaml, long harnessTimeoutMs) {
        String yaml = """
                id: %s
                contract: %s
                launcher:
                  properties:
                    sim.random.masterSeed: "20080415"
                    sim.stop.maxTrains: "%d"
                    stub.mode: %s
                run:
                  harnessTimeoutMs: %d
                  expect: bound-reached
                liveness:
                  - pattern: '^vl\\d+ started$'
                    atLeast: %d
                """.formatted(id, contract, maxTrains, mode, harnessTimeoutMs, livenessFloor)
                + extraYaml;
        return ScenarioSpecParser.parse(yaml, "<inline:" + id + ">");
    }

    private static <T> T withRecordMode(boolean recording, java.util.function.Supplier<T> body) {
        String previous = System.getProperty(GoldenStore.RECORD_PROPERTY);
        System.setProperty(GoldenStore.RECORD_PROPERTY, Boolean.toString(recording));
        try {
            return body.get();
        } finally {
            if (previous == null) {
                System.clearProperty(GoldenStore.RECORD_PROPERTY);
            } else {
                System.setProperty(GoldenStore.RECORD_PROPERTY, previous);
            }
        }
    }
}
