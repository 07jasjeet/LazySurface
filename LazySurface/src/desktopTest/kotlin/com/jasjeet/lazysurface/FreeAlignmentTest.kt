package com.jasjeet.lazysurface

import androidx.collection.mutableScatterMapOf
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LazySurfaceNeighbor.Alignment.Free]: adjacency without geometry. The relation
 * routes and hard-separates but must not pull items into alignment or exact gaps.
 */
class FreeAlignmentTest {

    private fun info(key: String, vararg neighbors: LazySurfaceNeighbor) = LazySurfaceItemInfo(
        key = key,
        neighbors = neighbors.toList(),
        contentType = null,
    )

    @Test
    fun `a free link between settled items moves nothing`() {
        // Two items far apart and wildly misaligned relative to the declared side.
        val resolved = mutableScatterMapOf<Any, Rect>(
            "a" to Rect(Offset(0f, 0f), Size(100f, 100f)),
            "b" to Rect(Offset(600f, -400f), Size(100f, 100f)),
        )
        val constraints = buildRelationConstraints(
            infos = listOf(
                info("a", LazySurfaceNeighbor.AtPivot),
                info("b", LazySurfaceNeighbor.endOf("a", LazySurfaceNeighbor.Alignment.Free)),
            ),
            itemByKey = mapOf<Any, LazySurfaceItemInfo>(
                "a" to info("a"), "b" to info("b"),
            ).toScatterMap(),
        ) { it.neighbors }

        val moved = CompiledConstraints(constraints, isRtl = false).solve(resolved)

        assertFalse("free link must not attract or align", moved)
        assertEquals(Rect(Offset(600f, -400f), Size(100f, 100f)), resolved["b"])
    }

    @Test
    fun `a free floor ignores margins, separating only on real overlap`() {
        // The relation carries a large margin: a positioning relation would hold
        // them 400 apart. They sit only 50 px apart but do NOT overlap, so the Free
        // floor (a pure never-overlap floor) must leave them where they are (not
        // enforce the gap).
        val aInfo = LazySurfaceItemInfo(
            key = "a",
            neighbors = listOf(LazySurfaceNeighbor.AtPivot),
            contentType = null,
        )
        val bInfo = LazySurfaceItemInfo(
            key = "b",
            neighbors = listOf(
                LazySurfaceNeighbor.endOf("a", LazySurfaceNeighbor.Alignment.Free).copy(margin = 400f),
            ),
            contentType = null,
        )
        val resolved = mutableScatterMapOf<Any, Rect>(
            "a" to Rect(Offset(0f, 0f), Size(100f, 100f)),
            "b" to Rect(Offset(150f, 0f), Size(100f, 100f)),
        )
        val constraints = buildRelationConstraints(
            infos = listOf(aInfo, bInfo),
            itemByKey = mapOf<Any, LazySurfaceItemInfo>("a" to aInfo, "b" to bInfo).toScatterMap(),
        ) { it.neighbors }

        val moved = CompiledConstraints(constraints, isRtl = false).solve(resolved)

        assertFalse("a non-overlapping free link must not enforce the margin gap", moved)
        assertEquals(Rect(Offset(150f, 0f), Size(100f, 100f)), resolved["b"])
    }

    @Test
    fun `a free link between cross-axis disjoint items moves nothing`() {
        // Full-width cards stacked in a column: their x projections overlap
        // completely, but the boxes never intersect. A horizontal Free link between
        // them must not behave like a positioning relation and shove one card
        // sideways until it clears the other.
        val resolved = mutableScatterMapOf<Any, Rect>(
            "a" to Rect(Offset(0f, 0f), Size(300f, 100f)),
            "b" to Rect(Offset(0f, 150f), Size(300f, 100f)),
        )
        val constraints = buildRelationConstraints(
            infos = listOf(
                info("a", LazySurfaceNeighbor.AtPivot),
                info("b", LazySurfaceNeighbor.endOf("a", LazySurfaceNeighbor.Alignment.Free)),
            ),
            itemByKey = mapOf<Any, LazySurfaceItemInfo>(
                "a" to info("a"), "b" to info("b"),
            ).toScatterMap(),
        ) { it.neighbors }

        val moved = CompiledConstraints(constraints, isRtl = false).solve(resolved)

        assertFalse("a free link must not resolve axis-projection overlap", moved)
        assertEquals(Rect(Offset(0f, 150f), Size(300f, 100f)), resolved["b"])
    }

