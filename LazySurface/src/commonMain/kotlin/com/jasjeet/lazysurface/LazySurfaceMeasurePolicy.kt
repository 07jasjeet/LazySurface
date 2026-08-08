package com.jasjeet.lazysurface

import androidx.collection.MutableScatterMap
import androidx.collection.ScatterMap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.layout.LazyLayoutMeasurePolicy
import androidx.compose.foundation.lazy.layout.LazyLayoutMeasureScope
import androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope

/**
 * The surface's measure machine: the [LazyLayoutMeasurePolicy] plus every object
 * that exists purely to serve the measure pass (pass scratch, solver memo,
 * measured-size cache, prefetch bookkeeping, item-animation records). Nothing here
 * is snapshot state.
 *
 * Remembered against the state in [LazySurface], so it lives exactly as long as
 * the LazyLayout it measures and its caches die with it. [LazySurfaceState] keeps
 * only what outlives measurement: declarations and their derived topology,
 * published geometry, and gesture/navigation state.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class LazySurfaceMeasurePolicy(
    private val state: LazySurfaceState,
    private val itemProvider: () -> LazySurfaceItemProvider,
    private val animationScope: CoroutineScope,
) : LazyLayoutMeasurePolicy {

    /** The prefetch executor and its live requests, owned by this layout instance. */
    val prefetchState = LazyLayoutPrefetchState()
    val prefetchHandles = MutableScatterMap<Any, LazyLayoutPrefetchState.PrefetchHandle>()

    /** Reusable pass-local key-addressed scratch, see [MeasureScratch]. */
    val measureScratch = MeasureScratch()

    /** Previous pass's solve, replayed when its inputs recur, see [SolveMemo]. */
    val solveMemo = SolveMemo()

    /** Index-addressed per-pass geometry and stamps, see [ResolveScratch]. */
    var resolveScratch = ResolveScratch(0)
        private set

    /**
     * Where the viewport center was on the previous measure pass. The delta to the
     * current center is the movement direction used to predict what to prefetch.
     */
    var previousPassCenter: Offset = Offset.Zero

    /**
     * Monotonic measure-pass counter: placement animations glide only between
     * consecutive placements, so items re-entering the viewport snap instead of
     * flying in from stale positions.
     */
    var passStamp = 0

    /** Live [LazySurfaceItemScope.animateItem] records, keyed by item. */
    val itemAnimations = MutableScatterMap<Any, LazySurfaceItemAnimation>()

    // Content is the only source of an item's size, so nothing is known about an
    // item until its first measurement.
    private val measuredSizes = MutableScatterMap<Any, IntSize>()

    /**
     * The infos list [ensureContent] last derived from, the same snapshot-immune
     * plain-field gate as [LazySurfaceState.updateItems].
     */
    private var registeredInfos: List<LazySurfaceItemInfo>? = null

    fun cachedSize(key: Any): IntSize? = measuredSizes[key]

    fun cacheMeasuredSize(key: Any, size: IntSize) {
        measuredSizes[key] = size
        // Kept mirrored into the index-addressed cache so the resolution hot path
        // reads sizes without hashing the key.
        val index = state.itemIndexOf.getOrDefault(key, PivotIndex)
        if (index >= 0) resolveScratch.setCachedSize(index, size)
    }

    /**
     * Re-derives the content-sized structures when registration changes. Runs after
     * [LazySurfaceState.updateItems] behind its own identity gate, so a fresh
     * policy over an already-registered state still builds its scratch.
     */
    fun ensureContent(infos: List<LazySurfaceItemInfo>, itemByKey: ScatterMap<Any, LazySurfaceItemInfo>) {
        if (registeredInfos === infos) return
        registeredInfos = infos

        // Sizes measured for items that no longer exist must not keep influencing
        // resolution or the bounding box.
        measuredSizes.removeIf { key, _ -> !itemByKey.containsKey(key) }

        // Fresh per-pass arrays for the new index space, with the surviving
        // measured sizes mirrored in by index.
        resolveScratch = ResolveScratch(infos.size).also { scratch ->
            infos.forEachIndexed { i, info ->
                measuredSizes[info.key]?.let { scratch.setCachedSize(i, it) }
            }
        }

        // Removed items take their animation records with them.
        itemAnimations.removeIf { key, animation ->
            val removed = !itemByKey.containsKey(key)
            if (removed) animation.cancel()
            removed
        }
    }

    override fun LazyLayoutMeasureScope.measure(constraints: Constraints): MeasureResult =
        measureLazySurface(
            itemProvider = itemProvider(),
            state = state,
            policy = this@LazySurfaceMeasurePolicy,
            viewportConstraints = constraints,
            animationScope = animationScope,
        )
}
