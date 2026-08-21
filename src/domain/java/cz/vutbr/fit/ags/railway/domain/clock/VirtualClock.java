/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 *
 * Open Cybele 1
 *
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */
package cz.vutbr.fit.ags.railway.domain.clock;

/**
 * A {@link SimClock} that advances only when a test says so — the capability the port gains
 * that the original could never have (#29, input to #38 and to #41's comparison log).
 *
 * <h2>Why it matters</h2>
 * Cybele's clock lives inside a source-less kernel jar and is driven by a kernel thread. There
 * is no seam: a test of "does a train appear 8500 simulated ms after startup" has to boot the
 * kernel, sleep in real time, and hope — which is exactly why the parity harness judges runs by
 * captured output and why every {@code docs/probes/ExpB*} result needs a measured startup gap
 * beside it before it means anything. A JADE L2 integration test built on this class instead
 * says {@code clock.advanceSimBy(8500)} and asserts, with no wall-clock dependence, no sleep,
 * no flake, and no machine-speed sensitivity.
 *
 * <h2>Contract</h2>
 * It is a {@link PacedClock} whose real-time reference is a {@link ManualNanoSource}, so pause,
 * resume and pace behave <em>identically</em> to production — the same code, exercised by the
 * same tests. Two ways to move it:
 * <ul>
 *   <li>{@link #advanceRealMs(long)} — moves the underlying real-time reference, so the pace
 *       applies. Use this to test that pace scaling is right.</li>
 *   <li>{@link #advanceSimBy(long)} / {@link #advanceSimTo(long)} — moves simulated time by
 *       exactly the requested amount, whatever the pace. Use this for everything else; it is
 *       what makes a deadline assertion exact rather than approximate.</li>
 * </ul>
 *
 * <p>Advancing simulated time while the clock is <b>paused</b> throws. A frozen clock that a
 * test can nudge forward would silently defeat the pause tests, which are the ones this class
 * exists to make trustworthy.</p>
 *
 * <p>Not thread-safe in the sense of being useful concurrently — it is safe (everything is
 * synchronised through {@link PacedClock}), but a virtual clock driven from two threads has no
 * meaning. Drive it from the test thread.</p>
 */
public final class VirtualClock extends PacedClock {

    private final ManualNanoSource manual;

    /** A virtual clock at simulated time 0, pace 1, {@link PauseSemantics#COUNTED}. */
    public VirtualClock() {
        this(0L, 1.0, PauseSemantics.COUNTED);
    }

    /**
     * @param startMs initial simulated time
     * @param pace initial pace; only observable through {@link #advanceRealMs(long)}
     */
    public VirtualClock(long startMs, double pace) {
        this(startMs, pace, PauseSemantics.COUNTED);
    }

    /**
     * @param startMs initial simulated time
     * @param pace initial pace
     * @param semantics how nested pauses compose; {@link PauseSemantics#BOOLEAN_2008} makes
     *     this clock reproduce the kernel's early-resume behaviour, which is how
     *     {@code SimClockPauseIdiomTest} pins {@code docs/probes/ExpI.java}'s finding without
     *     booting Cybele
     */
    public VirtualClock(long startMs, double pace, PauseSemantics semantics) {
        this(startMs, pace, semantics, new ManualNanoSource());
    }

    private VirtualClock(long startMs, double pace, PauseSemantics semantics,
            ManualNanoSource manual) {
        super(startMs, pace, manual, semantics);
        this.manual = manual;
    }

    /**
     * Advances the real-time reference. Simulated time moves by {@code realMs * pace} — or not
     * at all, if the clock is paused, which is the whole point of a pause.
     *
     * @param realMs real milliseconds to advance by; must not be negative
     */
    public void advanceRealMs(long realMs) {
        manual.advanceMillis(realMs);
    }

    /**
     * Advances simulated time by exactly {@code simMs}, whatever the pace.
     *
     * @param simMs simulated milliseconds; must not be negative — {@link SimClock#nowMs()} is
     *     monotone and a test must not be able to break that
     * @throws IllegalStateException if the clock is paused
     */
    public void advanceSimBy(long simMs) {
        if (simMs < 0) {
            throw new IllegalArgumentException("simulated time is monotone; cannot advance by "
                    + simMs);
        }
        advanceSimTo(Math.addExact(nowMs(), simMs));
    }

    /**
     * Advances simulated time to exactly {@code simMs}.
     *
     * @param simMs the target simulated instant; must not be before {@link SimClock#nowMs()}
     * @throws IllegalStateException if the clock is paused
     */
    public synchronized void advanceSimTo(long simMs) {
        if (isPaused()) {
            throw new IllegalStateException(
                    "clock is paused (depth " + pauseDepth() + "); simulated time cannot advance."
                    + " Resume it, or use advanceRealMs to prove the pause holds.");
        }
        final long now = nowMs();
        if (simMs < now) {
            throw new IllegalArgumentException("simulated time is monotone; cannot go from "
                    + now + " back to " + simMs);
        }
        rebaseTo(simMs);
    }
}
