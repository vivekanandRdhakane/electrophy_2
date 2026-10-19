/**
 * @file    main.c
 * @brief   LSM6DSV320X IMU on ESP32-C3 — SPI + BLE GATT Notify (NimBLE)
 *
 * Pin mapping
 * -----------
 *  ESP32-C3  |  Sensor
 * -----------+---------
 *  GPIO4     |  SCLK
 *  GPIO5     |  CS  (software-controlled)
 *  GPIO6     |  MOSI
 *  GPIO7     |  MISO
 *  GPIO10    |  INT1 (rising-edge interrupt)
 *
 * BLE
 * ---
 *  Device name : ESP_IMU
 *  Service     : 0x1819
 *  Characteristic : 0xABCD  (Notify)
 *  Payload     : ASCII string, same format as serial log
 *                "[mg] X=%7.2f Y=%7.2f Z=%7.2f [mdps] X=%8.2f Y=%8.2f Z=%8.2f"
 */

/* Set to 0 for sensor debugging without BLE; rebuild after changing. */
#ifndef USE_BLE
#define USE_BLE 1
#endif
#if USE_BLE != 0 && USE_BLE != 1
#error "USE_BLE must be 0 or 1"
#endif

#include <stdbool.h>
#include <stdio.h>
#include <math.h>
#include <string.h>
#include <strings.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#if USE_BLE
#include "freertos/event_groups.h"
#endif

#include "driver/spi_master.h"
#include "driver/gpio.h"
#include "driver/ledc.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_cpu.h"
#include "esp_task_wdt.h"
#if USE_BLE
#include "nvs_flash.h"

/* NimBLE */
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "imu_transport.h"
#endif

#include "lsm6dsv320x_reg.h"

/* ── Pin definitions ──────────────────────────────────────────────────────── */
#define PIN_SCLK    GPIO_NUM_4
#define PIN_CS      GPIO_NUM_5
#define PIN_MOSI    GPIO_NUM_6
#define PIN_MISO    GPIO_NUM_7
#define PIN_INT1    GPIO_NUM_10
#define PIN_POWER_HOLD GPIO_NUM_21  /* Package pin 28 / U0TXD */
#define PIN_STATUS_LED GPIO_NUM_8   /* Package pin 14, cathode: active low */

#define SPI_HOST_ID SPI2_HOST
#define SPI_CLK_HZ  (8 * 1000 * 1000)  /* 8 MHz */

#if USE_BLE
#define DEVICE_NAME       "ESP_IMU"
#define BLE_CONNECT_TIMEOUT_MS 30000

static struct ble_npl_callout ble_shutdown_callout;
static bool ble_shutdown_requested;

/* Nordic UART Service (NUS) UUIDs — Standard for BLE Terminal Apps */
/* Service: 6E400001-B5A3-F393-E0A9-E50E24DCCA9E */
static const ble_uuid128_t nus_svc_uuid =
    BLE_UUID128_INIT(0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0,
                     0x93, 0xf3, 0xa3, 0xb5, 0x01, 0x00, 0x40, 0x6e);

/* TX Characteristic (ESP32 Notify/Read -> Phone): 6E400003-B5A3-F393-E0A9-E50E24DCCA9E */
static const ble_uuid128_t nus_tx_chr_uuid =
    BLE_UUID128_INIT(0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0,
                     0x93, 0xf3, 0xa3, 0xb5, 0x03, 0x00, 0x40, 0x6e);

/* RX Characteristic (Phone Write -> ESP32): 6E400002-B5A3-F393-E0A9-E50E24DCCA9E */
static const ble_uuid128_t nus_rx_chr_uuid =
    BLE_UUID128_INIT(0x9e, 0xca, 0xdc, 0x24, 0x0e, 0xe5, 0xa9, 0xe0,
                     0x93, 0xf3, 0xa3, 0xb5, 0x02, 0x00, 0x40, 0x6e);

static const char *TAG_BLE = "BLE";
#endif

/* ── Global state ─────────────────────────────────────────────────────────── */
static const char *TAG = "LSM6DSV320X";
static spi_device_handle_t spi_handle;
static TaskHandle_t        sensor_task_handle = NULL;
static volatile bool sensor_ready;

/* Serialize polling SPI and complete sample/configuration operations. The
 * recursive mutex lets register helpers share the same operation-level lock. */
static SemaphoreHandle_t spi_mutex;

/* Driver context — file-scope so BLE command handler can reconfigure ODR */
static stmdev_ctx_t dev_ctx;

#if USE_BLE
/* BLE connection / subscription state */
static uint16_t ble_conn_handle    = BLE_HS_CONN_HANDLE_NONE;
static uint16_t imu_chr_val_handle = 0;
static bool     ble_notify_enabled = false;
static void status_led_update_streaming(void);
static uint8_t  own_addr_type      = BLE_OWN_ADDR_RANDOM; /* resolved at sync */

/* FreeRTOS event group: set when a central is connected */
#define BLE_CONNECTED_BIT   BIT0
static EventGroupHandle_t ble_event_group;
#endif

/* Streaming mode controlled by mobile commands */
typedef enum {
    STREAM_MODE_ALL = 0,     /* Low-G + High-G + Gyro */
    STREAM_MODE_LOW_ACC,     /* Low-G Accelerometer only */
    STREAM_MODE_HIGH_ACC,    /* High-G Accelerometer only */
    STREAM_MODE_BOTH_ACC,    /* Both Low-G & High-G Accelerometers */
    STREAM_MODE_ONLY_GYRO    /* Gyroscope only */
} stream_mode_t;

static volatile stream_mode_t current_stream_mode = STREAM_MODE_ALL;
#if USE_BLE
static volatile bool          g_data_paused       = false;

/* ── Binary Streaming Protocol ───────────────────────────────────────────── */
#define BINARY_MAGIC_0  0xAA
#define BINARY_MAGIC_1  0x55
#define BINARY_PACKET_MAX_BYTES IMU_PACKET_BYTES
/* NOTIFY_TX is submission, not radio ACK. Retain packets for bounded retries. */
#define BLE_TX_RETRY_US 2000LL

/* Payload formats (header field `fmt`) */
#define BINARY_FMT_16BIT 0   /* legacy: raw 16-bit per axis */
#define BINARY_FMT_12BIT 1   /* 12-bit packed (4 LSBs dropped) — 25% smaller */

typedef struct __attribute__((packed)) {
    uint8_t magic0;        /* 0xAA */
    uint8_t magic1;        /* 0x55 */
    uint8_t mode;          /* stream_mode_t (0: ALL, 1: LOW_ACC, 2: HIGH_ACC, 3: BOTH_ACC, 4: ONLY_GYRO) */
    uint8_t sample_count;  /* Number of samples in this packet */
    uint8_t xl_fs;         /* lsm6dsv320x_xl_full_scale_t (0: 2g, 1: 4g, 2: 8g, 3: 16g) */
    uint8_t hg_fs;         /* lsm6dsv320x_hg_xl_full_scale_t (0: 32g, 1: 64g, 2: 128g, 3: 256g, 4: 320g) */
    uint8_t gy_fs;         /* lsm6dsv320x_gy_full_scale_t (1: 250, 2: 500, 3: 1000, 4: 2000, 5: 4000) */
    uint8_t seq;           /* rolling packet sequence number 0..255 */
    uint8_t fmt;           /* payload format (BINARY_FMT_*) */
} imu_binary_header_t;

/* Local FIFO / batch accumulation buffer */
#define BLE_ATT_MTU_DEFAULT 23
static volatile uint16_t s_negotiated_mtu = BLE_ATT_MTU_DEFAULT;

static inline uint16_t imu_get_max_packet_bytes(void)
{
    uint16_t mtu = s_negotiated_mtu;
    /* ATT notification value cannot exceed (ATT_MTU - 3) */
    uint16_t att_val_max = (mtu > 3) ? (uint16_t)(mtu - 3) : 0;
    if (att_val_max > BINARY_PACKET_MAX_BYTES) {
        att_val_max = BINARY_PACKET_MAX_BYTES;
    }
    return att_val_max;
}

static uint8_t  s_batch_buf[BINARY_PACKET_MAX_BYTES];
static uint8_t  s_batch_sample_count = 0;
static uint8_t  s_batch_seq = 0;
static uint32_t s_batch_bitpos = 0;   /* bit offset of next packed axis in s_batch_buf */
static int64_t  s_last_batch_send_time = 0;
static imu_tx_queue_t s_tx_queue;
static int64_t s_next_tx_retry_us;
/* Host owns generation; sensor task alone owns batches and the retry queue. */
static volatile uint32_t s_stream_generation;
static uint32_t s_sensor_generation;
static volatile bool s_diagnostics_enabled;
static struct ble_npl_callout s_diagnostics_callout;
static uint8_t s_diagnostics_snapshot[72];
static void imu_publish_diagnostics(int64_t now_us);
static uint16_t s_link_interval, s_link_tx_octets;
static uint8_t s_link_tx_phy, s_link_rx_phy;
static struct {
    uint32_t acquired, submitted, overflow, discarded;
    uint32_t coalesced, timeouts, read_errors, retries, notify_errors;
    uint32_t bytes, packets;
} s_stats;
/* Optional wall-time profiling; cycle subtraction handles counter wrap.
 * CPU frequency is fixed in sdkconfig (power management disabled). */
typedef struct { uint64_t cycles; uint32_t count, max_cycles; } perf_stage_t;
typedef struct {
    perf_stage_t lock, read, pack, notify;
    uint32_t window_us, irq, acquired, coalesced;
} imu_perf_t;
static imu_perf_t s_perf, s_perf_snapshot;
static volatile uint32_t s_irq_count;
/* Sensor-task-owned FIFO state; combined modes retain direct-register tuples. */
static bool s_fifo_active, s_fifo_backlog;
static uint32_t s_fifo_generation = UINT32_MAX;
static uint32_t s_fifo_overruns, s_fifo_flushed, s_fifo_unexpected;
static uint16_t s_fifo_level, s_fifo_peak;
static uint8_t s_fifo_watermark;
static bool imu_fifo_sync(void);
static void imu_fifo_drain(void);
static inline void perf_add(perf_stage_t *stage, uint32_t start)
{
    uint32_t elapsed = esp_cpu_get_cycle_count() - start;
    stage->cycles += elapsed;
    stage->count++;
    if (elapsed > stage->max_cycles) stage->max_cycles = elapsed;
}
static void imu_discard_pending(void);
static void imu_drain_tx(void);

#endif

/* ── Dynamic ODR tracking ─────────────────────────────────────────────────── */
/* Current output data-rate (Hz) for each sensing path; 0 = power-down.
 * Initialised to match the startup configuration below. */
static int8_t s_freq_fine;
static bool s_freq_fine_valid;
static volatile float g_xl_odr_hz  = 15.0f;   /* Low-G  accel  — CTRL1  */
static volatile float g_gy_odr_hz  = 15.0f;   /* Gyro          — CTRL2  */
static volatile float g_hg_odr_hz  = 480.0f;  /* High-G accel  — CTRL1_XL_HG */

/* ── Dynamic Full-Scale (Range) tracking ──────────────────────────────────── */
static volatile lsm6dsv320x_xl_full_scale_t    g_xl_fs = LSM6DSV320X_2g;
static volatile lsm6dsv320x_hg_xl_full_scale_t g_hg_fs = LSM6DSV320X_320g;
static volatile lsm6dsv320x_gy_full_scale_t    g_gy_fs = LSM6DSV320X_2000dps;

static inline float convert_xl_to_mg(int16_t lsb, lsm6dsv320x_xl_full_scale_t fs)
{
    switch (fs) {
    case LSM6DSV320X_2g:  return lsm6dsv320x_from_fs2_to_mg(lsb);
    case LSM6DSV320X_4g:  return lsm6dsv320x_from_fs4_to_mg(lsb);
    case LSM6DSV320X_8g:  return lsm6dsv320x_from_fs8_to_mg(lsb);
    case LSM6DSV320X_16g: return lsm6dsv320x_from_fs16_to_mg(lsb);
    default:              return lsm6dsv320x_from_fs2_to_mg(lsb);
    }
}

