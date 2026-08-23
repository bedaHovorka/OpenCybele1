/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import java.util.ArrayList;
import java.util.List;

import cz.vutbr.fit.ags.railway.domain.clock.VirtualClock;
import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import cz.vutbr.fit.ags.railway.domain.msg.RoadStateReport;
import cz.vutbr.fit.ags.railway.domain.msg.Vote;
import cz.vutbr.fit.ags.railway.domain.msg.VoteRequest;
import cz.vutbr.fit.ags.railway.domain.msg.VoteResult;
import cz.vutbr.fit.ags.railway.jade.Messages;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Interaction pair 1 of 4: the election, {@code VOTE_REQUEST} → {@code VOTE} → {@code VOTE_RESULT}
 * (#38, {@code docs/message-ontology.md} §4.1).
 *
 * <h2>What this catches that L1 and L3 cannot</h2>
 *
 * At L1, {@code StationInboxTest} and {@code RoadAgentInboxTest} call {@code dispatch(acl)} with a
 * hand-built message and capture {@code emit}. They therefore prove the handler, and assume the
 * wiring: that {@code cfp} on {@code railway.VOTE_REQUEST} actually reaches a live station's
 * {@code Inbox} template rather than falling through to its {@code Drain}, that the {@code propose}
 * the station sends back is routed to the literal AID {@code Main} by the AMS white pages with no
 * DF and no registration, and that neither leaves anything in the receiver's one message queue.
 * <b>Those three assumptions are the port</b> (#27), and this is where they are exercised.
 * <p>
 * At L3 they are exercised too, but only in aggregate: an election that silently failed shows up as
 * a diff several thousand lines into a normalized trace, or as a run that hangs on
 * {@code VoteRound}'s deliberately untimed latch (DEF-22).
 *
 * <h2>The assertion with teeth is the second vote, not the first</h2>
 *
 * A first vote against an empty timetable is {@code 0}, and {@code 0} is also what a station that
 * never applied the {@code VOTE_RESULT} would answer — the exact shape of the weak test #37 caught
 * (a value that was {@code 0} under both the correct and the broken implementation). So every test
 * below drives the round to completion and then asks the voter for a <em>second</em> vote on the
 * same instant: the answer is non-zero only if the {@code accept-proposal} arrived, was matched by
 * the {@code :ontology} slot rather than by its performative, and reached
 * {@code StationSchedule.addToPlan}. Deleting the {@code VOTE_RESULT} case from
 * {@code Station.dispatch} turns {@code 2833} back into {@code 0} and fails it.
 */
class VoteRoundTripIT extends ContainerFixture {

    /** An arbitrary but fixed simulated instant. Nothing here reads a clock. */
    private static final long WHEN = 100_000L;

    @Test
    @DisplayName("a station votes on a cfp, and the accept-proposal it is sent changes its next vote")
    void the_station_leg_of_the_election_round_trips() throws Exception {
        final Driver main = driver(RailwayMainAgent.MAIN_AGENT_NAME);
        final Station station = start("stA", new Station(),
                Integer.valueOf(1), new ArrayList<String>(List.of("tr1")));

        // The station's opening push. It is the first line it would contribute to a trace, and it
        // is here because it proves the station reached the end of setup() before anything else.
        final StationInfo opening = main.expectContent(Channel.STATION_INFO, StationInfo.class, REPLY_MS);
        assertEquals(new StationInfo(0, 1), opening);

        // ---- cfp -> propose
        main.send(new VoteRequest("vl3", WHEN), "stA");
        final ACLMessage first = main.expect(Channel.VOTE, REPLY_MS);
        assertEquals(ACLMessage.PROPOSE, first.getPerformative(),
                "the VOTE channel's act is propose (docs/message-ontology.md section 4.1)");
        assertEquals("stA", Messages.senderName(first), "the vote came from the station itself");
        assertEquals(RailwayMainAgent.MAIN_AGENT_NAME, Messages.receiverName(first),
                "and it was addressed to Main by name -- no DF, no broadcast");
        final Vote firstVote = (Vote) Messages.contentOf(first);
        assertEquals("stA", firstVote.voter());
        assertEquals("vl3", firstVote.train());
        assertEquals(0L, firstVote.diff(), "an empty timetable has no objection");

        // ---- accept-proposal, and the proof it landed
        main.send(new VoteResult("vl3", WHEN), "stA");
        main.send(new VoteRequest("vl4", WHEN), "stA");
        final Vote secondVote = main.expectContent(Channel.VOTE, Vote.class, REPLY_MS);
        assertEquals("vl4", secondVote.train());
        final long window = ScenarioConfig.get().getStationVoteWindowMs();
        assertEquals(window / 3, secondVote.diff(),
                "capacity 1 with vl3's slot booked: the station must push vl4 to voteWindow/3"
                        + " past the last slot it holds. A zero here means the VOTE_RESULT never"
                        + " reached StationSchedule.addToPlan.");
        assertNotEquals(0L, secondVote.diff(), "and it must not be the vacuous value");

        main.expectSilence(SILENCE_MS);
        assertQueueDrained(station);
    }

    @Test
    @DisplayName("a track votes on the same cfp, and its accept-proposal changes its next vote too")
    void the_track_leg_of_the_election_round_trips() throws Exception {
        final Driver main = driver(RailwayMainAgent.MAIN_AGENT_NAME);
        // A track needs the simulation's shared clock as its fourth argument. A VirtualClock is a
        // PacedClock over a manual time source -- the same production code, driven by the test --
        // so nothing here depends on wall clock. This test never advances it: a vote is a pure
        // function of the timetable and the instant in the message.
        final RoadAgent road = start("tr1", new RoadAgent(),
                Long.valueOf(1), "stA", "stB", new VirtualClock(0L, 1.0));

        final RoadStateReport opening =
                main.expectContent(Channel.ROAD_STATE, RoadStateReport.class, REPLY_MS);
        assertEquals(cz.vutbr.fit.ags.railway.domain.msg.RoadDirection.FREE, opening.state());

        main.send(new VoteRequest("vl3", WHEN), "tr1");
        final Vote firstVote = main.expectContent(Channel.VOTE, Vote.class, REPLY_MS);
        assertEquals("tr1", firstVote.voter());
        assertEquals(0L, firstVote.diff());

        main.send(new VoteResult("vl3", WHEN), "tr1");
        main.send(new VoteRequest("vl4", WHEN), "tr1");
        final Vote secondVote = main.expectContent(Channel.VOTE, Vote.class, REPLY_MS);
        // RoadSchedule: anything at all in [t - delay, t + delay) pushes the newcomer one full
        // travel time past the last slot. delay is the 1 s constructor argument, in ms.
        assertEquals(1000L, secondVote.diff(),
                "a track holds one train: with vl3 booked at WHEN, vl4 must be pushed a full"
                        + " travel time. A zero here means the VOTE_RESULT never reached"
                        + " RoadSchedule.addToPlan.");

        main.expectSilence(SILENCE_MS);
        assertQueueDrained(road);
    }

    @Test
    @DisplayName("one cfp to each of two voters produces two proposes, each naming its own voter")
    void the_fan_in_leg_keeps_the_voters_apart() throws Exception {
        // VOTE is the one many-senders-to-one-receiver channel (docs/message-ontology.md section 2:
        // "fan-in is not fan-out"). Planning broadcasts a cfp to every member of a path and every
        // member answers the same AID. This is that shape in miniature: two voters, two unicasts
        // out, two unicasts back, and the collector has to be able to tell them apart -- which it
        // does from the payload's voter field, not from the channel, because there is one channel.
        final Driver main = driver(RailwayMainAgent.MAIN_AGENT_NAME);
        final Station station = start("stB", new Station(),
                Integer.valueOf(2), new ArrayList<String>(List.of("tr2")));
        final RoadAgent road = start("tr2", new RoadAgent(),
                Long.valueOf(2), "stB", "stC", new VirtualClock(0L, 1.0));
        // The two agents were started together, so their opening pushes race at Main. Which order
        // they arrive in is not a property of the port, so it is not asserted -- only that both
        // arrived. (This is the one place in the lane where two agents start concurrently; every
        // other assertion here is on a causally ordered pair.)
        assertEquals(List.of("ROAD_STATE", "STATION_INFO"), main.expectChannels(2, REPLY_MS));

        main.send(new VoteRequest("vl9", WHEN), "stB");
        main.send(new VoteRequest("vl9", WHEN), "tr2");

        final Vote a = main.expectContent(Channel.VOTE, Vote.class, REPLY_MS);
        final Vote b = main.expectContent(Channel.VOTE, Vote.class, REPLY_MS);
        final List<String> voters = new ArrayList<String>(List.of(a.voter(), b.voter()));
        java.util.Collections.sort(voters);
        assertEquals(List.of("stB", "tr2"), voters,
                "both voters answered, and each named itself rather than the other");
        assertEquals("vl9", a.train());
        assertEquals("vl9", b.train());

        main.expectSilence(SILENCE_MS);
        assertQueueDrained(station);
        assertQueueDrained(road);
    }
}
