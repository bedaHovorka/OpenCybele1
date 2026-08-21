package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.DiffAnatomy;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
import cz.vutbr.fit.ags.parity.normalize.CanonicalTraceNormalizer;
import cz.vutbr.fit.ags.parity.normalize.PassThroughNormalizer;
import cz.vutbr.fit.ags.parity.run.RunReport;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DiffAnatomy} answers the first question of a golden triage — <em>different things, or the
 * same things in a different order?</em> — and this proves it answers it both ways round and that
 * the runner actually prints it.
 *
 * <p>Written because #39 spent a measurement campaign deriving, by hand, two facts the failure
 * report could have stated: that a 74-line positional diff was a pure reordering, and that the
 * boundary that moved sat 7 ms from a 220 ms threshold.
 */
class DiffAnatomyIT {

    private static final String A = "vl0|1000|ENTER|vl0|stA|-|train=vl0,position=null,target=stC";
    private static final String B = "vl1|1008|LEAVE|vl1|stA|-|train=vl1";
    private static final String C = "vl2|1235|START|Main|vl2|-|station=stA";

    @Test
    @DisplayName("a pure reordering is reported as one, and a changed line is not")
    void multisetSeparatesReorderingFromBehaviour() {
        DiffAnatomy reordered = DiffAnatomy.of(List.of(A, B), List.of(B, A), List.of(),
                new PassThroughNormalizer());
        assertTrue(reordered.sameMultiset(), "same lines, other order");
        assertTrue(String.join("\n", reordered.describe()).contains("SAME MULTISET"));

        DiffAnatomy changed = DiffAnatomy.of(List.of(A, B), List.of(A, C), List.of(),
                new PassThroughNormalizer());
        assertFalse(changed.sameMultiset());
        assertEquals(List.of(B), changed.onlyInGolden());
        assertEquals(List.of(C), changed.onlyInRun());
        assertTrue(String.join("\n", changed.describe()).contains("DIFFERENT LINES"));
    }

    @Test
    @DisplayName("the margins are measured on the raw ticks, against the width the pipeline uses")
    void marginsComeFromTheRawCaptureAndThePipelineWidth() {
        DiffAnatomy anatomy = DiffAnatomy.of(List.of(A), List.of(A), List.of(A, B, C),
                new CanonicalTraceNormalizer(220));
        assertEquals(220L, anatomy.width());
        // Two gaps: 8 ms (merged, margin -212) and 227 ms (split, margin +7). Nearest first.
        assertEquals(List.of(7L, -212L), anatomy.margins().stream().map(DiffAnatomy.Margin::margin).toList());
        assertEquals(227L, anatomy.margins().get(0).gap());
        assertTrue(String.join("\n", anatomy.describe()).contains("margin +7"));

        DiffAnatomy widened = DiffAnatomy.of(List.of(A), List.of(A), List.of(A, B, C),
                new CanonicalTraceNormalizer(500));
        assertEquals(500L, widened.width(), "the width must come from the pipeline, not the default");

        DiffAnatomy noStage = DiffAnatomy.of(List.of(A), List.of(A), List.of(A, B, C),
                new PassThroughNormalizer());
        assertEquals(DiffAnatomy.NO_WIDTH, noStage.width());
        assertTrue(anatomy.margins().size() <= 5, "the report stays readable");
    }

    @Test
    @DisplayName("the runner prints the anatomy when a golden comparison fails")
    void theRunnerPrintsIt(@TempDir Path parityRoot) throws IOException {
        ScenarioRunner runner = new ScenarioRunner(new ParityLayout(parityRoot));
        ScenarioSpec spec = spec("anatomy-demo");

        RunReport recorded = withRecordMode(true, () -> runner.run(spec, new StubLauncher()));
        Path golden = recorded.goldenFile();

        // Swap two adjacent lines of the golden: the multiset is untouched, so the run now fails
        // on position alone -- the exact shape #39 had to diagnose by hand. The stub emits no
        // canonical line, which is why the margin list is empty here and is measured directly in
        // marginsComeFromTheRawCaptureAndThePipelineWidth instead; what this proves is the wiring.
        List<String> lines = new ArrayList<>(Files.readAllLines(golden, StandardCharsets.UTF_8));
        lines.add(0, lines.remove(1));
        Files.write(golden, lines, StandardCharsets.UTF_8);

        ScenarioFailedException failure = assertThrows(ScenarioFailedException.class,
                () -> withRecordMode(false, () -> runner.run(spec, new StubLauncher())));
        assertTrue(failure.getMessage().contains("SAME MULTISET"),
                "the failure report must say the run did the same things in another order: "
                        + failure.getMessage());
        assertTrue(failure.getMessage().contains("burst width in use: 220"),
                "and it must name the width the ordering rule is using: " + failure.getMessage());
    }

    private static ScenarioSpec spec(String id) {
        return ScenarioSpecParser.parse("""
                id: %s
                contract: strict
                launcher:
                  properties:
                    sim.random.masterSeed: "20080415"
                    sim.stop.maxTrains: "8"
                    stub.mode: normal
                run:
                  harnessTimeoutMs: 60000
                  expect: bound-reached
                liveness:
                  - pattern: '^vl\\d+ started$'
                    atLeast: 1
                """.formatted(id), "<inline:" + id + ">");
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
