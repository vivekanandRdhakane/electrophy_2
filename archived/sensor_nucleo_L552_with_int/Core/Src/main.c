/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file           : main.c
  * @brief          : Main program body
  ******************************************************************************
  * @attention
  *
  * Copyright (c) 2026 STMicroelectronics.
  * All rights reserved.
  *
  * This software is licensed under terms that can be found in the LICENSE file
  * in the root directory of this software component.
  * If no LICENSE file comes with this software, it is provided AS-IS.
  *
  ******************************************************************************
  */
/* USER CODE END Header */
/* Includes ------------------------------------------------------------------*/
#include "main.h"

/* Private includes ----------------------------------------------------------*/
/* USER CODE BEGIN Includes */

#include "lsm6dsv320x_reg.h"
#include <string.h>
/* USER CODE END Includes */

/* Private typedef -----------------------------------------------------------*/
/* USER CODE BEGIN PTD */

/* USER CODE END PTD */

/* Private define ------------------------------------------------------------*/
/* USER CODE BEGIN PD */

/* USER CODE END PD */

/* Private macro -------------------------------------------------------------*/
/* USER CODE BEGIN PM */

/* USER CODE END PM */

/* Private variables ---------------------------------------------------------*/

COM_InitTypeDef BspCOMInit;

SPI_HandleTypeDef hspi1;

/* USER CODE BEGIN PV */
float_t temp;
/* Flag set by EXTI0 ISR when LSM6DSV320X INT1 asserts data ready */
volatile uint8_t drdy_flag = 0;
/* USER CODE END PV */

/* Private function prototypes -----------------------------------------------*/
void SystemClock_Config(void);
static void MX_GPIO_Init(void);
static void MX_ICACHE_Init(void);
static void MX_SPI1_Init(void);
/* USER CODE BEGIN PFP */

/* USER CODE END PFP */

/* Private user code ---------------------------------------------------------*/
/* USER CODE BEGIN 0 */
static int32_t platform_write(void *handle, uint8_t reg, const uint8_t *bufp, uint16_t len)
{
  HAL_GPIO_WritePin(CS_GPIO_PORT, CS_PIN, GPIO_PIN_RESET);
  HAL_SPI_Transmit((SPI_HandleTypeDef*)handle, &reg, 1, 1000);
  HAL_SPI_Transmit((SPI_HandleTypeDef*)handle, (uint8_t*)bufp, len, 1000);
  HAL_GPIO_WritePin(CS_GPIO_PORT, CS_PIN, GPIO_PIN_SET);
  return 0;
}

static int32_t platform_read(void *handle, uint8_t reg, uint8_t *bufp, uint16_t len)
{
  reg |= 0x80; /* Read bit for LSM6DSV320X SPI */
  HAL_GPIO_WritePin(CS_GPIO_PORT, CS_PIN, GPIO_PIN_RESET);
  HAL_SPI_Transmit((SPI_HandleTypeDef*)handle, &reg, 1, 1000);
  HAL_SPI_Receive((SPI_HandleTypeDef*)handle, bufp, len, 1000);
  HAL_GPIO_WritePin(CS_GPIO_PORT, CS_PIN, GPIO_PIN_SET);
  return 0;
}
/* USER CODE END 0 */

/**
  * @brief  The application entry point.
  * @retval int
  */
