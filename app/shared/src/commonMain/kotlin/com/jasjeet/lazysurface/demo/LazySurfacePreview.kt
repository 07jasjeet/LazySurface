package com.jasjeet.lazysurface.demo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jasjeet.lazysurface.LazySurface
import com.jasjeet.lazysurface.LazySurfaceDebug
import org.jetbrains.compose.ui.tooling.preview.Preview
import com.jasjeet.lazysurface.LazySurfaceNeighbor
import com.jasjeet.lazysurface.RubberBandOverscrollFactory
import com.jasjeet.lazysurface.items
import com.jasjeet.lazysurface.rememberLazySurfaceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/** Every key this preview uses, in one place. */
private object ShowKeys {
    const val Center = "center"
    fun spoke(direction: String, step: Int) = "$direction-$step"
    fun grid(row: Int, col: Int) = "g-$row-$col"
    const val RingIn = "ring-in"
    fun ring(index: Int) = "ring-$index"
    fun stairs(index: Int) = "stairs-$index"
    fun spiral(index: Int) = "spiral-$index"
}

/** A single showcase card: its relations carry the whole arrangement. */
private data class DemoNode(
    val key: String,
    val relations: List<LazySurfaceNeighbor>,
    val color: Color,
    val size: DpSize,
    val margin: Dp,
    val label: String = key,
)

/** One lattice step of a walk, converts into the relation "I moved [this way] from prev". */
private enum class Move { LeftWard, RightWard, UpWard, DownWard }

private fun Move.from(prev: Any): LazySurfaceNeighbor = when (this) {
    Move.RightWard -> LazySurfaceNeighbor.endOf(prev)
    Move.LeftWard -> LazySurfaceNeighbor.startOf(prev)
    Move.DownWard -> LazySurfaceNeighbor.below(prev)
    Move.UpWard -> LazySurfaceNeighbor.above(prev)
}

private fun Move.inverted(): Move = when (this) {
    Move.RightWard -> Move.LeftWard
    Move.LeftWard -> Move.RightWard
    Move.DownWard -> Move.UpWard
    Move.UpWard -> Move.DownWard
}

/**
 * Four quadrants around the pivot, each a different arrangement grown purely from
 * neighbour relations:
 *
 *          NW: closed ring          NE: 5x5 grid
 *                        \  N  /
 *                    W (PIVOT) E
 *                        /  S  \
 *          SW: staircase          SE: square spiral
 */
