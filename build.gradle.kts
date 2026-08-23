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

// ---------------------------------------------------------------------------------------------
// The domain source set (#28, Phase1.md 1-PORT "extract domain logic into plain classes").
//
// `src/domain/java` holds the scheduling, voting and ordering rules as plain Java:
// StationSchedule, RoadSchedule, RoadQueue(+Item), TrainPlan, DispatchTimeline, VoteEnvelope,
// TravelDelay, plus the graph/multimap structures moved out of the application package.
//
// WHY A SOURCE SET AND NOT JUST A PACKAGE. Its dependency configuration is left EMPTY on
// purpose. A source set gets its own `domainImplementation`/`domainCompileOnly`, and nothing
// is added to them, so the domain classes compile against the JDK and nothing else: an
// `import cybele.kernel.*` in this tree does not fail a review, it fails the compiler. A
// package inside `main` could not make that promise -- the whole point of criterion 1 ("no
// framework imports") is that it must be structural.
//
// WHY IT SUITS #46. The output is a jar of its own (`opencybele-domain.jar`) whose only
// content is `cz.vutbr.fit.ags.railway.domain.**`, with no Cybele, JADE, Swing or harness
// class inside it and no transitive dependency to drag one in. Branch `jason` consumes that
// artifact -- or the same source directory -- unchanged, which is exactly what "the JADE
// agents and the Jason internal actions are thin glue over the same classes" requires. The
// application depends on the jar like any other library, so the direction of the dependency
// is one-way and enforced: domain never sees the app.
val domain by sourceSets.creating {
    java.setSrcDirs(listOf("src/domain/java"))
}

val domainJar by tasks.registering(Jar::class) {
    archiveBaseName.set("opencybele-domain")
    from(domain.output)
}

// ---------------------------------------------------------------------------------------------
// The jade source set (#27, Phase1.md 1-PORT "messages -> ACLMessage").
//
// `src/jade/java` holds the ONE layer of the message ontology that cannot be framework-free:
// the binding from the domain's message records to `jade.lang.acl.ACLMessage`, the fifteen
// `MessageTemplate`s, and the topic names the probe (#36) registers to.
//
// WHY A SOURCE SET OF ITS OWN, AND NOT `main`. `main` is the Cybele application. This branch's
// parity gate runs it, its goldens are frozen, and `./gradlew run` / `installDist` must keep
// launching it with `--patch-module java.base=cybelle` and nothing else on the classpath.
// Putting the JADE jar on `implementation` would drop jade-4.3.jar into `build/install/
// opencybele/lib` and onto the start script's classpath, which is a change to the thing under
// measurement for no benefit -- the Cybele agents deliberately do NOT use this ontology (that
// is #30-#34's job, ticket by ticket, each with its own gate run). A source set gives the
// classes a home, a compile check on every build, and zero reach into the running application.
//
// It depends on `domain` and on JADE, and on nothing else. In particular it must never import
// `cz.vutbr.fit.ags.xhovor07` -- the split between this and `domain` is the split between
// "needs jade.lang.acl" and "reusable verbatim by branch jason (#46)".
val jadeOntology by sourceSets.creating {
    java.setSrcDirs(listOf("src/jade/java"))
    compileClasspath += domain.output
    runtimeClasspath += domain.output
}

dependencies {
    "jadeOntologyImplementation"("net.sf.ingenias:jade:4.3")
}

