#pragma once

#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#define IMU_PACKET_BYTES 512
#define IMU_TX_QUEUE_CAPACITY 16

typedef struct {
    uint16_t len;
    uint8_t samples;
    uint8_t data[IMU_PACKET_BYTES];
} imu_tx_packet_t;

/* Owned by the sensor task. Retry the head without removing or modifying it. */
typedef struct {
    imu_tx_packet_t packets[IMU_TX_QUEUE_CAPACITY];
    uint8_t head;
    uint8_t count;
    uint8_t peak;
    uint32_t samples;
} imu_tx_queue_t;

static inline bool imu_tx_push(imu_tx_queue_t *q, const uint8_t *data,
                               uint16_t len, uint8_t samples)
{
    if (q->count == IMU_TX_QUEUE_CAPACITY || len > IMU_PACKET_BYTES) return false;
    imu_tx_packet_t *p = &q->packets[(q->head + q->count) % IMU_TX_QUEUE_CAPACITY];
    p->len = len;
    p->samples = samples;
    memcpy(p->data, data, len);
    q->count++;
    q->samples += samples;
    if (q->count > q->peak) q->peak = q->count;
    return true;
}

static inline void imu_tx_pop(imu_tx_queue_t *q)
{
    if (!q->count) return;
    q->samples -= q->packets[q->head].samples;
    q->head = (q->head + 1) % IMU_TX_QUEUE_CAPACITY;
    q->count--;
}

static inline uint32_t imu_tx_clear(imu_tx_queue_t *q)
{
    uint32_t discarded = q->samples;
    q->head = q->count = 0;
    q->samples = 0;
    return discarded;
}

/* Samples are 12-bit MSB-first; axis boundaries alternate between bit 0 and 4. */
static inline void imu_pack12(uint8_t *buf, uint32_t *bitpos, uint16_t value)
{
    uint32_t byte = *bitpos >> 3;
    value &= 0x0fff;
    if ((*bitpos & 7) == 0) {
        buf[byte] = value >> 4;
        buf[byte + 1] = (value & 0x0f) << 4;
    } else {
        buf[byte] |= value >> 8;
        buf[byte + 1] = value & 0xff;
    }
    *bitpos += 12;
}
