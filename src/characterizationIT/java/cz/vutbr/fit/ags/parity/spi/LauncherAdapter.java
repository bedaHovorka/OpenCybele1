package cz.vutbr.fit.ags.parity.spi;

import cz.vutbr.fit.ags.parity.normalize.DiagnosticFilter;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;

import java.nio.file.Path;
import java.util.List;

/**
 * The one place in the harness that is allowed to know what implementation is being driven.
 *
 * <p>An adapter turns an implementation-neutral {@link ScenarioSpec} into a command line. It does
 * <em>not</em> link against the implementation: {@code jade-develop} carries no Cybele dependency,
 * and the reference implementation lives on another branch, reached by path. That is what lets the
 * same harness drive OpenCybele (<a href="https://github.com/bedaHovorka/OpenCybele1/issues/13">#13</a>),
 * JADE (#36) and Jason (#43) without a single line of it changing.
 *
 * <p>An adapter is expected to be cheap to construct and free of per-run state; the runner may
 * reuse one instance across scenarios.
 */
public interface LauncherAdapter {

    /** Short stable id, used in failure reports and in golden-file provenance. */
    String id();

    /**
     * Builds the command line for one scenario.
     *
     * @param spec    the scenario to run
     * @param scratch a per-run empty directory the adapter may write into (it is the child's
     *                working directory unless the adapter chooses another)
     */
    LaunchSpec launch(ScenarioSpec spec, Path scratch);

    /**
     * Maps an exit status onto a {@link RunDisposition}. The default is the measured OpenCybele
     * table (see {@code docs/headless-and-stop.md}); every implementation is expected to honour it,
     * so overriding this is a deliberate statement that a target cannot.
     */
    default RunDisposition classifyExit(int exitCode) {
        return switch (exitCode) {
            case 0 -> RunDisposition.BOUND_REACHED;
            case 1 -> RunDisposition.STARTUP_ERROR;
            case 2 -> RunDisposition.WINDOW_CLOSED_EARLY;
            case 3 -> RunDisposition.WALL_CLOCK_TIMEOUT;
            case 4 -> RunDisposition.STALL;
            case 5 -> RunDisposition.CLOCK_COMMAND_DEAD;
            case 255 -> RunDisposition.AGENT_CONSTRUCTION_THROWABLE;
            default -> RunDisposition.UNKNOWN;
        };
    }

    /**
     * Line prefixes that mark a captured line as a <em>diagnostic</em> rather than a trace line.
     *
     * <p>{@code redirectErrorStream(true)} merges stderr into the trace, and the implementations
     * print per-run-varying diagnostics there: a resolved-configuration banner, a randomness
     * manifest, the timer-service and clock-check timings, and a stop banner. None of that can go
     * into a golden. Prefix is the discriminator because the diagnostics arrive as multi-line
     * banners whose bodies carry no other marker, and because trace lines are known never to start
     * with one of these.
     *
     * <p>The default covers the OpenCybele baseline's three shapes: {@code "--- "} banner rules,
     * {@code "!!! "} warnings and failures, and the two-space-indented banner bodies.
     */
    default List<String> diagnosticPrefixes() {
        return List.of("--- ", "---", "!!! ", "  ");
    }

    /**
     * The normalizer applied to the captured stream before it is recorded or compared. The default
     * drops diagnostics per {@link #diagnosticPrefixes()} and nothing else; <a
     * href="https://github.com/bedaHovorka/OpenCybele1/issues/21">#21</a> replaces it.
     */
    default TraceNormalizer normalizer() {
        return new DiagnosticFilter(diagnosticPrefixes());
    }
}
