package cz.vutbr.fit.ags.parity.normalize;

import cz.vutbr.fit.ags.parity.spi.TraceNormalizer;

import java.util.List;

/**
 * Records the captured stream verbatim. Useful only for a fully deterministic child that prints no
 * diagnostics; a real implementation's stream contains per-run-varying banners, so pinning it with
 * this normalizer produces a golden that flakes on its first replay.
 */
public final class PassThroughNormalizer implements TraceNormalizer {

    @Override
    public List<String> normalize(List<String> rawLines) {
        return List.copyOf(rawLines);
    }
}
