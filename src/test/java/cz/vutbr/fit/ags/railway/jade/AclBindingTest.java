/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.jade;

import cz.vutbr.fit.ags.railway.domain.msg.CanonicalMessages;
import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import jade.core.AID;
import jade.core.JadePlatformFixture;
import jade.core.messaging.TopicUtility;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The channel &rarr; {@code ACLMessage} binding: addressing, slots, and the round trip through
 * a real {@code jade.lang.acl.ACLMessage} from {@code net.sf.ingenias:jade:4.3}.
 */
class AclBindingTest {

    @BeforeAll
    static void platform() {
        JadePlatformFixture.install("opencybele-test");
    }

    @Test
    @DisplayName("all fifteen channels are plain unicast to an AID resolved by local name -- no DF")
    void addressing_is_direct_aid_unicast() {
        // Read the platform name AT ASSERT TIME, never as a literal. AID.platformID is a
        // JVM-global static and AgentContainerImpl overwrites it at boot, so once
        // JadeDeliverySpikeTest has run in this JVM the GUID suffix is that platform's
        // (e.g. "@172.17.0.1:33851/JADE"), not the fixture's. Hardcoding the fixture value
        // makes this test pass or fail on test-class discovery order, and the failure would
        // read as an addressing bug in the ontology when it is nothing of the sort.
        String platform = JadePlatformFixture.currentPlatformId();
        CanonicalMessages.all().forEach((channel, sample) -> {
            ACLMessage acl = Messages.build(sample.message(), sample.from(), sample.to());
            List<AID> receivers = receivers(acl);
            assertEquals(1, receivers.size(), channel.event() + " must have exactly one receiver");
            assertEquals(sample.to(), receivers.get(0).getLocalName(), channel.event());
            assertEquals(sample.to() + "@" + platform, receivers.get(0).getName(), channel.event());
            assertEquals(sample.from(), Messages.senderName(acl), channel.event());
            assertEquals(sample.to(), Messages.receiverName(acl), channel.event());
        });
    }

    @Test
    @DisplayName("the ontology slot carries the channel identity and the performative is #27's assignment")
    void slots_carry_the_channel_identity_and_the_performative() {
        CanonicalMessages.all().forEach((channel, sample) -> {
            ACLMessage acl = Messages.build(sample.message(), sample.from(), sample.to());
            assertEquals(channel.id(), acl.getOntology(), channel.event());
            assertEquals(ACLMessage.getInteger(channel.performative().fipaName()),
                    acl.getPerformative(), channel.event());
            assertSame(channel, Messages.channelOf(acl));
            // The slots deliberately left alone. reply-with/in-reply-to would invite a blocking
            // receive the baseline does not have; protocol would invite ContractNetInitiator.
            assertNull(acl.getReplyWith(), channel.event());
            assertNull(acl.getInReplyTo(), channel.event());
            assertNull(acl.getProtocol(), channel.event());
            assertNull(acl.getLanguage(), channel.event());
        });
    }

    @Test
    @DisplayName("the exact JADE performatives, spelled out")
    void the_exact_jade_performatives() {
        assertEquals(ACLMessage.CFP, RailwayOntology.performative(Channel.VOTE_REQUEST));
        assertEquals(ACLMessage.PROPOSE, RailwayOntology.performative(Channel.VOTE));
        assertEquals(ACLMessage.ACCEPT_PROPOSAL, RailwayOntology.performative(Channel.VOTE_RESULT));
        assertEquals(ACLMessage.REQUEST, RailwayOntology.performative(Channel.ENTER));
        assertEquals(ACLMessage.REQUEST, RailwayOntology.performative(Channel.TRAVEL_START));
        assertEquals(ACLMessage.REQUEST, RailwayOntology.performative(Channel.START));
        assertEquals(ACLMessage.REQUEST, RailwayOntology.performative(Channel.PLAN_TRAIN));
        assertEquals(ACLMessage.QUERY_REF, RailwayOntology.performative(Channel.PATH_FIND));
        assertEquals(ACLMessage.INFORM, RailwayOntology.performative(Channel.PATH_FIND_REPLY));
        assertEquals(ACLMessage.INFORM, RailwayOntology.performative(Channel.ENTER_REPLY));
        assertEquals(ACLMessage.INFORM, RailwayOntology.performative(Channel.LEAVE));
        assertEquals(ACLMessage.INFORM, RailwayOntology.performative(Channel.TRAVEL_END));
        assertEquals(ACLMessage.INFORM, RailwayOntology.performative(Channel.STATION_INFO));
        assertEquals(ACLMessage.INFORM, RailwayOntology.performative(Channel.ROAD_STATE));
        assertEquals(ACLMessage.INFORM, RailwayOntology.performative(Channel.TRAIN_STATE));
    }

