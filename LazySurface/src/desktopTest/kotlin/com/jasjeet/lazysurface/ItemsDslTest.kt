package com.jasjeet.lazysurface

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The collection verbs [LazySurfaceScope.items] and [LazySurfaceScope.itemsIndexed]:
 * one shared interval per call, per-element keys, relations and content types, and
 * the same registration rules as [LazySurfaceScope.item].
 */
class ItemsDslTest {
    private fun collect(content: LazySurfaceScope.() -> Unit) =
        LazySurfaceContent(Density(1f), content)

    private data class City(val id: String, val westOf: String?)

    @Test
    fun `items registers one info per element in order, keyed by the mapper`() {
        val cities = listOf(
            City("hub", westOf = null),
            City("east1", westOf = "hub"),
            City("east2", westOf = "east1"),
        )
        val content = collect {
            items(
                items = cities,
                key = { it.id },
                contentType = { "city" },
                neighbors = { city ->
                    val anchor = city.westOf
                    if (anchor == null) atPivot() else endOf(anchor)
                },
            ) {}
        }

        assertEquals(listOf("hub", "east1", "east2"), content.itemInfos.map { it.key })
        assertEquals(listOf("city", "city", "city"), content.itemInfos.map { it.contentType })
        assertEquals(LazySurfaceNeighbor.AtPivot, content.itemInfos[0].neighbors.single())
        assertEquals("hub", content.itemInfos[1].neighbors.single().key)
        assertEquals("east1", content.itemInfos[2].neighbors.single().key)
    }

    @Test
    fun `items defaults the key to the element itself`() {
        val content = collect {
            item("anchor", neighbors = { atPivot() }) {}
            items(listOf(1, 2, 3), neighbors = { endOf("anchor") }) {}
        }
        assertEquals(listOf("anchor", 1, 2, 3), content.itemInfos.map { it.key })
    }

    @Test
    fun `itemsIndexed hands the index to key, neighbors and contentType`() {
        val content = collect {
            itemsIndexed(
                items = listOf("a", "b", "c"),
                key = { index, item -> "$item$index" },
                contentType = { index, _ -> index % 2 },
                neighbors = { index, item ->
                    if (index == 0) atPivot() else endOf("${'a' + index - 1}${index - 1}")
                },
            ) { _, _ -> }
        }

        assertEquals(listOf("a0", "b1", "c2"), content.itemInfos.map { it.key })
        assertEquals(listOf(0, 1, 0), content.itemInfos.map { it.contentType })
        assertEquals(LazySurfaceNeighbor.AtPivot, content.itemInfos[0].neighbors.single())
        assertEquals("a0", content.itemInfos[1].neighbors.single().key)
        assertEquals("b1", content.itemInfos[2].neighbors.single().key)
    }

    @Test
    fun `one items call stores one shared interval`() {
        val content = collect {
            item("anchor", neighbors = { atPivot() }) {}
            items(listOf(1, 2, 3), neighbors = { endOf("anchor") }) {}
        }

        assertEquals(4, content.itemCount)
        val single = content.intervals[0]
        assertEquals(0, single.startIndex)
        assertEquals(1, single.size)

        val backed = content.intervals[1]
        assertEquals(1, backed.startIndex)
        assertEquals(3, backed.size)
        assertSame(backed.value, content.intervals[2].value)
        assertSame(backed.value, content.intervals[3].value)
        assertNotSame(single.value, backed.value)
    }

    @Test
    fun `separate items calls get separate intervals`() {
        val content = collect {
            item("anchor", neighbors = { atPivot() }) {}
            items(listOf("a"), key = { "first-$it" }, neighbors = { endOf("anchor") }) {}
            items(listOf("a"), key = { "second-$it" }, neighbors = { endOf("anchor") }) {}
        }
        assertNotSame(content.intervals[1].value, content.intervals[2].value)
    }

    @Test
    fun `interval key and type lambdas answer by local index`() {
        val content = collect {
            item("anchor", neighbors = { atPivot() }) {}
            items(listOf("x", "y"), key = { "k-$it" }, contentType = { "t-$it" }, neighbors = { endOf("anchor") }) {}
        }
        assertEquals("k-y", content.getKey(2))
        assertEquals("t-y", content.getContentType(2))
    }

    @Test
    fun `items inherits item's registration rules, duplicate keys throw`() {
        assertThrows(IllegalArgumentException::class.java) {
            collect {
                item("dup", neighbors = { atPivot() }) {}
                items(listOf("dup"), neighbors = { endOf("dup") }) {}
            }
        }
    }
}
