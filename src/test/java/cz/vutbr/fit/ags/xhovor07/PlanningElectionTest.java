/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import cz.vutbr.fit.ags.railway.domain.VoteRound;
import cz.vutbr.fit.ags.railway.domain.clock.SimClock;
import cz.vutbr.fit.ags.railway.domain.clock.VirtualClock;
import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.PlanTrain;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.StartCommand;
import cz.vutbr.fit.ags.railway.domain.msg.Vote;
import cz.vutbr.fit.ags.railway.domain.msg.VoteRequest;
import cz.vutbr.fit.ags.railway.domain.msg.VoteResult;
import cz.vutbr.fit.ags.railway.domain.util.HashMapGraph;
import cz.vutbr.fit.ags.railway.domain.util.UnorientedGraph;
import cz.vutbr.fit.ags.railway.jade.Messages;
import jade.core.Agent;
import jade.core.JadeAgentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 for #34: the election as a POJO — a real {@link Planning} installed on a real (unstarted)
 * JADE agent, its outbound seam redirected into a list, and simulated time a
 * {@link VirtualClock} the test owns ({@code docs/TESTING.md} §4.1).
 * <p>
 * No container, no platform, no threads — which matters more here than in the two sibling
 * suites, because the thing being replaced <em>was</em> a thread. "The last vote closes the
 * round" is an assertion in this file rather than a race, and the drain
 * {@code ClockTickerBehaviour} performs on the agent thread is performed by the test through
 * {@code agentClock().runDue()}: same call, same thread rules, no ticker.
 * <p>
 * The topology is a three-station line so that the numbers can be read off by hand:
 * <pre>
 *   stA --tr1(1s)-- stB --tr2(2s)-- stC
 * </pre>
 * so a {@code stA -> stC} path is {@code [stA, tr1, stB, tr2, stC]} and
 * {@code DispatchTimeline.accumulate} from {@code t} gives
 * {@code [t, t, t+1000, t+1000, t+3000]}.
 * <p>
 * What each group holds down:
 * <ul>
 *   <li><b>the round trip</b> — one {@code VOTE_REQUEST} per path member with the accumulated
 *       estimate, then one {@code VOTE_RESULT} per path member with the accumulated
 *       <em>agreed</em> instant, and the envelope delegated to #28's {@code VoteEnvelope};</li>
 *   <li><b>the absolute instant</b>, which is the finding {@code clock-abstraction.md} §2.5
 *       measured: a planner that arms a <em>relative</em> delay drifts by whatever simulated
 *       time passed while the votes were in flight;</li>
 *   <li><b>DEF-05 and DEF-09</b>, driven through the planner — which is where a port that had
 *       not read {@code defect-triage.md} would repair them;</li>
 *   <li><b>DEF-22's branch</b>: it reports, and it leaves the train dead.</li>
 * </ul>
 */
class PlanningElectionTest {

    private static final List<String> PATH = List.of("stA", "tr1", "stB", "tr2", "stC");
    private static final long START_MS = 1000L;
    /** The envelope of the ballots cast below: the largest vote wins. */
    private static final long ENVELOPE = 4200L;
    private static final long DEPARTURE = START_MS + ENVELOPE;

    /** One message the planner tried to send. */
    private record Sent(RailwayMessage message, String receiver) {
        Channel channel() {
            return message.channel();
        }
    }

    /** A real {@link Planning} with its outbound seam redirected into a list. */
    private static final class RecordingPlanning extends Planning {
        private static final long serialVersionUID = 1L;
        private final transient List<Sent> sent = new ArrayList<Sent>();

        RecordingPlanning(Agent host, UnorientedGraph<String, String> net,
                Map<String, Long> roadDelays, SimClock clock) {
            super(host, net, roadDelays, clock);
        }

        @Override
        protected void emit(RailwayMessage message, String receiver) {
            sent.add(new Sent(message, receiver));
        }

