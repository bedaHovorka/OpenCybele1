import java.util.concurrent.CountDownLatch;

import cybele.kernel.Cybele;
import cybele.kernel.Handler;

/**
 * Experiment B, refined: HOST scope (what the app uses) vs LOCAL scope, with a
 * long settle window after each pause so an ASYNCHRONOUS pause would still show.
 *
 * WARNING — startup registration race (INVENTORY.md SEM-02). Cybele.createClock
 * announces the new clock with an ASYNCHRONOUS sendAll; if it runs within about
 * 3.4 ms of Cybele.startUp() returning, the kernel's TimerAgent has not yet
 * subscribed, the announcement is silently dropped, and every subsequent
 * pauseClock/resumeClock/setPace/setTime on that clock id is a permanent silent
 * no-op while getTime/isPaused keep working. Every ExpB* probe sits inside that
 * window. A result from these probes is only meaningful next to a measured
 * startUp -> createClock gap; sleep >= 5 ms before createClock (or measure and
 * report the gap) if you re-run them. The real application is immune: the GUI
 * construction three lines above RailwayMainAgent.java:111 puts its gap at
 * 159-384 ms.
 */
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
