import java.util.concurrent.CountDownLatch;

import cybele.kernel.Cybele;
import cybele.kernel.Handler;

/**
 * Experiment B: are Cybele.pauseClock / resumeClock counted (reentrant)?
 * i.e. after pause,pause does ONE resume restart the clock, or are two needed?
 * RoadAgent.push/pop and Planning.planTrain nest pause/resume around a critical
 * section, so a counted implementation vs. a boolean flag changes behaviour.
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
public class ExpB {
    static final String CLOCK = "probeClock";
    static final CountDownLatch done = new CountDownLatch(1);

    static long sampleDelta() throws InterruptedException {
        long t0 = Cybele.getTime(CLOCK);
        Thread.sleep(600);
        return Cybele.getTime(CLOCK) - t0;
    }

    public static class Probe implements Handler {
        private static final long serialVersionUID = 1L;
        public Probe() {
            try { run(); } catch (InterruptedException e) { /* ignore */ }
            done.countDown();
        }
        private void run() throws InterruptedException {
            Cybele.createClock(CLOCK, Cybele.HOST, 0, 1);
            System.out.println("B0  after createClock            isPaused=" + Cybele.isPaused(CLOCK)
                    + " delta(600ms)=" + sampleDelta());
            System.out.println("B1  resumeClock #1 returned      " + Cybele.resumeClock(CLOCK));
            System.out.println("B2  running                      isPaused=" + Cybele.isPaused(CLOCK)
                    + " delta(600ms)=" + sampleDelta());
            System.out.println("B3  pauseClock #1 returned       " + Cybele.pauseClock(CLOCK));
            System.out.println("B4  after 1 pause                isPaused=" + Cybele.isPaused(CLOCK)
                    + " delta(600ms)=" + sampleDelta());
            System.out.println("B5  pauseClock #2 returned       " + Cybele.pauseClock(CLOCK));
            System.out.println("B6  after 2 pauses               isPaused=" + Cybele.isPaused(CLOCK)
                    + " delta(600ms)=" + sampleDelta());
            System.out.println("B7  resumeClock #1 returned      " + Cybele.resumeClock(CLOCK));
            System.out.println("B8  after 2 pauses + 1 resume    isPaused=" + Cybele.isPaused(CLOCK)
                    + " delta(600ms)=" + sampleDelta()
                    + "   <-- >0 means NOT counted (boolean flag)");
            System.out.println("B9  resumeClock #2 returned      " + Cybele.resumeClock(CLOCK));
            System.out.println("B10 after 2 pauses + 2 resumes   isPaused=" + Cybele.isPaused(CLOCK)
                    + " delta(600ms)=" + sampleDelta());
            System.out.println("B11 resumeClock on running clock " + Cybele.resumeClock(CLOCK)
                    + "   (extra, unmatched resume)");
            System.out.println("B12 pauseClock #1 after extra    " + Cybele.pauseClock(CLOCK));
            System.out.println("B13 after that single pause      isPaused=" + Cybele.isPaused(CLOCK)
                    + " delta(600ms)=" + sampleDelta()
                    + "   <-- >0 would mean the extra resume was counted");
            System.out.println("B14 pauseClock on paused clock   " + Cybele.pauseClock(CLOCK));
            System.out.println("B15 pauseClock on unknown clock  " + Cybele.pauseClock("noSuchClock"));
            System.out.println("B16 resumeClock on unknown clock " + Cybele.resumeClock("noSuchClock"));
        }
    }

    public static void main(String[] args) throws Exception {
        Cybele.startUp();
        Cybele.createAgent("probe", Probe.class.getName());
        done.await();
        System.exit(0);
    }
}
