// Reference beacon: transmits a *known* advertising PDU, on demand, at a chosen
// power.
//
// This exists to answer the one question the synthetic error models cannot.
// §6 of the specification asks for recovery to be evaluated two ways, and the
// second way needs ground truth that injected damage can never supply: a real
// transmitter sending a payload the receiver already knows, attenuated until
// real interference starts breaking it. Every packet the sniffer then receives
// can be compared byte for byte against what was actually sent, so the *shape*
// of real errors - scattered bits, or bursts, or something neither model
// predicts - is measured rather than assumed.
//
// Like the sniffer, this drives the RADIO peripheral directly and never starts
// the SoftDevice. A stack would decide the payload, the interval and the power
// for us, and every one of those has to be fixed and known here.

#pragma once

#include <Arduino.h>

// Legacy advertising: 6 byte AdvA plus at most 31 bytes of advertising data.
static const uint8_t BEACON_MAX_AD = 31;
static const uint8_t BEACON_ADDRESS_LEN = 6;

// 2 byte header + 6 byte address + advertising data.
static const uint8_t BEACON_MAX_PDU = 2 + BEACON_ADDRESS_LEN + BEACON_MAX_AD;

enum BeaconChannel : uint8_t {
    BEACON_CH_37 = 37,
    BEACON_CH_38 = 38,
    BEACON_CH_39 = 39,
    BEACON_CH_ALL = 0,
};

namespace beacon {

void init();

/**
 * Sets the advertiser address, given most-significant byte first as it is
 * written down. The radio transmits it least-significant byte first, which this
 * takes care of, so what the sniffer prints matches what was asked for.
 */
void setAddress(const uint8_t address[BEACON_ADDRESS_LEN], bool randomAddress);

/**
 * Sets the advertising data, and fixes its length.
 *
 * [length] is the length actually transmitted: shorter data is padded with
 * [filler] and longer data is truncated. Fixing the length matters more than it
 * looks - a receiver that gets the length field wrong misplaces the whole frame
 * boundary, and being able to say "every packet in this run was exactly N
 * bytes" is what separates that failure from a corrupted payload.
 */
bool setData(const uint8_t *data, uint8_t dataLength, uint8_t length, uint8_t filler);

/** PDU type in the header's low nibble; ADV_NONCONN_IND by default. */
void setPduType(uint8_t type);

void setChannel(BeaconChannel channel);
BeaconChannel channel();

/** True when [dbm] is one of the levels the radio actually supports. */
bool powerSupported(int8_t dbm);
void setPower(int8_t dbm);
int8_t power();

/** Every supported level, strongest first. */
const int8_t *powerLevels(uint8_t &count);

/** The PDU as it will be transmitted, header included. Returns its length. */
uint8_t pdu(uint8_t *out);

/**
 * Transmits the configured PDU once per selected channel.
 *
 * Blocking, and deliberately so: a transmission takes under 400 µs and the
 * whole point of this firmware is that the host knows exactly how many packets
 * went out and when. A queue would buy throughput nobody needs and cost the
 * certainty the measurement depends on.
 */
void transmitOnce();

}  // namespace beacon
