/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterRequest;
import cz.vutbr.fit.ags.railway.domain.msg.PathFindRequest;
import cz.vutbr.fit.ags.railway.domain.msg.PlanTrain;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.RoadDirection;
import cz.vutbr.fit.ags.railway.domain.msg.RoadStateReport;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import cz.vutbr.fit.ags.railway.domain.msg.TrainState;
import cz.vutbr.fit.ags.railway.domain.msg.Vote;
import cz.vutbr.fit.ags.railway.jade.Messages;
import cz.vutbr.fit.ags.railway.jade.Templates;
import jade.core.JadeAgentFixture;
import jade.lang.acl.ACLMessage;
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
 * The {@code Main} agent's queue, driven for real — and the one template rule in this port that a
 * tidy-up would silently break.
 * <p>
 * {@code Party.MAIN} has <b>six</b> inbound channels and this agent handles <b>four</b>. The other
 * two are {@link Planning}'s, and its {@code Inbox} narrows to {@code VOTE} alone while an election
 * is open so that a {@code PLAN_TRAIN} waits in <em>this</em> agent's mailbox — which is how the
 * port reproduces Cybele's serial dispatch, under which the application ran exactly one election at
 * a time. Widening {@link RailwayMainAgent#hubTemplate()} to
 * {@code Templates.inbound(Party.MAIN)} compiles, looks tidier, passes every other test in the
 * tree, and changes every departure time in the trace. So it is pinned here.
 */
class RailwayMainAgentInboxTest {

    private static final class RecordingHub extends RailwayMainAgent {
        private static final long serialVersionUID = 1L;
        private final transient List<RailwayMessage> sent = new ArrayList<RailwayMessage>();

        @Override
        protected void emit(RailwayMessage message, String receiver) {
            sent.add(message);
        }

        @Override
        protected void openView() {
            // no window
        }

        @Override
        protected void spawn(String name, String className, Object[] args) {
            // no container
        }
    }

    private static RecordingHub hub() {
        RecordingHub hub = new RecordingHub();
        JadeAgentFixture.name(hub, RailwayMainAgent.MAIN_AGENT_NAME);
        hub.setup();
        hub.sent.clear();
        return hub;
    }

    private static void post(RecordingHub hub, RailwayMessage message, String from) {
        hub.postMessage(Messages.build(message, from, RailwayMainAgent.MAIN_AGENT_NAME));
    }

    @Test
    @DisplayName("the inbox takes the hub's four channels and leaves Planning's two in the queue")
    void the_inbox_does_not_steal_PLAN_TRAIN_or_VOTE() {
        RecordingHub hub = hub();
        RailwayMainAgent.Inbox inbox = hub.new Inbox();
        inbox.setAgent(hub);

        post(hub, new StationInfo(0, 6), "stA");
        post(hub, new RoadStateReport(RoadDirection.FREE), "tr1");
        post(hub, new TrainState("stA -> stC : vl0 generated"), "vl0");
        post(hub, new PathFindRequest("stB", "stC"), "stB");
        post(hub, new PlanTrain("vl0", "stA", "stC"), RailwayMainAgent.MAIN_AGENT_NAME);
        post(hub, new Vote("stA", "vl0", 7L), "stA");
        assertEquals(6, hub.getCurQueueSize());

        for (int i = 0; i < 6; i++) {
            inbox.action();
        }

        assertEquals(2, hub.getCurQueueSize(),
                "PLAN_TRAIN and VOTE must stay queued -- they are Planning's, and its gate on"
                        + " PLAN_TRAIN is what keeps elections serial");
        assertEquals(1, hub.getStationInfos().size());
        assertEquals(1, hub.getRoadAgentStates().size());
        assertEquals(1, hub.getTrainTableModel().getRowCount());
        assertEquals(List.of(Channel.PATH_FIND_REPLY),
                List.of(hub.sent.get(0).channel()));
    }

    @Test
    @DisplayName("the hub template is the four channels, and is NOT Templates.inbound(Party.MAIN)")
    void the_hub_template_is_narrower_than_the_party() {
        jade.core.JadePlatformFixture.install("opencybele-test");
        for (Channel channel : List.of(Channel.STATION_INFO, Channel.ROAD_STATE,
                Channel.TRAIN_STATE, Channel.PATH_FIND)) {
            assertTrue(RailwayMainAgent.hubTemplate().match(sample(channel)),
                    "the hub must take " + channel.event());
        }
        for (Channel channel : List.of(Channel.PLAN_TRAIN, Channel.VOTE)) {
            assertFalse(RailwayMainAgent.hubTemplate().match(sample(channel)),
                    channel.event() + " belongs to Planning; taking it here defeats its gate");
            assertTrue(Templates.inbound(cz.vutbr.fit.ags.railway.domain.msg.Party.MAIN)
                            .match(sample(channel)),
                    "and inbound(MAIN) DOES match it, which is exactly why hubTemplate() is"
                            + " a list rather than that one-liner");
        }
    }

    @Test
    @DisplayName("the drain is the whole agent's complement, so it never competes with either inbox")
    void the_drain_belongs_to_the_agent_not_to_an_activity() {
        RecordingHub hub = hub();
        RailwayMainAgent.Drain drain = hub.new Drain();
        drain.setAgent(hub);

        // Planning's two: the agent-level drain must NOT eat them, or a queued PLAN_TRAIN would
        // be reported as a bug and discarded instead of waiting for the planner.
        post(hub, new PlanTrain("vl0", "stA", "stC"), RailwayMainAgent.MAIN_AGENT_NAME);
        post(hub, new Vote("stA", "vl0", 7L), "stA");
        // Not a Main channel at all.
        post(hub, new EnterRequest("vl0", null, "stC"), "vl0");

        String report = captureErr(() -> {
            drain.action();
            drain.action();
            drain.action();
        });

        assertEquals(2, hub.getCurQueueSize(), "Planning's two are untouched");
        assertTrue(report.contains(Channel.ENTER.id()), () -> report);
        assertFalse(report.contains(Channel.PLAN_TRAIN.id()), () -> report);
        assertTrue(hub.sent.isEmpty(), "a stray message must not provoke a reply");
    }

    @Test
    @DisplayName("a message with the right channel and the wrong act falls through to the drain")
    void a_tampered_performative_is_not_silently_accepted() {
        RecordingHub hub = hub();
        RailwayMainAgent.Inbox inbox = hub.new Inbox();
        inbox.setAgent(hub);

        ACLMessage tampered = Messages.build(new StationInfo(0, 6), "stA",
                RailwayMainAgent.MAIN_AGENT_NAME);
        tampered.setPerformative(ACLMessage.REQUEST);
        hub.postMessage(tampered);

        inbox.action();

        assertEquals(1, hub.getCurQueueSize(),
                "the template is ontology AND performative for exactly this reason"
                        + " (message-ontology.md 7)");
        assertTrue(hub.getStationInfos().isEmpty());
    }

    @Test
    @DisplayName("an empty queue blocks both behaviours instead of spinning or throwing")
    void an_empty_queue_just_blocks() {
        RecordingHub hub = hub();
        RailwayMainAgent.Inbox inbox = hub.new Inbox();
        RailwayMainAgent.Drain drain = hub.new Drain();
        inbox.setAgent(hub);
        drain.setAgent(hub);

        inbox.action();
        drain.action();

        assertEquals(0, hub.getCurQueueSize());
        assertTrue(hub.sent.isEmpty());
    }

    private static ACLMessage sample(Channel channel) {
        RailwayMessage message = switch (channel) {
            case STATION_INFO -> new StationInfo(0, 6);
            case ROAD_STATE -> new RoadStateReport(RoadDirection.FREE);
            case TRAIN_STATE -> new TrainState("stA -> stC : vl0 generated");
            case PATH_FIND -> new PathFindRequest("stB", "stC");
            case PLAN_TRAIN -> new PlanTrain("vl0", "stA", "stC");
            case VOTE -> new Vote("stA", "vl0", 7L);
            default -> throw new IllegalArgumentException(channel.name());
        };
        return Messages.build(message, "stA", RailwayMainAgent.MAIN_AGENT_NAME);
    }

    private static String captureErr(Runnable body) {
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