// #30 SPENDS THE BUDGET THE COMMENT ABOVE SET ASIDE, and this is the note that says so.
//
// That comment keeps JADE off `main` because "the Cybele agents deliberately do NOT use this
// ontology (that is #30-#34's job, ticket by ticket, each with its own gate run)". #30 is the
// first of those tickets: `Station` is now a `jade.core.Agent` that builds its messages with
// `Messages`/`Templates`, so `main` needs JADE and this source set's output on its compile
// classpath. Packaged as a jar, exactly like `domainJar`, so `main` consumes it as a library and
// the one-way dependency stays visible in the build file.
//
// The cost is the one that comment predicted: jade-4.3.jar and this jar land in
// `build/install/opencybele/lib` and on the start script's classpath. It is no longer a change to
// the thing under measurement -- per #4's sequencing decision the application stops running at
// #30, and the parity gate measures the frozen baseline dist in `../wt/opencybele-ref`, not this
// tree's. Nothing else in the build moves.
val jadeOntologyJar by tasks.registering(Jar::class) {
    archiveBaseName.set("opencybele-jade-ontology")
    from(jadeOntology.output)
}

tasks.named("check") {
    dependsOn(jadeOntology.classesTaskName)
}

// A classpath cannot exclude java.desktop or java.util.Random -- they are in the JDK. This
// does what the empty configuration cannot: it reads the sources and refuses the imports that
// would make the domain classes un-reusable, or re-introduce a global clock.
val domainPurity by tasks.registering {
    group = "verification"
    description = "Fails if a domain class imports a framework, a GUI toolkit or the harness (#28)."
    val sources = domain.java.asFileTree
    val report = layout.buildDirectory.file("domain-purity/report.txt")
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(report)
    doLast {
        // Cybele, JADE, Jason, the harness, every UI toolkit, and reflection -- the last one
        // because AbstractUnorientedGraph recovered a caller's method name from a stack trace
        // and #28 deleted it rather than porting it.
        val forbidden = listOf(
            "cybele.", "jade.", "jason.", "javax.swing", "java.awt", "javafx.",
            "cz.vutbr.fit.ags.xhovor07", "cz.vutbr.fit.ags.parity", "java.lang.reflect"
        )
        val offences = mutableListOf<String>()
        sources.forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) {
                    val imported = trimmed.removePrefix("import ").removePrefix("static ").trimEnd(';')
                    forbidden.filter { imported.startsWith(it) }.forEach {
                        offences += "${file.name}:${i + 1}: forbidden import '$imported' (matches '$it')"
                    }
                }
            }
        }
        val out = report.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            if (offences.isEmpty()) "domain purity OK: ${sources.files.size} files, no forbidden imports\n"
            else offences.joinToString("\n", postfix = "\n")
        )
        if (offences.isNotEmpty()) {
            throw GradleException("domain source set is not framework-free:\n" + offences.joinToString("\n"))
        }
    }
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
// #30 NOTE, DISCHARGED BY #36. `traceRun`/`traceCheck` stopped working at #30, because the
// application stopped booting: `Station` had become a `jade.core.Agent` and a Cybele
// `RailwayMainAgent` could not spawn it. That was expected and planned for (#4's sequencing
// decision: the tree compiles at every step of #30-#34 and runs at none of them). #36 completed
// the set -- `Main` boots a JADE main container and the probe registers to the fifteen topics --
// and these two tasks run again unchanged. `TraceCheck`'s one baseline-specific assertion, "field
// 6 is always '-'", was replaced rather than deleted: it now checks field 6 against the FIPA act
// #27 assigned to that channel.
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
    // The domain jar is a library like any other: on the compile and runtime classpath, and
    // therefore inside `installDist`'s lib/ and on the start script's classpath. The
    // dependency is deliberately one-way -- `domain` has no dependency on `main`.
    implementation(files(domainJar))

    // #30: `Station` is a `jade.core.Agent`. See the note next to `jadeOntologyJar` for why this
    // is now on `main` when the jadeOntology source set's comment says it should not be.
    implementation("net.sf.ingenias:jade:4.3")
    implementation(files(jadeOntologyJar))

    // L1 (TESTING.md 4.1): unit tests for the extracted domain rules. JUnit 5 is already a
    // dependency of the harness source set, so this adds no new coordinate to resolve.
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // #27's binding tests -- template disjointness and the ACLMessage round trip -- need the real
    // JADE classes to be worth anything: a hand-rolled stub would prove that the stub is disjoint.
    //
    // #30 CORRECTS THIS COMMENT. It used to end "JADE is on the TEST classpath only. It is
    // deliberately NOT on `implementation`", which the `implementation` line above now contradicts.
    // The explicit `testImplementation("net.sf.ingenias:jade:4.3")` that used to sit here is gone
    // with it: `testImplementation` extends `implementation`, so it was resolving the same
    // coordinate twice and stating a constraint that no longer holds. The tests still get JADE --
    // from `implementation`, along with the application they now test.
}

