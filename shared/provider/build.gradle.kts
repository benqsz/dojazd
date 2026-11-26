import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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

// The registry test validates the real data/cities.json shipped with the app and the files it refers to.
val citiesFile = rootProject.file("data/cities.json")
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("data"))
    systemProperty("dojazd.citiesFile", citiesFile.absolutePath)
}
