package cz.vutbr.fit.ags.parity.run;

import java.nio.file.Path;
import java.util.List;

/**
 * What one successful scenario produced. Returned so a caller can inspect a passing run; a failing
 * one arrives as a {@link ScenarioFailedException} instead.
 *
 * @param captured   the raw child process result
 * @param normalized the trace as recorded or compared
 * @param recorded   true when this run wrote the golden rather than compared against it
 * @param goldenFile the golden that was written or compared
 */
public record RunReport(CapturedRun captured, List<String> normalized, boolean recorded, Path goldenFile) {

    public RunReport {
        normalized = List.copyOf(normalized);
    }
}
