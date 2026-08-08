package com.jasjeet.lazysurface

import androidx.collection.MutableObjectIntMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The solver memo: identical inputs replay the previous refinement instead of
 * re-running the sweeps, any change in the pre-solve geometry or the resolved
 * membership re-solves.
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

    private fun indexOf(vararg keys: String) = MutableObjectIntMap<Any>().apply {
        keys.forEachIndexed { i, key -> put(key, i) }
    }

    private fun ResolveScratch.resolve(index: Int, x: Float, y: Float) {
        left[index] = x
        top[index] = y
        width[index] = 100f
        height[index] = 100f
        markResolved(index)
    }

    @Test
    fun `identical input replays the previous result, changed input re-solves`() {
        val memo = SolveMemo()
        val itemIndexOf = indexOf("a", "b")
        val scratch = ResolveScratch(2)

        scratch.beginPass()
        scratch.resolve(0, 0f, 0f)
        scratch.resolve(1, 200f, 0f)
        val firstMoved = solveMemoized(memo, scratch, constraints, itemIndexOf, isRtl = false)
        assertEquals(true, firstMoved)

        // Sentinel: if the second call replays the memo, this value surfaces, a
        // re-solve would overwrite it with the real refinement.
        memo.postLeft[1] = -9999f
        memo.postTop[1] = -9999f

        scratch.beginPass()
        scratch.resolve(0, 0f, 0f)
        scratch.resolve(1, 200f, 0f)
        val secondMoved = solveMemoized(memo, scratch, constraints, itemIndexOf, isRtl = false)
        assertEquals(-9999f, scratch.left[1])
        assertEquals(firstMoved, secondMoved)

        // A different starting position must miss the memo and truly solve.
        scratch.beginPass()
        scratch.resolve(0, 0f, 0f)
        scratch.resolve(1, 300f, 0f)
        solveMemoized(memo, scratch, constraints, itemIndexOf, isRtl = false)
        assertNotEquals(-9999f, scratch.left[1])
        val thirdLeft = scratch.left[1]

        // And the memo now holds the new generation: replaying the new input works.
        scratch.beginPass()
        scratch.resolve(0, 0f, 0f)
        scratch.resolve(1, 300f, 0f)
        solveMemoized(memo, scratch, constraints, itemIndexOf, isRtl = false)
        assertEquals(thirdLeft, scratch.left[1])
    }

    @Test
    fun `the same geometry under a different resolved set misses the memo`() {
        val memo = SolveMemo()
        // A third, relation-less item shares the index space: swapping which index
        // resolved (same rect values) must read as a different input.
        val itemIndexOf = indexOf("a", "b", "c")
        val scratch = ResolveScratch(3)

        scratch.beginPass()
        scratch.resolve(0, 0f, 0f)
        scratch.resolve(1, 200f, 0f)
        solveMemoized(memo, scratch, constraints, itemIndexOf, isRtl = false)

        memo.postLeft[1] = -9999f
        memo.postTop[1] = -9999f

        // Same resolved count, same rect values, but "b" swapped for "c": the input
        // comparison must catch the membership change, not just sizes and values.
        scratch.beginPass()
        scratch.resolve(0, 0f, 0f)
        scratch.resolve(2, 200f, 0f)
        scratch.left[1] = 123f // stale, unresolved slot: a replay would clobber it
        solveMemoized(memo, scratch, constraints, itemIndexOf, isRtl = false)

        assertNotEquals("a membership swap must re-solve, not replay", -9999f, scratch.left[1])
        assertEquals(123f, scratch.left[1])
        assertEquals(false, memo.resolvedFlags[1])
        assertEquals(true, memo.resolvedFlags[2])
    }
}
