// The Android build. `core` is a SEPARATE build, pulled in as a composite so
// that it can be developed and tested without the Android Gradle Plugin ever
// being applied — see core/settings.gradle.kts for why that matters.
//
// Everyday core work is `./gradlew -p core check`, which never loads this file.
// This build is what produces an APK.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "WorkOutMate"

include(":app")

// The substitution is spelled out rather than left to Gradle's automatic
// coordinate matching: an Android consumer resolving a JVM-target producer
// across a composite boundary is the one piece of this build arrangement most
// likely to misbehave, and an explicit rule fails loudly instead of silently
// falling back to a non-existent published artifact.
includeBuild("core") {
    dependencySubstitution {
        substitute(module("com.nerdginger.workoutmate:core")).using(project(":"))
    }
}
