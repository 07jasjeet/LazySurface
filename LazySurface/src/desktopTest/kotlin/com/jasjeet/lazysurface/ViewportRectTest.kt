package com.jasjeet.lazysurface

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportRectTest {

    private val viewport = IntSize(400, 800)

    @Test
    fun `an item at the viewport center surface point starts at the screen center`() {
        val rect = surfaceRectInViewport(
            rect = Rect(Offset(50f, 20f), Size(100f, 60f)),
            viewportCenterSurface = Offset(50f, 20f),
            zoom = 1f,
            viewportSize = viewport,
        )
        assertEquals(Rect(Offset(200f, 400f), Size(100f, 60f)), rect)
    }

    @Test
    fun `zoom scales both the distance from center and the on-screen size`() {
        val rect = surfaceRectInViewport(
            rect = Rect(Offset(100f, 100f), Size(100f, 50f)),
            viewportCenterSurface = Offset.Zero,
            zoom = 2f,
            viewportSize = viewport,
        )
        assertEquals(Rect(Offset(400f, 600f), Size(200f, 100f)), rect)
    }

    @Test
    fun `scrolling the viewport moves items the opposite way on screen`() {
        val surfaceRect = Rect(Offset.Zero, Size(10f, 10f))
        val before = surfaceRectInViewport(surfaceRect, Offset.Zero, 1f, viewport)
        val after = surfaceRectInViewport(surfaceRect, Offset(30f, -40f), 1f, viewport)
        assertEquals(before.topLeft - Offset(30f, -40f), after.topLeft)
    }
}
