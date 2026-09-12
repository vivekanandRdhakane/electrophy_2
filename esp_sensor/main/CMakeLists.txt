idf_component_register(
    SRCS
        "main.c"
        "lsm6dsv320x_reg.c"
    INCLUDE_DIRS "."
    PRIV_REQUIRES
        bt
        nvs_flash
        esp_driver_spi
        esp_driver_gpio)
