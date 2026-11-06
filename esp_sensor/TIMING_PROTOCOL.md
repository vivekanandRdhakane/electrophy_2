# Acquisition timing v2 (E003)

Implemented 5 November 2026. Firmware and Android must be deployed together to
use v2. This is a rate-reconstructed FIFO timeline with a device monotonic clock
anchor, **not hardware-validated sensor timestamps or synchronized device clocks**.

## Compatibility and scope

Firmware starts every connection in the existing AA55/v1 format. The new app
sends `proto2` during configuration. Single-sensor FIFO modes LOW_ACC, HIGH_ACC,
and ONLY_GYRO then emit AA58/v2. The temporary legacy/v2 comparison control has been removed.
The change invalidates pending batches/queue contents and establishes a new
timing segment. Reconnect, pause/resume, mode/range/ODR commands, and FIFO recovery
also establish new segments. Older apps never request v2. Older firmware ignores
the unknown command and continues sending v1; the app explicitly labels its
timing as arrival-estimated and precision timing unavailable.

BOTH_ACC and ALL continue using AA55. Their register tuples do not establish
per-sensor freshness or common acquisition times (E004). Do not use these modes
to validate timing, synchronized orientation, or quantitative paired measurements.
The app always displays its received timing basis, not just the requested switch.

## Wire format

Every AA58 packet has a 36-byte header. Integers are unsigned little-endian,
except that the Android implementation requires nanoseconds to fit a positive
signed 64-bit integer. Payload packing/scaling is unchanged. Packet length must
equal header plus the exact byte-rounded payload length. Unknown versions,
flags, modes, zero period/count, truncation, trailing bytes, and time overflow
are rejected before processing samples.

| Offset | Type | Meaning |
|---|---|---|
| 0–1 | u8 × 2 | AA58 |
| 2–8 | u8 × 7 | Existing mode, count, low-g FS, high-g FS, gyro FS, sequence, format |
| 9 | u8 | Version = 2 |
| 10 | u8 | Bit 0: FIFO rate-reconstructed estimate (required). Bit 1: segment follows unknown upstream loss (FIFO fault). Other bits rejected. |
| 11 | u8 | Header bytes = 36 |
| 12 | u32 | Random boot/session ID; reconnect retains boot ID, but changes segment |
| 16 | u32 | Timing segment; all configuration generations invalidate timing, even repeated commands |
| 20 | u32 | First sample index within segment; increments on acquisition before any local MTU/queue rejection |
| 24 | u64 | First sample's estimated device time in nanoseconds since ESP boot |
| 32 | u32 | Estimated period in integer nanoseconds |
| 36… | bytes | RAW16 or MSB-first packed12 payload, as in v1 |

Sample i has index `(first_sample + i) mod 2^32` and time
`first_time_ns + i * period_ns`. A period is fixed for the entire segment.
Configuration identity is the boot/segment pair plus the header mode/ranges;
this records a configuration boundary, **not a verified applied-register state**.
E007 remains responsible for applying and acknowledging settings correctly.

The 8-bit packet sequence still counts successful stack submissions and wraps
modulo 256. It is not a sample identity or phone acknowledgement. Within a
segment, unsigned 32-bit sample-index differences below 2^31 detect missing
samples. Larger backwards differences and duplicate/reordered timestamps are
rejected. Counter wrap is supported; ambiguous absences of 2^31 samples require
a fresh segment/reconnect. Nanosecond time does not wrap on practical runs;
overflow is rejected. Boot IDs are probabilistic identifiers, not authentication.

## Acquisition uncertainty and discontinuities

At the first nonempty FIFO status observation in a segment, firmware anchors
the oldest retained entry to:

`ESP monotonic time - (FIFO level - 1) * estimated period`.

The period uses selected ODR, with factory FREQ_FINE correction for normal
low-g/gyro rates when available; high-g uses nominal rate. No BLE clock enters
this timeline. Subsequent FIFO drains advance the integer clock by sample count,
so FIFO-service delays and radio congestion do not stretch samples. SPI status
access/scheduling and conversion phase introduce initial uncertainty (roughly a
sample period plus access delay under normal operation); oscillator/rate error
accumulates thereafter. These are **unmeasured bounds**, not accuracy guarantees.
Sensor timestamp tags remain disabled; absolute clock accuracy and long-run drift
must be measured before scientific timing claims. No cross-device alignment or
absolute one-way latency is inferred from ESP and phone clocks.

