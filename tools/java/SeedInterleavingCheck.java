/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 * 
 * Open Cybele 1 - verification tool, not part of the simulation
 * 
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;

import cz.vutbr.fit.ags.xhovor07.ScenarioConfig;
import cz.vutbr.fit.ags.xhovor07.SimRandom;

/**
 * Evidence for the third acceptance criterion of issue #15: <em>"same master seed &rArr;
 * identical per-agent draw sequences regardless of thread interleaving"</em>.
 * <p>
 * Comparing two runs of the simulation cannot establish that. The application's own
 * scheduling may simply happen to be stable on this machine on this day, and a green
 * result would then be a coincidence rather than a property. So this driver attacks the
 * streams directly: it produces a single-threaded <b>reference</b> sequence per stream,
 * then reproduces every stream many more times under deliberately different thread
 * interleavings, and requires the results to be element-for-element identical.
 * <p>
 * Four interleaving modes, chosen to break different assumptions:
 * <ol>
 * <li><b>barrier</b> — every stream's thread released at the same instant and left to
 *     race.</li>
 * <li><b>jitter</b> — pseudo-random {@code yield}/{@code sleep} between draws, from a
 *     <em>per-trial</em> scheduling seed, so no two trials interleave the same way.</li>
 * <li><b>handoff</b> — each stream's draws are split into chunks executed by a
 *     <em>different thread each time</em> (serially, as Cybele dispatches one activity's
 *     events, docs/INVENTORY.md SEM-04, on the jade-develop branch). This is the one that
 *     matters for the real system: agent
 *     handlers do not run on a pinned thread, so a stream must not depend on thread
 *     identity — only on how many draws that agent has taken.</li>
 * <li><b>oversubscribed</b> — jitter plus CPU-burner threads, so the scheduler preempts
 *     mid-sequence.</li>
 * </ol>
 * <p>
 * A test that only ever passes proves nothing about its own power, so the same harness is
 * run against a <b>positive control</b>: the pre-#15 design, one shared {@code Random}
 * consumed by all the stream threads. If the control's per-stream sequences came out
 * identical too, the harness would be blind and this driver fails.
 * <p>
 * Usage:
 * <pre>
 *   ./gradlew rngProof                 # runs check with the built-in parameters
 *   java -cp ... SeedInterleavingCheck check [masterSeed] [trials] [draws]
 *   java -cp ... SeedInterleavingCheck plan  &lt;masterSeed&gt; &lt;trains&gt;
 * </pre>
 * {@code plan} predicts, offline, what the generator stream will do in a run with that
 * seed — the origin/destination pairs and the inter-arrival gaps, in order. Comparing it
 * against the {@code System.out} of an actual run is the app-level half of the evidence.
 * 
 * @author Bedrich Hovorka
 */
public final class SeedInterleavingCheck {

    /**
     * Streams exercised: the generator activity's two, plus one per configured track.
     * Taken from {@link ScenarioConfig} rather than hardcoded, so a run under a
     * {@code -Dsim.topology} with different track names checks <em>those</em> streams
     * instead of silently passing on tracks that no longer exist.
     */
    private static String[] streams() {
	final ScenarioConfig config = ScenarioConfig.get();
	final List<String> names = new ArrayList<String>();
	names.add(SimRandom.GENERATOR_OD_STREAM);
	names.add(SimRandom.GENERATOR_INTERARRIVAL_STREAM);
	names.addAll(config.getRoadNames());
	return names.toArray(new String[names.size()]);
    }

    private static String[] STREAMS = null;

    private static final long DEFAULT_MASTER_SEED = 20080415L;
    private static final int DEFAULT_TRIALS = 24;
    private static final int DEFAULT_DRAWS = 4000;

    /** Draw kinds per step, mirroring the three real call sites. */
    private static final int LONGS_PER_DRAW = 3;

    private static int failures = 0;

    private SeedInterleavingCheck() {
	// EMPTY
    }