        List<Sent> of(Channel channel) {
            List<Sent> out = new ArrayList<Sent>();
            for (Sent s : sent) {
                if (s.channel() == channel) {
                    out.add(s);
                }
            }
            return out;
        }
    }

    private static UnorientedGraph<String, String> net() {
        UnorientedGraph<String, String> net = new HashMapGraph<String, String>();
        net.put("stA", "stB", "tr1");
        net.put("stB", "stC", "tr2");
        return net;
    }

    private static Map<String, Long> delays() {
        Map<String, Long> delays = new HashMap<String, Long>();
        delays.put("tr1", Long.valueOf(1L));
        delays.put("tr2", Long.valueOf(2L));
        return delays;
    }

    /** The planner, its host agent and the clock they share. */
    private record Fixture(RecordingPlanning planning, Agent host, VirtualClock clock) {
    }

    private static Fixture fixture() {
        return fixture(1.0);
    }

    private static Fixture fixture(double pace) {
        Agent host = new Agent();
        JadeAgentFixture.name(host, RailwayMainAgent.MAIN_AGENT_NAME);
        VirtualClock clock = new VirtualClock(START_MS, pace);
        RecordingPlanning planning = new RecordingPlanning(host, net(), delays(), clock);
        return new Fixture(planning, host, clock);
    }

    private static void deliver(Fixture f, RailwayMessage message, String from) {
        f.planning().dispatch(Messages.build(message, from, RailwayMainAgent.MAIN_AGENT_NAME));
    }

    /** Casts every vote but the last one; {@code lastVoter} is left to the caller. */
    private static void voteAllBut(Fixture f, String train, String lastVoter) {
        for (String voter : PATH) {
            if (!voter.equals(lastVoter)) {
                deliver(f, new Vote(voter, train, ballotOf(voter)), voter);
            }
        }
    }

    /** stB is the constrained member; everybody else is happy to leave now. */
    private static long ballotOf(String voter) {
        return "stB".equals(voter) ? ENVELOPE : 0L;
    }

    @Test
    @DisplayName("the election opens with one cfp per path member, carrying the accumulated estimate")
    void the_broadcast_is_one_vote_request_per_path_member() {
        Fixture f = fixture();

        deliver(f, new PlanTrain("vl0", "stA", "stC"), RailwayMainAgent.MAIN_AGENT_NAME);

        List<Sent> requests = f.planning().of(Channel.VOTE_REQUEST);
        assertEquals(5, requests.size());
        assertEquals(PATH, requests.stream().map(Sent::receiver).toList(),
                "in path order, station-track-station");
        assertEquals(List.of(1000L, 1000L, 2000L, 2000L, 4000L),
                requests.stream().map(s -> Long.valueOf(((VoteRequest) s.message()).expected()))
                        .map(Long::longValue).toList(),
                "DispatchTimeline: a station contributes nothing, a track its travel time");
        assertEquals("vl0", ((VoteRequest) requests.get(0).message()).train());

        VoteRound round = f.planning().electionInFlight();
        assertNotNull(round, "the round is registered before the first request goes out");
        assertEquals(PATH, round.path(), "the expected-responder set is explicit");
        assertEquals(5, round.outstanding());
        assertEquals(0, f.planning().plannedCount(), "nothing is planned until the votes are in");
    }

    @Test
    @DisplayName("the last vote closes the round exactly once and broadcasts the agreed departure")
    void the_last_vote_closes_the_round() {
        Fixture f = fixture();
        deliver(f, new PlanTrain("vl0", "stA", "stC"), RailwayMainAgent.MAIN_AGENT_NAME);

        voteAllBut(f, "vl0", "stC");
        assertTrue(f.planning().of(Channel.VOTE_RESULT).isEmpty(),
                "four of five votes decide nothing");
        assertNotNull(f.planning().electionInFlight());

        deliver(f, new Vote("stC", "vl0", 0L), "stC");

        List<Sent> results = f.planning().of(Channel.VOTE_RESULT);
        assertEquals(5, results.size(), "one accept-proposal per path member, winner or not");
        assertEquals(PATH, results.stream().map(Sent::receiver).toList());
        assertEquals(List.of(DEPARTURE, DEPARTURE, DEPARTURE + 1000, DEPARTURE + 1000,
                DEPARTURE + 3000),
                results.stream().map(s -> Long.valueOf(((VoteResult) s.message()).planned()))
                        .map(Long::longValue).toList(),
                "the envelope (max) applied to the request time, then re-accumulated");
        assertNull(f.planning().electionInFlight(), "the round is cleared, as :89 cleared the latch");
        assertEquals(1, f.planning().plannedCount());
        assertEquals(1, f.planning().agentClock().pending(), "exactly one departure armed");
    }

