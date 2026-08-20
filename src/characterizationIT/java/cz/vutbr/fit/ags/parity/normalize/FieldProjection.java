package cz.vutbr.fit.ags.parity.normalize;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rule that rewrites <em>one line in place</em>: it replaces a run-varying value with a fixed
 * token and changes nothing else. Projections never add, remove or move a line.
 *
 * <p>Each projection is a regex plus a replacement, so it can be unit-tested on a single string
 * with no run, no child process and no golden. {@link #appliesToCanonicalLinesOnly()} says whether
 * the rule may only touch a seven-field canonical line ({@code docs/trace-format.md}) — field-index
 * rules must, because their pattern would otherwise match a shape that merely resembles one.
 *
 * @param id                          stable short id
 * @param because                     the measured variance source
 * @param pattern                     what the value looks like
 * @param replacement                 the projected form, with {@code $1}-style group references
 * @param appliesToCanonicalLinesOnly restrict to seven-field canonical lines
 */
public record FieldProjection(String id, String because, Pattern pattern, String replacement,
        boolean appliesToCanonicalLinesOnly) implements TraceRule {

    public FieldProjection {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a projection needs an id");
        }
        if (because == null || because.isBlank()) {
            throw new IllegalArgumentException("projection '" + id + "' needs a justification:"
                    + " every rule must name the variance source it was measured against");
        }
    }

    /** @return {@code line} with the value projected, or {@code line} unchanged if it does not carry one */
    public String apply(String line, boolean canonical) {
        if (appliesToCanonicalLinesOnly && !canonical) {
            return line;
        }
        Matcher m = pattern.matcher(line);
        return m.find() ? m.replaceAll(replacement) : line;
    }

    /** True when this projection would change {@code line}. Used by the rule tests. */
    public boolean matches(String line, boolean canonical) {
        return !apply(line, canonical).equals(line);
    }
}
