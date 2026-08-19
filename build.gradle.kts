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
    // -ea turns the codebase's 32 `assert` statements into real invariant
    // checks. See README.md ("Assertions (-ea)") — enabling this is itself a
    // behaviour change, and the on/off decision for golden recording is
    // deliberately NOT made here (see issues #22/#24).
    //
    // --patch-module java.base=cybelle is required for Cybele to find
    // cybele.prop under JPMS; see README.md ("Why --patch-module").
    applicationDefaultJvmArgs = listOf("-ea", "--patch-module", "java.base=cybelle")
}

tasks.withType<JavaCompile>().configureEach {
    // Pin the source encoding rather than inheriting the daemon's platform default:
    // this branch exists to make runs reproducible, and a build that depends on the
    // ambient locale is not.
    options.encoding = "UTF-8"
}

// A second source set for verification drivers that are not part of the simulation and
// must never be on its classpath. `tools/java` holds SeedInterleavingCheck, the evidence
// for issue #15's "identical draw sequences regardless of thread interleaving" criterion.
// No external test framework is pulled in: this project builds from mavenLocal vendor jars
// and stays buildable offline.
val tools by sourceSets.creating {
    java.setSrcDirs(listOf("tools/java"))
    compileClasspath += sourceSets["main"].output + configurations.runtimeClasspath.get()
    runtimeClasspath += sourceSets["main"].output + configurations.runtimeClasspath.get()
}

// Sensitivity is cheap here and the budget is not: the pre-#15 shared-Random control is
// caught 0/47 at ten draws, so the small sweep below already has ~400x the sensitivity it
// needs, at 1/15th of the cost. `-Prng.full` runs the exhaustive sweep, and an explicit
// `--args=` overrides both.
val rngFull = providers.gradleProperty("rng.full").isPresent
val rngArgs = if (rngFull) listOf("check", "20080415", "24", "4000")
              else listOf("check", "20080415", "6", "500")
val rngReport = layout.buildDirectory.file("rng-proof/report.txt")

val rngProof by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Proves the per-agent RNG streams are identical under any thread interleaving (#15)."
    classpath = tools.runtimeClasspath
    mainClass.set("SeedInterleavingCheck")
    args = rngArgs
    // Forward -Dsim.* exactly as `run` does. `plan` mode resolves ScenarioConfig, so
    // without this `rngProof -Dsim.config=... --args="plan ..."` would silently predict
    // against the default topology, pairs and lambda while looking configured.
    val simProps = System.getProperties()
        .map { it.key.toString() to it.value.toString() }
        .filter { it.first.startsWith("sim.") }
        .toMap()
    simProps.forEach { (name, value) -> systemProperty(name, value) }
    systemProperty("rngproof.out", rngReport.get().asFile.absolutePath)
    // Declared inputs and outputs, so an incremental build can skip this. Without them the
    // task has no up-to-date state and re-runs in full on every single build.
    inputs.files(tools.runtimeClasspath).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("args", rngArgs)
    inputs.property("simProps", simProps)
    outputs.file(rngReport)
    doFirst { rngReport.get().asFile.parentFile.mkdirs() }
}

// A determinism guarantee that is not checked is a determinism hope. Cheap enough
// (~1 s, and UP-TO-DATE on an unchanged tree) that it can sit in every build.
tasks.named("check") {
    dependsOn(rngProof)
}

// #20's acceptance criterion -- "all fifteen channels appear in the trace, and it is
// COMPLETE" -- checked mechanically instead of by hand. Two steps: run the simulation
// headless and bounded with the probe on, then verdict what it produced.
//
// Deliberately NOT wired into `check`, unlike rngProof. This one boots the Cybele kernel,
// takes ~20 s of wall clock, and inherits the kernel's own residual hang (INVENTORY
// DEF-22, roughly 1 run in 45) -- putting that in every `./gradlew build` would trade a
// reliable build for a flaky one. It is one command when you want it.
val traceFile = layout.buildDirectory.file("trace/trace.txt")

val traceRun by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs a short bounded headless simulation with the parity trace probe on (#20)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("cz.vutbr.fit.ags.xhovor07.Main")
    jvmArgs("-ea", "--patch-module", "java.base=cybelle", "-Djava.awt.headless=true")
    systemProperty("sim.config", "scenarios/short-bounded.properties")
    systemProperty("sim.trace.enabled", "true")
    doFirst {
        traceFile.get().asFile.parentFile.mkdirs()
        // stdout is the trace; stderr stays on the console so the resolved-configuration
        // banner, the randomness manifest and any PROBE FAILURE report remain visible.
        standardOutput = traceFile.get().asFile.outputStream()
    }
    outputs.file(traceFile)
    outputs.upToDateWhen { false }   // a fresh run every time is the point
}

val traceCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verdicts the canonical parity trace: all 15 channels, round-trips, completeness (#20)."
    dependsOn(traceRun)
    classpath = tools.runtimeClasspath
    mainClass.set("TraceCheck")
    argumentProviders.add(CommandLineArgumentProvider { listOf(traceFile.get().asFile.absolutePath) })
}

tasks.named<JavaExec>("run") {
    // Forward -Dsim.* from the Gradle command line into the forked application JVM.
    // Without this, `./gradlew run -Dsim.arrival.lambdaMs=800` would set the property
    // on the Gradle daemon and the simulation would silently run with the defaults.
    // See docs/scenario-config.md.
    System.getProperties().forEach { key, value ->
        val name = key.toString()
        if (name.startsWith("sim.")) systemProperty(name, value.toString())
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("com.iai:cybele-api:1.0")
    implementation("com.iai:cybele-impl:1.0")
}
