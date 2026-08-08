package com.jasjeet.lazysurface.demo

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jasjeet.lazysurface.LazySurface
import com.jasjeet.lazysurface.LazySurfacePerformance
import com.jasjeet.lazysurface.rememberLazySurfaceState
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The solver's worst case, on purpose, a live perf harness rather than a pretty
 * arrangement. Three stressors, individually toggleable:
 *
 * - **The lattice itself**: a [GridSide]x[GridSide] grid where every cell declares
 *   ALL of its adjacencies (left and above), the robust-but-expensive declaration
 *   style: ~2 constraints per cell for the solver to check.
 * - **Pulse**: the center cell's size animates continuously, so every frame has
 *   different geometry, the solve memo misses on purpose and the full pipeline
 *   (resolve, solve, reflow) runs every frame. Freeze it and the memo takes over:
 *   solve time collapses to the replay cost.
 * - **Conflict**: one impossible relation (a cell claiming its right-hand relative
 *   sits on its left) that the lattice contradicts. The hard floors fight every
 *   sweep, the solver detects the stalemate once corrections stop shrinking and
 *   stops early (the plateau bail-out) instead of burning its full sweep budget on
 *   the same positions. The active set keeps the fight local: watch how little it
 *   adds while the pulse is frozen.
 *
 * The overlay shows rolling averages straight from [LazySurfacePerformance.monitor]:
 * Solve is nested inside Measure, so read them as "of X measure, Y was solving".
 */
private const val GridSide = 20

/**
 * Grid cell keys: typo-proof and readable in logs, no string mangling. Keys double
 * as Android saved-state identities, so from common code they must be a type
 * Bundle can store, kotlin.Pair is Serializable on the JVM, which qualifies.
 * (Android-only callers can use their own Parcelable/Serializable key types.)
 */
private typealias Cell = Pair<Int, Int>

private val CenterCell = Cell(10, 10)

/** The two cells the Conflict toggle ties together, marked red on the grid. */
private val ConflictA = Cell(10, 8)
private val ConflictB = Cell(10, 12)

@Preview
@Composable
fun WorstCasePreview(
    showRelationLines: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state = rememberLazySurfaceState()
    var pulsing by remember { mutableStateOf(true) }
    var conflict by remember { mutableStateOf(false) }

    // Rolling phase timings, exponentially smoothed. Written from the monitor (the
    // measure pass) and read by the overlay, a per-frame recomposition of the
    // overlay is part of what a real caller with a HUD costs.
    var solveText by remember { mutableStateOf("solve ;") }
    var resolveText by remember { mutableStateOf("resolve ;") }
    var measureText by remember { mutableStateOf("measure ;") }
    DisposableEffect(Unit) {
        val previous = LazySurfacePerformance.monitor
        var solveAvg = 0.0
        var resolveAvg = 0.0
        var measureAvg = 0.0
        LazySurfacePerformance.monitor = { phase, duration ->
            previous?.invoke(phase, duration)
            val micros = duration.inWholeMicroseconds.toDouble()
            when (phase) {
                LazySurfacePerformance.Phase.Solve -> {
                    solveAvg = solveAvg * 0.9 + micros * 0.1
                    solveText = "solve ${solveAvg.toInt()}us"
                }
                LazySurfacePerformance.Phase.Resolve -> {
                    resolveAvg = resolveAvg * 0.9 + micros * 0.1
                    resolveText = "resolve ${resolveAvg.toInt()}us"
                }
                LazySurfacePerformance.Phase.Measure -> {
                    measureAvg = measureAvg * 0.9 + micros * 0.1
                    measureText = "measure ${measureAvg.toInt()}us"
                }
            }
        }
        onDispose { LazySurfacePerformance.monitor = previous }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazySurface(
            state = state,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                items = List(GridSide * GridSide) { Cell(it / GridSide, it % GridSide) },
                neighbors = { cell ->
                    val (r, c) = cell
                    if (r == 0 && c == 0) atPivot()
                    if (c > 0) endOf(Cell(r, c - 1), margin = 12.dp)
                    if (r > 0) below(Cell(r - 1, c), margin = 12.dp)
                    // The impossible claim: this cell says it sits at the
                    // end of a cell that really lies four columns to its
                    // RIGHT. The lattice wins the joint placement, and the
                    // solver fights the floor forever.
                    if (conflict && cell == ConflictA) endOf(ConflictB)
                },
            ) { cell ->
                val (r, c) = cell
                val inConflict = conflict && (cell == ConflictA || cell == ConflictB)
                val tint =
                    if (inConflict) MaterialTheme.colorScheme.error
                    else lerp(Color(0xFF90CAF9), Color(0xFF0D47A1), (r + c) / (2f * GridSide))
                val scale = if (pulsing && cell == CenterCell) {
                    val transition = rememberInfiniteTransition(label = "pulse")
                    transition.animateFloat(
                        initialValue = 1f,
                        targetValue = 2.6f,
                        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
                        label = "centerScale",
                    ).value
                } else 1f
                Box(
                    modifier = Modifier
                        .relationLines(state, cell, showRelationLines)
                        .size(48.dp * scale)
                        .background(tint),
                ) {
                    Text(
                        "$r,$c",
                        modifier = Modifier.fillMaxSize().wrapContentSize(),
                        color = if (tint.luminance() > 0.55f) Color.Black else Color.White,
                        fontSize = 9.sp,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(contentPadding)
                .padding(12.dp),
        ) {
            Text(measureText, style = MaterialTheme.typography.labelLarge)
            Text(resolveText, style = MaterialTheme.typography.labelLarge)
            Text(solveText, style = MaterialTheme.typography.labelLarge)
            Row {
                Button(onClick = { pulsing = !pulsing }) {
                    Text(if (pulsing) "Freeze" else "Pulse")
                }
                Button(onClick = { conflict = !conflict }) {
                    Text(if (conflict) "Resolve" else "Conflict")
                }
            }
        }
    }
}
