---
name: esp-idf
description: >-
  Build, compile, flash, monitor, or configure ESP-IDF projects on Windows.
  Use whenever working on ESP32, ESP32-C3, or other Espressif chip projects,
  or when running idf.py commands.
---

# ESP-IDF Build and Development on Windows

## Overview
This environment has ESP-IDF v5.3.1 installed. The Python virtual environment is Python 3.11 located at `C:\Users\user\.espressif\python_env\idf5.3_py3.11_env`.

Because `export.ps1` defaults to searching for Python 3.12 (`idf5.3_py3.12_env`), `$env:IDF_PYTHON_ENV_PATH` **must** be explicitly set to the Python 3.11 environment path before invoking `export.ps1`.

## Key Paths
- **ESP-IDF Root**: `C:\Users\user\esp\v5.3.1\esp-idf`
- **Export Script**: `C:\Users\user\esp\v5.3.1\esp-idf\export.ps1`
- **Python Virtualenv**: `C:\Users\user\.espressif\python_env\idf5.3_py3.11_env`
- **Espressif Tools**: `C:\Users\user\.espressif\tools`

## Standard PowerShell Commands

### 1. Build Project
Always run from the project directory (e.g. where `CMakeLists.txt` is located):
```powershell
$env:IDF_PYTHON_ENV_PATH = "C:\Users\user\.espressif\python_env\idf5.3_py3.11_env"; . "C:\Users\user\esp\v5.3.1\esp-idf\export.ps1"; idf.py build
```

### 2. Flash to Target Device
```powershell
$env:IDF_PYTHON_ENV_PATH = "C:\Users\user\.espressif\python_env\idf5.3_py3.11_env"; . "C:\Users\user\esp\v5.3.1\esp-idf\export.ps1"; idf.py -p <PORT> flash
```
*(Replace `<PORT>` with the target serial port, e.g. `COM3`)*

### 3. Monitor Serial Output
```powershell
$env:IDF_PYTHON_ENV_PATH = "C:\Users\user\.espressif\python_env\idf5.3_py3.11_env"; . "C:\Users\user\esp\v5.3.1\esp-idf\export.ps1"; idf.py -p <PORT> monitor
```

### 4. Build, Flash, and Monitor
```powershell
$env:IDF_PYTHON_ENV_PATH = "C:\Users\user\.espressif\python_env\idf5.3_py3.11_env"; . "C:\Users\user\esp\v5.3.1\esp-idf\export.ps1"; idf.py -p <PORT> flash monitor
```

### 5. Clean / Target Configuration
```powershell
# Clean build artifacts
$env:IDF_PYTHON_ENV_PATH = "C:\Users\user\.espressif\python_env\idf5.3_py3.11_env"; . "C:\Users\user\esp\v5.3.1\esp-idf\export.ps1"; idf.py fullclean

# Set target chip (e.g., esp32c3)
$env:IDF_PYTHON_ENV_PATH = "C:\Users\user\.espressif\python_env\idf5.3_py3.11_env"; . "C:\Users\user\esp\v5.3.1\esp-idf\export.ps1"; idf.py set-target esp32c3
```

