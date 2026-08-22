/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real {@link TraceProbe}, with its output seam pointed at a queue instead of {@code System.out}
 * (#38).
 * <p>
 * <b>Everything above {@code emit(String)} is production code and runs unmodified</b> — in
 * particular {@code setup()}, which is the method #37 named as untestable at L1 because it needs a
 * {@code TopicManagementHelper} and therefore a container. Overriding the one {@code protected}
 * output method is the seam the class already provides for exactly this
 * ({@code TraceProbeTest.RecordingProbe} does the same thing at L1, without the container).
 */
final class RecordingProbe extends TraceProbe {

    private static final long serialVersionUID = 1L;

    private final transient BlockingQueue<String> lines = new LinkedBlockingQueue<String>();

    /**
     * Crossed once {@code TraceProbe.setup()} has returned — i.e. after the fifteen
     * {@code helper.register} calls.
     * <p>
     * <b>Why this and not {@code TraceProbe.awaitReady()}.</b> {@code TraceProbe.READY} is a static
     * latch that is counted down once per JVM and never reset, so the <em>second</em> probe started
     * in one JVM would find it already open and {@code awaitReady} would return before that probe
     * had registered anything — a race, and the kind that passes nineteen times out of twenty. This
     * latch belongs to the instance. {@code awaitReady()} itself is exercised where it is
     * meaningful: once, in {@code LifecycleIT}, and at its failure branch in
     * {@code ProbeReadyTimeoutIT}.
     * <p>
     * It is counted down even if {@code setup()} bailed out (no {@code TopicManagementService}, a
     * registration refused). That is deliberate: the barrier's job here is "setup has finished",
     * and a probe that finished by failing must not make the test hang — it must make it fail on
     * the assertion about what was observed.
     */
    private final transient CountDownLatch registered = new CountDownLatch(1);

    @Override
    protected void setup() {
        super.setup();
        registered.countDown();
    }

    @Override
    protected void emit(String line) {
        lines.add(line);
    }

    /**
     * Wait until this probe's {@code setup()} has run.
     *
     * @param timeoutMs bound
     * @throws InterruptedException if interrupted
     */
    void awaitRegistered(long timeoutMs) throws InterruptedException {
        assertTrue(registered.await(timeoutMs, TimeUnit.MILLISECONDS),
                "the probe did not finish setup() within " + timeoutMs + " ms");
    }

    /**
     * @param timeoutMs bound
     * @return the next rendered trace line, or {@code null}
     * @throws InterruptedException if interrupted
     */
    String poll(long timeoutMs) throws InterruptedException {
        return lines.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Assert the probe observed nothing. Bounded, so the window is real.
     *
     * @param timeoutMs how long silence must hold
     * @throws InterruptedException if interrupted
     */
    void expectSilence(long timeoutMs) throws InterruptedException {
        final String stray = poll(timeoutMs);
        assertNull(stray, () -> "the probe observed a message it should not have seen: " + stray);
    }

    /**
     * Field 3 (the event) of the next {@code count} lines, waiting for each with a bounded poll.
     *
     * @param count how many lines are expected
     * @param timeoutMs bound per line
     * @return the events, oldest first
     * @throws InterruptedException if interrupted
     */
    List<String> expectEvents(int count, long timeoutMs) throws InterruptedException {
        final List<String> events = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            final int seen = i;
            final String line = poll(timeoutMs);
            assertNotNull(line, () -> "the probe rendered only " + seen + " of " + count
                    + " expected trace lines within " + timeoutMs + " ms; got " + events);
            events.add(line.split("\\|", -1)[2]);
        }
        return events;
    }
}
