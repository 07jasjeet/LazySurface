package com.jasjeet.lazysurface

import androidx.collection.mutableScatterMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boundary behavior of the compiled solver and registration validation. */
class SolverEdgeCaseTest {
    private fun info(key: String, vararg neighbors: LazySurfaceNeighbor) = LazySurfaceItemInfo(
        key = key,
        neighbors = neighbors.toList(),
        contentType = null,
    )

    @Test
    fun `a constraint with an unresolved endpoint is inert`() {
        val infos = listOf(
            info("a", LazySurfaceNeighbor.AtPivot),
            info("b", LazySurfaceNeighbor.endOf("a")),
        )
        // Only "a" resolved this pass, the a-b constraint must not move anything.
        val resolved = mutableScatterMapOf<Any, Rect>(
            "a" to Rect(Offset(0f, 0f), Size(100f, 100f)),
        )
        val moved = CompiledConstraints(
            buildRelationConstraints(infos, infos.associateBy { it.key as Any }.toScatterMap()),
            isRtl = false,
        ).solve(resolved)

        assertFalse(moved)
        assertEquals(Rect(Offset(0f, 0f), Size(100f, 100f)), resolved["a"])
        assertEquals(1, resolved.size)
    }

    @Test
    fun `mutually declared relations agree instead of double-correcting`() {
        // The same fact declared from both ends: b at a's end, a at b's start.
        // Two compiled constraints, both zero at the exact geometry.
        val infos = listOf(
            info("a", LazySurfaceNeighbor.AtPivot, LazySurfaceNeighbor.startOf("b")),
            info("b", LazySurfaceNeighbor.endOf("a")),
        )
        val resolved = mutableScatterMapOf<Any, Rect>(
            "a" to Rect(Offset(0f, 0f), Size(100f, 100f)),
            "b" to Rect(Offset(100f, 0f), Size(100f, 100f)),
        )
        val moved = CompiledConstraints(
            buildRelationConstraints(infos, infos.associateBy { it.key as Any }.toScatterMap()),
            isRtl = false,
        ).solve(resolved)

        assertFalse("consistent duplicate declarations must not fight", moved)
        assertEquals(Rect(Offset(100f, 0f), Size(100f, 100f)), resolved["b"])
    }

    @Test
    fun `a pair related along both axes rests at the exact diagonal`() {
        // NE-style diagonal declaration: b at a's end AND on top of a. The two
        // separations fully define the geometry, the relations' cross-axis
        // alignments must not fight them.
        val infos = listOf(
            info("a", LazySurfaceNeighbor.AtPivot),
            info("b", LazySurfaceNeighbor.endOf("a"), LazySurfaceNeighbor.above("a")),
        )
        val resolved = mutableScatterMapOf<Any, Rect>(
            "a" to Rect(Offset(0f, 0f), Size(100f, 100f)),
            "b" to Rect(Offset(100f, -100f), Size(100f, 100f)),
        )
        val moved = CompiledConstraints(
            buildRelationConstraints(infos, infos.associateBy { it.key as Any }.toScatterMap()),
            isRtl = false,
        ).solve(resolved)

        assertFalse("a consistent diagonal must rest", moved)
        assertEquals(Rect(Offset(100f, -100f), Size(100f, 100f)), resolved["b"])
    }

    @Test
    fun `a collapsed diagonal pair converges to its corner`() {
        val infos = listOf(
            info("a", LazySurfaceNeighbor.AtPivot),
            info("b", LazySurfaceNeighbor.endOf("a"), LazySurfaceNeighbor.above("a")),
        )
        // b starts at the midpoint of its two relations' implied spots and must
        // be pushed out to where the two separations meet.
        val resolved = mutableScatterMapOf<Any, Rect>(
            "a" to Rect(Offset(0f, 0f), Size(100f, 100f)),
            "b" to Rect(Offset(50f, -50f), Size(100f, 100f)),
        )
        CompiledConstraints(
            buildRelationConstraints(infos, infos.associateBy { it.key as Any }.toScatterMap()),
            isRtl = false,
        ).solve(resolved)

        val a = resolved["a"]!!
        val b = resolved["b"]!!
        assertEquals("b sits at a's end", a.right, b.left, 1f)
        assertEquals("b sits on top of a", a.top, b.bottom, 1f)
    }

    @Test
    fun `a self relation is rejected at registration`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            LazySurfaceContent(Density(1f)) {
                item("hub", neighbors = { atPivot() }) {}
                item("loop", neighbors = { endOf("loop") }) {}
            }
        }
        assertTrue(error.message!!.contains("loop"))
    }

    @Test
    fun `a negative margin is rejected at registration`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            LazySurfaceContent(Density(1f)) {
                item("hub", neighbors = { atPivot() }) {}
                item("tucked", neighbors = { endOf("hub", margin = (-4).dp) }) {}
            }
        }
        assertTrue(error.message!!.contains("tucked"))
        assertTrue(error.message!!.contains("negative margin"))
    }
}
