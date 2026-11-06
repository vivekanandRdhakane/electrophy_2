package com.example.electrophy_app

import org.junit.Assert.*
import org.junit.Test

class CollisionChartDataTest {
    @Test fun aRetainedPeakWithEmptyHistoryHasNoBounds() {
        assertNull(collisionTimeBounds(emptyList(), 100f))
    }

    @Test fun onlyTheDeviceWithHistoryContributesBounds() {
        val points = listOf(AxisPoint(0f, 0f, 0f, 80f), AxisPoint(0f, 0f, 0f, 120f))
        assertEquals(listOf(-20f to 20f), listOfNotNull(
            collisionTimeBounds(emptyList(), 100f), collisionTimeBounds(points, 100f)))
        assertNull(collisionTimeBounds(points, null))
    }
}
