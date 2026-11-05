package com.example.electrophy_app

import org.junit.Assert.*
import org.junit.Test

class GattSessionGuardTest {
    @Test fun reconnectToTheSameDeviceRejectsAllPreviousCallbacks() {
        val owner = GattSessionGuard()
        val old = owner.begin()
        assertTrue(owner.owns(old))
        val current = owner.begin()
        assertFalse(owner.owns(old))
        assertTrue(owner.owns(current))
        owner.invalidate()
        assertFalse(owner.owns(old))
        assertFalse(owner.owns(current))
    }

    @Test fun disconnectingOneNewtonDeviceDoesNotInvalidateTheOtherOwner() {
        val first = GattSessionGuard()
        val second = GattSessionGuard()
        val a = first.begin()
        val b = second.begin()
        first.invalidate()
        assertFalse(first.owns(a))
        assertTrue(second.owns(b))
        val resumed = first.begin()
        assertTrue(first.owns(resumed))
        assertFalse(first.owns(a))
    }
}
