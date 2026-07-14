package com.jasjeet.lazysurface

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.layout.LazyLayout
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * A lazy layout that renders items on an open 2D plane.
 *
 * Unlike lists and grids there is no index and no strict arrangement: every item is
 * declared relative to its [neighbours][LazySurfaceNeighbor] (or the
 * [surface pivot][LazySurfacePivot]), separated by each relation's own deterministic
 * [margin][LazySurfaceNeighbor.margin]. An item's content decides its own size: it is measured
 * under unbounded constraints, so it must bound itself (fill modifiers have no effect,
 * and scrollable children requiring bounded constraints throw).
 *
 * Positions resolve outward from the pivot as the user scrolls: items whose size isn't
 * known are only composed once they could enter the area around the viewport, and items
 * only reachable beyond them stay unresolved until then. Scrolling is clamped to the
 * bounding shape derived from what is displayed, inflated by [contentPadding] beyond
 * the content, scrolling against its edge plays the
 * overscroll effect provided by the environment
 * ([androidx.compose.foundation.LocalOverscrollFactory]), the platform default, or
 * e.g. [RubberBandOverscrollFactory] for the iOS feel.
 *
 * @param state state object controlling and observing the surface, see
 * [rememberLazySurfaceState].
 * @param zoomRange how far the surface can be pinch-zoomed.
 * @param contentPadding the most blank space allowed to show between each content edge
 * and the matching viewport edge when scrolling reaches the bounding shape, applied
 * per side, regardless of which content sits there. Size it to the app's obstructed
 * regions (top/bottom bars) and content can always be scrolled clear of them, content
 * smaller than the padded viewport stays centered within the unobstructed area.
 * `start`/`end` resolve against the layout direction. `null` keeps the default of half
 * the viewport per side, the content edge may travel as far as the viewport center.
 * `PaddingValues(all)` reproduces the former single-value behavior.
 * @param interactable whether pan/zoom gestures are enabled.
 * @param flingBehavior what a released gesture does with its momentum. `null` (the
 * default) is free decay along the bounding shape. For the surface pager, pass the
 * standard [rememberSnapFlingBehavior] over a [LazySurfaceSnapLayoutInfoProvider]:
 * every release then lands centered on a snap anchor. The behavior runs on a scalar
 * arc-length scroll scope (androidx fling behaviors are one-dimensional): motion
 * starts along the gesture's direction and follows the chosen snap target's live
 * center once the provider picks one.
 * @param settleIntoBounds whether a viewport left outside the bounding shape's
 * allowed range (content removed, resized or contracted underneath it) settles back
 * to the nearest allowed position once idle, animated. The settle always yields to
 * user input. Disable to leave the offset wherever changes stranded it, until the
 * user moves it.
 * @param content DSL registering the items, see [LazySurfaceScope].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LazySurface(
    modifier: Modifier = Modifier,
    state: LazySurfaceState,
    zoomRange: ClosedFloatingPointRange<Float> = 0.5f..2f,
    contentPadding: PaddingValues? = null,
    interactable: Boolean = true,
    settleIntoBounds: Boolean = true,
    flingBehavior: FlingBehavior? = null,
    content: LazySurfaceScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val resolvedPadding = remember(contentPadding, density, layoutDirection) {
        contentPadding?.let { padding ->
            with(density) {
                ResolvedContentPadding(
                    left = padding.calculateLeftPadding(layoutDirection).toPx(),
                    top = padding.calculateTopPadding().toPx(),
                    right = padding.calculateRightPadding(layoutDirection).toPx(),
                    bottom = padding.calculateBottomPadding().toPx(),
                )
            }.also {
                require(it.left >= 0f && it.top >= 0f && it.right >= 0f && it.bottom >= 0f) {
                    "contentPadding must be non-negative, but resolved to $it"
                }
            }
        }
    }
    SideEffect {
        state.contentPaddingPx = resolvedPadding
    }

    val overscrollEffect = rememberOverscrollEffect()
    val itemProviderLambda = rememberLazySurfaceItemProviderLambda(
        state = state,
        content = content
    )

    val session = remember(state) { SurfaceGestureSession() }

    // Read at gesture end through updated state, so changing the behavior never
    // restarts the pointer input (which would cancel an active gesture).
    val currentFlingBehavior by rememberUpdatedState(flingBehavior)

    // Content removed underneath the viewport strands it in verified blank space,
    // snap straight to the graph-nearest surviving relative (nothing to route
    // through out here). Only once idle: the clamp already keeps user motion inside
    // the surviving shape, and a healthy fling or an animateToItem leg legitimately
    // crossing blank space must not be killed mid-flight.
    LaunchedEffect(state) {
        snapshotFlow { state.strandedNearestKey }.collect { key ->
            if (key != null) {
                while (session.movesOffset || state.navigationActive) withFrameNanos { }
                if (state.strandedNearestKey == key) {
                    // Isolated in a child job: a user gesture preempting the drag
                    // mutation cancels the job the mutation runs in, without this
                    // isolation that would be the watcher itself, killed forever.
                    launch { state.snapStraightToItem(key) }.join()
                }
            }
        }
    }

    // A shape contracting or shifting underneath an idle viewport can leave the
    // offset outside the allowed range, the clamp deliberately tolerates that and
    // only blocks moving further out. Settle back animated, at Default priority:
    // user input claims UserInput priority and always preempts the settle.
    LaunchedEffect(state, settleIntoBounds) {
        if (!settleIntoBounds) return@LaunchedEffect
        snapshotFlow { state.outOfBoundsResting() }.collect { target ->
            if (target != null) {
                // Waits out gestures, flings AND animateToItem journeys, a flight
                // crossing empty space must not be settled away mid-leg.
                while (session.movesOffset || state.navigationActive) withFrameNanos { }
                if (state.outOfBoundsResting() != null) {
                    launch { state.settleIntoBounds() }.join()
                }
            }
        }
    }

    LazyLayout(
        modifier = modifier
            .pointerInput(state, interactable, overscrollEffect) {
                if (!interactable) return@pointerInput

                // Fling velocity comes from raw pointer samples, not the surface's
                // offset: the offset yields at most one sample per frame, and a two
                // or three frame flick is too few for the tracker's polynomial fit,
                // which then reports zero velocity and silently kills the fling.
                val pointerTracker = VelocityTracker()

                detectSurfaceTransformGestures(
                    velocityTracker = pointerTracker,
                    onGestureStart = {
                        // A finger down stops any running animation immediately, the
                        // velocity history is dropped with it, so the eventual fling
                        // belongs to this gesture's samples alone.
                        LazySurfaceDebug.log {
                            "gesture start: cancels fling=${session.flingJob?.isActive == true} offset=${state.offset}"
                        }
                        session.gestureActive = true
                        session.flingMotionActive = false
                        session.flingJob?.cancel()
                        session.flingJob = null
                        state.resetVelocityTracking()
                        scope.launch { state.stopScrollAnimations() }
                    },
                    onGestureCancel = { session.gestureActive = false },
                    onGesture = { pan, zoomChange ->
                        val newZoom = (state.zoom * zoomChange).coerceIn(zoomRange)
                        state.zoom = newZoom

                        // The drag routes through the overscroll effect: the portion of
                        // the pan the bounding shape rejects stays unconsumed, and the
                        // effect turns it into the edge feedback.
                        val performPan: (Offset) -> Offset = { available ->
                            state.gesturePan(available, newZoom)
                        }
                        if (overscrollEffect != null) {
                            overscrollEffect.applyToScroll(pan, NestedScrollSource.UserInput, performPan)
                        } else {
                            performPan(pan)
                        }
                    },
                    onGestureEnd = {
                        session.gestureActive = false
                        session.flingMotionActive = true
                        session.flingJob = scope.launch {
                            val zoom = state.zoom
                            val tracked = pointerTracker.calculateVelocity()
                            // Degenerate tracker fits must never leak NaN into the
                            // animations.
                            val pointerVelocity = Velocity(
                                x = tracked.x.finiteOrZero(),
                                y = tracked.y.finiteOrZero(),
                            )
                            LazySurfaceDebug.log {
                                "gesture end: pointer=$pointerVelocity zoom=$zoom offset=${state.offset}"
                            }
                            val behavior = currentFlingBehavior
                            val performFling: suspend (Velocity) -> Velocity = { available ->
                                val leftover = state.clampedDecayFling(
                                    initialVelocity = Offset(-available.x / zoom, -available.y / zoom),
                                    decaySpec = splineBasedDecay(density),
                                    routeDelta = overscrollEffect?.let { effect ->
                                        { surfaceDelta: Offset, dominantX: Boolean ->
                                            // The platform iOS effect is one-dimensional:
                                            // a delta with both components resolves to
                                            // direction UNKNOWN and every spring projection
                                            // collapses to zero, stopping dead at edges.
                                            // Route only the dominant axis through the
                                            // effect, the minor drift applies directly,
                                            // matching that axis's silent wall-glide
                                            // semantics.
                                            val dominant = if (dominantX)
                                                Offset(surfaceDelta.x, 0f)
                                            else
                                                Offset(0f, surfaceDelta.y)
                                            val minor = surfaceDelta - dominant
                                            effect.applyToScroll(
                                                Offset(-dominant.x * zoom, -dominant.y * zoom),
                                                NestedScrollSource.SideEffect,
                                            ) { windowDelta -> state.gesturePan(windowDelta, zoom) }
                                            if (minor != Offset.Zero) {
                                                state.gesturePan(Offset(-minor.x * zoom, -minor.y * zoom), zoom)
                                            }
                                        }
                                    },
                                )
                                // The offset is final here: whatever applyToFling
                                // still runs (the overshoot release) is visual only,
                                // and the out-of-bounds settle may overlap it.
                                session.flingMotionActive = false
                                available - Velocity(-leftover.x * zoom, -leftover.y * zoom)
                            }
                            try {
                                when {
                                    behavior != null -> {
                                        // The behavior replaces only the decay: overshoot
                                        // the drag banked in the overscroll effect must
                                        // still be released, in parallel with the flight,
                                        // or its visual translation freezes and items
                                        // render shifted from their logical rects.
                                        if (overscrollEffect != null) {
                                            launch {
                                                overscrollEffect.applyToFling(Velocity.Zero) { it }
                                            }
                                        }
                                        // androidx fling behaviors are one-dimensional:
                                        // run this one on a scalar arc-length scope whose
                                        // direction starts as the gesture's and follows
                                        // the snap target's live center once a provider
                                        // publishes one (positions correcting mid-snap
                                        // curve the pursuit instead of derailing it).
                                        val surfaceVelocity = Offset(-pointerVelocity.x / zoom, -pointerVelocity.y / zoom)
                                        val speed = surfaceVelocity.getDistance()

                                        state.snapFlingDirection = if (speed > 0f) surfaceVelocity / speed else Offset.Zero
                                        state.snapTargetKey = null

                                        LazySurfaceDebug.log { "fling behavior: speed=$speed" }
                                        state.drag(MutatePriority.UserInput) {
                                            val arcScope = object : ScrollScope {
                                                override fun scrollBy(pixels: Float): Float {
                                                    val axis = state.snapTargetKey?.let { key ->
                                                        val center = state.lastKnownRects[key]?.center
                                                            ?: state.provisionalPositions.positionOf(key)?.center
                                                        center?.minus(state.offset)?.let { delta ->
                                                            val distance = delta.getDistance()
                                                            if (distance > 0f) delta / distance else null
                                                        }
                                                    } ?: state.snapFlingDirection
                                                    dragBy(axis * pixels)
                                                    return pixels
                                                }
                                            }
                                            with(behavior) { arcScope.performFling(speed) }
                                        }
                                    }
                                    overscrollEffect != null -> {
                                        overscrollEffect.applyToFling(pointerVelocity, performFling)
                                    }
                                    else -> {
                                        performFling(pointerVelocity)
                                    }
                                }
                            } finally {
                                session.flingMotionActive = false
                            }
                        }
                    },
                )
            }
            // Clip outside the overscroll translation so the clip window stays fixed
            // at the surface bounds while the effect translates content within it,
            // the reverse order cuts content against the displaced window.
            .clipToBounds()
            .overscroll(overscrollEffect),
        itemProvider = itemProviderLambda,
        prefetchState = state.prefetchState,
        measurePolicy = remember(state) {
            { viewportConstraints ->
                measureLazySurface(
                    itemProvider = itemProviderLambda(),
                    state = state,
                    viewportConstraints = viewportConstraints,
                    animationScope = scope,
                )
            }
        }
    )
}

