package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.jade.JadeLauncher;
import cz.vutbr.fit.ags.parity.normalize.CanonicalTraceNormalizer;
import cz.vutbr.fit.ags.parity.opencybele.OpenCybeleLauncher;
import cz.vutbr.fit.ags.parity.spi.LaunchSpec;
import cz.vutbr.fit.ags.parity.spi.RunDisposition;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpecParser;
import cz.vutbr.fit.ags.parity.ParityLayout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything about {@link JadeLauncher} that can be asserted <strong>without a built
 * implementation</strong>, so this class runs on every {@code characterizationIT} invocation and
 * not only on one with {@code -Pjade.dist}.
 *
 * <p>The counterpart that needs a child is {@link JadeParityIT}.
 */
class JadeLauncherIT {

    @Test
    @DisplayName("the adapter identifies itself as 'jade' and is unavailable without a dist")
    void identityAndAvailability() {
        JadeLauncher adapter = new JadeLauncher(null);
        assertEquals("jade", adapter.id());
        assertTrue(JadeLauncher.unavailableMessage().contains(JadeLauncher.DIST_PROPERTY));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> adapter.launch(anyScenario(), Path.of(".")));
        assertTrue(failure.getMessage().contains(JadeLauncher.DIST_PROPERTY), failure.getMessage());
    }

    @Test
    @DisplayName("the command line carries -ea, the whole dist, every scenario property — and NO --patch-module")
    void commandLineShape(@TempDir Path root) throws IOException {
        Path dist = fakeDist(root);
        Path scratch = Files.createDirectories(root.resolve("scratch"));
        ScenarioSpec spec = anyScenario();

        LaunchSpec launch = new JadeLauncher(dist).launch(spec, scratch);
        List<String> command = launch.command();

        assertTrue(command.get(0).endsWith("java") || command.get(0).endsWith("java.exe"),
                "the adapter must invoke java directly, never bin/opencybele: a Gradle start script"
                        + " routes a positional -D to the PROGRAM's arguments and sets no system"
                        + " property at all, so a pinned seed would be silently ignored. It also"
                        + " forks rather than execs, which leaves the inherited stdout open after"
                        + " the child exits — a truncated capture. Got: " + command);
        assertTrue(command.contains("-ea"),
                "the goldens were recorded with assertions on (parity-tests/golden/MANIFEST.md);"
                        + " a replay without -ea measures a different program");
        assertFalse(command.contains("--patch-module"),
                "--patch-module java.base=cybelle is Cybele's; the JADE implementation reads no"
                        + " cybele.prop and staging one would be cargo cult: " + command);
        assertEquals(scratch, launch.workingDirectory(),
                "the child must run in the harness' scratch, so a run cannot write into or read"
                        + " state out of the implementation's source tree");
        assertTrue(command.contains(JadeLauncher.MAIN_CLASS), "the entry point is missing");
        assertTrue(command.contains("-D" + JadeLauncher.FILE_DIR_PROPERTY + "="
                        + scratch.toAbsolutePath().normalize()),
                "JADE's AMS writes APDescription.txt into file-dir, i.e. the working directory"
                        + " unless told otherwise. #27 recorded that a recording run must set it"
                        + " rather than rely on where it was launched from -- and it must be"
                        + " ABSOLUTE, because the scratch path is relative to the HARNESS' working"
                        + " directory while the child runs inside the scratch itself. The relative"
                        + " form put a 25-line FileNotFoundException stack trace on stdout, ahead"
                        + " of the first trace line, on the first gate run: " + command);

        // Total, not a hand-picked subset: an earlier revision of the OpenCybele assertions checked
        // six of ten keys, and dropping sim.station.voteWindowMs alone moved the trace by two lines
        // per affected family — inside every declared tolerance and therefore invisible.
        spec.launcher().properties().forEach((key, value) ->
                assertTrue(command.contains("-D" + key + "=" + value),
                        "the scenario property " + key + " never reached the command line: " + command));

        // Stable run to run: two identical launches must produce the same argv, or the command
        // itself becomes a variance source in a golden-master suite.
        assertEquals(command, new JadeLauncher(dist).launch(spec, scratch).command());
    }

    @Test
    @DisplayName("the exit table is the measured one, unchanged — the two rows that differ are documented, not overridden")
    void exitTable(@TempDir Path root) throws IOException {
        JadeLauncher adapter = new JadeLauncher(fakeDist(root));
        assertEquals(RunDisposition.BOUND_REACHED, adapter.classifyExit(0));
        assertEquals(RunDisposition.STARTUP_ERROR, adapter.classifyExit(1));
        assertEquals(RunDisposition.WINDOW_CLOSED_EARLY, adapter.classifyExit(2));
        assertEquals(RunDisposition.WALL_CLOCK_TIMEOUT, adapter.classifyExit(3));
        assertEquals(RunDisposition.STALL, adapter.classifyExit(4));
        // 5 stays mapped even though this implementation can never emit it (SimClock has no
        // registration to lose); reusing the code would make a JADE run latch the whole suite.
        assertEquals(RunDisposition.CLOCK_COMMAND_DEAD, adapter.classifyExit(5));
        // 255 stays mapped even though this implementation never produces it: JADE kills the
        // agent, not the JVM. See JadeLauncher's class comment.
        assertEquals(RunDisposition.AGENT_CONSTRUCTION_THROWABLE, adapter.classifyExit(255));
        assertEquals(RunDisposition.UNKNOWN, adapter.classifyExit(42));
    }

    @Test
    @DisplayName("the declared diagnostics cover the config banner AND the frozen goldens' Cybele preamble")
    void diagnosticPrefixes(@TempDir Path root) throws IOException {
        JadeLauncher adapter = new JadeLauncher(fakeDist(root));
        List<String> prefixes = adapter.diagnosticPrefixes();
        assertTrue(prefixes.contains(JadeLauncher.CONFIG_BANNER_PREFIX));
        assertTrue(prefixes.containsAll(JadeLauncher.CYBELE_KERNEL_BANNER_PREFIXES));

        // The nine lines that are actually in every frozen golden, verbatim. ScenarioRunner
        // normalizes the GOLDEN with this adapter's normalizer before comparing, so if these
        // survive, every JADE comparison starts nine lines out of step and reports it as a
        // behavioural diff in whatever agent happens to be first.
        List<String> preamble = List.of(
                "Cybele version 1.2 starting ...",
                "*** Loading exception service (expecting impl. of GSI ver. 1.0) ... ",
                "*** Loading timer service (expecting impl. of GSI ver. 1.1) ... ",
                "... Cybele started");
        List<String> survivors = adapter.normalizer().normalize(preamble);
        assertTrue(survivors.isEmpty(), "a Cybele kernel boot line survived normalization and would"
                + " be compared as behaviour: " + survivors);

        // And the guard against over-filtering: a real trace line must not start with any declared
        // prefix. Field 1 is a station, track or train name.
        List<String> trace = List.of(
                "stA|1234|STATION_INFO|stA|Main|INFORM|occupied=0,capacity=6",
                "tr1|1234|ROAD_STATE|tr1|Main|INFORM|state=FREE",
                "vl3|1234|PLAN_TRAIN|Main|Main|REQUEST|train=vl3,from=stA,to=stB",
                "vl3 started",
                "vl3 in stA at 1234");
        assertEquals(trace.size(), adapter.normalizer().normalize(trace).size(),
                "a declared diagnostic prefix is eating trace");
    }

    @Test
    @DisplayName("the normalizer is the shared one — nothing JADE-specific leaks into the projection")
    void normalizerIsTheSharedOne(@TempDir Path root) throws IOException {
        JadeLauncher jade = new JadeLauncher(fakeDist(root));
        CanonicalTraceNormalizer canonical = CanonicalTraceNormalizer.findIn(jade.normalizer())
                .orElseThrow(() -> new AssertionError("the adapter's normalizer contains no"
                        + " CanonicalTraceNormalizer, so nothing is projecting the run-varying"
                        + " families and a golden could never match"));
        assertEquals(CanonicalTraceNormalizer.DEFAULT_SEGMENT_GAP_TICKS, canonical.segmentGapTicks(),
                "the adapter must not tune the shared burst width for its own target: the constant"
                        + " is #21's and any change to it has to be re-verified against the"
                        + " OpenCybele reference (Phase1.md L82)");

        // Field 6 is the one place the two implementations legitimately differ before projection —
        // the baseline writes '-', this one writes the FIPA act #27 assigned — and #20 reserved the
        // field precisely so that erasing it makes them converge by construction.
        assertEquals(
                jade.normalizer().normalize(List.of(
                        "vl3|1234|PLAN_TRAIN|Main|Main|REQUEST|train=vl3,from=stA,to=stB")),
                jade.normalizer().normalize(List.of(
                        "vl3|1234|PLAN_TRAIN|Main|Main|-|train=vl3,from=stA,to=stB")),
                "the performative projection must erase field 6 whatever it holds, or every single"
                        + " line diffs cross-branch");
    }

    @Test
    @DisplayName("no harness file outside the jade package names JADE")
    void jadeKnowledgeIsConfinedToTheAdapter() throws IOException {
        // Phase1.md L93: anything JADE-specific lives in this adapter only. Checked structurally
        // rather than by review, the same way HarnessSelfCheckIT checks the implementation link.
        Path sourceRoot = Path.of("src/characterizationIT/java");
        java.util.List<String> offenders = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.toString().contains("/parity/jade/")) {
                    continue;   // the one place that may know
                }
                boolean isJadeIT = file.getFileName().toString().startsWith("Jade");
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import ") && trimmed.contains("jade.")
                            && !trimmed.contains("cz.vutbr.fit.ags.parity.jade")) {
                        offenders.add(file + ": " + trimmed);
                    }
                    if (!isJadeIT && trimmed.startsWith("import ")
                            && trimmed.contains("cz.vutbr.fit.ags.parity.jade")) {
                        offenders.add(file + ": " + trimmed);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "JADE knowledge must be confined to the adapter and its own ITs: " + offenders);
    }

    @Test
    @DisplayName("the two adapters are independent: neither reads the other's dist property")
    void adaptersDoNotShareTheirDist(@TempDir Path root) throws IOException {
        // They drive two different implementations of the same application, so pointing one at the
        // other's build would compare a run to a golden recorded from a different program while
        // looking configured.
        assertFalse(JadeLauncher.DIST_PROPERTY.equals(OpenCybeleLauncher.DIST_PROPERTY));
        Path dist = fakeDist(root);
        assertEquals(dist, new JadeLauncher(dist).dist());
    }

    // --- helpers -----------------------------------------------------------------------------

    /** A dist-shaped directory: a lib/ with one jar in it. Never executed. */
    private static Path fakeDist(Path root) throws IOException {
        Path dist = Files.createDirectories(root.resolve("build/install/opencybele"));
        Files.createDirectories(dist.resolve("lib"));
        Files.writeString(dist.resolve("lib").resolve("opencybele.jar"), "not a jar");
        return dist;
    }

    /** A real scenario off disk, so the property assertions are made against real declarations. */
    private static ScenarioSpec anyScenario() {
        ParityLayout layout = ParityLayout.fromSystemProperties();
        return ScenarioSpecParser.parse(layout.scenariosDir().resolve("opencybele-strict.yaml"));
    }
}
