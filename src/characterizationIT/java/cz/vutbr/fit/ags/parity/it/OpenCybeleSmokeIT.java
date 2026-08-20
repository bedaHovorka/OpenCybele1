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

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The end-to-end smoke scenario #12 could not have: a real scenario, run through
 * {@link OpenCybeleLauncher} against the real application, recorded and compared as a golden
 * (<a href="https://github.com/bedaHovorka/OpenCybele1/issues/13">#13</a>, last acceptance
 * criterion).
 *
 * <pre>{@code
 * cd ../wt/opencybele-ref && ./gradlew installDist
 * ./gradlew characterizationIT -Popencybele.dist=/abs/path/wt/opencybele-ref/build/install/opencybele \
 *     -Dgolden.record=true      # record; drop the flag to compare
 * }</pre>
 *
 * <p>Without {@code -Popencybele.dist} this test is <strong>skipped</strong>, not failed: the
 * harness source set has no link to any implementation, and requiring a built one in order to run
 * the suite at all would defeat the split #11 chose. {@code OpenCybeleLauncherIT} still runs, and still asserts
 * every property of the adapter that does not need a child.
 */
class OpenCybeleSmokeIT {

    private static final String SCENARIO = "opencybele-smoke";

    @Test
    @DisplayName("the real application runs end to end through OpenCybeleLauncher and matches its golden")
    void openCybeleSmokeScenarioMatchesGolden() {
        assumeTrue(OpenCybeleLauncher.isAvailable(), OpenCybeleLauncher.unavailableMessage());

        ParityLayout layout = ParityLayout.fromSystemProperties();
        ScenarioSpec spec = ScenarioSpecParser.parse(layout.scenariosDir().resolve(SCENARIO + ".yaml"));

        // ScenarioRunner already does the load-bearing work: bounded waitFor (a timeout is
        // HARNESS_TIMEOUT and can never pass), exit classification, the error scan on the RAW
        // stream, liveness, then normalize and record-or-compare. What is asserted below is what
        // this adapter adds on top and what would otherwise fail silently.
        RunReport report = ScenarioRunner.usingDefaultLayout().run(spec, new OpenCybeleLauncher());

        List<String> raw = report.captured().lines();
        List<String> trace = report.normalized();

        assertPropertiesReachedTheChild(spec, raw);
        assertConfigurationDidNotReachTheTrace(trace);
        assertTraceIsTheCanonicalOne(trace);
        assertNothingThrew(raw);

        if (GoldenStore.recording()) {
            System.out.println("parity: recorded " + trace.size() + " line(s) to " + report.goldenFile());
        }
    }

    /**
     * <strong>Proof that the {@code -D} flags actually arrived — all of them.</strong>
     *
     * <p>This is the failure this issue was written around. {@code -D} flags handed
     * <em>positionally</em> to a Gradle start script are routed to the program's arguments and set
     * no system property at all — measured: a positional {@code -Dsim.random.masterSeed=20080415}
     * drew a different seed while the same value in {@code OPENCYBELE_OPTS} pinned it. A golden
     * recorded under a silently ignored seed is the worst outcome available here, so the adapter
     * invokes {@code java} directly and the arrival of the properties is <em>measured</em> out of
     * the child's own resolved-configuration banner rather than assumed.
     *
     * <p><strong>Driven from the scenario, not from a hand-written list.</strong> An earlier
     * revision checked six hard-coded keys out of the ten the scenario declared, and a reviewer
     * built the case that survives it: dropping {@code sim.station.voteWindowMs} alone moved the
     * trace by exactly 2 lines in each affected family — inside every declared tolerance, with the
     * distinct-entity count and all train-level counts unchanged — so the drop was invisible in
     * both directions and a golden recorded that way would have frozen a configuration this file
     * claims not to be using. Iterating {@link ScenarioSpec.Launcher#properties()} makes the claim
     * total, and keeps it total the next time the scenario gains a knob.
     *
     * <p>The match is anchored for the same reason. {@code startsWith} accepted
     * {@code sim.clock.pace = 80} as proof of {@code = 8} and {@code sim.stop.maxClockMs = 250000}
     * as proof of {@code = 25000}; the banner writes {@code <key> = <value>} and appends
     * {@code "    # default: …"} (or {@code "    # drawn …"}) only when the value differs from the
     * default, so the whole line is matched with that suffix optional.
     */
    private static void assertPropertiesReachedTheChild(ScenarioSpec spec, List<String> raw) {
        assertFalse(spec.launcher().properties().isEmpty(),
                "the scenario declares no properties, so this check would assert nothing");
        for (Map.Entry<String, String> declared : spec.launcher().properties().entrySet()) {
            // "<key> = <value>" exactly, optionally followed by the banner's 4-space annotation.
            Pattern bannerLine = Pattern.compile("^"
                    + Pattern.quote(declared.getKey() + " = " + declared.getValue())
                    + "(?:    # .*)?$");
            assertTrue(raw.stream().anyMatch(line -> bannerLine.matcher(line).matches()),
                    "the child's resolved-configuration banner has no line '" + declared.getKey()
                            + " = " + declared.getValue() + "', so that property never reached the"
                            + " JVM — or reached it with another value. That is the silent-ignore"
                            + " failure #13 exists to rule out, and a single dropped key is enough:"
                            + " it moves the trace by about two lines, which every tolerance in"
                            + " this scenario absorbs.");
        }
        assertFalse(raw.stream().anyMatch(line -> line.contains("drew ")),
                "the seed was drawn rather than pinned: -Dsim.random.masterSeed did not arrive.");
    }

