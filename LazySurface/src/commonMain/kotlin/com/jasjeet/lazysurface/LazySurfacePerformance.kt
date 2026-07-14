package com.jasjeet.lazysurface

import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Opt-in performance measurement of the surface's internal phases.
 *
 * Set [monitor] to receive the duration of every measured phase, e.g.:
 * ```
 * LazySurfacePerformance.monitor = { phase, duration ->
 *     frameStats.record(phase, duration)
 * }
 * ```
 * The default `null` disables measurement completely: no clock reads, no allocations.
 *
 * The monitor is invoked on the thread running the phase (the UI thread) once per
 * phase per frame, so it must be cheap, hand data off instead of processing in place.
 * [Duration] is a value class, so the callback itself does not allocate.
 */
object LazySurfacePerformance {
    /**
     * A phase the surface measures. [Measure] is the whole layout pass, [Resolve]
     * (position resolution, including composing and measuring items) and [Solve]
     * (the constraint-solver refinement) are nested inside it, so their durations
     * are part of the surrounding Measure duration, not additional to it.
     */
    enum class Phase { Measure, Resolve, Solve }

    @Volatile
    var monitor: ((phase: Phase, duration: Duration) -> Unit)? = null
}

internal inline fun <T> measured(phase: LazySurfacePerformance.Phase, block: () -> T): T {
    val monitor = LazySurfacePerformance.monitor ?: return block()
    val mark = TimeSource.Monotonic.markNow()
    return try {
        block()
    } finally {
        monitor(phase, mark.elapsedNow())
    }
}
