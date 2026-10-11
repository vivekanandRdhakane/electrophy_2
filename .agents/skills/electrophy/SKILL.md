---
name: electrophy
description: >-
  Complete project knowledge base for the Electrophy system — a high-speed IMU
  telemetry platform comprising an ESP32-C3 + LSM6DSV320X sensor module, a custom
  KiCad PCB, and an Android Jetpack Compose app. Use this skill whenever working
  on any part of the Electrophy project: firmware, Android app, BLE protocol,
  PCB design, experiment modes, 3D visualization, or data pipeline. This skill
  eliminates the need to re-parse the full codebase on every session.
---

# Electrophy Project Skill

## 1. System Overview

Electrophy is an end-to-end high-speed inertial measurement and physics experiment
telemetry system with three major components:

1. **Custom PCB** (`PCB_Project/electrophy_2/`) — KiCad 4-layer board with ESP32-C3FN4,
   LSM6DSV320X IMU, battery charging, USB-C, and 2.4 GHz antenna.
2. **ESP32-C3 Firmware** (`esp_sensor/`) — ESP-IDF v5.3.1 firmware that reads the
   IMU over 8 MHz SPI, packs data into 12-bit binary frames, and streams via BLE.
3. **Android App** (`Electrophy_app/`) — Kotlin/Jetpack Compose app that receives
   the BLE stream, calibrates, filters, visualizes with real-time charts and a
   software 3D aircraft model, and runs automated physics experiment analysis.

```
 ┌─────────────────────────────────────────────────┐
 │             LSM6DSV320X IMU                      │
 │  Low-G Accel  │  High-G (±320g)  │  Gyroscope   │
 └───────────────┬─────────────────────────────────┘
                 │ SPI 8 MHz + INT1 (DRDY)
                 ▼
 ┌─────────────────────────────────────────────────┐
 │           ESP32-C3 Firmware                      │
 │  sensor_task (Prio 5) · 12-bit packing engine   │
 │  NimBLE BLE Host · NUS GATT · Flow control      │
 └───────────────┬─────────────────────────────────┘
                 │ BLE 2M PHY │ MTU 517 │ NUS
                 ▼
 ┌─────────────────────────────────────────────────┐
 │         Electrophy Android App                   │
 │  BleViewModel · BitReader · Calibration          │
 │  Complementary Filter Attitude (Pitch/Roll/Yaw)  │
 │  EMA Filter · Auto-Trigger · Experiment Engine   │
 │  MPAndroidChart · 3D Aircraft + Avionics HUD     │
 └─────────────────────────────────────────────────┘
```

---

## 2. Repository Layout

```
electrophy_2/
├── .agents/
│   └── skills/
│       ├── electrophy/SKILL.md          ← This file
│       ├── esp-idf/SKILL.md             ← ESP-IDF build commands & paths
│       └── lsm6dsv320x/SKILL.md         ← IMU register-level programming
├── Electrophy_app/                      ← Android app (Gradle/Kotlin)
│   └── app/src/main/java/com/example/electrophy_app/
│       ├── MainActivity.kt              ← BleViewModel, BitReader, SensorChart, navigation
│       ├── ExperimentScreen.kt          ← Guided experiment UI + physics & math analysis
│       ├── ExperimentModes.kt           ← 16 experiment preset definitions (8 Physics + 8 Math)
│       ├── ExperimentPickerScreen.kt    ← Categorized experiment card catalog
│       ├── MathEngine.kt                ← Signal processing engine (Integration, Derivative, FFT, STFT, Stats, Autocorr, Filters, Comparator)
│       ├── MathVisualization.kt         ← Interactive math charts, Spectrogram, Histogram, and Lissajous views
│       └── Airplane3DView.kt           ← Software 3D engine + avionics HUD
├── esp_sensor/                          ← ESP-IDF firmware project
│   ├── main/
│   │   ├── main.c                       ← All firmware: SPI, sensor, BLE, packing
│   │   ├── lsm6dsv320x_reg.c           ← ST official driver (register access)
│   │   └── lsm6dsv320x_reg.h           ← ST official driver header
│   ├── CMakeLists.txt
│   ├── sdkconfig
│   ├── GEMINI.md                        ← ESP-IDF build environment notes
│   └── README.md
├── PCB_Project/electrophy_2/            ← KiCad PCB design
│   ├── electrophy_2.kicad_sch           ← Schematic
│   ├── electrophy_2.kicad_pcb           ← PCB layout (4-layer)
│   ├── electrophy_2.kicad_pro           ← KiCad project
│   ├── electrophy_2.csv                 ← Bill of Materials
│   ├── Gerber/                          ← Manufacturing Gerber files
│   ├── LSM6DSV320X.kicad_sym            ← Custom symbol library
│   ├── LSM6DSV320X.pretty/              ← Custom footprint library
│   ├── TPS27081ADDCR.kicad_sym          ← Load switch symbol
│   ├── TPS27081ADDCR.pretty/            ← Load switch footprint
│   ├── chip_antenna.pretty/             ← Antenna footprint
│   └── pogo_pads_4.pretty/              ← Programming pogo pad footprint
└── workspace.code-workspace             ← VS Code multi-root workspace
```

