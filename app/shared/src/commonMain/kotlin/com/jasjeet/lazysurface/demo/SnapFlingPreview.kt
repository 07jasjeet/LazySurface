package com.jasjeet.lazysurface.demo

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.center
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import com.jasjeet.lazysurface.LazySurface
import com.jasjeet.lazysurface.rememberSnapFlingBehavior
import com.jasjeet.lazysurface.LazySurfaceNeighbor.Alignment.End
import com.jasjeet.lazysurface.LazySurfaceNeighbor.Alignment.Free
import com.jasjeet.lazysurface.LazySurfaceNeighbor.Alignment.Start
import com.jasjeet.lazysurface.LazySurfaceNeighborsScope
import com.jasjeet.lazysurface.rememberLazySurfaceState
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview

private class CardSpec(
    val key: String,
    val width: Dp,
    val height: Dp,
    val subtitle: String? = null,
    /** Tapping this card flies to the given section. */
    val flyTo: String? = null,
    val neighbors: LazySurfaceNeighborsScope.() -> Unit,
)

private class SectionSpec(
    val anchor: String,
    val tint: (ColorScheme) -> Pair<Color, Color>,
    val cards: List<CardSpec>,
)

// Every positioning relation is single-axis with an exact alignment (or the anchor's
// diagonal two-relation spoke), so the whole graph stays contradiction-free: no
// warping, and the solver converges on the first sweep. Clusters grow away from the
// hub to keep the flight corridors between sections open.
//
// The ring between sections is closed with Alignment.Free links: pure adjacency.
// They give navigation direct section-to-section routes and open the scroll frontier
// along the ring, but never pull on the layout, which matters, because exact ring
// gaps would contradict the spokes (they differ by the hub's size) and warp the
// hexagon.
//
// Linking rule: section anchors never join other sections. Each section's outermost
// card joins the nearest card of the neighbouring section, so routes enter a section
// at its edge, the way the content actually meets.
/** Every key this preview uses, in one place. */
private object Hex {
    const val Hub = "hub"
    const val HubMetrics = "hub-metrics"
    const val HubTrend = "hub-trend"
    const val HubNote = "hub-note"
    const val HubJump = "hub-jump"
    const val East = "east"
    const val EastList = "east-list"
    const val EastMini = "east-mini"
    const val EastBanner = "east-banner"
    const val NorthEast = "north-east"
    const val NeGraph = "ne-graph"
    const val NeSide = "ne-side"
    const val NeChip = "ne-chip"
    const val NorthWest = "north-west"
    const val NwWide = "nw-wide"
    const val NwBox = "nw-box"
    const val West = "west"
    const val WestLog = "west-log"
    const val WestTiny = "west-tiny"
    const val SouthWest = "south-west"
    const val SwPanel = "sw-panel"
    const val SwSide = "sw-side"
    const val Relay1 = "relay-1"
    const val Relay2 = "relay-2"
    const val FarEast = "far-east"
    const val FeNote = "fe-note"
    const val SouthEast = "south-east"
    const val SeTall = "se-tall"
    const val SeNote = "se-note"
    const val SeDot = "se-dot"
    const val SeExtra = "se-extra"
}