static inline float convert_hg_to_g(int16_t lsb, lsm6dsv320x_hg_xl_full_scale_t fs)
{
    switch (fs) {
    case LSM6DSV320X_32g:  return lsm6dsv320x_from_fs32_to_mg(lsb) / 1000.0f;
    case LSM6DSV320X_64g:  return lsm6dsv320x_from_fs64_to_mg(lsb) / 1000.0f;
    case LSM6DSV320X_128g: return lsm6dsv320x_from_fs128_to_mg(lsb) / 1000.0f;
    case LSM6DSV320X_256g: return lsm6dsv320x_from_fs256_to_mg(lsb) / 1000.0f;
    case LSM6DSV320X_320g: return lsm6dsv320x_from_fs320_to_mg(lsb) / 1000.0f;
    default:               return lsm6dsv320x_from_fs320_to_mg(lsb) / 1000.0f;
    }
}

static inline float convert_gy_to_mdps(int16_t lsb, lsm6dsv320x_gy_full_scale_t fs)
{
    switch (fs) {
    case LSM6DSV320X_250dps:  return lsm6dsv320x_from_fs250_to_mdps(lsb);
    case LSM6DSV320X_500dps:  return lsm6dsv320x_from_fs500_to_mdps(lsb);
    case LSM6DSV320X_1000dps: return lsm6dsv320x_from_fs1000_to_mdps(lsb);
    case LSM6DSV320X_2000dps: return lsm6dsv320x_from_fs2000_to_mdps(lsb);
    case LSM6DSV320X_4000dps: return lsm6dsv320x_from_fs4000_to_mdps(lsb);
    default:                  return lsm6dsv320x_from_fs2000_to_mdps(lsb);
    }
}

/* Derived timing — updated by odr_recompute_timing() after every ODR change.
 * Send-interval: one BLE packet per cycle of the slowest active sensor.
 * DRDY timeout : 5× the slowest period (floor 50 ms) so a missed INT1 pulse
 *               doesn't stall the loop for more than one extra cycle. */
static volatile uint32_t g_drdy_timeout_ms = 350;      /* 5 × 66.7 ms ≈ 333 ms */

/**
 * @brief Dynamically update INT1 routing so that only the active sensor's
 *        DRDY interrupt triggers sensor_task wakeups.
 *
 * LSM6DSV320X routes Low-G and Gyro data-ready via INT1_CTRL (0x0D),
 * and High-G data-ready via CTRL7 (0x16). Both must be configured so
 * that High-G DRDY triggers INT1 when streaming High-G, Low-G DRDY triggers
 * INT1 when streaming Low-G, etc.
 */
static int32_t update_int_routing(void)
{
    if (spi_mutex == NULL) {
        return -1;
    }

    lsm6dsv320x_pin_int_route_t pin_int = {0};

    switch (current_stream_mode) {
    case STREAM_MODE_LOW_ACC:
        if (g_xl_odr_hz > 0.0f) {
            pin_int.drdy_xl = PROPERTY_ENABLE;
        }
        break;

    case STREAM_MODE_HIGH_ACC:
        if (g_hg_odr_hz > 0.0f) {
            pin_int.drdy_hg_xl = PROPERTY_ENABLE;
        }
        break;

    case STREAM_MODE_ONLY_GYRO:
        if (g_gy_odr_hz > 0.0f) {
            pin_int.drdy_g = PROPERTY_ENABLE;
        }
        break;

    case STREAM_MODE_BOTH_ACC:
        /* Route the fastest active sensor to drive INT1 */
        if (g_hg_odr_hz >= g_xl_odr_hz && g_hg_odr_hz > 0.0f) {
            pin_int.drdy_hg_xl = PROPERTY_ENABLE;
        } else if (g_xl_odr_hz > 0.0f) {
            pin_int.drdy_xl = PROPERTY_ENABLE;
        }
        break;

    case STREAM_MODE_ALL:
    default:
        /* Route the fastest active sensor among Low-G, High-G, Gyro */
        if (g_hg_odr_hz >= g_xl_odr_hz && g_hg_odr_hz >= g_gy_odr_hz && g_hg_odr_hz > 0.0f) {
            pin_int.drdy_hg_xl = PROPERTY_ENABLE;
        } else if (g_gy_odr_hz >= g_xl_odr_hz && g_gy_odr_hz > 0.0f) {
            pin_int.drdy_g = PROPERTY_ENABLE;
        } else if (g_xl_odr_hz > 0.0f) {
            pin_int.drdy_xl = PROPERTY_ENABLE;
        }
        break;
    }

#if USE_BLE
    if (s_fifo_active) {
        memset(&pin_int, 0, sizeof(pin_int));
        pin_int.fifo_th = PROPERTY_ENABLE;
    }
#endif
    int32_t rc = lsm6dsv320x_pin_int1_route_set(&dev_ctx, &pin_int);
    rc |= lsm6dsv320x_pin_int1_route_hg_set(&dev_ctx, &pin_int);

    ESP_LOGI("INT_ROUTE", "INT1 routing updated: mode=%d xl=%u hg=%u g=%u fifo=%u",
             current_stream_mode, pin_int.drdy_xl, pin_int.drdy_hg_xl, pin_int.drdy_g, pin_int.fifo_th);
    return rc;
}

/**
 * @brief Recompute the data-ready timeout, and update INT1 routing.
 *
 * Call this after changing any sensor ODR or stream mode so the main loop self-adjusts.
 * The DRDY timeout is 5× the period of the active streaming sensor, min 50 ms.
 */
static void odr_recompute_timing(void)
{
    float active_hz = 0.0f;

    switch (current_stream_mode) {
    case STREAM_MODE_LOW_ACC:
        active_hz = g_xl_odr_hz;
        break;
    case STREAM_MODE_HIGH_ACC:
        active_hz = g_hg_odr_hz;
        break;
    case STREAM_MODE_ONLY_GYRO:
        active_hz = g_gy_odr_hz;
        break;
    case STREAM_MODE_BOTH_ACC:
        if (g_xl_odr_hz > 0.0f && g_hg_odr_hz > 0.0f) {
            active_hz = (g_xl_odr_hz < g_hg_odr_hz) ? g_xl_odr_hz : g_hg_odr_hz;
        } else {
            active_hz = (g_xl_odr_hz > 0.0f) ? g_xl_odr_hz : g_hg_odr_hz;
        }
        break;
    case STREAM_MODE_ALL:
    default: {
        float min_h = 0.0f;
        if (g_xl_odr_hz > 0.0f) min_h = g_xl_odr_hz;
        if (g_gy_odr_hz > 0.0f) min_h = (min_h == 0.0f) ? g_gy_odr_hz : (g_gy_odr_hz < min_h ? g_gy_odr_hz : min_h);
        if (g_hg_odr_hz > 0.0f) min_h = (min_h == 0.0f) ? g_hg_odr_hz : (g_hg_odr_hz < min_h ? g_hg_odr_hz : min_h);
        active_hz = min_h;
        break;
    }
    }

    if (active_hz <= 0.0f) {
        /* All active sensors off — fall back to 1 Hz so the loop is not completely frozen */
        active_hz = 1.0f;
    }

    int64_t  period_us  = (int64_t)(1e6f / active_hz);
    uint32_t timeout_ms = (uint32_t)((5.0f * 1000.0f) / active_hz);

    if (timeout_ms < 50)     timeout_ms = 50;         /* floor 50 ms */

    g_drdy_timeout_ms  = timeout_ms;

    ESP_LOGI("ODR", "Timing updated: mode=%d active_odr=%.3f Hz  sample_period=%lld µs  drdy_timeout=%lu ms",
             current_stream_mode, active_hz, (long long)period_us, (unsigned long)timeout_ms);

    if (s_freq_fine_valid && current_stream_mode == STREAM_MODE_LOW_ACC && active_hz >= 7.5f) {
        ESP_LOGI("ODR", "Low-G normal-mode factory estimate=%.2f Hz (FREQ_FINE=%d)",
                 active_hz * (1.0f + 0.0013f * s_freq_fine), s_freq_fine);
    }

    /* Update hardware INT1 interrupt routing */
    update_int_routing();

}

/* ── INT1 ISR ─────────────────────────────────────────────────────────────── */
static void IRAM_ATTR int1_isr_handler(void *arg)
{
#if USE_BLE
    if (s_diagnostics_enabled) s_irq_count++;
#endif
    BaseType_t higher_prio_woken = pdFALSE;
    vTaskNotifyGiveFromISR(sensor_task_handle, &higher_prio_woken);
    portYIELD_FROM_ISR(higher_prio_woken);
}

/* ── Platform layer ───────────────────────────────────────────────────────── */

/**
 * @brief Write registers via SPI — CS↓ · [reg] · [data] · CS↑
 */
static int32_t platform_write(void *handle, uint8_t reg, const uint8_t *bufp, uint16_t len)
{
    (void)handle;

    xSemaphoreTakeRecursive(spi_mutex, portMAX_DELAY);

    gpio_set_level(PIN_CS, 0);

    spi_transaction_t t = {0};
    t.length    = 8;
    t.tx_buffer = &reg;
    esp_err_t rc = spi_device_polling_transmit(spi_handle, &t);

    if (rc == ESP_OK && len > 0) {
        memset(&t, 0, sizeof(t));
        t.length    = len * 8;
        t.tx_buffer = bufp;
        rc = spi_device_polling_transmit(spi_handle, &t);
    }

    gpio_set_level(PIN_CS, 1);

    xSemaphoreGiveRecursive(spi_mutex);
    return rc;
}

/**
 * @brief Read registers via SPI — CS↓ · [reg|0x80 + dummy] · [receive] · CS↑
 *
 * One full-duplex transaction: 1 address byte + `len` dummy bytes are clocked
 * out while `len+1` bytes are clocked in (byte 0 is don't-care). This halves
 * the per-read SPI overhead vs. two separate transactions, which matters in
 * the 7680 Hz hot loop. Buffers are 4-byte aligned so the driver doesn't have
 * to DMA-copy them.
 */
static int32_t platform_read(void *handle, uint8_t reg, uint8_t *bufp, uint16_t len)
{
    (void)handle;
    reg |= 0x80;

    uint8_t __attribute__((aligned(4))) tx[33];
    uint8_t __attribute__((aligned(4))) rx[33];

    if (len > 32) return ESP_ERR_INVALID_SIZE;

    tx[0] = reg;
    memset(&tx[1], 0x00, len);

    xSemaphoreTakeRecursive(spi_mutex, portMAX_DELAY);

    gpio_set_level(PIN_CS, 0);

    spi_transaction_t t = {0};
    t.length    = (len + 1) * 8;
    t.rxlength  = (len + 1) * 8;
    t.tx_buffer = tx;
    t.rx_buffer = rx;
    esp_err_t rc = spi_device_polling_transmit(spi_handle, &t);

    gpio_set_level(PIN_CS, 1);

    xSemaphoreGiveRecursive(spi_mutex);

    if (rc == ESP_OK) memcpy(bufp, &rx[1], len);
    return rc;
}

/**
 * @brief Millisecond delay shim.
 */
static void platform_delay(uint32_t ms)
{
    vTaskDelay(pdMS_TO_TICKS(ms));
}

/* ── Hardware initialisation ──────────────────────────────────────────────── */

static void spi_init(void)
{
    spi_mutex = xSemaphoreCreateRecursiveMutex();
    assert(spi_mutex != NULL);

    spi_bus_config_t bus_cfg = {
        .mosi_io_num     = PIN_MOSI,
        .miso_io_num     = PIN_MISO,
        .sclk_io_num     = PIN_SCLK,
        .quadwp_io_num   = -1,
        .quadhd_io_num   = -1,
        .max_transfer_sz = 64,
    };
    ESP_ERROR_CHECK(spi_bus_initialize(SPI_HOST_ID, &bus_cfg, SPI_DMA_CH_AUTO));

    spi_device_interface_config_t dev_cfg = {
        .clock_speed_hz = SPI_CLK_HZ,
        .mode           = 0,    /* CPOL=0, CPHA=0 */
        .spics_io_num   = -1,   /* manual CS */
        .queue_size     = 4,
    };
    ESP_ERROR_CHECK(spi_bus_add_device(SPI_HOST_ID, &dev_cfg, &spi_handle));
}

static void cs_gpio_init(void)
{
    gpio_config_t io_conf = {
        .pin_bit_mask = (1ULL << PIN_CS),
        .mode         = GPIO_MODE_OUTPUT,
        .pull_up_en   = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type    = GPIO_INTR_DISABLE,
    };
    ESP_ERROR_CHECK(gpio_config(&io_conf));
    gpio_set_level(PIN_CS, 1); /* deselect */
}

