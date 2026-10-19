| Supported Targets | ESP32 | ESP32-C2 | ESP32-C3 | ESP32-C5 | ESP32-C6 | ESP32-H2 | ESP32-P4 | ESP32-S2 | ESP32-S3 |
| ----------------- | ----- | -------- | -------- | -------- | -------- | -------- | -------- | -------- | -------- |

# _Sample project_

## Board power and status LED

For debugging, set `USE_BLE` near the top of `main/main.c` to `0` and rebuild
and flash. Set it to `1` to enable BLE normally. With `0`, BLE startup, GATT,
advertising, notifications, and the connection wait are compiled out; IMU
initialization and sampling continue without a phone. The USB monitor prints one
`ALL` sample per second: X/Y/Z for Low-G and High-G acceleration in mg and
gyroscope in mdps. Power hold and the LED
remain active. This switch does not change the ESP-IDF Bluetooth sdkconfig.

- GPIO21 (ESP32-C3 package pin 28 / U0TXD) drives TPS27081 POWER_HOLD high
  from the second-stage bootloader's before-init hook and stays high in the app.
- GPIO8 (package pin 14) drives the LED cathode: low is ON. Standby indication
  uses 5 kHz PWM with a smooth two-second breathing cycle: one second brighter,
  one second dimmer, starting off. Brightness is updated every 10 ms.
- Console output uses USB Serial/JTAG (GPIO18/19), freeing GPIO21 from UART TX.
  Use the native USB connection for logs.

Build and flash the **whole project**, including the bootloader (`idf.py build
flash`); an application-only flash will not install the early POWER_HOLD hook.
The ROM executes before this hook and can emit UART boot messages on GPIO21.
The hardware power-on path must sustain power until the hook runs; firmware
cannot guarantee a continuously high pin starting at the instant power is applied.
Check POWER_HOLD on an oscilloscope during a cold start and button release.

(See the README.md file in the upper level 'examples' directory for more information about examples.)

This is the simplest buildable example. The example is used by command `idf.py create-project`
that copies the project to user specified path and set it's name. For more information follow the [docs page](https://docs.espressif.com/projects/esp-idf/en/latest/api-guides/build-system.html#start-a-new-project)



## How to use example
We encourage the users to use the example as a template for the new projects.
A recommended way is to follow the instructions on a [docs page](https://docs.espressif.com/projects/esp-idf/en/latest/api-guides/build-system.html#start-a-new-project).

## Example folder contents

The project **sample_project** contains one source file in C language [main.c](main/main.c). The file is located in folder [main](main).

ESP-IDF projects are built using CMake. The project build configuration is contained in `CMakeLists.txt`
files that provide set of directives and instructions describing the project's source files and targets
(executable, library, or both). 

Below is short explanation of remaining files in the project folder.

```
├── CMakeLists.txt
├── main
│   ├── CMakeLists.txt
│   └── main.c
└── README.md                  This is the file you are currently reading
```
Additionally, the sample project contains Makefile and component.mk files, used for the legacy Make based build system. 
They are not used or needed when building with CMake and idf.py.
