/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import cz.vutbr.fit.ags.xhovor07.Planning;
import cz.vutbr.fit.ags.xhovor07.RailwayMainAgent;
import cz.vutbr.fit.ags.xhovor07.RoadAgent;
import cz.vutbr.fit.ags.xhovor07.StaticRailwayObject;
import cz.vutbr.fit.ags.xhovor07.Train;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The channel table itself (#27): fifteen channels, distinct identities, the payload arity each
 * record actually carries, and the performative assignment. These lock the decisions in
 * {@code docs/message-ontology.md} to the code — an edit to either without the other fails here.
 */
class ChannelTableTest {

    @Test
    @DisplayName("there are exactly fifteen channels, with distinct ids and event tokens")
    void fifteen_channels_with_distinct_identities() {
        assertEquals(15, Channel.values().length);
        Set<String> ids = new HashSet<>();
        Set<String> events = new HashSet<>();
        Set<String> inventoryIds = new HashSet<>();
        for (Channel c : Channel.values()) {
            assertTrue(ids.add(c.id()), "duplicate id " + c.id());
            assertTrue(events.add(c.event()), "duplicate event " + c.event());
            assertTrue(inventoryIds.add(c.inventoryId()), "duplicate inventory id " + c.inventoryId());
            assertSame(c, Channel.byEvent(c.event()));
            assertSame(c, Channel.byId(c.id()));
        }
        assertThrows(IllegalArgumentException.class, () -> Channel.byEvent("NOT_A_CHANNEL"));
        assertThrows(IllegalArgumentException.class, () -> Channel.byId("railway.NOT_A_CHANNEL"));
    }

    @Test
    @DisplayName("every channel name is pinned to the application constant it transcribes, not to a literal")
    void cybele_channel_names_are_pinned_to_the_real_constants() {
        // Against the CONSTANTS, never against hand-copied strings. `domain` cannot see the
        // application -- domainPurity forbids the import, which is the whole point of that
        // source set -- but this test can, and a transcription that is only checked against a
        // copy of itself is not checked. Renaming a constant in the app now fails here.
        assertEquals(StaticRailwayObject.VOTE_REQUEST + "stA", Channel.VOTE_REQUEST.cybeleChannelName("stA"));
        assertEquals(StaticRailwayObject.VOTE_RESULT + "stA", Channel.VOTE_RESULT.cybeleChannelName("stA"));
        assertEquals(StaticRailwayObject.ENTER + "stA", Channel.ENTER.cybeleChannelName("stA"));
        assertEquals(StaticRailwayObject.LEAVE + "stA", Channel.LEAVE.cybeleChannelName("stA"));
        assertEquals(Train.START + "vl3", Channel.START.cybeleChannelName("vl3"));
        assertEquals(Train.ENTER_REPLY + "vl3", Channel.ENTER_REPLY.cybeleChannelName("vl3"));
        assertEquals(Train.TRAVEL_END + "vl3", Channel.TRAVEL_END.cybeleChannelName("vl3"));
        assertEquals(RoadAgent.TRAVEL_START + "tr1", Channel.TRAVEL_START.cybeleChannelName("tr1"));
        // The SECOND exception, and it is #33's. Station.PATH_FIND_REPLY is DELETED: the JADE
        // hub sends the reply to a station's AID, so after #33 ported RailwayMainAgent,
        // RailwayCanvas and TraceProbe's use of it, this assertion was the constant's only
        // remaining owner -- i.e. a constant kept alive so that a test could assert it equals a
        // literal, which asserts nothing. Re-pointed at the literal, which is where a check on
        // Channel's transcription always belonged: the subject of this test is Channel, not the
        // agent the 2008 author happened to declare the name on. The other twelve rows keep
        // their constants because those constants still have non-test owners.
        assertEquals("PATH_FIND_REPLY.stA", Channel.PATH_FIND_REPLY.cybeleChannelName("stA"));
        assertEquals(RailwayMainAgent.CHANNEL_STATION_INFO + "stA", Channel.STATION_INFO.cybeleChannelName("stA"));
        assertEquals(RailwayMainAgent.CHANNEL_ROAD_STATE + "tr1", Channel.ROAD_STATE.cybeleChannelName("tr1"));
        // The first exception, and it is the constant's fault, not the test's:
        // RailwayMainAgent.CHANNEL_TRAIN_STATE is package-private, so only a test in
        // cz.vutbr.fit.ags.xhovor07 could name it. Literal, flagged, not quietly the same as
        // the rest.
        assertEquals("TRAIN.STATE.vl3", Channel.TRAIN_STATE.cybeleChannelName("vl3"));
        assertEquals(Planning.PLAN_TRAIN, Channel.PLAN_TRAIN.cybeleChannelName(null));
        assertEquals(Planning.VOTE, Channel.VOTE.cybeleChannelName(null));
        assertEquals(RailwayMainAgent.PATH_FIND, Channel.PATH_FIND.cybeleChannelName(null));

        // The three unsuffixed channels ignore the argument entirely. PATH_FIND's constant
        // really does end in a dot and really is used bare -- RailwayMainAgent.PATH_FIND is
        // "PATH_FIND." and nothing is appended to it. Reproduced, not tidied.
        assertEquals("PATH_FIND.", RailwayMainAgent.PATH_FIND);
        assertEquals(RailwayMainAgent.PATH_FIND, Channel.PATH_FIND.cybeleChannelName("stA"));
        assertFalse(Channel.PATH_FIND.suffixed());
        assertFalse(Channel.PLAN_TRAIN.suffixed());
        assertFalse(Channel.VOTE.suffixed());
        assertEquals(12, List.of(Channel.values()).stream().filter(Channel::suffixed).count());
    }

    @Test
    @DisplayName("every record carries exactly as many slots as its channel declares keys")
    void payload_arity_matches_the_record() {
        CanonicalMessages.all().forEach((channel, sample) -> {
            assertSame(channel, sample.message().channel());
            assertEquals(channel.payloadKeys().size(), sample.message().values().size(),
                    channel.event() + " arity");
        });
        assertEquals(15, CanonicalMessages.all().size());
    }

    @Test
    @DisplayName("the performative assignment is four requests, seven informs and one each of the election acts")
    void the_performative_assignment_is_pinned() {
        Map<Performative, Integer> counts = new EnumMap<>(Performative.class);
        for (Channel c : Channel.values()) {
            counts.merge(c.performative(), 1, Integer::sum);
        }
        assertEquals(4, counts.get(Performative.REQUEST));
        assertEquals(7, counts.get(Performative.INFORM));
        assertEquals(1, counts.get(Performative.CFP));
        assertEquals(1, counts.get(Performative.PROPOSE));
        assertEquals(1, counts.get(Performative.ACCEPT_PROPOSAL));
        assertEquals(1, counts.get(Performative.QUERY_REF));

        // The election, spelled out: it is a degenerate contract net with no refusal branch.
        assertEquals(Performative.CFP, Channel.VOTE_REQUEST.performative());
        assertEquals(Performative.PROPOSE, Channel.VOTE.performative());
        assertEquals(Performative.ACCEPT_PROPOSAL, Channel.VOTE_RESULT.performative());
        // The one question the application asks.
        assertEquals(Performative.QUERY_REF, Channel.PATH_FIND.performative());
        assertEquals(Performative.INFORM, Channel.PATH_FIND_REPLY.performative());
        // Hyphenated FIPA spelling, which is what JADE's getInteger() expects.
        assertEquals("ACCEPT-PROPOSAL", Performative.ACCEPT_PROPOSAL.fipaName());
        assertEquals("QUERY-REF", Performative.QUERY_REF.fipaName());
    }

    @Test
    @DisplayName("the subject comes from the payload on eight channels, an endpoint on seven")
    void the_subject_sources_partition_the_table() {
        long fromPayload = 0;
        long fromSender = 0;
        long fromReceiver = 0;
        for (Channel c : Channel.values()) {
            switch (c.subject()) {
                case FROM_PAYLOAD -> fromPayload++;
                case FROM_SENDER -> fromSender++;
                case FROM_RECEIVER -> fromReceiver++;
            }
        }
        assertEquals(8, fromPayload);
        assertEquals(3, fromSender);
        assertEquals(4, fromReceiver);
    }

    @Test
    @DisplayName("eleven of the fifteen channels have a train as their subject; the other four do not")
    void eleven_channels_are_about_a_train() {
        long aboutATrain = CanonicalMessages.all().entrySet().stream()
                .filter(e -> e.getValue().expectedSubject().startsWith("vl"))
                .count();
        assertEquals(11, aboutATrain);
        // docs/trace-format.md: "The other four -- PATH_FIND, PATH_FIND_REPLY, STATION_INFO and
        // ROAD_STATE -- concern no train and carry the station or track instead."
        Set<Channel> notATrain = Set.of(Channel.PATH_FIND, Channel.PATH_FIND_REPLY,
                Channel.STATION_INFO, Channel.ROAD_STATE);
        CanonicalMessages.all().forEach((channel, sample) -> assertEquals(
                !notATrain.contains(channel),
                sample.expectedSubject().startsWith("vl"),
                channel.event() + " subject " + sample.expectedSubject()));
    }

    @Test
    @DisplayName("the subject is computed from the channel identity, not from where a port hooks the send")
    void subject_is_computed_from_the_channel_identity() {
        CanonicalMessages.all().forEach((channel, sample) -> assertEquals(
                sample.expectedSubject(),
                sample.message().subject(sample.from(), sample.to()),
                channel.event()));
    }

    @Test
    @DisplayName("each agent kind's inbound set is the one the port has to template on")
    void inbound_sets_are_what_each_agent_must_consume() {
        // A station takes the four StaticRailwayObject channels plus its PathFinding reply.
        assertEquals(List.of(Channel.VOTE_REQUEST, Channel.VOTE_RESULT, Channel.ENTER,
                        Channel.LEAVE, Channel.PATH_FIND_REPLY),
                Channel.inboundFor(Party.STATION));
        // A track takes the same four plus TRAVEL_START.
        assertEquals(List.of(Channel.VOTE_REQUEST, Channel.VOTE_RESULT, Channel.ENTER,
                        Channel.LEAVE, Channel.TRAVEL_START),
                Channel.inboundFor(Party.ROAD));
        assertEquals(List.of(Channel.START, Channel.ENTER_REPLY, Channel.TRAVEL_END),
                Channel.inboundFor(Party.TRAIN));
        assertEquals(List.of(Channel.STATION_INFO, Channel.ROAD_STATE, Channel.TRAIN_STATE,
                        Channel.PLAN_TRAIN, Channel.VOTE, Channel.PATH_FIND),
                Channel.inboundFor(Party.MAIN));

        // Jointly exhaustive: the four inbound sets cover all fifteen channels.
        Set<Channel> covered = new HashSet<>();
        for (Party p : List.of(Party.STATION, Party.ROAD, Party.TRAIN, Party.MAIN)) {
            covered.addAll(Channel.inboundFor(p));
        }
        assertEquals(15, covered.size());
    }

    @Test
    @DisplayName("STATIC_OBJECT is an endpoint kind, not an agent kind, and asking is an error")
    void inbound_for_static_object_is_refused() {
        // It used to return the four StaticRailwayObject channels -- because `covers` is
        // trivially true for itself -- rather than the station-union-road set a caller would
        // read that as. A wrong answer in the shape of a right one is worse than no answer.
        assertThrows(IllegalArgumentException.class, () -> Channel.inboundFor(Party.STATIC_OBJECT));
    }

    @Test
    @DisplayName("five channel families carry `Main` in a trace endpoint, not just PLAN_TRAIN")
    void five_families_pin_main_as_an_endpoint() {
        // Generator, Planning and VoteCollecting are ACTIVITIES of RailwayMainAgent, so the
        // goldens carry the literal `Main` in these fields. If #34 gives the three their own
        // AIDs, all five diff -- not just PLAN_TRAIN, which is the only one the first draft of
        // docs/message-ontology.md warned about.
        assertEquals(Party.MAIN, Channel.PLAN_TRAIN.sender());     // field 4
        assertEquals(Party.MAIN, Channel.PLAN_TRAIN.receiver());   // field 5 -- both, uniquely
        assertEquals(Party.MAIN, Channel.VOTE_REQUEST.sender());   // Planning
        assertEquals(Party.MAIN, Channel.VOTE_RESULT.sender());    // Planning
        assertEquals(Party.MAIN, Channel.START.sender());          // Planning
        assertEquals(Party.MAIN, Channel.VOTE.receiver());         // VoteCollecting

        long endpointsNamedMain = List.of(Channel.values()).stream()
                .filter(c -> c.sender() == Party.MAIN || c.receiver() == Party.MAIN)
                .count();
        // The five above plus the two RailwayMainAgent handles itself (PATH_FIND,
        // PATH_FIND_REPLY) and the three state pushes it receives.
        assertEquals(10, endpointsNamedMain);
    }
}
