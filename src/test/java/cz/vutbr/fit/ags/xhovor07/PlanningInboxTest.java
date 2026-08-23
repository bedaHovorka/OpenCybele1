/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import cz.vutbr.fit.ags.railway.domain.clock.SimClock;
import cz.vutbr.fit.ags.railway.domain.clock.VirtualClock;
import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.PlanTrain;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import cz.vutbr.fit.ags.railway.domain.msg.Vote;
import cz.vutbr.fit.ags.railway.domain.msg.VoteRequest;
import cz.vutbr.fit.ags.railway.domain.util.HashMapGraph;
import cz.vutbr.fit.ags.railway.domain.util.UnorientedGraph;
import cz.vutbr.fit.ags.railway.jade.Messages;
import jade.core.Agent;
import jade.core.JadeAgentFixture;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The other half of #34's JADE-only risk: <b>one message queue, and a template that changes</b>.
 * <p>
 * {@code Planning}'s {@link Planning#template()} is not a constant, and that is not a
 * micro-optimisation — it is how the port keeps the seriality {@code latch.await()} used to
 * enforce. Cybele dispatched one activity's events serially, so a blocked {@code planTrain}
 * head-of-line-blocked the whole {@code PLAN_TRAIN} channel and <b>exactly one election ran at
 * a time</b>. Here the backlog is the agent's own mailbox: while a round is open the inbox
 * simply does not match {@code PLAN_TRAIN}, so it stays queued, in arrival order.
 * <p>
 * These tests drive the real {@code Inbox} against the real message queue — messages are posted
 * into the host agent and pulled out by the behaviour, with no container and no threads (see
 * {@link JadeAgentFixture} for what that costs). A suite that called {@code dispatch} directly
 * would pass while the gate was wide open.
 */
class PlanningInboxTest {

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

    private static final class RecordingPlanning extends Planning {
        private static final long serialVersionUID = 1L;
        private final transient List<RailwayMessage> sent = new ArrayList<RailwayMessage>();

        RecordingPlanning(Agent host, UnorientedGraph<String, String> net,
                Map<String, Long> roadDelays, SimClock clock) {
            super(host, net, roadDelays, clock);
        }

        @Override
        protected void emit(RailwayMessage message, String receiver) {
            sent.add(message);
        }

        /** The trains an election was opened for, in order — one entry per round, not per cfp. */
        List<String> trainsAsked() {
            List<String> out = new ArrayList<String>();
            for (RailwayMessage m : sent) {
                if (m.channel() == Channel.VOTE_REQUEST) {
                    String train = ((VoteRequest) m).train();
                    if (!out.contains(train)) {
                        out.add(train);
                    }
                }
            }
            return out;
        }
    }

    /** The planner, its host agent and its inbox, all wired. */
    private record Fixture(RecordingPlanning planning, Agent host, Planning.Inbox inbox) {
    }

    private static Fixture fixture() {
        Agent host = new Agent();
        JadeAgentFixture.name(host, RailwayMainAgent.MAIN_AGENT_NAME);
        RecordingPlanning planning =
                new RecordingPlanning(host, net(), delays(), new VirtualClock(1000L, 1.0));
        Planning.Inbox inbox = planning.new Inbox();
        inbox.setAgent(host);
        return new Fixture(planning, host, inbox);
    }

    private static void post(Fixture f, RailwayMessage message, String from) {
        f.host().postMessage(Messages.build(message, from, RailwayMainAgent.MAIN_AGENT_NAME));
    }

    @Test
    @DisplayName("the inbox consumes PLAN_TRAIN and VOTE and leaves the hub's channels alone")
    void the_inbox_takes_only_what_the_planner_owns() {
        Fixture f = fixture();

        post(f, new PlanTrain("vl0", "stA", "stC"), RailwayMainAgent.MAIN_AGENT_NAME);
        // Neither of these belongs to this activity: STATION_INFO is the hub's (#33) and a
        // VOTE_REQUEST is something Main SENDS, never receives.
        post(f, new StationInfo(1, 2), "stA");
        post(f, new VoteRequest("vl0", 0L), "Main");

        f.inbox().action();

        assertEquals(2, f.host().getCurQueueSize(), "the two foreign messages are still queued");
        assertEquals(List.of("vl0"), f.planning().trainsAsked(), "the election opened");
    }

