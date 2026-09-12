/**
 * @file    main.c
 * @brief   LSM6DSV320X IMU driver on ESP32-C3 via SPI (ESP-IDF)
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
 */

#include <stdio.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "driver/spi_master.h"
#include "driver/gpio.h"
#include "esp_log.h"

#include "lsm6dsv320x_reg.h"

/* ── Pin definitions ──────────────────────────────────────────────────────── */
#define PIN_SCLK    GPIO_NUM_4
#define PIN_CS      GPIO_NUM_5
#define PIN_MOSI    GPIO_NUM_6
#define PIN_MISO    GPIO_NUM_7
#define PIN_INT1    GPIO_NUM_10

#define SPI_HOST_ID SPI2_HOST   /* FSPI — the general-purpose SPI on C3 */
#define SPI_CLK_HZ  (8 * 1000 * 1000)  /* 8 MHz — well within sensor limit */

static const char *TAG = "LSM6DSV320X";

/* ── Global state ─────────────────────────────────────────────────────────── */
static spi_device_handle_t spi_handle;
static TaskHandle_t        sensor_task_handle = NULL;

/* ── INT1 ISR ─────────────────────────────────────────────────────────────── */
static void IRAM_ATTR int1_isr_handler(void *arg)
{
    BaseType_t higher_prio_woken = pdFALSE;
    vTaskNotifyGiveFromISR(sensor_task_handle, &higher_prio_woken);
    portYIELD_FROM_ISR(higher_prio_woken);
}

/* ── Platform layer ───────────────────────────────────────────────────────── */

/**
 * @brief  Write one or more registers via SPI (called by ST driver).
 *
 * Transaction: CS↓ → [reg addr] → [data bytes] → CS↑
 */
static int32_t platform_write(void *handle, uint8_t reg, const uint8_t *bufp, uint16_t len)
{
    (void)handle; /* spi_handle is a global */

    /* Assert CS */
    gpio_set_level(PIN_CS, 0);

    spi_transaction_t t = {0};

    /* Send register address */
    t.length    = 8;          /* bits */
    t.tx_buffer = &reg;
    t.flags     = 0;
    spi_device_transmit(spi_handle, &t);

    /* Send data payload */
    if (len > 0) {
        memset(&t, 0, sizeof(t));
        t.length    = len * 8;
        t.tx_buffer = bufp;
        spi_device_transmit(spi_handle, &t);
    }

    /* Deassert CS */
    gpio_set_level(PIN_CS, 1);
    return 0;
}

/**
 * @brief  Read one or more registers via SPI (called by ST driver).
 *
 * Transaction: CS↓ → [reg addr | 0x80] → [receive data bytes] → CS↑
 */
static int32_t platform_read(void *handle, uint8_t reg, uint8_t *bufp, uint16_t len)
{
    (void)handle;

    reg |= 0x80; /* set read bit */

    gpio_set_level(PIN_CS, 0);

    spi_transaction_t t = {0};

    /* Send register address */
    t.length    = 8;
    t.tx_buffer = &reg;
    t.flags     = 0;
    spi_device_transmit(spi_handle, &t);

    /* Receive data */
    if (len > 0) {
        memset(&t, 0, sizeof(t));
        t.length    = len * 8;
        t.rxlength  = len * 8;
        t.rx_buffer = bufp;
        spi_device_transmit(spi_handle, &t);
    }

    gpio_set_level(PIN_CS, 1);
    return 0;
}

/**
 * @brief  Millisecond delay shim (called by ST driver).
 */
static void platform_delay(uint32_t ms)
{
    vTaskDelay(pdMS_TO_TICKS(ms));
}

/* ── Hardware initialisation ──────────────────────────────────────────────── */

