/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 * 
 * Open Cybele 1
 * 
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */
package cz.vutbr.fit.ags.xhovor07;

import java.util.concurrent.atomic.AtomicBoolean;

import cybele.kernel.Cybele;

/**
 * Starts the run, bounds it, and ends it with an exit code that says what happened.
 * <p>
 * Everything here is <b>additive</b>: with no {@code sim.stop.*} key set — the default —
 * no bound is armed, nothing calls {@link Cybele#terminate()}, and the simulation runs
 * exactly as long as it always did. What is unconditional is the kernel-readiness barrier
 * and the clock-control check, because both of those guard against a failure that is
 * invisible rather than against a configuration.
 *
 * <h2>Why a class and not a few lines in {@code Main}</h2>
 *
 * Three measured facts about this kernel shape everything below.
 *
 * <ol>
 * <li><b>A throwable inside a Cybele handler does not fail the run.</b> Cybele invokes
 *     agent constructors and handlers reflectively, wraps whatever they throw in an
 *     {@code InvocationTargetException}, prints it to stderr and carries on with an
 *     unchanged exit status ({@code docs/assertion-triage.md}, Result 3). So a failure
 *     detected inside an agent has to <em>exit the process itself</em>; throwing is not
 *     an option, and neither is returning an error to a caller that does not exist.
 *     <br>
 *     <b>Agent <em>construction</em> is a different path, and it is not silent.</b>
 *     Measured by injecting a throw at {@link RailwayMainAgent}'s {@code createClock}
 *     line: the JVM exits <b>255</b> in about 0.26 s, with the stack trace on stderr, no
 *     stop banner and this class' exit code never set (3/3 here, 4 configurations
 *     independently). That is loud rather than silent, but it is a status outside the
 *     table below, so a harness reading exit codes has to know about it. See
 *     {@code docs/headless-and-stop.md}.</li>
 * <li><b>{@link Cybele#terminate()} never returns — it calls {@code System.exit(0)}.</b>
 *     Measured: a probe that printed a line after {@code terminate()} never printed it,
 *     and the process exited 0. An exit code therefore cannot be set <em>after</em>
 *     terminate; it has to be installed <em>before</em>, which is what the shutdown hook
 *     in {@link #install(ScenarioConfig)} is for — it calls {@link Runtime#halt(int)}
 *     with the intended code while the JVM is already shutting down, overriding the 0.
 *     Verified end to end: the probe exited 42 with the hook in place.</li>
 * <li><b>The kernel's timer service is not ready when {@code startUp()} returns.</b>
 *     See {@link #awaitTimerService()}.</li>
 * </ol>
 *
 * @author Bedrich Hovorka
 */
public final class RunControl {

    // ---------------------------------------------------------------- exit codes

    /** A declared bound was reached and the run stopped cleanly. */
    public static final int EXIT_OK = 0;
    /**
     * The GUI window was closed while a {@code sim.stop.*} bound was armed and had not
     * been reached.
     * <p>
     * Closing the window is the intended way to end an <em>unbounded</em> interactive run,
     * and that still exits {@link #EXIT_OK}. But a run that declared a bound and was ended
     * by a desktop, a session manager or a stray click did <b>not</b> reach that bound, and
     * exiting 0 for it breaks the same rule {@link #EXIT_WALL_CLOCK_TIMEOUT} exists to
     * enforce: a run that stopped early must not look like a pass. This is not
     * hypothetical — three runs in the criterion-5 series were closed by the desktop at
     * 2.3 s, and every one of them exited 0 before this code existed.
     */
    public static final int EXIT_WINDOW_CLOSED_EARLY = 2;
    /**
     * {@code sim.stop.wallClockMs} elapsed. A safety net firing is a <b>failure</b>: the
     * run did not reach the bound it declared, and a timeout that exited 0 would let a
     * hung port record a golden that looks like a pass.
     */
    public static final int EXIT_WALL_CLOCK_TIMEOUT = 3;
    /**
     * {@code sim.stop.stallMs} elapsed with no new train. A throwable in
     * {@code Generator.generateTrain} before {@code setTimer} re-arms kills generation
     * permanently while the process stays alive and quiet, so "nothing happened for a
     * while" has to be its own failure rather than a clean stop.
     */
    public static final int EXIT_GENERATOR_STALLED = 4;
    /**
     * The clock's command channel is dead: {@code pauseClock} does not pause. See
     * {@link #awaitTimerService()} and {@link #verifyClockControl(String, long)}.
     */
    public static final int EXIT_CLOCK_CONTROL_DEAD = 5;

