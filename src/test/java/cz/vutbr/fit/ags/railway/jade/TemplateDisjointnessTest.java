/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.jade;

import cz.vutbr.fit.ags.railway.domain.msg.CanonicalMessages;
import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.Party;
import cz.vutbr.fit.ags.railway.domain.msg.Performative;
import jade.core.JadePlatformFixture;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acceptance criterion the JADE port most needs and the Cybele baseline cannot fail:
 * <em>templates mutually disjoint, jointly exhaustive, no message type left unconsumed.</em>
 * <p>
 * A JADE agent has one queue. A message matching no active template stays in it forever, and
 * {@code Behaviour.block()} blocks the whole agent, so an incomplete template set does not fail
 * loudly — it hangs. These tests are the proof, against the real {@code MessageTemplate}
 * implementation rather than against a reading of it.
 */
class TemplateDisjointnessTest {

    @BeforeAll
    static void platform() {
        JadePlatformFixture.install("opencybele-test");
    }

    private static Map<Channel, ACLMessage> canonicalAclMessages() {
        Map<Channel, ACLMessage> out = new EnumMap<>(Channel.class);
        CanonicalMessages.all().forEach((channel, sample) ->
                out.put(channel, Messages.build(sample.message(), sample.from(), sample.to())));
        return out;
    }

    @Test
    @DisplayName("each of the fifteen templates matches its own channel and no other -- all 225 cells")
    void the_fifteen_templates_are_pairwise_disjoint() {
        Map<Channel, ACLMessage> messages = canonicalAclMessages();
        for (Channel template : Channel.values()) {
            MessageTemplate t = Templates.of(template);
            for (Channel message : Channel.values()) {
                assertEquals(template == message, t.match(messages.get(message)),
                        "template " + template.event() + " vs message " + message.event());
            }
        }
    }

    @Test
    @DisplayName("performative alone would NOT be disjoint, which is why the ontology slot carries the identity")
    void performative_alone_would_collide() {
        Map<Channel, ACLMessage> messages = canonicalAclMessages();
        // Seven channels are inform, and the collisions land inside single agents: a station
        // receives both LEAVE and PATH_FIND_REPLY, a train both ENTER_REPLY and TRAVEL_END, a
        // track both ENTER and TRAVEL_START, and Main three separate informs.
        MessageTemplate informOnly = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
        long informs = List.of(Channel.values()).stream()
                .filter(c -> informOnly.match(messages.get(c))).count();
        assertEquals(7, informs);
        assertTrue(informOnly.match(messages.get(Channel.LEAVE)));
        assertTrue(informOnly.match(messages.get(Channel.PATH_FIND_REPLY)));

        MessageTemplate requestOnly = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
        assertTrue(requestOnly.match(messages.get(Channel.ENTER)));
        assertTrue(requestOnly.match(messages.get(Channel.TRAVEL_START)));

        // The ontology slot resolves both, because a message has exactly one of them.
        assertFalse(Templates.of(Channel.LEAVE).match(messages.get(Channel.PATH_FIND_REPLY)));
        assertFalse(Templates.of(Channel.ENTER).match(messages.get(Channel.TRAVEL_START)));
    }

    @Test
    @DisplayName("each agent kind's inbound template matches exactly its own channels")
    void per_agent_templates_match_exactly_the_inbound_set() {
        Map<Channel, ACLMessage> messages = canonicalAclMessages();
        for (Party self : List.of(Party.STATION, Party.ROAD, Party.TRAIN, Party.MAIN)) {
            MessageTemplate inbound = Templates.inbound(self);
            MessageTemplate unexpected = Templates.unexpected(self);
            Set<Channel> expected = new HashSet<>(Channel.inboundFor(self));
            for (Channel c : Channel.values()) {
                ACLMessage m = messages.get(c);
                assertEquals(expected.contains(c), inbound.match(m), self + " inbound " + c.event());
                // Exhaustive by construction: unexpected() is the exact complement, so every
                // message is claimed by exactly one of the two.
                assertEquals(!expected.contains(c), unexpected.match(m), self + " unexpected " + c.event());
            }
        }
    }

    @Test
    @DisplayName("the four agent kinds together consume all fifteen channels; nothing is left in a queue")
    void every_channel_is_consumed_by_some_agent() {
        Map<Channel, ACLMessage> messages = canonicalAclMessages();
        Set<Channel> consumed = new HashSet<>();
        for (Party self : List.of(Party.STATION, Party.ROAD, Party.TRAIN, Party.MAIN)) {
            MessageTemplate inbound = Templates.inbound(self);
            for (Channel c : Channel.values()) {
                if (inbound.match(messages.get(c))) {
                    consumed.add(c);
                }
            }
        }
        assertEquals(15, consumed.size());
        assertEquals(new HashSet<>(List.of(Channel.values())), consumed);
    }

    @Test
    @DisplayName("a right-channel wrong-performative message matches nothing and is caught by the drain")
    void a_tampered_performative_is_caught_rather_than_accepted() {
        // The performative is kept in the template even though the ontology slot alone would be
        // disjoint. This is why: a message that claims to be an ENTER but carries INFORM is not
        // quietly consumed by the ENTER behaviour -- it falls through to unexpected(), which a
        // port should log loudly. #38's "the queue drains" is checkable against exactly this.
        CanonicalMessages.Sample sample = CanonicalMessages.all().get(Channel.ENTER);
        ACLMessage tampered = Messages.build(sample.message(), sample.from(), sample.to());
        tampered.setPerformative(ACLMessage.INFORM);

        for (Channel c : Channel.values()) {
            assertFalse(Templates.of(c).match(tampered), c.event() + " must not claim it");
        }
        assertTrue(Templates.unexpected(Party.ROAD).match(tampered));
        assertTrue(Templates.unexpected(Party.STATION).match(tampered));
    }

    @Test
    @DisplayName("a foreign message -- no ontology slot -- is caught by every agent's drain")
    void a_foreign_message_is_caught_by_the_drain() {
        ACLMessage foreign = new ACLMessage(ACLMessage.INFORM);
        foreign.setContent("hello");
        for (Party self : List.of(Party.STATION, Party.ROAD, Party.TRAIN, Party.MAIN)) {
            assertFalse(Templates.inbound(self).match(foreign), self.toString());
            assertTrue(Templates.unexpected(self).match(foreign), self.toString());
        }
    }

    @Test
    @DisplayName("a station's five templates are disjoint from each other -- the worst case in the app")
    void the_worst_case_agent_is_still_disjoint() {
        Map<Channel, ACLMessage> messages = canonicalAclMessages();
        for (Party self : List.of(Party.STATION, Party.ROAD)) {
            List<Channel> inbound = Channel.inboundFor(self);
            assertEquals(5, inbound.size());
            for (Channel a : inbound) {
                for (Channel b : inbound) {
                    assertEquals(a == b, Templates.of(a).match(messages.get(b)),
                            self + ": " + a.event() + " vs " + b.event());
                }
            }
        }
        // And the election acts are the ones a port is most likely to confuse, because Cybele
        // has none of them.
        assertEquals(Performative.CFP, Channel.VOTE_REQUEST.performative());
        assertEquals(Performative.ACCEPT_PROPOSAL, Channel.VOTE_RESULT.performative());
        assertFalse(Templates.of(Channel.VOTE_REQUEST).match(messages.get(Channel.VOTE_RESULT)));
    }
}
