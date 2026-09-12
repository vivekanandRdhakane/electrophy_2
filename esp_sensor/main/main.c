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

#include <stdio.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"

#include "driver/spi_master.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "nvs_flash.h"

/* NimBLE */
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

#include "lsm6dsv320x_reg.h"

/* ── Pin definitions ──────────────────────────────────────────────────────── */
#define PIN_SCLK    GPIO_NUM_4
#define PIN_CS      GPIO_NUM_5
#define PIN_MOSI    GPIO_NUM_6
#define PIN_MISO    GPIO_NUM_7
#define PIN_INT1    GPIO_NUM_10

#define SPI_HOST_ID SPI2_HOST
#define SPI_CLK_HZ  (8 * 1000 * 1000)  /* 8 MHz */

#define DEVICE_NAME       "ESP_IMU"

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

static const char *TAG     = "LSM6DSV320X";
static const char *TAG_BLE = "BLE";

/* ── Global state ─────────────────────────────────────────────────────────── */
static spi_device_handle_t spi_handle;
static TaskHandle_t        sensor_task_handle = NULL;

/* BLE connection / subscription state */
static uint16_t ble_conn_handle    = BLE_HS_CONN_HANDLE_NONE;
static uint16_t imu_chr_val_handle = 0;
static bool     ble_notify_enabled = false;
static uint8_t  own_addr_type      = BLE_OWN_ADDR_RANDOM; /* resolved at sync */

/* FreeRTOS event group: set when a central is connected */
#define BLE_CONNECTED_BIT   BIT0
static EventGroupHandle_t ble_event_group;

/* ── INT1 ISR ─────────────────────────────────────────────────────────────── */
static void IRAM_ATTR int1_isr_handler(void *arg)
{
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

    gpio_set_level(PIN_CS, 0);

    spi_transaction_t t = {0};
    t.length    = 8;
    t.tx_buffer = &reg;
    spi_device_transmit(spi_handle, &t);

    if (len > 0) {
        memset(&t, 0, sizeof(t));
        t.length    = len * 8;
        t.tx_buffer = bufp;
        spi_device_transmit(spi_handle, &t);
    }

    gpio_set_level(PIN_CS, 1);
    return 0;
}

/**
 * @brief Read registers via SPI — CS↓ · [reg|0x80] · [receive] · CS↑
 */
static int32_t platform_read(void *handle, uint8_t reg, uint8_t *bufp, uint16_t len)
{
    (void)handle;
    reg |= 0x80;

    gpio_set_level(PIN_CS, 0);

    spi_transaction_t t = {0};
    t.length    = 8;
    t.tx_buffer = &reg;
    spi_device_transmit(spi_handle, &t);

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
 * @brief Millisecond delay shim.
 */
static void platform_delay(uint32_t ms)
{
    vTaskDelay(pdMS_TO_TICKS(ms));
}

/* ── Hardware initialisation ──────────────────────────────────────────────── */

static void spi_init(void)
{
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
static char latest_ble_str[128] = "Sensor initializing...\r\n";

/* ── BLE: GATT characteristic access callback ─────────────────────────────── */
static int imu_chr_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                              struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        int rc = os_mbuf_append(ctxt->om, latest_ble_str, strlen(latest_ble_str));
        return (rc == 0) ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        /* Accept incoming text/commands from phone terminal */
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

/* ── BLE: send ASCII string notification ──────────────────────────────────── */
static void imu_notify_str(const char *str)
{
    if (!ble_notify_enabled || ble_conn_handle == BLE_HS_CONN_HANDLE_NONE) {
        return;
    }

    struct os_mbuf *om = ble_hs_mbuf_from_flat(str, strlen(str));
    if (om == NULL) {
        ESP_LOGW(TAG_BLE, "mbuf alloc failed — skipping notify");
        return;
    }

    int rc = ble_gatts_notify_custom(ble_conn_handle, imu_chr_val_handle, om);
    if (rc != 0 && rc != BLE_HS_ENOTCONN) {
        ESP_LOGW(TAG_BLE, "notify error: %d", rc);
    }
}

/* ── BLE: GAP event handler ───────────────────────────────────────────────── */
static void ble_start_advertising(void); /* forward declaration */

static int gap_event_handler(struct ble_gap_event *event, void *arg)
{
    switch (event->type) {

    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            ble_conn_handle = event->connect.conn_handle;
            ESP_LOGI(TAG_BLE, "Connected  (conn_handle=%d)", ble_conn_handle);
            /* Request MTU exchange so large strings fit into notifications */
            ble_gattc_exchange_mtu(ble_conn_handle, NULL, NULL);
            xEventGroupSetBits(ble_event_group, BLE_CONNECTED_BIT);
        } else {
            ESP_LOGW(TAG_BLE, "Connect failed (status=%d) — restarting advertising",
                     event->connect.status);
            ble_conn_handle    = BLE_HS_CONN_HANDLE_NONE;
            ble_notify_enabled = false;
            xEventGroupClearBits(ble_event_group, BLE_CONNECTED_BIT);
            ble_start_advertising();
        }
        break;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG_BLE, "Disconnected (reason=%d) — restarting advertising",
                 event->disconnect.reason);
        ble_conn_handle    = BLE_HS_CONN_HANDLE_NONE;
        ble_notify_enabled = false;
        xEventGroupClearBits(ble_event_group, BLE_CONNECTED_BIT);
        ble_start_advertising();
        break;

    case BLE_GAP_EVENT_SUBSCRIBE:
        if (event->subscribe.attr_handle == imu_chr_val_handle) {
            ble_notify_enabled = (event->subscribe.cur_notify != 0);
            ESP_LOGI(TAG_BLE, "Notifications %s",
                     ble_notify_enabled ? "ENABLED — streaming IMU data" : "DISABLED");
        }
        break;

    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG_BLE, "MTU negotiated: %d bytes", event->mtu.value);
        break;

    default:
        break;
    }
    return 0;
}

