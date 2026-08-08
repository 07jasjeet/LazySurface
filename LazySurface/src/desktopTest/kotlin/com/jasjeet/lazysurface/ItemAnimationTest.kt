package com.jasjeet.lazysurface

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemAnimationTest {

    private fun info(key: String, vararg neighbors: LazySurfaceNeighbor) =
        LazySurfaceItemInfo(key, neighbors.toList(), contentType = null)

    @Test
    fun `removed items take their animation records with them`() {
        val state = LazySurfaceState()
        val policy = LazySurfaceMeasurePolicy(
            state = state,
            itemProvider = { error("never composed in this test") },
            animationScope = CoroutineScope(EmptyCoroutineContext),
        )

        fun register(vararg infos: LazySurfaceItemInfo) {
            val list = infos.toList()
            val byKey = list.associateBy { it.key as Any }.toScatterMap()
            state.updateItems(list, byKey)
            policy.ensureContent(list, byKey)
        }

        val a = info("a", LazySurfaceNeighbor.AtPivot)
        val b = info("b", LazySurfaceNeighbor.endOf("a"))
        register(a, b)
        policy.itemAnimations["a"] = LazySurfaceItemAnimation(Offset.Zero, fadeIn = false)
        policy.itemAnimations["b"] = LazySurfaceItemAnimation(Offset.Zero, fadeIn = false)

        register(a) // b removed

        assertTrue(policy.itemAnimations.containsKey("a"))
        assertFalse(policy.itemAnimations.containsKey("b"))
    }

    @Test
    fun `fade-in records start transparent, others opaque`() {
        assertEquals(0f, LazySurfaceItemAnimation(Offset.Zero, fadeIn = true).alpha.value)
        assertEquals(1f, LazySurfaceItemAnimation(Offset.Zero, fadeIn = false).alpha.value)
    }
}
