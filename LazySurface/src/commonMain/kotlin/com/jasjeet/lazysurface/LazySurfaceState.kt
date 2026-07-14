package com.jasjeet.lazysurface

import kotlin.time.TimeSource
import androidx.collection.MutableScatterMap
import androidx.collection.MutableScatterSet
import androidx.collection.ScatterMap
import androidx.collection.mutableScatterMapOf
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.Drag2DScope
import androidx.compose.foundation.gestures.Draggable2DState
import androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlin.math.min

/**
 * A `PaddingValues` resolved against density and layout direction: the per-side
 * blank-space budget past the content edges, in absolute window pixels.
 */
internal class ResolvedContentPadding(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    override fun toString() = "[l=$left t=$top r=$right b=$bottom]"

    companion object {
        fun all(value: Float) = ResolvedContentPadding(value, value, value, value)
    }
}

/**
 * One direction of a declared relation: once [sourceKey] resolves, the item holding
 * this edge may be placed on [sideOfSource] of it.
 */
internal class PositioningEdge(
    val sourceKey: Any,
    val sideOfSource: LazySurfaceNeighbor.Side?,
    val alignment: LazySurfaceNeighbor.Alignment,
    /** The relation's own margin, the same from either endpoint's perspective. */
    val margin: Float,
)

/**
 * One key's place in the relation graph, derived from the effective (healed)
 * relations at content-change time, the measure pass reads it with a single map
 * lookup per item and zero graph allocations per frame.
 */
internal class GraphNode {
    /** The item's own declared relations as positioning constraints. */
    val ownConstraints = ArrayList<PositioningEdge>(2)

    /** Reverse edges: relations other items declared against this one, fallbacks. */
    val fallbackConstraints = ArrayList<PositioningEdge>(2)

    /** Keys this node can position once resolved, its BFS successors. */
    val dependents = ArrayList<Any>(2)

    /**
     * Undirected adjacency for routing and the stranded walk: symmetric regardless
     * of which endpoint declared the relation, and including Free links and healed
     * edges, strictly more than the declared neighbours.
     */
    val adjacent = ArrayList<Any>(2)

    /**
     * [adjacent] minus Free relations: the edges that can actually position an item.
     * Navigation falls back to these when a Free shortcut leads to a waypoint whose
     * position nothing has derived yet.
     */
    val positioningAdjacent = ArrayList<Any>(2)
}

@Composable
fun rememberLazySurfaceState(
    initialOffset: Offset = Offset.Zero,
    initialZoom: Float = 1f
) = rememberSaveable(saver = LazySurfaceState.StateSaver) {
    LazySurfaceState(initialOffset, initialZoom)
}

