/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import cz.vutbr.fit.ags.railway.domain.TravelDelay;
import cz.vutbr.fit.ags.railway.domain.clock.VirtualClock;
import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterReply;
import cz.vutbr.fit.ags.railway.domain.msg.EnterRequest;
import cz.vutbr.fit.ags.railway.domain.msg.LeaveNotice;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.RoadDirection;
import cz.vutbr.fit.ags.railway.domain.msg.RoadStateReport;
import cz.vutbr.fit.ags.railway.domain.msg.TravelEnd;
import cz.vutbr.fit.ags.railway.domain.msg.TravelStart;
import cz.vutbr.fit.ags.railway.domain.msg.Vote;
import cz.vutbr.fit.ags.railway.domain.msg.VoteRequest;
import cz.vutbr.fit.ags.railway.domain.msg.VoteResult;
import cz.vutbr.fit.ags.railway.jade.Messages;
import jade.core.JadeAgentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 for #31: the direction state machine, the waiting queue, the travel timer and the seeded
 * stream — a real {@link RoadAgent} driven as a POJO, {@code docs/TESTING.md} §4.1.
 * <p>
 * No container, no platform, no threads. The agent is named by {@link JadeAgentFixture}, its one
 * outbound seam is redirected into a list, and simulated time is a {@link VirtualClock} the test
 * owns — so "the timer fires exactly once, at the drawn delay" is an assertion here rather than a
 * sleep. The drain that {@code ClockTickerBehaviour} performs on the agent thread is performed by
 * the test instead, through {@code agentClock().runDue()}: same call, same thread rules, no ticker.
 * <p>
 * What each group is holding down:
 * <ul>
 *   <li><b>the state machine</b>, every arm including a train arriving while the track is occupied
 *       in each direction, and a handover that <em>reverses</em> the direction;</li>
 *   <li><b>{@code ENTER_REPLY.next}</b>, which is what pins direction in a golden — the
 *       {@code ROAD_STATE.state} field this agent is named for is erased by the normalizer's
 *       {@code road-state} projection ({@code docs/trace-normalizer.md} §2.1), and §2.4's
 *       correction says so explicitly;</li>
 *   <li><b>the queue-path signature</b> §2.4 tells #39 to look for: on a contended track the
 *       {@code ENTER_REPLY} is sent from inside {@code leave} and therefore lands <em>after</em>
 *       it. A port that never queues would pass every {@code ROAD_STATE} check and fail here;</li>
 *   <li><b>the two pinned defects</b>, DEF-04 and DEF-06, driven through the agent — which is
 *       where a port author who has not read {@code RoadQueue} would "fix" them;</li>
 *   <li><b>the travel timer and the seeded stream</b>, including DEF-16's negative delay.</li>
 * </ul>
 */
class RoadAgentStateMachineTest {

    /** {@code tr1} in the default topology: stA - stH, one second. */
    private static final String ROAD = "tr1";
    private static final String LEFT = "stA";
    private static final String RIGHT = "stH";
    private static final long DELAY_SEC = 1L;
    private static final long BASE_MS = DELAY_SEC * 1000L;

    /** One message the road tried to send. */
    private record Sent(RailwayMessage message, String receiver) {
        Channel channel() {
            return message.channel();
        }
    }

    /** A real {@link RoadAgent} with its outbound seam redirected into a list. */
    private static final class RecordingRoad extends RoadAgent {
        private static final long serialVersionUID = 1L;
        private final transient List<Sent> sent = new ArrayList<Sent>();

        @Override
        protected void emit(RailwayMessage message, String receiver) {
            sent.add(new Sent(message, receiver));
        }

        List<Channel> channels() {
            List<Channel> out = new ArrayList<Channel>();
            for (Sent s : sent) {
                out.add(s.channel());
            }
            return out;
        }

