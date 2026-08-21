/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
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

    // ExpI's four parameters, as constants, so the expected advance below is DERIVED from the
    // experiment's geometry rather than fed in as the literal 190. An earlier revision asserted
    // the literal, which discriminates between the two semantics perfectly well but lets a
    // reader believe ExpI's arithmetic is re-checked here when it was not.
    /** ExpI.HOLD_MS -- how long agent A stays inside its bracket. */
    private static final long HOLD_MS = 300;
    /** ExpI.SETTLE_MS -- A's settle window for the asynchronous pause, before it reads t0. */
    private static final long SETTLE_MS = 10;
    /** ExpI.OVERLAP_AT_MS -- how far into A's bracket agent B runs its own. */
    private static final long OVERLAP_AT_MS = 100;
    /** ExpI.B_BRACKET_MS -- agent B's bracket length. */
    private static final long B_BRACKET_MS = 20;

    /**
     * How much simulated time A sees pass inside its own bracket if pause is not counted: the
     * clock is frozen from A's pause until B's resume, and runs from there to A's resume.
     * With ExpI's numbers this is 300 + 10 - 100 - 20 = 190, and ExpI measured 188-191, 60/60.
     */
    private static final long EXPECTED_ADVANCE_IF_UNCOUNTED =
            HOLD_MS + SETTLE_MS - OVERLAP_AT_MS - B_BRACKET_MS;

    /** How long A's bracket lasts from the instant it takes its first reading. */
    private static final long A_BRACKET_AFTER_FIRST_READING = HOLD_MS + SETTLE_MS;

    /** ExpI's overlap arm, reproduced from ExpI's four constants. */
    @Test
    @DisplayName("BOOLEAN_2008: another agent's resume unfreezes the clock inside my bracket -- ExpI, pinned")
    void the_kernels_semantics_let_a_second_agent_resume_early() {
        VirtualClock clock = new VirtualClock(0, 1.0, PauseSemantics.BOOLEAN_2008);

        clock.pause();                                       // agent A enters its bracket
        clock.advanceRealMs(SETTLE_MS);                      // A's settle window, clock frozen
        long t0 = clock.nowMs();                             // A's first reading

        clock.advanceRealMs(OVERLAP_AT_MS - SETTLE_MS);      // A holds; B has not arrived yet
        clock.pause();                                       // agent B enters its own bracket
        clock.advanceRealMs(B_BRACKET_MS);
        clock.resume();                                      // B leaves -- the clock is RUNNING

        clock.advanceRealMs(A_BRACKET_AFTER_FIRST_READING - OVERLAP_AT_MS - B_BRACKET_MS);
        long t1 = clock.nowMs();
        clock.resume();                                      // agent A leaves its bracket

        assertEquals(EXPECTED_ADVANCE_IF_UNCOUNTED, t1 - t0,
                "ExpI's overlap arm measured 188-191 ms here, 60/60 over three runs");
        assertFalse(clock.isPaused());
    }

    @Test
    @DisplayName("ExpI's control arm: A alone, and the clock stays frozen for the whole bracket")
    void the_control_arm_stays_frozen() {
        VirtualClock clock = new VirtualClock(0, 1.0, PauseSemantics.BOOLEAN_2008);
        clock.pause();
        clock.advanceRealMs(SETTLE_MS);
        long t0 = clock.nowMs();
        clock.advanceRealMs(A_BRACKET_AFTER_FIRST_READING);
        long t1 = clock.nowMs();
        clock.resume();
        assertEquals(0, t1 - t0, "ExpI's control arm measured 0 ms, 60/60");
    }

    @Test
    @DisplayName("COUNTED: the same script leaves the clock frozen -- the divergence, asserted")
    void counting_closes_the_window() {
        // Byte-for-byte the overlap arm above, with one constructor argument changed. That is
        // what makes it a discriminating test rather than two unrelated scripts.
        VirtualClock clock = new VirtualClock(0, 1.0, PauseSemantics.COUNTED);

        clock.pause();
        clock.advanceRealMs(SETTLE_MS);
        long t0 = clock.nowMs();

        clock.advanceRealMs(OVERLAP_AT_MS - SETTLE_MS);
        clock.pause();
        clock.advanceRealMs(B_BRACKET_MS);
        clock.resume();
        assertTrue(clock.isPaused(), "B's resume must not undo A's pause");

        clock.advanceRealMs(A_BRACKET_AFTER_FIRST_READING - OVERLAP_AT_MS - B_BRACKET_MS);
        long t1 = clock.nowMs();
        clock.resume();

        assertEquals(0, t1 - t0);
        assertFalse(clock.isPaused());
    }

    @Test
    @DisplayName("a freeze is not inert: it defers every clock-derived event for the bracket's duration")
    void a_bracket_defers_every_pending_wakeup_by_its_duration() {
        // The half of the story "excludes nothing" would lose. All four Activity.setTimer sites
        // are on the ONE global clock, so a bracket anywhere stops every pending wake-up
        // everywhere -- deferred, not dropped, and all of them shift TOGETHER so their relative
        // order survives. That is why deleting the brackets is safe: #21's normalizer projects
        // the tick, and the order is unchanged.
        VirtualClock clock = new VirtualClock(0, 1.0, PauseSemantics.COUNTED);
        AgentClock other = new AgentClock(clock);
        List<String> fired = new ArrayList<String>();
        other.scheduleAt(100, () -> fired.add("first"));
        other.scheduleAt(150, () -> fired.add("second"));

        clock.pause();                       // some OTHER agent's bracket
        clock.advanceRealMs(500);            // half a second of real time inside it
        other.runDue();
        assertEquals(List.of(), fired, "no timer anywhere in the simulation can fire");

        clock.resume();
        clock.advanceRealMs(100);
        other.runDue();
        assertEquals(List.of("first"), fired, "deferred by the bracket, not dropped");
        clock.advanceRealMs(50);
        other.runDue();
        assertEquals(List.of("first", "second"), fired, "and in the same relative order");
    }

    /**
     * <b>A requirement on this design, not a discovery about it.</b>
     *
     * <p>ExpI's verdict 2 measured that Cybele's {@code pauseClock} blocks no thread. The
     * obvious "fix" once that is known — make {@code SimClock.pause()} acquire a real lock, so
     * that the bracket means what its author thought — is the thing this test forbids. Under
     * JADE every agent runs on its own thread and they share one clock; a blocking pause on a
     * shared clock, held across agent code, is a deadlock waiting for two agents to bracket in
     * the wrong order. So {@code pause()} must never block, and a future refactor that makes it
     * block must fail here rather than in a wedged platform.</p>
     *
     * <p>It is deliberately <em>not</em> offered as evidence that Cybele behaves this way —
     * {@code docs/probes/ExpI.java} is that evidence, against the real jars.</p>
     */
    @Test
    @DisplayName("pause() must never block another thread -- a blocking pause would deadlock the port")
    void pausing_the_clock_must_not_block_another_thread() throws Exception {
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
