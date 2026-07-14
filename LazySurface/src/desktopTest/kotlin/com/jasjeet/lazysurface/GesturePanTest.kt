package com.jasjeet.lazysurface

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gesture pan's consumption report must be exact in window-pixel space.
 * Converting window to surface and back (`available / zoom * zoom`) is lossy at
 * fractional zooms (a few float ULPs per frame) and overscroll effects treat any
 * unconsumed remainder as edge contact. Effects that latch into an overscroll mode
 * on first contact (the iOS platform effect) then swallow the rest of the drag as
 * band stretch mid-content: the surface freezes while the finger stretches an edge
 * that isn't there.
 */
class GesturePanTest {
    private fun info(key: String, neighbor: LazySurfaceNeighbor) = LazySurfaceItemInfo(
        key = key,
        neighbors = listOf(neighbor),
        contentType = null,
    )

    /** A wide horizontal arm around the origin, viewport 400x400. */
    private fun state(position: Offset, zoom: Float): LazySurfaceState {
        val state = LazySurfaceState(initialOffset = position, initialZoom = zoom)
        state.viewportSize = IntSize(400, 400)
        val rects = mapOf<Any, Rect>(
            "center" to Rect(-100f, -100f, 100f, 100f),
            "right-1" to Rect(100f, -100f, 300f, 100f),
            "right-2" to Rect(300f, -100f, 500f, 100f),
            "right-3" to Rect(500f, -100f, 700f, 100f),
        )
        val infos = listOf(
            info("center", LazySurfaceNeighbor.AtPivot),
            info("right-1", LazySurfaceNeighbor.endOf(LazySurfacePivot)),
            info("right-2", LazySurfaceNeighbor.endOf("right-1")),
            info("right-3", LazySurfaceNeighbor.endOf("right-2")),
        )
        state.updateItems(infos, infos.associateBy { it.key }.toScatterMap())
        state.lastKnownRects = rects.toScatterMap()
        state.resolvedMarginBoxes = rects.toScatterMap()
        state.visibleItemsInfo = rects.map { (key, rect) ->
            LazySurfaceVisibleItemInfo(
                key, null, rect,
                viewportRect = surfaceRectInViewport(rect, state.offset, state.zoom, state.viewportSize),
            )
        }
        return state
    }

    @Test
    fun `an unclamped pan is consumed exactly, at any fractional zoom`() {
        // The lossy round trip: 40 / 1.83 * 1.83 != 40 in floats. The report must
        // still be bit-exact, or every frame of a plain drag leaks a phantom
        // "unconsumed" remainder to the overscroll effect.
        for (zoom in listOf(1.83f, 0.7f, 1.37f, 2f)) {
            val state = state(Offset(0f, 0f), zoom)
            val available = Offset(-40f, 0f)
            val consumed = state.gesturePan(available, zoom)
            assertEquals("zoom $zoom leaked a residue", available, consumed)
            // And the pan actually moved the surface.
            assertTrue(state.offset.x > 0f)
        }
    }

    @Test
    fun `a clamped pan reports the genuine partial consumption`() {
        // Pinned against the arm's end: most of the pan must come back unconsumed
        // so the overscroll effect can stretch for real.
        val zoom = 1.83f
        val state = state(Offset(700f, 0f), zoom)
        val available = Offset(-40f, 0f)
        val consumed = state.gesturePan(available, zoom)
        assertNotEquals(available, consumed)
        assertTrue("expected near-zero consumption at the wall", kotlin.math.abs(consumed.x) < 1f)
    }
}
