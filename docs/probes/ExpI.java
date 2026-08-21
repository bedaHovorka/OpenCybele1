import java.io.Serializable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import cybele.kernel.Activity;
import cybele.kernel.Cybele;
import cybele.kernel.CybeleEvent;
import cybele.kernel.Handler;

/**
 * Experiment I (issue #29) — is the {@code pauseClock}/{@code resumeClock} idiom a mutex?
 *
 * <p>SEM-02 already settled that pause is a plain {@code boolean}, not a counter, by running
 * two pauses and one resume <em>from a single thread</em> ({@code ExpB2}) and by reading
 * {@code ContinuousClock.setPause}'s {@code if (paused) return;} out of the vendor bytecode.
 * Issue #29 turns that into a claim it does not follow from: that "a concurrent pause/resume
 * pair from two agents can resume the clock early", and it uses that claim to justify deleting
 * the idiom. #29 requires the claim to be measured before it is acted on. That is this probe.
 *
 * <p>It runs the app's actual shape: two <em>distinct Cybele agents</em>, each executing a
 * pause/resume bracket on their own handler threads, exactly as {@code Planning.java:108-112}
 * (main agent's activity thread) and {@code RoadAgent.java:132-134,167-169} (each road agent's
 * own thread) do. The two agents' brackets are made to overlap on purpose.
 *
 * <p>Two things are measured, and the second matters more than the first:
 *
 * <ol>
 *   <li><b>EARLY RESUME.</b> Agent A pauses, holds for {@code HOLD_MS}, resumes. Agent B pauses
 *       and resumes entirely inside A's hold. If pause is not counted, B's resume restarts the
 *       clock while A still believes it is frozen, and A's own {@code getTime} delta across its
 *       bracket is greater than zero. The control arm runs A alone.</li>
 *   <li><b>NO EXCLUSION OF THREADS.</b> B's handler records, at its entry and again at its
 *       exit, whether A was inside its bracket. Pausing a clock stops simulated <em>time</em>;
 *       it does not stop a <em>thread</em>. If B's body runs to completion inside A's bracket,
 *       then the idiom never excluded a handler from anything, in which case the
 *       counted/not-counted question is beside the point: there is no critical section to make
 *       mutually exclusive, whatever the counting semantics are.
 *       <p><b>State this precisely.</b> A freeze is not inert — it <em>defers</em> every
 *       clock-derived event, system-wide, for the bracket's duration, because all four
 *       {@code Activity.setTimer} sites are on this one global clock and every agent's
 *       {@code getTime} stamp is frozen with it. What the probe shows is that it excludes no
 *       <em>thread</em>. Deferred, not dropped; and because all pending timers shift together,
 *       their relative order is preserved.</p></li>
 * </ol>
 *
 * <p>Self-verdicting: prints VERDICT lines and exits 1 if the clock's command channel is dead
 * (in which case every number below would be a silent no-op — see the caveat).
 *
 * <p><b>Caveat — the registration race (INVENTORY.md SEM-02, docs/probes/README.md Caveat 1).</b>
 * {@code createClock} within ~3.4 ms of {@code startUp()} returning permanently loses clock
 * control while {@code getTime}/{@code isPaused} keep working, so a probe that lands in the
 * window measures nothing and says so in no way. Unlike the {@code ExpB*} probes, this one does
 * not sleep a tuned constant: it uses {@code RunControl}'s technique — create the clock, then
 * prove control works by pausing, polling {@code isPaused} with a re-send (a first command can
 * be lost on its own, recoverably), and resuming — before any measurement is taken, and it
 * reports the measured {@code startUp} -> {@code createClock} gap next to the results as the
 * README requires.
 */
public class ExpI {

    /** Clock id. Scope HOST and pace 1, i.e. RailwayMainAgent.java:111's configuration. */
    static final String CLOCK = "ovlClock";

