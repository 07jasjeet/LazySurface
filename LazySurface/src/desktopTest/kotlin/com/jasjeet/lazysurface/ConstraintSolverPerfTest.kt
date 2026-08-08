package com.jasjeet.lazysurface

import androidx.collection.MutableScatterMap
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Order-of-magnitude timing for the constraint pass, printed to the test output. A
 * 20x20 lattice (400 cells, 760 relations) is far denser than any realistic surface,
 * the assertions only guard against pathological regressions.
 */
class ConstraintSolverPerfTest {

    private val cell = 100f
    private val gap = 10f
    private val pitch = cell + gap
    private val n = 20

    private fun latticeInfos(): List<LazySurfaceItemInfo> = buildList {
        for (r in 0 until n) {
            for (c in 0 until n) {
                val relations = buildList {
                    if (c > 0) add(LazySurfaceNeighbor.endOf("$r-${c - 1}").copy(margin = gap))
                    if (r > 0) add(LazySurfaceNeighbor.below("${r - 1}-$c").copy(margin = gap))
                }
                add(LazySurfaceItemInfo("$r-$c", relations, contentType = null))
            }
        }
    }

    private fun rects(oversizedCenter: Boolean): MutableScatterMap<Any, Rect> {
        val rects = MutableScatterMap<Any, Rect>()
        for (r in 0 until n) {
            for (c in 0 until n) {
                rects["$r-$c"] = Rect(c * pitch, r * pitch, c * pitch + cell, r * pitch + cell)
            }
        }
        if (oversizedCenter) {
            val m = n / 2
            rects["$m-$m"] = Rect(m * pitch, m * pitch, m * pitch + 260f, m * pitch + 260f)
        }
        return rects
    }

    @Test
    fun `solver timing at 400 cells`() {
        val infos = latticeInfos()
        val byKey = infos.associateBy { it.key as Any }.toScatterMap()

        // Compiled once and reused across solves, like production.
        val compiled = MapSolver(buildRelationConstraints(infos, byKey), isRtl = false)

        fun measure(oversized: Boolean, runs: Int): Double {
            // Fresh input per run, building it is excluded from the timing, like
            // production, where templates rebuild only on content changes.
            val inputs = List(runs) { rects(oversized) }
            var total = 0L
            inputs.forEach { input ->
                val start = System.nanoTime()
                compiled.solve(input)
                total += System.nanoTime() - start
            }
            return total / runs / 1e6
        }

        measure(oversized = false, runs = 20) // warm-up
        measure(oversized = true, runs = 20)
        val consistentMs = measure(oversized = false, runs = 100)
        val conflictedMs = measure(oversized = true, runs = 100)
        println("solver 400 cells / 760 relations: consistent=%.3fms conflicted=%.3fms".format(consistentMs, conflictedMs))

        assertTrue("consistent pass too slow: ${consistentMs}ms", consistentMs < 5.0)
        assertTrue("conflicted pass too slow: ${conflictedMs}ms", conflictedMs < 50.0)
    }
}
