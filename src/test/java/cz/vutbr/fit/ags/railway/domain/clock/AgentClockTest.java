/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 for the per-agent scheduler (#29) — the replacement for
 * {@code Activity.setTimer(RailwayMainAgent.CLOCK_ID, …)} at all four sites.
 */
class AgentClockTest {

    private final VirtualClock clock = new VirtualClock(0, 1.0);
    private final AgentClock agent = new AgentClock(clock);
    private final List<String> fired = new ArrayList<String>();

    private void record(String label) {
        fired.add(label + "@" + clock.nowMs());
    }

    @Test
    @DisplayName("scheduling an absolute instant does not drift -- this is what deletes the pauseClock bracket")
    void scheduling_an_absolute_instant_does_not_drift() {
        // Planning.java:108-112 brackets its arming with pauseClock/resumeClock. The sequence
        // it makes atomic is: read the clock (:109), turn the agreed departure into a RELATIVE
        // delay, hand that delay to setTimer (:111) -- which measures it from the KERNEL's own,
        // later, clock read. Any simulated time elapsing between the two reads pushes the
        // departure late by exactly that much, so the bracket freezes time across them.
        //
        // Below, 300 ms of simulated time elapses between the computation and the arming. The
        // relative form lands late by exactly that; the absolute form lands where it was
        // computed. There is no second clock read to drift against, so there is nothing left
        // for a bracket to protect. See docs/clock-abstraction.md 3.
        final long agreedDeparture = agent.now() + 5000;
        final long relativeDelay = agreedDeparture - agent.now();
        clock.advanceSimBy(300);                       // the drift the bracket exists to stop
        agent.scheduleIn(relativeDelay, () -> record("relative"));
        agent.scheduleAt(agreedDeparture, () -> record("absolute"));

        clock.advanceSimTo(5000);
        agent.runDue();
        assertEquals(List.of("absolute@5000"), fired, "the absolute form is exactly on time");

        clock.advanceSimTo(5300);
        agent.runDue();
        assertEquals(List.of("absolute@5000", "relative@5300"), fired,
                "the relative form is late by the 300 ms of drift, which is the defect");
    }

    @Test
    @DisplayName("scheduleIn reads the clock once, so a later drain does not move the deadline")
    void a_relative_delay_is_resolved_at_arming_time() {
        agent.scheduleIn(1000, () -> record("t"));
        clock.advanceSimTo(900);
        agent.runDue();
        assertEquals(List.of(), fired);
        clock.advanceSimTo(1000);
        agent.runDue();
        assertEquals(List.of("t@1000"), fired, "1000 from the arming instant, not from the drain");
    }

    @Test
    @DisplayName("a negative delay fires at the next drain and is not clamped -- DEF-16")
    void a_negative_delay_is_not_clamped() {
        // RoadAgent.java:102 arms a Gaussian travel delay that is negative on 2.26 % of draws
        // for a 1 s road, and Cybele fires it immediately. docs/defect-triage.md 6.2 rejected
        // clamping. The clamp that DOES exist in the 2008 source, Planning.java:111's
        // `t > 0 ? t : 0`, belongs at that call site -- not here.
        agent.scheduleIn(-1500, () -> record("instantaneous"));
        agent.runDue();
        assertEquals(List.of("instantaneous@0"), fired);
    }

    @Test
    @DisplayName("wake-ups run on the caller's thread -- JADE's one-thread-per-agent invariant")
    void wakeups_run_on_the_calling_thread() {
        // The single most important property of this design. A clock service that fired
        // callbacks on a timer thread would let two threads into one agent's fields, which
        // every ported agent assumes cannot happen.
        final Thread caller = Thread.currentThread();
        final List<Thread> ran = new ArrayList<Thread>();
        agent.scheduleAt(0, () -> ran.add(Thread.currentThread()));
        agent.runDue();
        assertEquals(1, ran.size());
        assertSame(caller, ran.get(0));
    }

    @Test
    @DisplayName("nothing fires while the clock is paused, however much real time passes")
    void a_paused_clock_fires_nothing() {
        agent.scheduleAt(100, () -> record("t"));
        clock.pause();
        clock.advanceRealMs(10000);
        agent.runDue();
        assertEquals(List.of(), fired);
        clock.resume();
        clock.advanceSimTo(100);
        agent.runDue();
        assertEquals(List.of("t@100"), fired);
    }

    @Test
    @DisplayName("a pace change does not move a pending deadline in simulated time")
    void a_pace_change_leaves_pending_deadlines_where_they_are() {
        // Cybele rescales every pending timer on Cybele.setPace. Storing the deadline in
        // simulated ms makes that free and exact: 5000 means 5000 at every pace, and the only
        // thing the change moves is how much REAL time it takes to get there.
        agent.scheduleAt(5000, () -> record("t"));
        clock.advanceRealMs(1000);            // pace 1 -> sim 1000
        clock.setPace(8.0);
        assertEquals(Long.valueOf(5000), agent.nextDueMs(), "the deadline did not move");
        clock.advanceRealMs(500);             // pace 8 -> sim 1000 + 4000 = 5000
        agent.runDue();
        assertEquals(List.of("t@5000"), fired, "same instant, reached 8x sooner in real time");
    }

    @Test
    @DisplayName("cancel disarms a pending wake-up")
    void cancel_disarms() {
        long id = agent.scheduleAt(100, () -> record("cancelled"));
        agent.scheduleAt(100, () -> record("kept"));
        assertTrue(agent.cancel(id));
        assertEquals(1, agent.pending());
        clock.advanceSimTo(100);
        agent.runDue();
        assertEquals(List.of("kept@100"), fired);
    }

    @Test
    @DisplayName("runDue reports how many fired, and leaves the rest armed")
    void run_due_reports_its_work() {
        agent.scheduleAt(100, () -> record("a"));
        agent.scheduleAt(100, () -> record("b"));
        agent.scheduleAt(900, () -> record("c"));
        clock.advanceSimTo(100);
        assertEquals(2, agent.runDue());
        assertEquals(0, agent.runDue(), "a second drain at the same instant fires nothing again");
        assertEquals(1, agent.pending());
        assertEquals(Long.valueOf(900), agent.nextDueMs());
    }

    @Test
    @DisplayName("a throwing wake-up propagates and does not consume the ones behind it")
    void a_throwing_wakeup_leaves_the_rest_armed() {
        agent.scheduleAt(100, () -> {
            throw new IllegalStateException("handler blew up");
        });
        agent.scheduleAt(100, () -> record("survivor"));
        clock.advanceSimTo(100);
        assertThrows(IllegalStateException.class, agent::runDue);
        assertEquals(1, agent.pending(), "the survivor was never popped by the failed drain");
        assertEquals(1, agent.runDue());
        assertEquals(List.of("survivor@100"), fired);
    }

    @Test
    @DisplayName("a wake-up may arm, cancel and read the clock -- no lock is held while it runs")
    void a_wakeup_may_use_the_clock_it_was_fired_by() {
        agent.scheduleAt(100, () -> {
            record("outer");
            agent.scheduleIn(50, () -> record("inner"));
        });
        clock.advanceSimTo(100);
        agent.runDue();
        assertEquals(List.of("outer@100"), fired);
        clock.advanceSimTo(150);
        agent.runDue();
        assertEquals(List.of("outer@100", "inner@150"), fired);
    }

    @Test
    @DisplayName("nextDueMs is null on an idle agent, and the clock is the shared one")
    void the_idle_state_and_the_shared_clock() {
        assertNull(agent.nextDueMs());
        assertEquals(0, agent.pending());
        assertSame(clock, agent.clock(), "every agent must read the SAME clock, as myClock was");
        assertThrows(IllegalArgumentException.class, () -> new AgentClock(null));
    }
}