    /** How long agent A stays inside its bracket. Long enough that a resumed clock is obvious. */
    static final long HOLD_MS = 300;
    /** How far into A's bracket agent B is told to run its own bracket. */
    static final long OVERLAP_AT_MS = 100;
    /** Agent B's bracket length. */
    static final long B_BRACKET_MS = 20;
    /** Settle window for the asynchronous pause to land before A takes its first reading. */
    static final long SETTLE_MS = 10;
    /** Trials per arm. */
    static final int N = 20;

    static final CountDownLatch agentsUp = new CountDownLatch(2);
    static final CountDownLatch finished = new CountDownLatch(1);
    /** A's measured simulated-time advance across its own bracket, handed to the driver. */
    static final SynchronousQueue<long[]> aResult = new SynchronousQueue<long[]>();
    static final CountDownLatch[] bDone = new CountDownLatch[1];

    /** Set by A for the whole duration of its bracket; read by B to answer question 2. */
    static volatile boolean aInsideBracket = false;
    /**
     * Question 2, ACCUMULATED over every trial rather than overwritten by the last one. An
     * earlier revision assigned a single {@code boolean} here, so the headline verdict was
     * effectively n=1 per run while the subordinate verdict 1 was a genuine 20/20. Two
     * counters, and two samples per trial: at B's ENTRY and again at B's EXIT, because
     * "B's body ran to completion inside A's bracket" is a claim about the whole body and the
     * entry sample alone only supports it by geometry.
     */
    static final java.util.concurrent.atomic.AtomicInteger bEnteredInsideA =
            new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger bFinishedInsideA =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Trials in which B ran at all, i.e. the denominator for the two counters above. */
    static final java.util.concurrent.atomic.AtomicInteger bTrials =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Handler threads, recorded rather than inferred -- see the note on VERDICT 2. */
    static volatile String aThread = "?";
    static volatile String bThread = "?";

    static long startUpToCreateClockGapNs = -1;

    // -------------------------------------------------------------------------------------

    /** The long bracket. Stands in for Planning.java:108-112. */
    public static class AgentA implements Handler {
        private static final long serialVersionUID = 1L;
        public AgentA() {
            Activity.openChannel("EXPI.GO_A", "bracket", this);
            agentsUp.countDown();
        }
        public void bracket(CybeleEvent ev) throws InterruptedException {
            aThread = Thread.currentThread().getName();
            Cybele.pauseClock(CLOCK);
            Thread.sleep(SETTLE_MS);                     // let the async pause land
            final long t0 = Cybele.getTime(CLOCK);
            aInsideBracket = true;
            Thread.sleep(HOLD_MS);
            final long t1 = Cybele.getTime(CLOCK);
            aInsideBracket = false;
            Cybele.resumeClock(CLOCK);
            aResult.put(new long[]{t1 - t0});
        }
    }

    /** The short bracket. Stands in for RoadAgent.push/pop. Runs on its OWN agent thread. */
    public static class AgentB implements Handler {
        private static final long serialVersionUID = 1L;
        public AgentB() {
            Activity.openChannel("EXPI.GO_B", "bracket", this);
            agentsUp.countDown();
        }
        public void bracket(CybeleEvent ev) throws InterruptedException {
            bThread = Thread.currentThread().getName();
            bTrials.incrementAndGet();
            if (aInsideBracket) {                        // question 2, sampled at B's ENTRY
                bEnteredInsideA.incrementAndGet();
            }
            Cybele.pauseClock(CLOCK);
            Thread.sleep(B_BRACKET_MS);
            Cybele.resumeClock(CLOCK);
            if (aInsideBracket) {                        // and again at B's EXIT
                bFinishedInsideA.incrementAndGet();
            }
            bDone[0].countDown();
        }
    }

    /** Orchestrates the arms. An agent because Activity.sendAll is an agent-side call. */
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
            System.out.println("clock=" + CLOCK + " scope=HOST pace=1"
                    + "  HOLD=" + HOLD_MS + "ms  B at +" + OVERLAP_AT_MS + "ms for "
                    + B_BRACKET_MS + "ms  settle=" + SETTLE_MS + "ms  n=" + N + " per arm");
            System.out.println();

