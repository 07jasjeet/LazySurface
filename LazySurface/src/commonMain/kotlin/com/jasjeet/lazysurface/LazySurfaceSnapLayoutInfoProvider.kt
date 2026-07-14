package com.jasjeet.lazysurface

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/**
 * A [SnapLayoutInfoProvider] over a [LazySurfaceState], the surface pager, through
 * the standard androidx snapping API:
 *
 * ```
 * LazySurface(
 *     state = state,
 *     flingBehavior = rememberSnapFlingBehavior(   // androidx.compose.foundation
 *         remember(state) { LazySurfaceSnapLayoutInfoProvider(state) },
 *     ),
 * ) { ... }
 * ```
 *
 * Every released gesture then lands centered on a snap anchor: the fling's natural
 * landing point is projected with the platform decay curve, and the anchor nearest
 * that point wins, strong flings cross to farther anchors, gentle ones reach the
 * adjacent one, and a still release re-centers the nearest. No special-case
 * threshold, all of it falls out of the projection. Deterministic: ties resolve to
 * registration order.
 *
 * Bridging androidx's one-dimensional snapping contract onto a 2D surface: the
 * surface runs the fling behavior on a scalar arc-length scroll scope. This provider
 * chooses the 2D target and publishes its key, every scalar `scrollBy` then advances
 * toward the target's *live* center, so the snap follows position corrections (a
 * provisionally-placed page measuring in mid-snap) instead of a stale straight line.
 *
 * @param anchors which items are snap anchors, the "pages". Defaults to every item.
 */
fun LazySurfaceSnapLayoutInfoProvider(
    state: LazySurfaceState,
    anchors: (LazySurfaceItemInfo) -> Boolean = { true },
): SnapLayoutInfoProvider = object : SnapLayoutInfoProvider {

    override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float {
        // Project the natural landing in 2D: the gesture's direction (stashed by the
        // surface at release) carried by the decay travel androidx already computed.
        val landing = state.offset + state.snapFlingDirection * abs(decayOffset)
        var best: Any? = null
        var bestDistance = Float.MAX_VALUE
        state.itemsInfo.forEach { info ->
            if (!anchors(info)) return@forEach
            val center = state.lastKnownRects[info.key]?.center
                ?: state.provisionalPositions.positionOf(info.key)?.center
                ?: return@forEach
            val distance = (center - landing).getDistanceSquared()
            if (distance < bestDistance) {
                bestDistance = distance
                best = info.key
            }
        }
        state.snapTargetKey = best
        // No approach phase: the snap spring covers the whole (2D) distance.
        return 0f
    }

    override fun calculateSnapOffset(velocity: Float): Float {
        val key = state.snapTargetKey ?: return 0f
        val center = state.lastKnownRects[key]?.center
            ?: state.provisionalPositions.positionOf(key)?.center
            ?: return 0f
        return (center - state.offset).getDistance()
    }
}

/**
 * A [FlingBehavior] that snaps every released gesture onto one of
 * [lazySurfaceState]'s items.
 *
 * ```
 * LazySurface(
 *     state = state,
 *     flingBehavior = rememberSnapFlingBehavior(state),
 *     settleIntoBounds = false,
 * ) { ... }
 * ```
 *
 * Callers should disable [LazySurface]'s `settleIntoBounds`: the settle animation
 * re-centers the viewport into the bounding shape while the snap wants it centered
 * on an anchor, and the two policies fight over where an idle viewport belongs.
 * With snapping, the anchors ARE the resting positions.
 *
 * @param anchors which items are snap anchors, the "pages". Defaults to every item.
 */
@Composable
fun rememberSnapFlingBehavior(
    lazySurfaceState: LazySurfaceState,
    anchors: (LazySurfaceItemInfo) -> Boolean = { true },
): FlingBehavior {
    val currentAnchors by rememberUpdatedState(anchors)
    val snappingLayout = remember(lazySurfaceState) {
        LazySurfaceSnapLayoutInfoProvider(lazySurfaceState) { currentAnchors(it) }
    }
    return rememberSnapFlingBehavior(snappingLayout)
}