        List<RoadDirection> states() {
            List<RoadDirection> out = new ArrayList<RoadDirection>();
            for (Sent s : sent) {
                if (s.message() instanceof RoadStateReport report) {
                    out.add(report.state());
                }
            }
            return out;
        }

        Sent last() {
            return sent.get(sent.size() - 1);
        }
    }

    private static RecordingRoad road(VirtualClock clock) {
        return road(clock, ROAD);
    }

    private static RecordingRoad road(VirtualClock clock, String name) {
        RecordingRoad road = new RecordingRoad();
        JadeAgentFixture.name(road, name);
        road.setArguments(new Object[]{Long.valueOf(DELAY_SEC), LEFT, RIGHT, clock});
        road.setup();
        return road;
    }

    private static void enter(RecordingRoad road, String train, String from) {
        road.dispatch(Messages.build(new EnterRequest(train, from, "stB"), train, ROAD));
    }

    private static void leave(RecordingRoad road, String train) {
        road.dispatch(Messages.build(new LeaveNotice(train), train, ROAD));
    }

    private static void travelStart(RecordingRoad road, String train) {
        road.dispatch(Messages.build(new TravelStart(train), train, ROAD));
    }

    // ---------------------------------------------------------------------------------------
    // The state machine
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("setup ends in the opening ROAD_STATE=FREE, as the 2008 constructor's last statement did")
    void the_opening_line_is_a_free_road_state() {
        RecordingRoad road = road(new VirtualClock());

        assertEquals(List.of(Channel.ROAD_STATE), road.channels());
        assertEquals(List.of(RoadDirection.FREE), road.states());
        assertEquals(RailwayMainAgent.MAIN_AGENT_NAME, road.last().receiver());
        assertEquals(RoadDirection.FREE, road.roadState());
    }

    @Test
    @DisplayName("FREE + ENTER from the left station travels RIGHT, and the reply names the far end")
    void entering_from_the_left_travels_right() {
        RecordingRoad road = road(new VirtualClock());
        road.sent.clear();

        enter(road, "vl0", LEFT);

        assertEquals(RoadDirection.TRAVEL_RIGHT, road.roadState());
        assertEquals(List.of(Channel.ENTER_REPLY, Channel.ROAD_STATE), road.channels());
        EnterReply reply = (EnterReply) road.sent.get(0).message();
        assertEquals(ROAD, reply.object());
        assertEquals(RIGHT, reply.next(), "the far end, which is what pins direction in the golden");
        assertEquals("vl0", road.sent.get(0).receiver());
        assertEquals(List.of(RoadDirection.TRAVEL_RIGHT), road.states());
    }

    @Test
    @DisplayName("FREE + ENTER from the right station travels LEFT, and the reply names the far end")
    void entering_from_the_right_travels_left() {
        RecordingRoad road = road(new VirtualClock());
        road.sent.clear();

        enter(road, "vl0", RIGHT);

        assertEquals(RoadDirection.TRAVEL_LEFT, road.roadState());
        EnterReply reply = (EnterReply) road.sent.get(0).message();
        assertEquals(LEFT, reply.next());
        assertEquals(List.of(RoadDirection.TRAVEL_LEFT), road.states());
    }

    @Test
    @DisplayName("a train arriving while the track travels RIGHT is queued, and the unchanged state is republished")
    void an_arrival_while_travelling_right_is_queued() {
        VirtualClock clock = new VirtualClock();
        RecordingRoad road = road(clock);
        road.addToPlan("vl0", 1000);
        road.addToPlan("vl1", 2000);
        enter(road, "vl0", LEFT);
        road.sent.clear();

        enter(road, "vl1", RIGHT);

        assertEquals(RoadDirection.TRAVEL_RIGHT, road.roadState(), "the state must not move");
        assertEquals(1, road.queueSize());
        assertEquals(List.of(Channel.ROAD_STATE), road.channels(), "no reply for a queued train");
        assertEquals(List.of(RoadDirection.TRAVEL_RIGHT), road.states(),
                "the duplicate publish is 2008 behaviour and is the trace's witness to the queue"
                        + " path (trace-normalizer.md 2.4)");
    }

