# Electrophy — Complete 13 Feature Proposals & Roadmap

This document details all **13 feature proposals** for the **Electrophy** telemetry platform (ESP32-C3 + LSM6DSV320X + Android Jetpack Compose App). It spans High-Impact Scientific Tools, Education/Classroom Features, Maker & Engineering Diagnostics, and Connectivity Ecosystem enhancements.

---

## 📑 Table of Contents

- [🔥 High-Impact Core Features](#-high-impact-core-features)
  - [1. Structured CSV/JSON Data Export & Session Replay](#1-structured-csvjson-data-export--session-replay)
  - [2. Multi-Device Synchronization & Comparison (Dual-Module Link)](#2-multi-device-synchronization--comparison-dual-module-link)
  - [3. Vibration Analyzer & Industrial Machine Health Monitor (ISO 10816)](#3-vibration-analyzer--industrial-machine-health-monitor-iso-10816)
  - [4. Human Biomechanics & Gait Analysis (Pedometer & Impact Dynamics)](#4-human-biomechanics--gait-analysis-pedometer--impact-dynamics)
  - [5. Low-Power Autonomous Motion & Tilt Logger](#5-low-power-autonomous-motion--tilt-logger)
- [🎓 Education & Classroom Features](#-education--classroom-features)
  - [6. Automated Lab Report Generator (PDF/HTML)](#6-automated-lab-report-generator-pdfhtml)
  - [7. Acoustic & Mechanical Resonance Suite (Phone Mic + Sensor)](#7-acoustic--mechanical-resonance-suite-phone-mic--sensor)
  - [8. Interactive Physics Simulation Split-Screen (Theory vs. Reality)](#8-interactive-physics-simulation-split-screen-theory-vs-reality)
- [🛠️ Engineering & Maker Features](#️-engineering--maker-features)
  - [9. Impact Rating & Drop Test Certification Mode](#9-impact-rating--drop-test-certification-mode)
  - [10. Digital Spirit Level & Precision 2D Inclinometer](#10-digital-spirit-level--precision-2d-inclinometer)
  - [11. Gesture & Motion Pattern Recognition Engine](#11-gesture--motion-pattern-recognition-engine)
- [📡 Connectivity & Cloud Ecosystem](#-connectivity--cloud-ecosystem)
  - [12. MQTT & WebSocket Real-Time Telemetry Gateway](#12-mqtt--websocket-real-time-telemetry-gateway)
  - [13. Multi-Phone Classroom Sync (Teacher Hub Mode)](#13-multi-phone-classroom-sync-teacher-hub-mode)
- [📊 Comprehensive Feasibility & Impact Matrix](#-comprehensive-feasibility--impact-matrix)
- [🗺️ Phased Implementation Roadmap](#️-phased-implementation-roadmap)

---

## 🔥 High-Impact Core Features

### 1. Structured CSV/JSON Data Export & Session Replay

- **Objective**: Replace basic raw text dump with standardized scientific datasets and an offline time-travel replay engine.
- **Key Capabilities**:
  - **Standardized Export**:
    - Formatted CSV with clear headers: `timestamp_ms, low_g_x, low_g_y, low_g_z, high_g_x, high_g_y, high_g_z, gyro_x, gyro_y, gyro_z`.
    - JSON metadata block documenting active ODR, range ($\pm 2g \dots \pm 320g$), calibration offsets, device name, and experiment mode.
  - **Session Replay Player**:
    - Load saved experiments from storage.
    - Interactive scrubber bar with playback controls ($0.25\times, 0.5\times, 1\times, 2\times$).
    - Run offline Mathematics operations (FFT, Jerk derivative, Integration, Spectrogram) on previously recorded runs without reconnecting hardware.
  - **Direct Sharing**: Integrated Android Share Sheet (Google Drive, WhatsApp, Email, Slack, AirDrop-equivalents).

---

### 2. Multi-Device Synchronization & Comparison (Dual-Module Link)

- **Objective**: Connect two Electrophy BLE modules concurrently to unlock true multi-body dynamic physics.
- **Key Capabilities**:
  - **True Newton's Third Law Collision Rig**:
    - Mount Module A on Cart 1 and Module B on Cart 2.
    - Measure action-reaction forces simultaneously in the same timeframe:
      $$F_{1\to 2}(t) = -F_{2\to 1}(t)$$
    - Directly verify Conservation of Momentum:
      $$m_1 \Delta v_1 + m_2 \Delta v_2 = 0$$
  - **Differential Structural Dynamics**:
    - Compare chassis vibration vs. passenger compartment damping in RC cars/bicycles.
    - Measure structural bending modes by placing sensors at both ends of a bridge model.
  - **Dual Cursors**:
    - Crosshair inspection showing exact time difference $\Delta t$ and phase offset between the two separate physical devices.

---

### 3. Vibration Analyzer & Industrial Machine Health Monitor (ISO 10816)

- **Objective**: Transform Electrophy into an industrial predictive maintenance sensor for 3D printers, CNC routers, drones, and electric motors.
- **Key Capabilities**:
  - **ISO 10816 Severity Classification**:
    - Computes broadband Velocity RMS ($10\text{ Hz}$ to $1000\text{ Hz}$).
    - Automatic traffic-light grading: 🟢 **Good (Zone A)**, 🟡 **Acceptable (Zone B)**, 🟠 **Alert (Zone C)**, 🔴 **Danger (Zone D)**.
  - **Bearing Health Diagnostics**:
    - **Crest Factor** ($\text{Peak} / \text{RMS}$): Early warning metric for bearing ball flaking before broad RMS climbs.
    - **Harmonic Tracker**: Identifies unbalance ($1\times\text{ RPM}$), angular misalignment ($2\times\text{ RPM}$), or tooth mesh faults.
  - **Resonance / Modal Sweep**:
    - Ramp up motor speed to highlight mechanical structural resonance frequencies causing 3D print ringing or drone wobble.

---

### 4. Human Biomechanics & Gait Analysis (Pedometer & Impact Dynamics)

- **Objective**: Track human motion, running form, sports impact shocks, and rehabilitation progress.
- **Key Capabilities**:
  - **Step Detection & Cadence**:
    - Robust zero-crossing filter extracting Steps Per Minute (SPM) and stride intervals.
  - **Foot-Strike Shock Load**:
    - Utilizes the $\pm 32g$ / $\pm 320g$ channel to capture heel-strike shockwaves upon ground impact.
    - Evaluate footwear cushioning and running surface stiffness.
  - **Gait Asymmetry & Balance**:
    - Compares left vs. right foot peak shock load and contact times to detect fatigue or limping.
  - **Vertical Oscillation**:
    - Integrates vertical acceleration through the gait cycle to assess running economy and wasted vertical bounce.

---

### 5. Low-Power Autonomous Motion & Tilt Logger

- **Objective**: Long-duration battery-powered tracking for structural settling, overnight motion, or package shipping drop tests.
- **Key Capabilities**:
  - **Ultra-Low ODR Stream**:
    - Drops LSM6DSV320X to $1.875\text{ Hz}$ or $7.5\text{ Hz}$ with ESP32-C3 light-sleep to maximize battery runtime (days instead of hours).
  - **Event-Triggered Threshold Wakeup**:
    - Hardware remains dormant until shock threshold is exceeded (e.g. shipping package dropped $> 4g$).
    - Wakes up instantly to log peak impact and duration into flash memory.
  - **Long-Term Tilt Drift**:
    - Monitors sub-degree structural tilt over 24+ hours on retaining walls, buildings, or tripods.

---

## 🎓 Education & Classroom Features

### 6. Automated Lab Report Generator (PDF/HTML)

- **Objective**: Eliminate tedious manual lab report writing for students and educators with one-tap export.
- **Key Capabilities**:
  - **Formatted PDF Report**:
    - Title, student name, date/time, and test equipment specifications.
    - Embedded snapshot of high-contrast sensor charts with annotations and cursors.
    - Computed summary cards: Peak force, Impulse $J = \int F dt$, detected period $T$, damping ratio $\zeta$, or dominant FFT frequency.
  - **Theory Comparison Section**:
    - Auto-prints relevant theoretical equations ($T = 2\pi\sqrt{L/g}$, $F = ma$, $\Delta p = J$) with calculated percentage error between theory and measurement.

---

### 7. Acoustic & Mechanical Resonance Suite (Phone Mic + Sensor)

- **Objective**: Correlate mechanical surface vibrations with audible acoustic emissions using the phone's microphone.
- **Key Capabilities**:
  - **Cross-Domain Spectral Comparison**:
    - Run dual-FFT: Accelerometer spectrum on top, Phone microphone audio spectrum on bottom.
  - **Tuning Fork & Chladni Plate Resonance**:
    - Strike a tuning fork on the table: observe mechanical frequency spike on the IMU align with acoustic pitch on the microphone.
  - **Time-of-Flight Speed of Sound**:
    - Clap near the sensor: measure delay between mechanical surface shockwave (IMU) and acoustic wave arrival at phone microphone to compute acoustic velocity.

---

### 8. Interactive Physics Simulation Split-Screen (Theory vs. Reality)

- **Objective**: Display real-time sensor measurements directly superimposed over theoretical closed-form math simulations.
- **Key Capabilities**:
  - **Dual Overlay Rendering**:
    - **Live Sensor Trace** (solid red line) vs. **Ideal Equation Trace** (dashed cyan line).
  - **Interactive Sliders**:
    - Simple Pendulum: sliders for length $L$, mass $m$, air drag coefficient $b$.
    - Free Fall: slider for gravitational constant $g$ and drag $C_d$.
  - **Direct Concept Teaching**:
    - Clearly demonstrates the difference between frictionless textbook formulas and real-world damping, sensor thermal noise, and air resistance.

---

## 🛠️ Engineering & Maker Features

### 9. Impact Rating & Drop Test Certification Mode

- **Objective**: Professional drop and crash test certification tool for phone cases, packaging, drone landing gear, and protective gear.
- **Key Capabilities**:
  - **7680 Hz High-G Capture**:
    - Leverages full $7680\text{ Hz}$ ODR and $\pm 320g$ range to capture millisecond-level shock pulses without clipping.
  - **Standardized Impact Metrics**:
    - Peak acceleration ($g_{\text{max}}$), pulse duration ($\Delta t$ at $10\%$ threshold), and Velocity Change ($\Delta v$).
    - Computes **HIC (Head Injury Criterion)** and Damage Severity Index.
  - **Pass/Fail Thresholds**:
    - Configurable certification limits based on standards (e.g. MIL-STD-810G, IEC 60068).

---

### 10. Digital Spirit Level & Precision 2D Inclinometer

- **Objective**: A high-precision leveling tool with visual bubble and audible guidance for DIY, carpentry, and camera leveling.
- **Key Capabilities**:
  - **Visual 2D Bullseye Bubble Level**:
    - Displays pitch and roll on a smooth 2D reticle crosshair.
    - Numerical readout with $0.05^\circ$ resolution and calibration zero-offset tare button.
  - **Acoustic / Haptic Feedback**:
    - Beeps or vibrates with increasing frequency as alignment approaches perfect horizontal zero ($\pm 0.2^\circ$).
  - **Hold & Freeze Reading**:
    - Lock measurement value when measuring hard-to-reach angles.

---

### 11. Gesture & Motion Pattern Recognition Engine

- **Objective**: Recognize 3D gestures and motion templates to trigger automation or robotics control.
- **Key Capabilities**:
  - **Template Recording**:
    - User performs a motion (e.g. "figure-8", "double flick", "clockwise circle") to save as a reference pattern.
  - **Dynamic Time Warping (DTW)**:
    - Real-time time-series distance matching tolerant to variations in gesture speed.
  - **Action Triggers**:
    - Trigger app actions (e.g. start recording, toggle pause, sound buzzer) or send external commands over Bluetooth/network upon recognized gesture.

---

## 📡 Connectivity & Cloud Ecosystem

### 12. MQTT & WebSocket Real-Time Telemetry Gateway

- **Objective**: Stream live sensor data from the phone to local network brokers or cloud dashboards.
- **Key Capabilities**:
  - **Network Forwarding**:
    - Relay incoming BLE packets over Wi-Fi via MQTT or WebSocket JSON streams.
  - **Dashboard Integration**:
    - Live streaming into **Grafana**, **Node-RED**, **Home Assistant**, or **InfluxDB**.
  - **Python / Jupyter Integration**:
    - Simple WebSocket subscriber script for real-time robotic telemetry and data science notebooks.

---

### 13. Multi-Phone Classroom Sync (Teacher Hub Mode)

- **Objective**: Enable a teacher's tablet/phone to act as a central dashboard aggregating experiments from up to 20+ student modules simultaneously.
- **Key Capabilities**:
  - **Classroom Leaderboard & Overview**:
    - Teacher views live oscillation periods, collision impulse peaks, or acceleration values from all student workbenches on one large display.
  - **Outlier Highlighting**:
    - Instantly identifies which student setup has anomalous data (e.g. incorrect pendulum length or loose sensor mounting) for group discussion.
  - **Central Experiment Control**:
    - Teacher triggers synchronized "Start Experiment" or "Tare Calibration" broadcast across all student devices.

---

## 📊 Comprehensive Feasibility & Impact Matrix

| # | Feature Proposal | Category | Primary Sensor Channel | Technical Feasibility | Educational / User Impact |
| :-: | :--- | :--- | :--- | :---: | :---: |
| **1** | **CSV/JSON Export & Replay** | Core / Workflow | Low-G, High-G, Gyro | **High** (App-only) | 🔥🔥🔥🔥🔥 |
| **2** | **Multi-Device Synchronization** | Core / Physics | Dual High-G / Low-G | **Medium** (BLE client) | 🔥🔥🔥🔥🔥 |
| **3** | **Vibration Analyzer (ISO 10816)** | Core / Industrial | Low-G ($480\text{–}1920\text{ Hz}$) + FFT | **High** (Reuses MathEngine) | 🔥🔥🔥🔥🔥 |
| **4** | **Human Biomechanics & Gait** | Core / Sports | Low-G + High-G | **Medium** (Filter math) | 🔥🔥🔥🔥 |
| **5** | **Low-Power Autonomous Logger** | Core / Battery | Low-G ($1.875\text{ Hz}$) | **Medium** (Firmware sleep) | 🔥🔥🔥🔥 |
| **6** | **Automated Lab Report Generator** | Education | Analysis Metrics | **High** (PDF rendering) | 🔥🔥🔥🔥🔥 |
| **7** | **Acoustic & Resonance Suite** | Education / Sound | Low-G + Phone Mic | **Medium** (AudioRecord API) | 🔥🔥🔥🔥 |
| **8** | **Interactive Theory Simulation** | Education | Math simulation overlay | **High** (Compose math) | 🔥🔥🔥🔥 |
| **9** | **Drop Test Certification Mode** | Engineering | High-G ($7680\text{ Hz}, \pm 320g$) | **High** (Existing trigger) | 🔥🔥🔥🔥 |
| **10**| **Digital Spirit Level** | Maker / Utility | Low-G (Filtered gravity) | **High** (Canvas bubble) | 🔥🔥🔥🔥 |
| **11**| **Gesture Pattern Recognition** | Engineering / ML | Gyro + Low-G (DTW) | **Medium** (DTW algorithm) | 🔥🔥🔥 |
| **12**| **MQTT / WebSocket Gateway** | Connectivity | Network client | **High** (OkHttp / Paho) | 🔥🔥🔥🔥 |
| **13**| **Multi-Phone Classroom Sync** | Connectivity | Local Wi-Fi socket / mDNS | **Medium** (P2P protocol) | 🔥🔥🔥🔥 |

---

## 🗺️ Phased Implementation Roadmap

```
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 1: High-ROI Quick Wins (App-Only Updates)                       │
│ ────────────────────────────────────────────────────────────────────── │
│  • Feature 1: CSV/JSON Export & Session Replay                        │
│  • Feature 10: Precision Digital Spirit Level                          │
│  • Feature 6: PDF Lab Report Generator                                 │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 2: Domain Expansion (Reusing MathEngine & Trigger Framework)     │
│ ────────────────────────────────────────────────────────────────────── │
│  • Feature 3: ISO 10816 Machine Vibration Severity Suite              │
│  • Feature 9: 7680 Hz High-G Impact & Drop Test Certification          │
│  • Feature 4: Human Gait & Running Impact Dynamics                     │
│  • Feature 8: Simulation Split-Screen (Theory vs Real Data)           │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 3: Hardware & Dual-Link Advanced Capabilities                   │
│ ────────────────────────────────────────────────────────────────────── │
│  • Feature 2: Dual-Module Multi-Device Link (Newton's 3rd Law Rig)    │
│  • Feature 7: Microphone + Accelerometer Acoustic Resonance Suite      │
│  • Feature 11: Dynamic Time Warping (DTW) Gesture Recognizer           │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 4: Connectivity & Classroom Ecosystem                           │
│ ────────────────────────────────────────────────────────────────────── │
│  • Feature 12: MQTT / WebSocket Telemetry Streaming Gateway           │
│  • Feature 13: Multi-Phone Classroom Sync (Teacher Hub Mode)           │
│  • Feature 5: Firmware Low-Power Autonomous Shock & Tilt Logger        │
└────────────────────────────────────────────────────────────────────────┘
```
