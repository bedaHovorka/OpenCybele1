/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import java.util.ArrayList;
import java.util.List;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterReply;
import cz.vutbr.fit.ags.railway.domain.msg.EnterRequest;
import cz.vutbr.fit.ags.railway.domain.msg.PathFindReply;
import cz.vutbr.fit.ags.railway.domain.msg.PathFindRequest;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import cz.vutbr.fit.ags.railway.jade.Messages;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interaction pair 3 of 4: {@code PATH_FIND} → {@code PATH_FIND_REPLY} (#38).
 *
 * <h2>Why this pair is the one most worth a container</h2>
 *
 * It is the site of the port's largest structural change. The 2008 station resolved the onward
 * direction inside its own handler by calling {@code wait()} and letting a second Cybele activity
 * ({@code PathFinding}, ACT-04, instantiated once per station) {@code notify()} it. #30 deleted both
 * — a JADE agent runs every behaviour on one thread, so a blocking {@code action()} would stall the
 * whole agent — and replaced them with an explicit continuation parked in {@code Station.pending}.
 * <p>
 * A parked continuation is exactly the kind of thing that works in a POJO test and does not work on
 * a platform, because on a platform the resuming message arrives on the agent's own thread, through
 * the same single queue the parked {@code ENTER} came in on. If the resume were ever attempted from
 * another thread, or if {@code PATH_FIND_REPLY} fell through to the {@code Drain}, this test hangs
 * on a bounded poll and reports it; {@code StationContinuationTest} at L1 cannot.
 *
 * <h2>The assertion that carries the coalescing invariant</h2>
 *
 * "One {@code PATH_FIND} per (station, target), ever" is what the whole line-multiset argument in
 * {@code Station}'s class comment rests on — a duplicate request is a trace line the baseline never
 * wrote. The second train below asks for the same target after the cache is warm, and {@code Main}
 * is then asserted <b>silent</b> apart from the {@code STATION_INFO}: no second query-ref.
 */
class PathFindIT extends ContainerFixture {

    @Test
    @DisplayName("a cold target parks the train, the reply resumes it, and a warm target asks nobody")
    void the_path_find_round_trip_resumes_the_parked_enter() throws Exception {
        final Driver main = driver(RailwayMainAgent.MAIN_AGENT_NAME);
        final Driver vl3 = driver("vl3");
        final Driver vl4 = driver("vl4");
        final Station station = start("stA", new Station(),
                Integer.valueOf(4), new ArrayList<String>(List.of("tr1")));
        main.expect(Channel.STATION_INFO, REPLY_MS);

        // ---- cold: the ENTER parks and the station asks Main which way to send the train
        vl3.send(new EnterRequest("vl3", null, "stC"), "stA");
        final ACLMessage query = main.expect(Channel.PATH_FIND, REPLY_MS);
        assertEquals(ACLMessage.QUERY_REF, query.getPerformative(),
                "PATH_FIND's act is query-ref (docs/message-ontology.md section 3)");
        assertEquals(new PathFindRequest("stA", "stC"), Messages.contentOf(query));
        assertEquals("stA", Messages.senderName(query));

        // The continuation is parked, and the station has emitted nothing else: no ENTER_REPLY yet,
        // no STATION_INFO yet -- both are inside the continuation.
        assertEquals(List.of("stC"), new ArrayList<String>(station.pendingTargets()));
        vl3.expectSilence(SILENCE_MS);
        main.expectSilence(SILENCE_MS);

        // ---- the answer resumes it, on the station's own thread, through the same queue
        main.send(new PathFindReply("stC", "tr1"), "stA");
        final EnterReply resumed = vl3.expectContent(Channel.ENTER_REPLY, EnterReply.class, REPLY_MS);
        assertEquals("stA", resumed.object());
        assertEquals("tr1", resumed.next(), "the parked train is told the direction the reply named");
        assertEquals(new StationInfo(1, 4),
                main.expectContent(Channel.STATION_INFO, StationInfo.class, REPLY_MS));

        assertTrue(station.pendingTargets().isEmpty(), "nothing is still parked");
        assertEquals("tr1", station.getPathDirs().get("stC"), "and the answer was cached");

        // ---- warm: the same target must not produce a second query-ref
        vl4.send(new EnterRequest("vl4", null, "stC"), "stA");
        final EnterReply cached = vl4.expectContent(Channel.ENTER_REPLY, EnterReply.class, REPLY_MS);
        assertEquals("tr1", cached.next());
        assertEquals(new StationInfo(2, 4),
                main.expectContent(Channel.STATION_INFO, StationInfo.class, REPLY_MS));
        main.expectSilence(SILENCE_MS);   // and in particular: no second PATH_FIND

        assertQueueDrained(station);
    }

    @Test
    @DisplayName("two trains missing on the same target coalesce onto one query-ref and both resume")
    void a_second_miss_on_the_same_target_attaches_to_the_request_in_flight() throws Exception {
        // The 2008 station could not reach this state: its handler was blocked, so a second ENTER
        // could not start. This one can, and the invariant that replaces the block is "misses
        // coalesce per target". If they did not, the run would emit a PATH_FIND line the baseline
        // never wrote -- and the golden pins the multiset of lines in a burst.
        final Driver main = driver(RailwayMainAgent.MAIN_AGENT_NAME);
        final Driver vl3 = driver("vl3");
        final Driver vl4 = driver("vl4");
        final Station station = start("stB", new Station(),
                Integer.valueOf(4), new ArrayList<String>(List.of("tr2")));
        main.expect(Channel.STATION_INFO, REPLY_MS);

        vl3.send(new EnterRequest("vl3", null, "stH"), "stB");
        assertEquals(new PathFindRequest("stB", "stH"),
                Messages.contentOf(main.expect(Channel.PATH_FIND, REPLY_MS)));

        vl4.send(new EnterRequest("vl4", null, "stH"), "stB");
        main.expectSilence(SILENCE_MS);
        assertEquals(List.of("stH"), new ArrayList<String>(station.pendingTargets()),
                "one target in flight, not two requests for it");

        // One reply resumes both, in arrival order.
        main.send(new PathFindReply("stH", "tr2"), "stB");
        assertEquals("tr2", vl3.expectContent(Channel.ENTER_REPLY, EnterReply.class, REPLY_MS).next());
        assertEquals("tr2", vl4.expectContent(Channel.ENTER_REPLY, EnterReply.class, REPLY_MS).next());
        main.expect(Channel.STATION_INFO, REPLY_MS);
        main.expect(Channel.STATION_INFO, REPLY_MS);

        assertTrue(station.pendingTargets().isEmpty());
        assertQueueDrained(station);
    }
}
