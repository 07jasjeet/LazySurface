package com.jasjeet.lazysurface

import androidx.compose.runtime.MonotonicFrameClock
import androidx.collection.mutableScatterMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A navigation target parked beyond the resolution region has no resolved rect, only
 * an [ProvisionalPositions] placement. The flight must aim at
 * that provisional position instead of giving up in place (the measure passes it
 * causes en route then correct the aim, here, with no measure loop, arriving at the
 * provisional point IS the destination).
 */
class ProvisionalWaypointTest {

    /** Deterministic 60fps clock so animate() runs without a real frame dispatcher. */
    private val frameClock = object : MonotonicFrameClock {
        private var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            time += 16_000_000L
            return onFrame(time)
        }
    }

    private fun info(key: String, vararg neighbors: LazySurfaceNeighbor) = LazySurfaceItemInfo(
        key = key,
        neighbors = neighbors.toList(),
        contentType = null,
    )

    @Test
    fun `animateToItem flies to an item known only provisionally`() {
        val state = LazySurfaceState()
        val infos = listOf(
            info("hub", LazySurfaceNeighbor.AtPivot),
            info("west", LazySurfaceNeighbor.startOf("hub")),
        )
        state.updateItems(infos, infos.associateBy { it.key as Any }.toScatterMap())
        state.viewportSize = IntSize(400, 800)
        state.lastKnownRects = mapOf<Any, Rect>(
            "hub" to Rect(Offset(-50f, -50f), Size(100f, 100f)),
        ).toScatterMap()
        // Far beyond the resolution region: never resolved, position only provisional.
        val provisional = Rect(Offset(-900f, 0f), Size.Zero)
        state.provisionalPositions.update(
            infos = emptyList(),
            graph = state.graph,
            itemByKey = state.itemByKey,
            resolved = mutableScatterMapOf(),
            awaitingMeasure = mapOf<Any, Rect>("west" to provisional).toScatterMap(),
            cachedSize = { null },
            isRtl = false,
        )

        runBlocking(frameClock) {
            state.animateToItem("west")
        }

        assertTrue(
            "expected flight to reach the provisional point, offset=${state.offset}",
            (state.offset - provisional.center).getDistance() < 1f,
        )
    }

    @Test
    fun `resolved positions still win over provisional ones`() {
        val state = LazySurfaceState()
        val infos = listOf(
            info("hub", LazySurfaceNeighbor.AtPivot),
            info("west", LazySurfaceNeighbor.startOf("hub")),
        )
        state.updateItems(infos, infos.associateBy { it.key as Any }.toScatterMap())
        state.viewportSize = IntSize(400, 800)
        val resolvedRect = Rect(Offset(-500f, -20f), Size(100f, 40f))
        state.lastKnownRects = mapOf<Any, Rect>(
            "hub" to Rect(Offset(-50f, -50f), Size(100f, 100f)),
            "west" to resolvedRect,
        ).toScatterMap()
        state.provisionalPositions.update(
            infos = emptyList(),
            graph = state.graph,
            itemByKey = state.itemByKey,
            resolved = mutableScatterMapOf(),
            awaitingMeasure = mapOf<Any, Rect>("west" to Rect(Offset(-900f, 0f), Size.Zero)).toScatterMap(),
            cachedSize = { null },
            isRtl = false,
        )

        runBlocking(frameClock) {
            state.animateToItem("west")
        }

        assertEquals(resolvedRect.center.x, state.offset.x, 1f)
        assertEquals(resolvedRect.center.y, state.offset.y, 1f)
    }
}
