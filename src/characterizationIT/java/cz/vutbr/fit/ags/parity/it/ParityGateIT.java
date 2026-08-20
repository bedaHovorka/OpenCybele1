package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
import cz.vutbr.fit.ags.parity.opencybele.OpenCybeleLauncher;
import cz.vutbr.fit.ags.parity.run.ParityGate;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpecParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

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

    /**
     * Gate every OpenCybele scenario, not just one.
     *
     * <p>Until #23 this was a single hard-coded id, because there was a single scenario worth
     * gating. {@code Phase1.md} 1-PRE.2 says "zero flake across &ge;10 consecutive runs
     * <strong>per scenario</strong>", and #23's own acceptance is that each scenario passes this
     * gate <em>before</em> it is added to {@code COVERAGE.md} — so the set is read from the
     * catalogue and a scenario added tomorrow is gated without anyone remembering to add it here.
     *
     * <p>One scenario can be selected with {@code -Dparity.gate.scenario=<id>}, which is what to
     * use while tuning one; the default is all of them, which is what CI and the acceptance need.
     */
    static final String SCENARIO_PROPERTY = "parity.gate.scenario";

    @TestFactory
    @DisplayName("N consecutive runs of each OpenCybele scenario are byte-identical (opt-in)")
    Stream<DynamicTest> consecutiveRunsAreByteIdentical() {
        int runs = ParityGate.requestedRuns();
        ParityLayout layout = ParityLayout.fromSystemProperties();
        List<ScenarioSpec> specs = scenarios(layout);

        return specs.stream().map(spec -> DynamicTest.dynamicTest(spec.id(), () -> {
            assumeTrue(runs > 0, "gate not requested; run it with:\n    "
                    + ParityGate.invocation(spec.id()));
            assumeTrue(OpenCybeleLauncher.isAvailable(), OpenCybeleLauncher.unavailableMessage());
            // Recording N times and then declaring the last one reproducible would be circular:
            // each run would overwrite the golden it is about to be compared against.
            assumeFalse(GoldenStore.recording(), "the gate compares; it never records");

            ParityGate.Result result = new ParityGate(layout)
                    .run(spec, new OpenCybeleLauncher(), runs);
            System.out.println(result.describe());

            assertTrue(result.withinSessionStable(), "the gate throws before this, so reaching it"
                    + " means the runs agreed; the assertion is here so the claim is in the test and"
                    + " not only in the engine");
            assertFalse(result.entry().entityIds().isEmpty(),
                    "the gate compared entity-id sets across occasions and there are no entity ids"
                            + " to compare. That is a green light nobody earned: declare an"
                            + " `entity:` rule on the scenario.");
            assertTrue(result.crossOccasionDisagreements().isEmpty(),
                    "a run from another boot, or " + ParityGate.CROSS_OCCASION_HOURS + "+ hours ago,"
                            + " produced a DIFFERENT entity-id set. Per docs/defect-triage.md §8.2"
                            + " the scenario is above the density at which DEF-02 is suppressed —"
                            + " retune it, do not re-record the golden: "
                            + result.crossOccasionDisagreements());
        }));
    }

    private static List<ScenarioSpec> scenarios(ParityLayout layout) {
        String selected = System.getProperty(SCENARIO_PROPERTY, "").trim();
        List<ScenarioSpec> all = ScenarioSpecParser.parseAll(layout.scenariosDir()).stream()
                .filter(spec -> spec.id().startsWith("opencybele-"))
                .toList();
        if (selected.isEmpty()) {
            return all;
        }
        List<ScenarioSpec> one = all.stream().filter(spec -> spec.id().equals(selected)).toList();
        if (one.isEmpty()) {
            throw new IllegalArgumentException("-D" + SCENARIO_PROPERTY + "=" + selected
                    + " names no scenario. Available: " + all.stream().map(ScenarioSpec::id).toList());
        }
        return one;
    }
}
