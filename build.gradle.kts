import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.spotless)
}

// Formatting and style by ktlint, configured in .editorconfig. `spotlessApply` fixes what it can;
// `spotlessCheck` runs with `check`, so a build fails on code that is not formatted.
spotless {
    // Only where sources live: walking the build folders races with the tasks writing into them.
    kotlin {
        target("androidApp/src/**/*.kt", "shared/*/src/**/*.kt")
        // With the rules for Compose: how composables are named and take their parameters, what they remember.
        ktlint(libs.versions.ktlint.get())
            .customRuleSets(listOf("io.nlopez.compose.rules:ktlint:${libs.versions.composeRules.get()}"))
            .editorConfigOverride(
                mapOf(
                    // A screen fills the window and takes no modifier; the parts screens are made of do, and take one.
                    "ktlint_compose_modifier-missing-check" to "disabled",
                    // The one value handed down unseen: whether the dark theme is on, which the map has to know.
                    "compose_allowed_composition_locals" to "LocalDarkTheme",
                ),
            )
    }
    kotlinGradle {
        target("*.gradle.kts", "androidApp/*.gradle.kts", "shared/*/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

// Every compiler warning, including the extra ones Kotlin leaves off by default, fails the build.
subprojects {
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            allWarningsAsErrors.set(true)
            extraWarnings.set(true)
        }
    }
}

/**
 * Keeps each outside library behind one door, so that it can be watched, updated
 * or replaced in one place: fails the build when a source file imports one of
 * [rules]' packages from anywhere but the places listed for it.
 */
abstract class BoundariesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Import prefix to the paths (relative to the project, files or folders) that may import it. */
    @get:Input
    abstract val rules: MapProperty<String, List<String>>

    @get:Internal
    abstract val root: DirectoryProperty

    @TaskAction
    fun check() {
        val rootDir = root.get().asFile
        val broken =
            sources.files.sorted().flatMap { file ->
                val path = file.relativeTo(rootDir).invariantSeparatorsPath
                // Any mention counts, not only an import: a library can be used by its full name without one.
                // Comments are left out, where naming a library is no use of it.
                file.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.flatMap { line ->
                    rules.get().entries.mapNotNull { rule ->
                        val used = Regex("(?<![\\w.])" + Regex.escape(rule.key)).containsMatchIn(line)
                        if (!used || rule.value.any(path::startsWith)) {
                            null
                        } else {
                            "$path uses ${rule.key}*; allowed only in ${rule.value.joinToString()}"
                        }
                    }
                }
            }
        if (broken.isNotEmpty()) throw GradleException(broken.joinToString("\n", prefix = "Library boundaries broken:\n"))
    }
}

val checkBoundaries =
    tasks.register<BoundariesTask>("checkBoundaries") {
        description = "Checks that outside libraries are imported only where the architecture allows."
        group = "verification"
        root.set(layout.projectDirectory)
        sources.from(fileTree(layout.projectDirectory) { include("androidApp/src/main/**/*.kt", "shared/*/src/*Main/**/*.kt") })
        val app = "androidApp/src/main/kotlin/app/dojazd"
        rules.set(
            mapOf(
                // The app's only way to the network.
                "java.net." to listOf("$app/data/Network.kt"),
                "javax.net." to listOf("$app/data/Network.kt"),
                // Other ways online, which nothing may take: they would go around the log and offline mode.
                "okhttp3." to emptyList(),
                "android.net.http." to emptyList(),
                "android.webkit." to emptyList(),
                "android.app.DownloadManager" to emptyList(),
                // The map.
                "org.maplibre." to listOf("$app/ui/map/"),
                // The colour schemes.
                "com.materialkolor." to listOf("$app/ui/theme/"),
                // The timetable database.
                "app.cash.sqldelight." to listOf("shared/gtfs/", "$app/data/"),
                // Passing messages.
                "android.widget.Toast" to listOf("$app/ui/Components.kt"),
            ),
        )
    }

tasks.named("check") { dependsOn(checkBoundaries) }
