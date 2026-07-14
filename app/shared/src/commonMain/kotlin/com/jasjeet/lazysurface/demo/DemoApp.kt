package com.jasjeet.lazysurface.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.jasjeet.lazysurface.demo.ui.theme.LazyLayoutTestTheme
import com.jasjeet.lazysurface.LazySurfaceDebug
import com.jasjeet.lazysurface.LazySurfacePerformance

private enum class Sample(val label: String) {
    Showcase("Showcase"),
    SnapFling("Snap-fling"),
    FreeBridge("Free bridge"),
    WorstCase("Worst case"),
    Rings("Rings"),
    Flower("Flower"),
}

/** Shared entry point: every platform app just calls this. */
@Composable
fun DemoApp() {
    LazyLayoutTestTheme {
        // The window's background: without this the platform default shows through
        // (white on iOS, and a light window even in night mode on Android).
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val backStack = rememberSaveable { mutableStateListOf(Sample.Showcase) }
            // Draw every item's declared relations as lines between the cards
            // (dotted = Free). Applies to all samples.
            var showRelationLines by rememberSaveable { mutableStateOf(true) }
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    SampleSwitcher(
                        current = backStack.last(),
                        onSelect = { sample ->
                            if (sample != backStack.last()) backStack.add(sample)
                        },
                        showRelations = showRelationLines,
                        onToggleLines = { showRelationLines = !showRelationLines },
                    )
                },
            ) { padding ->
                DisposableEffect(Unit) {
                    LazySurfaceDebug.logger = { println("LazySurface: $it") }
                    LazySurfacePerformance.monitor = { phase, timeTaken ->
                        println("LazySurface: $timeTaken taken for ${phase.name}")
                    }
                    onDispose {
                        LazySurfaceDebug.logger = null
                        LazySurfacePerformance.monitor = null
                    }
                }

                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.fillMaxSize(),
                    onBack = {
                        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                    },
                    entryProvider = { sample ->
                        NavEntry(sample) {
                            // The surfaces deliberately stay full-bleed (no
                            // contentPadding), only each sample's overlays respect
                            // the scaffold insets.
                            when (sample) {
                                Sample.Showcase -> LazySurfacePreview(showRelationLines, padding)
                                Sample.SnapFling -> SnapFlingPreview(showRelationLines, padding)
                                Sample.FreeBridge -> FreeBridgePreview(showRelationLines, padding)
                                Sample.WorstCase -> WorstCasePreview(showRelationLines, padding)
                                Sample.Rings -> RingsPreview(showRelationLines, padding)
                                Sample.Flower -> FlowerPreview(showRelationLines, padding)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SampleSwitcher(
    current: Sample,
    onSelect: (Sample) -> Unit,
    showRelations: Boolean,
    onToggleLines: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
    ) {
        FilledTonalButton(onClick = onToggleLines) {
            Text(if (showRelations) "Relations ✓" else "Relations")
        }
        Spacer(Modifier.width(8.dp))
        Box {
            FilledTonalButton(onClick = { expanded = true }) {
                Text("${current.label}  ▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Sample.entries.forEach { sample ->
                    DropdownMenuItem(
                        text = { Text(sample.label) },
                        onClick = {
                            expanded = false
                            onSelect(sample)
                        },
                    )
                }
            }
        }
    }
}
