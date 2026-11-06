---
name: esp-idf
description: >-
  Build, compile, flash, monitor, or configure ESP-IDF projects on Windows.
  Use whenever working on ESP32, ESP32-C3, or other Espressif chip projects,
  or when running idf.py commands.
---

# ESP-IDF Build and Development on Windows

## Overview
This environment has **ESP-IDF v6.1** installed at `C:\esp\v6.1\esp-idf`.
The Espressif tools and Python 3.11 virtual environment are located at `C:\Espressif\tools`.

To activate the complete ESP-IDF environment in any PowerShell session, dot-source the profile script:
```powershell
. "C:\Espressif\tools\Microsoft.v6.1.PowerShell_profile.ps1"
```
This automatically sets `IDF_PATH`, `IDF_TOOLS_PATH`, `IDF_PYTHON_ENV_PATH`, and puts `idf.py`, `ninja`, `cmake`, and `esptool.py` into `PATH`.

## Key Paths
- **ESP-IDF Root**: `C:\esp\v6.1\esp-idf`
- **Activation Script**: `C:\Espressif\tools\Microsoft.v6.1.PowerShell_profile.ps1`
- **Python Virtualenv**: `C:\Espressif\tools\python\v6.1\venv`
- **Espressif Tools**: `C:\Espressif\tools`

## Device Ports (COM Ports)
The Electrophy setup typically connects two ESP32-C3 modules over USB: `COM3` and `COM5`.
To list currently connected serial ports in PowerShell:
```powershell
[System.IO.Ports.SerialPort]::GetPortNames()
```
If a port is not listed, the board may be unplugged, powered off, or held in reset.

## Standard PowerShell Commands

Always run from the project firmware directory (e.g. `electrophy_2/esp_sensor`):

### 1. Build Project
```powershell
. "C:\Espressif\tools\Microsoft.v6.1.PowerShell_profile.ps1"; idf.py build
```

### 2. Flash to Target Device
```powershell
. "C:\Espressif\tools\Microsoft.v6.1.PowerShell_profile.ps1"; idf.py -p COM3 flash
```
*(Or specify `COM5` or another port detected via `GetPortNames()`)*

### 3. Monitor Serial Output
```powershell
. "C:\Espressif\tools\Microsoft.v6.1.PowerShell_profile.ps1"; idf.py -p COM3 monitor
```
*(Press `Ctrl+]` to exit the monitor)*

### 4. Build, Flash, and Monitor
```powershell
. "C:\Espressif\tools\Microsoft.v6.1.PowerShell_profile.ps1"; idf.py -p COM3 flash monitor
```

### 5. Clean / Target Configuration
```powershell
# Clean build artifacts
. "C:\Espressif\tools\Microsoft.v6.1.PowerShell_profile.ps1"; idf.py fullclean

# Set target chip (e.g., esp32c3)
. "C:\Espressif\tools\Microsoft.v6.1.PowerShell_profile.ps1"; idf.py set-target esp32c3
```

## Important Notes & Troubleshooting
1. **Port Busy (`FileNotFoundError` or permission denied)**:
   - Ensure any running `idf.py monitor` or serial terminal sessions on that COM port are closed (`Ctrl+]`).
   - If flashing fails with "Could not open port", verify the port is plugged in using `[System.IO.Ports.SerialPort]::GetPortNames()`.
2. **VS Code Settings**:
   - `esp_sensor/.vscode/settings.json` contains `"idf.port": "COM3"`. Keep user modifications to this file intact (do not overwrite with git commits).
3. **Single-Core Watchdog Consideration**:
   - ESP32-C3 is a single-core RISC-V MCU. At high ODR streaming (up to 7.68 kHz), sensor reading tasks must feed the Task Watchdog Timer (`esp_task_wdt_reset()`) to prevent TWDT timeouts.