    // ---------------------------------------------------------------- tuning

    /** Watchdog poll interval, ms. */
    private static final long WATCHDOG_POLL_MS = 10;
    /** Poll interval while waiting for a pause command to land, ms. */
    private static final long PAUSE_POLL_MS = 5;
    /** How long one probe clock is given to prove the command path, ms. Measured: 5-10 ms. */
    private static final long PROBE_BUDGET_MS = 250;
    /**
     * How long the barrier may keep trying, in total, before declaring the timer service
     * dead. A <em>deadline</em> rather than an attempt count on purpose: an attempt count
     * has to be set to the worst case somebody once saw on one machine, which is the same
     * mistake as sleeping a constant. Measured cost on this machine is 14.7-274.2 ms and
     * one to three probe clocks unloaded, up to four under load — so 30 s is roughly two
     * orders of magnitude of headroom, and exit {@link #EXIT_CLOCK_CONTROL_DEAD} is the
     * one code that must never cry wolf: #24 reads it as "every golden after this is
     * worthless".
     */
    private static final long PROBE_DEADLINE_MS = 30000;
    /** Secondary cap, so a pathological zero-cost failure cannot spin. Never the binding one. */
    private static final int PROBE_ATTEMPTS = 2000;
    /** Pause between probe attempts that could not even create a clock, ms. */
    private static final long PROBE_RETRY_MS = 2;
    /** How long the real clock is given to answer its pause round-trip, ms. */
    private static final long CLOCK_CHECK_BUDGET_MS = 2000;
    /**
     * How long the clock must be observed to stay running after the round trip, ms.
     * <p>
     * This is the one constant here that is a <b>duration, not a condition</b>, and it is
     * worth saying so rather than filing it under "wait for the thing itself": it watches
     * for an event that was measured never to happen (a straggler pause landing after the
     * resume). It is insurance against a slower machine reordering what was measured on
     * this one, and it is also the largest cost this class adds to every run — including
     * the default GUI run, where it shifts every printed timestamp by about
     * {@code sim.clock.pace * 100} ms. The 250 ms and 2000 ms budgets are different in
     * kind: they are self-correcting, since a budget that expires too early burns another
     * probe id rather than passing a dead clock.
     */
    private static final long SETTLE_MS = 100;
    /** How long {@link Cybele#terminate()} is given to end the JVM before we halt it. */
    private static final long TERMINATE_GRACE_MS = 5000;

    private static final String PROBE_CLOCK_PREFIX = "RunControl.probeClock";

    // ---------------------------------------------------------------- state

    private static final AtomicBoolean stopping = new AtomicBoolean(false);
    /** Read by the shutdown hook, which is the only place an exit code can still be forced. */
    private static volatile int pendingExitCode = EXIT_OK;
    private static volatile long kernelStartedNanos;
    private static volatile long timerServiceReadyNanos;
    private static volatile int probeClocksBurnt;
    private static volatile boolean clockReady;
    private static volatile String clockId;
    private static volatile long clockStartMs;
    private static volatile ScenarioConfig config;
    /** Trains generated so far. Written by the generator thread, read by the watchdog. */
    private static volatile long trainsGenerated;
    private static volatile long lastTrainNanos;

    private RunControl() { /* no instances */ }

    // ---------------------------------------------------------------- startup

