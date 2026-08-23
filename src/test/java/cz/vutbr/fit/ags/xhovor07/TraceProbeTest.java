/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import cz.vutbr.fit.ags.railway.domain.clock.VirtualClock;
import cz.vutbr.fit.ags.railway.domain.msg.CanonicalMessages;
import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.PlanTrain;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import cz.vutbr.fit.ags.railway.domain.msg.TrainState;
import cz.vutbr.fit.ags.railway.jade.Messages;
import jade.core.JadeAgentFixture;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TraceProbe} at L1 — the instrument the whole of L3 reads through, and until #37 the one
 * ported class with no unit test at all.
 * <p>
 * What is worth testing here is <em>not</em> the rendering: {@code TraceLineTest} owns that, and
 * the probe deliberately renders no line of its own. What is the probe's own is everything
 * around it — which messages it will decode, where field 1 comes from, where field 2 comes from,
 * what happens when an observation throws, and the two completeness checks that exist to stop a
 * truncated run being recorded as a golden.
 * <p>
 * <b>Two pieces of global state this class has to be honest about.</b>
 * <ul>
 * <li>{@code TraceProbe.FAILURES} is a static counter with no reset, so every assertion about it
 *     is a <em>delta</em>, never an absolute.</li>
 * <li>{@code RunControl}'s clock is a first-writer-wins static that {@code RailwayMainAgent.setup}
 *     writes — so whether this class or {@code RailwayMainAgentStartupTest} installed the clock
 *     depends on test-discovery order, and a hard-coded tick makes this class pass or fail on it.
 *     (It did: the first version of this file asserted it had won the race, and on a clean build
 *     it had not.) So field 2 is not hard-coded. Instead the clock — whosever it is — is
 *     <b>paused</b> around every test and read once, which freezes simulated time without
 *     assuming anything about which clock is installed or where it starts.</li>
 * </ul>
 */
class TraceProbeTest {

    /** Simulated time, frozen for the duration of one test by {@link #freeze()}. */
    private long tick;

    @BeforeAll
    static void clock() {
        // Only if nobody has one yet: RunControl.useClock is first-writer-wins and reports a
        // second caller on stderr. This is what lets the class run on its own as well as in
        // the whole suite.
        if (RunControl.simClock() == null) {
            RunControl.useClock(new VirtualClock(7777L, 1.0), 7777L);
        }
    }

    @BeforeEach
    void freeze() {
        RunControl.simClock().pause();
        tick = RunControl.simTimeMs();
    }

    @AfterEach
    void thaw() {
        RunControl.simClock().resume();
    }

    /** The line a probe must render for {@code message}, at the instant frozen by {@link #freeze()}. */
    private String line(String subject, String event, String from, String to, String act, String payload) {
        return subject + '|' + tick + '|' + event + '|' + from + '|' + to + '|' + act + '|' + payload;
    }

    /** A probe whose output seam is a list instead of {@code System.out}. */
    private static final class RecordingProbe extends TraceProbe {
        private static final long serialVersionUID = 1L;
        private final transient List<String> lines = new ArrayList<String>();

        @Override
        protected void emit(String line) {
            lines.add(line);
        }
    }

    private static RecordingProbe probe() {
        RecordingProbe probe = new RecordingProbe();
        // setup() is deliberately NOT called: it needs a TopicManagementHelper, which needs a
        // container. Everything below is the container-free half the class was split for.
        JadeAgentFixture.name(probe, TraceProbe.PROBE_AGENT_NAME);
        return probe;
    }

    private static ACLMessage acl(RailwayMessage message, String from, String to) {
        return Messages.build(message, from, to);
    }

    // ------------------------------------------------------------------ rendering

    @Test
    @DisplayName("one observed message becomes exactly one canonical line, fields 1-7 spelled out")
    void an_observed_message_becomes_one_canonical_line() {
        RecordingProbe probe = probe();

        probe.observe(acl(new StationInfo(0, 6), "stA", "Main"));

        assertEquals(List.of(line("stA", "STATION_INFO", "stA", "Main", "INFORM",
                "occupied=0,capacity=6")), probe.lines);
    }

