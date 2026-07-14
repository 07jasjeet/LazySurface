package com.jasjeet.lazysurface

import androidx.collection.MutableLongIntMap
import androidx.collection.MutableObjectIntMap
import androidx.collection.MutableScatterMap
import androidx.collection.ScatterMap
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max

/**
 * One sided relation as a solver constraint template. Everything here is
 * content-derived, so templates are built once per content change and reused by
 * every per-frame solve.
 */
internal class RelationConstraint(
    val itemKey: Any,
    val neighborKey: Any,
    /** Side of the neighbour the item was declared to sit on. */
    val placedSide: LazySurfaceNeighbor.Side,
    val alignment: LazySurfaceNeighbor.Alignment,
    /** The relation's own margin, this constraint's declared gap and no other's. */
    val relationMargin: Float,
)

/**
 * Builds the solver's constraint templates from a relation view, one per sided
 * relation between live items. Centered-on-pivot relations have no side to separate
 * along, and the pivot itself is not a movable endpoint, so neither contributes.
 */
internal fun buildRelationConstraints(
    infos: List<LazySurfaceItemInfo>,
    itemByKey: ScatterMap<Any, LazySurfaceItemInfo>,
    neighborsOf: (LazySurfaceItemInfo) -> List<LazySurfaceNeighbor> = { it.neighbors },
): List<RelationConstraint> {
    val constraints = ArrayList<RelationConstraint>()
    infos.forEach { info ->
        neighborsOf(info).forEach { neighbor ->
            val side = neighbor.side ?: return@forEach
            val neighborKey = neighbor.key
            if (neighborKey === LazySurfacePivot || !itemByKey.containsKey(neighborKey)) return@forEach
            constraints.add(
                RelationConstraint(
                    itemKey = info.key,
                    neighborKey = neighborKey,
                    placedSide = side.opposite(),
                    alignment = neighbor.alignment,
                    relationMargin = neighbor.margin,
                )
            )
        }
    }
    return constraints
}

/**
 * The constraint templates compiled for the sweep hot path, plus the scratch the
 * sweeps run on. Compiled once per content change (and on direction change) and
 * reused by every solve, not thread safe, like the measure pass that owns it.
 *
 * **Primitive layout.** Endpoints become indices into flat position/extent arrays,
 * sides and alignments resolve against the layout direction at compile time, so a
 * sweep is pure float arithmetic. Sums are grouped differently than the naive
 * per-sweep math, so results can differ from it in the last float bit, orders of
 * magnitude below [SolverConvergedPx].
 *
 * **Active set.** A constraint is evaluated in a sweep only if one of its endpoints
 * moved in the previous sweep (every constraint starts active, and both endpoints
 * of a correction re-activate their constraints). A skipped constraint's endpoints
 * are exactly where they were when it last evaluated to zero correction, so its
 * evaluation would be zero again: skipping only skips no-ops, and evaluation order
 * stays registration order. The hard-only finishing phase re-activates everything
 * once, because dropping the soft terms changes what an evaluation returns.
 */
