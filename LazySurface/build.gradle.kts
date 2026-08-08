// The org.jetbrains.compose Gradle plugin is deliberately NOT applied: its resources
// wiring is incompatible with AGP 9's KMP library plugin, and this module needs none of
// its features. The Compose Multiplatform dependencies are declared as plain coordinates.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.07jasjeet", "lazysurface", "0.1.0")

    pom {
        name.set("LazySurface")
        description.set(
            "A lazy 2D-plane layout for Compose Multiplatform: items declared by key " +
                "and spatial relations, not coordinates."
        )
        url.set("https://github.com/07jasjeet/LazySurface")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
            }
        }
        developers {
            developer {
                id.set("07jasjeet")
                name.set("Jasjeet Singh")
                url.set("https://github.com/07jasjeet")
            }
        }
        scm {
            url.set("https://github.com/07jasjeet/LazySurface")
            connection.set("scm:git:git://github.com/07jasjeet/LazySurface.git")
            developerConnection.set("scm:git:ssh://git@github.com/07jasjeet/LazySurface.git")
        }
    }
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
            // Compose types appear in the public API (Modifier, PaddingValues,
            // FlingBehavior, ...), so consumers compile against them: api scope.
            // On Android these org.jetbrains.compose coordinates resolve to the
            // same androidx.compose artifacts the consumer already ships.
            api(libs.cmp.runtime)
            api(libs.cmp.foundation)
            api(libs.cmp.animation)
            api(libs.cmp.ui)
            implementation(libs.androidx.collection)
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.junit)
                // Skia native binaries for headless-rendering tests: the compose
                // plugin normally wires these, but this module declares CMP as plain
                // coordinates (see the note at the top of this file).
                implementation("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:0.144.6")
            }
        }
    }
}
