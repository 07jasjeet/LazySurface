package com.jasjeet.lazysurface

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A fling pinned against a wall must not bank the travel the wall rejects.
 *
 * The scenario: the viewport sits on the TOP edge of a horizontal strip and gets
 * flung diagonally, y (the major component) into the wall, x along it. The glide
 * along the strip eventually brings a tall tower into the clamp's reach, lifting the
 * ceiling. The pinned y component must then follow at its current, decayed velocity,
 * not teleport upward by everything it "travelled" while pinned.
 *
 *   tower [-200..0] x [-800..100]     s1..s4: strip cells 200x200 tall at y [-100..100]
 *   viewport 400x400 at zoom 1, glide starts at (700, -100), pinned at the top.
 */
class WallGlideFlingTest {

    private val rects: Map<Any, Rect> = mapOf(
        "tower" to Rect(-200f, -800f, 0f, 100f),
        "s1" to Rect(0f, -100f, 200f, 100f),
        "s2" to Rect(200f, -100f, 400f, 100f),
        "s3" to Rect(400f, -100f, 600f, 100f),
        "s4" to Rect(600f, -100f, 800f, 100f),
    )

    private fun buildState(): LazySurfaceState {
        val state = LazySurfaceState(initialOffset = Offset(700f, -100f))
        state.viewportSize = IntSize(400, 400)

        fun info(key: String, neighbor: LazySurfaceNeighbor) =
            LazySurfaceItemInfo(key, listOf(neighbor), contentType = null)

        val infos = listOf(
            info("tower", LazySurfaceNeighbor.AtPivot),
            info("s1", LazySurfaceNeighbor.endOf("tower")),
            info("s2", LazySurfaceNeighbor.endOf("s1")),
            info("s3", LazySurfaceNeighbor.endOf("s2")),
            info("s4", LazySurfaceNeighbor.endOf("s3")),
        )
        state.updateItems(infos, infos.associateBy { it.key }.toScatterMap())
        state.lastKnownRects = rects.toScatterMap()
        state.resolvedMarginBoxes = rects.toScatterMap()
        refreshVisible(state)
        return state
    }

    private fun refreshVisible(state: LazySurfaceState) {
        val halfW = state.viewportSize.width / 2f
        val halfH = state.viewportSize.height / 2f
        val viewport = Rect(
            state.offset.x - halfW, state.offset.y - halfH,
            state.offset.x + halfW, state.offset.y + halfH,
        )
        state.visibleItemsInfo = rects
            .filterValues { it.overlaps(viewport) }
            .map { (key, rect) ->
                LazySurfaceVisibleItemInfo(
                    key, null, rect,
                    viewportRect = surfaceRectInViewport(rect, state.offset, state.zoom, state.viewportSize),
                )
            }
    }

    @Test
    fun `pinned axis follows a receding wall at its decayed velocity, never teleports`() {
        val state = buildState()
        val dt = 1f / 60f
        val friction = 0.965f

        // Mostly-vertical fling into the top wall, with a glide component along it.
        var v = Offset(-1200f, -2000f)
        val dominantX = state.flingDominantAxisIsX(v)
        // y is pinned at the wall, so the glide axis carries the fling.
        assertTrue("expected x dominance while pinned at the top", dominantX == true)

        var yAtLift = Float.NaN
        var vyAtLift = 0f
        var maxFrameRise = 0f
        var frames = 0
        while (frames++ < 1000) {
            val before = state.offset
            val target = before + v * dt
            val clamped = state.coerceIntoResolvedShape(target)
            state.dispatchRawDelta(clamped - before)
            refreshVisible(state)
            if (dominantX == true && clamped.x != target.x) break

            val rise = before.y - state.offset.y // positive = moved up
            if (rise > maxFrameRise) maxFrameRise = rise
            // Any single frame's upward travel may never exceed that frame's own
            // velocity step, banked phantom travel would burst out right here.
            assertTrue(
                "frame $frames teleported: rose ${rise}px against a ${abs(v.y) * dt}px step",
                rise <= abs(v.y) * dt + 0.01f,
            )
            if (yAtLift.isNaN() && state.offset.y < -100f) {
                yAtLift = before.y
                vyAtLift = v.y
            }
            v *= friction
            if (abs(v.x) < 40f && abs(v.y) < 40f) break
        }

        // The ceiling did lift and y followed it upward...
        assertTrue("the tower never lifted the ceiling", !yAtLift.isNaN())
        val totalRise = yAtLift - state.offset.y
        assertTrue("y never followed the lifted ceiling", totalRise > 5f)
        // ...but only as far as the remaining decay allows (geometric series bound),
        // a fraction of the ~800px of banked travel the old absolute integration
        // would have released at once.
        val remainingDecayBound = abs(vyAtLift) * dt / (1f - friction) + 1f
        assertTrue(
            "y rose ${totalRise}px, beyond its remaining decay budget of ${remainingDecayBound}px",
            totalRise <= remainingDecayBound,
        )
    }
}
