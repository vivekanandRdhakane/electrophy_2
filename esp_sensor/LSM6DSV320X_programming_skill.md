# LSM6DSV320X Firmware Programming Skill

## Purpose

Use this skill when writing, reviewing, debugging, or modifying firmware that talks directly to the **STMicroelectronics LSM6DSV320X** IMU at register level.

This skill is intentionally **programming-focused**. Do not spend context on PCB layout, mechanical mounting, soldering, or general product/application information unless it directly affects firmware behavior.

Primary source: **LSM6DSV320X datasheet DS14623, Rev. 3, October 2025**.

## Device model

The LSM6DSV320X is not a conventional single-accelerometer IMU. Treat it as three sensing paths:

1. **Low-g accelerometer (UI chain)**: ±2/±4/±8/±16 g.
2. **High-g accelerometer**: ±32/±64/±128/±256/±320 g, with an independent ODR and dedicated processing path.
3. **Gyroscope (UI chain)**: ±250/±500/±1000/±2000/±4000 dps.

It also has FIFO, interrupts, timestamping, embedded functions, FSM, MLC, SFLP, and optional OIS/EIS paths.

For normal MCU firmware, prefer the **primary UI interface** and the ordinary UI accelerometer/gyro registers unless the task specifically requires high-g, FIFO, OIS/EIS, FSM, MLC, sensor hub, or SFLP.

## Firmware rules

- Treat register addresses and bit fields as authoritative. Never infer register layouts from another LSM6DSV-family device.
- Preserve reserved bits exactly as specified. Use read-modify-write for registers containing unrelated fields unless the whole register is intentionally configured.
- Use the datasheet's exact register names and addresses in code comments.
- Prefer small typed driver APIs over scattering magic register values through application code.
- Separate bus access from the sensor driver. The driver should call abstract `read_reg()`, `write_reg()`, and `read_regs()` primitives.
- For multi-byte sensor data, combine bytes as little-endian signed 16-bit two's-complement values.
- Enable register auto-increment (`IF_INC=1`) for burst reads/writes.
- Keep `BDU=1` for ordinary sensor sampling so a multi-byte sample is not partially updated while being read.
- Do not silently change the selected ODR/full-scale when implementing a helper; configuration should be explicit.
- When changing a full-scale setting that the datasheet requires to be changed while the sensing channel is powered down, follow that sequence rather than changing it on the fly.
- Do not assume the low-g and high-g accelerometers share the same registers, ODR, or sensitivity.

## Basic bus protocol

### I2C

The device is an I2C target.

- 7-bit target address is `110101x`.
- `SDO/TA0 = 0` -> 7-bit address `0x6A`.
- `SDO/TA0 = 1` -> 7-bit address `0x6B`.
- Supported I2C modes: Fast mode (400 kHz) and Fast-mode Plus (1 MHz).
- For I2C, `CS` is tied high / otherwise held in the I2C-select state.

When an MCU HAL expects a shifted address, verify whether it wants `0x6A/0x6B` or the 8-bit form before passing the address.

### SPI

- 3-wire and 4-wire SPI are supported.
- SPI modes 0 and 3 are supported.
- In 4-wire SPI, `SDI` is MOSI and `SDO` is MISO.
- `CS` goes low for a transaction and high at the end.
- A normal register access is a command byte followed by data; the first command bit is the R/W bit and the remaining 7 bits are the register address.
- For multi-byte accesses, address auto-increment depends on `IF_INC`.
- If there is a choice, mode 3 is a good default for driver implementations, but the MCU SPI configuration must match the actual board wiring and firmware framework.

## Minimal bring-up sequence

A robust first implementation should do this:

1. Initialize the MCU bus peripheral.
2. Read `WHO_AM_I` at `0x0F`.
3. Require the returned value to be `0x73`.
4. Optionally issue a software reset using `CTRL3.SW_RESET` (`0x12`, bit 0), then wait for reset completion and reconfigure the device.
5. Configure `CTRL3` (`0x12`):
   - `BDU=1`.
   - `IF_INC=1`.
6. Configure the low-g accelerometer (`CTRL1` + `CTRL8`) only if low-g data is needed.
7. Configure the gyroscope (`CTRL2` + `CTRL6`) only if gyro data is needed.
8. Configure the high-g accelerometer (`CTRL1_XL_HG`, `0x4E`) only if high-g data is needed.
9. Use `STATUS_REG` (`0x1E`) or an interrupt pin to know when data is ready.
10. Read and convert the raw samples.

