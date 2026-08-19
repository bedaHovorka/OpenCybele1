package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
import cz.vutbr.fit.ags.parity.run.RunReport;
import cz.vutbr.fit.ags.parity.run.ScenarioRunner;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpecParser;
import cz.vutbr.fit.ags.parity.stub.StubLauncher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The end-to-end smoke scenario: {@code parity-tests/scenarios/smoke-stub.yaml} driven through the
 * stub adapter.
 *
 * <p>It proves the whole pipeline works with <strong>no application source present</strong> — the
 * property that lets the harness live on {@code jade-develop} and later drive three
 * implementations that live elsewhere. The real {@code OpenCybeleLauncher} smoke test belongs to
 * <a href="https://github.com/bedaHovorka/OpenCybele1/issues/13">#13</a>, which this issue blocks.
 */
class SmokeScenarioIT {

    @Test
    @DisplayName("the SPI drives a scenario start to finish against a stub implementation")
    void smokeScenarioMatchesGolden() {
        ParityLayout layout = ParityLayout.fromSystemProperties();
        ScenarioSpec spec = ScenarioSpecParser.parse(layout.scenariosDir().resolve("smoke-stub.yaml"));

        RunReport report = ScenarioRunner.usingDefaultLayout().run(spec, new StubLauncher());

        assertTrue(report.captured().wallClockMs() >= 0);
        List<String> trace = report.normalized();
        assertFalse(trace.isEmpty(), "the normalized trace must not be empty");
        assertTrue(trace.stream().noneMatch(line -> line.startsWith("--- ")),
                "diagnostics must not reach the golden: " + trace);
        if (GoldenStore.recording()) {
            System.out.println("recorded " + trace.size() + " line(s) to " + report.goldenFile());
        }
    }
}