static void int1_gpio_init(void)
{
    gpio_config_t io_conf = {
        .pin_bit_mask = (1ULL << PIN_INT1),
        .mode         = GPIO_MODE_INPUT,
        .pull_up_en   = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_ENABLE,
        .intr_type    = GPIO_INTR_POSEDGE,
    };
    ESP_ERROR_CHECK(gpio_config(&io_conf));
    ESP_ERROR_CHECK(gpio_install_isr_service(0));
    ESP_ERROR_CHECK(gpio_isr_handler_add(PIN_INT1, int1_isr_handler, NULL));
}

/* Cache of latest string for GATT Read */
#if USE_BLE
static char latest_ble_str[256] = "Sensor initializing...\r\n";

/* ── BLE: GATT characteristic access callback ─────────────────────────────── */
static int imu_chr_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                              struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        int rc = os_mbuf_append(ctxt->om, latest_ble_str, strlen(latest_ble_str));
        return (rc == 0) ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        char cmd[32] = {0};
        uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
        if (len >= sizeof(cmd)) {
            len = sizeof(cmd) - 1;
        }
        os_mbuf_copydata(ctxt->om, 0, len, cmd);
        cmd[len] = '\0';

        /* Strip trailing \r, \n, spaces */
        while (len > 0 && (cmd[len - 1] == '\r' || cmd[len - 1] == '\n' || cmd[len - 1] == ' ')) {
            cmd[--len] = '\0';
        }

        ESP_LOGI(TAG_BLE, "Received command: \"%s\"", cmd);

        /* ── Pause / Resume commands ──────────────────────────────────── */
        if (strcasecmp(cmd, "diag_on") == 0 || strcasecmp(cmd, "diag_off") == 0) {
            s_diagnostics_enabled = strcasecmp(cmd, "diag_on") == 0;
            return 0;
        }
        if (!sensor_ready) return BLE_ATT_ERR_UNLIKELY;
        xSemaphoreTakeRecursive(spi_mutex, portMAX_DELAY);
        s_stream_generation++;

        if (strcasecmp(cmd, "pause") == 0 || strcasecmp(cmd, "stop") == 0) {
            g_data_paused = true;
            status_led_update_streaming();
            ESP_LOGI(TAG_BLE, "Data reading and sending PAUSED");
        } else if (strcasecmp(cmd, "resume") == 0 || strcasecmp(cmd, "start") == 0 || strcasecmp(cmd, "play") == 0) {
            g_data_paused = false;
            status_led_update_streaming();
            ESP_LOGI(TAG_BLE, "Data reading and sending RESUMED");

        /* ── Stream-mode commands ──────────────────────────────────────── */
        } else if (strcasecmp(cmd, "low_acc") == 0 || strcasecmp(cmd, "only_acc") == 0 || strcasecmp(cmd, "low_g") == 0) {
            current_stream_mode = STREAM_MODE_LOW_ACC;
            odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Switched stream mode: LOW-G ACCELEROMETER ONLY");
        } else if (strcasecmp(cmd, "high_acc") == 0 || strcasecmp(cmd, "high_g") == 0) {
            current_stream_mode = STREAM_MODE_HIGH_ACC;
            odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Switched stream mode: HIGH-G ACCELEROMETER ONLY");
        } else if (strcasecmp(cmd, "both_acc") == 0 || strcasecmp(cmd, "acc") == 0) {
            current_stream_mode = STREAM_MODE_BOTH_ACC;
            odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Switched stream mode: BOTH ACCELEROMETERS (LOW-G + HIGH-G)");
        } else if (strcasecmp(cmd, "only_gyro") == 0 || strcasecmp(cmd, "gyro") == 0) {
            current_stream_mode = STREAM_MODE_ONLY_GYRO;
            odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Switched stream mode: GYROSCOPE ONLY");
        } else if (strcasecmp(cmd, "all") == 0 || strcasecmp(cmd, "both") == 0) {
            current_stream_mode = STREAM_MODE_ALL;
            odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Switched stream mode: ALL (LOW-G + HIGH-G + GYRO)");

        /* ── Low-G accelerometer ODR commands (CTRL1 0x10) ────────────── */
        /* odr_low_g_<rate>  — keeps current operating mode, changes ODR only */
        } else if (strcasecmp(cmd, "odr_low_g_off") == 0) {
            lsm6dsv320x_xl_setup(&dev_ctx, LSM6DSV320X_ODR_OFF, LSM6DSV320X_XL_UNCHANGED_MD);
            g_xl_odr_hz = 0.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Low-G ODR: power-down");
        } else if (strcasecmp(cmd, "odr_low_g_1hz875") == 0) {
            lsm6dsv320x_xl_setup(&dev_ctx, LSM6DSV320X_ODR_AT_1Hz875, LSM6DSV320X_XL_UNCHANGED_MD);
            g_xl_odr_hz = 1.875f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Low-G ODR set to 1.875 Hz");
        } else if (strcasecmp(cmd, "odr_low_g_7hz5") == 0) {
            lsm6dsv320x_xl_setup(&dev_ctx, LSM6DSV320X_ODR_AT_7Hz5, LSM6DSV320X_XL_UNCHANGED_MD);
            g_xl_odr_hz = 7.5f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Low-G ODR set to 7.5 Hz");
        } else if (strcasecmp(cmd, "odr_low_g_15hz") == 0) {
            lsm6dsv320x_xl_setup(&dev_ctx, LSM6DSV320X_ODR_AT_15Hz, LSM6DSV320X_XL_UNCHANGED_MD);
            g_xl_odr_hz = 15.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Low-G ODR set to 15 Hz");
        } else if (strcasecmp(cmd, "odr_low_g_30hz") == 0) {
            lsm6dsv320x_xl_setup(&dev_ctx, LSM6DSV320X_ODR_AT_30Hz, LSM6DSV320X_XL_UNCHANGED_MD);
            g_xl_odr_hz = 30.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Low-G ODR set to 30 Hz");
        } else if (strcasecmp(cmd, "odr_low_g_60hz") == 0) {
            lsm6dsv320x_xl_setup(&dev_ctx, LSM6DSV320X_ODR_AT_60Hz, LSM6DSV320X_XL_UNCHANGED_MD);
            g_xl_odr_hz = 60.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Low-G ODR set to 60 Hz");
        } else if (strcasecmp(cmd, "odr_low_g_120hz") == 0) {
            lsm6dsv320x_xl_setup(&dev_ctx, LSM6DSV320X_ODR_AT_120Hz, LSM6DSV320X_XL_UNCHANGED_MD);
            g_xl_odr_hz = 120.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Low-G ODR set to 120 Hz");
        } else if (strcasecmp(cmd, "odr_low_g_240hz") == 0) {
            lsm6dsv320x_xl_setup(&dev_ctx, LSM6DSV320X_ODR_AT_240Hz, LSM6DSV320X_XL_UNCHANGED_MD);
            g_xl_odr_hz = 240.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Low-G ODR set to 240 Hz");
        } else if (strcasecmp(cmd, "odr_low_g_480hz") == 0) {
            lsm6dsv320x_xl_setup(&dev_ctx, LSM6DSV320X_ODR_AT_480Hz, LSM6DSV320X_XL_UNCHANGED_MD);
            g_xl_odr_hz = 480.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Low-G ODR set to 480 Hz");
        } else if (strcasecmp(cmd, "odr_low_g_960hz") == 0) {
            lsm6dsv320x_xl_setup(&dev_ctx, LSM6DSV320X_ODR_AT_960Hz, LSM6DSV320X_XL_UNCHANGED_MD);
            g_xl_odr_hz = 960.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Low-G ODR set to 960 Hz");
        } else if (strcasecmp(cmd, "odr_low_g_1920hz") == 0) {
            lsm6dsv320x_xl_setup(&dev_ctx, LSM6DSV320X_ODR_AT_1920Hz, LSM6DSV320X_XL_UNCHANGED_MD);
            g_xl_odr_hz = 1920.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Low-G ODR set to 1920 Hz");
        } else if (strcasecmp(cmd, "odr_low_g_3840hz") == 0) {
            lsm6dsv320x_xl_setup(&dev_ctx, LSM6DSV320X_ODR_AT_3840Hz, LSM6DSV320X_XL_UNCHANGED_MD);
            g_xl_odr_hz = 3840.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Low-G ODR set to 3840 Hz");
        } else if (strcasecmp(cmd, "odr_low_g_7680hz") == 0) {
            lsm6dsv320x_xl_setup(&dev_ctx, LSM6DSV320X_ODR_AT_7680Hz, LSM6DSV320X_XL_UNCHANGED_MD);
            g_xl_odr_hz = 7680.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Low-G ODR set to 7680 Hz");


        /* ── High-G accelerometer ODR commands (CTRL1_XL_HG 0x4E) ─────── */
        /* odr_high_g_<rate>  — keeps reg_out_en = 1 */
        } else if (strcasecmp(cmd, "odr_high_g_off") == 0) {
            lsm6dsv320x_hg_xl_data_rate_set(&dev_ctx, LSM6DSV320X_HG_XL_ODR_OFF, 0);
            g_hg_odr_hz = 0.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "High-G ODR: power-down");
        } else if (strcasecmp(cmd, "odr_high_g_480hz") == 0) {
            lsm6dsv320x_hg_xl_data_rate_set(&dev_ctx, LSM6DSV320X_HG_XL_ODR_AT_480Hz, 1);
            g_hg_odr_hz = 480.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "High-G ODR set to 480 Hz");
        } else if (strcasecmp(cmd, "odr_high_g_960hz") == 0) {
            lsm6dsv320x_hg_xl_data_rate_set(&dev_ctx, LSM6DSV320X_HG_XL_ODR_AT_960Hz, 1);
            g_hg_odr_hz = 960.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "High-G ODR set to 960 Hz");
        } else if (strcasecmp(cmd, "odr_high_g_1920hz") == 0) {
            lsm6dsv320x_hg_xl_data_rate_set(&dev_ctx, LSM6DSV320X_HG_XL_ODR_AT_1920Hz, 1);
            g_hg_odr_hz = 1920.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "High-G ODR set to 1920 Hz");
        } else if (strcasecmp(cmd, "odr_high_g_3840hz") == 0) {
            lsm6dsv320x_hg_xl_data_rate_set(&dev_ctx, LSM6DSV320X_HG_XL_ODR_AT_3840Hz, 1);
            g_hg_odr_hz = 3840.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "High-G ODR set to 3840 Hz");
        } else if (strcasecmp(cmd, "odr_high_g_7680hz") == 0) {
            lsm6dsv320x_hg_xl_data_rate_set(&dev_ctx, LSM6DSV320X_HG_XL_ODR_AT_7680Hz, 1);
            g_hg_odr_hz = 7680.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "High-G ODR set to 7680 Hz");

        /* ── Gyroscope ODR commands (CTRL2 0x11) ───────────────────────── */
        /* odr_gyro_<rate>  — keeps current operating mode, changes ODR only */
        } else if (strcasecmp(cmd, "odr_gyro_off") == 0) {
            lsm6dsv320x_gy_setup(&dev_ctx, LSM6DSV320X_ODR_OFF, LSM6DSV320X_GY_UNCHANGED_MD);
            g_gy_odr_hz = 0.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Gyro ODR: power-down");
        } else if (strcasecmp(cmd, "odr_gyro_7hz5") == 0) {
            lsm6dsv320x_gy_setup(&dev_ctx, LSM6DSV320X_ODR_AT_7Hz5, LSM6DSV320X_GY_UNCHANGED_MD);
            g_gy_odr_hz = 7.5f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Gyro ODR set to 7.5 Hz");
        } else if (strcasecmp(cmd, "odr_gyro_15hz") == 0) {
            lsm6dsv320x_gy_setup(&dev_ctx, LSM6DSV320X_ODR_AT_15Hz, LSM6DSV320X_GY_UNCHANGED_MD);
            g_gy_odr_hz = 15.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Gyro ODR set to 15 Hz");
        } else if (strcasecmp(cmd, "odr_gyro_30hz") == 0) {
            lsm6dsv320x_gy_setup(&dev_ctx, LSM6DSV320X_ODR_AT_30Hz, LSM6DSV320X_GY_UNCHANGED_MD);
            g_gy_odr_hz = 30.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Gyro ODR set to 30 Hz");
        } else if (strcasecmp(cmd, "odr_gyro_60hz") == 0) {
            lsm6dsv320x_gy_setup(&dev_ctx, LSM6DSV320X_ODR_AT_60Hz, LSM6DSV320X_GY_UNCHANGED_MD);
            g_gy_odr_hz = 60.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Gyro ODR set to 60 Hz");
        } else if (strcasecmp(cmd, "odr_gyro_120hz") == 0) {
            lsm6dsv320x_gy_setup(&dev_ctx, LSM6DSV320X_ODR_AT_120Hz, LSM6DSV320X_GY_UNCHANGED_MD);
            g_gy_odr_hz = 120.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Gyro ODR set to 120 Hz");
        } else if (strcasecmp(cmd, "odr_gyro_240hz") == 0) {
            lsm6dsv320x_gy_setup(&dev_ctx, LSM6DSV320X_ODR_AT_240Hz, LSM6DSV320X_GY_UNCHANGED_MD);
            g_gy_odr_hz = 240.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Gyro ODR set to 240 Hz");
        } else if (strcasecmp(cmd, "odr_gyro_480hz") == 0) {
            lsm6dsv320x_gy_setup(&dev_ctx, LSM6DSV320X_ODR_AT_480Hz, LSM6DSV320X_GY_UNCHANGED_MD);
            g_gy_odr_hz = 480.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Gyro ODR set to 480 Hz");
        } else if (strcasecmp(cmd, "odr_gyro_960hz") == 0) {
            lsm6dsv320x_gy_setup(&dev_ctx, LSM6DSV320X_ODR_AT_960Hz, LSM6DSV320X_GY_UNCHANGED_MD);
            g_gy_odr_hz = 960.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Gyro ODR set to 960 Hz");
        } else if (strcasecmp(cmd, "odr_gyro_1920hz") == 0) {
            lsm6dsv320x_gy_setup(&dev_ctx, LSM6DSV320X_ODR_AT_1920Hz, LSM6DSV320X_GY_UNCHANGED_MD);
            g_gy_odr_hz = 1920.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Gyro ODR set to 1920 Hz");
        } else if (strcasecmp(cmd, "odr_gyro_3840hz") == 0) {
            lsm6dsv320x_gy_setup(&dev_ctx, LSM6DSV320X_ODR_AT_3840Hz, LSM6DSV320X_GY_UNCHANGED_MD);
            g_gy_odr_hz = 3840.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Gyro ODR set to 3840 Hz");
        } else if (strcasecmp(cmd, "odr_gyro_7680hz") == 0) {
            lsm6dsv320x_gy_setup(&dev_ctx, LSM6DSV320X_ODR_AT_7680Hz, LSM6DSV320X_GY_UNCHANGED_MD);
            g_gy_odr_hz = 7680.0f; odr_recompute_timing();
            ESP_LOGI(TAG_BLE, "Gyro ODR set to 7680 Hz");

        /* ── Low-G accelerometer range commands (CTRL8 0x17) ──────────── */
        } else if (strcasecmp(cmd, "range_low_g_2g") == 0 || strcasecmp(cmd, "fs_low_g_2g") == 0) {
            lsm6dsv320x_xl_full_scale_set(&dev_ctx, LSM6DSV320X_2g);
            g_xl_fs = LSM6DSV320X_2g;
            ESP_LOGI(TAG_BLE, "Low-G range set to ±2g");
        } else if (strcasecmp(cmd, "range_low_g_4g") == 0 || strcasecmp(cmd, "fs_low_g_4g") == 0) {
            lsm6dsv320x_xl_full_scale_set(&dev_ctx, LSM6DSV320X_4g);
            g_xl_fs = LSM6DSV320X_4g;
            ESP_LOGI(TAG_BLE, "Low-G range set to ±4g");
        } else if (strcasecmp(cmd, "range_low_g_8g") == 0 || strcasecmp(cmd, "fs_low_g_8g") == 0) {
            lsm6dsv320x_xl_full_scale_set(&dev_ctx, LSM6DSV320X_8g);
            g_xl_fs = LSM6DSV320X_8g;
            ESP_LOGI(TAG_BLE, "Low-G range set to ±8g");
        } else if (strcasecmp(cmd, "range_low_g_16g") == 0 || strcasecmp(cmd, "fs_low_g_16g") == 0) {
            lsm6dsv320x_xl_full_scale_set(&dev_ctx, LSM6DSV320X_16g);
            g_xl_fs = LSM6DSV320X_16g;
            ESP_LOGI(TAG_BLE, "Low-G range set to ±16g");

        /* ── High-G accelerometer range commands (CTRL1_XL_HG 0x4E) ───── */
        } else if (strcasecmp(cmd, "range_high_g_32g") == 0 || strcasecmp(cmd, "fs_high_g_32g") == 0) {
            lsm6dsv320x_hg_xl_full_scale_set(&dev_ctx, LSM6DSV320X_32g);
            g_hg_fs = LSM6DSV320X_32g;
            ESP_LOGI(TAG_BLE, "High-G range set to ±32g");
        } else if (strcasecmp(cmd, "range_high_g_64g") == 0 || strcasecmp(cmd, "fs_high_g_64g") == 0) {
            lsm6dsv320x_hg_xl_full_scale_set(&dev_ctx, LSM6DSV320X_64g);
            g_hg_fs = LSM6DSV320X_64g;
            ESP_LOGI(TAG_BLE, "High-G range set to ±64g");
        } else if (strcasecmp(cmd, "range_high_g_128g") == 0 || strcasecmp(cmd, "fs_high_g_128g") == 0) {
            lsm6dsv320x_hg_xl_full_scale_set(&dev_ctx, LSM6DSV320X_128g);
            g_hg_fs = LSM6DSV320X_128g;
            ESP_LOGI(TAG_BLE, "High-G range set to ±128g");
        } else if (strcasecmp(cmd, "range_high_g_256g") == 0 || strcasecmp(cmd, "fs_high_g_256g") == 0) {
            lsm6dsv320x_hg_xl_full_scale_set(&dev_ctx, LSM6DSV320X_256g);
            g_hg_fs = LSM6DSV320X_256g;
            ESP_LOGI(TAG_BLE, "High-G range set to ±256g");
        } else if (strcasecmp(cmd, "range_high_g_320g") == 0 || strcasecmp(cmd, "fs_high_g_320g") == 0) {
            lsm6dsv320x_hg_xl_full_scale_set(&dev_ctx, LSM6DSV320X_320g);
            g_hg_fs = LSM6DSV320X_320g;
            ESP_LOGI(TAG_BLE, "High-G range set to ±320g");

        /* ── Gyroscope range commands (CTRL6 0x15) ────────────────────── */
        } else if (strcasecmp(cmd, "range_gyro_250dps") == 0 || strcasecmp(cmd, "fs_gyro_250dps") == 0 || strcasecmp(cmd, "range_gyro_250") == 0) {
            lsm6dsv320x_gy_full_scale_set(&dev_ctx, LSM6DSV320X_250dps);
            g_gy_fs = LSM6DSV320X_250dps;
            ESP_LOGI(TAG_BLE, "Gyro range set to ±250 dps");
        } else if (strcasecmp(cmd, "range_gyro_500dps") == 0 || strcasecmp(cmd, "fs_gyro_500dps") == 0 || strcasecmp(cmd, "range_gyro_500") == 0) {
            lsm6dsv320x_gy_full_scale_set(&dev_ctx, LSM6DSV320X_500dps);
            g_gy_fs = LSM6DSV320X_500dps;
            ESP_LOGI(TAG_BLE, "Gyro range set to ±500 dps");
        } else if (strcasecmp(cmd, "range_gyro_1000dps") == 0 || strcasecmp(cmd, "fs_gyro_1000dps") == 0 || strcasecmp(cmd, "range_gyro_1000") == 0) {
            lsm6dsv320x_gy_full_scale_set(&dev_ctx, LSM6DSV320X_1000dps);
            g_gy_fs = LSM6DSV320X_1000dps;
            ESP_LOGI(TAG_BLE, "Gyro range set to ±1000 dps");
        } else if (strcasecmp(cmd, "range_gyro_2000dps") == 0 || strcasecmp(cmd, "fs_gyro_2000dps") == 0 || strcasecmp(cmd, "range_gyro_2000") == 0) {
            lsm6dsv320x_gy_full_scale_set(&dev_ctx, LSM6DSV320X_2000dps);
            g_gy_fs = LSM6DSV320X_2000dps;
            ESP_LOGI(TAG_BLE, "Gyro range set to ±2000 dps");
        } else if (strcasecmp(cmd, "range_gyro_4000dps") == 0 || strcasecmp(cmd, "fs_gyro_4000dps") == 0 || strcasecmp(cmd, "range_gyro_4000") == 0) {
            lsm6dsv320x_gy_full_scale_set(&dev_ctx, LSM6DSV320X_4000dps);
            g_gy_fs = LSM6DSV320X_4000dps;
            ESP_LOGI(TAG_BLE, "Gyro range set to ±4000 dps");

        } else {
            ESP_LOGW(TAG_BLE,
                     "Unknown command \"%s\".\n"
                     "  Control:      pause | resume (or stop | start)\n"
                     "  Stream:       low_acc | high_acc | both_acc | only_gyro | all\n"
                     "  Low-G ODR:    odr_low_g_{off|1hz875|7hz5|15hz|30hz|60hz|120hz|240hz|480hz|960hz|1920hz|3840hz|7680hz}\n"
                     "  High-G ODR:   odr_high_g_{off|480hz|960hz|1920hz|3840hz|7680hz}\n"
                     "  Gyro ODR:     odr_gyro_{off|7hz5|15hz|30hz|60hz|120hz|240hz|480hz|960hz|1920hz|3840hz|7680hz}\n"
                     "  Low-G Range:  range_low_g_{2g|4g|8g|16g}\n"
                     "  High-G Range: range_high_g_{32g|64g|128g|256g|320g}\n"
                     "  Gyro Range:   range_gyro_{250dps|500dps|1000dps|2000dps|4000dps}",
                     cmd);
        }
        xSemaphoreGiveRecursive(spi_mutex);
        return 0;
    }
    return BLE_ATT_ERR_UNLIKELY;
}