### Minimal sanity-check pseudocode

```c
uint8_t who = lsm6dsv320x_read_reg(0x0F);
if (who != 0x73) {
    return SENSOR_NOT_PRESENT;
}

// CTRL3 (0x12): BDU=1, IF_INC=1
uint8_t ctrl3 = lsm6dsv320x_read_reg(0x12);
ctrl3 |= (1u << 6);   // BDU
ctrl3 |= (1u << 2);   // IF_INC
lsm6dsv320x_write_reg(0x12, ctrl3);
```

Adjust bit positions only after checking the exact register definition in the current datasheet; keep symbolic masks in the real driver.

## Key primary-interface registers

| Register | Address | Firmware purpose |
|---|---:|---|
| `FUNC_CFG_ACCESS` | `0x01` | Embedded/OIS/function-page access control |
| `PIN_CTRL` | `0x02` | Pin configuration |
| `IF_CFG` | `0x03` | Interface configuration |
| `FIFO_CTRL1` | `0x07` | FIFO watermark/configuration |
| `FIFO_CTRL2` | `0x08` | FIFO behavior/compression-related control |
| `FIFO_CTRL3` | `0x09` | Accelerometer/gyro batch data rates |
| `FIFO_CTRL4` | `0x0A` | FIFO mode and timestamp batching |
| `INT1_CTRL` | `0x0D` | INT1 data-ready/FIFO routing |
| `INT2_CTRL` | `0x0E` | INT2 data-ready/FIFO routing |
| `WHO_AM_I` | `0x0F` | Device identification, expected `0x73` |
| `CTRL1` | `0x10` | Low-g accelerometer ODR + operating mode |
| `CTRL2` | `0x11` | Gyroscope ODR + operating mode |
| `CTRL3` | `0x12` | Reset, BDU, register auto-increment |
| `CTRL6` | `0x15` | Gyroscope full-scale + LPF1 configuration |
| `CTRL7` | `0x16` | High-g data-ready interrupt routing + gyro LPF enable |
| `CTRL8` | `0x17` | Low-g accelerometer full-scale + filter settings |
| `FIFO_STATUS1` | `0x1B` | FIFO unread-word count / watermark-related status |
| `FIFO_STATUS2` | `0x1C` | FIFO flags and upper unread-word-count bits |
| `ALL_INT_SRC` | `0x1D` | Combined interrupt sources |
| `STATUS_REG` | `0x1E` | Data-ready status |
| `OUT_TEMP_L/H` | `0x20/0x21` | Temperature output |
| `OUTX/Y/Z_*_G` | `0x22..0x27` | UI gyroscope output |
| `OUTX/Y/Z_*_A` | `0x28..0x2D` | UI low-g accelerometer output |
| `UI_OUTX/Y/Z_*_A_OIS_HG` | `0x34..0x39` | OIS/high-g accelerometer output path |
| `TIMESTAMP0..3` | `0x40..0x43` | 32-bit timestamp |
| `WAKE_UP_SRC` | `0x45` | Wake-up/free-fall/activity status |
| `HG_WAKE_UP_SRC` | `0x4C` | High-g wake-up status |
| `CTRL2_XL_HG` | `0x4D` | High-g self-test/user-offset control |
| `CTRL1_XL_HG` | `0x4E` | High-g ODR, full-scale, and output-register enable |
| `HG_FUNCTIONS_ENABLE` | `0x52` | High-g wake-up/shock interrupt generator |
| `HG_WAKE_UP_THS` | `0x53` | High-g wake-up threshold |
| `MD1_CFG` | `0x5E` | Embedded-event routing to INT1 |
| `MD2_CFG` | `0x5F` | Embedded-event routing to INT2 |
| `FIFO_DATA_OUT_TAG` | `0x78` | FIFO sample type tag |
| `FIFO_DATA_OUT_X/Y/Z` | `0x79..0x7E` | FIFO 6-byte payload |

## Low-g accelerometer configuration

### `CTRL1` (`0x10`)

`CTRL1` controls the low-g accelerometer operating mode and ODR.

Operating modes include:

- `000`: high-performance
- `001`: high-accuracy ODR mode
- `011`: ODR-triggered mode
- `100`: low-power mode 1
- `101`: low-power mode 2
- `110`: low-power mode 3
- `111`: normal mode

