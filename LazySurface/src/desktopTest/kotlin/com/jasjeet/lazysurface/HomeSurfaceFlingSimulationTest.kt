package com.jasjeet.lazysurface

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Deterministic frame-by-frame replay of the Ultrahuman home-surface layout: an effort
 * card at the pivot, bio-intelligence east of it, notices west, science north and the
 * main feed south, every card ~viewport wide, separated by a 120dp section gap, with a
 * fixed contentPadding. Reproduces the reported phantom stop: a fast westward fling
 * from the bio card must sail past the effort card and reach the notices section.
 *
 * The simulation mirrors production's loop: each frame the decay proposes a value, the
 * clamp coerces it, the offset moves by the clamped delta, and only THEN does the
 * "measure pass" refresh [LazySurfaceState.visibleItemsInfo] from the new offset, the
 * same one-frame lag the real pipeline has.
 */
class HomeSurfaceFlingSimulationTest {

    private class HomeSurface(
        val viewport: IntSize = IntSize(1080, 2200),
        contentPaddingPx: ResolvedContentPadding? = ResolvedContentPadding.all(315f),
        zoom: Float = 1f,
        effortHeight: Float = 1400f,
        bioHeight: Float = 1000f,
        noticesHeight: Float = 1200f,
        scienceHeight: Float = 800f,
        mainFeedHeight: Float = 900f,
        startOffset: Offset,
    ) {
        val cardWidth = 945f
        val gap = 315f

        val effort = Rect(-cardWidth / 2, -effortHeight / 2, cardWidth / 2, effortHeight / 2)
        val bio = Rect(effort.right + gap, -bioHeight / 2, effort.right + gap + cardWidth, bioHeight / 2)
        val notices = Rect(effort.left - gap - cardWidth, -noticesHeight / 2, effort.left - gap, noticesHeight / 2)
        val science = Rect(-cardWidth / 2, effort.top - gap - scienceHeight, cardWidth / 2, effort.top - gap)
        val mainFeed = Rect(-cardWidth / 2, effort.bottom + gap, cardWidth / 2, effort.bottom + gap + mainFeedHeight)

        val rects: Map<Any, Rect> = mapOf(
            "effort" to effort, "bio" to bio, "notices" to notices,
            "science" to science, "mainFeed" to mainFeed,
        )

        val state = LazySurfaceState(initialOffset = startOffset)

        init {
            state.viewportSize = viewport
            state.zoom = zoom
            state.contentPaddingPx = contentPaddingPx

            fun info(key: String, neighbor: LazySurfaceNeighbor) =
                LazySurfaceItemInfo(key, listOf(neighbor), contentType = null)

            // Each satellite declares the section gap as its relation's margin.
            val infos = listOf(
                info("effort", LazySurfaceNeighbor.AtPivot),
                info("bio", LazySurfaceNeighbor.endOf("effort").copy(margin = gap)),
                info("notices", LazySurfaceNeighbor.startOf("effort").copy(margin = gap)),
                info("science", LazySurfaceNeighbor.above("effort").copy(margin = gap)),
                info("mainFeed", LazySurfaceNeighbor.below("effort").copy(margin = gap)),
            )
            state.updateItems(infos, infos.associateBy { it.key }.toScatterMap())
            state.lastKnownRects = rects.toScatterMap()
            // The bounding shape clamps to plain content rects.
            state.resolvedMarginBoxes = rects.toScatterMap()
            refreshVisible()
        }

        /** The measure pass's visibility rule: content rect overlaps the viewport rect. */
        fun refreshVisible() {
            val halfW = viewport.width / (2f * state.zoom)
            val halfH = viewport.height / (2f * state.zoom)
            val viewportRect = Rect(
                state.offset.x - halfW, state.offset.y - halfH,
                state.offset.x + halfW, state.offset.y + halfH,
            )
            state.visibleItemsInfo = rects
                .filterValues { it.overlaps(viewportRect) }
                .map { (key, rect) ->
                    LazySurfaceVisibleItemInfo(
                        key, null, rect,
                        viewportRect = surfaceRectInViewport(rect, state.offset, state.zoom, state.viewportSize),
                    )
                }
        }

        /**
         * Replays [LazySurface]'s fling: per frame the decay value advances, gets
         * clamped, the offset moves by the clamped delta, the pass refreshes
         * visibility, and a dominant-axis rejection stops everything.
         */
        fun fling(velocity: Offset, frameSeconds: Float = 1f / 60f): FlingResult {
            val dominantX = state.flingDominantAxisIsX(velocity)
                ?: return FlingResult(state.offset, stoppedByClamp = true, frames = 0)
            var v = velocity
            var frames = 0
            while (frames < 1000) {
                frames++
                // Incremental integration, like production: rejected travel is
                // spent against the wall, never banked for later.
                val target = state.offset + v * frameSeconds
                val clamped = state.coerceIntoResolvedShape(target)
                state.dispatchRawDelta(clamped - state.offset)
                val rejected = if (dominantX) clamped.x != target.x else clamped.y != target.y
                refreshVisible()
                if (rejected) return FlingResult(state.offset, stoppedByClamp = true, frames = frames)
                v *= 0.965f // splineBasedDecay-like frame friction
                if (abs(v.x) < 40f && abs(v.y) < 40f) break
            }
            return FlingResult(state.offset, stoppedByClamp = false, frames = frames)
        }
    }