static void spi_init(void)
{
    /* Bus config — SCLK, MOSI, MISO (no hardware CS) */
    spi_bus_config_t bus_cfg = {
        .mosi_io_num     = PIN_MOSI,
        .miso_io_num     = PIN_MISO,
        .sclk_io_num     = PIN_SCLK,
        .quadwp_io_num   = -1,
        .quadhd_io_num   = -1,
        .max_transfer_sz = 64,
    };
    ESP_ERROR_CHECK(spi_bus_initialize(SPI_HOST_ID, &bus_cfg, SPI_DMA_CH_AUTO));

    /* Device config — SPI Mode 0 (CPOL=0, CPHA=0), software CS */
    spi_device_interface_config_t dev_cfg = {
        .clock_speed_hz = SPI_CLK_HZ,
        .mode           = 0,        /* CPOL=0, CPHA=0 — matches LSM6DSV320X default */
        .spics_io_num   = -1,       /* CS is controlled manually */
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
    gpio_set_level(PIN_CS, 1); /* deselect at startup */
}

static void int1_gpio_init(void)
{
    gpio_config_t io_conf = {
        .pin_bit_mask = (1ULL << PIN_INT1),
        .mode         = GPIO_MODE_INPUT,
        .pull_up_en   = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_ENABLE,  /* keep line low when idle */
        .intr_type    = GPIO_INTR_POSEDGE,     /* rising-edge triggered */
    };
    ESP_ERROR_CHECK(gpio_config(&io_conf));

    /* Install the ISR service and attach our handler */
    ESP_ERROR_CHECK(gpio_install_isr_service(0));
    ESP_ERROR_CHECK(gpio_isr_handler_add(PIN_INT1, int1_isr_handler, NULL));
}

/* ── Sensor task ──────────────────────────────────────────────────────────── */

static void sensor_task(void *arg)
{
    /* Store own handle so the ISR can notify us */
    sensor_task_handle = xTaskGetCurrentTaskHandle();

    /* ── Hardware init ──────────────────────────────────────────────────── */
    spi_init();
    cs_gpio_init();
    int1_gpio_init();

    /* ── ST driver context ──────────────────────────────────────────────── */
    stmdev_ctx_t dev_ctx = {
        .write_reg = platform_write,
        .read_reg  = platform_read,
        .mdelay    = platform_delay,
        .handle    = NULL,  /* spi_handle is global; handle unused */
    };

    /* Short boot delay for the sensor */
    platform_delay(10);

    /* Configure 4-wire SPI mode explicitly */
    lsm6dsv320x_spi_mode_set(&dev_ctx, LSM6DSV320X_SPI_4_WIRE);
    platform_delay(10);

    /* ── WHO_AM_I check ─────────────────────────────────────────────────── */
    uint8_t whoamI = 0;
    lsm6dsv320x_device_id_get(&dev_ctx, &whoamI);

    if (whoamI != LSM6DSV320X_ID) {
        ESP_LOGE(TAG, "WHO_AM_I mismatch: got 0x%02X, expected 0x%02X", whoamI, LSM6DSV320X_ID);
        ESP_LOGE(TAG, "Check SPI wiring. Halting.");
        vTaskDelete(NULL);
        return;
    }
    ESP_LOGI(TAG, "LSM6DSV320X found! WHO_AM_I = 0x%02X", whoamI);

    /* ── Device configuration ───────────────────────────────────────────── */

    /* Software reset */
    lsm6dsv320x_sw_por(&dev_ctx);
    platform_delay(15);

    /* Block Data Update — prevents half-updated readings */
    lsm6dsv320x_block_data_update_set(&dev_ctx, PROPERTY_ENABLE);

    /* Output Data Rate: 15 Hz for both accel and gyro */
    lsm6dsv320x_xl_data_rate_set(&dev_ctx, LSM6DSV320X_ODR_AT_15Hz);
    lsm6dsv320x_gy_data_rate_set(&dev_ctx, LSM6DSV320X_ODR_AT_15Hz);

    /* Full-scale range */
    lsm6dsv320x_xl_full_scale_set(&dev_ctx, LSM6DSV320X_2g);
    lsm6dsv320x_gy_full_scale_set(&dev_ctx, LSM6DSV320X_2000dps);

    /* Route DRDY_XL and DRDY_G to INT1 pin (GPIO10) */
    lsm6dsv320x_pin_int_route_t pin_int = {0};
    pin_int.drdy_xl = PROPERTY_ENABLE;
    pin_int.drdy_g  = PROPERTY_ENABLE;
    lsm6dsv320x_pin_int1_route_set(&dev_ctx, &pin_int);

    ESP_LOGI(TAG, "Accel + Gyro initialized at 15 Hz, interrupt-driven via INT1 (GPIO10).");

    /* ── Main loop ──────────────────────────────────────────────────────── */
    int16_t data_raw_acceleration[3];
    int16_t data_raw_angular_rate[3];
    float   acceleration_mg[3];
    float   angular_rate_mdps[3];

    while (1) {
        /* Block until INT1 fires (task notification from ISR) */
        ulTaskNotifyTake(pdTRUE, portMAX_DELAY);

        memset(data_raw_acceleration, 0, sizeof(data_raw_acceleration));
        memset(data_raw_angular_rate, 0, sizeof(data_raw_angular_rate));

        lsm6dsv320x_acceleration_raw_get(&dev_ctx, data_raw_acceleration);
        lsm6dsv320x_angular_rate_raw_get(&dev_ctx, data_raw_angular_rate);

        acceleration_mg[0] = lsm6dsv320x_from_fs2_to_mg(data_raw_acceleration[0]);
        acceleration_mg[1] = lsm6dsv320x_from_fs2_to_mg(data_raw_acceleration[1]);
        acceleration_mg[2] = lsm6dsv320x_from_fs2_to_mg(data_raw_acceleration[2]);

        angular_rate_mdps[0] = lsm6dsv320x_from_fs2000_to_mdps(data_raw_angular_rate[0]);
        angular_rate_mdps[1] = lsm6dsv320x_from_fs2000_to_mdps(data_raw_angular_rate[1]);
        angular_rate_mdps[2] = lsm6dsv320x_from_fs2000_to_mdps(data_raw_angular_rate[2]);

        ESP_LOGI(TAG,
                 "[mg]   X=%7.2f  Y=%7.2f  Z=%7.2f   "
                 "[mdps] X=%8.2f  Y=%8.2f  Z=%8.2f",
                 acceleration_mg[0],  acceleration_mg[1],  acceleration_mg[2],
                 angular_rate_mdps[0], angular_rate_mdps[1], angular_rate_mdps[2]);
    }
}

/* ── Entry point ──────────────────────────────────────────────────────────── */

void app_main(void)
{
    ESP_LOGI(TAG, "Starting LSM6DSV320X sensor task...");
    xTaskCreate(sensor_task, "sensor_task", 4096, NULL, 5, NULL);
}
