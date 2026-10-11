# Electrophy app audit and indexed implementation plan

Reviewed: **11 October 2026**. Source baseline: repository commit `3262790` plus the files present during review. This is an audit and implementation specification, not a claim that the tasks below have been implemented.

## Verdict and scope

**The app is a useful prototype, but its measurements and all advertised scientific conclusions cannot currently be considered reliable.** There are confirmed data-integrity defects, a reproducible crash, incorrect numerical outputs, and physics labels that describe quantities the app does not actually measure. Fix acquisition and timekeeping before validating downstream experiment results.

Reviewed all seven application Kotlin files, all 16 experiment presets, Android permissions/build/test configuration, the firmware acquisition/command/packet paths, the bundled ST conversion driver, and relevant installed ESP-IDF v5.3.1 NimBLE source. The project context was read from `.agents/skills/electrophy/SKILL.md`. PCB electrical design was not audited.

Verification performed:

- Compiled the original `MathEngine.kt` with Kotlin 2.2.10 in a temporary JVM harness, with minimal stubs for Android-adjacent data types. Executed numerical probes of integration, FFT, STFT, statistics, autocorrelation, filter lab, and comparator; inspected derivative logic. This tests the actual engine source, not the complete Android app.
- Ran independent source-equivalent numerical checks for packet size, packing, high-g scaling, arrival-time distortion, notification counter behavior, vehicle integration, pendulum period ambiguity, free-fall triggering, and angle wrapping.
- Checked niche technical/physics claims against the primary references linked below.
- Attempted offline `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. The first invocation could not create a Gradle cache lock under `C:\.gradle`; a retry using a writable workspace cache and installed Gradle/JDK stopped with `Error loading java.security file`. **No full Android build, lint, or test-suite pass is claimed.** The temporary workspace cache was removed.
- No Android runtime session, device flash, BLE hardware run, calibrated motion test, or throughput benchmark was performed. UX findings are from source inspection; actual layout and performance require device testing.
- Production app/firmware files were not changed. The existing modification to `workspace.code-workspace` was left alone. This document is the audit deliverable.

The skill is useful context but is not authoritative where it contradicts code or the sensor driver. In particular, its high-g sensitivity table is wrong, its CSV description overstates the current text export, and its flow-control description overstates the current implementation.

## How another AI agent should use this document

Example request:

> Implement E005 from `electrophy_2/ELECTROPHY_APP_AUDIT_AND_IMPLEMENTATION_PLAN.md`. Read its evidence, dependencies, implementation instructions, and acceptance tests. Implement only that task and necessary prerequisites. Run the relevant checks and update its status with the result.

Rules for the implementing agent:

1. Read the selected task and the shared contracts below. Read applicable repository instructions and the project skill. Re-check current source; line numbers describe this audit snapshot and will move.
2. Keep **E001–E041 stable**. Do not renumber tasks after completing or reprioritizing them. `E` IDs are distinct from the unrelated numbered ideas in `HIGH_IMPACT_FEATURES.md`.
3. Dependencies refer to completed behavior, not necessarily merged commits. If missing, report the prerequisite and implement the smallest authorized prerequisite or a clearly labeled interim fix. Never imply a downstream scientific feature is validated while its timing/acquisition prerequisites remain broken.
4. Preserve unrelated user changes. Avoid broad rewrites while fixing a local defect. Put numerical algorithms and protocol decoding into pure functions/classes where they can be tested without Compose or a BLE device.
5. App/firmware protocol changes must ship together, with a written version/layout specification and compatibility behavior. Do not silently reinterpret the existing format. An app-only deployment must identify unsupported/legacy firmware and explain unavailable measurements.
6. Add meaningful regression tests listed in the task. Do not replace real checks with tests of labels or a copy of the same faulty formula. Use known analytic signals or independently computed reference values.
7. Report exactly what was tested: JVM tests, Android build/lint, UI tests, firmware build, and physical measurements are different evidence. Never claim hardware accuracy from a passing unit test.
8. For each completed task, append a short record: status, changed files, tests/results, remaining limitations, and any follow-up IDs. Do not mark complete until its acceptance criteria are satisfied, or explicitly split an interim mitigation from final completion.
9. Keep scientific uncertainty visible: missing, stale, clipped, incomplete, and invalid results must not be represented as plausible zero values. Do not add precision or accuracy claims unsupported by measurement.
10. For ordinary verification use the project's wrapper from `Electrophy_app`: `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. First establish a working compatible toolchain under E039. Read the ESP-IDF skill before firmware builds. No flashing is needed for most app-only fixes.

## Shared contracts and implementation order

These are design requirements, not a requirement to implement one large architecture rewrite:

- A measurement carries sensor identity, acquisition time, physical quantity/unit, applied configuration, calibration provenance, session/segment identity, and validity. Prefer integer microseconds or another documented monotonic tick unit internally; convert to plot-relative floating-point coordinates only for display.
- Keep acquired/raw data separate from calibrated and filtered data. Calibration and filtering must have explicit order and state reset rules. An experiment must say which representation it analyzes.
- Missing/off/stale sensors are absent/invalid, not zero. A repeated register value is not necessarily a new sample. Each sensor can have its own cadence.
- A capture is separate from a chart. A capture owns its boundaries, pre-event baseline, source axis/frame, parameters, and quality flags. Panning, decimation, and chart eviction must not alter saved measurement results.
- A device configuration has desired, pending, and applied states. Only applied settings describe a measurement. A rate change, reset, reconnect, or processing change must not silently mix incompatible data in one analysis.
- SI conventions: `1 g = 9.80665 m/s²`, `1 mg = 0.00980665 m/s²`, `1 mdps = 0.001 degree/s`. An accelerometer measures specific force; with a consistent body-to-world rotation, `a_world = R * f_body + g_world`. A stationary upward-facing accelerometer reads about +1 g while inertial acceleration is zero.

Recommended sequence: independently fix **E001, E005, E006** and establish **E039**; coordinate **E002–E004, E007–E010** as the acquisition/protocol foundation; then **E011–E022**; then the remaining P2 usability and interpretation work. Easy label corrections can be made early but do not count as complete scientific validation. E040 is optional expansion.

Priority meaning: **P0** silently corrupts foundational measurements; **P1** crashes, gives materially wrong results, or compromises capture reliability; **P2** completes interpretation, usability, or performance; **P3** optional expansion. P0 is a release priority here, not a claim of a safety-critical product.

## Task index

| ID | Priority | Category | Task |
|---|---|---|---|
| E001 | P0 | Units | Correct high-g sensitivity conversion |
| E002 | P0 | Protocol | MTU-aware packets and strict decoding |
| E003 | P0 | Timing | Acquisition timestamps and explicit gaps |
| E004 | P0 | Acquisition | Fresh per-sensor samples and real synchronization |
| E005 | P1 | Math | Fix median-filter crash |
| E006 | P1 | Math | Repair comparator lag/correlation |
| E007 | P1 | Configuration | Atomic applied configuration and valid ODR modes |
| E008 | P1 | Firmware | Propagate SPI/IMU errors |
| E009 | P1 | Transport | Correct flow control and loss accounting |
| E010 | P1 | Android | Reliable BLE lifecycle and operation completion |
| E011 | P1 | Capture | Separate captures, analysis intervals, and chart history |
| E012 | P1 | Math | Use actual time and align signals |
| E013 | P1 | Physics | Correct force, impulse, and SI quantities |
| E014 | P1 | Physics | Repair vehicle velocity-change experiment |
| E015 | P1 | Physics | Complete vector-based free-fall events |
| E016 | P1 | Physics | Measure pendulum swing period correctly |
| E017 | P1 | Attitude | Coordinate-consistent orientation estimator |
| E018 | P1 | Attitude | Correct plotted quantity and heading reset |
| E019 | P1 | Trigger | Typed trigger source and cancellable event lifecycle |
| E020 | P1 | Calibration | Validate and scope calibration |
| E021 | P1 | Recording | Reproducible structured recordings and reliable storage |
| E022 | P1 | Spectra | Correct FFT/STFT bins, amplitudes, and peak validity |
| E023 | P2 | Integration | Stable integral origins and honest detrending |
| E024 | P2 | Autocorrelation | Invariant period detection and useful lag range |
| E025 | P2 | Filters | Correct digital cutoff and response descriptions |
| E026 | P2 | Statistics | Correct statistical labels and plots |
| E027 | P2 | Derivative | Source-aware derivatives and accurate stencil labels |
| E028 | P2 | Physics | Correct the Newton's Third Law claim |
| E029 | P2 | Physics | Complete centripetal analysis |
| E030 | P2 | Physics | Complete elevator interpretation |
| E031 | P2 | Attitude UX | Remove unsupported flight/compass claims |
| E032 | P2 | Precision | Explicit transport resolution and range policy |
| E033 | P2 | Performance | Shared background analysis and bounded rendering |
| E034 | P2 | Charts | Trustworthy sample cursors and interval inspection |
| E035 | P2 | Navigation | Preserve experiment state and handle system Back |
| E036 | P2 | Connection UX | Device selection, recovery, and live diagnostics |
| E037 | P2 | Math UX | Inspectable FFT, spectrogram, and XY plots |
| E038 | P2 | Accessibility | Responsive controls and accessible plots |
| E039 | P1 | Validation | Reproducible build and scientific regression suite |
| E040 | P3 | Expansion | Saved-run browser and offline replay |
| E041 | P2 | Experiment UX | Guided setup, run, review, and quality feedback |