    @Test
    @DisplayName("the departure is armed on the absolute instant, so simulated time passing"
            + " during the election does not move it")
    void the_departure_does_not_drift_with_the_votes() {
        Fixture f = fixture();
        deliver(f, new PlanTrain("vl0", "stA", "stC"), RailwayMainAgent.MAIN_AGENT_NAME);

        // The votes take 2 simulated seconds to come back. On Cybele this is exactly the gap
        // probes/ExpJ.java measured: setTimer read the clock AGAIN, so a relative delay armed
        // here would have fired 2000 ms late, and the 2008 pauseClock bracket existed to stop
        // that. AgentClock.scheduleAt takes the instant the round already agreed on.
        f.clock().advanceSimBy(2000L);
        voteAllBut(f, "vl0", "stC");
        deliver(f, new Vote("stC", "vl0", 0L), "stC");

        assertEquals(DEPARTURE, ((VoteResult) f.planning().of(Channel.VOTE_RESULT).get(0)
                .message()).planned());

        f.clock().advanceSimTo(DEPARTURE - 1);
        assertEquals(0, f.planning().agentClock().runDue(), "not due yet");
        f.clock().advanceSimTo(DEPARTURE);
        assertEquals(1, f.planning().agentClock().runDue(),
                "due at requestTime+envelope, NOT at lastVoteTime+envelope");
    }

    @Test
    @DisplayName("the wake-up prints the departure line and sends START to the train")
    void the_wake_up_starts_the_train() {
        Fixture f = fixture();
        deliver(f, new PlanTrain("vl0", "stA", "stC"), RailwayMainAgent.MAIN_AGENT_NAME);
        voteAllBut(f, "vl0", "stC");
        deliver(f, new Vote("stC", "vl0", 0L), "stC");

        f.clock().advanceSimTo(DEPARTURE);
        String out = drainCapturingStdout(f);

        assertEquals("vl0 in stA at " + DEPARTURE, out.strip(),
                "TrainPlan.toString is trace-visible; a space here is a golden diff");
        List<Sent> starts = f.planning().of(Channel.START);
        assertEquals(1, starts.size());
        assertEquals("vl0", starts.get(0).receiver(), "addressed to the train's AID");
        assertEquals("stA", ((StartCommand) starts.get(0).message()).station());
        assertEquals(0, f.planning().plannedCount(), "the plan left the queue");
    }

    @Test
    @DisplayName("DEF-05: a duplicate vote closes the round with a short ballot,"
            + " and the 2008 assert catches it before anything is broadcast")
    void def05_a_duplicate_vote_closes_the_round_with_a_short_ballot() {
        Fixture f = fixture();
        deliver(f, new PlanTrain("vl0", "stA", "stC"), RailwayMainAgent.MAIN_AGENT_NAME);

        deliver(f, new Vote("stA", "vl0", 0L), "stA");
        deliver(f, new Vote("tr1", "vl0", 0L), "tr1");
        deliver(f, new Vote("stB", "vl0", ENVELOPE), "stB");
        deliver(f, new Vote("tr2", "vl0", 0L), "tr2");
        // stC's vote is lost; tr2 votes twice instead. Five arrivals, four distinct voters.
        // The explicit responder set makes it a one-line temptation to drop this arrival on the
        // floor -- that would be a repair, and defect-triage.md 6.1 says PIN.
        VoteRound round = f.planning().electionInFlight();
        assertThrows(AssertionError.class, () -> deliver(f, new Vote("tr2", "vl0", 0L), "tr2"),
                "Planning.java:96's assert v.size() == path.size(), reproduced;"
                        + " with -ea off it is Collections.max over a short list instead");

        assertEquals(List.of("stC"), round.missingResponders(),
                "the round closed with a voter that never answered");
        assertEquals(4, round.ballots().size());
        assertTrue(f.planning().of(Channel.VOTE_RESULT).isEmpty(),
                "the assert fires before the broadcast, exactly as at :96");
        assertNull(f.planning().electionInFlight(), "and :89 had already cleared the round");
        assertEquals(0, f.planning().plannedCount());
        assertEquals(0, f.planning().agentClock().pending(), "the train stays dead");
    }

