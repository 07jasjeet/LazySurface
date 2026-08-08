package com.jasjeet.lazysurface

import androidx.collection.IntList
import androidx.collection.MutableIntList
import androidx.collection.MutableScatterMap
import androidx.collection.MutableScatterSet
import androidx.collection.ObjectIntMap
import androidx.collection.ScatterMap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.layout.LazyLayoutMeasureScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.center
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Resolves item positions and measures/places the visible ones.
 *
 * Positions resolve by walking the neighbour graph breadth-first from the pivot. An
 * item is positioned against ALL of its declared relations jointly: it defers while
 * any endpoint might still resolve this pass, and only the final pass drops endpoints
 * that never resolved (see `jointPlace` for the per-axis combination), a global
 * relaxation pass then refines all positions together, see [CompiledConstraints].
 * Unknown-size items are composed only when they could fall inside the resolution
 * region, and everything reachable only through items awaiting their first
 * measurement stays unresolved until scrolled or animated towards, that is what
 * keeps the surface lazy.
 *
 * The pass runs on a dense index space: registration order indexes every per-pass
 * array (see [ResolveScratch]), edges carry their source's index, and the solver
 * sweeps the same arrays in place, so resolving hashes no keys and allocates no
 * Rects. Keys reappear only at the boundaries: LazyLayout's `compose` call and the
 * published state maps, materialized only when geometry actually changed.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyLayoutMeasureScope.measureLazySurface(
    itemProvider: LazySurfaceItemProvider,
    state: LazySurfaceState,
    policy: LazySurfaceMeasurePolicy,
    viewportConstraints: Constraints,
    animationScope: CoroutineScope,
): MeasureResult {
    return measured(LazySurfacePerformance.Phase.Measure) {
        measureLazySurfaceImpl(itemProvider, state, policy, viewportConstraints, animationScope)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyLayoutMeasureScope.measureLazySurfaceImpl(
    itemProvider: LazySurfaceItemProvider,
    state: LazySurfaceState,
    policy: LazySurfaceMeasurePolicy,
    viewportConstraints: Constraints,
    animationScope: CoroutineScope,
): MeasureResult {
    val viewportSize = IntSize(
        width = viewportConstraints.maxWidth,
        height = viewportConstraints.maxHeight,
    )
    state.viewportSize = viewportSize

    val isRtl = layoutDirection == LayoutDirection.Rtl
    val infos = itemProvider.itemInfos

    val itemByKey = itemProvider.itemByKey
    state.updateItems(infos, itemByKey)
    policy.ensureContent(infos, itemByKey)

    val zoom = state.zoom.coerceAtLeast(MinZoom)
    val center = state.offset

    val halfWidth = viewportSize.width / (2f * zoom)
    val halfHeight = viewportSize.height / (2f * zoom)

    /**
     * The viewport in surface coordinates, centered at [center]. Items whose content
     * rect overlaps it get composed, measured and placed.
     */
    val viewportRect = Rect(
        left = center.x - halfWidth,
        top = center.y - halfHeight,
        right = center.x + halfWidth,
        bottom = center.y + halfHeight,
    )

    /**
     * The viewport inflated by [ResolutionViewports] extra viewports on every side.
     * Unknown-size items inside it are composed just to learn their size, so the graph
     * resolves ahead of the direction the user is scrolling in.
     */
    val resolutionRect = Rect(
        left = center.x - halfWidth * (1 + 2 * ResolutionViewports),
        top = center.y - halfHeight * (1 + 2 * ResolutionViewports),
        right = center.x + halfWidth * (1 + 2 * ResolutionViewports),
        bottom = center.y + halfHeight * (1 + 2 * ResolutionViewports),
    )

    /**
     * What unknown-size probes test against: [resolutionRect] plus one viewport
     * extent per side. A zero-size probe marks the corner an item grows from, not
     * its coverage, parking against the raw region would freeze the frontier for
     * good. The slack is sound for items up to two viewport extents large.
     */
    val unknownSizeRect = Rect(
        left = resolutionRect.left - 2 * halfWidth,
        top = resolutionRect.top - 2 * halfHeight,
        right = resolutionRect.right + 2 * halfWidth,
        bottom = resolutionRect.bottom + 2 * halfHeight,
    )

    /**
     * Rebuilt only on content change (see [LazySurfaceState.updateItems]), relations
     * to removed items arrive already healed. The per-frame pass walks it read-only.
     */
    val graph = state.graph
    val nodeAt = state.nodeAt

    // The whole pass runs on these index-addressed arrays. Key-addressed maps are
    // materialized only at the publish boundary below, and a published view is
    // never mutated afterwards, so readers between frames stay safe.
    val scratch = policy.resolveScratch
    scratch.beginPass()
    val left = scratch.left
    val top = scratch.top
    val width = scratch.width
    val height = scratch.height

    /**
     * Items that ended up composed AND inside the viewport, in BFS discovery order,
     * which is also their draw order. Only these get placed in the layout block.
     */
    val placedItems = mutableListOf<PlacedSurfaceItem>()

    /**
     * Whether any resolved rect overlaps the viewport, gates the approach loop and
     * the stranded-snap detection.
     */
    var resolvedInViewport = false

    // Key-addressed scratch, touched only by items that compose this pass.
    val measureScratch = policy.measureScratch.also { it.reset() }
    val measuredPlaceables = measureScratch.measuredPlaceables
    val measuredSpecs = measureScratch.measuredSpecs

    /** Whether item [index]'s content rect overlaps [region], mirrors [Rect.overlaps]. */
    fun overlaps(index: Int, region: Rect): Boolean =
        left[index] < region.right && region.left < left[index] + width[index] &&
            top[index] < region.bottom && region.top < top[index] + height[index]

    /** Item [index]'s content rect, materialized. Boundary use only. */
    fun rectOf(index: Int) = Rect(
        left[index],
        top[index],
        left[index] + width[index],
        top[index] + height[index],
    )

    // Seed with the items the pivot can position directly.
    state.pivotNode?.dependentIndices?.let(scratch::enqueueAll)

    fun processItem(index: Int, forceMeasure: Boolean, finalPass: Boolean) {
        if (scratch.isResolved(index)) return
        val node = nodeAt[index] ?: return
        val info = infos[index]

        // The item's own declarations while any endpoint is usable, otherwise the
        // relations others declared against it.
        var edges = node.ownConstraints
        var usableSources = 0
        for (edge in edges) {
            if (edge.sourceIndex == PivotIndex || scratch.isResolved(edge.sourceIndex)) {
                usableSources++
            } else if (!finalPass) {
                // Might still resolve this pass: wait for the full constraint set.
                scratch.defer(index)
                return
            }
        }
        if (usableSources == 0) {
            edges = node.fallbackConstraints
            for (edge in edges) {
                if (edge.sourceIndex != PivotIndex && scratch.isResolved(edge.sourceIndex))
                    usableSources++
            }
        }
        if (usableSources == 0) {
            if (!finalPass) scratch.defer(index)
            return
        }

        val constraintEdges = edges

        /**
         * The joint placement for a given size: each axis averages the declared
         * separations when it has any and falls back to cross-axis alignment
         * preferences otherwise, so a diagonal pair resolves to its corner instead
         * of the midpoint. Remaining disagreements are the solver's job.
         */
        fun jointPlace(sizeWidth: Float, sizeHeight: Float): Offset {
            var declaredX = 0f
            var declaredY = 0f
            var alignmentX = 0f
            var alignmentY = 0f
            var declaredXCount = 0
            var declaredYCount = 0
            var alignmentXCount = 0
            var alignmentYCount = 0
            for (edge in constraintEdges) {
                val sourceIndex = edge.sourceIndex
                val anchorLeft: Float
                val anchorTop: Float
                val anchorRight: Float
                val anchorBottom: Float
                if (sourceIndex == PivotIndex) {
                    anchorLeft = 0f; anchorTop = 0f; anchorRight = 0f; anchorBottom = 0f
                } else if (scratch.isResolved(sourceIndex)) {
                    anchorLeft = left[sourceIndex]
                    anchorTop = top[sourceIndex]
                    anchorRight = anchorLeft + width[sourceIndex]
                    anchorBottom = anchorTop + height[sourceIndex]
                } else {
                    continue
                }

                val edgePlacement = placeItemTopLeft(
                    anchorLeft = anchorLeft,
                    anchorTop = anchorTop,
                    anchorRight = anchorRight,
                    anchorBottom = anchorBottom,
                    side = edge.sideOfSource,
                    alignment = edge.alignment,
                    sizeWidth = sizeWidth,
                    sizeHeight = sizeHeight,
                    isRtl = isRtl,
                    relationMargin = edge.margin,
                )
                when (edge.sideOfSource) {
                    null -> { // centered on the anchor: both axes declared
                        declaredX += edgePlacement.x
                        declaredXCount++

                        declaredY += edgePlacement.y
                        declaredYCount++
                    }
                    LazySurfaceNeighbor.Side.Start, LazySurfaceNeighbor.Side.End -> {
                        declaredX += edgePlacement.x
                        declaredXCount++

                        alignmentY += edgePlacement.y
                        alignmentYCount++
                    }
                    else -> {
                        declaredY += edgePlacement.y
                        declaredYCount++

                        alignmentX += edgePlacement.x
                        alignmentXCount++
                    }
                }
            }
            val jointLeft =
                if (declaredXCount > 0) declaredX / declaredXCount else alignmentX / alignmentXCount
            val jointTop =
                if (declaredYCount > 0) declaredY / declaredYCount else alignmentY / alignmentYCount
            return Offset(jointLeft, jointTop)
        }

        val sizeUnknown = scratch.cachedWidth[index] < 0
        val probeWidth = if (sizeUnknown) 0f else scratch.cachedWidth[index].toFloat()
        val probeHeight = if (sizeUnknown) 0f else scratch.cachedHeight[index].toFloat()

        /**
         * Provisional geometry at the last known (or zero) size. A zero-size probe
         * never extends past the true rect, so probe-overlap implies true-overlap
         * but not the reverse, hence the [unknownSizeRect] slack.
         */
        val probe = jointPlace(probeWidth, probeHeight)
        left[index] = probe.x
        top[index] = probe.y
        width[index] = probeWidth
        height[index] = probeHeight

        val shouldMeasure = forceMeasure ||
                overlaps(index, if (sizeUnknown) unknownSizeRect else viewportRect)

        var placeables: List<Placeable>? = null
        if (shouldMeasure) {
            val key = info.key
            placeables = compose(itemProvider.composeIndexOf(key)).map { measurable ->
                (measurable.parentData as? LazySurfaceAnimationSpecs)?.let { measuredSpecs[key] = it }
                measurable.measure(UnboundedConstraints)
            }
            measuredPlaceables[key] = placeables

            // Box measure policy for content
            val measuredSize = IntSize(
                width = placeables.maxOfOrNull { it.width } ?: 0,
                height = placeables.maxOfOrNull { it.height } ?: 0,
            )
            policy.cacheMeasuredSize(key, measuredSize)
            val measuredWidth = measuredSize.width.toFloat()
            val measuredHeight = measuredSize.height.toFloat()
            val measuredTopLeft = jointPlace(measuredWidth, measuredHeight)
            left[index] = measuredTopLeft.x
            top[index] = measuredTopLeft.y
            width[index] = measuredWidth
            height[index] = measuredHeight
        } else if (sizeUnknown) {
            // Too far to compose: parked, the zero-size probe stays as its
            // provisional position until the viewport comes close.
            scratch.park(index)
            scratch.undefer(index)
            return
        }
        // else: the probe at the cached size IS the final geometry, already written.

        scratch.markResolved(index)
        scratch.undefer(index)
        scratch.unpark(index)
        if (overlaps(index, viewportRect)) {
            resolvedInViewport = true
            if (placeables != null) {
                placedItems.add(
                    PlacedSurfaceItem(info, index, rectOf(index), placeables, measuredSpecs[info.key])
                )
                scratch.markPlaced(index)
            }
        }
        scratch.enqueueAll(node.dependentIndices)
    }

    fun drainQueue() {
        while (scratch.hasQueuedItems()) {
            processItem(scratch.dequeue(), forceMeasure = false, finalPass = false)
        }
    }

    /**
     * Drains the queue strictly, then final-passes what was left deferred, walked in
     * registration order for determinism. Each such resolution can complete another
     * item's constraint set, so the strict drain runs again after every one.
     */
    fun resolveAll() {
        drainQueue()
        var madeProgress = true
        while (madeProgress && scratch.deferredCount > 0) {
            madeProgress = false
            for (index in infos.indices) {
                if (scratch.isDeferred(index) && !scratch.isResolved(index)) {
                    processItem(index, forceMeasure = false, finalPass = true)
                    if (scratch.isResolved(index)) {
                        madeProgress = true
                        drainQueue()
                    }
                }
            }
        }
    }

    measured(LazySurfacePerformance.Phase.Resolve) {
        resolveAll()
    }

    // Approach: when the viewport shows no resolved content (state restore, initial
    // offset far from the pivot), force-measure the nearest awaiting item until
    // content arrives or the budget runs out. The size cache carries the progress
    // into the next pass.

    /** Force measurements this pass may still spend approaching the viewport. */
    var approachBudget = MaxApproachMeasurementsPerPass
    while (approachBudget-- > 0 && scratch.awaitingCount > 0 && !resolvedInViewport) {
        var nearestIndex = -1
        var nearestDistance = Float.MAX_VALUE
        for (index in infos.indices) {
            if (!scratch.isAwaiting(index)) continue
            val dx = left[index] + width[index] / 2f - center.x
            val dy = top[index] + height[index] / 2f - center.y
            val distance = dx * dx + dy * dy
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestIndex = index
            }
        }
        if (nearestIndex < 0) break
        scratch.unpark(nearestIndex)
        processItem(nearestIndex, forceMeasure = true, finalPass = true)
        resolveAll()
    }

    // Stranded: every position known yet nothing under the viewport, the content
    // beneath was removed. Snap to the hint (graph-nearest surviving relative of
    // what was displayed), else the first registered resolved item.
    if (!resolvedInViewport && scratch.awaitingCount == 0 && scratch.resolvedCount > 0) {
        val hint = state.strandedSnapHint
        val hintIndex = if (hint != null) state.itemIndexOf.getOrDefault(hint, PivotIndex) else PivotIndex
        state.strandedNearestKey = if (hintIndex >= 0 && scratch.isResolved(hintIndex)) {
            hint
        } else {
            var firstResolved: Any? = null
            for (index in infos.indices) {
                if (scratch.isResolved(index)) {
                    firstResolved = infos[index].key
                    break
                }
            }
            firstResolved
        }
        LazySurfaceDebug.log {
            "stranded: nothing under viewport at center=$center zoom=$zoom " +
                "resolved=${scratch.resolvedCount} -> snap target=${state.strandedNearestKey}"
        }
    } else {
        state.strandedNearestKey = null
        // Content is on screen again, the hint has served its purpose.
        if (resolvedInViewport) state.strandedSnapHint = null
    }

    // Refine the joint placements in place on the pass arrays. Scrolling changes
    // nothing in surface space, so scroll frames replay the previous refinement,
    // see [solveMemoized].
    val solverMoved = measured(LazySurfacePerformance.Phase.Solve) {
        solveMemoized(policy.solveMemo, scratch, state.relationConstraints, state.itemIndexOf, isRtl)
    }
    if (solverMoved) {
        placedItems.forEach { placed -> placed.rect = rectOf(placed.index) }

        // Visibility was decided pre-solve but rendering uses solved geometry:
        // compose whatever the solved positions put on screen, or those items
        // would pop in a frame late, already deep on screen.
        for (index in infos.indices) {
            if (!scratch.isResolved(index) || scratch.isPlaced(index)) continue
            if (!overlaps(index, viewportRect)) continue
            val info = infos[index]
            val key = info.key

            val placeables = measuredPlaceables[key] ?: run {
                val fresh = compose(itemProvider.composeIndexOf(key)).map { measurable ->
                    (measurable.parentData as? LazySurfaceAnimationSpecs)?.let { measuredSpecs[key] = it }
                    measurable.measure(UnboundedConstraints)
                }
                policy.cacheMeasuredSize(
                    key,
                    IntSize(
                        width = fresh.maxOfOrNull { it.width } ?: 0,
                        height = fresh.maxOfOrNull { it.height } ?: 0,
                    ),
                )
                fresh
            }
            placedItems.add(PlacedSurfaceItem(info, index, rectOf(index), placeables, measuredSpecs[key]))
            scratch.markPlaced(index)
        }
    }

    // ---- Item animations (Modifier.animateItem). ------------------------------------
    // Purely visual: logical rects keep feeding the clamp, solver and public state.
    // Reading the animatables here re-runs the pass until they settle.
    policy.passStamp++
    val passStamp = policy.passStamp
    placedItems.forEach { placed ->
        val key = placed.info.key
        val specs = placed.animationSpecs
        if (specs == null) {
            policy.itemAnimations.remove(key)?.cancel()
            return@forEach
        }
        val target = placed.rect.topLeft
        val animation = policy.itemAnimations.getOrPut(key) {
            LazySurfaceItemAnimation(target, fadeIn = specs.fadeInSpec != null).also { created ->
                specs.fadeInSpec?.let { spec ->
                    created.alphaJob = animationScope.launch {
                        created.alpha.animateTo(1f, spec)
                    }
                }
            }
        }
        val placedLastPass = animation.lastPlacedStamp == passStamp - 1
        animation.lastPlacedStamp = passStamp

        val glide = placedLastPass && specs.placementSpec != null
        if (glide) {
            if ((animation.position.targetValue - target).getDistanceSquared() > 0.25f) {
                animation.positionJob = animationScope.launch {
                    animation.position.animateTo(target, specs.placementSpec)
                }
            }
            placed.displayTopLeft = animation.position.value
        } else {
            // First placement, re-entry, or no spec: draw at the target THIS frame,
            // gliding from a stale position would fly across the viewport. The
            // animatable snaps in the background so a later glide starts right.
            if (animation.position.value != target) {
                animation.positionJob?.cancel()
                animation.positionJob = animationScope.launch { animation.position.snapTo(target) }
            }
            placed.displayTopLeft = target
        }
        placed.alpha = animation.alpha.value
    }

    // ---- Publish boundary: the only place pass geometry becomes Rects. --------------
    // Geometry-equal passes keep the previous maps untouched: republishing equal
    // contents would re-record every visible layer each scroll frame, and building
    // them would be the pass's only steady-state allocation.
    if (scratch.publishedDiffers()) {
        val freshResolved = MutableScatterMap<Any, Rect>(scratch.resolvedCount)
        var boundsLeft = Float.POSITIVE_INFINITY
        var boundsTop = Float.POSITIVE_INFINITY
        var boundsRight = Float.NEGATIVE_INFINITY
        var boundsBottom = Float.NEGATIVE_INFINITY
        for (index in infos.indices) {
            if (!scratch.isResolved(index)) continue
            val rect = rectOf(index)
            freshResolved[infos[index].key] = rect
            if (rect.left < boundsLeft) boundsLeft = rect.left
            if (rect.top < boundsTop) boundsTop = rect.top
            if (rect.right > boundsRight) boundsRight = rect.right
            if (rect.bottom > boundsBottom) boundsBottom = rect.bottom
        }
        scratch.snapshotPublished()
        state.lastKnownRects = freshResolved
        state.resolvedRects = freshResolved.asMap()

        state.resolvedBounds = if (freshResolved.isNotEmpty()) {
            Rect(boundsLeft, boundsTop, boundsRight, boundsBottom)
        } else {
            null
        }
    }
    // Content rects double as the margin boxes since margins moved onto relations:
    // the clamp's bounding shape is the same geometry the draw code reads.
    state.resolvedMarginBoxes = state.lastKnownRects

    // Awaiting items with their zero-size provisional rects: the resolution
    // frontier, usually empty or tiny.
    val awaitingMeasure: ScatterMap<Any, Rect> =
        if (scratch.awaitingCount == 0 && state.provisionalPositions.awaitingMeasure.isEmpty()) {
            state.provisionalPositions.awaitingMeasure
        } else {
            val fresh = MutableScatterMap<Any, Rect>(scratch.awaitingCount)
            for (index in infos.indices) {
                if (scratch.isAwaiting(index)) {
                    fresh[infos[index].key] = Rect(Offset(left[index], top[index]), Size.Zero)
                }
            }
            fresh
        }

    state.provisionalPositions.update(
        infos = infos,
        graph = graph,
        itemByKey = itemByKey,
        resolved = state.lastKnownRects,
        awaitingMeasure = awaitingMeasure,
        cachedSize = policy::cachedSize,
        isRtl = isRtl,
    )
    state.visibleItemsInfo = placedItems.map { placed ->
        LazySurfaceVisibleItemInfo(
            key = placed.info.key,
            contentType = placed.info.contentType,
            rect = placed.rect,
            viewportRect = surfaceRectInViewport(placed.rect, center, zoom, viewportSize),
            displayRect = placed.displayTopLeft
                ?.let { Rect(it, placed.rect.size) }
                ?: placed.rect,
        )
    }

    // ---- Prefetch: warm the items the viewport is heading towards. ----------------
    // The last two passes' offset delta predicts travel, items inside the
    // translated viewport precompose during idle time, stale predictions cancel.
    val movement = center - policy.previousPassCenter
    policy.previousPassCenter = center
    if (movement != Offset.Zero) {
        val lookahead = Offset(
            x = (movement.x * PrefetchLookaheadPasses).coerceIn(-2 * halfWidth, 2 * halfWidth),
            y = (movement.y * PrefetchLookaheadPasses).coerceIn(-2 * halfHeight, 2 * halfHeight),
        )
        val prefetchRect = viewportRect.translate(lookahead)

        /** Items predicted to enter the viewport soon, not visible yet. */
        val wanted = MutableScatterSet<Any>(MaxPrefetchedItems)
        var budget = MaxPrefetchedItems
        for (index in infos.indices) {
            if (budget <= 0) break
            if (!scratch.isResolved(index)) continue
            if (overlaps(index, prefetchRect) && !overlaps(index, viewportRect)) {
                wanted.add(infos[index].key)
                budget--
            }
        }
        policy.prefetchHandles.removeIf { key, handle ->
            val stale = !wanted.contains(key)
            if (stale) handle.cancel() // no-op if it already ran
            stale
        }
        wanted.forEach { key ->
            if (!policy.prefetchHandles.containsKey(key)) {
                policy.prefetchHandles[key] = policy.prefetchState.schedulePrecompositionAndPremeasure(
                    index = itemProvider.composeIndexOf(key),
                    constraints = UnboundedConstraints,
                )
            }
        }
    }

    /** Window-pixel point items are placed relative to: the middle of the viewport. */
    val viewportCenter = viewportSize.center

    // Rounding each item's viewport position independently makes neighbours cross
    // rounding boundaries on different frames, a ±1px shimmer while scrolling.
    // Rounding the shared scroll term ONCE keeps item pairs a constant integer
    // distance apart, the whole field translates together.
    val scrollX = (center.x * zoom).roundToInt()
    val scrollY = (center.y * zoom).roundToInt()
    return layout(width = viewportSize.width, height = viewportSize.height) {
        placedItems.forEach { placed ->
            val displayTopLeft = placed.displayTopLeft ?: placed.rect.topLeft
            placed.placeables.forEach { placeable ->
                placeable.placeWithLayer(
                    x = viewportCenter.x + (displayTopLeft.x * zoom).roundToInt() - scrollX,
                    y = viewportCenter.y + (displayTopLeft.y * zoom).roundToInt() - scrollY,
                ) {
                    scaleX = zoom
                    scaleY = zoom
                    transformOrigin = TransformOrigin(0f, 0f)
                    alpha = placed.alpha
                }
            }
        }
    }
}

/**
 * Index-addressed per-pass state, rebuilt only on content change (see
 * [LazySurfaceMeasurePolicy.ensureContent]). Geometry is four flat float arrays,
 * and the membership sets (resolved, awaiting, deferred, placed) are stamp checks
 * against the pass number, so [beginPass] invalidates them all in O(1) and passes
 * allocate nothing here. Stamp wrap-around would need 2^31 passes in one session.
 */
internal class ResolveScratch(val itemCount: Int) {
    /** Content geometry by index, meaningful where a stamp below is current. */
    val left = FloatArray(itemCount)
    val top = FloatArray(itemCount)
    val width = FloatArray(itemCount)
    val height = FloatArray(itemCount)

    /** Cached measured sizes, mirror of the key-addressed cache, -1 = never measured. */
    val cachedWidth = IntArray(itemCount) { -1 }
    val cachedHeight = IntArray(itemCount)

    /** The current pass number, the value every live stamp must equal. */
    var pass = 0
        private set

    /** Read-only outside this class and the solver, mutate via [markResolved]. */
    val resolvedStamp = IntArray(itemCount)
    private val awaitingStamp = IntArray(itemCount)
    private val deferredStamp = IntArray(itemCount)
    private val placedStamp = IntArray(itemCount)

    var resolvedCount = 0
        private set
    var awaitingCount = 0
        private set
    var deferredCount = 0
        private set

    /** The BFS work list: a growing int list drained by a moving head cursor. */
    private val queue = MutableIntList(64)
    private var queueHead = 0

    fun beginPass() {
        pass++
        resolvedCount = 0
        awaitingCount = 0
        deferredCount = 0
        queue.clear()
        queueHead = 0
    }

    fun isResolved(index: Int) = resolvedStamp[index] == pass
    fun markResolved(index: Int) {
        if (resolvedStamp[index] != pass) {
            resolvedStamp[index] = pass
            resolvedCount++
        }
    }

    fun isAwaiting(index: Int) = awaitingStamp[index] == pass
    fun park(index: Int) {
        if (awaitingStamp[index] != pass) {
            awaitingStamp[index] = pass
            awaitingCount++
        }
    }

    fun unpark(index: Int) {
        if (awaitingStamp[index] == pass) {
            awaitingStamp[index] = 0
            awaitingCount--
        }
    }

    fun isDeferred(index: Int) = deferredStamp[index] == pass
    fun defer(index: Int) {
        if (deferredStamp[index] != pass) {
            deferredStamp[index] = pass
            deferredCount++
        }
    }

    fun undefer(index: Int) {
        if (deferredStamp[index] == pass) {
            deferredStamp[index] = 0
            deferredCount--
        }
    }

    fun isPlaced(index: Int) = placedStamp[index] == pass
    fun markPlaced(index: Int) {
        placedStamp[index] = pass
    }

    fun enqueueAll(indices: IntList) {
        for (i in 0 until indices.size) queue.add(indices[i])
    }

    fun hasQueuedItems() = queueHead < queue.size
    fun dequeue(): Int = queue[queueHead++]

    fun setCachedSize(index: Int, size: IntSize) {
        cachedWidth[index] = size.width
        cachedHeight[index] = size.height
    }

    // Snapshot of what the published state maps hold, the publish boundary's
    // change gate.
    private val publishedLeft = FloatArray(itemCount)
    private val publishedTop = FloatArray(itemCount)
    private val publishedWidth = FloatArray(itemCount)
    private val publishedHeight = FloatArray(itemCount)
    private val publishedResolved = BooleanArray(itemCount)
    private var publishedValid = false

    /** Whether this pass's resolved geometry differs from the published snapshot. */
    fun publishedDiffers(): Boolean {
        if (!publishedValid) return true
        for (i in 0 until itemCount) {
            val resolved = resolvedStamp[i] == pass
            if (resolved != publishedResolved[i]) return true
            if (resolved &&
                (left[i] != publishedLeft[i] || top[i] != publishedTop[i] ||
                    width[i] != publishedWidth[i] || height[i] != publishedHeight[i])
            ) {
                return true
            }
        }
        return false
    }

    fun snapshotPublished() {
        left.copyInto(publishedLeft)
        top.copyInto(publishedTop)
        width.copyInto(publishedWidth)
        height.copyInto(publishedHeight)
        for (i in 0 until itemCount) publishedResolved[i] = resolvedStamp[i] == pass
        publishedValid = true
    }
}

/**
 * Key-addressed pass scratch, reused across passes (measure passes never overlap).
 * Only composed items touch these.
 */
internal class MeasureScratch {
    /**
     * Placeables measured this pass, by key. A measurable may only be measured
     * once per pass, so the post-solve visibility sweep reuses these.
     */
    val measuredPlaceables = MutableScatterMap<Any, List<Placeable>>()

    /** Animation specs read off each measured item's parent data this pass. */
    val measuredSpecs = MutableScatterMap<Any, LazySurfaceAnimationSpecs>()

    fun reset() {
        measuredPlaceables.clear()
        measuredSpecs.clear()
    }
}

/** A resolved, composed item that intersects the viewport, ready to be placed. */
private class PlacedSurfaceItem(
    val info: LazySurfaceItemInfo,
    /** The item's registration index, its slot in the pass arrays. */
    val index: Int,
    /** Content rect, mutable because the constraint pass may refine it. */
    var rect: Rect,
    val placeables: List<Placeable>,
    /** Parent data from Modifier.animateItem, when the item opted in. */
    val animationSpecs: LazySurfaceAnimationSpecs?,
) {
    /** Drawn top-left while a placement animation is in flight, null = logical. */
    var displayTopLeft: Offset? = null

    /** Appearance alpha from the item's animation record. */
    var alpha: Float = 1f
}

/**
 * Pure placement math: the top-left of a `sizeWidth x sizeHeight` item placed on
 * [side] of the anchor edges (centered on them when [side] is null), with
 * [relationMargin] as the gap. [isRtl] resolves direction-relative sides and
 * alignments to absolute geometry.
 */
internal fun placeItemTopLeft(
    anchorLeft: Float,
    anchorTop: Float,
    anchorRight: Float,
    anchorBottom: Float,
    side: LazySurfaceNeighbor.Side?,
    alignment: LazySurfaceNeighbor.Alignment,
    sizeWidth: Float,
    sizeHeight: Float,
    isRtl: Boolean,
    relationMargin: Float,
): Offset {
    val anchorCenterX = anchorLeft + (anchorRight - anchorLeft) / 2f
    val anchorCenterY = anchorTop + (anchorBottom - anchorTop) / 2f
    if (side == null) {
        return Offset(
            x = anchorCenterX - sizeWidth / 2f,
            y = anchorCenterY - sizeHeight / 2f,
        )
    }

    val x: Float
    val y: Float
    when (side) {
        LazySurfaceNeighbor.Side.Start, LazySurfaceNeighbor.Side.End -> {
            // The relation's margin is the gap, which absolute side it opens on
            // depends on the layout direction.
            val onAbsoluteRight = (side == LazySurfaceNeighbor.Side.End) != isRtl
            x = if (onAbsoluteRight) {
                anchorRight + relationMargin
            } else {
                anchorLeft - relationMargin - sizeWidth
            }
            y = when (alignment) {
                LazySurfaceNeighbor.Alignment.Start -> anchorTop
                // Free never positions, the Center value is only read by the hard
                // separation math, which ignores this axis.
                LazySurfaceNeighbor.Alignment.Center,
                LazySurfaceNeighbor.Alignment.Free -> anchorCenterY - sizeHeight / 2f
                LazySurfaceNeighbor.Alignment.End -> anchorBottom - sizeHeight
            }
        }
        LazySurfaceNeighbor.Side.Top, LazySurfaceNeighbor.Side.Bottom -> {
            y = if (side == LazySurfaceNeighbor.Side.Bottom) {
                anchorBottom + relationMargin
            } else {
                anchorTop - relationMargin - sizeHeight
            }
            x = when (alignment) {
                LazySurfaceNeighbor.Alignment.Start ->
                    if (isRtl) anchorRight - sizeWidth else anchorLeft
                LazySurfaceNeighbor.Alignment.Center,
                LazySurfaceNeighbor.Alignment.Free -> anchorCenterX - sizeWidth / 2f
                LazySurfaceNeighbor.Alignment.End ->
                    if (isRtl) anchorLeft else anchorRight - sizeWidth
            }
        }
    }
    return Offset(x, y)
}

/** The primitive [placeItemTopLeft] for callers that hold an anchor [Rect]. */
internal fun placeItemTopLeft(
    anchorRect: Rect,
    side: LazySurfaceNeighbor.Side?,
    alignment: LazySurfaceNeighbor.Alignment,
    size: Size,
    isRtl: Boolean,
    relationMargin: Float = 0f,
): Offset = placeItemTopLeft(
    anchorLeft = anchorRect.left,
    anchorTop = anchorRect.top,
    anchorRight = anchorRect.right,
    anchorBottom = anchorRect.bottom,
    side = side,
    alignment = alignment,
    sizeWidth = size.width,
    sizeHeight = size.height,
    isRtl = isRtl,
    relationMargin = relationMargin,
)

/** [placeItemTopLeft] as a full content rect, for the non-hot-path callers. */
internal fun placeItemRect(
    anchorRect: Rect,
    side: LazySurfaceNeighbor.Side?,
    alignment: LazySurfaceNeighbor.Alignment,
    size: Size,
    isRtl: Boolean,
    relationMargin: Float = 0f,
): Rect = Rect(
    offset = placeItemTopLeft(anchorRect, side, alignment, size, isRtl, relationMargin),
    size = size,
)

/**
 * Items are measured with no bounds at all, their content is what defines their size.
 */
private val UnboundedConstraints = Constraints()

/** How many extra viewports on each side of the visible one get resolved ahead of time. */
private const val ResolutionViewports = 1


private const val MaxApproachMeasurementsPerPass = 32

/**
 * How many measure passes ahead the movement between two passes is extrapolated when
 * predicting which items to prefetch (the prediction is clamped to two viewports).
 */
private const val PrefetchLookaheadPasses = 8


private const val MaxPrefetchedItems = 4


private const val MinZoom = 1e-4f

/**
 * Last pass's solve. The solver is a pure function of (positions, templates,
 * direction), so a pass deriving the same joint placements as the previous one
 * (every frame of a plain scroll) replays the refinement for two O(n) array walks.
 */
internal class SolveMemo {
    var constraints: List<RelationConstraint>? = null
    var isRtl = false
    var moved = false
    var valid = false

    /** Which indices were resolved when the memo was taken, pre == post. */
    var resolvedFlags = BooleanArray(0)
        private set

    /*
     * pre*: resolved slot's geometry as resolution derived it, snapshotted before
     * the solve. A pass matching this snapshot (same resolved set, same templates,
     * same direction) skips the sweeps entirely.
     */

    var preLeft = FloatArray(0)
        private set
    var preTop = FloatArray(0)
        private set
    var preWidth = FloatArray(0)
        private set
    var preHeight = FloatArray(0)
        private set

    /*
     * post*: the same slots after the solve, replayed into the scratch on a memo
     * hit. Resolved slots only, awaiting items keep their provisional geometry.
     */

    var postLeft = FloatArray(0)
        private set
    var postTop = FloatArray(0)
        private set
    var postWidth = FloatArray(0)
        private set
    var postHeight = FloatArray(0)
        private set

    /** The templates compiled for the sweep hot path, see [CompiledConstraints]. */
    var compiled: CompiledConstraints? = null

    fun ensureCapacity(itemCount: Int) {
        if (resolvedFlags.size == itemCount)
            return

        resolvedFlags = BooleanArray(itemCount)
        preLeft = FloatArray(itemCount)
        preTop = FloatArray(itemCount)
        preWidth = FloatArray(itemCount)
        preHeight = FloatArray(itemCount)
        postLeft = FloatArray(itemCount)
        postTop = FloatArray(itemCount)
        postWidth = FloatArray(itemCount)
        postHeight = FloatArray(itemCount)
        valid = false
    }
}

/**
 * [CompiledConstraints.solveInto] behind the [SolveMemo], same contract, same
 * result, in place on [scratch]'s arrays. Only the resolved slots are snapshotted
 * and replayed: awaiting items keep their provisional geometry either way.
 */
internal fun solveMemoized(
    memo: SolveMemo,
    scratch: ResolveScratch,
    constraints: List<RelationConstraint>,
    itemIndexOf: ObjectIntMap<Any>,
    isRtl: Boolean,
): Boolean {
    val itemCount = scratch.itemCount
    memo.ensureCapacity(itemCount)
    val left = scratch.left
    val top = scratch.top
    val width = scratch.width
    val height = scratch.height

    // Templates are rebuilt only on content change, so identity captures them.
    if (memo.valid && memo.constraints === constraints && memo.isRtl == isRtl) {
        var inputsMatch = true
        for (i in 0 until itemCount) {
            val resolved = scratch.isResolved(i)
            if (resolved != memo.resolvedFlags[i]) {
                inputsMatch = false
                break
            }
            if (resolved &&
                (left[i] != memo.preLeft[i] || top[i] != memo.preTop[i] ||
                    width[i] != memo.preWidth[i] || height[i] != memo.preHeight[i])
            ) {
                inputsMatch = false
                break
            }
        }
        if (inputsMatch) {
            for (i in 0 until itemCount) {
                if (memo.resolvedFlags[i]) {
                    left[i] = memo.postLeft[i]
                    top[i] = memo.postTop[i]
                    width[i] = memo.postWidth[i]
                    height[i] = memo.postHeight[i]
                }
            }
            return memo.moved
        }
    }

    val compiled = memo.compiled?.takeIf { it.source === constraints && it.isRtl == isRtl }
        ?: CompiledConstraints(constraints, isRtl, itemIndexOf, itemCount).also { memo.compiled = it }
    for (i in 0 until itemCount) {
        val resolved = scratch.isResolved(i)
        memo.resolvedFlags[i] = resolved
        if (resolved) {
            memo.preLeft[i] = left[i]
            memo.preTop[i] = top[i]
            memo.preWidth[i] = width[i]
            memo.preHeight[i] = height[i]
        }
    }
    val moved = compiled.solveInto(left, top, width, height, scratch.resolvedStamp, scratch.pass)
    for (i in 0 until itemCount) {
        if (memo.resolvedFlags[i]) {
            memo.postLeft[i] = left[i]
            memo.postTop[i] = top[i]
            memo.postWidth[i] = width[i]
            memo.postHeight[i] = height[i]
        }
    }
    memo.constraints = constraints
    memo.isRtl = isRtl
    memo.moved = moved
    memo.valid = true
    return moved
}