    /**
     * <strong>Proof that the banner defect is fixed.</strong> All ~20 {@code sim.* = value} lines
     * are stderr diagnostics that {@code redirectErrorStream(true)} folds into the trace and that
     * none of #12's default prefixes matched. {@link OpenCybeleLauncher#CONFIG_BANNER_PREFIX}
     * declares them; this asserts the declaration works against the real output, so the hole cannot
     * reopen the next time someone adds an unprefixed stderr line.
     */
    private static void assertConfigurationDidNotReachTheTrace(List<String> trace) {
        List<String> leaked = trace.stream().filter(line -> line.startsWith("sim.")).toList();
        assertTrue(leaked.isEmpty(),
                "configuration lines reached the normalized trace and would be recorded as"
                        + " behaviour: " + leaked);
        assertTrue(trace.stream().noneMatch(line -> line.contains("masterSeed")),
                "the seed line must never be in a golden — unpinned it differs on every run");
        assertTrue(trace.stream().noneMatch(line -> line.contains("Generator.interarrival")),
                "#15's randomness manifest must not reach the golden either");
        assertTrue(trace.stream().noneMatch(line -> line.startsWith("--- ")
                        || line.startsWith("!!! ") || line.startsWith("  ")),
                "no declared diagnostic shape may survive: " + trace);
    }

    /**
     * The recorded artefact must be the canonical trace of #20, not just the two old printlns —
     * and it must arrive <em>projected</em>, which is what changed with #21.
     *
     * <p>The tick assertions are written against {@code <T>} rather than {@code \d+} deliberately.
     * A normalizer that silently stopped projecting would leave these patterns matching a raw
     * numeric tick and nothing here would notice; asserting the projected shape makes "the
     * projection ran" a checked claim rather than an assumption.
     */
    private static void assertTraceIsTheCanonicalOne(List<String> trace) {
        assertFalse(trace.isEmpty(), "the normalized trace must not be empty");
        assertTrue(trace.stream().anyMatch(line -> line.matches("^vl\\d+\\|<T>\\|PLAN_TRAIN\\|.*")),
                "no canonical PLAN_TRAIN line with a projected tick: either sim.trace.enabled did"
                        + " not reach the child, or the tick projection stopped running");
        assertTrue(trace.stream().anyMatch(line -> line.matches("^st[A-H]\\|<T>\\|STATION_INFO\\|.*")));
        assertTrue(trace.stream().anyMatch(line -> line.matches("^vl\\d+ started$")),
                "the application's own departure println is part of the contract and is missing");
        assertTrue(trace.stream().noneMatch(line -> line.matches("^[^|]*\\|-?\\d+\\|.*")),
                "a raw numeric tick survived into the normalized trace");
    }

    /**
     * Belt and braces over {@code ErrorScanner}, which has already run and would have failed the
     * scenario. Named here because #13's acceptance list names it: a throwable in a Cybele handler
     * is printed and the exit status is left alone, so a green exit proves nothing.
     */
    private static void assertNothingThrew(List<String> raw) {
        List<String> hits = raw.stream()
                .filter(line -> line.contains("AssertionError") || line.contains("Exception in thread \""))
                .toList();
        assertTrue(hits.isEmpty(), "the captured stream contains throwables: " + hits);
    }
}