private fun buildShowcase(includeGrid: Boolean): List<DemoNode> {
    val nodes = mutableListOf<DemoNode>()

    // --- Spokes: two cards per compass direction, chaining out of the pivot card. ---
    val spokeSize = DpSize(96.dp, 56.dp)
    val spokes = listOf("n" to Move.UpWard, "e" to Move.RightWard, "s" to Move.DownWard, "w" to Move.LeftWard)
    spokes.forEach { (name, move) ->
        var prev: Any = ShowKeys.Center
        repeat(2) { step ->
            val key = ShowKeys.spoke(name, step + 1)
            nodes += DemoNode(
                key = key,
                relations = listOf(move.from(prev)),
                color = lerp(Color(0xFFB0BEC5), Color(0xFF455A64), step / 1f),
                size = spokeSize,
                margin = 16.dp,
                label = key.uppercase(),
            )
            prev = key
        }
    }

    // --- NE: a 5x5 grid growing up-and-right from above E-2. -----------------------
    // The CORRECT way to declare a lattice: every cell declares ALL of its adjacent
    // cells (the left one and the one below, the right/above directions come for free
    // since relations are bidirectional). Each cell is positioned by its first
    // declared relation, and the redundant ones make the grid robust: remove any cell
    // and the rest stay connected through the other adjacencies.
    if (includeGrid) {
        val gridSize = DpSize(56.dp, 56.dp)
        for (r in 0..4) {
            for (c in 0..4) {
                val relations = buildList {
                    if (c > 0) add(LazySurfaceNeighbor.endOf(ShowKeys.grid(r, c - 1)))
                    if (r > 0) add(LazySurfaceNeighbor.above(ShowKeys.grid(r - 1, c)))
                    if (r == 0 && c == 0) {
                        add(LazySurfaceNeighbor.above(ShowKeys.spoke("e", 2), LazySurfaceNeighbor.Alignment.Start))
                    }
                }
                nodes += DemoNode(
                    key = ShowKeys.grid(r, c),
                    relations = relations,
                    color = lerp(Color(0xFF90CAF9), Color(0xFF0D47A1), (r + c) / 8f),
                    size = gridSize,
                    margin = 6.dp,
                    label = "$r,$c",
                )
            }
        }
    }

    // --- NW: a closed ring (staircase diamond) up-left of N-2. ---------------------
    // A closed ring occupies every cell of its perimeter, so it must be entered from
    // outside it: a small connector west of N-2 anchors the ring's bottom vertex from
    // below. Uniform cards + margins make the walk land exactly back on ring-0, so the
    // closing relation on the last card is a consistent cycle, a link, not a fight.
    val ringSteps = 2
    val ringMoves = buildList {
        repeat(ringSteps) { add(Move.LeftWard); add(Move.UpWard) } // bottom -> left vertex
        repeat(ringSteps) { add(Move.UpWard); add(Move.RightWard) } // left -> top
        repeat(ringSteps) { add(Move.RightWard); add(Move.DownWard) } // top -> right
        repeat(ringSteps) { add(Move.DownWard); add(Move.LeftWard) } // right -> bottom
    }
    val ringSize = DpSize(48.dp, 48.dp)
    nodes += DemoNode(
        key = ShowKeys.RingIn,
        relations = listOf(Move.LeftWard.from(ShowKeys.spoke("n", 2))),
        color = Color(0xFF78909C),
        size = ringSize,
        margin = 6.dp,
        label = "•",
    )
    nodes += DemoNode(
        key = ShowKeys.ring(0),
        relations = listOf(Move.UpWard.from(ShowKeys.RingIn)),
        color = Color(0xFF1B5E20),
        size = ringSize,
        margin = 6.dp,
        label = "r0",
    )
    ringMoves.dropLast(1).forEachIndexed { index, move ->
        val key = ShowKeys.ring(index + 1)
        val relations = mutableListOf(move.from(ShowKeys.ring(index)))
        if (index == ringMoves.size - 2) {
            // The dropped final move would land exactly on ring-0: declare it.
            relations += LazySurfaceNeighbor.endOf(ShowKeys.ring(0))
        }
        if (index + 1 == 12) {
            // Experiment: a cross-quadrant relation tying r12 to the grid's 4,0.
            // With declaration-priority placement, r12 keeps anchoring to ring-11
            // (declared first) and the ring stays intact, this relation remains a
            // link only (routes + scroll shape), impossible to satisfy spatially.
            relations += LazySurfaceNeighbor.startOf(ShowKeys.grid(4, 0))
        }
        nodes += DemoNode(
            key = key,
            relations = relations,
            color = lerp(Color(0xFFA5D6A7), Color(0xFF1B5E20), (index + 1) / 15f),
            size = ringSize,
            margin = 6.dp,
            label = "r${index + 1}",
        )
    }

    // --- SW: a staircase descending left from below W-2. ---------------------------
    var stairsPrev: Any = ShowKeys.spoke("w", 2)
    repeat(12) { index ->
        val key = ShowKeys.stairs(index + 1)
        val move = if (index % 2 == 0) Move.DownWard else Move.LeftWard
        nodes += DemoNode(
            key = key,
            relations = listOf(move.from(stairsPrev)),
            color = lerp(Color(0xFFCE93D8), Color(0xFF4A148C), index / 11f),
            size = DpSize(56.dp, 56.dp),
            margin = 8.dp,
            label = "st${index + 1}",
        )
        stairsPrev = key
    }

    // --- SE: a square spiral below S-2, walked INWARD from its outer corner. -------
    // NOTE: Incorrect method of neighbour declaration. Each cell declares ONLY the
    // previous cell of the walk, a single fragile chain. Cells from different arms
    // of the spiral end up spatially adjacent WITHOUT any declared relation, so
    // nothing maintains their separation: resize one card and neighbouring arms
    // overlap or drift apart. Removing a cell splices the chain (tombstone healing),
    // but the healed neighbour keeps its own declared direction, so the spiral
    // rearranges rather than contracts cleanly. Contrast with the grid above, where
    // every cell declares all its adjacencies. Kept deliberately, as the
    // counter-example.
    // Walking inward keeps the whole block below-right of the entry card, so the
    // spiral never wraps back up into its anchor spoke. spiral-20 is the core.
    val spiralArms = listOf(
        Move.RightWard to 1, Move.DownWard to 1, Move.LeftWard to 2, Move.UpWard to 2,
        Move.RightWard to 3, Move.DownWard to 3, Move.LeftWard to 4, Move.UpWard to 4,
    )
    val spiralMoves = buildList {
        spiralArms.reversed().forEach { (direction, length) ->
            repeat(length) { add(direction.inverted()) }
        }
    }
    val spiralSize = DpSize(44.dp, 44.dp)
    nodes += DemoNode(
        key = ShowKeys.spiral(0),
        relations = listOf(Move.DownWard.from(ShowKeys.spoke("s", 2))),
        color = Color(0xFFFFCC80),
        size = spiralSize,
        margin = 6.dp,
        label = "sp0",
    )
    spiralMoves.forEachIndexed { index, move ->
        nodes += DemoNode(
            key = ShowKeys.spiral(index + 1),
            relations = listOf(move.from(ShowKeys.spiral(index))),
            color = lerp(Color(0xFFFFCC80), Color(0xFFE65100), (index + 1) / 20f),
            size = spiralSize,
            margin = 6.dp,
            label = if (index == spiralMoves.size - 1) "core" else "sp${index + 1}",
        )
    }

    return nodes
}