    private class FlingResult(val offset: Offset, val stoppedByClamp: Boolean, val frames: Int)

    @Test
    fun `fast westward fling from bio reaches the notices section`() {
        val surface = HomeSurface(startOffset = Offset(1260f, 0f))

        val result = surface.fling(Offset(-8000f, 0f))

        assertTrue(
            "fling stopped at ${result.offset} after ${result.frames} frames " +
                "(clamped=${result.stoppedByClamp}); expected to pass the effort card " +
                "(left edge ${surface.effort.left}) into notices",
            result.offset.x < surface.effort.left,
        )
    }

    @Test
    fun `westward fling with vertical drift still reaches the notices section`() {
        val surface = HomeSurface(startOffset = Offset(1260f, 0f))

        val result = surface.fling(Offset(-8000f, -1500f))

        assertTrue(
            "fling stopped at ${result.offset} after ${result.frames} frames " +
                "(clamped=${result.stoppedByClamp}); expected to pass the effort card",
            result.offset.x < surface.effort.left,
        )
    }

    @Test
    fun `janky frames must not turn the crossing into a wall`() {
        val surface = HomeSurface(startOffset = Offset(1260f, 0f))

        // 10 fps: each frame leaps ~800px, the crossing happens in very few passes.
        val result = surface.fling(Offset(-8000f, 0f), frameSeconds = 1f / 10f)

        assertTrue(
            "fling stopped at ${result.offset} after ${result.frames} frames " +
                "(clamped=${result.stoppedByClamp}); expected to pass the effort card",
            result.offset.x < surface.effort.left,
        )
    }

    @Test
    fun `fling from an offset riding above the row still crosses`() {
        // The user's y rarely sits exactly on the row's midline after real dragging.
        val surface = HomeSurface(startOffset = Offset(1260f, -400f))

        val result = surface.fling(Offset(-8000f, 0f))

        assertTrue(
            "fling stopped at ${result.offset} after ${result.frames} frames " +
                "(clamped=${result.stoppedByClamp}); expected to pass the effort card",
            result.offset.x < surface.effort.left,
        )
    }

    @Test
    fun `fuzz - no geometry or timing combination walls the crossing`() {
        val failures = mutableListOf<String>()
        for (zoom in floatArrayOf(0.6f, 0.8f, 1f, 1.3f)) {
            for (padding in listOf(ResolvedContentPadding.all(262f), ResolvedContentPadding.all(315f), ResolvedContentPadding.all(472f), null)) {
                for (effortH in floatArrayOf(1000f, 1400f, 2000f, 2600f)) {
                    for (bioH in floatArrayOf(600f, 1000f, 1600f)) {
                        for (noticesH in floatArrayOf(300f, 1200f)) {
                            for (satelliteH in floatArrayOf(600f, 1400f)) {
                                for (startY in floatArrayOf(0f, -400f, 500f)) {
                                    for (vy in floatArrayOf(0f, -1200f, 1800f)) {
                                        for (dt in floatArrayOf(1f / 60f, 1f / 12f)) {
                                            val surface = HomeSurface(
                                                zoom = zoom,
                                                contentPaddingPx = padding,
                                                effortHeight = effortH,
                                                bioHeight = bioH,
                                                noticesHeight = noticesH,
                                                scienceHeight = satelliteH,
                                                mainFeedHeight = satelliteH,
                                                startOffset = Offset(1260f, startY),
                                            )
                                            val result = surface.fling(Offset(-8000f, vy), dt)
                                            if (result.offset.x >= surface.effort.left) {
                                                failures += "zoom=$zoom p=$padding effortH=$effortH " +
                                                    "bioH=$bioH noticesH=$noticesH satH=$satelliteH " +
                                                    "startY=$startY vy=$vy dt=$dt -> " +
                                                    "stopped at ${result.offset} " +
                                                    "clamped=${result.stoppedByClamp} frames=${result.frames}"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(
            "${failures.size} configurations walled the crossing:\n" +
                failures.take(20).joinToString("\n"),
            failures.isEmpty(),
        )
    }
}