## Feature-by-feature coverage

All features depend on valid acquisition; a sound formula alone does not validate the end-to-end result.

| Existing feature | Assessment | Main tasks |
|---|---|---|
| Raw LOW/HIGH/BOTH/GYRO/ALL streaming | Packing sign convention is sound; timing, MTU, stale channels, ALL meaning, and error handling are not | E001–E004, E007–E010, E036 |
| ODR/range controls | Useful configuration coverage; displayed values can differ from hardware; 1.875 Hz request is rejected in current sensor mode | E007 |
| Pause/resume/clear | Basic actions exist; session time, callbacks, capture boundaries, and state resets are inconsistent | E003, E010, E011, E019, E035 |
| Calibration | Single-position mean subtraction is limited but useful; no motion/quality validation or device scope | E020 |
| EMA and auto-trigger | EMA recurrence is sound; cutoff label, unit mixing, and delayed trigger lifecycle need repair | E019, E025 |
| Recording/export | Records text, not reproducible timestamped CSV; charts and exports use different calibration states | E021 |
| Raw/physics charts and cursors | Useful live plots; live cursor can be an arbitrary coordinate and two cursors can refer to different axes | E011, E034, E038 |
| 3D Aeroplane Attitude | Static signs match renderer; dynamic estimation, heading claims, and degree plot are flawed | E017, E018, E031, E032 |
| Simple Pendulum | Computes Z-signal period, which can be half the actual swing period | E016 |
| Vehicle Acceleration | Uses the wrong sensor ODR, wrong units, and an unspecified frame | E014 |
| Newton's Third Law | Single-object acceleration is not an action–reaction measurement | E013, E028 |
| Free Fall | Magnitude-based helper is conceptually correct; trigger and event completeness are not | E015, E019 |
| Centripetal Acceleration | Generic peaks do not implement the advertised quantitative comparison | E029 |
| Impulse & Momentum | Integral lacks mass/SI conversion and an explicit event/baseline | E011, E013 |
| Elevator Physics | Useful qualitative trace with fixed upward mounting; interpretation and load results incomplete | E030 |
| Numerical Integration | Uniform-sample trapezoidal algorithm is sound; timebase, origin, and detrending semantics need work | E012, E023 |
| Numerical Derivative | Valid two-point central difference, not the implied higher-order stencil | E012, E027 |
| FFT Frequency Spectrum | FFT core and main window gain correction are sound; Nyquist and peak validity are wrong | E012, E022, E037 |
| Spectrogram | Windowed overlapping FFT approach is sound; scaling, axes, and recomputation need work | E022, E033, E037 |
| Statistics & Distribution | Population moments are sound on nonconstant data; labels/undefined values/plot scales need work | E026 |
| Autocorrelation | Biased normalized estimator is legitimate; detector depends on display scaling and lag range is short | E024 |
| Digital Filter Lab | Several correct basic recurrences; selecting median with default size crashes | E005, E025 |
| Signal Comparator | Pearson is correct only for aligned nonconstant data; lag search is broken and streams are not aligned | E006, E012 |
| Experiment catalog/navigation | All 16 presets are discoverable; state restoration, connection access, setup flow, and accessibility need work | E035–E038, E041 |

## Evidence reference paths

To keep tasks economical for smaller models, use these aliases. All paths are relative to `electrophy_2`:

- **MAIN**: `Electrophy_app/app/src/main/java/com/example/electrophy_app/MainActivity.kt`
- **EXP**: same directory, `ExperimentScreen.kt`
- **MODES**: same directory, `ExperimentModes.kt`
- **MATH**: same directory, `MathEngine.kt`
- **MATHUI**: same directory, `MathVisualization.kt`
- **AIR**: same directory, `Airplane3DView.kt`
- **PICKER**: same directory, `ExperimentPickerScreen.kt`
- **FW**: `esp_sensor/main/main.c`
- **ST**: `esp_sensor/main/lsm6dsv320x_reg.c`

## P0 — Measurement integrity

### E001 — Correct high-g sensitivity conversion

**Status:** [ ] TODO. **Scope:** app conversion and documentation. **Dependencies:** none.

**Evidence:** MAIN:923–929 uses `0.009765 g/LSB` at ±320 g. ST:86–108 specifies `10.417 mg/LSB`, or `0.010417 g/LSB`. This underreports by about **6.259%**. The 64/128/256 g entries also differ slightly from the official driver. The project skill repeats these errors.

