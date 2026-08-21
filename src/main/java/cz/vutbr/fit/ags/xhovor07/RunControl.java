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

import cz.vutbr.fit.ags.railway.domain.clock.SimClock;

/**
 * Starts the run, bounds it, and ends it with an exit code that says what happened.
 * <p>
 * Everything here is <b>additive</b>: with no {@code sim.stop.*} key set — the default —
 * no bound is armed, nothing terminates the platform, and the simulation runs exactly as long as
 * it always did.
 *
 * <h2>What #36 changed, and what it deliberately did not</h2>
 *
 * This class was written against the Cybele kernel and three measured facts about it. Two of the
 * three were about a clock inside a source-less jar; the port replaced that clock with an object
 * ({@link SimClock}, #29), and the machinery that defended against it goes with it:
 *
 * <ul>
 * <li><b>Gone: {@code awaitTimerService()}.</b> It existed because {@code Cybele.createClock}
 *     announced a new clock with an <em>asynchronous</em> {@code sendAll}, and a clock created
 *     before the kernel's {@code TimerAgent} had subscribed was permanently and silently
 *     uncommandable (INVENTORY SEM-06). A {@code PacedClock} is an object in this JVM. There is
 *     no announcement, so there is nothing to lose and nothing to wait for.</li>
 * <li><b>Gone: {@code verifyClockControl()}, and with it the pause/resume round trip.</b> Same
 *     reason: it proved that a <em>particular</em> clock had been registered. Half of it survives
 *     as {@link #useClock(SimClock, long)} — not the proof, only the handover, which is the half
 *     the watchdog actually needed. That is <a
 *     href="https://github.com/bedaHovorka/OpenCybele1/issues/81">#81</a>.</li>
 * <li><b>Kept: the exit-code shutdown hook.</b> Cybele's {@code terminate()} called
 *     {@code System.exit(0)} and never returned, so an exit code had to be installed
 *     <em>before</em> the stop. JADE does not do that with {@code setCloseVM(false)} — but the
 *     hook costs one thread and covers every path that ends the JVM without going through
 *     {@link #stop(int, String)}, including {@code jade.core.Runtime}'s own terminators.</li>
 * <li><b>Kept in a new shape: {@link #fail(int, String)}.</b> Cybele swallowed handler throwables
 *     and left the exit status alone, so a failure detected inside an agent had to exit the
 *     process itself. JADE does not swallow: an uncaught throwable in a behaviour kills
 *     <em>that agent</em>, prints {@code ***  Uncaught Exception for agent …} on stderr and two
 *     lines on <b>stdout</b> ({@code Agent.clean(false)}), and leaves the platform running. That
 *     is louder but no less fatal to a recording, so callers that want the run to end still have
 *     to say so, and this is still how they say it.</li>
 * </ul>
 *
 * <h2>Why the guard flag survived #81's review</h2>
 *
 * #81 asked whether the {@code clockReady} guard is still needed, since "a {@link SimClock} has no
 * registration race to lose". Something like it is, for a different reason, and it was kept rather
 * than deleted by default: the watchdog thread starts in {@link #install(ScenarioConfig)}, which
 * {@code Main} calls <em>before</em> the platform boots, and the clock is created by
 * {@code RailwayMainAgent.setup()} some milliseconds later. Between those two points there is
 * genuinely no clock to read. What changed is what the guard <em>means</em> — it was "the kernel
 * has confirmed this clock is commandable", it is now "a clock exists yet" — and once it means
 * only that, a {@code null} check on the field says it exactly, so the separate {@code boolean}
 * is gone and {@link #simClock()} is the single reader.
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
     * enforce: a run that stopped early must not look like a pass.
     */
    public static final int EXIT_WINDOW_CLOSED_EARLY = 2;
    /**
     * {@code sim.stop.wallClockMs} elapsed. A safety net firing is a <b>failure</b>: the
     * run did not reach the bound it declared, and a timeout that exited 0 would let a
     * hung port record a golden that looks like a pass.
     * <p>
     * <b>This is the code #81 was about.</b> While the simulated-clock bound was inert, every
     * {@code opencybele-*} scenario would have ended here — terminating, so not a hang, but
     * wherever the wall clock happened to fall rather than inside the quiet window the scenario
     * was tuned to end in, and reported as a timeout rather than as a bound.
     */
    public static final int EXIT_WALL_CLOCK_TIMEOUT = 3;
    /**
     * {@code sim.stop.stallMs} elapsed with no new train. A throwable in
     * {@code Generator.generateTrain} before it re-arms kills generation permanently while
     * the process stays alive and quiet, so "nothing happened for a while" has to be its own
     * failure rather than a clean stop.
     * <p>
     * The port makes this <em>more</em> reachable, not less: on Cybele a throwable in the
     * generator was swallowed and the rest of the simulation carried on; under JADE it kills the
     * whole {@code Main} agent, so the hub and the planner stop with it.
     */
    public static final int EXIT_GENERATOR_STALLED = 4;
    /**
     * The clock's command channel is dead.
     * <p>
     * <b>Unreachable on this implementation, and kept deliberately.</b> It named Cybele's
     * SEM-06 registration race, which a {@link SimClock} cannot have. The constant survives
     * because the harness' exit table ({@code docs/headless-and-stop.md},
     * {@code LauncherAdapter.classifyExit}) maps 5 to {@code CLOCK_COMMAND_DEAD} and latches the
     * whole suite on it; reusing 5 for anything else on this branch would make a JADE run poison
     * every later scenario for a reason that has nothing to do with a clock.
     */
    public static final int EXIT_CLOCK_CONTROL_DEAD = 5;

    // ---------------------------------------------------------------- tuning

    /** Watchdog poll interval, ms. */
    private static final long WATCHDOG_POLL_MS = 10;

    // ---------------------------------------------------------------- state

    private static final AtomicBoolean stopping = new AtomicBoolean(false);
    /** Read by the shutdown hook, which is the only place an exit code can still be forced. */
    private static volatile int pendingExitCode = EXIT_OK;
    private static volatile long runStartedNanos;
    /**
     * The one simulated clock, published by {@code RailwayMainAgent.setup()}. {@code null} until
     * then — see "Why the guard flag survived" in the class comment.
     */
    private static volatile SimClock clock;
    private static volatile long clockStartMs;
    private static volatile ScenarioConfig config;
    /** Trains generated so far. Written by the generator thread, read by the watchdog. */
    private static volatile long trainsGenerated;
    private static volatile long lastTrainNanos;

    private RunControl() { /* no instances */ }

    // ---------------------------------------------------------------- startup

    /**
     * Install the exit-code hook and start the watchdog. Call from {@code main}, before the
     * platform boots.
     *
     * @param cfg the resolved configuration
     */
    public static void install(ScenarioConfig cfg) {
        config = cfg;
        runStartedNanos = System.nanoTime();
        lastTrainNanos = runStartedNanos;
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
     * Publish the simulation's clock to the watchdog, so that {@code sim.stop.maxClockMs} bounds
     * the run in <b>simulated</b> time. Called by {@code RailwayMainAgent.setup()} immediately
     * after the clock is constructed — the statement position Cybele's
     * {@code verifyClockControl(CLOCK_ID, startMs)} used to hold.
     * <p>
     * <b>Why this matters more than it looks</b> (<a
     * href="https://github.com/bedaHovorka/OpenCybele1/issues/81">#81</a>): the bound is not a
     * timeout. {@code opencybele-strict.yaml}'s 24 000 was chosen to sit inside a <em>measured</em>
     * 1 952 ms gap between event bursts, so the run ends with no emission in flight and the last
     * burst is reproducible; 25 000 does not, and truncated one run in three. A run that ends on
     * the wall-clock net instead ends wherever it happens to be, which is precisely the condition
     * every scenario in the catalogue was tuned to avoid.
     * <p>
     * First writer wins, deliberately, rather than last: a second clock would mean two sources of
     * simulated time, which is the thing #29 exists to prevent. A second call is reported and
     * ignored.
     *
     * @param simClock the clock the simulation runs on
     * @param startMs {@code sim.clock.startMs}, the origin the bound is measured from
     */
    public static void useClock(SimClock simClock, long startMs) {
        if (simClock == null) {
            throw new IllegalArgumentException("RunControl.useClock needs a clock");
        }
        if (clock != null) {
            System.err.println("!!! RunControl.useClock called twice; ignoring the second clock."
                    + " The simulation has one clock (#29) and sim.stop.maxClockMs bounds that one.");
            return;
        }
        clockStartMs = startMs;
        clock = simClock;
        // The stall budget starts ticking from here, not from install(): whatever the platform
        // boot cost must not be charged to sim.stop.stallMs, which is a budget for the GENERATOR.
        lastTrainNanos = System.nanoTime();
    }

    /** @return the clock the bound reads, or {@code null} before the main agent has created it */
    public static SimClock simClock() {
        return clock;
    }

    /**
     * The simulated clock as the stop machinery reads it.
     *
     * @return simulated ms, or {@code -1} when no clock exists yet. {@code -1} is not a legal
     *         simulated time and {@code docs/trace-format.md} already reads it as a fault.
     */
    public static long simTimeMs() {
        final SimClock current = clock;
        return current == null ? -1L : current.nowMs();
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
            final long started = runStartedNanos;
            while (!stopping.get()) {
                sleep(WATCHDOG_POLL_MS);
                final long now = System.nanoTime();
                final SimClock current = clock;
                // Ordered so the SIMULATED bound is tested first. It is the bound every scenario
                // in the catalogue declares, and the wall-clock one is its safety net; testing the
                // net first would let a slow machine report EXIT_WALL_CLOCK_TIMEOUT for a run that
                // had already earned EXIT_OK on the same poll.
                if (maxClock > 0 && current != null) {
                    final long elapsed = current.nowMs() - clockStartMs;
                    if (elapsed >= maxClock) {
                        stop(EXIT_OK, "sim.stop.maxClockMs reached: simulated clock at "
                                + elapsed + " ms past sim.clock.startMs");
                        return;
                    }
                }
                if (wall > 0 && millisSince(started, now) >= wall) {
                    stop(EXIT_WALL_CLOCK_TIMEOUT, "sim.stop.wallClockMs exceeded: " + wall
                            + " ms of wall clock elapsed without reaching a declared bound"
                            + (maxClock > 0 && current == null
                                    ? ". NOTE: sim.stop.maxClockMs is set but no clock was ever"
                                            + " published to RunControl.useClock, so the simulated"
                                            + " bound never armed -- see issue #81"
                                    : ""));
                    return;
                }
                if (stall > 0 && millisSince(lastTrainNanos, now) >= stall) {
                    stop(EXIT_GENERATOR_STALLED, "sim.stop.stallMs exceeded: no train"
                            + " generated for " + stall + " ms (" + trainsGenerated
                            + " generated in total). Generation stops permanently and"
                            + " silently if generateTrain throws before it re-arms.");
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
     * <p>
     * <b>Three things happen here in a fixed order, and the order is the whole design.</b>
     *
     * <ol>
     * <li><b>Freeze the clock.</b> Every timer in the ported application is an {@code AgentClock}
     *     deadline in <em>simulated</em> milliseconds, drained by a ticker that compares against
     *     {@link SimClock#nowMs()}. A frozen clock therefore arms nothing further: no traversal
     *     ends, no election opens, no train is generated. In-flight message chains still finish,
     *     but they are microseconds long and they carry the frozen tick.</li>
     * <li><b>Flush stdout.</b> When stdout is a pipe — which is how the harness captures it — it is
     *     an 8 KiB buffer with no autoflush, so the last few hundred trace lines are still in
     *     memory at this point. Losing them would truncate the run silently.</li>
     * <li><b>{@link Runtime#halt(int)}, not {@code System.exit}.</b> Measured, and this is the
     *     finding that made the difference between a reproducible run and a flaky one:
     *     {@code System.exit} runs shutdown hooks and takes ~30 real ms to end the JVM, during
     *     which every agent thread keeps running and keeps printing. At {@code sim.clock.pace = 8}
     *     that is ~240 simulated ms past the bound — enough to emit a whole extra event burst.
     *     Across five runs of {@code opencybele-strict} the captured trace ended anywhere between
     *     simulated 22 664 and 24 404 and varied by 12–32 normalized lines run to run, and every
     *     one of those lines was emitted <em>after</em> the run had decided to stop.
     *     {@code halt} ends the JVM at once, which is what makes "the bound sits inside a measured
     *     quiet window" mean anything at all.</li>
     * </ol>
     *
     * <b>The platform is not shut down first</b>, for the same reason. {@code Cybele.terminate()}
     * was the only way to stop a kernel whose threads were not daemons; killing a JADE container
     * would instead run {@code takeDown()} on fifteen-plus live agents <em>after</em> the stop
     * banner — new lines on the captured stream, after the bound, in teardown order.
     *
     * @param code one of the {@code EXIT_*} constants
     * @param reason human-readable, printed to stderr
     */
    public static void stop(int code, String reason) {
        if (!stopping.compareAndSet(false, true)) return;
        pendingExitCode = code;
        freezeClock();
        System.out.flush();
        System.err.println("--- simulation stop ---");
        System.err.println("  reason        = " + reason);
        System.err.println("  exit code     = " + code);
        System.err.println("  trains generated = " + trainsGenerated);
        System.err.println("  simulated clock  = "
                + (clock != null ? clock.nowMs() + " ms" : "(clock never started)"));
        System.err.println("  wall clock       = " + millis(System.nanoTime() - runStartedNanos)
                + " ms since the run began");
        System.err.println("-----------------------");
        System.err.flush();
        System.out.flush();
        Runtime.getRuntime().halt(code);
    }

    /**
     * Stop simulated time before anything else, so no timer can arm during the shutdown. Failure
     * is swallowed: a clock that will not pause must not be the reason a run cannot end.
     */
    private static void freezeClock() {
        final SimClock current = clock;
        if (current == null) {
            return;
        }
        try {
            current.pause();
        } catch (RuntimeException e) {
            System.err.println("!!! could not pause the simulation clock while stopping: " + e);
        }
    }

    /**
     * Fail the run from inside an agent. Same as {@link #stop(int, String)} but shouting.
     * <p>
     * It kept its own name through the port even though JADE, unlike Cybele, does not swallow a
     * handler throwable: a caller here has <em>detected</em> a condition rather than thrown on it,
     * and throwing would kill one agent and leave the platform running with a hole in it. The
     * distinction the name draws is still real.
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
