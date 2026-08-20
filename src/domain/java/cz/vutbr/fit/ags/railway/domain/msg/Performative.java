/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

/**
 * The six FIPA communicative acts this application needs, and nothing else.
 * <p>
 * <strong>Cybele has no performatives at all</strong> — the only routing information in the
 * baseline is the channel-name string ({@code docs/INVENTORY.md} §4), and the canonical parity
 * trace writes a literal {@code -} in field 6 for exactly that reason
 * ({@code docs/trace-format.md}, "Why {@code performative} is empty"). Every value below is
 * therefore a <em>decision</em> taken by issue #27, justified per channel in
 * {@code docs/message-ontology.md}, not a fact recovered from the source.
 * <p>
 * The enum lives in the framework-free {@code domain} source set and carries the FIPA act name
 * as a {@code String}. That is what keeps it usable from the {@code jade} source set — where
 * {@code ACLMessage.getInteger(fipaName())} turns it into JADE's {@code int} with no hard-coded
 * table — and from branch {@code jason} (#46), where the same acts are reached through Jason's
 * KQML-style performative set. A {@code jade.lang.acl.ACLMessage} constant here would have made
 * the enum unusable on both counts and would fail {@code domainPurity}.
 */
public enum Performative {
    /** FIPA {@code request}: the sender wants the receiver to perform an action. */
    REQUEST("REQUEST"),
    /** FIPA {@code inform}: the sender states a proposition it believes true. */
    INFORM("INFORM"),
    /** FIPA {@code cfp}: call for proposals — opens the election. */
    CFP("CFP"),
    /** FIPA {@code propose}: a voter's answer to the call. */
    PROPOSE("PROPOSE"),
    /** FIPA {@code accept-proposal}: the agreed slot, broadcast to every voter on the path. */
    ACCEPT_PROPOSAL("ACCEPT-PROPOSAL"),
    /** FIPA {@code query-ref}: ask for the referent of an expression. */
    QUERY_REF("QUERY-REF");

    private final String fipaName;

    Performative(String fipaName) {
        this.fipaName = fipaName;
    }

    /**
     * The FIPA communicative-act name, spelled the way JADE spells it — hyphenated, so
     * {@code ACCEPT-PROPOSAL} and {@code QUERY-REF} rather than the Java identifier.
     * Verified round-trippable through {@code ACLMessage.getInteger}/{@code getPerformative}
     * on {@code net.sf.ingenias:jade:4.3}.
     *
     * @return the act name
     */
    public String fipaName() {
        return fipaName;
    }
}
