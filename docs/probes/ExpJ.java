import java.io.Serializable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import cybele.kernel.Activity;
import cybele.kernel.Cybele;
import cybele.kernel.CybeleEvent;
import cybele.kernel.Handler;

/**
 * Experiment J (issue #29) — <b>from which clock read does {@code Activity.setTimer}'s delay
 * run?</b>
 *
 * <p>This is the last unmeasured link in #29's argument, and it is the one that decides whether
 * {@code Planning.java:108-112}'s pause/resume bracket protects anything. The site reads the
 * clock at {@code :109} to turn an absolute agreed departure into a <em>relative</em> delay, and
 * hands that delay to {@code setTimer} at {@code :111}. If the kernel measures the delay from
 * its own, later read, then simulated time elapsing between the two pushes the departure late by
 * exactly that much — and freezing the clock across the two statements is a real fix for a real
 * defect. If instead the delay were somehow anchored to the application's read, the bracket
 * would be protecting nothing, exactly like {@code RoadAgent}'s two.</p>
 *
 * <p>{@code INVENTORY.md} records only that the four timer sites take a relative
 * {@code delayMillis} (TMR-01…TMR-04); {@code SEM-06} covers negative and zero delays, not the
 * read point. Neither answers this. So: three arms, {@code n = 10} each, one clock, one agent.</p>
 *
 * <pre>
 *   baseline    t0 = getTime();                        setTimer(D)    expect fire - t0 ~ D
 *   drift       t0 = getTime();  sleep(GAP);           setTimer(D)    expect fire - t0 ~ D + GAP
 *                                                                     if the kernel re-reads
 *   bracketed   pauseClock(); t0 = getTime(); sleep(GAP); setTimer(D); resumeClock()
 *                                                                     expect fire - t0 ~ D
 *                                                                     i.e. the app's own idiom,
 *                                                                     and the drift is gone
 * </pre>
 *
 * <p>The three arms together are the whole of §2.5: arm 2 measures the defect, arm 3 measures
 * that the 2008 bracket fixes it, and arm 1 is the control that says the harness can measure a
 * delay at all. Note that the disposition does not depend on the result — an absolute
 * {@code AgentClock.scheduleAt} deadline has no second read either way — but the reasoning
 * printed in the docs does, and it should not be the one link taken on trust while the two
 * deletions each got 60 trials.</p>
 *
 * <p>Self-verdicting: prints a VERDICT line and exits 1 if the clock's command channel is dead.
 * See {@code ExpI} and {@code docs/probes/README.md} caveat 1 for why that check exists.</p>
 */
public class ExpJ {

    static final String CLOCK = "readPointClock";
    /** The timer delay under test, in simulated ms. */
    static final long D_MS = 1000;
    /** Simulated time deliberately allowed to elapse between the app's read and setTimer. */
    static final long GAP_MS = 200;
    /** Settle window for the asynchronous pause to land before the bracketed arm reads. */
    static final long SETTLE_MS = 20;
    /** Trials per arm. */
    static final int N = 10;

    static final CountDownLatch agentsUp = new CountDownLatch(1);
    static final CountDownLatch finished = new CountDownLatch(1);
    /** {fire - t0, ev.getClockTime() - t0}, posted by the timer callback, drained by the driver. */
    static final LinkedBlockingQueue<long[]> result = new LinkedBlockingQueue<long[]>();

    static volatile long t0 = 0;
    static long startUpToCreateClockGapNs = -1;
    static volatile String armThread = "?";
    static volatile String fireThread = "?";

    // -------------------------------------------------------------------------------------

    /**
     * The agent that arms the timer and receives it.
     *
     * <p><b>Why the arming lives here and the driving lives in a second agent.</b> Per SEM-04 a
     * blocking handler holds its agent's dispatch thread, so an agent that armed a timer and
     * then blocked waiting for it would never dispatch it — the probe would deadlock rather
     * than measure. So {@code arm} does its work and <em>returns</em>, freeing the thread long
     * before the timer is due, and a separate {@code Driver} agent does the waiting. (This is
     * the same fact that makes {@code ExpI}'s two agents genuinely concurrent.)</p>
     */
    public static class Timer implements Handler {
        private static final long serialVersionUID = 1L;

        public Timer() {
            Activity.openChannel("EXPJ.ARM", "arm", this);
            agentsUp.countDown();
        }

        /** One trial: the application's read, the gap, the arming. Returns immediately after. */
        public void arm(CybeleEvent ev) throws InterruptedException {
            armThread = Thread.currentThread().getName();
            final Serializable[] message = ev.getMessage();
            final boolean withGap = ((Boolean) message[0]).booleanValue();
            final boolean bracketed = ((Boolean) message[1]).booleanValue();

            if (bracketed) {
                Cybele.pauseClock(CLOCK);
                Thread.sleep(SETTLE_MS);                 // let the async pause land (SEM-02)
            }
            t0 = Cybele.getTime(CLOCK);                  // the application's read, Planning:109
            if (withGap) {
                Thread.sleep(GAP_MS);                    // time the bracket would have frozen
            }
            Activity.setTimer(CLOCK, D_MS, this, "fire");        // Planning:111
            if (bracketed) {
                Cybele.resumeClock(CLOCK);
            }
        }

