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
 * Experiment H: the two facts issue #20's probe agent rests on, beyond SEM-03.
 *
 *  H1  Subscription ORDER. SEM-03 (ExpC) showed two subscribers work when both open the
 *      channel before anything is sent. The probe must open TRAIN.STATE.<train> etc.
 *      BEFORE the owning agent exists (the owner opens it and immediately sends on it, so
 *      reacting to the owner's first message is already too late). Does an earlier
 *      subscriber survive a later openChannel of the same name by another agent?
 *
 *  H2  Is CybeleEvent.getClockTime() meaningful on a MESSAGE event, or only on a TIMER
 *      event? If it carried the sender's clock reading the probe could record send-time
 *      ticks instead of handling-time ticks.
 *
 *  H3  Does opening a channel that NOBODY ever opens or sends on cost anything visible
 *      (the probe pre-opens a look-ahead window of train channels, most of which are for
 *      trains that are never generated in a bounded run)?
 *
 * Measured output:
 *
 *   === ExpH (2 handler invocations; 2 = an early subscriber survives a later one) ===
 *      early   got msg1  tag=OWNED.thing  type=1  getClockTime()=-1  getClockId()=null  Cybele.getTime=2014
 *      owner   got msg1  getClockTime()=-1
 *      clock now = 6506
 *
 *   H1  YES. Both subscribers fire, and the one that subscribed BEFORE the other agent
 *       existed is not displaced by the later openChannel of the same name. This is what
 *       lets #20's probe pre-open START./ENTER_REPLY./TRAVEL_END./TRAIN.STATE. for a train
 *       that has not been created yet -- which it must, because Train's constructor opens
 *       its own channels and sends on TRAIN.STATE immediately, so reacting to a train's
 *       first message is already too late.
 *   H2  NO. getClockTime() is -1 and getClockId() is null on a MESSAGE event (type 1), on a
 *       live, running clock. Only TIMER events carry a clock stamp. A trace tick therefore
 *       has to be Cybele.getTime(CLOCK_ID) read at HANDLING time, and the format has to say
 *       so rather than imply a send-time stamp it cannot have. See docs/trace-format.md.
 *   H3  Inert. 64 channels that nobody opens and nobody sends on: no error, no warning,
 *       no effect on the two deliveries above.
 *
 * NOTE the Thread.sleep(4000) after startUp(): without it createClock hits the SEM-02
 * registration race and dies with an NPE inside IAITimerService.newClock. RunControl's
 * awaitTimerService() is the production answer; a sleep is enough for a probe.
 */
public class ExpH {
    static final List<String> log = Collections.synchronizedList(new ArrayList<String>());
    static final CountDownLatch earlyReady = new CountDownLatch(1);
    static final CountDownLatch lateReady = new CountDownLatch(1);

    static void log(String s) { log.add(s); }

    /** Subscribes FIRST, before the owner agent is even created. */
    public static class EarlySubscriber implements Handler {
        private static final long serialVersionUID = 1L;
        public EarlySubscriber() {
            Activity.openChannel("OWNED.thing", "h", this);
            // H3: a channel nobody will ever open or send on.
            for (int i = 0; i < 64; i++) Activity.openChannel("NEVER.used" + i, "h", this);
            earlyReady.countDown();
        }
        public void h(CybeleEvent ev) {
            log("early   got " + ev.getMessage()[0]
                    + "  tag=" + ev.getTag()
                    + "  type=" + ev.getEventType()
                    + "  getClockTime()=" + ev.getClockTime()
                    + "  getClockId()=" + ev.getClockId()
                    + "  Cybele.getTime=" + Cybele.getTime("expClock"));
        }
    }

    /** The "application" agent, which opens the same channel afterwards. */
    public static class Owner implements Handler {
        private static final long serialVersionUID = 1L;
        public Owner() {
            try { earlyReady.await(); } catch (InterruptedException e) { /* ignore */ }
            Activity.openChannel("OWNED.thing", "h", this);
            lateReady.countDown();
        }
        public void h(CybeleEvent ev) {
            log("owner   got " + ev.getMessage()[0]
                    + "  getClockTime()=" + ev.getClockTime());
        }
    }

    public static class Sender implements Handler {
        private static final long serialVersionUID = 1L;
        public Sender() {
            try { lateReady.await(); } catch (InterruptedException e) { /* ignore */ }
            try { Thread.sleep(1500); } catch (InterruptedException e) { /* ignore */ }
            Activity.sendAll("OWNED.thing", new Serializable[]{"msg1"});
        }
    }

    public static void main(String[] args) throws Exception {
        Cybele.startUp();
        Thread.sleep(4000);   // SEM-02: the timer service is not ready when startUp() returns
        Cybele.createClock("expClock", Cybele.HOST, 0, 1.0);
        Thread.sleep(500);
        Cybele.resumeClock("expClock");
        Cybele.createAgent("early", EarlySubscriber.class.getName());
        Cybele.createAgent("owner", Owner.class.getName());
        Cybele.createAgent("sender", Sender.class.getName());
        Thread.sleep(6000);
        System.out.println("=== ExpH (" + log.size() + " handler invocations; 2 = an early subscriber survives a later one) ===");
        List<String> sorted = new ArrayList<String>(log);
        Collections.sort(sorted);
        for (String s : sorted) System.out.println("   " + s);
        System.out.println("   clock now = " + Cybele.getTime("expClock"));
        System.exit(0);
    }
}
