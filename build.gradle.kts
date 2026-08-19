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
    // Path to a built implementation, for the adapters that need one (#13 uses this).
    providers.gradleProperty("opencybele.dist").orNull?.let { systemProperty("opencybele.dist", it) }

    // A golden run is never up to date; there is no input Gradle can hash that captures "the
    // behaviour of a child process".
    outputs.upToDateWhen { false }

    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
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
