package probe;

import java.util.concurrent.CountDownLatch;

import cybele.kernel.Cybele;
import cybele.kernel.Handler;

/** Experiment B, part 3: how long does a HOST-scope pauseClock take to land,
 *  and what does the app's tight pause/work/resume pattern actually do? */
public class ExpB3 {
    static final CountDownLatch done = new CountDownLatch(1);

    public static class Probe implements Handler {
        private static final long serialVersionUID = 1L;
        public Probe() {
            try { run(); } catch (InterruptedException e) { /* ignore */ }
            done.countDown();
        }
        private void run() throws InterruptedException {
            final String c = "hostClock";
            Cybele.createClock(c, Cybele.HOST, 0, 1);
            Cybele.resumeClock(c);
            Thread.sleep(300);

            long t0 = System.nanoTime();
            Cybele.pauseClock(c);
            long land = -1;
            for (int i = 0; i < 200; i++) {
                if (Cybele.isPaused(c)) { land = System.nanoTime() - t0; break; }
                Thread.sleep(50);
            }
            System.out.println("  HOST pauseClock latency to isPaused()==true : "
                    + (land < 0 ? "NEVER" : (land / 1000000.0) + " ms"));

            t0 = System.nanoTime();
            Cybele.resumeClock(c);
            land = -1;
            for (int i = 0; i < 200; i++) {
                if (!Cybele.isPaused(c)) { land = System.nanoTime() - t0; break; }
                Thread.sleep(50);
            }
            System.out.println("  HOST resumeClock latency to isPaused()==false: "
                    + (land < 0 ? "NEVER" : (land / 1000000.0) + " ms"));

            // the app's pattern: RoadAgent.push()/pop(), Planning.planTrain():
            //   pauseClock(); <a few statements>; resumeClock();
            Thread.sleep(300);
            long clockBefore = Cybele.getTime(c);
            long realBefore = System.currentTimeMillis();
            Cybele.pauseClock(c);
            int dummy = 0;
            for (int i = 0; i < 1000; i++) dummy += i;   // stand-in for queue.offer()
            Cybele.resumeClock(c);
            Thread.sleep(2000);
            long clockAfter = Cybele.getTime(c);
            long realAfter = System.currentTimeMillis();
            System.out.println("  app pattern pause->work->resume (dummy=" + dummy + "):");
            System.out.println("    real elapsed  = " + (realAfter - realBefore) + " ms");
            System.out.println("    clock elapsed = " + (clockAfter - clockBefore) + " ms");
            System.out.println("    isPaused now  = " + Cybele.isPaused(c));
        }
    }

    public static void main(String[] args) throws Exception {
        Cybele.startUp();
        Cybele.createAgent("probe", Probe.class.getName());
        done.await();
        System.exit(0);
    }
}
