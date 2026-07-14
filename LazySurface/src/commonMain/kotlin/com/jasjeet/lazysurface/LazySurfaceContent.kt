package com.jasjeet.lazysurface

import androidx.collection.MutableObjectIntMap
import androidx.collection.MutableScatterSet
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density

/**
 * Executes the user's content DSL and collects the registered items. Indices exist only
 * here, internally, because [androidx.compose.foundation.lazy.layout.LazyLayout] is
 * index based, the public surface of the library is keyed. [density] resolves the
 * Dp margins declared in the DSL into surface pixels at registration time.
 */
internal class LazySurfaceContent(
    private val density: Density,
    content: LazySurfaceScope.() -> Unit,
) : LazySurfaceScope {
    val entries = ArrayList<LazySurfaceItemEntry>()
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
        entries.forEach { entry ->
            val positioned = positioningTargets.contains(entry.key) ||
                entry.neighbors.any { it.alignment != LazySurfaceNeighbor.Alignment.Free }
            if (positioned) return@forEach
            val freeLinked = entry.neighbors.isNotEmpty() || freeTargets.contains(entry.key)
            require(!freeLinked) {
                "Item '${entry.key}' is connected only by Free relations. Free links " +
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
        require(key !== LazySurfacePivot) { "LazySurfacePivot is the root of the surface and cannot be used as an item key." }
        require(!keyToIndex.containsKey(key)) { "LazySurface keys must be unique, but '$key' was registered twice." }
        keyToIndex[key] = entries.size
        val definition = LazySurfaceNeighborsScope(density).apply(neighbors)
        val declared = definition.buildNeighbors()
        // Collected as registration goes, so validate() needs no relation re-walk.
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
        entries.add(
            LazySurfaceItemEntry(
                key = key,
                neighbors = declared,
                contentType = contentType,
                content = content,
            )
        )
    }
}

internal class LazySurfaceItemEntry(
    val key: Any,
    val neighbors: List<LazySurfaceNeighbor>,
    val contentType: Any?,
    val content: @Composable LazySurfaceItemScope.() -> Unit,
)