/**
 * Per-surface gesture bookkeeping shared between the pointer-input handler and the
 * effects around it. Plain fields on purpose, not snapshot state: they are only read
 * from gesture callbacks and frame-polling waits, never from a tracked context.
 */
private class SurfaceGestureSession {
    /** The fling in flight, a new touch cancels it, overscroll release spring included. */
    var flingJob: Job? = null

    var gestureActive = false

    /**
     * True from a gesture's end until its fling stops moving the offset. The fling
     * job's tail (the overscroll release spring) is visual-only, gating on the whole
     * job would serialize follow-up motions behind an animation that no longer
     * touches the offset.
     */
    var flingMotionActive = false

    /** Whether anything is still mutating the offset, visual tails excluded. */
    val movesOffset: Boolean get() = gestureActive || flingMotionActive
}

/**
 * [androidx.compose.foundation.gestures.detectTransformGestures] with the two things it
 * lacks: a gesture-start callback (a landing finger must interrupt running animations)
 * and a gesture-end callback, so the fling fires exactly once per gesture.
 * [onGestureCancel] fires instead of [onGestureEnd] when the gesture ends without ever
 * crossing the touch slop.
 *
 * [velocityTracker] is fed the tracked pointer's raw changes, historical between-frame
 * samples included, so even a two-frame flick yields a real velocity. It resets when
 * the tracked pointer changes, since different pointers must not blend into one fit.
 *
 * Once the surface owns the gesture (touch slop crossed, or a second finger down)
 * every pointer change is consumed, stationary pointers and ups included, children
 * observe that and cancel their pending taps, so item content only ever receives
 * clean, single-finger clicks.
 */