/* ── BLE: start advertising ───────────────────────────────────────────────── */
static void ble_start_advertising(void)
{
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
}

/* ── BLE: NimBLE host FreeRTOS task ──────────────────────────────────────── */
static void nimble_host_task(void *param)
{
    ESP_LOGI(TAG_BLE, "NimBLE host task started.");
    nimble_port_run();              /* blocks here until nimble_port_stop() */
    nimble_port_freertos_deinit();
}

/* ── BLE: full stack initialisation ──────────────────────────────────────── */
static void ble_stack_init(void)
{
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
    nimble_port_freertos_init(nimble_host_task);
}

/* ── Sensor task ──────────────────────────────────────────────────────────── */
static void sensor_task(void *arg)
{
    /* Store own handle so the ISR can send task notifications */
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
        .handle    = NULL,
    };

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

    lsm6dsv320x_block_data_update_set(&dev_ctx, PROPERTY_ENABLE);

    /* Set Pulsed mode on DRDY pin so it pulses instead of latching indefinitely */
    lsm6dsv320x_data_ready_mode_set(&dev_ctx, LSM6DSV320X_DRDY_PULSED);

    lsm6dsv320x_xl_data_rate_set(&dev_ctx, LSM6DSV320X_ODR_AT_15Hz);
    lsm6dsv320x_gy_data_rate_set(&dev_ctx, LSM6DSV320X_ODR_AT_15Hz);
    lsm6dsv320x_xl_full_scale_set(&dev_ctx, LSM6DSV320X_2g);
    lsm6dsv320x_gy_full_scale_set(&dev_ctx, LSM6DSV320X_2000dps);

    /* Route DRDY_XL + DRDY_G to INT1 (GPIO10) */
    lsm6dsv320x_pin_int_route_t pin_int = {0};
    pin_int.drdy_xl = PROPERTY_ENABLE;
    pin_int.drdy_g  = PROPERTY_ENABLE;
    lsm6dsv320x_pin_int1_route_set(&dev_ctx, &pin_int);

    ESP_LOGI(TAG, "Sensor ready. Waiting for a BLE connection before streaming...");

    /* ── Main loop ──────────────────────────────────────────────────────── */
    int16_t data_raw_acceleration[3];
    int16_t data_raw_angular_rate[3];
    float   acceleration_mg[3];
    float   angular_rate_mdps[3];
    char    ble_str[128]; /* buffer for the ASCII notification string */

    while (1) {
        /* ── Gate: pause until a BLE central is connected ─────────────── */
        if (!(xEventGroupGetBits(ble_event_group) & BLE_CONNECTED_BIT)) {
            ESP_LOGI(TAG, "No BLE connection — sensor paused. Connect via nRF Connect.");
            /* Block indefinitely until connected; auto-resumes on connect */
            xEventGroupWaitBits(ble_event_group, BLE_CONNECTED_BIT,
                                pdFALSE,    /* do NOT clear bit on exit */
                                pdTRUE,     /* wait for ALL bits (only one here) */
                                portMAX_DELAY);
            /* Perform a read to clear any pending/latched data on the sensor */
            lsm6dsv320x_acceleration_raw_get(&dev_ctx, data_raw_acceleration);
            lsm6dsv320x_angular_rate_raw_get(&dev_ctx, data_raw_angular_rate);
            ulTaskNotifyTake(pdTRUE, 0);
            ESP_LOGI(TAG, "BLE connected — starting sensor data stream.");
        }

        /* ── Wait for INT1 data-ready interrupt with 200ms timeout ───── */
        /* If no interrupt arrives in 200ms (e.g. startup pulse missed), proceed anyway */
        ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(200));

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

        static int64_t last_send_time = 0;
        int64_t now = esp_timer_get_time();

        /* Send exactly 1 sample per second (1,000,000 microseconds) */
        if (now - last_send_time >= 1000000LL) {
            last_send_time = now;

            /* Format: identical to the serial log with CRLF for terminals */
            snprintf(ble_str, sizeof(ble_str),
                     "[mg] X=%7.2f Y=%7.2f Z=%7.2f  "
                     "[mdps] X=%8.2f Y=%8.2f Z=%8.2f\r\n",
                     acceleration_mg[0],   acceleration_mg[1],   acceleration_mg[2],
                     angular_rate_mdps[0], angular_rate_mdps[1], angular_rate_mdps[2]);

            /* Update cache for GATT Read */
            strncpy(latest_ble_str, ble_str, sizeof(latest_ble_str) - 1);
            latest_ble_str[sizeof(latest_ble_str) - 1] = '\0';

            /* Serial output */
            ESP_LOGI(TAG, "%s", ble_str);

            /* BLE notification (ASCII string — readable directly in nRF Connect) */
            imu_notify_str(ble_str);
        }
    }
}

/* ── Entry point ──────────────────────────────────────────────────────────── */
void app_main(void)
{
    ESP_LOGI(TAG, "=== ESP_IMU starting ===");

    /* Start BLE stack first — it runs in its own NimBLE host task */
    ble_stack_init();

    /* Start sensor task — handles SPI + INT1 + BLE notify */
    xTaskCreate(sensor_task, "sensor_task", 4096, NULL, 5, NULL);
}