    /**
     * @param args see the class comment
     * @throws Exception if a worker thread cannot be joined
     */
    public static void main(String[] args) throws Exception {
	// Optional: mirror everything printed into a file, so Gradle can treat this task as
	// having an output and skip it when nothing changed (it used to re-run on every
	// incremental build).
	final String report = System.getProperty("rngproof.out");
	if (report != null && report.length() > 0) {
	    final PrintStream file = new PrintStream(new FileOutputStream(report), true, "UTF-8");
	    System.setOut(new PrintStream(new TeeStream(System.out, file), true, "UTF-8"));
	}
	final String mode = (args.length > 0) ? args[0] : "check";
	if ("plan".equals(mode)) {
	    plan(Long.parseLong(args[1]), Integer.parseInt(args[2]));
	    return;
	}
	if (!"check".equals(mode)) {
	    System.err.println("usage: SeedInterleavingCheck [check [seed] [trials] [draws] | plan <seed> <trains>]");
	    System.exit(2);
	}
	STREAMS = streams();
	final long masterSeed = (args.length > 1) ? Long.parseLong(args[1]) : DEFAULT_MASTER_SEED;
	final int trials = (args.length > 2) ? Integer.parseInt(args[2]) : DEFAULT_TRIALS;
	final int draws = (args.length > 3) ? Integer.parseInt(args[3]) : DEFAULT_DRAWS;
	check(masterSeed, trials, draws);
	System.out.println();
	System.out.println(failures == 0 ? "RESULT: PASS" : "RESULT: FAIL (" + failures + ")");
	if (failures != 0) System.exit(1);
    }

    private static void check(long masterSeed, int trials, int draws) throws Exception {
	System.out.println("SeedInterleavingCheck");
	System.out.println("  masterSeed = " + masterSeed);
	System.out.println("  streams    = " + STREAMS.length + " " + Arrays.toString(STREAMS));
	System.out.println("  trials     = " + trials + " per mode, draws = " + draws
		+ " (x" + LONGS_PER_DRAW + " values) per stream");
	System.out.println("  cores      = " + Runtime.getRuntime().availableProcessors());

	// ---- part 1: the derivation is a pure function, and separates the streams ----
	System.out.println();
	System.out.println("[1] per-stream seed derivation");
	final long[] seeds = new long[STREAMS.length];
	for (int i = 0; i < STREAMS.length; i++) {
	    seeds[i] = SimRandom.seedFor(masterSeed, STREAMS[i]);
	    if (seeds[i] != SimRandom.seedFor(masterSeed, STREAMS[i])) {
		fail("seedFor(" + STREAMS[i] + ") is not a pure function");
	    }
	    for (int j = 0; j < i; j++) {
		// java.util.Random scrambles the seed into a 48-bit state, so two seeds
		// agreeing in their low 48 bits produce the same stream even if the longs
		// differ. Compare what the generator keeps, not what was handed to it.
		if (((seeds[i] ^ seeds[j]) & ((1L << 48) - 1)) == 0) {
		    fail("48-bit seed collision: " + STREAMS[i] + " and " + STREAMS[j]);
		}
	    }
	    System.out.println(String.format("    %-9s seed = %20d  0x%016x", STREAMS[i], seeds[i], seeds[i]));
	}
	if (SimRandom.seedFor(masterSeed + 1, STREAMS[0]) == seeds[0]) fail("master seed is ignored");

	// ---- part 2: single-threaded reference ----
	final long[][] reference = new long[STREAMS.length][];
	for (int i = 0; i < STREAMS.length; i++) {
	    reference[i] = drawAll(SimRandom.forAgent(masterSeed, STREAMS[i]), draws);
	}

	// ---- part 3: the same streams, many interleavings ----
	final String[] modes = {"barrier", "jitter", "handoff", "oversubscribed"};
	System.out.println();
	System.out.println("[2] per-agent streams under concurrent interleavings");
	for (int m = 0; m < modes.length; m++) {
	    int matched = 0;
	    for (int t = 0; t < trials; t++) {
		final long[][] got = run(modes[m], masterSeed, draws, t, false);
		if (equalAll(reference, got)) {
		    matched++;
		} else {
		    fail("mode " + modes[m] + " trial " + t + ": " + firstDifference(reference, got));
		}
	    }
	    System.out.println(String.format("    %-15s %2d/%2d trials byte-identical to the "
		    + "single-threaded reference", modes[m], matched, trials));
	}
	System.out.println("    checksums  " + checksums(reference));

	// ---- part 4: positive control - the pre-#15 shared Random ----
	System.out.println();
	System.out.println("[3] positive control: one shared Random (the pre-#15 design), same harness");
	int controlStable = 0;
	int controlTrials = 0;
	long[][] first = null;
	for (int m = 0; m < modes.length; m++) {
	    for (int t = 0; t < trials; t++) {
		final long[][] got = run(modes[m], masterSeed, draws, t, true);
		if (first == null) {
		    first = got;
		} else {
		    controlTrials++;
		    if (equalAll(first, got)) controlStable++;
		}
	    }
	}
	System.out.println(String.format("    %2d/%2d trials reproduced the first trial's per-stream"
		+ " sequences (expected: few or none)", controlStable, controlTrials));
	if (controlStable == controlTrials) {
	    fail("the control never diverged - this harness cannot tell the two designs apart,"
		    + " so its PASS above is worthless");
	} else {
	    System.out.println("    => the harness does detect interleaving-dependent streams,"
		    + " so [2] is a real result");
	}
    }

