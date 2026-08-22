/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import java.util.concurrent.TimeUnit;

import cz.vutbr.fit.ags.railway.domain.clock.VirtualClock;
import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterReply;
import cz.vutbr.fit.ags.railway.domain.msg.EnterRequest;
import cz.vutbr.fit.ags.railway.domain.msg.LeaveNotice;
import cz.vutbr.fit.ags.railway.domain.msg.RoadDirection;
import cz.vutbr.fit.ags.railway.domain.msg.RoadStateReport;
import cz.vutbr.fit.ags.railway.domain.msg.TravelEnd;
import cz.vutbr.fit.ags.railway.domain.msg.TravelStart;
import cz.vutbr.fit.ags.railway.jade.Messages;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Interaction pair 4 of 4: {@code TRAVEL_START} → {@code TRAVEL_END} (#38).
 *
 * <h2>The pair that is not a reply</h2>
 *
 * Every other pair in this lane is request-then-answer on the receiving agent's own thread. This
 * one is not: {@code RoadAgent.travelStart} arms a simulated-time wake-up on its
 * {@code AgentClock} and returns, and the {@code TRAVEL_END} is emitted later by
 * {@code ClockTickerBehaviour} draining that clock. So what is under test is the seam #29 built —
 * a wake-up scheduled in <em>simulated</em> time firing on the <em>agent's own</em> thread — and
 * that seam exists nowhere but on a live platform. A POJO test can call {@code travelEnd()}
 * directly; it cannot show that anything would ever call it.
 *
 * <h2>{@code ENTER} and {@code TRAVEL_START} in the same class, and what that does <em>not</em>
 * prove</h2>
 *
 * A track's {@code ENTER} and its {@code TRAVEL_START} are <b>both {@code request}</b> — collision
 * row 2 of {@code docs/message-ontology.md} §7. Sending both to one live agent, in that order, and
 * getting the two different handlers is worth having; a {@code TRAVEL_END} arriving where a second
 * {@code ENTER_REPLY} would mean a misroute is a real assertion.
 * <p>
 * <b>But it is not the proof of §7's ontology-slot decision, and an earlier draft of this comment
 * claimed it was.</b> That claim was mutation-tested and is false: replacing
 * {@code Templates.of(channel)} with {@code MatchPerformative} alone — dropping the
 * {@code MatchOntology} half entirely — leaves this whole lane <b>green</b>. The reason is
 * structural. Every ported agent has <em>one</em> {@code Inbox} behaviour over the union of its
 * channels, and the routing decision is made afterwards by {@code dispatch}, which reads the
 * {@code :ontology} slot <em>off the message</em>. A weaker template still delivers both requests
 * to the same inbox, and the same {@code switch} still separates them.
 * <p>
 * So the disjointness of the fifteen templates is checked where it <em>is</em> observable: at L1, by
 * {@code TemplateDisjointnessTest} (all 225 cells against the real {@code MessageTemplate}) and the
 * four {@code *InboxTest} classes, which that same mutation fails <b>sixteen</b> times over. Two
 * mechanisms guard the §7 hang, and only one of them is visible from outside the agent. Recorded
 * here rather than quietly dropped, because a comment that overstates what a test proves is the
 * thing that makes a green suite misleading.
 *
 * <h2>No wall clock anywhere</h2>
 *
 * The track is given a {@link VirtualClock} — a {@code PacedClock} over a manual time source, i.e.
 * the production clock code driven by the test. Simulated time moves only when this test says so,
 * so the travel timer cannot fire early on a fast machine or late on a loaded one. The loop that
 * advances it is bounded by a {@code poll(timeout)} per step and by a deadline overall.
 */
class TravelIT extends ContainerFixture {

    /** The track's nominal travel time, in seconds — {@code RoadAgent}'s first argument. */
    private static final long DELAY_SECONDS = 1L;

    /** One advance step: two nominal travel times, comfortably past any Gaussian jitter draw. */
    private static final long STEP_SIM_MS = 2 * DELAY_SECONDS * 1000L;

    @Test
    @DisplayName("enter, travel_start, then the simulated-time wake-up sends travel_end to the train")
    void the_traversal_round_trips_through_the_simulated_clock() throws Exception {
        final Driver main = driver(RailwayMainAgent.MAIN_AGENT_NAME);
        final Driver vl3 = driver("vl3");
        final VirtualClock clock = new VirtualClock(0L, 1.0);
        final RoadAgent road = start("tr1", new RoadAgent(),
                Long.valueOf(DELAY_SECONDS), "stA", "stB", clock);

        assertEquals(RoadDirection.FREE,
                main.expectContent(Channel.ROAD_STATE, RoadStateReport.class, REPLY_MS).state());

        // ---- request #1: ENTER. The track admits and replies with the FAR end.
        vl3.send(new EnterRequest("vl3", "stA", "stB"), "tr1");
        final EnterReply admitted = vl3.expectContent(Channel.ENTER_REPLY, EnterReply.class, REPLY_MS);
        assertEquals("tr1", admitted.object());
        assertEquals("stB", admitted.next(),
                "entered from the left station, so the far end is the right one -- the field that"
                        + " catches a direction error, since ROAD_STATE.state is erased by the"
                        + " normalizer");
        assertEquals(RoadDirection.TRAVEL_RIGHT,
                main.expectContent(Channel.ROAD_STATE, RoadStateReport.class, REPLY_MS).state());

        // ---- request #2: TRAVEL_START. Same act, different ontology, different handler.
        vl3.send(new TravelStart("vl3"), "tr1");

        // Nothing is emitted synchronously; the wake-up is armed on the agent's AgentClock. Move
        // simulated time forward until the ticker drains it, bounded both per step and overall.
        ACLMessage arrived = null;
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REPLY_MS);
        while (arrived == null && System.nanoTime() < deadline) {
            clock.advanceSimBy(STEP_SIM_MS);
            arrived = vl3.poll(50L);
        }
        assertNotNull(arrived, "no TRAVEL_END within " + REPLY_MS + " ms of advancing the simulated"
                + " clock. Either travelStart armed nothing, or the ClockTickerBehaviour is not"
                + " draining this agent's AgentClock.");
        assertEquals(Channel.TRAVEL_END.id(), arrived.getOntology(),
                "a second ENTER_REPLY here would mean the second request had been routed into"
                        + " enter() -- the two share an act and are separated by the :ontology"
                        + " slot in RoadAgent.dispatch");
        assertEquals("tr1", ((TravelEnd) Messages.contentOf(arrived)).road());
        assertEquals("vl3", Messages.receiverName(arrived), "addressed to the travelling train");

        // ---- and the track frees up when the train leaves it
        vl3.send(new LeaveNotice("vl3"), "tr1");
        assertEquals(RoadDirection.FREE,
                main.expectContent(Channel.ROAD_STATE, RoadStateReport.class, REPLY_MS).state());

        vl3.expectSilence(SILENCE_MS);
        assertQueueDrained(road);
    }

    @Test
    @DisplayName("a second train is queued while the track is occupied and admitted when it frees")
    void the_track_queue_hands_the_slot_on_after_the_traversal() throws Exception {
        final Driver main = driver(RailwayMainAgent.MAIN_AGENT_NAME);
        final Driver vl3 = driver("vl3");
        final Driver vl4 = driver("vl4");
        final VirtualClock clock = new VirtualClock(0L, 1.0);
        final RoadAgent road = start("tr2", new RoadAgent(),
                Long.valueOf(DELAY_SECONDS), "stB", "stC", clock);
        main.expect(Channel.ROAD_STATE, REPLY_MS);

        vl3.send(new EnterRequest("vl3", "stB", "stC"), "tr2");
        assertEquals("stC", vl3.expectContent(Channel.ENTER_REPLY, EnterReply.class, REPLY_MS).next());
        main.expect(Channel.ROAD_STATE, REPLY_MS);

        // A queued train needs a planned slot: RoadQueueItem reads RoadSchedule.plannedTime, and an
        // unplanned train is DEF-06 (an NPE that under JADE kills the agent). So book it the way
        // Planning would -- through the election, on the wire.
        main.send(new cz.vutbr.fit.ags.railway.domain.msg.VoteResult("vl4", 0L), "tr2");
        vl4.send(new EnterRequest("vl4", "stB", "stC"), "tr2");
        assertEquals(RoadDirection.TRAVEL_RIGHT,
                main.expectContent(Channel.ROAD_STATE, RoadStateReport.class, REPLY_MS).state(),
                "the queued arm republishes the unchanged state -- the TRAVEL_x, TRAVEL_x pair the"
                        + " normalizer reads the queue path off");
        vl4.expectSilence(SILENCE_MS);

        // vl3 finishes and leaves; the slot goes to vl4, and vl4 is the one told.
        vl3.send(new LeaveNotice("vl3"), "tr2");
        final ACLMessage handover = vl4.expect(Channel.ENTER_REPLY, REPLY_MS);
        assertEquals("vl4", Messages.receiverName(handover));
        assertEquals("stC", ((EnterReply) Messages.contentOf(handover)).next());
        main.expect(Channel.ROAD_STATE, REPLY_MS);

        vl3.expectSilence(SILENCE_MS);
        assertQueueDrained(road);
    }
}
