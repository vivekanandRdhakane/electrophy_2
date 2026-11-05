package com.example.electrophy_app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.hypot

internal data class PlanarZero(val x: Float = 0f, val y: Float = 0f)
internal fun collisionSignedPlanarG(point: AxisPoint, zero: PlanarZero, alongX: Boolean): Float {
    val x = point.x - zero.x
    val y = point.y - zero.y
    val magnitude = hypot(x, y)
    val along = if (alongX) x else y
    if (abs(along) < magnitude * 0.05f) return 0f
    return if (along >= 0f) magnitude else -magnitude
}
internal enum class PairPhase { IDLE, BASELINE, ARMED, CAPTURING, COMPLETE, INTERRUPTED }
internal data class PairSettings(
    val alongX: Boolean = true, val condition: String = "Deviation",
    val thresholdG: Float = 5f, val preNs: Long = 1_500_000_000,
    val postNs: Long = 1_500_000_000, val triggerEnabled: Boolean = true,
    val firstName: String = "Device 1", val secondName: String = "Device 2",
    val collectBaseline: Boolean = true,
    val firstZero: PlanarZero = PlanarZero(), val secondZero: PlanarZero = PlanarZero()
)
internal data class CollisionSide(
    val points: List<AxisPoint>, val baseline: PlanarZero, val peakTime: Float,
    val triggerNs: Long, val bootId: Long, val segmentId: Long
)
internal data class CollisionPair(val first: CollisionSide, val second: CollisionSide, val alongX: Boolean,
                                  val firstName: String, val secondName: String,
                                  val preNs: Long, val postNs: Long)
internal data class PairRunState(
    val phase: PairPhase = PairPhase.IDLE,
    val message: String = "Start a new run with both strikers still.",
    val firstZero: PlanarZero = PlanarZero(), val secondZero: PlanarZero = PlanarZero(),
    val completed: CollisionPair? = null, val runId: Long = 0
)

/** Owns one paired run. Device clocks are never compared with each other. */
internal class PairedCollisionController {
    private data class Stream(
        val previousEpoch: Pair<Long, Long>? = null,
        val buffer: ArrayDeque<AxisPoint> = ArrayDeque(),
        var epoch: Pair<Long, Long>? = null, var lastIndex: Long? = null,
        var lastNs: Long? = null, var lastArrival: Long = 0,
        var zero: PlanarZero? = null, var triggerNs: Long? = null,
        var triggerArrival: Long? = null, var frozen: CollisionSide? = null
    )
    private val streams = arrayOf(Stream(), Stream())
    private var settings = PairSettings()
    private var startedMs = 0L
    private val externalCrossings = arrayOfNulls<Long>(2)
    private var pauseScheduled = false
    private val mutable = MutableStateFlow(PairRunState())
    val state: StateFlow<PairRunState> = mutable
    private fun running() = when (mutable.value.phase) {
        PairPhase.BASELINE, PairPhase.ARMED, PairPhase.CAPTURING -> true
        else -> false
    }

    @Synchronized fun start(next: PairSettings, nowMs: Long,
                            previousEpochs: List<Pair<Long, Long>?> = listOf(null, null)) {
        settings = next
        streams.indices.forEach { streams[it] = Stream(previousEpoch = previousEpochs[it],
            zero = if (next.collectBaseline) null else if (it == 0) next.firstZero else next.secondZero) }
        startedMs = nowMs
        externalCrossings.fill(null)
        pauseScheduled = false
        val valid = next.thresholdG.isFinite() && next.thresholdG > 0 && next.preNs >= 0 && next.postNs > 0
        mutable.value = mutable.value.copy(
            phase = if (valid) PairPhase.BASELINE else PairPhase.INTERRUPTED,
            message = if (valid) "Keep both strikers still — collecting fresh baselines and pre-impact history."
                else "Enter a positive finite threshold and capture duration.",
            firstZero = if (next.collectBaseline) PlanarZero() else next.firstZero,
            secondZero = if (next.collectBaseline) PlanarZero() else next.secondZero,
            runId = mutable.value.runId + 1)
    }

    @Synchronized fun interrupt(reason: String) {
        if (!running()) return // Completed pairs are immutable and survive later failures.
        streams.forEach { it.triggerNs = null; it.triggerArrival = null; it.frozen = null }
        externalCrossings.fill(null)
        pauseScheduled = false
        mutable.value = mutable.value.copy(phase = PairPhase.INTERRUPTED, message = "$reason Start a new run.")
    }