    // ------------------------------------------------------------------ drawing

    /**
     * One stream's sequence. Each step takes all three draw kinds the simulation uses
     * anywhere — {@code nextInt(pairs)} (`Generator.generateTrain`, the origin/destination
     * choice), {@code nextDouble()} (`Generator.exp`, the exponential) and
     * {@code nextGaussian()} (`RoadAgent.travelStart`, the travel jitter). <b>No single
     * real stream takes all three</b>; mixing them here is a harness choice, so that one
     * sequence exercises {@code nextGaussian}'s cached second value and the 48-bit state
     * advance of each kind. It tests the generator, not a replay of the application.
     */
    private static long[] drawAll(Random r, int draws) {
	final long[] out = new long[draws * LONGS_PER_DRAW];
	for (int i = 0; i < draws; i++) {
	    out[i * LONGS_PER_DRAW] = r.nextInt(pairBound());
	    out[i * LONGS_PER_DRAW + 1] = Double.doubleToLongBits(r.nextDouble());
	    out[i * LONGS_PER_DRAW + 2] = Double.doubleToLongBits(r.nextGaussian());
	}
	return out;
    }

    /** As {@link #drawAll} but for one chunk of a stream, so several threads can share it. */
    private static void drawRange(Random r, long[] out, int from, int to, Random jitter) {
	for (int i = from; i < to; i++) {
	    out[i * LONGS_PER_DRAW] = r.nextInt(pairBound());
	    out[i * LONGS_PER_DRAW + 1] = Double.doubleToLongBits(r.nextDouble());
	    out[i * LONGS_PER_DRAW + 2] = Double.doubleToLongBits(r.nextGaussian());
	    if (jitter != null) stumble(jitter);
	}
    }

    /** The bound the application actually uses at its {@code nextInt} call site. */
    private static int pairBound() {
	return ScenarioConfig.get().getTrainPairs().length;
    }

    /** Perturb this thread's progress so no two trials line up the same way. */
    private static void stumble(Random jitter) {
	final int d = jitter.nextInt(64);
	if (d == 0) {
	    try {
		Thread.sleep(1);
	    } catch (InterruptedException e) {
		Thread.currentThread().interrupt();
	    }
	} else if (d < 8) {
	    Thread.yield();
	}
    }

    // ------------------------------------------------------------------ interleavings