    @Test
    @DisplayName("DEF-09: a vote for a train with no round aborts the handler, unguarded")
    void def09_a_late_or_foreign_vote_aborts_the_handler() {
        Fixture f = fixture();
        deliver(f, new PlanTrain("vl0", "stA", "stC"), RailwayMainAgent.MAIN_AGENT_NAME);

        // VoteCollecting.java:53-54 -- assert containsKey(train), then get(train).countDown().
        // With -ea it is an AssertionError; with -ea off the very next line dereferences null.
        // Neither is guarded, here or there.
        assertThrows(AssertionError.class, () -> deliver(f, new Vote("stA", "vl9", 0L), "stA"));

        voteAllBut(f, "vl0", "stC");
        deliver(f, new Vote("stC", "vl0", 0L), "stC");
        assertThrows(AssertionError.class, () -> deliver(f, new Vote("stC", "vl0", 0L), "stC"),
                "and once the round is closed and cleared, a late vote is the same fault");
    }

    @Test
    @DisplayName("DEF-17: a wake-up polls the queue head, not the plan it was armed for")
    void def17_the_wake_up_polls_the_queue_head() {
        Fixture f = fixture();

        // Two elections at the same instant with the same envelope, so the two plans tie on
        // departure and TrainPlan.compareTo falls through to the train id. vl1's wake-up is
        // armed FIRST and therefore fires first -- and departs vl0, because vl0 is the head.
        deliver(f, new PlanTrain("vl1", "stC", "stA"), RailwayMainAgent.MAIN_AGENT_NAME);
        voteAllBut(f, "vl1", "stC");
        deliver(f, new Vote("stC", "vl1", 0L), "stC");
        deliver(f, new PlanTrain("vl0", "stA", "stC"), RailwayMainAgent.MAIN_AGENT_NAME);
        voteAllBut(f, "vl0", "stC");
        deliver(f, new Vote("stC", "vl0", 0L), "stC");
        assertEquals(2, f.planning().plannedCount());

        f.clock().advanceSimTo(DEPARTURE);
        String out = drainCapturingStdout(f);

        assertEquals(List.of("vl0 in stA at " + DEPARTURE, "vl1 in stC at " + DEPARTURE),
                out.lines().toList(),
                "queue order, not arming order -- a planner that departed its own plan would"
                        + " print vl1 first");
        assertEquals(List.of("vl0", "vl1"),
                f.planning().of(Channel.START).stream().map(Sent::receiver).toList());
    }

