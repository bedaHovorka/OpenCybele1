package cz.vutbr.fit.ags.parity.run;

/**
 * A condition that invalidates every later scenario in the same suite, not just this one.
 *
 * <p>The measured case is exit 5 — the simulation clock stopped answering its command channel.
 * A run in that state keeps its clock advancing, keeps generating trains and exits green, while
 * every pause, resume and {@code setTimer} is a no-op. A golden recorded against it is wrong with
 * no symptom, and there is no reason to believe the next scenario in the same environment fares
 * better. So the first occurrence latches: {@link ScenarioRunner} refuses to launch anything
 * afterwards and says why.
 *
 * <p>An {@link Error} rather than an exception because no scenario should catch it, and because
 * JUnit offers no portable "abort the remaining tests" — latching plus a loud failure is the
 * mechanism that works in every runner the three branches use.
 */
public class SuiteFatalError extends Error {

    private static final long serialVersionUID = 1L;

    public SuiteFatalError(String message) {
        super(message);
    }
}
