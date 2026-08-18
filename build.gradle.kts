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

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("com.iai:cybele-api:1.0")
    implementation("com.iai:cybele-impl:1.0")
}
