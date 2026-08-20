package cz.vutbr.fit.ags.parity.normalize;

import cz.vutbr.fit.ags.parity.spi.TraceNormalizer;

import java.util.List;

/**
 * Two normalizers, applied in order.
 *
 * <p>A named class rather than a lambda for one reason that is not cosmetic: when the pipeline
 * projects away everything a run printed, {@code ScenarioRunner}'s failure report names the
 * normalizer responsible, and {@code SomeClass$$Lambda$47/0x00007f} names nothing. The chain's
 * {@link #toString()} spells both stages out.
 */
public record ChainedNormalizer(TraceNormalizer first, TraceNormalizer next) implements TraceNormalizer {

    @Override
    public List<String> normalize(List<String> rawLines) {
        return next.normalize(first.normalize(rawLines));
    }

    @Override
    public String toString() {
        return first + " -> " + next;
    }
}
