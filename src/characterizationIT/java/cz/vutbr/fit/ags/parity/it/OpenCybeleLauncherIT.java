package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.opencybele.OpenCybeleLauncher;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpecParser;
import cz.vutbr.fit.ags.parity.spi.LaunchSpec;
import cz.vutbr.fit.ags.parity.spi.RunDisposition;
import cz.vutbr.fit.ags.parity.spi.TraceNormalizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks of {@link OpenCybeleLauncher} that need no application: the command line it assembles, the
 * exit table it honours, and the diagnostics it declares.
 *
 * <p>These run on every {@code characterizationIT} invocation, with or without
 * {@code -Popencybele.dist}. That matters: the properties they assert are the ones whose failure is
 * silent. A dropped {@code --patch-module} kills the child loudly, but a {@code -D} that never
 * reaches the JVM, an exit code that maps to the wrong disposition, or a configuration banner that
 * leaks into a golden all look like success.
 *
 * <p>The end-to-end run lives in {@link OpenCybeleSmokeIT}.
 */
class OpenCybeleLauncherIT {

    /**
     * A captured stream in the exact shape the application emits, taken verbatim from a run. Two
     * banners bracket the configuration body, the randomness manifest follows, then the kernel
     * banner, then trace. Only the trace and the kernel banner may survive normalization.
     */
    private static final List<String> CAPTURED_HEAD = List.of(
            "--- scenario configuration ---",
            "sim.arrival.lambdaMs = 2000    # default: 8500",
            "sim.arrival.firstFireMs = 200    # default: 1000",
            "sim.arrival.pairs = stA>stB,stA>stC,stB>stA,stB>stC,stC>stB,stC>stA",
            "sim.station.voteWindowMs = 2000    # default: 8500",
            "sim.topology = stA-stH:tr1,stH-stG:tr2,stG-stE:tr3",
            "sim.random.masterSeed = 20080415    # default: random",
            "sim.headless = true    # default: false",
            "sim.trace.enabled = true    # default: false",
            "sim.trace.trainLookahead = 32",
            "------------------------------",
            "--- random streams ---",
            "  Generator.od = -947576953628979",
            "  Generator.interarrival = -8007800441624140388",
            "  tr1 = 6186417220773925723",
            "----------------------",
            "Cybele version 1.2 starting ...",
            "*** Loading exception service (expecting impl. of GSI ver. 1.0) ... ",
            "    version 1.0 from Intelligent Automation, inc (www.i-a-i.com) ",
            "... Cybele started",
            "vl3|13016|PLAN_TRAIN|Main|Main|-|train=vl3,from=stA,to=stB",
            "vl3 started",
            "vl3 in stA at 12928",
            "stA|13024|STATION_INFO|stA|Main|-|occupied=1,capacity=6",
            "--- simulation stop ---",
            "  reason        = sim.stop.maxClockMs reached",
            "-----------------------");

    @Test
    @DisplayName("the command line is assembled explicitly: classpath, --patch-module, -ea, CWD, properties")
    void commandIsAssembledExplicitly(@TempDir Path tmp) throws IOException {
        Path home = fakeApplication(tmp);
        Path dist = home.resolve("build/install/opencybele");
        Path scratch = Files.createDirectories(tmp.resolve("scratch"));

        OpenCybeleLauncher adapter = new OpenCybeleLauncher(dist, null);
        assertEquals(home, adapter.home(),
                "the application home must be found by walking up from the dist");

        LaunchSpec launch = adapter.launch(specWithConfig(), scratch);
        List<String> cmd = launch.command();

        assertTrue(cmd.get(0).endsWith("java") || cmd.get(0).endsWith("java.exe"),
                "the child is a JVM invoked directly, not the Gradle start script — a start script"
                        + " routes $@ to the PROGRAM's arguments, so -D flags handed to it are"
                        + " silently ignored. Got: " + cmd.get(0));
        assertTrue(cmd.contains("-ea"), "assertions must be on: " + cmd);

        int patch = cmd.indexOf("--patch-module");
        assertTrue(patch >= 0, "--patch-module is required for Cybele under JPMS: " + cmd);
        assertEquals("java.base=cybelle", cmd.get(patch + 1));

        int cp = cmd.indexOf("-cp");
        assertTrue(cp >= 0, "the classpath must be spelled out: " + cmd);
        // Exact equality, not "contains the three jars". What this guards is that the launcher
        // contributes NOTHING of its own -- no inherited java.class.path, no vendor jar resolved
        // by the harness -- and that it orders what the dist holds, so the command line is
        // byte-stable run to run. It is a fixture, so it cannot notice a real dist gaining a jar;
        // that is deliberately not this test's job, since the classpath is whatever installDist
        // produced and the harness has no opinion about it.
        Path lib = dist.resolve("lib");
        assertEquals(String.join(java.io.File.pathSeparator,
                        lib.resolve("cybele-api-1.0.jar").toString(),
                        lib.resolve("cybele-impl-1.0.jar").toString(),
                        lib.resolve("opencybele.jar").toString()),
                cmd.get(cp + 1),
                "the classpath must be exactly the dist's jars, sorted by file name");

        int main = cmd.indexOf(OpenCybeleLauncher.MAIN_CLASS);
        assertTrue(main >= 0, "the entry point is missing: " + cmd);
        assertEquals(List.of("--extra-arg"), cmd.subList(main + 1, cmd.size()),
                "program arguments follow the main class and nothing else does");

        // Declaration order is preserved, and every property is a JVM -D BEFORE the main class,
        // which is the only position at which the JVM reads it as a system property at all.
        assertEquals(List.of("-Dsim.headless=true", "-Dsim.random.masterSeed=20080415",
                        "-Dsim.stop.maxClockMs=30000"),
                cmd.stream().filter(a -> a.startsWith("-Dsim.") && !a.startsWith("-Dsim.config=")).toList());
        assertTrue(cmd.indexOf("-Dsim.headless=true") < main, "properties must precede the main class");

        Path config = home.resolve("scenarios/smoke.properties");
        assertTrue(cmd.contains("-Dsim.config=" + config),
                "launcher.config must be resolved against the application home and passed"
                        + " ABSOLUTE — the child's working directory is the scratch dir: " + cmd);

        assertEquals(scratch, launch.workingDirectory(),
                "the working directory is set explicitly, because --patch-module resolves against it");
        assertTrue(Files.isRegularFile(scratch.resolve("cybelle/cybele.prop")),
                "cybele.prop must be staged into the working directory the child actually runs in");
        assertTrue(Files.isRegularFile(scratch.resolve("cybelle/ICS.prop")));
        assertFalse(Files.exists(scratch.resolve("cybelle/stray.jar")),
                "only *.prop is staged: --patch-module folds the WHOLE directory into java.base");
    }

