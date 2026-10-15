pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central first: Maven Central's CDN rate-limits some shared
        // networks (HTTP 429). Same artifacts; mavenCentral() stays as the fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central first: Maven Central's CDN rate-limits some shared
        // networks (HTTP 429). Same artifacts; mavenCentral() stays as the fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
    }
}

rootProject.name = "Mise"
include(":core")