int main(void)
{

  /* USER CODE BEGIN 1 */

  /* USER CODE END 1 */

  /* MCU Configuration--------------------------------------------------------*/

  /* Reset of all peripherals, Initializes the Flash interface and the Systick. */
  HAL_Init();

  /* USER CODE BEGIN Init */

  /* USER CODE END Init */

  /* Configure the system clock */
  SystemClock_Config();

  /* USER CODE BEGIN SysInit */

  /* USER CODE END SysInit */

  /* Initialize all configured peripherals */
  MX_GPIO_Init();
  MX_ICACHE_Init();
  MX_SPI1_Init();
  /* USER CODE BEGIN 2 */


  /* USER CODE END 2 */

  /* Initialize leds */
  BSP_LED_Init(LED_GREEN);
  BSP_LED_Init(LED_BLUE);
  BSP_LED_Init(LED_RED);

  /* Initialize USER push-button, will be used to trigger an interrupt each time it's pressed.*/
  BSP_PB_Init(BUTTON_USER, BUTTON_MODE_EXTI);

  /* Initialize COM1 port (115200, 8 bits (7-bit data + 1 stop bit), no parity */
  BspCOMInit.BaudRate   = 115200;
  BspCOMInit.WordLength = COM_WORDLENGTH_8B;
  BspCOMInit.StopBits   = COM_STOPBITS_1;
  BspCOMInit.Parity     = COM_PARITY_NONE;
  BspCOMInit.HwFlowCtl  = COM_HWCONTROL_NONE;
  if (BSP_COM_Init(COM1, &BspCOMInit) != BSP_ERROR_NONE)
  {
    Error_Handler();
  }

  /* Infinite loop */
  /* USER CODE BEGIN WHILE */
  /* Initialize COM1 port (115200, 8 bits (7-bit data + 1 stop bit), no parity */
  BspCOMInit.BaudRate   = 115200;
  BspCOMInit.WordLength = COM_WORDLENGTH_8B;
  BspCOMInit.StopBits   = COM_STOPBITS_1;
  BspCOMInit.Parity     = COM_PARITY_NONE;
  BspCOMInit.HwFlowCtl  = COM_HWCONTROL_NONE;
  if (BSP_COM_Init(COM1, &BspCOMInit) != BSP_ERROR_NONE)
  {
    Error_Handler();
  }
  stmdev_ctx_t dev_ctx;
  uint8_t whoamI;

  dev_ctx.write_reg = platform_write;
  dev_ctx.read_reg = platform_read;
  dev_ctx.mdelay = (void (*)(uint32_t))HAL_Delay;
  dev_ctx.handle = &hspi1;

  HAL_GPIO_WritePin(CS_GPIO_PORT, CS_PIN, GPIO_PIN_RESET);

  /* Wait sensor boot time */
  HAL_Delay(100);
/* Explicitly configure 4-wire SPI mode (SIM = 0 in IF_CFG register 0x03) */
    lsm6dsv320x_spi_mode_set(&dev_ctx, LSM6DSV320X_SPI_4_WIRE);
  /* Check device ID */
  lsm6dsv320x_device_id_get(&dev_ctx, &whoamI);
  if (whoamI != LSM6DSV320X_ID) {
    printf("LSM6DSV320X not found! WHO_AM_I = 0x%02X (expected 0x%02X)\r\n", whoamI, LSM6DSV320X_ID);
    printf("Please verify SPI wiring and that CS is connected to PA4.\r\n");
  } else {
    printf("LSM6DSV320X Found! WHO_AM_I = 0x%02X\r\n", whoamI);

    /* Perform device power-on-reset */
    lsm6dsv320x_sw_por(&dev_ctx);
    HAL_Delay(15);

    /* Enable Block Data Update */
    lsm6dsv320x_block_data_update_set(&dev_ctx, PROPERTY_ENABLE);

    /* Set Output Data Rate for accelerometer and gyroscope */
    lsm6dsv320x_xl_data_rate_set(&dev_ctx, LSM6DSV320X_ODR_AT_15Hz);
    lsm6dsv320x_gy_data_rate_set(&dev_ctx, LSM6DSV320X_ODR_AT_15Hz);

    /* Set full scale */
    lsm6dsv320x_xl_full_scale_set(&dev_ctx, LSM6DSV320X_2g);
    lsm6dsv320x_gy_full_scale_set(&dev_ctx, LSM6DSV320X_2000dps);

    /* Route drdy_xl and drdy_g to INT1 pin so PB0 fires on every new sample */
    lsm6dsv320x_pin_int_route_t pin_int = {0};
    pin_int.drdy_xl = PROPERTY_ENABLE;
    pin_int.drdy_g  = PROPERTY_ENABLE;
    lsm6dsv320x_pin_int1_route_set(&dev_ctx, &pin_int);

    printf("LSM6DSV320X Accelerometer + Gyroscope initialized (interrupt-driven via INT1/PB0).\r\n");
  }
  while (1)
  {

    /* USER CODE END WHILE */

    /* USER CODE BEGIN 3 */
    if (drdy_flag)
    {
      drdy_flag = 0;

      int16_t data_raw_acceleration[3];
      int16_t data_raw_angular_rate[3];
      float acceleration_mg[3];
      float angular_rate_mdps[3];

      memset(data_raw_acceleration, 0x00, 3 * sizeof(int16_t));
      memset(data_raw_angular_rate, 0x00, 3 * sizeof(int16_t));

      lsm6dsv320x_acceleration_raw_get(&dev_ctx, data_raw_acceleration);
      lsm6dsv320x_angular_rate_raw_get(&dev_ctx, data_raw_angular_rate);

      acceleration_mg[0] = lsm6dsv320x_from_fs2_to_mg(data_raw_acceleration[0]);
      acceleration_mg[1] = lsm6dsv320x_from_fs2_to_mg(data_raw_acceleration[1]);
      acceleration_mg[2] = lsm6dsv320x_from_fs2_to_mg(data_raw_acceleration[2]);

      angular_rate_mdps[0] = lsm6dsv320x_from_fs2000_to_mdps(data_raw_angular_rate[0]);
      angular_rate_mdps[1] = lsm6dsv320x_from_fs2000_to_mdps(data_raw_angular_rate[1]);
      angular_rate_mdps[2] = lsm6dsv320x_from_fs2000_to_mdps(data_raw_angular_rate[2]);

      temp = acceleration_mg[2];

      printf("%lu ms\t[mg] X=%0.2f\tY=%0.2f\tZ=%0.2f\t[mdps] X=%0.2f\tY=%0.2f\tZ=%0.2f\r\n",
             (unsigned long)HAL_GetTick(),
             acceleration_mg[0], acceleration_mg[1], acceleration_mg[2],
             angular_rate_mdps[0], angular_rate_mdps[1], angular_rate_mdps[2]);
      BSP_LED_Toggle(LED_GREEN);
    }
    /* No HAL_Delay here — CPU is free; INT1 wakes us when data is ready */
  }
  /* USER CODE END 3 */
}

