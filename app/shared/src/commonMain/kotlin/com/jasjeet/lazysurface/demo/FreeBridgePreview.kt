package com.jasjeet.lazysurface.demo

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.center
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import com.jasjeet.lazysurface.LazySurface
import com.jasjeet.lazysurface.LazySurfaceDebug
import com.jasjeet.lazysurface.LazySurfaceNeighbor.Alignment.Free
import com.jasjeet.lazysurface.rememberLazySurfaceState
import com.jasjeet.lazysurface.rememberSnapFlingBehavior
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Two long straight chains diverge from the origin: one grows south, one grows west.
 * A single Free relation bridges their far ends. The demo makes the lazy pipeline
 * visible at its extremes:
 *
 * **What is measured.** Only items whose (provisionally placed) rect falls inside the
 * resolution region, the viewport inflated by one extra viewport per side, are
 * composed and measured. Standing at the origin measures the head of both chains,
 * everything beyond parks: the first unmeasured item of each chain waits one hop past
 * the last measured one, and the items behind it are only *derivable*: their
 * positions telescope through the graph with zero extents for unknown sizes, so the
 * far end's derived position underestimates the true distance until the chain is
 * actually measured.
 *
 * **What a swipe does.** Scroll room is granted by what the viewport can currently
 * see: displayed items, their declared neighbours, and the parked frontier item of a
 * chain being scrolled along. At the south end the bridge's far side (west end) has
 * no measured or parked rect, so the Free link grants no scroll room and a fling
 * outwards snaps back: swipes are geometric, they never follow graph edges.
 *
 * **What the bridge is for.** Tap a bridge card: [com.jasjeet.lazysurface.LazySurfaceState.animateToItem]
 * routes across the Free link (one hop) and aims at the far end's derived position,
 * re-aiming every frame. The clamp walls bend the straight-line aim into the
 * L-shaped path around the corner, and if the shortcut stalls entirely the journey
 * replans along positioning edges through the origin. Either way the viewport sweeps
 * the unmeasured chain, items measure in as the resolution region reaches them, the
 * aim refines outwards with each measurement, and the flight lands centered on the
 * true far end. Watch the run in Logcat via [LazySurfaceDebug].
 */
private const val ChainLength = 14

/** Every key this preview uses, in one place. */
private object Bridge {
    const val Origin = "origin"
    fun south(index: Int) = "south-$index"
    fun west(index: Int) = "west-$index"
    val SouthEnd = south(ChainLength)
    val WestEnd = west(ChainLength)
}

@Preview
@Composable
fun FreeBridgePreview(
    showRelationLines: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state = rememberLazySurfaceState()
    val scope = rememberCoroutineScope()

    var navigatingTo by remember { mutableStateOf<String?>(null) }

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
                navigatingTo = null
            }
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
            flingBehavior = rememberSnapFlingBehavior(state),
        ) {
            item(key = Bridge.Origin, neighbors = { atPivot() }) {
                BridgeCard(
                    modifier = Modifier.relationLines(state, Bridge.Origin, showRelationLines),
                    key = Bridge.Origin,
                    subtitle = "chains diverge south and west",
                    tint = MaterialTheme.colorScheme.primaryContainer to
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    highlighted = centeredKey == "origin",
                )
            }
            for (index in 1..ChainLength) {
                val south = Bridge.south(index)
                item(
                    key = south,
                    neighbors = {
                        below(if (index == 1) Bridge.Origin else Bridge.south(index - 1)) margin 40.dp
                        // The bridge: pure adjacency between the two chain ends.
                        // It routes navigation and never positions either end.
                        if (index == ChainLength) endOf(Bridge.WestEnd) aligned Free
                    },
                ) {
                    BridgeCard(
                        modifier = Modifier.relationLines(state, south, showRelationLines),
                        key = south,
                        subtitle = when (index) {
                            ChainLength -> "tap: cross the free bridge to ${Bridge.WestEnd}"
                            else -> null
                        },
                        tint = MaterialTheme.colorScheme.secondaryContainer to
                            MaterialTheme.colorScheme.onSecondaryContainer,
                        highlighted = centeredKey == south,
                        onTap = if (index == ChainLength) {
                            { navigate(Bridge.WestEnd) }
                        } else null,
                    )
                }
                val west = Bridge.west(index)
                item(
                    key = west,
                    neighbors = {
                        startOf(if (index == 1) Bridge.Origin else Bridge.west(index - 1)) margin 40.dp
                    },
                ) {
                    BridgeCard(
                        modifier = Modifier.relationLines(state, west, showRelationLines),
                        key = west,
                        subtitle = when (index) {
                            ChainLength -> "tap: cross the free bridge to ${Bridge.SouthEnd}"
                            else -> null
                        },
                        tint = MaterialTheme.colorScheme.tertiaryContainer to
                            MaterialTheme.colorScheme.onTertiaryContainer,
                        highlighted = centeredKey == west,
                        onTap = if (index == ChainLength) {
                            { navigate(Bridge.SouthEnd) }
                        } else null,
                    )
                }
            }
        }

        Text(
            text = navigatingTo?.let { "flying to $it (touch to take over)" }
                ?: "at ${centeredKey ?: "origin"}: follow a chain to its end",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(contentPadding)
                .padding(bottom = 48.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun BridgeCard(
    key: String,
    modifier: Modifier = Modifier,
    subtitle: String?,
    tint: Pair<Color, Color>,
    highlighted: Boolean,
    onTap: (() -> Unit)? = null,
) {
    val (container, onContainer) = tint
    val border by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primary else Color.Transparent,
    )
    Column(
        modifier = modifier
            .size(200.dp, 110.dp)
            .border(3.dp, border, RoundedCornerShape(18.dp))
            .background(container, RoundedCornerShape(18.dp))
            .let { if (onTap != null) it.clickable(onClick = onTap) else it }
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.titleSmall,
            color = onContainer,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}
