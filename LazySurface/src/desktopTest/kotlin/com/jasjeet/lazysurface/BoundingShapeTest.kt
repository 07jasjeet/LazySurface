package com.jasjeet.lazysurface

import androidx.collection.mutableScatterMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundingShapeTest {

    // --- nearestPointInRects (stranded-offset fallback) ------------------------------

    private val horizontalStrip = Rect(-1000f, -100f, 1000f, 100f)
    private val verticalStrip = Rect(-100f, -1000f, 100f, 1000f)
    private val plusShape = listOf(horizontalStrip, verticalStrip)

    private fun clamp(target: Offset) = LazySurfaceState.nearestPointInRects(target, plusShape)

    @Test
    fun `points inside the shape stay untouched`() {
        assertEquals(Offset(900f, 50f), clamp(Offset(900f, 50f)))
        assertEquals(Offset(-50f, -900f), clamp(Offset(-50f, -900f)))
    }

    @Test
    fun `no vertical room at the end of a horizontal arm`() {
        assertEquals(Offset(900f, -100f), clamp(Offset(900f, -400f)))
        assertEquals(Offset(900f, 100f), clamp(Offset(900f, 400f)))
    }

    @Test
    fun `arm ends clamp diagonally to the corner`() {
        assertEquals(Offset(1000f, 100f), clamp(Offset(1200f, 300f)))
    }

    @Test
    fun `empty shape leaves the target unclamped`() {
        assertEquals(Offset(5f, 6f), LazySurfaceState.nearestPointInRects(Offset(5f, 6f), emptyList()))
    }

    // --- Visibility-driven clamping ---------------------------------------------------
    //
    // A plus of 200x200 items with zero margins, so margin box == content box:
    //
    //   up-3    [-100..100] x [-700..-500]
    //   up-2    [-100..100] x [-500..-300]
    //   up-1    [-100..100] x [-300..-100]
    //   center  [-100..100] x [-100..100]
    //   right-1 [100..300], right-2 [300..500], right-3 [500..700], all y [-100..100]
    //
    // Viewport 400x400 at zoom 1 => half extents (200, 200).

    private val itemRects: Map<Any, Rect> = mapOf(
        "center" to Rect(-100f, -100f, 100f, 100f),
        "up-1" to Rect(-100f, -300f, 100f, -100f),
        "up-2" to Rect(-100f, -500f, 100f, -300f),
        "up-3" to Rect(-100f, -700f, 100f, -500f),
        "right-1" to Rect(100f, -100f, 300f, 100f),
        "right-2" to Rect(300f, -100f, 500f, 100f),
        "right-3" to Rect(500f, -100f, 700f, 100f),
    )

    private fun stateAt(position: Offset, frontierRight4: Boolean = false): LazySurfaceState {
        val state = LazySurfaceState(initialOffset = position)
        state.viewportSize = IntSize(400, 400)

        fun info(key: String, neighbor: LazySurfaceNeighbor) = LazySurfaceItemInfo(
            key = key,
            neighbors = listOf(neighbor),
            contentType = null,
        )

        val infos = listOfNotNull(
            info("center", LazySurfaceNeighbor.AtPivot),
            info("up-1", LazySurfaceNeighbor.above(LazySurfacePivot)),
            info("up-2", LazySurfaceNeighbor.above("up-1")),
            info("up-3", LazySurfaceNeighbor.above("up-2")),
            info("right-1", LazySurfaceNeighbor.endOf(LazySurfacePivot)),
            info("right-2", LazySurfaceNeighbor.endOf("right-1")),
            info("right-3", LazySurfaceNeighbor.endOf("right-2")),
            // Declared past the arm's end but never measured: its zero-size provisional
            // rect sits where the arm's next box will grow, the resolution frontier.
            if (frontierRight4) info("right-4", LazySurfaceNeighbor.endOf("right-3")) else null,
        )
        state.updateItems(infos, infos.associateBy { it.key }.toScatterMap())
        if (frontierRight4) {
            state.provisionalPositions.update(
            infos = emptyList(),
            graph = state.graph,
            itemByKey = state.itemByKey,
            resolved = mutableScatterMapOf(),
            awaitingMeasure = mapOf<Any, Rect>("right-4" to Rect(700f, 0f, 700f, 0f)).toScatterMap(),
            cachedSize = { null },
            isRtl = false,
        )
        }
        state.lastKnownRects = itemRects.toScatterMap()
        state.resolvedMarginBoxes = itemRects.toScatterMap()

        // What production would display: items whose rect intersects the viewport.
        val halfW = state.viewportSize.width / 2f
        val halfH = state.viewportSize.height / 2f
        val viewportRect = Rect(
            position.x - halfW, position.y - halfH,
            position.x + halfW, position.y + halfH,
        )
        state.visibleItemsInfo = itemRects
            .filterValues { it.overlaps(viewportRect) }
            .map { (key, rect) ->
                LazySurfaceVisibleItemInfo(
                    key, null, rect,
                    viewportRect = surfaceRectInViewport(rect, state.offset, state.zoom, state.viewportSize),
                )
            }
        return state
    }

    @Test
    fun `travel spans all known content in the band`() {
        // At (0, -200) the right arm overlaps the viewport's band, so its whole
        // known extent grants room, not just what is composed this frame. A drag
        // faster than one item per frame must never hit a wall inside measured
        // content (it used to: room stopped one graph hop past the displayed set).
        val state = stateAt(Offset(0f, -200f))

        assertEquals(Offset(400f, -200f), state.coerceIntoResolvedShape(Offset(400f, -200f)))
        assertEquals(Offset(600f, -200f), state.coerceIntoResolvedShape(Offset(600f, -200f)))
        assertEquals(Offset(700f, -200f), state.coerceIntoResolvedShape(Offset(900f, -200f)))
    }

    @Test
    fun `junction row out of view contracts travel to the current strip`() {
        // At (0, -600) only up-2/up-3 are displayed, the junction row is out of the
        // visible container, so sideways travel clamps to the strip's own width.
        val state = stateAt(Offset(0f, -600f))

        assertEquals(Offset(100f, -600f), state.coerceIntoResolvedShape(Offset(400f, -600f)))
    }

    @Test
    fun `end of an arm allows no perpendicular room`() {
        // At (600, 0) only the right arm is displayed: vertical drags clamp to its
        // height and horizontal ones to the last known box (right-3, end of chain).
        val state = stateAt(Offset(600f, 0f))

        assertEquals(Offset(700f, 0f), state.coerceIntoResolvedShape(Offset(900f, 0f)))
        assertEquals(Offset(600f, 100f), state.coerceIntoResolvedShape(Offset(600f, 400f)))
    }

    @Test
    fun `offset beyond the contracted range may only move back towards it`() {
        // Hovering above the right arm: moving further away is denied, moving back
        // towards the arm flows freely.
        val state = stateAt(Offset(400f, -250f))

        assertEquals(Offset(400f, -250f), state.coerceIntoResolvedShape(Offset(400f, -500f)))
        assertEquals(Offset(400f, -150f), state.coerceIntoResolvedShape(Offset(400f, -150f)))
    }

    // --- contentPadding ---------------------------------------------------------------
    //
    // Same plus shape. Without a contentPadding the viewport *center* may reach the
    // shape's edge (half a viewport of blank space), with one, the viewport *edge* may
    // travel at most that far past the content edge.

    @Test
    fun `zero content padding stops the viewport edge at the content edge`() {
        // Right arm ends at x=700, the viewport is 400 wide, so its center stops at 500.
        val state = stateAt(Offset(400f, 0f))
        state.contentPaddingPx = ResolvedContentPadding.all(0f)

        assertEquals(Offset(500f, 0f), state.coerceIntoResolvedShape(Offset(600f, 0f)))
    }

    @Test
    fun `content padding grants exactly that much blank space past the edge`() {
        val state = stateAt(Offset(400f, 0f))
        state.contentPaddingPx = ResolvedContentPadding.all(50f)

        assertEquals(Offset(550f, 0f), state.coerceIntoResolvedShape(Offset(600f, 0f)))
    }

    @Test
    fun `content smaller than the padded viewport stays centered on that axis`() {
        // The right arm is 200 tall against a 400-tall viewport: with a fixed padding
        // the vertical range inverts and collapses to the arm's midline (y = 0).
        val state = stateAt(Offset(400f, 0f))
        state.contentPaddingPx = ResolvedContentPadding.all(0f)

        assertEquals(Offset(400f, 0f), state.coerceIntoResolvedShape(Offset(400f, 80f)))
    }

    // --- Fling dominance ----------------------------------------------------------
    //
    // The decay stops when the *dominant* axis gets rejected by the shape, so an axis
    // already pinned against the shape at release must never be dominant: a small
    // flick's velocity direction is noisy, and a pinned dominant axis would kill the
    // whole fling on its first frame.

    @Test
    fun `axis pinned against the strip edge cannot carry the fling`() {
        // Dragging along the right arm left y pinned at its bottom edge (y = 100). A
        // small flick pointing mostly outward must still fling along the arm on x.
        val state = stateAt(Offset(400f, 100f))

        assertEquals(true, state.flingDominantAxisIsX(Offset(x = 30f, y = 40f)))
    }

    @Test
    fun `axis with room keeps plain magnitude dominance`() {
        // Same spot, but the flick points back INTO the arm: y has room, so the larger
        // component wins as usual.
        val state = stateAt(Offset(400f, 100f))

        assertEquals(false, state.flingDominantAxisIsX(Offset(x = 30f, y = -40f)))
    }

    @Test
    fun `a slam into the pinned axis is a wall hit, not a glide`() {
        // Pinned at the arm's bottom edge, flinging DOWN violently with slight
        // sideways drift (the device logs' case: 7400 into the wall vs 1100
        // along it). Gliding the minor axis would discard the slam's energy and
        // delay the edge bounce until the glide rests.
        val state = stateAt(Offset(400f, 100f))

        assertEquals(null, state.flingDominantAxisIsX(Offset(x = 300f, y = 1200f)))
    }

    @Test
    fun `fling with no room on either axis is all edge leftover`() {
        // Wedged into the arm's end corner (x and y both at their limits), flinging
        // further outward has nowhere to go at all.
        val state = stateAt(Offset(700f, 100f))

        assertEquals(null, state.flingDominantAxisIsX(Offset(x = 30f, y = 40f)))
    }

    // --- Resolution frontier ---------------------------------------------------------
    //
    // The clamp must only claim a wall where emptiness is verified. A reachable item
    // that is positioned but not yet measured means the shape on that side is still
    // growing: travel opens a whole viewport past its anchored position instead of
    // stopping at the last resolved box.

    @Test
    fun `unmeasured neighbour keeps its side of the shape open`() {
        // Without the frontier the arm ends at 700, with unmeasured right-4 anchored
        // there, travel opens one viewport (400) past it.
        val walled = stateAt(Offset(600f, 0f))
        assertEquals(Offset(700f, 0f), walled.coerceIntoResolvedShape(Offset(1000f, 0f)))

        val extended = stateAt(Offset(600f, 0f), frontierRight4 = true)
        assertEquals(Offset(1000f, 0f), extended.coerceIntoResolvedShape(Offset(1000f, 0f)))
        assertEquals(Offset(1100f, 0f), extended.coerceIntoResolvedShape(Offset(1300f, 0f)))
    }

    @Test
    fun `frontier extension composes with content padding`() {
        // Extended extent 1100, half viewport 200, zero blank space allowed: the
        // center may reach 1100 - 200 = 900.
        val state = stateAt(Offset(600f, 0f), frontierRight4 = true)
        state.contentPaddingPx = ResolvedContentPadding.all(0f)

        assertEquals(Offset(900f, 0f), state.coerceIntoResolvedShape(Offset(1000f, 0f)))
    }

    @Test
    fun `frontier inside the resolved shape opens nothing`() {
        // An unmeasured item whose anchored position lies within the already-resolved
        // extent is not a frontier on any side, the bounds stay put.
        val state = stateAt(Offset(600f, 0f), frontierRight4 = true)
        state.provisionalPositions.update(
            infos = emptyList(),
            graph = state.graph,
            itemByKey = state.itemByKey,
            resolved = mutableScatterMapOf(),
            awaitingMeasure = mapOf<Any, Rect>("right-4" to Rect(400f, 0f, 400f, 0f)).toScatterMap(),
            cachedSize = { null },
            isRtl = false,
        )

        assertEquals(Offset(700f, 0f), state.coerceIntoResolvedShape(Offset(1000f, 0f)))
    }

    @Test
    fun `content padding scales with zoom like the viewport extents`() {
        // Zoomed to 2x the viewport spans 200x200 surface units, and the 50px padding
        // becomes 25 surface units: the center may reach 700 - 100 + 25 = 625.
        val state = stateAt(Offset(600f, 0f))
        state.zoom = 2f
        state.contentPaddingPx = ResolvedContentPadding.all(50f)

        assertEquals(Offset(625f, 0f), state.coerceIntoResolvedShape(Offset(700f, 0f)))
    }

    @Test
    fun `asymmetric padding centers small content within the unobstructed area`() {
        // A 100px bottom-bar budget and none at the top: the arm (200 tall against a
        // 400 viewport) pins 50px above the physical center, centered between the
        // padded edges, clear of the bar.
        val state = stateAt(Offset(400f, 0f))
        state.contentPaddingPx = ResolvedContentPadding(left = 0f, top = 0f, right = 0f, bottom = 100f)

        assertEquals(Offset(400f, 50f), state.coerceIntoResolvedShape(Offset(400f, 80f)))
    }

    // --- Out-of-bounds settling --------------------------------------------------

    @Test
    fun `an offset inside the shape needs no settling`() {
        assertEquals(null, stateAt(Offset(400f, 0f)).outOfBoundsResting())
    }

    @Test
    fun `an offset beyond the range settles to the nearest allowed point`() {
        // Hovering above the right arm: its y range is [-100, 100], so the resting
        // point pulls straight down to the range edge, x is already legal.
        assertEquals(Offset(400f, -100f), stateAt(Offset(400f, -250f)).outOfBoundsResting())
    }

    @Test
    fun `settling respects the content padding`() {
        // With a fixed padding of zero the vertical range collapses to the arm's
        // midline, an offset above it rests exactly there.
        val state = stateAt(Offset(400f, -250f))
        state.contentPaddingPx = ResolvedContentPadding.all(0f)

        assertEquals(Offset(400f, 0f), state.outOfBoundsResting())
    }

    @Test
    fun `per-side padding budgets apply to their own edges`() {
        // Travelling to the arm's end: the END budget (120) grants more room than the
        // start budget would, the bound is maxX - halfW + 120 = 620.
        val state = stateAt(Offset(400f, 0f))
        state.contentPaddingPx = ResolvedContentPadding(left = 0f, top = 0f, right = 120f, bottom = 0f)

        assertEquals(Offset(620f, 0f), state.coerceIntoResolvedShape(Offset(700f, 0f)))
    }
}
