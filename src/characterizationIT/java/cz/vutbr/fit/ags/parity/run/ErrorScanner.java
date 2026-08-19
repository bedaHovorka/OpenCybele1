package cz.vutbr.fit.ags.parity.run;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Scans a captured stream for evidence that something threw.
 *
 * <p><strong>Why this exists at all: the exit status does not carry it.</strong> Cybele invokes
 * agent constructors and handlers reflectively; a throwable inside one is wrapped in an
 * {@code InvocationTargetException}, printed to stderr by the kernel's agent thread, and the
 * process' exit status is left alone. No throwable location was found that changes it — including
 * an uncaught throwable in {@code Main.main}, after which the simulation carried on generating
 * trains. A harness that judged a run by {@code waitFor()} would pass every one of those.
 *
 * <p>Two signatures cover it: {@code AssertionError} (the assertions enabled by {@code -ea} on the
 * reproducible baseline) and {@code Exception in thread "} (the JVM's own uncaught-handler
 * banner, which is what the kernel's swallowing path ultimately prints through).
 *
 * <p><strong>Occurrence count is not failure count.</strong> {@code AssertionError} appears twice
 * per firing assertion — once in the kernel's wrapper message and once in the cause line — so this
 * class reports <em>hits</em> and never converts them to a number of failures.
 *
 * <p>The scan runs on the raw captured lines, before normalization. With
 * {@code redirectErrorStream(true)} the stack trace is inside the trace, so a normalizer that ran
 * first would either scrub the evidence or bake it into a golden as expected output.
 */
public final class ErrorScanner {

    /** The signatures. Deliberately literal: a clever regex here fails open. */
    public static final List<Pattern> SIGNATURES = List.of(
            Pattern.compile("AssertionError"),
            Pattern.compile("Exception in thread \""));

    private ErrorScanner() {
    }

    /**
     * @param lines            raw captured lines
     * @param allowed          patterns whose matching lines are exempt, from the scenario's
     *                         {@code allowErrorLines}
     * @return every offending line, prefixed with its 1-based line number; empty when clean
     */
    public static List<String> scan(List<String> lines, List<Pattern> allowed) {
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (matchesAny(line, SIGNATURES) && !matchesAny(line, allowed)) {
                hits.add("line " + (i + 1) + ": " + line);
            }
        }
        return hits;
    }

    private static boolean matchesAny(String line, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }
}
