pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MapLibre and the Socket.IO client both publish to Maven Central,
        // so no extra repository is needed for the map or the live layer.
    }
}

rootProject.name = "Convoy"
include(":app")
