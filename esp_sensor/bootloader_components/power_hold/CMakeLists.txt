idf_component_register(SRCS "power_hold.c" REQUIRES hal soc esp_rom)
target_link_libraries(${COMPONENT_LIB} INTERFACE "-u bootloader_hooks_include")