// The binding classes are compiled by their own source set; the unit tests see them the same
// way #30-#34 will, as a compiled output on the classpath rather than as loose sources.
sourceSets["test"].compileClasspath += jadeOntology.output
sourceSets["test"].runtimeClasspath += jadeOntology.output

tasks.named<Test>("test") {
    dependsOn(jadeOntology.classesTaskName)
    useJUnitPlatform()
    // ONE JVM for the whole lane, pinned rather than inherited from the default. #27's
    // JadeDeliverySpikeTest boots a JADE main container, and `jade.core.Runtime` is a JVM-wide
    // singleton -- as is `jade.core.AID.platformID`, which AgentContainerImpl overwrites at
    // boot. Booting a second one in a second fork is not something these tests are written for,
    // and a test that reasons about that global state has to be able to say what it is running
    // in. Both values happen to be today's defaults; the point is that a future edit has to
    // change them deliberately rather than inherit a change. (Same reasoning, and the same two
    // lines, as characterizationIT below.)
    //
    // #38 CONSIDERED MOVING JadeDeliverySpikeTest INTO THE integrationTest LANE AND DID NOT.
    // It is the slowest test here (~1.3 s) and it does boot a container, so the lane below is where
    // a class of its shape would be written today. Two reasons it stays: it is #27's spike, cited by
    // name in docs/message-ontology.md sections 6 and 7 as the evidence for the topic-granularity
    // decision, and moving the file would strand those references; and its
    // booting_a_container_overwrites_the_global_platform_id test is the regression cover for a
    // hazard that belongs to THIS lane -- with it gone, no container would ever boot in the `test`
    // JVM and AclBindingTest's "derive the platform id, never hardcode it" discipline would stop
    // being exercised against a real overwrite. The two lines above would then be a pin with nothing
    // left to pin.
    maxParallelForks = 1
    forkEvery = 0
    // -ea matches how the application runs (README "Assertions (-ea)", #22): a domain class
    // asserting its own preconditions must be exercised the same way here as in a recording.
    jvmArgs("-ea")
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named("check") {
    dependsOn(domainPurity)
}

// ---------------------------------------------------------------------------------------------
// L2 — the integration layer (TESTING.md §4.2, Phase1.md 1-POST.2, issue #38).
//
// A source set of its own, and unlike `characterizationIT` it is wired TO `main`: these tests boot
// a real JADE main container in-process and start the REAL Station / RoadAgent / Train / TraceProbe
// inside it. That is the point -- the messaging wiring (AID unicast, the fifteen topics, the one
// shared message queue) is invisible to an L1 POJO test and un-isolatable at L3, where a divergence
// is one line in a several-thousand-line trace.
//
// WHY A SOURCE SET AND NOT A PACKAGE IN `test`. The `test` lane runs its 321 tests in ONE JVM
// (forkEvery = 0), which it can do because a POJO test mutates little global state. A container
// test mutates a lot: `jade.core.Runtime` is a JVM-wide singleton, `jade.core.AID.platformID` is
// overwritten by every container boot, and `RunControl`'s clock, `TraceProbe.READY` and
// `TraceProbe.FAILURES` are statics with no reset hook -- the last two are named in #37 as
// untestable at L1 for exactly that reason. A separate lane can pick a DIFFERENT fork policy for
// them, and does:
//
//   maxParallelForks = 1   no two containers alive in one JVM at once; the singleton forbids it.
//   forkEvery = 1          ONE JVM PER TEST CLASS. This is the lane's fixture, not a tuning knob.
//                          It is what makes `TraceProbe.READY` (a 60 s latch counted down once and
//                          never reset) and `TraceProbe.FAILURES` (a monotone counter with no
//                          reset) testable at all: every class gets them at their initial value.
//                          ProbeReadyTimeoutIT and ProbeFailureCapIT depend on this and say so in
//                          their class comments. It also makes "one container per class" the same
//                          statement as "one container per JVM", so no class can inherit another's
//                          platform id -- the order-coupling JadeDeliverySpikeTest documents.
//
// Both values are pinned rather than inherited, for the reason the `test` lane gives above: a
// future edit has to change them deliberately.
//
// NOT WIRED INTO `check`, on the precedent `characterizationIT` sets below: this lane binds a JICP
// listener and forks a JVM per class, and `./gradlew build` must stay a build. `check` compiles it,
// so a test that stops compiling still fails the build.
// ---------------------------------------------------------------------------------------------

val integrationTestSourceSet: SourceSet = sourceSets.create("integrationTest")

configurations["integrationTestImplementation"].extendsFrom(configurations["implementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])

dependencies {
    // The application itself, as a compiled output: these tests start the real agent classes.
    "integrationTestImplementation"(sourceSets["main"].output)
    "integrationTestImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
    "integrationTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "L2 in-process JADE container tests, one container per class (TESTING.md §4.2)."
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()

    // See the block comment above. These two lines ARE the fixture.
    maxParallelForks = 1
    forkEvery = 1

    // -ea for the reason the `test` lane gives: the agents' asserts are a contract surface and the
    // goldens were recorded with them on. Headless because a container test must never open a
    // Swing window.
    jvmArgs("-ea", "-Djava.awt.headless=true")

    // ABSOLUTE, and that is not a style preference: JADE's AMS concatenates this value with
    // "APDescription.txt" rather than resolving it, and #36 lost a gate run to a relative one that
    // put a 25-line FileNotFoundException ahead of line 1 of the trace. Each class makes its own
    // subdirectory under this root and deletes it again.
    val jadeFileDir = layout.buildDirectory.dir("integration-test/jade")
    systemProperty("jade.file.dir", jadeFileDir.get().asFile.absolutePath)
    doFirst { jadeFileDir.get().asFile.mkdirs() }

    // A container boot is not something Gradle can hash.
    outputs.upToDateWhen { false }

    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named("check") {
    dependsOn(integrationTestSourceSet.classesTaskName)
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
    //
    // #36 adds `jade.dist`, the same thing for adapter 2: a `build/install/opencybele` built from
    // the JADE port. There is no `jade.home` -- OpenCybeleLauncher needs a second directory only
    // because Cybele reads cybelle/*.prop through --patch-module, and the JADE implementation
    // reads no such file.
    for (name in listOf("opencybele.dist", "opencybele.home", "jade.dist")) {
        (providers.gradleProperty(name).orNull ?: providers.systemProperty(name).orNull)
            ?.let { systemProperty(name, file(it).absolutePath) }
    }
    for (name in listOf("opencybele.java", "jade.java")) {
        (providers.gradleProperty(name).orNull ?: providers.systemProperty(name).orNull)
            ?.let { systemProperty(name, it) }
    }

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
    for (name in listOf("opencybele.dist", "opencybele.home", "jade.dist")) {
        (providers.gradleProperty(name).orNull ?: providers.systemProperty(name).orNull)
            ?.let { systemProperty(name, file(it).absolutePath) }
    }
    for (name in listOf("opencybele.java", "jade.java")) {
        (providers.gradleProperty(name).orNull ?: providers.systemProperty(name).orNull)
            ?.let { systemProperty(name, it) }
    }

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