ODR selection includes 1.875 Hz, 7.5 Hz, 15 Hz, 30 Hz, 60 Hz, 120 Hz, 240 Hz, 480 Hz, 960 Hz, 1.92 kHz, 3.84 kHz, and 7.68 kHz, depending on operating mode.

### `CTRL8` (`0x17`)

`FS_XL[1:0]` selects low-g full scale:

| FS bits | Range | Sensitivity |
|---|---:|---:|
| `00` | ±2 g | 0.061 mg/LSB |
| `01` | ±4 g | 0.122 mg/LSB |
| `10` | ±8 g | 0.244 mg/LSB |
| `11` | ±16 g | 0.488 mg/LSB |

Raw conversion:

```c
a_g = raw * sensitivity_mg_per_lsb / 1000.0f;
```

Avoid hard-coding one sensitivity value if the driver allows runtime full-scale changes.

## Gyroscope configuration

### `CTRL2` (`0x11`)

Controls gyro operating mode and ODR.

Common operating modes:

- `000`: high-performance
- `001`: high-accuracy ODR mode
- `011`: ODR-triggered mode
- `100`: sleep
- `101`: low-power

### `CTRL6` (`0x15`)

`FS_G[2:0]` selects gyro UI full scale:

| FS bits | Range | Sensitivity |
|---|---:|---:|
| `001` | ±250 dps | 8.75 mdps/LSB |
| `010` | ±500 dps | 17.50 mdps/LSB |
| `011` | ±1000 dps | 35 mdps/LSB |
| `100` | ±2000 dps | 70 mdps/LSB |
| `101` | ±4000 dps | 140 mdps/LSB |

Important: the datasheet marks `000` as reserved/default for `FS_G` and says a valid configuration from `001` to `101` must be selected while the gyroscope is in power-down mode.

## High-g accelerometer: critical distinction

The LSM6DSV320X has an **independent high-g accelerometer channel**. Do not implement high-g sampling by changing the normal accelerometer full-scale to ±16 g.

### `CTRL1_XL_HG` (`0x4E`)

Important fields:

- `XL_HG_REGOUT_EN`: enables reading high-g data through the UI output-register addresses `0x34..0x39`.
- `HG_USR_OFF_ON_OUT`: enables high-g user offset functionality on output registers.
- `ODR_XL_HG[2:0]`: high-g ODR.
- `FS_XL_HG[2:0]`: high-g full scale.

High-g ODR codes:

| ODR code | ODR |
|---|---:|
| `000` | power-down |
| `011` | 480 Hz |
| `100` | 960 Hz |
| `101` | 1.92 kHz |
| `110` | 3.84 kHz |
| `111` | 7.68 kHz |

High-g full-scale codes:

| FS code | Range | Sensitivity |
|---|---:|---:|
| `000` | ±32 g | 0.976 mg/LSB |
| `001` | ±64 g | 1.952 mg/LSB |
| `010` | ±128 g | 3.904 mg/LSB |
| `011` | ±256 g | 7.808 mg/LSB |
| `100` | ±320 g | 10.417 mg/LSB |

Example conversion:

```c
high_g = raw * high_g_sensitivity_mg_per_lsb / 1000.0f;
```

### High-g output registers

When `XL_HG_REGOUT_EN` is enabled, read:

- X: `0x34` low, `0x35` high
- Y: `0x36` low, `0x37` high
- Z: `0x38` low, `0x39` high

Each axis is a signed 16-bit two's-complement value.

### High-g data-ready

`STATUS_REG` (`0x1E`) contains `XLHGDA`, indicating that new high-g accelerometer data is available.

`CTRL7` (`0x16`) contains dedicated high-g data-ready interrupt routing bits:

- `INT1_DRDY_XL_HG`
- `INT2_DRDY_XL_HG`

This is useful when collecting high-g samples without continuously polling.

## Reading sensor samples

For normal UI accelerometer + gyro sampling, use a single burst read starting at `0x22` when `IF_INC=1` so that the following registers are read contiguously:

```text
0x22-0x27  gyro X/Y/Z
0x28-0x2D  accel X/Y/Z
```

Depending on the application, temperature can be read separately from `0x20-0x21`.

Use signed 16-bit reconstruction:

```c
static inline int16_t s16_from_le(uint8_t lo, uint8_t hi)
{
    return (int16_t)(((uint16_t)hi << 8) | lo);
}
```

Then apply the sensitivity selected by the current configuration.

## Data-ready and status handling

`STATUS_REG` (`0x1E`) provides:

- `XLDA`: low-g accelerometer data available
- `GDA`: gyroscope data available
- `TDA`: temperature data available
- `XLHGDA`: high-g accelerometer data available
- `GDA_EIS`: enhanced-EIS gyro data available
- `OIS_DRDY`: OIS data available

For interrupt-driven firmware, configure `INT1_CTRL` or `INT2_CTRL` for the desired data-ready source instead of polling continuously.

The data-ready behavior can be latched or pulsed using the device's interrupt configuration. In latched mode, reading the associated output data clears the corresponding ready condition according to the datasheet behavior.

## Block Data Update (BDU)

`CTRL3.BDU` defaults to 1.

With `BDU=1`, output registers are not updated until the low and high parts of the current word have been read. Keep BDU enabled for normal MCU burst reads unless there is a deliberate reason to use continuous-update behavior.

This is especially important when an MCU reads multiple bytes over a relatively slow bus.

## Register auto-increment

`CTRL3.IF_INC` defaults to 1.

With `IF_INC=1`, register addresses increment during multi-byte accesses over I2C, I3C, or SPI. Keep this enabled unless a specialized access pattern requires otherwise.

## Software reset

`CTRL3.SW_RESET` (`0x12`) resets control registers to their default values and automatically clears.

A safe driver reset helper should:

1. Write `SW_RESET=1`.
2. Wait/poll until the bit clears or use the timing specified by the datasheet/application note.
3. Reapply all required configuration.
4. Re-check `WHO_AM_I` if the reset path is being used for recovery.

Do not assume a reset preserves ODR/full-scale/FIFO/interrupt configuration.

## FIFO programming

The device has a smart FIFO with up to 4.5 KB capacity when compression is enabled.

Key configuration registers:

- `FIFO_CTRL1` (`0x07`) — watermark threshold.
- `FIFO_CTRL2` (`0x08`) — FIFO-related control/compression behavior.
- `FIFO_CTRL3` (`0x09`) — gyro and accelerometer batch data rates.
- `FIFO_CTRL4` (`0x0A`) — FIFO mode and timestamp batching.
- `FIFO_STATUS1` (`0x1B`) and `FIFO_STATUS2` (`0x1C`) — FIFO state and unread-word count.

`FIFO_CTRL3.BDR_XL` and `FIFO_CTRL3.BDR_GY` select the data rates written into FIFO.

FIFO modes include bypass, FIFO, continuous, continuous-to-FIFO, continuousWTM-to-full, bypass-to-continuous, and bypass-to-FIFO.

### FIFO read procedure

Each FIFO word is **7 bytes**:

```text
1 byte  FIFO_DATA_OUT_TAG      0x78
6 bytes payload                 0x79..0x7E
```

The 6-byte payload is X/Y/Z as three little-endian 16-bit words.

`FIFO_DATA_OUT_TAG.TAG_SENSOR[4:0]` identifies what sensor/function produced the word. Firmware must inspect the tag before interpreting the payload as accelerometer, gyroscope, timestamp, SFLP, MLC, high-g peak, etc.

Do not assume every FIFO word is a normal 3-axis accelerometer sample.

### FIFO word count

`DIFF_FIFO[8:0]` in `FIFO_STATUS1/2` reports the number of unread FIFO words, where one word is the 7-byte tag + payload unit.

For a FIFO drain routine:

```c
while (fifo_words_available()) {
    uint8_t tag = read_reg(FIFO_DATA_OUT_TAG);
    uint8_t raw[6];
    read_regs(FIFO_DATA_OUT_X_L, raw, 6);

    decode_fifo_word(tag, raw);
}
```

Use the actual FIFO status/availability condition from the current configuration rather than blindly looping on a byte count.

## Timestamping

The timestamp counter can be enabled through the embedded functions configuration.

Timestamp registers:

- `TIMESTAMP0` `0x40`
- `TIMESTAMP1` `0x41`
- `TIMESTAMP2` `0x42`
- `TIMESTAMP3` `0x43`

The timestamp is a 32-bit value. Datasheet typical resolution is about **21.7 µs per LSB**.

When using FIFO timestamps, decode the FIFO stream/tagging rules instead of treating timestamps as ordinary sensor-axis samples.

## High-g wake-up and shock

The high-g event engine is separate from normal data acquisition.

Relevant registers:

- `HG_WAKE_UP_SRC` (`0x4C`) — event status.
- `CTRL2_XL_HG` (`0x4D`) — high-g control/self-test.
- `CTRL1_XL_HG` (`0x4E`) — high-g ODR/full-scale/output routing.
- `HG_FUNCTIONS_ENABLE` (`0x52`) — enables high-g interrupt generator and selects wake-up/shock routing.
- `HG_WAKE_UP_THS` (`0x53`) — high-g wake-up threshold.
- `INACTIVITY_THS` (`0x55`) — includes high-g shock-change routing bits.

High-g wake-up threshold resolution depends on full scale:

- 1 g/LSB when high-g full scale is ≤ ±256 g.
- 1.25 g/LSB at ±320 g.

High-g interrupt logic can route wake-up and shock events to INT1 and/or INT2.

When debugging an event interrupt, inspect the corresponding source/status registers rather than assuming the interrupt pin alone identifies the event.

## Interrupt routing pattern

Use this mental model:

```text
Sensor event/data source
        |
        +--> source/status register
        |
        +--> INT1_CTRL / INT2_CTRL
        |
        +--> MD1_CFG / MD2_CFG (embedded-function events)
        |
        +--> MCU EXTI/GPIO ISR
```

For a debugging-friendly driver:

1. Configure the interrupt source.
2. Configure the physical INT pin routing.
3. In the ISR, capture a lightweight event flag only.
4. In the main task/thread, read the source/status register and sensor data.
5. Clear/acknowledge conditions by following the datasheet's read/clear behavior.

Avoid doing long SPI/I2C transactions inside a hard interrupt handler unless the platform architecture explicitly supports it.

## Common conversion helpers

Keep conversion centralized:

```c
float accel_raw_to_g(int16_t raw, float mg_per_lsb)
{
    return (raw * mg_per_lsb) / 1000.0f;
}

float gyro_raw_to_dps(int16_t raw, float mdps_per_lsb)
{
    return (raw * mdps_per_lsb) / 1000.0f;
}
```

For performance-sensitive embedded code, use fixed-point or integer units such as mg and mdps rather than floating point.

## Configuration API recommendation

A clean driver can expose APIs similar to:

```c
bool lsm6dsv320x_init(lsm6dsv320x_t *dev);
bool lsm6dsv320x_reset(lsm6dsv320x_t *dev);
bool lsm6dsv320x_set_accel_odr(lsm6dsv320x_t *dev, lsm6dsv320x_accel_odr_t odr);
bool lsm6dsv320x_set_accel_fs(lsm6dsv320x_t *dev, lsm6dsv320x_accel_fs_t fs);
bool lsm6dsv320x_set_gyro_odr(lsm6dsv320x_t *dev, lsm6dsv320x_gyro_odr_t odr);
bool lsm6dsv320x_set_gyro_fs(lsm6dsv320x_t *dev, lsm6dsv320x_gyro_fs_t fs);
bool lsm6dsv320x_high_g_enable(lsm6dsv320x_t *dev, ...);
bool lsm6dsv320x_read_accel(lsm6dsv320x_t *dev, vec3_i16_t *raw);
bool lsm6dsv320x_read_gyro(lsm6dsv320x_t *dev, vec3_i16_t *raw);
bool lsm6dsv320x_read_high_g(lsm6dsv320x_t *dev, vec3_i16_t *raw);
bool lsm6dsv320x_fifo_read_word(lsm6dsv320x_t *dev, lsm6dsv320x_fifo_word_t *word);
```

Keep the bus abstraction in the device object:

```c
typedef bool (*lsm6dsv320x_read_fn)(void *ctx,
                                    uint8_t reg,
                                    uint8_t *data,
                                    size_t len);

typedef bool (*lsm6dsv320x_write_fn)(void *ctx,
                                     uint8_t reg,
                                     const uint8_t *data,
                                     size_t len);
```

The exact API can be adapted to the user's MCU/HAL.

## Debugging checklist

When the sensor is not working, debug in this order:

### 1. Bus communication

- Verify CS/I2C addressing and bus mode.
- Read `WHO_AM_I (0x0F)` and require `0x73`.
- Test a harmless register read/write such as `CTRL3`.

### 2. Configuration

- Read back the control registers after writing them.
- Verify ODR is not zero/power-down.
- Verify full-scale bits are correct.
- Verify gyro `FS_G` is a valid non-reserved code.
- Verify high-g `XL_HG_REGOUT_EN` is enabled before trying to read high-g output registers.

### 3. Data path

