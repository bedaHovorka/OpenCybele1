import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import cybele.kernel.Activity;
import cybele.kernel.Cybele;
import cybele.kernel.CybeleEvent;
import cybele.kernel.Handler;

/**
 * Experiment A, control run. Application MESSAGE events all carry the hardcoded
 * CybeleEvent priority 4 (DEFAULT_PRIORITY); TIMER events carry 8. So a timer
 * event queued in the MIDDLE of a burst of messages is the only lever the
 * application has on any priority comparator. Running this file under
 *   (i) default (both app.param lines commented) and
 *   (ii) agent_queue merge_sort staticpriority_comp
 * shows whether the probe is capable of observing a re-ordering at all.
 *
 * The (ii) control line is NOT either of the commented-out lines in cybele.prop
 * -- cybele.prop:66 puts merge_sort on the SYSTEM queue only. See
 * docs/probes/README.md for the exact line to substitute.
 *
 * KNOWN FLAKINESS — roughly 1 run in 6 dies during startup with
 *   NullPointerException ... TimerAgent.register ... because "this.ag" is null
 * thrown from com.iai.cybele.timer.IAITimerService.newClock. This is the same
 * startup race as INVENTORY.md SEM-02: a handler constructor calls createClock
 * before the kernel's timer-service agent is up. Re-run; it is not a defect in
 * the probe. Sleeping a few ms after Cybele.startUp() and before the first
 * createClock makes it go away (and also closes the SEM-02 race).
 */
public class ExpA2 {
    static final List<String> log = Collections.synchronizedList(new ArrayList<String>());
    static final CountDownLatch receiverReady = new CountDownLatch(1);
    static final CountDownLatch done = new CountDownLatch(1);
    static final String CLOCK = "probeClock";

    static void log(String s) { log.add(s); }

    public static class Recv implements Handler {
        private static final long serialVersionUID = 1L;
        public Recv() {
            Cybele.createClock(CLOCK, Cybele.HOST, 0, 1);
            Cybele.resumeClock(CLOCK);
            Activity.openChannel("BLOCK", "block", this);
            Activity.openChannel("MSG", "onEvent", this);
            receiverReady.countDown();
        }
        public void block(CybeleEvent ev) {
            log("block-begin");
            // arm a timer that will expire while we are still asleep
            Activity.setTimer(CLOCK, 800, this, "onTimer");
            try { Thread.sleep(2500); } catch (InterruptedException e) { /* ignore */ }
            log("block-end");
        }
        public void onEvent(CybeleEvent ev) {
            log("MSG seq=" + ev.getMessage()[0] + " type=" + ev.getEventType() + " prio=" + ev.getPriority());
        }
        public void onTimer(CybeleEvent ev) {
            log("TIMER type=" + ev.getEventType() + " prio=" + ev.getPriority());
        }
    }

    public static class Send implements Handler {
        private static final long serialVersionUID = 1L;
        public Send() {
            try { receiverReady.await(); } catch (InterruptedException e) { /* ignore */ }
            Activity.sendAll("BLOCK", new Serializable[]{"0"});
            try { Thread.sleep(300); } catch (InterruptedException e) { /* ignore */ }
            Activity.sendAll("MSG", new Serializable[]{"1"});
            Activity.sendAll("MSG", new Serializable[]{"2"});
            try { Thread.sleep(900); } catch (InterruptedException e) { /* ignore */ }
            // by now the 800ms timer event has been queued behind msgs 1 and 2
            Activity.sendAll("MSG", new Serializable[]{"3"});
            Activity.sendAll("MSG", new Serializable[]{"4"});
            done.countDown();
        }
    }

    public static void main(String[] args) throws Exception {
        Cybele.startUp();
        Cybele.createAgent("recv", Recv.class.getName());
        Cybele.createAgent("send", Send.class.getName());
        done.await();
        Thread.sleep(4000);
        System.out.println("=== ExpA2 dispatch order ===");
        for (String s : log) System.out.println("   " + s);
        System.exit(0);
    }
}