private suspend fun PointerInputScope.detectSurfaceTransformGestures(
    velocityTracker: VelocityTracker,
    onGestureStart: () -> Unit,
    onGestureCancel: () -> Unit,
    onGesture: (pan: Offset, zoom: Float) -> Unit,
    onGestureEnd: () -> Unit,
) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop

        val down = awaitFirstDown(requireUnconsumed = false)
        onGestureStart()
        velocityTracker.resetTracking()
        velocityTracker.addPointerInputChange(down)
        var trackedPointerId = down.id
        var canceled = false
        do {
            val event = awaitPointerEvent()
            // Only consumed movement (a nested scrollable claiming a drag) surrenders
            // the gesture. Consumed downs/ups must not: tap detectors consume those
            // merely to claim a press, and honoring them would let a card's clickable
            // abort the surface's pinch by eating the second finger's down.
            canceled = event.changes.fastAny { it.isConsumed && it.positionChangedIgnoreConsumed() }
            if (!canceled) {
                val trackedChange = event.changes.fastFirstOrNull {
                    it.id == trackedPointerId && it.pressed
                } ?: event.changes.fastFirstOrNull { it.pressed }?.also { replacement ->
                    // The tracked finger lifted: follow another one, from scratch.
                    trackedPointerId = replacement.id
                    velocityTracker.resetTracking()
                }
                trackedChange?.let(velocityTracker::addPointerInputChange)

                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()

                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    pan += panChange
                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = abs(1 - zoom) * centroidSize
                    if (zoomMotion > touchSlop || pan.getDistance() > touchSlop) {
                        pastTouchSlop = true
                    }
                }

                // A second finger makes this the surface's gesture by definition, a
                // pinch must never leak a click to the card underneath, even before
                // the zoom crosses the touch slop.
                val multiTouch = event.changes.count { it.pressed } > 1

                if (pastTouchSlop && (zoomChange != 1f || panChange != Offset.Zero)) {
                    onGesture(panChange, zoomChange)
                }
                if (pastTouchSlop || multiTouch) {
                    // Consume every change, stationary pointers and ups included,
                    // children verify consumption in the Final pass, so this is what
                    // reliably cancels a pending click. Consuming only moved pointers
                    // let a pinch's anchored finger complete a clean tap.
                    event.changes.fastForEach { it.consume() }
                }
            }
        } while (!canceled && event.changes.fastAny { it.pressed })
        if (pastTouchSlop) onGestureEnd() else onGestureCancel()
    }
}