internal class CompiledConstraints(
    val source: List<RelationConstraint>,
    val isRtl: Boolean,
) {
    /** Cross-axis alignment resolved to absolute geometry at compile time. */
    private companion object {
        const val AlignNone = 0
        const val AlignLeading = 1
        const val AlignCenter = 2
        const val AlignTrailing = 3
    }

    private val constraintCount = source.size

    /** Unique constraint endpoints, index-addressed everywhere below. */
    private val keys = ArrayList<Any>()
    private val itemIdx = IntArray(constraintCount)
    private val neighborIdx = IntArray(constraintCount)
    private val horizontal = BooleanArray(constraintCount)
    /** +1 when the item sits at the neighbour's greater coordinate side. */
    private val away = FloatArray(constraintCount)
    /** The relation's declared gap along the axis, zero for Free. */
    private val gap = FloatArray(constraintCount)
    private val alignMode = IntArray(constraintCount)
    private val free = BooleanArray(constraintCount)

    /** CSR adjacency: the constraints each endpoint participates in. */
    private val adjacencyStart: IntArray
    private val adjacency: IntArray

    // Per-solve scratch, sized once: positions/extents by endpoint index.
    private val x: FloatArray
    private val y: FloatArray
    private val w: FloatArray
    private val h: FloatArray
    private val present: BooleanArray
    private val movedEver: BooleanArray

    /** Sweep number up to which each constraint stays active. */
    private val activeStamp = IntArray(constraintCount)

    /** Sweeps the last [solve] actually ran, across both phases, a diagnostic. */
    var lastSolveSweeps: Int = 0
        private set

    init {
        val keyIndex = MutableObjectIntMap<Any>()
        fun indexOf(key: Any): Int {
            val existing = keyIndex.getOrDefault(key, -1)
            if (existing >= 0) return existing
            keys.add(key)
            return (keys.size - 1).also { keyIndex[key] = it }
        }

        source.forEachIndexed { i, constraint ->
            itemIdx[i] = indexOf(constraint.itemKey)
            neighborIdx[i] = indexOf(constraint.neighborKey)
            val side = constraint.placedSide
            val isHorizontal = side == LazySurfaceNeighbor.Side.Start || side == LazySurfaceNeighbor.Side.End
            horizontal[i] = isHorizontal
            val isFree = constraint.alignment == LazySurfaceNeighbor.Alignment.Free
            free[i] = isFree
            away[i] = if (isHorizontal) {
                if ((side == LazySurfaceNeighbor.Side.End) != isRtl) 1f else -1f
            } else {
                if (side == LazySurfaceNeighbor.Side.Bottom) 1f else -1f
            }
            gap[i] = if (isFree) 0f else constraint.relationMargin
            alignMode[i] = when (constraint.alignment) {
                LazySurfaceNeighbor.Alignment.Free -> AlignNone
                LazySurfaceNeighbor.Alignment.Center -> AlignCenter
                // The cross axis of a vertical relation is horizontal, so its
                // start/end alignments mirror under RTL, a horizontal relation
                // aligns on y, which has no direction.
                LazySurfaceNeighbor.Alignment.Start ->
                    if (!isHorizontal && isRtl) AlignTrailing else AlignLeading
                LazySurfaceNeighbor.Alignment.End ->
                    if (!isHorizontal && isRtl) AlignLeading else AlignTrailing
            }
        }

        // A pair related along both axes is a diagonal declaration: the two
        // separations fully specify the pair's geometry, and each relation's
        // cross-axis alignment would only fight the other's separation. Dropping
        // those alignments lets a consistent diagonal rest instead of tug-of-warring
        // every sweep. Free constraints never position an axis, so they don't count.
        run {
            val pairAxes = MutableLongIntMap()
            fun pairKey(i: Int): Long {
                val a = itemIdx[i]
                val b = neighborIdx[i]
                val lo = if (a < b) a else b
                val hi = if (a < b) b else a
                return (lo.toLong() shl 32) or hi.toLong()
            }
            for (i in 0 until constraintCount) {
                if (free[i]) continue
                val axis = if (horizontal[i]) 1 else 2
                val key = pairKey(i)
                pairAxes[key] = pairAxes.getOrDefault(key, 0) or axis
            }
            for (i in 0 until constraintCount) {
                if (!free[i] && pairAxes.getOrDefault(pairKey(i), 0) == 3) {
                    alignMode[i] = AlignNone
                }
            }
        }

        val keyCount = keys.size
        adjacencyStart = IntArray(keyCount + 1)
        for (i in 0 until constraintCount) {
            adjacencyStart[itemIdx[i] + 1]++
            adjacencyStart[neighborIdx[i] + 1]++
        }
        for (j in 1..keyCount) adjacencyStart[j] += adjacencyStart[j - 1]
        adjacency = IntArray(2 * constraintCount)
        val cursor = adjacencyStart.copyOf()
        for (i in 0 until constraintCount) {
            adjacency[cursor[itemIdx[i]]++] = i
            adjacency[cursor[neighborIdx[i]]++] = i
        }

        x = FloatArray(keyCount)
        y = FloatArray(keyCount)
        w = FloatArray(keyCount)
        h = FloatArray(keyCount)
        present = BooleanArray(keyCount)
        movedEver = BooleanArray(keyCount)
    }

    /**
     * The global constraint pass: projected Gauss–Seidel sweeps over every declared
     * relation whose two endpoints both resolved this pass. Each relation contributes:
     *  - **Hard separation** along the relation's axis: the item may sit *farther*
     *    from its neighbour than declared, never closer. Violations are corrected
     *    fully, split between both endpoints, the split is what propagates a push
     *    through the graph so surrounding items make room.
     *  - **Soft gap** along the same axis: when not violating the floor, a
     *    fractional pull toward the exact declared gap.
     *  - **Soft alignment** across the axis, also fractional, so competing
     *    alignments blend instead of teleporting items.
     *  - Free relations keep only a no-overlap guarantee, exerting no force unless
     *    the two content boxes actually intersect.
     *
     * Positions start from the joint placement, which is exact wherever the graph is
     * consistent, so the common path measures no correction on the first sweep and
     * exits immediately. Sweeps run in registration order with a fixed budget, so
     * results are deterministic for a given graph, conflicts too large for one
     * budget keep converging across passes, because every pass re-derives and
     * re-solves from scratch.
     *
     * Contradictions never converge at all, they settle into a tug-of-war whose
     * corrections stop shrinking, while genuine convergence decays geometrically. A
     * phase whose max correction fails to shrink for [PlateauStalledSweeps]
     * consecutive sweeps is verifiably at its stalemate and stops early. The
     * detection is a pure function of the sweep sequence, so the bail-out is
     * deterministic too: the same inputs bail at the same sweep with the same
     * geometry.
     *
     * @return whether any position moved beyond the convergence threshold.
     */
    fun solve(resolved: MutableScatterMap<Any, Rect>): Boolean {
        if (constraintCount == 0) return false

        for (j in keys.indices) {
            val rect = resolved[keys[j]]
            present[j] = rect != null
            movedEver[j] = false
            if (rect != null) {
                x[j] = rect.left
                y[j] = rect.top
                w[j] = rect.right - rect.left
                h[j] = rect.bottom - rect.top
            }
        }

        var anyMoved = false
        var sweepNo = 1
        lastSolveSweeps = 0

        fun phase(budget: Int, softConstraints: Boolean) {
            activeStamp.fill(sweepNo)
            var remaining = budget
            var previous = Float.POSITIVE_INFINITY
            var stalled = 0
            while (remaining-- > 0) {
                val correction = sweep(sweepNo++, softConstraints)
                lastSolveSweeps++
                if (correction <= SolverConvergedPx) return
                anyMoved = true
                // Plateau: corrections that stop shrinking are a stalemate, not
                // progress, stop re-fighting it.
                stalled = if (correction > previous * PlateauShrinkFactor) stalled + 1 else 0
                if (stalled >= PlateauStalledSweeps) return
                previous = correction
            }
        }

        phase(MixedSolverSweeps, softConstraints = true)
        // Finishing phase: soft pulls can hold a floor slightly violated at the
        // mixed equilibrium, running the floors alone restores the no-overlap
        // guarantee and converges monotonically, since separations only grow here.
        phase(HardOnlySweeps, softConstraints = false)

        for (j in keys.indices) {
            if (present[j] && movedEver[j]) {
                resolved[keys[j]] = Rect(x[j], y[j], x[j] + w[j], y[j] + h[j])
            }
        }
        return anyMoved
    }

    /**
     * One relaxation sweep over the active constraints, without [softConstraints]
     * only the hard floor projections run.
     *
     * @return the largest correction applied.
     */
    private fun sweep(sweepNo: Int, softConstraints: Boolean): Float {
        var maxCorrection = 0f
        for (i in 0 until constraintCount) {
            if (activeStamp[i] < sweepNo) continue
            val a = itemIdx[i]
            val b = neighborIdx[i]
            if (!present[a] || !present[b]) continue

            val isFree = free[i]
            if (isFree &&
                !(x[a] < x[b] + w[b] && x[b] < x[a] + w[a] && y[a] < y[b] + h[b] && y[b] < y[a] + h[a])
            ) {
                // Free relations exert no force unless the boxes truly intersect.
                continue
            }
            val soft = softConstraints && !isFree

            var deltaAx = 0f
            var deltaAy = 0f
            var deltaBx = 0f
            var deltaBy = 0f
            val direction = away[i]
            if (horizontal[i]) {
                val gapError =
                    if (direction > 0f) x[b] + w[b] + gap[i] - x[a]
                    else x[a] + w[a] + gap[i] - x[b]
                if (gapError > 0f) {
                    // Closer than declared: hard, push fully apart.
                    val push = direction * gapError * 0.5f
                    deltaAx += push
                    deltaBx -= push
                } else if (soft) {
                    // Farther than declared: soft, pull toward the exact gap.
                    val pull = direction * gapError * SoftFactor
                    deltaAx += pull
                    deltaBx -= pull
                }
                if (soft && alignMode[i] != AlignNone) {
                    val expected = y[b] + when (alignMode[i]) {
                        AlignCenter -> (h[b] - h[a]) / 2f
                        AlignTrailing -> h[b] - h[a]
                        else -> 0f
                    }
                    val misalignment = y[a] - expected
                    deltaAy -= misalignment * SoftFactor
                    deltaBy += misalignment * SoftFactor
                }
            } else {
                val gapError =
                    if (direction > 0f) y[b] + h[b] + gap[i] - y[a]
                    else y[a] + h[a] + gap[i] - y[b]
                if (gapError > 0f) {
                    val push = direction * gapError * 0.5f
                    deltaAy += push
                    deltaBy -= push
                } else if (soft) {
                    val pull = direction * gapError * SoftFactor
                    deltaAy += pull
                    deltaBy -= pull
                }
                if (soft && alignMode[i] != AlignNone) {
                    val expected = x[b] + when (alignMode[i]) {
                        AlignCenter -> (w[b] - w[a]) / 2f
                        AlignTrailing -> w[b] - w[a]
                        else -> 0f
                    }
                    val misalignment = x[a] - expected
                    deltaAx -= misalignment * SoftFactor
                    deltaBx += misalignment * SoftFactor
                }
            }

            val correction = max(
                max(abs(deltaAx), abs(deltaAy)),
                max(abs(deltaBx), abs(deltaBy)),
            )
            if (correction > 0f) {
                if (correction > maxCorrection) maxCorrection = correction
                if (deltaAx != 0f || deltaAy != 0f) {
                    x[a] += deltaAx
                    y[a] += deltaAy
                    movedEver[a] = true
                    activate(a, sweepNo + 1)
                }
                if (deltaBx != 0f || deltaBy != 0f) {
                    x[b] += deltaBx
                    y[b] += deltaBy
                    movedEver[b] = true
                    activate(b, sweepNo + 1)
                }
            }
        }
        return maxCorrection
    }

    /** A moved endpoint re-activates every constraint it participates in. */
    private fun activate(endpoint: Int, untilSweep: Int) {
        for (p in adjacencyStart[endpoint] until adjacencyStart[endpoint + 1]) {
            val constraint = adjacency[p]
            if (activeStamp[constraint] < untilSweep) activeStamp[constraint] = untilSweep
        }
    }
}

