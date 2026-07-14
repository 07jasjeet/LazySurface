// The org.jetbrains.compose plugin is safe here (no AGP in this module) and is what
// unpacks the Skiko wasm runtime into the webpack bundle.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "webApp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":app:shared"))
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.ui)
            implementation(libs.kotlinx.browser)
        }
    }
}