    @Test
    @DisplayName("a train arriving while the track travels LEFT is queued the same way")
    void an_arrival_while_travelling_left_is_queued() {
        RecordingRoad road = road(new VirtualClock());
        road.addToPlan("vl0", 1000);
        road.addToPlan("vl1", 2000);
        enter(road, "vl0", RIGHT);
        road.sent.clear();

        enter(road, "vl1", LEFT);

        assertEquals(RoadDirection.TRAVEL_LEFT, road.roadState());
        assertEquals(1, road.queueSize());
        assertEquals(List.of(RoadDirection.TRAVEL_LEFT), road.states());
    }

    @Test
    @DisplayName("LEAVE with an empty queue goes FREE and drops the train from the timetable")
    void leaving_an_uncontended_track_goes_free() {
        RecordingRoad road = road(new VirtualClock());
        road.addToPlan("vl0", 1000);
        enter(road, "vl0", LEFT);
        road.sent.clear();

        leave(road, "vl0");

        assertEquals(RoadDirection.FREE, road.roadState());
        assertEquals(List.of(Channel.ROAD_STATE), road.channels());
        assertEquals(List.of(RoadDirection.FREE), road.states());
        // The slot is gone: the road no longer objects to a train wanting the same instant.
        assertEquals(0, road.computeDifference("vl9", 1000));
    }

    @Test
    @DisplayName("LEAVE with a queued train hands the track over directly -- and can reverse the direction")
    void leaving_a_contended_track_hands_over_and_can_reverse() {
        RecordingRoad road = road(new VirtualClock());
        road.addToPlan("vl0", 1000);
        road.addToPlan("vl1", 2000);
        enter(road, "vl0", LEFT);          // TRAVEL_RIGHT
        enter(road, "vl1", RIGHT);         // queued, wants the other direction
        road.sent.clear();

        leave(road, "vl0");

        assertEquals(RoadDirection.TRAVEL_LEFT, road.roadState(), "the handover reverses direction");
        assertEquals(0, road.queueSize());
        assertEquals(List.of(Channel.ENTER_REPLY, Channel.ROAD_STATE), road.channels(),
                "the queued train's reply is sent from INSIDE leave, and therefore lands after it"
                        + " -- trace-normalizer.md 2.4's signature of the queue path");
        EnterReply reply = (EnterReply) road.sent.get(0).message();
        assertEquals("vl1", road.sent.get(0).receiver());
        assertEquals(LEFT, reply.next());
    }

    @Test
    @DisplayName("a handover that keeps the direction still emits the reply from inside leave")
    void a_handover_in_the_same_direction_keeps_the_direction() {
        RecordingRoad road = road(new VirtualClock());
        road.addToPlan("vl0", 1000);
        road.addToPlan("vl1", 2000);
        enter(road, "vl0", LEFT);
        enter(road, "vl1", LEFT);
        road.sent.clear();

        leave(road, "vl0");

        assertEquals(RoadDirection.TRAVEL_RIGHT, road.roadState());
        assertEquals(List.of(Channel.ENTER_REPLY, Channel.ROAD_STATE), road.channels());
        assertEquals(RIGHT, ((EnterReply) road.sent.get(0).message()).next());
    }

