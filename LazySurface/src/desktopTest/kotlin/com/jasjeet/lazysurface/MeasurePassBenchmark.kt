package com.jasjeet.lazysurface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory

/**
 * End-to-end measure-pass benchmark, public API only so the same file runs against
 * both the map-addressed and the index-addressed implementation (the world is
 * detected via reflection and tagged in the output).
 *
 * A 60x50 grid (3000 items) is chained pivot -> column spine -> rows, so every pass
 * resolves the full graph while only ~200 items are composed. Two scenarios:
 *  - steady-scroll: a small pan every frame, geometry in surface space unchanged,
 *    the memo-replay / publish-gate path, i.e. every frame of a plain scroll.
 *  - active-geometry: one mid-viewport item toggles its size every frame, the full
 *    resolve + solve + publish path.
 *
 * Not a JMH rig: single JVM, wall clock, allocation counts from ThreadMXBean. Meant
 * for A/B deltas on the same machine, not absolute numbers.
 */
class MeasurePassBenchmark {
    private val rows = 60
    private val cols = 50

    private fun key(r: Int, c: Int) = "i$r-$c"

    @Test
    fun `measure pass benchmark`() {
        val world = try {
            Class.forName("com.jasjeet.lazysurface.ResolveScratch")
            "indexed(new)"
        } catch (e: ClassNotFoundException) {
            "map(old)"
        }

        val pitch = 56f // 44px item + 12px margin at density 1
        var bump by mutableStateOf(false)
        val state = LazySurfaceState(
            initialOffset = Offset(cols / 2 * pitch, rows / 2 * pitch),
        )

        val phaseNanos = LongArray(LazySurfacePerformance.Phase.entries.size)
        LazySurfacePerformance.monitor = { phase, duration ->
            phaseNanos[phase.ordinal] += duration.inWholeNanoseconds
        }
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val threadId = Thread.currentThread().id

        val report = StringBuilder("world=$world\n")

        try {
            ImageComposeScene(width = 800, height = 800, density = Density(1f)) {
                LazySurface(state = state, settleIntoBounds = false) {
                    for (r in 0 until rows) {
                        for (c in 0 until cols) {
                            item(
                                key = key(r, c),
                                neighbors = {
                                    when {
                                        r == 0 && c == 0 -> atPivot()
                                        c == 0 -> below(key(r - 1, 0), margin = 12.dp)
                                        else -> endOf(key(r, c - 1), margin = 12.dp)
                                    }
                                },
                            ) {
                                val extra = if (bump && r == rows / 2 && c == cols / 2) 1.dp else 0.dp
                                Box(Modifier.size(44.dp + extra))
                            }
                        }
                    }
                }
            }.use { scene ->
                var nanoTime = 0L
                fun frame(pan: Offset?) {
                    pan?.let(state::dispatchRawDelta)
                    Snapshot.sendApplyNotifications()
                    nanoTime += 16_666_667L
                    scene.render(nanoTime)
                }

                // A square wander, 2px per frame, turning every 100 frames: stays
                // well inside the grid, columns enter/leave the viewport regularly.
                fun panFor(i: Int): Offset = when ((i / 100) % 4) {
                    0 -> Offset(2f, 0f)
                    1 -> Offset(0f, 2f)
                    2 -> Offset(-2f, 0f)
                    else -> Offset(0f, -2f)
                }

                fun runScenario(name: String, frames: Int, perFrame: (Int) -> Unit) {
                    phaseNanos.fill(0L)
                    val allocBefore = mx.getThreadAllocatedBytes(threadId)
                    val wallBefore = System.nanoTime()
                    repeat(frames) { perFrame(it) }
                    val wallNanos = System.nanoTime() - wallBefore
                    val allocBytes = mx.getThreadAllocatedBytes(threadId) - allocBefore
                    val measure = phaseNanos[LazySurfacePerformance.Phase.Measure.ordinal]
                    val resolve = phaseNanos[LazySurfacePerformance.Phase.Resolve.ordinal]
                    val solve = phaseNanos[LazySurfacePerformance.Phase.Solve.ordinal]
                    report.append(
                        "scenario=$name frames=$frames " +
                            "wallMsPerFrame=${"%.3f".format(wallNanos / 1e6 / frames)} " +
                            "measureUsPerFrame=${"%.1f".format(measure / 1e3 / frames)} " +
                            "resolveUsPerFrame=${"%.1f".format(resolve / 1e3 / frames)} " +
                            "solveUsPerFrame=${"%.1f".format(solve / 1e3 / frames)} " +
                            "allocKbPerFrame=${"%.1f".format(allocBytes / 1024.0 / frames)}\n"
                    )
                }

                // Warmup: JIT both the scroll and the geometry-change paths.
                repeat(400) { frame(panFor(it)) }
                repeat(200) { bump = !bump; frame(null) }
                repeat(200) { frame(panFor(it)) }

                runScenario("steady-scroll", frames = 1500) { frame(panFor(it)) }
                runScenario("active-geometry", frames = 800) { bump = !bump; frame(null) }
            }
        } finally {
            LazySurfacePerformance.monitor = null
        }

        println(report)
        File(BenchOutputPath).appendText(report.toString())
    }

    private companion object {
        const val BenchOutputPath =
            "/private/tmp/claude-501/-Users-jasjeetsingh-AndroidStudioProjects-LazyLayoutTest/5061e8bd-453f-46fa-a22d-d2e61fd5f149/scratchpad/lazysurface-bench.txt"
    }
}
