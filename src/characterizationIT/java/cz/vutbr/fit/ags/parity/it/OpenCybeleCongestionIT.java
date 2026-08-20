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
 * {@code opencybele-congestion} — the scenario that closes the coverage hole
 * {@code docs/trace-normalizer.md} §2.4 left open, and the test that says so in the form §2.4 asks
 * for.
 *
 * <p>§2.4's finding, in short: {@code ROAD_STATE.state} has to be projected away because on the
 * shipped topology the road's queued path is taken in 1 run of 8, and without the projection a
 * {@code strict} golden flakes at that rate. But the code behind that field is real — {@code push()}
 * → {@code queue.offer} → {@code OueueItem.compareTo} → {@code pop()} → {@code acceptTrain}, a
 * different path through {@code RoadAgent} — and its guidance to #39 is explicit:
 *
 * <blockquote>check {@code ENTER_REPLY}'s position relative to the matching {@code LEAVE} before
 * dismissing it. A port whose {@code ENTER_REPLY} never follows a {@code LEAVE} on a contended
 * track has lost the queue path, and that is a port bug this projection cannot see.</blockquote>
 *
 * <p>So that check is written here, against a scenario built to make the path happen on schedule
 * rather than on luck. Two claims:
 *
 * <ul>
 *   <li>the queued handover <strong>happened</strong> — some train's {@code ENTER} on a track was
 *       answered only after <em>another</em> train's {@code LEAVE} of the same track;</li>
 *   <li>the golden <strong>can see it</strong> — the {@code ENTER} and its {@code ENTER_REPLY} fall
 *       in different bursts of the normalizer that the adapter actually uses, so a port that never
 *       queues produces a different projected trace and fails.</li>
 * </ul>
 *
 * <p>The second claim is the one that keeps the first from being decorative: the projection erases
 * ticks, so a queued handover that landed inside a single burst would be sorted flat and the golden
 * would be identical either way.
 */
class OpenCybeleCongestionIT {

    private static final String SCENARIO = "opencybele-congestion";

    @Test
    @DisplayName("a train is queued on a contended track in the opposing direction, and the golden sees it")
    void congestionScenarioForcesTheRoadQueuePath() {
        assumeTrue(OpenCybeleLauncher.isAvailable(), OpenCybeleLauncher.unavailableMessage());

        ParityLayout layout = ParityLayout.fromSystemProperties();
        ScenarioSpec spec = ScenarioSpecParser.parse(layout.scenariosDir().resolve(SCENARIO + ".yaml"));
        assertEquals(ContractLevel.STRICT, spec.contract());

        RunReport report = ScenarioRunner.usingDefaultLayout().run(spec, new OpenCybeleLauncher());
        List<String> raw = report.captured().lines();
        ScenarioAssertions.assertAdapterContract(spec, raw, report.normalized());

        List<Handover> handovers = queuedHandovers(raw);
        assertTrue(!handovers.isEmpty(), "no train was queued on a track in this run. The scenario"
                + " exists for exactly this and nothing else in the set covers it; check whether the"
                + " topology, the delays or the bound have been retuned, and re-derive the queue"
                + " event before touching this assertion.");
        assertTrue(handovers.stream().anyMatch(Handover::opposingDirection),
                "a train was queued, but never one heading the OTHER way down the track. The"
                        + " opposing-direction case is one of the three edge behaviours #23 names"
                        + " (RoadAgent.java:123-127) and it is what this topology is fed from both"
                        + " ends to produce: " + handovers);
        assertGoldenCanSeeIt(report, handovers);

        if (GoldenStore.recording()) {
            System.out.println("parity: recorded " + report.normalized().size() + " line(s) to "
                    + report.goldenFile() + "; queued handovers: " + handovers);
        }
    }

    /**
     * @param train the queued train
     * @param road the contended track
     * @param enteredAt simulated tick of the queued train's {@code ENTER}
     * @param repliedAt simulated tick of the {@code ENTER_REPLY} sent from inside {@code leave()}
     * @param opposingDirection whether the queued train was heading the other way down the track
     */
    private record Handover(String train, String road, long enteredAt, long repliedAt,
            boolean opposingDirection) {
        @Override
        public String toString() {
            return train + " queued on " + road + " at " + enteredAt + ", handed over at "
                    + repliedAt + (opposingDirection ? " (opposing)" : " (same direction)");
        }
    }

