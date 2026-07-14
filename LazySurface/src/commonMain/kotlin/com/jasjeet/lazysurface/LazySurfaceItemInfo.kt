package com.jasjeet.lazysurface

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.center
import androidx.compose.ui.unit.toOffset

/** Static description of an item registered on the surface. */
data class LazySurfaceItemInfo(
    val key: Any,
    val neighbors: List<LazySurfaceNeighbor>,
    val contentType: Any?,
)

/** An item currently composed and visible in the viewport. */
data class LazySurfaceVisibleItemInfo(
    val key: Any,
    val contentType: Any?,
    /** Resolved content rect in surface coordinates (pivot at origin, unzoomed pixels). */
    val rect: Rect,
    /**
     * Content rect in viewport coordinates: pixels from the viewport's top-left corner,
     * scaled by zoom, the item's on-screen footprint. Compare its edges against
     * [LazySurfaceState.viewportSize] to tell how close the item is to each screen
     * edge, or use `viewportRect.center` for distances from the middle of the screen.
     * Follows the logical position, [LazySurfaceItemScope.animateItem] glides are
     * display-only and never reflected here.
     */
    val viewportRect: Rect,
)

/**
 * Maps a surface-space rect to viewport (screen) coordinates for a viewport whose
 * center shows the surface point [viewportCenterSurface] at [zoom].
 */
internal fun surfaceRectInViewport(
    rect: Rect,
    viewportCenterSurface: Offset,
    zoom: Float,
    viewportSize: IntSize,
): Rect = Rect(
    offset = viewportSize.center.toOffset() + (rect.topLeft - viewportCenterSurface) * zoom,
    size = rect.size * zoom,
)
