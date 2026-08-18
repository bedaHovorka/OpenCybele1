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

val rngProof by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Proves the per-agent RNG streams are identical under any thread interleaving (#15)."
    classpath = tools.runtimeClasspath
    mainClass.set("SeedInterleavingCheck")
    // Arguments: [check|plan] [...]. `./gradlew rngProof --args="plan 20080415 30"`
    // predicts a run's generator draws offline.
}

// A determinism guarantee that is not checked is a determinism hope.
tasks.named("check") {
    dependsOn(rngProof)
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