    @Test
    @DisplayName("a PLAN_TRAIN arriving during an election stays in the mailbox"
            + " -- the seriality latch.await() gave, without the wait")
    void the_backlog_is_the_mailbox() {
        Fixture f = fixture();

        post(f, new PlanTrain("vl0", "stA", "stC"), RailwayMainAgent.MAIN_AGENT_NAME);
        f.inbox().action();
        assertNotNull(f.planning().electionInFlight());

        post(f, new PlanTrain("vl1", "stC", "stA"), RailwayMainAgent.MAIN_AGENT_NAME);
        post(f, new PlanTrain("vl2", "stA", "stC"), RailwayMainAgent.MAIN_AGENT_NAME);
        f.inbox().action();
        f.inbox().action();
        assertEquals(2, f.host().getCurQueueSize(),
                "an open round narrows the template to VOTE alone");
        assertEquals(List.of("vl0"), f.planning().trainsAsked());

        // Feed the round. Votes are matched even while the two PLAN_TRAINs sit in the queue,
        // which is the half a naive "block the whole inbox" gate would get wrong.
        for (String voter : List.of("stA", "tr1", "stB", "tr2", "stC")) {
            post(f, new Vote(voter, "vl0", 0L), voter);
        }
        for (int i = 0; i < 5; i++) {
            f.inbox().action();
        }
        assertNull(f.planning().electionInFlight(), "the round closed");
        assertEquals(2, f.host().getCurQueueSize(), "and only the backlog is left");

        f.inbox().action();
        assertEquals(List.of("vl0", "vl1"), f.planning().trainsAsked(),
                "the next election opens, in ARRIVAL order -- receive() scans the queue in order");
        assertEquals(1, f.host().getCurQueueSize(), "vl2 is still waiting its turn");
    }

    @Test
    @DisplayName("DEF-22: a wedged election leaves its backlog in the queue forever,"
            + " which is 'no further train is planned'")
    void def22_a_wedged_election_wedges_the_backlog() {
        Fixture f = fixture();
        post(f, new PlanTrain("vl0", "stA", "stC"), RailwayMainAgent.MAIN_AGENT_NAME);
        f.inbox().action();

        // Four of five voters answer; stC's VOTE is lost.
        for (String voter : List.of("stA", "tr1", "stB", "tr2")) {
            post(f, new Vote(voter, "vl0", 0L), voter);
        }
        post(f, new PlanTrain("vl1", "stC", "stA"), RailwayMainAgent.MAIN_AGENT_NAME);
        for (int i = 0; i < 10; i++) {
            f.inbox().action();
        }

        assertNotNull(f.planning().electionInFlight(), "the round never closes");
        assertEquals(1, f.planning().electionInFlight().outstanding());
        assertEquals(1, f.host().getCurQueueSize(), "vl1 will never be planned");
        assertEquals(List.of("vl0"), f.planning().trainsAsked());
        assertEquals(0, f.planning().plannedCount());
    }

    @Test
    @DisplayName("an empty queue blocks instead of spinning or throwing")
    void an_empty_queue_just_blocks() {
        Fixture f = fixture();
        f.inbox().action();
        assertEquals(0, f.host().getCurQueueSize());
        assertTrue(f.planning().trainsAsked().isEmpty());
    }

    @Test
    @DisplayName("a message with the right channel and the wrong act is not silently accepted")
    void a_tampered_performative_falls_through() {
        Fixture f = fixture();
        ACLMessage tampered = Messages.build(new PlanTrain("vl0", "stA", "stC"),
                RailwayMainAgent.MAIN_AGENT_NAME, RailwayMainAgent.MAIN_AGENT_NAME);
        tampered.setPerformative(ACLMessage.INFORM);
        f.host().postMessage(tampered);

        f.inbox().action();

        assertEquals(1, f.host().getCurQueueSize(),
                "the template is ontology AND performative for exactly this reason"
                        + " (message-ontology.md 7)");
        assertTrue(f.planning().trainsAsked().isEmpty());
    }
}
