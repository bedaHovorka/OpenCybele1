/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterReply;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.StartCommand;
import cz.vutbr.fit.ags.railway.domain.msg.TrainState;
import cz.vutbr.fit.ags.railway.domain.msg.TravelEnd;
import jade.core.JadeAgentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 for #32: the train's walk, its status payloads and its death — a real {@link Train} driven as
 * a POJO, {@code docs/TESTING.md} §4.1.
 * <p>
 * No container, no platform, no threads. The agent is named by {@link JadeAgentFixture} and its one
 * outbound seam is redirected into a list, so every assertion below is on <b>what the train tried
 * to send, in the order it tried to send it</b> — which is the only thing about a train that a
 * golden records.
 * <p>
 * <b>Where the platform stops and this test starts.</b> {@link Train#die()} is overridden here to
 * count the call instead of calling {@code doDelete()}, because {@code doDelete()} needs a
 * container. The link {@code doDelete()} → {@code takeDown()} is <em>JADE's</em>, not this port's,
 * and it is proved by #26's smoke test rather than re-asserted here; what this test owns is the two
 * halves either side of it — that the arrival branch calls {@link Train#die()} exactly once and
 * enters nothing, and that {@link Train#takeDown()} emits {@code LEAVE} then {@code KILL}. The one
 * test that spans the join, {@link #a_full_route_releases_every_object_exactly_once()}, performs
 * the link explicitly and says so.
 * <p>
 * What each group is holding down:
 * <ul>
 *   <li><b>DEF-07</b> — {@code leaveObject} is sent on <em>entering the next</em> object, before
 *       {@code position} is updated, so the {@code LEAVE} names the object being departed. The
 *       golden can only see this on a queued hop ({@code opencybele-congestion}, #72); here it is
 *       pinned directly at the emission, where it is unambiguous.</li>
 *   <li><b>DEF-08, de-claimed</b> — the destructor's {@code LEAVE} <em>balances</em> the
 *       destination's {@code occupied++}; the conservation law is "every visited object released
 *       exactly once", and a port that "fixed" the imagined double-decrement would break it.</li>
 *   <li><b>DEF-11</b> — the sentinel is selected by value. The trace cannot tell {@code ==} from
 *       {@code .equals} (which is why the port was allowed to drop the idiom), so the assertion is
 *       made through {@code sendStatusMessage} with a string that is equal to {@code KILL} without
 *       being that literal.</li>
 *   <li><b>the status payloads</b>, byte for byte, because {@code trace-normalizer.md}
 *       deliberately does not project {@code TRAIN_STATE.state}.</li>
 *   <li><b>the arrival branch</b>, which is the one a port most easily gets wrong by entering
 *       something instead of dying.</li>
 * </ul>
 */
class TrainWalkTest {

    private static final String TRAIN = "vl0";
    private static final String FROM = "stA";
    private static final String TO = "stB";
    private static final String TRACK = "tr1";

    /**
     * A {@link Train} whose outbound seam is a list and whose death is a counter.
     */
    private static final class RecordingTrain extends Train {
        private static final long serialVersionUID = 1L;
        private final transient List<RailwayMessage> sent = new ArrayList<RailwayMessage>();
        private final transient List<String> to = new ArrayList<String>();
        private transient int deaths;

        @Override
        protected void emit(RailwayMessage message, String receiver) {
            sent.add(message);
            to.add(receiver);
        }

        @Override
        protected void die() {
            deaths++;
        }

        /** Every message so far as {@code EVENT|receiver|payload} — the fields a golden holds. */
        List<String> wire() {
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < sent.size(); i++) {
                out.add(sent.get(i).channel().event() + "|" + to.get(i) + "|" + sent.get(i).payload());
            }
            return out;
        }

        void clear() {
            sent.clear();
            to.clear();
        }
    }

    /** A freshly created train, {@code stA -> stB}, with its {@code generated} line consumed. */
    private static RecordingTrain train() {
        RecordingTrain train = born();
        train.clear();
        return train;
    }

    /** A freshly created train with its {@code generated} line still in the list. */
    private static RecordingTrain born() {
        RecordingTrain train = new RecordingTrain();
        JadeAgentFixture.name(train, TRAIN);
        train.setArguments(new Object[]{FROM, TO});
        train.setup();
        return train;
    }

    /** A train that has been told to start and admitted to its origin station. */
    private static RecordingTrain atOrigin() {
        RecordingTrain train = train();
        train.start(new StartCommand(FROM));
        train.entered(new EnterReply(FROM, TRACK));
        train.clear();
        return train;
    }

    /** A train that has been admitted to the track and is crossing it. */
    private static RecordingTrain onTrack() {
        RecordingTrain train = atOrigin();
        train.entered(new EnterReply(TRACK, TO));
        train.clear();
        return train;
    }

    // ------------------------------------------------------------------ birth

    @Test
    @DisplayName("a new train announces itself, and that is the only thing it does")
    void a_new_train_announces_itself_and_nothing_else() {
        RecordingTrain train = born();

        assertEquals(List.of("TRAIN_STATE|Main|state=stA -> stB : vl0 generated"), train.wire(),
                "trace-format.md: the FIRST TRAIN_STATE of a train must be its generated line, and"
                        + " the string is compared character for character");
        assertNull(train.position(), "a train has entered nothing yet");
        assertNull(train.nextPosition());
        assertTrue(!train.hasStarted());
    }

    @Test
    @DisplayName("a train with no START never proceeds — DEF-02's outcome, reproduced not simulated")
    void a_train_that_is_never_started_stays_put() {
        RecordingTrain train = train();

        // Nothing arrives. In the container Inbox.block()s on an empty queue and the agent idles
        // forever, exactly as the 2008 train sat with three open channels and nothing on them.
        assertEquals(List.of(), train.wire());
        assertTrue(!train.hasStarted());
        assertNull(train.position());
    }

    @Test
    @DisplayName("a train without both endpoints is refused at setup rather than tracing nulls")
    void the_endpoints_are_checked_at_setup() {
        assertThrows(IllegalArgumentException.class, () -> setupWith((Object[]) null));
        assertThrows(IllegalArgumentException.class, () -> setupWith(new Object[]{FROM}));
        assertThrows(IllegalArgumentException.class, () -> setupWith(new Object[]{FROM, null}));
        assertThrows(IllegalArgumentException.class, () -> setupWith(new Object[]{FROM, ""}));
        assertThrows(IllegalArgumentException.class, () -> setupWith(new Object[]{null, TO}));
    }

    private static void setupWith(Object[] args) {
        RecordingTrain train = new RecordingTrain();
        JadeAgentFixture.name(train, TRAIN);
        train.setArguments(args);
        train.setup();
    }

    // ------------------------------------------------------------------ start

    @Test
    @DisplayName("START enters the origin, and that first ENTER carries position=null")
    void start_enters_the_origin_with_a_null_position() {
        RecordingTrain train = train();

        String out = capturingStdout(() -> train.start(new StartCommand(FROM)));

        assertEquals(List.of("ENTER|stA|train=vl0,position=null,target=stB"), train.wire(),
                "message-ontology.md 5.4: position is null on a train's very first ENTER, and that"
                        + " is correct rather than a fault");
        assertTrue(train.hasStarted());
        assertEquals("vl0 started" + System.lineSeparator(), out,
                "the println is trace-visible — every golden carries a '<train> started' stream");
    }

    @Test
    @DisplayName("the ENTER a train sends names its final destination, not its next hop")
    void every_enter_carries_the_final_destination() {
        RecordingTrain train = onTrack();

        train.travelEnd(new TravelEnd(TRACK));

        assertEquals(List.of("ENTER|stB|train=vl0,position=tr1,target=stB"), train.wire(),
                "message-ontology.md 5.2: one shape goes to both receivers — a station reads"
                        + " target, a track reads position — so both slots are contractual");
    }

    // ------------------------------------------------------------------ DEF-07

    @Test
    @DisplayName("DEF-07: the LEAVE names the object being DEPARTED, sent before position moves")
    void the_leave_names_the_old_object_and_goes_out_first() {
        RecordingTrain train = atOrigin();

        train.entered(new EnterReply(TRACK, TO));

        // The whole hop, in emission order. LEAVE is first and it names stA -- the object the
        // train is leaving -- although by the end of the handler the train is in tr1.
        assertEquals(List.of(
                "LEAVE|stA|train=vl0",
                "TRAIN_STATE|Main|state=stA -> stB : entered to tr1",
                "TRAVEL_START|tr1|train=vl0"), train.wire());
        assertEquals(TRACK, train.position(),
                "the train IS in tr1 by now — which is exactly why the LEAVE above had to be sent"
                        + " before the assignment, and why a port that hoists the assignment"
                        + " releases the object the train just entered");
    }

    @Test
    @DisplayName("DEF-07's first-hop exception: a train that has entered nothing releases nothing")
    void the_first_hop_releases_nothing() {
        RecordingTrain train = train();
        train.start(new StartCommand(FROM));
        train.clear();

        train.entered(new EnterReply(FROM, TRACK));

        assertEquals(List.of(
                "TRAIN_STATE|Main|state=stA -> stB : entered to stA",
                "ENTER|tr1|train=vl0,position=stA,target=stB"), train.wire(),
                "no LEAVE: leaveObject is a no-op while position is null, which is why a route of"
                        + " n objects produces n LEAVEs and not n+1 (defect-triage.md 3.1)");
    }

    // ------------------------------------------------------------------ the track

    @Test
    @DisplayName("entering a track boards it and then waits — no ENTER until TRAVEL_END")
    void a_track_is_boarded_and_then_waited_out() {
        RecordingTrain train = atOrigin();

        train.entered(new EnterReply(TRACK, TO));
        assertEquals("TRAVEL_START|tr1|train=vl0", last(train.wire()),
                "a track is told to start the traversal, not asked to be entered again");
        train.clear();

        train.travelEnd(new TravelEnd(TRACK));
        assertEquals(List.of("ENTER|stB|train=vl0,position=tr1,target=stB"), train.wire());
    }

    @Test
    @DisplayName("TRAVEL_END with nowhere to go emits nothing rather than entering null")
    void travel_end_without_a_next_position_does_nothing() {
        RecordingTrain train = train();
        train.start(new StartCommand(FROM));
        train.clear();
        assertNull(train.nextPosition(), "arrange: no ENTER_REPLY has set one yet");

        train.travelEnd(new TravelEnd(TRACK));

        assertEquals(List.of(), train.wire(),
                "2008's null guard is kept: the branch it protects is DEF-01's, where a station"
                        + " hands back a null next, and removing it sends an ENTER to null");
    }

    // ------------------------------------------------------------------ arrival and death

    @Test
    @DisplayName("arrival dies instead of entering: next=null at a station is the end of the walk")
    void arriving_dies_rather_than_entering() {
        RecordingTrain train = onTrack();

        train.entered(new EnterReply(TO, null));

        assertEquals(List.of(
                "LEAVE|tr1|train=vl0",
                "TRAIN_STATE|Main|state=stA -> stB : entered to stB"), train.wire(),
                "the handler releases the track and reports the arrival, and sends NOTHING else —"
                        + " an ENTER here would be a train that never stops");
        assertEquals(1, train.deaths, "message-ontology.md 5.4: a null next is how a train is told"
                + " it has arrived, and it calls die()");
        assertEquals(TO, train.position());
        assertNull(train.nextPosition());
    }

    @Test
    @DisplayName("a mid-route station does NOT die — the same handler, the other branch")
    void a_station_with_a_next_hop_does_not_die() {
        RecordingTrain train = onTrack();

        train.entered(new EnterReply("stC", "tr2"));

        assertEquals(0, train.deaths);
        assertEquals("ENTER|tr2|train=vl0,position=stC,target=stB", last(train.wire()));
    }

    @Test
    @DisplayName("the destructor sends LEAVE for the destination and THEN KILL")
    void the_destructor_sends_leave_then_kill() {
        RecordingTrain train = onTrack();
        train.entered(new EnterReply(TO, null));
        train.clear();

        train.takeDown();

        assertEquals(List.of(
                "LEAVE|stB|train=vl0",
                "TRAIN_STATE|Main|state=KILL"), train.wire(),
                "defect-triage.md: a completed train must emit a final LEAVE to its destination"
                        + " IMMEDIATELY BEFORE state=KILL; its absence is a port bug, and so is"
                        + " the reverse order");
    }

    @Test
    @DisplayName("DEF-08 de-claimed: every visited object is released exactly once, destination included")
    void a_full_route_releases_every_object_exactly_once() {
        RecordingTrain train = born();

        // stA -> tr1 -> stB, the whole of opencybele-lifecycle's vl0.
        train.start(new StartCommand(FROM));
        train.entered(new EnterReply(FROM, TRACK));
        train.entered(new EnterReply(TRACK, TO));
        train.travelEnd(new TravelEnd(TRACK));
        train.entered(new EnterReply(TO, null));
        assertEquals(1, train.deaths);
        // The platform's own link, performed here: doDelete() -> takeDown(). See the class comment.
        train.takeDown();

        Map<String, Integer> released = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < train.sent.size(); i++) {
            if (train.sent.get(i).channel() == Channel.LEAVE) {
                released.merge(train.to.get(i), Integer.valueOf(1), (a, b) -> Integer.valueOf(a.intValue() + 1));
            }
        }

        assertEquals(Map.of(FROM, Integer.valueOf(1), TRACK, Integer.valueOf(1), TO, Integer.valueOf(1)),
                released,
                "defect-triage.md 4.2 measured 199 LEAVE records and zero duplicate (train, object)"
                        + " pairs. The destructor's LEAVE is not a second one for the destination —"
                        + " it is the ONLY one, and it balances Station.enter's occupied++. A port"
                        + " that 'fixes' DEF-08 by deleting it leaks occupancy on every arrival.");
        assertEquals(3, released.size(), "route of three objects, three LEAVEs — not four");
    }

    // ------------------------------------------------------------------ status payloads

    @Test
    @DisplayName("the three status payloads are byte-identical to the baseline's")
    void the_status_payloads_are_byte_identical() {
        RecordingTrain train = born();
        train.start(new StartCommand(FROM));
        train.entered(new EnterReply(FROM, TRACK));
        train.entered(new EnterReply(TRACK, TO));
        train.travelEnd(new TravelEnd(TRACK));
        train.entered(new EnterReply(TO, null));
        train.takeDown();

        List<String> states = new ArrayList<String>();
        for (RailwayMessage message : train.sent) {
            if (message instanceof TrainState state) {
                states.add(state.state());
            }
        }

        assertEquals(List.of(
                "stA -> stB : vl0 generated",
                "stA -> stB : entered to stA",
                "stA -> stB : entered to tr1",
                "stA -> stB : entered to stB",
                "KILL"), states,
                "message-ontology.md 5.7 keeps TRAIN_STATE an opaque string because it is in the"
                        + " trace character for character, spaces and ':' included, and"
                        + " trace-normalizer.md deliberately does not project it");
    }

    @Test
    @DisplayName("DEF-11: the sentinel is selected BY VALUE — a non-interned \"KILL\" is still bare")
    void the_kill_sentinel_is_selected_by_value() {
        RecordingTrain train = train();

        train.sendStatusMessage(new String("KILL"));

        assertEquals(List.of("TRAIN_STATE|Main|state=KILL"), train.wire(),
                "2008 wrote (mess == KILLED) and defect-triage.md grants #32 the carve-out to drop"
                        + " the idiom because no caller can tell the difference. This is the one"
                        + " caller that can: with == restored, the line would read"
                        + " 'state=stA -> stB : KILL'.");
    }

    @Test
    @DisplayName("every non-sentinel status is prefixed — one near-miss per weaker predicate")
    void a_non_sentinel_status_is_prefixed() {
        RecordingTrain train = train();

        train.sendStatusMessage("KILLED");   // startsWith would fold this into the sentinel
        train.sendStatusMessage("NOT KILL"); // endsWith would
        train.sendStatusMessage("kill");     // equalsIgnoreCase would
        train.sendStatusMessage("");         // isEmpty, or a contains() written the wrong way, would

        assertEquals(List.of(
                "TRAIN_STATE|Main|state=stA -> stB : KILLED",
                "TRAIN_STATE|Main|state=stA -> stB : NOT KILL",
                "TRAIN_STATE|Main|state=stA -> stB : kill",
                "TRAIN_STATE|Main|state=stA -> stB : "), train.wire(),
                "the sentinel test is equality with \"KILL\" and nothing weaker. Each argument"
                        + " above is chosen so that exactly one plausible substitute would fold it"
                        + " into the bare sentinel — and folding a status line into KILL makes the"
                        + " hub drop the train's row while the train is still running.");
    }

    private static String last(List<String> lines) {
        return lines.get(lines.size() - 1);
    }

    private static String capturingStdout(Runnable body) {
        PrintStream out = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            body.run();
        } finally {
            System.setOut(out);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