    @Test
    @DisplayName("the payload survives the ACL round trip unchanged, for every channel")
    void the_payload_round_trips_through_an_acl_message() {
        CanonicalMessages.all().forEach((channel, sample) -> {
            ACLMessage acl = Messages.build(sample.message(), sample.from(), sample.to());
            RailwayMessage back = Messages.contentOf(acl);
            assertEquals(sample.message(), back, channel.event());
            assertEquals(sample.message().payload(), back.payload(), channel.event());
        });
    }

    @Test
    @DisplayName("the conversation id is the trace subject, on every channel")
    void the_conversation_id_is_the_trace_subject() {
        CanonicalMessages.all().forEach((channel, sample) -> {
            ACLMessage acl = Messages.build(sample.message(), sample.from(), sample.to());
            assertEquals(sample.expectedSubject(), acl.getConversationId(), channel.event());
            // The fast path (reads the slot) and the normative derivation (reads the channel
            // identity and the payload) must agree, or the slot is a lie the probe would repeat.
            assertEquals(sample.expectedSubject(), Messages.subjectOf(acl), channel.event());
            assertEquals(sample.expectedSubject(), Messages.computeSubject(acl), channel.event());
            assertEquals(sample.expectedSubject(),
                    Messages.subjectOf(acl, Messages.contentOf(acl)), channel.event());
        });
    }

    @Test
    @DisplayName("StationInfo crosses as a value snapshot, not as the live alias Cybele passes")
    void station_info_is_a_snapshot() {
        // The baseline runs Local;NoSerialization, so the same Station.Info instance crosses the
        // channel on every sendInfo() and is then mutated in place (SEM-05, DEF-13). Here the
        // record is immutable and the ACL round trip yields a distinct, equal object -- the
        // wire form #27 pins.
        StationInfo sent = new StationInfo(1, 6);
        ACLMessage acl = Messages.build(sent, "stA", "Main");
        RailwayMessage received = Messages.contentOf(acl);
        assertEquals(sent, received);
        assertNotSame(sent, received);
        // A second send after the station's occupancy moved carries the new value; the first
        // message is unaffected, which is exactly what the aliased baseline cannot promise.
        StationInfo later = new StationInfo(2, 6);
        assertEquals(new StationInfo(1, 6), Messages.contentOf(acl));
        assertEquals(new StationInfo(2, 6), Messages.contentOf(Messages.build(later, "stA", "Main")));
        // And no enclosing instance to drag a whole Station onto the wire with it.
        assertNull(StationInfo.class.getEnclosingClass());
    }

    @Test
    @DisplayName("the probe copy is a second receiver, and it is off unless a topic is passed")
    void the_probe_copy_is_additive() {
        CanonicalMessages.Sample sample = CanonicalMessages.all().get(Channel.ENTER);
        AID topic = TopicUtility.createTopic(RailwayOntology.topicName(Channel.ENTER));

        ACLMessage withoutProbe = Messages.build(sample.message(), sample.from(), sample.to());
        assertEquals(1, receivers(withoutProbe).size());

        ACLMessage withProbe = Messages.build(sample.message(), sample.from(), sample.to(), topic);
        List<AID> both = receivers(withProbe);
        assertEquals(2, both.size());
        assertTrue(TopicUtility.isTopic(both.get(1)));
        // Field 5 still names the agent, never the topic.
        assertEquals(sample.to(), Messages.receiverName(withProbe));
        assertEquals(Messages.subjectOf(withoutProbe), Messages.subjectOf(withProbe));
    }

    @Test
    @DisplayName("there are fifteen topic names, one per channel constant, and they are stable strings")
    void topics_are_per_channel_constant() {
        List<String> names = new ArrayList<>();
        for (Channel c : Channel.values()) {
            String name = RailwayOntology.topicName(c);
            assertTrue(names.add(name));
            // The topic name and the ontology slot are deliberately the same string: one
            // identity per channel constant, used for delivery-observation and for matching.
            assertEquals(RailwayOntology.ontology(c), name);
            assertTrue(TopicUtility.isTopic(TopicUtility.createTopic(name)));
            // No agent name in it. That is the whole granularity decision in one assertion:
            // the count does not grow with the topology or with the number of trains.
            assertEquals("railway." + c.event(), name);
        }
        assertEquals(15, names.stream().distinct().count());
    }

    private static List<AID> receivers(ACLMessage acl) {
        List<AID> out = new ArrayList<>();
        jade.util.leap.Iterator it = acl.getAllReceiver();
        while (it.hasNext()) {
            out.add((AID) it.next());
        }
        return out;
    }
}
