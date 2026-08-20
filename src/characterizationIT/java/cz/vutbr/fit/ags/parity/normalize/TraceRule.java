package cz.vutbr.fit.ags.parity.normalize;

/**
 * One named, independently testable normalizer rule.
 *
 * <p>Every rule this harness applies exists because a variance source was <em>measured</em>, and
 * the measurement travels with the rule rather than living only in a document that can drift away
 * from the code. {@link #id()} names the rule in failure reports and in the mutation tests;
 * {@link #because()} is the observed variance source that justifies it.
 *
 * <p><strong>A rule must be able to fail.</strong> This project has rejected four "locks that
 * cannot fail"; a normalizer rule that silently matches everything, or that never matches
 * anything, is the same defect wearing a different hat. Each rule is therefore exercised in both
 * directions by {@code TraceNormalizerIT}: it must change an input that carries the variance, and
 * it must leave an input that does not carry it alone.
 */
public interface TraceRule {

    /** Stable short id, e.g. {@code tick}. Used by tests and failure reports. */
    String id();

    /** The observed variance source that justifies this rule. One sentence, with the measurement. */
    String because();
}