    @Test
    @DisplayName("a full traversal emits exactly one ROAD_STATE per transition, in 2008's sequence")
    void every_transition_publishes_exactly_one_road_state() {
        VirtualClock clock = new VirtualClock();
        RecordingRoad road = road(clock);
        road.addToPlan("vl0", 1000);
        road.addToPlan("vl1", 2000);

        enter(road, "vl0", LEFT);          // FREE -> TRAVEL_RIGHT
        travelStart(road, "vl0");          // no state change, no line
        enter(road, "vl1", RIGHT);         // queued: TRAVEL_RIGHT republished
        clock.advanceSimBy(60000);
        road.agentClock().runDue();        // TRAVEL_END, no state change
        leave(road, "vl0");                // handover: -> TRAVEL_LEFT
        leave(road, "vl1");                // -> FREE

        assertEquals(List.of(RoadDirection.FREE, RoadDirection.TRAVEL_RIGHT,
                        RoadDirection.TRAVEL_RIGHT, RoadDirection.TRAVEL_LEFT, RoadDirection.FREE),
                road.states());
        assertEquals(RoadDirection.FREE, road.roadState());
    }

    @Test
    @DisplayName("LEAVE on a free track trips the single-occupancy invariant (DEF-14's guard)")
    void leaving_a_free_track_trips_the_invariant() {
        RecordingRoad road = road(new VirtualClock());

        assertThrows(AssertionError.class, () -> leave(road, "vl0"));
    }

    // ---------------------------------------------------------------------------------------
    // Queue admission and ordering, under the real (pinned-defective) comparator
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("the queue admits in planned-slot order, not arrival order")
    void the_queue_admits_in_plan_order() {
        RecordingRoad road = road(new VirtualClock());
        road.addToPlan("vl0", 1000);
        road.addToPlan("late", 90000);
        road.addToPlan("early", 20000);
        enter(road, "vl0", LEFT);
        enter(road, "late", LEFT);
        enter(road, "early", LEFT);
        road.sent.clear();

        leave(road, "vl0");

        assertEquals("early", road.sent.get(0).receiver(), "the earlier plan wins");
        assertEquals(1, road.queueSize());
    }

    @Test
    @DisplayName("DEF-04, through the agent: a plan tie is NOT broken by direction frequency")
    void a_direction_tie_is_not_broken_by_direction_frequency() {
        RecordingRoad road = road(new VirtualClock());
        road.addToPlan("vl0", 1000);
        enter(road, "vl0", LEFT);

        // TOOLCHAIN NOTE: this arrangement is tuned against JDK 21's PriorityQueue.offer, which
        // sets `size` AFTER siftUp -- so a frequency() walking the queue mid-sift does not see
        // the element being inserted. JDK 8 set it before. The build pins a 21 toolchain; if that
        // moves, re-check that the mutant below still flips, because a different mid-sift view
        // changes which arrangement makes a working tie-break visible.
        //
        // Six waiting trains, ONE planned slot between them, so every comparison in the heap is
        // a tie and the tie-break is the only thing that could decide anything. The shape is not
        // arbitrary: with a WORKING frequency tie-break this is the arrangement in which the
        // busier direction visibly wins -- the last arrival sifts past its parent and past the
        // root, because by then four of the six waiting trains come from the left. Under the
        // 2008 comparator every one of those comparisons returns 0, nothing sifts, and the
        // first arrival stays at the head.
        String[] trains = {"r1", "l1", "r2", "l2", "l3", "l4"};
        String[] from = {RIGHT, LEFT, RIGHT, LEFT, LEFT, LEFT};
        for (int i = 0; i < trains.length; i++) {
            road.addToPlan(trains[i], 50000);
            enter(road, trains[i], from[i]);
        }
        assertEquals(6, road.queueSize());
        road.sent.clear();

        leave(road, "vl0");

        // Do not "fix" DEF-04: RoadQueue.frequency compares a RoadQueueItem to a String through
        // Object.equals, is false for every pair that can be built, and returns a constant 0 --
        // so the author's documented "potom podle poctu pozadavku z daneho smeru" never runs and
        // the heap's own order decides. A port whose road queue really did break ties by
        // direction would order trains differently from every recorded golden (defect-triage.md
        // DEF-04, "that is a port bug").
        assertEquals("r1", road.sent.get(0).receiver(),
                "DEF-04 is pinned: the direction tie-break is dead code returning 0, so the"
                        + " first arrival keeps the head of the heap");
        assertEquals(RoadDirection.TRAVEL_LEFT, road.roadState(),
                "and the direction follows the train the dead tie-break left in front");
    }