    private static long[][] run(String mode, long masterSeed, int draws, int trial, boolean shared)
	    throws Exception {
	final long[][] out = new long[STREAMS.length][draws * LONGS_PER_DRAW];
	// The control shares ONE generator across all stream threads - the pre-#15 design.
	final Random sharedRandom = shared ? new Random(masterSeed) : null;
	final boolean jittery = !"barrier".equals(mode);
	final boolean handoff = "handoff".equals(mode);
	final int chunks = handoff ? 8 : 1;
	final CyclicBarrier gate = new CyclicBarrier(STREAMS.length + 1);

	final AtomicBoolean burn = new AtomicBoolean(true);
	final List<Thread> burners = new ArrayList<Thread>();
	if ("oversubscribed".equals(mode)) {
	    final int n = 2 * Runtime.getRuntime().availableProcessors();
	    for (int i = 0; i < n; i++) {
		final Thread b = new Thread(new Burner(burn), "burn-" + i);
		b.setDaemon(true);
		b.start();
		burners.add(b);
	    }
	}

	final List<Thread> drivers = new ArrayList<Thread>();
	for (int i = 0; i < STREAMS.length; i++) {
	    final Random r = shared ? sharedRandom : SimRandom.forAgent(masterSeed, STREAMS[i]);
	    // A distinct scheduling seed per trial, per mode, per stream: the interleaving
	    // must differ from trial to trial, or the trials are one trial repeated.
	    final Random jitter = jittery
		    ? new Random(SimRandom.seedFor(trial * 1000003L + mode.hashCode(), STREAMS[i]))
		    : null;
	    final Thread d = new Thread(new Driver(gate, r, out[i], draws, chunks, jitter),
		    "stream-" + STREAMS[i]);
	    drivers.add(d);
	    d.start();
	}
	gate.await();
	for (int i = 0; i < drivers.size(); i++) {
	    drivers.get(i).join();
	}
	burn.set(false);
	for (int i = 0; i < burners.size(); i++) {
	    burners.get(i).join();
	}
	return out;
    }

    /**
     * Drives one stream. With {@code chunks > 1} the draws are handed from one thread to
     * the next: the stream's calls stay serialised (as Cybele serialises one activity's
     * events, SEM-04) but no two consecutive chunks run on the same thread.
     */
    private static final class Driver implements Runnable {
	private final CyclicBarrier gate;
	private final Random random;
	private final long[] out;
	private final int draws;
	private final int chunks;
	private final Random jitter;

	Driver(CyclicBarrier gate, Random random, long[] out, int draws, int chunks, Random jitter) {
	    this.gate = gate;
	    this.random = random;
	    this.out = out;
	    this.draws = draws;
	    this.chunks = chunks;
	    this.jitter = jitter;
	}

	public void run() {
	    try {
		gate.await();
		final int step = (draws + chunks - 1) / chunks;
		for (int from = 0; from < draws; from += step) {
		    final int to = Math.min(draws, from + step);
		    if (chunks == 1) {
			drawRange(random, out, from, to, jitter);
		    } else {
			final Thread hop = new Thread(new Chunk(random, out, from, to, jitter),
				Thread.currentThread().getName() + "-hop-" + from);
			hop.start();
			hop.join();   // serial per stream, but on a brand new thread each time
		    }
		}
	    } catch (Exception e) {
		synchronized (SeedInterleavingCheck.class) {
		    fail("driver threw " + e);
		}
	    }
	}
    }

    private static final class Chunk implements Runnable {
	private final Random random;
	private final long[] out;
	private final int from;
	private final int to;
	private final Random jitter;

	Chunk(Random random, long[] out, int from, int to, Random jitter) {
	    this.random = random;
	    this.out = out;
	    this.from = from;
	    this.to = to;
	    this.jitter = jitter;
	}

	public void run() {
	    drawRange(random, out, from, to, jitter);
	}
    }

