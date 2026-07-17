package com.jasjeet.lazysurface

import androidx.collection.MutableScatterMap
import androidx.collection.ScatterMap
import androidx.collection.MutableScatterSet
import androidx.collection.mutableScatterMapOf
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
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Resolves item positions and measures/places the visible ones.
 *
 * Positions resolve by walking the neighbour graph breadth-first from the pivot. An
 * item is positioned against ALL of its declared relations jointly: it defers while
 * any endpoint might still resolve this pass, and only the final pass drops endpoints
 * that never resolved (see `jointRect` for the per-axis combination), a global
 * relaxation pass then refines all positions together, see [CompiledConstraints.solve].
 * Unknown-size items are composed only when they could fall inside the resolution
 * region, and everything reachable only through items awaiting their first
 * measurement stays unresolved until scrolled or animated towards, that is what
 * keeps the surface lazy.
 *
 * All of this speaks item *keys*, the only index in play is the one LazyLayout's
 * `compose` call demands, translated at that single boundary by
 * [LazySurfaceItemProvider.composeIndexOf].
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyLayoutMeasureScope.measureLazySurface(
    itemProvider: LazySurfaceItemProvider,
    state: LazySurfaceState,
    viewportConstraints: Constraints,
    animationScope: CoroutineScope,
): MeasureResult {
    return measured(LazySurfacePerformance.Phase.Measure) {
        measureLazySurfaceImpl(itemProvider, state, viewportConstraints, animationScope)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyLayoutMeasureScope.measureLazySurfaceImpl(
    itemProvider: LazySurfaceItemProvider,
    state: LazySurfaceState,
    viewportConstraints: Constraints,
    animationScope: CoroutineScope,
): MeasureResult {
    /** Size of the container the surface is measured into, in raw window pixels. */
    val viewportSize = IntSize(
        width = viewportConstraints.maxWidth,
        height = viewportConstraints.maxHeight,
    )
    state.viewportSize = viewportSize

    /**
     * Sides and cross-axis alignments are declared direction-relative (start/end),
     * this is where they resolve to absolute surface geometry, so the whole
     * arrangement mirrors under RTL.
     */
    val isRtl = layoutDirection == LayoutDirection.Rtl

    /** Static descriptions of every registered item, in registration order. */
    val infos = itemProvider.itemInfos

    val itemByKey = itemProvider.itemByKey
    state.updateItems(infos, itemByKey)

    val zoom = state.zoom.coerceAtLeast(MinZoom)

    /** Surface-space point currently at the center of the viewport. */
    val center = state.offset

    /** Half the viewport's extents in surface coordinates, both scale by 1/zoom. */
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
     * What unknown-size probes test against: the resolution region inflated by one
     * more viewport extent per side. A zero-size probe marks the corner an item grows
     * from, not its coverage, so parking an edge item whose probe sits just outside
     * the region would freeze the frontier there for good. The viewport-derived slack
     * is sound for any item up to two viewport extents large, with no dependence on
     * what happens to have been measured.
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

    // The maps built below are freshly allocated every pass on purpose: they escape
    // to the state, where the clamp and routing read the PREVIOUS pass's view between
    // frames, reusing them here would corrupt that published view mid-gesture.

    /** Content rects of every item whose position got resolved this pass, by key. */
    val resolved = MutableScatterMap<Any, Rect>(infos.size)

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

    /**
     * Items awaiting their first measurement: positionable from a resolved neighbour
     * but too far to be worth composing this pass, keyed with their provisional
     * (zero-size) rects. Everything reachable only through them stays unresolved
     * until the viewport moves closer or the approach loop forces them.
     */
    val awaitingMeasure = mutableScatterMapOf<Any, Rect>()

    // Pass-local scratch, reused across passes (cleared here) so steady-state
    // scrolling allocates none of it.
    val scratch = state.measureScratch.also { it.reset() }
    val queue = scratch.queue
    val deferred = scratch.deferred
    val measuredPlaceables = scratch.measuredPlaceables
    val measuredSpecs = scratch.measuredSpecs

    // Seed with the items the pivot can position directly.
    graph[LazySurfacePivot]?.dependents?.let(queue::addAll)

    // An item is positioned against ALL of its constraints jointly: the strict drain
    // (finalPass = false) defers while any endpoint might still resolve, the final
    // pass drops endpoints that never resolved. Items that declared nothing are
    // positioned by the relations others declared against them.
    fun processItem(key: Any, forceMeasure: Boolean, finalPass: Boolean) {
        if (resolved.containsKey(key)) return
        val info = itemByKey[key] ?: return
        val node = graph[key] ?: return

        // Pick the constraint set: the item's own declarations while any endpoint is
        // usable, otherwise the relations others declared against it. Walked in
        // place, no per-item collection is built.
        var edges = node.ownConstraints
        var usable = 0
        for (edge in edges) {
            if (edge.sourceKey === LazySurfacePivot || resolved.containsKey(edge.sourceKey)) {
                usable++
            } else if (!finalPass) {
                // The endpoint might still resolve this pass: wait rather than
                // resolve against a partial constraint set.
                deferred.add(key)
                return
            }
        }
        if (usable == 0) {
            edges = node.fallbackConstraints
            for (edge in edges) {
                if (resolved.containsKey(edge.sourceKey)) usable++
            }
        }
        if (usable == 0) {
            if (!finalPass) deferred.add(key)
            return
        }

        val constraintEdges = edges

        /**
         * The joint placement for a given size, combined per axis: on its own axis a
         * relation states the declared separation, on the cross axis only an
         * alignment preference. Each axis averages the separations when it has any
         * and falls back to alignment preferences otherwise, so a diagonal pair
         * resolves to the corner where its two separations meet instead of their
         * midpoint. Remaining disagreements are the global solver's job, unresolved
         * endpoints (final pass only) skip.
         */
        fun jointRect(size: Size): Rect {
            var mainX = 0f
            var mainY = 0f
            var crossX = 0f
            var crossY = 0f
            var mainXCount = 0
            var mainYCount = 0
            var crossXCount = 0
            var crossYCount = 0
            for (edge in constraintEdges) {
                val sourceRect = if (edge.sourceKey === LazySurfacePivot)
                    Rect.Zero
                else
                    resolved[edge.sourceKey] ?: continue

                val exact = placeItemTopLeft(
                    anchorRect = sourceRect,
                    side = edge.sideOfSource,
                    alignment = edge.alignment,
                    size = size,
                    isRtl = isRtl,
                    relationMargin = edge.margin,
                )
                when (edge.sideOfSource) {
                    null -> { // centered on the anchor: both axes declared
                        mainX += exact.x; mainXCount++
                        mainY += exact.y; mainYCount++
                    }
                    LazySurfaceNeighbor.Side.Start, LazySurfaceNeighbor.Side.End -> {
                        mainX += exact.x; mainXCount++
                        crossY += exact.y; crossYCount++
                    }
                    else -> {
                        mainY += exact.y; mainYCount++
                        crossX += exact.x; crossXCount++
                    }
                }
            }
            val left = if (mainXCount > 0) mainX / mainXCount else crossX / crossXCount
            val top = if (mainYCount > 0) mainY / mainYCount else crossY / crossYCount
            return Rect(Offset(left, top), size)
        }

        /** Last measured size, or `null` before the item has been composed once. */
        val cachedSize = state.cachedSize(key)
        val sizeUnknown = cachedSize == null

        /**
         * Where the item would sit with its last known (or zero) size. A zero-size
         * probe never extends past the true rect, so probe-overlap implies
         * true-overlap, but not the reverse, which is why unknown sizes test
         * against [unknownSizeRect] rather than the raw region.
         */
        val provisionalRect = jointRect(cachedSize?.toSize() ?: Size.Zero)

        val shouldMeasure = forceMeasure ||
                provisionalRect.overlaps(
                    if (sizeUnknown) unknownSizeRect else viewportRect
                )

        val rect: Rect

        /** The measured content, kept only when this item might need placing. */
        var placeables: List<Placeable>? = null
        if (shouldMeasure) {
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
            state.cacheMeasuredSize(key, measuredSize)
            rect = jointRect(Size(measuredSize.width.toFloat(), measuredSize.height.toFloat()))
        } else if (sizeUnknown) {
            // Too far to compose: parked until the viewport comes close (or the
            // approach loop below forces it).
            awaitingMeasure[key] = provisionalRect
            deferred.remove(key)
            return
        } else {
            rect = provisionalRect
        }

        resolved[key] = rect
        deferred.remove(key)
        awaitingMeasure.remove(key)
        if (rect.overlaps(viewportRect)) {
            resolvedInViewport = true
            if (placeables != null) {
                placedItems.add(PlacedSurfaceItem(info, rect, placeables, measuredSpecs[key]))
            }
        }
        graph[key]?.dependents?.let(queue::addAll)
    }

    fun drainQueue() {
        while (queue.isNotEmpty()) {
            processItem(queue.removeFirst(), forceMeasure = false, finalPass = false)
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
        while (madeProgress && deferred.isNotEmpty()) {
            madeProgress = false
            infos.forEach { info ->
                if (deferred.contains(info.key) && !resolved.containsKey(info.key)) {
                    processItem(info.key, forceMeasure = false, finalPass = true)
                    if (resolved.containsKey(info.key)) {
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

    // Approach: when the viewport shows no resolved content (a state restore, an
    // initial offset far from the pivot), walk towards it by force-measuring the
    // nearest awaiting item until content arrives or the per-pass budget runs out,
    // the size cache makes the next pass continue from there.

    /** Force measurements this pass may still spend approaching the viewport. */
    var approachBudget = MaxApproachMeasurementsPerPass
    while (approachBudget-- > 0 && awaitingMeasure.isNotEmpty() && !resolvedInViewport) {
        var nearestKey: Any? = null
        var nearestDistance = Float.MAX_VALUE
        awaitingMeasure.forEach { key, rect ->
            val distance = (rect.center - center).getDistanceSquared()
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestKey = key
            }
        }
        val possibleNearestItemKey = nearestKey ?: break
        awaitingMeasure.remove(possibleNearestItemKey)
        processItem(possibleNearestItemKey, forceMeasure = true, finalPass = true)
        resolveAll()
    }

    // Stranded: every position is known yet nothing lies inside the viewport, the
    // content underneath was removed. Snap preference: the graph-nearest surviving
    // relative of what was displayed (the snap hint), else the first registered
    // resolved item, selection is by nodes, never by spatial distance.
    if (!resolvedInViewport && awaitingMeasure.isEmpty() && resolved.isNotEmpty()) {
        state.strandedNearestKey = state.strandedSnapHint?.takeIf(resolved::containsKey)
            ?: infos.firstOrNull { resolved.containsKey(it.key) }?.key
        LazySurfaceDebug.log {
            "stranded: nothing under viewport at center=$center zoom=$zoom " +
                "resolved=${resolved.size} -> snap target=${state.strandedNearestKey}"
        }
    } else {
        state.strandedNearestKey = null
        // Content is on screen again, the hint has served its purpose.
        if (resolvedInViewport) state.strandedSnapHint = null
    }

    // Refine the joint placements so declared neighbours push apart rather than
    // overlap and alignments blend. Identical inputs skip the sweeps entirely (see
    // [solveMemoized]): scrolling changes nothing in surface space, so scroll frames
    // replay the previous refinement.
    val solverMoved = measured(LazySurfacePerformance.Phase.Solve) {
        solveMemoized(state.solveMemo, resolved, state.relationConstraints, isRtl)
    }
    if (solverMoved) {
        placedItems.forEach { placed ->
            placed.rect = resolved[placed.info.key] ?: placed.rect
        }

        // Visibility was decided against the pre-solve positions but rendering uses
        // the solved ones: an item whose solved rect is on screen while its unsolved
        // rect is not would stay uncomposed and pop in already deep on screen.
        // Compose whatever the solved geometry says is actually visible, now.
        val placedKeys = MutableScatterSet<Any>(placedItems.size)
        placedItems.forEach { placedKeys.add(it.info.key) }
        resolved.forEach { key, rect ->
            if (!rect.overlaps(viewportRect) || placedKeys.contains(key)) return@forEach
            val info = itemByKey[key] ?: return@forEach
            // A measurable may be measured only once per pass: items already measured
            // (ahead of the viewport, but not placed) are reused, everything else is
            // composed fresh here.
            val placeables = measuredPlaceables[key] ?: run {
                val fresh = compose(itemProvider.composeIndexOf(key)).map { measurable ->
                    (measurable.parentData as? LazySurfaceAnimationSpecs)?.let { measuredSpecs[key] = it }
                    measurable.measure(UnboundedConstraints)
                }
                state.cacheMeasuredSize(
                    key,
                    IntSize(
                        width = fresh.maxOfOrNull { it.width } ?: 0,
                        height = fresh.maxOfOrNull { it.height } ?: 0,
                    ),
                )
                fresh
            }
            placedItems.add(PlacedSurfaceItem(info, rect, placeables, measuredSpecs[key]))
        }
    }

    // ---- Item animations (Modifier.animateItem). ------------------------------------
    // Purely visual: the logical rects keep feeding the clamp, the solver and the
    // public state. Reading the animatables here makes their frames re-run the
    // measure pass until everything settles.
    state.passStamp++
    val passStamp = state.passStamp
    placedItems.forEach { placed ->
        val key = placed.info.key
        val specs = placed.animationSpecs
        if (specs == null) {
            state.itemAnimations.remove(key)?.cancel()
            return@forEach
        }
        val target = placed.rect.topLeft
        val animation = state.itemAnimations.getOrPut(key) {
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
                    animation.position.animateTo(target, specs.placementSpec!!)
                }
            }
            placed.displayTopLeft = animation.position.value
        } else {
            // First placement, re-entry, or no placement spec: draw at the target
            // THIS frame, gliding in from a stale position would fly across the
            // viewport. The animatable is aligned only in the background so a later
            // glide starts from the right place, drawing from it here would flash
            // one stale frame, the snap coroutine runs after this pass has rendered.
            if (animation.position.value != target) {
                animation.positionJob?.cancel()
                animation.positionJob = animationScope.launch { animation.position.snapTo(target) }
            }
            placed.displayTopLeft = target
        }
        placed.alpha = animation.alpha.value
    }

    /**
     * Content rects of the resolved items, by key. Their union is the *bounding
     * shape* scrolling gets clamped to, growing as more of the graph resolves.
     */
    val marginBoxes = MutableScatterMap<Any, Rect>(resolved.size)

    /** Bounding box of all resolved rects, informational only, never a clamp. */
    var bounds: Rect? = null
    resolved.forEach { key, rect ->
        marginBoxes[key] = rect
        bounds = bounds?.let {
            Rect(
                left = minOf(it.left, rect.left),
                top = minOf(it.top, rect.top),
                right = maxOf(it.right, rect.right),
                bottom = maxOf(it.bottom, rect.bottom),
            )
        } ?: rect
    }

    // Publish only when something actually moved: the published rects are read by
    // item draw code, so re-publishing identical contents would invalidate and
    // re-record every visible item's layer on every frame of a plain scroll.
    if (!resolved.rectContentEquals(state.lastKnownRects)) {
        state.lastKnownRects = resolved
        state.resolvedRects = resolved.asMap()
    }
    state.resolvedMarginBoxes = marginBoxes
    state.provisionalPositions.update(
        infos = infos,
        graph = graph,
        itemByKey = itemByKey,
        resolved = resolved,
        awaitingMeasure = awaitingMeasure,
        cachedSize = state::cachedSize,
        isRtl = isRtl,
    )
    state.resolvedBounds = bounds
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
    // The offset delta between the last two passes predicts the travel direction,
    // items inside the translated viewport get precomposed and premeasured during
    // idle frame time. Predictions no longer ahead are cancelled.
    val movement = center - state.previousPassCenter
    state.previousPassCenter = center
    if (movement != Offset.Zero) {
        val lookahead = Offset(
            x = (movement.x * PrefetchLookaheadPasses).coerceIn(-2 * halfWidth, 2 * halfWidth),
            y = (movement.y * PrefetchLookaheadPasses).coerceIn(-2 * halfHeight, 2 * halfHeight),
        )
        val prefetchRect = viewportRect.translate(lookahead)

        /** Items predicted to enter the viewport soon, not visible yet. */
        val wanted = MutableScatterSet<Any>(MaxPrefetchedItems)
        var budget = MaxPrefetchedItems
        resolved.forEach { key, rect ->
            if (budget > 0 && rect.overlaps(prefetchRect) && !rect.overlaps(viewportRect)) {
                wanted.add(key)
                budget--
            }
        }
        state.prefetchHandles.removeIf { key, handle ->
            val stale = !wanted.contains(key)
            if (stale) handle.cancel() // no-op if it already ran
            stale
        }
        wanted.forEach { key ->
            if (!state.prefetchHandles.containsKey(key)) {
                state.prefetchHandles[key] = state.prefetchState.schedulePrecompositionAndPremeasure(
                    index = itemProvider.composeIndexOf(key),
                    constraints = UnboundedConstraints,
                )
            }
        }
    }

    /** Window-pixel point items are placed relative to: the middle of the viewport. */
    val viewportCenter = viewportSize.center

    // Rounding each item's viewport-relative position independently makes neighbours
    // cross rounding boundaries on different frames: a ±1px relative shimmer during
    // every scroll. Rounding the shared scroll term ONCE per pass and each item's
    // scaled surface position separately keeps every pair of items a constant
    // integer distance apart, the whole field translates by the same integer.
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
 * Pass-local scratch containers, held on the state and reused across measure passes:
 * cleared at each pass start so steady-state scrolling performs no scratch
 * allocation. Safe because measure passes never overlap.
 */
internal class MeasureScratch {
    /** Work list of item keys whose positioning source just resolved. */
    val queue = ArrayDeque<Any>()

    /**
     * Items waiting for a constraint endpoint that has not resolved *yet*: an item
     * is positioned against ALL of its declared relations together, so it must not
     * resolve while one of them might still arrive this pass.
     */
    val deferred = MutableScatterSet<Any>()

    /**
     * Every placeable list measured this pass, by item key. A measurable may only be
     * measured once per pass, so the post-solve visibility sweep must reuse these for
     * items that were measured ahead of the viewport but not placed.
     */
    val measuredPlaceables = MutableScatterMap<Any, List<Placeable>>()

    /** Animation specs read off each measured item's parent data this pass. */
    val measuredSpecs = MutableScatterMap<Any, LazySurfaceAnimationSpecs>()

    fun reset() {
        queue.clear()
        deferred.clear()
        measuredPlaceables.clear()
        measuredSpecs.clear()
    }
}

/** A resolved, composed item that intersects the viewport, ready to be placed. */
private class PlacedSurfaceItem(
    val info: LazySurfaceItemInfo,
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
 * Pure placement math: the top-left of an item of [size] placed on [side] of
 * [anchorRect] (or centered on it when [side] is `null`), with [relationMargin] as
 * the gap. [side] and, for vertical relations, the cross-axis [alignment] are
 * direction-relative, [isRtl] resolves them to absolute surface geometry. Returns
 * an [Offset] (a value class) so the per-frame resolution can call it for every
 * constraint of every item without allocating, [placeItemRect] wraps it for callers
 * that want the full rect.
 */
internal fun placeItemTopLeft(
    anchorRect: Rect,
    side: LazySurfaceNeighbor.Side?,
    alignment: LazySurfaceNeighbor.Alignment,
    size: Size,
    isRtl: Boolean,
    relationMargin: Float = 0f,
): Offset {
    if (side == null) {
        return Offset(
            x = anchorRect.center.x - size.width / 2f,
            y = anchorRect.center.y - size.height / 2f,
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
                anchorRect.right + relationMargin
            } else {
                anchorRect.left - relationMargin - size.width
            }
            y = when (alignment) {
                LazySurfaceNeighbor.Alignment.Start -> anchorRect.top
                // Free never positions, the Center value is only read by the hard
                // separation math, which ignores this axis.
                LazySurfaceNeighbor.Alignment.Center,
                LazySurfaceNeighbor.Alignment.Free -> anchorRect.center.y - size.height / 2f
                LazySurfaceNeighbor.Alignment.End -> anchorRect.bottom - size.height
            }
        }
        LazySurfaceNeighbor.Side.Top, LazySurfaceNeighbor.Side.Bottom -> {
            y = if (side == LazySurfaceNeighbor.Side.Bottom) {
                anchorRect.bottom + relationMargin
            } else {
                anchorRect.top - relationMargin - size.height
            }
            x = when (alignment) {
                LazySurfaceNeighbor.Alignment.Start ->
                    if (isRtl) anchorRect.right - size.width else anchorRect.left
                LazySurfaceNeighbor.Alignment.Center,
                LazySurfaceNeighbor.Alignment.Free -> anchorRect.center.x - size.width / 2f
                LazySurfaceNeighbor.Alignment.End ->
                    if (isRtl) anchorRect.left else anchorRect.right - size.width
            }
        }
    }
    return Offset(x, y)
}

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
 * Note that fill modifiers are documented no-ops under unbounded constraints, and
 * children requiring bounded constraints (scrollable containers) throw Compose's
 * standard infinity-constraints exception.
 */
private val UnboundedConstraints = Constraints()

/** How many extra viewports on each side of the visible one get resolved ahead of time. */
private const val ResolutionViewports = 1

/** Caps how many forced measurements a single pass may spend, see the approach loop. */
private const val MaxApproachMeasurementsPerPass = 32

/**
 * How many measure passes ahead the movement between two passes is extrapolated when
 * predicting which items to prefetch (the prediction is clamped to two viewports).
 */
private const val PrefetchLookaheadPasses = 8

/** How many prefetch requests may be alive at once. */
private const val MaxPrefetchedItems = 4

/** Floor for the zoom factor in measure math, guarding against division by zero. */
private const val MinZoom = 1e-4f

/** Structural equality for resolved-rect maps, allocation free. */
private fun ScatterMap<Any, Rect>.rectContentEquals(other: ScatterMap<Any, Rect>): Boolean {
    if (size != other.size) return false
    var equal = true
    forEach { key, value -> if (equal && other[key] != value) equal = false }
    return equal
}

/**
 * Last pass's solve, kept on the state. The solver is a pure function of (positions,
 * constraint templates, direction), so a pass deriving the same joint placements as
 * the previous one (every frame of a plain scroll) replays the previous refinement
 * for the cost of two O(n) map walks, sweeps only run when geometry actually changed.
 */
internal class SolveMemo {
    val preSolve = MutableScatterMap<Any, Rect>()
    val postSolve = MutableScatterMap<Any, Rect>()
    var constraints: List<RelationConstraint>? = null
    var isRtl = false
    var moved = false

    /** The templates compiled for the sweep hot path, see [CompiledConstraints]. */
    var compiled: CompiledConstraints? = null
}

/** [CompiledConstraints.solve] behind the [SolveMemo], same contract, same result. */
internal fun solveMemoized(
    memo: SolveMemo,
    resolved: MutableScatterMap<Any, Rect>,
    constraints: List<RelationConstraint>,
    isRtl: Boolean,
): Boolean {
    // Templates are rebuilt only on content change, so identity captures them.
    if (memo.constraints === constraints && memo.isRtl == isRtl &&
        resolved.rectContentEquals(memo.preSolve)
    ) {
        memo.postSolve.forEach { key, value -> resolved[key] = value }
        return memo.moved
    }
    val compiled = memo.compiled?.takeIf { it.source === constraints && it.isRtl == isRtl }
        ?: CompiledConstraints(constraints, isRtl).also { memo.compiled = it }
    memo.preSolve.clear()
    resolved.forEach { key, value -> memo.preSolve[key] = value }
    val moved = compiled.solve(resolved)
    memo.postSolve.clear()
    resolved.forEach { key, value -> memo.postSolve[key] = value }
    memo.constraints = constraints
    memo.isRtl = isRtl
    memo.moved = moved
    return moved
}
