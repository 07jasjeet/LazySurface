package com.jasjeet.lazysurface

import androidx.collection.MutableScatterMap
import androidx.collection.ScatterMap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.layout.LazyLayoutItemProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity

@OptIn(ExperimentalFoundationApi::class)
internal class LazySurfaceItemProvider(
    private val content: LazySurfaceContent,
    private val itemScope: LazySurfaceItemScope,
) : LazyLayoutItemProvider {
    override val itemCount: Int get() = content.entries.size

    /**
     * The [androidx.compose.foundation.lazy.layout.LazyLayoutMeasureScope.compose]
     * index for [key], the single place where the keyed world of the surface meets
     * LazyLayout's index-based API. No other logic deals in indices.
     */
    fun composeIndexOf(key: Any): Int = content.keyToIndex.getOrDefault(key, -1)

    val itemInfos: List<LazySurfaceItemInfo> by lazy {
        content.entries.map { entry ->
            LazySurfaceItemInfo(
                key = entry.key,
                neighbors = entry.neighbors,
                contentType = entry.contentType,
            )
        }
    }

    val itemByKey: ScatterMap<Any, LazySurfaceItemInfo> by lazy {
        val map = MutableScatterMap<Any, LazySurfaceItemInfo>(itemInfos.size)
        itemInfos.forEach { info -> map[info.key] = info }
        map
    }

    fun entryAt(index: Int): LazySurfaceItemEntry = content.entries[index]

    @Composable
    override fun Item(index: Int, key: Any) {
        content.entries[index].content(itemScope)
    }

    override fun getKey(index: Int): Any = content.entries[index].key

    override fun getContentType(index: Int): Any? = content.entries[index].contentType

    override fun getIndex(key: Any): Int = content.keyToIndex.getOrDefault(key, -1)

    // The identity of the provider is its collected content: equality lets the
    // LazyLayout skip recompositions when the content didn't actually change.
    override fun equals(other: Any?): Boolean =
        this === other || (other is LazySurfaceItemProvider && content == other.content)

    override fun hashCode(): Int = content.hashCode()
}

@Composable
internal fun rememberLazySurfaceItemProviderLambda(
    state: LazySurfaceState,
    content: LazySurfaceScope.() -> Unit,
): () -> LazySurfaceItemProvider {
    val latestContent by rememberUpdatedState(content)
    val latestDensity by rememberUpdatedState(LocalDensity.current)
    return remember(state) {
        val providerState = derivedStateOf(referentialEqualityPolicy()) {
            LazySurfaceItemProvider(
                content = LazySurfaceContent(latestDensity, latestContent),
                itemScope = LazySurfaceItemScopeImpl(state),
            )
        }
        providerState::value
    }
}
