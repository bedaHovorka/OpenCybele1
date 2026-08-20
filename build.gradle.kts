plugins {
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("cz.vutbr.fit.ags.xhovor07.Main")
    applicationName = "opencybele"
    applicationDefaultJvmArgs = listOf("--patch-module", "java.base=cybelle")
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("com.iai:cybele-api:1.0")
    implementation("com.iai:cybele-impl:1.0")
}

// ---------------------------------------------------------------------------------------------
// L3 — the parity harness (TESTING.md §6, issue #12).
//
// A source set of its own, wired to NOTHING in `main`: it does not extend the application's
// configurations and must never import an application class. The harness drives an implementation
// by building a command line and starting a child JVM, which is what lets it live on a branch that
// carries no implementation at all and still drive OpenCybele (#13), JADE (#36) and Jason (#43).
// ---------------------------------------------------------------------------------------------

val characterizationITSourceSet: SourceSet = sourceSets.create("characterizationIT")

dependencies {
    "characterizationITImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
    // SnakeYAML: one jar, no transitive dependencies, test scope only. See docs/parity-harness.md.
    "characterizationITImplementation"("org.yaml:snakeyaml:2.4")
    "characterizationITRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val characterizationIT by tasks.registering(Test::class) {
    group = "verification"
    description = "L3 golden-master parity scenarios (TESTING.md §6)."
    testClassesDirs = characterizationITSourceSet.output.classesDirs
    classpath = characterizationITSourceSet.runtimeClasspath
    useJUnitPlatform()

    // Sequential on purpose: the goldens, the record-mode switch, ScenarioRunner's static
    // suite-fatal latch and (later) the implementations' JVM-singleton runtimes are all global
    // state. All three levers are pinned rather than left at a default that a future edit could
    // flip: forkEvery = 0 keeps one JVM (so the latch cannot silently reset between forks), and
    // src/characterizationIT/resources/junit-platform.properties disables parallel execution.
    maxParallelForks = 1
    forkEvery = 0

    // Record mode, per TESTING.md §3.2:  ./gradlew characterizationIT -Dgolden.record=true
    systemProperty("golden.record", providers.systemProperty("golden.record").getOrElse("false"))
    // Where scenarios/ and golden/ live; see ParityLayout.
    systemProperty("parity.root", providers.systemProperty("parity.root").getOrElse("parity-tests"))
    // Paths to a built implementation, for the adapters that need one (#13's OpenCybeleLauncher).
    //
    //   ./gradlew characterizationIT -Popencybele.dist=/abs/path/to/build/install/opencybele
    //
    // `dist` supplies the jars; `home` (normally derived by walking up from the dist) supplies
    // cybelle/*.prop and the scenarios/*.properties files, neither of which is part of the dist.
    // `java` overrides the JVM the child runs on. Absent the first, OpenCybeleSmokeIT SKIPS rather
    // than fails: this branch carries no implementation and must stay buildable without one.
    //
    // dist/home are made absolute, because the value is a path relative to the invoking shell
    // while the test JVM's working directory is the project directory. `java` is NOT: it may be a
    // bare command name resolved on PATH, and absolutising `java` to <project>/java produced a
    // launch failure with error=2.
    for (name in listOf("opencybele.dist", "opencybele.home")) {
        (providers.gradleProperty(name).orNull ?: providers.systemProperty(name).orNull)
            ?.let { systemProperty(name, file(it).absolutePath) }
    }
    (providers.gradleProperty("opencybele.java").orNull
        ?: providers.systemProperty("opencybele.java").orNull)
        ?.let { systemProperty("opencybele.java", it) }

    // A golden run is never up to date; there is no input Gradle can hash that captures "the
    // behaviour of a child process".
    outputs.upToDateWhen { false }

    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// ---------------------------------------------------------------------------------------------
// The zero-flake gate (#21, Phase1.md 1-PRE.2).
//
//   ./gradlew parityGate -Popencybele.dist=/abs/path/build/install/opencybele -Dparity.gate.runs=10
//
// A lane of its own rather than a flag on characterizationIT, because it is minutes rather than
// seconds and because it must be invocable as ONE command -- "a repeatable command, not a manual
// ritual" is the acceptance criterion. It runs exactly ParityGateIT; every other test stays out of
// the way. -Dparity.gate.runs is forwarded like the other harness properties, and with it absent
// the test SKIPS and prints the command, rather than passing vacuously.
//
// Since #23 it gates EVERY opencybele-* scenario in the catalogue -- Phase1.md 1-PRE.2 says "per
// scenario" and #23's acceptance is that each one passes the gate before it enters COVERAGE.md.
// While tuning a single scenario, add -Dparity.gate.scenario=<id>.
//
// The ledger it appends to (build/parity-gate/<scenario>.tsv, or -Dparity.gate.ledger=...) is what
// makes the cross-occasion half of the gate executable: a later invocation -- after a reboot, or
// hours later, or on another machine pointed at the same directory -- compares against it. Do not
// put it under a path a clean build deletes if you want that comparison to survive one.
// ---------------------------------------------------------------------------------------------
val parityGate by tasks.registering(Test::class) {
    group = "verification"
    description = "Zero-flake gate: N consecutive byte-identical runs per scenario (#21)."
    testClassesDirs = characterizationITSourceSet.output.classesDirs
    classpath = characterizationITSourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("cz.vutbr.fit.ags.parity.it.ParityGateIT") }
    maxParallelForks = 1
    forkEvery = 0
    outputs.upToDateWhen { false }

    systemProperty("parity.root", providers.systemProperty("parity.root").getOrElse("parity-tests"))
    systemProperty("golden.record", "false")
    for (name in listOf("parity.gate.runs", "parity.gate.ledger", "parity.gate.scenario")) {
        (providers.gradleProperty(name).orNull ?: providers.systemProperty(name).orNull)
            ?.let { systemProperty(name, it) }
    }
    for (name in listOf("opencybele.dist", "opencybele.home")) {
        (providers.gradleProperty(name).orNull ?: providers.systemProperty(name).orNull)
            ?.let { systemProperty(name, file(it).absolutePath) }
    }
    (providers.gradleProperty("opencybele.java").orNull
        ?: providers.systemProperty("opencybele.java").orNull)
        ?.let { systemProperty("opencybele.java", it) }

    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// `./gradlew build` compiles the harness but does not run it: TESTING.md §6 keeps
// characterizationIT as its own lane, and it will later need a built implementation to point at.
tasks.named("check") {
    dependsOn(characterizationITSourceSet.classesTaskName)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
