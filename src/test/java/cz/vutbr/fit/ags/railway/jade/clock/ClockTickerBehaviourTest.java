/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.jade.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import cz.vutbr.fit.ags.railway.domain.clock.AgentClock;
import cz.vutbr.fit.ags.railway.domain.clock.VirtualClock;
import jade.core.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 for the JADE binding of the clock (#29).
 *
 * <p>Deliberately <b>does not boot a container</b>. What a container would add is JADE's own
 * guarantee that {@code onTick} runs on the agent thread, which is JADE's property rather than
 * this class's, and paying a main-container boot for it would drag a second JVM-global platform
 * into the {@code test} JVM beside {@code JadeDeliverySpikeTest}'s. The property this code owns
 * — that the drain runs on the caller's thread and touches nothing else — is pinned by
 * {@code AgentClockTest.wakeups_run_on_the_calling_thread}. What is left to check here is the
 * wiring, and that a tick is exactly one drain.</p>
 */
class ClockTickerBehaviourTest {

    private final VirtualClock clock = new VirtualClock(0, 1.0);
    private final AgentClock agentClock = new AgentClock(clock);

    @Test
    @DisplayName("one tick is one drain, on the ticking thread")
    void a_tick_drains_the_due_wakeups() {
        final List<Thread> ran = new ArrayList<Thread>();
        agentClock.scheduleAt(1000, () -> ran.add(Thread.currentThread()));
        ClockTickerBehaviour behaviour = new ClockTickerBehaviour(new Agent(), 10, agentClock);

        behaviour.onTick();
        assertEquals(List.of(), ran, "nothing is due yet");

        clock.advanceSimTo(1000);
        behaviour.onTick();
        assertEquals(1, ran.size());
        assertSame(Thread.currentThread(), ran.get(0),
                "the wake-up runs on the thread that ticked -- in JADE, the agent's own");
        assertEquals(0, agentClock.pending());
    }

    @Test
    @DisplayName("the behaviour carries the agent's clock and rejects a missing one")
    void the_wiring_is_checked_at_construction() {
        Agent agent = new Agent();
        ClockTickerBehaviour behaviour = new ClockTickerBehaviour(agent, 10, agentClock);
        assertSame(agentClock, behaviour.agentClock());
        assertSame(agent, behaviour.getAgent());
        assertThrows(IllegalArgumentException.class,
                () -> new ClockTickerBehaviour(agent, 10, null));
    }

    @Test
    @DisplayName("the ticker is a granularity, not a per-timer waker: many deadlines, one behaviour")
    void one_behaviour_serves_every_deadline_the_agent_arms() {
        // The reason this is a TickerBehaviour and not a WakerBehaviour per timer: a
        // WakerBehaviour deadline is wall-clock and fixed at add time, so it survives neither
        // Cybele.setPace (Gui.java:98) nor a pause.
        final List<String> fired = new ArrayList<String>();
        agentClock.scheduleAt(1000, () -> fired.add("a"));
        agentClock.scheduleAt(1000, () -> fired.add("b"));
        agentClock.scheduleAt(3000, () -> fired.add("c"));
        ClockTickerBehaviour behaviour = new ClockTickerBehaviour(new Agent(), 10, agentClock);

        clock.advanceSimTo(1000);
        behaviour.onTick();
        assertEquals(List.of("a", "b"), fired);

        clock.setPace(8.0);                 // a pace change must not move a pending deadline
        clock.advanceRealMs(250);           // 1000 + 8 * 250 = 3000 simulated
        behaviour.onTick();
        assertEquals(List.of("a", "b", "c"), fired);
        assertTrue(clock.nowMs() >= 3000);
    }
}
