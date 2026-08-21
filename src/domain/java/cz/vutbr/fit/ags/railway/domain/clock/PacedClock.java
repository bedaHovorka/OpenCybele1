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
 * Simulated time derived from real time at an adjustable pace — the replacement for the kernel's
 * {@code com.iai.cybele.timer.ContinuousClock} (#29).
 *
 * <h2>The state</h2>
 * Three fields and a depth counter, all under this object's monitor:
 * <pre>
 * nowMs() = epochSimMs                                        while paused
 *         = epochSimMs + (nanos() - epochNanos) * pace / 1e6  while running
 * </pre>
 * Every operation that would make that formula discontinuous — {@link #pause()},
 * {@link #resume()}, {@link #setPace(double)} — first <em>rebases</em>: it folds the elapsed
 * simulated time into {@code epochSimMs} and restarts the real-time measurement. So
 * {@link #nowMs()} is continuous and monotone across all three, by construction rather than by
 * inspection.
 *
 * <h2>Why a rebase and not a stored real-time deadline</h2>
 * The kernel stores timer expiries in real time and has to walk and rescale every pending one
 * when the pace changes. Nothing here has to: {@link AgentClock} keeps deadlines in
 * <em>simulated</em> milliseconds, which a pace change does not move. That is the same
 * observable semantics — "fires when simulated time reaches T" — with no rescale pass, no
 * rounding drift accumulating over repeated pace changes, and no lock taken outside this
 * object.
 *
 * <h2>Threading</h2>
 * Safe for concurrent use. Readers are every agent thread; writers are the pace control and
 * whichever agent brackets a section. The monitor is held only for arithmetic — never across a
 * callback, and this class never calls out to anything.
 *
 * <h2>What it does not reproduce</h2>
 * The kernel's clock control is <b>asynchronous</b> ({@code pauseClock} is a {@code sendAll} to
 * the {@code TimerAgent}, sub-2 ms) and its {@code createClock} sits in a ~3.4 ms registration
 * race that can leave clock control a permanent silent no-op (SEM-02, {@code docs/probes/}
 * README caveat 1, issue #17). Neither is reproduced, and neither is behaviour worth
 * reproducing: the latency applies equally to pause and resume so it only time-shifts a bracket
 * rather than changing its duration, and the race is a startup defect that #17 spent a barrier
 * and an exit code defending against. A {@code SimClock} is an object in the same JVM — there
 * is no announcement to lose.
 */
public class PacedClock implements SimClock {

    private final NanoSource source;
    private final PauseSemantics semantics;

    /** Simulated time at the last rebase. */
    private long epochSimMs;
    /** The real-time reading at the last rebase. */
    private long epochNanos;
    /** Simulated ms per real ms. Strictly positive. */
    private double pace;
    /** Outstanding pauses. {@code 0} means running. Never exceeds 1 under {@code BOOLEAN_2008}. */
    private int pauseDepth;

    /**
     * A clock on {@link NanoSource#system()} with {@link PauseSemantics#COUNTED} — the
     * production configuration.
     *
     * @param startMs initial simulated time, i.e. {@code sim.clock.startMs}
     * @param pace initial pace, i.e. {@code sim.clock.pace}; must be positive and finite
     */
    public PacedClock(long startMs, double pace) {
        this(startMs, pace, NanoSource.system(), PauseSemantics.COUNTED);
    }

    /**
     * @param startMs initial simulated time
     * @param pace initial pace; must be positive and finite
     * @param source the real-time reference; see {@link NanoSource}
     * @param semantics how nested pauses compose; see {@link PauseSemantics}
     */
    public PacedClock(long startMs, double pace, NanoSource source, PauseSemantics semantics) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (semantics == null) {
            throw new IllegalArgumentException("semantics must not be null");
        }
        requireUsablePace(pace);
        this.source = source;
        this.semantics = semantics;
        this.epochSimMs = startMs;
        this.epochNanos = source.nanos();
        this.pace = pace;
        // ContinuousClock.<init> sets paused = false: a Cybele clock is created RUNNING, and
        // #17's startup barrier rests on that fact (docs/headless-and-stop.md). Same here.
        this.pauseDepth = 0;
    }

    @Override
    public final synchronized long nowMs() {
        if (pauseDepth > 0) {
            return epochSimMs;
        }
        final long elapsedNanos = source.nanos() - epochNanos;
        // elapsedNanos is non-negative (NanoSource is monotone), so the cast floors rather than
        // truncating towards zero, and nowMs() cannot go backwards between two calls.
        return epochSimMs + (long) (elapsedNanos * pace / 1000000.0);
    }

    @Override
    public final synchronized void pause() {
        if (pauseDepth == 0) {
            epochSimMs = nowMs();          // fold elapsed simulated time in before freezing
            epochNanos = source.nanos();
            pauseDepth = 1;
        } else if (semantics == PauseSemantics.COUNTED) {
            pauseDepth++;
        }
        // BOOLEAN_2008 and already paused: the kernel's `if (paused) return;`. Nothing to do.
    }

    @Override
    public final synchronized void resume() {
        if (pauseDepth == 0) {
            return;                        // the kernel's `if (!paused) return;`
        }
        if (semantics == PauseSemantics.BOOLEAN_2008) {
            pauseDepth = 0;                // one resume undoes any number of pauses
        } else {
            pauseDepth--;
        }
        if (pauseDepth == 0) {
            epochNanos = source.nanos();   // real time spent frozen is not simulated time
        }
    }

    @Override
    public final synchronized boolean isPaused() {
        return pauseDepth > 0;
    }

    /**
     * @return how many pauses are outstanding. Diagnostic — a bracket that leaks a pause shows
     *     up here as a depth that never returns to zero, which is the failure mode
     *     {@code ContinuousClock.getInfo()} has permanently (it calls {@code setPause()} and
     *     never resumes; SEM-02). Under {@link PauseSemantics#BOOLEAN_2008} this is only ever
     *     0 or 1.
     */
    public final synchronized int pauseDepth() {
        return pauseDepth;
    }

    @Override
    public final synchronized double pace() {
        return pace;
    }

    @Override
    public final synchronized void setPace(double newPace) {
        requireUsablePace(newPace);
        epochSimMs = nowMs();              // rebase first: elapsed time keeps the OLD pace
        epochNanos = source.nanos();
        pace = newPace;
    }

    /**
     * Moves simulated time to {@code simMs} without consuming real time, and rebases.
     *
     * <p>Protected rather than public: on a {@link PacedClock} an arbitrary jump would break
     * the only promise the class makes. {@link VirtualClock} exposes it under a contract that
     * keeps {@link SimClock#nowMs()} monotone.</p>
     *
     * @param simMs the new simulated time
     */
    protected final synchronized void rebaseTo(long simMs) {
        epochSimMs = simMs;
        epochNanos = source.nanos();
    }

    /** @return the real-time reference, for subclasses that drive it */
    protected final NanoSource source() {
        return source;
    }

    private static void requireUsablePace(double pace) {
        // Rejecting 0 is a decision, not an oversight: see SimClock#setPace.
        if (!(pace > 0.0) || Double.isInfinite(pace)) {
            throw new IllegalArgumentException("pace must be positive and finite, was " + pace);
        }
    }

    @Override
    public synchronized String toString() {
        return "PacedClock[now=" + nowMs() + "ms pace=" + pace
                + " pauseDepth=" + pauseDepth + " " + semantics + "]";
    }
}
