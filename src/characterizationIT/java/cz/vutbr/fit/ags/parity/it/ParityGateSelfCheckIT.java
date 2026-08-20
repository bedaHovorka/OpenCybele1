package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
import cz.vutbr.fit.ags.parity.run.ParityGate;
import cz.vutbr.fit.ags.parity.run.ScenarioFailedException;
import cz.vutbr.fit.ags.parity.run.ScenarioRunner;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the zero-flake gate can fail, and that it cannot be talked into a claim it has no evidence
 * for.
 *
 * <p>{@code ParityGateIT} runs the gate against the real application and is opt-in, so on an
 * ordinary build it is skipped — which would leave the gate itself completely untested. These run
 * against the stub, in milliseconds, on every build. The stub's {@code flaky} mode exists for the
 * first of them and for nothing else: a gate nobody has watched go red is a lock that cannot fail,
 * which is the defect class this project has rejected four times.
 */
class ParityGateSelfCheckIT {

    @Test
    @DisplayName("the gate goes RED when consecutive runs are not byte-identical")
    void divergentRunsFailTheGate(@TempDir Path parityRoot) {
        ParityLayout layout = new ParityLayout(parityRoot);
        ScenarioSpec spec = flakySpec();
        // Record from run 1, so the golden itself is one of the two shapes the stub alternates
        // between. Every later run comparing green would then be the flake going unnoticed.
        withRecordMode(true, () -> new ScenarioRunner(layout).run(spec, new StubLauncher()));

        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(false, () -> gate(layout, parityRoot)
                        .run(spec, new StubLauncher(), 4)));
        assertTrue(failure.getMessage().contains("zero-flake gate FAILED")
                        || failure.getMessage().contains("does not match its golden"),
                "the gate must report the divergence rather than average it away: "
                        + failure.getMessage());
    }

    @Test
    @DisplayName("a green gate says what it established AND what it did not")
    void greenGateReportsBothHalves(@TempDir Path parityRoot) {
        ParityLayout layout = new ParityLayout(parityRoot);
        ScenarioSpec spec = spec("gate-normal", "normal");
        withRecordMode(true, () -> new ScenarioRunner(layout).run(spec, new StubLauncher()));

        ParityGate.Result result = withRecordMode(false,
                () -> gate(layout, parityRoot).run(spec, new StubLauncher(), 3));
        assertTrue(result.withinSessionStable());

        String report = result.describe();
        assertTrue(report.contains("ESTABLISHED:"), report);
        assertTrue(report.contains("NOT ESTABLISHED:"),
                "a gate that prints only its green half is how '10 consecutive runs' came to be"
                        + " mistaken for reproducibility: " + report);
        assertTrue(report.contains("CROSS-OCCASION:"), report);
    }

    @Test
    @DisplayName("running the gate twice in one session buys no cross-occasion evidence")
    void loopingDoesNotManufactureEvidence(@TempDir Path parityRoot) {
        ParityLayout layout = new ParityLayout(parityRoot);
        ScenarioSpec spec = spec("gate-loop", "normal");
        withRecordMode(true, () -> new ScenarioRunner(layout).run(spec, new StubLauncher()));

        withRecordMode(false, () -> gate(layout, parityRoot).run(spec, new StubLauncher(), 2));
        ParityGate.Result second = withRecordMode(false,
                () -> gate(layout, parityRoot).run(spec, new StubLauncher(), 2));

        assertEquals(1, second.earlier().size(), "the first invocation must be in the ledger");
        assertTrue(second.crossOccasionEvidence().isEmpty(),
                "docs/defect-triage.md §8.2: 'a script that loops twice satisfies the letter while"
                        + " delivering exactly the worthless consecutive-run evidence'. Same boot,"
                        + " seconds apart, is not a separate occasion.");
        assertTrue(second.describe().contains("no qualifying earlier run"), second.describe());
    }

    @Test
    @DisplayName("an earlier occasion with a different entity-id set is surfaced, not averaged")
    void crossOccasionDisagreementIsSurfaced(@TempDir Path parityRoot) throws IOException {
        ParityLayout layout = new ParityLayout(parityRoot);
        ScenarioSpec spec = spec("gate-cross", "normal");
        withRecordMode(true, () -> new ScenarioRunner(layout).run(spec, new StubLauncher()));

        // A hand-written ledger line standing in for "the same scenario, a week ago, on a machine
        // that had rebooted since" — the shape docs/kernel-config.md measured and could not
        // explain, and the one thing consecutive runs structurally cannot see.
        Path ledger = parityRoot.resolve("ledger");
        Files.createDirectories(ledger);
        Files.writeString(ledger.resolve("gate-cross.tsv"),
                String.join("\t",
                        "00000000-dead-beef-0000-000000000000",
                        Instant.now().minus(7, ChronoUnit.DAYS).toString(),
                        "gate-cross",
                        "0000000000000000000000000000000000000000000000000000000000000000",
                        "42",
                        "vl1,vl2,vl3") + System.lineSeparator(),
                StandardCharsets.UTF_8);

        ParityGate.Result result = withRecordMode(false,
                () -> gate(layout, ledger).run(spec, new StubLauncher(), 2));

        assertTrue(result.withinSessionStable(), "this session is stable; that is not the question");
        assertEquals(1, result.crossOccasionEvidence().size(),
                "a different boot id a week ago is a separate occasion");
        assertEquals(1, result.crossOccasionDisagreements().size(),
                "and its entity-id set differs, which is precisely the comparison §8.2 asks for");
        assertTrue(result.describe().contains("DIFFERS"), result.describe());

        // The IT that people actually run turns that into a failure; assert the predicate here so
        // the engine and the assertion cannot drift apart.
        assertFalse(result.crossOccasionDisagreements().isEmpty());
        assertEquals(Set.of("vl1", "vl2", "vl3"),
                result.crossOccasionEvidence().get(0).entityIds());
    }

    @Test
    @DisplayName("a gate of one run is rejected rather than reported as green")
    void oneRunIsNotAGate(@TempDir Path parityRoot) {
        ParityLayout layout = new ParityLayout(parityRoot);
        assertThrows(IllegalArgumentException.class,
                () -> gate(layout, parityRoot).run(spec("gate-one", "normal"), new StubLauncher(), 1));
    }

    // --- helpers ---------------------------------------------------------------------------

    private static ParityGate gate(ParityLayout layout, Path ledger) {
        String previous = System.getProperty(ParityGate.LEDGER_PROPERTY);
        System.setProperty(ParityGate.LEDGER_PROPERTY, ledger.toString());
        try {
            return new ParityGate(layout);
        } finally {
            if (previous == null) {
                System.clearProperty(ParityGate.LEDGER_PROPERTY);
            } else {
                System.setProperty(ParityGate.LEDGER_PROPERTY, previous);
            }
        }
    }

    private static ScenarioSpec flakySpec() {
        // A fresh seed per @TempDir run, so the stub's counter file (which lives outside the
        // per-run scratch directory, deliberately) is this test's and nobody else's.
        return spec("gate-flaky-" + System.nanoTime(), "flaky");
    }

    private static ScenarioSpec spec(String id, String mode) {
        String yaml = """
                id: %s
                contract: strict
                launcher:
                  properties:
                    sim.random.masterSeed: "%d"
                    sim.stop.maxTrains: "6"
                    stub.mode: %s
                run:
                  harnessTimeoutMs: 60000
                  expect: bound-reached
                liveness:
                  - pattern: '^vl\\d+ started$'
                    atLeast: 6
                entity:
                  pattern: '^(vl\\d+) '
                """.formatted(id, Math.abs(id.hashCode()), mode);
        return ScenarioSpecParser.parse(yaml, "<inline:" + id + ">");
    }

    private static <T> T withRecordMode(boolean recording, Supplier<T> body) {
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
