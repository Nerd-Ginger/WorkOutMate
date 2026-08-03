// A SEPARATE Gradle build, deliberately. The Android Gradle Plugin is never
// applied here and `google()` is never declared.
//
// Two reasons, and both matter:
//
// 1. Gradle configures every project in a build on every invocation. If :app
//    lived in this build, running a single core test would still configure
//    :app, apply AGP, and reach for dl.google.com — which some environments
//    block outright. Keeping the builds separate is what makes
//    `./gradlew -p core check` work with nothing but Maven Central.
//
// 2. The repository list is the architectural guardrail. Because only
//    mavenCentral() is declared, adding an androidx dependency to core fails
//    here with a clean "not found in mavenCentral" instead of silently
//    resolving in CI and quietly making the core untestable. The rule "core
//    stays platform-free" is enforced by the build, not by discipline.

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    // Share the repo-root catalog rather than keeping a second version list in
    // step by hand. Entries referring to AGP/androidx are never resolved here.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "core"
