import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:model"))
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.driver.jvm)
        }
    }
}

sqldelight {
    databases {
        create("GtfsDatabase") {
            packageName.set("app.dojazd.gtfs.db")
        }
    }
}

// Opt-in integration test against a real feed:
//   ./gradlew :shared:gtfs:jvmTest -Pdojazd.gtfsZip=/path/to/gtfs.zip
tasks.withType<Test>().configureEach {
    providers.gradleProperty("dojazd.gtfsZip").orNull?.let { path ->
        inputs.file(path)
        systemProperty("dojazd.gtfsZip", path)
    }
}
