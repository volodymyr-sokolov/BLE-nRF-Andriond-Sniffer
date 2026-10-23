// Airtime-aware transmission planning for the reference beacon.
//
// This is the *option* half of the experiment: the same beacon, but sensing the
// medium before it uses it and choosing its advertising interval, channel map
// and start phase from what it heard. It is compiled only into the
// `beacon_adaptive` environment, so the plain `beacon` firmware is provably the
// unmodified transmitter it always was and the two arms of the experiment
// differ by a whole binary rather than by a runtime flag nobody can see.
//
// Everything here follows `ble-advertising-queueing-theory.md`. The section
// numbers in the comments are that document's, and where an implementation is
// coarser than the theory it says so rather than pretending otherwise.
//
//   §2  air-time and load          lambda_k, E[S_k], rho_k, G_k
//   §3  collision model            P_succ,k = exp(-lambda_k (T_own + E[S_k]))
//   §6  periodicity                J(T_a) minimised over prime q, subject to
//                                  T_a >= 3 T_pkt / (G* - G_foreign)
//   §7  start phase                occupancy histogram convolved with the
//                                  advDelay kernel, argmin over the period
//   §9  closed loop                EWMA on lambda, re-plan on relative change
//
// Fixed point throughout. The nRF52840 has an FPU, but the values cross the USB
// link as text and a scaled integer is the same number on both sides of it,
// where a printed float is whatever the two libraries happen to agree on.

#pragma once

#include <Arduino.h>

// Bits of a channel mask. 37 is bit 0, so the mask reads in channel order.
static const uint8_t AIRTIME_CH37 = 0x01;
static const uint8_t AIRTIME_CH38 = 0x02;
static const uint8_t AIRTIME_CH39 = 0x04;
static const uint8_t AIRTIME_ALL  = 0x07;

// Bins of the §7 occupancy histogram. 2.5 ms at the default 100 ms period, so
// the mandatory U[0,10 ms] advDelay kernel spans four of them and the
// convolution in §7 is a four-bin circular box filter.
static const uint8_t AIRTIME_BINS = 40;

/** What one dwell on one channel measured. Raw counts, plus §2/§3 derived. */
struct AirtimeChannel {
    uint8_t  channel;        // 37, 38 or 39

    // Measured.
    uint32_t listenUs;       // time actually spent in RX on this channel
    uint32_t packets;        // PDUs demodulated, CRC good or bad
    uint32_t crcBad;         // of those, ones whose CRC did not check out
    uint32_t airtimeUs;      // sum of T_pkt(L) over the packets seen
    uint32_t energySamples;  // RSSI samples taken
    uint32_t busySamples;    // of those, above the energy threshold
    int8_t   peakRssi;       // strongest sample in the dwell

    // Derived, §2 and §3. Scaled: see the accessors below.
    uint32_t lambdaE3;       // arrivals per second, x 1e3
    uint32_t meanServiceUs;  // E[S_k], mean air-time of a foreign packet
    uint32_t rhoE6;          // rho_k = lambda_k E[S_k], x 1e6
    uint32_t loadE6;         // G_k   = lambda_k T_own,  x 1e6
    uint32_t loadEffE6;      // G_k the planner acts on; see AirtimeConfig.useBusy
    uint32_t pSuccE6;        // P_succ,k, x 1e6
    uint32_t busyE6;         // fraction of energy samples over threshold, x 1e6

    // §7. Arrival times folded modulo the observation period.
    uint16_t occupancy[AIRTIME_BINS];
};

/** What the planner decided, and enough of why to defend it afterwards. */
struct AirtimePlan {
    uint16_t q;              // T_a in units of 0.625 ms, prime (§6)
    uint32_t intervalUs;     // T_a itself
    uint8_t  channelMask;    // channels that survived the G* test (§6)
    uint8_t  droppedMask;    // channels the G* test removed
    uint32_t phaseUs[3];     // phi_k*, indexed 0..2 for channels 37..39 (§7)
    uint32_t costE3;         // J(T_a) at the chosen T_a, x 1e3
    uint32_t floorUs;        // the binding 3 T_pkt / (G* - G_foreign) bound
    bool     forced;         // true when every channel failed and one was kept
};

