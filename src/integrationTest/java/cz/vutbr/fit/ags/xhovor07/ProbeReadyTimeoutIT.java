/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second of #37's three "cannot be tested at L1" items: {@code TraceProbe.awaitReady()}'s
 * <b>failure</b> branch (#38).
 *
 * <h2>Why this needs a lane and not just a test</h2>
 *
 * The branch fires when the probe never registered — no {@code TopicManagementService} in the
 * container profile, or the agent never started — and it is the difference between a run that fails
 * and a run that produces an EMPTY trace with a green exit status, which in record mode would be
 * frozen into a golden. Two things make it unreachable from the {@code test} lane:
 * <ul>
 * <li>{@code TraceProbe.READY} is a {@code static CountDownLatch} with <b>no reset</b>. Once any
 *     probe anywhere in the JVM has registered, the latch is open for the rest of the process, and
 *     {@code awaitReady} returns immediately forever. The {@code test} lane runs 321 tests in one
 *     JVM ({@code forkEvery = 0}); this lane forks one per class ({@code forkEvery = 1}), so this
 *     class gets the latch closed.</li>
 * <li>The wait is {@code READY_TIMEOUT_MS} = one minute. That is the right production value and the
 *     wrong test value, so #38 added the narrowest seam that fixes it: a package-private
 *     {@code awaitReady(long)} overload that the public no-arg method delegates to. No reset hook
 *     was added — a {@code resetForTests()} would let a production caller re-arm a barrier meant to
 *     be crossed once, which is a wider hole than a caller choosing its own bound.</li>
 * </ul>
 *
 * <h2>This class must stay alone in its JVM, and it fails loudly if it does not</h2>
 *
 * It starts no probe and boots no container, so nothing here can open the latch. If
 * {@code forkEvery} were changed and another class's probe shared this JVM, {@code awaitReady}
 * would return normally and {@code assertThrows} would <b>fail</b> — the safe direction. It does
 * not silently pass.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ProbeReadyTimeoutIT {

    private static final long SHORT_BOUND_MS = 200L;

    @Test
    @DisplayName("a run whose probe never registered is refused, not started with an empty trace")
    void await_ready_refuses_when_the_probe_never_registered() {
        final long startedNanos = System.nanoTime();

        final IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> TraceProbe.awaitReady(SHORT_BOUND_MS),
                        "awaitReady must refuse to release the run. If this passed silently, the"
                                + " probe's registration barrier is not a barrier -- and a"
                                + " sim.trace.enabled=true run with no trace would look like a"
                                + " short, clean one.");

        final long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        assertTrue(waitedMs >= SHORT_BOUND_MS,
                "it must actually WAIT on the latch for the full bound before giving up -- it"
                        + " waited " + waitedMs + " ms of " + SHORT_BOUND_MS + ". A method that"
                        + " threw unconditionally would also satisfy assertThrows.");

        final String message = thrown.getMessage();
        assertTrue(message.contains("did not register within " + SHORT_BOUND_MS + " ms"),
                "the bound must be the one that was asked for, so the overload cannot silently"
                        + " ignore it: " + message);
        assertTrue(message.contains("TopicManagementService"),
                "and the message must name the usual cause, which is the one thing the operator"
                        + " can fix: " + message);
        assertTrue(message.contains(ScenarioConfig.KEY_TRACE_ENABLED),
                "and the setting that makes an empty trace a failed run: " + message);
    }
}
