package com.jasjeet.lazysurface

import androidx.collection.MutableScatterMap
import androidx.collection.ScatterMap
import androidx.collection.mutableScatterMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize

/**
 * The single owner of provisional geometry: positions for every item that is
 * positioning-connected but not yet measured, at cached size where one is known and
 * zero extents otherwise. The graph determines where a connected item sits only up
 * to the sizes along the chain, so deep entries are systematically-short estimates
 * that correct as measurements land.
 *
 * Two consumers, two tiers:
 *  - [awaitingMeasure]: the pass's one-hop rects, served as-is. The clamp reads
 *    these: where such a neighbour of visible content sits, the bounding shape is
 *    unknown rather than absent, so that side is treated as still growing rather
 *    than as a wall.
 *  - [positionOf]: any positioning-connected item. Falls back from [awaitingMeasure]
 *    to the transitive closure, derived on demand and memoized until the next
 *    [update]: plain scrolling never pays for it, only frames that actually
 *    navigate do, and a fully-measured surface pays nothing at all. `null` has one
 *    meaning: not positioning-connected, for which nothing is derivable.
 *
 * The measure pass publishes its outputs here once per pass via [update], nothing
 * else writes.
 */
internal class ProvisionalPositions {
    private var infos: List<LazySurfaceItemInfo> = emptyList()
    private var graph: ScatterMap<Any, GraphNode> = mutableScatterMapOf()
    private var itemByKey: ScatterMap<Any, LazySurfaceItemInfo> = mutableScatterMapOf()
    private var resolved: ScatterMap<Any, Rect> = mutableScatterMapOf()
    private var cachedSize: (Any) -> IntSize? = { null }
    private var isRtl: Boolean = false

    /**
     * Items positioned against resolved content and awaiting their first
     * measurement, as parked by the pass, also the seed tier of the closure.
     */
    var awaitingMeasure: ScatterMap<Any, Rect> = mutableScatterMapOf()
        private set

    private val derived = MutableScatterMap<Any, Rect>()
    private var derivedStale = false

    /** Publishes one measure pass's outputs. O(1): the closure is deferred. */
    fun update(
        infos: List<LazySurfaceItemInfo>,
        graph: ScatterMap<Any, GraphNode>,
        itemByKey: ScatterMap<Any, LazySurfaceItemInfo>,
        resolved: ScatterMap<Any, Rect>,
        awaitingMeasure: ScatterMap<Any, Rect>,
        cachedSize: (Any) -> IntSize?,
        isRtl: Boolean,
    ) {
        this.infos = infos
        this.graph = graph
        this.itemByKey = itemByKey
        this.resolved = resolved
        this.awaitingMeasure = awaitingMeasure
        this.cachedSize = cachedSize
        this.isRtl = isRtl
        derivedStale = true
    }

    /**
     * The derivable position of any unmeasured item: its awaiting-measure rect, or
     * its entry in the lazily-derived transitive closure.
     */
    fun positionOf(key: Any): Rect? = awaitingMeasure[key] ?: deepDerived()[key]

    private fun deepDerived(): ScatterMap<Any, Rect> {
        if (!derivedStale) return derived
        derivedStale = false
        derived.clear()
        // Fully positioned surface: nothing to derive, and this stays O(1).
        if (infos.size <= resolved.size + awaitingMeasure.size) return derived

        fun rectOf(key: Any): Rect? =
            if (key === LazySurfacePivot) Rect.Zero
            else resolved[key] ?: awaitingMeasure[key] ?: derived[key]

        // A breadth-first walk seeded from everything already positioned, following
        // dependents outward: a chain declared against its dependency direction
        // still derives in this single O(items + edges) sweep, unlike a
        // registration-order fixpoint. Unplaceable items are skipped, the parent
        // that later places them re-enqueues their keys. Deterministic: seeds in
        // registration order, dependents in declaration order.
        val queue = ArrayDeque<Any>()
        infos.forEach { info ->
            if (rectOf(info.key) != null) {
                graph[info.key]?.dependents?.let(queue::addAll)
            }
        }
        graph[LazySurfacePivot]?.dependents?.let(queue::addAll)

        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            if (rectOf(key) != null) continue
            val info = itemByKey[key] ?: continue
            val node = graph[key] ?: continue

            // Constraint selection mirrors the measure pass: the item's own
            // declarations while any endpoint is placeable, else the relations others
            // declared against it. Free edges are absent from both by construction.
            val ownUsable = node.ownConstraints.count { rectOf(it.sourceKey) != null }
            val edges: List<PositioningEdge> =
                if (ownUsable > 0) node.ownConstraints else node.fallbackConstraints
            val usable =
                if (ownUsable > 0) ownUsable
                else edges.count { rectOf(it.sourceKey) != null }
            if (usable == 0) continue // a later-placed parent re-enqueues this key

            val size = cachedSize(key)
                ?.let { Size(it.width.toFloat(), it.height.toFloat()) }
                ?: Size.Zero
            var left = 0f
            var top = 0f
            for (edge in edges) {
                val sourceRect = rectOf(edge.sourceKey) ?: continue
                val exact = placeItemRect(
                    anchorRect = sourceRect,
                    side = edge.sideOfSource,
                    alignment = edge.alignment,
                    size = size,
                    isRtl = isRtl,
                    relationMargin = edge.margin,
                )
                left += exact.left
                top += exact.top
            }
            derived[key] = Rect(Offset(left / usable, top / usable), size)
            node.dependents.let(queue::addAll)
        }
        return derived
    }
}