private val Sections = listOf(
    SectionSpec(Hex.Hub, { it.primaryContainer to it.onPrimaryContainer }, listOf(
        CardSpec(Hex.Hub, 200.dp, 130.dp, "drag toward a section") { atPivot() },
        CardSpec(Hex.HubMetrics, 150.dp, 100.dp, "above, start-aligned") {
            above(Hex.Hub) aligned Start margin 14.dp
        },
        CardSpec(Hex.HubTrend, 120.dp, 160.dp, "beside") {
            endOf(Hex.Hub) aligned End margin 14.dp
        },
        CardSpec(Hex.HubNote, 210.dp, 70.dp, "below, end-aligned") {
            below(Hex.Hub) aligned End margin 14.dp
        },
        CardSpec(Hex.HubJump, 150.dp, 100.dp, "tap: fly to far-east", flyTo = Hex.FarEast) {
            below(Hex.HubNote) aligned Start margin 14.dp
        },
    )),
    SectionSpec(Hex.East, { it.secondaryContainer to it.onSecondaryContainer }, listOf(
        CardSpec(Hex.East, 220.dp, 140.dp, "section anchor") {
            endOf(Hex.Hub) margin 680.dp
        },
        CardSpec(Hex.EastList, 160.dp, 200.dp) {
            endOf(Hex.East) aligned Start margin 16.dp
            below(Hex.NeSide) aligned Free
        },
        CardSpec(Hex.EastMini, 110.dp, 80.dp) {
            endOf(Hex.EastList) aligned End margin 12.dp
        },
        CardSpec(Hex.EastBanner, 240.dp, 90.dp) {
            below(Hex.East) aligned End margin 16.dp
            above(Hex.SeNote) aligned Free
        },
    )),
    SectionSpec(Hex.NorthEast, { it.tertiaryContainer to it.onTertiaryContainer }, listOf(
        CardSpec(Hex.NorthEast, 220.dp, 140.dp, "section anchor") {
            endOf(Hex.Hub) margin 230.dp
            above(Hex.Hub) margin 640.dp
        },
        CardSpec(Hex.NeGraph, 200.dp, 150.dp) {
            above(Hex.NorthEast) aligned End margin 14.dp
            endOf(Hex.NwWide) aligned Free
        },
        CardSpec(Hex.NeSide, 130.dp, 110.dp) {
            endOf(Hex.NorthEast) aligned Start margin 14.dp
        },
        CardSpec(Hex.NeChip, 150.dp, 60.dp) {
            above(Hex.NeSide) margin 10.dp
        },
    )),
    SectionSpec(Hex.NorthWest, { it.errorContainer to it.onErrorContainer }, listOf(
        CardSpec(Hex.NorthWest, 220.dp, 140.dp, "section anchor") {
            startOf(Hex.Hub) margin 230.dp
            above(Hex.Hub) margin 640.dp
        },
        CardSpec(Hex.NwWide, 260.dp, 100.dp) {
            above(Hex.NorthWest) aligned Start margin 14.dp
        },
        CardSpec(Hex.NwBox, 120.dp, 120.dp) {
            startOf(Hex.NorthWest) aligned End margin 14.dp
        },
    )),
    SectionSpec(Hex.West, { it.surfaceVariant to it.onSurfaceVariant }, listOf(
        CardSpec(Hex.West, 220.dp, 140.dp, "section anchor") {
            startOf(Hex.Hub) margin 680.dp
        },
        CardSpec(Hex.WestLog, 170.dp, 120.dp) {
            startOf(Hex.West) aligned Start margin 16.dp
            below(Hex.NwBox) aligned Free
        },
        CardSpec(Hex.WestTiny, 90.dp, 90.dp) {
            startOf(Hex.WestLog) margin 12.dp
            above(Hex.SwSide) aligned Free
        },
    )),
    SectionSpec(Hex.SouthWest, { it.inverseSurface to it.inverseOnSurface }, listOf(
        CardSpec(Hex.SouthWest, 220.dp, 140.dp, "section anchor") {
            startOf(Hex.Hub) margin 230.dp
            below(Hex.Hub) margin 640.dp
        },
        CardSpec(Hex.SwPanel, 190.dp, 130.dp) {
            below(Hex.SouthWest) aligned End margin 14.dp
        },
        CardSpec(Hex.SwSide, 110.dp, 140.dp) {
            startOf(Hex.SouthWest) aligned Start margin 14.dp
        },
    )),
    // The deep-chain scenario: far-east sits behind two very large relays that stay
    // unmeasured until something approaches them, and it also carries a Free link to
    // the hub. The first flight from the hub rides the Free route, stalls (nothing
    // has positioned far-east yet), falls back to the positioning chain and hops the
    // relays as they measure in, once the position is known, later flights beeline.
    SectionSpec(Hex.FarEast, { it.surfaceContainerHigh to it.onSurface }, listOf(
        CardSpec(Hex.Relay1, 340.dp, 260.dp, "large, unmeasured until approached") {
            endOf(Hex.EastList) margin 60.dp
        },
        CardSpec(Hex.Relay2, 360.dp, 300.dp, "even larger") {
            endOf(Hex.Relay1) margin 60.dp
        },
        CardSpec(Hex.FarEast, 220.dp, 140.dp, "free-linked to the hub's edge") {
            endOf(Hex.Relay2) margin 60.dp
            endOf(Hex.HubTrend) aligned Free
        },
        CardSpec(Hex.FeNote, 180.dp, 90.dp, "end of the chain") {
            below(Hex.FarEast) margin 14.dp
        },
    )),
    SectionSpec(Hex.SouthEast, { it.surfaceContainerHighest to it.onSurface }, listOf(
        CardSpec(Hex.SouthEast, 220.dp, 140.dp, "section anchor") {
            endOf(Hex.Hub) margin 230.dp
            below(Hex.Hub) margin 640.dp
        },
        CardSpec(Hex.SeTall, 140.dp, 220.dp) {
            below(Hex.SouthEast) aligned Start margin 14.dp
            endOf(Hex.SwPanel) aligned Free
        },
        CardSpec(Hex.SeNote, 180.dp, 90.dp) {
            endOf(Hex.SouthEast) aligned End margin 14.dp
        },
        CardSpec(Hex.SeDot, 80.dp, 80.dp) {
            endOf(Hex.SeTall) aligned Start margin 12.dp
        },
        CardSpec(Hex.SeExtra, 150.dp, 70.dp) {
            below(Hex.SeNote) aligned End margin 12.dp
        },
    )),
)

