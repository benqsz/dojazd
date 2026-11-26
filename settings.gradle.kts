pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dojazd"

include(":androidApp")
include(":shared:model")
include(":shared:gtfs")
include(":shared:realtime")
include(":shared:routing")
include(":shared:geocode")
include(":shared:provider")
include(":shared:storage")
