package com.jasjeet.lazysurface

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [LazySurfaceSnapLayoutInfoProvider] through the androidx snapping contract: the
 * approach pass picks the anchor nearest the projected 2D landing and publishes its
 * key, the snap pass answers with the scalar distance the arc-length scope travels.
 */
class FlingBehaviorTest {

    private fun info(key: String, vararg neighbors: LazySurfaceNeighbor) = LazySurfaceItemInfo(
        key = key,
        neighbors = neighbors.toList(),
        contentType = null,
    )

    private fun state(): LazySurfaceState {
        val state = LazySurfaceState()
        val infos = listOf(
            info("hub", LazySurfaceNeighbor.AtPivot),
            info("east", LazySurfaceNeighbor.endOf("hub")),
            info("far-east", LazySurfaceNeighbor.endOf("east")),
            info("decoration", LazySurfaceNeighbor.below("hub")),
        )
        state.updateItems(infos, infos.associateBy { it.key as Any }.toScatterMap())
        state.viewportSize = androidx.compose.ui.unit.IntSize(400, 800)
        state.lastKnownRects = mapOf<Any, Rect>(
            "hub" to Rect(Offset(-100f, -70f), Size(200f, 140f)),
            "east" to Rect(Offset(800f, -70f), Size(200f, 140f)),
            "decoration" to Rect(Offset(-100f, -300f), Size(200f, 100f)),
        ).toScatterMap()
        return state
    }

    @Test
    fun `a still release re-centers the nearest anchor`() {
        val state = state()
        state.dispatchRawDelta(Offset(120f, 0f)) // drifted a little towards east
        state.snapFlingDirection = Offset.Zero

        val provider = LazySurfaceSnapLayoutInfoProvider(state)
        provider.calculateApproachOffset(velocity = 0f, decayOffset = 0f)

        assertEquals("hub", state.snapTargetKey)
        // The snap distance is the way back to hub's center.
        assertEquals(120f, provider.calculateSnapOffset(velocity = 0f), 1f)
    }

    @Test
    fun `a strong fling lands on the anchor near its projected landing`() {
        val state = state()
        state.snapFlingDirection = Offset(1f, 0f)

        val provider = LazySurfaceSnapLayoutInfoProvider(state)
        provider.calculateApproachOffset(velocity = 4000f, decayOffset = 900f)

        assertEquals("east", state.snapTargetKey)
        assertEquals(900f, provider.calculateSnapOffset(velocity = 4000f), 1f)
    }

    @Test
    fun `non-anchor items are never targets`() {
        val state = state()
        state.dispatchRawDelta(Offset(0f, -280f)) // right on top of "decoration"
        state.snapFlingDirection = Offset.Zero

        val provider = LazySurfaceSnapLayoutInfoProvider(state, anchors = { it.key != "decoration" })
        provider.calculateApproachOffset(velocity = 0f, decayOffset = 0f)

        assertEquals("hub", state.snapTargetKey)
    }

    @Test
    fun `unmeasured anchors are aimed at through their provisional position`() {
        val state = state()
        // far-east has never been measured, publish what a pass would derive.
        state.provisionalPositions.update(
            infos = state.itemsInfo,
            graph = state.graph,
            itemByKey = state.itemByKey,
            resolved = state.lastKnownRects,
            awaitingMeasure = androidx.collection.mutableScatterMapOf(),
            cachedSize = { null },
            isRtl = false,
        )
        state.snapFlingDirection = Offset(1f, 0f)

        val provider = LazySurfaceSnapLayoutInfoProvider(state)
        provider.calculateApproachOffset(velocity = 6000f, decayOffset = 1500f)

        assertEquals("far-east", state.snapTargetKey)
    }

    @Test
    fun `no positioned anchors means no snap`() {
        val state = LazySurfaceState()
        val infos = listOf(info("hub", LazySurfaceNeighbor.AtPivot))
        state.updateItems(infos, infos.associateBy { it.key as Any }.toScatterMap())

        val provider = LazySurfaceSnapLayoutInfoProvider(state)
        provider.calculateApproachOffset(velocity = 0f, decayOffset = 0f)

        assertNull(state.snapTargetKey)
        assertEquals(0f, provider.calculateSnapOffset(velocity = 0f), 0f)
    }
}
