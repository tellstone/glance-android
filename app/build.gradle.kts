import java.util.zip.ZipFile

val liveAddress = providers.environmentVariable("GLANCE_LIVE_ADDRESS").orNull
val liveInstrumentationClass = providers.environmentVariable("GLANCE_LIVE_TEST_CLASS").orNull
val liveElectrumHost = providers.environmentVariable("GLANCE_LIVE_ELECTRUM_HOST").orNull
val liveElectrumPort = providers.environmentVariable("GLANCE_LIVE_ELECTRUM_PORT").orNull
val liveElectrumTls = providers.environmentVariable("GLANCE_LIVE_ELECTRUM_TLS").orNull
val liveEsploraBaseUrl = providers.environmentVariable("GLANCE_LIVE_ESPLORA_BASE_URL").orNull

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.glance.wallet"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.glance.wallet"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (!liveAddress.isNullOrBlank()) {
            testInstrumentationRunnerArguments["glance.live.address"] = liveAddress
        }
        if (!liveInstrumentationClass.isNullOrBlank()) {
            testInstrumentationRunnerArguments["class"] = liveInstrumentationClass
        } else {
            testInstrumentationRunnerArguments["notClass"] = "app.glance.wallet.TorLiveSmokeTest"
        }
        if (!liveElectrumHost.isNullOrBlank()) {
            testInstrumentationRunnerArguments["glance.live.electrum_host"] = liveElectrumHost
        }
        if (!liveElectrumPort.isNullOrBlank()) {
            testInstrumentationRunnerArguments["glance.live.electrum_port"] = liveElectrumPort
        }
        if (!liveElectrumTls.isNullOrBlank()) {
            testInstrumentationRunnerArguments["glance.live.electrum_tls"] = liveElectrumTls
        }
        if (!liveEsploraBaseUrl.isNullOrBlank()) {
            testInstrumentationRunnerArguments["glance.live.esplora_base_url"] = liveEsploraBaseUrl
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // The pinned catalog is intentionally audited in Phase 10, rather than failing CI whenever
        // a newer dependency is published.
        disable += "GradleDependency"
        disable += "NewerVersionAvailable"
        warningsAsErrors = true
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-crypto"))
    implementation(project(":core-network"))
    implementation(project(":core-data"))
    implementation(project(":core-security"))

    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.lottie.compose)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kmp.tor.runtime)
    implementation(libs.kmp.tor.resource.exec)
    implementation(libs.kmp.tor.resource.compilation)
    implementation(libs.acinq.secp256k1.jni.android)

    testImplementation(libs.junit)
    testImplementation(libs.acinq.bitcoin.kmp)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(project(":core-data"))
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.acinq.bitcoin.kmp)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

val releaseApk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk")

tasks.register("verifyReleaseLoggingStripped") {
    group = "verification"
    description = "Fails when a release APK retains the debug Timber logging implementation."
    dependsOn("assembleRelease")
    inputs.file(releaseApk)
    doLast {
        val apk = inputs.files.singleFile
        check(apk.isFile) { "Release APK was not produced." }
        val forbiddenTypes = listOf(
            "Ltimber/log/Timber;",
            "Lapp/glance/wallet/core/security/RedactingDebugTree;",
            "Lapp/glance/wallet/core/security/SecurityLogging;",
        )
        ZipFile(apk).use { archive ->
            val dexContents = archive.entries().asSequence()
                .filter { entry -> entry.name.matches(Regex("classes\\d*\\.dex")) }
                .map { entry -> archive.getInputStream(entry).readBytes().decodeToString() }
                .toList()
            forbiddenTypes.forEach { type ->
                check(dexContents.none { dex -> type in dex }) { "Release APK retains logging type: $type" }
            }
        }
    }
}

tasks.named("check").configure { dependsOn("verifyReleaseLoggingStripped") }
