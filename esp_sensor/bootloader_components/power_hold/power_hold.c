#include "hal/gpio_ll.h"
#include "soc/gpio_struct.h"
#include "soc/gpio_sig_map.h"
#include "esp_rom_gpio.h"

/* Force this object into the bootloader despite the weak hook reference. */
void bootloader_hooks_include(void) {}

void bootloader_before_init(void)
{
    /* U0TXD / package pin 28. No drivers or initialized RAM needed here.
     * The ROM runs before this hook; hardware must sustain power until then.
     * Use USB Serial/JTAG console so later initialization cannot reclaim TX. */
    gpio_ll_set_level(&GPIO, 21, 1);
    esp_rom_gpio_connect_out_signal(21, SIG_GPIO_OUT_IDX, false, false);
    gpio_ll_output_enable(&GPIO, 21);
    esp_rom_gpio_pad_select_gpio(21);
    gpio_ll_pullup_en(&GPIO, 21);
}
