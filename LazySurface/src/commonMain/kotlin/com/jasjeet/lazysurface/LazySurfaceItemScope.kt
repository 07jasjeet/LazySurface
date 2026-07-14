package com.jasjeet.lazysurface

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyScopeMarker
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/**
 * Receiver scope for item content in a [LazySurface].
 *
 * Item content is measured under unbounded constraints, so the regular fill modifiers
 * (`fillMaxWidth` etc.) have no effect. To size an item against the surface's container
 * use [fillParentMaxWidth], [fillParentMaxHeight] or [fillParentMaxSize]. The resulting
 * size is the viewport's size at 1x zoom, keeping the item's surface geometry stable
 * while pinch-zooming.
 */
@Stable
@LazyScopeMarker
interface LazySurfaceItemScope {
    /** Sizes the content's width to [fraction] of the viewport's width (at 1x zoom). */
    fun Modifier.fillParentMaxWidth(fraction: Float = 1f): Modifier

    /** Sizes the content's height to [fraction] of the viewport's height (at 1x zoom). */
    fun Modifier.fillParentMaxHeight(fraction: Float = 1f): Modifier

    /** Sizes the content to [fraction] of the viewport's size (at 1x zoom). */
    fun Modifier.fillParentMaxSize(fraction: Float = 1f): Modifier

    /**
     * Animates this item's movements and appearance. Placement: whenever the item's
     * resolved position changes while it stays on screen, a neighbour resized, the
     * graph healed after a removal, the solver reflowed, the content glides to its
     * new spot instead of teleporting. Appearance: a newly added item fades in.
     *
     * Both are purely visual: scrolling, the scroll clamp and every position in
     * [LazySurfaceState] always work against the item's logical rect. Pass `null` to
     * disable either animation.
     */
    fun Modifier.animateItem(
        placementSpec: FiniteAnimationSpec<Offset>? = spring(
            stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = Offset.VisibilityThreshold,
        ),
        fadeInSpec: FiniteAnimationSpec<Float>? = spring(stiffness = Spring.StiffnessMediumLow),
    ): Modifier
}

@Stable
internal class LazySurfaceItemScopeImpl(
    private val state: LazySurfaceState
) : LazySurfaceItemScope {

    override fun Modifier.fillParentMaxWidth(fraction: Float): Modifier {
        requireValidFraction(fraction)
        return layout { measurable, constraints ->
            val width = (state.viewportSize.width * fraction).roundToInt()
            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
    }

    override fun Modifier.fillParentMaxHeight(fraction: Float): Modifier {
        requireValidFraction(fraction)
        return layout { measurable, constraints ->
            val height = (state.viewportSize.height * fraction).roundToInt()
            val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
    }

    override fun Modifier.fillParentMaxSize(fraction: Float): Modifier {
        requireValidFraction(fraction)
        return layout { measurable, constraints ->
            val width = (state.viewportSize.width * fraction).roundToInt()
            val height = (state.viewportSize.height * fraction).roundToInt()
            val placeable = measurable.measure(
                constraints.copy(
                    minWidth = width, maxWidth = width,
                    minHeight = height, maxHeight = height,
                )
            )
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
    }

    override fun Modifier.animateItem(
        placementSpec: FiniteAnimationSpec<Offset>?,
        fadeInSpec: FiniteAnimationSpec<Float>?,
    ): Modifier = this.then(LazySurfaceAnimationSpecs(placementSpec, fadeInSpec))

    private fun requireValidFraction(fraction: Float) {
        require(fraction in 0f..1f) { "fraction must be within 0f..1f but was $fraction" }
    }
}
