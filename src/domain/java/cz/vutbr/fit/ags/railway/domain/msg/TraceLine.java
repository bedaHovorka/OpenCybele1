/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

/**
 * Renders one line of the canonical parity trace: {@code agent|tick|event|from|to|performative|payload}.
 * <p>
 * Framework-free, so the Cybele probe, #36's JADE probe and #43's Jason {@code AgArch} can all
 * produce byte-identical lines from the same code instead of from three readings of
 * {@code docs/trace-format.md}. That document, not any one probe, is still the contract; this
 * class is its executable form.
 * <p>
 * <strong>Field 6 is a parameter, and that is the whole point of it existing.</strong> The
 * baseline has no performatives and writes the literal {@code -}. JADE and Jason write a real
 * act name. #21's normalizer erases field 6 before comparing, so the two are equal after
 * projection and unequal before it — which is exactly what the field was reserved for.
 * {@link #JADE} passes the performative #27 assigned; {@link #NONE} passes the baseline's dash.
 */
public final class TraceLine {

    /** Field-6 policy for a framework with no performatives: the literal {@code -}. */
    public static final boolean NONE = false;
    /** Field-6 policy for a framework that has them: {@link Performative#fipaName()}. */
    public static final boolean JADE = true;

    private TraceLine() {
    }

    /**
     * Render one trace line.
     *
     * @param message           the message
     * @param tick              the simulated clock as the probe reads it when it handles the
     *                          message; never wall time, and {@code -1} means "the clock did not
     *                          answer", which is a fault, not a legal time
     * @param from              the sending agent's local name — for CH-13 always {@code Main},
     *                          even in a port where {@code Generator} is an agent of its own
     * @param to                the addressed agent's local name
     * @param withPerformative  {@link #JADE} to write the FIPA act name in field 6,
     *                          {@link #NONE} to write the baseline's {@code -}
     * @return the line, without a trailing newline
     */
    public static String render(RailwayMessage message, long tick, String from, String to,
                                boolean withPerformative) {
        Channel channel = message.channel();
        return Payloads.escape(message.subject(from, to))
                + '|' + tick
                + '|' + channel.event()
                + '|' + Payloads.escape(from)
                + '|' + Payloads.escape(to)
                + '|' + (withPerformative ? channel.performative().fipaName() : "-")
                + '|' + message.payload();
    }
}
