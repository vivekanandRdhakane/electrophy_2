if(IDF_VERSION_MAJOR EQUAL 5 AND IDF_VERSION_MINOR LESS 3)
    set(imu_driver_components driver)
else()
    set(imu_driver_components esp_driver_spi esp_driver_gpio esp_driver_ledc)
endif()

idf_component_register(
    SRCS
        "main.c"
        "battery_monitor.c"
        "lsm6dsv320x_reg.c"
    INCLUDE_DIRS "."
    PRIV_REQUIRES
        bt
        nvs_flash
        esp_adc
        ${imu_driver_components})
