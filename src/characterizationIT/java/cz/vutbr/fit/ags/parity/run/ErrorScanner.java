package cz.vutbr.fit.ags.parity.run;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Scans a captured stream for evidence that something threw.
 *
 * <p><strong>Why this exists at all: the exit status does not carry it.</strong> Cybele invokes
 * agent handlers reflectively; a throwable inside one is wrapped in an
 * {@code InvocationTargetException}, handed to the kernel's exception handler, printed to stderr,
 * and the process' exit status is left alone. A harness that judged a run by {@code waitFor()}
 * would pass every one of those. (Agent <em>construction</em> is a different path and is loud —
 * a throwable escaping {@code RailwayMainAgent}'s constructor exits 255 — which is itself a warning
 * against generalising any single measurement into a rule about "the kernel".)
 *
 * <h2>The signature list, and how it was derived</h2>
 *
 * <p>An earlier revision of this class scanned for {@code AssertionError} and
 * {@code Exception in thread "} only, and claimed those two "cover it". They do not, and the
 * overclaim is instructive: {@code docs/assertion-triage.md}, the cited evidence, only ever
 * measured <em>planted {@code AssertionError}s</em>. Its recommendation was right for assertions
 * and was then generalised past what it measured. For a non-assertion throwable swallowed in a
 * handler — an NPE, an {@code IllegalStateException}, an {@code IndexOutOfBoundsException}, i.e.
 * the shapes a real port bug actually produces — the captured stream contains
 * <strong>neither</strong> of those two strings, and such a run was reproduced passing the scan and
 * being recorded as a golden with the NPE baked in as expected output.
 *
 * <p>The list below is instead read out of the vendored kernel. {@code javap -c} on
 * {@code com/iai/cybele/exception/IAIExceptionHandler} shows {@code print(header, throwable)}
 * writing {@code "\n***" + header + " ->"} to {@code System.err} before anything else, then, by
 * stack-trace option, {@code toString()} or {@code printStackTrace()}, plus
 * {@code "Originated from --- "}, {@code "Exception thrown by target ---"},
 * {@code "Unsupported stack trace option"} and {@code "Thrown in Thread '"}. The same on
 * {@code com/iai/cybele/thmgmt/IAIAgentThread} shows every reflective-dispatch failure routed
 * through {@code handleException("Thread Mgmt Exception", …)} carrying one of
 * {@code "InvocationTargetException occured in"}, {@code "ClassCastException occured in"},
 * {@code "General Exception occured in"} or {@code "Failed to invoke the method"} — the kernel's
 * own spelling of "occured" included.
 *
 * <p>So the banner line is emitted for <em>every</em> swallowed throwable regardless of type or of
 * the configured trace option, which is what makes it the load-bearing signature rather than the
 * type names. The JVM-level ones ({@code OutOfMemoryError}, {@code StackOverflowError}, the
 * hs_err crash banner) are there because a run that died that way is not a run that produced a
 * trace.
 *
 * <p><strong>Occurrence count is not failure count.</strong> One firing assertion prints
 * {@code AssertionError} twice — the wrapper message and the cause line — and the kernel path adds
 * a banner and a stack trace on top. This class reports <em>hits</em> and never converts them to a
 * number of failures.
 *
 * <p>The scan runs on the raw captured lines, before normalization. With
 * {@code redirectErrorStream(true)} the stack trace is inside the trace, so a normalizer that ran
 * first would either scrub the evidence or bake it into a golden as expected output.
 */
public final class ErrorScanner {

    /**
     * The signatures. Deliberately literal: a clever regex here fails open, and every entry below
     * is either a string disassembled out of the vendored kernel or a banner the JVM itself prints.
     */
    public static final List<Pattern> SIGNATURES = List.of(
            // --- JVM-level -------------------------------------------------------------------
            Pattern.compile("AssertionError"),
            Pattern.compile("Exception in thread \""),
            Pattern.compile("OutOfMemoryError"),
            Pattern.compile("StackOverflowError"),
            Pattern.compile("^# A fatal error has been detected"),
            Pattern.compile("java\\.lang\\.reflect\\.InvocationTargetException"),
            // --- IAIExceptionHandler.print ---------------------------------------------------
            Pattern.compile("^\\*\\*\\*.* ->"),
            Pattern.compile("Originated from --- "),
            Pattern.compile("Exception thrown by target ---"),
            Pattern.compile("Thrown in Thread '"),
            Pattern.compile("Unsupported stack trace option"),
            // --- IAIAgentThread reflective dispatch ------------------------------------------
            Pattern.compile("Thread Mgmt Exception"),
            Pattern.compile("Failed to invoke the method"),
            Pattern.compile("(InvocationTargetException|ClassCastException|General Exception) occured in"));

    private ErrorScanner() {
    }

    /**
     * What the scan saw.
     *
     * @param hits     offending lines, each prefixed with its 1-based line number
     * @param exempted lines that matched a signature but were silenced by {@code allowErrorLines}
     */
    public record ScanResult(List<String> hits, List<String> exempted) {

        public ScanResult {
            hits = List.copyOf(hits);
            exempted = List.copyOf(exempted);
        }

        public boolean clean() {
            return hits.isEmpty();
        }
    }

    /**
     * @param lines   raw captured lines
     * @param allowed patterns whose matching lines are exempt, from the scenario's
     *                {@code allowErrorLines}
     */
    public static ScanResult scan(List<String> lines, List<Pattern> allowed) {
        List<String> hits = new ArrayList<>();
        List<String> exempted = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!matchesAny(line, SIGNATURES)) {
                continue;
            }
            String located = "line " + (i + 1) + ": " + line;
            if (matchesAny(line, allowed)) {
                exempted.add(located);
            } else {
                hits.add(located);
            }
        }
        return new ScanResult(hits, exempted);
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
