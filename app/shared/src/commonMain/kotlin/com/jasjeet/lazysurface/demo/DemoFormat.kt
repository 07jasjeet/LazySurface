package com.jasjeet.lazysurface.demo

import kotlin.math.roundToInt

internal fun Float.format2(): String {
    val scaled = (this * 100).roundToInt()
    val whole = scaled / 100
    val cents = (if (scaled < 0) -scaled else scaled) % 100
    return "$whole.${cents.toString().padStart(2, '0')}"
}

internal fun Float.format0(): String = roundToInt().toString()
