package com.jasjeet.lazysurface

/**
 * The root of the surface: a zero-size point at the origin with zero margins.
 *
 * It is not an item, it is what the neighbour graph is rooted at. Use it as a
 * neighbour of items that sit relative to the surface itself rather than to another
 * item, e.g. `LazySurfaceNeighbor.endOf(LazySurfacePivot)` ("I sit at the pivot's end").
 */
data object LazySurfacePivot

/**
 * A spatial relation between the declaring item and one of its neighbours, phrased from
 * the declaring item's own position: *where do I sit relative to the neighbour?*
 *
 * Every relation works in both directions. Declaring `startOf("b")` on item `"a"` ("a
 * sits at b's start") states the same fact as declaring `endOf("a")` on item `"b"`,
 * whichever of the two items resolves its position first positions the other across
 * the relation. Items therefore may declare no relations at all (letting neighbours
 * place them), one, or several.
 *
 * With several relations an item is positioned against **all of them jointly**: it
 * waits while any endpoint may still resolve this pass, drops the endpoints that never
 * do (too far, unreachable) for the frame, and takes the least-squares position of the
 * rest. A global constraint pass then refines every position together, along each
 * relation's axis the declared gap is a hard minimum (declared neighbours never
 * overlap, resizes push room open) while gap exactness and cross-axis alignment are
 * soft preferences that blend under conflict. Relations also serve as links for
 * navigation ([LazySurfaceState.animateToItem] routes) and for the scrollable
 * bounding shape.
 *
 * When a relation's target is removed from the content, the relation *heals*: it
 * retargets to the removed item's nearest surviving anchor (chasing through chains of
 * removals), keeping the declared side and alignment, a removed chain link closes
 * the gap instead of orphaning everything declared behind it. Relations that nothing
 * survives to splice to are dropped, and a re-declared key always wins back its
 * original relations.
 *
 * @property key the neighbour's item key, or [LazySurfacePivot].
 * @property side which side of the declaring item the neighbour sits on (the inverse
 * of the factory's phrasing: `below(key)` records the neighbour on this item's Top),
 * `null` means
 * the declaring item is centered on the neighbour (only used by [AtPivot]).
 * @property alignment cross-axis alignment between the two content boxes.
 * @property margin the declared gap between the two content boxes along the relation's
 * axis, in px. It belongs to THIS relation alone and leaks into no other, two items
 * may sit a large margin apart while a third hugs one of them on the very same side.
 * This is deliberately a margin and not padding inside the item's composable: the
 * margin is *not* part of the composed content, so the surface can defer composing an
 * item until its content box (not its spacing) would actually enter the viewport.
 */
@ConsistentCopyVisibility
data class LazySurfaceNeighbor internal constructor(
    val key: Any,
    val side: Side?,
    val alignment: Alignment,
    val margin: Float = 0f,
) {
    /**
     * The side of the declaring item a neighbour sits on. [Start] and [End] are
     * direction-relative: Start resolves to the left in left-to-right layouts and to
     * the right in right-to-left ones, End the other way around, the whole
     * arrangement mirrors horizontally when the layout direction reverses.
     */
    enum class Side { Start, Top, End, Bottom }

    /**
     * How the two content boxes line up on the axis *across* the relation: vertically
     * for horizontal relations ([Side.Start]/[Side.End]), horizontally for vertical
     * ones. Every alignment is symmetric, which is what allows a relation to position
     * either of its two items.
     */
    enum class Alignment {
        /**
         * Leading edges aligned. For horizontal relations the tops, for vertical
         * relations the start edges resolved against the layout direction (left in
         * left-to-right layouts, right in right-to-left ones).
         */
        Start,

        /** Centers aligned on the cross axis. The default. */
        Center,

        /**
         * Trailing edges aligned. For horizontal relations the bottoms, for vertical
         * relations the end edges resolved against the layout direction (right in
         * left-to-right layouts, left in right-to-left ones).
         */
        End,

        /**
         * No geometry preference at all: the relation declares pure adjacency. It is
         * a routing edge for [LazySurfaceState.animateToItem], a link for the
         * scrollable bounding shape, and a hard "never overlap" guarantee: only when
         * the two content boxes truly intersect are they pushed apart, along the
         * relation's axis toward the declared side. It neither aligns the items, nor
         * pulls them to the declared gap, nor positions them: an item whose relations
         * are all Free is placed only by the relations other items declare against
         * it. Use it to link content that is laid out independently, e.g. far-apart
         * sections that navigation should route between directly.
         *
         * Rule, enforced at registration: every item still needs at least one
         * positioning relation (declared on it or against it). Free is for extra
         * edges, never the only edge. Content whose item is connected solely by
         * Free relations throws, in the same spirit as the duplicate-key check.
         */
        Free,
    }

    companion object {
        /** The declaring item is centered on the [pivot][LazySurfacePivot] (the origin). */
        val AtPivot = LazySurfaceNeighbor(key = LazySurfacePivot, side = null, alignment = Alignment.Center)

        /** This item sits on top of [key]. */
        fun above(key: Any, alignment: Alignment = Alignment.Center) =
            LazySurfaceNeighbor(key, Side.Bottom, alignment)

        /** This item sits below [key]. */
        fun below(key: Any, alignment: Alignment = Alignment.Center) =
            LazySurfaceNeighbor(key, Side.Top, alignment)

        /** This item sits at the start side of [key] (left of it in LTR). */
        fun startOf(key: Any, alignment: Alignment = Alignment.Center) =
            LazySurfaceNeighbor(key, Side.End, alignment)

        /** This item sits at the end side of [key] (right of it in LTR). */
        fun endOf(key: Any, alignment: Alignment = Alignment.Center) =
            LazySurfaceNeighbor(key, Side.Start, alignment)
    }
}

internal fun LazySurfaceNeighbor.Side.opposite(): LazySurfaceNeighbor.Side = when (this) {
    LazySurfaceNeighbor.Side.Start -> LazySurfaceNeighbor.Side.End
    LazySurfaceNeighbor.Side.End -> LazySurfaceNeighbor.Side.Start
    LazySurfaceNeighbor.Side.Top -> LazySurfaceNeighbor.Side.Bottom
    LazySurfaceNeighbor.Side.Bottom -> LazySurfaceNeighbor.Side.Top
}
