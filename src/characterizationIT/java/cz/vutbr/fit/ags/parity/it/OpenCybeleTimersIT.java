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
 *   <li><strong>The travel-time noise (CFG-04) is observable on its own</strong>, magnitude and
 *       sign, because {@code tr1}'s nominal delay is 0 and the Gaussian is therefore the whole
 *       delay. On the shipped 1 s roads it is a small perturbation of a large constant and the
 *       burst structure swallows it.</li>
 * </ol>
 *
 * <p><strong>What this scenario does NOT pin, contrary to an earlier revision: DEF-16.</strong> A
 * clamping port ({@code Math.max(0, …)}) was built and produced byte-identical goldens for all four
 * scenarios, because Cybele fires a {@code 0} timer exactly as immediately as a {@code -703} one.
 * The correction and its measurement are in {@code COVERAGE.md} §10.9 and in
 * {@link #assertTheNoiseDecidesBurstMembership}; DEF-16 belongs to #28 as an L1 test on the
 * extracted delay expression.
 *
 * <p><strong>On "not durations".</strong> The noise check below reads the gap between a
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
    @DisplayName("the four timer sites fire the counts the scenario is sized for, and the noise decides burst membership")
    void timerScenarioPinsFiringCountsAndTheTravelNoise() {
        assumeTrue(OpenCybeleLauncher.isAvailable(), OpenCybeleLauncher.unavailableMessage());

        ParityLayout layout = ParityLayout.fromSystemProperties();
        ScenarioSpec spec = ScenarioSpecParser.parse(layout.scenariosDir().resolve(SCENARIO + ".yaml"));
        assertEquals(ContractLevel.STRICT, spec.contract());
        assertTrue(spec.launcher().properties().get("sim.road.delaysSec").contains("tr1=0"),
                "tr1's nominal delay must stay 0: it is the entire mechanism by which the travel"
                        + " noise becomes the whole delay and therefore observable at all. On a 1 s"
                        + " road it is a small perturbation of a large constant and the burst"
                        + " structure swallows it.");

        RunReport report = ScenarioRunner.usingDefaultLayout().run(spec, new OpenCybeleLauncher());
        List<String> raw = report.captured().lines();
        ScenarioAssertions.assertAdapterContract(spec, raw, report.normalized());

        assertFiringCounts(raw);
        assertTheNoiseDecidesBurstMembership(raw);

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
     * CFG-04 — the travel-time noise, magnitude <em>and</em> sign — and <strong>not</strong>
     * DEF-16.
     *
     * <p>An earlier revision of this test called the two sides of the burst boundary "DEF-16's
     * fire-immediately branch" and "the ordinary branch". That was wrong twice over, and the
     * correction is the point of this comment.
     *
     * <ol>
     *   <li>A clamping port — {@code Math.max(0, delayInSeconds() + (long)(500*nextGaussian()))} —
     *       was built, confirmed in bytecode, and produced <strong>byte-identical goldens for all
     *       four scenarios</strong>. Cybele fires a {@code 0} timer exactly as immediately as a
     *       {@code -703} one, so the clamp changes nothing any observer can read. DEF-16 is
     *       unmappable by a golden; it belongs to #28 as an L1 test. COVERAGE.md §10.9.</li>
     *   <li>{@code vl1}'s same-burst placement comes from a <strong>positive 28 ms</strong> draw,
     *       not from a negative one — so "two on the fire-immediately branch" was a
     *       mis-attribution even as a description of this run.</li>
     * </ol>
     *
     * <p>What the assertion below <em>does</em> establish is real and is otherwise unmade anywhere
     * in the set: with {@code tr1}'s nominal delay at 0, the Gaussian is the <em>whole</em> delay,
     * so its magnitude and its sign both decide burst membership. A port that dropped the noise
     * would put every traversal at 0 and lose the over-boundary case; a port that used
     * {@code Math.abs} would push {@code vl0}'s ~-703 to ~+703 and lose the under-boundary case.
     * Both halves are therefore checked, and both are named for what they are — same burst and next
     * burst — rather than for a defect they do not distinguish.
     *
     * <p><strong>On "not durations".</strong> The gap read here is on the <em>simulated</em> clock
     * and is compared against {@link CanonicalTraceNormalizer#DEFAULT_SEGMENT_GAP_TICKS} rather
     * than against a number chosen here, so the claim is "these two lines fall on the same side of
     * the normalizer's burst boundary" — a statement about the projected trace's ordering, and the
     * only form in which this behaviour reaches a golden at all. No wall-clock duration is read
     * anywhere in this file.
     */
    private static void assertTheNoiseDecidesBurstMembership(List<String> raw) {
        long gap = CanonicalTraceNormalizer.DEFAULT_SEGMENT_GAP_TICKS;
        Pattern start = Pattern.compile("^(vl\\d+)\\|(\\d+)\\|TRAVEL_START\\|[^|]*\\|(tr\\d+)\\|.*$");
        Pattern end = Pattern.compile("^(vl\\d+)\\|(\\d+)\\|TRAVEL_END\\|(tr\\d+)\\|.*$");
        Map<String, Long> armed = new LinkedHashMap<>();
        List<Long> sameBurst = new ArrayList<>();
        List<Long> nextBurst = new ArrayList<>();

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
                    (elapsed <= gap ? sameBurst : nextBurst).add(elapsed);
                }
            }
        }

        assertTrue(!sameBurst.isEmpty(), "no traversal completed inside its own burst. On a road"
                + " whose nominal delay is 0 that means the noise never came out at or below zero —"
                + " a port taking Math.abs of the Gaussian looks exactly like this. Observed"
                + " simulated gaps: " + nextBurst);
        assertTrue(!nextBurst.isEmpty(), "every traversal completed inside its own burst, so the"
                + " noise never produced a value over " + gap + " ms — a port that dropped the"
                + " Gaussian entirely looks exactly like this. Observed simulated gaps: "
                + sameBurst);
    }

    private static long count(List<String> lines, String regex) {
        Pattern p = Pattern.compile(regex);
        return lines.stream().filter(line -> p.matcher(line).find()).count();
    }
}
