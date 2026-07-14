package com.jasjeet.lazysurface

import androidx.collection.MutableScatterMap

internal fun <V> Map<Any, V>.toScatterMap(): MutableScatterMap<Any, V> {
    val map = MutableScatterMap<Any, V>(size)
    forEach { (key, value) -> map[key] = value }
    return map
}