    @Test
    fun `a free link still pushes overlapping items apart`() {
        val resolved = mutableScatterMapOf<Any, Rect>(
            "a" to Rect(Offset(0f, 0f), Size(100f, 100f)),
            // b declares a on its start side but overlaps it.
            "b" to Rect(Offset(40f, 10f), Size(100f, 100f)),
        )
        val constraints = buildRelationConstraints(
            infos = listOf(
                info("a", LazySurfaceNeighbor.AtPivot),
                info("b", LazySurfaceNeighbor.endOf("a", LazySurfaceNeighbor.Alignment.Free)),
            ),
            itemByKey = mapOf<Any, LazySurfaceItemInfo>(
                "a" to info("a"), "b" to info("b"),
            ).toScatterMap(),
        ) { it.neighbors }

        CompiledConstraints(constraints, isRtl = false).solve(resolved)

        val a = resolved["a"]!!
        val b = resolved["b"]!!
        assertTrue("hard floor must separate them, a=$a b=$b", b.left >= a.right)
        // The cross axis is free: no alignment pull happened.
        assertEquals(10f, b.top, 0.01f)
    }

    @Test
    fun `a free link is a routing edge`() {
        val state = LazySurfaceState()
        val infos = listOf(
            info("hub", LazySurfaceNeighbor.AtPivot),
            info("east", LazySurfaceNeighbor.endOf("hub")),
            info(
                "north-east",
                LazySurfaceNeighbor.endOf("hub"),
                LazySurfaceNeighbor.above("east", LazySurfaceNeighbor.Alignment.Free),
            ),
        )
        state.updateItems(infos, infos.associateBy { it.key as Any }.toScatterMap())

        assertEquals(
            listOf("north-east"),
            state.routeTowards("east", "north-east"),
        )
    }

    private val frameClock = object : MonotonicFrameClock {
        private var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            time += 16_000_000L
            return onFrame(time)
        }
    }

    @Test
    fun `positioning-only routes exclude free edges`() {
        val state = LazySurfaceState()
        val infos = listOf(
            // The shortcut: x links d directly, without geometry.
            info(
                "x",
                LazySurfaceNeighbor.AtPivot,
                LazySurfaceNeighbor.startOf("d", LazySurfaceNeighbor.Alignment.Free),
            ),
            info("a", LazySurfaceNeighbor.endOf("x")),
            info("d", LazySurfaceNeighbor.endOf("a")),
        )
        state.updateItems(infos, infos.associateBy { it.key as Any }.toScatterMap())

        assertEquals(listOf<Any>("d"), state.routeTowards("x", "d"))
        assertEquals(
            listOf<Any>("a", "d"),
            state.routeTowards("x", "d", LazySurfaceState.RouteEdges.PositioningOnly),
        )
    }

    @Test
    fun `a shortcut stalled on a free-only hop hands over to the positioning chain`() {
        val state = LazySurfaceState()
        val infos = listOf(
            // "ghost" is a routing hop with no derivable position: its only
            // positioning relation dangles (the runtime shape a removal-heal can
            // leave behind). The shortest route to d rides it, the walk must stall
            // there and the positioning phase must still reach d.
            info(
                "x",
                LazySurfaceNeighbor.AtPivot,
                LazySurfaceNeighbor.startOf("ghost", LazySurfaceNeighbor.Alignment.Free),
            ),
            info(
                "ghost",
                LazySurfaceNeighbor.endOf("missing-anchor"),
                LazySurfaceNeighbor.startOf("d", LazySurfaceNeighbor.Alignment.Free),
            ),
            info("a", LazySurfaceNeighbor.endOf("x")),
            info("d", LazySurfaceNeighbor.endOf("a")),
        )
        state.updateItems(infos, infos.associateBy { it.key as Any }.toScatterMap())
        state.viewportSize = IntSize(400, 800)
        state.lastKnownRects = mapOf<Any, Rect>(
            "x" to Rect(Offset(-50f, -50f), Size(100f, 100f)),
        ).toScatterMap()
        state.provisionalPositions.update(
            infos = infos,
            graph = state.graph,
            itemByKey = state.itemByKey,
            resolved = state.lastKnownRects,
            awaitingMeasure = mutableScatterMapOf(),
            cachedSize = { null },
            isRtl = false,
        )

        runBlocking(frameClock) {
            state.animateToItem("d")
        }

        val derivedD = state.provisionalPositions.positionOf("d")!!.center
        assertEquals(derivedD.x, state.offset.x, 1f)
        assertEquals(derivedD.y, state.offset.y, 1f)
    }

    @Test
    fun `a free link contributes no positioning constraints`() {
        val state = LazySurfaceState()
        val infos = listOf(
            // hub positions floater, floater's own declaration is Free only.
            info("hub", LazySurfaceNeighbor.AtPivot, LazySurfaceNeighbor.startOf("floater")),
            info("floater", LazySurfaceNeighbor.below("hub", LazySurfaceNeighbor.Alignment.Free)),
        )
        state.updateItems(infos, infos.associateBy { it.key as Any }.toScatterMap())

        val node = state.graph["floater"]!!
        assertTrue("the free declaration adds no own constraint", node.ownConstraints.isEmpty())
        assertTrue("but the adjacency is kept", node.adjacent.contains("hub"))
    }
}

