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
 * One agent's view of simulated time: the shared {@link SimClock} to read, and a private
 * {@link DeadlineQueue} of wake-ups that fire <b>on the agent's own thread</b> (#29).
 *
 * <p>This is the replacement for {@code Activity.setTimer(RailwayMainAgent.CLOCK_ID, delay,
 * handler, "method")} at all four sites: {@code Generator.java:50} and {@code :65},
 * {@code Planning.java:111}, {@code RoadAgent.java:102}.</p>
 *
 * <h2>The dispatch rule, and why it is shaped like this</h2>
 * Nothing here starts a thread. {@link #runDue()} runs the due tasks <em>on the calling
 * thread</em>, and the caller is the host framework's per-agent granularity ticker — a JADE
 * {@code ClockTickerBehaviour} on the agent thread, a Jason environment step (#47). That is not
 * an implementation detail to be optimised away later:
 *
 * <blockquote><b>One thread per agent is JADE's invariant.</b> An agent's fields are
 * unsynchronised because only its own thread touches them. A clock service that called back
 * from a timer thread would silently break that for every agent that armed a timer — the port
 * would compile, run, and be wrong in a way no golden could reliably catch.</blockquote>
 *
 * <p>The cost is resolution: a wake-up fires at the first tick at or after its deadline, so it
 * is late by up to one granularity period. Cybele is late too — a zero-delay timer fires in
 * 1–5 ms (SEM-06) — and the goldens do not pin timestamps ({@code docs/trace-format.md}: the
 * five clock-derived families are projected by the normalizer, #21). Choose the granularity so
 * that {@code granularity × pace} is small against the simulation's shortest interval; see
 * {@code ClockTickerBehaviour}.</p>
 *
 * <h2>Absolute deadlines, and the bracket they delete</h2>
 * {@link #scheduleAt(long, Runnable)} takes the simulated <em>instant</em> to fire at. That is
 * what {@code Planning} already computes — {@code requestTime + timeDiff}, the agreed departure
 * — before converting it to a relative delay at {@code :109} only because
 * {@code Activity.setTimer} demands one. The conversion is why the site is bracketed with
 * {@code pauseClock}/{@code resumeClock}: a relative delay is measured from the kernel's
 * <em>own</em> clock read inside {@code setTimer}, so any simulated time elapsing between the
 * application's {@code getTime} at {@code :109} and that read pushes the departure late by the
 * drift. Freezing the clock across the two statements removes the drift.
 *
 * <p>Passing the instant removes the second read, so there is nothing to drift against and
 * nothing to bracket. {@code AgentClockTest.scheduling_an_absolute_instant_does_not_drift}
 * asserts exactly that: with the clock advancing between the computation and the arming, the
 * wake-up still fires at the instant that was computed.</p>
 *
 * <p>{@link #scheduleIn(long, Runnable)} is offered for the sites that genuinely are relative
 * ({@code Generator}'s exponential inter-arrival, {@code RoadAgent}'s travel delay) and is
 * itself drift-free: it reads the clock once, here, and stores the absolute result.</p>
 *
 * <h2>Threading</h2>
 * Arming is safe from any thread. Draining is not concurrent-safe by contract — call
 * {@link #runDue()} from one thread, the agent's.
 */
public final class AgentClock {

    private final SimClock clock;
    private final DeadlineQueue queue = new DeadlineQueue();

    /**
     * @param clock the shared simulated clock; the same instance for every agent, as
     *     {@code myClock} was
     */
    public AgentClock(SimClock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    /** @return the shared clock, for reads and for the pace control */
    public SimClock clock() {
        return clock;
    }

    /** @return current simulated time in ms. {@code Cybele.getTime(CLOCK_ID)}. */
    public long now() {
        return clock.nowMs();
    }

    /**
     * Arms a wake-up for an absolute simulated instant.
     *
     * @param dueSimMs the simulated instant to fire at. An instant already past fires at the
     *     next {@link #runDue()} rather than being dropped — Cybele fires a negative-delay
     *     timer immediately (SEM-06) and DEF-16 depends on it.
     * @param task the callback; runs on the {@link #runDue()} caller's thread
     * @return a handle for {@link #cancel(long)}
     */
    public long scheduleAt(long dueSimMs, Runnable task) {
        return queue.scheduleAt(dueSimMs, task);
    }

    /**
     * Arms a wake-up {@code delayMs} of simulated time from now.
     * {@code Activity.setTimer(CLOCK_ID, delayMs, …)}.
     *
     * <p>The clock is read once, here, and the absolute deadline is what is stored — so the
     * result does not depend on how much simulated time passes before the queue is next
     * drained. That is why this needs no {@code pauseClock} bracket either.</p>
     *
     * @param delayMs simulated milliseconds; <b>may be negative</b>, and then fires at the next
     *     drain. Do not clamp it here: {@code Planning.java:111}'s {@code t > 0 ? t : 0} is the
     *     2008 clamp and belongs at that call site if it is ported at all, while
     *     {@code RoadAgent}'s negative Gaussian delay (DEF-16) must stay unclamped.
     * @param task the callback
     * @return a handle for {@link #cancel(long)}
     */
    public long scheduleIn(long delayMs, Runnable task) {
        return scheduleAt(clock.nowMs() + delayMs, task);
    }

    /**
     * @param id a handle from {@link #scheduleAt} or {@link #scheduleIn}
     * @return whether it was still pending. {@code Activity.clearTimer}.
     */
    public boolean cancel(long id) {
        return queue.cancel(id);
    }

    /**
     * Runs every wake-up that is due, in deadline order, <b>on the calling thread</b>.
     *
     * <p>The simulated time is read <em>once</em>, at entry, and the queue's arming sequence is
     * bounded at entry too, so a task that arms a fresh past-dated deadline does not re-enter
     * this drain — it fires at the next one. Without that bound a {@code scheduleIn(-1, …)}
     * from inside a callback would spin the agent thread forever, and a negative delay is not
     * hypothetical: it is 2.26 % of {@code RoadAgent}'s travel-time draws on a 1 s road
     * (DEF-16).</p>
     *
     * <p><b>The bound is per drain, not global — do not call {@link #runDue()} from inside a
     * wake-up.</b> A callback that re-enters {@code runDue()} gets a fresh, higher sequence
     * limit and will therefore pick up the deadline it just armed: measured, a self-rearming
     * callback that called {@code runDue()} itself fired five times inside one outer drain.
     * That does not violate this class's contract — the drain the framework started still
     * terminates — but it is exactly the spin the bound is there to prevent, re-created by
     * hand. Arm from a wake-up freely; drain only from the agent's ticker.</p>
     *
     * <p>Tasks run outside every lock this package holds, so a task may arm, cancel, read the
     * clock, or pause it. If a task throws, the throwable propagates and the wake-ups that had
     * not yet been polled stay in the queue — they are not consumed by the failed drain.</p>
     *
     * @return how many wake-ups fired
     */
    public int runDue() {
        final long now = clock.nowMs();
        final long limit = queue.sequence();
        int fired = 0;
        Runnable task;
        while ((task = queue.pollDue(now, limit)) != null) {
            task.run();
            fired++;
        }
        return fired;
    }

    /**
     * @return the earliest pending deadline in simulated ms, or {@code null} if nothing is
     *     armed. For a host that wants to sleep until the next wake-up instead of ticking at a
     *     fixed granularity — a Jason environment can do that; a JADE {@code TickerBehaviour}
     *     cannot without resetting its period on every arm.
     */
    public Long nextDueMs() {
        return queue.nextDueMs();
    }

    /** @return how many wake-ups are armed */
    public int pending() {
        return queue.size();
    }

    @Override
    public String toString() {
        return "AgentClock[now=" + now() + "ms pending=" + pending() + "]";
    }
}
