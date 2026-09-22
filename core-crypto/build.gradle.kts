plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core-common"))
    implementation(libs.acinq.bitcoin.kmp)
    implementation(libs.acinq.secp256k1.jni.jvm)
    testImplementation(libs.junit)
}
