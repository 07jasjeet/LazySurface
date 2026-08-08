package com.jasjeet.lazysurface.demo

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.jasjeet.lazysurface.LazySurfaceItemScope
import com.jasjeet.lazysurface.LazySurfaceNeighbor
import com.jasjeet.lazysurface.LazySurfaceScope

/**
 * Concentric rings around [center] (any already-registered item) built from
 * two kinds of relations. Each placed item is keyed by [key] of its index, so
 * several clusters can share one surface as long as their key spaces differ.
 *
 * **Between layers**, the eight anchors of ring k each relate to the anchor
 * one layer in on the same ray ([center] itself for ring 1):
 *  - Cardinals (N/E/S/W) sit directly on an axis, so one verb pins them:
 *    `above(inner, margin = ringSpacing)`.
 *  - Corners (NE/SE/SW/NW) sit on a diagonal and ray to the same diagonal's
 *    inner anchor (the center itself for ring 1) with both verbs
 *    (`endOf(inner); above(inner)` for NE, `12 -> 4`, `28 -> 12`) at a
 *    quarter of the axis margin: a diagonal already steps out on both axes
 *    at once, so it needs far less margin for the same ring-to-ring
 *    distance. Corners never relate to their own ring's cardinals, on a
 *    wide ring "adjacent to both" is geometrically impossible, and the
 *    fight would drag the cardinals off their rays.
 *  - Intermediate items ("in-items", the ones between an axis item and a
 *    diagonal item, eight sections per ring) also link one layer in: an
 *    outer section always holds one in-item more than the inner one, so
 *    positions pair 1:1 counted from the cardinal and spread the section
 *    evenly, while the in-item hugging the diagonal links to the inner
 *    corner itself.
 *
 * [ringSpacing] is the only spacing knob: full on the cardinal rays, a
 * quarter on the diagonal rays, and the in-items' layer links measure it on
 * their own axis. In-ring spacing is not declared at all, it falls out of
 * the ray geometry, which is what keeps the routine exact for ANY
 * ring-to-item-size ratio.
 *
 * **Within a circle**, ring neighbours are joined by Free edges only:
 * adjacency for navigation routing plus a hard no-overlap floor, but no
 * declared geometry, so nothing in-ring can ever fight the rays. A grown
 * item pushes its ring neighbours through those floors and the outer layers
 * through the ray floors.
 *
 * Items register rings inside out, within a ring the eight anchors come
 * first, then the in-items land one per section round-robin (see [slotOf]),
 * so ANY count spreads evenly across all eight sections instead of piling
 * into an arc, and growing the count never moves an already-placed item.
 * Every item's layer link targets the always-complete ring below, so partial
 * rings never strand anything. Free edges to slots not occupied yet simply
 * drop until that neighbour arrives.
 */
