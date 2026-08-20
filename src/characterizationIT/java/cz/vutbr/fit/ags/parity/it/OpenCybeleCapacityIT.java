package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code opencybele-capacity} — a train refused entry to a full station, and the cascade behind it.
 *
 * <p>This scenario exists because the first revision of #23 recorded {@code Station.enter}'s
 * {@code queue.offer} branch as <em>unreachable</em>, on the strength of a detector that could not
 * see it: a queued train usually receives <strong>no reply at all</strong>, and the detector looked
 * for a <em>late</em> one. The three claims below are asserted here rather than left to the golden
 * so that the retraction cannot silently un-happen.
 *
 * <ol>
 *   <li><strong>The station queue fired.</strong> Station {@code ENTER}s outnumber station
 *       {@code ENTER_REPLY}s, which is only possible if {@code enter} took the capacity branch.</li>
 *   <li><strong>The road queue reached depth two</strong>, which is the only condition under which
 *       {@code java.util.PriorityQueue.offer} performs a comparison — and therefore the only way
 *       {@code OueueItem.compareTo}, with DEF-03's narrowing and DEF-04's dead tie-break, runs at
 *       all. In the other four scenarios it never does.</li>
 *   <li><strong>{@code sim.station.voteWindowMs} is visible in the trace.</strong> #18 split one
 *       constant into an arrival rate and a planning horizon; every other scenario sets the two
 *       equal, so a port that never split them is indistinguishable. Here the station votes carry
 *       delays derived from {@code voteWindow} that clear the normalizer's 1000 ms {@code diff}
 *       quantum.</li>
 * </ol>
 */
class OpenCybeleCapacityIT {

    private static final String SCENARIO = "opencybele-capacity";

    @Test
    @DisplayName("a full station refuses a train, a road queue reaches depth two, and voteWindow is visible")
    void capacityScenarioReachesTheBranchesNothingElseDoes() {
        assumeTrue(OpenCybeleLauncher.isAvailable(), OpenCybeleLauncher.unavailableMessage());

        ParityLayout layout = ParityLayout.fromSystemProperties();
        ScenarioSpec spec = ScenarioSpecParser.parse(layout.scenariosDir().resolve(SCENARIO + ".yaml"));
        assertEquals(ContractLevel.STRICT, spec.contract());
        Map<String, String> props = spec.launcher().properties();
        assertNotEquals(props.get("sim.arrival.lambdaMs"), props.get("sim.station.voteWindowMs"),
                "this scenario is the only place CFG-06 — #18's split of Generator.LAMBDA into an"
                        + " arrival rate and a planning horizon — is observable. With the two equal,"
                        + " a port that never split the constant produces an identical golden.");
        assertTrue(props.get("sim.station.capacities").contains("stB=1"),
                "stB's capacity of 1 is the mechanism; without it nothing here fires");

        RunReport report = ScenarioRunner.usingDefaultLayout().run(spec, new OpenCybeleLauncher());
        List<String> raw = report.captured().lines();
        ScenarioAssertions.assertAdapterContract(spec, raw, report.normalized());

        assertAStationRefusedATrain(raw);
        assertARoadQueueReachedDepthTwo(raw);
        assertVoteWindowSurvivesTheQuantum(report.normalized());

        if (GoldenStore.recording()) {
            System.out.println("parity: recorded " + report.normalized().size() + " line(s) to "
                    + report.goldenFile());
        }
    }

    /**
     * INVENTORY EVT-12 / ST-16. {@code Station.enter} either admits (increment, send
     * {@code ENTER_REPLY}) or enqueues (no reply at all), and both paths end in {@code sendInfo()}.
     * So the signature is an <strong>imbalance</strong>: an {@code ENTER} on a station with no
     * {@code ENTER_REPLY} from that station for that train, anywhere in the trace.
     *
     * <p>Read from the RAW stream. The projected trace would do as well — no rule touches these
     * lines — but the raw one is where the claim is easiest to check by hand against a capture.
     */
    private static void assertAStationRefusedATrain(List<String> raw) {
        Pattern enter = Pattern.compile("^(vl\\d+)\\|\\d+\\|ENTER\\|[^|]*\\|(st[A-Z])\\|.*$");
        Pattern reply = Pattern.compile("^(vl\\d+)\\|\\d+\\|ENTER_REPLY\\|(st[A-Z])\\|.*$");
        Set<String> entered = new LinkedHashSet<>();
        Set<String> admitted = new LinkedHashSet<>();
        for (String line : raw) {
            Matcher e = enter.matcher(line);
            if (e.matches()) {
                entered.add(e.group(1) + "@" + e.group(2));
                continue;
            }
            Matcher r = reply.matcher(line);
            if (r.matches()) {
                admitted.add(r.group(1) + "@" + r.group(2));
            }
        }
        Set<String> refused = new LinkedHashSet<>(entered);
        refused.removeAll(admitted);
        assertTrue(!refused.isEmpty(), "every station ENTER in this run was answered, so"
                + " Station.enter never took its `info.occupied == info.capacity` branch and the"
                + " scenario is covering nothing it was built for. " + entered.size() + " ENTER(s),"
                + " " + admitted.size() + " reply(s). Re-derive the queue event before touching this"
                + " assertion — COVERAGE.md §10.1 records what the lever is.");
    }