---

## 3. Hardware (PCB)

### 3.1 Key Components (BOM)

| Ref  | Part                    | Function                           |
|:-----|:------------------------|:-----------------------------------|
| IC1  | **ESP32-C3FN4**         | MCU with BLE 5.0, RISC-V, 4MB flash |
| U1   | **LSM6DSV320X**         | 6-axis IMU + High-G accel (±320g)  |
| U2   | BQ21040DBV              | Li-ion/LiPo battery charger        |
| U3   | TPS27081ADDCR           | Load switch (power control)        |
| U4   | USBLC6-2SC6             | USB ESD protection                 |
| U5   | MIC5504-3.3YM5          | 3.3V LDO regulator                |
| J1   | USB-C (14-pin, USB 2.0) | Charging + programming             |
| J3   | Conn_01x04              | 4-pin pogo pad connector (SWD/programming) |
| Y1   | 40 MHz crystal (15 pF)  | Main MCU clock                     |
| Y2   | 32.768 kHz crystal      | RTC / low-power clock              |
| BAT1 | Battery connector       | Li-ion/LiPo battery               |
| L2   | 2.4 GHz inductor        | BLE antenna matching               |

### 3.2 SPI Wiring (ESP32-C3 → LSM6DSV320X)

| Signal | GPIO  |
|:-------|:------|
| SCLK   | GPIO4 |
| CS     | GPIO5 (software-toggled) |
| MOSI   | GPIO6 |
| MISO   | GPIO7 |
| INT1   | GPIO10 (rising-edge interrupt) |

- SPI clock: **8 MHz**, 4-wire mode
- SPI host: **SPI2_HOST**

### 3.3 PCB Design Notes

- **4-layer stackup** designed in KiCad
- Custom footprint libraries for LSM6DSV320X (LGA-14L), TPS27081ADDCR, chip antenna, and pogo pads
- Gerber files pre-generated in `Gerber/` subfolder
- USB-C with 5.1kΩ CC resistors (R16, R17) for USB 2.0 identification

---

## 4. ESP32-C3 Firmware (`esp_sensor/`)

### 4.1 Build Environment

- **ESP-IDF v5.3.1**, Python 3.11 virtual environment
- **Target chip**: esp32c3
- See the `esp-idf` skill for build/flash/monitor commands

### 4.2 Architecture

Single source file `main/main.c` containing:

- **SPI driver**: `platform_write()` / `platform_read()` with `spi_mutex` serialization
- **Sensor task** (`sensor_task`, priority 5): polls INT1, reads sensor data, batches and sends
- **NimBLE BLE host**: Nordic UART Service (NUS), command handler, notification TX
- **12-bit packing engine**: `imu_accumulate_sample()` / `imu_flush_batch()`
- **Dynamic INT routing**: `update_int_routing()` — configures INT1_CTRL and CTRL7 for active sensors
- **Dynamic timing**: `odr_recompute_timing()` — adjusts task wait timeout to 5× slowest sensor period

### 4.3 Sensor Configuration Defaults