/**
 * An unpositioned target must plan on the positioning chain immediately: riding a
 * Free shortcut first means flying to its last positioned waypoint, settling in
 * vain, and backtracking, a visible detour for nothing.
 */
class PlanTimeRouteModeTest {

    private fun info(key: String, vararg neighbors: LazySurfaceNeighbor) = LazySurfaceItemInfo(
        key = key,
        neighbors = neighbors.toList(),
        contentType = null,
    )

    private val frameClock = object : MonotonicFrameClock {
        private var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            time += 16_000_000L
            return onFrame(time)
        }
    }

    @Test
    fun `an underivable target launches no shortcut chase`() {
        val state = LazySurfaceState()
        val infos = listOf(
            info("x", LazySurfaceNeighbor.AtPivot),
            // The bait: a positioned waypoint free-linked from x and on to d. d's
            // only positioning relation dangles (what a removal-heal can leave), so
            // the graph can derive no position for it, the only honest outcome is
            // to not fly at all.
            info(
                "bait",
                LazySurfaceNeighbor.above("x"),
                LazySurfaceNeighbor.endOf("d", LazySurfaceNeighbor.Alignment.Free),
            ),
            info("d", LazySurfaceNeighbor.endOf("missing-anchor")),
        )
        state.updateItems(infos, infos.associateBy { it.key as Any }.toScatterMap())
        state.viewportSize = IntSize(400, 800)
        val baitCenter = Offset(0f, 500f)
        state.lastKnownRects = mapOf<Any, Rect>(
            "x" to Rect(Offset(-50f, -50f), Size(100f, 100f)),
            "bait" to Rect(Offset(-50f, 450f), Size(100f, 100f)),
        ).toScatterMap()

        var closestToBait = Float.MAX_VALUE
        val clock = object : MonotonicFrameClock {
            private var time = 0L
            override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
                time += 16_000_000L
                val result = onFrame(time)
                closestToBait = minOf(closestToBait, (state.offset - baitCenter).getDistance())
                return result
            }
        }

        runBlocking(clock) {
            state.animateToItem("d")
        }

        // No movement at all: the target is not positioning-connected, so both
        // phases decline instead of chasing the positioned shortcut hop.
        assertEquals(0f, (state.offset - Offset.Zero).getDistance(), 1f)
        assertTrue(
            "flight should not have chased the shortcut, got within $closestToBait px of it",
            closestToBait > 300f,
        )
    }
}

/**
 * [ProvisionalPositions]: every positioning-connected item gets a transitive
 * provisional position (unmeasured extents as zero, cached sizes where known), so a
 * reachable navigation target always has an aim point.
 */
class ProvisionalClosureTest {

    private fun info(key: String, vararg neighbors: LazySurfaceNeighbor) = LazySurfaceItemInfo(
        key = key,
        neighbors = neighbors.toList(),
        contentType = null,
    )

    private fun state(vararg infos: LazySurfaceItemInfo): LazySurfaceState {
        val state = LazySurfaceState()
        val list = infos.toList()
        state.updateItems(list, list.associateBy { it.key as Any }.toScatterMap())
        return state
    }

    @Test
    fun `a deep unmeasured chain collapses onto the last known edge`() {
        val infos = listOf(
            info("x", LazySurfaceNeighbor.AtPivot),
            info("a", LazySurfaceNeighbor.endOf("x")),
            info("b", LazySurfaceNeighbor.endOf("a")),
        )
        val state = state(*infos.toTypedArray())

        val positions = ProvisionalPositions()
        positions.update(
            infos = infos,
            graph = state.graph,
            itemByKey = state.itemByKey,
            resolved = mapOf<Any, Rect>("x" to Rect(Offset.Zero, Size(100f, 100f))).toScatterMap(),
            awaitingMeasure = mutableScatterMapOf(),
            cachedSize = { null },
            isRtl = false,
        )

        // Zero extents: both hang off x's right edge, vertically centered on it.
        assertEquals(Offset(100f, 50f), positions.positionOf("a")!!.center)
        assertEquals(Offset(100f, 50f), positions.positionOf("b")!!.center)
    }

