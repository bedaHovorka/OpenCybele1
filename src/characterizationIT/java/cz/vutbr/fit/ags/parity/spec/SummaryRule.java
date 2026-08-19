package cz.vutbr.fit.ags.parity.spec;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One aggregate a {@link ContractLevel#SUMMARY} scenario compares: how many lines match a pattern,
 * within a tolerance.
 *
 * @param label     name used in the failure report
 * @param pattern   regex matched against each normalized line
 * @param tolerance permitted absolute difference between golden and actual counts
 */
public record SummaryRule(String label, Pattern pattern, int tolerance) {

    public SummaryRule {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("summary rule needs a label");
        }
        if (pattern == null) {
            throw new IllegalArgumentException("summary rule needs a pattern");
        }
        if (tolerance < 0) {
            throw new IllegalArgumentException("summary.tolerance must not be negative");
        }
    }

    public int count(List<String> lines) {
        int n = 0;
        for (String line : lines) {
            if (pattern.matcher(line).find()) {
                n++;
            }
        }
        return n;
    }
}
