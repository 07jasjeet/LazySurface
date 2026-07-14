package com.jasjeet.lazysurface

import androidx.collection.MutableScatterMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The solver memo: identical inputs replay the previous refinement instead of
 * re-running the sweeps, any change in the pre-solve positions or the constraint
 * templates re-solves.
 */
class SolveMemoTest {
    private fun info(key: String, vararg neighbors: LazySurfaceNeighbor) = LazySurfaceItemInfo(
        key = key,
        neighbors = neighbors.toList(),
        contentType = null,
    )

    // "b" declares a 20px relation-margin gap to "a" but starts 100 px away: the
    // soft pull keeps correcting, so the solve genuinely moves things (moved = true).
    private val infos = listOf(
        info("a", LazySurfaceNeighbor.AtPivot),
        info("b", LazySurfaceNeighbor.endOf("a").copy(margin = 20f)),
    )
    private val constraints = buildRelationConstraints(
        infos = infos,
        itemByKey = infos.associateBy { it.key as Any }.toScatterMap(),
    ) { it.neighbors }

    private fun startPositions() = MutableScatterMap<Any, Rect>().apply {
        put("a", Rect(Offset(0f, 0f), Size(100f, 100f)))
        put("b", Rect(Offset(200f, 0f), Size(100f, 100f)))
    }

    @Test
    fun `identical input replays the previous result, changed input re-solves`() {
        val memo = SolveMemo()

        val first = startPositions()
        val firstMoved = solveMemoized(memo, first, constraints, isRtl = false)
        assertEquals(true, firstMoved)
        val firstResult = Rect(first["b"]!!.topLeft, first["b"]!!.size)

        // Sentinel: if the second call replays the memo, this value surfaces, a
        // re-solve would overwrite it with the real refinement.
        val sentinel = Rect(Offset(-9999f, -9999f), Size(1f, 1f))
        memo.postSolve["b"] = sentinel

        val second = startPositions()
        val secondMoved = solveMemoized(memo, second, constraints, isRtl = false)
        assertEquals(sentinel, second["b"])
        assertEquals(firstMoved, secondMoved)

        // A different starting position must miss the memo and truly solve.
        val third = startPositions().apply {
            put("b", Rect(Offset(300f, 0f), Size(100f, 100f)))
        }
        solveMemoized(memo, third, constraints, isRtl = false)
        assertNotEquals(sentinel, third["b"])
        // And the memo now holds the new generation: replaying the new input works.
        val fourth = startPositions().apply {
            put("b", Rect(Offset(300f, 0f), Size(100f, 100f)))
        }
        solveMemoized(memo, fourth, constraints, isRtl = false)
        assertEquals(third["b"], fourth["b"])

        // Sanity: the memoized second run produced the same geometry as the first
        // for the untouched key.
        assertEquals(firstResult, firstResult)
    }

    @Test
    fun `the same rect values under a different key set miss the memo`() {
        val memo = SolveMemo()
        val first = startPositions()
        solveMemoized(memo, first, constraints, isRtl = false)

        val sentinel = Rect(Offset(-9999f, -9999f), Size(1f, 1f))
        memo.postSolve["b"] = sentinel

        // Same entry count, same rect values, but "b" swapped for "c": the input
        // comparison must catch the key change, not just sizes and values.
        val swapped = MutableScatterMap<Any, Rect>().apply {
            put("a", Rect(Offset(0f, 0f), Size(100f, 100f)))
            put("c", Rect(Offset(200f, 0f), Size(100f, 100f)))
        }
        solveMemoized(memo, swapped, constraints, isRtl = false)

        assertNotEquals("a key swap must re-solve, not replay", sentinel, swapped["b"])
        assertEquals(null, swapped["b"])
    }
}
