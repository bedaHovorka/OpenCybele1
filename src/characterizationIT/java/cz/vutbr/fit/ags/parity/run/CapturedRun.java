package cz.vutbr.fit.ags.parity.run;

import cz.vutbr.fit.ags.parity.spi.LaunchSpec;
import cz.vutbr.fit.ags.parity.spi.RunDisposition;

import java.util.List;

/**
 * The raw result of one child process: what it printed, what it exited with, and how long it took.
 *
 * <p>{@code lines} is the <em>merged</em> stream — stderr is redirected into stdout so that a
 * stack trace cannot arrive out of band. That is also why diagnostics have to be filtered out
 * before recording, and why the error scan runs on this list rather than on the normalized one.
 *
 * @param launch         the command line that produced this
 * @param exitCode       the child's exit status, or -1 when the harness killed it
 * @param disposition    the exit status, classified
 * @param lines          the merged stdout+stderr stream, split into lines
 * @param wallClockMs    how long the child ran, in real milliseconds
 */
public record CapturedRun(LaunchSpec launch, int exitCode, RunDisposition disposition,
        List<String> lines, long wallClockMs) {

    public CapturedRun {
        lines = List.copyOf(lines);
    }

    /** The last {@code n} lines, for a failure report — the stop banner lives at the end. */
    public List<String> tail(int n) {
        return lines.subList(Math.max(0, lines.size() - n), lines.size());
    }
}
