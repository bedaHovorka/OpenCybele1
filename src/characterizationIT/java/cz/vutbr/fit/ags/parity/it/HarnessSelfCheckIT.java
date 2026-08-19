package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
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
        assertFalse(Files.exists(parityRoot.resolve("golden").resolve("throwing.txt")),
                "a run that printed a throwable must not be recordable as a golden");
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
    }

    @Test
    @DisplayName("the harness kills a child that never ends, and fails")
    void harnessTimeoutIsNotAPass(@TempDir Path parityRoot) {
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        ScenarioSpec spec = spec("hanging", "strict", "hang", 8, 8, "", 2_000L);

        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(true, () -> runner.run(spec, new StubLauncher())));
        assertTrue(failure.getMessage().contains("HARNESS_TIMEOUT"), failure.getMessage());
    }

    @Test
    @DisplayName("a dead clock command channel latches the whole suite, not one scenario")
    void deadClockChannelLatchesTheSuite(@TempDir Path parityRoot) {
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        ScenarioSpec fatal = spec("clock-dead", "strict", "clock-dead", 8, 0, "");
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

    // --- helpers ------------------------------------------------------------------------------

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
