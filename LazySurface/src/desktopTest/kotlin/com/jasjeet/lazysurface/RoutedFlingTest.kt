package com.jasjeet.lazysurface

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fling decay routed through an overscroll effect (the way LazySurface wires it
 * when one is installed): every frame's travel goes through the route, the lossy
 * window-conversion round trip must not read as a phantom wall mid-content, and a
 * genuine wall must still stop the fling and hand leftover velocity to the effect.
 */
class RoutedFlingTest {
    private fun info(key: String, neighbor: LazySurfaceNeighbor) = LazySurfaceItemInfo(
        key = key,
        neighbors = listOf(neighbor),
        contentType = null,
    )

    /** A horizontal arm ending at x=700, viewport 400x400. */
    private fun state(zoom: Float): LazySurfaceState {
        val state = LazySurfaceState(initialOffset = Offset.Zero, initialZoom = zoom)
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

    private val frameClock = object : MonotonicFrameClock {
        private var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            time += 16_000_000L
            return onFrame(time)
        }
    }

    @Test
    fun `a routed fling crosses content and hands leftover to the effect at the wall`() {
        val zoom = 1.83f
        val state = state(zoom)
        var routedFrames = 0

        val leftover = runBlocking(frameClock) {
            state.clampedDecayFling(
                initialVelocity = Offset(4000f, 0f),
                decaySpec = exponentialDecay(),
                routeDelta = { surfaceDelta, _ ->
                    routedFrames++
                    // The LazySurface wiring minus the effect itself: window-space
                    // conversion in, gesturePan applies and coerces.
                    state.gesturePan(Offset(-surfaceDelta.x * zoom, -surfaceDelta.y * zoom), zoom)
                },
            )
        }

        assertTrue("route saw no frames", routedFrames > 5)
        // The lossy round trip must not stop the fling mid-content: it reaches the
        // arm's end (center range tops out at x=700) before rejecting.
        assertTrue("stopped mid-content at ${state.offset.x}", state.offset.x > 650f)
        assertTrue("no leftover handed over at the wall", leftover.x > 0f)
    }

    @Test
    fun `the routing splits deltas by axis for the one-dimensional platform effect`() {
        // The iOS platform effect derives its direction from received deltas and
        // treats any two-component delta as UNKNOWN, collapsing all its spring
        // projections to zero. The wiring must hand it dominant-axis deltas only,
        // while the minor drift still travels (applied around the effect).
        val zoom = 1.83f
        val state = state(zoom)
        var mixedAxisDeltas = 0
        var routedTravelX = 0f

        runBlocking(frameClock) {
            state.clampedDecayFling(
                initialVelocity = Offset(3000f, 400f), // mostly-horizontal diagonal fling
                decaySpec = exponentialDecay(),
                routeDelta = { surfaceDelta, dominantX ->
                    // The LazySurface wiring, with a recording "effect".
                    val dominant = if (dominantX) Offset(surfaceDelta.x, 0f) else Offset(0f, surfaceDelta.y)
                    val minor = surfaceDelta - dominant
                    if (dominant.x != 0f && dominant.y != 0f) mixedAxisDeltas++
                    routedTravelX += dominant.x
                    state.gesturePan(Offset(-dominant.x * zoom, -dominant.y * zoom), zoom)
                    if (minor != Offset.Zero) {
                        state.gesturePan(Offset(-minor.x * zoom, -minor.y * zoom), zoom)
                    }
                },
            )
        }

        assertEquals("the effect must only ever see axis-pure deltas", 0, mixedAxisDeltas)
        assertTrue("dominant travel never routed", routedTravelX > 100f)
        // The minor drift still moved the surface even though it bypassed the effect.
        assertTrue("minor-axis drift was lost", state.offset.y > 5f)
    }

    @Test
    fun `a fling with no room still routes one contact frame`() {
        // Pinned at the wall, flinging into it: the decay never runs, but effects
        // that key their edge feedback off per-frame contact (the iOS platform
        // effect) must still learn of the wall, one frame's travel routes through
        // before the whole velocity returns as leftover.
        val zoom = 1.83f
        val state = state(zoom)
        // Walk to the wall first so there is genuinely no room to the east.
        state.gesturePan(Offset(-3000f, 0f), zoom)
        assertTrue(state.offset.x > 650f)

        var routedFrames = 0
        var routedDelta = Offset.Zero
        val leftover = runBlocking(frameClock) {
            state.clampedDecayFling(
                initialVelocity = Offset(4000f, 100f),
                decaySpec = exponentialDecay(),
                routeDelta = { surfaceDelta, _ ->
                    routedFrames++
                    routedDelta = surfaceDelta
                    state.gesturePan(Offset(-surfaceDelta.x * zoom, -surfaceDelta.y * zoom), zoom)
                },
            )
        }

        assertEquals("exactly the synthetic contact frame", 1, routedFrames)
        assertTrue("the contact frame carried the fling's direction", routedDelta.x > 0f)
        assertEquals("all velocity must be edge leftover", 4000f, leftover.x, 0.01f)
    }

    @Test
    fun `a routed fling that decays inside the shape leaves nothing over`() {
        val zoom = 1.83f
        val state = state(zoom)

        val leftover = runBlocking(frameClock) {
            state.clampedDecayFling(
                initialVelocity = Offset(300f, 0f), // decays to rest long before x=700
                decaySpec = exponentialDecay(),
                routeDelta = { surfaceDelta, _ ->
                    state.gesturePan(Offset(-surfaceDelta.x * zoom, -surfaceDelta.y * zoom), zoom)
                },
            )
        }

        assertEquals(Offset.Zero, leftover)
        assertTrue(state.offset.x in 10f..650f)
    }
}
