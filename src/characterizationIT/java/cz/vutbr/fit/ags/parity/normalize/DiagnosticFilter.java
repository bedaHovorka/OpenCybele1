package cz.vutbr.fit.ags.parity.normalize;

import cz.vutbr.fit.ags.parity.spi.TraceNormalizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Drops lines that begin with any of the declared diagnostic prefixes, and blank lines.
 *
 * <p>This is the <em>only</em> normalization the skeleton performs, and it exists to prove the
 * seam rather than to be sufficient: it removes the per-run-varying banners that
 * {@code redirectErrorStream(true)} folds into the trace, and leaves everything else exactly as
 * emitted. Timestamps, startup emission order and equal-millisecond interleaving are untouched —
 * those are <a href="https://github.com/bedaHovorka/OpenCybele1/issues/21">#21</a>, which will
 * chain onto or replace this class.
 */
public final class DiagnosticFilter implements TraceNormalizer {

    private final List<String> prefixes;

    public DiagnosticFilter(List<String> prefixes) {
        this.prefixes = List.copyOf(prefixes);
    }

    @Override
    public List<String> normalize(List<String> rawLines) {
        List<String> kept = new ArrayList<>(rawLines.size());
        for (String line : rawLines) {
            if (!line.isBlank() && !isDiagnostic(line)) {
                kept.add(line);
            }
        }
        return List.copyOf(kept);
    }

    private boolean isDiagnostic(String line) {
        for (String prefix : prefixes) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
