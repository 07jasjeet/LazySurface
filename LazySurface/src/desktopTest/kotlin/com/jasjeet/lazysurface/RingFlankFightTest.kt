package com.jasjeet.lazysurface

import androidx.collection.MutableScatterMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Harness for solver-level ring-layout scenarios (the shape the demo's placeAround builds):
 * declare items and placeAround-style rings, every item initialised at its exact declared spot,
 * settle with the real compiled solver, then read two generic health metrics:
 *
 *  - [drift]: how far an item ended from its declared spot; any non-zero value means some
 *    relation fought the declared geometry.
 *  - [stretch]: how far a declared relation's measured gap ended from its declared margin;
 *    positive when pushed apart, negative when the floor was violated.
 *
 * Diagram legend, used by every scenario below:
 *
 * ```
 *   ───  hard relation: declared geometry (ray margins, alignment)
 *   ╌╌╌  Free relation: no-overlap floor only, no declared geometry
 * ```
 *
 * A ring built by [ring] looks like this (cardinals on the axes ray at `ringSpacing`, corners
 * tucked in on the diagonals at `diagonalSpacing`, both verbs — all of these are hard rays),
 * indices in fill order:
 *
 * ```
 *      (7)      (0)      (4)
 *       NW       N       NE
 *          ╲     │     ╱
 *  (3) W ──── center ──── E (1)
 *          ╱     │     ╲
 *       SW       S       SE
 *      (6)      (2)      (5)
 * ```
 *
 * New layout reports ("item X settles far from item Y") should become a scenario of a few lines:
 * build the clusters involved, [solve], then assert on drift/stretch of the items named.
 */
internal class RingScenario {
    private val relationsByKey = LinkedHashMap<Any, MutableList<LazySurfaceNeighbor>>()
    private val rects = MutableScatterMap<Any, Rect>()
    private val declared = HashMap<Any, Offset>()

    /** placeAround's ring-1 fill order: cardinals N, E, S, W, then corners NE, SE, SW, NW. */
    private val slotOrder = intArrayOf(0, 2, 4, 6, 1, 3, 5, 7)

    fun item(key: Any, size: Float, cx: Float, cy: Float, relations: List<LazySurfaceNeighbor> = emptyList()) {
        relationsByKey.getOrPut(key) { mutableListOf() } += relations
        rects[key] = Rect(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        declared[key] = Offset(cx, cy)
    }

    /** Adds relations to an already-declared item (guards, flanks, whatever the scenario needs). */
    fun relate(key: Any, vararg relations: LazySurfaceNeighbor) {
        relationsByKey.getOrPut(key) { mutableListOf() } += relations
    }

    /**
     * A placeAround-style ring 1 around an already-added [center]: up to eight [size] items filling
     * [slotOrder] — cardinals raying at [ringSpacing], corners at [diagonalSpacing] with both verbs
     * — each registered with its ray relations and placed at its exact declared spot. Returns the
     * keys in fill order (index 0 = N, 1 = E, 2 = S, 3 = W, 4 = NE, 5 = SE, 6 = SW, 7 = NW).
     */
    fun ring(
        center: Any,
        size: Float,
        count: Int,
        ringSpacing: Float,
        diagonalSpacing: Float = ringSpacing / 4f,
        key: (Int) -> Any = { "$center/p$it" },
    ): List<Any> {
        require(count <= 8) { "the harness models ring 1 only" }
        val centerRect = requireNotNull(rects[center]) { "add $center before ringing it" }
        val c = centerRect.center
        val ray = centerRect.width / 2f + ringSpacing + size / 2f
        val diagonal = centerRect.width / 2f + diagonalSpacing + size / 2f
        return (0 until count).map { index ->
            val k = key(index)
            when (slotOrder[index]) {
                0 -> item(k, size, c.x, c.y - ray, listOf(LazySurfaceNeighbor.above(center).copy(margin = ringSpacing)))
                2 -> item(k, size, c.x + ray, c.y, listOf(LazySurfaceNeighbor.endOf(center).copy(margin = ringSpacing)))
                4 -> item(k, size, c.x, c.y + ray, listOf(LazySurfaceNeighbor.below(center).copy(margin = ringSpacing)))
                6 -> item(k, size, c.x - ray, c.y, listOf(LazySurfaceNeighbor.startOf(center).copy(margin = ringSpacing)))
                1 -> item(k, size, c.x + diagonal, c.y - diagonal, listOf(
                    LazySurfaceNeighbor.endOf(center).copy(margin = diagonalSpacing),
                    LazySurfaceNeighbor.above(center).copy(margin = diagonalSpacing),
                ))
                3 -> item(k, size, c.x + diagonal, c.y + diagonal, listOf(
                    LazySurfaceNeighbor.endOf(center).copy(margin = diagonalSpacing),
                    LazySurfaceNeighbor.below(center).copy(margin = diagonalSpacing),
                ))
                5 -> item(k, size, c.x - diagonal, c.y + diagonal, listOf(
                    LazySurfaceNeighbor.startOf(center).copy(margin = diagonalSpacing),
                    LazySurfaceNeighbor.below(center).copy(margin = diagonalSpacing),
                ))
                else -> item(k, size, c.x - diagonal, c.y - diagonal, listOf(
                    LazySurfaceNeighbor.startOf(center).copy(margin = diagonalSpacing),
                    LazySurfaceNeighbor.above(center).copy(margin = diagonalSpacing),
                ))
            }
            k
        }
    }

    /** Free no-overlap floors from every [from] item toward every [to] item, along [verbs]. */
    fun guard(
        from: Iterable<Any>,
        to: Iterable<Any>,
        verbs: (target: Any) -> List<LazySurfaceNeighbor>,
    ) {
        from.forEach { f -> to.forEach { t -> relate(f, *verbs(t).toTypedArray()) } }
    }

    /** Settles the scenario with the real compiled solver, re-solving passes like measure does. */
    fun solve(passes: Int = 60): RingScenario {
        val infos = relationsByKey.map { (key, relations) ->
            LazySurfaceItemInfo(key, relations, contentType = null)
        }
        val byKey = MutableScatterMap<Any, LazySurfaceItemInfo>(infos.size)
        infos.forEach { byKey[it.key] = it }
        val compiled = CompiledConstraints(buildRelationConstraints(infos, byKey), isRtl = false)
        for (pass in 0 until passes) {
            if (!compiled.solve(rects)) break
        }
        return this
    }

    /** How far [key] settled from its declared spot. */
    fun drift(key: Any): Float {
        val now = requireNotNull(rects[key]).center
        val spot = requireNotNull(declared[key])
        return hypot(now.x - spot.x, now.y - spot.y)
    }

    /**
     * Measured gap of the declared non-Free relation [key] → [neighbor] minus its declared margin:
     * 0 = held exactly, positive = pushed apart, negative = floor violated. Corner relations have
     * two axes; [LazySurfaceNeighbor.Side.Start]/[LazySurfaceNeighbor.Side.End] measure
     * horizontally, [LazySurfaceNeighbor.Side.Top]/[LazySurfaceNeighbor.Side.Bottom] vertically.
     */
    fun stretch(key: Any, neighbor: Any, side: LazySurfaceNeighbor.Side? = null): Float {
        val relation = requireNotNull(relationsByKey[key]).first {
            it.key == neighbor && it.alignment != LazySurfaceNeighbor.Alignment.Free &&
                (side == null || it.side == side)
        }
        val a = requireNotNull(rects[key])
        val b = requireNotNull(rects[neighbor])
        val horizontal = relation.side == LazySurfaceNeighbor.Side.Start || relation.side == LazySurfaceNeighbor.Side.End
        val gap =
            if (horizontal) max(b.left - a.right, a.left - b.right)
            else max(b.top - a.bottom, a.top - b.bottom)
        return gap - relation.margin
    }
}

/**
 * Cardinal-corner flank relations must be Free, not hard geometry. A corner sits tucked in on the
 * diagonal, far off the cardinal's row, so a hard 0-margin flank (pull-to-touch plus centre
 * cross-alignment) contradicts the corner's own ray and the tug never resolves:
 *
 * ```
 *        (N)┄┄┄┐  flank under test, N↔NE: hard ─── pulls them touching
 *         │    ┆  and row-aligned; Free ╌╌╌ only forbids overlap…
 *         │   (NE)
 *         │   ╱   …while NE's solid diagonal ray holds it down here,
 *         │  ╱    off N's row (│ and ╱ are hard rays to the center)
 *       center
 *         │
 *        (p)      the S tile's own sub-ring petal, between S and the
 *        (S)      center: exactly where the pumped drift shows up
 * ```
 *
 * Every pass re-fights the tug: the cardinal is dragged, its ray floor resists, and the petal
 * between it and the center takes hard pushes its soft ray pull can't return — settling far off
 * its declared gap. With Free flanks (floors only, no declared geometry) everything holds exactly.
 */
class RingFlankFightTest {

    private fun scenario(hardFlanks: Boolean): RingScenario = RingScenario().apply {
        fun flank(neighbor: LazySurfaceNeighbor): LazySurfaceNeighbor =
            if (hardFlanks) neighbor.copy(margin = 0f)
            else neighbor.copy(alignment = LazySurfaceNeighbor.Alignment.Free)

        item("hub", 300f, 0f, 0f)
        val tiles = ring("hub", 170f, 8, ringSpacing = 850f, key = { it })
        // Flanks around each corner, declared by the cardinals (indices 0..3 = N, E, S, W;
        // 4..7 = NE, SE, SW, NW), the way placeAround declares them.
        relate(tiles[0], flank(LazySurfaceNeighbor.endOf(tiles[7])), flank(LazySurfaceNeighbor.startOf(tiles[4])))
        relate(tiles[1], flank(LazySurfaceNeighbor.below(tiles[4])), flank(LazySurfaceNeighbor.above(tiles[5])))
        relate(tiles[2], flank(LazySurfaceNeighbor.startOf(tiles[5])), flank(LazySurfaceNeighbor.endOf(tiles[6])))
        relate(tiles[3], flank(LazySurfaceNeighbor.above(tiles[6])), flank(LazySurfaceNeighbor.below(tiles[7])))
        // The south tile's own sub-ring of four petals; "m0" is its top petal.
        ring(tiles[2], 150f, 4, ringSpacing = 37.5f, key = { "m$it" })
        solve()
    }

    @Test
    fun `hard zero-margin flanks against diagonal rays pump the contested side, Free flanks do not`() {
        val hard = scenario(hardFlanks = true)
        val free = scenario(hardFlanks = false)
        println("hard flanks: south tile drift=${hard.drift(2)}, top petal stretch=${hard.stretch("m0", 2)}")
        println("free flanks: south tile drift=${free.drift(2)}, top petal stretch=${free.stretch("m0", 2)}")

        assertTrue("free-flank ring should not drift (was ${free.drift(2)})", free.drift(2) < 5f)
        assertTrue("free-flank petal gap should hold (was ${free.stretch("m0", 2)})", abs(free.stretch("m0", 2)) < 5f)
    }
}

/**
 * Two clusters sharing a diagonal ray need enough diagonal spacing for BOTH sub-rings; the outer
 * cluster's inward-facing petals are the first to collide:
 *
 * ```
 *                     b b b
 *                    b  B  b      B's solid ─── diagonal ray anchors it
 *                   [b] b b       on A (the same way A anchors on the hub)
 *          a a a  ╱  ┆
 *         a  A  a    ┆╌╌ every B item carries a Free floor toward every
 *          a a a         A item: no-overlap only, no declared geometry
 *   hub ╱
 *                   [b] = B's inward petals (W, SW): at a quarter-margin
 *                   diagonal their rects land inside A's petal ring
 * ```
 *
 * The clusters carry mutual Free no-overlap floors, so instead of overlapping, the floors fire
 * permanently and push the facing petals off their declared ray gaps — the "petal settles far
 * from its own centre" symptom. Widening the diagonal until both sub-rings' rects clear (0.4 of
 * the ring spacing here) removes the contact and every gap holds exactly.
 */
class DiagonalWhorlCrowdingTest {

    private fun scenario(diagonalSpacing: Float): RingScenario = RingScenario().apply {
        val free = LazySurfaceNeighbor.Alignment.Free
        item("hub", 300f, 0f, 0f)

        val innerRay = 150f + diagonalSpacing + 85f
        item("A", 170f, innerRay, -innerRay, listOf(
            LazySurfaceNeighbor.endOf("hub").copy(margin = diagonalSpacing),
            LazySurfaceNeighbor.above("hub").copy(margin = diagonalSpacing),
        ))
        val innerPetals = ring("A", 150f, 7, ringSpacing = 37.5f, key = { "a$it" })

        val outerRay = innerRay + diagonalSpacing + 170f
        item("B", 170f, outerRay, -outerRay, listOf(
            LazySurfaceNeighbor.endOf("A").copy(margin = diagonalSpacing),
            LazySurfaceNeighbor.above("A").copy(margin = diagonalSpacing),
        ))
        val outerPetals = ring("B", 150f, 8, ringSpacing = 37.5f, key = { "b$it" })

        // Mutual keep-out: the outer cluster's items carry Free floors toward the inner cluster,
        // along the pair's direction (B sits up-right of A).
        guard(from = outerPetals + listOf("B"), to = innerPetals + listOf("A")) { target ->
            listOf(LazySurfaceNeighbor.endOf(target, free), LazySurfaceNeighbor.above(target, free))
        }
        solve()
    }

    @Test
    fun `quarter-margin diagonal crowds facing petals, a wider diagonal clears them`() {
        val tight = scenario(diagonalSpacing = 212.5f)
        val wide = scenario(diagonalSpacing = 340f)
        // Fill order: index 3 = W petal, index 6 = SW petal — the two facing the inner cluster.
        println("diag 212.5: west stretch=${tight.stretch("b3", "B")}, sw stretch=${tight.stretch("b6", "B")}")
        println("diag 340.0: west stretch=${wide.stretch("b3", "B")}, sw stretch=${wide.stretch("b6", "B")}")

        assertTrue("wide diagonal should hold the west ray (was ${wide.stretch("b3", "B")})", abs(wide.stretch("b3", "B")) < 5f)
        assertTrue("wide diagonal should hold the sw ray (was ${wide.stretch("b6", "B")})", abs(wide.stretch("b6", "B")) < 5f)
        assertTrue("tight diagonal must reproduce the crowding (was ${tight.stretch("b3", "B")})", tight.stretch("b3", "B") > 20f)
    }
}
