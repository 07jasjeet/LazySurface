package com.jasjeet.lazysurface

import androidx.collection.MutableObjectIntMap
import androidx.collection.MutableScatterSet
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.layout.LazyLayoutIntervalContent
import androidx.compose.foundation.lazy.layout.MutableIntervalList
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density

/**
 * Executes the user's content DSL and collects the registered items, framework
 * interval style: composable content is stored once per registration call in
 * [intervals] (see [LazyLayoutIntervalContent]), while the data every item must
 * materialize eagerly for the relation graph (key, neighbours, content type) lives
 * in [itemInfos]. Indices exist only here, internally, because
 * [androidx.compose.foundation.lazy.layout.LazyLayout] is index based, the public
 * surface of the library is keyed. [density] resolves the Dp margins declared in
 * the DSL into surface pixels at registration time.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class LazySurfaceContent(
    private val density: Density,
    content: LazySurfaceScope.() -> Unit,
) : LazyLayoutIntervalContent<LazySurfaceInterval>(), LazySurfaceScope {

    override val intervals = MutableIntervalList<LazySurfaceInterval>()

    /** Eager per-item registration data, in registration order, the graph's input. */
    val itemInfos = ArrayList<LazySurfaceItemInfo>()
    val keyToIndex = MutableObjectIntMap<Any>()

    /** Keys targeted by at least one positioning (non-Free) relation. */
    private val positioningTargets = MutableScatterSet<Any>()

    /** Keys targeted by at least one Free relation. */
    private val freeTargets = MutableScatterSet<Any>()

    init {
        apply(content)
        validate()
    }

    /**
     * Fails fast on declaration errors the engine could otherwise only express as
     * silently missing content. Registration inspects what the caller literally
     * wrote, runtime healing (removals splicing relations away) happens downstream
     * of here and can therefore never throw.
     *
     * Today's single rule: an item whose only connectivity is
     * [Free][LazySurfaceNeighbor.Alignment.Free] can never be positioned, so it
     * would never resolve, render, or be reachable by navigation.
     */
    private fun validate() {
        itemInfos.forEach { info ->
            val positioned = positioningTargets.contains(info.key) ||
                info.neighbors.any { it.alignment != LazySurfaceNeighbor.Alignment.Free }
            if (positioned) return@forEach
            val freeLinked = info.neighbors.isNotEmpty() || freeTargets.contains(info.key)
            require(!freeLinked) {
                "Item '${info.key}' is connected only by Free relations. Free links " +
                    "route and separate but never position, so this item could never " +
                    "resolve or render. Give it at least one positioning relation " +
                    "(any non-Free alignment, or atPivot), declared on it or against it."
            }
        }
    }

    override fun item(
        key: Any,
        contentType: Any?,
        neighbors: LazySurfaceNeighborsScope.() -> Unit,
        content: @Composable LazySurfaceItemScope.() -> Unit
    ) {
        register(
            key = key,
            contentType = contentType,
            declared = LazySurfaceNeighborsScope(density).apply(neighbors).buildNeighbors(),
        )
        intervals.addInterval(
            1,
            LazySurfaceInterval(
                key = { key },
                type = { contentType },
                item = { content() },
            ),
        )
    }

    override fun <T : Any> items(
        items: List<T>,
        key: (item: T) -> Any,
        contentType: (item: T) -> Any?,
        neighbors: LazySurfaceNeighborsScope.(item: T) -> Unit,
        itemContent: @Composable LazySurfaceItemScope.(item: T) -> Unit,
    ) {
        items.forEach { element ->
            val definition = LazySurfaceNeighborsScope(density)
            definition.neighbors(element)
            register(
                key = key(element),
                contentType = contentType(element),
                declared = definition.buildNeighbors(),
            )
        }
        intervals.addInterval(
            items.size,
            LazySurfaceInterval(
                key = { index -> key(items[index]) },
                type = { index -> contentType(items[index]) },
                item = { index -> itemContent(items[index]) },
            ),
        )
    }

    override fun <T : Any> itemsIndexed(
        items: List<T>,
        key: (index: Int, item: T) -> Any,
        contentType: (index: Int, item: T) -> Any?,
        neighbors: LazySurfaceNeighborsScope.(index: Int, item: T) -> Unit,
        itemContent: @Composable LazySurfaceItemScope.(index: Int, item: T) -> Unit,
    ) {
        items.forEachIndexed { index, element ->
            val definition = LazySurfaceNeighborsScope(density)
            definition.neighbors(index, element)
            register(
                key = key(index, element),
                contentType = contentType(index, element),
                declared = definition.buildNeighbors(),
            )
        }
        intervals.addInterval(
            items.size,
            LazySurfaceInterval(
                key = { index -> key(index, items[index]) },
                type = { index -> contentType(index, items[index]) },
                item = { index -> itemContent(index, items[index]) },
            ),
        )
    }

    private fun register(
        key: Any,
        contentType: Any?,
        declared: List<LazySurfaceNeighbor>,
    ) {
        require(key !== LazySurfacePivot) {
            "LazySurfacePivot is the root of the surface and cannot be used as an item key."
        }
        require(!keyToIndex.containsKey(key)) {
            "LazySurface keys must be unique, but '$key' was registered twice."
        }

        keyToIndex[key] = itemInfos.size
        declared.forEach { neighbor ->
            // A self-relation never crashes downstream, but it would feed the solver
            // a constraint that fights itself forever, a silent per-frame cost for
            // what is always a declaration typo.
            require(neighbor.key != key) {
                "Item '$key' declares a relation to itself. Relations join two different items."
            }
            require(neighbor.margin >= 0f) {
                "Item '$key' declares a relation to '${neighbor.key}' with a negative " +
                    "margin (${neighbor.margin} px). Margins are separation distances and " +
                    "can never be negative. To pull an item closer than a neighbour's " +
                    "edge, size the neighbour (or an invisible scaffold item) instead."
            }
            if (neighbor.alignment == LazySurfaceNeighbor.Alignment.Free) {
                freeTargets.add(neighbor.key)
            } else {
                positioningTargets.add(neighbor.key)
            }
        }
        itemInfos.add(
            LazySurfaceItemInfo(
                key = key,
                neighbors = declared,
                contentType = contentType,
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
internal class LazySurfaceInterval(
    override val key: (index: Int) -> Any,
    override val type: (index: Int) -> Any?,
    val item: @Composable LazySurfaceItemScope.(index: Int) -> Unit,
) : LazyLayoutIntervalContent.Interval
