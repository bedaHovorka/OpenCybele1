package cz.vutbr.fit.ags.parity.spec;

import cz.vutbr.fit.ags.parity.spi.RunDisposition;

/**
 * The ending a scenario declares, and the only endings it is allowed to declare.
 *
 * <p>Deliberately narrower than {@link RunDisposition}: a wall-clock timeout, a stall or a dead
 * clock command channel can never be a scenario's expected outcome, because each of them means the
 * run did not reach the point the golden describes. Naming one here is rejected at parse time
 * rather than at comparison time.
 */
public enum ExpectedOutcome {

    /** The run reached a declared stop bound. Exit 0. The normal case. */
    BOUND_REACHED(RunDisposition.BOUND_REACHED, "bound-reached"),

    /**
     * The run refused its configuration before the kernel started. Exit 1. For scenarios that pin
     * validation behaviour; such a scenario will normally also need an {@code allowErrorLines}
     * entry, since the rejection is printed as an uncaught exception.
     */
    STARTUP_ERROR(RunDisposition.STARTUP_ERROR, "startup-error");

    private final RunDisposition disposition;
    private final String yamlName;

    ExpectedOutcome(RunDisposition disposition, String yamlName) {
        this.disposition = disposition;
        this.yamlName = yamlName;
    }

    public RunDisposition disposition() {
        return disposition;
    }

    public String yamlName() {
        return yamlName;
    }

    public static ExpectedOutcome fromYaml(String text) {
        for (ExpectedOutcome outcome : values()) {
            if (outcome.yamlName.equalsIgnoreCase(text) || outcome.name().equalsIgnoreCase(text)) {
                return outcome;
            }
        }
        throw new IllegalArgumentException("run.expect: '" + text + "' is not a declarable outcome."
                + " Allowed: bound-reached, startup-error."
                + " A timeout, a stall or a dead clock channel is never a pass and cannot be expected.");
    }
}
