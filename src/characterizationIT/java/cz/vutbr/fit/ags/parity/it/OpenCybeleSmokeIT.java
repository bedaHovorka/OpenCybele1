package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
import cz.vutbr.fit.ags.parity.opencybele.OpenCybeleLauncher;
import cz.vutbr.fit.ags.parity.run.RunReport;
import cz.vutbr.fit.ags.parity.run.ScenarioRunner;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpecParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The adapter smoke test: a real scenario, run through {@link OpenCybeleLauncher} against the real
 * application, recorded and compared as a golden
 * (<a href="https://github.com/bedaHovorka/OpenCybele1/issues/13">#13</a>, last acceptance
 * criterion).
 *
 * <pre>{@code
 * cd ../wt/opencybele-ref && ./gradlew installDist
 * ./gradlew characterizationIT -Popencybele.dist=/abs/path/wt/opencybele-ref/build/install/opencybele \
 *     -Dgolden.record=true      # record; drop the flag to compare
 * }</pre>
 *
 * <h2>The scenario it drives changed in #23 — the smoke/strict fold</h2>
 *
 * <p>This test used to drive {@code opencybele-smoke.yaml}, which was
 * {@code opencybele-strict.yaml} with one different number: {@code sim.stop.maxClockMs = 25000}
 * instead of {@code 24000}. Both files said, in their own comments, that #23 should fold them.
 * #23 did, and the file that went was the smoke one, because 25000 is the WEAKER of the pair and
 * measurably so:
 *
 * <ul>
 *   <li>At 25000 the trace's last event burst <em>begins</em> at simulated 25072 — 72 ms after the
 *       bound — so those 16 lines are emitted inside the shutdown window. Measured over 38 runs:
 *       26 produced 623 lines, 1 produced 611, 3 produced 609 and 8 produced 607. One run in three
 *       truncates.</li>
 *   <li>That truncation is why the file could only hold a {@code summary} contract, and a
 *       {@code summary} contract is where a late DEF-22 wedge hides inside a tolerance —
 *       {@code ParityGate}'s own subject.</li>
 *   <li>At 24000 the bound sits inside the measured 1952 ms gap between bursts, and 14 of 14
 *       captures were byte-identical.</li>
 * </ul>
 *
 * <p>So keeping both meant keeping a scenario that flakes at 1 in 12 and pins counts rather than
 * content, in order to assert adapter properties that hold just as well against the strict one.
 * The assertions moved; the scenario went. Nothing was re-recorded — {@code Phase1.md} L7 forbids
 * that, and {@code opencybele-strict.txt} is untouched.
 *
 * <p>Without {@code -Popencybele.dist} this test is <strong>skipped</strong>, not failed: the
 * harness source set has no link to any implementation. {@code OpenCybeleLauncherIT} still runs,
 * and still asserts every property of the adapter that does not need a child.
 */
class OpenCybeleSmokeIT {

    private static final String SCENARIO = "opencybele-strict";

    @Test
    @DisplayName("the real application runs end to end through OpenCybeleLauncher and matches its golden")
    void openCybeleSmokeScenarioMatchesGolden() {
        assumeTrue(OpenCybeleLauncher.isAvailable(), OpenCybeleLauncher.unavailableMessage());

        ParityLayout layout = ParityLayout.fromSystemProperties();
        ScenarioSpec spec = ScenarioSpecParser.parse(layout.scenariosDir().resolve(SCENARIO + ".yaml"));

        // ScenarioRunner already does the load-bearing work: bounded waitFor (a timeout is
        // HARNESS_TIMEOUT and can never pass), exit classification, the error scan on the RAW
        // stream, liveness, then normalize and record-or-compare. ScenarioAssertions covers what
        // this adapter adds on top and what would otherwise fail silently.
        RunReport report = ScenarioRunner.usingDefaultLayout().run(spec, new OpenCybeleLauncher());
        ScenarioAssertions.assertAdapterContract(spec, report.captured().lines(), report.normalized());

        if (GoldenStore.recording()) {
            System.out.println("parity: recorded " + report.normalized().size() + " line(s) to "
                    + report.goldenFile());
        }
    }
}
