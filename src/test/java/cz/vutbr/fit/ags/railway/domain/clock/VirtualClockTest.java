/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 for the virtual clock (#29) — the determinism the JADE port gains and Cybele can never
 * have.
 *
 * <p>Cybele's clock is driven by a kernel thread inside a source-less jar, so a Cybele-side
 * timing test is a sleep-and-hope: {@code docs/probes/README.md} spends three caveats on
 * exactly that. Here the test owns time.</p>
 */
class VirtualClockTest {

    @Test
    @DisplayName("advanceSimBy moves simulated time by exactly that much, whatever the pace")
    void advancing_simulated_time_is_exact() {
        VirtualClock c = new VirtualClock(0, 8.0);
        c.advanceSimBy(8500);
        assertEquals(8500, c.nowMs(), "the pace does not enter: the test asked for 8500");
        c.advanceSimTo(20000);
        assertEquals(20000, c.nowMs());
    }

    @Test
    @DisplayName("advanceRealMs goes through the pace, so pace behaviour is exercised too")
    void advancing_real_time_goes_through_the_pace() {
        VirtualClock c = new VirtualClock(0, 8.0);
        c.advanceRealMs(100);
        assertEquals(800, c.nowMs());
        c.setPace(0.3);
        c.advanceRealMs(1000);
        assertEquals(1100, c.nowMs(), "800 at pace 8 plus 300 at pace 0.3");
    }

    @Test
    @DisplayName("a paused virtual clock cannot be nudged forward -- the pause tests must stay honest")
    void simulated_time_cannot_be_advanced_while_paused() {
        VirtualClock c = new VirtualClock(0, 1.0);
        c.pause();
        assertThrows(IllegalStateException.class, () -> c.advanceSimBy(100));
        assertThrows(IllegalStateException.class, () -> c.advanceSimTo(100));
        // Real time is different: it MUST be allowed to pass, because "the clock stays put
        // while real time runs" is the property a pause test has to demonstrate.
        c.advanceRealMs(100);
        assertEquals(0, c.nowMs());
        c.resume();
        c.advanceSimBy(100);
        assertEquals(100, c.nowMs());
    }

    @Test
    @DisplayName("simulated time is monotone: a test cannot rewind it")
    void time_cannot_be_rewound() {
        VirtualClock c = new VirtualClock(500, 1.0);
        assertThrows(IllegalArgumentException.class, () -> c.advanceSimTo(499));
        assertThrows(IllegalArgumentException.class, () -> c.advanceSimBy(-1));
        assertThrows(IllegalArgumentException.class, () -> c.advanceRealMs(-1));
        assertEquals(500, c.nowMs());
    }

    @Test
    @DisplayName("two identical drives produce identical timelines -- no wall clock, no flake")
    void the_same_script_produces_the_same_timeline_every_time() {
        // This is the property #38's L2 integration tests are built on: an assertion about
        // WHEN something happened is exact, not "within 50 ms if the machine is not busy".
        assertEquals(drive(), drive());
    }

    private static String drive() {
        VirtualClock c = new VirtualClock(0, 1.0);
        AgentClock agent = new AgentClock(c);
        StringBuilder log = new StringBuilder();
        agent.scheduleAt(8500, () -> log.append("vl0@").append(c.nowMs()).append(' '));
        agent.scheduleAt(17000, () -> log.append("vl1@").append(c.nowMs()).append(' '));
        agent.scheduleAt(12000, () -> log.append("vl2@").append(c.nowMs()).append(' '));
        for (int step = 0; step < 4; step++) {
            c.advanceSimBy(5000);
            agent.runDue();
        }
        return log.toString();
    }

    @Test
    @DisplayName("pause and pace behave exactly as on a PacedClock -- same code, not a stub")
    void the_control_surface_is_the_production_one() {
        VirtualClock c = new VirtualClock(0, 1.0, PauseSemantics.BOOLEAN_2008);
        c.pause();
        c.pause();
        c.resume();
        c.advanceRealMs(100);
        assertEquals(100, c.nowMs(), "BOOLEAN_2008 resumes on the first resume, here as there");
    }
}
