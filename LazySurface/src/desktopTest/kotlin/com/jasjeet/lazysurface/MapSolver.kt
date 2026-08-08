package com.jasjeet.lazysurface

import androidx.collection.MutableObjectIntMap
import androidx.collection.MutableScatterMap
import androidx.compose.ui.geometry.Rect
import org.jetbrains.annotations.TestOnly

/**
 * Test-only map facade over [CompiledConstraints]. Production always compiles into
 * the measure pass's shared index space and solves its arrays in place, tests keep
 * the convenience of solving plain rect maps: this builds a key index over the
 * constraint endpoints, loads the rects into flat arrays, sweeps, and writes back
 * the endpoints that moved.
 */
@TestOnly
internal class MapSolver(source: List<RelationConstraint>, isRtl: Boolean) {
    private val keys = ArrayList<Any>()
    private val keyIndex = MutableObjectIntMap<Any>()

    init {
        fun register(key: Any) {
            if (keyIndex.getOrDefault(key, -1) < 0) {
                keyIndex[key] = keys.size
                keys.add(key)
            }
        }
        source.forEach { constraint ->
            register(constraint.itemKey)
            register(constraint.neighborKey)
        }
    }

    private val compiled = CompiledConstraints(source, isRtl, keyIndex, keys.size)
    private val x = FloatArray(keys.size)
    private val y = FloatArray(keys.size)
    private val w = FloatArray(keys.size)
    private val h = FloatArray(keys.size)
    private val loadedX = FloatArray(keys.size)
    private val loadedY = FloatArray(keys.size)
    private val present = IntArray(keys.size)

    val lastSolveSweeps: Int get() = compiled.lastSolveSweeps

    fun solve(resolved: MutableScatterMap<Any, Rect>): Boolean {
        for (j in keys.indices) {
            val rect = resolved[keys[j]]
            present[j] = if (rect != null) 1 else 0
            if (rect != null) {
                x[j] = rect.left
                y[j] = rect.top
                w[j] = rect.right - rect.left
                h[j] = rect.bottom - rect.top
                loadedX[j] = rect.left
                loadedY[j] = rect.top
            }
        }
        val moved = compiled.solveInto(x, y, w, h, present, stamp = 1)
        // The solver only moves positions, never sizes, so a changed coordinate is
        // an exact write-back gate: untouched entries keep their original rects.
        for (j in keys.indices) {
            if (present[j] == 1 && (x[j] != loadedX[j] || y[j] != loadedY[j])) {
                resolved[keys[j]] = Rect(x[j], y[j], x[j] + w[j], y[j] + h[j])
            }
        }
        return moved
    }
}
