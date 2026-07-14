package com.jasjeet.lazysurface

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Receiver scope for [LazySurface] content. Items live on a 2D plane and are declared
 * purely by [key] and their [neighbour definition][LazySurfaceNeighborsScope], there is
 * no concept of an index.
 */
interface LazySurfaceScope {
    /**
     * Adds a single item to the surface.
     *
     * The [content] decides its own size: it is measured under unbounded constraints
     * the first time it comes near the viewport, so it must bound itself (e.g. with
     * `Modifier.size`/`width`/`height` or the `fillParentMax*` modifiers of
     * [LazySurfaceItemScope]). `fillMaxWidth`/`fillMaxHeight`/`fillMaxSize` have no
     * effect under unbounded constraints, and children that require bounded
     * constraints (scrollable containers like `LazyColumn`) throw the standard
     * Compose infinity-constraints exception.
     *
     * @param key unique identity of the item on the surface. Keys double as
     * saved-instance-state identities, so on Android they must be types a Bundle
     * can store: strings, numbers, enums, `Pair`, or your own
     * `Parcelable`/`Serializable`. Other platforms accept any type.
     * @param contentType type used for composition reuse of similar items.
     * @param neighbors declares the item's margins and spatial relations, e.g.
     * `{ margins(16.dp); below("a") }`. Relations are bidirectional, so the block
     * may declare nothing when other items say where this one sits instead, the item
     * just needs to be connected to the pivot through *some* chain of declared
     * relations to ever resolve.
     */
    fun item(
        key: Any,
        contentType: Any? = null,
        neighbors: LazySurfaceNeighborsScope.() -> Unit = {},
        content: @Composable LazySurfaceItemScope.() -> Unit
    )
}

/**
 * Builder for one item's neighbour relations, phrased from the item's own
 * perspective: *where do I sit relative to my neighbours?*
 *
 * ```
 * item(key = "a", neighbors = {
 *     endOf("b")                   // I sit at b's end (right of it in LTR)
 *     below("c", margin = 16.dp)   // I sit below c, 16dp away
 * }) { ... }
 * ```
 *
 * Each positional verb declares one relation. A relation's [margin] is the declared
 * gap between the two content boxes along its axis and belongs to that relation
 * alone, margins are declared in [Dp] and resolved against the composition's
 * [Density] when the item is registered.
 *
 * The horizontal verbs are direction-relative: `startOf` resolves to the left in
 * left-to-right layouts and to the right in right-to-left ones, `endOf` the other
 * way around, so the arrangement mirrors correctly when the layout direction
 * reverses.
 */
class LazySurfaceNeighborsScope internal constructor(
    private val density: Density,
) {
    private val relations = ArrayList<LazySurfaceNeighbor>(2)

    /** Centers this item on the [pivot][LazySurfacePivot] (the origin of the surface). */
    fun atPivot() {
        relations.add(LazySurfaceNeighbor.AtPivot)
    }

    /** Adds a prebuilt [relation], for relations carried in data rather than code. */
    fun neighbor(relation: LazySurfaceNeighbor, margin: Dp? = null) {
        relations.add(
            if (margin != null) relation.copy(margin = with(density) { margin.toPx() }) else relation
        )
    }

    /** This item sits on top of [key]. */
    fun above(
        key: Any,
        align: LazySurfaceNeighbor.Alignment = LazySurfaceNeighbor.Alignment.Center,
        margin: Dp? = null,
    ): LazySurfaceRelation = declare(LazySurfaceNeighbor.above(key, align), LazySurfaceNeighbor.Side.Bottom, margin)

    /** This item sits below [key]. */
    fun below(
        key: Any,
        align: LazySurfaceNeighbor.Alignment = LazySurfaceNeighbor.Alignment.Center,
        margin: Dp? = null,
    ): LazySurfaceRelation = declare(LazySurfaceNeighbor.below(key, align), LazySurfaceNeighbor.Side.Top, margin)

    /** This item sits at the start side of [key] (left of it in LTR). */
    fun startOf(
        key: Any,
        align: LazySurfaceNeighbor.Alignment = LazySurfaceNeighbor.Alignment.Center,
        margin: Dp? = null,
    ): LazySurfaceRelation = declare(LazySurfaceNeighbor.startOf(key, align), LazySurfaceNeighbor.Side.End, margin)

    /** This item sits at the end side of [key] (right of it in LTR). */
    fun endOf(
        key: Any,
        align: LazySurfaceNeighbor.Alignment = LazySurfaceNeighbor.Alignment.Center,
        margin: Dp? = null,
    ): LazySurfaceRelation = declare(LazySurfaceNeighbor.endOf(key, align), LazySurfaceNeighbor.Side.Start, margin)

    private fun declare(
        relation: LazySurfaceNeighbor,
        facingSide: LazySurfaceNeighbor.Side,
        margin: Dp?,
    ): LazySurfaceRelation {
        relations.add(if (margin != null) relation.copy(margin = with(density) { margin.toPx() }) else relation)
        return LazySurfaceRelation(this, relations.lastIndex, facingSide)
    }

    internal fun replaceAlignment(index: Int, alignment: LazySurfaceNeighbor.Alignment) {
        relations[index] = relations[index].copy(alignment = alignment)
    }

    internal fun replaceMargin(index: Int, margin: Dp) {
        relations[index] = relations[index].copy(margin = with(density) { margin.toPx() })
    }

    internal fun buildNeighbors(): List<LazySurfaceNeighbor> = relations
}

/**
 * A just-declared relation, returned by the positional verbs
 * ([LazySurfaceNeighborsScope.above], [below][LazySurfaceNeighborsScope.below],
 * [startOf][LazySurfaceNeighborsScope.startOf],
 * [endOf][LazySurfaceNeighborsScope.endOf]) so the declaration reads on in plain
 * words:
 *
 * ```
 * below(Header) aligned Start margin 14.dp
 * ```
 *
 * Both refinements are optional and chainable in any order.
 */
class LazySurfaceRelation internal constructor(
    private val scope: LazySurfaceNeighborsScope,
    private val index: Int,
    @Suppress("unused") private val facingSide: LazySurfaceNeighbor.Side,
) {
    /** Cross-axis alignment of this relation, see [LazySurfaceNeighbor.Alignment]. */
    infix fun aligned(alignment: LazySurfaceNeighbor.Alignment): LazySurfaceRelation {
        scope.replaceAlignment(index, alignment)
        return this
    }

    /**
     * Extra distance along this relation's axis, belonging to THIS relation alone,
     * it adds to the two items' side margins for this relation's gap and leaks into
     * no other relation, so a third item can hug either endpoint on the same side.
     */
    infix fun margin(value: Dp): LazySurfaceRelation {
        scope.replaceMargin(index, value)
        return this
    }
}

/**
 * Adds a collection of items to the surface. Each element is identified by [key] and
 * related to the rest of the surface by its [neighbors] block, elements may reference
 * each other or items registered elsewhere.
 */
inline fun <T> LazySurfaceScope.items(
    items: List<T>,
    key: (item: T) -> Any,
    crossinline neighbors: LazySurfaceNeighborsScope.(item: T) -> Unit = {},
    contentType: (item: T) -> Any? = { null },
    crossinline itemContent: @Composable LazySurfaceItemScope.(item: T) -> Unit
) {
    items.forEach { item ->
        item(
            key = key(item),
            contentType = contentType(item),
            neighbors = { neighbors(item) },
        ) { itemContent(item) }
    }
}