@Preview
@Composable
fun LazySurfacePreview(
    showRelationLines: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var includeGrid by remember { mutableStateOf(true) }
    // Keys removed by their cross buttons. Reset clears this.
    var removedKeys by remember { mutableStateOf(setOf<String>()) }
    // Per-item size multipliers, randomized on tap. Reset clears this.
    val sizeScale = remember { mutableStateMapOf<String, Float>() }
    val nodes = remember(includeGrid, removedKeys) {
        buildShowcase(includeGrid).filterNot { it.key in removedKeys }
    }
    val allKeys = remember(nodes) { listOf<Any>(ShowKeys.Center) + nodes.map { it.key } }
    val state = rememberLazySurfaceState()
    val scope = rememberCoroutineScope()

    // Chaos mode: a randomly picked item gets a new random size every 200 ms.
    // Visible victims reshuffle their neighbourhood live, off-screen ones stay
    // last-known until the viewport travels past them again.
    var chaos by remember { mutableStateOf(false) }
    LaunchedEffect(chaos, nodes) {
        while (chaos) {
            delay(200.milliseconds)
            nodes.randomOrNull()?.let { node ->
                sizeScale[node.key] = 0.5f + Random.nextFloat() * 1.5f
            }
        }
    }

    Box {
        // The environment decides the overscroll feel: here the iOS-style rubber band.
        CompositionLocalProvider(LocalOverscrollFactory provides RubberBandOverscrollFactory()) {
        LazySurface(
            modifier = Modifier.fillMaxSize(),
            state = state,
        ) {
            item(
                key = ShowKeys.Center,
                neighbors = { atPivot() },
            ) {
                Surface(
                    modifier = Modifier
                        .fillParentMaxWidth(0.4f)
                        .height(80.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface
                ) {
                    Text(
                        modifier = Modifier.fillMaxSize().wrapContentSize(),
                        text = "PIVOT",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            items(
                items = nodes,
                key = { it.key },
                neighbors = { node ->
                    // Margins belong to relations: each link states the full gap
                    // (both endpoints used to contribute node.margin each).
                    node.relations.forEach { neighbor(it, margin = node.margin * 2) }
                },
            ) { node ->
                // Tapping a tile randomizes its size, dependants shift the same pass
                // while visible, and off-screen relatives correct when travelled past.
                // The cross removes the item: relations that pointed at it heal to its
                // nearest surviving anchor, so a removed chain link closes the gap
                // instead of amputating everything declared behind it.
                // The scale is animated: every frame re-measures the item, so the
                // neighbourhood reflows continuously instead of jumping.
                val scale by animateFloatAsState(
                    targetValue = sizeScale[node.key] ?: 1f,
                    label = "sizeScale",
                )
                Box(
                    modifier = Modifier
                        // Additions fade in, graph changes (removals healing shut,
                        // neighbours resizing) glide instead of teleporting.
                        .animateItem()
                        // Before the background: the line stub under the card stays
                        // hidden, only the part spanning the gap shows.
                        .relationLines(state, node.key, showRelationLines)
                        .size(DpSize(node.size.width * scale, node.size.height * scale))
                        .background(node.color)
                        .clickable {
                            // A fresh random size on every tap, repeated taps keep resizing.
                            sizeScale[node.key] = 0.5f + Random.nextFloat() * 1.5f
                        }
                ) {
                    val onTile = if (node.color.luminance() > 0.55f) Color.Black else Color.White
                    Text(
                        node.label,
                        modifier = Modifier.align(Alignment.Center),
                        color = onTile,
                        fontSize = 10.sp,
                    )
                    Text(
                        "✕",
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .clickable { removedKeys = removedKeys + node.key }
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        color = onTile,
                        fontSize = 10.sp,
                    )
                }
            }
        }
        }

        Text(
            modifier = Modifier
                .padding(contentPadding)
                .align(Alignment.TopStart)
                .padding(16.dp),
            text = "zoom ${state.zoom.format2()}  offset (${state.offset.x.format0()}, " +
                "${state.offset.y.format0()})\nvisible ${state.visibleItemsInfo.size} / " +
                "${state.totalItemsCount}"
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(contentPadding)
                .padding(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            Row {
                Button(onClick = { scope.launch { state.animateToItem(allKeys.random()) } }) {
                    Text("Random")
                }
                Button(onClick = { scope.launch { state.animateToItem(ShowKeys.grid(4, 4)) } }) {
                    Text("Grid")
                }
                Button(onClick = { scope.launch { state.animateToItem(ShowKeys.ring(8)) } }) {
                    Text("Ring")
                }
            }
            Row {
                Button(onClick = { scope.launch { state.animateToItem(ShowKeys.spiral(20)) } }) {
                    Text("Spiral")
                }
                Button(onClick = { scope.launch { state.animateToItem(ShowKeys.stairs(12)) } }) {
                    Text("Stairs")
                }
                Button(onClick = { includeGrid = !includeGrid }) {
                    Text(if (includeGrid) "Del grid" else "Add grid")
                }
            }
            Row {
                Button(onClick = { chaos = !chaos }) {
                    Text(if (chaos) "Calm" else "Chaos")
                }
                Button(
                    onClick = {
                        chaos = false
                        removedKeys = emptySet()
                        sizeScale.clear()
                        includeGrid = true
                    }
                ) {
                    Text("Reset")
                }
                Button(onClick = { scope.launch { state.animateToItem(ShowKeys.Center) } }) {
                    Text("Pivot")
                }
            }
        }
    }
}
