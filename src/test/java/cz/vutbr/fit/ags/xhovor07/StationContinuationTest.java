/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterReply;
import cz.vutbr.fit.ags.railway.domain.msg.EnterRequest;
import cz.vutbr.fit.ags.railway.domain.msg.LeaveNotice;
import cz.vutbr.fit.ags.railway.domain.msg.PathFindReply;
import cz.vutbr.fit.ags.railway.domain.msg.PathFindRequest;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import cz.vutbr.fit.ags.railway.domain.msg.Vote;
import cz.vutbr.fit.ags.railway.domain.msg.VoteRequest;
import cz.vutbr.fit.ags.railway.domain.msg.VoteResult;
import cz.vutbr.fit.ags.railway.jade.Messages;
import jade.core.JadeAgentFixture;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 for #30's async continuation — the construct that replaced {@code Station.getPathDirection}'s
 * blocking {@code wait()} and the {@code PathFinding} activity that woke it.
 * <p>
 * Behaviours-as-POJOs, {@code docs/TESTING.md} §4.1: no container, no platform, no threads. The
 * station is a real {@link Station}, named by {@link JadeAgentFixture}, with its one outbound seam
 * overridden so every message it tries to send is recorded instead. Inbound messages are real
 * {@code ACLMessage}s built by {@link Messages} and either handed straight to
 * {@link Station#dispatch} or posted into the agent's queue and pulled out by its own
 * {@code Inbox} behaviour.
 * <p>
 * What each group of tests is holding down:
 * <ul>
 *   <li>the cache-hit path is still <em>synchronous</em>, so the warm run emits what 2008 emitted,
 *       in 2008's order;</li>
 *   <li>a miss suspends <em>that train</em> and nothing else — the SEM-04 station-wide stall is
 *       gone on purpose, and this suite says so out loud rather than leaving it to a golden;</li>
 *   <li>a reply resumes each parked continuation exactly once, an unmatched reply resumes none,
 *       and two targets pending at once resolve independently;</li>
 *   <li>misses on one target coalesce into a single {@code PATH_FIND}, because the golden pins the
 *       multiset of lines in a burst ({@code docs/trace-normalizer.md} §3);</li>
 *   <li>DEF-23 keeps its untimed half: nothing ever resumes a continuation with a made-up answer.</li>
 * </ul>
 */
class StationContinuationTest {

    private static final long WINDOW = 8500L;

    /** One message the station tried to send. */
    private record Sent(RailwayMessage message, String receiver) {
        Channel channel() {
            return message.channel();
        }
    }

    /** A real {@link Station} with its outbound seam redirected into a list. */
    private static final class RecordingStation extends Station {
        private static final long serialVersionUID = 1L;
        private final transient List<Sent> sent = new ArrayList<Sent>();

        @Override
        protected void emit(RailwayMessage message, String receiver) {
            sent.add(new Sent(message, receiver));
        }
    }

    private static RecordingStation station(String name, int capacity) {
        RecordingStation station = new RecordingStation();
        JadeAgentFixture.name(station, name);
        station.setArguments(new Object[]{Integer.valueOf(capacity), List.of("tr1", "tr2")});
        station.setup();
        // setup()'s last act is the opening STATION_INFO. Drop it so each test starts from zero.
        assertEquals(1, station.sent.size(), "setup must emit exactly one opening STATION_INFO");
        assertEquals(Channel.STATION_INFO, station.sent.get(0).channel());
        station.sent.clear();
        return station;
    }

    private static ACLMessage inbound(RailwayMessage message, String from, String to) {
        return Messages.build(message, from, to);
    }

    private static void deliver(Station station, RailwayMessage message, String from) {
        station.dispatch(inbound(message, from, station.getLocalName()));
    }

    private static List<Sent> on(RecordingStation station, Channel channel) {
        List<Sent> out = new ArrayList<Sent>();
        for (Sent s : station.sent) {
            if (s.channel() == channel) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<Channel> channels(RecordingStation station) {
        List<Channel> out = new ArrayList<Channel>();
        for (Sent s : station.sent) {
            out.add(s.channel());
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // The hit path: unchanged from 2008, and synchronous
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a warm cache resolves in-line: ENTER_REPLY then STATION_INFO, and no PATH_FIND")
    void a_cache_hit_resolves_in_line() {
        RecordingStation station = station("stA", 2);
        deliver(station, new PathFindReply("stC", "tr1"), "Main");
        assertEquals(List.of(), channels(station), "a reply nobody waits for sends nothing");

        deliver(station, new EnterRequest("vl0", "tr9", "stC"), "vl0");

        assertEquals(List.of(Channel.ENTER_REPLY, Channel.STATION_INFO), channels(station),
                "2008 order: sendEnterReply(...) then sendInfo()");
        assertEquals(new EnterReply("stA", "tr1"), station.sent.get(0).message());
        assertEquals("vl0", station.sent.get(0).receiver());
        assertEquals(new StationInfo(1, 2), station.sent.get(1).message());
        assertEquals("Main", station.sent.get(1).receiver());
        assertTrue(station.pendingTargets().isEmpty());
    }

    @Test
    @DisplayName("a train already at its destination resolves null with no round trip")
    void arriving_at_the_destination_needs_no_path_find() {
        RecordingStation station = station("stA", 2);

        deliver(station, new EnterRequest("vl0", "tr9", "stA"), "vl0");

        assertEquals(List.of(Channel.ENTER_REPLY, Channel.STATION_INFO), channels(station));
        assertNull(((EnterReply) station.sent.get(0).message()).next(),
                "null next= is how a train learns it has arrived (CH-06)");
        assertTrue(station.pendingTargets().isEmpty());
    }

    @Test
    @DisplayName("a full station queues the train and sends only STATION_INFO")
    void a_full_station_queues_without_resolving_anything() {
        RecordingStation station = station("stA", 1);
        deliver(station, new EnterRequest("vl0", "tr9", "stA"), "vl0");
        station.sent.clear();

        deliver(station, new EnterRequest("vl1", "tr9", "stC"), "vl1");

        assertEquals(List.of(Channel.STATION_INFO), channels(station),
                "the queue is the refusal: nothing else is sent (message-ontology.md 4.2)");
        assertEquals(new StationInfo(1, 1), station.sent.get(0).message());
        assertTrue(station.pendingTargets().isEmpty(), "a queued train resolves nothing yet");
    }

    // ---------------------------------------------------------------------------------------
    // The miss path: suspend this train, keep the station running
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a cache miss sends one PATH_FIND, emits nothing else, and parks the target")
    void a_cache_miss_suspends_the_train() {
        RecordingStation station = station("stA", 2);

        deliver(station, new EnterRequest("vl0", "tr9", "stC"), "vl0");

        assertEquals(List.of(Channel.PATH_FIND), channels(station));
        assertEquals(new PathFindRequest("stA", "stC"), station.sent.get(0).message());
        assertEquals("Main", station.sent.get(0).receiver());
        assertEquals(List.of("stC"), List.copyOf(station.pendingTargets()));
    }

    @Test
    @DisplayName("the reply resumes the parked continuation exactly once")
    void the_reply_resumes_exactly_once() {
        RecordingStation station = station("stA", 2);
        deliver(station, new EnterRequest("vl0", "tr9", "stC"), "vl0");
        station.sent.clear();

        deliver(station, new PathFindReply("stC", "tr1"), "Main");

        assertEquals(List.of(Channel.ENTER_REPLY, Channel.STATION_INFO), channels(station));
        assertEquals(new EnterReply("stA", "tr1"), station.sent.get(0).message());
        assertEquals("vl0", station.sent.get(0).receiver());
        assertEquals(new StationInfo(1, 2), station.sent.get(1).message(),
                "occupied was incremented before the round trip, exactly as in 2008");
        assertTrue(station.pendingTargets().isEmpty());

        station.sent.clear();
        deliver(station, new PathFindReply("stC", "tr1"), "Main");
        assertEquals(List.of(), channels(station), "a second copy of the same reply resumes nothing");
    }

    @Test
    @DisplayName("a reply for a target nobody is waiting on updates the cache and resumes nothing")
    void an_unmatched_reply_is_not_a_wakeup() {
        RecordingStation station = station("stA", 2);
        deliver(station, new EnterRequest("vl0", "tr9", "stC"), "vl0");
        station.sent.clear();

        deliver(station, new PathFindReply("stD", "tr7"), "Main");

        assertEquals(List.of(), channels(station),
                "DEF-01's mis-targeted wakeup: the 2008 notify() would have woken the stC waiter"
                        + " and handed it a null direction; matching by target cannot");
        assertEquals("tr7", station.getPathDirs().get("stD"), "the answer is still cached");
        assertEquals(List.of("stC"), List.copyOf(station.pendingTargets()),
                "the stC continuation is still parked");
    }

    @Test
    @DisplayName("two targets pending at once resolve independently and in reply order")
    void two_pending_targets_do_not_deadlock() {
        RecordingStation station = station("stA", 3);

        deliver(station, new EnterRequest("vl0", "tr9", "stC"), "vl0");
        deliver(station, new EnterRequest("vl1", "tr9", "stD"), "vl1");

        assertEquals(List.of(Channel.PATH_FIND, Channel.PATH_FIND), channels(station),
                "two distinct targets, two requests -- 2008 could not even reach this state");
        assertEquals(List.of("stC", "stD"), List.copyOf(station.pendingTargets()));
        station.sent.clear();

        // Answer the SECOND one first: nothing about the first may move.
        deliver(station, new PathFindReply("stD", "tr7"), "Main");
        assertEquals(List.of(Channel.ENTER_REPLY, Channel.STATION_INFO), channels(station));
        assertEquals("vl1", station.sent.get(0).receiver());
        assertEquals(new EnterReply("stA", "tr7"), station.sent.get(0).message());
        assertEquals(List.of("stC"), List.copyOf(station.pendingTargets()));
        station.sent.clear();

        deliver(station, new PathFindReply("stC", "tr1"), "Main");
        assertEquals(List.of(Channel.ENTER_REPLY, Channel.STATION_INFO), channels(station));
        assertEquals("vl0", station.sent.get(0).receiver());
        assertEquals(new EnterReply("stA", "tr1"), station.sent.get(0).message());
        assertTrue(station.pendingTargets().isEmpty());
    }

    @Test
    @DisplayName("two trains missing on the same target coalesce into one PATH_FIND")
    void a_second_miss_on_one_target_sends_no_second_request() {
        RecordingStation station = station("stA", 3);

        deliver(station, new EnterRequest("vl0", "tr9", "stC"), "vl0");
        deliver(station, new EnterRequest("vl1", "tr8", "stC"), "vl1");

        assertEquals(1, on(station, Channel.PATH_FIND).size(),
                "the 2008 station could not send a second request while blocked, so a second line"
                        + " here would be a line the golden never recorded");
        station.sent.clear();

        deliver(station, new PathFindReply("stC", "tr1"), "Main");

        assertEquals(List.of(Channel.ENTER_REPLY, Channel.STATION_INFO,
                        Channel.ENTER_REPLY, Channel.STATION_INFO), channels(station));
        assertEquals("vl0", station.sent.get(0).receiver(), "arrival order is preserved");
        assertEquals("vl1", station.sent.get(2).receiver());
        assertTrue(station.pendingTargets().isEmpty());
    }

    @Test
    @DisplayName("the station keeps answering votes while a continuation is parked (SEM-04 is gone)")
    void a_parked_continuation_does_not_starve_the_other_channels() {
        RecordingStation station = station("stA", 3);
        deliver(station, new EnterRequest("vl0", "tr9", "stC"), "vl0");
        station.sent.clear();

        deliver(station, new VoteRequest("vl9", 1000L), "Main");
        deliver(station, new VoteResult("vl9", 1000L), "Main");
        deliver(station, new EnterRequest("vl1", "tr9", "stA"), "vl1");

        assertEquals(List.of(Channel.VOTE, Channel.ENTER_REPLY, Channel.STATION_INFO),
                channels(station),
                "in Cybele all three would have queued behind the blocked handler (SEM-04)");
        assertEquals(new Vote("stA", "vl9", 0L), station.sent.get(0).message());
        assertEquals(List.of("stC"), List.copyOf(station.pendingTargets()));
    }

    // ---------------------------------------------------------------------------------------
    // leave(): the hoisted removeTrain, and the emission order
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("leave on a cache hit still emits ENTER_REPLY then STATION_INFO")
    void leave_keeps_the_2008_emission_order() {
        RecordingStation station = fullStationWithOneQueued();
        deliver(station, new PathFindReply("stC", "tr1"), "Main");
        station.sent.clear();

        deliver(station, new LeaveNotice("vl0"), "vl0");

        assertEquals(List.of(Channel.ENTER_REPLY, Channel.STATION_INFO), channels(station));
        assertEquals("vl1", station.sent.get(0).receiver(), "the queued train takes the slot");
        assertEquals(new EnterReply("stA", "tr1"), station.sent.get(0).message());
        assertEquals(new StationInfo(1, 1), station.sent.get(1).message(),
                "the slot was handed over, not released");
    }

    @Test
    @DisplayName("leave removes the train from the timetable BEFORE it suspends on a cache miss")
    void leave_hoists_remove_train_above_the_suspension() {
        RecordingStation station = fullStationWithOneQueued();
        deliver(station, new VoteResult("vl0", 1000L), "Main");
        assertEquals(2833L, station.computeDifference("probe", 1000L),
                "precondition: with vl0 still booked, a capacity-1 station cannot take another");
        station.sent.clear();

        deliver(station, new LeaveNotice("vl0"), "vl0");
        assertEquals(List.of(Channel.PATH_FIND), channels(station), "suspended on the stC miss");
        station.sent.clear();

        // The window that SEM-04 made impermeable in 2008 is permeable here. What a vote sees
        // inside it must be what it would have seen after the handler finished.
        deliver(station, new VoteRequest("vl9", 1000L), "Main");

        assertEquals(new Vote("stA", "vl9", 0L), station.sent.get(0).message(),
                "vl0's slot is already gone: 0, not 2833. Transliterating the 2008 statement"
                        + " order would have voted against a departed train.");
    }

    // ---------------------------------------------------------------------------------------
    // DEF-23: the untimed half is kept
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a lost PATH_FIND_REPLY parks the train forever and never invents an answer")
    void there_is_no_timeout_and_no_fall_through() {
        RecordingStation station = station("stA", 3);
        deliver(station, new EnterRequest("vl0", "tr9", "stC"), "vl0");
        station.sent.clear();

        for (int i = 0; i < 20; i++) {
            deliver(station, new VoteRequest("vl" + i, 1000L + i), "Main");
            deliver(station, new PathFindReply("stZ" + i, "tr" + i), "Main");
        }

        assertTrue(on(station, Channel.ENTER_REPLY).isEmpty(),
                "DEF-23 is class (c) and PINNED: no timeout branch may resume with a null"
                        + " direction, which would manufacture DEF-01 out of it");
        assertEquals(List.of("stC"), List.copyOf(station.pendingTargets()),
                "and the evidence stays visible instead of presenting as a hang");
        assertFalse(on(station, Channel.VOTE).isEmpty(), "the station itself is not stalled");
    }

    /** capacity 1, {@code vl0} admitted and at its destination, {@code vl1} queued for {@code stC}. */
    private static RecordingStation fullStationWithOneQueued() {
        RecordingStation station = station("stA", 1);
        deliver(station, new EnterRequest("vl0", "tr9", "stA"), "vl0");
        deliver(station, new EnterRequest("vl1", "tr9", "stC"), "vl1");
        station.sent.clear();
        return station;
    }
}
