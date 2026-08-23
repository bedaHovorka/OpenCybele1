package cz.vutbr.fit.ags.parity.jade;

import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spi.LaunchSpec;
import cz.vutbr.fit.ags.parity.spi.LauncherAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Adapter 2: drives the <strong>JADE</strong> implementation in a child JVM
 * (<a href="https://github.com/bedaHovorka/OpenCybele1/issues/36">#36</a>).
 *
 * <p>This is the one place in the harness that is allowed to know JADE exists.
 * {@code Phase1.md} L93 requires it, and it is checkable: nothing under
 * {@code cz.vutbr.fit.ags.parity} outside this package imports {@code jade.*} or names a JADE
 * concept, and this class itself does not either — it builds an argv. The implementation is
 * reached <strong>by path</strong>, exactly as {@code OpenCybeleLauncher} reaches the baseline, so
 * the harness still compiles on a branch carrying no implementation at all.
 *
 * <pre>{@code
 * ./gradlew installDist                       # on the branch carrying the JADE port
 * ./gradlew characterizationIT -Pjade.dist=/abs/path/to/build/install/opencybele
 * }</pre>
 *
 * <h2>What it does <em>not</em> do, and why that is the news</h2>
 *
 * <p>Compared with {@code OpenCybeleLauncher} this class is shorter by everything Cybele needed:
 *
 * <ul>
 *   <li><strong>No {@code --patch-module java.base=cybelle}.</strong> That flag existed because
 *       Cybele's kernel read {@code cybele.prop} through
 *       {@code Properties.class.getResourceAsStream}, a {@code java.base}-loaded class, which under
 *       JPMS no longer falls back to the application classpath. The JADE implementation reads no
 *       such file.</li>
 *   <li><strong>No staged {@code cybelle/} directory</strong>, for the same reason — and therefore
 *       no <em>application home</em> either. {@code OpenCybeleLauncher} needs two directories, the
 *       dist for the jars and the checkout above it for {@code cybelle/*.prop}; this needs one.
 *       A scenario that declares {@code launcher.config} is resolved against the dist's parent
 *       instead, and none of the five in the catalogue declares one.</li>
 * </ul>
 *
 * <p>What is kept is kept for the reason #13 measured, not by inheritance:
 *
 * <ul>
 *   <li><strong>{@code java} is invoked directly, never {@code bin/opencybele}.</strong> A Gradle
 *       start script routes everything in {@code $@} to the <em>program's</em> arguments, so a
 *       positional {@code -Dsim.random.masterSeed=20080415} sets no system property, warns about
 *       nothing, and lets the run draw a fresh seed while appearing to accept the pin. That
 *       failure is target-independent — it is a property of the start script, and this branch's
 *       {@code installDist} produces the same one. It would also fork rather than {@code exec},
 *       leaving the inherited stdout open after the child exits, which
 *       {@code ScenarioRunner.execute} refuses as a truncated capture.</li>
 *   <li><strong>{@code -ea}.</strong> The goldens were recorded with assertions on
 *       ({@code parity-tests/golden/MANIFEST.md}) and every {@code assert} in the ported agents is
 *       documented against that setting, so a replay without it measures a different program.</li>
 *   <li><strong>The environment is refused if it would inject JVM flags.</strong>
 *       {@code JAVA_TOOL_OPTIONS} and its two relatives announce themselves on stderr, straight
 *       into the captured trace.</li>
 * </ul>
 *
 * <h2>{@code classifyExit} is deliberately not overridden — and one row of the table is unreachable</h2>
 *
 * <p>The implementation honours the measured table ({@code docs/headless-and-stop.md}) for codes
 * 0&ndash;4: its {@code RunControl} sets them and they mean the same things. Two rows deserve a
 * note rather than an override, because an override would change what a <em>code</em> means rather
 * than fix anything:
 *
 * <ul>
 *   <li><strong>5, {@code CLOCK_COMMAND_DEAD}, is unreachable here.</strong> It named Cybele's
 *       clock-registration race (INVENTORY SEM-06); the port's clock is an object in the same JVM
 *       (#29) with no announcement to lose. The implementation reserves the code and never emits
 *       it, so the harness' suite latch simply never fires — which is right, because a JADE run
 *       cannot poison the environment that way.</li>
 *   <li><strong>255, {@code AGENT_CONSTRUCTION_THROWABLE}, is <em>not reproduced</em>.</strong> On
 *       Cybele a throwable escaping an agent constructor killed the JVM in ~0.26 s with status
 *       255. JADE kills only that agent: it prints {@code ***  Uncaught Exception for agent …} and
 *       a stack trace on stderr and two lines on <strong>stdout</strong>, and the platform runs on
 *       one agent short. So the same fault surfaces as a stall, a wall-clock timeout or a
 *       truncated trace instead of as a status. It is still caught — see
 *       {@link #diagnosticPrefixes()} — but by the stream, not by the exit code.</li>
 * </ul>
 */
public final class JadeLauncher implements LauncherAdapter {

    /** Path to a {@code build/install/opencybele} directory built from the JADE port. */
    public static final String DIST_PROPERTY = "jade.dist";

    /** Optional override for the JVM used to run the child; defaults to the harness' own. */
    public static final String JAVA_PROPERTY = "jade.java";

    /**
     * The application's entry point, as a string, for the reason {@code OpenCybeleLauncher} gives:
     * this source set has no implementation to link against and {@code HarnessSelfCheckIT} fails
     * the build if any harness file imports one.
     */
    public static final String MAIN_CLASS = "cz.vutbr.fit.ags.xhovor07.Main";

    /**
     * Where the child is told to put JADE's {@code APDescription.txt}.
     *
     * <p>JADE's AMS writes that file on startup into {@code getProperty("file-dir", "")} — i.e.
     * <strong>the process working directory</strong> unless told otherwise. The working directory
     * here is the run's scratch, so it would land there in any case; it is set explicitly because
     * "it happens to be right" and "it is stated to be right" are different guarantees, and
     * because #27 recorded this as a thing a recording run must set rather than a thing a test
     * must remember.
     */
    public static final String FILE_DIR_PROPERTY = "jade.file.dir";

    /**
     * The resolved-configuration banner, exactly as {@code OpenCybeleLauncher} declares it and for
     * exactly the same reason: {@code ScenarioConfig.describe()} writes ~20 unindented
     * {@code sim.<key> = <value>} lines to stderr, and {@code redirectErrorStream(true)} folds them
     * into the trace. Configuration is a manifest recorded <em>alongside</em> a golden (#24), never
     * inside it.
     *
     * <p>It is declared here rather than shared, because a shared default is a claim about every
     * target. This one happens to hold for two implementations of the same application; the third
     * (#43) will say so for itself.
     */
    public static final String CONFIG_BANNER_PREFIX = "sim.";

    /**
     * <strong>The Cybele kernel's boot banner — nine lines that are in every frozen golden and
     * that no JADE run can produce.</strong>
     *
     * <p>This is the one entry here that is about reading the <em>golden</em> rather than about
     * filtering this target's output, and it is worth being explicit about why it is legitimate.
     *
     * <p>{@code ScenarioRunner} normalizes the golden with <em>this adapter's</em> normalizer
     * before comparing — that is the mechanism that let #21 land without re-recording anything.
     * The goldens were recorded through {@code OpenCybeleLauncher}, whose declared prefixes do not
     * catch the kernel's own preamble, so nine lines of it were frozen into the head of all five
     * files:
     *
     * <pre>
     * Cybele version 1.2 starting ...
     * *** Loading exception service (expecting impl. of GSI ver. 1.0) ...
     * ... (five more services) ...
     * ... Cybele started
     * </pre>
     *
     * <p>They are a <strong>kernel boot banner</strong>: not a message, not a channel, not
     * anything {@code docs/trace-format.md} specifies, and not anything a port of the
     * <em>application</em> could reproduce. Declaring them diagnostic here removes them from both
     * sides of the comparison and touches neither the golden file (which {@code Phase1.md} L7
     * freezes) nor {@code OpenCybeleLauncher} (so no OpenCybele run changes and nothing needs
     * re-verifying against the reference dist).
     *
     * <p><strong>Reported, not hidden.</strong> The underlying defect is #13's — the OpenCybele
     * adapter should have declared its own kernel banner, as it declares its own configuration
     * banner — and it is recorded on #36 rather than fixed here, because fixing it in the
     * OpenCybele adapter would change what the reference target emits and that is the one thing
     * this ticket must not do on the way to a first gate run.
     */
    public static final List<String> CYBELE_KERNEL_BANNER_PREFIXES =
            List.of("Cybele version ", "*** Loading ", "... Cybele started");

    /**
     * The one line JADE writes to <strong>stdout</strong> that is neither trace nor an agent death.
     *
     * <p>{@code jade.core.TimerDispatcher} prints
     * {@code Warning: No mapping found for expired timer <epochMillis>} directly to
     * {@code System.out} — not through {@code java.util.logging}, so the application's log handler
     * cannot reach it — when a behaviour's wake-up timer fires after its owner has gone. Trains die
     * constantly in this simulation, so it happens: <strong>measured once in 20 runs of
     * {@code opencybele-capacity}</strong>, and once is enough, because the line carries a
     * <em>wall-clock epoch</em> and lands in the middle of the trace. Its single occurrence shifted
     * every following line by one and turned a byte-identical run into a 190-line diff.
     *
     * <p>Declared as narrowly as the mechanism allows — the whole sentence, not {@code "Warning: "}
     * — so that some other platform message on stdout still surfaces as a diff rather than being
     * swallowed by a prefix that was widened for convenience.
     */
    public static final String JADE_EXPIRED_TIMER_WARNING = "Warning: No mapping found for expired timer ";

    /** Environment variables that would silently add JVM flags to a command line built here. */
    private static final List<String> ENV_THAT_LEAKS_JVM_FLAGS =
            List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS");

    private final Path dist;

    /** Uses {@code -Djade.dist}. */
    public JadeLauncher() {
        this(distFromSystemProperties());
    }

    /**
     * @param dist a {@code build/install/opencybele} directory built from the JADE port (must
     *             contain {@code lib/*.jar})
     */
    public JadeLauncher(Path dist) {
        this.dist = dist == null ? null : dist.toAbsolutePath().normalize();
    }

    /**
     * True when a built JADE implementation was pointed at and looks usable. Scenarios driving
     * this adapter are skipped rather than failed when it is false, for the reason
     * {@code OpenCybeleLauncher} gives: the harness must stay buildable and testable with no
     * implementation checked out anywhere.
     */
    public static boolean isAvailable() {
        Path candidate = distFromSystemProperties();
        return candidate != null && Files.isDirectory(candidate.resolve("lib"));
    }

    /** A message naming what to do about {@link #isAvailable()} being false. */
    public static String unavailableMessage() {
        return "no JADE build to drive: pass -P" + DIST_PROPERTY + "=<path to"
                + " build/install/opencybele>, built with `./gradlew installDist` on the branch"
                + " carrying the JADE port. The harness deliberately has no compile-time link to"
                + " the implementation, so this cannot be discovered.";
    }

    @Override
    public String id() {
        return "jade";
    }

    /** @return the {@code build/install/opencybele} directory this adapter drives */
    public Path dist() {
        return dist;
    }

    /**
     * Builds the child's command line.
     *
     * <p>{@code java -ea -cp <jars> -Djade.file.dir=<scratch> -Dsim.config=<abs> -D<k>=<v>… Main <args>}.
     * The scenario's properties keep their declaration order ({@code ScenarioSpec.Launcher}
     * preserves it), so two runs of the same scenario produce a byte-identical command line.
     *
     * <p>The child's working directory is the run's scratch, so a run cannot write into, or read
     * state out of, the implementation's source tree — the same rule #13 set, kept even though
     * this target has nothing to stage there.
     */
    @Override
    public LaunchSpec launch(ScenarioSpec spec, Path scratch) {
        requireUsableDist();
        rejectLeakingEnvironment();

        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        command.add("-ea");
        command.add("-cp");
        command.add(classpath());
        // ABSOLUTE, and measured rather than assumed. ParityLayout hands out a scratch path
        // RELATIVE to the harness' working directory; the child's working directory is that same
        // scratch, so the relative form resolves to <scratch>/<scratch> inside the child, which
        // does not exist. JADE's AMS does not fail loudly on that -- writeAPDescription catches
        // IOException and calls printStackTrace(), putting a 25-line FileNotFoundException trace on
        // stdout, i.e. into the trace, ahead of the first canonical line. Measured on the first
        // gate run: every scenario differed at line 1 for this and nothing else.
        command.add("-D" + FILE_DIR_PROPERTY + "=" + scratch.toAbsolutePath().normalize());
        if (spec.launcher().config() != null) {
            command.add("-Dsim.config=" + resolveConfig(spec.launcher().config()));
        }
        for (Map.Entry<String, String> property : spec.launcher().properties().entrySet()) {
            command.add("-D" + property.getKey() + "=" + property.getValue());
        }
        command.add(MAIN_CLASS);
        command.addAll(spec.launcher().args());
        return new LaunchSpec(command, spec.launcher().env(), scratch);
    }

    /**
     * The harness' four defaults, plus this target's configuration banner, plus the frozen
     * goldens' Cybele preamble.
     *
     * <p><strong>What is <em>not</em> here is the decision this ticket owed, so it is stated
     * rather than left to be inferred from an absence.</strong> When a throwable escapes a JADE
     * behaviour, {@code jade.core.Agent} prints four things: {@code ***  Uncaught Exception for
     * agent <name>  ***} and a stack trace on stderr, and — from {@code Agent.clean(false)} —
     * {@code ERROR: Agent <name> died without being properly terminated !!!} and
     * {@code State was <n>} on <strong>stdout</strong>, the stream the normalizer parses.
     *
     * <p><strong>All four are in contract. None of them is filtered.</strong> Three reasons:
     *
     * <ol>
     *   <li><em>They mean an agent died.</em> On Cybele the equivalent event left the agent alive
     *       with a corrupt heap and the exit status untouched, which is why {@code ErrorScanner}
     *       had to be taught the kernel's byte shapes at all. Here the consequence is worse, not
     *       milder — the agent is gone and every message it owed the rest of the run is gone with
     *       it — so the run must not be able to pass.</li>
     *   <li><em>Filtering them would make the death invisible to the comparison.</em> The run
     *       would then differ from its golden only by the <em>absence</em> of that agent's later
     *       lines, which reads as a behavioural diff in whatever agent happens to be downstream.
     *       That is exactly the misdiagnosis #81 was raised to prevent, one level up.</li>
     *   <li><em>They cannot be normalized away by accident.</em> They carry no {@code |}, so
     *       {@code CanonicalTraceNormalizer} leaves them intact and they land in
     *       {@code TraceComparator}'s unattributed bucket, which is compared with exact list
     *       equality — the strictest comparison in the harness. And they name the dead agent, so
     *       triage starts in the right file.</li>
     * </ol>
     *
     * <p><strong>The residual, recorded rather than papered over.</strong> Under {@code -ea} —
     * which is how the goldens were recorded and how this adapter replays — a failing
     * {@code assert} prints {@code java.lang.AssertionError} through
     * {@code Throwable.printStackTrace}, which {@code ErrorScanner.SIGNATURES} already matches, so
     * the run fails the raw-stream scan and can never be recorded. A <em>non</em>-assertion
     * throwable (an NPE, an {@code IllegalStateException}) matches none of the fourteen signatures,
     * because that list was read out of the Cybele kernel. Such a run still cannot pass a
     * comparison — the four lines above blow it up — but it could in principle be
     * <em>recorded</em>, and the SPI has no per-adapter hook for error signatures to close that
     * with. Left as a finding for a follow-up rather than closed by editing {@code ScenarioRunner},
     * which #36's acceptance criteria forbid.
     */
    @Override
    public List<String> diagnosticPrefixes() {
        List<String> prefixes = new ArrayList<>(LauncherAdapter.super.diagnosticPrefixes());
        prefixes.add(CONFIG_BANNER_PREFIX);
        prefixes.add(JADE_EXPIRED_TIMER_WARNING);
        prefixes.addAll(CYBELE_KERNEL_BANNER_PREFIXES);
        return List.copyOf(prefixes);
    }

    /**
     * Resolves a scenario's {@code launcher.config} against the dist's parent directory.
     *
     * <p>{@code OpenCybeleLauncher} derives an application <em>home</em> by walking up until it
     * finds {@code cybelle/cybele.prop}; there is no such marker here and nothing to look for, so
     * the rule is the simple one and an absolute path is the way to be sure.
     */
    public Path resolveConfig(String config) {
        Path path = Path.of(config);
        Path base = dist.getParent() == null ? dist : dist.getParent();
        Path resolved = (path.isAbsolute() ? path : base.resolve(path)).normalize();
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalStateException("launcher.config '" + config + "' resolves to "
                    + resolved + ", which does not exist. It is resolved against " + base
                    + "; give the scenario an absolute path if that is wrong.");
        }
        return resolved;
    }

    private static Path distFromSystemProperties() {
        String value = System.getProperty(DIST_PROPERTY);
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }

    private void requireUsableDist() {
        if (dist == null) {
            throw new IllegalStateException(unavailableMessage());
        }
        if (!Files.isDirectory(dist.resolve("lib"))) {
            throw new IllegalStateException("-D" + DIST_PROPERTY + "=" + dist
                    + " has no lib/ directory. Expected a `./gradlew installDist` output"
                    + " (build/install/opencybele).");
        }
    }

    /**
     * Every jar the dist carries, in sorted order so the command line is stable run to run. The
     * harness never resolves a coordinate itself — doing so would be a dependency on the
     * implementation's build.
     */
    private String classpath() {
        List<Path> jars = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dist.resolve("lib"))) {
            entries.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(jars::add);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + dist.resolve("lib"), e);
        }
        if (jars.isEmpty()) {
            throw new IllegalStateException("no jars under " + dist.resolve("lib")
                    + "; run `./gradlew installDist` on the implementation branch first.");
        }
        StringBuilder sb = new StringBuilder();
        for (Path jar : jars) {
            if (sb.length() > 0) {
                sb.append(java.io.File.pathSeparatorChar);
            }
            sb.append(jar);
        }
        return sb.toString();
    }

    /**
     * The child JVM must be the one this command line was written for, and it must not pick up
     * flags from the ambient environment. Same three variables, same reasoning, as #13: they are
     * silent argument injectors and the JVM announces them on stderr, straight into the captured
     * trace and the golden.
     */
    private static void rejectLeakingEnvironment() {
        for (String name : ENV_THAT_LEAKS_JVM_FLAGS) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                throw new IllegalStateException(name + " is set (" + value + "). It would add JVM"
                        + " flags to a command line this adapter assembles explicitly, and the JVM"
                        + " announces it on stderr — straight into the captured trace and the"
                        + " golden. Unset it before running the parity suite.");
            }
        }
    }

    private static String javaBinary() {
        String override = System.getProperty(JAVA_PROPERTY);
        if (override != null && !override.isBlank()) {
            return override;
        }
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