    @Test
    @DisplayName("DEF-06, through the agent: queueing an unplanned train throws from inside the heap")
    void queueing_an_unplanned_train_throws_from_inside_the_heap() {
        RecordingRoad road = road(new VirtualClock());
        road.addToPlan("vl0", 1000);
        road.addToPlan("planned", 50000);
        enter(road, "vl0", LEFT);
        enter(road, "planned", LEFT);      // heap of one: no comparison yet
        road.sent.clear();

        // 'ghost' never got a VOTE_RESULT, so RoadSchedule.plannedTime returns null and
        // RoadQueueItem.diff unboxes it inside PriorityQueue's sift. Pinned, NOT guarded:
        // defect-triage.md 3.3 rules "do not file, and do not fix", naming this ticket.
        assertThrows(NullPointerException.class, () -> enter(road, "ghost", LEFT));
        // And it escapes mid-handler, so the state line for that ENTER is never emitted --
        // which is the observable shape of "the heap is left undefined".
        assertTrue(road.sent.isEmpty(), "the NPE escapes before sendState");
    }

    @Test
    @DisplayName("the comparison instant is read when the heap operation runs, and cancels out of the order")
    void the_comparison_instant_does_not_change_the_order() {
        // Same three trains, same plan, two very different clock histories: the 2008 bracket
        // froze the clock across each heap operation, #28 fixes one value per operation, and
        // (plan(a)-t)-(plan(b)-t) == plan(a)-plan(b) regardless. So the order must be identical.
        assertEquals("early", admissionOrderWithClockSteps(0L));
        assertEquals("early", admissionOrderWithClockSteps(37_000L));
    }

    private static String admissionOrderWithClockSteps(long stepMs) {
        VirtualClock clock = new VirtualClock();
        RecordingRoad road = road(clock);
        road.addToPlan("vl0", 1000);
        road.addToPlan("late", 90000);
        road.addToPlan("early", 20000);
        enter(road, "vl0", LEFT);
        clock.advanceSimBy(stepMs);
        enter(road, "late", LEFT);
        clock.advanceSimBy(stepMs);
        enter(road, "early", LEFT);
        clock.advanceSimBy(stepMs);
        road.sent.clear();
        leave(road, "vl0");
        return road.sent.get(0).receiver();
    }

    // ---------------------------------------------------------------------------------------
    // The travel timer, through AgentClock
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("the travel timer fires exactly once, at the drawn delay, and not before")
    void the_travel_timer_fires_exactly_once_at_its_deadline() {
        VirtualClock clock = new VirtualClock();
        RecordingRoad road = road(clock);
        enter(road, "vl0", LEFT);
        road.sent.clear();

        travelStart(road, "vl0");
        long delay = firstDelayOf(ROAD);
        assertTrue(delay > 0, "this seed's first tr1 draw must be positive for this test to mean"
                + " anything; the negative case is covered separately");
        assertEquals(1, road.agentClock().pending());
        assertTrue(road.sent.isEmpty(), "arming sends nothing");

        clock.advanceSimTo(delay - 1);
        assertEquals(0, road.agentClock().runDue(), "not due yet");
        assertTrue(road.sent.isEmpty());

        clock.advanceSimTo(delay);
        assertEquals(1, road.agentClock().runDue());
        assertEquals(List.of(Channel.TRAVEL_END), road.channels());
        TravelEnd end = (TravelEnd) road.sent.get(0).message();
        assertEquals(ROAD, end.road());
        assertEquals("vl0", road.sent.get(0).receiver());

        clock.advanceSimBy(600000);
        assertEquals(0, road.agentClock().runDue(), "a one-shot wake-up, not a ticker");
        assertEquals(1, road.sent.size());
    }