/**
 * Dominant-axis shortfall (surface px per frame) that counts as a wall rejection.
 * Big enough to swallow float noise from the routed window-conversion round trip,
 * far below any real per-frame travel a wall rejects.
 */
private const val FlingRejectionSlopPx = 0.25f

/**
 * Decays the offset inside the bounding shape. The shape clamps each axis
 * independently, and what a rejection means depends on the fling's *dominant* axis
 * (see [flingDominantAxisIsX]): a rejected dominant axis is a wall hit, ending the
 * decay and returning the remaining velocity as leftover for the caller's overscroll
 * bounce, a rejected non-dominant axis is mere cross-drift, absorbed silently so the
 * fling slides along the edge instead of dying on it (no real flick is perfectly
 * straight). The whole fling stops on the dominant rejection because the overscroll
 * contract hands over leftover velocity exactly once, on return.
 *
 * A rejection is trustworthy because the clamp never claims a wall at the resolution
 * frontier: a side with unmeasured reachable items stays open by a viewport and keeps
 * growing as the fling's own measure passes resolve them (see
 * [LazySurfaceState.coerceIntoResolvedShape]). What remains is verified emptiness,
 * worth stopping and bouncing at.
 *
 * @return the surface-space velocity left over when the fling hit the shape's edge on
 * its dominant axis, or [Offset.Zero] when it decayed to rest inside the shape.
 */