**Implement:** Centralize conversion by sensor/range/format. Use high-g g/LSB factors `[0.000976, 0.001952, 0.003904, 0.007808, 0.010417]`. Preserve the legacy packed12 multiplier of 16. Reject unknown range IDs. Correct the skill's table and any duplicate factors. Do not replace correct low-g/gyro factors using a guessed full-scale/32768 formula; use the manufacturer's sensitivities. [ST driver](https://github.com/STMicroelectronics/lsm6dsv320x-pid/blob/master/lsm6dsv320x_reg.c).

**Acceptance:** Packed value 600 at high-g FS code 4 becomes **100.0032 g**, not 93.744 g. Verify positive/negative/boundary values for every scale and both legacy formats against the bundled driver. Threshold display, export, and analysis must share the same conversion.

### E002 — MTU-aware packets and strict decoding

**Status:** [ ] TODO. **Scope:** app + firmware. **Dependencies:** coordinate packet layout with E003/E004.

**Evidence:** FW:896–898 batches against a fixed 512-byte buffer; FW:1009–1011 only logs MTU. Full notifications can contain 509 value bytes and need ATT MTU ≥512. The installed NimBLE path (`ble_att_cmd.c:74`, `ble_att.c:408–411`) truncates oversized ATT payloads. At MTU 247, a low-g packet can still declare 111 samples but contain only 52 complete tuples. MAIN:225–226 returns zero after bit exhaustion, manufacturing apparent zero-g samples. MAIN:913 treats every unknown format as raw16; range IDs default silently.

**Implement:** Size values to `min(buffer_capacity, negotiated_ATT_MTU - 3)`. Define behavior when a full tuple plus header cannot fit; do not silently send truncated ALL tuples at MTU 23. Validate the entire frame before changing state: magic/version, allowed mode/format/ranges, sample count, and required payload bytes (`ceil(count*axes*bits/8)` plus header for the legacy layout). Explicitly define allowed padding. Make bit-reader exhaustion an error. Never feed invalid frames to calibration, triggers, timekeeping, charts, or recording. Bound text fallback accumulation; malformed binary must not become an unbounded text buffer.

**Acceptance:** All modes at MTUs 23/185/247/517, odd packed sample counts, every truncation boundary, invalid headers/IDs, and random input. No invalid input emits a sample or starts free fall. Every transmitted value fits its link capacity. Add paired firmware encoder/app decoder fixtures.

### E003 — Acquisition timestamps and explicit gaps

**Status:** [ ] TODO. **Scope:** app + firmware protocol. **Dependencies:** coordinate E002/E004/E007; required by quantitative analysis.

**Evidence:** FW:127–137 has no acquisition timestamps or sample counters. MAIN:949–953 spreads samples between phone wall-clock notification times and invents +20 ms when callbacks coalesce. MAIN:908 reads but ignores sequence. MAIN:1145–1155 independently uses receipt timing for attitude. Reconnect resets the time origin without clearing chart data; resume spreads a batch across the pause.

**Implement:** Write a versioned packet specification with session/segment and configuration IDs, acquisition timestamp information, per-sensor sample counters, and validity/loss metadata. Prefer verified sensor FIFO/timestamp information; a device monotonic read timestamp needs its acquisition uncertainty documented. Define wrapping and reset behavior. Store monotonic time in `Long`/`Double`, preserving true gaps. Keep phone receipt times for transport-arrival diagnostics; absolute one-way latency additionally requires a device-to-phone clock mapping. Reset/segment on pause, reconnect, and configuration changes; never mix old/new zero-based timelines. Legacy timing must be labeled estimated and unsuitable for claims that require precision.

**Acceptance:** The same acquired samples delivered with different BLE jitter, same-millisecond bursts, and batching have identical retained timestamps and valid-interval results. Dropped samples leave explicit gaps. Phone clock changes do not alter measurements. Cover counter/timestamp wrap, pause/resume, reconnect, and long runs. No subsequent packet is stretched over a missing interval.

### E004 — Fresh per-sensor samples and real synchronization

**Status:** [ ] TODO. **Scope:** firmware acquisition and app sample model. **Dependencies:** E003, E008; coordinate E007/E009.

**Evidence:** FW:241–259 routes only the fastest selected DRDY. FW:1249 discards the returned notification count. FW:1254–1265 reads every selected channel on every wakeup, without confirming per-sensor freshness, ODR-off state, or read success. ALL at low/gyro 120 Hz and high-g 480 Hz can publish repeated low/gyro registers as 480 fresh tuples/s. Timeout wakeups and collapsed interrupts further obscure sample identity.

**Implement:** Use the part's tagged FIFO with verified configuration/timing, or read each sensor only when its data-ready state confirms a fresh sample and provide its own counter/time. Never represent an off sensor with stale values. Explicitly account for overruns/missed DRDY and rejected reads. Synchronization/resampling belongs in a declared downstream operation. Define what ALL means: the current app sometimes means accel+gyro while the wire layout includes high-g. Make source availability explicit across protocol, raw UI, and math selection.

**Acceptance:** At 120/480/120 Hz, unique per-sensor counts reflect each actual cadence. An off channel emits no valid samples. Timeout/configuration wakeups and injected SPI failures create no fake data. Validate high-rate counts and loss with FIFO counters/logic-analyzer evidence before claiming 7.68 kHz fidelity. Merely obtaining 7680 repeated values is not a pass.

## P1 — Incorrect results, crashes, and unreliable captures

### E005 — Fix median-filter crash

**Status:** [ ] TODO. **Scope:** math engine + control. **Dependencies:** none.

**Evidence:** MATH:680–695 allocates `FloatArray(w)` but fills `2*(w/2)+1` interior entries. MATHUI:68 defaults to window **12**. Selecting MEDIAN with ordinary data throws `ArrayIndexOutOfBoundsException: Index 12 out of bounds for length 12` in an actual compiled-engine probe.

**Implement:** Define centered odd window sizes, normalize public engine input safely, and make the UI show the effective odd size. Deliberately handle clipped edge windows; if their population is even, specify whether the median averages the two center values. Do not just enlarge one array while leaving contradictory window labels.

**Acceptance:** Every selectable/default size, even requested sizes, short/empty input, larger-than-input windows, constants, ramps, and isolated impulses. No crash; expected medians and effective-size labels agree.

### E006 — Repair comparator lag/correlation

**Status:** [ ] TODO. **Scope:** math engine/results. **Dependencies:** local algebra fix independent; final cross-sensor correctness needs E012.

**Evidence:** MATH:753–797 initializes `bestCorr=-2`, then only updates when `abs(c)>abs(bestCorr)`. Normal inputs cannot satisfy this, so lag remains zero and the clamped peak becomes −1. Actual identical sine probe: Pearson=1, peak=−1. A 10 Hz sine delayed six samples at 240 Hz produces lag=0 instead of 25 ms.

**Implement:** Correct initialization and choose/document positive-similarity versus absolute-similarity search. Preserve polarity. Normalize using the actual overlap or another documented bounded estimator; do not conceal incorrect normalization by clamping. Define delay sign in user language. Return unavailable for constant/insufficient signals. Report phase only for a justified common periodic component, not every arbitrary waveform.

**Acceptance:** Identical, inverted, constant, noisy, and positive/negative delayed deterministic broadband signals; edge overlaps; periodic examples with a stated ambiguity convention. For a nonperiodic B delayed 25 ms, report that B lags A by 25 ms within one-sample/interpolation tolerance. Aligned Pearson remains correct.

### E007 — Atomic applied configuration and valid ODR modes

**Status:** [ ] TODO. **Scope:** firmware + app applied-state UI. **Dependencies:** E008; coordinate E002–E004/E010.

**Evidence:** FW:504–732 mutates hardware and shared batch state from the BLE callback while the sensor task uses them. A partly filled batch can be labeled with a newly selected scale at FW:865–869. The SPI mutex does not make a whole configuration transaction atomic. Driver returns are ignored. FW:545–547 requests 1.875 Hz with unchanged high-performance mode; ST:629–637 rejects that combination, but firmware claims it succeeded. MAIN:1318 resumes before sending the rest of the configuration.

**Implement:** Have one acquisition owner consume validated configuration requests. Finish/discard a batch using its old metadata, apply a complete configuration with driver return checks/readback and required settling, increment configuration ID, acknowledge actual settings, then restore the requested running/paused state. Changing a range while paused must not restart capture. Separate desired/pending/applied settings. Make invalid/unsupported commands explicit errors. Select the documented low-power mode for 1.875 Hz or remove that option until supported; restore appropriate modes at higher rates. A GATT write acknowledgement alone is not confirmation that the IMU accepted the configuration. [ST datasheet](https://www.st.com/resource/en/datasheet/lsm6dsv320x.pdf).

**Acceptance:** Rapid range/mode changes cannot create mixed-scale or mixed-layout batches. Driver failures and invalid commands leave a clear failed/pending state rather than false success. Verify 1.875 Hz by register state and measured cadence. Capture metadata matches the applied configuration, even during reconnect/preset changes.

### E008 — Propagate SPI/IMU errors

**Status:** [ ] TODO. **Scope:** firmware readiness/error path. **Dependencies:** none.

**Evidence:** FW:347–370 discards SPI write errors and returns success. FW:382–413 discards read errors, can copy uninitialized receive data on failure, and silently clamps oversized reads. Initialization/acquisition commonly ignore driver results.

**Implement:** Propagate transport failure; release CS/mutex on every path; reject invalid lengths. Only publish fully successful measurements. Check sensor identity/configuration/readiness, expose fault counters/status, and define bounded recovery. Do not report a ready measurement device when sensor initialization failed.

**Acceptance:** Inject failures in every transaction stage, missing/wrong WHO_AM_I, oversized reads, and repeated read failure. No arbitrary values reach a packet. Resources are released and recovery/failure status is deterministic.

### E009 — Correct flow control and loss accounting

**Status:** [ ] TODO. **Scope:** firmware transport + app diagnostics. **Dependencies:** E003/E004.

**Evidence:** FW:810–816 increments `s_notify_in_flight` after `ble_gatts_notify_custom`; FW:1013–1016 decrements in `BLE_GAP_EVENT_NOTIFY_TX`. Installed IDF 5.3.1 NimBLE `ble_gattc.c:4468–4473` dispatches that event synchronously before returning, for a transmission attempt. Sequential success therefore leaves the count around one, not the claimed queue occupancy. Some dropped batches never advance sequence; app ignores sequence anyway.

**Implement:** Use a bounded acquisition-to-transport queue, explicit overflow policy, actual return-code handling, and pacing based on verified SDK semantics. Do not call notification-attempt callbacks over-air or application delivery acknowledgements. If reliable capture delivery is required, design application credits/acknowledgements or capture-then-transfer. Count all acquisition/queue/transport losses using sample identity, independently of successful packet numbering. Expose configured, acquired, and received rates separately.

Report exact loss counts where observable and explicitly unknown counts for sensor/FIFO overruns where they cannot be recovered. A counter assigned after reading registers cannot alone count unread sensor conversions.

**Acceptance:** Callback-before-return mock, allocation failures, 1M PHY, insufficient throughput, slow receiver, and disconnect during transmit. Memory remains bounded, acquisition remains responsive, no deadlock, and loss totals reconcile with sample counters.

### E010 — Reliable BLE lifecycle and operation completion

**Status:** [ ] TODO. **Scope:** Android connection owner. **Dependencies:** coordinate E007/E003.

**Evidence:** MAIN:829 declares Connected before successful CCCD completion and without requiring every characteristic. MAIN:840–855 ignores write status. MAIN:729–733/1565 treats a 250 ms timeout as completion; a late callback can advance the wrong later operation. Service discovery depends on `onMtuChanged` even when `requestMtu` fails immediately. `connectToDevice` does not retain the returned GATT until a connection callback; cancellation while connecting is incomplete. No `onCleared` cleanup or connection-generation guard exists.

**Implement:** Model scanning/connecting/discovering/subscribing/configuring/ready/error. Serialize relevant GATT operations with retained identity; check immediate start and callback status, handle fallback and bounded timeout/retry, and reconnect cleanly when state is unknown. Reject old-session callbacks and close the exact GATT instance. Retain the `BluetoothGatt` object returned by `connectGatt` immediately. Cancel scans/handlers/retries on disconnect/owner cleanup. Choose and document foreground-only capture or a properly implemented background recording service. Follow [Android BluetoothGatt contracts](https://developer.android.com/reference/android/bluetooth/BluetoothGatt).

**Acceptance:** Failed/missing CCCD, missing RX/TX, MTU-start failure, late/failed writes, scan error, connection timeout, cancellation while connecting, old disconnect callbacks after reconnect, activity rotation and final owner disposal. Never show Ready without an operational subscription/applied configuration. No leaked callbacks or GATT objects.

### E011 — Separate captures, analysis intervals, and chart history

**Status:** [ ] TODO. **Scope:** data/capture model + shared chart selection. **Dependencies:** E003/E004; coordinate E019/E021.

**Evidence:** MAIN:102/1129–1133 retains 1500 points. This is about 0.195 s at 7680 Hz, 0.391 s at 3840 Hz, 3.125 s at 480 Hz, and 12.5 s at 120 Hz. Several presets request much longer windows. EXP:399–405 analyzes the entire retained list while its chart independently selects a viewport. EXP:1605/1643 re-estimates baseline from whichever first ten samples remain. Results change when old data is evicted. Promised impulse shading is absent.

**Implement:** Introduce explicit captures with start/trigger/end, fixed pre-event baseline, source/frame, applied config/calibration, and quality flags. Use a bounded duration-aware full-rate acquisition/analysis buffer distinct from plot decimation. Lift selected interval/axis above chart and analysis; feed both the same slice and draw integral/event shading. Freeze finished captures. Define supported maximum duration/rate and storage strategy rather than retaining unbounded data.

**Acceptance:** Every supported preset duration is actually available. Saved integrals remain unchanged while the live chart scrolls. Selecting either of two pulses changes shading/results together. Missing pre-trigger baseline, clipping, gap, and incomplete endpoint are visible. Chart point eviction cannot alter a captured result.

### E012 — Use actual time and align signals before math

**Status:** [ ] TODO. **Scope:** shared math input preparation. **Dependencies:** E003/E004/E011.

**Evidence:** MATH:161/201/245/415/540/714–720 uses selected ODR. Comparator pairs equal-length tails by index with no time alignment; EXP:150–155 uses only source A's ODR and coerces off to 1 Hz. Actual engine probe: constant 1 at `[0,10,30] ms` integrates to 0.02 using 100 Hz, versus 0.03 for valid irregular timestamp integration.

**Implement:** Distinguish valid irregular sampling from missing measurements. Use actual valid intervals for calculus; segment or reject gaps rather than inventing missing values. FFT/STFT/autocorrelation require a verified uniform contiguous segment or explicit validated resampling. Align comparator streams on a common time interval/grid, with anti-aliasing before downsampling. Reject duplicate/reversed time, unavailable sources, and invalid rates. Changes in rate/configuration/processing must not retroactively reinterpret old points.

**Acceptance:** Known irregular samples, explicit missing intervals, unequal-rate streams, offset starts, no overlap, ODR changes, and duplicate/reordered timestamps. A valid irregular `[0,10,30] ms` constant record integrates to 0.03; a declared loss spanning that interval is flagged according to policy. Known frequencies and delays remain correct or produce a clear unavailable result.

### E013 — Correct force, impulse, and SI quantities

**Status:** [ ] TODO. **Scope:** collision/impulse/peak analyses and quantity metadata. **Dependencies:** E001/E011/E012/E020.

**Evidence:** EXP:424–433 labels accelerometer g/mg values “Peak Force” and g·s as `∫F·dt`. EXP:1602–1612 integrates baseline-subtracted Z without mass or SI conversion. MODES:111–130/172–191 promises force, impulse, and `Δv=J/m`, but there is no mass input.

**Implement:** First correct the labels to peak specific force/acceleration and acceleration integral. For force/impulse, require positive finite mass in kg and an explicit physical model. A fixed horizontal axis along motion is a useful initial supported setup: after appropriate baseline/external-force assumptions, report `F=m*a`, `J=∫F dt`, and `Δv=∫a dt` in N, N·s, and m/s. Mass alone does not isolate a particular contact force from other forces. For general orientation, use the documented gravity/frame transformation. Retain raw g/mg as a clearly separate display. Use timestamped trapezoids over the selected capture interval, with a fixed baseline. [BIPM standard gravity](https://jcgm.bipm.org/vim/en/2.12.html), [NXP specific-force/tilt discussion](https://www.nxp.com/docs/en/application-note/AN3461.pdf).

**Acceptance:** A horizontal acceleration pulse with integral 2 m/s and mass 0.5 kg gives impulse 1 N·s. Doubling mass doubles force/impulse and leaves Δv unchanged. Equivalent low/high-g inputs give equal SI results. Missing/invalid mass leaves force unavailable. Stationary baseline-relative data yields zero integral. Do not convert an existing wrong number merely by changing its label.

### E014 — Repair vehicle velocity-change experiment

**Status:** [ ] TODO. **Scope:** vehicle preset, setup, integration. **Dependencies:** E011–E013/E020; dynamic-orientation option also needs E017.

**Evidence:** EXP:446–449 chooses `highGOdr ?: lowGOdr`, but highGOdr is non-null, even for low-g data. With low-g 120 Hz and high-g 480 Hz, integration is one quarter of its intended scale; a retained high-g `off` instead hits a 100 Hz fallback. Output is labeled `units/s` although an integral multiplies by time. MODES:92–106 selects Z without forward-axis setup and arms a 3 g deviation trigger.

**Implement:** Consume actual low-g timestamps, eliminate duplicated string/rate parsing, and report Δv in m/s. Add forward axis/sign, stationary pre-run baseline, explicit start/stop/reset, and a fixed-orientation mounting contract. Flag motion outside that model; a baseline does not remove gravity when the board rotates. A general moving-orientation option requires synchronized gyro, gravity correction, and drift handling. Remove the ordinary-driving 3 g auto-stop default; retain deliberate spike capture as a separate choice. Do not label Δv as absolute speed without initial velocity.

Low-g alone cannot reliably distinguish tilt from linear acceleration. Fixed mounting is a declared user assumption; any claimed automatic rotation check requires synchronized gyro and stated detection limits. If that check is not implemented, show the limitation and do not claim automatic rejection of tilted runs.

**Acceptance:** A baseline-corrected +1000 mg plateau with endpoints 1 s apart integrates to 9.80665 m/s, independently of retained high-g settings or prior experiments. Stationary data gives zero. Rotating a stationary board is corrected or visibly unsupported, not plausible vehicle acceleration. Re-run starts a new baseline/origin.

### E015 — Complete vector-based free-fall events

**Status:** [ ] TODO. **Scope:** shared trigger/duration detector. **Dependencies:** E002/E003/E011/E019/E020.

**Evidence:** MODES:142–148 triggers on `Z <= 100 mg` and pauses 500 ms later, but EXP:1653–1674 measures `|a| < 200 mg`. A stationary side-on board `[1000,0,0] mg` triggers despite not falling. The helper reports the longest retained low-g interval without requiring a complete start/end; a fall exceeding the delay can be presented as a completed truncated fall.

**Implement:** Use one calibrated specific-force magnitude detector for arming, triggering, and analysis. Define hysteresis, minimum duration, stationary/armed state, return/contact completion, and a maximum timeout that yields incomplete status. Use acquisition timestamps and justified threshold-crossing interpolation. Reject intervals spanning gaps/missing endpoints. Analyze acquisition data with documented filter-delay behavior. Explain near-zero accelerometer output as weightlessness/near-zero specific force, not absence of gravitational acceleration.

**Acceptance:** No stationary orientation at magnitude 1 g triggers. A complete 100 ms zero-g interval is measured within stated sampling tolerance. An 800 ms event is not silently cut to 500 ms. A low-g event at the capture boundary is in progress/incomplete. Gaps and invalid packets cannot create free fall.

### E016 — Measure pendulum swing period correctly

**Status:** [ ] TODO. **Scope:** pendulum signal/setup/estimator. **Dependencies:** E003/E011/E012.

**Evidence:** MODES:74–84 uses low-g only and suggests `T=2π√(L/g)` without a mounting model. EXP:1615–1637 finds upward mean crossings of Z. A co-rotating radial accelerometer contains `g*cos(theta) + L*thetaDot²`, terms that can repeat twice per swing. A reproduced `theta=0.2*cos(πt)` model with a 2 s swing yields a 1 s signal period. The half-period conclusion is an inference from the radial equations, not a universal correction. [Pendulum acceleration research](https://arxiv.org/pdf/1406.3867).

**Implement:** Prefer the gyro axis normal to the swing plane, with mounting/axis guidance and sufficient cycles. Add noise/prominence/hysteresis and cycle-consistency checks. If retaining accelerometer mode, call it signal period unless a specific documented mounting/model justifies conversion. Never automatically double every acceleration period. Add length from pivot to center of mass and a small-angle/simple-pendulum assumption for theoretical comparison. Distinguish physical-pendulum geometry when appropriate. [phyphox pendulum method](https://phyphox.org/wiki/index.php?title=Experiment:_Pendulum).

**Acceptance:** A 0.5 Hz angular-rate oscillation gives about 2 s. The corresponding radial signal is not reported as an unqualified 1 s swing. Flat/noise-only input, insufficient cycles, and invalid timing yield an explanatory unavailable result. Theory and measurement use the same physical period.

### E017 — Coordinate-consistent orientation estimator

**Status:** [ ] TODO. **Scope:** pure attitude estimator and input pipeline. **Dependencies:** E003/E004/E020; coordinate E032.

**Evidence:** MAIN:1145–1184 integrates host-arrival time and independent body gyro axes as Euler angle derivatives; these are not generally equivalent. It always blends accelerometer tilt, even during free fall/strong acceleration, and clamps Euler angles. Arithmetic blending across ±180° takes the wrong path: `0.97*179+0.03*(-179)=168.26°` although the orientations differ by 2°. Reset does not consistently reset estimator time/state. Static tilt signs do agree with AIR's X-right/Y-forward/Z-up convention.

**Implement:** Extract a pure normalized-quaternion or rotation-matrix estimator with documented body/world conventions. Integrate calibrated body angular rate using actual sample intervals, and synchronize accel observations by time. Reduce/disable gravity correction for invalid, saturated, low-g, or inconsistent observations; magnitude gating alone cannot reject every linear acceleration. Make correction time-constant based and independent of notification batching. Convert to Euler for display only. Reset time/history/reference coherently across sessions and explicit resets. Preserve correct static renderer signs unless a verified board mapping requires change. Yaw remains relative without an absolute heading reference. [NXP AN3461](https://www.nxp.com/docs/en/application-note/AN3461.pdf).

Convert gyro mdps to rad/s using `π/180000` before quaternion propagation. Continuity through 90° pitch applies to the quaternion/rendered orientation; Euler readouts have unavoidable singularities/branch changes and cannot be globally nonsingular.

**Acceptance:** Analytic rotations about individual and combined axes, ±180° crossings, passage through 90° pitch, stationary tilt, free fall, and acceleration disturbance. The same time-stamped motion has the same orientation under different BLE delivery schedules. Long pauses cannot integrate stale angular rate. Verify physical board axes separately from the renderer convention.

### E018 — Correct attitude plot and heading reset

**Status:** [ ] TODO. **Scope:** aircraft UI and reference ownership. **Dependencies:** E011/E017 for the final attitude-history plot; truthful relabeling is independent.

**Evidence:** EXP:314–319 selects low-g points in aircraft ALL mode; EXP:342–345 plots them with MODES:61–62 “Orientation Telemetry (deg)”. An upright ~1000 mg signal is thus plotted as ~1000 degrees. EXP:83/326–329 stores a local yaw offset while MAIN:675/1493 separately resets the underlying yaw, leaving inconsistent zeros.

**Implement:** Plot timestamped pitch/roll/relative-yaw history in degrees, or explicitly label an interim acceleration plot in mg. Own zero-heading/reference state in the estimator/ViewModel. Define Clear, Calibrate, Zero Heading, reconnect, and new-experiment behavior together. Break wrapped-yaw discontinuities or offer continuous-angle display.

**Acceptance:** Stationary upright pitch/roll plot near 0°, a known 30° pitch plots 30°, and no acceleration samples are labeled degrees. Zero Heading followed by Clear/Calibrate/reconnect produces the documented reference without a stale local offset.

### E019 — Typed trigger source and cancellable event lifecycle

**Status:** [ ] TODO. **Scope:** raw/experiment trigger engine. **Dependencies:** E003/E011; coordinate E015/E020.

**Evidence:** MAIN:408–460 has one X/Y/Z baseline and threshold. MAIN:1000–1010 passes low-g mg and high-g g or gyro mdps through that same state in combined modes, mixing quantities. Trigger evaluation occurs before calibration. Delayed pause uses an anonymous new Handler callback; disabling the trigger, Clear, changing experiment, or disconnecting does not cancel it. An old event can pause a later run. Its delay is based on receipt time, not the triggering sample.

**Implement:** Select sensor + axis/magnitude + units explicitly. Keep baseline state per selected source and processing definition. Use an event/session token and cancellable job/state machine: disarmed, warming/armed, triggered, collecting, complete/cancelled. Stop/reset must invalidate pending jobs. Capture the exact trigger acquisition time and define pre/post intervals in measurement time. Keep host-side notification delay distinct from physical event timing. Define crossing versus level triggering, hysteresis, minimum dwell, and rearm behavior. Validate finite thresholds and nonnegative bounded durations.

**Acceptance:** An mg trigger never consumes g/mdps. Calibration state matches the trigger's displayed signal. Trigger→disable, trigger→Clear, trigger→new experiment, and trigger→disconnect/reconnect cannot pause the new session. Test threshold chatter, missing baseline, batch boundaries, and event delay independently of BLE latency.

### E020 — Validate and scope calibration

**Status:** [ ] TODO. **Scope:** calibration pipeline, persistence, and UX. **Dependencies:** E004/E007/E011; no full attitude rewrite required.

**Evidence:** MAIN:599–685 averages available samples without motion/variance/sample-count validation, assumes +Z-up gravity, and sets one `isCalibrated` flag if either low-g or gyro produced samples. MAIN:688–714 persists one global set for every device. High-g has no calibration. Filtered values are passed to calibration (MAIN:996–1014/1091 onward). Disconnect during collection can leave the progress state unresolved. Calibration help promises zero drift/perfect level attitude.

**Implement:** Clearly define single-position bias calibration and supported sensors. Temporarily acquire the required channels at an applied known configuration, wait for settling, use unfiltered raw physical samples, and validate stationary magnitude/gyro variance/adequate independent counts. For accelerometer calibration require a level +Z-up fixture/setup and reject gross tilt/motion with documented tolerances; stationary gyro bias calibration can use any orientation. A single static pose cannot uniquely distinguish small tilt from horizontal bias, so disclose that ambiguity. Scope validity separately per sensor and stable physical-device identity, not its common name or potentially rotating BLE address; retain calibration method/time/configuration and invalidate appropriately. Restore the prior acquisition state. Add cancellation and timeouts. Keep high-g visibly uncalibrated unless a validated method is added; do not reuse low-g offsets. Consider six-position scale/bias calibration later, without calling the present method full calibration. Reset processing state after acceptance.

**Acceptance:** Moving, tilted, sparse, disconnected, powered-off-channel, and failed-read runs cannot produce a universal CALIBRATED badge. Device B does not inherit device A's offsets. Repeated calibration of a known synthetic bias converges to that bias without double subtraction. The UI states residual drift/limits and reports sensor-specific validity.

### E021 — Reproducible structured recordings and reliable storage

**Status:** [ ] TODO. **Scope:** recording/export pipeline. **Dependencies:** E003/E007/E011/E020.

**Evidence:** MAIN:1073–1086 formats strings and writes synchronously inside packet handling. MAIN:1501 creates `.txt` in cache; rows have no time/configuration/sample identity. Values are filtered but uncalibrated, while charts subtract calibration later. Stop/empty-share/storage-error handling is minimal. A capture cannot be independently reconstructed from the export.

**Implement:** Use an ordered bounded background writer with visible overflow/storage errors. Define a schema with session/segment, acquisition time, sensor/sample identity, raw counts or raw physical samples, physical units, applied configuration, calibration/filter provenance, and gaps/clipping. A long-format CSV per sensor plus a metadata JSON is one workable choice for asynchronous channels; never imply simultaneous tuples by filling missing channels with zero. Export processed values or enough metadata to reproduce them. Finalize/flush before sharing. Preserve recordings in durable app storage or a user-selected location; keep temporary share copies separate. Show duration/size/error/recording state across disconnect and distinguish Save from Share.

For recursive filters, raw samples and alpha alone are insufficient if recording starts mid-stream. Store processed values or the initial filter state/prehistory (or explicit capture-boundary reset) and every processing-parameter epoch needed for exact replay.

**Acceptance:** Export reproduces the declared chart/analysis inputs within serialization precision. Mode/rate changes and gaps remain visible. Low storage, write failure, stop during incoming traffic, empty recordings, and disconnection are handled explicitly. Sustained recording at each validated rate is loss-accounted and does not block BLE parsing. Update the skill's inaccurate CSV description when the new format exists.

### E022 — Correct FFT/STFT bins, amplitudes, and peak validity

**Status:** [ ] TODO. **Scope:** shared spectrum computation/results. **Dependencies:** E012 for measurement validity; pure algorithm fixes independent.

**Evidence:** MATH:244–327 excludes Nyquist (`N/2`), labels an all-zero input's first non-DC bin dominant, and calls a magnitude/distance-only second candidate a distinct local peak. MATH:433–448 STFT lacks FFT's coherent-window-gain compensation. Actual probes: 256 zeros at 256 Hz→dominant 1 Hz; `(-1)^n`→missing 128 Hz amplitude; a unit-amplitude 16 Hz Hann tone gives FFT 0.999996 but STFT 0.498045.

**Implement:** Share tested real-spectrum code returning `N/2+1` bins. Double only interior one-sided amplitudes, not DC/Nyquist. Apply matching coherent-gain normalization for amplitude STFT; if choosing PSD, explicitly change quantity/units and use the appropriate power normalization. Return absent/insignificant peak status instead of an invented frequency. Require actual local maxima and justified prominence/separation; do not report a window sidelobe as a second physical tone. Define dB reference and floor. Distinguish bin spacing, observation duration, and resolvable separation; zero-padding does not add information. [SciPy spectral analysis reference](https://docs.scipy.org/doc/scipy-1.15.2/tutorial/signal.html).

**Acceptance:** DC, zero, impulse, Nyquist, bin-centered/off-bin sine, two resolvable tones, single-tone leakage, multiple windows, and partial records. `(-1)^n` at fs=256 has amplitude 1 at 128 Hz with a rectangular window. FFT/STFT amplitudes agree under the same definition. No data/no significant tone returns unavailable, not 1 Hz.

### E039 — Reproducible build and scientific regression suite

**Status:** [ ] TODO. **Scope:** build/test infrastructure and documented hardware qualification. **Dependencies:** begin independently; expand fixtures as tasks land.

**Evidence:** The local unit test is only `assertEquals(4,2+2)`; the instrumentation test is the generated package-name check. The wrapper specifies Gradle 9.6.0 and the daemon file JDK 25, while the build uses AGP 8.5.2/Kotlin 2.1.0 and compileSdk 35. AGP 8.5 officially supports up to API 34. This is a documented compatibility mismatch, not proof that the current source cannot compile. The attempted audit build failed in local cache/JDK setup before app validation. [AGP 8.5 compatibility](https://developer.android.com/build/releases/agp-8-5-0-release-notes), [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html).

**Implement:** Establish and pin a supported AGP/Gradle/Kotlin/JDK/SDK combination, retaining required APIs; choose versions from official compatibility documentation when implementing rather than blindly upgrading everything. Document clean Windows build setup and CI. Create pure protocol/scientific fixtures covering tasks E001–E032, plus fake GATT/device callbacks and captured packet replays. Add Android UI/lifecycle tests where platform behavior matters. Treat tests as part of each fix, not a late substitute for them.

**Acceptance:** A documented fresh environment builds debug, runs meaningful unit tests and lint, and produces recorded outcomes. Firmware compiles using its documented IDF version after relevant changes. A hardware checklist records device/phone/firmware/configuration, actual unique rates/loss, negotiated MTU/PHY, clock drift, stationary noise/bias, six orientations, known angular motion, and known vibration/impact stimulus within the equipment's limits. Test at least the Android permission/API branches (pre-31, 31+, and 33+ value callbacks), different MTUs, long captures, pause/reconnect, and memory/storage pressure. Report measured tolerances, not just pass/fail. Do not claim calibrated force/impact accuracy or sustainable 7.68 kHz acquisition until the required hardware evidence exists.

## P2 — Interpretation, performance, and user experience

### E023 — Stable integral origins and honest detrending

**Status:** [ ] TODO. **Scope:** numerical integration mode. **Dependencies:** E011/E012.

**Evidence:** MATH:154–187 performs valid uniform-sample trapezoidal integration, but MATHUI:561–579/866–880/1075–1079 recomputes from zero at the moving visible window's left edge. Changing view width changes “Current Integral.” Default mean removal subtracts the full-window input mean and, for second integration, the mean velocity; it erases constant acceleration and changes assumed initial conditions.

**Implement:** Offer clearly named window integral and integral since an explicit origin/reset. Preserve the latter independently of scrolling. State initial conditions and whether the operation is AC/mean-removed. Detrending is not gravity compensation. Use quantity-aware units: integrating angular rate about a fixed known axis gives angular displacement; independently integrating body gyro components does not generally give 3D Euler attitude angles. A second gyro integral has angle×time units, not displacement. Integral of acceleration magnitude is not generally vector speed. Keep generic educational math separate from physical motion estimates.

**Acceptance:** Constant retained-DC input gives `a*t` and `a*t²/2` with zero initial conditions. View width does not alter the since-reset result. Reset establishes an explicit new origin. Mean removal gives zero for constant input and is explained. Source/unit changes invalidate or segment the old integral.

### E024 — Invariant autocorrelation period detection and useful lag range

**Status:** [ ] TODO. **Scope:** autocorrelation engine/control. **Dependencies:** E011/E012.

**Evidence:** MATH:568–617 applies a fixed `>0.15` detection threshold to raw covariance when normalization is disabled. A 0.1-amplitude 10 Hz sine yields T=0.1 s normalized but no period in raw mode. UI max lag defaults to 128 samples at 240 Hz (~0.53 s), and its largest choice 256 (~1.07 s) cannot cover many suggested pendulums. Instructions promise an exact fundamental/first major peak while code chooses a strongest candidate.

**Implement:** Detect on a normalized internal estimator regardless of display scaling. Label raw covariance with squared input units. Set lag horizon in seconds, show searchable period range, and require adequate history/cycles. Use prominence/consistency/harmonic checks and report an estimate, not an exact fundamental. Preserve the current legitimate biased estimator denominator unless intentionally changing its contract. [NIST autocorrelation definition](https://itl.nist.gov/div898/handbook/eda/section3/eda35c.htm), [NIST explanation of denominator N](https://www.itl.nist.gov/div898/handbook/eda/section3/autocopl.htm).

**Acceptance:** Unit/amplitude changes and raw/normalized display toggles do not change the period of the same valid signal. Test constant/noise-only input, 2 s periods with sufficient history, harmonics, and partial cycles. Unsupported lag ranges are visible before analysis.

### E025 — Correct digital cutoff and response descriptions

**Status:** [ ] TODO. **Scope:** raw EMA + filter lab + help text. **Dependencies:** a valid per-source sample rate; coordinate E012.

**Evidence:** MAIN:344–346 and MATH:699–701 report `alpha*fs/(2π*(1-alpha))` as EMA cutoff. That is an analog/backward-Euler parameter, not the actual digital −3 dB frequency. At fs=240 Hz, alpha=.5 displays 38.197 Hz while the actual crossing is 27.606 Hz; alpha=.9 displays 343.775 Hz above Nyquist although there is no −3 dB crossing. HP is `input-LP`; band-pass cascades have independent sliders and can imply reversed edges.

**Implement:** For `H(z)=alpha/[1-(1-alpha)z^-1]`, the LP −3 dB crossing is `fs/(2π)*acos(1-alpha²/[2*(1-alpha)])` only when the argument is valid. For `alpha > 2*(sqrt(2)-1)`, report no crossing below Nyquist. Handle alpha 0/1 explicitly. Derive/test HP cutoff against a declared gain reference and the actual cascade response; do not reuse an LP label blindly. Order requested band edges or display the true resulting response. Show per-sensor cutoff when cadences differ. Explain moving-average latency, centered median look-ahead, and that a residual is not automatically noise. Centralize duplicated cutoff logic.

**Acceptance:** Steady-state sine-sweep response matches the implemented filter across alpha values, including no-crossing cases. Test DC/step behavior, band ordering, per-sensor rates, and endpoints. UI never implies a realizable cutoff above Nyquist. A filter result preserves its declared delay/phase interpretation.

### E026 — Correct statistical labels and plots

**Status:** [ ] TODO. **Scope:** statistics result/visualization. **Dependencies:** none for local fixes; valid data input from E012.

**Evidence:** MATHUI:701–706 calls `mean±2*stddev` “Confidence Bands.” These are descriptive spread, not confidence intervals for the mean. MODES:321 asserts Gaussian thermal/MEMS noise without evidence. Histogram bars and Gaussian reference use different vertical scales (MATHUI:1537–1572). MATH:498–505 returns zero skewness/kurtosis at zero variance, where these standardized moments are undefined.

**Implement:** Label population statistics as descriptive moments, rename bands “±2 standard deviations,” and describe the normal curve as a reference model. Use one count/density scale for both bars and curve. Show unavailable for undefined moments; choose a sensible centered constant-signal histogram domain. Do not automatically replace population variance denominator N with N−1. Any future inferential confidence interval must declare assumptions, including dependence among sensor samples. [NIST confidence interval for the mean](https://www.itl.nist.gov/div898/handbook/eda/section3/eda352.htm).

**Acceptance:** `[1,2,3]` gives mean 2, population variance 2/3, RMS `sqrt(14/3)`, skewness 0, excess kurtosis −1.5. Cover empty/singleton/constant inputs, histogram count conservation, consistent bar/reference scales, and non-Gaussian data. No normality claim follows solely from a bell-shaped overlay.

### E027 — Source-aware derivatives and accurate stencil labels

**Status:** [ ] TODO. **Scope:** derivative mode/terminology. **Dependencies:** E012.

**Evidence:** MATH:218–224 uses two endpoints separated by `2*k` samples, while MATHUI:170–185 calls choices 1-step/3-step/5-step stencils. This is a wider secant, not a three-/five-point higher-order formula or smoothing fit. Labels jerk/snap do not fit gyro sources; a derivative of acceleration magnitude is not vector jerk magnitude.

**Implement:** Name the current method accurately: central difference with half-span k, declared endpoint treatment and response. Alternatively implement an independently validated multi-point/Savitzky–Golay derivative as a distinct option. Use source-specific names and units: angular acceleration/angular jerk for gyro derivatives; scalar-magnitude derivative for magnitude input. Explain noise amplification and rotating-frame/gravity limits before calling results physical jerk.

**Acceptance:** Constant, linear, quadratic, and sinusoidal inputs across widths and endpoints; valid irregular-time policy; angular and magnitude sources; correct rate/unit scaling. Wider intervals must not be advertised as an unimplemented higher-order stencil.

### E028 — Correct the Newton's Third Law claim

**Status:** [ ] TODO. **Scope:** current single-sensor experiment wording/model. **Dependencies:** E013; optional two-object work also requires synchronized captures.

**Evidence:** MODES:111–130 has one high-g stream and peak/integral features. It does not measure both objects or isolate equal-and-opposite interaction forces.

**Implement:** Rename the current mode to Collision Acceleration, or clearly describe it as a one-object measurement within a third-law lesson. Do not claim experimental verification of an action–reaction pair. Explain that different masses have different accelerations under equal-and-opposite forces. Two-device capture/import can be a separately scoped future enhancement requiring both masses, common axes/time, and external-force assumptions; do not add dual BLE merely to repair current wording.

**Acceptance:** The existing single-capture screen makes no paired-force verification claim. An educational unequal-mass example distinguishes force equality from acceleration inequality. Any future quantitative paired result requires the necessary second capture and alignment metadata.

### E029 — Complete centripetal analysis

**Status:** [ ] TODO. **Scope:** centripetal preset and inputs/results. **Dependencies:** E011–E013; synchronized angular-rate option needs E004/E012.

**Evidence:** MODES:155–169 describes `a=omega²*r`, but the current screen only returns generic historical axis peaks labeled force. No radius, radial direction, or angular-speed comparison exists.

**Implement:** Add radial axis/sign, radius from rotation axis to sensor, and a documented mounting setup. For example, horizontal radial measurement can keep gravity on a different vertical axis. Compare steady radial mean/variation with `omega²*r`; obtain synchronized gyro angular speed or explicitly user-supplied RPM and convert to rad/s. Mark supplied versus measured inputs. Handle gravity projection, transient intervals, clipping, and invalid radius. Use a selected steady interval instead of the highest historical peak.

**Acceptance:** Radius 0.1 m and angular speed 2 rad/s predicts 0.4 m/s² (~40.79 mg). Separate vertical 1 g does not contaminate radial comparison. Degrees/s and RPM inputs convert consistently. Zero/negative radius and saturated/unstable runs do not yield an unqualified agreement score.

### E030 — Complete elevator interpretation

**Status:** [ ] TODO. **Scope:** elevator setup and analysis. **Dependencies:** E011/E013/E020.

**Evidence:** MODES:194–208 asks the user to hold the device but assumes upward Z and supplies no quantitative load analysis. Tilting can resemble acceleration.

**Implement:** Guide stable +Z-up mounting or establish vertical from a stationary gravity reference with clearly limited orientation assumptions. Display signed vertical specific force/load ratio, baseline-relative vertical acceleration, and optional apparent weight `N=mass*f_up` with mass in kg. Explain that acceleration direction differs from travel direction: upward motion can include downward acceleration while braking. Flag tilt or correct it using a validated orientation pipeline.

**Acceptance:** Upward specific force 1.1 g gives load ratio 1.1 and net upward acceleration 0.980665 m/s²; 0.9 g gives negative net vertical acceleration. Mass changes apparent weight, not load ratio. Stationary tilted data is corrected or rejected under the declared setup.

### E031 — Remove unsupported flight/compass claims

**Status:** [ ] TODO. **Scope:** aircraft HUD/help/calibration wording. **Dependencies:** labels can be fixed independently; derived heading rate needs E017.

**Evidence:** AIR:185 maps pitch to CLIMB/DIVE; AIR:194–198/409–413 uses geographic compass directions for integrated relative yaw. Norm-based specific force is presented as aircraft load factor, and some help associates descent with reduced g (AIR:280/300/326–344). MAIN:1193 labels body-Z rate as turn rate. MAIN:3365–3372 promises zero drift/perfect level calibration.

**Implement:** Use nose up/down for pitch, relative heading for yaw, and remove N/E/S/W unless a real absolute reference exists. Name `|f|/g` specific-force magnitude: it is unsigned and is not signed normal load. Label body-Z angular rate accurately or derive a valid world-heading rate. Replace zero-drift guarantees with bias correction and measured residual limits. Explain that pitch does not determine climb rate. Show stale/unavailable telemetry when source quality fails, rather than plausible default 1 g/zero angles. Offer appropriate low-rate gyro range guidance through E032.

**Acceptance:** Yaw zero does not imply north. A stationary nose-up board is not labeled climbing. Negative normal acceleration is not represented as positive signed normal load. Missing/stale input and residual drift are visible. [ST tilt foundations](https://www.st.com/resource/en/application_note/an4509-tilt-measurement-using-a-lowg-3axis-accelerometer-stmicroelectronics.pdf), [NXP orientation limitations](https://www.nxp.com/docs/en/application-note/AN3461.pdf).

### E032 — Explicit transport resolution and range policy

**Status:** [ ] TODO. **Scope:** versioned transport precision and range UX. **Dependencies:** E001–E004/E009.

**Evidence:** FW:828–832 always applies signed `raw >> 4`. The existing bit packing correctly transmits that value, but it is lossy floor quantization: exhaustive 16-bit input characterization gives reconstruction error −15..0 raw counts and mean −7.5 counts across all codes. At ±2000 dps the transmitted step is 1.12°/s, larger than the 0.25°/s yaw deadband. At corrected ±320 g the step is 0.166672 g.

**Implement:** Offer raw16 at rates supported by measured transport capacity, or a new documented rounded quantizer/format with boundary saturation rules. Do not silently change legacy packed12 semantics. Preserve sensitivity/quantization metadata. Show the resolution/range tradeoff and suggest narrow ranges for small motions while detecting clipping. A ±250 dps range has 0.14°/s packed steps, but changing range alone does not prove drift-free accuracy. Preserve compatibility with older firmware.

**Acceptance:** Signed extrema, zero, ±1 count, full code range, rounding bias characterization, saturation, and mixed-version decoding. Compare small sinusoid and constant angular-rate reconstruction at supported formats/ranges. Verify throughput before making raw16 the default for high-rate modes.

### E033 — Shared background analysis and bounded rendering

**Status:** [ ] TODO. **Scope:** processing architecture/performance. **Dependencies:** E011/E012; preserve E022 result definitions.

**Evidence:** MATHUI:550–818 and 828–1066 independently extract/process the same data in composition. STFT recomputes its available history on each update. MAIN repeatedly copies whole lists and rebuilds chart datasets. Duplicate UI-thread work is confirmed; actual frame-drop severity has not been measured.

**Implement:** Compute one immutable result per operation/input/parameter version on a background dispatcher; share it between plot and result card. Cancel obsolete work and publish at a bounded UI rate. Keep ordered acquisition/recording independent of display refresh. Decimate for drawing with a peak-preserving method where impacts matter; analyze original data. Cache/incrementally update STFT on stable sample offsets. Avoid quadratic allocation churn when increasing history capacity. Prevent old-source jobs from overwriting current results.

**Acceptance:** Profile long-window, high-rate, and combined-sensor cases on a representative phone. Record frame time, CPU/memory, backlog, and received-loss counters before/after. Rapid control changes, pause/reset, and source switches stay responsive without stale results. Numeric outputs match the full-rate reference and drawing decimation does not erase reported peaks.

### E034 — Trustworthy sample cursors and interval inspection

**Status:** [ ] TODO. **Scope:** raw/physics chart interaction. **Dependencies:** E011; time precision from E003.

**Evidence:** MAIN:2801–2807 makes a live cursor from an offset touch coordinate, not a sampled trace. It is presented as point inspection. Paused highlights at MAIN:2780–2798 discard dataset identity, so C1/C2 can be different axes and still produce a slope. MAIN:2996–3018 assigns zero slope at zero Δt and omits axis-specific units. A later single tap replaces C1 although text says tap for C2. EXP contains a separate chart implementation with similar behavior.

**Implement:** Distinguish a free coordinate crosshair from sample inspection; default measurement inspection to a real sample on an explicitly selected trace. Retain source/axis/sample identity and units in cursor state. Permit slope only on the same physical quantity/trace, or explicitly describe a cross-trace difference without calling it a derivative. Show undefined at zero Δt. Add simple C1/C2 selection handles or buttons so dual measurement does not require two simultaneous fingers. Preserve cursor selections on paused captures and invalidate them coherently on new data/session. Reuse interaction logic across raw and experiment charts.

**Acceptance:** Tapping known synthetic points returns their exact sample time/value/axis within selection tolerance. Different axes cannot silently generate a same-trace slope. Zero Δt is unavailable. Two cursors can be placed with one finger/accessibility controls, and displayed Δt/Δvalue/slope units are correct. Selected interval agrees with E011 shading/results.

### E035 — Preserve experiment state and handle system Back

**Status:** [ ] TODO. **Scope:** navigation and saved UI/session state. **Dependencies:** coordinate E010/E011/E019.

**Evidence:** MAIN:1599–1600 stores routing/active experiment only in `remember`; EXP:87 likewise stores math state locally. Activity recreation returns to raw mode while the ViewModel may retain a different active experiment/configuration. EXP:103 reapplies presets and clears data on entry. There is no explicit system-Back navigation path. Raw mode inherits modified experiment settings without a clear restore action.

**Implement:** Use saved navigation/parameter state with stable experiment IDs (Navigation Compose or a small equivalent is sufficient). Make applying a preset an explicit new-run/configuration action, not an accidental consequence of composition. Define resume versus new run, manual-setting restoration, and system Back behavior. Preserve a recorded capture when leaving its screen; if Clear discards unsaved work, provide undo or a targeted save/discard choice. Distinguish display pause from acquisition pause if both are offered. Do not silently restart/reset a capture during rotation.

**Acceptance:** Rotation and process restoration recover the intended screen/settings under the documented capture policy. System Back returns through experiment→catalog→raw appropriately. Re-entering a screen does not clear a completed run or arm an old trigger. Manual raw settings can be restored deliberately.

### E036 — Device selection, recovery, and live diagnostics

**Status:** [ ] TODO. **Scope:** connection UX and stream availability. **Dependencies:** E004/E007/E009/E010.

**Evidence:** MAIN:740–747 connects to the first named `ESP_IMU`, with no device choice. MAIN:1220–1223 returns silently when Bluetooth/scanner is unavailable; scan error/recovery details are absent. The experiment screen has a status chip but no connect action. ALL is named All Sensors, decoded as nine axes, but raw UI MAIN:1696–1709/1899–1913 only presents low-g and gyro; automatic selection can leave high-g off.

**Implement:** Show a scan list with device name, identity, RSSI, and remembered last device; use service/capability filtering with a documented legacy fallback. Provide actionable Bluetooth-off, permission-denied/permanently-denied, legacy location-state, no-device, connection-failed, and reconnect states. Share connection access across raw and experiment screens. Make ALL/accel+gyro/high-g availability explicit, not contradictory. Show per-sensor configured and received unique rates, capture gaps/drops, clipping, freshness, and applied settings; put detailed MTU/PHY/protocol diagnostics in an expandable panel.

**Acceptance:** Two same-name modules are selectable and calibration follows the chosen device. Users can connect/recover from an experiment without losing setup. Permission denial and Bluetooth-off explain the next action. Off sensors are visibly unavailable and cannot produce analyses. Diagnostics distinguish requested ODR from actual delivered valid samples.

### E037 — Inspectable FFT, spectrogram, and XY plots

**Status:** [ ] TODO. **Scope:** math plot interaction/axes. **Dependencies:** E012/E022/E024.

**Evidence:** MATHUI:1217 declares `inspectedBin` but never assigns it, so the FFT inspection badge cannot activate. MATHUI:1321–1443 does not use STFT slice times for its horizontal axis and has no useful numeric time/amplitude legend; each frame scales colors to its current maximum. MATHUI:1919–2025 does not draw passed XY labels/numeric axes and uses one raw scale even for incompatible units.

**Implement:** Wire FFT selection to actual bins and show frequency/amplitude/units/window information. Draw STFT against acquisition time with frequency ticks, numeric color legend, and explicit fixed/auto scaling; inspect time/frequency/amplitude. Label both XY axes and independently scale different physical units, or normalize with a declared dimensionless convention. Preserve per-experiment controls via E035. Indicate analysis history and frequency/lag limits so users understand missing detail.

**Acceptance:** A known chirp appears at the right times/frequencies. A signal amplitude step remains comparable under fixed spectrogram scale. Selecting a known FFT bin shows its actual value. Mixed mg/mdps XY axes are correctly labeled/scaled; normalized mode is unmistakable. Touch/keyboard/TalkBack paths can reach the core inspection values.

### E038 — Responsive controls and accessible plots

**Status:** [ ] TODO. **Scope:** all screens. **Dependencies:** none for local improvements; coordinate E034–E037.

**Evidence:** PICKER uses non-wrapping configuration-chip rows. MAIN/EXP divide fixed screen space between charts and dense controls; several controls/text use 9–11 sp sizes and very short slider rows. Custom MATHUI chips lack explicit selection semantics, and Canvas charts lack equivalent accessible measurement output. These are source-indicated risks, not a completed device visual audit.

**Implement:** Test and adapt layouts for narrow phones, landscape, large font/display size, keyboard, and screen-reader use. Wrap/scroll chip groups intentionally, keep primary run/stop/connect controls reachable, and provide suitable touch targets. Add semantic labels/selected states for custom controls. Do not use color alone for axes/connection/quality. Supply text/table alternatives for essential plot values and a full-screen chart option. Move shared visible text to resources and use locale-aware formatting while keeping exported numeric schema locale-stable.

**Acceptance:** Device/emulator screenshots and interaction checks at compact width and enlarged fonts; no clipped critical controls or hidden result errors. TalkBack announces operation/source/selection/value/status, axes are identifiable without color, and run/stop/inspect are usable with accessible input. Validate light/dark contrast only if both themes are supported.

### E041 — Guided setup, run, review, and quality feedback

**Status:** [ ] TODO. **Scope:** experiment workflow UX. **Dependencies:** E007/E011 and the selected experiment's correctness tasks.

**Evidence:** EXP applies a preset immediately on entry, hides setup instructions by default, and mixes live controls/results. Physics parameters such as mass, axis, radius, and capture completeness are missing from the current workflow. The catalog title “Physics Experiments” also includes eight mathematics modes.

**Implement:** Use a concise setup→ready→run→review flow. Show required mounting/axis, physical assumptions, parameters with units, active calibrated sources, applied range/rate, and readiness before arming. Start a new capture deliberately. During capture show elapsed time and event state; after capture show the selected interval, quality flags, calculation definition, result units, and save/repeat actions. Make expected qualitative behavior and theory-versus-measurement comparison available without crowding live controls. Rename catalog appropriately and add lightweight category/search/favorites only where they improve discovery. Parameter validation belongs next to its input.

**Acceptance:** A new user can complete each of the eight physics experiments without guessing orientation, mass, axis, or what constitutes a completed run. Incomplete/gapped/clipped/uncalibrated conditions explain which result is affected. Repeat uses a fresh baseline and preserves the previous saved capture. All math modes disclose source, interval, processing parameters, and units.

## P3 — Optional expansion after correctness

### E040 — Saved-run browser and offline replay

**Status:** [ ] TODO. **Scope:** optional app feature. **Dependencies:** E011/E012/E021/E035.

**Evidence/opportunity:** Current files are shared text dumps with no import/replay. `HIGH_IMPACT_FEATURES.md` idea 1 already proposes structured export/replay; this task makes it a bounded follow-on to reliable captures, not a duplicate competing data format.

**Implement:** List named captures with experiment/device/time/configuration/quality; open a saved run, scrub/replay, and run supported math analyses without hardware. Preserve original data and processing provenance; edited analysis settings create a new derived result. Use E021's schema. Validate versions/units/counts/size when importing. Keep original and derived timestamps intact; replay speed affects display, not analysis time.

**Acceptance:** A saved deterministic capture produces the same result online and offline, at every replay speed. Interrupted/incompatible/corrupt files fail with useful messages. Users can rename/share/delete their own selected runs without deleting unrelated recordings. Memory remains bounded on large imports.

## Reproduced numerical evidence

These are audit checks, not substitutes for permanent regression tests or hardware qualification.

| Check | Observed current behavior | Correct expectation/interpretation | Task |
|---|---|---|---|
| High-g packed code 600, FS=320 g | 93.744 g | 100.0032 g using official sensitivity | E001 |
| Fixed full low-g packet at MTU 247 | Header can declare 111 tuples; only 52 complete tuples fit; decoder invents zeros | Size to MTU and reject incomplete frames | E002 |
| Coalesced packet arrival times | Artificial +20 ms increments | Acquisition times independent of delivery | E003 |
| Median filter, default window 12, ramp | JVM array-index exception | Defined median, no crash | E005 |
| Identical 10 Hz sinusoids | Pearson 1, peak cross-correlation −1 | Consistent positive match at zero lag | E006 |
| 10 Hz sine delayed six samples at 240 Hz | Lag 0, phase 0 | Delay/phase according to a declared periodic convention; use broadband tests for unique lag | E006 |
| Constant 1 at valid times 0/10/30 ms | Integral 0.02 at supplied 100 Hz | 0.03 using the actual valid intervals | E012 |
| 120 samples of +1000 mg with low-g 120/high-g 480 Hz | Legacy rectangle sum 250 “units/s” | SI Δv depends on actual duration; a 1 s plateau is 9.80665 m/s | E014 |
| Stationary side-on `[1000,0,0] mg` | Z-based free-fall trigger fires | Magnitude near 1 g rejects free fall | E015 |
| Radial signal of a 2 s pendulum model | Mean-crossing signal period 1 s | Do not equate radial signal period with full swing without model | E016 |
| Blend +179° with −179°, alpha=.97 | 168.26° | Continuous orientation across the wrap | E017 |
| 256 zeros, fs=256 Hz | Dominant frequency 1 Hz, amplitude 0 | No significant peak | E022 |
| `(-1)^n`, N=256, fs=256, rectangular window | Spectrum stops at 127 Hz; missing tone | Nyquist bin 128 Hz, amplitude 1 | E022 |
| Unit 16 Hz Hann sine, N=256, fs=256 Hz | FFT 0.999996; STFT 0.498045 | Matching amplitude normalization | E022 |
| 0.1-amplitude 10 Hz autocorrelation, 480 samples, fs=240 Hz, maxLag=128 | T=.1 s normalized, no period raw | Display scaling cannot change detection | E024 |
| EMA alpha=.5, fs=240 Hz | Label 38.197 Hz | Actual LP −3 dB crossing 27.606 Hz | E025 |
| EMA alpha=.9, fs=240 Hz | Label 343.775 Hz | No −3 dB crossing below Nyquist | E025 |
| Signed packed12 reconstruction across all 65536 input codes | Errors −15..0 raw LSB; average −7.5 | Existing floor quantization is lossy, not a sign-unpacking bug | E032 |

## Correct foundations to preserve

- The packed12 MSB-first ordering and sign extension agree between firmware and app; multiplying by 16 correctly reconstructs the retained upper bits. Precision loss and incorrect high-g sensitivity are separate issues.
- Low-g and gyro sensitivities match the official bundled driver.
- The radix-2 FFT matched a direct DFT in the JVM probe (maximum tested error approximately `8.1e-7`). Standard window formulas and FFT coherent-gain compensation are useful existing foundations.
- Population mean/variance, RMS, skewness, and excess kurtosis are correct for ordinary nonconstant input. N is a valid denominator for the current descriptive population variance and biased autocorrelation contracts.
- The numerical trapezoidal integrator and centered two-point derivative are valid under their uniform-sampling assumptions. Fix timing and interpretation instead of replacing correct arithmetic without evidence.
- Axis peak selection correctly returns the signed sample with largest absolute magnitude. A signed negative peak is not itself a bug.
- Mean-crossing interpolation works for a clean suitable periodic signal; the pendulum problem is the physical signal/model and robustness, not an automatic need to swap crossing signs.
- Vector magnitude is appropriate for detecting near-zero specific force in free fall and for displaying unsigned g magnitude. It is not signed normal load.
- Static accel tilt signs match the software renderer's declared axes; physical board mounting still needs verification.
- Firmware already enables BDU and serializes individual SPI access. The bundled gyro full-scale setter already powers down/restores the gyro; do not file that as an additional missing step without new evidence.
- `g_send_interval_us` is unused; its 1 ms floor does not prove a 1 kHz acquisition ceiling. High-rate fidelity must be measured by unique samples and losses.

## Boundaries and roadmap reconciliation

`HIGH_IMPACT_FEATURES.md` is a wishlist, not an implementation/validation record. Keep it as product ideas; use this file for the current corrective work. Its feature 1 overlaps E021/E040, and feature 2 could later extend E028. Industrial severity grading, certification, biomechanical conclusions, and other expansions need their own requirements and validation; do not inherit those claims from existing generic signal-processing code.

Physical tests still needed after code fixes include sensor axis/mount verification, noise and bias across ranges, calibration repeatability, FIFO timestamp behavior, achievable unique acquisition rates, radio/phone-dependent throughput, packet loss, clipping, sensor bandwidth/aliasing, and comparison against a characterized motion/vibration reference. The present audit cannot certify absolute measurement accuracy from source code.

## Completion log template

For each implemented task append:

```text
Task: E___
Status: Complete / Interim mitigation / Blocked prerequisite
Changed files:
Behavior changed:
Regression tests and actual results:
Android/firmware build result:
Hardware/UI verification performed (or explicitly not performed):
Remaining limitations and follow-up task IDs:
```
