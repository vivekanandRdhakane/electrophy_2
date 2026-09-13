# ESP-IDF Build & Environment Guidelines

## Toolchain & Build Command
This system uses ESP-IDF v5.3.1 with Python 3.11 virtual environment. `export.ps1` expects `$env:IDF_PYTHON_ENV_PATH` to be explicitly specified:

- **Build**:
  ```powershell
  $env:IDF_PYTHON_ENV_PATH = "C:\Users\user\.espressif\python_env\idf5.3_py3.11_env"; . "C:\Users\user\esp\v5.3.1\esp-idf\export.ps1"; idf.py build
  ```

- **Flash**:
  ```powershell
  $env:IDF_PYTHON_ENV_PATH = "C:\Users\user\.espressif\python_env\idf5.3_py3.11_env"; . "C:\Users\user\esp\v5.3.1\esp-idf\export.ps1"; idf.py -p <PORT> flash
  ```

- **Monitor**:
  ```powershell
  $env:IDF_PYTHON_ENV_PATH = "C:\Users\user\.espressif\python_env\idf5.3_py3.11_env"; . "C:\Users\user\esp\v5.3.1\esp-idf\export.ps1"; idf.py -p <PORT> monitor
  ```

- **Full Clean**:
  ```powershell
  $env:IDF_PYTHON_ENV_PATH = "C:\Users\user\.espressif\python_env\idf5.3_py3.11_env"; . "C:\Users\user\esp\v5.3.1\esp-idf\export.ps1"; idf.py fullclean
  ```

## Key Environment Paths
- ESP-IDF Root: `C:\Users\user\esp\v5.3.1\esp-idf`
- Python Environment: `C:\Users\user\.espressif\python_env\idf5.3_py3.11_env`
- Tools Directory: `C:\Users\user\.espressif\tools`

