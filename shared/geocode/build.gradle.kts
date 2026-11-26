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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// One test searches the address file of Szczecin that ships with the app.
val szczecinAddresses = rootProject.file("data/addresses/szczecin.tsv")
tasks.withType<Test>().configureEach {
    inputs.file(szczecinAddresses)
    systemProperty("dojazd.addresses", szczecinAddresses.absolutePath)
}
