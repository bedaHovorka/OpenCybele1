package cz.vutbr.fit.ags.parity.spi;

import java.util.List;

/**
 * Projects a captured run down to the lines a golden is allowed to contain.
 *
 * <p><strong>This skeleton deliberately ships only the trivial implementations.</strong> The real
 * normalizer — timestamp projection, startup-block sorting by agent name, canonicalisation of
 * equal-millisecond interleavings — is <a
 * href="https://github.com/bedaHovorka/OpenCybele1/issues/21">#21</a>. What this interface fixes
 * now is the <em>position</em> of that work in the pipeline: normalization happens
 * <strong>after</strong> the error scan and the liveness check, never before. With
 * {@code redirectErrorStream(true)} a firing assertion's stack trace is part of the captured
 * stream, so a normalizer that ran first would either scrub the evidence or bake it into a golden
 * as expected output.
 *
 * <p>Implementations must be pure and deterministic: the same input list must always produce the
 * same output list.
 */
@FunctionalInterface
public interface TraceNormalizer {

    List<String> normalize(List<String> rawLines);
}
