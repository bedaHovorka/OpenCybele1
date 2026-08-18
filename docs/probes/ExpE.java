package probe;

import java.io.Serializable;
import java.util.concurrent.CountDownLatch;

import cybele.kernel.Activity;
import cybele.kernel.Cybele;
import cybele.kernel.CybeleEvent;
import cybele.kernel.Handler;

/** Experiment E (supporting): (1) does Activity.setTimer fire for a NEGATIVE
 *  delay (RoadAgent.java:102 can produce one for delay==1 roads), (2) for a
 *  zero delay (Planning.java:111), and (3) is a sendAll to a not-yet-opened
 *  channel dropped (Planning.java:126 "//BUG ne vzdy se doruci")?
 *  NB every handler/ctor returns promptly: a Cybele activity dispatches its
 *  own events serially, so a blocking ctor would starve its own timers. */
public class ExpE {
    static final String CLOCK = "c";
    static volatile boolean negFired, zeroFired, posFired, lateEarly, lateAfter;
    static final CountDownLatch armed = new CountDownLatch(1);
    static final CountDownLatch opened = new CountDownLatch(1);

    public static class Timers implements Handler {
        private static final long serialVersionUID = 1L;
        public Timers() {
            Cybele.createClock(CLOCK, Cybele.HOST, 0, 1);
            Cybele.resumeClock(CLOCK);
            Activity.setTimer(CLOCK, -1500, this, "onNeg");
            Activity.setTimer(CLOCK, 0, this, "onZero");
            Activity.setTimer(CLOCK, 500, this, "onPos");
            armed.countDown();
        }
        public void onNeg(CybeleEvent ev)  { negFired = true; }
        public void onZero(CybeleEvent ev) { zeroFired = true; }
        public void onPos(CybeleEvent ev)  { posFired = true; }
    }

    public static class LateOpener implements Handler {
        private static final long serialVersionUID = 1L;
        public LateOpener() {
            Activity.openChannel("LATE", "onLate", this);
            opened.countDown();
        }
        public void onLate(CybeleEvent ev) {
            if ("early".equals(ev.getMessage()[0])) lateEarly = true; else lateAfter = true;
        }
    }

    public static class EarlySender implements Handler {
        private static final long serialVersionUID = 1L;
        public EarlySender() {
            Activity.sendAll("LATE", new Serializable[]{"early"});   // nobody subscribed yet
            try {
                Cybele.createAgent("late", LateOpener.class.getName());
                opened.await();
                Thread.sleep(1000);
                Activity.sendAll("LATE", new Serializable[]{"after"});
            } catch (InterruptedException e) { /* ignore */ }
        }
    }

    public static void main(String[] args) throws Exception {
        Cybele.startUp();
        Cybele.createAgent("timers", Timers.class.getName());
        armed.await();
        Thread.sleep(3000);
        System.out.println("  negative-delay (-1500) timer fired = " + negFired);
        System.out.println("  zero-delay     (    0) timer fired = " + zeroFired);
        System.out.println("  positive-delay (  500) timer fired = " + posFired);
        Cybele.createAgent("early", EarlySender.class.getName());
        Thread.sleep(4000);
        System.out.println("  sendAll BEFORE openChannel delivered = " + lateEarly
                + "   (false => silently dropped)");
        System.out.println("  sendAll AFTER  openChannel delivered = " + lateAfter
                + "   (control: true => the probe works)");
        System.exit(0);
    }
}