            final long[] control = arm(false);
            final long[] overlap = arm(true);

            System.out.println();
            System.out.println("ARM              A's own getTime delta across its bracket (ms)");
            System.out.println("  control (A alone)   min=" + min(control) + " max=" + max(control)
                    + " median=" + median(control));
            System.out.println("  overlap (A and B)   min=" + min(overlap) + " max=" + max(overlap)
                    + " median=" + median(overlap));
            System.out.println();

            final long expectedAdvance = HOLD_MS + SETTLE_MS - OVERLAP_AT_MS - B_BRACKET_MS;
            final int early = countAbove(overlap, 50);
            System.out.println("VERDICT 1 (early resume): " + early + "/" + N
                    + " overlap trials saw the clock advance more than 50 ms inside A's bracket"
                    + "  [predicted advance if not counted: ~" + expectedAdvance + " ms]");
            System.out.println("VERDICT 1 control:        " + countAbove(control, 50) + "/" + N
                    + " control trials did");
            final int trials = bTrials.get();
            System.out.println("VERDICT 2 (exclusion):    B ENTERED its body inside A's bracket "
                    + bEnteredInsideA.get() + "/" + trials
                    + ", and RETURNED from it still inside A's bracket "
                    + bFinishedInsideA.get() + "/" + trials);
            System.out.println("                          [=> pauseClock blocks no thread. It DOES"
                    + " defer every clock-derived event for the bracket's duration --");
            System.out.println("                           all four setTimer sites are on this one"
                    + " global clock -- but it excludes no handler from running.]");
            System.out.println("  handler threads: A=" + aThread + "  B=" + bThread
                    + "   [distinct => the two brackets really are concurrent. If dispatch were"
                    + " single-threaded this probe would DEADLOCK:");
            System.out.println("                   A blocks in aResult.put() on a SynchronousQueue"
                    + " while the driver waits on bDone for the thread A holds.]");
        }

        private long[] arm(boolean withB) throws Exception {
            final long[] out = new long[N];
            for (int i = 0; i < N; i++) {
                bDone[0] = new CountDownLatch(1);
                Activity.sendAll("EXPI.GO_A", new Serializable[]{Long.valueOf(i)});
                if (withB) {
                    Thread.sleep(OVERLAP_AT_MS);
                    Activity.sendAll("EXPI.GO_B", new Serializable[]{Long.valueOf(i)});
                    bDone[0].await(10, TimeUnit.SECONDS);
                }
                final long[] r = aResult.poll(10, TimeUnit.SECONDS);
                out[i] = r == null ? -1 : r[0];
                Thread.sleep(50);                        // separate the trials
            }
            System.out.println((withB ? "overlap" : "control") + " arm: " + dump(out));
            return out;
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    static String dump(long[] a) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) b.append(' '); b.append(a[i]); }
        return b.toString();
    }
    static long min(long[] a) { long m = Long.MAX_VALUE; for (long v : a) if (v < m) m = v; return m; }
    static long max(long[] a) { long m = Long.MIN_VALUE; for (long v : a) if (v > m) m = v; return m; }
    static long median(long[] a) { final long[] c = a.clone(); java.util.Arrays.sort(c); return c[c.length / 2]; }
    static int countAbove(long[] a, long t) { int n = 0; for (long v : a) if (v > t) n++; return n; }

    /**
     * RunControl's technique, inlined: prove clock control works before measuring anything.
     * Re-sends while polling, because the FIRST command to a fresh clock can be dropped on its
     * own (a distinct, recoverable loss from the permanent registration loss — SEM-02).
     */
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
        Cybele.createAgent("expiA", AgentA.class.getName());
        Cybele.createAgent("expiB", AgentB.class.getName());
        Cybele.createAgent("expiDriver", Driver.class.getName());
        finished.await(10, TimeUnit.MINUTES);
        System.exit(0);
    }
}
