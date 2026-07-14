// The org.jetbrains.compose Gradle plugin is deliberately NOT applied: its resources
// wiring is incompatible with AGP 9's KMP library plugin, and this module needs none of
// its features. The Compose Multiplatform dependencies are declared as plain coordinates.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)

    androidLibrary {
        namespace = "com.jasjeet.lazysurface"
        compileSdk = 36
        minSdk = 24
    }

    jvm("desktop")

    iosArm64()
    iosSimulatorArm64()

    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            // Keep this list minimal: the Android AAR is consumed as a bare file (no
            // POM), so every dependency here must already exist on the consuming
            // project's classpath. On Android these org.jetbrains.compose coordinates
            // resolve to the same androidx.compose artifacts the consumer ships.
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.foundation)
            implementation(libs.cmp.animation)
            implementation(libs.cmp.ui)
            implementation(libs.androidx.collection)
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.junit)
            }
        }
    }
}
