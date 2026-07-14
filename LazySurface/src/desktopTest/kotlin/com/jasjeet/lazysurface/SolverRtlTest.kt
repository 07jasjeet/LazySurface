package com.jasjeet.lazysurface

import androidx.collection.mutableScatterMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The compiled solver's direction resolution. Sides and cross-axis alignments
 * resolve against the layout direction at compile time, so every mapping here has
 * an RTL mirror that no other test exercises: the away sign of horizontal
 * relations, which absolute side the relation margin's gap opens on, and the
 * leading/trailing flip of vertical relations' cross-axis alignment.
 */
class SolverRtlTest {
    private fun info(key: String, vararg neighbors: LazySurfaceNeighbor) =
        LazySurfaceItemInfo(
            key = key,
            neighbors = neighbors.toList(),
            contentType = null,
        )

    private fun compiled(infos: List<LazySurfaceItemInfo>, isRtl: Boolean) = CompiledConstraints(
        buildRelationConstraints(infos, infos.associateBy { it.key as Any }.toScatterMap()),
        isRtl = isRtl,
    )

    // "b" declares a on its start with a 35px relation margin. Under RTL, b's start
    // is its RIGHT, so b sits to a's LEFT, the margin itself is direction-agnostic
    // (a gap is a gap), only the side it opens on mirrors.
    private val rtlHorizontalInfos = listOf(
        info("a", LazySurfaceNeighbor.AtPivot),
        info("b", LazySurfaceNeighbor.endOf("a").copy(margin = 35f)),
    )

    @Test
    fun `rtl flips the side and applies the relation margin on the mirrored side`() {
        // Exactly at the RTL equilibrium: b's right edge 35 short of a's left.
        val resolved = mutableScatterMapOf<Any, Rect>(
            "a" to Rect(Offset(0f, 0f), Size(100f, 100f)),
            "b" to Rect(Offset(-135f, 0f), Size(100f, 100f)),
        )
        val moved = compiled(rtlHorizontalInfos, isRtl = true).solve(resolved)

        assertFalse("already at the RTL equilibrium, nothing may move", moved)
        assertEquals(Rect(Offset(-135f, 0f), Size(100f, 100f)), resolved["b"])
    }

    @Test
    fun `rtl hard floor separates to the relation margin on the mirrored side`() {
        // b starts far too close on the wrong side of the gap, the floor must open
        // exactly the declared 35 px between the RTL-resolved facing edges.
        val resolved = mutableScatterMapOf<Any, Rect>(
            "a" to Rect(Offset(0f, 0f), Size(100f, 100f)),
            "b" to Rect(Offset(-120f, 0f), Size(100f, 100f)),
        )
        compiled(rtlHorizontalInfos, isRtl = true).solve(resolved)

        val a = resolved["a"]!!
        val b = resolved["b"]!!
        assertEquals(35f, a.left - b.right, 0.5f)
    }

    // "b" declares a on its top with Start alignment: the cross axis of a vertical
    // relation is horizontal, so Start means left edges in LTR and RIGHT edges
    // under RTL.
    private fun verticalInfos() = listOf(
        info("a", LazySurfaceNeighbor.AtPivot),
        info("b", LazySurfaceNeighbor.below("a", LazySurfaceNeighbor.Alignment.Start)),
    )

    private fun verticalRects(bLeft: Float) = mutableScatterMapOf<Any, Rect>(
        "a" to Rect(Offset(0f, 0f), Size(100f, 100f)),
        "b" to Rect(Offset(bLeft, 100f), Size(60f, 50f)),
    )

    @Test
    fun `vertical start alignment aligns left edges in ltr`() {
        val resolved = verticalRects(bLeft = 25f)
        compiled(verticalInfos(), isRtl = false).solve(resolved)

        val a = resolved["a"]!!
        val b = resolved["b"]!!
        assertEquals("left edges align in LTR", 0f, b.left - a.left, 0.5f)
    }

    @Test
    fun `vertical start alignment aligns right edges under rtl`() {
        val resolved = verticalRects(bLeft = 25f)
        compiled(verticalInfos(), isRtl = true).solve(resolved)

        val a = resolved["a"]!!
        val b = resolved["b"]!!
        assertEquals("right edges align under RTL", 0f, b.right - a.right, 0.5f)
    }
}
