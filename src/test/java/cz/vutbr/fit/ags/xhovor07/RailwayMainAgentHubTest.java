/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.PathFindReply;
import cz.vutbr.fit.ags.railway.domain.msg.PathFindRequest;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.RoadDirection;
import cz.vutbr.fit.ags.railway.domain.msg.RoadStateReport;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import cz.vutbr.fit.ags.railway.domain.msg.TrainState;
import cz.vutbr.fit.ags.railway.jade.Messages;
import jade.core.JadeAgentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 for #33: the hub's <b>social-knowledge</b> half — the {@code PATH_FIND} service, the three
 * state collections and the listener fan-out that replaced {@code java.util.Observable}.
 * <p>
 * The agent is driven through its own {@link RailwayMainAgent#dispatch} seam with its outbound
 * seam redirected into a list; no container, no threads, no window. {@code setup()} runs for real
 * — the table model has to be registered by it, which is half of what the last test checks — but
 * the factory is a no-op here: {@link RailwayMainAgentStartupTest} is where what it asked for is
 * asserted on.
 */
class RailwayMainAgentHubTest {

    private record Sent(RailwayMessage message, String receiver) {
    }

    private static final class RecordingHub extends RailwayMainAgent {
        private static final long serialVersionUID = 1L;
        private final transient List<Sent> sent = new ArrayList<Sent>();

        @Override
        protected void emit(RailwayMessage message, String receiver) {
            sent.add(new Sent(message, receiver));
        }

        @Override
        protected void openView() {
            // no window
        }

        @Override
        protected void spawn(String name, String className, Object[] args) {
            // no container; RailwayMainAgentStartupTest is where the factory is asserted on
        }
    }

    private static RecordingHub hub() {
        RecordingHub hub = new RecordingHub();
        JadeAgentFixture.name(hub, RailwayMainAgent.MAIN_AGENT_NAME);
        hub.setup();
        hub.sent.clear();
        return hub;
    }

    private static void deliver(RecordingHub hub, RailwayMessage message, String from) {
        hub.dispatch(Messages.build(message, from, RailwayMainAgent.MAIN_AGENT_NAME));
    }

    // ------------------------------------------------------------------ PATH_FIND

    @Test
    @DisplayName("PATH_FIND is answered to the asking station, with the target it asked about")
    void the_path_find_round_trip() {
        RecordingHub hub = hub();

        deliver(hub, new PathFindRequest("stB", "stC"), "stB");

        assertEquals(1, hub.sent.size());
        Sent reply = hub.sent.get(0);
        assertEquals(Channel.PATH_FIND_REPLY, reply.message().channel());
        assertEquals("stB", reply.receiver(),
                "CH-09 goes to the ASKING station -- 2008 addressed PATH_FIND_REPLY.<from>");
        PathFindReply content = (PathFindReply) reply.message();
        assertEquals("stC", content.target(), "the reply carries the target, not the next hop");
        // stB -> stC is stB --tr5-- stD --tr4-- stE --tr6-- stF --tr7-- stC on the default
        // topology, so the direction out of stB is tr5. The golden's opening burst agrees:
        // "stB|<T>|PATH_FIND_REPLY|Main|stB|<P>|target=stC,direction=tr5".
        assertEquals("tr5", content.direction());
    }

    @Test
    @DisplayName("the direction is the first hop of the path, per station, and never the target")
    void the_direction_is_read_off_the_topology() {
        RecordingHub hub = hub();

        deliver(hub, new PathFindRequest("stA", "stC"), "stA");
        deliver(hub, new PathFindRequest("stC", "stA"), "stC");
        deliver(hub, new PathFindRequest("stG", "stD"), "stG");

        List<String> directions = new ArrayList<String>();
        for (Sent s : hub.sent) {
            directions.add(((PathFindReply) s.message()).direction());
        }
        // stA -> stC leaves by tr1 (stA's only track); stC -> stA leaves by tr7 (stC's only
        // track); stG -> stD leaves by tr3, towards stE. Compare with iteration-order.md's
        // "stA->stC = [stA, tr1, stH, tr2, stG, tr3, stE, tr6, stF, tr7, stC]".
        assertEquals(List.of("tr1", "tr7", "tr3"), directions);
        assertEquals(List.of("stA", "stC", "stG"),
                List.of(hub.sent.get(0).receiver(), hub.sent.get(1).receiver(),
                        hub.sent.get(2).receiver()));
    }

    // ------------------------------------------------------------------ state collection

    @Test
    @DisplayName("station and road state are keyed by the SENDER, not by anything in the payload")
    void state_is_filed_under_the_sending_agent() {
        RecordingHub hub = hub();

        deliver(hub, new StationInfo(3, 6), "stA");
        deliver(hub, new RoadStateReport(RoadDirection.TRAVEL_RIGHT), "tr4");

        // 2008 recovered the name from the channel tag suffix (objectName); this reads the
        // :sender AID, which INVENTORY DEF-12 calls the more correct form. The payload carries
        // neither name, so getting this wrong cannot be papered over.
        assertEquals(new StationInfo(3, 6), hub.getStationInfos().get("stA"));
        assertEquals(RoadDirection.TRAVEL_RIGHT, hub.getRoadAgentStates().get("tr4"));
        assertNull(hub.getStationInfos().get("stB"));
        assertTrue(hub.sent.isEmpty(), "collecting state provokes no reply");
    }

    @Test
    @DisplayName("the published station info is the immutable snapshot the station sent")
    void the_published_payload_is_a_value_not_an_alias() {
        RecordingHub hub = hub();
        StationInfo sent = new StationInfo(1, 6);

        deliver(hub, sent, "stA");
        deliver(hub, new StationInfo(2, 6), "stA");

        // DEF-13's aliasing is structurally unreproducible under JADE's snapshot semantics
        // (message-ontology.md 5.5), so the map holds VALUES: the second push replaces the
        // first, and nothing the station does afterwards can move what the canvas already read.
        assertEquals(new StationInfo(2, 6), hub.getStationInfos().get("stA"));
        assertEquals(1, sent.occupied(), "the record the test built is untouched");
    }

    @Test
    @DisplayName("TRAIN_STATE accumulates in arrival order and KILL removes the train")
    void the_train_table_follows_the_state_stream() {
        RecordingHub hub = hub();

        deliver(hub, new TrainState("stA -> stC : vl0 generated"), "vl0");
        deliver(hub, new TrainState("stB -> stA : vl1 generated"), "vl1");
        deliver(hub, new TrainState("stA -> stC : entered to tr1"), "vl0");

        javax.swing.table.TableModel model = hub.getTrainTableModel();
        assertEquals(2, model.getRowCount());
        assertEquals(List.of("vl0", "vl1"),
                List.of(model.getValueAt(0, 0), model.getValueAt(1, 0)),
                "trainStates is a LinkedHashMap; arrival order is what the JTable shows");
        assertEquals("stA -> stC : entered to tr1", model.getValueAt(0, 1));

        deliver(hub, new TrainState(TrainState.KILLED), "vl0");

        assertEquals(1, model.getRowCount(), "the destructor sentinel removes the row");
        assertEquals("vl1", model.getValueAt(0, 0));
    }

    @Test
    @DisplayName("KILL is the sentinel, not a state -- a train may legitimately report anything else")
    void only_the_sentinel_removes() {
        RecordingHub hub = hub();

        deliver(hub, new TrainState("vl0 KILLED at stA"), "vl0");

        assertEquals(1, hub.getTrainTableModel().getRowCount(),
                "the comparison is on the whole string, exactly as 2008's equals(KILLED) was");
    }

    // ------------------------------------------------------------------ the listener fan-out

    @Test
    @DisplayName("every state message notifies every listener, and PATH_FIND notifies none")
    void the_listeners_replace_Observable() {
        RecordingHub hub = hub();
        List<String> first = new ArrayList<String>();
        List<String> second = new ArrayList<String>();
        hub.addListener(() -> first.add("x"));
        hub.addListener(() -> second.add("x"));

        deliver(hub, new StationInfo(0, 6), "stA");
        deliver(hub, new RoadStateReport(RoadDirection.FREE), "tr1");
        deliver(hub, new TrainState("stA -> stC : vl0 generated"), "vl0");
        int afterState = first.size();
        deliver(hub, new PathFindRequest("stB", "stC"), "stB");

        assertEquals(3, afterState, "one notification per state push, exactly as fireChange was");
        assertEquals(3, first.size(), "answering a PATH_FIND changes no published state");
        assertEquals(first.size(), second.size(), "both listeners, not just the last registered");
    }

    @Test
    @DisplayName("the table model is registered by setup(), not by the view it feeds")
    void the_model_subscribes_without_a_view() {
        // openView() is overridden to nothing throughout this suite, so no Gui, no JTable and no
        // canvas is ever built -- and the model still sees every state push. That is
        // defect-triage.md 4.5's point reproduced: addObserver(trainTableModel) was
        // UNCONDITIONAL and ABOVE the headless guard, so it is the AGENT that feeds the model,
        // never the window.
        RecordingHub hub = hub();

        deliver(hub, new TrainState("stA -> stC : vl0 generated"), "vl0");

        assertEquals(1, hub.getTrainTableModel().getRowCount());

        // WHAT THIS CANNOT CHECK, stated rather than implied by the test's name. The stronger
        // claim -- "sim.headless=true changes nothing about DEF-24" -- needs a run whose
        // configuration says headless, and ScenarioConfig is a JVM-wide memoised singleton
        // resolved on first use, so no test in this shared JVM can produce one. A mutant that
        // wrote `if (!config.isHeadless()) addListener(trainTableModel);` is therefore the SAME
        // PROGRAM here, and this suite cannot kill it. Falsifying it needs a headless launch,
        // which is #38's L2 lane.
    }

    // ------------------------------------------------------------------ the unexpected arm

    @Test
    @DisplayName("a channel the hub does not own is reported rather than dispatched")
    void an_unhandled_channel_falls_through_to_the_report() {
        RecordingHub hub = hub();
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        java.io.PrintStream err = System.err;
        try {
            System.setErr(new java.io.PrintStream(captured, true,
                    java.nio.charset.StandardCharsets.UTF_8));
            // VOTE is Party.MAIN inbound, but it is Planning's, not the hub's. Reaching
            // dispatch() with one at all would be a template bug; the default arm says so.
            hub.dispatch(Messages.build(new cz.vutbr.fit.ags.railway.domain.msg.Vote(
                    "stA", "vl0", 7L), "stA", RailwayMainAgent.MAIN_AGENT_NAME));
        } finally {
            System.setErr(err);
        }
        String report = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(report.contains(Channel.VOTE.id()), () -> report);
        assertTrue(hub.sent.isEmpty());
    }
}
