package com.jasjeet.lazysurface

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemAnimationTest {

    private fun info(key: String, vararg neighbors: LazySurfaceNeighbor) =
        LazySurfaceItemInfo(key, neighbors.toList(), contentType = null)

    private fun LazySurfaceState.update(vararg infos: LazySurfaceItemInfo) {
        val list = infos.toList()
        updateItems(list, list.associateBy { it.key as Any }.toScatterMap())
    }

    @Test
    fun `removed items take their animation records with them`() {
        val state = LazySurfaceState()
        val a = info("a", LazySurfaceNeighbor.AtPivot)
        val b = info("b", LazySurfaceNeighbor.endOf("a"))
        state.update(a, b)
        state.itemAnimations["a"] = LazySurfaceItemAnimation(Offset.Zero, fadeIn = false)
        state.itemAnimations["b"] = LazySurfaceItemAnimation(Offset.Zero, fadeIn = false)

        state.update(a) // b removed

        assertTrue(state.itemAnimations.containsKey("a"))
        assertFalse(state.itemAnimations.containsKey("b"))
    }

    @Test
    fun `fade-in records start transparent, others opaque`() {
        assertEquals(0f, LazySurfaceItemAnimation(Offset.Zero, fadeIn = true).alpha.value)
        assertEquals(1f, LazySurfaceItemAnimation(Offset.Zero, fadeIn = false).alpha.value)
    }
}
