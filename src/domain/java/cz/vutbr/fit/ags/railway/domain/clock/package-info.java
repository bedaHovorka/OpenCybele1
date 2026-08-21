/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 *
 * Open Cybele 1
 *
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */
/**
 * Simulated time, owned explicitly (#29) — the replacement for Cybele's global
 * {@code myClock} and for {@code Activity.setTimer}.
 *
 * <h2>The gap this closes</h2>
 * Cybele has a first-class simulated clock: {@code Cybele.createClock}, {@code getTime},
 * {@code pauseClock}, {@code setPace}, and timers that are <em>clock-relative</em> rather than
 * wall-clock. JADE has none of that — {@code WakerBehaviour} and {@code TickerBehaviour} are
 * wall-clock, with no shared notion of simulated time, no pace and no pause. Jason has none
 * either. So the clock has to become an object the port carries, and this package is it.
 *
 * <h2>Two halves, deliberately separate</h2>
 * <ul>
 *   <li>{@link cz.vutbr.fit.ags.railway.domain.clock.SimClock} is the <b>time source</b>:
 *       {@code nowMs}, {@code pause}, {@code resume}, {@code setPace}. One instance is shared
 *       by every agent, exactly as {@code myClock} was.
 *       {@link cz.vutbr.fit.ags.railway.domain.clock.PacedClock} drives it from a real-time
 *       source; {@link cz.vutbr.fit.ags.railway.domain.clock.VirtualClock} drives it from the
 *       test.</li>
 *   <li>{@link cz.vutbr.fit.ags.railway.domain.clock.AgentClock} is the <b>scheduler</b>: one
 *       per agent, a private min-heap of absolute simulated deadlines
 *       ({@link cz.vutbr.fit.ags.railway.domain.clock.DeadlineQueue}) that is popped
 *       <em>on the agent's own thread</em> by whatever the host framework provides — a JADE
 *       {@code TickerBehaviour}, a Jason environment step. Nothing in this package ever
 *       starts a thread or fires a callback on one.</li>
 * </ul>
 *
 * <p>That split is not decoration. <b>One thread per agent is JADE's invariant</b>: an agent's
 * state is unsynchronised because only its own thread touches it. A timer service that called
 * back on a shared timer thread would break that for every ported agent at once. So this
 * package schedules, and the host dispatches.</p>
 *
 * <h2>Deadlines are absolute simulated milliseconds</h2>
 * Cybele stores a real-time expiry and <em>rescales every pending timer</em> when the pace
 * changes. Storing the deadline in simulated time instead makes that rescaling automatic and
 * exact — a deadline of "simulated t = 42000" means the same thing at pace 8, 1 and 0.3, and a
 * pace change touches no pending timer at all. Same observable semantics, no rescale pass, and
 * no lock ordering between the clock and seven agents' queues.
 *
 * <p>It also removes the reason {@code Planning.java:108-112} bracketed its arming with
 * {@code pauseClock}/{@code resumeClock}. That bracket exists to stop simulated time drifting
 * between the {@code getTime} read at {@code :109} and the kernel's own read inside
 * {@code setTimer} at {@code :111}, because the second read is what a <em>relative</em> delay
 * is measured from. {@link cz.vutbr.fit.ags.railway.domain.clock.AgentClock#scheduleAt} takes
 * the absolute instant the caller already computed, so there is no second read to drift
 * against. See {@code docs/clock-abstraction.md} §3.</p>
 *
 * <h2>Contract of this package</h2>
 * It lives in the {@code domain} source set, so it is framework-free by construction and by
 * {@code ./gradlew domainPurity}: no Cybele, no JADE, no Jason, no GUI toolkit, no reflection.
 * Branch {@code jason} (#46, #47) consumes it unchanged. Real time enters only through
 * {@link cz.vutbr.fit.ags.railway.domain.clock.NanoSource}, which the caller supplies, so a
 * test can drive the whole package without a wall clock.
 *
 * <h2>Reproducing the 2008 quirks</h2>
 * Cybele's pause is a plain {@code boolean}, not a counter, and is therefore not composable
 * across agents ({@code docs/INVENTORY.md} SEM-02, {@code docs/probes/ExpI.java}).
 * {@link cz.vutbr.fit.ags.railway.domain.clock.PauseSemantics} keeps both behaviours
 * available: {@code COUNTED} is the default, {@code BOOLEAN_2008} reproduces the kernel's.
 * Under the Phase-1 scope guard a port must be <em>able</em> to reproduce a defect even where
 * it chooses not to — see {@code docs/defect-triage.md}.
 */
package cz.vutbr.fit.ags.railway.domain.clock;