    /**
     * <strong>What this asserts is the mapping, not the emission.</strong> That the application
     * actually produces each of these statuses was measured in
     * {@code docs/headless-and-stop.md} by observing {@code $?}, and is not re-verified here — a
     * green run of this test is not end-to-end proof that exit 4 ever happens.
     */
    @Test
    @DisplayName("all seven measured exit codes map to their dispositions, including 2 and 255")
    void exitTableCoversEveryMeasuredCode() {
        OpenCybeleLauncher adapter = new OpenCybeleLauncher(null, null);
        // docs/headless-and-stop.md, "Exit codes" — every one verified by observing $?, not by
        // reading source. Codes 2 and 255 postdate #12's table and are the two most easily
        // mis-read: 2 is "the run did NOT finish" and 255 is a status the application never sets.
        assertEquals(RunDisposition.BOUND_REACHED, adapter.classifyExit(0));
        assertEquals(RunDisposition.STARTUP_ERROR, adapter.classifyExit(1));
        assertEquals(RunDisposition.WINDOW_CLOSED_EARLY, adapter.classifyExit(2));
        assertEquals(RunDisposition.WALL_CLOCK_TIMEOUT, adapter.classifyExit(3));
        assertEquals(RunDisposition.STALL, adapter.classifyExit(4));
        assertEquals(RunDisposition.CLOCK_COMMAND_DEAD, adapter.classifyExit(5));
        assertEquals(RunDisposition.AGENT_CONSTRUCTION_THROWABLE, adapter.classifyExit(255));
        assertEquals(RunDisposition.UNKNOWN, adapter.classifyExit(42));
        assertEquals(RunDisposition.UNKNOWN, adapter.classifyExit(-1));

        // Only BOUND_REACHED and STARTUP_ERROR may be declared by a scenario; the rest are
        // "the run gave up" and must never read as a pass.
        assertFalse(adapter.classifyExit(2).isDeclarable());
        assertFalse(adapter.classifyExit(255).isDeclarable());
    }