internal fun LazySurfaceScope.placeAround(
    center: Any,
    count: Int,
    ringSpacing: Dp,
    key: (Int) -> Any = { it },
    itemContent: @Composable LazySurfaceItemScope.(index: Int, ring: Int) -> Unit,
) {
    items(
        items = List(count) { it },
        key = { key(it) },
        neighbors = { index ->
            val ring = ringOf(index)
            val slot = slotOf(ring, index - ringStart(ring))
            val octant = slot / ring
            val pos = slot % ring
            if (pos == 0) {
                // Anchor: relate to the same ray's anchor one layer in.
                // Even octants are the axes, odd octants the diagonals.
                val inner: Any =
                    if (ring == 1) center
                    else key(indexAt(ring - 1, octant * (ring - 1)))
                val diagonal = ringSpacing / 4
                when {
                    // Cardinals: one axis verb along their ray.
                    octant % 2 == 0 -> when (octant) {
                        0 -> above(inner, margin = ringSpacing)   // N
                        2 -> endOf(inner, margin = ringSpacing)   // E
                        4 -> below(inner, margin = ringSpacing)   // S
                        else -> startOf(inner, margin = ringSpacing) // W
                    }
                    // Corners ray to the same diagonal's inner anchor (the
                    // center itself for ring 1) at a quarter of the axis
                    // margin: a diagonal steps out on both axes at once, so
                    // it needs far less margin for the same distance. Corners
                    // never relate to their own ring's cardinals, on a wide
                    // ring "adjacent to both" is geometrically impossible,
                    // and the fight drags the cardinals off their rays.
                    else -> when (octant) {
                        1 -> { endOf(inner, margin = diagonal); above(inner, margin = diagonal) }    // NE
                        3 -> { endOf(inner, margin = diagonal); below(inner, margin = diagonal) }    // SE
                        5 -> { startOf(inner, margin = diagonal); below(inner, margin = diagonal) }  // SW
                        else -> { startOf(inner, margin = diagonal); above(inner, margin = diagonal) } // NW
                    }
                }
            } else {
                // In-item: positioned by its layer link alone, the in-ring
                // guard edges below carry no geometry.
                // The layer link spreads the section evenly
                // over the one below: an inner section always holds one
                // in-item fewer, so positions pair 1:1 counted from the
                // cardinal and the in-item hugging the diagonal links to the
                // inner corner itself.
                val innerSection = octant * (ring - 1)
                val innerSlot = when {
                    octant % 2 == 0 ->
                        if (pos == ring - 1) (octant + 1) * (ring - 1)
                        else innerSection + pos
                    else ->
                        if (pos == 1) innerSection
                        else innerSection + pos - 1
                }
                val inner = key(indexAt(ring - 1, innerSlot))
                // The link keeps the full ringSpacing on its axis. The taper
                // toward the diagonal is NOT declared here: with the corners
                // tucked in by their quarter margins, every edge slants, and a
                // constant axis gap over a slanted edge already thins the
                // radial ring separation toward the diagonal. Declaring a
                // smaller margin on top of that double-counts the taper, the
                // links then under-reach, and their soft pulls drag the inner
                // corners outward until the diagonal reads as full margin.
                when (octant) {
                    7, 0 -> above(inner, margin = ringSpacing)
                    1, 2 -> endOf(inner, margin = ringSpacing)
                    3, 4 -> below(inner, margin = ringSpacing)
                    else -> startOf(inner, margin = ringSpacing)
                }
            }

            // Every item guards against its ring predecessor (the first slot
            // wraps to the ring's last) with a Free edge along the local
            // direction of travel: adjacency for navigation routing plus a
            // hard no-overlap floor, but NO declared geometry, so it can
            // never fight the rays, however sparse or dense the ring is.
            val previousInRing = key(indexAt(ring, if (slot == 0) 8 * ring - 1 else slot - 1))
            val edgeOctant = if (pos == 0) (octant + 7) % 8 else octant
            when (edgeOctant) {
                7, 0 -> endOf(previousInRing, LazySurfaceNeighbor.Alignment.Free)   // top, rightward
                1, 2 -> below(previousInRing, LazySurfaceNeighbor.Alignment.Free)   // right, downward
                3, 4 -> startOf(previousInRing, LazySurfaceNeighbor.Alignment.Free) // bottom, leftward
                else -> above(previousInRing, LazySurfaceNeighbor.Alignment.Free)   // left, upward
            }
        },
    ) { index ->
        itemContent(index, ringOf(index))
    }
}

/**
 * The ring slot the n-th item added to a ring occupies: the eight anchors
 * first (cardinals, then corners), then one in-item per section round-robin,
 * each section filling from its cardinal side. Any count therefore spreads
 * evenly across all eight sections, and because round-robin only appends,
 * growing the count never moves an already-placed item.
 */
internal fun slotOf(ring: Int, ringIndex: Int): Int = when {
    ringIndex < 4 -> 2 * ringIndex * ring          // cardinals: octants 0, 2, 4, 6
    ringIndex < 8 -> (2 * (ringIndex - 4) + 1) * ring // corners: octants 1, 3, 5, 7
    else -> {
        val section = (ringIndex - 8) % 8
        val round = (ringIndex - 8) / 8 + 1        // this section's round-th in-item
        val pos = if (section % 2 == 0) round else ring - round
        section * ring + pos
    }
}

/** Inverse of [slotOf]: the index of the item occupying [slot] of [ring]. */
internal fun indexAt(ring: Int, slot: Int): Int {
    val octant = slot / ring
    val pos = slot % ring
    return ringStart(ring) + when {
        pos == 0 && octant % 2 == 0 -> octant / 2
        pos == 0 -> 4 + (octant - 1) / 2
        else -> {
            val round = if (octant % 2 == 0) pos else ring - pos
            8 + (round - 1) * 8 + octant
        }
    }
}

/** Items in rings 1..k-1: 8 + 16 + ... = 4k(k-1). */
internal fun ringStart(ring: Int): Int = 4 * ring * (ring - 1)

internal fun ringOf(index: Int): Int {
    var ring = 1
    while (index >= ringStart(ring + 1)) ring++
    return ring
}
