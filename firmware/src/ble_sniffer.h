// Raw nRF52840 RADIO driver for BLE advertising channels.
//
// The SoftDevice is never enabled by this firmware, so the RADIO peripheral is
// ours to program directly. We sit in continuous RX on one advertising channel
// (or hop across all three) and hand every received PDU - including ones that
// fail CRC - to a lock-free ring buffer that the main loop drains.

#pragma once

#include <Arduino.h>

// Legacy advertising PDUs carry at most 37 payload bytes on top of the 2 byte
// header, so the radio is configured with MAXLEN 37 and nothing longer can land.
static const uint8_t SNIFF_MAX_PDU = 39;

/**
 * Bytes captured in raw mode: 2 header + 37 payload + 3 CRC.
 *
 * Requirement F2 of the specification: capture a fixed number of bytes rather
 * than trusting the Length field. If Length is corrupted the radio otherwise
 * misplaces the packet boundary and throws away the tail together with the CRC -
 * exactly the packets recovery most wants.
 */
static const uint8_t SNIFF_RAW_LEN = 42;

// Channel selector. The radio can only listen on one frequency at a time, so
// CHANNEL_ALL is implemented as a round-robin hop rather than true parallelism.
enum SniffChannel : uint8_t {
    CHANNEL_37  = 37,
    CHANNEL_38  = 38,
    CHANNEL_39  = 39,
    CHANNEL_ALL = 0,
};

struct SniffPacket {
    uint64_t timestampUs;  // microseconds since boot, latched at packet END
    uint8_t  channel;      // 37, 38 or 39 - always the real channel, never ALL
    int8_t   rssi;         // dBm, negative
    bool     crcOk;
    uint8_t  length;       // valid bytes in pdu[]
    uint8_t  pdu[SNIFF_RAW_LEN];
    /** True when pdu[] is whitened and carries the CRC (raw mode). */
    bool     raw;
};

struct SniffStats {
    uint32_t received;   // packets handed to the ring buffer
    uint32_t crcErrors;  // subset of received that failed CRC
    uint32_t dropped;    // packets lost because the ring buffer was full
    uint32_t missed;     // ends the radio signalled that we never serviced
};

namespace sniffer {

void init();

// Starts continuous RX. Safe to call while already running - it restarts on the
// new channel.
void start(SniffChannel channel);

// Powers the radio down. No further packets are queued.
void stop();

/**
 * Switches between the two capture modes.
 *
 * Normal mode lets the radio parse the length, dewhiten and verify the CRC, and
 * delivers a clean PDU. Raw mode (requirements F2 and F4) captures a fixed 42
 * bytes with whitening and CRC checking both disabled, so the host receives the
 * bytes as they arrived and can recompute everything - which is what makes
 * syndrome decoding possible at all.
 *
 * Takes effect on the next start().
 */
void setRawMode(bool raw);
bool rawMode();

// Records the channel to use without touching the radio. When RX is already
// running this restarts it on the new channel instead.
void select(SniffChannel channel);

bool isRunning();
SniffChannel channel();

// Number of captured packets waiting to be read.
uint32_t queued();

// Advances the round-robin hop when CHANNEL_ALL is selected. Cheap no-op
// otherwise; call it often from the main loop.
void poll();

// Pops the oldest queued packet. Returns false when the queue is empty.
bool read(SniffPacket &out);

SniffStats stats();
void resetStats();

// 64 bit microsecond clock that survives the 32 bit micros() wrap.
uint64_t micros64();

}  // namespace sniffer
