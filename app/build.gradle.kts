plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nerdginger.workoutmate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nerdginger.workoutmate"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Web assets are hand-written and already minimal; compressing them buys
    // nothing and makes them harder to inspect in a built APK.
    androidResources {
        noCompress += listOf("json")
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "ObsoleteLintCustomCheck")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.security.crypto)
    testImplementation(libs.junit)
}
