# Measuring the acquisition and BLE path

Flash the current firmware and install the updated Electrophy Android app.
Connect, choose Low-G only, set 7680 Hz, and enable **Stream diagnostics**.
Allow about six seconds for the first rate comparison, then observe at least
30 seconds. Compare 3840 Hz as a baseline. Use the same phone, orientation,
distance, sensor range, and power supply for both boards; leave recording off
for the first comparison because text recording still formats/writes samples
on the app receive path.

The sensor ODR setting is a target, not a measurement of delivered samples.
Diagnostic rates are sample tuples per second (three axes in a single-sensor
mode, nine axes in ALL), not individual axis values.

| Measurement | Meaning |
| --- | --- |
| Acquired | Successful register reads following a data-ready task notification. No read is counted on a timeout or SPI error. |
| Submitted | Samples accepted by `ble_gatts_notify_custom`. This is not a radio or phone acknowledgement. |
| Received | Valid samples decoded by the Android app, counted before filtering, plotting, or recording. Updates every two seconds, including zero during idle. |
| Pending | Samples still in the application retry queue or partial batch. Excludes NimBLE/controller buffers. |
| Overflow | Samples discarded because the 16-packet application queue filled (or the MTU cannot fit a sample). |
| Discarded | Unsent samples intentionally cleared on configuration/pause/subscription/disconnection changes, MTU changes, or permanent send errors. |
| Retries | Temporary allocation/submission failures. The head packet is retained; this counter does not mean samples were lost. |
| DRDY coalesced | Extra ISR notifications found at a single wake-up. Register reads can only obtain one current sample; no FIFO recovery is implemented. This does not count pulses that never reached the ISR and is not an exact hardware loss count. |
| Timeouts / read errors | Missing data-ready notifications / failed SPI sample reads. Retry-only waits do not count as DRDY timeouts. |
| RX sequence gaps | Missing sequence positions modulo 256 between accepted sample packets decoded on the phone. Whole multiples of 256 missing packets cannot be detected. Older firmware increments sequence before submission, so its gaps can also include local drops. |

Firmware counters are unsigned 32-bit totals since boot. Pending and queue
depth are gauges, and peak queue depth is since boot. App gap/error counts reset
on connection or Clear. Rates use separate time windows: compare sustained
averages, not a single instantaneous difference. A stale/missing firmware report
is shown as waiting, not a fresh zero rate.

Interpretation:

- Acquired below target with rising coalesced notifications: investigate
  acquisition/task scheduling first. Polling SPI and faster 12-bit packing reduce
  overhead, but hardware measurements are still required.
- Acquired above submitted, with pending/overflow/retries rising: the BLE stack
  is not draining as quickly as the producer. A finite queue absorbs bursts;
  it cannot fix a sustained throughput deficit.
- Submitted above received over a sustained interval, or rising RX gaps: inspect
  the phone receive path and connection continuity as well as BLE delivery.
- Weaker RSSI or performance strongly dependent on distance/orientation, with
  otherwise comparable PHY/MTU/interval/data length: investigate RF layout and
  antenna performance. These counters do not directly measure RF retransmissions
  or establish antenna impedance mismatch.

## Transport changes

Only the sensor task owns partial batches and the retry queue. Configuration
commands advance a generation counter; old unsent data is discarded rather than
mixed with a new mode/range. Recursive SPI locking covers complete configuration
and sample operations, and the lock is released before calling NimBLE.

The oldest queued packet is retried after at least 2 ms on ENOMEM/EAGAIN/EBUSY
(low sensor rates may retry at the FreeRTOS tick interval). Acquisition does not
sleep for the BLE retry. A successful submission advances the sample packet's
8-bit sequence; failures do not. The previous NOTIFY_TX-based in-flight counter
is removed because that callback is issued on submission, not radio completion.
Diagnostics are sent best effort every two seconds after `diag_on`; if allocation
fails, that diagnostic report is skipped. `diag_off` leaves only overflow/error
summaries and normal connection/command logs. Sample protocol compatibility is
unchanged; older apps can stream normally with diagnostics off.

The 3-second startup LED, steady LED during streaming, breathing when idle, and
30-second disconnected power-off remain in place. `USE_BLE=0` retains 1 Hz ALL
console output with no BLE auto-shutdown.

References: [ESP-IDF SPI polling](https://docs.espressif.com/projects/esp-idf/en/v5.3.1/esp32c3/api-reference/peripherals/spi_master.html)
and [NimBLE notification mbuf ownership](https://mynewt.apache.org/latest/network/ble_hs/ble_gatts.html).

## Diagnostic packet v1

72 bytes on the existing TX characteristic, little-endian. Diagnostic packets
have magic AA56 and do not consume the AA55 sample sequence. They require an
ATT MTU of at least 75. The app requests 517; smaller negotiated MTUs still carry
sample data, but a diagnostic packet is skipped if it does not fit.

| Offset | Type | Field |
| --- | --- | --- |
| 0–3 | u8 × 4 | AA, 56, version=1, stream mode |
| 4 | u32 | Sensor-task snapshot uptime in ms |
| 8, 12, 16, 20 | u32 | Acquired, submitted, overflow, discarded samples |
| 24, 28, 32 | u32 | Coalesced notifications, DRDY timeouts, read errors |
| 36, 40 | u32 | Transient retry attempts, permanent send errors |
| 44, 48 | u32 | Submitted sample-packet bytes, submitted packets |
| 52 | u32 | Pending samples |
| 56, 58 | u16 | Queue depth, peak depth |
| 60, 62 | u16 | Total free msys blocks, negotiated ATT MTU |
| 64, 66 | u16 | Connection interval in 1.25 ms units, negotiated TX data length |
| 68 | i8 | ESP-side RSSI in dBm; 127 means unavailable |
| 69, 70 | u8 | TX/RX PHY: 0 unknown, 1=1M, 2=2M, 3=coded |
| 71 | u8 | Bit 0: paused |

Counters are snapshotted by the sensor task once per second while diagnostics
are enabled. Link values are sampled on the host's two-second reporting tick.
The phone calculates firmware rates from the snapshot uptime, not packet arrival
time. No buffer-pool tuning or increased radio TX power is applied by this change.

## Validation

- `idf.py build` (or the existing Ninja build) checks the firmware with BLE on.
- Compile `main.c` with `-DUSE_BLE=0` to check the console-only branch.
- `tests/test_imu_transport.c` checks queue saturation/retry retention, FIFO wrap,
  clearing, known packing vectors, and all 4096 12-bit values. Run with a native
  C compiler: `cc -std=c11 -Wall -Wextra -Werror tests/test_imu_transport.c -o transport_test`
  then `./transport_test`. Cross-compiling it alone does not execute the tests.
- Android: `gradlew :app:testDebugUnitTest :app:assembleDebug`. Tests cover packet
  parsing, firmware counter/time wrap, mode changes, sequence wrap, and idle rates.
- Hardware checks: stream at 3840/7680 Hz, enable/disable diagnostics, pause/resume,
  change mode/range, disable notifications, reconnect, and confirm LEDs/power-off.
  Verify actual throughput and drops on both boards before claiming improvement.
