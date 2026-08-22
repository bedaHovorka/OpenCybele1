/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The third of #37's three "cannot be tested at L1" items: {@code TraceProbe.MAX_FAILURE_REPORTS}
 * (#38).
 *
 * <h2>Why it needs a JVM of its own rather than a container</h2>
 *
 * This is the one class in the lane that boots no container — and it is here anyway, because what
 * it needs is not a platform but a <b>pristine process</b>. {@code TraceProbe.FAILURES} is a
 * {@code static AtomicInteger} with no reset, so the only way to reach the cap is to burn the
 * counter from zero up to it, and the only way to have it at zero is a JVM in which no other test
 * has reported a probe failure. The {@code test} lane runs everything in one JVM and
 * {@code TraceProbeTest} deliberately asserts only <em>deltas</em> for that reason; this lane forks
 * a JVM per class, so this class gets the counter at zero and can spend it. The first assertion
 * states that dependency out loud and fails if it stops holding.
 *
 * <h2>What the cap is for</h2>
 *
 * A systematic probe fault — a payload the renderer cannot read, one per message, for the whole run
 * — would otherwise write two stderr blocks per observation and bury the trace it broke under its
 * own complaints. So the reports stop at {@value cz.vutbr.fit.ags.xhovor07.TraceProbe#MAX_FAILURE_REPORTS}
 * and say once that they have stopped. <b>The count does not stop</b>, which is the part worth
 * pinning: {@code probeFailures()} is what tells a caller the trace has holes in it, and a capped
 * <em>count</em> would make a badly broken run indistinguishable from a mildly broken one.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ProbeFailureCapIT {

    @Test
    @DisplayName("failure reports stop at the cap, say so once, and the failure count keeps going")
    void the_report_cap_suppresses_reports_but_never_the_count() throws Exception {
        assertEquals(0, TraceProbe.probeFailures(),
                "this class needs a JVM whose FAILURES counter is untouched. That is what"
                        + " forkEvery = 1 on the integrationTest task buys, and the reason this"
                        + " class is in this lane despite booting no container.");

        final int cap = TraceProbe.MAX_FAILURE_REPORTS;
        final TraceProbe probe = new RecordingProbe();
        final ACLMessage undecodable = undecodable();

        final PrintStream realErr = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        final String reported;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            // One more than the cap: the last one must be counted and not reported.
            for (int i = 0; i < cap + 1; i++) {
                probe.observe(undecodable);
            }
        } finally {
            System.setErr(realErr);
            reported = captured.toString(StandardCharsets.UTF_8);
        }

        // Every report up to the cap is written...
        for (int n = 1; n <= cap; n++) {
            assertTrue(reported.contains("!!! PROBE FAILURE (" + n + "):"),
                    "report " + n + " of " + cap + " is missing from stderr");
        }
        // ...the cap announces itself exactly once...
        final String announcement = "further reports suppressed after " + cap + ".";
        assertEquals(1, occurrences(reported, announcement),
                "the 'suppressed' notice must be written exactly once, at the cap");
        // ...and the one past it is silent.
        assertFalse(reported.contains("!!! PROBE FAILURE (" + (cap + 1) + "):"),
                "report " + (cap + 1) + " is past the cap and must not be written. Deleting the"
                        + " `if (n > MAX_FAILURE_REPORTS) return;` guard fails here.");

        // But it WAS counted: the trace has cap+1 holes in it and probeFailures says so.
        assertEquals(cap + 1, TraceProbe.probeFailures(),
                "the cap bounds the REPORTS, never the count -- a capped count would make a run"
                        + " with a thousand holes look like a run with twenty");

        // The probe survived all of it. Under JADE a rethrow would kill the agent and turn a hole
        // in the trace into a truncation, which is why report() never rethrows.
        assertEquals(cap + 2, failuresAfterOneMore(probe, undecodable));
    }

    private static int failuresAfterOneMore(TraceProbe probe, ACLMessage undecodable) {
        probe.observe(undecodable);
        return TraceProbe.probeFailures();
    }

    /**
     * A message that carries a railway ontology slot and a payload that is not a railway record, so
     * {@code Messages.contentOf} throws inside {@code observe} and is caught by its
     * {@code catch (Throwable)}. That is the shape of a real probe fault: a well-addressed message
     * the renderer cannot turn into a trace line.
     */
    private static ACLMessage undecodable() throws Exception {
        final ACLMessage acl = new ACLMessage(ACLMessage.INFORM);
        acl.setOntology(Channel.STATION_INFO.id());
        acl.setContentObject("not a railway message");
        return acl;
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
