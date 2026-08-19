package cz.vutbr.fit.ags.parity.run;

/**
 * One scenario did not hold. Carries the whole report, already formatted.
 *
 * <p>The harness raises this rather than calling a JUnit assertion so that it stays free of any
 * test framework: {@code jason} runs its tests through Jason's own runner, and the parity suite has
 * to be drivable from there unchanged.
 */
public class ScenarioFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ScenarioFailedException(String message) {
        super(message);
    }
}
