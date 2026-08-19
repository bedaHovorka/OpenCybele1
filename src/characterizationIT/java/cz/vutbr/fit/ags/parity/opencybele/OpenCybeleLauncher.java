package cz.vutbr.fit.ags.parity.opencybele;

import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spi.LaunchSpec;
import cz.vutbr.fit.ags.parity.spi.LauncherAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Adapter 1: drives the OpenCybele application in a child JVM
 * (<a href="https://github.com/bedaHovorka/OpenCybele1/issues/13">#13</a>).
 *
 * <p>The application lives on another branch — {@code opencybele-baseline} — and is reached
 * <strong>by path</strong>, never by a compile-time dependency. This class names exactly one
 * application symbol, {@link #MAIN_CLASS}, and it names it as a string. That is the whole point of
 * the split #11 chose: the harness compiles on a branch that carries no Cybele, no application
 * source and no vendor jar, and {@code HarnessSelfCheckIT} fails the build if any harness file ever
 * imports {@code cz.vutbr.fit.ags.xhovor07}.
 *
 * <h2>Where the application is</h2>
 *
 * <pre>{@code
 * cd ../wt/opencybele-ref && ./gradlew installDist
 * ./gradlew characterizationIT -Popencybele.dist=/abs/path/to/wt/opencybele-ref/build/install/opencybele
 * }</pre>
 *
 * <p>{@code -Popencybele.dist} is forwarded to the test JVM by {@code build.gradle.kts}. Two
 * directories matter and they are not the same one:
 *
 * <ul>
 *   <li>the <strong>dist</strong> ({@code build/install/opencybele}) supplies the jars —
 *       {@code opencybele.jar} plus the two vendor jars {@code installDist} copied out of
 *       {@code ~/.m2};</li>
 *   <li>the <strong>application home</strong> (the project directory above it) supplies
 *       {@code cybelle/*.prop} and the {@code scenarios/} files, neither of which is part of the
 *       dist. It is found by walking up from the dist until a {@code cybelle/cybele.prop} appears,
 *       and can be pinned outright with {@code -Dopencybele.home=…}.</li>
 * </ul>
 *
 * <h2>Why this invokes {@code java} and not {@code bin/opencybele}</h2>
 *
 * <p><strong>Measured, and the reason this issue exists in the shape it does:</strong> a Gradle
 * start script routes everything in {@code $@} to the <em>program's</em> arguments, so a positional
 * {@code -Dsim.random.masterSeed=20080415} handed to {@code build/install/opencybele/bin/opencybele}
 * sets no system property, warns about nothing, and lets the run draw a fresh seed while appearing
 * to accept the pin. A golden recorded under a silently ignored seed is the worst failure available
 * here. The start script's own escape hatch is {@code OPENCYBELE_OPTS}; assembling the
 * {@code java} command line directly is strictly better, because
 *
 * <ul>
 *   <li>every element is explicit and inspectable in the failure report
 *       ({@link LaunchSpec#describe()}), rather than word-split out of one environment string;</li>
 *   <li>the start script is a shell that {@code exec}s — but a launcher that <em>forks</em> instead
 *       leaves the inherited stdout open after the child exits, which
 *       {@code ScenarioRunner.execute} explicitly refuses as a truncated capture. Removing the
 *       middleman removes that whole failure class.</li>
 * </ul>
 *
 * <p>Whichever route is taken, the fact that the properties arrived has to be <em>proved</em>, not
 * assumed. The application prints its resolved configuration on stderr, which
 * {@code redirectErrorStream(true)} folds into the captured stream, so
 * {@code OpenCybeleSmokeIT} asserts the banner shows the values this adapter passed — including one
 * that differs from the config file's, so an ignored {@code -D} cannot pass by coincidence.
 *
 * <h2>Nothing is inherited</h2>
 *
 * <p>The classpath, {@code --patch-module}, {@code -ea}, the working directory and every
 * {@code sim.*} property are set here. {@code --patch-module java.base=cybelle} is
 * <strong>required</strong> (Cybele loads {@code cybele.prop} through
 * {@code Properties.class.getResourceAsStream}, which under JPMS no longer falls back to the
 * classpath) and it resolves <strong>relative to the child's working directory</strong> — so the
 * adapter stages {@code cybelle/*.prop} into the harness' per-run scratch directory and runs there,
 * rather than borrowing the reference checkout as a working directory. A run therefore cannot write
 * into, or read state out of, the application's source tree.
 */
public final class OpenCybeleLauncher implements LauncherAdapter {

    /** Path to a {@code build/install/opencybele} directory. Set by {@code -Popencybele.dist}. */
    public static final String DIST_PROPERTY = "opencybele.dist";

    /** Optional override for the application home; normally derived from the dist. */
    public static final String HOME_PROPERTY = "opencybele.home";

    /** Optional override for the JVM used to run the child; defaults to the harness' own. */
    public static final String JAVA_PROPERTY = "opencybele.java";

    /**
     * The application's entry point, as a string. It is a string and not a {@code Class} on
     * purpose: this branch has no application source to link against, and
     * {@code HarnessSelfCheckIT} asserts that no harness file imports one.
     */
    public static final String MAIN_CLASS = "cz.vutbr.fit.ags.xhovor07.Main";

    /**
     * The directory Cybele's {@code --patch-module} injection points at, relative to the child's
     * working directory.
     */
    public static final String PATCH_DIR = "cybelle";

    /**
     * The extra diagnostic prefix this target needs, and the one defect
     * <a href="https://github.com/bedaHovorka/OpenCybele1/issues/13">#13</a> owns.
     *
     * <p>{@code ScenarioConfig.describe()} writes the resolved-configuration banner as ~20
     * unindented {@code sim.<key> = <value>} lines, so none of the default prefixes
     * ({@code "--- "}, {@code "---"}, {@code "!!! "}, two spaces) catches it and every one of those
     * lines would be recorded as trace. That is not merely ugly: with {@code sim.random.masterSeed}
     * unpinned the banner republishes a <em>drawn</em> number, so the golden could never match; and
     * adding an unrelated {@code sim.*} key — as #20 just did, adding two — would change every
     * golden in the suite for a reason that has nothing to do with behaviour. Configuration is a
     * manifest recorded <em>alongside</em> a golden (#24), never inside it.
     *
     * <p>Three fixes were on the table. It is declared <strong>here</strong>, in the adapter,
     * rather than by indenting or prefixing the banner in the application, because #12 chose
     * deliberately that a new diagnostic shape is declared by the adapter that knows about it —
     * the normalizer stays implementation-agnostic and the application stays unaware that a harness
     * exists. Indenting the banner would additionally couple the application's output formatting to
     * a filter rule it cannot see, and the next unindented line reopens the hole silently.
     *
     * <p>The prefix cannot collide with trace: field 1 of a canonical line is a station, track or
     * train name ({@code stA}, {@code tr1}, {@code vl3} — never {@code Main}, and never a
     * configuration key), and the application's own two {@code println} families both begin
     * {@code vl<n>}. Every key {@code ScenarioConfig} accepts begins {@code sim.}.
     *
     * <p>The randomness manifest #15 prints was checked rather than assumed: its rule lines start
     * {@code "--- "}/{@code "---"} and its body lines with two spaces, so the default prefixes
     * already remove all eleven of them — measured, zero survivors in a captured run.
     */
    public static final String CONFIG_BANNER_PREFIX = "sim.";

    /** Environment variables that would silently add JVM flags to a command line built here. */
    private static final List<String> ENV_THAT_LEAKS_JVM_FLAGS =
            List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS");

    private final Path dist;
    private final Path home;

    /** Uses {@code -Dopencybele.dist} / {@code -Dopencybele.home}. */
    public OpenCybeleLauncher() {
        this(distFromSystemProperties(), homeFromSystemProperties());
    }

    /**
     * @param dist a {@code build/install/opencybele} directory (must contain {@code lib/*.jar})
     * @param home the application home containing {@code cybelle/} and {@code scenarios/}; when
     *             {@code null} it is derived by walking up from {@code dist}
     */
    public OpenCybeleLauncher(Path dist, Path home) {
        this.dist = dist == null ? null : dist.toAbsolutePath().normalize();
        this.home = home == null ? deriveHome(this.dist) : home.toAbsolutePath().normalize();
    }

    /**
     * True when a built application was pointed at and looks usable. Scenarios driving this adapter
     * are skipped rather than failed when it is false: {@code jade-develop} must stay buildable and
     * testable with no implementation checked out anywhere.
     */
    public static boolean isAvailable() {
        Path candidate = distFromSystemProperties();
        return candidate != null && Files.isDirectory(candidate.resolve("lib"));
    }

    /** A message naming what to do about {@link #isAvailable()} being false. */
    public static String unavailableMessage() {
        return "no OpenCybele build to drive: pass -P" + DIST_PROPERTY + "=<path to"
                + " build/install/opencybele>, built with `./gradlew installDist` on the"
                + " opencybele-baseline branch. The harness deliberately has no compile-time link to"
                + " the application, so this cannot be discovered.";
    }

    @Override
    public String id() {
        return "opencybele";
    }

    /**
     * Builds the child's command line, and stages the files it needs into {@code scratch}.
     *
     * <p>Order of the JVM arguments is fixed and every one of them is spelled out:
     * {@code java -ea --patch-module java.base=cybelle -cp <jars> -Dsim.config=<abs> -D<k>=<v>… Main <args>}.
     * The properties keep the scenario's declaration order ({@code ScenarioSpec.Launcher} preserves
     * it), so two runs of the same scenario produce a byte-identical command line.
     */
    @Override
    public LaunchSpec launch(ScenarioSpec spec, Path scratch) {
        requireUsableDist();
        rejectLeakingEnvironment();
        stagePatchDirectory(scratch);

        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        // #14: assertions are part of the contract this harness measures, and they are off by
        // default in every JVM.
        command.add("-ea");
        // REQUIRED. Cybele's kernel reads cybele.prop with Properties.class.getResourceAsStream(),
        // a java.base-loaded class; since JPMS that no longer falls back to the application
        // classpath, so without this the child dies at once with "Cannot find cybele.prop at class
        // path". The flag only works against a plain directory, and it is resolved relative to the
        // CHILD'S WORKING DIRECTORY -- which is why stagePatchDirectory ran first and why
        // LaunchSpec.workingDirectory below is set explicitly rather than inherited.
        command.add("--patch-module");
        command.add("java.base=" + PATCH_DIR);
        command.add("-cp");
        command.add(classpath());
        if (spec.launcher().config() != null) {
            // Absolute: ScenarioConfig opens it with `new FileInputStream(path)`, i.e. relative to
            // the child's working directory, which is the scratch directory and not the checkout
            // the scenario file was written against.
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
     * Adds the resolved-configuration banner to the defaults; see {@link #CONFIG_BANNER_PREFIX}
     * for why it is declared here rather than fixed in the application.
     */
    @Override
    public List<String> diagnosticPrefixes() {
        List<String> prefixes = new ArrayList<>(LauncherAdapter.super.diagnosticPrefixes());
        prefixes.add(CONFIG_BANNER_PREFIX);
        return List.copyOf(prefixes);
    }

    // classifyExit is DELIBERATELY not overridden. The default table in LauncherAdapter is the one
    // measured on this very application (docs/headless-and-stop.md), including exit 2
    // (WINDOW_CLOSED_EARLY) and 255 (AGENT_CONSTRUCTION_THROWABLE); overriding it is documented as
    // "a deliberate statement that a target cannot honour it", and this target defines it.
    // OpenCybeleLauncherIT asserts all seven codes through this class rather than trusting that.

    // --- the application on disk ----------------------------------------------------------

    /** @return the {@code build/install/opencybele} directory this adapter drives */
    public Path dist() {
        return dist;
    }

    /** @return the application home holding {@code cybelle/} and {@code scenarios/} */
    public Path home() {
        return home;
    }

    /** Resolves a scenario's {@code launcher.config} against the application home. */
    public Path resolveConfig(String config) {
        Path path = Path.of(config);
        Path resolved = (path.isAbsolute() ? path : home.resolve(path)).normalize();
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalStateException("launcher.config '" + config + "' resolves to "
                    + resolved + ", which does not exist. It is resolved against the application"
                    + " home (" + home + "); pin that with -D" + HOME_PROPERTY + " if it is wrong.");
        }
        return resolved;
    }

    private static Path distFromSystemProperties() {
        String value = System.getProperty(DIST_PROPERTY);
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }

    private static Path homeFromSystemProperties() {
        String value = System.getProperty(HOME_PROPERTY);
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }

    /**
     * Walks up from the dist looking for {@code cybelle/cybele.prop}. A dist normally sits at
     * {@code <home>/build/install/opencybele}, but the search is by content rather than by depth so
     * that a relocated or copied dist still works.
     */
    private static Path deriveHome(Path dist) {
        if (dist == null) {
            return null;
        }
        for (Path candidate = dist; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve(PATCH_DIR).resolve("cybele.prop"))) {
                return candidate;
            }
        }
        return null;
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
        if (home == null) {
            throw new IllegalStateException("cannot find the application home above " + dist
                    + ": no directory on the way up contains " + PATCH_DIR + "/cybele.prop, which"
                    + " the child needs for --patch-module. Pin it with -D" + HOME_PROPERTY + "=…");
        }
    }

    /**
     * The whole classpath, spelled out: every jar the dist carries, in sorted order so the command
     * line is stable run to run. That is {@code opencybele.jar} plus the two vendor jars
     * {@code installDist} copied out of {@code ~/.m2} — the harness never resolves them itself,
     * because doing so would be a dependency on the application's build.
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
                    + "; run `./gradlew installDist` on the application branch first.");
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
     * Copies {@code <home>/cybelle/*.prop} into {@code <scratch>/cybelle} so the child can be run
     * with its working directory inside the harness' scratch area.
     *
     * <p>Only {@code .prop} files are copied, deliberately. {@code --patch-module java.base=<dir>}
     * injects the <em>whole</em> directory into {@code java.base}; the checkout's {@code cybelle/}
     * has historically also held the untracked vendor jars, and folding those into the platform
     * module is not something a test harness should do by accident.
     */
    private void stagePatchDirectory(Path scratch) {
        Path source = home.resolve(PATCH_DIR);
        Path target = scratch.resolve(PATCH_DIR);
        try {
            Files.createDirectories(target);
            List<Path> copied = new ArrayList<>();
            try (Stream<Path> entries = Files.list(source)) {
                for (Path file : entries.sorted().toList()) {
                    if (Files.isRegularFile(file) && file.getFileName().toString().endsWith(".prop")) {
                        Files.copy(file, target.resolve(file.getFileName()),
                                StandardCopyOption.REPLACE_EXISTING);
                        copied.add(file.getFileName());
                    }
                }
            }
            if (!Files.isRegularFile(target.resolve("cybele.prop"))) {
                throw new IllegalStateException("no cybele.prop under " + source + " (copied "
                        + copied + "). Without it the child cannot start: Cybele reads it through"
                        + " --patch-module java.base=" + PATCH_DIR + ".");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot stage " + source + " into " + target, e);
        }
    }

    /**
     * The child JVM must be the one this command line was written for, and it must not pick up
     * flags from the ambient environment. {@code JAVA_TOOL_OPTIONS} and its two relatives are
     * silent argument injectors — they would add JVM flags nobody declared and print a
     * {@code Picked up …} line straight into the captured trace — so their presence is an error
     * here rather than something to hope about.
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