/** Everything the host can turn. Defaults are the document's own numbers. */
struct AirtimeConfig {
    uint32_t senseMs;        // dwell per channel in a sensing pass
    uint32_t gStarE3;        // G*, x 1e3. 100 == 0.1, the §3 design target
    uint32_t tAMinMs;        // lower clamp on T_a; 20 ms is the BLE floor
    uint32_t tAMaxMs;        // upper clamp; bounds how long a run can take
    uint32_t periodMs;       // P-hat for the §7 histogram
    uint32_t c1, c2, c3;     // §6 cost weights, x 100
    uint32_t alphaE3;        // §9 EWMA factor, x 1e3
    uint32_t replanE3;       // §9 re-plan threshold on |dlambda|/lambda, x 1e3
    int8_t   busyDbm;        // energy-detect threshold for the busy fraction

    // Whether the energy detector is allowed to drive the plan, not merely be
    // reported alongside it. Off by default, because the document's model is
    // written in terms of packet arrivals and running it on something else by
    // default would mean the firmware was not doing what its citations say.
    //
    // Turn it on when the interference does not decode. A carrier from a signal
    // generator, a microwave oven, a non-BLE radio: all of them occupy the
    // channel and none of them produce an arrival for lambda_k to count, so
    // with this off the planner correctly measures no load and correctly
    // decides to do nothing, about a channel that is in fact unusable.
    uint32_t useBusy;
    uint32_t resenseEvents;  // re-sense every this many advertising events
};

namespace airtime {

void init();

AirtimeConfig &config();

/**
 * Dwells on each channel in [mask] and fills in the measurements.
 *
 * [ownPayloadLen] is needed here rather than only at planning time because
 * G_k and P_succ,k are both defined against our own packet's air-time, so a
 * measurement reported without it would be half a measurement - which is
 * exactly what a bare `sense` produced before it was passed in.
 *
 * The radio is left disabled afterwards, so the caller can transmit
 * immediately. Receiving is polled rather than interrupt driven: the beacon has
 * nothing else to do during a sensing window, and a poll loop that restarts the
 * radio by hand cannot have a second packet overwrite the first one's length
 * before it has been read, which is the failure the sniffer's own comments
 * describe having to design around.
 */
void sense(uint8_t mask, uint32_t ownPayloadLen);

/** The measurements for one channel, 37..39; nullptr for anything else. */
const AirtimeChannel *measured(uint8_t channel);

/**
 * Folds a fresh sensing pass into the running estimate (§9).
 *
 * Inverse-probability weighting is not needed here and is deliberately absent:
 * the document's warning applies to a *scanner* that splits its time unevenly
 * between channels and must divide raw counts by f_k before comparing them.
 * This senses each channel for the same dwell, so f_k is equal by construction
 * and there is nothing to correct for.
 */
void update(uint8_t mask, uint32_t ownPayloadLen);

/** True when §9's relative-change threshold has tripped since the last plan. */
bool shouldReplan();

/**
 * Chooses T_a, the channel map and the start phases from what was measured.
 *
 * [wanted] is the channel mask the operator asked for; the planner may remove
 * channels from it (§6) but never adds one, because a run is supposed to
 * measure the channels it says it measured.
 */
AirtimePlan plan(uint8_t wanted, uint32_t ownPayloadLen);

/** The plan in force. */
const AirtimePlan &current();

/** The mandatory advDelay of §2, U[0, 10 ms], from the hardware RNG. */
uint32_t advDelayUs();

/** T_pkt(L) = 8 (10 + L) microseconds, §2. */
uint32_t packetAirtimeUs(uint8_t payloadLen);

}  // namespace airtime
