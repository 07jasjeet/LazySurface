package com.jasjeet.lazysurface.demo

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jasjeet.lazysurface.LazySurface
import com.jasjeet.lazysurface.LazySurfaceItemScope
import com.jasjeet.lazysurface.LazySurfaceNeighbor
import com.jasjeet.lazysurface.LazySurfaceScope
import com.jasjeet.lazysurface.rememberLazySurfaceState
import org.jetbrains.compose.ui.tooling.preview.Preview


private const val PivotKey = "rings-pivot"

private val RingSpacing = 60.dp
private val ItemSize = 52.dp
private val PivotSize = 0.dp

@Preview
@Composable
fun RingsPreview(
    showRelationLines: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state = rememberLazySurfaceState()
    var count by rememberSaveable { mutableIntStateOf(40) } // rings 1-3ish
    // Tap an item to toggle it between its base size and 1.5x, a live probe of
    // how the arrangement reacts to a resizing item: the in-ring chains push
    // its neighbours along the edge, and the ray floors push outer layers out.
    var enlarged by remember { mutableStateOf(emptySet<Int>()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazySurface(
            state = state,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = PivotKey, neighbors = { atPivot() }) {
                Box(
                    Modifier
                        .size(PivotSize)
                        .background(MaterialTheme.colorScheme.onBackground, CircleShape),
                )
            }
            placeAround(
                center = PivotKey,
                count = count,
                ringSpacing = RingSpacing,
            ) { index, ring ->
                val tint = ringTints[(ring - 1) % ringTints.size]
                val size by animateDpAsState(if (index in enlarged) ItemSize * 1.5f else ItemSize)
                Box(
                    modifier = Modifier
                        .relationLines(state, index, showRelationLines)
                        .size(size)
                        .clip(CircleShape)
                        .background(tint)
                        .clickable {
                            enlarged = if (index in enlarged) enlarged - index else enlarged + index
                        },
                ) {
                    Text(
                        "$index",
                        modifier = Modifier.fillMaxSize().wrapContentSize(),
                        color = if (tint.luminance() > 0.55f) Color.Black else Color.White,
                        fontSize = 11.sp,
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
                "$count items, ${ringOf((count - 1).coerceAtLeast(0))} rings",
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = count.toFloat(),
                onValueChange = { count = it.toInt().coerceAtLeast(1) },
                valueRange = 1f..168f, // through ring 6
                modifier = Modifier.width(280.dp),
            )
        }
    }
}

private val ringTints = listOf(
    Color(0xFF4DB6AC),
    Color(0xFF7986CB),
    Color(0xFFFFB74D),
    Color(0xFFE57373),
    Color(0xFF81C784),
    Color(0xFFBA68C8),
)
