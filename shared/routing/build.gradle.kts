import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
            api(project(":shared:gtfs"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.driver.jvm)
        }
    }
}

// One test walks the street network of Szczecin that ships with the app; another, opt-in, plans over a real feed:
//   ./gradlew :shared:routing:jvmTest -Pdojazd.gtfsZip=/path/to/gtfs.zip
val szczecinWalks = rootProject.file("data/walks/szczecin.walk")
tasks.withType<Test>().configureEach {
    inputs.file(szczecinWalks)
    systemProperty("dojazd.walks", szczecinWalks.absolutePath)
    providers.gradleProperty("dojazd.gtfsZip").orNull?.let { path ->
        inputs.file(path)
        systemProperty("dojazd.gtfsZip", path)
    }
}