    @Test
    fun `cached sizes stretch the chain to real extents`() {
        val infos = listOf(
            info("x", LazySurfaceNeighbor.AtPivot),
            info("a", LazySurfaceNeighbor.endOf("x")),
            info("b", LazySurfaceNeighbor.endOf("a")),
        )
        val state = state(*infos.toTypedArray())

        val positions = ProvisionalPositions()
        positions.update(
            infos = infos,
            graph = state.graph,
            itemByKey = state.itemByKey,
            resolved = mapOf<Any, Rect>("x" to Rect(Offset.Zero, Size(100f, 100f))).toScatterMap(),
            awaitingMeasure = mutableScatterMapOf(),
            cachedSize = { key -> if (key == "a") androidx.compose.ui.unit.IntSize(40, 20) else null },
            isRtl = false,
        )

        assertEquals(Rect(100f, 40f, 140f, 60f), positions.positionOf("a"))
        // b chains off a's REAL right edge.
        assertEquals(Offset(140f, 50f), positions.positionOf("b")!!.center)
    }

    @Test
    fun `items without a live positioning path derive nothing`() {
        val infos = listOf(
            info("x", LazySurfaceNeighbor.AtPivot, LazySurfaceNeighbor.startOf("f", LazySurfaceNeighbor.Alignment.Free)),
            // f's positioning relation dangles: routable via the free link, but
            // nothing can place it.
            info("f", LazySurfaceNeighbor.endOf("missing-anchor")),
        )
        val state = state(*infos.toTypedArray())

        val positions = ProvisionalPositions()
        positions.update(
            infos = infos,
            graph = state.graph,
            itemByKey = state.itemByKey,
            resolved = mapOf<Any, Rect>("x" to Rect(Offset.Zero, Size(100f, 100f))).toScatterMap(),
            awaitingMeasure = mutableScatterMapOf(),
            cachedSize = { null },
            isRtl = false,
        )

        assertFalse(positions.positionOf("f") != null)
    }

    @Test
    fun `a flight aims at the derived position of a deep target`() {
        val infos = listOf(
            info(
                "x",
                LazySurfaceNeighbor.AtPivot,
                LazySurfaceNeighbor.startOf("d", LazySurfaceNeighbor.Alignment.Free),
            ),
            info("a", LazySurfaceNeighbor.endOf("x")),
            info("d", LazySurfaceNeighbor.endOf("a")),
        )
        val state = state(*infos.toTypedArray())
        state.viewportSize = IntSize(400, 800)
        state.lastKnownRects = mapOf<Any, Rect>(
            "x" to Rect(Offset(-50f, -50f), Size(100f, 100f)),
        ).toScatterMap()
        // What a measure pass would compute: the closure over the whole chain.
        state.provisionalPositions.update(
            infos = infos,
            graph = state.graph,
            itemByKey = state.itemByKey,
            resolved = state.lastKnownRects,
            awaitingMeasure = mutableScatterMapOf(),
            cachedSize = { null },
            isRtl = false,
        )

        val frameClock = object : MonotonicFrameClock {
            private var time = 0L
            override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
                time += 16_000_000L
                return onFrame(time)
            }
        }
        runBlocking(frameClock) {
            state.animateToItem("d")
        }

        // No settling, no stall: the journey flew straight to d's derived point.
        val derivedD = state.provisionalPositions.positionOf("d")!!.center
        assertEquals(derivedD.x, state.offset.x, 1f)
        assertEquals(derivedD.y, state.offset.y, 1f)
    }
}

/**
 * Free-only connectivity is a declaration error: such an item could never resolve,
 * render, or be navigated to. Registration fails fast, validation lives in
 * [LazySurfaceContent] and inspects the raw declarations, so runtime removals that
 * heal relations away can never throw.
 */
class FreeOnlyValidationTest {

    private fun collect(content: LazySurfaceScope.() -> Unit) =
        LazySurfaceContent(androidx.compose.ui.unit.Density(1f), content)

    @Test
    fun `an item declaring only free relations throws`() {
        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            collect {
                item("hub", neighbors = { atPivot() }) {}
                item("floater", neighbors = {
                    endOf("hub", LazySurfaceNeighbor.Alignment.Free)
                }) {}
            }
        }
        assertTrue(error.message!!.contains("floater"))
    }

    @Test
    fun `an undeclared item targeted only by a free relation throws`() {
        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            collect {
                item("hub", neighbors = {
                    atPivot()
                    startOf("orphan", LazySurfaceNeighbor.Alignment.Free)
                }) {}
                item("orphan") {}
            }
        }
        assertTrue(error.message!!.contains("orphan"))
    }

    @Test
    fun `free-declaring items are fine once anything positions them`() {
        collect {
            // hub positions "edge" from its side, edge itself only declares free.
            item("hub", neighbors = {
                atPivot()
                startOf("edge")
            }) {}
            item("edge", neighbors = {
                below("hub", LazySurfaceNeighbor.Alignment.Free)
            }) {}
        } // must not throw
    }

    @Test
    fun `fully isolated items keep the documented silent behavior`() {
        collect {
            item("hub", neighbors = { atPivot() }) {}
            item("island") {}
        } // must not throw
    }
}
