/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import cz.vutbr.fit.ags.railway.domain.clock.SimClock;
import cz.vutbr.fit.ags.railway.domain.msg.RoadDirection;
import cz.vutbr.fit.ags.railway.domain.msg.RoadStateReport;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import cz.vutbr.fit.ags.railway.jade.Messages;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 for #33: the <b>agent factory</b> and the <b>startup barrier</b>, driven as a POJO
 * ({@code docs/TESTING.md} §4.1) — a real {@link RailwayMainAgent} with its two container-touching
 * seams redirected into lists, so that {@code setup()} runs for real without a platform and
 * without a display.
 * <p>
 * Three things are held down here, and the third is the one this ticket exists for.
 * <ol>
 *   <li><b>Creation order is #19's</b>, not the JDK's and not lexicographic. The literals below are
 *       copied from {@code docs/iteration-order.md}'s before/after table, which is itself the
 *       output of {@code docs/probes/OrderLock.java} on the pre-#19 and post-#19 trees. A port that
 *       "tidied" the iteration into {@code stA…stH} would be caught here rather than by a golden.</li>
 *   <li><b>Which agents get a clock.</b> A station takes two arguments and no clock; a road takes
 *       four, the fourth being the <em>same instance</em> every other road got. That asymmetry has
 *       to live somewhere and this is the somewhere.</li>
 *   <li><b>The generator is armed by the fifteenth opener, not by {@code setup()}.</b> That is the
 *       whole startup-ordering decision: the normalizer's {@code startup-block} rule sorts the
 *       <em>maximal opening run</em> of {@code STATION_INFO}/{@code ROAD_STATE} lines, so it needs
 *       that run to be unbroken, and the only thing that can break it is a {@code PLAN_TRAIN}
 *       arriving before the last opener. See {@link RailwayMainAgent}'s class comment.</li>
 * </ol>
 */
class RailwayMainAgentStartupTest {

    /** One {@code createNewAgent(...).start()} the hub asked for. */
    private record Spawned(String name, String className, Object[] args) {
    }

    /**
     * A real hub with its two container-touching seams redirected: {@link #spawn} into a list, and
     * {@link #openView()} into nothing. Nothing else is stubbed — the barrier, the behaviours, the
     * planner and the generator are all the production ones.
     */
    private static final class RecordingHub extends RailwayMainAgent {
        private static final long serialVersionUID = 1L;
        private final transient List<Spawned> spawned = new ArrayList<Spawned>();

        @Override
        protected void spawn(String name, String className, Object[] args) {
            spawned.add(new Spawned(name, className, args));
        }

        @Override
        protected void openView() {
            // No JFrame in a unit test; see RailwayMainAgent.openView's Javadoc for why this is a
            // method rather than an inline `if (!headless)`.
        }

        List<String> spawnedNames() {
            List<String> names = new ArrayList<String>();
            for (Spawned s : spawned) {
                names.add(s.name());
            }
            return names;
        }

        Spawned spawnedNamed(String name) {
            for (Spawned s : spawned) {
                if (s.name().equals(name)) {
                    return s;
                }
            }
            throw new AssertionError("nothing spawned called " + name);
        }
    }

    private static RecordingHub hub() {
        RecordingHub hub = new RecordingHub();
        JadeAgentFixture.name(hub, RailwayMainAgent.MAIN_AGENT_NAME);
        hub.setup();
        return hub;
    }

    /** Deliver one child's opening state push through the agent's real queue and dispatch. */
    private static void openerFrom(RecordingHub hub, String child) {
        if (child.startsWith("st")) {
            hub.dispatch(Messages.build(new StationInfo(0, 1), child,
                    RailwayMainAgent.MAIN_AGENT_NAME));
        } else {
            hub.dispatch(Messages.build(new RoadStateReport(RoadDirection.FREE), child,
                    RailwayMainAgent.MAIN_AGENT_NAME));
        }
    }

    // -------------------------------------------------------------------- the factory

    @Test
    @DisplayName("eight stations then seven roads, in #19's measured order and no other")
    void the_creation_order_is_the_one_iteration_order_md_pinned() {
        RecordingHub hub = hub();

        // docs/iteration-order.md, claims 1 and 2, verbatim. NOT stA..stH / tr1..tr7: that is
        // the plain-LinkedHash variant #19 explicitly REJECTED because it changes both orders,
        // and agent creation order is observable in a trace.
        assertEquals(List.of("stB", "stA", "stD", "stC", "stF", "stE", "stH", "stG",
                        "tr1", "tr4", "tr7", "tr5", "tr3", "tr6", "tr2"),
                hub.spawnedNames());
    }

