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


## Acquisition bottleneck measurements

At boot, `ODR` logs the signed factory `FREQ_FINE` register and its estimated
normal-mode 7680 Hz rate. Low-G-only ODR changes also log the estimate for the
selected rate. This is a factory estimate, not a measured interrupt frequency.
ST specifies `actual = nominal * (1 + 0.0013 * FREQ_FINE)` for the normal ODR
series (7.5 through 7680 Hz); do not apply this estimate to HAODR modes.
See [LSM6DSV320X datasheet, section 9.54](https://www.st.com/resource/en/datasheet/lsm6dsv320x.pdf).

With the existing app Stream diagnostics button ON, the USB monitor now also
prints two `PERF` lines every two seconds. Each report describes the latest
completed approximately one-second window, not the entire two-second interval:

- `window_ms`: actual measurement duration; rates use that duration.
- `irq_hz`: GPIO ISR invocations per second. This is not a logic-analyzer count;
  edges can still be missed while interrupts are masked. Includes interrupts
  during pause, so compare only steady, unpaused streaming windows.
- `acquired_hz`: successful sensor reads per second.
- `coalesced_hz`: extra task notifications consumed without additional reads.
- `discarded` and `read_errors`: cumulative counters since boot.
- `lock`, `read`, `pack`, `notify`: average/maximum elapsed microseconds for the
  configuration/SPI mutex wait, sensor register reads, packing/enqueue, and actual
  mbuf allocation/notification submission attempts respectively. Notify is per
  packet attempt; the other stages are per acquisition attempt. `attempts` is
  the number of notification attempts in that window.

Timings use CPU cycles at the configured fixed 160 MHz (power management OFF).
They include preemption, so a long SPI read does not by itself prove the SPI bus
is slow. They exclude the wait for a data-ready notification and do not measure
RF completion. Profiling adds some overhead while diagnostics are enabled;
compare the phone receive rate with diagnostics OFF as a control. No per-sample
logging is added. Windows reset after stream-generation changes and diagnostic
enable/disable. STREAM counters now use the same completed-operation snapshot
as the phone packet; pending includes the partial batch as well as queued data.

### Board comparison

1. Flash the rebuilt firmware and capture the boot `Factory FREQ_FINE` line.
2. Use Low-G only, 7680 Hz, Record OFF, and Stream diagnostics ON.
3. Wait five seconds after changing settings, then capture ten consecutive
   STREAM/PERF reports and the phone receive rate. Repeat at 3840 Hz.
4. Repeat on the development board at the same phone distance/orientation.

If IRQ rate follows the factory estimate but acquisition is lower, investigate
service delays and notification coalescing. Large notify maxima can delay
acquisition even with zero retries. If IRQ rate is below the estimate, measure
physical INT1 pulses with a logic analyzer before attributing the gap to RF.
If acquisition/submission match but the phone receives fewer samples, investigate
transport and phone processing. Direct-register reads cannot recover intermediate samples. The hardware FIFO
implementation below addresses that limitation for single-sensor BLE streams.
ODR/PHY/antenna settings are not altered by the FIFO change.


## Hardware FIFO acquisition

Low-G-only, High-G-only, and gyro-only BLE streams now use continuous hardware
FIFO, following the local ST example
`STMems_Standard_C_drivers/lsm6dsv320x_STdC/examples/lsm6dsv320x_read_fifo.c`.
Only the selected sensor is batched at its configured rate. INT1 routes the FIFO
watermark; the task reads status and uncompressed tagged seven-byte entries.
Compression and timestamp batching are disabled. BLE sample packets are unchanged;
no Android update is required for FIFO acquisition.

Watermark is 16 entries at rates >=480 Hz, one entry below that. Each pass reads
at most 32 entries, releases the configuration mutex, then submits BLE data.
Known backlog is drained without waiting for a new edge; a one-RTOS-tick fallback
poll prevents an already-high watermark from stranding data. Individual reads
remain within the SPI platform's 32-byte limit. The hardware FIFO retains samples
while BLE submission occupies the task; the physical sensor ODR is unchanged.

Stream-generation changes (ODR/range/mode, pause/resume, subscription, connection,
and MTU changes) discard old software packets and flush hardware FIFO through
bypass before restarting. FIFO is disabled while paused/disconnected. ALL and
BOTH ACC retain direct-register tuples: combining asynchronous FIFO streams at
different ODRs needs a separately defined alignment policy. USE_BLE=0 is unchanged.
Configuration failures stop acquisition and retry once per second. Entry-read
failures force FIFO flush/reconfiguration before further samples are accepted.

USB FIFO fields:

- `active`: whether the current stream uses FIFO.
- `level`: last observed level minus consumed entries; excludes arrivals during
  the drain. `peak`: highest observed pre-drain level since boot.
- `overrun_observations`: status reads reporting current or latched overrun, not
  exact lost samples or distinct events. Changes warn even with diagnostics OFF.
- `flushed`: entries observed before FIFO bypass, separate from software
  `discarded` because these entries were never counted as acquired.
- `unexpected_tags`: entries not matching the selected sensor.

In FIFO mode PERF `irq_hz` counts watermark interrupts, not samples. Compare
`acquired_hz` and phone receive rate. `coalesced` remains cumulative but only grows
on the direct-register path; notification coalescing does not discard FIFO entries.
`read` timing is per FIFO entry, `lock` per drain pass. FIFO status-read overhead
is outside `read` timing. FIFO fields are live observations; STREAM counters use
the completed-operation snapshot.

Hardware checks still required:

1. Low-G 7680/3840 Hz, Record OFF: wait five seconds after each setting change,
   then capture 20 seconds of STREAM/FIFO/PERF and phone receive rate. For the
   measured FREQ_FINE=-46 board, aim near 7218/3609 acquired samples/s, zero
   overruns/unexpected tags/read errors, and no sustained software queue growth.
2. Repeat High-G-only and gyro-only, checking axis signs and scale with known
   motion/static orientation. Test 15 Hz for timely delivery and duplicates.
3. Exercise range/ODR changes, pause/resume, reconnect, and single/combined mode
   transitions. Confirm old-range samples are not delivered after changes.
4. Compare diagnostics ON/OFF and recording ON/OFF. Build checks alone do not
   validate physical FIFO operation or delivered throughput.