/* ── BLE: GATT service table (Nordic UART Service) ─────────────────────────── */
static const struct ble_gatt_svc_def gatt_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &nus_svc_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                /* TX Characteristic: ESP32 sends notifications to phone */
                .uuid       = &nus_tx_chr_uuid.u,
                .access_cb  = imu_chr_access_cb,
                .flags      = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &imu_chr_val_handle,
            },
            {
                /* RX Characteristic: Phone can send commands/text to ESP32 */
                .uuid       = &nus_rx_chr_uuid.u,
                .access_cb  = imu_chr_access_cb,
                .flags      = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            { 0 }, /* end of characteristics */
        },
    },
    { 0 }, /* end of services */
};

/* Binary batching and bounded transport. */
#define PACKED_BITS_PER_AXIS 12

static inline uint16_t pack_axis(int16_t raw)
{
    /* Arithmetic shift keeps the sign; the low 12 bits are the two's-complement
     * value (0x0800..0x0FFF encode negative). */
    return (uint16_t)(raw >> 4) & 0x0FFFu;
}

static void imu_discard_pending(void)
{
    s_stats.discarded += imu_tx_clear(&s_tx_queue) + s_batch_sample_count;
    s_batch_sample_count = 0;
    s_batch_bitpos = 0;
    s_next_tx_retry_us = 0;
}

