/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import java.util.ArrayList;
import java.util.List;

import cz.vutbr.fit.ags.railway.domain.clock.VirtualClock;
import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterReply;
import cz.vutbr.fit.ags.railway.domain.msg.EnterRequest;
import cz.vutbr.fit.ags.railway.domain.msg.LeaveNotice;
import cz.vutbr.fit.ags.railway.domain.msg.StartCommand;
import cz.vutbr.fit.ags.railway.domain.msg.TrainState;
import cz.vutbr.fit.ags.railway.jade.Messages;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle test {@code docs/Phase1.md} L78 asks for, in the form this application admits (#38).
 *
 * <h2>"Registers with the DF" does not apply, and #38 says what replaces it</h2>
 *
 * The template asks a lifecycle test to check that an agent starts, registers with the DF and
 * terminates cleanly. <b>This application uses no DF at all</b> ({@code docs/message-ontology.md}
 * §2: nothing advertises a capability, nothing searches for one, every channel is addressed by a
 * literal name), so the issue restates the criterion: <em>"is reachable by its local name, and its
 * probe topic is registered"</em>, and agent death is only meaningful for {@code Train}. That is
 * exactly the pair of tests below.
 *
 * <h2>The first of #37's three untestable items</h2>
 *
 * {@code TraceProbe.setup()} needs a {@code TopicManagementHelper}, which needs a container with
 * {@code jade.core.messaging.TopicManagementService} in its profile. So the whole method — the
 * helper lookup, the fifteen {@code register} calls, the {@code READY.countDown()} that releases
 * {@code Main} — has never been executed by a test until this one. {@link #awaitReady()} is the
 * second item, and this class exercises its <em>success</em> branch; its failure branch is
 * {@code ProbeReadyTimeoutIT}, which needs a JVM in which no probe has ever registered.
 *
 * <h2>Why the method order is declared</h2>
 *
 * Same reason as {@code AddressingIT}: {@code TraceTopics.enable()} is one-way, and
 * {@code TraceProbe.READY} is a static latch counted down once per JVM. The one call to
 * {@code TraceProbe.awaitReady()} that means anything is the first one, so it is in the first test.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LifecycleIT extends ContainerFixture {

    @BeforeAll
    static void publishAClock() {
        // TraceProbe reads trace field 2 from RunControl.simTimeMs(), which is -1 until the main
        // agent publishes a clock. Nothing here asserts on field 2, but a probe rendering -1 for a
        // whole class would be misleading to read in a failure report. First-writer-wins, and with
        // forkEvery = 1 this class is the only writer in its JVM.
        if (RunControl.simClock() == null) {
            RunControl.useClock(new VirtualClock(1000L, 1.0), 1000L);
        }
    }

    @Test
    @Order(1)
    @DisplayName("the probe's setup() registers all fifteen topics, and awaitReady() releases the run")
    void the_probe_registers_to_every_channel_topic() throws Exception {
        assertFalse(TraceTopics.isEnabled(), "premise: nothing has enabled tracing yet");
        TraceTopics.enable();

        final int failuresBefore = TraceProbe.probeFailures();
        final Driver main = driver(RailwayMainAgent.MAIN_AGENT_NAME);
        final RecordingProbe probe = start(TraceProbe.PROBE_AGENT_NAME, new RecordingProbe());

        // The production barrier, on its success branch. This is the call Main makes between
        // starting the probe and starting RailwayMainAgent; it returns only once setup() has
        // reached READY.countDown(), i.e. after all fifteen helper.register calls.
        TraceProbe.awaitReady();

        // One message on every channel, each carrying its channel's topic AID as a second
        // receiver, exactly as an agent's emit seam builds it. If a single register() had been
        // skipped -- or if the fifteen topics were minted per instance rather than per constant --
        // that channel's line would simply be missing.
        for (Channel channel : Channel.values()) {
            main.send(ChannelSamples.of(channel), RailwayMainAgent.MAIN_AGENT_NAME,
                    TraceTopics.topic(channel));
        }

        final List<String> observed = new ArrayList<String>(
                probe.expectEvents(Channel.values().length, REPLY_MS));
        final List<String> expected = new ArrayList<String>();
        for (Channel channel : Channel.values()) {
            expected.add(channel.event());
        }
        java.util.Collections.sort(observed);
        java.util.Collections.sort(expected);
        assertEquals(expected, observed,
                "the probe must observe every one of the fifteen channels. A missing event names"
                        + " the topic whose registration did not take.");

        // And the named receiver still got all fifteen: the topic copy is additive.
        final List<String> delivered = new ArrayList<String>();
        for (int i = 0; i < Channel.values().length; i++) {
            delivered.add(Messages.channelOf(main.expect(Channel.values()[i], REPLY_MS)).event());
        }
        java.util.Collections.sort(delivered);
        assertEquals(expected, delivered, "every message also reached its named receiver");

        assertEquals(failuresBefore, TraceProbe.probeFailures(),
                "rendering fifteen well-formed messages must not report a probe failure");
        assertQueueDrained(probe);
    }

    @Test
    @Order(2)
    @DisplayName("a train is reachable by name, walks its route, dies, and leaves the platform")
    void a_train_lives_from_generated_to_kill() throws Exception {
        final Driver main = driver(RailwayMainAgent.MAIN_AGENT_NAME);
        final Driver stA = driver("stA");
        final RecordingProbe probe = start(TraceProbe.PROBE_AGENT_NAME, new RecordingProbe());
        probe.awaitRegistered(REPLY_MS);

        // A one-hop route: origin and destination are the same station, which is the shortest walk
        // that still reaches Train's arrival branch -- the ENTER_REPLY with next == null that makes
        // it call Agent.die(). docs/message-ontology.md section 5.4 calls that null one of the two
        // meaningful ones in the whole protocol.
        final Train train = start("vl3", new Train(), "stA", "stA");
        assertTrue(isAlive("vl3"), "the train is reachable by its local name -- this repo's"
                + " replacement for 'registers with the DF', since it uses no DF");

        // ---- birth
        assertEquals("stA -> stA : vl3 generated",
                main.expectContent(Channel.TRAIN_STATE, TrainState.class, REPLY_MS).state());

        // ---- the walk
        main.send(new StartCommand("stA"), "vl3");
        final EnterRequest enter = stA.expectContent(Channel.ENTER, EnterRequest.class, REPLY_MS);
        assertEquals("vl3", enter.train());
        assertEquals("stA", enter.endStation());
        stA.send(new EnterReply("stA", null), "vl3");
        assertEquals("stA -> stA : entered to stA",
                main.expectContent(Channel.TRAIN_STATE, TrainState.class, REPLY_MS).state());

        // ---- death: takeDown's two statements, in order and both on the wire
        final ACLMessage farewell = stA.expect(Channel.LEAVE, REPLY_MS);
        assertEquals("vl3", ((LeaveNotice) Messages.contentOf(farewell)).train(),
                "a completed train must emit a final LEAVE to its destination immediately before"
                        + " TRAIN_STATE state=KILL -- its absence is a port bug (defect-triage.md)");
        assertEquals(TrainState.KILLED,
                main.expectContent(Channel.TRAIN_STATE, TrainState.class, REPLY_MS).state());

        // ---- and the platform has forgotten it
        assertDiesWithin("vl3", DRAIN_MS);
        stA.expectSilence(SILENCE_MS);
        main.expectSilence(SILENCE_MS);

        // The probe, which nothing addressed, saw the whole story -- including the two teardown
        // messages, which reach the transport because Agent.clean() runs takeDown() before it calls
        // handleEnd(). Every one of these seven is causally after the one before it, so the order
        // is the run's order and not a race.
        assertEquals(List.of("TRAIN_STATE", "START", "ENTER", "ENTER_REPLY", "TRAIN_STATE",
                        "LEAVE", "TRAIN_STATE"),
                probe.expectEvents(7, REPLY_MS),
                "the probe observed the train's whole life without being addressed once");
        probe.expectSilence(SILENCE_MS);
        assertQueueDrained(probe);
    }
}