    /** Console and file at once, so a captured report is exactly what was printed. */
    private static final class TeeStream extends java.io.OutputStream {
	private final java.io.OutputStream a;
	private final java.io.OutputStream b;

	TeeStream(java.io.OutputStream a, java.io.OutputStream b) {
	    this.a = a;
	    this.b = b;
	}

	@Override
	public void write(int value) throws IOException {
	    a.write(value);
	    b.write(value);
	}

	@Override
	public void flush() throws IOException {
	    a.flush();
	    b.flush();
	}
    }

    private static final class Burner implements Runnable {
	private final AtomicBoolean live;

	Burner(AtomicBoolean live) {
	    this.live = live;
	}

	public void run() {
	    long x = 1;
	    while (live.get()) {
		for (int i = 0; i < 10000; i++) x = x * 6364136223846793005L + 1442695040888963407L;
		if (x == 42) System.out.print("");  // keep the loop from being optimised away
		Thread.yield();
	    }
	}
    }

    // ------------------------------------------------------------------ reporting

    private static boolean equalAll(long[][] a, long[][] b) {
	for (int i = 0; i < a.length; i++) {
	    if (!Arrays.equals(a[i], b[i])) return false;
	}
	return true;
    }

    private static String firstDifference(long[][] a, long[][] b) {
	for (int i = 0; i < a.length; i++) {
	    for (int j = 0; j < a[i].length; j++) {
		if (a[i][j] != b[i][j]) {
		    return "stream " + STREAMS[i] + " differs at value " + j
			    + " (draw " + (j / LONGS_PER_DRAW) + "): expected " + a[i][j]
			    + ", got " + b[i][j];
		}
	    }
	}
	return "no difference (?)";
    }

    private static String checksums(long[][] sequences) {
	final StringBuilder sb = new StringBuilder();
	for (int i = 0; i < sequences.length; i++) {
	    long h = 0xCBF29CE484222325L;
	    for (int j = 0; j < sequences[i].length; j++) {
		h = (h ^ sequences[i][j]) * 0x100000001B3L;
	    }
	    if (sb.length() > 0) sb.append(' ');
	    sb.append(STREAMS[i]).append('=').append(String.format("%016x", h));
	}
	return sb.toString();
    }

    private static void fail(String message) {
	failures++;
	System.out.println("    FAIL: " + message);
    }

    // ------------------------------------------------------------------ plan mode

    /**
     * What the generator stream will do in a run with this seed, computed offline: the
     * same two draws in the same order as {@code Generator.generateTrain}.
     */
    private static void plan(long masterSeed, int trains) {
	System.setProperty(ScenarioConfig.KEY_RANDOM_MASTER_SEED, Long.toString(masterSeed));
	final ScenarioConfig config = ScenarioConfig.load();
	final Serializable[][] pairs = config.getTrainPairs();
	final long lambda = config.getArrivalLambdaMs();
	final Random od = SimRandom.forAgent(masterSeed, SimRandom.GENERATOR_OD_STREAM);
	final Random interarrival = SimRandom.forAgent(masterSeed, SimRandom.GENERATOR_INTERARRIVAL_STREAM);
	long clock = config.getArrivalFirstFireMs();
	System.out.println("# generator plan for masterSeed=" + masterSeed
		+ " lambdaMs=" + lambda + " firstFireMs=" + config.getArrivalFirstFireMs());
	System.out.println("# nominalAtMs is firstFireMs + the sum of the drawn gaps: what the");
	System.out.println("# generator ASKS the timer for, not when a train is observed to depart");
	System.out.println("# (the run's own delivery latency and the voting delay both move that).");
	System.out.println("# train from to nominalAtMs");
	for (int i = 0; i < trains; i++) {
	    final Serializable[] pair = pairs[od.nextInt(pairs.length)];
	    System.out.println("vl" + i + " " + pair[0] + " " + pair[1] + " " + clock);
	    clock += Math.round(-((double) lambda) * Math.log(interarrival.nextDouble()));
	}
    }
}
