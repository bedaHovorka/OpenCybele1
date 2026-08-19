package cz.vutbr.fit.ags.parity.spi;

/**
 * How a run ended, derived from the child process' exit status.
 *
 * <p>The exit-code table is the one measured on the OpenCybele baseline and documented in
 * {@code docs/headless-and-stop.md}; every implementation the harness drives is expected to
 * reproduce it, because "the run reached the bound it declared" is part of the behavioural
 * contract, not an implementation detail. An adapter that cannot may override
 * {@link LauncherAdapter#classifyExit(int)}.
 *
 * <p>The distinction that matters most: {@link #BOUND_REACHED} is the only disposition that
 * means <em>the scenario ran to its declared end</em>. Everything else is "gave up", and a
 * harness that treats a timeout as a pass records a golden of a truncated run.
 */
public enum RunDisposition {

    /** Exit 0 — a declared stop bound was reached (or, in GUI mode, the window was closed). */
    BOUND_REACHED,

    /** Exit 1 — configuration or startup error, raised before the agent kernel started. */
    STARTUP_ERROR,

    /**
     * Exit 2 — the GUI window was closed while a {@code sim.stop.*} bound was armed and unreached.
     * The run did <em>not</em> finish. Closing the window of an unbounded interactive run is still
     * exit 0; this code exists because a desktop, a session manager or a stray click used to end a
     * bounded run with "a declared bound was reached".
     */
    WINDOW_CLOSED_EARLY,

    /** Exit 3 — the wall-clock safety net fired. The run did <em>not</em> finish. */
    WALL_CLOCK_TIMEOUT,

    /** Exit 4 — the stall detector fired: generation stopped while the process stayed alive. */
    STALL,

    /**
     * Exit 5 — the simulation clock stopped answering its command channel. Fatal to the whole
     * suite rather than to one scenario: every later run in the same environment is suspect,
     * and a golden recorded against a dead clock is wrong with no symptom.
     */
    CLOCK_COMMAND_DEAD,

    /**
     * Exit 255 — a throwable escaped an agent <em>constructor</em>. Unlike the handler path, this
     * one is loud: the JVM dies in ~0.26 s with a stack trace on stderr, no stop banner, and no
     * exit code the application ever set. Worth its own name precisely because it contradicts the
     * tempting rule "Cybele swallows every throwable".
     */
    AGENT_CONSTRUCTION_THROWABLE,

    /** The harness itself killed the process after {@code run.harnessTimeoutMs}. Never a pass. */
    HARNESS_TIMEOUT,

    /** An exit status the adapter does not recognise. Never a pass. */
    UNKNOWN;

    /** True for the dispositions a scenario is allowed to declare as its expected ending. */
    public boolean isDeclarable() {
        return this == BOUND_REACHED || this == STARTUP_ERROR;
    }
}
