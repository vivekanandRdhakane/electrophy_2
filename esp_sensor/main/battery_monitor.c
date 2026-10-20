#include "battery_monitor.h"
#include <stdatomic.h>
#include "esp_adc/adc_oneshot.h"
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static adc_oneshot_unit_handle_t adc;
static adc_cali_handle_t calibration;
static gpio_num_t hold_pin;
static atomic_uint battery_mv;

static void power_off(void)
{
    /* Stay off even if USB or a held button keeps the MCU powered. */
    gpio_set_level(hold_pin, 0);
    for (;;) vTaskDelay(pdMS_TO_TICKS(1000));
}

static void battery_task(void *arg)
{
    (void)arg;
    unsigned low_count = 0, failures = 0;
    for (;;) {
        int raw, total_mv = 0;
        esp_err_t err = adc_oneshot_read(adc, ADC_CHANNEL_3, &raw);
        /* Discard the first conversion; average spaced, calibrated samples. */
        for (int i = 0; i < 16 && err == ESP_OK; ++i) {
            vTaskDelay(1); /* At least one RTOS tick, including 100 Hz builds. */
            int pin_mv;
            err = adc_oneshot_read(adc, ADC_CHANNEL_3, &raw);
            if (err == ESP_OK) err = adc_cali_raw_to_voltage(calibration, raw, &pin_mv);
            if (err == ESP_OK) {
                total_mv += pin_mv;
            }
        }
        if (err == ESP_OK) {
            const unsigned mv = (total_mv * 2 + 8) / 16;
            atomic_store(&battery_mv, mv);
            failures = 0;
            low_count = mv < BATTERY_CUTOFF_MV ? low_count + 1 : 0;
            if (low_count >= BATTERY_LOW_READINGS) {
                ESP_LOGW("BATTERY", "Battery %u mV below %d mV; releasing POWER_HOLD",
                         mv, BATTERY_CUTOFF_MV);
                power_off();
            }
        } else {
            atomic_store(&battery_mv, 0);
            low_count = 0;
            ESP_LOGE("BATTERY", "ADC read failed: %s", esp_err_to_name(err));
            /* Cannot protect the battery without working measurements. */
            if (++failures >= BATTERY_LOW_READINGS) power_off();
        }
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

uint16_t battery_monitor_mv(void)
{
    return (uint16_t)atomic_load(&battery_mv);
}

void battery_monitor_start(gpio_num_t power_hold_pin)
{
    hold_pin = power_hold_pin;
    const adc_oneshot_unit_init_cfg_t unit = { .unit_id = ADC_UNIT_1 };
    const adc_oneshot_chan_cfg_t channel = {
        .atten = ADC_ATTEN_DB_12, .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    const adc_cali_curve_fitting_config_t cal = {
        .unit_id = ADC_UNIT_1, .chan = ADC_CHANNEL_3,
        .atten = ADC_ATTEN_DB_12, .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    esp_err_t err = adc_oneshot_new_unit(&unit, &adc);
    if (err == ESP_OK) err = adc_oneshot_config_channel(adc, ADC_CHANNEL_3, &channel);
    if (err == ESP_OK) err = adc_cali_create_scheme_curve_fitting(&cal, &calibration);
    if (err != ESP_OK) {
        ESP_LOGE("BATTERY", "Battery monitor unavailable: %s; powering off", esp_err_to_name(err));
        power_off();
    }
    /* Above the busy sensor task (priority 5), with long sleeps between reads. */
    if (xTaskCreate(battery_task, "battery", 3072, NULL, 6, NULL) != pdPASS) {
        ESP_LOGE("BATTERY", "Cannot create battery task; powering off");
        power_off();
    }
}