    @Test
    @DisplayName("DEF-22: the watchdog reports an incomplete election once, and leaves it open")
    void def22_the_watchdog_reports_and_does_not_resume() {
        Fixture f = fixture();
        deliver(f, new PlanTrain("vl0", "stA", "stC"), RailwayMainAgent.MAIN_AGENT_NAME);
        deliver(f, new Vote("stA", "vl0", 0L), "stA");
        deliver(f, new Vote("tr1", "vl0", 0L), "tr1");
        VoteRound round = f.planning().electionInFlight();
        long opened = round.openedAtNanos();

        assertEquals("", captureErr(() -> f.planning()
                .reportOverdue(opened + (Planning.VOTE_WATCHDOG_MS - 1) * 1000000L)).strip(),
                "a healthy round must never trip it");

        String report = captureErr(() -> f.planning()
                .reportOverdue(opened + (Planning.VOTE_WATCHDOG_MS + 1) * 1000000L));
        assertTrue(report.contains("vl0"), () -> report);
        assertTrue(report.contains("DEF-22"), () -> report);
        assertTrue(report.contains("[stB, tr2, stC]"),
                () -> "it must name the silent voters, which the 2008 latch could not: " + report);

        // The whole point: reporting is not resuming.
        assertTrue(f.planning().of(Channel.VOTE_RESULT).isEmpty(),
                "no envelope synthesised from the two votes received");
        assertTrue(f.planning().of(Channel.START).isEmpty());
        assertEquals(0, f.planning().plannedCount(), "the train never departs -- the hang's outcome");
        assertEquals(0, f.planning().agentClock().pending());
        assertEquals(round, f.planning().electionInFlight(),
                "and the round is NOT abandoned: DEF-22 leaked its entry, so does this");

        assertEquals("", captureErr(() -> f.planning()
                .reportOverdue(opened + 600_000L * 1000000L)).strip(),
                "once per round, or the report drowns ErrorScanner instead of informing it");
    }

    @Test
    @DisplayName("a vote arriving after the alarm still closes the round, as the armed latch would have")
    void a_very_late_vote_still_completes_a_reported_round() {
        Fixture f = fixture();
        deliver(f, new PlanTrain("vl0", "stA", "stC"), RailwayMainAgent.MAIN_AGENT_NAME);
        voteAllBut(f, "vl0", "stC");
        long opened = f.planning().electionInFlight().openedAtNanos();
        captureErr(() -> f.planning()
                .reportOverdue(opened + (Planning.VOTE_WATCHDOG_MS + 1) * 1000000L));

        deliver(f, new Vote("stC", "vl0", 0L), "stC");

        assertEquals(5, f.planning().of(Channel.VOTE_RESULT).size(),
                "the 2008 latch stayed armed after a lost vote; abandoning the round on the"
                        + " alarm would diverge from that AND open a fresh path into DEF-09");
        assertEquals(1, f.planning().plannedCount());
    }

    @Test
    @DisplayName("the ticker period comes from the shared granularity seam, not a second constant")
    void the_ticker_budget_is_the_road_agent_seam() {
        assertEquals(RoadAgent.granularityMsFor(8.0), fixture(8.0).planning().tickPeriodMs());
        assertEquals(RoadAgent.granularityMsFor(1.0), fixture(1.0).planning().tickPeriodMs());
        assertEquals(RoadAgent.granularityMsFor(0.3), fixture(0.3).planning().tickPeriodMs());
    }

    @Test
    @DisplayName("a message the planner has no handler for is reported on stderr, not swallowed")
    void an_unexpected_message_is_named() {
        Fixture f = fixture();
        String report = captureErr(() -> deliver(f,
                new cz.vutbr.fit.ags.railway.domain.msg.TrainState("vl0 in stA"), "vl0"));
        assertTrue(report.contains(Channel.TRAIN_STATE.id()), () -> report);
        assertTrue(f.planning().of(Channel.VOTE_REQUEST).isEmpty());
    }

    @Test
    @DisplayName("the planner needs all four of its collaborators")
    void the_constructor_refuses_a_missing_collaborator() {
        Agent host = new Agent();
        JadeAgentFixture.name(host, RailwayMainAgent.MAIN_AGENT_NAME);
        assertThrows(IllegalArgumentException.class,
                () -> new Planning(null, net(), delays(), new VirtualClock()));
        assertThrows(IllegalArgumentException.class,
                () -> new Planning(host, net(), delays(), null));
    }

    private static String drainCapturingStdout(Fixture f) {
        PrintStream out = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            f.planning().agentClock().runDue();
        } finally {
            System.setOut(out);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static String captureErr(Runnable body) {
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            body.run();
        } finally {
            System.setErr(err);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
