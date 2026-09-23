plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.glance.wallet.core.security"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-data"))
    implementation(project(":core-crypto"))
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore.tink)
    implementation(libs.androidx.room.runtime)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

val securitySources = fileTree("src") { include("**/*.kt", "**/*.java") }

tasks.register("verifySecurityLogging") {
    group = "verification"
    description = "Rejects raw Android logging calls in core-security source."
    inputs.files(securitySources)
    doLast {
        val prohibited = Regex("\\b(?:android\\.util\\.)?Log\\.[A-Za-z]+\\s*\\(|\\bprintln\\s*\\(")
        inputs.files.files.forEach { file ->
            if (prohibited.containsMatchIn(file.readText())) error("Raw logging is prohibited: ${file.name}")
        }
    }
}

tasks.named("check").configure { dependsOn("verifySecurityLogging") }
