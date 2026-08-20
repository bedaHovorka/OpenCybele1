/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 * 
 * Open Cybele 1
 * 
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */
package cz.vutbr.fit.ags.xhovor07;

import java.nio.charset.Charset;
import java.util.Random;

/**
 * Per-agent pseudo-random streams derived from one scenario-level master seed.
 * <p>
 * This replaces the single {@code private static final Random} that used to live in
 * {@link Generator} and was published through {@code Generator.getRandom()}. That object
 * was consumed by three call sites on <em>different</em> threads — the generator activity
 * ({@code nextInt}, {@code nextDouble}) and every {@link RoadAgent}'s event thread
 * ({@code nextGaussian}) — so even a seeded shared instance would hand a different number
 * to a different call site on every run: {@code java.util.Random} is thread-safe, but the
 * <em>order</em> in which the threads consume it is the scheduler's business. Seeding it
 * would have produced a reproducible <em>multiset</em> of draws and nothing more.
 * <p>
 * The fix is one stream per agent. Each stream is a separate {@link Random} whose seed is
 * a pure function of the master seed and the agent's name, so:
 * <ul>
 * <li>a stream's sequence depends only on <em>how many</em> draws that agent has taken,
 *     never on what any other agent did, or when, or on which thread;</li>
 * <li>agents are seeded independently of the order they are created in — which matters
 *     here, and keeps mattering after #19. #19 fixed the order in which
 *     {@link RailwayMainAgent} <em>issues</em> the create requests (it was hash-order
 *     dependent: {@code docs/INVENTORY.md} NDT-01/NDT-02, on the {@code jade-develop}
 *     branch). It did not, and could not, fix the order in which the resulting agent
 *     <em>constructors</em> run: {@code Cybele.createAgent} is asynchronous, and the
 *     initialisation order was measured as six different orders in six runs of one build.
 *     Seeding the n-th agent created would therefore have produced nondeterministic
 *     streams even with #19 in place;</li>
 * <li>adding, removing or renaming an agent perturbs only that agent's stream.</li>
 * </ul>
 * <p>
 * The seed itself comes from {@link ScenarioConfig#getMasterSeed()}, which is resolved
 * once per JVM. Be aware that {@code ScenarioConfig} is the one configuration value that
 * is <b>not idempotent</b>: with {@code sim.random.masterSeed} unset, each construction
 * draws a <em>different</em> seed. {@link Main} calls {@code load()} on the main thread
 * before the kernel starts and republishes the resolved number into the system properties,
 * so every later {@code get()} in that JVM agrees with it. A second JVM — a remote Cybele
 * container today, a second JADE container in phase 2 — resolves its own configuration and
 * would therefore draw its <em>own</em> seed unless the resolved value is passed to it.
 * Any port that distributes agents must transport the master seed explicitly.
 * <p>
 * Note what this does <b>not</b> buy. Per-stream determinism is a property of each
 * sequence, not of the run: the simulation still reads a real-time clock, still dispatches
 * on a shared thread pool and still loses messages ({@code docs/INVENTORY.md} DEF-02, on {@code jade-develop}), so the same master
 * seed does not yet make the whole run byte-identical. See {@code docs/seeded-rng.md} for
 * exactly what is and is not reproducible today, and what blocks the rest (#16, #19).
 * <p>
 * <b>Derivation.</b> {@code seed = splitmix64_finalizer(fnv1a64(agentName, masterSeed))}:
 * the master seed is folded into the FNV-1a basis, the UTF-8 bytes of the name are hashed
 * in, and the result is passed through the SplitMix64 finalizer so that names differing in
 * one character (<code>tr1</code>/<code>tr2</code>) land far apart. Deliberately arithmetic
 * only — no {@code String.hashCode}, no {@code MessageDigest}, nothing whose value could
 * drift between JDKs — so a seed printed by one run reproduces that run anywhere.
 * 
 * @author Bedrich Hovorka
 */
public final class SimRandom {

    /**
     * Stream name of the {@link Generator} activity's origin/destination choice.
     * <p>
     * Not an agent name: the generator is an activity of {@link RailwayMainAgent}, and it
     * is the only draw-taking activity that agent has. Its two draws get <b>one stream
     * each</b>, rather than sharing one. They could have shared: both are taken inside a
     * single {@code generateTrain} invocation, in an order fixed by the source text, so a
     * shared stream would also have been interleaving-independent. The reason not to is
     * alignment stability — with a shared stream, adding or reordering a draw in that
     * handler shifts every subsequent value of <em>both</em> series, which surfaces later
     * as a golden diff that reads like a behaviour regression. Separate streams cost
     * nothing and make each series depend only on its own call site.
     */
    public static final String GENERATOR_OD_STREAM = "Generator.od";

    /**
     * Stream name of the {@link Generator} activity's exponential inter-arrival time.
     * See {@link #GENERATOR_OD_STREAM} for why the two draws do not share a stream.
     */
    public static final String GENERATOR_INTERARRIVAL_STREAM = "Generator.interarrival";

    /** FNV-1a 64-bit offset basis. */
    private static final long FNV_OFFSET_BASIS = 0xCBF29CE484222325L;
    /** FNV-1a 64-bit prime. */
    private static final long FNV_PRIME = 0x100000001B3L;
    /** Fractional part of the golden ratio, as used by SplitMix64. */
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private SimRandom() {
	// EMPTY - static factory only
    }

    /**
     * A fresh stream for one agent, seeded from the scenario's master seed.
     * <p>
     * Call this <b>once</b>, from the agent's constructor, and keep the result in a
     * {@code final} field: calling it again would restart that agent's sequence from the
     * beginning.
     * 
     * @param agentName agent (or activity) name; must be non-null and non-empty
     * @return that agent's generator
     */
    public static Random forAgent(String agentName) {
	return forAgent(ScenarioConfig.get().getMasterSeed(), agentName);
    }

    /**
     * A fresh stream for one agent, against an explicitly given master seed.
     * Used by the offline checker in {@code tools/java}; the simulation itself takes the
     * seed from the scenario configuration.
     * 
     * @param masterSeed the scenario master seed
     * @param agentName agent (or activity) name; must be non-null and non-empty
     * @return that agent's generator
     */
    public static Random forAgent(long masterSeed, String agentName) {
	return new Random(seedFor(masterSeed, agentName));
    }

    /**
     * The seed one agent's stream is started from. A pure function: same arguments, same
     * result, on any JVM, in any order, on any thread.
     * 
     * @param masterSeed the scenario master seed
     * @param agentName agent (or activity) name; must be non-null and non-empty
     * @return the derived per-agent seed
     */
    public static long seedFor(long masterSeed, String agentName) {
	if (agentName == null || agentName.length() == 0) {
	    throw new IllegalArgumentException("agent name must be non-empty to derive an RNG stream");
	}
	long h = FNV_OFFSET_BASIS ^ masterSeed;
	final byte[] bytes = agentName.getBytes(UTF8);
	for (int i = 0; i < bytes.length; i++) {
	    h ^= (bytes[i] & 0xFFL);
	    h *= FNV_PRIME;
	}
	return mix64(h);
    }

    /**
     * SplitMix64's finalizer — an avalanche mix, so one flipped input bit changes about
     * half the output bits.
     */
    private static long mix64(long seed) {
	long z = seed + GOLDEN_GAMMA;
	z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
	z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
	return z ^ (z >>> 31);
    }
}