    @Test
    @DisplayName("stations get {capacity, roads} and no clock; roads get {delay, left, right, clock}")
    void the_construction_arguments_say_which_agents_own_time() {
        RecordingHub hub = hub();
        SimClock shared = hub.simClock();
        assertNotNull(shared, "setup() creates the one shared clock");

        Spawned stB = hub.spawnedNamed("stB");
        assertEquals(Station.class.getName(), stB.className());
        assertEquals(2, stB.args().length, "a station reads no clock at all (#30)");
        assertEquals(Integer.valueOf(5), stB.args()[0], "stB=5 in the default capacities");
        assertEquals(List.of("tr5"), new ArrayList<String>(
                (java.util.Collection<String>) stB.args()[1]));

        Spawned tr1 = hub.spawnedNamed("tr1");
        assertEquals(RoadAgent.class.getName(), tr1.className());
        assertEquals(4, tr1.args().length);
        assertEquals(Long.valueOf(1L), tr1.args()[0]);
        assertSame(shared, tr1.args()[3], "every road schedules on ONE clock, not on its own");

        for (String road : List.of("tr1", "tr4", "tr7", "tr5", "tr3", "tr6", "tr2")) {
            assertSame(shared, hub.spawnedNamed(road).args()[3], road);
        }
        for (String station : List.of("stA", "stB", "stC", "stD", "stE", "stF", "stG", "stH")) {
            for (Object arg : hub.spawnedNamed(station).args()) {
                assertFalse(arg instanceof SimClock, station + " must not be handed a clock");
            }
        }
    }

    @Test
    @DisplayName("road endpoints are sim.topology declaration order, not lexicographic")
    void the_endpoint_order_is_reproduced_and_not_re_derived() {
        RecordingHub hub = hub();

        // docs/iteration-order.md claim 3, verbatim. Five of these seven (tr2..tr6) would flip
        // under a lexicographic rule, and with them the TRAVEL_LEFT/TRAVEL_RIGHT symbol on every
        // ROAD_STATE line in every trace.
        Map<String, List<String>> expected = new LinkedHashMap<String, List<String>>();
        expected.put("tr1", List.of("stA", "stH"));
        expected.put("tr2", List.of("stH", "stG"));
        expected.put("tr3", List.of("stG", "stE"));
        expected.put("tr4", List.of("stE", "stD"));
        expected.put("tr5", List.of("stD", "stB"));
        expected.put("tr6", List.of("stF", "stE"));
        expected.put("tr7", List.of("stC", "stF"));

        for (Map.Entry<String, List<String>> e : expected.entrySet()) {
            Object[] args = hub.spawnedNamed(e.getKey()).args();
            assertEquals(e.getValue(), List.of((String) args[1], (String) args[2]), e.getKey());
        }
    }