/** Card keys per section anchor, straight from the specs. */
private val SectionCards: Map<String, List<String>> =
    Sections.associate { section -> section.anchor to section.cards.map { it.key } }

/** The section anchor each card belongs to. */
private val SectionOf: Map<String, String> =
    Sections.flatMap { section -> section.cards.map { it.key to section.anchor } }.toMap()

@Preview
@Composable
fun SnapFlingPreview(
    showRelationLines: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state = rememberLazySurfaceState()
    val scope = rememberCoroutineScope()

    /** The anchor the viewport is currently on, travel is measured from it. */
    var current by remember { mutableStateOf(Hex.Hub) }
    var navigatingTo by remember { mutableStateOf<String?>(null) }

    // The item closest to the middle of the screen right now, straight from
    // viewportRect. Purely visual (highlights the card).
    val centeredKey by remember(state) {
        derivedStateOf {
            val mid = state.viewportSize.center.toOffset()
            state.visibleItemsInfo
                .minByOrNull { (it.viewportRect.center - mid).getDistance() }
                ?.key
        }
    }

    fun navigate(destination: String) {
        if (navigatingTo != null) return
        navigatingTo = destination
        scope.launch {
            try {
                state.animateToItem(destination, spring(stiffness = Spring.StiffnessLow))
            } finally {
                // The anchor self-corrects from the centered card on the next
                // evaluation, wherever the flight actually ended.
                navigatingTo = null
            }
        }
    }

    // The anchor label follows whichever section's card is centered.
    LaunchedEffect(state) {
        snapshotFlow { centeredKey }.collect { key ->
            (key as? String)?.let { SectionOf[it]?.let { section -> current = section } }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazySurface(
            state = state,
            modifier = Modifier.fillMaxSize(),
            // The surface pager: released gestures always land centered on a
            // section anchor, momentum decides which one. All the policy this demo
            // used to hand-roll now lives in the library.
            flingBehavior = rememberSnapFlingBehavior(state),
        ) {
            Sections.forEach { section ->
                items(
                    items = section.cards,
                    key = { it.key },
                    neighbors = { card -> card.neighbors(this) },
                ) { card ->
                    SectionCard(
                        spec = card,
                        tint = section.tint(MaterialTheme.colorScheme),
                        highlighted = centeredKey == card.key,
                        onTap = card.flyTo?.let { destination -> { navigate(destination) } },
                        modifier = Modifier.relationLines(state, card.key, showRelationLines),
                    )
                }
            }
        }

        Text(
            text = navigatingTo?.let { "flying to $it (touch to take over)" }
                ?: "at $current: fling toward a section to snap there",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(contentPadding)
                .padding(bottom = 48.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun SectionCard(
    spec: CardSpec,
    tint: Pair<Color, Color>,
    highlighted: Boolean,
    onTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val (container, onContainer) = tint
    val border by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primary else Color.Transparent,
    )
    Column(
        modifier = modifier
            .size(spec.width, spec.height)
            .border(3.dp, border, RoundedCornerShape(18.dp))
            .background(container, RoundedCornerShape(18.dp))
            .let { if (onTap != null) it.clickable(onClick = onTap) else it }
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            spec.key,
            style = MaterialTheme.typography.titleSmall,
            color = onContainer,
            textAlign = TextAlign.Center,
        )
        if (spec.subtitle != null && spec.height >= 90.dp) {
            Text(
                spec.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}
