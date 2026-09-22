plugins {
    alias(libs.plugins.kotlin.jvm)
}

import org.gradle.api.tasks.testing.Test

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-crypto"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.withType<Test>().configureEach {
    outputs.upToDateWhen { System.getenv("GLANCE_LIVE_SMOKE") != "true" }
}
