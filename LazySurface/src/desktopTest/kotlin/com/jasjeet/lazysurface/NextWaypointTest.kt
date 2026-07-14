package com.jasjeet.lazysurface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Route planning over a small cross-shaped neighbour graph:
 *
 *                                                        satellite
 *                                                            |
 *                     up-2 (up-1) (pivot) (right-1) right-2, right-3
 *                                      |
 *                                   down-1
 *
 * (satellite hangs above right-3, declared by right-3, the relation is bidirectional.)
 *
 * Routes are pure graph traversal: they never consult resolved positions, because an
 * offscreen item's cached position is only what was true when it was last seen.
 */
class GraphRouteTest {
    private fun info(key: String, vararg neighbors: LazySurfaceNeighbor) = LazySurfaceItemInfo(
        key = key,
        neighbors = neighbors.toList(),
        contentType = null,
    )

    private fun buildState(): LazySurfaceState {
        val state = LazySurfaceState()
        val infos = listOf(
            info("center", LazySurfaceNeighbor.AtPivot),
            info("right-1", LazySurfaceNeighbor.endOf(LazySurfacePivot)),
            info("right-2", LazySurfaceNeighbor.endOf("right-1")),
            // right-3 also declares the reverse-direction relation for "satellite",
            // which itself declares nothing at all.
            info("right-3", LazySurfaceNeighbor.endOf("right-2"), LazySurfaceNeighbor.below("satellite")),
            info("satellite"),
            info("up-1", LazySurfaceNeighbor.above(LazySurfacePivot)),
            info("up-2", LazySurfaceNeighbor.above("up-1")),
            info("down-1", LazySurfaceNeighbor.below(LazySurfacePivot)),
        )
        state.updateItems(infos, infos.associateBy { it.key }.toScatterMap())
        return state
    }

    @Test
    fun `route climbs to the junction then descends the other side`() {
        val state = buildState()

        assertEquals(
            listOf("right-2", "right-1", LazySurfacePivot, "down-1"),
            state.routeTowards("right-3", "down-1"),
        )
    }

    @Test
    fun `route crosses the pivot only where the graph does`() {
        val state = buildState()

        assertEquals(
            listOf(LazySurfacePivot, "up-1", "up-2"),
            state.routeTowards("right-1", "up-2"),
        )
    }

    @Test
    fun `standing at the target routes to the target itself`() {
        val state = buildState()

        assertEquals(listOf<Any>("right-3"), state.routeTowards("right-3", "right-3"))
    }

    @Test
    fun `a relation declared by the other side still links the item into the graph`() {
        val state = buildState()

        assertEquals(listOf<Any>("satellite"), state.routeTowards("right-3", "satellite"))
    }

    @Test
    fun `unknown key has no route`() {
        val state = buildState()

        assertNull(state.routeTowards("center", "no-such-item"))
    }

    @Test
    fun `a route never visits a node twice`() {
        val state = buildState()

        // The longest route in the graph, crossing the junction: satellite -> up-2.
        val route = state.routeTowards("satellite", "up-2")!!

        assertEquals(
            listOf("right-3", "right-2", "right-1", LazySurfacePivot, "up-1", "up-2"),
            route,
        )
        assertEquals(route.size, route.toSet().size)
    }
}