    @Test
    @DisplayName("DEF-16: a negative travel delay fires at the very next drain, unclamped")
    void a_negative_travel_delay_fires_immediately() {
        // Find the first draw on tr1's own stream that TravelDelay turns negative. 2.2645 % of
        // draws on a 1 s track, so this is a handful of iterations; the search keeps the test
        // independent of which master seed the JVM resolved.
        Random oracle = SimRandom.forAgent(ScenarioConfig.get().getMasterSeed(), ROAD);
        int index = -1;
        long negative = 0;
        for (int i = 0; i < 20000 && index < 0; i++) {
            long d = TravelDelay.travelMs(BASE_MS, oracle.nextGaussian());
            if (d < 0) {
                index = i;
                negative = d;
            }
        }
        assertTrue(index >= 0, "tr1's stream must contain a negative draw within 20000");
        final long negativeDelay = negative;

        VirtualClock clock = new VirtualClock();
        RecordingRoad road = road(clock);
        enter(road, "vl0", LEFT);
        // Burn the draws before it, letting each one fire so nothing is left armed.
        for (int i = 0; i < index; i++) {
            road.sent.clear();
            travelStart(road, "vl0");
            clock.advanceSimBy(BASE_MS + 4 * TravelDelay.JITTER_MS);
            road.agentClock().runDue();
        }
        road.sent.clear();

        travelStart(road, "vl0");
        assertEquals(1, road.agentClock().pending());
        // No advance at all: the deadline is already in the past, and AgentClock fires an
        // instant already past rather than dropping it -- which is what Cybele does with a
        // negative delay (SEM-06) and what DEF-16 depends on. Do not clamp to 0.
        assertEquals(1, road.agentClock().runDue(),
                () -> "a delay of " + negativeDelay + " ms must fire at the next drain");
        assertEquals(List.of(Channel.TRAVEL_END), road.channels());
    }

    @Test
    @DisplayName("DEF-14: traveledTrain is one slot, and travelEnd notifies whatever is in it")
    void the_travelled_train_is_a_single_slot() {
        VirtualClock clock = new VirtualClock();
        RecordingRoad road = road(clock);
        enter(road, "vl0", LEFT);
        travelStart(road, "vl0");
        travelStart(road, "vl1");          // overwrites the slot; safe only because a track
        road.sent.clear();                 // holds one train (defect-triage.md DEF-14)

        clock.advanceSimBy(600000);
        road.agentClock().runDue();

        // Deterministically TWO: two travelStart calls arm two independent wake-ups, nothing
        // cancels the first, and both are due after the advance. Asserting ">= 1" would pass for
        // a port that cancelled the pending timer on re-arm -- which is precisely the tidy-up
        // DEF-14 invites, and precisely what must not be done.
        assertEquals(2, road.sent.size(), "two arms, two wake-ups; re-arming must not cancel");
        for (Sent s : road.sent) {
            assertEquals("vl1", s.receiver(), "both wake-ups read the one slot");
        }
    }

    // ---------------------------------------------------------------------------------------
    // The seeded stream (#15)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("the travel jitter is drawn from the stream keyed by THIS agent's name")
    void the_jitter_comes_from_the_name_keyed_stream() {
        VirtualClock clock = new VirtualClock();
        RecordingRoad road = road(clock);
        enter(road, "vl0", LEFT);
        Random oracle = SimRandom.forAgent(ScenarioConfig.get().getMasterSeed(), ROAD);

        for (int i = 0; i < 12; i++) {
            long expected = TravelDelay.travelMs(BASE_MS, oracle.nextGaussian());
            long before = clock.nowMs();
            travelStart(road, "vl0");
            road.sent.clear();
            // The wake-up is armed at now + delay; walking the clock to exactly that instant
            // and finding it due is the assertion that the drawn value is the one used.
            clock.advanceSimTo(Math.max(clock.nowMs(), before + expected));
            assertEquals(1, road.agentClock().runDue(),
                    "draw " + i + " must arm the deadline TravelDelay computes from it");
            assertEquals(List.of(Channel.TRAVEL_END), road.channels());
            clock.advanceSimBy(BASE_MS + 4 * TravelDelay.JITTER_MS);
        }
    }

