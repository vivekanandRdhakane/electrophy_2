package com.example.electrophy_app

import java.util.concurrent.atomic.AtomicLong

/** Callback ownership survives reconnects to the same BLE address. */
internal class GattSessionGuard {
    private val generation = AtomicLong(0)
    fun begin(): Long = generation.incrementAndGet()
    fun invalidate(): Long = generation.incrementAndGet()
    fun current(): Long = generation.get()
    fun owns(session: Long): Boolean = generation.get() == session
}
