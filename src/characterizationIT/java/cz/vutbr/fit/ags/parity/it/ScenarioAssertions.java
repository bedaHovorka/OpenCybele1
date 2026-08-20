package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four claims that every OpenCybele scenario has to make, factored out of
 * {@link OpenCybeleSmokeIT} when <a href="https://github.com/bedaHovorka/OpenCybele1/issues/23">#23</a>
 * added three more scenarios.
 *
 * <p>They were originally written for one scenario and are copied here unchanged in substance,
 * because each of them exists to close a hole somebody actually fell into. Repeating them per
 * scenario is not ceremony: a scenario declares its own twenty {@code sim.*} keys, and a key
 * silently dropped on the way to <em>that</em> child is invisible in <em>that</em> golden.
 */
final class ScenarioAssertions {

    private ScenarioAssertions() {
    }

    /** Every claim below, in the order {@code ScenarioRunner} would want them. */
    static void assertAdapterContract(ScenarioSpec spec, List<String> raw, List<String> trace) {
        assertPropertiesReachedTheChild(spec, raw);
        assertConfigurationDidNotReachTheTrace(trace);
        assertTraceIsTheCanonicalOne(trace);
        assertNothingThrew(raw);
    }

    /**
     * <strong>Proof that the {@code -D} flags actually arrived — all of them.</strong>
     *
     * <p>{@code -D} flags handed <em>positionally</em> to a Gradle start script are routed to the
     * program's arguments and set no system property at all — measured: a positional
     * {@code -Dsim.random.masterSeed=20080415} drew a different seed while the same value in
     * {@code OPENCYBELE_OPTS} pinned it. A golden recorded under a silently ignored seed is the
     * worst outcome available here, so the adapter invokes {@code java} directly and the arrival of
     * the properties is <em>measured</em> out of the child's own resolved-configuration banner.
     *
     * <p><strong>Driven from the scenario, not from a hand-written list.</strong> An earlier
     * revision checked six hard-coded keys out of ten, and dropping
     * {@code sim.station.voteWindowMs} alone moved the trace by exactly 2 lines per affected
     * family — inside every declared tolerance and therefore invisible. Iterating
     * {@link ScenarioSpec.Launcher#properties()} makes the claim total.
     *
     * <p>The match is anchored for the same reason: {@code startsWith} accepted
     * {@code sim.clock.pace = 80} as proof of {@code = 8}. The banner writes {@code <key> = <value>}
     * and appends {@code "    # default: …"} only when the value differs from the default, so the
     * whole line is matched with that suffix optional.
     */
    static void assertPropertiesReachedTheChild(ScenarioSpec spec, List<String> raw) {
        assertFalse(spec.launcher().properties().isEmpty(),
                "the scenario declares no properties, so this check would assert nothing");
        for (Map.Entry<String, String> declared : spec.launcher().properties().entrySet()) {
            Pattern bannerLine = Pattern.compile("^"
                    + Pattern.quote(declared.getKey() + " = " + declared.getValue())
                    + "(?:    # .*)?$");
            assertTrue(raw.stream().anyMatch(line -> bannerLine.matcher(line).matches()),
                    "the child's resolved-configuration banner has no line '" + declared.getKey()
                            + " = " + declared.getValue() + "', so that property never reached the"
                            + " JVM — or reached it with another value. That is the silent-ignore"
                            + " failure #13 exists to rule out, and a single dropped key is enough:"
                            + " it moves the trace by about two lines.");
        }
        assertFalse(raw.stream().anyMatch(line -> line.contains("drew ")),
                "the seed was drawn rather than pinned: -Dsim.random.masterSeed did not arrive.");
    }

    /**
     * <strong>Proof that the banner defect stays fixed.</strong> The ~20 {@code sim.* = value} lines
     * are stderr diagnostics that {@code redirectErrorStream(true)} folds into the trace;
     * {@code OpenCybeleLauncher.CONFIG_BANNER_PREFIX} declares them out of it.
     */
    static void assertConfigurationDidNotReachTheTrace(List<String> trace) {
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
     * The recorded artefact must be the canonical trace of #20, not just the two old printlns — and
     * it must arrive <em>projected</em>, which is what #21 added.
     *
     * <p>The tick assertions are written against {@code <T>} rather than {@code \d+} deliberately: a
     * normalizer that silently stopped projecting would leave a {@code \d+} pattern matching a raw
     * tick and nothing here would notice.
     */
    static void assertTraceIsTheCanonicalOne(List<String> trace) {
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
     * Belt and braces over {@code ErrorScanner}, which has already run. Named separately because a
     * throwable in a Cybele handler is printed and the exit status is left alone, so a green exit
     * proves nothing — and because the {@code Cybele.terminate()} race that every scenario's bound
     * is sized against surfaces here and only here.
     */
    static void assertNothingThrew(List<String> raw) {
        List<String> hits = raw.stream()
                .filter(line -> line.contains("AssertionError") || line.contains("Exception in thread \""))
                .toList();
        assertTrue(hits.isEmpty(), "the captured stream contains throwables: " + hits);
    }
}