FIFO overrun, partial FIFO read, or unexpected tag forces a flush/new segment.
Exact upstream loss can be unknown. Queue/MTU drops within a segment preserve
sample identity and time, allowing exact missing-acquired-sample detection after
the next received packet. The first observed packet establishes a boundary:
samples before attachment/Clear are not counted as transport loss. Loss after the
last received packet cannot be inferred until another packet arrives; consult
firmware counters and pending depth too. IDs do not recover lost values.

Android retains nanoseconds, boot/segment IDs, sample indices, and timing flags
on AxisPoint through filtering/calibration. Only plot coordinates use relative
Float milliseconds. It rejects unannounced period/range/mode changes. Following
user feedback and the paired 7,680 Hz Newton stabilization (commit `e5858c3`),
in-segment missing samples/gaps are tracked in diagnostics (`timingGapSamples`,
missing-sample counters, and UI status) without clearing live chart history,
stopping the live plot, or pausing paired runs; sample objects preserve their
true discontinuous device nanosecond timestamps and sample indices so downstream
data models retain explicit gaps. Only a true segment change with discontinuity
(`newSegment && discontinuity`, e.g. reconnect, configuration changes, or FIFO
fault flush) resets the plot-time origin, clears that device's live chart buffer,
and invalidates pending single-shot trigger callbacks so unrelated time coordinates
are not mixed. The UI reports missing samples and history breaks; this does not
save the old history as an immutable capture. Legacy data still uses explicitly
estimated arrival timing. v2 gyro integration uses retained device sample time;
the broader attitude model remains E017.

## Expected bandwidth cost

The header increases from 9 to 36 bytes: **27 extra bytes per packet**, covering
identities and period as well as time. This is larger than an isolated 8-byte
timestamp. Maximum single-sensor packed12 capacities are:

| ATT MTU | Value budget (firmware cap 512) | Legacy samples | v2 samples |
|---|---:|---:|---:|
| 23 | 20 | 2 | 0 |
| 185 | 182 | 38 | 32 |
| 247 | 244 | 52 | 46 |
| 517 | 512 | 111 | 105 |

v2 needs MTU >=44 for one packed12 tuple. At smaller MTUs firmware counts rejected
samples as overflow; the app must not imply measurements are arriving. Diagnostics
also require MTU >=75. At MTU 517 and full packets, sustaining 7680 samples/s
requires approximately 73.1 v2 packets/s versus 69.2 legacy packets/s, around
5.7% more notifications. Actual batching, negotiated link, and phone processing
determine observed throughput. No sampling/throughput increase is promised.

## Initial throughput verification — 5 November 2026

The user's single-device Android OFF/ON/OFF logs showed approximately:

| Run | Observed duration | Received samples/s | Received bytes/s |
|---|---:|---:|---:|
| Legacy | 42.2 s | 7668 | 35169 |
| v2 | 82.5 s | 7670 | 37182 |
| Legacy | 42.2 s | 7667 | 35161 |

The v2 interval stayed in segment 43 with zero detected missing samples and
zero timing rejections. Its bandwidth cost was about 5.7%, without an observed
received-rate reduction in this run. Earlier firmware logs recorded a separate
congestion episode; this test does not establish loss-free operation under all
radio conditions. Clock accuracy/drift, combined modes, other devices, and long
captures remain unqualified. Legacy missing-sample counters cannot establish
loss-free delivery because legacy frames have no acquisition sample identity.

Following this verification, the app always requests v2. The A/B switch, proto1
command, TIMING overhead logs, E003 Logcat summaries, and their bookkeeping were
removed. Legacy decoding remains for older firmware and unsupported combined
modes. Existing opt-in STREAM/FIFO/PERF diagnostics, visible timing quality/loss
counters, and regression tests remain available for ongoing fault diagnosis.