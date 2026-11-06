package com.example.electrophy_app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PairedCollisionControllerTest {
    private val settings = PairSettings(preNs = 100_000_000, postNs = 20_000_000)
    private fun points(device: Int, start: Int, count: Int, pulse: Int = -1,
                       segment: Long = 1, legacy: Boolean = false, moving: Boolean = false) =
        (start until start + count).map { i ->
            val value = if (moving) (if (i % 2 == 0) 1f else -1f)
                else if (i == pulse) (if (device == 0) 10f else -8f) else 0f
            AxisPoint(value, 0f, 0f, i.toFloat(),
                if (legacy) null else (device + 1) * 9_000_000_000L + i * 1_000_000L,
                if (legacy) null else i.toLong(), if (legacy) null else segment,
                if (legacy) null else (device + 1).toLong(), if (legacy) 0 else 1)
        }
    private fun armed(): PairedCollisionController {
        val c = PairedCollisionController()
        c.start(settings, 1000)
        c.accept(0, points(0, 0, 200), 1200)
        c.accept(1, points(1, 0, 200), 1200)
        assertEquals(PairPhase.ARMED, c.state.value.phase)
        return c
    }
    private fun complete(c: PairedCollisionController) {
        c.accept(0, points(0, 200, 40, 210), 1300)
        assertEquals(PairPhase.CAPTURING, c.state.value.phase)
        c.accept(1, points(1, 200, 40, 215), 1800)
        assertEquals(PairPhase.COMPLETE, c.state.value.phase)
    }

    @Test fun bothIntervalsMustCompleteAndDeviceClocksStayIndependent() {
        val c = armed()
        c.accept(0, points(0, 200, 40, 210), 1300)
        c.accept(1, points(1, 200, 20, 215), 1800)
        assertNull(c.state.value.completed)
        c.accept(1, points(1, 220, 20), 1900)
        val pair = c.state.value.completed!!
        assertEquals(210f, pair.first.peakTime)
        assertEquals(215f, pair.second.peakTime)
        assertEquals(121, pair.first.points.size)
        assertEquals(121, pair.second.points.size)
        assertEquals(120_000_000L, pair.first.points.last().acquisitionTimeNs!! - pair.first.points.first().acquisitionTimeNs!!)
        assertNotEquals(pair.first.triggerNs, pair.second.triggerNs)
    }

    @Test fun interruptedRunCannotFinishFromLaterCallbacks() {
        val c = armed()
        c.accept(0, points(0, 200, 40, 210), 1300)
        c.interrupt("Device 2 disconnected.")
        c.accept(1, points(1, 200, 40, 215), 1400)
        assertEquals(PairPhase.INTERRUPTED, c.state.value.phase)
        assertNull(c.state.value.completed)
        assertTrue(c.state.value.message.contains("disconnected"))
    }

    @Test fun connectionFailureFreezesThePlotWithoutRequiringSensorPause() {
        val c = armed()
        val before = c.state.value.runId
        c.interrupt("Connection failed.")
        val frozen = c.state.value.interruptedPoints!!
        assertEquals(200, frozen.first.size)
        assertEquals(200, frozen.second.size)
        assertFalse(shouldPauseSensorsForPairPhase(c.state.value.phase))
        c.accept(0, points(0, 200, 40), 1300)
        assertEquals(frozen, c.state.value.interruptedPoints)
        c.start(settings, 1400)
        assertEquals(before + 1, c.state.value.runId)
        assertNull(c.state.value.interruptedPoints)
        assertTrue(shouldPauseSensorsForPairPhase(PairPhase.COMPLETE))
    }

    @Test fun missingSamplesDoNotStopTheLivePair() {
        val c = armed()
        c.accept(0, points(0, 200, 40, 210), 1300)
        c.accept(1, points(1, 201, 40, 215), 1400)
        assertEquals(PairPhase.COMPLETE, c.state.value.phase)
        assertEquals(215L, c.state.value.completed!!.second.points.first { it.x < -5f }.sampleIndex)
    }

    @Test fun segmentRestartRearmsWithoutStoppingThePlot() {
        val c = armed()
        c.accept(0, points(0, 200, 40, 210), 1300)
        c.accept(1, points(1, 0, 40, segment = 2), 1400)
        assertEquals(PairPhase.BASELINE, c.state.value.phase)
        assertNull(c.state.value.completed)
        c.accept(1, points(1, 40, 200, segment = 2), 1500)
        assertEquals(PairPhase.ARMED, c.state.value.phase)
    }

    @Test fun freshRunClearsOldEventSelectionsAndBaselines() {
        val c = armed()
        c.accept(0, points(0, 200, 40, 210), 1300)
        c.start(settings, 1400)
        c.accept(0, points(0, 0, 200), 1500)
        c.accept(1, points(1, 0, 200), 1500)
        c.accept(1, points(1, 200, 40, 215), 1600)
        assertNull(c.state.value.completed) // Device 1's earlier crossing cannot be reused.
        assertEquals(PairPhase.CAPTURING, c.state.value.phase)
    }

    @Test fun completedPairSurvivesClearAndFailedSubsequentRun() {
        val c = armed()
        complete(c)
        val saved = c.state.value.completed!!
        val original = saved.first.points.toList()
        c.clearLive()
        assertSame(saved, c.state.value.completed)
        c.start(settings, 2000)
        c.accept(0, points(0, 0, 200), 2100)
        c.interrupt("Configuration changed.")
        assertSame(saved, c.state.value.completed)
        assertEquals(original, saved.first.points)
        c.accept(1, points(1, 0, 200), 2200)
        assertEquals(PairPhase.INTERRUPTED, c.state.value.phase)
    }

    @Test fun staleStreamAndUnmatchedEventKeepThePairRunning() {
        val stale = armed()
        stale.tick(4201)
        assertEquals(PairPhase.BASELINE, stale.state.value.phase)
        stale.accept(0, points(0, 200, 200), 4300)
        stale.accept(1, points(1, 200, 200), 4300)
        assertEquals(PairPhase.ARMED, stale.state.value.phase)
        val unmatched = armed()
        unmatched.accept(0, points(0, 200, 40, 210), 1300)
        unmatched.accept(0, points(0, 240, 10), 4000)
        unmatched.accept(1, points(1, 200, 10), 4000)
        unmatched.tick(4301)
        assertEquals(PairPhase.ARMED, unmatched.state.value.phase)
        assertNull(unmatched.state.value.completed)
    }

    @Test fun legacyDataAndMovingBaselineCannotProduceValidPair() {
        val legacy = PairedCollisionController()
        legacy.start(settings, 1000)
        legacy.accept(0, points(0, 0, 200, legacy = true), 1100)
        assertEquals(PairPhase.INTERRUPTED, legacy.state.value.phase)
        val moving = PairedCollisionController()
        moving.start(settings, 1000)
        moving.accept(0, points(0, 0, 200, moving = true), 1200)
        moving.accept(1, points(1, 0, 200), 1200)
        assertEquals(PairPhase.BASELINE, moving.state.value.phase)
    }

    @Test fun segmentChangeDuringStartupRebuildsThatBaseline() {
        val c = PairedCollisionController()
        c.start(settings, 1000)
        c.accept(0, points(0, 0, 80), 1100)
        c.streamChanged(0, "Resume segment", 1100)
        c.accept(1, points(1, 0, 200), 1200)
        assertEquals(PairPhase.BASELINE, c.state.value.phase)
        c.accept(0, points(0, 0, 200, segment = 2), 1200)
        assertEquals(PairPhase.ARMED, c.state.value.phase)
    }

    @Test fun oversizedHistoryRequestsFailExplicitly() {
        val c = PairedCollisionController()
        c.start(settings.copy(preNs = 60_000_000_000), 1000)
        c.accept(0, points(0, 0, 50_000), 1200)
        assertEquals(PairPhase.INTERRUPTED, c.state.value.phase)
        assertTrue(c.state.value.message.contains("Shorten"))
    }

    @Test fun oldQueuedSegmentsCannotSupplyNewRunBaselinesOrTriggers() {
        val c = PairedCollisionController()
        c.start(settings, 1000, listOf(1L to 1L, 2L to 1L))
        c.accept(0, points(0, 0, 300, 210, segment = 0), 1100)
        assertEquals(PairPhase.BASELINE, c.state.value.phase)
        c.accept(0, points(0, 0, 300, 210), 1100)
        c.accept(1, points(1, 0, 300, 215), 1100)
        assertEquals(PairPhase.BASELINE, c.state.value.phase)
        assertNull(c.state.value.completed)
        c.accept(0, points(0, 0, 200, segment = 2), 1200)
        c.accept(1, points(1, 0, 200, segment = 2), 1200)
        assertEquals(PairPhase.ARMED, c.state.value.phase)
        c.accept(0, points(0, 200, 40, 210, segment = 2), 1300)
        c.accept(1, points(1, 200, 40, 215, segment = 2), 1400)
        assertEquals(PairPhase.COMPLETE, c.state.value.phase)
        assertEquals(2L, c.state.value.completed!!.first.segmentId)
    }

    @Test fun differentBatchDeliveryDoesNotChangeCaptureBoundaries() {
        val a = armed()
        val b = armed()
        complete(a)
        b.accept(0, points(0, 200, 15, 210), 1300)
        b.accept(1, points(1, 200, 20, 215), 1500)
        b.accept(0, points(0, 215, 25), 1700)
        b.accept(1, points(1, 220, 20), 1800)
        assertEquals(a.state.value.completed, b.state.value.completed)
    }

    @Test fun negativeThresholdAndDisabledTriggerAreExplicit() {
        val below = PairedCollisionController()
        below.start(settings.copy(condition = "Below"), 1000)
        below.accept(0, points(0, 0, 200), 1200)
        below.accept(1, points(1, 0, 200), 1200)
        below.accept(0, points(0, 200, 40, 210).map { it.copy(x = -abs(it.x)) }, 1300)
        below.accept(1, points(1, 200, 40, 215), 1400)
        assertEquals(PairPhase.COMPLETE, below.state.value.phase)
        val disabled = PairedCollisionController()
        disabled.start(settings.copy(triggerEnabled = false), 1000)
        disabled.accept(0, points(0, 0, 200), 1200)
        disabled.accept(1, points(1, 0, 200), 1200)
        disabled.accept(0, points(0, 200, 40, 210), 1300)
        disabled.accept(1, points(1, 200, 40, 215), 1400)
        assertEquals(PairPhase.ARMED, disabled.state.value.phase)
        assertNull(disabled.state.value.completed)
    }

    @Test fun manualPauseFreezesBothContinuousWindowsAndResumeDoesNotReuseCrossings() {
        val c = armed()
        c.accept(0, points(0, 200, 15, 210), 1300)
        c.accept(1, points(1, 200, 15), 1300)
        c.pause()
        assertEquals(PairPhase.COMPLETE, c.state.value.phase)
        val paused = c.state.value.completed!!
        assertEquals(214f, paused.first.points.last().time)
        assertEquals(214f, paused.second.points.last().time)
        c.start(settings, 1500)
        c.accept(0, points(0, 0, 200), 1600)
        c.accept(1, points(1, 0, 200), 1600)
        c.accept(1, points(1, 200, 40, 215), 1700)
        assertEquals(PairPhase.CAPTURING, c.state.value.phase)
        assertSame(paused, c.state.value.completed)
    }

    @Test fun pauseCannotTurnAnInterruptedPairIntoACompleteOne() {
        val c = armed()
        c.interrupt("Missing samples.")
        c.pause()
        assertEquals(PairPhase.INTERRUPTED, c.state.value.phase)
        assertNull(c.state.value.completed)
    }

    @Test fun originalThresholdCallbacksPauseWithoutReadinessOrPrehistoryGate() {
        val c = PairedCollisionController()
        c.start(settings.copy(preNs = 1_500_000_000, triggerEnabled = false, collectBaseline = false), 1000)
        c.accept(0, points(0, 0, 20), 1020)
        c.accept(1, points(1, 0, 20), 1020)
        assertEquals(PairPhase.BASELINE, c.state.value.phase)
        assertFalse(c.thresholdCrossed(0, 1021))
        assertTrue(c.thresholdCrossed(1, 1022))
        assertFalse(c.thresholdCrossed(1, 1023)) // Schedule the paired pause only once.
        c.accept(0, points(0, 20, 20, 25), 1040)
        c.accept(1, points(1, 20, 20, 30), 1040)
        c.pause() // Both pause callbacks have completed.
        assertEquals(PairPhase.COMPLETE, c.state.value.phase)
        assertEquals(25f, c.state.value.completed!!.first.peakTime)
        assertEquals(30f, c.state.value.completed!!.second.peakTime)
    }

    @Test fun peakAnchorUsesTheSameSignedSignalAsThePlot() {
        val c = PairedCollisionController()
        c.start(settings.copy(triggerEnabled = false, collectBaseline = false), 1000)
        c.accept(0, points(0, 0, 40, 10).map { if (it.sampleIndex == 15L) it.copy(x = 0f, y = 100f) else it }, 1040)
        c.accept(1, points(1, 0, 40, 12), 1040)
        c.pause()
        assertEquals(10f, c.state.value.completed!!.first.peakTime)
        assertEquals(12f, c.state.value.completed!!.second.peakTime)
    }

    @Test fun interruptedOrRestartedExternalCrossingsCannotPauseALaterRun() {
        val c = armed()
        assertFalse(c.thresholdCrossed(0, 1300))
        c.interrupt("Gap")
        assertFalse(c.thresholdCrossed(1, 1400))
        c.pause()
        assertNull(c.state.value.completed)
        c.start(settings, 1500)
        assertFalse(c.thresholdCrossed(1, 1510))
        assertTrue(c.thresholdCrossed(0, 1520))
    }

    @Test fun anExpiredExternalCrossingDoesNotMatchALaterEvent() {
        val c = armed()
        assertFalse(c.thresholdCrossed(0, 1300))
        assertFalse(c.thresholdCrossed(1, 4301))
        assertTrue(c.thresholdCrossed(0, 4302))
    }

    @Test fun presetZerosDoNotImplyThatBothStreamsAlreadyHaveTimestamps() {
        for (firstArrival in 0..1) {
            val c = PairedCollisionController()
            c.start(settings.copy(triggerEnabled = false, collectBaseline = false), 1000)
            // Reproduces the reported NPE: one device arrives first, including
            // device 2 first or device 1 accumulating all its prehistory first.
            c.accept(firstArrival, points(firstArrival, 0, 500), 1500)
            assertEquals(PairPhase.BASELINE, c.state.value.phase)
            c.accept(1 - firstArrival, points(1 - firstArrival, 0, 200), 1600)
            assertEquals(PairPhase.ARMED, c.state.value.phase)
            assertFalse(c.thresholdCrossed(0, 1700))
            assertTrue(c.thresholdCrossed(1, 1701))
            c.pause()
            assertEquals(PairPhase.COMPLETE, c.state.value.phase)
        }
    }

    @Test fun segmentRestartWithPresetZeroWaitsForFreshSamplesFromThatDevice() {
        val c = PairedCollisionController()
        c.start(settings.copy(triggerEnabled = false, collectBaseline = false), 1000)
        c.accept(0, points(0, 0, 500), 1500)
        c.streamChanged(0, "Resume", 1500)
        c.accept(1, points(1, 0, 500), 1500)
        assertEquals(PairPhase.BASELINE, c.state.value.phase)
        c.accept(0, points(0, 0, 200, segment = 2), 1600)
        assertEquals(PairPhase.ARMED, c.state.value.phase)
    }
}
