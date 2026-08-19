package cz.vutbr.fit.ags.parity.spec;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A floor (and optional ceiling) on how much a run actually did.
 *
 * <p>This exists because of a measured third failure mode invisible to both the exit status and an
 * exception grep: a throwable inside {@code Generator.generateTrain} before its timer re-arms stops
 * train generation <em>permanently</em> while the process keeps its clock, keeps painting, reaches
 * its simulated-time bound and exits 0. Nothing about that run is red. Only "it produced far less
 * than it should have" catches it, and the harness has to ask.
 *
 * <p>The child's own stall detector (exit 4) covers the same hazard from the inside; this is the
 * outside half, and it also catches a port that quietly stops emitting a whole class of line.
 *
 * <p>Liveness is evaluated on the <strong>raw</strong> captured lines, before normalization, so
 * that a rule authored today does not have to be re-authored when #21 lands.
 *
 * @param pattern       regex matched against each line ({@link Matcher#find()} semantics)
 * @param atLeast       minimum number of matches (or of distinct captures, see {@code distinctGroup})
 * @param atMost        optional maximum, or {@code null}
 * @param distinctGroup when &gt; 0, count <em>distinct</em> values of that capture group rather
 *                      than matches; guards against one repeated line satisfying a floor
 */
public record LivenessRule(Pattern pattern, int atLeast, Integer atMost, int distinctGroup) {

    public LivenessRule {
        if (pattern == null) {
            throw new IllegalArgumentException("liveness rule needs a pattern");
        }
        if (atLeast < 1) {
            // atLeast: 0 satisfies the "every scenario needs a liveness rule" mandate while
            // asserting nothing, which is worse than having no rule: it looks like a floor.
            throw new IllegalArgumentException("liveness.atLeast must be at least 1 for /" + pattern
                    + "/; a floor of 0 is satisfied by a run that produced nothing at all");
        }
        if (atMost != null && atMost < atLeast) {
            throw new IllegalArgumentException("liveness.atMost (" + atMost
                    + ") is below liveness.atLeast (" + atLeast + ") for /" + pattern + "/");
        }
        if (distinctGroup < 0) {
            throw new IllegalArgumentException("liveness.distinctGroup must not be negative");
        }
    }

    /** @return the count this rule measures over {@code lines} */
    public int count(List<String> lines) {
        int matches = 0;
        Set<String> distinct = new HashSet<>();
        for (String line : lines) {
            Matcher m = pattern.matcher(line);
            if (m.find()) {
                matches++;
                if (distinctGroup > 0 && m.groupCount() >= distinctGroup) {
                    distinct.add(m.group(distinctGroup));
                }
            }
        }
        return distinctGroup > 0 ? distinct.size() : matches;
    }

    /** @return a failure description, or empty when the rule holds */
    public Optional<String> check(List<String> lines) {
        int actual = count(lines);
        String what = distinctGroup > 0 ? "distinct group(" + distinctGroup + ") values" : "matches";
        if (actual < atLeast) {
            return Optional.of("liveness: /" + pattern + "/ produced " + actual + " " + what
                    + ", expected at least " + atLeast
                    + " — the run exited without doing what the scenario declares."
                    + " A simulation can die silently and still exit 0.");
        }
        if (atMost != null && actual > atMost) {
            return Optional.of("liveness: /" + pattern + "/ produced " + actual + " " + what
                    + ", expected at most " + atMost);
        }
        return Optional.empty();
    }
}
