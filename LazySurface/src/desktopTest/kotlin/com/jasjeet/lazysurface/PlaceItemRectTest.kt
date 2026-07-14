package com.jasjeet.lazysurface

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceItemRectTest {
    // Anchor content box: 100x50 at origin.
    private val anchorRect = Rect(0f, 0f, 100f, 50f)

    // Item under test: 40x20, placed one 15px relation-margin gap away.
    private val relationMargin = 15f
    private val itemSize = Size(40f, 20f)

    private fun place(
        side: LazySurfaceNeighbor.Side?,
        alignment: LazySurfaceNeighbor.Alignment = LazySurfaceNeighbor.Alignment.Center,
        isRtl: Boolean = false,
    ) = placeItemRect(
        anchorRect = anchorRect,
        side = side,
        alignment = alignment,
        size = itemSize,
        isRtl = isRtl,
        relationMargin = relationMargin,
    )

    @Test
    fun `end side opens the relation margin and centers on cross axis`() {
        // LTR: end = right, left = 100 + 15, centerY = 25
        assertEquals(Rect(115f, 15f, 155f, 35f), place(LazySurfaceNeighbor.Side.End))
    }

    @Test
    fun `start side grows away from the anchored edge`() {
        // LTR: start = left, right = 0 - 15 = -15, width extends leftwards. Start aligns tops
        assertEquals(
            Rect(-55f, 0f, -15f, 20f),
            place(LazySurfaceNeighbor.Side.Start, LazySurfaceNeighbor.Alignment.Start)
        )
    }

    @Test
    fun `top side with End alignment aligns bottoms-right`() {
        // bottom = 0 - 15 = -15. End aligns right edges in LTR
        assertEquals(
            Rect(60f, -35f, 100f, -15f),
            place(LazySurfaceNeighbor.Side.Top, LazySurfaceNeighbor.Alignment.End)
        )
    }

    @Test
    fun `bottom side centers on cross axis`() {
        // top = 50 + 15 = 65, centerX = 50
        assertEquals(Rect(30f, 65f, 70f, 85f), place(LazySurfaceNeighbor.Side.Bottom))
    }

    @Test
    fun `null side centers the item on the anchor`() {
        val rect = placeItemRect(
            anchorRect = Rect.Zero,
            side = null,
            alignment = LazySurfaceNeighbor.Alignment.Center,
            size = itemSize,
            isRtl = false,
            relationMargin = relationMargin,
        )
        assertEquals(Rect(-20f, -10f, 20f, 10f), rect)
    }

    @Test
    fun `the relation margin is the entire gap`() {
        val rect = placeItemRect(
            anchorRect = anchorRect,
            side = LazySurfaceNeighbor.Side.End,
            alignment = LazySurfaceNeighbor.Alignment.Center,
            size = itemSize,
            isRtl = false,
            relationMargin = 37f,
        )
        assertEquals(Rect(137f, 15f, 177f, 35f), rect)
    }

    @Test
    fun `a relation used in reverse mirrors the forward placement`() {
        // "B is at my end" placed forward: B against A on A's end side.
        val a = Rect(0f, 0f, 100f, 50f)
        val forward = placeItemRect(
            anchorRect = a,
            side = LazySurfaceNeighbor.Side.End,
            alignment = LazySurfaceNeighbor.Alignment.Center,
            size = itemSize,
            isRtl = false,
            relationMargin = relationMargin,
        )
        // The same relation solved from B's side: A sits on the opposite side of B,
        // the same relation margin apart, and must land exactly where it started.
        val backward = placeItemRect(
            anchorRect = forward,
            side = LazySurfaceNeighbor.Side.End.opposite(),
            alignment = LazySurfaceNeighbor.Alignment.Center,
            size = Size(a.width, a.height),
            isRtl = false,
            relationMargin = relationMargin,
        )
        assertEquals(a, backward)
    }

    // --- Right-to-left resolution ------------------------------------------------------

    @Test
    fun `end side resolves to the absolute left under RTL`() {
        // RTL end = left, the placement mirrors the LTR start side exactly.
        assertEquals(
            place(LazySurfaceNeighbor.Side.Start, isRtl = false),
            place(LazySurfaceNeighbor.Side.End, isRtl = true),
        )
        assertEquals(
            place(LazySurfaceNeighbor.Side.End, isRtl = false),
            place(LazySurfaceNeighbor.Side.Start, isRtl = true),
        )
    }

    @Test
    fun `the relation margin opens on the mirrored side under RTL`() {
        // The margin is direction-agnostic (a gap is a gap), under RTL the End-side
        // gap sits to the anchor's absolute LEFT: right = 0 - 37 = -37.
        val rect = placeItemRect(
            anchorRect = anchorRect,
            side = LazySurfaceNeighbor.Side.End,
            alignment = LazySurfaceNeighbor.Alignment.Center,
            size = itemSize,
            isRtl = true,
            relationMargin = 37f,
        )
        assertEquals(Rect(-77f, 15f, -37f, 35f), rect)
    }

    @Test
    fun `vertical relation cross alignment mirrors under RTL`() {
        // Below the anchor, Start-aligned: LTR aligns left edges, RTL right edges.
        assertEquals(
            Rect(0f, 65f, 40f, 85f),
            place(LazySurfaceNeighbor.Side.Bottom, LazySurfaceNeighbor.Alignment.Start, isRtl = false),
        )
        assertEquals(
            Rect(60f, 65f, 100f, 85f),
            place(LazySurfaceNeighbor.Side.Bottom, LazySurfaceNeighbor.Alignment.Start, isRtl = true),
        )
    }

    @Test
    fun `a relation used in reverse mirrors the forward placement under RTL too`() {
        val a = Rect(0f, 0f, 100f, 50f)
        val forward = placeItemRect(
            anchorRect = a,
            side = LazySurfaceNeighbor.Side.End,
            alignment = LazySurfaceNeighbor.Alignment.Center,
            size = itemSize,
            isRtl = true,
            relationMargin = relationMargin,
        )
        val backward = placeItemRect(
            anchorRect = forward,
            side = LazySurfaceNeighbor.Side.End.opposite(),
            alignment = LazySurfaceNeighbor.Alignment.Center,
            size = Size(a.width, a.height),
            isRtl = true,
            relationMargin = relationMargin,
        )
        assertEquals(a, backward)
    }
}
