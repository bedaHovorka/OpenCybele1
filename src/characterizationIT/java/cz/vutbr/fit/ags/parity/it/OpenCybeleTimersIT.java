package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
import cz.vutbr.fit.ags.parity.normalize.CanonicalTraceNormalizer;
import cz.vutbr.fit.ags.parity.opencybele.OpenCybeleLauncher;
import cz.vutbr.fit.ags.parity.run.RunReport;
import cz.vutbr.fit.ags.parity.run.ScenarioRunner;
import cz.vutbr.fit.ags.parity.spec.ContractLevel;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpecParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code opencybele-timers} — the issue's <em>"one scenario per timer/periodic activity, asserting
 * tick counts over a bounded window, not durations"</em>.
 *
 * <p>Two things are asserted that the golden alone does not say out loud.
 *
 * <ol>
 *   <li><strong>The firing counts of the four timer sites</strong>, as exact equalities over this
 *       scenario's bound. The scenario's liveness rules carry floors; this carries the numbers, so
 *       that a port which fires TMR-04 once per train instead of once per traversal fails with
 *       "expected 6 travel timers, saw 3" rather than with a golden diff.</li>
 *   <li><strong>DEF-16 — the negative delay that Cybele fires immediately</strong> — is present in
 *       <em>both</em> of its branches. {@code docs/defect-triage.md} §3.1 classes it (a), "PIN — do
 *       not clamp", and says plainly that "a port that clamps to 0 produces a diff and THAT is the
 *       port bug". That claim needs a scenario in which an unclamped negative delay actually
 *       occurs, and until #23 there was none.</li>
 * </ol>
 *
 * <p><strong>On "not durations".</strong> The DEF-16 check below reads the gap between a
 * {@code TRAVEL_START} and its {@code TRAVEL_END}. That gap is on the <em>simulated</em> clock, and
 * it is compared against {@link CanonicalTraceNormalizer#segmentGapTicks()} rather than against a
 * number of milliseconds chosen here — i.e. the assertion is "these two lines fall on the same side
 * of the normalizer's burst boundary", which is a statement about the projected trace's ORDERING
 * and is the only form in which this behaviour survives into a golden at all. No wall-clock
 * duration is read anywhere in this file.
 */
class OpenCybeleTimersIT {

    private static final String SCENARIO = "opencybele-timers";

    /** Measured over four captures of this scenario; identical in all of them. */
    private static final int TMR_01_AND_02_FIRINGS = 3;   // PLAN_TRAIN: one bootstrap, two Poisson
    private static final int TMR_03_FIRINGS = 3;          // START: one per planned train
    private static final int TMR_04_FIRINGS = 6;          // TRAVEL_END: three trains x two roads

    @Test
    @DisplayName("the four timer sites fire the counts the scenario is sized for, and DEF-16 fires immediately")
    void timerScenarioPinsFiringCountsAndTheNegativeDelayBranch() {
        assumeTrue(OpenCybeleLauncher.isAvailable(), OpenCybeleLauncher.unavailableMessage());

        ParityLayout layout = ParityLayout.fromSystemProperties();
        ScenarioSpec spec = ScenarioSpecParser.parse(layout.scenariosDir().resolve(SCENARIO + ".yaml"));
        assertEquals(ContractLevel.STRICT, spec.contract());
        assertTrue(spec.launcher().properties().get("sim.road.delaysSec").contains("tr1=0"),
                "tr1's nominal delay must stay 0: it is the entire mechanism by which this scenario"
                        + " reaches DEF-16's fire-immediately branch reproducibly rather than on"
                        + " 2.2645 % of traversals");

        RunReport report = ScenarioRunner.usingDefaultLayout().run(spec, new OpenCybeleLauncher());
        List<String> raw = report.captured().lines();
        ScenarioAssertions.assertAdapterContract(spec, raw, report.normalized());

        assertFiringCounts(raw);
        assertBothBranchesOfTheTravelTimer(raw);

        if (GoldenStore.recording()) {
            System.out.println("parity: recorded " + report.normalized().size() + " line(s) to "
                    + report.goldenFile());
        }
    }

    /** Counts over a bounded window. TMR-01 and TMR-02 share the {@code PLAN_TRAIN} channel. */
    private static void assertFiringCounts(List<String> raw) {
        assertEquals(TMR_01_AND_02_FIRINGS, count(raw, "^vl\\d+\\|\\d+\\|PLAN_TRAIN\\|"),
                "TMR-01 (Generator.java:50, once at sim.arrival.firstFireMs) plus TMR-02"
                        + " (Generator.java:65, self-re-arming) over this bound");
        assertEquals(TMR_03_FIRINGS, count(raw, "^vl\\d+\\|\\d+\\|START\\|"),
                "TMR-03 (Planning.java:111) fires exactly once per planned train");
        assertEquals(TMR_04_FIRINGS, count(raw, "^vl\\d+\\|\\d+\\|TRAVEL_END\\|"),
                "TMR-04 (RoadAgent.java:102) fires exactly once per road TRAVERSAL. A port that"
                        + " arms it once per train would produce 3 here and still satisfy every"
                        + " liveness floor in the scenario.");
        assertEquals(TMR_04_FIRINGS, count(raw, "^vl\\d+\\|\\d+\\|TRAVEL_START\\|"),
                "and each of those firings was armed by a TRAVEL_START, so the count above cannot"
                        + " come from three timers firing twice");
    }

    /**
     * DEF-16, both branches, in one run.
     *
     * <p>{@code tr1}'s nominal delay is 0, so the armed value is {@code (long)(500*nextGaussian())}
     * and roughly half the draws are at or below zero. Cybele fires such a timer at once (SEM-06:
     * −1500 ms → callback in 1–5 ms), so the traversal is instantaneous and {@code TRAVEL_END}
     * stays inside its {@code TRAVEL_START}'s burst; an ordinary positive draw pushes it out into
     * the next one. Both must be present, or the scenario has stopped covering what
     * {@code COVERAGE.md} says it covers.
     */
    private static void assertBothBranchesOfTheTravelTimer(List<String> raw) {
        long gap = CanonicalTraceNormalizer.DEFAULT_SEGMENT_GAP_TICKS;
        Pattern start = Pattern.compile("^(vl\\d+)\\|(\\d+)\\|TRAVEL_START\\|[^|]*\\|(tr\\d+)\\|.*$");
        Pattern end = Pattern.compile("^(vl\\d+)\\|(\\d+)\\|TRAVEL_END\\|(tr\\d+)\\|.*$");
        Map<String, Long> armed = new LinkedHashMap<>();
        List<Long> immediate = new ArrayList<>();
        List<Long> ordinary = new ArrayList<>();

        for (String line : raw) {
            Matcher s = start.matcher(line);
            if (s.matches()) {
                armed.put(s.group(1) + "@" + s.group(3), Long.parseLong(s.group(2)));
                continue;
            }
            Matcher e = end.matcher(line);
            if (e.matches()) {
                Long at = armed.remove(e.group(1) + "@" + e.group(3));
                if (at != null) {
                    long elapsed = Long.parseLong(e.group(2)) - at;
                    (elapsed <= gap ? immediate : ordinary).add(elapsed);
                }
            }
        }

        assertTrue(!immediate.isEmpty(), "no traversal completed inside its own burst, so DEF-16's"
                + " fire-immediately branch was not taken. tr1's delay must be 0 and the seed must"
                + " be pinned; observed simulated gaps: " + ordinary);
        assertTrue(!ordinary.isEmpty(), "every traversal completed inside its own burst, so the"
                + " ORDINARY branch is missing and the scenario proves only half of what"
                + " COVERAGE.md claims; observed simulated gaps: " + immediate);
    }

    private static long count(List<String> lines, String regex) {
        Pattern p = Pattern.compile(regex);
        return lines.stream().filter(line -> p.matcher(line).find()).count();
    }
}
