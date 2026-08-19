package cz.vutbr.fit.ags.parity.golden;

import java.util.List;

/**
 * The verdict of one golden comparison.
 *
 * @param matched  whether the run satisfied its contract
 * @param failures human-readable differences, empty when {@code matched}
 */
public record ComparisonResult(boolean matched, List<String> failures) {

    public ComparisonResult {
        failures = List.copyOf(failures);
    }

    public static ComparisonResult match() {
        return new ComparisonResult(true, List.of());
    }

    public static ComparisonResult mismatch(List<String> failures) {
        if (failures.isEmpty()) {
            throw new IllegalArgumentException("a mismatch must say what differed");
        }
        return new ComparisonResult(false, failures);
    }
}
