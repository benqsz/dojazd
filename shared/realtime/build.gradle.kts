import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// GTFS-RT: live delays, vehicle positions and service alerts.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:model"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Opt-in check against real feeds:
//   ./gradlew :shared:realtime:jvmTest -Pdojazd.gtfsRtDir=/path/to/folder/with/pb/files
tasks.withType<Test>().configureEach {
    providers.gradleProperty("dojazd.gtfsRtDir").orNull?.let { path ->
        inputs.dir(path)
        systemProperty("dojazd.gtfsRtDir", path)
    }
}
