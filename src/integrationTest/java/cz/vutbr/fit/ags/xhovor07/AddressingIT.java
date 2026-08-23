/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import java.util.ArrayList;
import java.util.List;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterReply;
import cz.vutbr.fit.ags.railway.domain.msg.EnterRequest;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import cz.vutbr.fit.ags.railway.jade.Messages;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The addressing mechanism, exercised on a real platform</b> — the core of #27, and the one
 * thing no POJO test in this repository can see (#38).
 *
 * <h2>What is actually being claimed</h2>
 *
 * {@code docs/message-ontology.md} §2 decides: <em>direct AID unicast for all fifteen channels, no
 * DF.</em> {@code AclBindingTest.addressing_is_direct_aid_unicast} checks the shape of that — one
 * named receiver per message, built from a local name — but a shape is not a delivery. Nothing at
 * L1 can distinguish "the AMS white pages resolved {@code stA} to the agent called {@code stA}"
 * from "every agent gets every message and the right one happened to answer". These four tests
 * distinguish it, and they do it in both directions: a message reaches its intended recipient, and
 * a message to the wrong AID reaches <b>nobody</b> — including the agent that would have been right
 * if the name had been.
 *
 * <h2>And the other half: fifteen topics that must not perturb anything</h2>
 *
 * A topic AID added as a <em>second</em> receiver is how a JADE probe gets what a Cybele channel
 * gave for free (§6). The claim that matters for the goldens is not that the probe sees traffic —
 * it is that the named agent still receives <b>exactly one</b> copy and behaves identically. So the
 * two tests below run the same {@code ENTER} round trip twice, once with tracing off and once with
 * it on, and assert the named receiver's side is unchanged.
 *
 * <h2>Why this class declares a method order, which no other class here does</h2>
 *
 * {@code TraceTopics.enable()} is deliberately one-way: it is a {@code volatile boolean} that
 * {@code Main} sets once on the main thread before any agent exists, and there is no
 * {@code disable()} because production never needs one and a reset hook would be a wider seam than
 * the tests need. So the tracing-off observation has to happen before the tracing-on one. The order
 * is declared rather than assumed, and the off-test opens with an {@code assertFalse} so that a
 * future reorder <em>fails</em> instead of quietly passing on the wrong premise.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AddressingIT extends ContainerFixture {

    @Test
    @Order(1)
    @DisplayName("a unicast reaches the named agent and no other agent on the platform")
    void unicast_reaches_exactly_the_named_agent() throws Exception {
        final Driver main = driver(RailwayMainAgent.MAIN_AGENT_NAME);
        final Driver vl3 = driver("vl3");
        // A second, identically-shaped addressee that must see nothing. A Driver rather than a real
        // Station on purpose: a Station's silence could only be inferred from the absence of a
        // STATION_INFO, while a Driver's is directly observable.
        final Driver bystander = driver("stB");
        final Station station = start("stA", new Station(),
                Integer.valueOf(4), new ArrayList<String>(List.of("tr1")));
        main.expect(Channel.STATION_INFO, REPLY_MS);

        vl3.send(new EnterRequest("vl3", null, "stA"), "stA");

        final ACLMessage reply = vl3.expect(Channel.ENTER_REPLY, REPLY_MS);
        assertEquals("stA", ((EnterReply) Messages.contentOf(reply)).object());
        // The GUID is derived from the running platform, never spelled: AID.platformID is a
        // JVM-global static that a container boot overwrites.
        assertEquals("vl3@" + platformId(),
                ((AID) reply.getAllReceiver().next()).getName(),
                "the reply is addressed by GUID to this platform's vl3");
        assertEquals(1, countReceivers(reply), "exactly one receiver: no topic copy, no fan-out");
        main.expect(Channel.STATION_INFO, REPLY_MS);

        bystander.expectSilence(SILENCE_MS);
        assertQueueDrained(station);
    }

    @Test
    @Order(2)
    @DisplayName("a send to an AID nobody answers to reaches nobody, and bounces from the AMS")
    void a_wrong_aid_reaches_no_one() throws Exception {
        final Driver main = driver(RailwayMainAgent.MAIN_AGENT_NAME);
        final Driver vl3 = driver("vl3");
        final Station station = start("stA", new Station(),
                Integer.valueOf(4), new ArrayList<String>(List.of("tr1")));
        main.expect(Channel.STATION_INFO, REPLY_MS);

        // ---- the negative: stZ does not exist. stA is running and would have handled this.
        vl3.send(new EnterRequest("vl3", null, "stA"), "stZ");

        // The AMS tells the sender the message could not be delivered. That is the shape
        // UnexpectedMessage.report was written for after #36 measured four of them in a gate run.
        final ACLMessage bounce = vl3.expect(Channel.ENTER, REPLY_MS);
        assertEquals(ACLMessage.FAILURE, bounce.getPerformative(),
                "an undeliverable message comes back as a FAILURE carrying the original ontology");
        assertNotNull(Messages.senderName(bounce));
        assertEquals("ams", Messages.senderName(bounce), "and it comes from the AMS, not from stA");

        // stA neither answered nor holds it: the name, not the ontology, is what routes.
        main.expectSilence(SILENCE_MS);
        assertQueueDrained(station);

        // ---- the positive, in the same test, so the negative cannot pass vacuously. If the
        // platform were simply not delivering anything, this line would fail too.
        vl3.send(new EnterRequest("vl3", null, "stA"), "stA");
        assertEquals("stA", vl3.expectContent(Channel.ENTER_REPLY, EnterReply.class, REPLY_MS).object());
        assertEquals(new StationInfo(1, 4),
                main.expectContent(Channel.STATION_INFO, StationInfo.class, REPLY_MS));
        assertQueueDrained(station);
    }

    @Test
    @Order(3)
    @DisplayName("with tracing off a topic-registered probe sees nothing, and delivery is unchanged")
    void with_tracing_off_the_probe_is_a_dead_letter() throws Exception {
        assertFalse(TraceTopics.isEnabled(),
                "this test's premise is that TraceTopics.enable() has not been called yet."
                        + " TraceTopics has no disable(), so the order declared on this class is"
                        + " load-bearing -- see the class comment.");

        final Driver main = driver(RailwayMainAgent.MAIN_AGENT_NAME);
        final Driver vl3 = driver("vl3");
        final RecordingProbe probe = start(TraceProbe.PROBE_AGENT_NAME, new RecordingProbe());
        probe.awaitRegistered(REPLY_MS);
        final Station station = start("stA", new Station(),
                Integer.valueOf(4), new ArrayList<String>(List.of("tr1")));

        // The station's own opening push and a full ENTER round trip -- three messages on three
        // different channels, all of which the probe is registered to.
        main.expect(Channel.STATION_INFO, REPLY_MS);
        vl3.send(new EnterRequest("vl3", null, "stA"), "stA");
        vl3.expect(Channel.ENTER_REPLY, REPLY_MS);
        main.expect(Channel.STATION_INFO, REPLY_MS);

        probe.expectSilence(SILENCE_MS);
        assertEquals(0, probe.getCurQueueSize(),
                "with the topic omitted the probe is not a receiver at all, so nothing even"
                        + " reaches its queue: a probe-off run is byte-for-byte the run it would"
                        + " have been");
        assertQueueDrained(station);
    }

    @Test
    @Order(4)
    @DisplayName("with tracing on the probe sees a copy it was never addressed to, and delivery is still one copy")
    void the_probe_eavesdrops_without_perturbing_delivery() throws Exception {
        TraceTopics.enable();
        assertTrue(TraceTopics.isEnabled());

        final Driver main = driver(RailwayMainAgent.MAIN_AGENT_NAME);
        final Driver vl3 = driver("vl3");
        final RecordingProbe probe = start(TraceProbe.PROBE_AGENT_NAME, new RecordingProbe());
        // The probe must be registered before the first message of the run: Station.setup() sends
        // its opening STATION_INFO, so a probe that subscribed a moment later would miss line 1.
        // That barrier is exactly what Main does with TraceProbe.awaitReady().
        probe.awaitRegistered(REPLY_MS);
        final Station station = start("stA", new Station(),
                Integer.valueOf(4), new ArrayList<String>(List.of("tr1")));

        // ---- the named receivers still get exactly what they got with tracing off
        final ACLMessage opening = main.expect(Channel.STATION_INFO, REPLY_MS);
        assertEquals(2, countReceivers(opening),
                "the topic AID is an ADDITIONAL receiver, not a replacement for the named one");
        assertEquals(RailwayMainAgent.MAIN_AGENT_NAME, Messages.receiverName(opening),
                "and Messages.receiverName still reports the agent, never the topic");

        vl3.send(new EnterRequest("vl3", null, "stA"), "stA");
        assertEquals("stA", vl3.expectContent(Channel.ENTER_REPLY, EnterReply.class, REPLY_MS).object());
        main.expect(Channel.STATION_INFO, REPLY_MS);
        // Exactly one copy each -- an extra receiver must not become an extra delivery.
        vl3.expectSilence(SILENCE_MS);

        // ---- and the probe, which nothing addressed, saw all three
        final List<String> events = probe.expectEvents(4, REPLY_MS);
        assertEquals(List.of("STATION_INFO", "ENTER", "ENTER_REPLY", "STATION_INFO"), events,
                "the probe observes the traffic of agents that do not know it exists, on the"
                        + " per-constant topics -- one registration covering every instance");
        assertQueueDrained(station);
        assertQueueDrained(probe);
    }

    private static int countReceivers(ACLMessage acl) {
        int n = 0;
        for (jade.util.leap.Iterator it = acl.getAllReceiver(); it.hasNext(); it.next()) {
            n++;
        }
        return n;
    }
}