/**
  * @brief System Clock Configuration
  * @retval None
  */
void SystemClock_Config(void)
{
  RCC_OscInitTypeDef RCC_OscInitStruct = {0};
  RCC_ClkInitTypeDef RCC_ClkInitStruct = {0};

  /** Configure the main internal regulator output voltage
  */
  if (HAL_PWREx_ControlVoltageScaling(PWR_REGULATOR_VOLTAGE_SCALE0) != HAL_OK)
  {
    Error_Handler();
  }

  /** Initializes the RCC Oscillators according to the specified parameters
  * in the RCC_OscInitTypeDef structure.
  */
  RCC_OscInitStruct.OscillatorType = RCC_OSCILLATORTYPE_MSI;
  RCC_OscInitStruct.MSIState = RCC_MSI_ON;
  RCC_OscInitStruct.MSICalibrationValue = RCC_MSICALIBRATION_DEFAULT;
  RCC_OscInitStruct.MSIClockRange = RCC_MSIRANGE_6;
  RCC_OscInitStruct.PLL.PLLState = RCC_PLL_ON;
  RCC_OscInitStruct.PLL.PLLSource = RCC_PLLSOURCE_MSI;
  RCC_OscInitStruct.PLL.PLLM = 1;
  RCC_OscInitStruct.PLL.PLLN = 55;
  RCC_OscInitStruct.PLL.PLLP = RCC_PLLP_DIV7;
  RCC_OscInitStruct.PLL.PLLQ = RCC_PLLQ_DIV2;
  RCC_OscInitStruct.PLL.PLLR = RCC_PLLR_DIV2;
  if (HAL_RCC_OscConfig(&RCC_OscInitStruct) != HAL_OK)
  {
    Error_Handler();
  }

  /** Initializes the CPU, AHB and APB buses clocks
  */
  RCC_ClkInitStruct.ClockType = RCC_CLOCKTYPE_HCLK|RCC_CLOCKTYPE_SYSCLK
                              |RCC_CLOCKTYPE_PCLK1|RCC_CLOCKTYPE_PCLK2;
  RCC_ClkInitStruct.SYSCLKSource = RCC_SYSCLKSOURCE_PLLCLK;
  RCC_ClkInitStruct.AHBCLKDivider = RCC_SYSCLK_DIV1;
  RCC_ClkInitStruct.APB1CLKDivider = RCC_HCLK_DIV1;
  RCC_ClkInitStruct.APB2CLKDivider = RCC_HCLK_DIV1;

  if (HAL_RCC_ClockConfig(&RCC_ClkInitStruct, FLASH_LATENCY_5) != HAL_OK)
  {
    Error_Handler();
  }
}

/**
  * @brief ICACHE Initialization Function
  * @param None
  * @retval None
  */
