package com.jasjeet.lazysurface.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jasjeet.lazysurface.LazySurface
import com.jasjeet.lazysurface.rememberLazySurfaceState
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview

// A garden grown from ONE routine at two levels: flower cores ring the garden's
// pivot, and each core's petals ring that core, placeAround links items to any
// placed item, so a flower is just a cluster whose center happens to be a petal
// ring's anchor. Tapping a core flies the viewport to that flower.

private const val GardenPivot = "garden-pivot"

/** Distance between flower rings, wide enough that petal whorls never meet. */
private val FlowerSpacing = 220.dp

/** A petal's distance from its core. */
private val PetalSpacing = 6.dp

private val CoreSize = 48.dp
private val PetalSize = 40.dp
private const val PetalCount = 8

private data class FlowerPalette(val core: Color, val petal: Color)

private val flowerPalettes = listOf(
    FlowerPalette(core = Color(0xFFFFB300), petal = Color(0xFFF48FB1)), // rose
    FlowerPalette(core = Color(0xFFFFA000), petal = Color(0xFFB39DDB)), // lavender
    FlowerPalette(core = Color(0xFFFFB300), petal = Color(0xFF90CAF9)), // sky
    FlowerPalette(core = Color(0xFFFF8F00), petal = Color(0xFFFFCC80)), // marigold
    FlowerPalette(core = Color(0xFFFFB300), petal = Color(0xFFA5D6A7)), // mint
    FlowerPalette(core = Color(0xFFFFA000), petal = Color(0xFFEF9A9A)), // poppy
)

private fun flowerKey(flower: Int) = "f$flower"
private fun petalKey(flower: Int, petal: Int) = "f$flower-p$petal"

@Preview
@Composable
fun FlowerPreview(
    showRelationLines: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state = rememberLazySurfaceState()
    val scope = rememberCoroutineScope()
    var flowers by rememberSaveable { mutableIntStateOf(5) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazySurface(
            state = state,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = GardenPivot, neighbors = { atPivot() }) { Box(Modifier) }

            // The garden: flower cores ring the pivot.
            placeAround(
                center = GardenPivot,
                count = flowers,
                ringSpacing = FlowerSpacing,
                key = { flowerKey(it) },
            ) { flower, _ ->
                val palette = flowerPalettes[flower % flowerPalettes.size]
                Box(
                    Modifier
                        .relationLines(state, flowerKey(flower), showRelationLines)
                        .size(CoreSize)
                        .background(palette.core, CircleShape)
                        .clickable { scope.launch { state.animateToItem(flowerKey(flower)) } },
                )
            }

            // Each flower: petals ring their own core, not the pivot.
            repeat(flowers) { flower ->
                val palette = flowerPalettes[flower % flowerPalettes.size]
                placeAround(
                    center = flowerKey(flower),
                    count = PetalCount,
                    ringSpacing = PetalSpacing,
                    key = { petalKey(flower, it) },
                ) { petal, _ ->
                    Box(
                        Modifier
                            .relationLines(state, petalKey(flower, petal), showRelationLines)
                            .size(PetalSize)
                            .background(palette.petal, CircleShape),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(contentPadding)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "$flowers flowers",
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = flowers.toFloat(),
                onValueChange = { flowers = it.toInt().coerceAtLeast(1) },
                valueRange = 1f..24f, // rings 1 and 2 of the garden
                modifier = Modifier.width(280.dp),
            )
        }
    }
}