    @Test
    @DisplayName("every one of the fifteen channels is observable and renders one line")
    void every_channel_renders_exactly_one_line() {
        RecordingProbe probe = probe();

        CanonicalMessages.all().forEach((channel, sample) ->
                probe.observe(acl(sample.message(), sample.from(), sample.to())));

        assertEquals(Channel.values().length, probe.lines.size());
        // Field 3 is the channel event, in the order the samples were fed. A probe that dropped
        // or duplicated a channel fails on this list, not just on the count.
        List<String> events = new ArrayList<String>();
        for (String line : probe.lines) events.add(line.split("\\|", -1)[2]);
        List<String> expected = new ArrayList<String>();
        CanonicalMessages.all().keySet().forEach(c -> expected.add(c.event()));
        assertEquals(expected, events);
    }

    @Test
    @DisplayName("field 1 is derived from the payload, not read off the :conversation-id slot")
    void the_subject_is_recomputed_and_not_trusted_from_the_slot() {
        RecordingProbe probe = probe();
        ACLMessage tampered = acl(new StationInfo(1, 6), "stA", "Main");
        assertEquals("stA", tampered.getConversationId(), "the sender did set the slot");
        tampered.setConversationId("NOT-THE-SUBJECT");

        probe.observe(tampered);

        // observe()'s Javadoc calls the derivation "normative" and the slot "the fast path".
        // If it ever takes the fast path, this line begins "NOT-THE-SUBJECT|".
        assertEquals(List.of(line("stA", "STATION_INFO", "stA", "Main", "INFORM",
                "occupied=1,capacity=6")), probe.lines);
    }

    @Test
    @DisplayName("field 2 is SIMULATED time read at handling time, not the wall clock")
    void the_tick_is_simulated_time_and_not_wall_time() {
        RecordingProbe probe = probe();

        probe.observe(acl(new StationInfo(0, 6), "stA", "Main"));

        final long rendered = Long.parseLong(probe.lines.get(0).split("\\|", -1)[1]);
        assertEquals(tick, rendered, "field 2 is RunControl.simTimeMs, read when the probe handles"
                + " the message -- trace-format.md, and what makes field 2 monotonically"
                + " non-decreasing over the whole trace");
        // The clock is paused, so simulated time cannot have moved between freeze() and here.
        // The wall clock has. A probe that stamped System.currentTimeMillis() would render an
        // epoch millisecond, thirteen digits and twelve orders of magnitude away.
        assertTrue(Math.abs(System.currentTimeMillis() - rendered) > 1_000_000_000L,
                () -> "field 2 must not be wall time, but rendered " + rendered);
    }

    // ------------------------------------------------------------------ failure

    @Test
    @DisplayName("an observation that throws is reported, counted, and does not stop the probe")
    void a_failed_observation_is_reported_and_the_probe_carries_on() {
        RecordingProbe probe = probe();
        // An ACL that matches the template (right ontology, right performative) but whose
        // content slot was never filled: contentOf() throws inside observe().
        ACLMessage broken = acl(new StationInfo(0, 6), "stA", "Main");
        broken.setContent(null);
        broken.setByteSequenceContent(null);

        int before = TraceProbe.probeFailures();
        String report = capturingErr(() -> probe.observe(broken));
        int after = TraceProbe.probeFailures();

        assertEquals(before + 1, after, "exactly one failure counted");
        assertTrue(probe.lines.isEmpty(), "a half-decoded message must not become a trace line");
        assertTrue(report.contains("!!! PROBE FAILURE"),
                () -> "the harness scans for this exact prefix: " + report);
        assertTrue(report.contains("Exception in thread \""),
                () -> "and for the JVM's uncaught-exception header, so the run cannot be"
                        + " recorded as a golden: " + report);

        // "The probe keeps running afterwards" -- the next message still renders.
        probe.observe(acl(new StationInfo(2, 6), "stA", "Main"));
        assertEquals(List.of(line("stA", "STATION_INFO", "stA", "Main", "INFORM",
                "occupied=2,capacity=6")), probe.lines);
        assertEquals(after, TraceProbe.probeFailures(), "and the good one counts nothing");
    }

    // ------------------------------------------------------------------ completeness

    @Test
    @DisplayName("a train whose first TRAIN_STATE is its 'generated' line is not a gap")
    void the_generated_line_opens_a_train_cleanly() {
        RecordingProbe probe = probe();

        int before = TraceProbe.probeFailures();
        probe.observe(acl(new TrainState("stA -> stB : vl3 generated"), "vl3", "Main"));

        assertEquals(before, TraceProbe.probeFailures());
        assertEquals(1, probe.lines.size());
    }

