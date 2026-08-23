/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import cz.vutbr.fit.ags.railway.domain.clock.VirtualClock;
import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterRequest;
import cz.vutbr.fit.ags.railway.domain.msg.PathFindReply;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.RoadDirection;
import cz.vutbr.fit.ags.railway.domain.msg.TravelStart;
import cz.vutbr.fit.ags.railway.domain.msg.Vote;
import cz.vutbr.fit.ags.railway.domain.msg.VoteRequest;
import cz.vutbr.fit.ags.railway.jade.Messages;
import jade.core.JadeAgentFixture;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #31's half of the JADE-only risk {@code docs/message-ontology.md} §7 names: <b>one message
 * queue, two behaviours</b>. A message matching no active template sits in the queue forever and
 * presents as a hang — a failure the Cybele baseline structurally cannot have, since every channel
 * there had its own handler method.
 * <p>
 * A road is the agent that makes the case for matching on the <em>ontology</em> slot rather than
 * the performative: its {@code ENTER} and its {@code TRAVEL_START} are both {@code request}, and
 * they are the two halves of one train's traversal. §7's table lists exactly that collision.
 * <p>
 * Driven for real: messages are posted into the agent's own queue and pulled out by its own
 * {@code Inbox}/{@code Drain} behaviours, with no container and no threads.
 */
class RoadAgentInboxTest {

    private static final String ROAD = "tr1";

    private static final class RecordingRoad extends RoadAgent {
        private static final long serialVersionUID = 1L;
        private final transient List<RailwayMessage> sent = new ArrayList<RailwayMessage>();

        @Override
        protected void emit(RailwayMessage message, String receiver) {
            sent.add(message);
        }

        List<Channel> channels() {
            List<Channel> out = new ArrayList<Channel>();
            for (RailwayMessage m : sent) {
                out.add(m.channel());
            }
            return out;
        }
    }

    private static RecordingRoad road() {
        RecordingRoad road = new RecordingRoad();
        JadeAgentFixture.name(road, ROAD);
        road.setArguments(new Object[]{Long.valueOf(1L), "stA", "stH", new VirtualClock()});
        road.setup();
        road.sent.clear();
        return road;
    }

    private static void post(RecordingRoad road, RailwayMessage message, String from) {
        road.postMessage(Messages.build(message, from, ROAD));
    }

    @Test
    @DisplayName("the inbox consumes the five road channels and leaves everything else alone")
    void the_inbox_takes_only_what_the_road_owns() {
        RecordingRoad road = road();
        RoadAgent.Inbox inbox = road.new Inbox();
        inbox.setAgent(road);

        post(road, new EnterRequest("vl0", "stA", "stB"), "vl0");
        post(road, new VoteRequest("vl1", 10000), RailwayMainAgent.MAIN_AGENT_NAME);
        // Neither of these is inbound for a road: VOTE is Main's, PATH_FIND_REPLY a station's.
        post(road, new Vote("stB", "vl9", 7L), "stB");
        post(road, new PathFindReply("stC", "tr1"), RailwayMainAgent.MAIN_AGENT_NAME);
        assertEquals(4, road.getCurQueueSize());

        inbox.action();
        inbox.action();
        inbox.action();

        assertEquals(2, road.getCurQueueSize(), "the two foreign messages are still queued");
        assertEquals(List.of(Channel.ENTER_REPLY, Channel.ROAD_STATE, Channel.VOTE), road.channels());
        assertEquals(RoadDirection.TRAVEL_RIGHT, road.roadState());
    }

    @Test
    @DisplayName("ENTER and TRAVEL_START are both 'request' and the ontology slot still tells them apart")
    void the_two_request_channels_do_not_collide() {
        RecordingRoad road = road();
        RoadAgent.Inbox inbox = road.new Inbox();
        inbox.setAgent(road);

        post(road, new EnterRequest("vl0", "stA", "stB"), "vl0");
        post(road, new TravelStart("vl0"), "vl0");
        inbox.action();
        inbox.action();

        assertEquals(0, road.getCurQueueSize());
        // ENTER went to enter() -- reply plus state -- and TRAVEL_START went to travelStart(),
        // which sends nothing and arms the timer. Had the template matched on the performative
        // alone, the second message would have been handled as an ENTER.
        assertEquals(List.of(Channel.ENTER_REPLY, Channel.ROAD_STATE), road.channels());
        assertEquals(1, road.agentClock().pending(), "the travel timer is armed, exactly once");
    }

    @Test
    @DisplayName("the drain catches what the inbox will not, reports it, and empties the queue")
    void the_drain_is_the_exact_complement() {
        RecordingRoad road = road();
        RoadAgent.Inbox inbox = road.new Inbox();
        RoadAgent.Drain drain = road.new Drain();
        inbox.setAgent(road);
        drain.setAgent(road);

        post(road, new Vote("stB", "vl9", 7L), "stB");
        post(road, new PathFindReply("stC", "tr1"), RailwayMainAgent.MAIN_AGENT_NAME);

        inbox.action();
        assertEquals(2, road.getCurQueueSize(), "the inbox must not touch them");

        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            drain.action();
            drain.action();
        } finally {
            System.setErr(err);
        }

        assertEquals(0, road.getCurQueueSize(), "a stray message must not be able to wedge us");
        assertTrue(road.sent.isEmpty(), "and it must not provoke a reply either");
        String report = captured.toString(StandardCharsets.UTF_8);
        assertTrue(report.contains(Channel.VOTE.id()), () -> "drain must name the channel: " + report);
        assertTrue(report.contains(Channel.PATH_FIND_REPLY.id()), () -> report);
    }

    @Test
    @DisplayName("an empty queue blocks both behaviours instead of spinning or throwing")
    void an_empty_queue_just_blocks() {
        RecordingRoad road = road();
        RoadAgent.Inbox inbox = road.new Inbox();
        RoadAgent.Drain drain = road.new Drain();
        inbox.setAgent(road);
        drain.setAgent(road);

        inbox.action();
        drain.action();

        assertEquals(0, road.getCurQueueSize());
        assertTrue(road.sent.isEmpty());
    }

    @Test
    @DisplayName("a message with the right channel and the wrong act falls through to the drain")
    void a_tampered_performative_is_not_silently_accepted() {
        RecordingRoad road = road();
        RoadAgent.Inbox inbox = road.new Inbox();
        inbox.setAgent(road);

        ACLMessage tampered = Messages.build(new TravelStart("vl0"), "vl0", ROAD);
        tampered.setPerformative(ACLMessage.INFORM);
        road.postMessage(tampered);

        inbox.action();

        assertEquals(1, road.getCurQueueSize(),
                "the template is ontology AND performative for exactly this reason"
                        + " (message-ontology.md 7)");
        assertTrue(road.sent.isEmpty());
        assertEquals(0, road.agentClock().pending());
    }
}
