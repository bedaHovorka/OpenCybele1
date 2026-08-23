/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterReply;
import cz.vutbr.fit.ags.railway.domain.msg.EnterRequest;
import cz.vutbr.fit.ags.railway.domain.msg.LeaveNotice;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.StartCommand;
import cz.vutbr.fit.ags.railway.domain.msg.TravelEnd;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #32's half of the JADE-only risk {@code docs/message-ontology.md} §7 names: <b>one message queue,
 * two behaviours</b>. A message matching no active template sits in the queue forever and presents
 * as a hang — a failure the Cybele baseline structurally cannot have, since every channel there had
 * its own handler method.
 * <p>
 * A train is §7's <b>third</b> collision row, and the sharpest of the four: {@code ENTER_REPLY} and
 * {@code TRAVEL_END} are <em>both</em> {@code inform}, and they are the two halves of one hop. A
 * template matching on the act alone would hand a {@code TRAVEL_END} to {@code entered}, which
 * reads its {@code object} slot as the new position — so the train would relocate to whatever that
 * message happened to carry. The test below shows that concretely rather than by inspection.
 * <p>
 * Driven for real: messages are posted into the agent's own queue and pulled out by its own
 * {@code Inbox}/{@code Drain} behaviours, with no container and no threads.
 */
class TrainInboxTest {

    private static final String TRAIN = "vl0";

    private static final class RecordingTrain extends Train {
        private static final long serialVersionUID = 1L;
        private final transient List<RailwayMessage> sent = new ArrayList<RailwayMessage>();

        @Override
        protected void emit(RailwayMessage message, String receiver) {
            sent.add(message);
        }

        @Override
        protected void die() {
            // no container here; TrainWalkTest owns the death assertions
        }

        List<Channel> channels() {
            List<Channel> out = new ArrayList<Channel>();
            for (RailwayMessage m : sent) {
                out.add(m.channel());
            }
            return out;
        }
    }

    private static RecordingTrain train() {
        RecordingTrain train = new RecordingTrain();
        JadeAgentFixture.name(train, TRAIN);
        train.setArguments(new Object[]{"stA", "stB"});
        train.setup();
        train.sent.clear();
        return train;
    }

    private static void post(RecordingTrain train, RailwayMessage message, String from) {
        train.postMessage(Messages.build(message, from, TRAIN));
    }

    @Test
    @DisplayName("the inbox consumes the three train channels and leaves everything else alone")
    void the_inbox_takes_only_what_the_train_owns() {
        RecordingTrain train = train();
        Train.Inbox inbox = train.new Inbox();
        inbox.setAgent(train);

        post(train, new StartCommand("stA"), RailwayMainAgent.MAIN_AGENT_NAME);
        post(train, new EnterReply("stA", "tr1"), "stA");
        // Neither of these is inbound for a train: ENTER and VOTE_REQUEST are a static object's,
        // and LEAVE is one the train itself SENDS.
        post(train, new LeaveNotice("vl9"), "vl9");
        post(train, new VoteRequest("vl9", 10000), RailwayMainAgent.MAIN_AGENT_NAME);
        assertEquals(4, train.getCurQueueSize());

        inbox.action();
        inbox.action();
        inbox.action();

        assertEquals(2, train.getCurQueueSize(), "the two foreign messages are still queued");
        // START -> ENTER stA; ENTER_REPLY -> the status line plus the ENTER for the next hop.
        assertEquals(List.of(Channel.ENTER, Channel.TRAIN_STATE, Channel.ENTER), train.channels());
        assertEquals("stA", train.position());
    }

    @Test
    @DisplayName("ENTER_REPLY and TRAVEL_END are both 'inform' and the ontology slot still tells them apart")
    void the_two_inform_channels_do_not_collide() {
        RecordingTrain train = train();
        Train.Inbox inbox = train.new Inbox();
        inbox.setAgent(train);
        train.start(new StartCommand("stA"));
        train.entered(new EnterReply("stA", "tr1"));
        train.entered(new EnterReply("tr1", "stB"));
        train.sent.clear();

        post(train, new TravelEnd("tr1"), "tr1");
        inbox.action();

        assertEquals(0, train.getCurQueueSize());
        assertEquals("tr1", train.position(),
                "TRAVEL_END went to travelEnd(), which does not touch position. Had the template"
                        + " matched on the performative alone it would have been handled as an"
                        + " ENTER_REPLY and moved the train to the payload's road field.");
        assertEquals(List.of(Channel.ENTER), train.channels(),
                "and the reaction is the ENTER for the far station, not a second TRAVEL_START");
        assertEquals("stB", ((EnterRequest) train.sent.get(0)).endStation());
    }

    @Test
    @DisplayName("the drain catches what the inbox will not, reports it, and empties the queue")
    void the_drain_is_the_exact_complement() {
        RecordingTrain train = train();
        Train.Inbox inbox = train.new Inbox();
        Train.Drain drain = train.new Drain();
        inbox.setAgent(train);
        drain.setAgent(train);

        post(train, new LeaveNotice("vl9"), "vl9");
        post(train, new VoteRequest("vl9", 10000), RailwayMainAgent.MAIN_AGENT_NAME);

        inbox.action();
        assertEquals(2, train.getCurQueueSize(), "the inbox must not touch them");

        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            drain.action();
            drain.action();
        } finally {
            System.setErr(err);
        }

        assertEquals(0, train.getCurQueueSize(), "a stray message must not be able to wedge us");
        assertTrue(train.sent.isEmpty(), "and it must not provoke a reply either");
        String report = captured.toString(StandardCharsets.UTF_8);
        assertTrue(report.contains(Channel.LEAVE.id()), () -> "drain must name the channel: " + report);
        assertTrue(report.contains(Channel.VOTE_REQUEST.id()), () -> report);
    }

    @Test
    @DisplayName("an empty queue blocks both behaviours — DEF-02's outcome, not a spin and not a throw")
    void an_empty_queue_just_blocks() {
        RecordingTrain train = train();
        Train.Inbox inbox = train.new Inbox();
        Train.Drain drain = train.new Drain();
        inbox.setAgent(train);
        drain.setAgent(train);

        inbox.action();
        drain.action();

        assertEquals(0, train.getCurQueueSize());
        assertTrue(train.sent.isEmpty());
        assertTrue(!train.hasStarted(), "a train whose START never arrives waits forever, and that"
                + " is the observable the port is required to be able to reproduce. It is NOT"
                + " simulated: JADE queues an AID-addressed message whether or not a behaviour is"
                + " waiting, so DEF-02's race has no analogue (recorded for #41).");
        assertNull(train.position());
    }

    @Test
    @DisplayName("a message with the right channel and the wrong act falls through to the drain")
    void a_tampered_performative_is_not_silently_accepted() {
        RecordingTrain train = train();
        Train.Inbox inbox = train.new Inbox();
        inbox.setAgent(train);

        ACLMessage tampered = Messages.build(new StartCommand("stA"), RailwayMainAgent.MAIN_AGENT_NAME, TRAIN);
        tampered.setPerformative(ACLMessage.INFORM);
        train.postMessage(tampered);

        inbox.action();

        assertEquals(1, train.getCurQueueSize(),
                "the template is ontology AND performative for exactly this reason"
                        + " (message-ontology.md 7)");
        assertTrue(train.sent.isEmpty());
        assertTrue(!train.hasStarted());
    }
}