    @Test
    @DisplayName("the resolved-configuration banner never reaches the normalized trace")
    void configurationBannerIsDeclaredAsADiagnostic() {
        // THE DEFECT THIS ISSUE OWNS. ScenarioConfig.describe() writes ~20 unindented
        // `sim.<key> = <value>` lines to stderr, and redirectErrorStream(true) makes stderr part of
        // the trace. None of #12's default prefixes matches them, so all ~20 would be recorded as
        // behaviour — including sim.random.masterSeed, which for an unpinned seed republishes a
        // DRAWN number and makes the golden unmatchable. Declared in the adapter (rather than
        // indented in the application) because #12 chose that a new diagnostic shape is declared by
        // the adapter that knows about it.
        TraceNormalizer normalizer = new OpenCybeleLauncher(null, null).normalizer();
        List<String> normalized = normalizer.normalize(CAPTURED_HEAD);

        assertTrue(normalized.stream().noneMatch(l -> l.startsWith("sim.")),
                "configuration is a manifest recorded ALONGSIDE a golden, never inside it: "
                        + normalized);
        assertTrue(normalized.stream().noneMatch(l -> l.contains("masterSeed")),
                "the seed line above all: unpinned it differs every run");

        // Confirmed rather than assumed, per the issue: #15's randomness manifest is already
        // covered by the default prefixes — its rules start "--- "/"---" and its body two spaces.
        assertTrue(normalized.stream().noneMatch(l -> l.contains("Generator.od")),
                "the randomness manifest must not reach the golden either: " + normalized);
        assertTrue(normalized.stream().noneMatch(l -> l.startsWith("--- ")));

        // And the filter must still be a filter, not a shredder.
        assertEquals(List.of(
                        "Cybele version 1.2 starting ...",
                        "*** Loading exception service (expecting impl. of GSI ver. 1.0) ... ",
                        "... Cybele started",
                        "vl3|13016|PLAN_TRAIN|Main|Main|-|train=vl3,from=stA,to=stB",
                        "vl3 started",
                        "vl3 in stA at 12928",
                        "stA|13024|STATION_INFO|stA|Main|-|occupied=1,capacity=6"),
                normalized,
                "trace lines and the deterministic kernel banner must survive untouched");
    }

    @Test
    @DisplayName("the extra prefix cannot swallow a trace line")
    void theExtraPrefixCannotSwallowTrace() {
        List<String> prefixes = new OpenCybeleLauncher(null, null).diagnosticPrefixes();
        assertTrue(prefixes.contains(OpenCybeleLauncher.CONFIG_BANNER_PREFIX));
        // Field 1 of a canonical line is a station, track or train name, and the application's own
        // two printlns start with a train name. None of them can start with "sim.".
        for (String line : List.of(
                "vl3|13016|PLAN_TRAIN|Main|Main|-|train=vl3,from=stA,to=stB",
                "stA|13024|STATION_INFO|stA|Main|-|occupied=1,capacity=6",
                "tr1|13080|ROAD_STATE|tr1|Main|-|state=TRAVEL_LEFT",
                "vl3 started",
                "vl3 in stA at 12928")) {
            for (String prefix : prefixes) {
                assertFalse(line.startsWith(prefix),
                        "declared diagnostic prefix '" + prefix + "' would strip trace line: " + line);
            }
        }
    }

    @Test
    @DisplayName("a missing or unusable application is reported, never quietly substituted")
    void missingApplicationIsReported(@TempDir Path tmp) throws IOException {
        OpenCybeleLauncher none = new OpenCybeleLauncher(null, null);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> none.launch(specWithConfig(), tmp));
        assertTrue(e.getMessage().contains(OpenCybeleLauncher.DIST_PROPERTY), e.getMessage());

        Path empty = Files.createDirectories(tmp.resolve("not-a-dist"));
        OpenCybeleLauncher bad = new OpenCybeleLauncher(empty, empty);
        assertTrue(assertThrows(IllegalStateException.class, () -> bad.launch(specWithConfig(), tmp))
                .getMessage().contains("lib/"));
    }

    // --- fixtures ---------------------------------------------------------------------------

    private static ScenarioSpec specWithConfig() {
        return ScenarioSpecParser.parse("""
                id: fixture
                contract: strict
                launcher:
                  config: scenarios/smoke.properties
                  properties:
                    sim.headless: "true"
                    sim.random.masterSeed: "20080415"
                    sim.stop.maxClockMs: "30000"
                  args: ["--extra-arg"]
                run:
                  harnessTimeoutMs: 60000
                  expect: bound-reached
                liveness:
                  - pattern: '^vl\\d+ started$'
                    atLeast: 1
                """, "fixture");
    }

    /** A directory tree shaped like a built application, with no application in it. */
    private static Path fakeApplication(Path tmp) throws IOException {
        Path home = Files.createDirectories(tmp.resolve("app"));
        Path cybelle = Files.createDirectories(home.resolve("cybelle"));
        Files.writeString(cybelle.resolve("cybele.prop"), "cybele.services = timer\n");
        Files.writeString(cybelle.resolve("ICS.prop"), "ICSBrowser = 127.0.0.1\n");
        // The checkout's cybelle/ has historically also held the untracked vendor jars, and
        // --patch-module folds the whole directory into java.base. Staging must not copy this.
        Files.writeString(cybelle.resolve("stray.jar"), "not really a jar");
        Files.createDirectories(home.resolve("scenarios"));
        Files.writeString(home.resolve("scenarios/smoke.properties"), "sim.headless = true\n");
        Path lib = Files.createDirectories(home.resolve("build/install/opencybele/lib"));
        for (String jar : List.of("opencybele.jar", "cybele-api-1.0.jar", "cybele-impl-1.0.jar")) {
            Files.writeString(lib.resolve(jar), "");
        }
        return home;
    }
}