static void MX_ICACHE_Init(void)
{

  /* USER CODE BEGIN ICACHE_Init 0 */

  /* USER CODE END ICACHE_Init 0 */

  /* USER CODE BEGIN ICACHE_Init 1 */

  /* USER CODE END ICACHE_Init 1 */

  /** Enable instruction cache in 1-way (direct mapped cache)
  */
  if (HAL_ICACHE_ConfigAssociativityMode(ICACHE_1WAY) != HAL_OK)
  {
    Error_Handler();
  }
  if (HAL_ICACHE_Enable() != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN ICACHE_Init 2 */

  /* USER CODE END ICACHE_Init 2 */

}

/**
  * @brief SPI1 Initialization Function
  * @param None
  * @retval None
  */
static void MX_SPI1_Init(void)
{

  /* USER CODE BEGIN SPI1_Init 0 */

  /* USER CODE END SPI1_Init 0 */

  /* USER CODE BEGIN SPI1_Init 1 */

  /* USER CODE END SPI1_Init 1 */
  /* SPI1 parameter configuration*/
  hspi1.Instance = SPI1;
  hspi1.Init.Mode = SPI_MODE_MASTER;
  hspi1.Init.Direction = SPI_DIRECTION_2LINES;
  hspi1.Init.DataSize = SPI_DATASIZE_8BIT;
  hspi1.Init.CLKPolarity = SPI_POLARITY_LOW;
  hspi1.Init.CLKPhase = SPI_PHASE_1EDGE;
  hspi1.Init.NSS = SPI_NSS_SOFT;
  hspi1.Init.BaudRatePrescaler = SPI_BAUDRATEPRESCALER_16;
  hspi1.Init.FirstBit = SPI_FIRSTBIT_MSB;
  hspi1.Init.TIMode = SPI_TIMODE_DISABLE;
  hspi1.Init.CRCCalculation = SPI_CRCCALCULATION_DISABLE;
  hspi1.Init.CRCPolynomial = 7;
  hspi1.Init.CRCLength = SPI_CRC_LENGTH_DATASIZE;
  hspi1.Init.NSSPMode = SPI_NSS_PULSE_ENABLE;
  if (HAL_SPI_Init(&hspi1) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN SPI1_Init 2 */

  /* USER CODE END SPI1_Init 2 */

}

/**
  * @brief GPIO Initialization Function
  * @param None
  * @retval None
  */
static void MX_GPIO_Init(void)
{
  GPIO_InitTypeDef GPIO_InitStruct = {0};
  /* USER CODE BEGIN MX_GPIO_Init_1 */

  /* USER CODE END MX_GPIO_Init_1 */

  /* GPIO Ports Clock Enable */
  __HAL_RCC_GPIOC_CLK_ENABLE();
  __HAL_RCC_GPIOA_CLK_ENABLE();
  __HAL_RCC_GPIOB_CLK_ENABLE();

  /*Configure GPIO pin Output Level */
  HAL_GPIO_WritePin(GPIOA, GPIO_PIN_4, GPIO_PIN_RESET);

  /*Configure GPIO pin : PA4 (SPI CS) */
  GPIO_InitStruct.Pin = GPIO_PIN_4;
  GPIO_InitStruct.Mode = GPIO_MODE_OUTPUT_PP;
  GPIO_InitStruct.Pull = GPIO_NOPULL;
  GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
  HAL_GPIO_Init(GPIOA, &GPIO_InitStruct);

  /* USER CODE BEGIN MX_GPIO_Init_2 */
  /* Ensure CS (PA4) is HIGH (deselected) initially */
  HAL_GPIO_WritePin(CS_GPIO_PORT, CS_PIN, GPIO_PIN_RESET);

  /*Configure PB0 as EXTI input for LSM6DSV320X INT1 (rising-edge triggered) */
  GPIO_InitStruct.Pin = INT1_PIN;
  GPIO_InitStruct.Mode = GPIO_MODE_IT_RISING;
  GPIO_InitStruct.Pull = GPIO_PULLDOWN;
  HAL_GPIO_Init(INT1_GPIO_PORT, &GPIO_InitStruct);

  /* Enable and configure NVIC for EXTI0 (INT1) */
  HAL_NVIC_SetPriority(INT1_EXTI_IRQn, 0, 1);
  HAL_NVIC_EnableIRQ(INT1_EXTI_IRQn);

  /* USER CODE END MX_GPIO_Init_2 */
}

/* USER CODE BEGIN 4 */

/* USER CODE END 4 */

/**
  * @brief  This function is executed in case of error occurrence.
  * @retval None
  */
void Error_Handler(void)
{
  /* USER CODE BEGIN Error_Handler_Debug */
  /* User can add his own implementation to report the HAL error return state */
  __disable_irq();
  while (1)
  {
  }
  /* USER CODE END Error_Handler_Debug */
}
#ifdef USE_FULL_ASSERT
/**
  * @brief  Reports the name of the source file and the source line number
  *         where the assert_param error has occurred.
  * @param  file: pointer to the source file name
  * @param  line: assert_param error line source number
  * @retval None
  */
void assert_failed(uint8_t *file, uint32_t line)
{
  /* USER CODE BEGIN 6 */
  /* User can add his own implementation to report the file name and line number,
     ex: printf("Wrong parameters value: file %s on line %d\r\n", file, line) */
  /* USER CODE END 6 */
}
#endif /* USE_FULL_ASSERT */