static void imu_drain_tx(void)
{
    if (s_sensor_generation != s_stream_generation || g_data_paused ||
        !ble_notify_enabled || ble_conn_handle == BLE_HS_CONN_HANDLE_NONE) {
        imu_discard_pending();
        ulTaskNotifyTake(pdTRUE, 0);
        s_sensor_generation = s_stream_generation;
        return;
    }
    if (!s_tx_queue.count || esp_timer_get_time() < s_next_tx_retry_us) return;
    imu_tx_packet_t *packet = &s_tx_queue.packets[s_tx_queue.head];
    if (packet->len > imu_get_max_packet_bytes()) {
        s_stats.discarded += packet->samples;
        imu_tx_pop(&s_tx_queue);
        return;
    }
    /* Accepted notifications have consecutive sequence numbers. */
    ((imu_binary_header_t *)packet->data)->seq = s_batch_seq;
    bool profile = s_diagnostics_enabled;
    uint32_t notify_start = profile ? esp_cpu_get_cycle_count() : 0;
    struct os_mbuf *om = ble_hs_mbuf_from_flat(packet->data, packet->len);
    int rc = om ? ble_gatts_notify_custom(ble_conn_handle, imu_chr_val_handle, om)
                : BLE_HS_ENOMEM;
    if (profile) perf_add(&s_perf.notify, notify_start);
    /* notify_custom consumes the mbuf on success AND failure. */
    if (rc == BLE_HS_ENOMEM || rc == BLE_HS_EAGAIN || rc == BLE_HS_EBUSY) {
        s_stats.retries++;
        s_next_tx_retry_us = esp_timer_get_time() + BLE_TX_RETRY_US;
        return;
    }
    if (rc == 0) {
        s_batch_seq++;
        s_stats.submitted += packet->samples;
        s_stats.bytes += packet->len;
        s_stats.packets++;
    } else {
        s_stats.notify_errors++;
        s_stats.discarded += packet->samples;
    }
    imu_tx_pop(&s_tx_queue);
    s_next_tx_retry_us = 0;
}

static void imu_flush_batch(void)
{
    if (s_batch_sample_count == 0) return;
    uint16_t total_len = sizeof(imu_binary_header_t) + (s_batch_bitpos + 7) / 8;
    imu_binary_header_t *hdr = (imu_binary_header_t *)s_batch_buf;
    hdr->sample_count = s_batch_sample_count;
    if (!imu_tx_push(&s_tx_queue, s_batch_buf, total_len, s_batch_sample_count)) {
        s_stats.overflow += s_batch_sample_count;
    }
    s_batch_sample_count = 0;
    s_batch_bitpos = 0;
    s_last_batch_send_time = esp_timer_get_time();
}

static void imu_accumulate_sample(const int16_t *raw_lg, const int16_t *raw_hg, const int16_t *raw_gy)
{
    /* Axes packed per sample (3 per sensing path). */
    uint8_t axes;
    switch (current_stream_mode) {
    case STREAM_MODE_BOTH_ACC: axes = 6; break;
    case STREAM_MODE_ALL:      axes = 9; break;
    default:                   axes = 3; break;
    }

    const uint16_t max_packet_bytes = imu_get_max_packet_bytes();
    if (max_packet_bytes <= sizeof(imu_binary_header_t)) {
        s_stats.overflow++;
        return;
    }

    const uint16_t payload_bytes    = max_packet_bytes - (uint16_t)sizeof(imu_binary_header_t);
    const uint16_t payload_bits     = payload_bytes * 8;
    const uint16_t bits_per_sample  = (uint16_t)axes * PACKED_BITS_PER_AXIS;
    const uint16_t max_samples      = payload_bits / bits_per_sample;

    if (max_samples == 0) {
        /* Not enough capacity for even one full sample tuple in this mode under current MTU */
        s_stats.overflow++;
        return;
    }

    if (s_batch_sample_count == 0) {
        /* Start a fresh batch: clear payload bits and reset the bit cursor. */
        memset(&s_batch_buf[sizeof(imu_binary_header_t)], 0,
               sizeof(s_batch_buf) - sizeof(imu_binary_header_t));
        s_batch_bitpos = 0;
        imu_binary_header_t *hdr = (imu_binary_header_t *)s_batch_buf;
        *hdr = (imu_binary_header_t){
            .magic0 = BINARY_MAGIC_0, .magic1 = BINARY_MAGIC_1,
            .mode = current_stream_mode, .xl_fs = g_xl_fs,
            .hg_fs = g_hg_fs, .gy_fs = g_gy_fs, .fmt = BINARY_FMT_12BIT,
        };
    }

    uint8_t  *payload = &s_batch_buf[sizeof(imu_binary_header_t)];
    uint32_t  bitpos  = s_batch_bitpos;

    switch (current_stream_mode) {
    case STREAM_MODE_LOW_ACC:
        imu_pack12(payload, &bitpos, pack_axis(raw_lg[0]));
        imu_pack12(payload, &bitpos, pack_axis(raw_lg[1]));
        imu_pack12(payload, &bitpos, pack_axis(raw_lg[2]));
        break;
    case STREAM_MODE_HIGH_ACC:
        imu_pack12(payload, &bitpos, pack_axis(raw_hg[0]));
        imu_pack12(payload, &bitpos, pack_axis(raw_hg[1]));
        imu_pack12(payload, &bitpos, pack_axis(raw_hg[2]));
        break;
    case STREAM_MODE_ONLY_GYRO:
        imu_pack12(payload, &bitpos, pack_axis(raw_gy[0]));
        imu_pack12(payload, &bitpos, pack_axis(raw_gy[1]));
        imu_pack12(payload, &bitpos, pack_axis(raw_gy[2]));
        break;
    case STREAM_MODE_BOTH_ACC:
        for (int a = 0; a < 3; a++) imu_pack12(payload, &bitpos, pack_axis(raw_lg[a]));
        for (int a = 0; a < 3; a++) imu_pack12(payload, &bitpos, pack_axis(raw_hg[a]));
        break;
    case STREAM_MODE_ALL:
    default:
        for (int a = 0; a < 3; a++) imu_pack12(payload, &bitpos, pack_axis(raw_lg[a]));
        for (int a = 0; a < 3; a++) imu_pack12(payload, &bitpos, pack_axis(raw_hg[a]));
        for (int a = 0; a < 3; a++) imu_pack12(payload, &bitpos, pack_axis(raw_gy[a]));
        break;
    }

    s_batch_bitpos = bitpos;
    s_batch_sample_count++;

    int64_t now = esp_timer_get_time();
    /* Flush if batch capacity reached OR >= 30 ms elapsed (~33 fps visual refresh) */
    if (s_batch_sample_count >= max_samples || (now - s_last_batch_send_time >= 30000LL)) {
        imu_flush_batch();
    }
}


/* ── BLE: GAP event handler ───────────────────────────────────────────────── */
static void ble_start_advertising(void); /* forward declaration */

/* Runs on the NimBLE host queue, serialized with connection events. */
static void ble_connection_timeout(struct ble_npl_event *event)
{
    (void)event;
    if (ble_conn_handle != BLE_HS_CONN_HANDLE_NONE) {
        return;
    }
    ble_shutdown_requested = true;
    ble_gap_adv_stop();
    ESP_LOGW(TAG_BLE, "No BLE connection for %d ms; releasing POWER_HOLD.",
             BLE_CONNECT_TIMEOUT_MS);
    ESP_ERROR_CHECK(gpio_set_level(PIN_POWER_HOLD, 0));
}

static void ble_start_connection_timeout(void)
{
    int rc = ble_npl_callout_reset(&ble_shutdown_callout,
                                  ble_npl_time_ms_to_ticks32(BLE_CONNECT_TIMEOUT_MS));
    ESP_ERROR_CHECK(rc);
}

/* Based on ST read_fifo: continuous FIFO and uncompressed tagged entries.
 * Read one complete seven-byte entry per SPI transaction (platform limit 32). */
static float imu_fifo_rate(void)
{
    switch (current_stream_mode) {
    case STREAM_MODE_LOW_ACC: return g_xl_odr_hz;
    case STREAM_MODE_HIGH_ACC: return g_hg_odr_hz;
    case STREAM_MODE_ONLY_GYRO: return g_gy_odr_hz;
    default: return 0;
    }
}

static uint8_t imu_fifo_bdr(float hz)
{
    /* XL and gyro batch-rate encodings match in the ST driver. */
    static const float rates[] = {0, 1.875f, 7.5f, 15, 30, 60, 120, 240, 480, 960, 1920, 3840, 7680};
    for (uint8_t i = 1; i < sizeof(rates) / sizeof(rates[0]); i++) {
        if (hz == rates[i]) return i;
    }
    return 0;
}

static bool imu_fifo_sync(void)
{
    static bool last_running;
    static int64_t retry_after;
    bool running = !g_data_paused && ble_notify_enabled && ble_conn_handle != BLE_HS_CONN_HANDLE_NONE;
    if (s_fifo_generation == s_stream_generation && last_running == running) return true;
    if (esp_timer_get_time() < retry_after) return false;
    xSemaphoreTakeRecursive(spi_mutex, portMAX_DELAY);
    uint32_t generation = s_stream_generation;
    running = !g_data_paused && ble_notify_enabled && ble_conn_handle != BLE_HS_CONN_HANDLE_NONE;
    float rate = imu_fifo_rate();
    bool enable = running && rate > 0;
    lsm6dsv320x_fifo_status_t status = {0};
    int32_t rc = lsm6dsv320x_fifo_status_get(&dev_ctx, &status);
    rc |= lsm6dsv320x_fifo_mode_set(&dev_ctx, LSM6DSV320X_BYPASS_MODE);
    if (rc == 0) s_fifo_flushed += status.fifo_level;
    s_fifo_active = false;
    s_fifo_backlog = false;
    s_fifo_level = 0;
    imu_discard_pending();
    s_sensor_generation = generation;
    uint8_t bdr = imu_fifo_bdr(rate);
    rc |= lsm6dsv320x_fifo_xl_batch_set(&dev_ctx,
        (lsm6dsv320x_fifo_xl_batch_t)(enable && current_stream_mode == STREAM_MODE_LOW_ACC ? bdr : 0));
    rc |= lsm6dsv320x_fifo_gy_batch_set(&dev_ctx,
        (lsm6dsv320x_fifo_gy_batch_t)(enable && current_stream_mode == STREAM_MODE_ONLY_GYRO ? bdr : 0));
    rc |= lsm6dsv320x_fifo_hg_xl_batch_set(&dev_ctx, enable && current_stream_mode == STREAM_MODE_HIGH_ACC);
    rc |= lsm6dsv320x_fifo_compress_algo_set(&dev_ctx, LSM6DSV320X_CMP_DISABLE);
    rc |= lsm6dsv320x_fifo_timestamp_batch_set(&dev_ctx, LSM6DSV320X_TMSTMP_NOT_BATCHED);
    s_fifo_watermark = rate >= 480 ? 16 : 1;
    rc |= lsm6dsv320x_fifo_watermark_set(&dev_ctx, s_fifo_watermark);
    if (rc == 0 && enable) rc = lsm6dsv320x_fifo_mode_set(&dev_ctx, LSM6DSV320X_STREAM_MODE);
    s_fifo_active = rc == 0 && enable;
    rc |= update_int_routing();
    if (rc != 0) s_fifo_active = false;
    ulTaskNotifyTake(pdTRUE, 0);
    if (rc == 0) {
        s_fifo_generation = generation;
        last_running = running;
        retry_after = 0;
        ESP_LOGI("FIFO", "mode=%d active=%u rate=%.2f watermark=%u flushed=%lu",
                 current_stream_mode, s_fifo_active, rate, s_fifo_watermark, (unsigned long)s_fifo_flushed);
    } else {
        s_stats.read_errors++;
        retry_after = esp_timer_get_time() + 1000000;
        ESP_LOGE("FIFO", "Configuration failed rc=%ld; retry in 1 second", (long)rc);
    }
    xSemaphoreGiveRecursive(spi_mutex);
    return rc == 0;
}

