package com.jasjeet.lazysurface

import androidx.collection.MutableScatterMap
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The global constraint pass over a 3x3 lattice: cells 100x100 with a 10px relation
 * margin on every adjacency, every cell declaring its start-side and above
 * adjacencies, rows grow downward, columns rightward:
 *
 *   0-0  0-1  0-2
 *   1-0  1-1  1-2
 *   2-0  2-1  2-2
 */
class ConstraintSolverTest {

    private val cell = 100f
    private val gap = 10f
    private val pitch = cell + gap

    private fun latticeInfos(): List<LazySurfaceItemInfo> = buildList {
        for (r in 0..2) {
            for (c in 0..2) {
                val relations = buildList {
                    if (c > 0) add(LazySurfaceNeighbor.endOf("$r-${c - 1}").copy(margin = gap))
                    if (r > 0) add(LazySurfaceNeighbor.below("${r - 1}-$c").copy(margin = gap))
                }
                add(LazySurfaceItemInfo("$r-$c", relations, contentType = null))
            }
        }
    }

    private fun consistentRects(): MutableScatterMap<Any, Rect> {
        val rects = MutableScatterMap<Any, Rect>()
        for (r in 0..2) {
            for (c in 0..2) {
                rects["$r-$c"] = Rect(c * pitch, r * pitch, c * pitch + cell, r * pitch + cell)
            }
        }
        return rects
    }

    private fun solve(rects: MutableScatterMap<Any, Rect>): Boolean {
        val infos = latticeInfos()
        return CompiledConstraints(
            buildRelationConstraints(infos, infos.associateBy { it.key as Any }.toScatterMap()),
            isRtl = false,
        ).solve(rects)
    }

    /** Every declared adjacency must keep at least the declared gap between rects. */
    private fun assertNoDeclaredOverlap(rects: MutableScatterMap<Any, Rect>) {
        for (r in 0..2) {
            for (c in 0..2) {
                val here = rects["$r-$c"]!!
                if (c > 0) {
                    val left = rects["$r-${c - 1}"]!!
                    assertTrue(
                        "cells $r-${c - 1} | $r-$c overlap: ${left.right} vs ${here.left}",
                        here.left >= left.right + gap - 0.5f,
                    )
                }
                if (r > 0) {
                    val above = rects["${r - 1}-$c"]!!
                    assertTrue(
                        "cells ${r - 1}-$c / $r-$c overlap: ${above.bottom} vs ${here.top}",
                        here.top >= above.bottom + gap - 0.5f,
                    )
                }
            }
        }
    }

    @Test
    fun `a consistent lattice converges on the first sweep with no movement`() {
        val rects = consistentRects()
        val before = buildMap { rects.forEach { k, v -> put(k, v) } }

        assertFalse(solve(rects))
        before.forEach { (key, rect) -> assertEquals(rect, rects[key]) }
    }

    @Test
    fun `an oversized center cell pushes its neighbourhood apart instead of overlapping`() {
        val rects = consistentRects()
        // The center cell grew 160x160 in place, before the solve it overlaps the
        // cells to its end side and below.
        rects["1-1"] = Rect(pitch, pitch, pitch + 160f, pitch + 160f)

        assertTrue(solve(rects))
        assertNoDeclaredOverlap(rects)
    }

    @Test
    fun `two orthogonal relations settle an item diagonally off the anchor's corner`() {
        // "a is above me" AND "a is before me": both separations must hold, so the
        // item belongs south-east of a's corner, one gap away on each axis.
        val a = LazySurfaceItemInfo("a", emptyList(), contentType = null)
        val x = LazySurfaceItemInfo(
            "x",
            listOf(
                LazySurfaceNeighbor.below("a").copy(margin = gap),
                LazySurfaceNeighbor.endOf("a").copy(margin = gap),
            ),
            contentType = null,
        )
        val infos = listOf(a, x)
        val size = androidx.compose.ui.geometry.Size(50f, 50f)
        val rects = MutableScatterMap<Any, Rect>()
        rects["a"] = Rect(0f, 0f, 100f, 100f)
        // The joint init the measure pass would produce: both exact spots, averaged.
        val below = placeItemRect(rects["a"]!!, LazySurfaceNeighbor.Side.Bottom,
            LazySurfaceNeighbor.Alignment.Center, size, isRtl = false, relationMargin = gap)
        val after = placeItemRect(rects["a"]!!, LazySurfaceNeighbor.Side.End,
            LazySurfaceNeighbor.Alignment.Center, size, isRtl = false, relationMargin = gap)
        rects["x"] = Rect(
            androidx.compose.ui.geometry.Offset((below.left + after.left) / 2, (below.top + after.top) / 2),
            size,
        )

        CompiledConstraints(
            buildRelationConstraints(infos, infos.associateBy { it.key as Any }.toScatterMap()),
            isRtl = false,
        ).solve(rects)

        val fa = rects["a"]!!
        val fx = rects["x"]!!
        assertTrue("overlaps vertically: ${fx.top} vs ${fa.bottom}", fx.top >= fa.bottom + gap - 0.5f)
        assertTrue("overlaps horizontally: ${fx.left} vs ${fa.right}", fx.left >= fa.right + gap - 0.5f)
        // And it sits AT the corner, not drifted far past it.
        assertEquals(fa.bottom + gap, fx.top, 3f)
        assertEquals(fa.right + gap, fx.left, 3f)
    }

    @Test
    fun `solving is deterministic`() {
        val first = consistentRects().also { it["1-1"] = Rect(pitch, pitch, pitch + 160f, pitch + 160f) }
        val second = consistentRects().also { it["1-1"] = Rect(pitch, pitch, pitch + 160f, pitch + 160f) }

        solve(first)
        solve(second)

        first.forEach { key, rect -> assertEquals(rect, second[key]) }
    }
}
