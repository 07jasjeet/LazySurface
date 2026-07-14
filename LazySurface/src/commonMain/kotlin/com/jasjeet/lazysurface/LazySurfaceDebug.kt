package com.jasjeet.lazysurface

import kotlin.concurrent.Volatile

/**
 * Opt-in diagnostics for the surface's gesture, fling and clamp decisions.
 *
 * Set [logger] to receive trace lines, e.g. in the app's debug init:
 * ```
 * LazySurfaceDebug.logger = { Log.d("LazySurface", it) }
 * ```
 * The default `null` disables tracing, call sites don't even build the message then.
 */
object LazySurfaceDebug {
    @Volatile
    var logger: ((String) -> Unit)? = null

    internal inline fun log(message: () -> String) {
        logger?.invoke(message())
    }
}