/**
 * Budget for the mixed (soft + hard) sweeps per measure pass. Conflicts that need
 * more keep converging across passes, each pass re-initialises from joint placement
 * and re-solves, so residuals shrink frame over frame instead of accumulating.
 */
private const val MixedSolverSweeps = 24

/** Budget for the finishing separation-only sweeps that restore the no-overlap floor. */
private const val HardOnlySweeps = 16

/**
 * A sweep must shrink the max correction to this fraction of the previous sweep's
 * or it counts as stalled. A contradiction's tug-of-war holds at ~1.0, genuine
 * convergence decays below this, hard pushes halve per hop, and soft diffusion
 * along an N-item chain decays at roughly 1 - (pi/N)^2/3 per sweep, ~0.95 for
 * eight items. The margin is deliberately thin on the diffusion side: a chain long
 * enough to decay above this factor gets truncated like a smaller sweep budget
 * would, deterministically, the same way every pass.
 */
private const val PlateauShrinkFactor = 0.97f

/**
 * Consecutive stalled sweeps that end a phase early. More than one, so a transient
 * flat sweep while a correction wave builds up doesn't cut a genuine convergence.
 */
private const val PlateauStalledSweeps = 3

/** Corrections below this many surface px count as converged, float noise, not conflict. */
private const val SolverConvergedPx = 0.25f

/**
 * Fraction of a soft-constraint error corrected per endpoint per sweep. Fractional so
 * that competing pulls blend into a least-squares-like equilibrium instead of
 * oscillating.
 */
private const val SoftFactor = 0.35f
