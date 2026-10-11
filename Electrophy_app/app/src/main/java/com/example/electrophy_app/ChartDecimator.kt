package com.example.electrophy_app

/**
 * Peak-preserving decimation for real-time sensor charting.
 *
 * Implements digital oscilloscope Min-Max decimation: divides the visible viewport
 * into pixel buckets and preserves the minimum and maximum extrema in each bucket
 * in chronological order, guaranteeing that transient peaks and impacts are never dropped
 * while keeping sample counts stable across frames.
 */
object ChartDecimator {

    /**
     * Decimates [points] down to at most [maxTargetPoints] using Min-Max peak preservation.
     */
    fun decimate(points: List<AxisPoint>, maxTargetPoints: Int = 2000): List<AxisPoint> {
        if (points.size <= maxTargetPoints || maxTargetPoints < 4) {
            return points
        }

        val result = ArrayList<AxisPoint>(maxTargetPoints)
        // 2 points per bucket (min and max)
        val numBuckets = (maxTargetPoints - 2) / 2
        if (numBuckets <= 1) {
            return points
        }

        val bucketSize = points.size.toDouble() / numBuckets

        // Always include the absolute first point
        result.add(points[0])

        for (b in 0 until numBuckets) {
            val startIdx = (b * bucketSize).toInt().coerceIn(0, points.size - 1)
            val endIdx = ((b + 1) * bucketSize).toInt().coerceIn(startIdx + 1, points.size)
            if (startIdx >= endIdx) continue

            var pMin = points[startIdx]
            var pMax = points[startIdx]
            var minVal = minOf(pMin.x, pMin.y, pMin.z)
            var maxVal = maxOf(pMax.x, pMax.y, pMax.z)

            var minIdx = startIdx
            var maxIdx = startIdx

            for (i in startIdx until endIdx) {
                val pt = points[i]
                val currentMin = minOf(pt.x, pt.y, pt.z)
                val currentMax = maxOf(pt.x, pt.y, pt.z)

                if (currentMin < minVal) {
                    minVal = currentMin
                    pMin = pt
                    minIdx = i
                }
                if (currentMax > maxVal) {
                    maxVal = currentMax
                    pMax = pt
                    maxIdx = i
                }
            }

            // Add extrema in temporal order
            if (minIdx == maxIdx) {
                if (result.last().time < pMin.time) {
                    result.add(pMin)
                }
            } else if (minIdx < maxIdx) {
                if (result.last().time < pMin.time) result.add(pMin)
                if (result.last().time < pMax.time) result.add(pMax)
            } else {
                if (result.last().time < pMax.time) result.add(pMax)
                if (result.last().time < pMin.time) result.add(pMin)
            }
        }

        // Always include the absolute last point
        val lastPoint = points.last()
        if (result.isEmpty() || result.last().time < lastPoint.time) {
            result.add(lastPoint)
        }

        return result
    }
}
