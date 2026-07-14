package com.jasjeet.lazysurface

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where does the surface snap back to when the content underneath the viewport gets
 * removed?
 *
 *      lure                (spatially nearest to the stranded viewport, graph-far)
 *        |
 *     right-1 (center) up-1 (up-2) up-3    (viewport rides up-2/up-3, ups deleted)
 */
class StrandedSnapHintTest {
    private fun info(key: String, vararg neighbors: LazySurfaceNeighbor) = LazySurfaceItemInfo(
        key = key,
        neighbors = neighbors.toList(),
        contentType = null,
    )

    private fun rectAt(center: Offset) = Rect(Offset(center.x - 50f, center.y - 50f), Size(100f, 100f))

    private val centers = mapOf(
        "center" to Offset(0f, 0f),
        "up-1" to Offset(0f, -200f),
        "up-2" to Offset(0f, -400f),
        "up-3" to Offset(0f, -600f),
        "right-1" to Offset(200f, 0f),
        "lure" to Offset(200f, -500f), // ~224px from the stranded spot, center is 600px
    )

    private val oldInfos = listOf(
        info("center", LazySurfaceNeighbor.AtPivot),
        info("up-1", LazySurfaceNeighbor.above("center")),
        info("up-2", LazySurfaceNeighbor.above("up-1")),
        info("up-3", LazySurfaceNeighbor.above("up-2")),
        info("right-1", LazySurfaceNeighbor.endOf("center")),
        info("lure", LazySurfaceNeighbor.above("right-1")),
    )

    private fun strandedState(): LazySurfaceState {
        val state = LazySurfaceState(initialOffset = Offset(0f, -600f)) // riding on up-3
        state.updateItems(oldInfos, oldInfos.associateBy { it.key }.toScatterMap())
        state.lastKnownRects = centers
            .mapValues { (_, center) -> rectAt(center) }
            .mapKeys { (key, _) -> key as Any }
            .toScatterMap()
        state.visibleItemsInfo = listOf("up-2", "up-3").map { key ->
            val rect = rectAt(centers.getValue(key))
            LazySurfaceVisibleItemInfo(
                key, null, rect,
                viewportRect = surfaceRectInViewport(rect, state.offset, state.zoom, state.viewportSize),
            )
        }
        return state
    }

    @Test
    fun `snaps to the graph-nearest survivor, not the spatially nearest one`() {
        val state = strandedState()

        // The whole up chain disappears underneath the viewport.
        val newInfos = oldInfos.filterNot { it.key.toString().startsWith("up-") }
        state.updateItems(newInfos, newInfos.associateBy { it.key }.toScatterMap())

        // "lure" is spatially much closer than center, but distance plays no part:
        // the walk back through the pre-deletion graph reaches center first
        // (up-3 → up-2 → up-1 → center), lure is two more hops beyond it.
        assertEquals("center", state.strandedSnapHint)
    }

    @Test
    fun `keeps following a displayed item that itself survives`() {
        val state = strandedState()

        // Only up-3 is removed, up-2 was displayed and survives, depth zero wins.
        val newInfos = oldInfos.filterNot { it.key == "up-3" }
        state.updateItems(newInfos, newInfos.associateBy { it.key }.toScatterMap())

        assertEquals("up-2", state.strandedSnapHint)
    }

    @Test
    fun `a change arriving while already stranded keeps the hint`() {
        val state = strandedState()

        // First change strands the viewport, the hint chases to center.
        val afterFirst = oldInfos.filterNot { it.key.toString().startsWith("up-") }
        state.updateItems(afterFirst, afterFirst.associateBy { it.key }.toScatterMap())
        assertEquals("center", state.strandedSnapHint)

        // While stranded nothing is visible, so the next change has no seeds to
        // walk from, it must keep the hint it has, not wipe it to null.
        state.visibleItemsInfo = emptyList()
        val afterSecond = afterFirst.filterNot { it.key == "lure" }
        state.updateItems(afterSecond, afterSecond.associateBy { it.key }.toScatterMap())

        assertEquals("center", state.strandedSnapHint)
    }

    @Test
    fun `no surviving relatives leaves the hint empty for the distance fallback`() {
        val state = strandedState()

        // Everything the viewport was connected to disappears, only the unrelated
        // "island" remains, registered fresh in the new content.
        val newInfos = listOf(info("island", LazySurfaceNeighbor.AtPivot))
        state.updateItems(newInfos, newInfos.associateBy { it.key }.toScatterMap())

        assertNull(state.strandedSnapHint)
    }
}
