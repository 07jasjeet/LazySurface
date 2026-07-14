// Demo UI shared across the sample apps, per the JetBrains default KMP structure
// (May 2026): this module is a pure library; platform entry points live in the
// sibling app modules (androidApp, desktopApp, webApp, iosApp).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)

    androidLibrary {
        namespace = "com.jasjeet.lazysurface.demo.shared"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")

    // The framework Xcode embeds (see iosApp). Linking needs the iOS SDK from a full
    // Xcode install, so the binaries are declared only when one can actually link:
    // when the build is driven by Xcode itself (its script phase exports SDK_NAME and
    // selects the Xcode via DEVELOPER_DIR), or when xcode-select points at a full
    // Xcode (`sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`).
    // Otherwise the iOS targets still compile to klibs and `gradlew build` stays green.
    val invokedByXcode = providers.environmentVariable("SDK_NAME").isPresent
    val xcodeSelected = providers.exec {
        commandLine("xcode-select", "-p")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().contains("Xcode.app")

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        if (invokedByXcode || xcodeSelected) {
            target.binaries.framework {
                baseName = "SampleShared"
                isStatic = true
            }
        }
    }

    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":LazySurface"))
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.foundation)
            implementation(libs.cmp.animation)
            implementation(libs.cmp.ui)
            implementation(libs.cmp.material3)
            implementation(libs.cmp.preview)
            implementation(libs.navigation3.runtime)
            implementation(libs.jb.navigation3.ui)
        }
        androidMain.dependencies {
            // Lets Android Studio render the commonMain @Preview composables.
            implementation(libs.cmp.ui.tooling)
        }
    }
}
