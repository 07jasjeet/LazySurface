package com.jasjeet.lazysurface

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * An iOS-style rubber-band [OverscrollEffect]: the content translates past the edge
 * with resistance that grows with the overshoot, springs back on release, and flings
 * into the edge dip past it once before settling.
 *
 * Provide it through [androidx.compose.foundation.LocalOverscrollFactory] with
 * [RubberBandOverscrollFactory] to give it to every scrollable in a subtree including
 * [LazySurface] or pass an instance wherever an [OverscrollEffect] is accepted.
 *
 * The overshoot is purely visual: the scrolled state itself stays clamped, this effect
 * only translates the content it decorates.
 */
@OptIn(ExperimentalFoundationApi::class)
class RubberBandOverscrollEffect(
    /**
     * Resistance coefficient of the band: how much of the overshoot becomes visible
     * translation, and the scale of the asymptotic cap. [DefaultRubberBandFactor]
     * (0.55) is the classic iOS scroll-view feel, lower is stiffer.
     */
    private val rubberBandFactor: Float = DefaultRubberBandFactor,
) : OverscrollEffect {

    /** Accumulated raw (un-resisted) overshoot, in pointer-space pixels. */
    private var overshoot by mutableStateOf(Offset.Zero)

    /** Container size, learned by the [node] on measure, the rubber range basis. */
    private var containerWidth = 0f
    private var containerHeight = 0f

    override val isInProgress: Boolean
        get() = overshoot != Offset.Zero

    /**
     * The band's current visual translation in window pixels — the resisted overshoot the
     * [node] applies to the content it decorates; [Offset.Zero] when not overscrolled.
     * Overlays drawn outside the decorated content (a relation-lines layer on the surface's
     * own modifier, say) can read this to move with the band; it is snapshot-backed, so a
     * draw block reading it re-draws as the band stretches and springs back.
     */
    val displacement: Offset
        get() = Offset(
            x = rubber(overshoot.x, containerWidth / 2f),
            y = rubber(overshoot.y, containerHeight / 2f),
        )

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        // Input pulling back towards the shape relaxes the overshoot before any of it
        // scrolls the content.
        val releasedX = release(overshoot.x, delta.x)
        val releasedY = release(overshoot.y, delta.y)
        overshoot += Offset(releasedX, releasedY)
        val afterRelease = delta - Offset(releasedX, releasedY)

        val consumedByScroll = performScroll(afterRelease)
        val unconsumed = afterRelease - consumedByScroll

        // Only a finger stretches the band, programmatic/fling remainders are handled
        // by applyToFling's spring.
        return if (source == NestedScrollSource.UserInput && unconsumed != Offset.Zero) {
            overshoot = (overshoot + unconsumed).finiteOrZero()
            delta // everything visually consumed
        } else {
            Offset(releasedX, releasedY) + consumedByScroll
        }
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        coroutineScope {
            // A band stretched at release springs home IMMEDIATELY, in parallel with
            // the content fling sequencing it after the fling made the band hang
            // frozen whenever the fling ran long. The fling keeps the FULL velocity:
            // scroll rounding leaves microscopic overshoot residue after ordinary
            // drags, and diverting velocity to the spring on any nonzero overshoot
            // would replace every normal decay with the edge bounce. At a real edge
            // the fling rejects within a frame anyway, so nothing is lost.
            val release = if (overshoot != Offset.Zero) {
                launch { springHome(initialVelocity = Offset.Zero) }
            } else {
                null
            }

            val consumed = performFling(velocity)
            val remaining = velocity - consumed
            val kick = Offset(remaining.x, remaining.y).finiteOrZero()
            if (kick != Offset.Zero) {
                // The fling hit the shape's edge: dip past it exactly once.
                release?.cancelAndJoin()
                springHome(kick)
            }
        }
    }

    /** Springs the band back to rest, the critically damped spring dips at most once. */
    private suspend fun springHome(initialVelocity: Offset) {
        if (overshoot == Offset.Zero && initialVelocity == Offset.Zero) return
        animate(
            typeConverter = Offset.VectorConverter,
            initialValue = overshoot.finiteOrZero(),
            targetValue = Offset.Zero,
            initialVelocity = initialVelocity,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        ) { value, _ -> overshoot = value }
        overshoot = Offset.Zero
    }

    override val node: DelegatableNode = TranslationNode()

    /** Translates the decorated content by the resisted overshoot. */
    private inner class TranslationNode : Modifier.Node(), LayoutModifierNode {
        // Applied as a graphics-layer block: layer blocks re-execute on snapshot
        // changes without any measure or layout pass. Reading the overshoot in the
        // placement position instead would only re-apply on relayout and during
        // pure overscroll nothing relayouts (the offset is pinned at the edge), which
        // would freeze the band until an unrelated invalidation flushed it.
        private val applyOvershoot: GraphicsLayerScope.() -> Unit = {
            translationX = rubber(overshoot.x, containerWidth / 2f)
            translationY = rubber(overshoot.y, containerHeight / 2f)
        }

        override fun MeasureScope.measure(
            measurable: Measurable,
            constraints: Constraints,
        ): MeasureResult {
            val placeable = measurable.measure(constraints)
            containerWidth = placeable.width.toFloat()
            containerHeight = placeable.height.toFloat()
            return layout(placeable.width, placeable.height) {
                placeable.placeWithLayer(0, 0, layerBlock = applyOvershoot)
            }
        }
    }

    /** Resistance curve: linear at the edge, asymptotically capped at [range]. */
    private fun rubber(overshoot: Float, range: Float): Float =
        if (range <= 0f) 0f
        else rubberBandFactor * range * overshoot / (range + rubberBandFactor * abs(overshoot))

    companion object {
        /** The classic iOS scroll-view resistance. */
        const val DefaultRubberBandFactor = 0.55f

        private fun Offset.finiteOrZero(): Offset = Offset(
            x = if (x.isFinite()) x else 0f,
            y = if (y.isFinite()) y else 0f,
        )

        /**
         * How much of [delta] is consumed by relaxing an existing [overshoot] pulling
         * the opposite way. Returns the (signed) change to apply to the overshoot.
         */
        private fun release(overshoot: Float, delta: Float): Float = when {
            overshoot == 0f || delta == 0f || sign(overshoot) == sign(delta) -> 0f
            abs(delta) < abs(overshoot) -> delta // partial release
            else -> -overshoot // fully released, the rest of the delta scrolls
        }
    }
}

/**
 * Factory for [RubberBandOverscrollEffect], to be installed via
 * [androidx.compose.foundation.LocalOverscrollFactory]:
 *
 * ```
 * CompositionLocalProvider(LocalOverscrollFactory provides RubberBandOverscrollFactory()) {
 *     LazySurface(...)
 * }
 * ```
 *
 * Equality is by [rubberBandFactor], so recomposing with an equal factory never
 * recreates the effects it produced.
 */
@OptIn(ExperimentalFoundationApi::class)
class RubberBandOverscrollFactory(
    /** See [RubberBandOverscrollEffect.rubberBandFactor]. */
    private val rubberBandFactor: Float = RubberBandOverscrollEffect.DefaultRubberBandFactor,
) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect =
        RubberBandOverscrollEffect(rubberBandFactor)

    override fun equals(other: Any?): Boolean =
        other is RubberBandOverscrollFactory && other.rubberBandFactor == rubberBandFactor

    override fun hashCode(): Int = rubberBandFactor.hashCode()
}
