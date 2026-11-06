package com.example.electrophy_app

import org.junit.Assert.*
import org.junit.Test

class GattWriteGateTest {
    @Test fun subscriptionMustFinishBeforeAnyCommandCanStart() {
        val gate = GattWriteGate()
        assertTrue(gate.begin(GattWriteGate.Kind.SUBSCRIPTION, "CCCD"))
        assertFalse(gate.begin(GattWriteGate.Kind.COMMAND, "high_acc"))
        assertFalse(gate.complete(GattWriteGate.Kind.COMMAND, true))
        assertEquals(GattWriteGate.Kind.SUBSCRIPTION, gate.pending)
        assertTrue(gate.complete(GattWriteGate.Kind.SUBSCRIPTION, true))
        assertTrue(gate.begin(GattWriteGate.Kind.COMMAND, "high_acc"))
    }

    @Test fun delayedCallbackKeepsAttClientBusyUntilItReallyCompletes() {
        val gate = GattWriteGate()
        gate.begin(GattWriteGate.Kind.COMMAND, "odr_high_g_7680hz")
        repeat(50) { assertFalse(gate.begin(GattWriteGate.Kind.COMMAND, "high_acc")) }
        assertEquals("odr_high_g_7680hz", gate.label)
        assertTrue(gate.complete(GattWriteGate.Kind.COMMAND, true))
        assertTrue(gate.begin(GattWriteGate.Kind.COMMAND, "high_acc"))
    }

    @Test fun missingCallbackCannotReleaseTheQueueOrAcceptLateSuccess() {
        val gate = GattWriteGate()
        gate.begin(GattWriteGate.Kind.COMMAND, "pause")
        assertTrue(gate.timeout())
        assertTrue(gate.failed)
        assertFalse(gate.begin(GattWriteGate.Kind.COMMAND, "resume"))
        assertFalse(gate.complete(GattWriteGate.Kind.COMMAND, true))
        assertFalse(gate.timeout())
        gate.reset() // New connection owner.
        assertTrue(gate.begin(GattWriteGate.Kind.SUBSCRIPTION, "CCCD"))
    }

    @Test fun failedOrRefusedWriteCannotBecomeAnInfiniteRetryLoop() {
        for (kind in GattWriteGate.Kind.entries) {
            val gate = GattWriteGate()
            gate.begin(kind, "operation")
            assertTrue(gate.complete(kind, false))
            repeat(50) { assertFalse(gate.begin(kind, "operation")) }
            assertTrue(gate.failed)
        }
    }

    @Test fun oneDeviceFailureDoesNotReleaseTheOtherDevicesWrite() {
        val first = GattWriteGate()
        val second = GattWriteGate()
        first.begin(GattWriteGate.Kind.COMMAND, "pause")
        second.begin(GattWriteGate.Kind.COMMAND, "pause")
        first.timeout()
        assertEquals(GattWriteGate.Kind.COMMAND, second.pending)
        assertFalse(second.failed)
        assertTrue(second.complete(GattWriteGate.Kind.COMMAND, true))
    }

    @Test fun refusedStartWaitsBeforeRetryAndDoesNotReleaseLaterCommands() {
        val gate = GattWriteGate()
        gate.begin(GattWriteGate.Kind.COMMAND, "pause")
        assertTrue(gate.retryRefusedCommand())
        assertFalse(gate.failed)
        assertFalse(gate.begin(GattWriteGate.Kind.COMMAND, "resume"))
        assertFalse(gate.complete(GattWriteGate.Kind.COMMAND, true))
        assertFalse(gate.timeout()) // No write was accepted, so no callback is due.
        assertTrue(gate.retryReady())
        assertTrue(gate.begin(GattWriteGate.Kind.COMMAND, "pause"))
        assertTrue(gate.complete(GattWriteGate.Kind.COMMAND, true))
        assertTrue(gate.begin(GattWriteGate.Kind.COMMAND, "resume"))
    }

    @Test fun repeatedBusyStartsHaveABoundedRetryBudget() {
        val gate = GattWriteGate()
        repeat(3) {
            assertTrue(gate.begin(GattWriteGate.Kind.COMMAND, "pause"))
            assertTrue(gate.retryRefusedCommand())
            assertTrue(gate.retryReady())
        }
        assertTrue(gate.begin(GattWriteGate.Kind.COMMAND, "pause"))
        assertFalse(gate.retryRefusedCommand())
        assertTrue(gate.complete(GattWriteGate.Kind.COMMAND, false))
        assertFalse(gate.retryReady())
        assertFalse(gate.begin(GattWriteGate.Kind.COMMAND, "resume"))
    }

    @Test fun successfulWriteRestoresRetryBudgetForTheNextCommand() {
        val gate = GattWriteGate()
        repeat(2) {
            repeat(3) {
                assertTrue(gate.begin(GattWriteGate.Kind.COMMAND, "pause"))
                assertTrue(gate.retryRefusedCommand())
                assertTrue(gate.retryReady())
            }
            assertTrue(gate.begin(GattWriteGate.Kind.COMMAND, "pause"))
            assertTrue(gate.complete(GattWriteGate.Kind.COMMAND, true))
        }
    }

    @Test fun reconnectClearsPendingRetryWithoutAffectingTheOtherDevice() {
        val first = GattWriteGate()
        val second = GattWriteGate()
        first.begin(GattWriteGate.Kind.COMMAND, "pause")
        second.begin(GattWriteGate.Kind.COMMAND, "pause")
        first.retryRefusedCommand()
        first.reset()
        assertFalse(first.retryReady())
        assertTrue(first.begin(GattWriteGate.Kind.SUBSCRIPTION, "CCCD"))
        assertEquals(GattWriteGate.Kind.COMMAND, second.pending)
    }
}
