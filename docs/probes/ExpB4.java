package probe;

import java.util.concurrent.CountDownLatch;

import cybele.kernel.Cybele;
import cybele.kernel.Handler;

/** Experiment B, part 4: bracket the latency of a HOST-scope pauseClock using a
 *  fresh clock per trial and a single sleep (no polling, which perturbs it). */
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