    @Synchronized fun clearLive() {
        externalCrossings.fill(null)
        pauseScheduled = false
        streams.indices.forEach { streams[it] = Stream() }
        mutable.value = mutable.value.copy(phase = PairPhase.IDLE,
            message = "Live history cleared. Start a new run with both strikers still.",
            firstZero = PlanarZero(), secondZero = PlanarZero(), runId = mutable.value.runId + 1)
    }

    /** Original threshold callbacks: no baseline/prehistory gate before scheduling Pause. */
    @Synchronized fun thresholdCrossed(device: Int, nowMs: Long): Boolean {
        if (!running() || pauseScheduled) return false
        externalCrossings[device] = nowMs
        if (externalCrossings.all { it != null && nowMs - it in 0L..3000L }) {
            pauseScheduled = true
            mutable.value = mutable.value.copy(phase = PairPhase.CAPTURING,
                message = "Pausing after both threshold crossings…")
            return true
        }
        return false
    }

    /** Manual Pause freezes the current continuous windows; no automatic event is implied. */
    @Synchronized fun pause() {
        if (!running()) return
        if (streams.any { it.buffer.size < 2 }) {
            interrupt("Not enough continuous data to compare yet."); return
        }
        val sides = streams.map { s ->
            val end = s.buffer.last().acquisitionTimeNs!!
            // Match the earlier screen: search the recent 10-second history,
            // so the first crossing isn't evicted by the other device's delay.
            val start = end - 10_000_000_000L
            val points = s.buffer.filter { it.acquisitionTimeNs!! >= start }.toList()
            val zero = s.zero ?: PlanarZero() // Same unzeroed display as the earlier screen.
            val peak = points.maxBy { abs(collisionSignedPlanarG(it, zero, settings.alongX)) }
            CollisionSide(points, zero, peak.time, peak.acquisitionTimeNs!!, s.epoch!!.first, s.epoch!!.second)
        }
        val pre = sides.minOf { it.triggerNs - it.points.first().acquisitionTimeNs!! }
        val post = sides.minOf { it.points.last().acquisitionTimeNs!! - it.triggerNs }
        mutable.value = mutable.value.copy(phase = PairPhase.COMPLETE,
            message = "Paused · peaks aligned for waveform comparison.",
            completed = CollisionPair(sides[0], sides[1], settings.alongX,
                settings.firstName, settings.secondName, pre, post))
    }

    /** Startup configuration may create several segments before either device is armed. */
    @Synchronized fun streamChanged(device: Int, reason: String) {
        if (mutable.value.phase == PairPhase.BASELINE) {
            streams[device] = Stream(previousEpoch = streams[device].epoch ?: streams[device].previousEpoch,
                zero = if (settings.collectBaseline) null else if (device == 0) settings.firstZero else settings.secondZero)
        } else interrupt(reason)
    }

    @Synchronized fun tick(nowMs: Long) {
        if (!running()) return
        val waiting = streams.any { nowMs - (it.lastArrival.takeIf { t -> t > 0 } ?: startedMs) > 3000 }
        if (waiting) { interrupt("A device stopped delivering samples."); return }
        val unmatched = externalCrossings.filterNotNull().minOrNull()
        if (!pauseScheduled && unmatched != null && nowMs - unmatched > 3000) {
            externalCrossings.fill(null) // Expired crossing cannot pair with a later collision.
        }
        val firstCrossing = streams.mapNotNull { it.triggerArrival }.minOrNull()
        if (firstCrossing != null && streams.any { it.triggerNs == null } && nowMs - firstCrossing > 3000)
            interrupt("The second device did not detect the event in time.")
    }

