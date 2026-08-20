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
 *   <li>{@code kernel-swallow} — an NPE swallowed by the kernel's exception handler, in the exact
 *       byte shape disassembled out of {@code IAIExceptionHandler} and {@code IAIAgentThread}:
 *       no {@code AssertionError}, no {@code Exception in thread "}, status still 0;</li>
 *   <li>{@code duplicate} — the same train line repeated, so a {@code distinctGroup} rule has
 *       something to distinguish itself from a plain match count against;</li>
 *   <li>{@code all-indented} — every line prefixed with two spaces, i.e. matched by the DEFAULT
 *       diagnostic prefixes, so the normalized trace comes out empty;</li>
 *   <li>{@code orphan} — exits while a grandchild still holds the inherited stdout, truncating the
 *       capture;</li>
 *   <li>{@code silent-death} — generation stops early and the run still exits 0 with no error
 *       anywhere in the stream;</li>
 *   <li>{@code wall-clock-timeout} / {@code stall} / {@code clock-dead} — exits 3, 4 and 5;</li>
 *   <li>{@code startup-error} — rejects its configuration before starting, exit 1;</li>
 *   <li>{@code hang} — never exits, for the harness' own timeout;</li>
 *   <li>{@code flaky} — emits <em>one extra line</em> on every second invocation, counted in a
 *       file outside the per-run scratch directory. The only mode that is not a pure function of
 *       its inputs, and it exists for exactly one reason: the zero-flake gate (#21) has to be
 *       shown to go red. A gate that has never been observed to fail is a lock that cannot
 *       fail.</li>
 * </ul>
 *
 * <p>Output is a deterministic function of {@code sim.random.masterSeed} in every mode but
 * {@code flaky}, so the trace is reproducible without any of the residual nondeterminism the real
 * implementations have. The skeleton is not the place to fake those; #21 worked against the real
 * thing.
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
        if (Boolean.parseBoolean(System.getProperty("stub.startup", "true"))) {
            startupBlock();
        }

        if ("clock-dead".equals(mode)) {
            // A dead clock channel is usually PRECEDED by a swallowed throwable, which is the case
            // that used to leave the suite latch unset: the error scan threw first.
            if (Boolean.parseBoolean(System.getProperty("stub.throwable", "false"))) {
                printKernelSwallowedThrowable();
            }
            System.err.println("!!! FATAL: clock 'main' did not answer a pause/resume round trip");
            System.exit(5);
        }

        if ("orphan".equals(mode)) {
            try {
                new ProcessBuilder("sleep", "30").inheritIO().start();
            } catch (java.io.IOException e) {
                System.err.println("could not spawn grandchild: " + e);
            }
            System.out.println("vl1 in stA at 1100");
            System.exit(0);
        }

        Random random = new Random(seed);
        int generated = "silent-death".equals(mode) ? Math.min(2, maxTrains) : maxTrains;
        long clock = 1000L;
        for (int i = 1; i <= generated; i++) {
            clock += 100 + random.nextInt(400);
            String origin = STATIONS[random.nextInt(STATIONS.length)];
            if ("all-indented".equals(mode)) {
                System.out.println("  vl" + i + " in " + origin + " at " + clock);
                System.out.println("  vl" + i + " started");
                continue;
            }
            if ("duplicate".equals(mode)) {
                // Deliberately the SAME id and the same text every time: a distinctGroup rule must
                // count one, a plain match count must count `generated`.
                System.out.println("vl1 in stA at 1100");
                System.out.println("vl1 started");
                continue;
            }
            System.out.println("vl" + i + " in " + origin + " at " + clock);
            System.out.println("vl" + i + " started");
            if ("kernel-swallow".equals(mode) && i == 2) {
                printKernelSwallowedThrowable();
            }
            if ("assert-error".equals(mode) && i == 2) {
                printSwallowedAssertion();
            }
            if ("hang".equals(mode) && i == 2) {
                Thread.sleep(Long.MAX_VALUE);
            }
        }

        if ("flaky".equals(mode)) {
            // Not seeded, not random: a counter OUTSIDE the scratch directory, which
            // ParityLayout.scratchFor deletes before every run. Deterministic in the only sense
            // that matters here — the second invocation is guaranteed to differ from the first, so
            // a gate that failed to notice is a broken gate rather than an unlucky one.
            java.nio.file.Path counter = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                    "parity-stub-flaky-" + seed + ".count");
            long n = 0L;
            try {
                if (java.nio.file.Files.isRegularFile(counter)) {
                    n = Long.parseLong(java.nio.file.Files.readString(counter).trim());
                }
                java.nio.file.Files.writeString(counter, Long.toString(n + 1));
            } catch (java.io.IOException | NumberFormatException ignored) {
                // A stub that cannot count still has to run; it just stops being flaky.
            }
            if (n % 2 == 1) {
                System.out.println("vl99 in stA at 9999");
                System.out.println("vl99 started");
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
        String indent = "all-indented".equals(System.getProperty("stub.mode", "normal")) ? "  " : "";
        for (String track : TRACKS) {
            System.out.println(indent + track + " free");
        }
        for (String station : STATIONS) {
            System.out.println(indent + station + " 0/2");
        }
    }

    /**
     * The kernel's swallowed-throwable shape, byte for byte as disassembled from the vendored jar:
     * {@code IAIAgentThread} routes a reflective-dispatch failure through
     * {@code handleException("Thread Mgmt Exception", …)}, and {@code IAIExceptionHandler.print}
     * emits a blank line, then {@code ***<header> -><throwable>}, then the stack trace.
     *
     * <p>Note what is NOT here: no {@code AssertionError}, no {@code Exception in thread "}. This is
     * the shape a real port bug produces — an NPE inside an agent handler — and the first version
     * of the error scan passed it and recorded it as a golden.
     */
    private static void printKernelSwallowedThrowable() {
        System.err.println();
        System.err.println("***Thread Mgmt Exception -> cybele.exception.CybeleException:"
                + " General Exception occured in enter of the class"
                + " cz.vutbr.fit.ags.xhovor07.Station");
        System.err.println("java.lang.NullPointerException: Cannot invoke \"java.util.List.size()\""
                + " because \"this.waiting\" is null");
        System.err.println("\tat cz.vutbr.fit.ags.xhovor07.Station.enter(Station.java:118)");
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