@Stable
class LazySurfaceState(
    initialOffset: Offset = Offset.Zero,
    initialZoom: Float = 1f
) : Draggable2DState {
    var viewportSize: IntSize by mutableStateOf(IntSize.Zero)
        internal set

    /** All items registered on the surface. */
    var itemsInfo: List<LazySurfaceItemInfo> by mutableStateOf(emptyList())
        internal set

    /**
     * The infos list the engine last registered, the identity gate for content
     * changes. Deliberately a plain field and NOT the snapshot-backed [itemsInfo]:
     * a measure pass can run inside a snapshot that doesn't yet see the previous
     * pass's writes, and a snapshot-read gate then re-registers identical content
     * on such passes, rebuilding the graph, defeating [SolveMemo]'s identity check,
     * and feeding an invalidation loop. It must observe what the engine actually
     * did last, unconditionally.
     */
    private var registeredInfos: List<LazySurfaceItemInfo>? = null

    val totalItemsCount: Int get() = itemsInfo.size

    /** Items currently composed and visible in the viewport. */
    var visibleItemsInfo: List<LazySurfaceVisibleItemInfo> by mutableStateOf(emptyList())
        internal set

    /**
     * Content rects (surface coordinates) of every item whose position is known so far
     * **as of each item's last measurement**. Entries are authoritative only near the
     * viewport (they are re-measured every pass there), positions of items that haven't
     * been near the viewport lately are last-known bookkeeping that self-corrects
     * whenever the viewport travels past them. Nothing in the library flies to or
     * clamps against a far entry without traversing to it first.
     *
     * Iteration order is unspecified. For ordered data use [itemsInfo] (registration
     * order) or [visibleItemsInfo] (placement order).
     */
    var resolvedRects: Map<Any, Rect> by mutableStateOf(emptyMap())
        internal set

    /**
     * Same data as [resolvedRects], kept as a scatter map for allocation-free access,
     * entries are last-measured positions, not a promise about where an item is now.
     */
    internal var lastKnownRects: ScatterMap<Any, Rect> = mutableScatterMapOf()

    /**
     * The bounding box of everything resolved so far, informational only. Scrolling is
     * NOT clamped to this rect but to the *bounding shape*: the union of the resolved
     * items' content rects (see [coerceIntoResolvedShape]). `null` until the first item
     * resolves. Rebuilt from the current content on every pass, so removed items drop
     * out immediately, sizes of far-away items may be stale until travelled past.
     */
    var resolvedBounds: Rect? by mutableStateOf(null)
        internal set

    /**
     * Content rects of all resolved items, by key, their union is the bounding
     * shape, the scrollable area of the surface.
     */
    internal var resolvedMarginBoxes: ScatterMap<Any, Rect> = mutableScatterMapOf()

    /** Provisional geometry for everything unmeasured, see [ProvisionalPositions]. */
    internal val provisionalPositions = ProvisionalPositions()

    /**
     * Interop scratch for the androidx snapping bridge. The snapping contract is
     * one-dimensional, so the surface runs a [androidx.compose.foundation.gestures.FlingBehavior]
     * on a scalar arc-length scroll scope: at release the surface stashes the
     * gesture's unit direction here, [LazySurfaceSnapLayoutInfoProvider] picks the
     * 2D target and publishes its key, and every scalar `scrollBy` advances toward
     * the target's live center (falling back to the stashed direction while no
     * target is chosen).
     */
    internal var snapFlingDirection: Offset = Offset.Zero
    internal var snapTargetKey: Any? = null

    internal var itemByKey: ScatterMap<Any, LazySurfaceItemInfo> = mutableScatterMapOf()
        private set

    /**
     * Set when the viewport is verifiably stranded in blank space: every item position
     * is known (nothing left for the approach loop to resolve) yet nothing lies inside the
     * viewport, the content underneath was removed. Holds the key of the item
     * [LazySurface] snaps the viewport back to.
     */
    internal var strandedNearestKey: Any? by mutableStateOf(null)

    /**
     * Where to snap should the latest content change strand the viewport: the
     * graph-nearest surviving relative of the previously displayed items, found by
     * a breadth-first walk of the *pre-change* neighbour graph, never by spatial
     * distance. `null` when nothing survived, the measure pass then falls back to
     * the first registered resolved item.
     */
    internal var strandedSnapHint: Any? = null

    private fun computeSnapHint(
        oldGraph: ScatterMap<Any, GraphNode>,
        newByKey: ScatterMap<Any, LazySurfaceItemInfo>,
    ): Any? {
        val previouslyVisible = visibleItemsInfo
        if (previouslyVisible.isEmpty()) return null

        val visited = MutableScatterSet<Any>()
        val queue = ArrayDeque<Any>()
        previouslyVisible.forEach { item ->
            if (visited.add(item.key)) queue.add(item.key)
        }

        // The first still-existing node the walk encounters wins, no distance involved.
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            if (key !== LazySurfacePivot && newByKey.containsKey(key)) return key
            oldGraph[key]?.adjacent?.forEach { neighbor ->
                if (visited.add(neighbor)) queue.add(neighbor)
            }
        }
        return null
    }

    /**
     * The relation graph, one node per participating key (including the pivot),
     * derived from the effective (healed) relations. Rebuilt only when the content
     * changes, the per-frame measure pass walks it read-only.
     */
    internal var graph: ScatterMap<Any, GraphNode> = mutableScatterMapOf()
        private set

    /**
     * Prebuilt solver constraints, margins included, one per sided relation among
     * live items. The per-pass solve only looks up live rects against these.
     */
    internal var relationConstraints: List<RelationConstraint> = emptyList()
        private set

    var zoom by mutableFloatStateOf(initialZoom)
        internal set

    /** Surface-space point currently at the center of the viewport. Pivot is at [Offset.Zero]. */
    var offset by mutableStateOf(initialOffset)
        private set

    /**
     * Where the viewport center was on the previous measure pass. The delta to the
     * current center is the movement direction used to predict what to prefetch.
     */
    internal var previousPassCenter: Offset = Offset.Zero

    /**
     * The most blank space allowed between each content edge and the matching viewport
     * edge when scrolling reaches the bounding shape, in window pixels, resolved from
     * [LazySurface]'s `contentPadding` against density and layout direction. `null`
     * keeps the default of half the viewport per side, the content edge may reach the
     * viewport center.
     */
    internal var contentPaddingPx: ResolvedContentPadding? = null

    /** Live prefetch requests, keyed by the item they warm. */
    @OptIn(ExperimentalFoundationApi::class)
    internal val prefetchHandles = MutableScatterMap<Any, LazyLayoutPrefetchState.PrefetchHandle>()

    /** Reusable measure-pass scratch, see [MeasureScratch]. */
    internal val measureScratch = MeasureScratch()

    /** Previous pass's solve, replayed when its inputs recur, see [SolveMemo]. */
    internal val solveMemo = SolveMemo()

    /** Live [LazySurfaceItemScope.animateItem] records, keyed by item. */
    internal val itemAnimations = MutableScatterMap<Any, LazySurfaceItemAnimation>()

    /**
     * Monotonic measure-pass counter: placement animations glide only between
     * consecutive placements, so items re-entering the viewport snap instead of
     * flying in from stale positions.
     */
    internal var passStamp = 0

    // Content is the only source of an item's size, so nothing is known about an
    // item until its first measurement.
    private val measuredSizes = MutableScatterMap<Any, IntSize>()

    internal fun cachedSize(key: Any): IntSize? = measuredSizes[key]

    internal fun cacheMeasuredSize(key: Any, size: IntSize) {
        measuredSizes[key] = size
    }

    /**
     * Anchors of removed items, the relation graph's tombstones. A live relation
     * targeting a departed key splices through these to its nearest surviving anchor,
     * so removing a node from a chain closes the gap instead of amputating everything
     * behind it. An entry lives while some live relation still chases through it, and
     * disappears the moment its key is declared again.
     */
    private val tombstones = MutableScatterMap<Any, List<LazySurfaceNeighbor>>()

    /**
     * Each live item's relations with departed targets healed, retargeted to the
     * nearest surviving anchor with the declared side and alignment preserved, or
     * dropped when nothing survives to splice to. This is the graph every consumer
     * walks (resolution, the solver, routing, the clamp), [itemsInfo] keeps reporting
     * the declared originals.
     */
    internal var effectiveNeighbors: ScatterMap<Any, List<LazySurfaceNeighbor>> = mutableScatterMapOf()
        private set

    internal fun effectiveNeighborsOf(info: LazySurfaceItemInfo): List<LazySurfaceNeighbor> =
        effectiveNeighbors[info.key] ?: info.neighbors

    internal fun updateItems(
        infos: List<LazySurfaceItemInfo>,
        newItemByKey: ScatterMap<Any, LazySurfaceItemInfo>,
    ) {
        if (registeredInfos !== infos) {
            registeredInfos = infos
            // Decided against the old graph before it is replaced. A change arriving
            // while already stranded computes nothing, keep the previous hint, the
            // consumer drops it if it no longer resolves.
            strandedSnapHint = computeSnapHint(oldGraph = graph, newByKey = newItemByKey) ?: strandedSnapHint

            // Tombstone the anchors of everything that just departed, re-declared
            // keys shed theirs, a live key always wins over its own tombstone.
            itemByKey.forEach { key, info ->
                if (!newItemByKey.containsKey(key)) tombstones[key] = info.neighbors
            }
            tombstones.removeIf { key, _ -> newItemByKey.containsKey(key) }

            itemsInfo = infos
            itemByKey = newItemByKey

            /** Tombstones some chase walked through this update still needed. */
            val usedTombstones = MutableScatterSet<Any>()

            /**
             * The nearest surviving stand-in for [target]: the target itself when
             * alive, otherwise the first of its tombstoned anchors that chases to
             * a live key (or the pivot). `null` when the chase dead-ends or cycles.
             */
            fun survivor(target: Any, visited: MutableScatterSet<Any>): Any? {
                if (target === LazySurfacePivot || newItemByKey.containsKey(target))
                    return target

                if (!visited.add(target))
                    return null

                val anchors = tombstones[target] ?: return null
                usedTombstones.add(target)
                anchors.forEach { anchor ->
                    survivor(anchor.key, visited)?.let { return it }
                }
                return null
            }

            val effective = MutableScatterMap<Any, List<LazySurfaceNeighbor>>(infos.size)
            infos.forEach { info ->
                /** Copy-on-heal: stays null while the declared list is usable as-is. */
                var healed: MutableList<LazySurfaceNeighbor>? = null
                info.neighbors.forEachIndexed { index, neighbor ->
                    val target = if (neighbor.key === LazySurfacePivot || newItemByKey.containsKey(neighbor.key))
                        // Duplicates survivor's liveness check to avoid allocating a
                        // visited set on the common live-target path.
                        neighbor.key
                    else
                        survivor(neighbor.key, MutableScatterSet())

                    val replacement = when (target) {
                        neighbor.key -> neighbor
                        null, info.key -> null // nothing survived to splice to, or self
                        else -> neighbor.copy(key = target)
                    }

                    if (replacement !== neighbor && healed == null) {
                        healed = ArrayList(info.neighbors.subList(0, index))
                    }

                    if (replacement != null)
                        healed?.add(replacement)
                }
                effective[info.key] = healed ?: info.neighbors
            }
            effectiveNeighbors = effective

            // Tombstones nothing chased through are no longer reachable from any
            // live declaration, drop them.
            tombstones.removeIf { key, _ -> !usedTombstones.contains(key) }

            // One traversal derives the whole topology from the healed relations.
            val newGraph = MutableScatterMap<Any, GraphNode>(infos.size + 1)
            fun nodeOf(key: Any): GraphNode = newGraph.getOrPut(key) { GraphNode() }
            infos.forEach { info ->
                effective[info.key]?.forEach { neighbor ->
                    val neighborKey = neighbor.key
                    if (neighborKey !== LazySurfacePivot && !newItemByKey.containsKey(neighborKey)) {
                        return@forEach
                    }
                    val itemNode = nodeOf(info.key)
                    val neighborNode = nodeOf(neighborKey)
                    itemNode.adjacent.add(neighborKey)
                    neighborNode.adjacent.add(info.key)

                    // Free relations are adjacency-only: they route, link the
                    // bounding shape and hard-separate (via the solver), but they
                    // never position an item, so they stay out of the constraint
                    // sets and the BFS dependents.
                    if (neighbor.alignment == LazySurfaceNeighbor.Alignment.Free)
                        return@forEach

                    itemNode.positioningAdjacent.add(neighborKey)
                    neighborNode.positioningAdjacent.add(info.key)
                    // "The neighbour sits on side S of me" -> I sit on the opposite
                    // side of it.
                    itemNode.ownConstraints.add(
                        PositioningEdge(neighborKey, neighbor.side?.opposite(), neighbor.alignment, neighbor.margin)
                    )
                    neighborNode.dependents.add(info.key)
                    if (neighborKey !== LazySurfacePivot) {
                        neighborNode.fallbackConstraints.add(
                            PositioningEdge(info.key, neighbor.side, neighbor.alignment, neighbor.margin)
                        )
                        itemNode.dependents.add(neighborKey)
                    }
                }
            }
            graph = newGraph
            relationConstraints = buildRelationConstraints(infos, newItemByKey) {
                effective[it.key] ?: it.neighbors
            }
            // Sizes measured for items that no longer exist must not keep influencing
            // resolution or the bounding box.
            measuredSizes.removeIf { key, _ -> !newItemByKey.containsKey(key) }
            // Removed items take their animation records with them.
            itemAnimations.removeIf { key, animation ->
                val removed = !newItemByKey.containsKey(key)
                if (removed) animation.cancel()
                removed
            }
        }
    }

    /**
     * Applies one gesture pan (window pixels) and returns how much of it the surface
     * consumed, in the same window-pixel space.
     *
     * The consumption report must be EXACT: overscroll effects treat any unconsumed
     * remainder as edge contact, and the window-to-surface-and-back conversion is
     * lossy at fractional zooms (`available / zoom * zoom` misses by a few float
     * ULPs), so a converted report leaks a microscopic remainder on every frame of a
     * plain mid-content drag. Effects that latch on first contact (the iOS platform
     * effect) then swallow the rest of the gesture as band stretch mid-surface. When
     * the shape did not clamp, report [available] itself, bit for bit. Only a
     * genuine clamp reports the partial, converted amount.
     */
    internal fun gesturePan(available: Offset, gestureZoom: Float): Offset {
        val before = offset
        val target = before - available / gestureZoom
        val clamped = coerceIntoResolvedShape(target)
        dispatchRawDelta(clamped - before)
        return if (clamped == target) available else (offset - before) * -gestureZoom
    }

    /**
     * Clamps a proposed viewport-center position to the bounding *shape* built from
     * every item whose position is currently known. Per axis, scrolling may reach as
     * far as the known boxes lying inside the band the viewport overlaps on the
     * other axis, bands decide reach, never what happens to be composed this frame,
     * since granting room only from displayed items slams fast drags into a phantom
     * wall one item past the viewport. A side with a reachable but still unmeasured
     * item stays open, and blank space past the shape is governed by
     * [contentPaddingPx], see [currentShapeClamp]. When the allowed range contracts
     * past the current offset, the coordinate is never snapped, it may only move
     * back towards the range.
     */
    internal fun coerceIntoResolvedShape(target: Offset): Offset {
        val clamp = currentShapeClamp() ?: return target
        // Stranded outside the reachable shape entirely (e.g. items got removed
        // underneath the viewport): pull towards its nearest point instead.
        if (!clamp.hasX && !clamp.hasY) return nearestPointInRects(target, clamp.boxes)

        val current = offset
        return Offset(
            x = if (clamp.hasX) directionalCoerce(current.x, target.x, clamp.minX, clamp.maxX) else current.x,
            y = if (clamp.hasY) directionalCoerce(current.y, target.y, clamp.minY, clamp.maxY) else current.y,
        )
    }

    /**
     * Where the viewport should settle when it was left outside the bounding shape's
     * allowed range, content removed, resized or contracted underneath it: the
     * nearest allowed center. `null` when the offset is already legal, or when
     * nothing is visible to judge against (the stranded machinery owns that case).
     */
    internal fun outOfBoundsResting(): Offset? {
        val clamp = currentShapeClamp() ?: return null
        val target = if (!clamp.hasX && !clamp.hasY) {
            nearestPointInRects(offset, clamp.boxes)
        } else {
            Offset(
                x = if (clamp.hasX) offset.x.coerceIn(clamp.minX, clamp.maxX) else offset.x,
                y = if (clamp.hasY) offset.y.coerceIn(clamp.minY, clamp.maxY) else offset.y,
            )
        }
        // Sub-pixel drift is float noise, not a reason to animate.
        return if ((target - offset).getDistanceSquared() > 0.25f) target else null
    }

    /**
     * Animates the viewport back inside the bounding shape, see [outOfBoundsResting].
     * A beginning gesture or fling reclaims the drag mutex at the same priority and
     * preempts it, user input always wins. The shape can keep shifting while settling
     * (every frame re-measures), so the leg re-aims a bounded number of times and
     * leaves any remainder to the next watcher round.
     */
    internal suspend fun settleIntoBounds() {
        drag(MutatePriority.UserInput) {
            var attempts = 16
            // Velocity carries across re-aims: a leg restarted from rest each frame
            // never leaves the spring's slow initial ramp while the shape keeps
            // shifting, and the settle would visibly hang until the shape went quiet.
            var velocity = Offset.Zero
            while (attempts-- > 0) {
                val target = outOfBoundsResting() ?: return@drag
                var reAim = false
                try {
                    animate(
                        typeConverter = Offset.VectorConverter,
                        initialValue = offset,
                        targetValue = target,
                        initialVelocity = velocity,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ) { value, frameVelocity ->
                        velocity = frameVelocity
                        dragBy(value - offset)
                        val live = outOfBoundsResting()
                        if (live != null && (live - target).getDistance() > 1f) {
                            reAim = true
                            throw RetargetInterrupt()
                        }
                    }
                } catch (interrupt: RetargetInterrupt) {
                    // Control flow only, reAim carries the reason.
                }
                if (!reAim) return@drag
            }
        }
    }

    /** The current per-axis ranges the viewport center may occupy,`null` means unclamped. */
    private fun currentShapeClamp(): ShapeClamp? {
        val visible = visibleItemsInfo
        // Nothing displayed yet (first frame, or a restore far from resolved content
        // that the approach loop is still working towards): leave the offset free.
        if (visible.isEmpty()) return null

        // Every known box grants room (the band checks below decide which apply per
        // axis), the parked frontier rects keep unexplored sides open. Both maps are
        // the previous pass's published views.
        val reachableBoxes = ArrayList<Rect>(resolvedMarginBoxes.size)
        resolvedMarginBoxes.forEach { _, box -> reachableBoxes.add(box) }
        val awaitingMeasure = provisionalPositions.awaitingMeasure
        if (reachableBoxes.isEmpty()) return null

        val viewport = viewportSize
        val clampedZoom = zoom.coerceAtLeast(1e-4f)
        val halfWidth = viewport.width / (2f * clampedZoom)
        val halfHeight = viewport.height / (2f * clampedZoom)
        val current = offset

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var xRangeFound = false
        var yRangeFound = false
        for (box in reachableBoxes) {
            if (box.top <= current.y + halfHeight && box.bottom >= current.y - halfHeight) {
                xRangeFound = true
                minX = minOf(minX, box.left)
                maxX = maxOf(maxX, box.right)
            }
            if (box.left <= current.x + halfWidth && box.right >= current.x - halfWidth) {
                yRangeFound = true
                minY = minOf(minY, box.top)
                maxY = maxOf(maxY, box.bottom)
            }
        }

        // A reachable item that is positioned but not yet measured has an unknown
        // extent: that side opens a whole viewport past its anchored position,
        // renewed every pass until its real box takes over. Otherwise travel towards
        // still-resolving content hits a phantom wall and a fast fling dies there.
        awaitingMeasure.forEach { _, rect ->
            if (xRangeFound && rect.top <= current.y + halfHeight && rect.bottom >= current.y - halfHeight) {
                if (rect.left <= minX) minX = minOf(minX, rect.left - 2 * halfWidth)
                if (rect.right >= maxX) maxX = maxOf(maxX, rect.right + 2 * halfWidth)
            }
            if (yRangeFound && rect.left <= current.x + halfWidth && rect.right >= current.x - halfWidth) {
                if (rect.top <= minY) minY = minOf(minY, rect.top - 2 * halfHeight)
                if (rect.bottom >= maxY) maxY = maxOf(maxY, rect.bottom + 2 * halfHeight)
            }
        }

        // contentPadding is a screen distance, so like the viewport extents it scales
        // by 1/zoom, unset, each side defaults to the half extent, letting the
        // viewport center reach the shape's edges.
        val padding = contentPaddingPx
        val padLeft = if (padding != null) padding.left / clampedZoom else halfWidth
        val padRight = if (padding != null) padding.right / clampedZoom else halfWidth
        val padTop = if (padding != null) padding.top / clampedZoom else halfHeight
        val padBottom = if (padding != null) padding.bottom / clampedZoom else halfHeight

        var centerMinX = 0f
        var centerMaxX = 0f
        if (xRangeFound) {
            centerMinX = minX + halfWidth - padLeft
            centerMaxX = maxX - halfWidth + padRight
            if (centerMinX > centerMaxX) {
                // The shape is smaller than the padded viewport: pin to the middle of
                // the center range, centring the content between the paddings.
                val mid = (centerMinX + centerMaxX) / 2f
                centerMinX = mid
                centerMaxX = mid
            }
        }
        var centerMinY = 0f
        var centerMaxY = 0f
        if (yRangeFound) {
            centerMinY = minY + halfHeight - padTop
            centerMaxY = maxY - halfHeight + padBottom
            if (centerMinY > centerMaxY) {
                val mid = (centerMinY + centerMaxY) / 2f
                centerMinY = mid
                centerMaxY = mid
            }
        }
        return ShapeClamp(
            hasX = xRangeFound, minX = centerMinX, maxX = centerMaxX,
            hasY = yRangeFound, minY = centerMinY, maxY = centerMaxY,
            boxes = reachableBoxes,
        )
    }

    /** One-line clamp snapshot for [LazySurfaceDebug] traces. */
    internal fun describeClampForDebug(): String {
        val clamp = currentShapeClamp() ?: return "clamp=null (nothing visible)"
        return "clamp x=[${clamp.minX}..${clamp.maxX}](${clamp.hasX}) " +
            "y=[${clamp.minY}..${clamp.maxY}](${clamp.hasY}) offset=$offset zoom=$zoom " +
            "padding=$contentPaddingPx viewport=$viewportSize " +
            "visible=${visibleItemsInfo.map { it.key }} " +
            "awaitingMeasure=${provisionalPositions.awaitingMeasure.asMap().keys}"
    }

    /** Per-axis range the viewport center may occupy, and the boxes it derives from. */
    private class ShapeClamp(
        val hasX: Boolean, val minX: Float, val maxX: Float,
        val hasY: Boolean, val minY: Float, val maxY: Float,
        val boxes: List<Rect>,
    )

    @OptIn(ExperimentalFoundationApi::class)
    internal val prefetchState = LazyLayoutPrefetchState()

    /**
     * Timestamp of the last velocity sample. Two samples in the same millisecond give
     * the tracker's polynomial fit a zero time delta, which produces NaN velocities,
     * positions are absolute, so skipping the duplicate loses nothing.
     */
    private var lastVelocitySampleMillis = 0L

    /**
     * Base mark for velocity timestamps. The tracker only ever differentiates
     * timestamps, so a monotonic clock counting from state creation is enough, and
     * unlike a wall clock it exists on every platform and never jumps.
     */
    private val creationMark = TimeSource.Monotonic.markNow()

    private fun monotonicMillis(): Long = creationMark.elapsedNow().inWholeMilliseconds

    private val draggableState = Draggable2DState {
        offset += it
        val now = monotonicMillis()
        if (now != lastVelocitySampleMillis) {
            lastVelocitySampleMillis = now
            velocityTracker.addPosition(now, offset)
        }
    }

    internal val velocityTracker = VelocityTracker().apply {
        addPosition(monotonicMillis(), initialOffset)
    }

    /**
     * Forgets all velocity history. A beginning gesture must compute its fling from
     * its own pointer samples alone: animation frames feed the tracker too, and a
     * quick flick landing within the tracker's stop-gap of a cancelled fling blends
     * stale samples into the fit, producing arbitrary (often near-zero) velocities.
     */
    internal fun resetVelocityTracking() {
        velocityTracker.resetTracking()
        lastVelocitySampleMillis = 0L
    }

    override fun dispatchRawDelta(delta: Offset) = draggableState.dispatchRawDelta(delta)

    /**
     * Interrupts any in-flight scroll animation, fling decay, route travel, stranded
     * snap-back. Raw drag deltas bypass the mutation mutex, so a beginning gesture must
     * explicitly claim it: acquiring at [MutatePriority.UserInput] cancels the holder.
     */
    internal suspend fun stopScrollAnimations() {
        drag(MutatePriority.UserInput) { /* claiming the mutex is the whole job */ }
    }

    override suspend fun drag(dragPriority: MutatePriority, block: suspend Drag2DScope.() -> Unit) =
        draggableState.drag(dragPriority, block)

    /**
     * Animates the viewport to center on the item with [key] by **traversing the
     * neighbour graph**, the shortest chain of relations from the nearest known
     * item to the target (relations count in both directions, and the pivot is a node
     * like any other).
     *
     * Traversal is deliberate: an offscreen item's cached position is only what was
     * true when it was last seen, so the journey never trusts it, it travels hop by
     * adjacent hop, and the measure passes it causes re-measure everything along the
     * way, correcting stale layout before the viewport arrives. Waypoints that are
     * *currently displayed* are the exception: their positions are verified ground
     * truth this frame, so the walk fast-forwards to the farthest on-screen waypoint
     * of its route, a visible target is one direct glide, and stretches crossing
     * visible territory collapse into single legs. The walk is anchored to the graph:
     * progress is the last waypoint reached, and a route is re-planned from there only
     * when a waypoint vanishes or its position gets corrected, never from spatial
     * proximity, which on convoluted shapes (a spiral) would revisit already-visited
     * nodes and loop.
     */
    suspend fun animateToItem(
        key: Any,
        animationSpec: AnimationSpec<Offset> = spring()
    ) {
        LazySurfaceDebug.log { "animateToItem($key) from offset=$offset" }
        activeNavigations++
        try {
            drag(MutatePriority.UserInput) {
                animateToItemInternal(key, animationSpec)
            }
        } finally {
            activeNavigations--
        }
    }

    private var activeNavigations = 0

    /**
     * Whether an [animateToItem] journey is in flight. The stranded and settle
     * watchers must not rescue a viewport deliberately crossing blank space between
     * distant content, a long leg legitimately shows nothing for a moment, and
     * settling it away kills the journey.
     */
    internal val navigationActive: Boolean get() = activeNavigations > 0

    /** Mutable progress of one [animateToItemInternal] journey, shared by its phases. */
    private class Journey(
        /**
         * The last waypoint reached. Progress is purely graph-anchored, never
         * re-derived from spatial proximity mid-flight, a spiral's spatially-nearest
         * cell can be graph-far, and re-anchoring there loops the animation.
         */
        var from: Any,
        /**
         * Route plans the journey may still spend, shared across phases. Routes are
         * only re-planned on invalidation, the budget guards against content that
         * mutates faster than the walk can converge.
         */
        var plansLeft: Int,
    )

    /** How one [walkRoutes] phase ended. */
    private enum class WalkEnd {
        /** Settled on the target: the journey is complete. */
        Arrived,

        /**
         * A waypoint on this route has no derivable position, it is not
         * positioning-connected. A shortcut phase hands over to the positioning
         * phase, which routes around such hops by construction, for the positioning
         * phase itself this means the target is unreachable.
         */
        Stalled,

        /** No route, the target vanished, or the plan budget ran out. */
        GaveUp,
    }

    private suspend fun Drag2DScope.animateToItemInternal(
        key: Any,
        animationSpec: AnimationSpec<Offset>
    ) {
        if (!itemByKey.containsKey(key)) return
        val journey = Journey(
            from = nearestKnownKey() ?: LazySurfacePivot,
            plansLeft = totalItemsCount + 4,
        )

        // At most two phases: (1) shortest routes, Free links included, entered only
        // when the destination already has a position, (2) positioning relations
        // only, the one path that can locate an unpositioned target by measuring hop
        // by hop as the viewport approaches. A stalled phase 1 hands over and never
        // goes back: guaranteed progress beats a possible later shortcut.
        if (waypointCenter(key) != null) {
            when (walkRoutes(journey, key, animationSpec, RouteEdges.AllRelations)) {
                WalkEnd.Arrived, WalkEnd.GaveUp -> return
                WalkEnd.Stalled -> LazySurfaceDebug.log {
                    "animateToItem($key): shortcut route stalled, escalating to the positioning chain"
                }
            }
        }
        if (walkRoutes(journey, key, animationSpec, RouteEdges.PositioningOnly) == WalkEnd.Stalled) {
            LazySurfaceDebug.log { "animateToItem($key): stalled at ${journey.from}, $key unreachable" }
        }
    }

    /**
     * Plans and walks routes from [Journey.from] towards [key] over the given
     * [edges] until the target is reached, the phase stalls on an unpositioned
     * waypoint, or nothing more can be done. Progress survives in [journey], so a
     * follow-up phase continues from wherever this one ended.
     */
    private suspend fun Drag2DScope.walkRoutes(
        journey: Journey,
        key: Any,
        animationSpec: AnimationSpec<Offset>,
        edges: RouteEdges,
    ): WalkEnd {
        while (journey.plansLeft-- > 0) {
            if (!itemByKey.containsKey(key)) return WalkEnd.GaveUp // target left the surface
            val route = routeTowards(journey.from, key, edges) ?: run {
                LazySurfaceDebug.log { "animateToItem($key): no route from ${journey.from} ($edges)" }
                return WalkEnd.GaveUp
            }
            LazySurfaceDebug.log { "animateToItem($key): route from ${journey.from} = $route ($edges)" }
            var invalidated = false
            var index = 0
            while (index < route.size) {
                // Fast-forward: a displayed item is verified ground truth, unlike
                // off-screen cache, so aim straight at the farthest on-screen
                // waypoint ahead. Off-screen stretches still walk hop by hop.
                for (ahead in route.size - 1 downTo index + 1) {
                    if (isDisplayed(route[ahead])) {
                        index = ahead
                        break
                    }
                }

                val waypoint = route[index]
                val isFinal = waypoint == key
                val center = waypointCenter(waypoint)
                if (center == null) {
                    // Every positioning-connected item has at least a derived
                    // provisional position, so an aimless waypoint is a Free-only
                    // hop, the positioning phase routes around such hops.
                    LazySurfaceDebug.log { "animateToItem($key): $waypoint has no derivable position ($edges)" }
                    return WalkEnd.Stalled
                }
                // Already standing on this waypoint: flow past it without animating.
                if (!isFinal && (offset - center).getDistance() <= flowThroughDistance()) {
                    journey.from = waypoint
                    index++
                    continue
                }
                val outcome = animateLeg(
                    waypoint = waypoint,
                    animationSpec = animationSpec,
                    flowThroughDistance = if (isFinal) null else flowThroughDistance(),
                )
                when (outcome) {
                    LegOutcome.Arrived, LegOutcome.FlowedThrough -> {
                        if (isFinal && outcome == LegOutcome.Arrived) return WalkEnd.Arrived
                        journey.from = waypoint
                        index++
                    }
                    // A waypoint vanished or its position got corrected by fresh
                    // measurement: re-plan the remaining route from the current spot.
                    LegOutcome.Invalidated -> {
                        invalidated = true
                        break
                    }
                }
            }
            if (!invalidated) return WalkEnd.Arrived // route exhausted
        }
        return WalkEnd.GaveUp // plan budget exhausted
    }

    /** Whether the item with [key] is currently composed and inside the viewport. */
    private fun isDisplayed(key: Any): Boolean {
        if (key === LazySurfacePivot) return false
        val visible = visibleItemsInfo
        for (item in visible) {
            if (item.key == key) return true
        }
        return false
    }

    /**
     * Straight-line travel to [key]'s center. Used for the stranded snap-back, where
     * the viewport hangs in blank space with nothing meaningful to route through,
     * following the neighbour graph from the nearest known item would detour
     * through unrelated content.
     */
    internal suspend fun snapStraightToItem(key: Any) {
        LazySurfaceDebug.log { "snapStraightToItem($key) from offset=$offset" }
        drag(MutatePriority.UserInput) {
            // Re-aim when the leg gets interrupted by live position corrections.
            var legsLeft = 8
            while (legsLeft-- > 0) {
                if (animateLeg(key, spring(), flowThroughDistance = null) == LegOutcome.Arrived) return@drag
                if (lastKnownRects[key] == null) return@drag // target vanished
            }
        }
    }

    /** Which relation edges a route may travel. */
    internal enum class RouteEdges {
        /**
         * Every declared relation, [Free][LazySurfaceNeighbor.Alignment.Free] links
         * included: the shortest way, valid whenever the waypoints have positions.
         */
        AllRelations,

        /**
         * Positioning relations only. Free links carry no geometry, so when a route
         * stalls on an item nothing has positioned yet, only the positioning chain
         * can get there: approaching it measures the chain hop by hop until the
         * target's position exists.
         */
        PositioningOnly,
    }

    /**
     * The shortest route (in relation hops) from [startKey] to [targetKey]: a
     * breadth-first simple path over the undirected neighbour graph. The start is
     * excluded, the last element is always [targetKey],`null` when not connected.
     */
    internal fun routeTowards(
        startKey: Any,
        targetKey: Any,
        edges: RouteEdges = RouteEdges.AllRelations,
    ): List<Any>? {
        if (startKey == targetKey) return listOf(targetKey)

        val parent = MutableScatterMap<Any, Any>()
        val visited = MutableScatterSet<Any>()
        val queue = ArrayDeque<Any>()
        visited.add(startKey)
        queue.add(startKey)
        while (queue.isNotEmpty()) {
            val nodeKey = queue.removeFirst()
            if (nodeKey == targetKey) {
                val route = ArrayList<Any>()
                var current = nodeKey
                while (current != startKey) {
                    route.add(current)
                    current = parent[current] ?: break
                }
                route.reverse()
                return route
            }
            val neighbors = graph[nodeKey]?.let {
                if (edges == RouteEdges.PositioningOnly) it.positioningAdjacent else it.adjacent
            }
            neighbors?.forEach { next ->
                if (visited.add(next)) {
                    parent[next] = nodeKey
                    queue.add(next)
                }
            }
        }
        return null // not connected
    }

    private fun nearestKnownKey(): Any? {
        var nearest: Any? = null
        var nearestDistance = Float.MAX_VALUE
        lastKnownRects.forEach { key, rect ->
            val distance = (rect.center - offset).getDistanceSquared()
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearest = key
            }
        }
        return nearest
    }

    /**
     * Best known aim point for a waypoint: resolved (or last-seen) positions win,
     * then the provisional closure, which places every positioning-connected item
     * with unmeasured extents as zero, systematically short, refined en route.
     * `null` has exactly one meaning: not positioning-connected.
     */
    private fun waypointCenter(waypoint: Any): Offset? =
        if (waypoint === LazySurfacePivot) Offset.Zero
        else lastKnownRects[waypoint]?.center ?: provisionalPositions.positionOf(waypoint)?.center

    /**
     * How close the viewport center needs to get to a waypoint before the animation is
     * allowed to flow onwards to the next one, in surface pixels.
     */
    private fun flowThroughDistance(): Float {
        val viewport = viewportSize
        if (viewport == IntSize.Zero) return 0f
        return min(viewport.width, viewport.height) / (4f * zoom.coerceAtLeast(1e-4f))
    }

    /** Why a [animateLeg] ended. */
    private enum class LegOutcome {
        /** Settled exactly on a still-valid waypoint. */
        Arrived,

        /** Came within the flow-through distance, close enough to chain the next leg. */
        FlowedThrough,

        /** The waypoint vanished or its position got corrected: the route is stale. */
        Invalidated,
    }

    /**
     * Animates the offset towards [waypoint]'s live center.
     *
     * @param flowThroughDistance when non-null, the leg ends early once the viewport is
     * this close to the waypoint, letting the route flow through it without stopping.
     */
    private suspend fun Drag2DScope.animateLeg(
        waypoint: Any,
        animationSpec: AnimationSpec<Offset>,
        flowThroughDistance: Float?,
    ): LegOutcome {
        val aimedCenter = waypointCenter(waypoint) ?: return LegOutcome.Invalidated
        // A degenerate tracker fit must never poison the animation with NaN.
        val tracked = velocityTracker.calculateVelocity()
        val initialVelocity = Offset(
            x = if (tracked.x.isFinite()) tracked.x else 0f,
            y = if (tracked.y.isFinite()) tracked.y else 0f,
        )
        var outcome = LegOutcome.Arrived
        try {
            animate(
                typeConverter = Offset.VectorConverter,
                initialValue = offset,
                targetValue = aimedCenter,
                initialVelocity = initialVelocity,
                animationSpec = animationSpec,
            ) { value, _ ->
                dragBy(value - offset)
                val liveCenter = waypointCenter(waypoint)
                when {
                    // The waypoint vanished or its position got corrected by a fresh
                    // measurement mid-flight: bail out and let the caller re-plan.
                    liveCenter == null || (liveCenter - aimedCenter).getDistance() > 1f -> {
                        outcome = LegOutcome.Invalidated
                        throw RetargetInterrupt()
                    }

                    flowThroughDistance != null &&
                        (offset - liveCenter).getDistance() <= flowThroughDistance -> {
                        outcome = LegOutcome.FlowedThrough
                        throw RetargetInterrupt()
                    }
                }
            }
        } catch (interrupt: RetargetInterrupt) {
            // Control-flow only, the outcome captures the reason.
        }
        return outcome
    }

    // Control flow only, thrown at most once per settle re-aim, the trace is never read.
    private class RetargetInterrupt : RuntimeException()

    companion object {
        /**
         * Moves towards [target] within [min]..[max], but when [current] already sits
         * outside the range only movement back towards it is allowed, never further
         * away. This keeps clamping continuous when the reachable range contracts
         * (content left the viewport or got removed) while the offset is beyond it.
         */
        private fun directionalCoerce(current: Float, target: Float, min: Float, max: Float): Float = when {
            current < min -> target.coerceIn(current, max)
            current > max -> target.coerceIn(min, current)
            else -> target.coerceIn(min, max)
        }

        /** Nearest point to [target] inside the union of [rects], [target] if none. */
        internal fun nearestPointInRects(target: Offset, rects: List<Rect>): Offset {
            if (rects.isEmpty()) return target
            var best = target
            var bestDistance = Float.MAX_VALUE
            for (rect in rects) {
                val candidate = Offset(
                    x = target.x.coerceIn(rect.left, rect.right),
                    y = target.y.coerceIn(rect.top, rect.bottom),
                )
                val distance = (candidate - target).getDistanceSquared()
                if (distance < bestDistance) {
                    if (distance == 0f) return target // already inside the shape
                    bestDistance = distance
                    best = candidate
                }
            }
            return best
        }

        val StateSaver = listSaver<LazySurfaceState, Float>(
            save = { listOf(it.offset.x, it.offset.y, it.zoom) },
            restore = { LazySurfaceState(Offset(it[0], it[1]), it[2]) }
        )
    }
}
