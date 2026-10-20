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

With `USE_BLE=1`, the device releases POWER_HOLD (GPIO21 low) if no BLE device
connects within 30 seconds of BLE startup, or reconnects within 30 seconds after
a disconnect. A successful connection cancels the timeout; notification
subscription is not required. Adjust `BLE_CONNECT_TIMEOUT_MS` in `main/main.c`
to change the timeout. This automatic shutdown is disabled with `USE_BLE=0`.

## Battery monitoring

GPIO3 (ADC1 channel 3) measures the midpoint of the 100k/100k battery divider.
The monitor uses ESP32-C3 curve-fitting ADC calibration and 12 dB attenuation,
averages 16 conversions spaced one RTOS tick apart, and multiplies the measured
pin voltage by two. Each measurement is followed by a one-second sleep.
Monitoring starts before BLE/sensor initialization and works with `USE_BLE=0`,
while disconnected, and while streaming is paused.

Three consecutive measurements below **3300 mV** release GPIO21 POWER_HOLD.
With the current 100 Hz RTOS tick, confirmation takes approximately 2.5–3.7
seconds after crossing the threshold. A recovered reading resets confirmation.
Change `BATTERY_CUTOFF_MV` and `BATTERY_LOW_READINGS` in
`main/battery_monitor.h` to tune this. Initialization/calibration failure or
three consecutive read failures also release power hold rather than operate
without monitoring. USB or a held power button may keep the board powered;
the monitor never reasserts power hold after a shutdown decision.

BLE sends a five-byte battery frame on the existing NUS TX characteristic
every two seconds when subscribed, independent of stream pause and diagnostics:
`AA 57 01 <battery_mV low byte> <battery_mV high byte>`.
Zero voltage means unavailable. IMU packets remain unchanged.

The Android app shows `Connected · N%` on the main and experiment screens.
An unavailable reading, older firmware, or no battery frame for ten seconds
shows `Connected · --%`; disconnect/reconnect clears the previous reading.
`BatteryStatus.kt` interpolates an approximate standard 4.2 V LiPo curve:
3.30 V = 0%, 3.70 V = 20%, 3.85 V = 50%, 4.00 V = 75%, 4.20 V = 100%.
These are estimates of usable charge above the chosen cutoff, not a calibrated
fuel gauge. The 40 mAh capacity alone does not specify the voltage curve;
load, temperature, cell aging, and charging affect it.

Bench validation before use:

- Compare reported millivolts with a meter at several battery voltages,
  especially 3.3 V and 4.2 V. The high-impedance divider can affect ADC accuracy;
  check whether a 100 nF capacitor from GPIO3 to ground is fitted.
- With a current-limited supply replacing the cell, verify GPIO21 goes low
  below 3.3 V after three readings, and short dips do not shut it down.
- Repeat while paused, disconnected, and streaming at maximum ODR. Check
  percentage updates and disconnect/reconnect behavior in both app screens.
- Confirm the cell manufacturer's cutoff and discharge curve before tuning
  the constants. Firmware shutdown supplements the cell protection circuit;
  it does not disconnect a divider wired directly across the battery.

References: [Espressif ADC oneshot and calibration](https://docs.espressif.com/projects/esp-idf/en/v5.3.1/esp32c3/api-reference/peripherals/adc_oneshot.html),
[standard LiPo voltage behavior](https://learn.adafruit.com/li-ion-and-lipoly-batteries/voltages).

## Streaming performance and diagnostics

Binary samples keep the existing AA55 / 12-bit packet format. Temporary NimBLE
buffer exhaustion now retries the oldest packet from a bounded 16-packet queue
instead of immediately discarding it. Sustained overload can still fill the
queue; overflow samples and permanent notification errors are reported every
two seconds when their counts change. Short SPI accesses use polling transfers,
and packed axes are written directly as bytes/nibbles.

In the updated Android app, enable **Stream diagnostics** after connecting.
It sends `diag_on`; `diag_off` disables the additional diagnostic traffic and
periodic detailed USB logs. The panel compares acquired samples, samples
submitted to NimBLE, and samples decoded on the phone, and shows queue/drop
counts and negotiated link settings. See [STREAM_DIAGNOSTICS.md](STREAM_DIAGNOSTICS.md)
for counter definitions, protocol layout, and the board-comparison procedure.

- GPIO21 (ESP32-C3 package pin 28 / U0TXD) drives TPS27081 POWER_HOLD high
  from the second-stage bootloader's before-init hook and stays high in the app.
- GPIO8 (package pin 14) drives the LED cathode: low is ON. After board power
  initialization, the LED stays steadily on for a three-second startup pause
  before BLE and the sensor task start, giving the USB monitor time to attach.
  Standby indication then
  uses 5 kHz PWM with a smooth two-second breathing cycle: one second brighter,
  one second dimmer, starting off. Brightness is updated every 10 ms.
  With BLE connected, notifications enabled, and streaming not paused, the LED
  stays fully on and the breathing timer is stopped. Pausing, disabling
  notifications, or disconnecting resumes breathing. LED updates run only on
  these state transitions, not in the sample/notification sending loop.
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