| Sensor   | Default ODR | Default FS   | Mode            |
|:---------|:------------|:-------------|:----------------|
| Low-G    | 15 Hz       | ±2g          | High-Performance |
| Gyro     | 15 Hz       | ±2000 dps    | High-Performance |
| High-G   | 480 Hz      | ±320g        | Output enabled   |

### 4.4 Streaming Modes

| Mode       | ID | Axes per sample | Data source                |
|:-----------|:--:|:---------------:|:---------------------------|
| `ALL`      | 0  | 9               | Low-G + High-G + Gyro     |
| `LOW_ACC`  | 1  | 3               | Low-G only                 |
| `HIGH_ACC` | 2  | 3               | High-G only                |
| `BOTH_ACC` | 3  | 6               | Low-G + High-G             |
| `ONLY_GYRO`| 4  | 3               | Gyro only                  |

### 4.5 12-Bit Packing

Raw 16-bit values are arithmetically right-shifted by 4 (`raw >> 4`) to produce
signed 12-bit values, then bit-packed MSB-first into the batch buffer.
This gives a **25% size reduction** (4.5 bytes per 3-axis sample vs. 6 bytes).

Flush triggers:
- Buffer capacity reached (512-byte `s_batch_buf`)
- ≥ 30 ms elapsed since last flush (~33 fps minimum)

### 4.6 BLE Flow Control

- `MAX_BLE_NOTIFY_IN_FLIGHT = 12`
- Tracked via `BLE_GAP_EVENT_NOTIFY_TX` callback
- Samples dropped cleanly under backpressure (no crash/hang)
- Task watchdog reconfigured to avoid panics at high ODR (up to 7.68 kHz)

---

## 5. BLE Protocol

### 5.1 GATT Profile