static void imu_fifo_drain(void)
{
    bool profile = s_diagnostics_enabled;
    uint32_t start = profile ? esp_cpu_get_cycle_count() : 0;
    xSemaphoreTakeRecursive(spi_mutex, portMAX_DELAY);
    if (profile) perf_add(&s_perf.lock, start);
    if (!s_fifo_active || s_fifo_generation != s_stream_generation || g_data_paused || !ble_notify_enabled) {
        xSemaphoreGiveRecursive(spi_mutex);
        return;
    }
    /* Include the latched overrun bit which ST's status getter omits. Reading
     * both status bytes together also gives the level from this observation. */
    uint8_t status[2] = {0};
    int32_t rc = lsm6dsv320x_read_reg(&dev_ctx, LSM6DSV320X_FIFO_STATUS1, status, 2);
    if (rc != 0) {
        s_stats.read_errors++;
        s_fifo_backlog = false;
        xSemaphoreGiveRecursive(spi_mutex);
        return;
    }
    s_fifo_level = (uint16_t)status[0] | (uint16_t)(status[1] & 1u) << 8;
    if (s_fifo_level > s_fifo_peak) s_fifo_peak = s_fifo_level;
    if (status[1] & 0x48u) s_fifo_overruns++; /* Current or latched overrun; not a loss count. */
    /* Bound mutex ownership; return to transport/watchdog between chunks. */
    uint16_t count = s_fifo_level > 32 ? 32 : s_fifo_level;
    s_fifo_backlog = s_fifo_level > count;
    for (uint16_t i = 0; i < count; i++) {
        lsm6dsv320x_fifo_out_raw_t entry = {0};
        if (profile) start = esp_cpu_get_cycle_count();
        rc = lsm6dsv320x_fifo_out_raw_get(&dev_ctx, &entry);
        if (profile) perf_add(&s_perf.read, start);
        if (rc != 0) {
            s_stats.read_errors++;
            /* Partial read can leave entry pointer uncertain: flush before reuse. */
            s_fifo_generation = s_stream_generation - 1;
            s_fifo_backlog = false;
            break;
        }
        s_fifo_level--;
        uint8_t expected = current_stream_mode == STREAM_MODE_LOW_ACC ? LSM6DSV320X_XL_NC_TAG :
            current_stream_mode == STREAM_MODE_HIGH_ACC ? LSM6DSV320X_XL_HG_TAG : LSM6DSV320X_GY_NC_TAG;
        if (entry.tag != expected) { s_fifo_unexpected++; continue; }
        if (s_fifo_generation != s_stream_generation) break;
        int16_t raw[3];
        for (unsigned a = 0; a < 3; a++) raw[a] = (int16_t)((uint16_t)entry.data[2*a] | (uint16_t)entry.data[2*a+1] << 8);
        s_stats.acquired++;
        if (profile) start = esp_cpu_get_cycle_count();
        imu_accumulate_sample(raw, raw, raw); /* Single-sensor mode selects one. */
        if (profile) perf_add(&s_perf.pack, start);
    }
    xSemaphoreGiveRecursive(spi_mutex);
    imu_drain_tx();
}

static int gap_event_handler(struct ble_gap_event *event, void *arg)
{
    switch (event->type) {

    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            s_stream_generation++;
            s_diagnostics_enabled = false;
            memset(s_diagnostics_snapshot, 0, sizeof(s_diagnostics_snapshot));
            s_link_interval = 0;
            s_link_tx_phy = s_link_rx_phy = 0;
            s_link_tx_octets = 0;
            ble_npl_callout_stop(&ble_shutdown_callout);
            ble_conn_handle = event->connect.conn_handle;
            s_negotiated_mtu = BLE_ATT_MTU_DEFAULT;
            ESP_LOGI(TAG_BLE, "Connected  (conn_handle=%d)", ble_conn_handle);
            /* Request a large MTU and the fastest practical LE link. The phone
             * may reject any preference; streaming continues with negotiated values. */
            ble_gattc_exchange_mtu(ble_conn_handle, NULL, NULL);
            struct ble_gap_upd_params params = {
                .itvl_min = 6,              /* 7.5 ms */
                .itvl_max = 12,             /* 15 ms */
                .latency = 0,
                .supervision_timeout = 400, /* 4 s */
                .min_ce_len = 0,
                .max_ce_len = 0,
            };
            int rc = ble_gap_update_params(ble_conn_handle, &params);
            if (rc != 0) ESP_LOGW(TAG_BLE, "conn-parameter update request failed: %d", rc);
            rc = ble_gap_set_data_len(ble_conn_handle, 251, 2120);
            if (rc != 0) ESP_LOGW(TAG_BLE, "data-length request failed: %d", rc);
            rc = ble_gap_set_prefered_le_phy(ble_conn_handle,
                                               BLE_GAP_LE_PHY_2M_MASK,
                                               BLE_GAP_LE_PHY_2M_MASK, 0);
            if (rc != 0) ESP_LOGW(TAG_BLE, "2M PHY request failed: %d", rc);
            xEventGroupSetBits(ble_event_group, BLE_CONNECTED_BIT);
            status_led_update_streaming();
        } else {
            ESP_LOGW(TAG_BLE, "Connect failed (status=%d) — restarting advertising",
                     event->connect.status);
            ble_conn_handle    = BLE_HS_CONN_HANDLE_NONE;
            ble_notify_enabled = false;
            s_negotiated_mtu   = BLE_ATT_MTU_DEFAULT;
            xEventGroupClearBits(ble_event_group, BLE_CONNECTED_BIT);
            status_led_update_streaming();
            ble_start_advertising();
        }
        break;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG_BLE, "Disconnected (reason=%d) — restarting advertising",
                 event->disconnect.reason);
        ble_conn_handle    = BLE_HS_CONN_HANDLE_NONE;
        ble_notify_enabled = false;
        s_negotiated_mtu   = BLE_ATT_MTU_DEFAULT;
        s_stream_generation++;
        s_diagnostics_enabled = false;
        xEventGroupClearBits(ble_event_group, BLE_CONNECTED_BIT);
        status_led_update_streaming();
        ble_start_connection_timeout();
        ble_start_advertising();
        break;

    case BLE_GAP_EVENT_SUBSCRIBE:
        if (event->subscribe.attr_handle == imu_chr_val_handle) {
            s_stream_generation++;
            ble_notify_enabled = (event->subscribe.cur_notify != 0);
            status_led_update_streaming();
            ESP_LOGI(TAG_BLE, "Notifications %s",
                     ble_notify_enabled ? "ENABLED — streaming IMU data" : "DISABLED");
        }
        break;

    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG_BLE, "MTU negotiated: %d bytes", event->mtu.value);
        s_stream_generation++;
        s_negotiated_mtu = event->mtu.value;
        break;

    case BLE_GAP_EVENT_CONN_UPDATE: {
        struct ble_gap_conn_desc desc;
        if (ble_gap_conn_find(ble_conn_handle, &desc) == 0) {
            s_link_interval = desc.conn_itvl;
            ESP_LOGI(TAG_BLE, "Link interval=%.2f ms latency=%u",
                     desc.conn_itvl * 1.25, desc.conn_latency);
        }
        break;
    }
    case BLE_GAP_EVENT_PHY_UPDATE_COMPLETE:
        if (event->phy_updated.status == 0) {
            s_link_tx_phy = event->phy_updated.tx_phy;
            s_link_rx_phy = event->phy_updated.rx_phy;
            ESP_LOGI(TAG_BLE, "Link PHY tx=%u rx=%u (1=1M, 2=2M)",
                     s_link_tx_phy, s_link_rx_phy);
        }
        break;
    case BLE_GAP_EVENT_DATA_LEN_CHG:
        s_link_tx_octets = event->data_len_chg.max_tx_octets;
        ESP_LOGI(TAG_BLE, "Link data length tx=%u rx=%u",
                 s_link_tx_octets, event->data_len_chg.max_rx_octets);
        break;

    default:
        break;
    }
    return 0;
}

/* ── BLE: start advertising ───────────────────────────────────────────────── */
static void ble_start_advertising(void)
{
    if (ble_shutdown_requested) {
        return;
    }
    struct ble_hs_adv_fields fields = {0};
    fields.flags            = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name             = (uint8_t *)DEVICE_NAME;
    fields.name_len         = strlen(DEVICE_NAME);
    fields.name_is_complete = 1;
    fields.uuids128         = &nus_svc_uuid;
    fields.num_uuids128     = 1;
    fields.uuids128_is_complete = 1;

    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGE(TAG_BLE, "adv_set_fields error: %d", rc);
        return;
    }

    struct ble_gap_adv_params adv_params = {0};
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;      /* connectable */
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;      /* general discoverable */
    adv_params.itvl_min  = BLE_GAP_ADV_ITVL_MS(100);
    adv_params.itvl_max  = BLE_GAP_ADV_ITVL_MS(150);

    rc = ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER,
                           &adv_params, gap_event_handler, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG_BLE, "adv_start error: %d", rc);
    } else {
        ESP_LOGI(TAG_BLE, "Advertising as \"%s\"", DEVICE_NAME);
    }
}

/* ── BLE: host callbacks ──────────────────────────────────────────────────── */
static void ble_on_sync(void)
{
    int rc;

    /* Ensure a valid identity address exists (public preferred, else random) */
    rc = ble_hs_util_ensure_addr(0);
    if (rc != 0) {
        ESP_LOGE(TAG_BLE, "ensure_addr failed: %d", rc);
        return;
    }

    /* Infer the correct own-address type to use in advertising */
    rc = ble_hs_id_infer_auto(0, &own_addr_type);
    if (rc != 0) {
        ESP_LOGE(TAG_BLE, "infer_auto addr type failed: %d", rc);
        return;
    }

    ESP_LOGI(TAG_BLE, "NimBLE host synced (addr_type=%d) — starting advertising.", own_addr_type);
    ble_start_advertising();
}

static void ble_on_reset(int reason)
{
    ESP_LOGE(TAG_BLE, "NimBLE host reset (reason=%d)", reason);
    if (ble_conn_handle != BLE_HS_CONN_HANDLE_NONE) {
        s_stream_generation++;
        ble_conn_handle = BLE_HS_CONN_HANDLE_NONE;
        ble_notify_enabled = false;
        xEventGroupClearBits(ble_event_group, BLE_CONNECTED_BIT);
        status_led_update_streaming();
        ble_start_connection_timeout();
    }
}

/* ── BLE: NimBLE host FreeRTOS task ──────────────────────────────────────── */
static void nimble_host_task(void *param)
{
    ESP_LOGI(TAG_BLE, "NimBLE host task started.");
    nimble_port_run();              /* blocks here until nimble_port_stop() */
    nimble_port_freertos_deinit();
}

/* ── BLE: full stack initialisation ──────────────────────────────────────── */
/* Diagnostic v1: 72 little-endian bytes, separate from AA55 sample packets. */
static void diag_u32(uint8_t *p, uint32_t v)
{
    p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24;
}
static void diag_u16(uint8_t *p, uint16_t v) { p[0] = v; p[1] = v >> 8; }

static void imu_publish_diagnostics(int64_t now_us)
{
    static int64_t last_snapshot_us;
    static uint32_t last_irq, last_acquired, last_coalesced, generation;
    static bool was_enabled;
    bool enabled = s_diagnostics_enabled;
    if (!enabled && !was_enabled) return;
    if (!enabled || !was_enabled || generation != s_stream_generation) {
        memset(&s_perf, 0, sizeof(s_perf));
        last_snapshot_us = now_us;
        last_irq = s_irq_count;
        last_acquired = s_stats.acquired;
        last_coalesced = s_stats.coalesced;
        generation = s_stream_generation;
        was_enabled = enabled;
        vTaskSuspendAll();
        memset(&s_perf_snapshot, 0, sizeof(s_perf_snapshot));
        xTaskResumeAll();
        return;
    }
    if (now_us - last_snapshot_us < 1000000LL) return;
    uint32_t irq = s_irq_count;
    s_perf.window_us = (uint32_t)(now_us - last_snapshot_us);
    s_perf.irq = irq - last_irq;
    s_perf.acquired = s_stats.acquired - last_acquired;
    s_perf.coalesced = s_stats.coalesced - last_coalesced;
    last_snapshot_us = now_us;
    last_irq = irq;
    last_acquired = s_stats.acquired;
    last_coalesced = s_stats.coalesced;
    uint8_t data[72] = {0xaa, 0x56, 1, current_stream_mode};
    diag_u32(data + 4, (uint32_t)(now_us / 1000));
    diag_u32(data + 8, s_stats.acquired);
    diag_u32(data + 12, s_stats.submitted);
    diag_u32(data + 16, s_stats.overflow);
    diag_u32(data + 20, s_stats.discarded);
    diag_u32(data + 24, s_stats.coalesced);
    diag_u32(data + 28, s_stats.timeouts);
    diag_u32(data + 32, s_stats.read_errors);
    diag_u32(data + 36, s_stats.retries);
    diag_u32(data + 40, s_stats.notify_errors);
    diag_u32(data + 44, s_stats.bytes);
    diag_u32(data + 48, s_stats.packets);
    diag_u32(data + 52, s_tx_queue.samples + s_batch_sample_count);
    diag_u16(data + 56, s_tx_queue.count);
    diag_u16(data + 58, s_tx_queue.peak);
    data[71] = g_data_paused ? 1 : 0;
    /* Publish only complete sensor-task operations, never an intermediate
     * enqueue/pop, so acquisition and transport counters can be compared. */
    vTaskSuspendAll();
    memcpy(s_diagnostics_snapshot, data, sizeof(data));
    s_perf_snapshot = s_perf;
    xTaskResumeAll();
    memset(&s_perf, 0, sizeof(s_perf));
}

