package com.jasjeet.lazysurface

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Job

/**
 * Parent data attached by [LazySurfaceItemScope.animateItem], read off the measured
 * content at placement time. Unlike relations, animation specs are only needed once
 * an item is composed, which is exactly when parent data exists.
 */
internal class LazySurfaceAnimationSpecs(
    val placementSpec: FiniteAnimationSpec<Offset>?,
    val fadeInSpec: FiniteAnimationSpec<Float>?,
) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@LazySurfaceAnimationSpecs
}

/**
 * The live animation record of one item: its drawn position chasing the logical rect,
 * and its appearance alpha. Purely visual, the logical rect keeps feeding the clamp,
 * the solver and the public state, so scrolling never fights an animation.
 */
internal class LazySurfaceItemAnimation(initialPosition: Offset, fadeIn: Boolean) {
    /** Drawn top-left in surface coordinates, chasing the logical rect's top-left. */
    val position = Animatable(initialPosition, Offset.VectorConverter)

    val alpha = Animatable(if (fadeIn) 0f else 1f)

    /**
     * The measure-pass stamp this item was last placed on. Gliding only happens
     * between consecutive placements: an item re-entering the viewport after an
     * absence snaps to its position instead of flying in from a stale one.
     */
    var lastPlacedStamp = 0

    var positionJob: Job? = null
    var alphaJob: Job? = null

    fun cancel() {
        positionJob?.cancel()
        alphaJob?.cancel()
    }
}
