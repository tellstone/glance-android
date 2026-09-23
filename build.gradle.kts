// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
}

val kotlinFileSoftLimit = 500
val kotlinFileHardLimit = 1_000

val verifyKotlinFileLength = tasks.register("verifyKotlinFileLength") {
    group = "verification"
    description = "Reports production Kotlin files above $kotlinFileSoftLimit lines and rejects files above $kotlinFileHardLimit lines."

    doLast {
        val sourceFiles = subprojects.flatMap { project ->
            project.fileTree(project.projectDir) {
                include("src/main/**/*.kt")
            }.files
        }.sortedBy { it.invariantSeparatorsPath }
        val oversized = sourceFiles.map { file ->
            file to file.useLines { lines -> lines.count() }
        }.filter { (_, lines) -> lines > kotlinFileSoftLimit }

        oversized.filter { (_, lines) -> lines <= kotlinFileHardLimit }.forEach { (file, lines) ->
            logger.warn("Kotlin source-size target exceeded ($lines/$kotlinFileSoftLimit lines): ${file.relativeTo(rootDir).invariantSeparatorsPath}")
        }
        val violations = oversized.filter { (_, lines) -> lines > kotlinFileHardLimit }
        check(violations.isEmpty()) {
            violations.joinToString(
                prefix = "Production Kotlin files must not exceed $kotlinFileHardLimit lines:\n",
                separator = "\n",
            ) { (file, lines) -> "- ${file.relativeTo(rootDir).invariantSeparatorsPath}: $lines lines" }
        }
    }
}

gradle.projectsEvaluated {
    subprojects.forEach { project ->
        project.tasks.matching { it.name == "check" }.configureEach {
            dependsOn(verifyKotlinFileLength)
        }
    }
}
