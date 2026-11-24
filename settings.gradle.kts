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
    }
}

rootProject.name = "StructuraAI"

// :core  — pure-Kotlin design engine (IS codes). No Android dependency, unit-testable on any JVM.
// :app   — Android app (Jetpack Compose UI).
include(":core")
include(":app")
