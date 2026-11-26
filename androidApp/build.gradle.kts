plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// A release build is given its version as -PappVersionName=1.2.3.
val appVersionName = providers.gradleProperty("appVersionName").getOrElse("0.0.1")

// 1.2.3 -> 10203. Must only ever grow, or Android refuses the update.
fun versionCodeOf(versionName: String): Int {
    val match =
        Regex("""^(\d+)\.(\d{1,2})\.(\d{1,2})$""").matchEntire(versionName)
            ?: error("appVersionName must look like 1.2.3 (minor and patch below 100), got '$versionName'")
    val (major, minor, patch) = match.destructured
    return major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

// Signing is configured only when the keystore is provided through the environment;
// otherwise `assembleRelease` produces an unsigned APK.
val keystoreFile = providers.environmentVariable("DOJAZD_KEYSTORE_FILE").orNull

android {
    namespace = "app.dojazd"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.dojazd"
        minSdk = 26
        targetSdk = 37
        versionCode = versionCodeOf(appVersionName)
        versionName = appVersionName

        // The map library's native code is about 10 MB per processor type. Phones and tablets are ARM;
        // the 32-bit x86 build would only be dead weight.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = providers.environmentVariable("DOJAZD_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("DOJAZD_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("DOJAZD_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            // Its own app, so that trying a change out does not replace the released one and its data.
            applicationIdSuffix = ".debug"
            // Emulators on Intel computers.
            ndk {
                abiFilters += "x86_64"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
            // The embedded git revision would make a build from a source archive or a worktree differ
            // from the published APK, and the tag already names the commit.
            vcsInfo.include = false
        }
        // The release build, signed with the debug key so that it installs anywhere: for judging how fast
        // the app really is, as the debug build runs unoptimised Compose and is many times slower.
        //   ./gradlew :androidApp:installOptimized
        create("optimized") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Ship the city registry (data/cities.json) as an app asset.
    sourceSets {
        named("main") {
            assets.directories.add(rootProject.file("data").path)
        }
    }

    // The app has its own language setting, so every language must be installed,
    // not just the ones matching the device (relevant once app bundles are published).
    bundle {
        language {
            enableSplit = false
        }
    }

    // Map files are read at arbitrary positions and are compressed inside already.
    androidResources {
        noCompress += "pmtiles"
        // The libraries come translated into some eighty languages. Kept, they would make Android pick,
        // say, German for a user whose languages are German then Polish, and the app would fall back to English.
        localeFilters += listOf("en", "pl")
    }

    // Every check Android lint has, and every warning an error. See lint.xml for the few that are turned off.
    lint {
        checkAllWarnings = true
        warningsAsErrors = true
        abortOnError = true
        lintConfig = file("lint.xml")
    }

    // Do not embed the dependency list blob that only Google Play can read.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

/**
 * The map asks for a glyph file for every range of 256 characters it meets in
 * a label, and shows nothing at all when one is missing. Only the ranges of the
 * alphabets worth shipping are in the repository (see tools/mapstyle); this
 * task adds a file without glyphs for each of the others, a thousand of them,
 * so that a stray character costs a blank spot instead of the whole map.
 */
abstract class EmptyGlyphsTask : DefaultTask() {
    /** `assets/map/fonts`: a folder of shipped ranges per font, and `fonts.txt` naming every font the styles use. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fonts: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = fonts.get().asFile
        val target = output.get().asFile
        target.deleteRecursively()
        for (font in source.resolve("fonts.txt").readLines().filter { it.isNotBlank() }) {
            for (start in 0 until 65_536 step 256) {
                val range = "$start-${start + 255}"
                if (source.resolve("$font/$range.pbf").exists()) continue
                // The protobuf message glyphs { stacks { name: <font>, range: <range> } }, with no glyphs in it.
                val stack = field(0x0A, font.toByteArray()) + field(0x12, range.toByteArray())
                target
                    .resolve("map/fonts/$font")
                    .apply { mkdirs() }
                    .resolve("$range.pbf")
                    .writeBytes(field(0x0A, stack))
            }
        }
    }

    private fun field(
        tag: Int,
        content: ByteArray,
    ) = byteArrayOf(tag.toByte(), content.size.toByte()) + content
}

val generateEmptyGlyphs =
    tasks.register<EmptyGlyphsTask>("generateEmptyGlyphs") {
        fonts.set(layout.projectDirectory.dir("src/main/assets/map/fonts"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(generateEmptyGlyphs, EmptyGlyphsTask::output)
    }
}

dependencies {
    implementation(project(":shared:provider"))
    implementation(project(":shared:gtfs"))
    implementation(project(":shared:routing"))
    implementation(project(":shared:geocode"))
    implementation(project(":shared:storage"))
    implementation(project(":shared:realtime"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.sqldelight.driver.android)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.materialkolor)
    implementation(libs.maplibre)
    // Lets the ahead-of-time compilation profiles that Compose and other libraries ship take effect
    // for an APK installed from outside an app store too, which makes the app start and scroll faster.
    implementation(libs.androidx.profileinstaller)

    testImplementation(kotlin("test-junit"))
}