    @Test
    @DisplayName("a refused creation is fatal and names the agent, rather than leaving the run short")
    void a_refused_creation_is_not_swallowed() {
        // The production seam wraps StaleProxyException in an IllegalStateException naming the
        // agent. Swallowing it would leave pendingStartup permanently non-empty, i.e. a run that
        // generates no trains at all and says nothing about why.
        RailwayMainAgent hub = new RailwayMainAgent() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void openView() {
                // no window
            }
        };
        JadeAgentFixture.name(hub, RailwayMainAgent.MAIN_AGENT_NAME);
        // No container: getContainerController() is null on an unstarted agent, so the very first
        // spawn throws out of setup(). What matters is that it THROWS rather than continuing with
        // a simulation that is missing an agent.
        assertThrows(RuntimeException.class, hub::setup);
    }

    // -------------------------------------------------------------------- the barrier

    @Test
    @DisplayName("the generator is not armed by setup(), only by the fifteenth opener")
    void the_generator_waits_for_the_whole_startup_block() {
        RecordingHub hub = hub();
        Generator generator = hub.generator();
        assertNotNull(generator);
        assertFalse(generator.isArmed(), "setup() must not arm it -- that is the whole decision");
        assertEquals(15, hub.pendingStartup().size());

        List<String> children = new ArrayList<String>(hub.spawnedNames());
        for (int i = 0; i < children.size() - 1; i++) {
            openerFrom(hub, children.get(i));
            assertFalse(generator.isArmed(),
                    "still armed too early after " + (i + 1) + " of 15 openers");
        }
        assertEquals(1, hub.pendingStartup().size());

        openerFrom(hub, children.get(children.size() - 1));

        assertTrue(generator.isArmed(), "the fifteenth opener arms it");
        assertTrue(hub.pendingStartup().isEmpty());
        assertEquals(1, hub.planning().agentClock().pending(),
                "exactly one wake-up: the first fire, on the clock Planning and Generator share");
    }

    @Test
    @DisplayName("fifteen openers from the same agent do not arm it, and later ones do not re-arm")
    void the_barrier_counts_distinct_agents_and_arms_exactly_once() {
        RecordingHub hub = hub();
        Generator generator = hub.generator();

        for (int i = 0; i < 15; i++) {
            openerFrom(hub, "stB");
        }
        assertFalse(generator.isArmed(), "a chatty station is not fifteen agents");
        assertEquals(14, hub.pendingStartup().size());

        for (String child : hub.spawnedNames()) {
            openerFrom(hub, child);
        }
        assertTrue(generator.isArmed());
        int armedOnce = hub.planning().agentClock().pending();

        // Every child keeps pushing state for the rest of the run. None of those may arm a second,
        // interleaved chain of trains on the same vl<n> counter.
        for (String child : hub.spawnedNames()) {
            openerFrom(hub, child);
        }
        assertEquals(armedOnce, hub.planning().agentClock().pending(),
                "a later STATION_INFO must not arm the generator again");
    }

    @Test
    @DisplayName("a foreign agent's state push cannot complete the barrier")
    void an_unexpected_sender_does_not_count_towards_the_barrier() {
        RecordingHub hub = hub();
        for (String child : hub.spawnedNames()) {
            if (!child.equals("tr2")) {
                openerFrom(hub, child);
            }
        }
        assertEquals(List.of("tr2"), hub.pendingStartup());

        openerFrom(hub, "tr99");

        assertFalse(hub.generator().isArmed());
        assertEquals(List.of("tr2"), hub.pendingStartup(),
                "the set is the configured children, not a countdown");
    }

    @Test
    @DisplayName("the watchdog names who has not booted, once, and still refuses to arm")
    void the_startup_watchdog_reports_and_does_not_arm() {
        RecordingHub hub = hub();
        for (String child : hub.spawnedNames()) {
            if (!child.equals("tr2") && !child.equals("stF")) {
                openerFrom(hub, child);
            }
        }

        long overdue = System.nanoTime()
                + (RailwayMainAgent.STARTUP_WATCHDOG_MS + 1) * 1000000L;
        String first = captureErr(() -> hub.reportStartupOverdue(overdue));
        String second = captureErr(() -> hub.reportStartupOverdue(overdue));

        assertTrue(first.contains("stF"), () -> first);
        assertTrue(first.contains("tr2"), () -> first);
        assertTrue(first.contains("NOT armed"), () -> first);
        assertFalse(hub.generator().isArmed(), "reporting is not arming (defect-triage 6.1)");
        assertEquals(0, hub.planning().agentClock().pending());
        assertEquals("", second, "once per run: ErrorScanner reads stderr, an unbounded spew"
                + " drowns it rather than informing it");
    }

    @Test
    @DisplayName("the watchdog is silent before its bound and silent once everyone has booted")
    void the_startup_watchdog_does_not_cry_wolf() {
        RecordingHub hub = hub();
        assertEquals("", captureErr(() -> hub.reportStartupOverdue(System.nanoTime())),
                "nothing is overdue one nanosecond after setup()");

        for (String child : hub.spawnedNames()) {
            openerFrom(hub, child);
        }
        long overdue = System.nanoTime()
                + (RailwayMainAgent.STARTUP_WATCHDOG_MS + 1) * 1000000L;
        assertEquals("", captureErr(() -> hub.reportStartupOverdue(overdue)),
                "a healthy run must never see this alarm");
    }

    // -------------------------------------------------------------------- one clock, one ticker

    @Test
    @DisplayName("Generator schedules on Planning's AgentClock, so the agent has one ticker")
    void the_generator_shares_the_planner_s_clock() {
        RecordingHub hub = hub();
        assertEquals(0, hub.planning().agentClock().pending());

        for (String child : hub.spawnedNames()) {
            openerFrom(hub, child);
        }

        // The wake-up the generator armed is visible on the PLANNER's queue, which is the whole
        // claim: one AgentClock on this agent, drained by the one ClockTickerBehaviour Planning
        // registered, budgeted by one sim.clock.granularityMs.
        assertEquals(1, hub.planning().agentClock().pending());
        assertSame(hub.simClock(), hub.planning().agentClock().clock());
        assertEquals(RoadAgent.granularityMsFor(hub.simClock().pace()),
                hub.planning().tickPeriodMs());
    }

    @Test
    @DisplayName("the hub creates exactly one SimClock and hands that instance to everyone")
    void there_is_one_clock_in_the_process() {
        RecordingHub hub = hub();
        SimClock shared = hub.simClock();
        assertInstanceOf(cz.vutbr.fit.ags.railway.domain.clock.PacedClock.class, shared);
        assertEquals(ScenarioConfig.get().getClockPace(), shared.pace());
        assertSame(shared, hub.planning().agentClock().clock());
        assertSame(shared, hub.spawnedNamed("tr1").args()[3]);
        assertSame(shared, hub.spawnedNamed("tr7").args()[3]);
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