| Item              | UUID / Value                                    |
|:------------------|:------------------------------------------------|
| Device name       | `ESP_IMU`                                       |
| NUS Service       | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`         |
| TX Characteristic | `6E400003-...` (Notify, ESP→Android)            |
| RX Characteristic | `6E400002-...` (Write, Android→ESP)             |
| CCCD              | `00002902-0000-1000-8000-00805F9B34FB`          |

Connection parameters negotiated by Android:
- **LE 2M PHY**
- **MTU 517 bytes**
- **CONNECTION_PRIORITY_HIGH**

### 5.2 Binary Packet Format (TX: ESP → Android)

#### Header (9 bytes, little-endian)

| Offset | Field          | Type    | Description                                      |
|:------:|:---------------|:--------|:-------------------------------------------------|
| 0      | `magic0`       | uint8   | `0xAA`                                           |
| 1      | `magic1`       | uint8   | `0x55`                                           |
| 2      | `mode`         | uint8   | Stream mode (0–4, see §4.4)                      |
| 3      | `sample_count` | uint8   | Number of sample tuples N                        |
| 4      | `xl_fs`        | uint8   | Low-G full scale index (0=2g, 1=4g, 2=8g, 3=16g)|
| 5      | `hg_fs`        | uint8   | High-G FS (0=32g..4=320g)                        |
| 6      | `gy_fs`        | uint8   | Gyro FS (1=250..5=4000 dps)                      |
| 7      | `seq`          | uint8   | Rolling sequence counter (0–255)                  |
| 8      | `fmt`          | uint8   | 1 = 12-bit packed MSB-first, 0 = 16-bit raw      |

#### Payload (when `fmt == 1`)

Each axis is a 12-bit signed integer packed consecutively MSB-first:
- `LOW_ACC` / `HIGH_ACC` / `ONLY_GYRO`: 3 axes → 36 bits → 4.5 bytes/sample
- `BOTH_ACC`: 6 axes → 72 bits → 9 bytes/sample
- `ALL`: 9 axes → 108 bits → 13.5 bytes/sample

#### Value Conversion on Android

```
Actual Value = V_12 × (Sensitivity × 16)
```

Where V₁₂ is extracted by `BitReader.readSigned12()` and sensitivities are:

| Sensor  | FS        | Sensitivity      |
|:--------|:----------|:-----------------|
| Low-G   | ±2g       | 0.061 mg/LSB     |
| Low-G   | ±4g       | 0.122 mg/LSB     |
| Low-G   | ±8g       | 0.244 mg/LSB     |
| Low-G   | ±16g      | 0.488 mg/LSB     |
| High-G  | ±32g      | 0.000976 g/LSB   |
| High-G  | ±64g      | 0.001952 g/LSB   |
| High-G  | ±128g     | 0.003904 g/LSB   |
| High-G  | ±256g     | 0.007808 g/LSB   |
| High-G  | ±320g     | 0.010417 g/LSB   |
| Gyro    | ±250 dps  | 8.75 mdps/LSB    |
| Gyro    | ±500 dps  | 17.50 mdps/LSB   |
| Gyro    | ±1000 dps | 35.0 mdps/LSB    |
| Gyro    | ±2000 dps | 70.0 mdps/LSB    |
| Gyro    | ±4000 dps | 140.0 mdps/LSB   |

### 5.3 Command Protocol (RX: Android → ESP)

UTF-8 ASCII strings written to the RX characteristic:

**Mode commands:**
`low_acc`, `high_acc`, `both_acc`, `only_gyro`, `all`

**Stream control:**
`pause`, `resume`, `stop`, `start`

**ODR configuration:**
- `odr_low_g_<rate>` — rates: `off`, `1hz875`, `7hz5`, `15hz`, `30hz`, `60hz`, `120hz`, `240hz`, `480hz`, `960hz`, `1920hz`, `3840hz`, `7680hz`
- `odr_high_g_<rate>` — rates: `off`, `480hz`, `960hz`, `1920hz`, `3840hz`, `7680hz`
- `odr_gyro_<rate>` — same as low_g rates

**Full-scale range:**
- `range_low_g_<2g|4g|8g|16g>`
- `range_high_g_<32g|64g|128g|256g|320g>`
- `range_gyro_<250dps|500dps|1000dps|2000dps|4000dps>`

---

## 6. Android App (`Electrophy_app/`)

### 6.1 Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose (Material 3)
- **Charts**: MPAndroidChart (LineChart) with custom Compose Canvas overlays
- **3D Engine**: Custom software renderer on Compose Canvas
- **Build**: Gradle with Kotlin DSL (`build.gradle.kts`)
- **Min SDK**: Requires BLE permissions (legacy + Android 12+)

### 6.2 Key Classes

#### `BleViewModel` (in `MainActivity.kt`)
Central ViewModel managing:
- `ConnectionState` flow (Disconnected → Scanning → Connecting → Connected)
- BLE GATT lifecycle, PHY negotiation, MTU negotiation
- Serialized BLE GATT command write queue (`commandQueue`, `processNextCommand()`, `onDescriptorWrite`, `onCharacteristicWrite`) and `syncFullConfigurationToDevice()` so multi-command experiment presets (ranges, ODRs, stream mode) are never dropped by Android's single-in-flight GATT constraint
- Binary packet unpacking via `BitReader`
- Sensor calibration offsets (`CalibrationOffsets`, persisted in SharedPreferences)
- Real-time EMA filtering with configurable α
- Auto-trigger spike capture with delayed pause
- `ChartData` state (ring buffers of chart points per axis)
- `AirplaneAttitude` state (pitch, roll, yaw from complementary filter)
- Logging to CSV files (via FileProvider)

#### `BitReader` (in `MainActivity.kt`)
MSB-first bit reader that extracts signed 12-bit integers from packed binary
notifications using `readSigned12()` with sign extension.

#### `SensorChart` (in `MainActivity.kt`)
Composable wrapper around MPAndroidChart's `LineChart` with:
- Single-touch crosshair cursor (C1) — shows value at touch point
- Dual-touch cursors (C1 + C2) — shows Δt, Δv, and slope between two points
- Time-slider for scrolling through history when paused

#### `SensorCalibrationCard` (in `MainActivity.kt`)
UI and logic for stationary zero-drift calibration:
- Averages X, Y, Z, and Gyro over 1–5 seconds
- Z-axis reference: +1000 mg (Earth gravity, Z pointing up)
- Offsets persisted to SharedPreferences

### 6.3 Navigation / Screens

```
AppRouter
├── BleAppScreen          ← Raw data mode (manual controls)
├── ExperimentPickerScreen ← Categorized experiment card catalog
└── ExperimentScreen       ← Guided experiment with preset config
```

### 6.4 Signal Processing Pipeline

1. **Binary unpack** → raw 12-bit values → physical units (mg, g, mdps)
2. **Calibration offset subtraction** (zero-drift compensation)
3. **EMA low-pass filter**: y[n] = α·x[n] + (1-α)·y[n-1]
4. **Auto-trigger engine**: monitors threshold (Above/Below/Deviation), schedules delayed pause
5. **Attitude estimator** (complementary filter):
   - Accel pitch/roll: atan2-based
   - Gyro integration for angular rates
   - Dynamic α = 0.4/(0.4 + dt), bounded [0.90, 0.99]
   - Yaw/heading: gyro Z integration with ±0.25°/s deadband
6. **Chart update** → MPAndroidChart data points
7. **3D model update** → Euler rotation → camera projection → render

### 6.5 Experiment Engine (in `ExperimentScreen.kt`)

Real-time physics analysis functions:
- `findPeakValues()` — peak |X|, |Y|, |Z| accelerations
- `calculateImpulse()` — Riemann sum: J = ∫(F_z - F_baseline)dt
- `detectPeriod()` — oscillation period T via mean zero-crossing interpolation
- `estimateVelocityChange()` — Δv = ∫(a_z - a_baseline)dt
- `detectFreeFallDuration()` — measures time where √(X²+Y²+Z²) < 200 mg

### 6.6 Experiment Presets (in `ExperimentModes.kt`)

| # | Experiment              | Category              | Mode     | ODR     | FS Range   | Key Analysis               |
|:-:|:------------------------|:----------------------|:---------|:--------|:-----------|:---------------------------|
| 1 | 3D Aeroplane Attitude   | Flight & Aerodynamics | ALL      | 120 Hz  | ±4g, ±2000dps | 3D aircraft + HUD       |
| 2 | Simple Pendulum         | Kinematics            | LOW_G    | 120 Hz  | ±4g        | Period detection (T)        |
| 3 | Vehicle Acceleration    | Kinematics            | LOW_G    | 120 Hz  | ±8g        | Velocity integration (Δv)  |
| 4 | Newton's Third Law      | Mechanics             | HIGH_G   | 7680 Hz | ±320g      | Peak force + Impulse (J)   |
| 5 | Free Fall               | Mechanics             | LOW_G    | 960 Hz  | ±2g        | Free-fall duration          |
| 6 | Centripetal Acceleration| Mechanics             | LOW_G    | 480 Hz  | ±16g       | Peak force (a = ω²r)       |
| 7 | Impulse & Momentum      | Mechanics             | HIGH_G   | 3840 Hz | ±256g      | Peak force + Impulse        |
| 8 | Elevator Physics        | Mechanics             | LOW_G    | 60 Hz   | ±4g        | Apparent weight variation   |
| 9 | Numerical Integration   | Mathematics           | LOW_G*   | 120 Hz  | ±4g        | 1st & 2nd order ∫x dt, DC drift toggle |
| 10| Numerical Derivative    | Mathematics           | LOW_G*   | 240 Hz  | ±8g        | Jerk (dx/dt) & Snap (d²x/dt²), stencil smoothing |
| 11| FFT Frequency Spectrum  | Mathematics           | LOW_G*   | 480 Hz  | ±4g        | Radix-2 FFT, windowing, parabolic peak interpolation |
| 12| Spectrogram (STFT)      | Mathematics           | LOW_G*   | 480 Hz  | ±4g        | 2D Time-Frequency waterfall heatmap |
| 13| Statistics & Distribution| Mathematics          | LOW_G*   | 240 Hz  | ±4g        | Histogram + Gaussian fit, μ, σ, skewness, kurtosis |
| 14| Autocorrelation         | Mathematics           | LOW_G*   | 240 Hz  | ±4g        | R(τ) vs lag, automatic fundamental period T |
| 15| Digital Filter Lab      | Mathematics           | LOW_G*   | 240 Hz  | ±4g        | LP, HP, BP, Moving Avg, Median + noise residual |
| 16| Signal Comparator       | Mathematics           | LOW_G*   | 120 Hz  | ±4g, ±2000dps | Dual-axis Pearson r, phase lag, XY Lissajous |

*\*All Mathematics modes allow runtime switching across any single axis (`aX`, `aY`, `aZ`, `|a|`, `hgX`, `hgY`, `hgZ`, `|hg|`, `gX`, `gY`, `gZ`, `|g|`) and automatically switch the BLE sensor stream mode and ODR as needed.*

### 6.7 3D Visualization (in `Airplane3DView.kt`)

Custom software 3D rendering engine on Compose Canvas:

- **Geometry**: High-contrast jet mesh (red nose, white fuselage, yellow wings, cyan cockpit, blue tail, red/green nav lights)
- **Camera modes**:
  - `ISOMETRIC` — 3/4 axonometric view (~26° elevation, ~32° azimuth)
  - `TOP_DOWN` — plan view
  - `PILOT` — cockpit HUD with artificial horizon, pitch ladder (±10°, ±20°), flight reticle
- **Pipeline**: Euler rotation → camera projection → Lambertian shading (ambient 0.75) → perspective → Painter's algorithm → path fill/stroke
- **Telemetry badges**: Pitch (Climb/Dive), Roll (Bank), Yaw/Compass (16-point cardinal), G-Force, Turn Rate (°/s)

### 6.8 Android Permissions

| Permission              | When Required         |
|:------------------------|:----------------------|
| BLUETOOTH               | Android ≤ 10 (legacy) |
| BLUETOOTH_ADMIN          | Android ≤ 10 (legacy) |
| ACCESS_FINE_LOCATION     | Android ≤ 10 (legacy) |
| ACCESS_COARSE_LOCATION   | Android ≤ 10 (legacy) |
| BLUETOOTH_SCAN           | Android 12+ (neverForLocation) |
| BLUETOOTH_CONNECT        | Android 12+           |

Hardware features required: `bluetooth`, `bluetooth_le`

FileProvider configured for CSV log file sharing.

---

## 7. Cross-Cutting Concerns

### 7.1 Adding a New Experiment Mode

1. Define a new `ExperimentMode` entry in `ExperimentModes.kt` with sensor mode, ODRs, ranges, auto-trigger rules, filter α, and analysis flags.
2. If new analysis is needed, add the analysis function in `ExperimentScreen.kt` and wire it into the analysis card section.
3. The experiment automatically appears in `ExperimentPickerScreen.kt` grouped by its `category` field.

### 7.2 Adding a New BLE Command

1. Add the command string parsing in `imu_chr_access_cb()` in `main.c`.
2. Add the corresponding call in `BleViewModel` in `MainActivity.kt` using `sendCommand("command")` (which automatically serializes writes through `commandQueue`).

### 7.3 Changing Packet Format

The binary packet format is tightly coupled between:
- **ESP side**: `imu_accumulate_sample()` / `imu_flush_batch()` in `main.c`
- **Android side**: `BitReader` and the unpacking logic in `BleViewModel.onCharacteristicChanged()` in `MainActivity.kt`

Both sides must be updated in sync. The header magic bytes `0xAA 0x55` and the `fmt` field provide basic version identification.

### 7.4 Modifying the 3D Aircraft Model

The mesh geometry is defined as `PolyFace` lists with `Vec3` vertices in `Airplane3DView.kt`. Colors, face ordering, and the Painter's algorithm depth sort are all in that file. The `Vec3` class provides `rotateRoll`, `rotatePitch`, `rotateYaw`, and `rotateEuler` for Euler rotations.

---

## 8. Related Skills

- **`esp-idf`** — ESP-IDF build commands, paths, and environment setup for this Windows machine.
- **`lsm6dsv320x`** — Register-level programming reference for the LSM6DSV320X IMU (addresses, bit fields, sensitivities, FIFO, interrupts, debugging checklist).

Consult these skills for detailed firmware development and sensor register work.

