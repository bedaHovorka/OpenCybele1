package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
import cz.vutbr.fit.ags.parity.opencybele.OpenCybeleLauncher;
import cz.vutbr.fit.ags.parity.run.ParityGate;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpecParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The repeatable command behind {@code Phase1.md} 1-PRE.2's gate.
 *
 * <pre>{@code
 * ./gradlew parityGate \
 *     -Popencybele.dist=/abs/path/wt/opencybele-ref/build/install/opencybele \
 *     -Dparity.gate.runs=10
 * }</pre>
 *
 * <p><strong>Opt-in on purpose.</strong> Ten repetitions of a real 3 s scenario is ~40 s of child
 * processes; putting that in the default {@code characterizationIT} lane would make every unrelated
 * change pay for it, and a gate people disable is worse than one they invoke. With
 * {@code -Dparity.gate.runs} absent the test is skipped and prints the command.
 *
 * <p>What it establishes, and what it does not, is
 * {@link ParityGate}'s subject and is printed in full on every invocation — including the failure
 * to establish cross-occasion reproducibility, which is the half that gets dropped when a gate
 * reports only its green.
 */
class ParityGateIT {

    private static final String SCENARIO = "opencybele-strict";

    @Test
    @DisplayName("N consecutive runs of the strict scenario are byte-identical (opt-in)")
    void consecutiveRunsAreByteIdentical() {
        int runs = ParityGate.requestedRuns();
        assumeTrue(runs > 0, "gate not requested; run it with:\n    " + ParityGate.invocation(SCENARIO));
        assumeTrue(OpenCybeleLauncher.isAvailable(), OpenCybeleLauncher.unavailableMessage());
        // Recording N times and then declaring the last one reproducible would be circular: each
        // run would overwrite the golden it is about to be compared against.
        assumeFalse(GoldenStore.recording(), "the gate compares; it never records");

        ParityLayout layout = ParityLayout.fromSystemProperties();
        ScenarioSpec spec = ScenarioSpecParser.parse(layout.scenariosDir().resolve(SCENARIO + ".yaml"));

        ParityGate.Result result = new ParityGate(layout).run(spec, new OpenCybeleLauncher(), runs);
        System.out.println(result.describe());

        assertTrue(result.withinSessionStable(), "the gate throws before this, so reaching it means"
                + " the runs agreed; the assertion is here so the claim is in the test and not only"
                + " in the engine");
        assertFalse(result.entry().entityIds().isEmpty(),
                "the gate compared entity-id sets across occasions and there are no entity ids to"
                        + " compare. That is a green light nobody earned: declare an `entity:` rule"
                        + " on the scenario.");
        assertTrue(result.crossOccasionDisagreements().isEmpty(),
                "a run from another boot, or " + ParityGate.CROSS_OCCASION_HOURS + "+ hours ago,"
                        + " produced a DIFFERENT entity-id set. Per docs/defect-triage.md §8.2 the"
                        + " scenario is above the density at which DEF-02 is suppressed — retune it,"
                        + " do not re-record the golden: " + result.crossOccasionDisagreements());
    }
}
