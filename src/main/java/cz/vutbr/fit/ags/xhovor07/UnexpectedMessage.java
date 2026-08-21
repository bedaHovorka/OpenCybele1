/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 *
 * Open Cybele 1
 *
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */
package cz.vutbr.fit.ags.xhovor07;

import cz.vutbr.fit.ags.railway.jade.Messages;
import jade.core.AID;
import jade.lang.acl.ACLMessage;

/**
 * What the five agents' drains say when they catch something, and the one distinction they have to
 * draw before saying it (#36).
 *
 * <h2>Why this exists</h2>
 *
 * {@code Templates.unexpected(Party)} is {@code not(inbound(self))} — the exact complement of a
 * kind of agent's inbound channel set. That is the right template for the purpose #27 gave it (a
 * railway message nobody has a behaviour for must be named, not left to rot in a queue), and
 * {@code RailwayMainAgent}'s and {@code Station}'s drains both carry a written warning that it is
 * <em>also</em> the complement of <b>everything</b>, so on a live platform it matches AMS traffic
 * as well.
 * <p>
 * <b>#36's first gate run turned that warning into four measured lines.</b> On
 * {@code opencybele-capacity}:
 *
 * <pre>
 * RoadAgent tr1: unexpected message, ontology=railway.ENTER_REPLY performative=FAILURE from=ams
 * RoadAgent tr1: unexpected message, ontology=railway.ROAD_STATE  performative=FAILURE from=ams
 * RoadAgent tr2: unexpected message, ontology=railway.ROAD_STATE  performative=FAILURE from=ams
 * Train     vl2: unexpected message, ontology=railway.LEAVE       performative=FAILURE from=ams
 * </pre>
 *
 * Those are the AMS telling a sender that a message could not be delivered, because the addressee
 * had already died — DEF-08's second {@code LEAVE} to a station a train has left, a state push to a
 * hub mid-teardown. The <b>baseline does exactly the same thing and says nothing</b>: a Cybele
 * {@code sendAll} to a channel with no subscriber is dropped silently (INVENTORY {@code SEM-06}).
 * So the behaviour is identical and only the reporting differs — which makes those four lines a
 * diagnostic, and made them, before this class existed, four lines of trace with no golden
 * counterpart.
 *
 * <h2>The two cases, and why they must not share a shape</h2>
 *
 * <ul>
 * <li><b>Platform housekeeping</b> — the sender is the AMS. Reported with the {@code !!! } prefix
 *     the harness declares as a diagnostic, so a human running the application sees it and a golden
 *     never does.</li>
 * <li><b>A railway message with no handler</b> — a template gap, i.e. a <em>port bug</em>, and the
 *     one thing this drain was actually built to catch. Reported the same way {@code TraceProbe}
 *     reports a probe fault: a readable {@code !!! } line, followed by a stack trace under the
 *     JVM's own {@code Exception in thread "…"} header, which is one of the signatures the parity
 *     harness scans the <b>raw</b> stream for before it normalizes anything
 *     ({@code docs/parity-harness.md} §4). The run can then never pass a scenario or be recorded as
 *     a golden.</li>
 * </ul>
 *
 * Neither case throws. The queue is drained either way, because one stray message must not be able
 * to wedge an agent — and under JADE a throw out of a behaviour would kill the agent, turning a
 * reportable anomaly into a hole in the run.
 */
final class UnexpectedMessage {

    private UnexpectedMessage() { /* no instances */ }

    /**
     * Report a message an agent has no handler for, and say which of the two kinds it is.
     *
     * @param kind the reporting class' name, e.g. {@code RoadAgent} — kept as a parameter rather
     *        than derived, because {@code Planning} is an activity of {@code Main} and reports
     *        under its own name exactly as it did in 2008
     * @param self the reporting agent's local name
     * @param ams the platform's AMS, from {@code Agent.getAMS()}; may be {@code null} in a POJO
     *        test, in which case nothing can be attributed to the platform
     * @param acl the offending message
     */
    static void report(String kind, String self, AID ams, ACLMessage acl) {
	final String sender = Messages.senderName(acl);
	final String head = kind + " " + self + ": ";
	final String describe = "ontology=" + acl.getOntology() + " performative="
		+ ACLMessage.getPerformative(acl.getPerformative()) + " from=" + sender;
	if (ams != null && sender != null && sender.equals(ams.getLocalName())) {
	    System.err.println("!!! " + head + "platform message dropped (" + describe + ")."
		    + " This is the AMS reporting that a message could not be delivered -- almost"
		    + " always to an agent that has already died. The baseline drops the same send"
		    + " silently (INVENTORY SEM-06), so this is a diagnostic and not a trace line.");
	    return;
	}
	System.err.println("!!! " + head + "unexpected message (" + describe + "). A railway"
		+ " message that no behaviour of this agent matches is a TEMPLATE GAP -- a port"
		+ " bug -- and it is what this drain exists to catch. Consumed, not rethrown: a"
		+ " throw out of a behaviour would kill the agent and turn one stray message into"
		+ " a hole in the run.");
	// The shape docs/parity-harness.md section 4 scans the RAW stream for, so this run can
	// never pass a scenario or be recorded as a golden. Same idiom as TraceProbe.report.
	System.err.print("Exception in thread \"" + Thread.currentThread().getName() + "\" ");
	new IllegalStateException(head + "unexpected message, " + describe)
		.printStackTrace(System.err);
	System.err.flush();
    }
}
