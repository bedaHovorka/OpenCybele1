package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
import cz.vutbr.fit.ags.parity.normalize.CanonicalTraceNormalizer;
import cz.vutbr.fit.ags.parity.normalize.DiagnosticFilter;
import cz.vutbr.fit.ags.parity.opencybele.OpenCybeleLauncher;
import cz.vutbr.fit.ags.parity.run.RunReport;
import cz.vutbr.fit.ags.parity.run.ScenarioRunner;
import cz.vutbr.fit.ags.parity.spec.ContractLevel;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpecParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The acceptance test for <a href="https://github.com/bedaHovorka/OpenCybele1/issues/21">#21</a>:
 * a real implementation, run end to end, compared to its golden <strong>line for line</strong>.
 *
 * <p>{@code OpenCybeleSmokeIT} proves the adapter; this proves the projection. The difference that
 * matters is the contract level: {@code opencybele-smoke} is {@code summary}, so it compares
 * counts and would pass against a trace whose every payload was wrong;
 * {@code opencybele-strict} is {@code strict}, so every one of its 607 lines must be the line the
 * golden has, in the position the golden has it.
 *
 * <p>Beyond running the scenario, this asserts the two properties that decide whether a passing
 * {@code strict} run means anything — both of them the "lock that cannot fail" shape this project
 * has rejected four times:
 *
 * <ul>
 *   <li>the trace still segments into <em>many</em> bursts, so the golden pins order and not just a
 *       multiset;</li>
 *   <li>the trace is <em>not</em> globally sorted, which is what a single-burst degeneracy would
 *       look like from outside.</li>
 * </ul>
 */
class OpenCybeleStrictIT {

    private static final String SCENARIO = "opencybele-strict";

    /**
     * Measured floor. At the shipped burst width the scenario segments into 23 bursts; anything
     * near 1 means the width has been widened until the ordering assertion evaporated.
     */
    private static final int MIN_SEGMENTS = 10;

    @Test
    @DisplayName("the real application matches its golden LINE FOR LINE at a strict contract")
    void strictScenarioMatchesGoldenLineForLine() {
        assumeTrue(OpenCybeleLauncher.isAvailable(), OpenCybeleLauncher.unavailableMessage());

        ParityLayout layout = ParityLayout.fromSystemProperties();
        ScenarioSpec spec = ScenarioSpecParser.parse(layout.scenariosDir().resolve(SCENARIO + ".yaml"));
        assertEquals(ContractLevel.STRICT, spec.contract(),
                "this test exists to exercise the strict path; weakening the scenario's contract"
                        + " silently turns it into a second copy of OpenCybeleSmokeIT");

        RunReport report = ScenarioRunner.usingDefaultLayout().run(spec, new OpenCybeleLauncher());
        List<String> trace = report.normalized();

        assertFalse(trace.isEmpty(), "the normalized trace must not be empty");
        assertTrue(trace.stream().anyMatch(line -> line.matches("^vl\\d+\\|<T>\\|VOTE\\|.*")),
                "no projected VOTE line: the election is the system's core algorithm and the thing"
                        + " #34 restructures; a strict golden that does not contain it proves little");
        assertOrderIsStillAsserted(report);

        if (GoldenStore.recording()) {
            System.out.println("parity: recorded " + trace.size() + " line(s) to " + report.goldenFile());
        }
    }

    /**
     * A {@code strict} contract is only as strong as the order the normalizer left in the trace.
     * {@link CanonicalTraceNormalizer} sorts <em>within</em> a burst, so widening the burst until
     * the whole run is one burst would turn the golden into a sorted multiset — which still passes
     * {@code strict}, still looks green, and no longer detects a port that emits the right lines in
     * the wrong order. Both halves of that are checked here rather than trusted.
     */
    private static void assertOrderIsStillAsserted(RunReport report) {
        OpenCybeleLauncher adapter = new OpenCybeleLauncher();
        // From the ADAPTER, not a fresh default: a floor measured against `new
        // CanonicalTraceNormalizer()` guards the constant rather than the pipeline, and would stay
        // green while the normalizer actually in use had collapsed the run into one segment.
        CanonicalTraceNormalizer normalizer = CanonicalTraceNormalizer.findIn(adapter.normalizer())
                .orElseThrow(() -> new AssertionError("the adapter's normalizer no longer contains a"
                        + " CanonicalTraceNormalizer, so nothing here is measuring the projection"));
        List<String> filtered = new DiagnosticFilter(adapter.diagnosticPrefixes())
                .normalize(report.captured().lines());
        int segments = normalizer.segments(filtered).size();
        assertTrue(segments >= MIN_SEGMENTS,
                "the trace segmented into only " + segments + " burst(s). The golden then pins a"
                        + " multiset and no ordering at all — a lock that cannot fail. Either the"
                        + " burst width (" + normalizer.segmentGapTicks() + " ms) has been widened"
                        + " past what docs/trace-normalizer.md measured, or the scenario has become"
                        + " one continuous burst and needs a lower arrival rate.");

        List<String> sorted = new ArrayList<>(report.normalized());
        sorted.sort(CanonicalTraceNormalizer.NATURAL_ORDER);
        assertNotEquals(sorted, report.normalized(),
                "the normalized trace is in full sorted order, which is what a single-burst"
                        + " degeneracy looks like from outside: every line would compare equal to a"
                        + " golden recorded from any run with the same multiset.");
    }
}