internal suspend fun LazySurfaceState.clampedDecayFling(
    initialVelocity: Offset,
    decaySpec: DecayAnimationSpec<Offset>,
    /**
     * Applies one frame's surface-space travel through the overscroll effect, the
     * way androidx scrollables route fling frames (source SideEffect). Effects that
     * key edge feedback off per-frame contact (the iOS platform one) never learn of
     * a wall otherwise, the fling would stop dead instead of handing them the
     * bounce. `null` applies travel directly.
     */
    routeDelta: ((delta: Offset, dominantX: Boolean) -> Unit)? = null,
): Offset {
    val velocity = Offset(initialVelocity.x.finiteOrZero(), initialVelocity.y.finiteOrZero())
    val dominantX = flingDominantAxisIsX(velocity)
        ?: return velocity.also {
            // No room on either axis: all velocity is edge leftover. The decay never
            // runs, so route one synthetic frame of travel anyway, a per-frame-contact
            // effect must see the wall reject something or it swallows the bounce.
            routeDelta?.invoke(velocity * (1f / 60f), abs(velocity.x) >= abs(velocity.y))
            LazySurfaceDebug.log { "fling: no room on either axis v=$velocity ${describeClampForDebug()}" }
        }
    LazySurfaceDebug.log { "fling: start v=$velocity dominantX=$dominantX ${describeClampForDebug()}" }
    var leftover = Offset.Zero
    drag(MutatePriority.UserInput) {
        val animatable = Animatable(offset, Offset.VectorConverter)
        try {
            // The decay is integrated incrementally: each frame applies only that
            // frame's travel, so rejected travel is spent against the wall rather
            // than banked, and a pinned axis follows a receding wall at its current
            // decayed velocity instead of teleporting to its virtual decay position.
            var previousValue = offset
            animatable.animateDecay(velocity, decaySpec) {
                val delta = value - previousValue
                previousValue = value

                // The shape is re-read every frame: it keeps growing while the fling
                // measures new items ahead of it.
                val target = offset + delta
                val achieved = if (routeDelta != null) {
                    routeDelta(delta, dominantX)
                    offset
                } else {
                    val clamped = coerceIntoResolvedShape(target)
                    dragBy(clamped - offset)
                    clamped
                }
                // Slop instead of exact equality: the routed path converts through
                // window pixels and back, which is float-lossy at fractional zooms.
                val rejectedDominant =
                    if (dominantX) abs(achieved.x - target.x) > FlingRejectionSlopPx
                    else abs(achieved.y - target.y) > FlingRejectionSlopPx
                if (rejectedDominant) {
                    // The spline decay reports NaN velocity on an axis that started at
                    // exactly zero, sanitize before it reaches any log or animation.
                    leftover = Offset(
                        this.velocity.x.finiteOrZero(),
                        this.velocity.y.finiteOrZero(),
                    )
                    LazySurfaceDebug.log {
                        "fling: rejected on ${if (dominantX) "x" else "y"} value=$value " +
                            "achieved=$achieved leftover=$leftover ${describeClampForDebug()}"
                    }
                    throw FlingStopped()
                }
            }
        } catch (stopped: FlingStopped) {
            // The leftover velocity is the caller's to bounce with.
        }
    }
    LazySurfaceDebug.log { "fling: ended at offset=$offset leftover=$leftover" }
    return Offset(leftover.x.finiteOrZero(), leftover.y.finiteOrZero())
}