    @Test
    @DisplayName("two roads draw different sequences, keyed by name and not by creation order")
    void the_stream_key_is_the_agent_name() {
        VirtualClock clock = new VirtualClock();
        RecordingRoad one = road(clock, "tr1");
        RecordingRoad two = road(clock, "tr2");

        List<Long> firstOfOne = drawnDelays(one, clock, 5);
        List<Long> firstOfTwo = drawnDelays(two, clock, 5);
        assertNotEquals(firstOfOne, firstOfTwo, "tr1 and tr2 are different streams");

        // Create them in the OPPOSITE order and each still gets its own name's sequence, which
        // is the property #15 bought and that JADE's own creation order would otherwise break.
        VirtualClock again = new VirtualClock();
        RecordingRoad twoFirst = road(again, "tr2");
        RecordingRoad oneSecond = road(again, "tr1");
        assertEquals(firstOfTwo, drawnDelays(twoFirst, again, 5));
        assertEquals(firstOfOne, drawnDelays(oneSecond, again, 5));
    }

    private static List<Long> drawnDelays(RecordingRoad road, VirtualClock clock, int count) {
        enter(road, "vl0", LEFT);
        List<Long> delays = new ArrayList<Long>();
        for (int i = 0; i < count; i++) {
            long before = clock.nowMs();
            travelStart(road, "vl0");
            Long due = road.agentClock().nextDueMs();
            delays.add(Long.valueOf(due.longValue() - before));
            clock.advanceSimBy(BASE_MS + 4 * TravelDelay.JITTER_MS);
            road.agentClock().runDue();
        }
        return delays;
    }

    private static long firstDelayOf(String name) {
        Random oracle = SimRandom.forAgent(ScenarioConfig.get().getMasterSeed(), name);
        return TravelDelay.travelMs(BASE_MS, oracle.nextGaussian());
    }

    // ---------------------------------------------------------------------------------------
    // The voting side, inherited from StaticRailwayObject and now inlined
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a vote request answers Main with this road's name, the train and the difference")
    void the_vote_names_the_voter_the_train_and_the_difference() {
        RecordingRoad road = road(new VirtualClock());
        road.sent.clear();

        road.dispatch(Messages.build(new VoteRequest("vl0", 10000), RailwayMainAgent.MAIN_AGENT_NAME, ROAD));

        assertEquals(List.of(Channel.VOTE), road.channels());
        Vote vote = (Vote) road.sent.get(0).message();
        assertEquals(ROAD, vote.voter());
        assertEquals("vl0", vote.train());
        assertEquals(0, vote.diff(), "an empty timetable does not object");
        assertEquals(RailwayMainAgent.MAIN_AGENT_NAME, road.sent.get(0).receiver());
    }

    @Test
    @DisplayName("a vote result books the slot and the next vote in that window is pushed past it")
    void a_vote_result_books_the_slot() {
        RecordingRoad road = road(new VirtualClock());
        road.dispatch(Messages.build(new VoteResult("vl0", 10000), RailwayMainAgent.MAIN_AGENT_NAME, ROAD));
        road.sent.clear();

        road.dispatch(Messages.build(new VoteRequest("vl1", 10500), RailwayMainAgent.MAIN_AGENT_NAME, ROAD));

        Vote vote = (Vote) road.sent.get(0).message();
        // RoadSchedule: something is planned inside [t-delay, t+delay), so vote for one full
        // travel time after the LAST slot in the whole timetable.
        assertEquals(10000 + BASE_MS - 10500, vote.diff());
    }

