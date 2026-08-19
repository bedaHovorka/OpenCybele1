package cz.vutbr.fit.ags.parity.spec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One scenario, as data. Replaying it against another implementation must change only the
 * {@link cz.vutbr.fit.ags.parity.spi.LauncherAdapter} — nothing in here names a framework.
 *
 * <p>The format is documented, with a worked example, in {@code docs/parity-harness.md}. It is
 * parsed by {@link ScenarioSpecParser}, which rejects unknown keys: a spec that looks fully
 * specified while quietly falling back to a default is exactly how a golden gets recorded under a
 * configuration nobody chose.
 *
 * @param id                scenario id; must match the file name stem
 * @param description       free text, for humans and failure reports
 * @param contract          how strongly the golden is compared
 * @param goldenFile        golden file name inside {@code parity-tests/golden}
 * @param launcher          implementation-neutral knobs handed to the adapter
 * @param run               harness-side run control
 * @param liveness          at least one rule; a scenario that asserts nothing about how much it
 *                          did cannot detect a silently dead simulation
 * @param entity            how to read an entity id; required unless {@code contract} is STRICT
 * @param summary           aggregates; present only when {@code contract} is SUMMARY
 * @param allowErrorLines   error-scan exemptions, for scenarios that deliberately pin a failure
 */
public record ScenarioSpec(
        String id,
        String description,
        ContractLevel contract,
        String goldenFile,
        Launcher launcher,
        Run run,
        List<LivenessRule> liveness,
        EntityRule entity,
        List<SummaryRule> summary,
        List<Pattern> allowErrorLines) {

    public ScenarioSpec {
        liveness = List.copyOf(liveness);
        summary = List.copyOf(summary);
        allowErrorLines = List.copyOf(allowErrorLines);
    }

    /**
     * Implementation-neutral configuration. {@code properties} are key/value pairs the adapter
     * turns into whatever its target understands — on a JVM target, {@code -D} flags; the harness
     * itself never interprets them.
     *
     * @param config     optional implementation-side config file, resolved by the adapter
     * @param properties configuration keys, in declaration order
     * @param env        environment variables added to the child
     * @param args       extra program arguments
     */
    public record Launcher(String config, Map<String, String> properties, Map<String, String> env,
            List<String> args) {

        public Launcher {
            // LinkedHashMap, not Map.copyOf: declaration order is preserved so that the command
            // line an adapter builds is the same on every run. A command line that reorders itself
            // is a difference between two runs that nobody declared.
            properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
            env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
            args = List.copyOf(args);
        }
    }

    /**
     * @param harnessTimeoutMs hard cap after which the harness kills the child and fails the
     *                         scenario. This is a backstop for a child whose own safety net did not
     *                         fire; it is never a pass, and it should sit well above the child's
     *                         own {@code wallClockMs}
     * @param expect           the ending the scenario declares
     */
    public record Run(long harnessTimeoutMs, ExpectedOutcome expect) {

        public Run {
            if (harnessTimeoutMs <= 0) {
                throw new IllegalArgumentException("run.harnessTimeoutMs must be positive");
            }
        }
    }
}
