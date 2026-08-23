/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterRequest;
import cz.vutbr.fit.ags.railway.domain.msg.PathFindReply;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import cz.vutbr.fit.ags.railway.domain.msg.TrainState;
import cz.vutbr.fit.ags.railway.domain.msg.Vote;
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
 * The other half of #30's JADE-only risk: <b>one message queue, two behaviours</b>.
 * <p>
 * {@code docs/message-ontology.md} §7 names this as the failure mode a Cybele baseline
 * structurally cannot have — there every channel had its own handler method, here a message
 * matching no active template sits in the queue forever and presents as a hang. So the templates
 * are driven for real: messages are posted into the agent's own queue and pulled out by its own
 * {@code Inbox}/{@code Drain} behaviours, with no container and no threads
 * ({@code docs/TESTING.md} §4.1, and see {@link JadeAgentFixture} for what that costs).
 */
class StationInboxTest {

    private static final class RecordingStation extends Station {
        private static final long serialVersionUID = 1L;
        private final transient List<RailwayMessage> sent = new ArrayList<RailwayMessage>();

        @Override
        protected void emit(RailwayMessage message, String receiver) {
            sent.add(message);
        }
    }

    private static RecordingStation station() {
        RecordingStation station = new RecordingStation();
        JadeAgentFixture.name(station, "stA");
        station.setArguments(new Object[]{Integer.valueOf(2), List.of("tr1")});
        station.setup();
        station.sent.clear();
        return station;
    }

    private static void post(RecordingStation station, RailwayMessage message, String from) {
        station.postMessage(Messages.build(message, from, "stA"));
    }

    @Test
    @DisplayName("the inbox consumes the five station channels and leaves everything else alone")
    void the_inbox_takes_only_what_the_station_owns() {
        RecordingStation station = station();
        Station.Inbox inbox = station.new Inbox();
        inbox.setAgent(station);

        post(station, new EnterRequest("vl0", "tr9", "stA"), "vl0");
        post(station, new PathFindReply("stC", "tr1"), "Main");
        // Neither of these is inbound for a station: VOTE and STATION_INFO are Main's.
        post(station, new Vote("stB", "vl9", 7L), "stB");
        post(station, new StationInfo(1, 2), "stB");
        assertEquals(4, station.getCurQueueSize());

        inbox.action();
        inbox.action();
        inbox.action();

        assertEquals(2, station.getCurQueueSize(), "the two foreign messages are still queued");
        assertEquals(List.of(Channel.ENTER_REPLY, Channel.STATION_INFO),
                List.of(station.sent.get(0).channel(), station.sent.get(1).channel()));
        assertEquals("tr1", station.getPathDirs().get("stC"), "the reply was consumed too");
    }

    @Test
    @DisplayName("the drain catches what the inbox will not, reports it, and empties the queue")
    void the_drain_is_the_exact_complement() {
        RecordingStation station = station();
        Station.Inbox inbox = station.new Inbox();
        Station.Drain drain = station.new Drain();
        inbox.setAgent(station);
        drain.setAgent(station);

        post(station, new Vote("stB", "vl9", 7L), "stB");
        post(station, new TrainState("vl0 in stA"), "vl0");

        inbox.action();
        assertEquals(2, station.getCurQueueSize(), "the inbox must not touch them");

        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            drain.action();
            drain.action();
        } finally {
            System.setErr(err);
        }

        assertEquals(0, station.getCurQueueSize(), "a stray message must not be able to wedge us");
        assertTrue(station.sent.isEmpty(), "and it must not provoke a reply either");
        String report = captured.toString(StandardCharsets.UTF_8);
        assertTrue(report.contains(Channel.VOTE.id()), () -> "drain must name the channel: " + report);
        assertTrue(report.contains(Channel.TRAIN_STATE.id()), () -> report);
    }

    @Test
    @DisplayName("an empty queue blocks both behaviours instead of spinning or throwing")
    void an_empty_queue_just_blocks() {
        RecordingStation station = station();
        Station.Inbox inbox = station.new Inbox();
        Station.Drain drain = station.new Drain();
        inbox.setAgent(station);
        drain.setAgent(station);

        inbox.action();
        drain.action();

        assertEquals(0, station.getCurQueueSize());
        assertTrue(station.sent.isEmpty());
    }

    @Test
    @DisplayName("a message with the right channel and the wrong act falls through to the drain")
    void a_tampered_performative_is_not_silently_accepted() {
        RecordingStation station = station();
        Station.Inbox inbox = station.new Inbox();
        inbox.setAgent(station);

        ACLMessage tampered = Messages.build(new EnterRequest("vl0", "tr9", "stA"), "vl0", "stA");
        tampered.setPerformative(ACLMessage.INFORM);
        station.postMessage(tampered);

        inbox.action();

        assertEquals(1, station.getCurQueueSize(),
                "the template is ontology AND performative for exactly this reason"
                        + " (message-ontology.md 7)");
        assertTrue(station.sent.isEmpty());
    }
}
