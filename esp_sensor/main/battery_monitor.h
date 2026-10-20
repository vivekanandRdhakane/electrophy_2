#pragma once

#include <stdint.h>
#include "driver/gpio.h"

/* Battery-terminal voltage, before the 100k/100k divider. */
#define BATTERY_CUTOFF_MV 3300
#define BATTERY_LOW_READINGS 3

void battery_monitor_start(gpio_num_t power_hold_pin);
/* Zero means no valid current reading. */
uint16_t battery_monitor_mv(void);
