/* Host test: cc -std=c11 -Wall -Wextra -Werror tests/test_imu_transport.c -o transport_test */
#include <assert.h>
#include <stdio.h>
#include <stddef.h>
#include "../main/imu_transport.h"

_Static_assert(offsetof(imu_timed_header_t, boot_id) == 12, "boot offset");
_Static_assert(offsetof(imu_timed_header_t, segment_id) == 16, "segment offset");
_Static_assert(offsetof(imu_timed_header_t, first_sample) == 20, "counter offset");
_Static_assert(offsetof(imu_timed_header_t, first_time_ns) == 24, "timestamp offset");
_Static_assert(offsetof(imu_timed_header_t, period_ns) == 32, "period offset");

static void test_timing(void)
{
    assert(imu_timing_anchor_ns(1000000000ULL, 16, 130208) == 998046880ULL);
    assert(imu_timing_anchor_ns(1000000000ULL, 1, 130208) == 1000000000ULL);
    assert(imu_timing_anchor_ns(1, 32, 130208) == 0); /* no unsigned underflow */
    assert((512 - sizeof(imu_timed_header_t)) * 8 / 36 == 105);
    assert((244 - sizeof(imu_timed_header_t)) * 8 / 36 == 46);
    imu_timed_header_t header = {0};
    header.base.magic0 = 0xaa; header.base.magic1 = 0x58;
    header.version = 2; header.flags = 1; header.header_bytes = 36;
    header.boot_id = 7; header.segment_id = 2; header.first_sample = 0;
    header.first_time_ns = 1000000000ULL; header.period_ns = 130208;
    const uint8_t *bytes = (const uint8_t *)&header;
    assert(bytes[12] == 7 && bytes[16] == 2 && bytes[24] == 0 && bytes[25] == 0xca);
}

static void test_packing(void)
{
    uint8_t packed[7] = {0};
    const uint8_t expected[] = {0x12, 0x3a, 0xbc, 0xff, 0xf0, 0x00};
    uint32_t pos = 0;
    imu_pack12(packed, &pos, 0x123);
    imu_pack12(packed, &pos, 0xabc);
    imu_pack12(packed, &pos, 0xfff); /* -1 after 12-bit sign extension */
    imu_pack12(packed, &pos, 0);
    assert(pos == 48 && memcmp(packed, expected, sizeof(expected)) == 0);
    assert(packed[6] == 0);
    /* Exercise both nibble alignments for every possible 12-bit axis value. */
    for (int v = 0; v < 4096; ++v) {
        memset(packed, 0, sizeof(packed)); pos = 0;
        imu_pack12(packed, &pos, v);
        imu_pack12(packed, &pos, 4095 - v);
        assert(((packed[0] << 4) | (packed[1] >> 4)) == v);
        assert((((packed[1] & 15) << 8) | packed[2]) == 4095 - v);
    }
}

static void test_queue_backpressure(void)
{
    imu_tx_queue_t q = {0};
    uint8_t payload[IMU_PACKET_BYTES] = {0};
    for (unsigned i = 0; i < IMU_TX_QUEUE_CAPACITY; ++i) {
        payload[0] = i;
        assert(imu_tx_push(&q, payload, sizeof(payload), 111));
    }
    assert(q.count == 16 && q.peak == 16 && q.samples == 16 * 111);
    assert(!imu_tx_push(&q, payload, sizeof(payload), 111));
    assert(!imu_tx_push(&q, payload, IMU_PACKET_BYTES + 1, 1));
    /* Failed submission does not pop: the exact head survives every retry. */
    for (unsigned retry = 0; retry < 10; ++retry) {
        assert(q.packets[q.head].data[0] == 0);
        assert(q.packets[q.head].len == 512 && q.count == 16);
    }
    for (unsigned i = 0; i < 40; ++i) {
        assert(q.packets[q.head].data[0] == i);
        imu_tx_pop(&q);
        payload[0] = i + 16;
        assert(imu_tx_push(&q, payload, sizeof(payload), 111));
    }
    assert(imu_tx_clear(&q) == 16 * 111);
    assert(q.count == 0 && q.samples == 0);
    assert(imu_tx_clear(&q) == 0);
    imu_tx_pop(&q); /* empty pop is harmless */
    assert(q.count == 0);
}

int main(void)
{
    test_timing();
    test_packing();
    test_queue_backpressure();
    puts("IMU packing and bounded queue tests passed");
    return 0;
}
