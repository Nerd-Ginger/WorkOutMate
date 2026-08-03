import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

group = "com.nerdginger.workoutmate"
version = "2.0.0"

kotlin {
    // `jvm()` is the only target, and that is the point: it makes the whole
    // module compilable and testable with no Android SDK present. The Android
    // app consumes this JVM artifact directly (androidJvm consuming jvm is the
    // same arrangement Ktor and OkHttp publish for).
    //
    // iOS targets get added here when iOS becomes real. Nothing in commonMain
    // may reference a platform API, so that change stays additive — platform
    // needs are expressed as the ports in `platform/Ports.kt`.
    jvm {
        compilerOptions {
            // Matches the Android build's target. No toolchain is declared on
            // purpose: requesting a JDK that isn't installed makes Gradle try
            // to download one, which fails in offline or restricted setups.
            // Compiling on a newer JDK targeting 17 bytecode is fine here
            // because the module has no Java sources.
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            api(libs.sqldelight.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Real SQLite, in memory. Every query and migration in this module
            // is exercised against the actual engine rather than a fake.
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

sqldelight {
    databases {
        create("WorkoutDb") {
            packageName.set("com.nerdginger.workoutmate.db")
        }
    }
}

tasks.withType<Test>().configureEach {
    // Deliberately not useJUnitPlatform(): kotlin("test") resolves to the
    // JUnit 4 variant on JVM, and switching the platform without adding a
    // JUnit 5 engine makes the suite silently find zero tests.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
