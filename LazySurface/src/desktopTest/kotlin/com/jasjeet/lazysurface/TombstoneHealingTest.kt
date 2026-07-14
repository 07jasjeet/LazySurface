package com.jasjeet.lazysurface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Removing an item must splice its neighbours together instead of amputating the
 * subgraph behind it: relations that target a departed key heal to the departed
 * item's nearest surviving anchor, keeping the declared side and alignment.
 */
class TombstoneHealingTest {

    private fun info(key: String, vararg neighbors: LazySurfaceNeighbor) =
        LazySurfaceItemInfo(key, neighbors.toList(), contentType = null)

    private fun LazySurfaceState.update(vararg infos: LazySurfaceItemInfo) {
        val list = infos.toList()
        updateItems(list, list.associateBy { it.key as Any }.toScatterMap())
    }

    private fun LazySurfaceState.effectiveOf(key: String): List<LazySurfaceNeighbor> =
        effectiveNeighborsOf(itemsInfo.first { it.key == key })

    @Test
    fun `removing a chain link splices its neighbours, side preserved`() {
        val state = LazySurfaceState()
        val a = info("a", LazySurfaceNeighbor.AtPivot)
        val b = info("b", LazySurfaceNeighbor.endOf("a"))
        val c = info("c", LazySurfaceNeighbor.endOf("b", LazySurfaceNeighbor.Alignment.End))
        state.update(a, b, c)

        state.update(a, c) // b removed

        val healed = state.effectiveOf("c").single()
        assertEquals("a", healed.key)
        assertEquals(LazySurfaceNeighbor.Side.Start, healed.side)
        assertEquals(LazySurfaceNeighbor.Alignment.End, healed.alignment)
    }

    @Test
    fun `removing several links at once heals transitively`() {
        val state = LazySurfaceState()
        val a = info("a", LazySurfaceNeighbor.AtPivot)
        val b = info("b", LazySurfaceNeighbor.endOf("a"))
        val c = info("c", LazySurfaceNeighbor.endOf("b"))
        val d = info("d", LazySurfaceNeighbor.endOf("c"))
        state.update(a, b, c, d)

        state.update(a, d) // b AND c removed together

        assertEquals("a", state.effectiveOf("d").single().key)
    }

    @Test
    fun `a chase that reaches nothing alive ends at the pivot when declared`() {
        val state = LazySurfaceState()
        val a = info("a", LazySurfaceNeighbor.AtPivot)
        val b = info("b", LazySurfaceNeighbor.endOf("a"))
        state.update(a, b)

        state.update(b) // a removed, its only anchor is the pivot

        assertEquals(LazySurfacePivot, state.effectiveOf("b").single().key)
        assertEquals(LazySurfaceNeighbor.Side.Start, state.effectiveOf("b").single().side)
    }

    @Test
    fun `re-declaring a removed key restores the original topology`() {
        val state = LazySurfaceState()
        val a = info("a", LazySurfaceNeighbor.AtPivot)
        val b = info("b", LazySurfaceNeighbor.endOf("a"))
        val c = info("c", LazySurfaceNeighbor.endOf("b"))
        state.update(a, b, c)
        state.update(a, c) // b removed -> c healed to a

        state.update(a, b, c) // b returns

        assertEquals("b", state.effectiveOf("c").single().key)
    }

    @Test
    fun `a revived key reclaims its followers and its next death tombstones the new life`() {
        val state = LazySurfaceState()
        val x = info("x", LazySurfaceNeighbor.AtPivot)
        val y = info("y", LazySurfaceNeighbor.above("x"))
        val i = info("i", LazySurfaceNeighbor.endOf("x"))
        val j = info("j", LazySurfaceNeighbor.endOf("i"))
        state.update(x, y, i, j)

        // First death: j heals to i's first-life anchor.
        state.update(x, y, j)
        assertEquals("x", state.effectiveOf("j").single().key)

        // Revival at a different place: the heal was only a stand-in, the live key
        // wins its follower back, wherever it now lives.
        state.update(x, y, info("i", LazySurfaceNeighbor.startOf("y")), j)
        assertEquals("i", state.effectiveOf("j").single().key)

        // The second death heals through the second life's anchor, not the first's.
        state.update(x, y, j)
        val healed = state.effectiveOf("j").single()
        assertEquals("y", healed.key)
        assertEquals(LazySurfaceNeighbor.Side.Start, healed.side)
    }

    @Test
    fun `tombstone cycles drop the relation instead of looping`() {
        val state = LazySurfaceState()
        val root = info("root", LazySurfaceNeighbor.AtPivot)
        // x and y anchor only each other, removed together they form a dead cycle.
        val x = info("x", LazySurfaceNeighbor.endOf("y"))
        val y = info("y", LazySurfaceNeighbor.endOf("x"))
        val z = info("z", LazySurfaceNeighbor.endOf("y"))
        state.update(root, x, y, z)

        state.update(root, z) // x and y removed

        assertTrue(state.effectiveOf("z").isEmpty())
    }

    @Test
    fun `a splice that would point an item at itself is dropped`() {
        val state = LazySurfaceState()
        val a = info("a", LazySurfaceNeighbor.AtPivot)
        // b's only anchor is c, c declares b, removing b would splice c onto itself.
        val b = info("b", LazySurfaceNeighbor.endOf("c"))
        val c = info("c", LazySurfaceNeighbor.AtPivot, LazySurfaceNeighbor.startOf("b"))
        state.update(a, b, c)

        state.update(a, c) // b removed

        // c keeps its pivot relation, the healed-to-self one is gone.
        assertEquals(listOf<Any>(LazySurfacePivot), state.effectiveOf("c").map { it.key })
    }

    @Test
    fun `healed links join the routing graph`() {
        val state = LazySurfaceState()
        val a = info("a", LazySurfaceNeighbor.AtPivot)
        val b = info("b", LazySurfaceNeighbor.endOf("a"))
        val c = info("c", LazySurfaceNeighbor.endOf("b"))
        state.update(a, b, c)

        state.update(a, c) // b removed

        // Route a -> c crosses the healed link directly.
        assertNotNull(state.routeTowards("a", "c"))
        assertEquals(listOf<Any>("c"), state.routeTowards("a", "c"))
    }

    @Test
    fun `unreferenced tombstones are evicted`() {
        val state = LazySurfaceState()
        val a = info("a", LazySurfaceNeighbor.AtPivot)
        val b = info("b", LazySurfaceNeighbor.endOf("a"))
        val c = info("c", LazySurfaceNeighbor.endOf("b"))
        state.update(a, b, c)
        state.update(a, c) // b tombstoned, still referenced by c
        state.update(a) // c removed too: nothing references b's tombstone any more

        // A newcomer declaring the long-dead b finds no tombstone to heal through.
        state.update(a, info("d", LazySurfaceNeighbor.endOf("b")))
        assertTrue(state.effectiveOf("d").isEmpty())
    }

    @Test
    fun `healing keeps the relation's own margin`() {
        // Not a state test, documents the geometry: after b's removal, c's healed
        // relation to a keeps the margin c declared (the heal retargets the key,
        // side, alignment and margin alone), so c sits that gap from a directly.
        val a = androidx.compose.ui.geometry.Rect(0f, 0f, 100f, 50f)
        val healed = placeItemRect(
            anchorRect = a,
            side = LazySurfaceNeighbor.Side.End,
            alignment = LazySurfaceNeighbor.Alignment.Center,
            size = androidx.compose.ui.geometry.Size(40f, 20f),
            isRtl = false,
            relationMargin = 15f,
        )
        assertEquals(115f, healed.left)
    }
}