    // ---------------------------------------------------------------------------------------
    // setup() argument validation
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a null neighbour is refused at setup rather than misrouting every train later")
    void a_null_neighbour_is_refused() {
        RecordingRoad road = new RecordingRoad();
        JadeAgentFixture.name(road, ROAD);
        road.setArguments(new Object[]{Long.valueOf(1L), null, RIGHT, new VirtualClock()});

        assertThrows(IllegalArgumentException.class, road::setup);
    }

    @Test
    @DisplayName("the shared SimClock is a required argument, not an optional one")
    void the_clock_is_required() {
        RecordingRoad road = new RecordingRoad();
        JadeAgentFixture.name(road, ROAD);
        road.setArguments(new Object[]{Long.valueOf(1L), LEFT, RIGHT});
        assertThrows(IllegalArgumentException.class, road::setup);

        RecordingRoad wrongType = new RecordingRoad();
        JadeAgentFixture.name(wrongType, ROAD);
        wrongType.setArguments(new Object[]{Long.valueOf(1L), LEFT, RIGHT, "not a clock"});
        assertThrows(IllegalArgumentException.class, wrongType::setup);
    }

    @Test
    @DisplayName("the agent clock is built over the SHARED clock it was handed, not a private one")
    void the_agent_clock_wraps_the_shared_clock() {
        VirtualClock clock = new VirtualClock(4200, 1.0);
        RecordingRoad road = road(clock);

        assertSame(clock, road.agentClock().clock());
        assertEquals(4200, road.agentClock().now());
    }

    // ---------------------------------------------------------------------------------------
    // The ticker granularity (review FIX 1)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("the ticker period is a SIMULATED-ms budget divided by the pace, not a fixed real period")
    void the_granularity_is_derived_from_the_pace() {
        // The quantity a golden is sensitive to is the normalizer's 220 simulated-ms
        // segmentation boundary; opencybele-capacity.yaml measures the tightest surviving
        // margin at 44 ms. Worst-case ticker lateness is period x pace SIMULATED ms, so the
        // period has to shrink as the pace grows or the budget is silently multiplied.
        assertEquals(1, RoadAgent.granularityMsFor(8.0), "the Fast preset");
        assertEquals(5, RoadAgent.granularityMsFor(1.0), "the Normal preset");
        assertEquals(17, RoadAgent.granularityMsFor(0.3), "the Slow preset");

        for (double pace : new double[]{0.3, 1.0, 4.0, 8.0}) {
            long lateSimMs = Math.round(RoadAgent.granularityMsFor(pace) * pace);
            assertTrue(lateSimMs <= 8,
                    () -> "pace " + pace + " must stay far inside the 44 ms tightest margin,"
                            + " was " + Math.round(RoadAgent.granularityMsFor(pace) * pace));
        }
    }

    @Test
    @DisplayName("the period floors at one real millisecond, because a JADE ticker cannot go below it")
    void the_granularity_floors_at_one_real_millisecond() {
        assertEquals(1, RoadAgent.granularityMsFor(5.0));
        assertEquals(1, RoadAgent.granularityMsFor(1000.0),
                "above the budget the period cannot shrink further; the worst case becomes pace");
        assertThrows(IllegalArgumentException.class, () -> RoadAgent.granularityMsFor(0.0));
        assertThrows(IllegalArgumentException.class, () -> RoadAgent.granularityMsFor(-1.0));
    }

    @Test
    @DisplayName("the agent ticks at the period its own shared clock's pace implies")
    void the_agent_ticks_at_the_derived_period() {
        assertEquals(RoadAgent.granularityMsFor(8.0), road(new VirtualClock(0, 8.0)).tickPeriodMs());
        assertEquals(RoadAgent.granularityMsFor(1.0), road(new VirtualClock(0, 1.0)).tickPeriodMs());
        assertEquals(RoadAgent.granularityMsFor(0.3), road(new VirtualClock(0, 0.3)).tickPeriodMs());
    }
}
