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

    /**
     * This normalizer followed by {@code next}.
     *
     * <p>The two halves of the seam compose rather than replace one another: an adapter declares
     * <em>its target's</em> diagnostic shapes ({@link cz.vutbr.fit.ags.parity.normalize.DiagnosticFilter}),
     * and the implementation-neutral projection
     * ({@link cz.vutbr.fit.ags.parity.normalize.CanonicalTraceNormalizer}) runs after it on what is
     * left. Keeping them separate is what lets a new target declare a new banner prefix without
     * touching a single normalizer rule.
     */
    default TraceNormalizer andThen(TraceNormalizer next) {
        return new cz.vutbr.fit.ags.parity.normalize.ChainedNormalizer(this, next);
    }
}