static double perf_avg_us(perf_stage_t stage)
{
    return stage.count ? (double)stage.cycles / stage.count / CONFIG_ESP_DEFAULT_CPU_FREQ_MHZ : 0;
}
static double perf_max_us(perf_stage_t stage)
{
    return (double)stage.max_cycles / CONFIG_ESP_DEFAULT_CPU_FREQ_MHZ;
}
static uint32_t diag_read_u32(const uint8_t *p)
{
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}

static void ble_diagnostics_tick(struct ble_npl_event *event)
{
    (void)event;
    static uint32_t last_overflow, last_errors, last_fifo_overruns;
    if (s_fifo_overruns != last_fifo_overruns) {
        ESP_LOGW("FIFO", "Hardware overrun observations=%lu; lost sample count unknown",
                 (unsigned long)s_fifo_overruns);
        last_fifo_overruns = s_fifo_overruns;
    }
    if (s_stats.overflow != last_overflow || s_stats.notify_errors != last_errors) {
        ESP_LOGW("STREAM", "Queue overflow dropped=%lu samples, permanent notify errors=%lu (since boot)",
                 (unsigned long)s_stats.overflow, (unsigned long)s_stats.notify_errors);
        last_overflow = s_stats.overflow;
        last_errors = s_stats.notify_errors;
    }
    if (s_diagnostics_enabled && ble_notify_enabled && ble_conn_handle != BLE_HS_CONN_HANDLE_NONE) {
        int8_t rssi = 127;
        ble_gap_conn_rssi(ble_conn_handle, &rssi);
        struct ble_gap_conn_desc desc;
        if (ble_gap_conn_find(ble_conn_handle, &desc) == 0) s_link_interval = desc.conn_itvl;
        if (!s_link_tx_phy) ble_gap_read_le_phy(ble_conn_handle, &s_link_tx_phy, &s_link_rx_phy);
        uint8_t data[72];
        imu_perf_t perf;
        vTaskSuspendAll();
        memcpy(data, s_diagnostics_snapshot, sizeof(data));
        perf = s_perf_snapshot;
        xTaskResumeAll();
        diag_u16(data + 60, os_msys_num_free());
        diag_u16(data + 62, s_negotiated_mtu);
        diag_u16(data + 64, s_link_interval);
        diag_u16(data + 66, s_link_tx_octets);
        data[68] = (uint8_t)rssi;
        data[69] = s_link_tx_phy;
        data[70] = s_link_rx_phy;
        /* Best effort: never queue diagnostics ahead of sensor data. */
        if (data[0] == 0xaa && imu_get_max_packet_bytes() >= sizeof(data)) {
            struct os_mbuf *om = ble_hs_mbuf_from_flat(data, sizeof(data));
            if (om) ble_gatts_notify_custom(ble_conn_handle, imu_chr_val_handle, om);
        }
        snprintf(latest_ble_str, sizeof(latest_ble_str),
                 "acquired=%lu submitted=%lu overflow=%lu coalesced=%lu retries=%lu pending=%lu",
                 (unsigned long)diag_read_u32(data + 8), (unsigned long)diag_read_u32(data + 12),
                 (unsigned long)diag_read_u32(data + 16), (unsigned long)diag_read_u32(data + 24),
                 (unsigned long)diag_read_u32(data + 36), (unsigned long)diag_read_u32(data + 52));
        ESP_LOGI("STREAM", "%s RSSI=%d MTU=%u interval=%.2fms PHY=%u/%u DLE=%u",
                 latest_ble_str, rssi, s_negotiated_mtu, s_link_interval * 1.25,
                 s_link_tx_phy, s_link_rx_phy, s_link_tx_octets);
        ESP_LOGI("FIFO", "active=%u level=%u peak=%u overrun_observations=%lu flushed=%lu unexpected_tags=%lu",
                 s_fifo_active, s_fifo_level, s_fifo_peak, (unsigned long)s_fifo_overruns,
                 (unsigned long)s_fifo_flushed, (unsigned long)s_fifo_unexpected);
        if (perf.window_us) {
            double scale = 1000000.0 / perf.window_us;
            ESP_LOGI("PERF", "window_ms=%lu irq_hz=%.1f acquired_hz=%.1f coalesced_hz=%.1f discarded=%lu read_errors=%lu",
                     (unsigned long)(perf.window_us / 1000), perf.irq * scale,
                     perf.acquired * scale, perf.coalesced * scale,
                     (unsigned long)diag_read_u32(data + 20), (unsigned long)diag_read_u32(data + 32));
            ESP_LOGI("PERF", "us avg/max: lock=%.2f/%.2f read=%.2f/%.2f pack=%.2f/%.2f notify=%.2f/%.2f attempts=%lu",
                     perf_avg_us(perf.lock), perf_max_us(perf.lock),
                     perf_avg_us(perf.read), perf_max_us(perf.read),
                     perf_avg_us(perf.pack), perf_max_us(perf.pack),
                     perf_avg_us(perf.notify), perf_max_us(perf.notify),
                     (unsigned long)perf.notify.count);
        }
    }
    ble_npl_callout_reset(&s_diagnostics_callout, ble_npl_time_ms_to_ticks32(2000));
}

static void ble_stack_init(void)
{
    /* Suppress verbose NimBLE procedure logs (e.g. notify procedure initiation on every packet) */
    esp_log_level_set("NimBLE", ESP_LOG_WARN);

    /* Create event group for BLE connection state */
    ble_event_group = xEventGroupCreate();
    assert(ble_event_group != NULL);

    /* NVS is required by the BLE controller */
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    /* Initialise NimBLE port */
    ESP_ERROR_CHECK(nimble_port_init());

    /* Host configuration */
    ble_hs_cfg.sync_cb  = ble_on_sync;
    ble_hs_cfg.reset_cb = ble_on_reset;

    /* GAP device name */
    ble_svc_gap_device_name_set(DEVICE_NAME);

    /* Register standard GAP + GATT services, then our custom IMU service */
    ble_svc_gap_init();
    ble_svc_gatt_init();
    ESP_ERROR_CHECK(ble_gatts_count_cfg(gatt_svcs));
    ESP_ERROR_CHECK(ble_gatts_add_svcs(gatt_svcs));

    /* Launch the NimBLE host task (handles all BLE events internally) */
    ble_npl_callout_init(&ble_shutdown_callout, nimble_port_get_dflt_eventq(),
                         ble_connection_timeout, NULL);
    ble_start_connection_timeout();
    ble_npl_callout_init(&s_diagnostics_callout, nimble_port_get_dflt_eventq(),
                         ble_diagnostics_tick, NULL);
    ble_npl_callout_reset(&s_diagnostics_callout, ble_npl_time_ms_to_ticks32(2000));
    nimble_port_freertos_init(nimble_host_task);
}

/* ── Sensor task ──────────────────────────────────────────────────────────── */
#endif /* USE_BLE */

