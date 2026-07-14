package com.jasjeet.lazysurface

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registration gate must be immune to snapshot isolation. A measure pass can
 * run inside a snapshot taken before the previous pass's writes applied, if the
 * gate read the snapshot-backed [LazySurfaceState.itemsInfo], such a pass would
 * see stale content, re-register identical infos, and replace the plain-world
 * engine structures (graph, constraint templates) every pass, defeating
 * [SolveMemo] and feeding an invalidation loop. This reproduces that exact
 * scenario and pins the plain-field gate.
 */
class RegistrationGateTest {
    private fun info(key: String, vararg neighbors: LazySurfaceNeighbor) = LazySurfaceItemInfo(
        key = key,
        neighbors = neighbors.toList(),
        contentType = null,
    )

    @Test
    fun `a pass inside a stale snapshot does not re-register identical content`() {
        val state = LazySurfaceState()
        val infos = listOf(
            info("a", LazySurfaceNeighbor.AtPivot),
            info("b", LazySurfaceNeighbor.endOf("a")),
        )
        val byKey = infos.associateBy { it.key as Any }.toScatterMap()

        // Taken BEFORE registration: inside it, snapshot-backed state still reads
        // the pre-registration world.
        val stale = Snapshot.takeMutableSnapshot()
        try {
            state.updateItems(infos, byKey)
            val constraints = state.relationConstraints
            val graph = state.graph

            stale.enter {
                // The staleness premise: this snapshot's view predates the write.
                assertTrue(
                    "the snapshot must see pre-registration itemsInfo for this test to mean anything",
                    state.itemsInfo.isEmpty(),
                )
                // The phantom pass: same infos instance, old snapshot view.
                state.updateItems(infos, byKey)
            }

            assertSame("a stale pass must not rebuild the constraint templates", constraints, state.relationConstraints)
            assertSame("a stale pass must not rebuild the graph", graph, state.graph)
        } finally {
            stale.dispose()
        }
    }
}
