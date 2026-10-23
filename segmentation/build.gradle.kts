plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

// Opt-in local replay of real traces: gradlew :segmentation:test -Ptarry.replay=<dir of CSVs> -i
tasks.withType<Test>().configureEach {
    providers.gradleProperty("tarry.replay").orNull?.let { systemProperty("tarry.replay", it) }
    testLogging.showStandardStreams = providers.gradleProperty("tarry.replay").isPresent
}