static void sensor_task(void *arg)
{
    /* Store own handle so the ISR can send task notifications */
    sensor_task_handle = xTaskGetCurrentTaskHandle();

    /* This task is the watchdog user — it feeds the TWDT every loop iteration.
     * (The idle task was unsubscribed in app_main because high-ODR streaming
     * starves it on this single-core chip.) */
    ESP_ERROR_CHECK(esp_task_wdt_add(NULL));

    /* ── Hardware init ──────────────────────────────────────────────────── */
    spi_init();
    cs_gpio_init();
    int1_gpio_init();

    /* ── ST driver context (global — shared with BLE command handler) ──── */
    dev_ctx.write_reg = platform_write;
    dev_ctx.read_reg  = platform_read;
    dev_ctx.mdelay    = platform_delay;
    dev_ctx.handle    = NULL;

    platform_delay(10);

    /* Set 4-wire SPI mode */
    lsm6dsv320x_spi_mode_set(&dev_ctx, LSM6DSV320X_SPI_4_WIRE);
    platform_delay(10);

    /* ── WHO_AM_I check ─────────────────────────────────────────────────── */
    uint8_t whoamI = 0;
    lsm6dsv320x_device_id_get(&dev_ctx, &whoamI);

    if (whoamI != LSM6DSV320X_ID) {
        ESP_LOGE(TAG, "WHO_AM_I mismatch: got 0x%02X, expected 0x%02X — check SPI wiring.",
                 whoamI, LSM6DSV320X_ID);
        vTaskDelete(NULL);
        return;
    }
    ESP_LOGI(TAG, "LSM6DSV320X found! WHO_AM_I = 0x%02X", whoamI);

    /* ── Sensor configuration ───────────────────────────────────────────── */
    lsm6dsv320x_sw_por(&dev_ctx);
    platform_delay(15);

    s_freq_fine_valid = lsm6dsv320x_odr_cal_reg_get(&dev_ctx, &s_freq_fine) == 0;
    if (s_freq_fine_valid) {
        ESP_LOGI("ODR", "Factory FREQ_FINE=%d correction=%+.2f%%; normal-mode 7680 estimate=%.2f Hz",
                 s_freq_fine, s_freq_fine * 0.13, 7680.0 * (1.0 + 0.0013 * s_freq_fine));
    } else {
        ESP_LOGW("ODR", "Cannot read factory frequency correction");
    }
    lsm6dsv320x_block_data_update_set(&dev_ctx, PROPERTY_ENABLE);

    /* Set Pulsed mode on DRDY pin so it pulses instead of latching indefinitely */
    lsm6dsv320x_data_ready_mode_set(&dev_ctx, LSM6DSV320X_DRDY_PULSED);

    /* 1. Low-G Accelerometer: 15 Hz, High-Performance, ±2g */
    lsm6dsv320x_xl_setup(&dev_ctx, LSM6DSV320X_ODR_AT_15Hz, LSM6DSV320X_XL_HIGH_PERFORMANCE_MD);
    lsm6dsv320x_xl_full_scale_set(&dev_ctx, LSM6DSV320X_2g);

    /* 2. Gyroscope: 15 Hz, High-Performance, ±2000dps */
    lsm6dsv320x_gy_setup(&dev_ctx, LSM6DSV320X_ODR_AT_15Hz, LSM6DSV320X_GY_HIGH_PERFORMANCE_MD);
    lsm6dsv320x_gy_full_scale_set(&dev_ctx, LSM6DSV320X_2000dps);

    /* 3. High-G Accelerometer: 480 Hz ODR, enable output registers, ±320g */
    lsm6dsv320x_hg_xl_full_scale_set(&dev_ctx, LSM6DSV320X_320g);
    lsm6dsv320x_hg_xl_data_rate_set(&dev_ctx, LSM6DSV320X_HG_XL_ODR_AT_480Hz, 1);

    /* Initialise timing and route DRDY to INT1 (GPIO10) based on current stream mode */
    odr_recompute_timing();
    sensor_ready = true;

#if USE_BLE
    ESP_LOGI(TAG, "Sensor ready (Low-G + High-G + Gyro). Waiting for BLE connection...");
#else
    ESP_LOGI(TAG, "Sensor ready. BLE disabled; printing ALL readings once per second.");
#endif

    /* ── Main loop ──────────────────────────────────────────────────────── */
    int16_t data_raw_acceleration[3];
    int16_t data_raw_hg_acceleration[3];
    int16_t data_raw_angular_rate[3];
#if !USE_BLE
    int64_t last_console_sample_us = esp_timer_get_time();
#endif

    while (1) {
        /* Feed the task watchdog, but throttle to every 20 ms. A per-iteration
         * reset runs a critical section each sample, which is measurable CPU
         * overhead in the 7680 Hz hot loop (timeout is 5 s, so 20 ms is ample). */
        static int64_t s_last_wdt_feed_us = 0;
        int64_t loop_now_us = esp_timer_get_time();
        if (loop_now_us - s_last_wdt_feed_us >= 20000LL) {
            esp_task_wdt_reset();
            s_last_wdt_feed_us = loop_now_us;
        }

        /* ── Gate: pause until a BLE central is connected ─────────────── */
#if USE_BLE
        if (!imu_fifo_sync()) { vTaskDelay(1); continue; }
        imu_publish_diagnostics(loop_now_us);
        imu_drain_tx();
        if (!(xEventGroupGetBits(ble_event_group) & BLE_CONNECTED_BIT)) {
            static bool disconn_logged = false;
            if (!disconn_logged) {
                ESP_LOGI(TAG, "No BLE connection — sensor paused. Connect via nRF Connect.");
                disconn_logged = true;
            }
            /* Wait with a finite timeout so we keep feeding the watchdog */
            if (xEventGroupWaitBits(ble_event_group, BLE_CONNECTED_BIT,
                                    pdFALSE,    /* do NOT clear bit on exit */
                                    pdTRUE,     /* wait for ALL bits (only one here) */
                                    pdMS_TO_TICKS(1000)) == 0) {
                continue;   /* still disconnected — loop and feed the watchdog again */
            }
            disconn_logged = false;
            /* Perform a read to clear any pending/latched data on the sensor */
            lsm6dsv320x_acceleration_raw_get(&dev_ctx, data_raw_acceleration);
            lsm6dsv320x_hg_acceleration_raw_get(&dev_ctx, data_raw_hg_acceleration);
            lsm6dsv320x_angular_rate_raw_get(&dev_ctx, data_raw_angular_rate);
            ulTaskNotifyTake(pdTRUE, 0);
            ESP_LOGI(TAG, "BLE connected — starting sensor data stream.");
        }

        /* ── Gate: paused by command ──────────────────────────────────── */
        if (g_data_paused || !ble_notify_enabled) {
            imu_discard_pending();
            vTaskDelay(pdMS_TO_TICKS(50));
            /* Clear any accumulated notification so resuming starts cleanly */
            ulTaskNotifyTake(pdTRUE, 0);
            continue;
        }
#endif

        /* ── Wait for INT1 data-ready interrupt ───────────────────────── */
        /* Timeout = 5× the period of the slowest active sensor (dynamic) */
        TickType_t wait_ticks = pdMS_TO_TICKS(g_drdy_timeout_ms);
#if USE_BLE
        bool retry_wait = s_tx_queue.count > 0;
        if (retry_wait || s_fifo_active) wait_ticks = 1;
        if (s_fifo_active && s_fifo_backlog) wait_ticks = 0;
#endif
        uint32_t ready_count = ulTaskNotifyTake(pdTRUE, wait_ticks);
#if USE_BLE
        if (s_fifo_active) {
            /* Poll as well: an already-high watermark must not strand data. */
            imu_fifo_drain();
            continue;
        }
#endif
        if (!ready_count) {
#if USE_BLE
            if (!retry_wait) s_stats.timeouts++;
#endif
            continue; /* A timeout is not a new sensor sample. */
        }
#if USE_BLE
        bool profile = s_diagnostics_enabled;
        uint32_t stage_start = profile ? esp_cpu_get_cycle_count() : 0;
#endif
        xSemaphoreTakeRecursive(spi_mutex, portMAX_DELAY);
#if USE_BLE
        if (profile) perf_add(&s_perf.lock, stage_start);
        if (s_sensor_generation != s_stream_generation) {
            imu_discard_pending();
            s_sensor_generation = s_stream_generation;
            ulTaskNotifyTake(pdTRUE, 0);
            xSemaphoreGiveRecursive(spi_mutex);
            continue;
        }
        if (g_data_paused || !ble_notify_enabled) {
            xSemaphoreGiveRecursive(spi_mutex);
            continue;
        }
        s_stats.coalesced += ready_count - 1;
#endif
#if USE_BLE
        if (profile) stage_start = esp_cpu_get_cycle_count();
#endif
        int32_t read_rc = 0;
        if (current_stream_mode == STREAM_MODE_LOW_ACC || current_stream_mode == STREAM_MODE_BOTH_ACC || current_stream_mode == STREAM_MODE_ALL) {
            read_rc |= lsm6dsv320x_acceleration_raw_get(&dev_ctx, data_raw_acceleration);
        }
        if (current_stream_mode == STREAM_MODE_HIGH_ACC || current_stream_mode == STREAM_MODE_BOTH_ACC || current_stream_mode == STREAM_MODE_ALL) {
            read_rc |= lsm6dsv320x_hg_acceleration_raw_get(&dev_ctx, data_raw_hg_acceleration);
        }
        if (current_stream_mode == STREAM_MODE_ONLY_GYRO || current_stream_mode == STREAM_MODE_ALL) {
            read_rc |= lsm6dsv320x_angular_rate_raw_get(&dev_ctx, data_raw_angular_rate);
        }
#if USE_BLE
        if (profile) perf_add(&s_perf.read, stage_start);
#endif
        if (read_rc != 0) {
#if USE_BLE
            s_stats.read_errors++;
#endif
            xSemaphoreGiveRecursive(spi_mutex);
            continue;
        }
#if USE_BLE
        s_stats.acquired++;
        if (profile) stage_start = esp_cpu_get_cycle_count();
        imu_accumulate_sample(data_raw_acceleration, data_raw_hg_acceleration, data_raw_angular_rate);
        if (profile) perf_add(&s_perf.pack, stage_start);
        xSemaphoreGiveRecursive(spi_mutex);
        /* Never call NimBLE with the SPI/configuration mutex held. */
        imu_drain_tx();
#else
        xSemaphoreGiveRecursive(spi_mutex);
        /* ALL mode is the default without BLE commands. Keep sampling at the
         * configured sensor rates and print one snapshot per second. */
        int64_t now = esp_timer_get_time();
        if (now - last_console_sample_us >= 1000000LL) {
            last_console_sample_us = now;
            /* Conversion scales match the fixed sensor setup above. */
            ESP_LOGI(TAG,
                     "ALL Low-G [mg] X=%.2f Y=%.2f Z=%.2f | "
                     "High-G [mg] X=%.2f Y=%.2f Z=%.2f | "
                     "Gyro [mdps] X=%.2f Y=%.2f Z=%.2f",
                     lsm6dsv320x_from_fs2_to_mg(data_raw_acceleration[0]),
                     lsm6dsv320x_from_fs2_to_mg(data_raw_acceleration[1]),
                     lsm6dsv320x_from_fs2_to_mg(data_raw_acceleration[2]),
                     lsm6dsv320x_from_fs320_to_mg(data_raw_hg_acceleration[0]),
                     lsm6dsv320x_from_fs320_to_mg(data_raw_hg_acceleration[1]),
                     lsm6dsv320x_from_fs320_to_mg(data_raw_hg_acceleration[2]),
                     lsm6dsv320x_from_fs2000_to_mdps(data_raw_angular_rate[0]),
                     lsm6dsv320x_from_fs2000_to_mdps(data_raw_angular_rate[1]),
                     lsm6dsv320x_from_fs2000_to_mdps(data_raw_angular_rate[2]));
        }
#endif
    }
}

/* ── Entry point ──────────────────────────────────────────────────────────── */
static int64_t status_led_start_us;
static esp_timer_handle_t led_timer;
static SemaphoreHandle_t status_led_mutex;
static bool status_led_streaming;

#if USE_BLE
/* Called only on BLE state changes, never from the per-sample send path. */
static void status_led_update_streaming(void)
{
    bool streaming = ble_conn_handle != BLE_HS_CONN_HANDLE_NONE &&
                     ble_notify_enabled && !g_data_paused;
    xSemaphoreTake(status_led_mutex, portMAX_DELAY);
    if (streaming != status_led_streaming) {
        status_led_streaming = streaming;
        if (streaming) {
            ESP_ERROR_CHECK(esp_timer_stop(led_timer));
            /* 100% duty at 12-bit resolution: inverted output stays LOW. */
            ESP_ERROR_CHECK(ledc_set_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0, 4096));
            ESP_ERROR_CHECK(ledc_update_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0));
        } else {
            status_led_start_us = esp_timer_get_time();
            ESP_ERROR_CHECK(esp_timer_start_periodic(led_timer, 10000));
        }
    }
    xSemaphoreGive(status_led_mutex);
}
#endif

static void status_led_tick(void *arg)
{
    (void)arg;
    xSemaphoreTake(status_led_mutex, portMAX_DELAY);
    /* A callback already queued when the timer stopped must not dim the LED. */
    if (status_led_streaming) {
        xSemaphoreGive(status_led_mutex);
        return;
    }
    /* A cosine envelope gives gentle turnarounds; square it for a more
     * even perceived brightness change. Use elapsed time to avoid drift. */
    const int64_t phase_us = (esp_timer_get_time() - status_led_start_us) % 2000000;
    const float phase = (float)phase_us / 2000000.0f;
    const float brightness = 0.5f - 0.5f * cosf(6.28318530718f * phase);
    const uint32_t duty = (uint32_t)(4095.0f * brightness * brightness + 0.5f);
    /* Serialize duty writes with BLE state changes. */
    ESP_ERROR_CHECK(ledc_set_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0, duty));
    ESP_ERROR_CHECK(ledc_update_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0));
    xSemaphoreGive(status_led_mutex);
}

static void board_power_and_led_init(void)
{
    /* Preload HIGH before enabling output; the bootloader already holds it. */
    ESP_ERROR_CHECK(gpio_set_level(PIN_POWER_HOLD, 1));
    const gpio_config_t power_cfg = {
        .pin_bit_mask = 1ULL << PIN_POWER_HOLD,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    ESP_ERROR_CHECK(gpio_config(&power_cfg));

    /* Keep the active-low LED steadily on during the USB startup window. */
    ESP_ERROR_CHECK(gpio_set_level(PIN_STATUS_LED, 0));
    const gpio_config_t led_cfg = {
        .pin_bit_mask = 1ULL << PIN_STATUS_LED,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    ESP_ERROR_CHECK(gpio_config(&led_cfg));
}

static void status_led_start_breathing(void)
{
    status_led_mutex = xSemaphoreCreateMutex();
    assert(status_led_mutex != NULL);
    /* 5 kHz hardware PWM; inverted output because the LED cathode is driven. */
    const ledc_timer_config_t pwm_timer = {
        .speed_mode = LEDC_LOW_SPEED_MODE,
        .duty_resolution = LEDC_TIMER_12_BIT,
        .timer_num = LEDC_TIMER_0,
        .freq_hz = 5000,
        .clk_cfg = LEDC_AUTO_CLK,
    };
    ESP_ERROR_CHECK(ledc_timer_config(&pwm_timer));
    const ledc_channel_config_t pwm_channel = {
        .gpio_num = PIN_STATUS_LED,
        .speed_mode = LEDC_LOW_SPEED_MODE,
        .channel = LEDC_CHANNEL_0,
        .intr_type = LEDC_INTR_DISABLE,
        .timer_sel = LEDC_TIMER_0,
        .duty = 0,
        .hpoint = 0,
        .flags.output_invert = 1,
    };
    ESP_ERROR_CHECK(ledc_channel_config(&pwm_channel));
    /* Standby breathing: one second brighter, one second dimmer. */
    const esp_timer_create_args_t timer_args = {
        .callback = status_led_tick,
        .name = "status_led",
    };
    ESP_ERROR_CHECK(esp_timer_create(&timer_args, &led_timer));
    status_led_start_us = esp_timer_get_time();
    ESP_ERROR_CHECK(esp_timer_start_periodic(led_timer, 10000));
}

void app_main(void)
{
    board_power_and_led_init();
    /* Yield before starting BLE or the sensor task so USB can enumerate and
     * a monitor can attach before a possible task failure. */
    vTaskDelay(pdMS_TO_TICKS(3000));
    status_led_start_breathing();
    ESP_LOGI(TAG, "=== ESP_IMU starting ===");
#if USE_BLE
    esp_log_level_set("NimBLE", ESP_LOG_WARN);
#endif

    /* The sensor task streams continuously and, at high ODR, keeps the CPU so
     * busy that the IDLE task (the default Task-Watchdog user) never runs,
     * which triggers false watchdog timeouts. Stop watching the idle task and
     * monitor the sensor task itself instead (see sensor_task). */
    esp_task_wdt_config_t wdt_cfg = {
        .timeout_ms     = 5000,
        .idle_core_mask = 0,   /* don't watch the (intentionally starved) idle task */
        .trigger_panic  = false,
    };
    ESP_ERROR_CHECK(esp_task_wdt_reconfigure(&wdt_cfg));

    /* Start BLE stack first — it runs in its own NimBLE host task */
#if USE_BLE
    ble_stack_init();
#endif

    /* Start sensor task — handles SPI + INT1 + BLE notify */
    xTaskCreate(sensor_task, "sensor_task", 4096, NULL, 5, NULL);
}