    @Test
    @DisplayName("a first TRAIN_STATE that is the KILL sentinel is a gap: the train's whole story is missing")
    void a_first_state_of_KILL_is_reported_as_a_gap() {
        RecordingProbe probe = probe();

        int before = TraceProbe.probeFailures();
        String report = capturingErr(() ->
                probe.observe(acl(new TrainState(TrainState.KILLED), "vl3", "Main")));

        assertEquals(before + 1, TraceProbe.probeFailures());
        assertTrue(report.contains("TRACE GAP"), () -> report);
        assertTrue(report.contains("KILL sentinel"), () -> report);
        assertEquals(1, probe.lines.size(), "the line is still emitted; the gap is separate");
    }

    @Test
    @DisplayName("a first TRAIN_STATE that is neither KILL nor 'generated' is the barrier failing")
    void a_first_state_mid_journey_is_reported_as_a_gap() {
        RecordingProbe probe = probe();

        int before = TraceProbe.probeFailures();
        String report = capturingErr(() ->
                probe.observe(acl(new TrainState("stA -> stB : entered to tr1"), "vl3", "Main")));

        assertEquals(before + 1, TraceProbe.probeFailures());
        assertTrue(report.contains("registration barrier"), () -> report);
    }

    @Test
    @DisplayName("only the FIRST state of a train is checked -- a later non-generated line is fine")
    void the_check_is_on_the_first_state_only() {
        RecordingProbe probe = probe();
        probe.observe(acl(new TrainState("stA -> stB : vl3 generated"), "vl3", "Main"));

        int before = TraceProbe.probeFailures();
        probe.observe(acl(new TrainState("stA -> stB : entered to tr1"), "vl3", "Main"));
        probe.observe(acl(new TrainState(TrainState.KILLED), "vl3", "Main"));

        assertEquals(before, TraceProbe.probeFailures(),
                "a KILL is how every train ends; only a KILL that arrives FIRST is a gap");
        assertEquals(3, probe.lines.size());
    }

    // ------------------------------------------------------------------ the grace window

    @Test
    @DisplayName("GENERATED_GRACE boundary: at grace-1 later trains a silent train is still waited for")
    void one_short_of_the_grace_is_not_yet_a_gap() {
        RecordingProbe probe = probe();

        int before = TraceProbe.probeFailures();
        // vl0 is announced and never speaks. Then vl1..vl7 are announced: seven later trains,
        // one short of TraceProbe.GENERATED_GRACE.
        for (int i = 0; i <= TraceProbe.GENERATED_GRACE - 1; i++) announce(probe, i);

        assertEquals(before, TraceProbe.probeFailures(),
                "the grace is " + TraceProbe.GENERATED_GRACE + " later trains; only "
                        + (TraceProbe.GENERATED_GRACE - 1) + " have been announced");
    }

    @Test
    @DisplayName("GENERATED_GRACE boundary: at exactly grace later trains the silent train is a gap")
    void exactly_the_grace_makes_it_a_gap() {
        RecordingProbe probe = probe();

        int before = TraceProbe.probeFailures();
        for (int i = 0; i <= TraceProbe.GENERATED_GRACE - 1; i++) announce(probe, i);
        // Exactly the arrangement of the test above plus ONE more announcement. `announcedIndex
        // - GENERATED_GRACE` is 0 here and vl0 is the head of the watch list, so the `<=` at
        // TraceProbe.checkGenerated is what makes this the first index to trip: turn it into
        // `<` and this test fails while the one above still passes.
        String report = capturingErr(() -> announce(probe, TraceProbe.GENERATED_GRACE));

        assertEquals(before + 1, TraceProbe.probeFailures());
        assertTrue(report.contains("vl0 was announced on PLAN_TRAIN but never sent a TRAIN_STATE"),
                () -> report);
    }

    @Test
    @DisplayName("a train that did speak is never called a gap, however many trains follow it")
    void a_train_that_spoke_is_dropped_from_the_watch_list() {
        RecordingProbe probe = probe();
        int before = TraceProbe.probeFailures();

        // Every train announces and then speaks, which is the healthy run. vl0 is GENERATED_GRACE
        // announcements behind by the end, so if checkFirstState stopped taking it OFF the watch
        // list the last announcement below would call it stale.
        for (int i = 0; i <= TraceProbe.GENERATED_GRACE; i++) {
            announce(probe, i);
            probe.observe(acl(new TrainState("stA -> stB : vl" + i + " generated"), "vl" + i, "Main"));
        }

        assertEquals(before, TraceProbe.probeFailures());
    }