    @Synchronized fun accept(device: Int, batch: List<AxisPoint>, nowMs: Long) {
        if (!running() || batch.isEmpty()) return
        tick(nowMs)
        if (!running()) return
        var s = streams[device]
        for (p in batch) {
            val ns = p.acquisitionTimeNs
            val index = p.sampleIndex
            val boot = p.acquisitionBootId
            val segment = p.timingSegment
            if (ns == null || index == null || boot == null || segment == null) {
                interrupt("Both devices require v2 timing for a paired capture."); return
            }
            if (!p.x.isFinite() || !p.y.isFinite() || !p.z.isFinite()) {
                interrupt("A device supplied invalid measurements."); return
            }
            val epoch = boot to segment
            if (s.epoch == null && s.previousEpoch?.first == boot) {
                val advance = (segment - s.previousEpoch.second) and 0xffff_ffffL
                if (advance == 0L || advance >= 0x8000_0000L) continue // old queued segments
            }
            if (s.epoch != null && s.epoch != epoch) {
                streamChanged(device, "Device ${device + 1} changed its data segment.")
                if (!running()) return
                s = streams[device]
            }
            if (s.lastIndex != null && (index != ((s.lastIndex!! + 1) and 0xffff_ffffL) || ns <= s.lastNs!!)) {
                interrupt("Device ${device + 1} has missing or reordered samples."); return
            }
            s.epoch = epoch; s.lastIndex = index; s.lastNs = ns; s.lastArrival = nowMs
            s.buffer.addLast(p)
            // Bounded per-device storage; oversized requests fail instead of silently truncating.
            if (s.buffer.size > 50_000) s.buffer.removeFirst()
            if (mutable.value.phase == PairPhase.BASELINE && s.buffer.size == 50_000 &&
                ns - s.buffer.first().acquisitionTimeNs!! < settings.preNs) {
                interrupt("Pre-impact interval exceeds retained history. Shorten the capture window."); return
            }
            if (s.zero == null && s.buffer.size % 16 == 0) {
                val recent = s.buffer.asReversed().takeWhile { ns - it.acquisitionTimeNs!! <= 100_000_000 }.asReversed()
                if (recent.size >= 16 && ns - recent.first().acquisitionTimeNs!! >= 90_000_000) {
                    val x = recent.map { it.x.toDouble() }.average().toFloat()
                    val y = recent.map { it.y.toDouble() }.average().toFloat()
                    if (recent.all { hypot(it.x - x, it.y - y) <= 0.25f }) s.zero = PlanarZero(x, y)
                }
            }
            if (mutable.value.phase == PairPhase.BASELINE && streams.all {
                    val last = it.lastNs
                    val first = it.buffer.firstOrNull()?.acquisitionTimeNs
                    it.zero != null && last != null && first != null && last - first >= settings.preNs
                }) {
                mutable.value = mutable.value.copy(phase = PairPhase.ARMED,
                    message = if (settings.triggerEnabled) "Ready — perform one collision."
                        else "Baselines ready. Enable auto-trigger, then start a new run.",
                    firstZero = streams[0].zero!!, secondZero = streams[1].zero!!)
            }
            if ((mutable.value.phase == PairPhase.ARMED || mutable.value.phase == PairPhase.CAPTURING) &&
                settings.triggerEnabled && s.triggerNs == null) {
                val zero = s.zero!!
                val value = if (settings.alongX) p.x - zero.x else p.y - zero.y
                val crossed = when (settings.condition) {
                    "Above" -> value >= settings.thresholdG
                    "Below" -> value <= -settings.thresholdG
                    else -> abs(value) >= settings.thresholdG
                }
                if (crossed) {
                    if (ns - s.buffer.first().acquisitionTimeNs!! < settings.preNs) {
                        interrupt("Not enough pre-impact history. Shorten the capture window."); return
                    }
                    s.triggerNs = ns; s.triggerArrival = nowMs
                    mutable.value = mutable.value.copy(phase = PairPhase.CAPTURING,
                        message = "Collecting both impact intervals…")
                }
            }
            val trigger = s.triggerNs
            if (trigger != null && s.frozen == null && ns >= trigger + settings.postNs) {
                val start = trigger - settings.preNs
                if (s.buffer.first().acquisitionTimeNs!! > start) {
                    interrupt("Capture exceeded retained history. Shorten the capture window."); return
                }
                val points = s.buffer.filter { it.acquisitionTimeNs!! in start..(trigger + settings.postNs) }
                val peak = points.filter { it.acquisitionTimeNs!! >= trigger }.maxByOrNull {
                    hypot(it.x - s.zero!!.x, it.y - s.zero!!.y)
                } ?: run { interrupt("Impact interval is incomplete."); return }
                s.frozen = CollisionSide(points.toList(), s.zero!!, peak.time, trigger, boot, segment)
            }
        }
        if (streams.all { it.frozen != null }) {
            mutable.value = mutable.value.copy(phase = PairPhase.COMPLETE,
                message = "Capture complete · peaks aligned for waveform comparison.",
                completed = CollisionPair(streams[0].frozen!!, streams[1].frozen!!, settings.alongX,
                    settings.firstName, settings.secondName, settings.preNs, settings.postNs))
        }
    }
}
