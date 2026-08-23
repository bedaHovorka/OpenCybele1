/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import java.util.ArrayList;
import java.util.List;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterReply;
import cz.vutbr.fit.ags.railway.domain.msg.EnterRequest;
import cz.vutbr.fit.ags.railway.domain.msg.LeaveNotice;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import cz.vutbr.fit.ags.railway.domain.msg.Vote;
import cz.vutbr.fit.ags.railway.jade.Messages;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Interaction pair 2 of 4: the queueing system, {@code ENTER} → {@code ENTER_REPLY} → {@code LEAVE}
 * (#38).
 *
 * <h2>What the container adds over the L1 test of the same handlers</h2>
 *
 * {@code StationInboxTest} proves that {@code enter} admits or queues and that {@code leave} hands
 * the slot on. It cannot prove the part that decides <em>who gets told</em>: the reply is addressed
 * to the train named in the payload, so a station holding two trains has to send the second
 * {@code inform} to a different AID from the first. Get that wrong and every train still gets an
 * {@code ENTER_REPLY} — just not its own — which at L1 is invisible (the capture sees the same
 * count of sends) and at L3 is a route that silently diverges hundreds of lines later.
 * <p>
 * The station here has <b>capacity 1</b>, so the queue path is the path: the second train must get
 * nothing at all until the first leaves. "Nothing at all" is asserted with a bounded poll, which is
 * the only honest form of it.
 *
 * <h2>The destination is the station itself, on purpose</h2>
 *
 * {@code Station.resolveDirection} short-circuits when the target is the station's own name, so
 * this class exercises {@code ENTER}/{@code LEAVE} with no {@code PATH_FIND} round trip in the
 * middle. That pair gets its own class ({@code PathFindIT}) rather than being tangled into this
 * one — one test per interaction pair is the acceptance criterion.
 */
class EnterLeaveIT extends ContainerFixture {

    @Test
    @DisplayName("enter, enter_reply, leave -- and the queued train's reply goes to the queued train")
    void the_queueing_system_round_trips_and_addresses_each_reply_to_its_own_train() throws Exception {
        final Driver main = driver(RailwayMainAgent.MAIN_AGENT_NAME);
        final Driver vl3 = driver("vl3");
        final Driver vl4 = driver("vl4");
        final Station station = start("stA", new Station(),
                Integer.valueOf(1), new ArrayList<String>(List.of("tr1")));

        assertEquals(new StationInfo(0, 1),
                main.expectContent(Channel.STATION_INFO, StationInfo.class, REPLY_MS),
                "the opening push: empty, capacity 1");

        // ---- vl3 enters an empty station
        vl3.send(new EnterRequest("vl3", null, "stA"), "stA");
        final ACLMessage reply = vl3.expect(Channel.ENTER_REPLY, REPLY_MS);
        assertEquals(ACLMessage.INFORM, reply.getPerformative());
        assertEquals("stA", Messages.senderName(reply));
        assertEquals("vl3", Messages.receiverName(reply), "addressed to vl3 by name");
        final EnterReply admitted = (EnterReply) Messages.contentOf(reply);
        assertEquals("stA", admitted.object());
        assertNull(admitted.next(), "target == this station means 'you have arrived'");
        assertEquals(new StationInfo(1, 1),
                main.expectContent(Channel.STATION_INFO, StationInfo.class, REPLY_MS));

        // ---- vl4 finds it full and is queued: no reply, and the station says so on STATION_INFO
        vl4.send(new EnterRequest("vl4", null, "stA"), "stA");
        assertEquals(new StationInfo(1, 1),
                main.expectContent(Channel.STATION_INFO, StationInfo.class, REPLY_MS),
                "the queued arm republishes the unchanged occupancy -- 2008 behaviour, and the"
                        + " signature the normalizer reads the queue path off");
        vl4.expectSilence(SILENCE_MS);

        // ---- vl3 leaves; the slot goes straight to vl4, and vl4 is the one told about it
        vl3.send(new LeaveNotice("vl3"), "stA");
        final ACLMessage handover = vl4.expect(Channel.ENTER_REPLY, REPLY_MS);
        assertEquals("vl4", Messages.receiverName(handover),
                "the handover reply must be addressed to the QUEUED train, not to the departed one."
                        + " An L1 capture of Station.emit cannot tell those two apart.");
        assertEquals("stA", ((EnterReply) Messages.contentOf(handover)).object());
        assertEquals(new StationInfo(1, 1),
                main.expectContent(Channel.STATION_INFO, StationInfo.class, REPLY_MS));

        // vl3 has left; nothing further is addressed to it.
        vl3.expectSilence(SILENCE_MS);
        assertQueueDrained(station);
    }

    @Test
    @DisplayName("a message the station has no handler for is drained, and the station keeps working")
    void an_unhandled_message_does_not_wedge_the_queue() throws Exception {
        // THE JADE-only failure mode, made a test rather than a warning. A JADE agent has one
        // message queue; receive(template) consumes only what matches, so a message matching no
        // active template is never removed. VOTE is a Main channel and is not in
        // Templates.inbound(Party.STATION), so this message can only leave the queue through the
        // Drain behaviour docs/message-ontology.md section 7 requires every ported agent to carry.
        //
        // Deleting `addBehaviour(new Drain())` from Station.setup() leaves the queue at 1 forever
        // and fails the first assertion; it does NOT fail any L1 test, because a POJO station has
        // no queue at all. Expect a loud "!!! ... TEMPLATE GAP" report and a stack trace on stderr
        // while this test runs -- that is the drain doing its job, not a failure.
        final Driver main = driver(RailwayMainAgent.MAIN_AGENT_NAME);
        final Driver vl3 = driver("vl3");
        final Station station = start("stA", new Station(),
                Integer.valueOf(2), new ArrayList<String>(List.of("tr1")));
        main.expect(Channel.STATION_INFO, REPLY_MS);

        main.send(new Vote("stZ", "vl9", 42L), "stA");
        assertQueueDrained(station);

        // And it is still a working station afterwards: one stray message must not wedge an agent.
        vl3.send(new EnterRequest("vl3", null, "stA"), "stA");
        assertEquals("stA", vl3.expectContent(Channel.ENTER_REPLY, EnterReply.class, REPLY_MS).object());
        main.expectContent(Channel.STATION_INFO, StationInfo.class, REPLY_MS);
        assertQueueDrained(station);
    }
}