    @Test
    @DisplayName("a PLAN_TRAIN whose subject is not a vl<n> name is not watched for at all")
    void a_non_train_subject_arms_no_watch() {
        RecordingProbe probe = probe();

        int before = TraceProbe.probeFailures();
        // trainIndex() returns -1 for each of these, so checkGenerated is never called and
        // nothing can ever be reported stale for them.
        probe.observe(acl(new PlanTrain("vl", "stA", "stB"), "Main", "Main"));
        probe.observe(acl(new PlanTrain("vlx", "stA", "stB"), "Main", "Main"));
        probe.observe(acl(new PlanTrain("stA", "stA", "stB"), "Main", "Main"));
        probe.observe(acl(new PlanTrain("vl99999999999999999999", "stA", "stB"), "Main", "Main"));
        // If any of the four had armed index 0 -- which is what trainIndex returning 0 instead
        // of -1 would do -- this announcement is exactly the one that would call it stale.
        announce(probe, TraceProbe.GENERATED_GRACE);

        assertEquals(before, TraceProbe.probeFailures());
        assertEquals(5, probe.lines.size(),
                "every one of them is still rendered -- they are trace lines, just not trains");
    }

    // ------------------------------------------------------------------ the two behaviours

    @Test
    @DisplayName("Observe consumes exactly the fifteen railway channels off the shared queue")
    void observe_takes_the_railway_traffic() {
        RecordingProbe probe = probe();
        TraceProbe.Observe observe = probe.new Observe();
        observe.setAgent(probe);

        probe.postMessage(acl(new StationInfo(0, 6), "stA", "Main"));
        probe.postMessage(foreign());
        probe.postMessage(acl(new TrainState("stA -> stB : vl3 generated"), "vl3", "Main"));
        assertEquals(3, probe.getCurQueueSize());

        observe.action();
        observe.action();
        observe.action();

        assertEquals(1, probe.getCurQueueSize(), "the foreign message is left for the Drain");
        assertEquals(2, probe.lines.size());
    }

    @Test
    @DisplayName("Drain consumes the exact complement, says so on stderr, and counts no failure")
    void the_drain_is_the_exact_complement() {
        RecordingProbe probe = probe();
        TraceProbe.Observe observe = probe.new Observe();
        TraceProbe.Drain drain = probe.new Drain();
        observe.setAgent(probe);
        drain.setAgent(probe);

        probe.postMessage(foreign());
        observe.action();
        assertEquals(1, probe.getCurQueueSize(), "Observe must not touch it");

        int before = TraceProbe.probeFailures();
        String report = capturingErr(drain::action);

        assertEquals(0, probe.getCurQueueSize(), "a stray AMS reply must not grow a queue forever");
        assertTrue(probe.lines.isEmpty(), "and it must never become a trace line");
        assertTrue(report.contains("unobservable message reached the probe"), () -> report);
        assertEquals(before, TraceProbe.probeFailures(),
                "a stray inform is not a hole in the trace, so it must not make the run"
                        + " unrecordable");
    }

    @Test
    @DisplayName("an empty queue blocks both behaviours rather than spinning or throwing")
    void an_empty_queue_just_blocks() {
        RecordingProbe probe = probe();
        TraceProbe.Observe observe = probe.new Observe();
        TraceProbe.Drain drain = probe.new Drain();
        observe.setAgent(probe);
        drain.setAgent(probe);

        observe.action();
        drain.action();

        assertEquals(0, probe.getCurQueueSize());
        assertTrue(probe.lines.isEmpty());
        assertFalse(observe.done(), "a CyclicBehaviour never finishes");
        assertFalse(drain.done());
    }

    // ------------------------------------------------------------------ helpers

    /** {@code Main} announcing {@code vl<index>} on PLAN_TRAIN, which is what arms the watch. */
    private static void announce(RecordingProbe probe, int index) {
        probe.observe(acl(new PlanTrain("vl" + index, "stA", "stB"), "Main", "Main"));
    }

    /** Platform traffic: not a railway channel, so it belongs to the Drain. */
    private static ACLMessage foreign() {
        ACLMessage ams = new ACLMessage(ACLMessage.FAILURE);
        ams.setOntology("FIPA-Agent-Management");
        ams.setContent("(unreachable)");
        return ams;
    }

    private static String capturingErr(Runnable body) {
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            body.run();
        } finally {
            System.setErr(err);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
