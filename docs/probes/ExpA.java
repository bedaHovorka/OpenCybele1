package probe;

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
 * Experiment A: what is the effective Cybele event-queue discipline when BOTH
 * cybele.srv.evmgmt.app.param.iai lines in cybele.prop are commented out?
 *
 * Receiver opens 4 channels on ONE agent with different Subscription static
 * priorities (the 4th openChannel arg). A "BLOCK" event makes the handler sleep
 * so that the following 6 events pile up in the agent queue. The order in which
 * they are then dispatched reveals the discipline:
 *   send order preserved      -> FIFO   (no_sort / no_comp)
 *   high priority first       -> priority queue
 *   reverse send order        -> LIFO
 */
public class ExpA {
    static final List<String> log = Collections.synchronizedList(new ArrayList<String>());
    static final CountDownLatch receiverReady = new CountDownLatch(1);
    static final CountDownLatch done = new CountDownLatch(1);

    static void log(String s) {
        log.add(s);
        System.out.println("[log] " + s);
    }

    public static class Recv implements Handler {
        private static final long serialVersionUID = 1L;
        public Recv() {
            Activity.openChannel("BLOCK", "block", this, 5);
            Activity.openChannel("P_LOW", "onEvent", this, 1);
            Activity.openChannel("P_MID", "onEvent", this, 5);
            Activity.openChannel("P_HIGH", "onEvent", this, 9);
            receiverReady.countDown();
        }
        public void block(CybeleEvent ev) {
            log("block-begin");
            try { Thread.sleep(2500); } catch (InterruptedException e) { /* ignore */ }
            log("block-end");
        }
        public void onEvent(CybeleEvent ev) {
            log("recv tag=" + ev.getTag() + " seq=" + ev.getMessage()[0]
                    + " prio=" + ev.getPriority());
        }
    }

    public static class Send implements Handler {
        private static final long serialVersionUID = 1L;
        public Send() {
            try { receiverReady.await(); } catch (InterruptedException e) { /* ignore */ }
            Activity.sendAll("BLOCK", new Serializable[]{"0"});
            try { Thread.sleep(400); } catch (InterruptedException e) { /* ignore */ }
            // receiver is now asleep inside block(); these 6 queue up
            Activity.sendAll("P_MID",  new Serializable[]{"1"});
            Activity.sendAll("P_LOW",  new Serializable[]{"2"});
            Activity.sendAll("P_HIGH", new Serializable[]{"3"});
            Activity.sendAll("P_MID",  new Serializable[]{"4"});
            Activity.sendAll("P_HIGH", new Serializable[]{"5"});
            Activity.sendAll("P_LOW",  new Serializable[]{"6"});
            log("all 6 sent, order 1..6 (mid,low,high,mid,high,low)");
            done.countDown();
        }
    }

    public static void main(String[] args) throws Exception {
        Cybele.startUp();
        Cybele.createAgent("recv", Recv.class.getName());
        Cybele.createAgent("send", Send.class.getName());
        done.await();
        Thread.sleep(4000);
        System.out.println("=== ExpA dispatch order ===");
        for (String s : log) System.out.println("   " + s);
        System.exit(0);
    }
}
