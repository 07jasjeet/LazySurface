plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jasjeet.lazysurface.demo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jasjeet.lazysurface.demo"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Unminified so profiler traces keep readable LazySurface frames, and
            // debug-signed so `installRelease` works on a local device — together
            // with the manifest's <profileable> this is the profiling build.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The demo UI (and its previews) live in :app:shared; this module is only the
    // Android entry point.
    implementation(project(":app:shared"))
    implementation(libs.androidx.activity.compose)
}