    /**
     * A queued handover, read off the RAW stream: an {@code ENTER} on a track whose
     * {@code ENTER_REPLY} arrives only after some <em>other</em> train's {@code LEAVE} of that same
     * track. In the ordinary path the reply is sent from {@code enter()} itself and there is no
     * intervening {@code LEAVE}.
     */
    private static List<Handover> queuedHandovers(List<String> raw) {
        Pattern enter = Pattern.compile(
                "^(vl\\d+)\\|(\\d+)\\|ENTER\\|[^|]*\\|(tr\\d+)\\|[^|]*\\|train=vl\\d+,position=([^,]*),.*$");
        Pattern reply = Pattern.compile("^(vl\\d+)\\|(\\d+)\\|ENTER_REPLY\\|(tr\\d+)\\|[^|]*\\|[^|]*\\|object=tr\\d+,next=(.*)$");
        Pattern leave = Pattern.compile("^(vl\\d+)\\|(\\d+)\\|LEAVE\\|[^|]*\\|(tr\\d+)\\|.*$");

        Map<String, long[]> pending = new LinkedHashMap<>();   // train@road -> {tick}
        Map<String, String> from = new LinkedHashMap<>();      // train@road -> station entered from
        Map<String, String> travelling = new LinkedHashMap<>();// road -> station the occupant came from
        Map<String, Boolean> sawLeave = new LinkedHashMap<>();
        List<Handover> out = new ArrayList<>();

        for (String line : raw) {
            Matcher e = enter.matcher(line);
            if (e.matches()) {
                String key = e.group(1) + "@" + e.group(3);
                pending.put(key, new long[] {Long.parseLong(e.group(2))});
                from.put(key, e.group(4));
                sawLeave.put(key, Boolean.FALSE);
                continue;
            }
            Matcher l = leave.matcher(line);
            if (l.matches()) {
                // NOT removed on LEAVE: when a queued train's reply arrives, the entry still names
                // the train that was on the track, which is how the opposing-direction test below
                // has something to compare against.
                for (Map.Entry<String, Boolean> waiting : sawLeave.entrySet()) {
                    if (waiting.getKey().endsWith("@" + l.group(3))
                            && !waiting.getKey().startsWith(l.group(1) + "@")) {
                        waiting.setValue(Boolean.TRUE);
                    }
                }
                continue;
            }
            Matcher r = reply.matcher(line);
            if (r.matches()) {
                String key = r.group(1) + "@" + r.group(3);
                long[] entered = pending.remove(key);
                String occupantFrom = travelling.get(r.group(3));
                travelling.put(r.group(3), from.get(key));
                if (entered != null && Boolean.TRUE.equals(sawLeave.remove(key))) {
                    boolean opposing = occupantFrom != null && !occupantFrom.equals(from.get(key));
                    out.add(new Handover(r.group(1), r.group(3), entered[0],
                            Long.parseLong(r.group(2)), opposing));
                }
            }
        }
        return out;
    }

    /**
     * The projection has to keep the fingerprint. {@link CanonicalTraceNormalizer} segments at tick
     * gaps greater than its own {@code segmentGapTicks} and sorts within a segment, so a handover
     * whose wait fell inside one burst would be flattened and the golden would be blind to it.
     *
     * <p>Read from the ADAPTER's normalizer rather than from a fresh default, for the reason
     * {@code OpenCybeleStrictIT} gives: a check against {@code new CanonicalTraceNormalizer()}
     * guards the constant, not the pipeline.
     */
    private static void assertGoldenCanSeeIt(RunReport report, List<Handover> handovers) {
        OpenCybeleLauncher adapter = new OpenCybeleLauncher();
        CanonicalTraceNormalizer normalizer = CanonicalTraceNormalizer.findIn(adapter.normalizer())
                .orElseThrow(() -> new AssertionError("the adapter's normalizer no longer contains a"
                        + " CanonicalTraceNormalizer, so nothing here is measuring the projection"));
        long gap = normalizer.segmentGapTicks();
        List<Handover> visible = handovers.stream()
                .filter(h -> h.repliedAt() - h.enteredAt() > gap)
                .toList();
        assertTrue(!visible.isEmpty(), "every queued handover in this run waited " + gap + " ms or"
                + " less on the simulated clock, so the normalizer sorts its ENTER and ENTER_REPLY"
                + " into one burst and the golden cannot tell the queued path from the ordinary"
                + " one. The scenario is then green and covering nothing. Observed: " + handovers);
        // Belt and braces: the same thing said against the artefact that is actually compared.
        assertTrue(report.normalized().stream().anyMatch(line -> line.contains("|ENTER_REPLY|")),
                "the normalized trace carries no ENTER_REPLY at all");
    }
}