    /**
     * Install the exit-code hook and start the watchdog. Call from {@code main},
     * immediately after {@link Cybele#startUp()}.
     *
     * @param cfg the resolved configuration
     */
    public static void install(ScenarioConfig cfg) {
        config = cfg;
        kernelStartedNanos = System.nanoTime();
        lastTrainNanos = kernelStartedNanos;
        // See the class Javadoc, point 2: terminate() exits 0 and never returns, so the
        // only place left to state an exit code is a shutdown hook that halts.
        Runtime.getRuntime().addShutdownHook(new Thread("sim-exit-code") {
            @Override
            public void run() {
                System.out.flush();
                System.err.flush();
                final int code = pendingExitCode;
                if (code != EXIT_OK) Runtime.getRuntime().halt(code);
            }
        });
        if (cfg.hasStopCondition()) {
            final Thread watchdog = new Thread(new Watchdog(), "sim-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }
    }

    /**
     * Block until the kernel's timer service can actually be commanded.
     * <p>
     * {@code Cybele.createClock} builds the {@code ContinuousClock} synchronously — so
     * {@code getTime()} and {@code isPaused()} work at once — but announces it with an
     * <em>asynchronous</em> {@code Activity.sendAll("Cybele.TimerService.newClock", …)}.
     * Only when the kernel's {@code TimerAgent} handles that broadcast does it open the
     * per-clock command channel that {@code pauseClock}/{@code resumeClock}/
     * {@code setPace}/{@code setTime} all funnel into, and a {@code sendAll} to a channel
     * nobody has subscribed to yet is silently dropped ({@code INVENTORY} SEM-06). A clock
     * created too early is therefore <b>permanently uncommandable, silently</b>.
     * <p>
     * Measured on this tree, gap from {@code startUp()} returning to {@code createClock}:
     * <ul>
     * <li>≈1 ms — {@code createClock} itself throws
     *     {@code NullPointerException … TimerAgent.register … "this.ag" is null} (6/6 runs);
     *     the service object exists but its agent does not. In an agent constructor that
     *     throwable does <b>not</b> leave a running simulation behind: measured, the JVM
     *     exits 255 with the stack trace on stderr and no stop banner. Loud, but with a
     *     status no table names, and {@link #verifyClockControl} never runs — it is the
     *     next statement.</li>
     * <li>≈3 ms — no throw, but the announcement is lost: {@code pauseClock} returns
     *     {@code true}, {@code isPaused()} stays {@code false} and the clock keeps
     *     advancing, for the life of the JVM.</li>
     * <li>≥5 ms — lands, and the pause takes effect within 5–10 ms (3/3 at each of
     *     5/20/100 ms).</li>
     * </ul>
     * A retry on the <b>same clock id</b> does not recover it — a clock id whose first
     * {@code createClock} threw was still dead when re-created 16 ms later — so each
     * attempt below uses a fresh id.
     * <p>
     * <b>Probe clocks are unreclaimable, not merely throwaway.</b> The Cybele API has no
     * {@code destroyClock}, so every attempt leaves a {@code ContinuousClock}, a
     * TimerAgent registration and a non-daemon thread parked on {@code wait} behind for
     * the life of the JVM — and a <em>failed</em> attempt leaves its clock <b>running</b>,
     * with late pause commands still in flight. This is inert: every clock API is keyed by
     * id with no default-clock fallback, so a probe cannot leak into {@code getTime} or
     * into the simulation's command path. It is not free either, which is a second reason
     * the budget below is a deadline rather than a large attempt count.
     * <p>
     * Sleeping a fixed few milliseconds would work today and rot the first time this runs
     * on a slower or busier machine. This waits for the thing itself instead: it creates a
     * throwaway clock and asks it to pause. {@code isPaused()} is the verdict because it
     * is the one signal measured to distinguish the two cases — {@code pauseClock}'s
     * return value is {@code true} either way, and "did the clock stop advancing" needs a
     * window long enough for the configured pace to be visible.
     */
    public static void awaitTimerService() {
        final long t0 = System.nanoTime();
        final long deadline = t0 + PROBE_DEADLINE_MS * 1000000L;
        Throwable lastThrow = null;
        int attempt = 0;
        while (System.nanoTime() < deadline && attempt < PROBE_ATTEMPTS) {
            attempt++;
            final String id = PROBE_CLOCK_PREFIX + attempt;
            probeClocksBurnt = attempt;
            try {
                Cybele.createClock(id, Cybele.HOST, 0, 1);
            } catch (Throwable t) {
                // Normally the TimerAgent simply is not constructed yet, and the throw IS
                // the "not ready" signal. Nothing is matched on the type or the message:
                // any throw is retried, which survives a kernel build that fails
                // differently. But the last one is kept, because a throw that is NOT the
                // known startup NPE would otherwise be spent here and never reported, and
                // the failure below would name the wrong cause with no evidence.
                lastThrow = t;
                sleep(PROBE_RETRY_MS);
                continue;
            }
            if (pauseLands(id, PROBE_BUDGET_MS)) {
                timerServiceReadyNanos = System.nanoTime();
                // The bounds' budgets start ticking here, not at startUp: whatever the
                // barrier cost must not be charged to sim.stop.stallMs.
                lastTrainNanos = timerServiceReadyNanos;
                System.err.println("--- kernel timer service ready after "
                        + millis(timerServiceReadyNanos - t0) + " ms and " + attempt
                        + " probe clock(s) ---");
                return;   // the probe clock stays paused; see awaitTimerService's note
            }
        }
        final StringBuilder why = new StringBuilder();
        why.append("the kernel timer service never accepted a pause command, over ")
           .append(attempt).append(" probe clocks and ")
           .append(millis(System.nanoTime() - t0))
           .append(" ms. Nothing on any clock could be paced, paused or scheduled, so the")
           .append(" run would be meaningless.");
        if (lastThrow != null) {
            why.append(" Last throwable from createClock was: ").append(lastThrow);
        }
        if (lastThrow != null) {
            System.err.println("!!! last throwable from createClock during the barrier:");
            lastThrow.printStackTrace();
            System.err.flush();
        }
        fail(EXIT_CLOCK_CONTROL_DEAD, why.toString());
    }

    /**
     * Verify that the simulation clock itself answers its command channel, and fail loudly
     * if it does not.
     * <p>
     * The registration described in {@link #awaitTimerService()} is <b>per clock</b>, so
     * clearing the barrier is necessary but not sufficient: this is the check that the
     * clock the simulation actually runs on is commandable. Without it, a lost
     * registration is invisible — {@code getTime()} keeps advancing, trains keep being
     * generated, the GUI keeps painting — while {@code Planning}'s pause/resume bracket,
     * every {@code setTimer} and the GUI's pace toolbar are all no-ops. Every golden
     * recorded afterwards would be wrong with no visible symptom, which is the one failure
     * mode a golden-master harness cannot survive.
     * <p>
     * <b>Call it between {@code createClock} and the existing {@code resumeClock}</b>: at
     * that point the clock carries no timers and no agent has been created, so pausing it
     * is inert, and the {@code resumeClock} that the code already performs is the other
     * half of the round trip. Nothing extra is sent to the clock the simulation runs on.
     *
     * <p>
     * <b>Scope.</b> This catches the <em>silent</em> failure — the announcement dropped,
     * the clock uncommandable, everything else apparently normal. It structurally cannot
     * catch the other one: it is the statement immediately after {@code createClock} in
     * the same constructor, so if {@code createClock} itself throws, this never runs. That
     * mode is covered instead by the JVM exiting 255 (see the class Javadoc), which is
     * loud enough not to need a check — the point of this one is that its failure mode
     * has no symptom at all.
     * <p>
     * <b>It is not free of traffic on the real clock.</b> "Nothing extra is sent" would be
     * wrong: at a 5 ms poll and a 107-135 ms round trip this issues roughly 20-27
     * {@code pauseClock} commands and a {@code resumeClock}, then watches the clock for
     * {@link #SETTLE_MS}. What is true is that it is <em>inert</em> — no timer exists on
     * the clock yet, no other agent exists, and criterion 5 measured no change to the
     * message sequence. What it does move is the clock's absolute reading: the settle
     * window runs with the clock going, so every timestamp the run later prints is offset
     * by roughly {@code pace * SETTLE_MS} against the pre-#17 baseline.
     *
     * @param id the clock id to verify
     * @param startMs the clock's configured start time, for the bound in {@link Watchdog}
     */
    public static void verifyClockControl(String id, long startMs) {
        // Sampled, not printed, until the verdict is in: a println here is itself ~1 ms of
        // work in front of the race this number measures.
        final long t0 = System.nanoTime();
        final String gap = "startUp -> createClock gap " + millis(t0 - kernelStartedNanos)
                + " ms (" + (timerServiceReadyNanos == 0 ? "NO BARRIER"
                        : "barrier cleared at " + millis(timerServiceReadyNanos - kernelStartedNanos)
                          + " ms, " + probeClocksBurnt + " probe clock(s)") + ")";
        final boolean paused = pauseLands(id, CLOCK_CHECK_BUDGET_MS);
        if (!paused) {
            fail(EXIT_CLOCK_CONTROL_DEAD, gap + ": clock '" + id + "' does not answer pauseClock:"
                    + " isPaused() stayed false and the clock kept advancing for "
                    + CLOCK_CHECK_BUDGET_MS + " ms. Its registration with the kernel"
                    + " TimerAgent was lost (INVENTORY SEM-06), which is permanent and"
                    + " silent: getTime() still works, so the simulation would run on with"
                    + " every pauseClock/resumeClock/setPace/setTimer a no-op.");
            return;   // not reached
        }
        if (!resumeLands(id, CLOCK_CHECK_BUDGET_MS)) {
            fail(EXIT_CLOCK_CONTROL_DEAD, gap + ": clock '" + id + "' paused but did not resume"
                    + " within " + CLOCK_CHECK_BUDGET_MS + " ms. The simulation would sit"
                    + " frozen at " + Cybele.getTime(id) + " ms with no error.");
            return;   // not reached
        }
        if (!settlesRunning(id, SETTLE_MS)) {
            fail(EXIT_CLOCK_CONTROL_DEAD, gap + ": clock '" + id + "' would not stay running"
                    + " after being resumed. A pause command is arriving late and nothing will"
                    + " undo it, so the simulation would freeze silently.");
            return;   // not reached
        }
        clockId = id;
        clockStartMs = startMs;
        clockReady = true;
        // Second re-stamp (the barrier did the first): the clock check costs 107-135 ms,
        // and sim.stop.stallMs is a budget for the GENERATOR, not for startup.
        lastTrainNanos = System.nanoTime();
        System.err.println("--- clock '" + id + "' answered a pause/resume round trip in "
                + millis(System.nanoTime() - t0) + " ms; " + gap + " ---");
    }

    /**
     * Ask a clock to pause and wait until {@code isPaused()} agrees.
     * <p>
     * <b>The command is re-sent on every poll, and that is not belt-and-braces.</b> There
     * are two distinct ways a clock command goes missing, and only one of them is fatal:
     * <ul>
     * <li>the per-clock command channel is <em>not open yet</em> — the TimerAgent opens it
     *     when it handles the {@code newClock} announcement, which is asynchronous. A
     *     command sent into that window is dropped (SEM-06) but the channel does open
     *     moments later, so a re-send lands. Measured: a {@code pauseClock} issued
     *     immediately after {@code createClock} — no intervening statement — was lost 6/6
     *     even for a clock created 400 ms after {@code startUp()}, where no race exists;
     *     the same call with one {@code println} in front of it landed 6/6 within ~25 ms.
     *     Sending once and polling would therefore report a perfectly healthy clock as
     *     dead, which is how this method was first written and why it is written this way
     *     now.</li>
     * <li>the <em>announcement itself</em> was dropped, so the channel is never opened at
     *     all. That is the permanent failure {@link #awaitTimerService()} describes, and
     *     re-sending does not recover it — measured: 400 re-sends over 2 s, still paused
     *     = false. This is what exhausting the budget means.</li>
     * </ul>
     * Re-sending is safe on a live clock: pause/resume are a boolean flag, not a counter
     * (INVENTORY SEM-02), so N pauses are undone by one resume.
     *
     * @return {@code true} once {@code isPaused()} reports the pause landed
     */
    private static boolean pauseLands(String id, long budgetMs) {
        final long deadline = System.nanoTime() + budgetMs * 1000000L;
        do {
            Cybele.pauseClock(id);
            if (Cybele.isPaused(id)) return true;
            sleep(PAUSE_POLL_MS);
        } while (System.nanoTime() < deadline);
        return Cybele.isPaused(id);
    }

    /**
     * Watch a resumed clock for {@code settleMs} and make sure it stays resumed.
     * <p>
     * {@link #pauseLands} may queue two or three pause commands before {@code isPaused()}
     * flips, and a straggler landing after the resume would leave the clock stopped with
     * nobody to restart it — a silent freeze. Measured, this does not happen (a burst of
     * five pauses followed by one resume left the clock running for 3 s, 3/3 at bursts of
     * 1, 3 and 5), which is why the window here is short: it is insurance against a
     * slower machine reordering what was measured on this one, not a known defect.
     *
     * @return {@code true} if the clock was running throughout, after at most one nudge
     */
    private static boolean settlesRunning(String id, long settleMs) {
        final long deadline = System.nanoTime() + settleMs * 1000000L;
        while (System.nanoTime() < deadline) {
            if (Cybele.isPaused(id) && !resumeLands(id, CLOCK_CHECK_BUDGET_MS)) return false;
            sleep(PAUSE_POLL_MS);
        }
        return !Cybele.isPaused(id);
    }

    /** The other half of {@link #pauseLands}; same re-send rule, same reason. */
    private static boolean resumeLands(String id, long budgetMs) {
        final long deadline = System.nanoTime() + budgetMs * 1000000L;
        do {
            Cybele.resumeClock(id);
            if (!Cybele.isPaused(id)) return true;
            sleep(PAUSE_POLL_MS);
        } while (System.nanoTime() < deadline);
        return !Cybele.isPaused(id);
    }

    // ---------------------------------------------------------------- bounds

    /**
     * Record that one more train has been generated, and report whether the generator
     * should keep going.
     *
     * @param generated the number of trains generated so far, including this one
     * @return {@code false} if {@code sim.stop.maxTrains} has been reached, in which case
     *         the caller must not re-arm its timer — the run is ending
     */
    public static boolean trainGenerated(long generated) {
        trainsGenerated = generated;
        lastTrainNanos = System.nanoTime();
        final ScenarioConfig cfg = config;
        if (cfg == null) return true;
        final long max = cfg.getStopMaxTrains();
        if (max > 0 && generated >= max) {
            stop(EXIT_OK, "sim.stop.maxTrains reached: " + generated + " trains generated");
            return false;
        }
        return true;
    }

    /** The watchdog. Reads only; it never sends anything into the simulation. */
    private static final class Watchdog implements Runnable {
        @Override
        public void run() {
            final ScenarioConfig cfg = config;
            final long wall = cfg.getStopWallClockMs();
            final long maxClock = cfg.getStopMaxClockMs();
            final long stall = cfg.getStopStallMs();
            final long started = kernelStartedNanos;
            while (!stopping.get()) {
                sleep(WATCHDOG_POLL_MS);
                final long now = System.nanoTime();
                if (wall > 0 && millisSince(started, now) >= wall) {
                    stop(EXIT_WALL_CLOCK_TIMEOUT, "sim.stop.wallClockMs exceeded: " + wall
                            + " ms of wall clock elapsed without reaching a declared bound");
                    return;
                }
                if (maxClock > 0 && clockReady) {
                    final long elapsed = Cybele.getTime(clockId) - clockStartMs;
                    if (elapsed >= maxClock) {
                        stop(EXIT_OK, "sim.stop.maxClockMs reached: simulated clock at "
                                + elapsed + " ms past sim.clock.startMs");
                        return;
                    }
                }
                if (stall > 0 && millisSince(lastTrainNanos, now) >= stall) {
                    stop(EXIT_GENERATOR_STALLED, "sim.stop.stallMs exceeded: no train"
                            + " generated for " + stall + " ms (" + trainsGenerated
                            + " generated in total). Generation stops permanently and"
                            + " silently if generateTrain throws before its timer re-arms.");
                    return;
                }
            }
        }
    }

    // ---------------------------------------------------------------- shutdown

    /**
     * The GUI window was closed. Decides between {@link #EXIT_OK} and
     * {@link #EXIT_WINDOW_CLOSED_EARLY} — see the latter — and stops the run.
     */
    public static void windowClosed() {
        final ScenarioConfig cfg = config;
        final boolean bounded = cfg != null && cfg.hasStopCondition();
        stop(bounded ? EXIT_WINDOW_CLOSED_EARLY : EXIT_OK,
                bounded ? "GUI window closed before the declared sim.stop.* bound was reached"
                        : "GUI window closed (no sim.stop.* bound was armed)");
    }

    /**
     * End the run. Idempotent; the first caller decides the exit code.
     *
     * @param code one of the {@code EXIT_*} constants
     * @param reason human-readable, printed to stderr
     */
    public static void stop(int code, String reason) {
        if (!stopping.compareAndSet(false, true)) return;
        pendingExitCode = code;
        System.out.flush();
        System.err.println("--- simulation stop ---");
        System.err.println("  reason        = " + reason);
        System.err.println("  exit code     = " + code);
        System.err.println("  trains generated = " + trainsGenerated);
        System.err.println("  simulated clock  = "
                + (clockReady ? Cybele.getTime(clockId) + " ms" : "(clock never started)"));
        System.err.println("  wall clock       = " + millis(System.nanoTime() - kernelStartedNanos)
                + " ms since Cybele.startUp()");
        System.err.println("-----------------------");
        System.err.flush();
        System.out.flush();
        terminateKernel();
        // terminate() is measured never to return; if a future kernel ever does, or if it
        // hangs, this is the backstop that still produces the right status.
        System.exit(code);
    }

    /**
     * Fail the run from inside an agent. Same as {@link #stop(int, String)} but shouting,
     * and it exists as its own name because the caller cannot simply throw: Cybele
     * swallows handler throwables without touching the exit status.
     *
     * @param code one of the {@code EXIT_*} constants
     * @param reason human-readable, printed to stderr
     */
    public static void fail(int code, String reason) {
        System.out.flush();
        System.err.println("!!! FATAL: " + reason);
        System.err.flush();
        stop(code, reason);
    }

    /**
     * {@link Cybele#terminate()} on a thread we can abandon. It ends the JVM itself, so
     * normally nothing after it runs; the join is there so that a kernel that ever hangs
     * in shutdown cannot turn a wall-clock timeout back into a hang.
     */
    private static void terminateKernel() {
        final Thread t = new Thread("sim-terminate") {
            @Override
            public void run() {
                try {
                    Cybele.terminate();
                } catch (Throwable ignored) {
                    /* the kernel is going away; nothing here can improve on that */
                }
            }
        };
        t.setDaemon(true);
        t.start();
        try {
            t.join(TERMINATE_GRACE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            System.err.println("!!! Cybele.terminate() did not finish within "
                    + TERMINATE_GRACE_MS + " ms; halting.");
            System.err.flush();
            System.out.flush();
            Runtime.getRuntime().halt(pendingExitCode);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long millisSince(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1000000L;
    }

    private static String millis(long nanos) {
        return String.format("%.2f", Double.valueOf(nanos / 1e6));
    }
}
