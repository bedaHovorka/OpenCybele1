import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import cybele.kernel.Activity;
import cybele.kernel.Agent;
import cybele.kernel.Cybele;
import cybele.kernel.CybeleEvent;
import cybele.kernel.Handler;

/**
 * Experiment C: does ONE Cybele channel name support TWO simultaneous
 * subscribers, i.e. do two Activity.openChannel calls on the same name both
 * fire on a single Activity.sendAll?  (Issue #20's probe agent depends on it.)
 *
 * Three variants are exercised on three distinct channel names:
 *   SHARED_SAME_ACT   two openChannel calls, same activity, different methods
 *   SHARED_TWO_ACT    two openChannel calls, two activities of the SAME agent
 *   SHARED_TWO_AGENT  two openChannel calls, two DIFFERENT agents
 */
public class ExpC {
    static final List<String> log = Collections.synchronizedList(new ArrayList<String>());
    static final CountDownLatch aReady = new CountDownLatch(1);
    static final CountDownLatch bReady = new CountDownLatch(1);
    static final CountDownLatch cReady = new CountDownLatch(2);

    static void log(String s) { log.add(s); }

    public static class SecondActivity implements Handler {
        private static final long serialVersionUID = 1L;
        public SecondActivity() {
            Activity.openChannel("SHARED_TWO_ACT", "h", this);
            bReady.countDown();
        }
        public void h(CybeleEvent ev) { log("SHARED_TWO_ACT  subscriber#2 (2nd activity) got " + ev.getMessage()[0]); }
    }

    public static class AgentA implements Handler {
        private static final long serialVersionUID = 1L;
        public AgentA() {
            // variant 1: same activity, same channel, two different callbacks
            Activity.openChannel("SHARED_SAME_ACT", "h1", this);
            Activity.openChannel("SHARED_SAME_ACT", "h2", this);
            // variant 2: this activity + a second activity of the same agent
            Activity.openChannel("SHARED_TWO_ACT", "h3", this);
            Agent.createActivity("second", SecondActivity.class.getName(), new Object[]{});
            // variant 3: first of two agents
            Activity.openChannel("SHARED_TWO_AGENT", "h4", this);
            aReady.countDown();
            cReady.countDown();
        }
        public void h1(CybeleEvent ev) { log("SHARED_SAME_ACT subscriber#1 (h1) got " + ev.getMessage()[0]); }
        public void h2(CybeleEvent ev) { log("SHARED_SAME_ACT subscriber#2 (h2) got " + ev.getMessage()[0]); }
        public void h3(CybeleEvent ev) { log("SHARED_TWO_ACT  subscriber#1 (1st activity) got " + ev.getMessage()[0]); }
        public void h4(CybeleEvent ev) { log("SHARED_TWO_AGENT subscriber#1 (agentA) got " + ev.getMessage()[0]); }
    }

    public static class AgentB implements Handler {
        private static final long serialVersionUID = 1L;
        public AgentB() {
            Activity.openChannel("SHARED_TWO_AGENT", "h", this);
            cReady.countDown();
        }
        public void h(CybeleEvent ev) { log("SHARED_TWO_AGENT subscriber#2 (agentB) got " + ev.getMessage()[0]); }
    }

    public static class Sender implements Handler {
        private static final long serialVersionUID = 1L;
        public Sender() {
            try { aReady.await(); bReady.await(); cReady.await(); } catch (InterruptedException e) { /* ignore */ }
            Activity.sendAll("SHARED_SAME_ACT",  new Serializable[]{"msgSAME"});
            Activity.sendAll("SHARED_TWO_ACT",   new Serializable[]{"msgACT"});
            Activity.sendAll("SHARED_TWO_AGENT", new Serializable[]{"msgAGENT"});
        }
    }

    public static void main(String[] args) throws Exception {
        Cybele.startUp();
        Cybele.createAgent("agentA", AgentA.class.getName());
        Cybele.createAgent("agentB", AgentB.class.getName());
        Cybele.createAgent("sender", Sender.class.getName());
        Thread.sleep(5000);
        System.out.println("=== ExpC deliveries (" + log.size() + " handler invocations, 6 = all fan-out works) ===");
        List<String> sorted = new ArrayList<String>(log);
        Collections.sort(sorted);
        for (String s : sorted) System.out.println("   " + s);
        System.exit(0);
    }
}
