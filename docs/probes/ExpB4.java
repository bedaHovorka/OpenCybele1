import java.util.concurrent.CountDownLatch;

import cybele.kernel.Cybele;
import cybele.kernel.Handler;

/**
 * Experiment B, part 4: bracket the latency of a HOST-scope pauseClock using a
 * fresh clock per trial and a single sleep (no polling).
 *
 * This probe reports the pause AS LANDING where ExpB/ExpB3 report it never
 * landing. The difference is not the measurement technique: evaluating the
 * runtime concatenation "clk" + w before the first createClock pays the
 * invokedynamic/StringConcatFactory bootstrap (about +3.5 ms), which pushes this
 * probe just past the race window below. The clock-name string is irrelevant.
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
public class ExpB4 {
    static final CountDownLatch done = new CountDownLatch(1);
    static final long[] WAITS = {0, 50, 100, 200, 500, 1000, 2000, 3000, 5000};

    public static class Probe implements Handler {
        private static final long serialVersionUID = 1L;
        public Probe() {
            try { run(); } catch (InterruptedException e) { /* ignore */ }
            done.countDown();
        }
        private void run() throws InterruptedException {
            for (long w : WAITS) {
                String c = "clk" + w;
                Cybele.createClock(c, Cybele.HOST, 0, 1);
                Cybele.resumeClock(c);
                Thread.sleep(200);
                Cybele.pauseClock(c);
                if (w > 0) Thread.sleep(w);
                long t0 = Cybele.getTime(c);
                boolean p = Cybele.isPaused(c);
                Thread.sleep(400);
                long adv = Cybele.getTime(c) - t0;
                System.out.println("  wait=" + w + "ms after pauseClock -> isPaused=" + p
                        + " clockAdvance(400ms real)=" + adv);
            }
            // tight app pattern, repeated, on its own clock
            String c = "tight";
            Cybele.createClock(c, Cybele.HOST, 0, 1);
            Cybele.resumeClock(c);
            Thread.sleep(200);
            long cb = Cybele.getTime(c), rb = System.currentTimeMillis();
            for (int i = 0; i < 50; i++) {
                Cybele.pauseClock(c);
                Cybele.resumeClock(c);
            }
            Thread.sleep(3000);
            System.out.println("  50x tight pause/resume: real=" + (System.currentTimeMillis() - rb)
                    + "ms clock=" + (Cybele.getTime(c) - cb) + "ms isPaused=" + Cybele.isPaused(c));
        }
    }

    public static void main(String[] args) throws Exception {
        Cybele.startUp();
        Cybele.createAgent("probe", Probe.class.getName());
        done.await();
        System.exit(0);
    }
}
