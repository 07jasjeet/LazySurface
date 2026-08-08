package com.jasjeet.lazysurface

import androidx.collection.mutableScatterMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plateau bail-out: a contradiction settles into a tug-of-war whose corrections
 * stop shrinking, and the solver stops paying for it instead of burning the full
 * sweep budget on the same positions.
 */
class SolverPlateauTest {
    private fun info(key: String, vararg neighbors: LazySurfaceNeighbor) = LazySurfaceItemInfo(
        key = key,
        neighbors = neighbors.toList(),
        contentType = null,
    )

    /** The declared gap of every relation in these graphs, in px. */
    private val gap = 10f

    /** a (b) c, d, left to right, each declares the previous on its start. */
    private fun chain(contradiction: Boolean): List<LazySurfaceItemInfo> = listOf(
        info(
            "a",
            LazySurfaceNeighbor.AtPivot,
            // The impossible claim: d really sits three cells to the RIGHT.
            *(if (contradiction) arrayOf(LazySurfaceNeighbor.endOf("d").copy(margin = gap)) else emptyArray()),
        ),
        info("b", LazySurfaceNeighbor.endOf("a").copy(margin = gap)),
        info("c", LazySurfaceNeighbor.endOf("b").copy(margin = gap)),
        info("d", LazySurfaceNeighbor.endOf("c").copy(margin = gap)),
    )

    /** Consistent chain geometry: 100 wide, 10px relation-margin gaps. */
    private fun chainRects() = mutableScatterMapOf<Any, Rect>(
        "a" to Rect(Offset(0f, 0f), Size(100f, 100f)),
        "b" to Rect(Offset(110f, 0f), Size(100f, 100f)),
        "c" to Rect(Offset(220f, 0f), Size(100f, 100f)),
        "d" to Rect(Offset(330f, 0f), Size(100f, 100f)),
    )

    private fun compiled(contradiction: Boolean): MapSolver {
        val infos = chain(contradiction)
        return MapSolver(
            buildRelationConstraints(infos, infos.associateBy { it.key as Any }.toScatterMap()),
            isRtl = false,
        )
    }

    @Test
    fun `a contradiction stops at the plateau instead of the full budget`() {
        val solver = compiled(contradiction = true)
        solver.solve(chainRects())
        // The full budget is 24 mixed + 16 hard sweeps, the stalemate must be
        // detected within a few sweeps per phase.
        assertTrue(
            "expected an early plateau exit, ran ${solver.lastSolveSweeps} sweeps",
            solver.lastSolveSweeps <= 16,
        )
    }

    @Test
    fun `a consistent graph converges on the first sweep of each phase`() {
        val solver = compiled(contradiction = false)
        val moved = solver.solve(chainRects())
        assertEquals(false, moved)
        assertEquals(2, solver.lastSolveSweeps)
    }

    /** A consistent chain whose cross-axis alignment starts as a staircase. */
    private fun staircase(length: Int): Pair<MapSolver, androidx.collection.MutableScatterMap<Any, Rect>> {
        val infos = (0 until length).map { i ->
            if (i == 0) info("i0", LazySurfaceNeighbor.AtPivot)
            else info("i$i", LazySurfaceNeighbor.endOf("i${i - 1}").copy(margin = gap))
        }
        val resolved = mutableScatterMapOf<Any, Rect>()
        for (i in 0 until length) {
            resolved["i$i"] = Rect(Offset(i * 110f, i * 20f), Size(100f, 100f))
        }
        val solver = MapSolver(
            buildRelationConstraints(infos, infos.associateBy { it.key as Any }.toScatterMap()),
            isRtl = false,
        )
        return solver to resolved
    }

    @Test
    fun `slow but genuine convergence runs to equilibrium`() {
        // Short chain: diffusion decays fast enough to fully converge in budget.
        // The plateau must not cut it, every adjacent pair ends aligned.
        val (solver, resolved) = staircase(4)
        solver.solve(resolved)

        for (i in 1 until 4) {
            val previous = resolved["i${i - 1}"]!!
            val current = resolved["i$i"]!!
            assertTrue(
                "pair $i still misaligned by ${current.top - previous.top}",
                kotlin.math.abs(current.top - previous.top) < 1f,
            )
        }
    }

    @Test
    fun `slow diffusion is not mistaken for a stalemate`() {
        // Longer chain: the slowest diffusion mode decays at ~0.95 per sweep,
        // slow, but genuinely shrinking. The plateau must not bail after a few
        // sweeps the way it does for a contradiction, the solver keeps working
        // deep into its budget.
        val (solver, resolved) = staircase(8)
        solver.solve(resolved)

        assertTrue(
            "expected a deep run, plateau cut at ${solver.lastSolveSweeps} sweeps",
            solver.lastSolveSweeps >= 15,
        )
    }
}
