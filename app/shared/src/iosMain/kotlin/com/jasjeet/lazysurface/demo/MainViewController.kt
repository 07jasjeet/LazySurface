package com.jasjeet.lazysurface.demo

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Entry point for the iOS app, iosApp wraps this in SwiftUI. */
fun MainViewController(): UIViewController = ComposeUIViewController { DemoApp() }