        /** The timer callback. Records when it actually fired, on the clock under test. */
        public void fire(CybeleEvent ev) {
            fireThread = Thread.currentThread().getName();
            final long now = Cybele.getTime(CLOCK);
            // ExpH/H2: getClockTime() is populated for TIMER events (it is -1 on a MESSAGE
            // event). Recorded beside getTime so the two sources can be compared.
            result.add(new long[]{now - t0, ev.getClockTime() - t0});
        }
    }

    /** Orchestrates the arms. A second agent, so its blocking does not stall the Timer agent. */
    public static class Driver implements Handler {
        private static final long serialVersionUID = 1L;

        public Driver() {
            try {
                agentsUp.await(10, TimeUnit.SECONDS);
                run();
            } catch (Exception e) {
                e.printStackTrace();
            }
            finished.countDown();
        }

        private void run() throws Exception {
            System.out.println("startUp -> createClock gap: "
                    + (startUpToCreateClockGapNs / 1000000.0) + " ms"
                    + "   (race window is ~3.4 ms; see SEM-02)");
            System.out.println("clock=" + CLOCK + " scope=HOST pace=1  D=" + D_MS
                    + "ms  GAP=" + GAP_MS + "ms  settle=" + SETTLE_MS + "ms  n=" + N + " per arm");
            System.out.println();

            final long[] baseline = arm("baseline ", false, false);
            final long[] drift = arm("drift    ", true, false);
            final long[] bracketed = arm("bracketed", true, true);

            System.out.println();
            System.out.println("ARM         fire - t0 (simulated ms), t0 taken BEFORE the gap");
            System.out.println("  baseline    " + stats(baseline) + "   [control: no gap]");
            System.out.println("  drift       " + stats(drift) + "   [gap, clock RUNNING]");
            System.out.println("  bracketed   " + stats(bracketed)
                    + "   [gap, clock PAUSED across it -- the 2008 idiom]");
            System.out.println();

            final long dBase = median(baseline);
            final long dDrift = median(drift);
            final long dBracket = median(bracketed);
            final long observedDrift = dDrift - dBase;

            System.out.println("VERDICT 1: setTimer's delay is measured from "
                    + (observedDrift > GAP_MS / 2
                        ? "the KERNEL's OWN, LATER read"
                        : "something anchored BEFORE the call")
                    + "  [drift - baseline = " + observedDrift + " ms against a "
                    + GAP_MS + " ms gap]");
            System.out.println("VERDICT 2: the pauseClock bracket "
                    + (Math.abs(dBracket - dBase) <= GAP_MS / 2 ? "REMOVES" : "does NOT remove")
                    + " that drift  [bracketed - baseline = " + (dBracket - dBase) + " ms]");
            System.out.println("  threads: arm=" + armThread + "  fire=" + fireThread
                    + "  driver=" + Thread.currentThread().getName());
        }

        private long[] arm(String label, boolean withGap, boolean bracketed) throws Exception {
            result.clear();
            final long[] out = new long[N];
            for (int i = 0; i < N; i++) {
                Activity.sendAll("EXPJ.ARM", new Serializable[]{
                        Boolean.valueOf(withGap), Boolean.valueOf(bracketed)});
                final long[] r = result.poll(30, TimeUnit.SECONDS);
                out[i] = r == null ? -1 : r[0];
                Thread.sleep(50);
            }
            System.out.println(label + " arm: " + dump(out));
            return out;
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    static String dump(long[] a) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) b.append(' '); b.append(a[i]); }
        return b.toString();
    }
    static String stats(long[] a) {
        return "min=" + min(a) + " max=" + max(a) + " median=" + median(a);
    }
    static long min(long[] a) { long m = Long.MAX_VALUE; for (long v : a) if (v < m) m = v; return m; }
    static long max(long[] a) { long m = Long.MIN_VALUE; for (long v : a) if (v > m) m = v; return m; }
    static long median(long[] a) { final long[] c = a.clone(); java.util.Arrays.sort(c); return c[c.length / 2]; }

    /** ExpI's check, verbatim: prove clock control works before measuring anything. */
    static boolean clockControlWorks() throws InterruptedException {
        final long deadline = System.nanoTime() + 250L * 1000000L;
        while (System.nanoTime() < deadline) {
            Cybele.pauseClock(CLOCK);
            if (Cybele.isPaused(CLOCK)) {
                Cybele.resumeClock(CLOCK);
                final long d2 = System.nanoTime() + 250L * 1000000L;
                while (System.nanoTime() < d2) {
                    if (!Cybele.isPaused(CLOCK)) return true;
                    Cybele.resumeClock(CLOCK);
                    Thread.sleep(5);
                }
                return false;
            }
            Thread.sleep(5);
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        Cybele.startUp();
        final long afterStartUp = System.nanoTime();
        Thread.sleep(20);                                 // clear the ~3.4 ms registration race
        startUpToCreateClockGapNs = System.nanoTime() - afterStartUp;
        Cybele.createClock(CLOCK, Cybele.HOST, 0, 1);
        Cybele.resumeClock(CLOCK);
        if (!clockControlWorks()) {
            System.out.println("VERDICT: clock command channel is DEAD (SEM-02 registration race)."
                    + " gap=" + (startUpToCreateClockGapNs / 1000000.0) + " ms."
                    + " Every measurement below would be a silent no-op. Re-run.");
            System.exit(1);
        }
        Cybele.createAgent("expjTimer", Timer.class.getName());
        Cybele.createAgent("expjDriver", Driver.class.getName());
        finished.await(10, TimeUnit.MINUTES);
        System.exit(0);
    }
}
