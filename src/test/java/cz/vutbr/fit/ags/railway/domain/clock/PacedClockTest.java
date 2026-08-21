/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 for the clock itself (#29) — the replacement for the kernel's {@code ContinuousClock}.
 *
 * <p>Every assertion here is exact, because real time enters only through a
 * {@link ManualNanoSource}. There is not one {@code Thread.sleep} in this file, and that is the
 * point: {@code docs/probes/ExpB*} had to be read alongside a measured startup gap before any
 * of its numbers meant anything, and this is what replaces that regime.</p>
 */
class PacedClockTest {

    private final ManualNanoSource source = new ManualNanoSource();

    private PacedClock clock(long startMs, double pace) {
        return new PacedClock(startMs, pace, source, PauseSemantics.COUNTED);
    }

    @Test
    @DisplayName("a fresh clock reads sim.clock.startMs and is RUNNING, as ContinuousClock is")
    void a_fresh_clock_starts_at_the_configured_value_and_runs() {
        PacedClock c = clock(1000, 1.0);
        assertEquals(1000, c.nowMs());
        // ContinuousClock.<init> sets paused = false. #17's startup barrier rests on this fact
        // (docs/headless-and-stop.md): had clocks started paused, the barrier's "did the pause
        // land?" probe would have passed on its first poll and been a silent no-op forever.
        assertFalse(c.isPaused());
        assertEquals(0, c.pauseDepth());
    }

    @Test
    @DisplayName("simulated time advances at the pace: 100 real ms at pace 8 is 800 simulated ms")
    void simulated_time_advances_at_the_pace() {
        PacedClock c = clock(0, 8.0);
        source.advanceMillis(100);
        assertEquals(800, c.nowMs());
        source.advanceMillis(100);
        assertEquals(1600, c.nowMs());
    }

    @Test
    @DisplayName("pace 0.3 is the toolbar's slow preset and scales down, not to zero")
    void a_fractional_pace_scales_down() {
        PacedClock c = clock(0, 0.3);
        source.advanceMillis(1000);
        assertEquals(300, c.nowMs());
    }

    @Test
    @DisplayName("pause freezes simulated time however much real time passes")
    void pause_freezes_simulated_time() {
        PacedClock c = clock(0, 1.0);
        source.advanceMillis(50);
        c.pause();
        long frozenAt = c.nowMs();
        assertEquals(50, frozenAt);
        source.advanceMillis(500);
        assertEquals(frozenAt, c.nowMs(), "a paused clock reads the same value forever");
        assertTrue(c.isPaused());
    }

    @Test
    @DisplayName("real time spent frozen is not simulated time: resume continues, it does not catch up")
    void resume_continues_rather_than_catching_up() {
        PacedClock c = clock(0, 1.0);
        source.advanceMillis(50);
        c.pause();
        source.advanceMillis(500);      // 500 real ms while frozen
        c.resume();
        source.advanceMillis(10);
        assertEquals(60, c.nowMs(), "50 before the pause + 10 after the resume; the 500 is gone");
    }

    @Test
    @DisplayName("COUNTED: two pauses need two resumes -- the divergence from the kernel")
    void counted_pauses_nest() {
        PacedClock c = clock(0, 1.0);
        c.pause();
        c.pause();
        assertEquals(2, c.pauseDepth());
        c.resume();
        assertTrue(c.isPaused(), "one resume must not undo two pauses");
        source.advanceMillis(100);
        assertEquals(0, c.nowMs());
        c.resume();
        assertFalse(c.isPaused());
        source.advanceMillis(100);
        assertEquals(100, c.nowMs());
    }

    @Test
    @DisplayName("BOOLEAN_2008: two pauses and ONE resume leaves the clock running -- SEM-02, pinned")
    void the_kernels_boolean_pause_is_reproducible() {
        // ContinuousClock.setPause() opens `if (paused) return;` and setResume() opens
        // `if (!paused) return;` -- one field, no counter. Measured by docs/probes/ExpB2.java
        // (2 pauses, 1 resume, one thread) and by ExpI.java (two agents, overlapping brackets).
        // docs/defect-triage.md requires a port to be ABLE to reproduce a 2008 behaviour even
        // where it chooses not to; this is that capability, asserted rather than claimed.
        PacedClock c = new PacedClock(0, 1.0, source, PauseSemantics.BOOLEAN_2008);
        c.pause();
        c.pause();
        assertEquals(1, c.pauseDepth(), "no depth counter exists in this mode");
        c.resume();
        assertFalse(c.isPaused(), "one resume undoes any number of pauses");
        source.advanceMillis(100);
        assertEquals(100, c.nowMs());
    }

    @Test
    @DisplayName("resuming a running clock is a no-op, as the kernel's `if (!paused) return;` is")
    void resuming_a_running_clock_does_nothing() {
        PacedClock c = clock(0, 1.0);
        c.resume();
        c.resume();
        assertEquals(0, c.pauseDepth(), "resume must not drive the depth negative");
        c.pause();
        assertTrue(c.isPaused(), "one pause after two stray resumes must still freeze it");
    }

    @Test
    @DisplayName("a pace change rebases: elapsed time keeps the old pace, the rest takes the new one")
    void a_pace_change_rebases_rather_than_reinterpreting_history() {
        PacedClock c = clock(0, 1.0);
        source.advanceMillis(100);
        assertEquals(100, c.nowMs());
        c.setPace(8.0);
        assertEquals(100, c.nowMs(), "the change itself must not move simulated time");
        source.advanceMillis(100);
        assertEquals(900, c.nowMs(), "100 at pace 1 plus 100 at pace 8");
    }

    @Test
    @DisplayName("nowMs is monotone across pause, resume and every pace change")
    void now_is_monotone_across_every_control_operation() {
        PacedClock c = clock(0, 1.0);
        long previous = c.nowMs();
        double[] paces = {8.0, 0.3, 1.0, 40.0, 0.001};
        for (double pace : paces) {
            c.setPace(pace);
            previous = assertNotBefore(previous, c.nowMs());
            source.advanceMillis(7);
            previous = assertNotBefore(previous, c.nowMs());
            c.pause();
            previous = assertNotBefore(previous, c.nowMs());
            source.advanceMillis(13);
            previous = assertNotBefore(previous, c.nowMs());
            c.resume();
            previous = assertNotBefore(previous, c.nowMs());
        }
    }

    @Test
    @DisplayName("pace must be positive and finite -- 0 is rejected, because pause already means that")
    void an_unusable_pace_is_rejected() {
        PacedClock c = clock(0, 1.0);
        // A pace of 0 would be a second representation of "stopped", and would make isPaused()
        // a lie. SimClock#setPace says so; this is the enforcement.
        assertThrows(IllegalArgumentException.class, () -> c.setPace(0.0));
        assertThrows(IllegalArgumentException.class, () -> c.setPace(-1.0));
        assertThrows(IllegalArgumentException.class, () -> c.setPace(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> c.setPace(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new PacedClock(0, 0.0));
        assertEquals(1.0, c.pace(), "a rejected change must leave the pace alone");
    }

    @Test
    @DisplayName("the production constructor uses System.nanoTime and starts running")
    void the_production_constructor_is_wired_to_the_system_clock() {
        // The one test that touches real time, and it asserts nothing about durations -- only
        // that the default wiring exists and does not read backwards.
        PacedClock c = new PacedClock(4200, 1.0);
        assertFalse(c.isPaused());
        long first = c.nowMs();
        long second = c.nowMs();
        assertTrue(first >= 4200, "starts at the configured origin");
        assertTrue(second >= first, "System.nanoTime is monotone and so is this");
    }

    private static long assertNotBefore(long previous, long now) {
        assertTrue(now >= previous, "clock went backwards: " + previous + " -> " + now);
        return now;
    }
}
