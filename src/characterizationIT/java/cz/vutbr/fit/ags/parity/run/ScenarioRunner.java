package cz.vutbr.fit.ags.parity.run;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.ComparisonResult;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
import cz.vutbr.fit.ags.parity.golden.TraceComparator;
import cz.vutbr.fit.ags.parity.spec.LivenessRule;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spi.LaunchSpec;
import cz.vutbr.fit.ags.parity.spi.LauncherAdapter;
import cz.vutbr.fit.ags.parity.spi.RunDisposition;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs one scenario against one {@link LauncherAdapter} and judges the result.
 *
 * <p>The runner has no compile-time link to any implementation: it builds no command line of its
 * own, it only executes the one the adapter hands it. That is what keeps this branch free of a
 * Cybele, JADE or Jason dependency while still driving all three.
 *
 * <h2>The order of the checks is the design</h2>
 *
 * <ol>
 *   <li><strong>Suite latch.</strong> If an earlier scenario reported a dead clock command channel,
 *       nothing is launched at all.</li>
 *   <li><strong>Launch and capture</strong> the merged stdout+stderr stream, with a hard harness
 *       timeout that is never a pass.</li>
 *   <li><strong>Error scan on the raw stream</strong>, before any normalization. A throwable does
 *       not change the exit status, and with the streams merged its stack trace is inside the
 *       trace — normalizing first would either scrub the evidence or freeze it into a golden.</li>
 *   <li><strong>Exit classification.</strong> "Reached the bound it declared" and "gave up" are
 *       different outcomes; only the first can pass. Exit 5 latches the suite.</li>
 *   <li><strong>Liveness.</strong> A simulation whose generator died keeps its clock, reaches its
 *       simulated-time bound and exits 0 with nothing in the trace to grep for. Only a floor on how
 *       much it produced catches that.</li>
 *   <li><strong>Normalize</strong>, then record or compare at the declared contract level.</li>
 * </ol>
 *
 * <p>Recording is deliberately last: a golden can only be written by a run that already passed
 * every check above it.
 */
public final class ScenarioRunner {

    /** Latched once, by the first scenario that hits a suite-fatal condition. */
    private static final AtomicReference<String> SUITE_FATAL = new AtomicReference<>();

    private final GoldenStore goldenStore;
    private final ParityLayout layout;

    public ScenarioRunner(ParityLayout layout) {
        this.layout = layout;
        this.goldenStore = new GoldenStore(layout.goldenDir());
    }

    public static ScenarioRunner usingDefaultLayout() {
        return new ScenarioRunner(ParityLayout.fromSystemProperties());
    }

    public GoldenStore goldenStore() {
        return goldenStore;
    }

    /** Clears the suite latch. For the harness' own tests; a real suite never calls it. */
    public static void resetSuiteLatch() {
        SUITE_FATAL.set(null);
    }

    public static Optional<String> suiteFatalReason() {
        return Optional.ofNullable(SUITE_FATAL.get());
    }

