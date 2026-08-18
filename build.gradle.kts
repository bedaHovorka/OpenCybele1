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
    // -ea turns the codebase's 33 `assert` statements into real invariant
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