    /**
     * INVENTORY ST-21, and with it DEF-03 and DEF-04.
     *
     * <p>{@code RoadAgent.queue} is a {@code java.util.PriorityQueue}, and {@code offer} on an
     * <em>empty</em> queue performs no comparison at all — it writes the element at index 0 and
     * returns. So a road that only ever holds one queued train never calls
     * {@code OueueItem.compareTo}. Depth two is the threshold, and this is the only scenario that
     * reaches it.
     */
    private static void assertARoadQueueReachedDepthTwo(List<String> raw) {
        Pattern enter = Pattern.compile("^(vl\\d+)\\|(\\d+)\\|ENTER\\|[^|]*\\|(tr\\d+)\\|.*$");
        Pattern reply = Pattern.compile("^(vl\\d+)\\|\\d+\\|ENTER_REPLY\\|(tr\\d+)\\|.*$");
        Map<String, List<String>> pending = new LinkedHashMap<>();
        List<String> depthTwo = new ArrayList<>();
        for (String line : raw) {
            Matcher e = enter.matcher(line);
            if (e.matches()) {
                List<String> waiting = pending.computeIfAbsent(e.group(3), k -> new ArrayList<>());
                waiting.add(e.group(1));
                if (waiting.size() >= 2) {
                    depthTwo.add(e.group(3) + " held " + waiting + " at tick " + e.group(2));
                }
                continue;
            }
            Matcher r = reply.matcher(line);
            if (r.matches()) {
                pending.getOrDefault(r.group(2), new ArrayList<>()).remove(r.group(1));
            }
        }
        assertTrue(!depthTwo.isEmpty(), "no road ever held two trains at once, so"
                + " PriorityQueue.offer never compared anything and OueueItem.compareTo — with"
                + " DEF-03's int narrowing and DEF-04's dead tie-break inside it — did not run."
                + " This is the only scenario in the set that reaches it; losing that silently is"
                + " what this assertion prevents.");
    }

    /**
     * INVENTORY CFG-06 and ST-17's under-capacity arm.
     *
     * <p>{@code Station.computeDifference} returns {@code plannedTrains*voteWindow/6} when the
     * station is under capacity. At {@code voteWindow = 6000} that is 1000 or 2000 — above
     * {@code CanonicalTraceNormalizer.TIME_QUANTUM}, so it survives into the golden as
     * {@code diff=<T~1000>} or better. At the 2000–3500 used elsewhere the same arithmetic yields
     * 333, 583 and 666, all of which render as {@code diff=<T~0>} and pin nothing — which is
     * exactly how an implementation mutant that returned {@code 0} from this branch was found to
     * pass the whole suite.
     */
    private static void assertVoteWindowSurvivesTheQuantum(List<String> trace) {
        Pattern stationVote = Pattern.compile(
                "^vl\\d+\\|<T>\\|VOTE\\|st[A-Z]\\|[^|]*\\|[^|]*\\|voter=st[A-Z],train=vl\\d+,diff=<T~(\\d+)>$");
        List<String> nonZero = new ArrayList<>();
        for (String line : trace) {
            Matcher m = stationVote.matcher(line);
            if (m.matches() && !"0".equals(m.group(1))) {
                nonZero.add(line);
            }
        }
        assertTrue(!nonZero.isEmpty(), "every station vote in the NORMALIZED trace reads"
                + " diff=<T~0>, so Station.computeDifference could return a constant zero and this"
                + " golden would not notice — which is what an implementation mutant demonstrated"
                + " on the pre-revision scenario set. sim.station.voteWindowMs must be large enough"
                + " that plannedTrains*voteWindow/6 clears the normalizer's 1000 ms diff quantum.");
    }
}
