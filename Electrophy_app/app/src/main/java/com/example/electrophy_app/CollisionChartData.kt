package com.example.electrophy_app

/** A frozen peak anchor can outlive a device's history during a disconnect/reset. */
internal fun collisionTimeBounds(points: List<AxisPoint>, anchor: Float?): Pair<Float, Float>? {
    if (anchor == null || points.isEmpty()) return null
    return points.first().time - anchor to points.last().time - anchor
}
