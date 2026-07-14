package com.jasjeet.lazysurface.demo

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import com.jasjeet.lazysurface.LazySurfacePivot
import com.jasjeet.lazysurface.LazySurfaceNeighbor
import com.jasjeet.lazysurface.LazySurfaceState
import kotlin.math.abs
import kotlin.math.min

/**
 * Draws this item's declared relations as lines to its neighbours, spanning the gap
 * between the two cards. Free relations draw dotted, everything else solid.
 *
 * The drawing rides on the item itself: items are placed with a top-left scaled
 * layer, so item-local pixels ARE surface pixels and a line drawn past the item's
 * own bounds pans and zooms with the surface for free (nothing clips individual
 * items, only the surface clips at the viewport). Each line is drawn once, by its
 * declaring side. Neighbours whose position isn't resolved yet (unmeasured, far
 * away) contribute no line until the surface learns where they are.
 *
 * Chain this before the card's background so the stubs under the card stay hidden.
 */
@Composable
fun Modifier.relationLines(
    state: LazySurfaceState,
    key: Any,
    show: Boolean,
): Modifier {
    if (!show) return this
    val color = MaterialTheme.colorScheme.outline
    // The declared relations of this item, registration-order lookup, cached until
    // the content itself changes.
    val relations by remember(state, key) {
        derivedStateOf { state.itemsInfo.firstOrNull { it.key == key }?.neighbors.orEmpty() }
    }
    return drawBehind {
        // resolvedRects is snapshot state: reading it here re-draws the lines
        // whenever a pass moves something, without recomposing the item.
        val rects = state.resolvedRects
        val own = rects[key] ?: return@drawBehind
        val dotted = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
        relations.forEach { relation ->
            if (relation.key === LazySurfacePivot) return@forEach
            val other = rects[relation.key] ?: return@forEach
            gapSegment(own, other)?.let { (start, end) ->
                drawLine(
                    color = color,
                    start = start - own.topLeft,
                    end = end - own.topLeft,
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = if (relation.alignment == LazySurfaceNeighbor.Alignment.Free) dotted else null,
                )
            }
        }
    }
}

/**
 * The part of the center-to-center segment lying between the two rects, in surface
 * coordinates, from where it exits [own] to where it enters [other]. `null` when
 * the rects touch or overlap (no gap to draw in) or share a center.
 */
private fun gapSegment(own: Rect, other: Rect): Pair<Offset, Offset>? {
    val dir = other.center - own.center
    val exitOwn = exitFraction(own, dir) ?: return null
    val exitOther = exitFraction(other, dir) ?: return null
    val start = exitOwn
    val end = 1f - exitOther
    if (start >= end) return null
    return own.center + dir * start to own.center + dir * end
}

/** Fraction of [dir] at which a segment from the rect's center crosses its edge. */
private fun exitFraction(rect: Rect, dir: Offset): Float? {
    val tx = if (dir.x != 0f) (rect.width / 2f) / abs(dir.x) else Float.POSITIVE_INFINITY
    val ty = if (dir.y != 0f) (rect.height / 2f) / abs(dir.y) else Float.POSITIVE_INFINITY
    val t = min(tx, ty)
    return if (t == Float.POSITIVE_INFINITY) null else t
}
