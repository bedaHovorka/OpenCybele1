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
 * The simulation's single source of simulated time — what {@code Cybele.getTime(CLOCK_ID)} was,
 * as an object rather than a static (#29).
 *
 * <p>One instance is shared by every agent. It is <b>read from many threads and written from
 * few</b>: in the ported application the writers are the pace toolbar ({@code Gui.java:98}, the
 * only {@code setPace} caller in the whole tree) and whichever agent brackets a section with
 * {@link #pause()}/{@link #resume()}. Every implementation here is therefore thread-safe, and
 * {@link #nowMs()} is monotone non-decreasing for concurrent readers.</p>
 *
 * <h2>Why this is an interface and not a class</h2>
 * Two implementations matter and neither is a special case of the other:
 * <ul>
 *   <li>{@link PacedClock} — simulated time derived from real time at a pace. This is what the
 *       application runs on and what the parity gate measures.</li>
 *   <li>{@link VirtualClock} — simulated time advanced <em>explicitly by the test</em>. Cybele
 *       can never have this: its clock is inside a source-less kernel jar, driven by a kernel
 *       thread, so every Cybele-side timing test is a sleep-and-hope. With a virtual clock a
 *       JADE L2 integration test (#38) is fully deterministic — "advance to t = 8500, assert
 *       exactly one train was generated" — with no wall-clock dependence at all. That is a
 *       capability the port gains over the original, and #41's comparison log should say so.</li>
 * </ul>
 *
 * <h2>What is deliberately NOT here</h2>
 * Scheduling. A time source that also fires callbacks has to own a thread, and a callback on a
 * timer thread breaks JADE's one-thread-per-agent invariant for every agent that uses it.
 * Scheduling lives in {@link AgentClock}, one per agent, popped on the agent's own thread.
 *
 * <h2>Units</h2>
 * Simulated time is a {@code long} count of simulated milliseconds, the same unit and origin as
 * {@code Cybele.getTime}: it starts at {@code sim.clock.startMs} and advances at
 * {@code sim.clock.pace} simulated ms per real ms.
 */
public interface SimClock {

    /**
     * The current simulated time in milliseconds.
     *
     * <p>Monotone non-decreasing: it never runs backwards, not across a {@link #pause()}, a
     * {@link #resume()} or a {@link #setPace(double)}. While paused it returns the same value
     * on every call.</p>
     *
     * @return simulated milliseconds since the clock's origin
     */
    long nowMs();

    /**
     * Freezes simulated time. {@code Cybele.pauseClock(CLOCK_ID)}.
     *
     * <p>Unlike Cybele's, this is <b>synchronous</b> — the clock is already frozen when this
     * returns. Cybele's is an asynchronous {@code sendAll} to the kernel's {@code TimerAgent}
     * with a sub-2 ms delivery latency, which time-shifts the frozen window but does not change
     * its duration (SEM-02, n = 40 on the real application). A synchronous pause is therefore
     * behaviour-preserving for the three bracketed sections in the 2008 source.</p>
     *
     * <p>Whether a second {@code pause()} nests depends on {@link PauseSemantics}; see there,
     * and see {@code docs/clock-abstraction.md} §2 for the measurement that made the default
     * {@link PauseSemantics#COUNTED}.</p>
     */
    void pause();

    /**
     * Resumes simulated time. {@code Cybele.resumeClock(CLOCK_ID)}. Resuming a running clock is
     * a no-op, as it is in the kernel ({@code ContinuousClock.setResume} opens
     * {@code if (!paused) return;}).
     */
    void resume();

    /** @return whether simulated time is currently frozen. {@code Cybele.isPaused(CLOCK_ID)}. */
    boolean isPaused();

    /** @return simulated milliseconds per real millisecond; strictly positive */
    double pace();

    /**
     * Changes the pace at runtime. {@code Cybele.setPace}, whose only caller is the GUI toolbar
     * at {@code Gui.java:98}.
     *
     * <p><b>Pending deadlines are rescaled, because Cybele's are.</b> Here that costs nothing:
     * {@link AgentClock} stores deadlines as absolute <em>simulated</em> instants, which do not
     * move when the pace changes — the same instant simply arrives sooner or later in real
     * time. No pending timer is touched and no lock is taken beyond this clock's own.</p>
     *
     * <p>Simulated time already elapsed is not re-interpreted: the clock rebases on the change,
     * so {@link #nowMs()} is continuous across it.</p>
     *
     * @param pace simulated ms per real ms; must lie in
     *     {@code [PacedClock.MIN_PACE, PacedClock.MAX_PACE]}
     * @throws IllegalArgumentException if {@code pace} is outside that range, or is
     *     {@code NaN}. The upper bound exists because {@link #nowMs()}'s promise of
     *     monotonicity is otherwise falsifiable: a pace large enough to saturate the
     *     {@code double}-to-{@code long} cast, followed by a rebase, wraps the clock negative.
     *     The implementation saturates rather than wrapping in any case, so the bound is a
     *     second line, not the only one.
     *     Pace {@code 0} is rejected on purpose: a stopped clock is {@link #pause()}, and
     *     letting a pace of zero mean the same thing gives two representations of one state
     *     and makes {@link #isPaused()} a lie.
     */
    void setPace(double pace);
}