- Check `STATUS_REG` data-ready bits.
- Read a complete 16-bit axis word, not only one byte.
- Check sign extension / two's-complement handling.
- Confirm the conversion sensitivity matches the currently selected full scale.

### 4. High-g-specific issues

- Do not read low-g registers and expect ±320 g behavior.
- Check `CTRL1_XL_HG` ODR/full-scale.
- Check `CTRL7` if using high-g DRDY interrupts.
- Check `HG_WAKE_UP_SRC` for wake-up event status.

### 5. FIFO-specific issues

- Confirm the selected FIFO mode.
- Confirm batch data rates in `FIFO_CTRL3`.
- Read FIFO status before draining.
- Decode `FIFO_DATA_OUT_TAG` for every FIFO word.
- Do not assume every FIFO word is a 3-axis accelerometer sample.

## Common mistakes to prevent

1. **Using the ±16 g low-g configuration for impacts above 16 g.**
   Use the dedicated high-g channel.

2. **Assuming high-g data is automatically present at `0x34..0x39`.**
   `XL_HG_REGOUT_EN` must be configured.

3. **Using the wrong sensitivity after changing full scale.**
   Raw counts are meaningful only with the matching FS sensitivity.

4. **Changing gyro full scale while it is active when the datasheet requires power-down.**
   Power the gyro down, update FS, then re-enable it.

5. **Reading only one byte of a 16-bit sample.**
   Read low + high bytes as an atomic/burst transaction when possible.

6. **Disabling BDU without a specific reason.**
   This can expose inconsistent multi-byte samples.

7. **Assuming FIFO is just a raw stream of XYZ samples.**
   FIFO words carry a tag and may contain different sensor/function data types.

8. **Hard-coding register values without preserving unrelated bits.**
   Prefer symbolic masks and read-modify-write.

9. **Ignoring reset side effects.**
   After software reset, reconfigure all required settings.

10. **Confusing OIS/EIS output registers with the normal UI path.**
    Use the primary UI output registers unless the application explicitly needs OIS/EIS.

## Coding style for AI-generated firmware

When generating code for this sensor:

- First identify the exact sensor path: low-g accel, high-g accel, gyro, FIFO, interrupt, or embedded function.
- State the exact registers being used.
- Use named constants/enums for ODR and FS selections.
- Use helper functions for register bit-field updates.
- Read back configuration during bring-up and assertions/tests.
- Keep raw data structures separate from converted physical-unit structures.
- Make axis order and sign conventions explicit.
- Do not invent undocumented register values.
- Do not reuse register definitions from LSM6DSO, LSM6DSM, LSM6DSOX, LSM6DSV, or another related part unless the exact register definition has been verified for **LSM6DSV320X**.
- When the datasheet refers to an application note for a detailed sequence, flag that dependency instead of fabricating a sequence.

## Useful test cases

A driver implementation should ideally have tests for:

- `WHO_AM_I == 0x73`.
- I2C address `0x6A` and `0x6B` selection logic.
- SPI read/write transaction framing.
- Signed little-endian 16-bit reconstruction.
- Low-g FS-to-sensitivity mapping.
- Gyro FS-to-sensitivity mapping.
- High-g FS-to-sensitivity mapping.
- High-g output-register enable/disable behavior.
- `STATUS_REG` data-ready decoding.
- FIFO tag decoding.
- FIFO unread-word count handling.
- Software-reset and post-reset reconfiguration.
- Interrupt source/routing decoding.

## Datasheet-specific references

Use these sections as the first places to inspect when a programming question arises:

- Digital interfaces: Section 5.1
- Functionality / operating behavior: Section 6
- FIFO: Section 6.5, especially FIFO reading procedure
- Register map: Section 8
- Register descriptions: Section 9
- `WHO_AM_I`, primary control registers: Section 9.13 onward
- High-g controls: Section 9.52-9.53 and related high-g registers
- FIFO output registers: Section 9.90 onward

## Scope boundary

This skill should help an AI agent write **firmware** for the LSM6DSV320X. It should not attempt to replace the complete datasheet or application notes for:

- PCB layout and mechanical mounting
- MEMS soldering/handling
- production calibration procedures
- detailed OIS/EIS integration
- detailed FSM bytecode authoring
- detailed MLC model generation/configuration
- MIPI I3C certification/compliance work
- electrical safety or absolute-maximum-rating analysis

For those tasks, consult the relevant ST documentation directly.
