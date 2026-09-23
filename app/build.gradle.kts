import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class VerifyReleaseConfiguration : DefaultTask() {
    @get:Input abstract val storeFilePath: Property<String>
    @get:Input abstract val storePassword: Property<String>
    @get:Input abstract val keyAlias: Property<String>
    @get:Input abstract val keyPassword: Property<String>
    @get:Input abstract val donationOnChain: Property<String>
    @get:Input abstract val donationLightning: Property<String>

    @TaskAction
    fun verify() {
        val missingSigningInputs = listOf(
            "GLANCE_RELEASE_STORE_FILE" to storeFilePath.get(),
            "GLANCE_RELEASE_STORE_PASSWORD" to storePassword.get(),
            "GLANCE_RELEASE_KEY_ALIAS" to keyAlias.get(),
            "GLANCE_RELEASE_KEY_PASSWORD" to keyPassword.get(),
        ).filter { (_, value) -> value.isBlank() }.map { it.first }
        check(missingSigningInputs.isEmpty()) {
            "Release signing is not configured; missing ${missingSigningInputs.joinToString()}."
        }
        check(!donationOnChain.get().contains("placeholder", ignoreCase = true)) {
            "GLANCE_DONATION_ON_CHAIN must be a real public donation address."
        }
        check(!donationLightning.get().contains("placeholder", ignoreCase = true)) {
            "GLANCE_DONATION_LIGHTNING must be a real public Lightning invoice or address."
        }
    }
}

val liveAddress = providers.environmentVariable("GLANCE_LIVE_ADDRESS").orNull
val liveInstrumentationClass = providers.environmentVariable("GLANCE_LIVE_TEST_CLASS").orNull
val liveElectrumHost = providers.environmentVariable("GLANCE_LIVE_ELECTRUM_HOST").orNull
val liveElectrumPort = providers.environmentVariable("GLANCE_LIVE_ELECTRUM_PORT").orNull
val liveElectrumTls = providers.environmentVariable("GLANCE_LIVE_ELECTRUM_TLS").orNull
val liveEsploraBaseUrl = providers.environmentVariable("GLANCE_LIVE_ESPLORA_BASE_URL").orNull
val configuredDonationOnChain = providers.environmentVariable("GLANCE_DONATION_ON_CHAIN").orNull
    ?: "bc1qglanceplaceholderdonation"
val configuredDonationLightning = providers.environmentVariable("GLANCE_DONATION_LIGHTNING").orNull
    ?: "lnbc1placeholderdonation"
val releaseStoreFile = providers.environmentVariable("GLANCE_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("GLANCE_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("GLANCE_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("GLANCE_RELEASE_KEY_PASSWORD").orNull

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

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
        versionName = providers.gradleProperty("glanceVersionName").orElse("0.1.0-beta.1").get()
        buildConfigField("String", "DONATION_ON_CHAIN", configuredDonationOnChain.asBuildConfigString())
        buildConfigField("String", "DONATION_LIGHTNING", configuredDonationLightning.asBuildConfigString())

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
    signingConfigs {
        if (
            !releaseStoreFile.isNullOrBlank() &&
            !releaseStorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() &&
            !releaseKeyPassword.isNullOrBlank()
        ) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
            buildTypes.named("release") {
                signingConfig = signingConfigs.getByName("release")
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

val releaseApkDirectory = layout.buildDirectory.dir("outputs/apk/release")

tasks.register("verifyReleaseLoggingStripped") {
    group = "verification"
    description = "Fails when a release APK retains the debug Timber logging implementation."
    dependsOn("assembleRelease")
    inputs.dir(releaseApkDirectory)
    doLast {
        val apk = inputs.files.asFileTree.matching { include("*.apk") }.files
            .sortedBy { it.name }
            .firstOrNull { it.name == "app-release.apk" }
            ?: inputs.files.asFileTree.matching { include("*.apk") }.files.singleOrNull()
            ?: error("Release APK was not produced.")
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

tasks.register<VerifyReleaseConfiguration>("verifyReleaseConfiguration") {
    group = "verification"
    description = "Fails when a CI release lacks signing or real public donation values."
    storeFilePath.set(releaseStoreFile ?: "")
    storePassword.set(releaseStorePassword ?: "")
    keyAlias.set(releaseKeyAlias ?: "")
    keyPassword.set(releaseKeyPassword ?: "")
    this.donationOnChain.set(configuredDonationOnChain)
    this.donationLightning.set(configuredDonationLightning)
}

tasks.named("check").configure { dependsOn("verifyReleaseLoggingStripped") }