    /**
     * Runs one scenario end to end.
     *
     * @throws ScenarioFailedException when this scenario did not hold
     * @throws SuiteFatalError         when the environment invalidated the whole suite
     */
    public RunReport run(ScenarioSpec spec, LauncherAdapter adapter) {
        String latched = SUITE_FATAL.get();
        if (latched != null) {
            throw new SuiteFatalError("suite aborted before scenario '" + spec.id() + "': " + latched);
        }

        Path scratch = layout.scratchFor(spec.id());
        LaunchSpec launch = adapter.launch(spec, scratch);
        CapturedRun captured = execute(launch, spec, adapter);

        // 3 — error scan, on the RAW stream, before anything is projected away.
        List<String> errors = ErrorScanner.scan(captured.lines(), spec.allowErrorLines());
        if (!errors.isEmpty()) {
            throw new ScenarioFailedException(report(spec, adapter, captured,
                    "the run printed a throwable. The exit status does not carry this:"
                            + " the kernel wraps a throwable from an agent handler, prints it and"
                            + " leaves the status alone. Occurrence count is not failure count —"
                            + " one firing assertion prints 'AssertionError' twice.",
                    errors));
        }

        // 4 — exit classification.
        if (captured.disposition() == RunDisposition.CLOCK_COMMAND_DEAD) {
            String reason = "scenario '" + spec.id() + "' exited " + captured.exitCode()
                    + " (clock command channel dead). Every later run in this environment is suspect:"
                    + " the clock keeps advancing and the process exits green while every pause,"
                    + " resume and timer is a no-op, so a golden recorded now would be wrong with no"
                    + " symptom. Suite latched.";
            SUITE_FATAL.compareAndSet(null, reason);
            throw new SuiteFatalError(reason + System.lineSeparator()
                    + report(spec, adapter, captured, "clock command channel dead", List.of()));
        }
        RunDisposition expected = spec.run().expect().disposition();
        if (captured.disposition() != expected) {
            throw new ScenarioFailedException(report(spec, adapter, captured,
                    "the run ended as " + captured.disposition() + " (exit " + captured.exitCode()
                            + ") but the scenario declares " + expected + ". A run that gave up is"
                            + " not a run that reached its bound, and must never be read as a pass.",
                    List.of()));
        }

        // 5 — liveness, on the raw stream, so a rule survives changes to the normalizer.
        List<String> livenessFailures = new ArrayList<>();
        for (LivenessRule rule : spec.liveness()) {
            rule.check(captured.lines()).ifPresent(livenessFailures::add);
        }
        if (!livenessFailures.isEmpty()) {
            throw new ScenarioFailedException(report(spec, adapter, captured,
                    "the run exited cleanly but did too little. This is the failure mode that is"
                            + " invisible to both the exit status and an exception grep: a throwable"
                            + " in the generator before its timer re-arms stops generation for good"
                            + " while the process stays green.",
                    livenessFailures));
        }

        // 6 — normalize, then record or compare.
        List<String> normalized = adapter.normalizer().normalize(captured.lines());
        if (GoldenStore.recording()) {
            Path written = goldenStore.record(spec.goldenFile(), normalized);
            return new RunReport(captured, normalized, true, written);
        }
        List<String> golden = goldenStore.read(spec.goldenFile());
        ComparisonResult comparison = TraceComparator.compare(spec, golden, normalized);
        if (!comparison.matched()) {
            throw new ScenarioFailedException(report(spec, adapter, captured,
                    "the run does not match its golden at contract level "
                            + spec.contract().yamlName() + " (" + goldenStore.fileFor(spec.goldenFile())
                            + "). A golden diff is a port bug until proven a harness defect;"
                            + " re-recording is not a triage option.",
                    comparison.failures()));
        }
        return new RunReport(captured, normalized, false, goldenStore.fileFor(spec.goldenFile()));
    }

    // --- process handling -------------------------------------------------------------------

    private CapturedRun execute(LaunchSpec launch, ScenarioSpec spec, LauncherAdapter adapter) {
        ProcessBuilder builder = new ProcessBuilder(launch.command())
                .directory(launch.workingDirectory().toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(launch.environment());

        List<String> lines = new CopyOnWriteArrayList<>();
        long startedNanos = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ScenarioFailedException("scenario '" + spec.id() + "': cannot start "
                    + launch.describe() + " — " + e.getMessage());
        }

        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException e) {
                lines.add("<harness: stream closed: " + e.getMessage() + ">");
            }
        }, "parity-capture-" + spec.id());
        pump.setDaemon(true);
        pump.start();

        boolean finished;
        try {
            finished = process.waitFor(spec.run().harnessTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ScenarioFailedException("scenario '" + spec.id() + "': interrupted while waiting");
        }

        int exitCode;
        RunDisposition disposition;
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exitCode = -1;
            disposition = RunDisposition.HARNESS_TIMEOUT;
        } else {
            exitCode = process.exitValue();
            disposition = adapter.classifyExit(exitCode);
        }
        joinQuietly(pump);
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        return new CapturedRun(launch, exitCode, disposition, new ArrayList<>(lines), elapsedMs);
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- reporting --------------------------------------------------------------------------

    private String report(ScenarioSpec spec, LauncherAdapter adapter, CapturedRun captured,
            String headline, List<String> details) {
        StringBuilder sb = new StringBuilder();
        String nl = System.lineSeparator();
        sb.append("scenario '").append(spec.id()).append("' via adapter '").append(adapter.id())
                .append("' FAILED: ").append(headline).append(nl);
        sb.append("  contract    : ").append(spec.contract().yamlName()).append(nl);
        sb.append("  exit        : ").append(captured.exitCode())
                .append(" -> ").append(captured.disposition()).append(nl);
        sb.append("  wall clock  : ").append(captured.wallClockMs()).append(" ms").append(nl);
        sb.append("  command     : ").append(captured.launch().describe()).append(nl);
        if (!details.isEmpty()) {
            sb.append("  detail:").append(nl);
            for (String detail : details) {
                sb.append("    - ").append(detail).append(nl);
            }
        }
        sb.append("  last ").append(Math.min(20, captured.lines().size()))
                .append(" captured line(s):").append(nl);
        for (String line : captured.tail(20)) {
            sb.append("    | ").append(line).append(nl);
        }
        return sb.toString();
    }
}
