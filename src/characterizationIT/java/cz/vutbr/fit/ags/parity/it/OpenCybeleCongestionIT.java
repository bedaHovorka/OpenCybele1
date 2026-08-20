package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
import cz.vutbr.fit.ags.parity.it.TrainTrace.Traversal;
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
import java.util.List;
import java.util.Optional;

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
 * rather than on luck. Three claims:
 *
 * <ul>
 *   <li>the queued handover <strong>happened</strong> — a train asked a road to admit it while
 *       another train was still on that road, so {@code RoadAgent.enter} took its
 *       {@code state != FREE} branch;</li>
 *   <li>the queued train was heading <strong>the other way</strong>, which is
 *       {@code acceptTrain}'s else-arm and one of the three edge behaviours #23 names;</li>
 *   <li>the golden <strong>can see it</strong> — the {@code ENTER} and its {@code ENTER_REPLY} fall
 *       in different bursts of the normalizer that the adapter actually uses, so a port that never
 *       queues produces a different projected trace and fails.</li>
 * </ul>
 *
 * <p>The third claim is the one that keeps the first two from being decorative: the projection
 * erases ticks, so a queued handover that landed inside a single burst would be sorted flat and the
 * golden would be identical either way. It is also what makes this scenario, and only this
 * scenario, able to pin <strong>DEF-07</strong> — see {@code assertDef07SurvivesTheProjection}.
 *
 * <h2>#72: none of this is read off the order of the raw lines any more</h2>
 *
 * <p>The first revision of this test reconstructed the handover, and the direction, from where the
 * {@code ENTER}/{@code ENTER_REPLY}/{@code LEAVE} lines fell relative to one another, and kept a
 * cross-train {@code road -> station-the-occupant-came-from} register while it walked the stream.
 * That order is not a fact about the application: the probe stamps and prints at <em>handling</em>
 * time, and two lines that share a tick come out in whichever order the kernel delivered them
 * ({@code docs/trace-format.md}). Measured over 40 captures of this scenario, 2 of them print an
 * {@code ENTER_REPLY} <em>before</em> the {@code ENTER} it answers; the register then took
 * {@code null} for that road, and the opposing-direction claim collapsed to {@code false} for the
 * rest of the run. Everything below is derived from payload content and from tick intervals whose
 * width is seconds — see {@link TrainTrace} and {@code docs/raw-assertion-audit.md}.
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

        TrainTrace causal = TrainTrace.of(raw);
        assertTrue(causal.malformed().isEmpty(), "the causal reconstruction is unsound for this"
                + " capture, so nothing below means what it says: " + causal.malformed());

        List<Handover> handovers = queuedHandovers(causal);
        assertTrue(!handovers.isEmpty(), "no train was queued on a track in this run. The scenario"
                + " exists for exactly this and nothing else in the set covers it; check whether the"
                + " topology, the delays or the bound have been retuned, and re-derive the queue"
                + " event before touching this assertion. Road traversals seen: "
                + causal.roadTraversals());
        assertTrue(handovers.stream().anyMatch(Handover::opposingDirection),
                "a train was queued, but never one heading the OTHER way down the track. The"
                        + " opposing-direction case is one of the three edge behaviours #23 names"
                        + " (RoadAgent.java:123-127) and it is what this topology is fed from both"
                        + " ends to produce: " + handovers);

        long gap = adapterBurstWidth();
        assertGoldenCanSeeIt(report, handovers, gap);
        assertDef07SurvivesTheProjection(causal, handovers, gap);

        if (GoldenStore.recording()) {
            System.out.println("parity: recorded " + report.normalized().size() + " line(s) to "
                    + report.goldenFile() + "; queued handovers: " + handovers);
        }
    }

    /**
     * A queued handover, and the traversal it was queued behind.
     *
     * @param queued   the train that had to wait
     * @param occupant the train that was on the track when it asked, and whose {@code LEAVE} is
     *                 what {@code RoadAgent.leave} answered the request from
     */
    private record Handover(Traversal queued, Traversal occupant) {

        /**
         * Whether the queued train was heading the other way down the track.
         *
         * <p>Read from {@code ENTER.position} and {@code ENTER_REPLY.next} — the two ends of each
         * traversal, both of them payload — and never from {@code ROAD_STATE.state}, which the
         * {@code road-state} projection erases. {@code RoadAgent.acceptTrain} replies with
         * {@code rightStation} when the train came from the left and with {@code leftStation} when
         * it came from the right, so a reversed pair of traversals is exactly
         * {@code from == other.to && to == other.from}.
         */
        boolean opposingDirection() {
            return queued.opposes(occupant);
        }

        @Override
        public String toString() {
            return queued + " behind " + occupant
                    + (opposingDirection() ? " (opposing)" : " (same direction)");
        }
    }

    /**
     * Every queued handover in the run, derived <strong>causally</strong>.
     *
     * <p>{@code RoadAgent.enter} pushes onto the queue exactly when {@code state != FREE}, i.e.
     * exactly when another train is on the road at that moment. So a handover is a traversal whose
     * {@code ENTER} tick falls inside another traversal's {@code [ENTER_REPLY, LEAVE)} interval —
     * an interval seconds wide, on the simulated clock, with no reference to where any line landed
     * in the stream. In the ordinary path the road is {@code FREE}, no traversal contains the
     * request, and nothing is reported.
     */
    private static List<Handover> queuedHandovers(TrainTrace causal) {
        List<Handover> out = new ArrayList<>();
        for (Traversal traversal : causal.roadTraversals()) {
            Optional<Traversal> occupant = causal.occupantWhenRequested(traversal);
            occupant.ifPresent(held -> out.add(new Handover(traversal, held)));
        }
        return List.copyOf(out);
    }

    /**
     * Read the burst width from the ADAPTER's normalizer rather than from a fresh default, for the
     * reason {@code OpenCybeleStrictIT} gives: a check against {@code new CanonicalTraceNormalizer()}
     * guards the constant, not the pipeline.
     */
    private static long adapterBurstWidth() {
        OpenCybeleLauncher adapter = new OpenCybeleLauncher();
        return CanonicalTraceNormalizer.findIn(adapter.normalizer())
                .orElseThrow(() -> new AssertionError("the adapter's normalizer no longer contains a"
                        + " CanonicalTraceNormalizer, so nothing here is measuring the projection"))
                .segmentGapTicks();
    }

    /**
     * The projection has to keep the fingerprint. {@link CanonicalTraceNormalizer} segments at tick
     * gaps greater than its own {@code segmentGapTicks} and sorts within a segment, so a handover
     * whose wait fell inside one burst would be flattened and the golden would be blind to it.
     *
     * <p>The wait is bimodal by construction and the measurement is in
     * {@code docs/raw-assertion-audit.md} §3: over 40 captures, 480 admissions took 0–32 ms and 40
     * took 424–448 ms, with nothing in between. The 220 ms boundary sits in that empty band, which
     * is what makes this a reading of the data rather than a tolerance.
     */
    private static void assertGoldenCanSeeIt(RunReport report, List<Handover> handovers, long gap) {
        List<Handover> visible = handovers.stream()
                .filter(h -> h.queued().waited() > gap)
                .toList();
        assertTrue(!visible.isEmpty(), "every queued handover in this run waited " + gap + " ms or"
                + " less on the simulated clock, so the normalizer sorts its ENTER and ENTER_REPLY"
                + " into one burst and the golden cannot tell the queued path from the ordinary"
                + " one. The scenario is then green and covering nothing. Observed: " + handovers);
        // Belt and braces: the same thing said against the artefact that is actually compared.
        assertTrue(report.normalized().stream().anyMatch(line -> line.contains("|ENTER_REPLY|")),
                "the normalized trace carries no ENTER_REPLY at all");
    }

    /**
     * <strong>DEF-07, pinned where the projection can carry it — which is here and nowhere else.</strong>
     *
     * <p>{@code docs/defect-triage.md} §3.1 classes DEF-07 (a), deterministic, "the port must
     * reproduce it": {@code Train.entered} sends {@code LEAVE} to the OLD object only after the NEW
     * object has already admitted the train and incremented its occupancy. The classification is
     * right. What was wrong, until #72, was <em>where</em> that got checked: on an uncontended hop
     * the {@code ENTER_REPLY} comes back inside the same tick, so the {@code LEAVE} lands in the
     * same burst as the {@code ENTER} that preceded it, the normalizer sorts the burst, and the
     * emission order is gone from the golden — {@code parity-tests/golden/opencybele-lifecycle.txt}
     * even lists a train's two final {@code LEAVE}s in the opposite order to the one it emitted
     * them in. Reading that order off the raw stream instead is no better: it is the probe's
     * handling order, and it inverts a few percent of the time.
     *
     * <p>On a <em>queued</em> hop the same defect is separated by the whole wait. The train asks the
     * road to admit it, the road makes it wait 424–448 ms, and only when the reply finally arrives
     * does {@code Train.entered} run and release the station the train came from. So
     * {@code LEAVE|<train>|<origin>} lands in a LATER burst than {@code ENTER|<train>|<road>} — and
     * that <strong>is</strong> in the golden, positionally, at {@code strict}. A port that released
     * the origin before asking for admission would emit that {@code LEAVE} in the earlier burst and
     * diff.
     */
    private static void assertDef07SurvivesTheProjection(TrainTrace causal, List<Handover> handovers,
            long gap) {
        List<String> witnesses = new ArrayList<>();
        List<String> examined = new ArrayList<>();
        for (Handover handover : handovers) {
            Traversal queued = handover.queued();
            List<Long> released = causal.leaves(queued.train(), queued.from());
            if (released.isEmpty()) {
                continue;
            }
            long delay = released.get(0) - queued.requestedAt();
            String observation = queued.train() + " released " + queued.from() + " " + delay
                    + " ms after asking " + queued.road() + " to admit it";
            examined.add(observation);
            if (delay > gap) {
                witnesses.add(observation);
            }
        }
        assertTrue(!witnesses.isEmpty(), "DEF-07 is no longer pinned anywhere. It is deterministic"
                + " (docs/defect-triage.md §3.1, class (a)) but it is only VISIBLE on a queued hop,"
                + " where Train.entered's LEAVE for the origin is deferred past the burst boundary"
                + " and therefore reaches the golden. In this run no queued train's LEAVE of its"
                + " origin was more than " + gap + " ms after its ENTER on the track, so the"
                + " ordering has collapsed back into one burst and a port that leaves before it"
                + " enters would pass the whole suite. Observed: " + examined);
    }
}
