package com.jasjeet.lazysurface

import androidx.collection.mutableScatterMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The positional verbs declare exactly the same relations as the neighbour-centric
 * builders (only the phrasing flips to the declaring item's own position) and the
 * infix refinements apply alignment and the relation's own margin.
 */
class DslVerbsTest {
    private fun neighbors(block: LazySurfaceNeighborsScope.() -> Unit): LazySurfaceNeighborsScope =
        LazySurfaceNeighborsScope(Density(1f)).apply(block)

    @Test
    fun `verbs record the neighbour on the opposite side of the phrasing`() {
        // "I sit above a" records a on my BOTTOM, the relation's side field is
        // always the side of the DECLARING item the neighbour occupies.
        val scope = neighbors {
            above("a")
            below("b")
            startOf("c")
            endOf("d")
        }
        assertEquals(
            listOf(
                LazySurfaceNeighbor("a", LazySurfaceNeighbor.Side.Bottom, LazySurfaceNeighbor.Alignment.Center),
                LazySurfaceNeighbor("b", LazySurfaceNeighbor.Side.Top, LazySurfaceNeighbor.Alignment.Center),
                LazySurfaceNeighbor("c", LazySurfaceNeighbor.Side.End, LazySurfaceNeighbor.Alignment.Center),
                LazySurfaceNeighbor("d", LazySurfaceNeighbor.Side.Start, LazySurfaceNeighbor.Alignment.Center),
            ),
            scope.buildNeighbors(),
        )
    }

    @Test
    fun `companion factories match the scope verbs`() {
        val scope = neighbors {
            above("a")
            below("b")
            startOf("c")
            endOf("d")
        }
        assertEquals(
            listOf(
                LazySurfaceNeighbor.above("a"),
                LazySurfaceNeighbor.below("b"),
                LazySurfaceNeighbor.startOf("c"),
                LazySurfaceNeighbor.endOf("d"),
            ),
            scope.buildNeighbors(),
        )
    }

    @Test
    fun `aligned refines the declared relation in place`() {
        val scope = neighbors {
            below("header") aligned LazySurfaceNeighbor.Alignment.Start
            endOf("side") aligned LazySurfaceNeighbor.Alignment.Free
        }
        assertEquals(
            listOf(
                LazySurfaceNeighbor.below("header", LazySurfaceNeighbor.Alignment.Start),
                LazySurfaceNeighbor.endOf("side", LazySurfaceNeighbor.Alignment.Free),
            ),
            scope.buildNeighbors(),
        )
    }

    @Test
    fun `margin belongs to the relation, by param or infix`() {
        val byParam = neighbors {
            below("header", margin = 14.dp)
            startOf("side", margin = 8.dp)
        }
        assertEquals(14f, byParam.buildNeighbors()[0].margin)
        assertEquals(8f, byParam.buildNeighbors()[1].margin)

        val byInfix = neighbors {
            above("footer") margin 10.dp
            endOf("rail") aligned LazySurfaceNeighbor.Alignment.End margin 6.dp
        }
        assertEquals(10f, byInfix.buildNeighbors()[0].margin)
        assertEquals(
            LazySurfaceNeighbor.endOf("rail", LazySurfaceNeighbor.Alignment.End).copy(margin = 6f),
            byInfix.buildNeighbors()[1],
        )
    }

    @Test
    fun `a relation margin does not leak into other relations on the same side`() {
        // The nested-cluster regression: "hub" holds a large margin toward "anchor"
        // below it, while "satellite" hugs hub from the same side. satellite's gap
        // must be its own relation's margin, not hub's.
        val infos = listOf(
            LazySurfaceItemInfo("anchor", listOf(LazySurfaceNeighbor.AtPivot), null),
            LazySurfaceItemInfo(
                "hub",
                listOf(LazySurfaceNeighbor.above("anchor").copy(margin = 300f)),
                null,
            ),
            LazySurfaceItemInfo(
                "satellite",
                listOf(LazySurfaceNeighbor.below("hub")),
                null,
            ),
        )
        val constraints = buildRelationConstraints(infos, infos.associateBy { it.key as Any }.toScatterMap())
        val hubToAnchor = constraints.first { it.itemKey == "hub" }
        val satelliteToHub = constraints.first { it.itemKey == "satellite" }
        assertEquals(300f, hubToAnchor.relationMargin)
        assertEquals(0f, satelliteToHub.relationMargin)

        // And the solver holds satellite flush against hub while hub keeps its 300.
        val resolved = mutableScatterMapOf<Any, Rect>(
            "anchor" to Rect(Offset(0f, 0f), Size(100f, 100f)),
            "hub" to Rect(Offset(0f, -400f), Size(100f, 100f)),
            "satellite" to Rect(Offset(0f, -300f), Size(100f, 100f)),
        )
        val moved = MapSolver(constraints, isRtl = false).solve(resolved)
        assertFalse("a flush satellite under a wide-margin hub is at rest", moved)
    }
}
