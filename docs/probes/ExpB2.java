package probe;

import java.util.concurrent.CountDownLatch;

import cybele.kernel.Cybele;
import cybele.kernel.Handler;

/** Experiment B, refined: HOST scope (what the app uses) vs LOCAL scope, with a
 *  long settle window after each pause so an ASYNCHRONOUS pause would still show. */
public class ExpB2 {
    static final CountDownLatch done = new CountDownLatch(1);

    static long delta(String clock, long ms) throws InterruptedException {
        long t0 = Cybele.getTime(clock);
        Thread.sleep(ms);
        return Cybele.getTime(clock) - t0;
    }

    public static class Probe implements Handler {
        private static final long serialVersionUID = 1L;
        public Probe() {
            try {
                scenario("hostClock", Cybele.HOST, "HOST  (== RailwayMainAgent.java:111)");
                scenario("localClock", Cybele.LOCAL, "LOCAL");
            } catch (InterruptedException e) { /* ignore */ }
            done.countDown();
        }
        private void scenario(String c, int scope, String label) throws InterruptedException {
            System.out.println("##### scope " + label + " #####");
            Cybele.createClock(c, scope, 0, 1);
            Cybele.resumeClock(c);
            System.out.println("  running          isPaused=" + Cybele.isPaused(c) + " delta(500ms)=" + delta(c, 500));
            System.out.println("  pauseClock #1 -> " + Cybele.pauseClock(c));
            Thread.sleep(3000);   // generous settle window for an async pause
            System.out.println("  after 1 pause    isPaused=" + Cybele.isPaused(c) + " delta(500ms)=" + delta(c, 500));
            System.out.println("  pauseClock #2 -> " + Cybele.pauseClock(c));
            Thread.sleep(3000);
            System.out.println("  after 2 pauses   isPaused=" + Cybele.isPaused(c) + " delta(500ms)=" + delta(c, 500));
            System.out.println("  resumeClock #1 ->" + Cybele.resumeClock(c));
            Thread.sleep(3000);
            System.out.println("  2 pause/1 resume isPaused=" + Cybele.isPaused(c) + " delta(500ms)=" + delta(c, 500)
                    + "   <-- delta>0 => NOT counted");
            System.out.println("  resumeClock #2 ->" + Cybele.resumeClock(c));
            Thread.sleep(3000);
            System.out.println("  2 pause/2 resume isPaused=" + Cybele.isPaused(c) + " delta(500ms)=" + delta(c, 500));
        }
    }

    public static void main(String[] args) throws Exception {
        Cybele.startUp();
        Cybele.createAgent("probe", Probe.class.getName());
        done.await();
        System.exit(0);
    }
}
