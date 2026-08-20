package cz.vutbr.fit.ags.parity.normalize;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rule that rewrites <em>one line in place</em>: it replaces a run-varying value with a fixed
 * token, or with a coarser form of itself, and changes nothing else. Projections never add, remove
 * or move a line.
 *
 * <p>Each projection is a regex plus a replacement, so it can be unit-tested on a single string
 * with no run, no child process and no golden. {@link #appliesToCanonicalLinesOnly()} says whether
 * the rule may only touch a seven-field canonical line ({@code docs/trace-format.md}) — field-index
 * rules must, because their pattern would otherwise match a shape that merely resembles one.
 *
 * <h2>Erasing versus quantising</h2>
 *
 * <p>{@link #erase} replaces the value with a constant token: the right answer when nothing about
 * the value is reproducible, as for the {@code tick} field or the {@code performative}.
 *
 * <p>{@link #quantise} keeps the value's <em>magnitude</em> at a declared resolution —
 * {@code diff=41904} becomes {@code diff=<T~41000>}. It is the right answer whenever a value's
 * run-to-run jitter is small against its signal, and the difference matters: erasing
 * {@code VOTE.diff} outright leaves a port free to return zero from every vote and still produce a
 * byte-identical golden, because the only payload carrying the election's arithmetic has been
 * projected out of existence. The measurements that set the quantum are in
 * {@code docs/trace-normalizer.md} §2.2.
 *
 * <p>Quantising floors: {@code v -> (v / quantum) * quantum}, so it is stable exactly when a
 * value's whole jitter band sits inside one bucket. That is a property of the data and it is
 * measured, not assumed — a value that straddles a bucket boundary flakes, and no amount of extra
 * headroom removes the possibility, only its probability. The consequence at the other end is that
 * <strong>values below one quantum are indistinguishable from zero</strong>.
 *
 * @param id                          stable short id
 * @param because                     the measured variance source
 * @param pattern                     what the value looks like
 * @param replacement                 the projected form: {@code $1}-style group references, plus
 *                                    the literal {@code {Q}} where a quantised rule puts its token
 * @param appliesToCanonicalLinesOnly restrict to seven-field canonical lines
 * @param quantum                     bucket width, or {@code 0} for a plain erase
 * @param numberGroup                 capture group holding the number, for a quantised rule
 */
public record FieldProjection(String id, String because, Pattern pattern, String replacement,
        boolean appliesToCanonicalLinesOnly, long quantum, int numberGroup) implements TraceRule {

    /** Marker that opens every projected time token, erased or quantised alike. */
    public static final String TIME_OPEN = "<T";

    public FieldProjection {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a projection needs an id");
        }
        if (because == null || because.isBlank()) {
            throw new IllegalArgumentException("projection '" + id + "' needs a justification:"
                    + " every rule must name the variance source it was measured against");
        }
        if (quantum < 0) {
            throw new IllegalArgumentException("projection '" + id + "': quantum must not be negative");
        }
        if (quantum > 0 && !replacement.contains("{Q}")) {
            throw new IllegalArgumentException("projection '" + id + "': a quantised rule's"
                    + " replacement must contain {Q}, or the bucket it computed goes nowhere");
        }
    }

    /** A rule that replaces the value with a constant token. */
    public static FieldProjection erase(String id, String because, Pattern pattern,
            String replacement, boolean canonicalOnly) {
        return new FieldProjection(id, because, pattern, replacement, canonicalOnly, 0L, 0);
    }

    /** A rule that keeps the value's magnitude, floored to {@code quantum}. */
    public static FieldProjection quantise(String id, String because, Pattern pattern,
            String replacement, boolean canonicalOnly, long quantum, int numberGroup) {
        if (quantum <= 0) {
            throw new IllegalArgumentException("projection '" + id + "': a quantised rule needs a"
                    + " positive quantum");
        }
        return new FieldProjection(id, because, pattern, replacement, canonicalOnly, quantum, numberGroup);
    }

    /** @return {@code line} with the value projected, or {@code line} unchanged if it carries none */
    public String apply(String line, boolean canonical) {
        if (appliesToCanonicalLinesOnly && !canonical) {
            return line;
        }
        Matcher m = pattern.matcher(line);
        if (!m.find()) {
            return line;
        }
        m.reset();
        StringBuilder out = new StringBuilder(line.length());
        while (m.find()) {
            m.appendReplacement(out, quantum == 0 ? replacement
                    : replacement.replace("{Q}", Matcher.quoteReplacement(bucketToken(m.group(numberGroup)))));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** {@code 41904} at quantum 1000 → {@code <T~41000>}; a value it cannot parse → {@code <T>}. */
    private String bucketToken(String value) {
        try {
            long parsed = Long.parseLong(value);
            return TIME_OPEN + "~" + (Math.floorDiv(parsed, quantum) * quantum) + ">";
        } catch (NumberFormatException notANumber) {
            return TIME_OPEN + ">";
        }
    }

    /** True when this projection would change {@code line}. Used by the rule tests. */
    public boolean matches(String line, boolean canonical) {
        return !apply(line, canonical).equals(line);
    }
}
