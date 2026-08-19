package cz.vutbr.fit.ags.parity.stub;

import java.util.Random;

/**
 * A stand-in simulation: a child JVM that prints an OpenCybele-shaped trace and exits with an
 * OpenCybele-shaped status.
 *
 * <p>It exists so the SPI can be driven start to finish on a branch that contains no application
 * code at all — which is the point of the skeleton, and the property {@code jade-develop} has to
 * keep. It is <em>not</em> a model of the railway; it is a model of the five things the harness has
 * to be able to tell apart, each reachable through {@code -Dstub.mode}:
 *
 * <ul>
 *   <li>{@code normal} — a healthy bounded run, exit 0;</li>
 *   <li>{@code assert-error} — a throwable printed mid-run while the status stays 0, twice, as a
 *       firing assertion actually prints;</li>
 *   <li>{@code silent-death} — generation stops early and the run still exits 0 with no error
 *       anywhere in the stream;</li>
 *   <li>{@code wall-clock-timeout} / {@code stall} / {@code clock-dead} — exits 3, 4 and 5;</li>
 *   <li>{@code startup-error} — rejects its configuration before starting, exit 1;</li>
 *   <li>{@code hang} — never exits, for the harness' own timeout.</li>
 * </ul>
 *
 * <p>Output is a deterministic function of {@code sim.random.masterSeed}, so the trace is
 * reproducible without any of the residual nondeterminism the real implementations have. The
 * skeleton is not the place to fake those; #21 works against the real thing.
 */
public final class StubSimulation {

    private static final String[] STATIONS = {"stA", "stB", "stC", "stD", "stE", "stF", "stG", "stH"};
    private static final String[] TRACKS = {"tr1", "tr2", "tr3", "tr4", "tr5", "tr6", "tr7"};

    private StubSimulation() {
    }

    public static void main(String[] args) throws InterruptedException {
        String mode = System.getProperty("stub.mode", "normal");
        long seed = Long.parseLong(System.getProperty("sim.random.masterSeed", "0"));
        int maxTrains = Integer.parseInt(System.getProperty("sim.stop.maxTrains", "10"));

        if ("startup-error".equals(mode)) {
            System.err.println("Exception in thread \"main\" java.lang.IllegalArgumentException:"
                    + " sim.station.capacities: no entry for station 'stH' declared in sim.topology");
            System.err.println("\tat cz.vutbr.fit.ags.parity.stub.StubSimulation.main(StubSimulation.java)");
            System.exit(1);
        }

        banner(seed, maxTrains, mode);
        startupBlock();

        if ("clock-dead".equals(mode)) {
            System.err.println("!!! FATAL: clock 'main' did not answer a pause/resume round trip");
            System.exit(5);
        }

        Random random = new Random(seed);
        int generated = "silent-death".equals(mode) ? Math.min(2, maxTrains) : maxTrains;
        long clock = 1000L;
        for (int i = 1; i <= generated; i++) {
            clock += 100 + random.nextInt(400);
            String origin = STATIONS[random.nextInt(STATIONS.length)];
            System.out.println("vl" + i + " in " + origin + " at " + clock);
            System.out.println("vl" + i + " started");
            if ("assert-error".equals(mode) && i == 2) {
                printSwallowedAssertion();
            }
            if ("hang".equals(mode) && i == 2) {
                Thread.sleep(Long.MAX_VALUE);
            }
        }

        switch (mode) {
            case "wall-clock-timeout" -> stop("sim.stop.wallClockMs elapsed", 3, generated, clock);
            case "stall" -> stop("sim.stop.stallMs elapsed - no train generated", 4, generated, clock);
            case "silent-death" -> stop("sim.stop.maxTrains reached", 0, generated, clock);
            default -> stop("sim.stop.maxTrains reached", 0, generated, clock);
        }
    }

    private static void banner(long seed, int maxTrains, String mode) {
        System.err.println("--- scenario configuration ---");
        System.err.println("  sim.random.masterSeed = " + seed);
        System.err.println("  sim.stop.maxTrains = " + maxTrains);
        System.err.println("  stub.mode = " + mode);
        System.err.println("------------------------------");
        System.err.println("--- random streams ---");
        System.err.println("  generator.od = " + (seed ^ 0x9E3779B97F4A7C15L));
        System.err.println("----------------------");
        // A diagnostic that varies from run to run, so the harness has to strip it rather than
        // happen to match it: the same shape as the real implementations' timing banners.
        System.err.println("--- kernel timer service ready after " + System.nanoTime() % 97 + " ms");
    }

    private static void startupBlock() {
        for (String track : TRACKS) {
            System.out.println(track + " free");
        }
        for (String station : STATIONS) {
            System.out.println(station + " 0/2");
        }
    }

    /**
     * Prints what a firing assertion actually looks like once the kernel has swallowed it: the
     * wrapper line and the cause line, so {@code AssertionError} appears twice for one failure —
     * which is why the harness reports hits and never counts failures.
     */
    private static void printSwallowedAssertion() {
        System.err.println("java.lang.reflect.InvocationTargetException:"
                + " java.lang.AssertionError: train left a station it had not entered");
        System.err.println("Caused by: java.lang.AssertionError: train left a station it had not entered");
        System.err.println("\tat cz.vutbr.fit.ags.parity.stub.StubSimulation.main(StubSimulation.java)");
    }

    private static void stop(String reason, int code, int generated, long clock) {
        System.out.flush();
        System.err.println("--- simulation stop ---");
        System.err.println("  reason        = " + reason);
        System.err.println("  exit code     = " + code);
        System.err.println("  trains generated = " + generated);
        System.err.println("  simulated clock  = " + clock + " ms");
        System.err.println("  wall clock       = " + (System.nanoTime() % 1000) + " ms");
        System.err.println("-----------------------");
        System.exit(code);
    }
}
