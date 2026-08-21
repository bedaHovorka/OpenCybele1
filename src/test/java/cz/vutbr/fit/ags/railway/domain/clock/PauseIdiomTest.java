/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The {@code pauseClock}/{@code resumeClock} idiom, as measured — pinned in code so the port
 * cannot quietly assume something else (#29).
 *
 * <h2>What was measured</h2>
 * {@code docs/probes/ExpI.java} runs two <em>distinct Cybele agents</em> against the real
 * {@code CybeleImpl.jar}, each bracketing work with {@code pauseClock}/{@code resumeClock} on
 * its own handler thread — the application's shape, where {@code Planning.java:108-112} runs on
 * the main agent's activity thread and {@code RoadAgent.java:132-134,167-169} run on each road
 * agent's. Agent B's bracket is placed inside agent A's on purpose:
 *
 * <pre>
 * control arm (agent A alone)        0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
 * overlap arm (A, with B inside it)  190 190 190 189 190 190 190 190 190 189 ...
 * VERDICT 1 (early resume): 20/20 overlap trials saw the clock advance inside A's bracket
 * VERDICT 1 control:        0/20
 * VERDICT 2 (exclusion):    B's body ran while A held the bracket: true
 * </pre>
 * 60/60 across three consecutive runs, {@code startUp -> createClock} gap 20 ms against a
 * ~3.4 ms registration race window.
 *
 * <h2>What it means</h2>
 * <ol>
 *   <li>#29's hypothesis is <b>confirmed</b>: agent B's resume restarts the clock while agent A
 *       still believes it is frozen. The window A thought it had is 190 of its 300 ms.</li>
 *   <li>And the larger finding, which changes the conclusion rather than supporting it:
 *       <b>the idiom is not a mutex and never was.</b> Pausing a clock stops simulated
 *       <em>time</em>, not <em>threads</em> — B's body ran to completion inside A's bracket.
 *       So "replace it with a real lock" answers a question the code never asked. The register
 *       of "locks that cannot fail" gains a seventh entry; see
 *       {@code docs/clock-abstraction.md} §2.</li>
 * </ol>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PauseIdiomTest {

    /** ExpI's overlap arm, reproduced exactly, at ExpI's numbers. */
    @Test
    @DisplayName("BOOLEAN_2008: another agent's resume unfreezes the clock inside my bracket -- ExpI, pinned")
    void the_kernels_semantics_let_a_second_agent_resume_early() {
        VirtualClock clock = new VirtualClock(0, 1.0, PauseSemantics.BOOLEAN_2008);

        clock.pause();                       // agent A enters its bracket
        long t0 = clock.nowMs();

        clock.pause();                       // agent B enters its own, 100 ms in
        clock.resume();                      // and leaves it 20 ms later -- clock now RUNNING

        clock.advanceRealMs(190);            // the remainder of A's 300 ms hold, at pace 1
        long t1 = clock.nowMs();
        clock.resume();                      // agent A leaves its bracket

        assertEquals(190, t1 - t0, "ExpI's overlap arm measured 189-191 ms here, 20/20");
        assertFalse(clock.isPaused());
    }

    @Test
    @DisplayName("ExpI's control arm: A alone, and the clock stays frozen for the whole bracket")
    void the_control_arm_stays_frozen() {
        VirtualClock clock = new VirtualClock(0, 1.0, PauseSemantics.BOOLEAN_2008);
        clock.pause();
        long t0 = clock.nowMs();
        clock.advanceRealMs(310);
        long t1 = clock.nowMs();
        clock.resume();
        assertEquals(0, t1 - t0, "ExpI's control arm measured 0 ms, 20/20");
    }

    @Test
    @DisplayName("COUNTED: the same overlap leaves the clock frozen -- the divergence, asserted")
    void counting_closes_the_window() {
        VirtualClock clock = new VirtualClock(0, 1.0, PauseSemantics.COUNTED);
        clock.pause();
        long t0 = clock.nowMs();
        clock.pause();
        clock.resume();
        assertTrue(clock.isPaused(), "B's resume must not undo A's pause");
        clock.advanceRealMs(190);
        long t1 = clock.nowMs();
        clock.resume();
        assertEquals(0, t1 - t0);
        assertFalse(clock.isPaused());
    }

    /**
     * ExpI's VERDICT 2, which is the finding that decided the ticket. It is not about counting
     * at all: whatever the semantics, {@link SimClock#pause()} does not block another thread,
     * so a bracket around a critical section excludes nobody from it.
     */
    @Test
    @DisplayName("a bracket excludes nothing: another thread runs its whole body inside mine")
    void pausing_the_clock_does_not_exclude_another_thread() throws Exception {
        final VirtualClock clock = new VirtualClock(0, 1.0, PauseSemantics.COUNTED);
        final CountDownLatch aHoldsTheBracket = new CountDownLatch(1);
        final CountDownLatch bFinished = new CountDownLatch(1);
        final AtomicBoolean bRanWhileAHeldIt = new AtomicBoolean(false);
        final AtomicBoolean aStillHolding = new AtomicBoolean(false);

        Thread b = new Thread(() -> {
            try {
                aHoldsTheBracket.await(20, TimeUnit.SECONDS);
                bRanWhileAHeldIt.set(aStillHolding.get());
                clock.pause();               // would BLOCK here if this were a real lock
                clock.resume();
                bFinished.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "agent-B");
        b.start();

        clock.pause();                       // agent A enters its bracket and stays in it
        aStillHolding.set(true);
        aHoldsTheBracket.countDown();
        assertTrue(bFinished.await(20, TimeUnit.SECONDS),
                "agent B completed its whole bracket while agent A held one -- no exclusion");
        aStillHolding.set(false);
        clock.resume();
        b.join();

        assertTrue(bRanWhileAHeldIt.get());
        assertFalse(clock.isPaused(), "and the depths still balance");
    }
}
