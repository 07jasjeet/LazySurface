package com.jasjeet.lazysurface.demo

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication

// Phone-ish portrait window, pan with mouse drag. Pinch zoom needs a touchscreen or
// trackpad gesture support, a plain mouse can only pan.
fun main() = singleWindowApplication(
    title = "LazySurface Demo",
    state = WindowState(size = DpSize(420.dp, 900.dp)),
) {
    DemoApp()
}