/**
 * Picks the fling's dominant axis, the one whose rejection counts as hitting a wall
 * in [clampedDecayFling]. Dominance normally follows the larger velocity component,
 * but an axis with no room to move in its velocity's direction (probed with a
 * one-pixel step) cannot carry a fling: letting a pinned axis win would kill the
 * whole decay on its first frame instead of gliding along the wall it is pinned to.
 *
 * That courtesy is for flings *along* a wall, not slams *into* it. When the pinned
 * axis carries [WallSlamVelocityFactor] times the free axis's velocity, the gesture
 * is about the wall, gliding the minor remainder would discard nearly all of the
 * fling's energy and delay the edge bounce until the glide rests. Such a slam is a
 * wall hit: no dominant axis, all velocity is edge leftover, the overscroll effect
 * bounces immediately.
 *
 * @return `true`/`false` for x/y dominance, or `null` when the fling has nowhere to
 * go and all of its velocity is edge leftover.
 */
internal fun LazySurfaceState.flingDominantAxisIsX(velocity: Offset): Boolean? {
    val probe = coerceIntoResolvedShape(offset + Offset(velocity.x.sign, velocity.y.sign))
    val xCanMove = abs(probe.x - offset.x) > MinimumFlingRoomPx
    val yCanMove = abs(probe.y - offset.y) > MinimumFlingRoomPx
    return when {
        !xCanMove && !yCanMove -> null
        !yCanMove -> if (abs(velocity.y) >= WallSlamVelocityFactor * abs(velocity.x)) null else true
        !xCanMove -> if (abs(velocity.x) >= WallSlamVelocityFactor * abs(velocity.y)) null else false
        else -> abs(velocity.x) >= abs(velocity.y)
    }
}

/**
 * The least probed movement that counts as scroll room, in surface pixels. The clamp
 * bounds are float arithmetic re-derived every frame, sub-pixel wiggle must not read
 * as room and hand dominance to an axis that is effectively pinned.
 */
private const val MinimumFlingRoomPx = 0.5f

/**
 * How much faster the pinned axis must be than the free one before a fling counts as
 * a wall slam instead of a glide with drift. Small flicks have noisy directions
 * (ratios near 1 must keep gliding), a deliberate slam into a wall measures several
 * times the cross drift.
 */
private const val WallSlamVelocityFactor = 2f

private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f

// Control flow only, thrown at most once per fling, the stack trace is never read.
private class FlingStopped : RuntimeException()
