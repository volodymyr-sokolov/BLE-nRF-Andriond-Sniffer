#include "airtime.h"

#include <math.h>
#include <nrf.h>
#include <string.h>

namespace {

// The three primary advertising channels, in the order the mask bits run.
const uint8_t CHANNELS[3] = {37, 38, 39};

// §4. Switchover time between stations of the polling server. 150 microseconds
// is the document's figure and is what an advertising event actually costs
// between channels; it enters the energy term of §6's cost function.
const uint32_t SWITCHOVER_US = 150;

// §2. advDelay is U[0, 10 ms] and is not optional - the specification requires
// it, and §7 turns on the fact that it smears anything finer than 10 ms.
const uint32_t ADV_DELAY_MAX_US = 10000;

// T_a is expressed in units of 0.625 ms, and the range is the specification's.
const uint32_t SLOT_US = 625;
const uint16_t Q_MIN = 32;     // 20 ms
const uint16_t Q_MAX = 16384;  // 10.24 s

// EasyDMA writes here, so it is word aligned; 2 byte header plus the 37 byte
// maximum advertising payload the radio is configured to accept.
uint8_t g_rxBuffer[40] __attribute__((aligned(4)));

AirtimeChannel g_measured[3];
AirtimePlan    g_plan;
AirtimeConfig  g_config;

// §9. The smoothed estimate, kept across sensing passes, and the largest
// relative move any channel has made since the plan in force was computed.
uint32_t g_smoothedLambdaE3[3];
bool     g_haveSmoothed[3];
uint32_t g_maxRelativeMoveE3;

uint8_t frequencyFor(uint8_t channel) {
    switch (channel) {
        case 37: return 2;   // 2402 MHz
        case 38: return 26;  // 2426 MHz
        default: return 80;  // 2480 MHz, channel 39
    }
}

int indexOf(uint8_t channel) {
    for (int i = 0; i < 3; i++) {
        if (CHANNELS[i] == channel) {
            return i;
        }
    }
    return -1;
}

uint32_t rng8() {
    NRF_RNG->EVENTS_VALRDY = 0;
    NRF_RNG->TASKS_START = 1;
    for (uint32_t spin = 0; spin < 100000 && !NRF_RNG->EVENTS_VALRDY; spin++) {
        __NOP();
    }
    const uint32_t value = NRF_RNG->VALUE;
    NRF_RNG->EVENTS_VALRDY = 0;
    return value & 0xFF;
}

bool isPrime(uint32_t n) {
    if (n < 2) return false;
    if (n % 2 == 0) return n == 2;
    for (uint32_t d = 3; d * d <= n; d += 2) {
        if (n % d == 0) return false;
    }
    return true;
}

/** One RSSI sample, or 0 if the radio did not produce one in time. */
int8_t sampleRssi() {
    NRF_RADIO->EVENTS_RSSIEND = 0;
    NRF_RADIO->TASKS_RSSISTART = 1;
    for (uint32_t spin = 0; spin < 2000 && !NRF_RADIO->EVENTS_RSSIEND; spin++) {
        __NOP();
    }
    if (!NRF_RADIO->EVENTS_RSSIEND) {
        return 0;
    }
    NRF_RADIO->EVENTS_RSSIEND = 0;
    return -(int8_t)(NRF_RADIO->RSSISAMPLE & 0x7F);
}

/**
 * Listens on one channel for the configured dwell and fills [out].
 *
 * Everything the medium does is counted, not only what decodes: packets whose
 * CRC fails are still air-time and are very often the collisions the whole
 * model is about, and the periodic energy samples catch interference that never
 * produces a BLE packet at all - which is exactly what a bench generator is.
 */
void dwell(AirtimeChannel &out, uint8_t channel) {
    memset(&out, 0, sizeof(out));
    out.channel = channel;
    out.peakRssi = -128;

    NRF_RADIO->RXADDRESSES = 1UL;  // logical address 0; the beacon set BASE0
    NRF_RADIO->FREQUENCY   = frequencyFor(channel);
    NRF_RADIO->DATAWHITEIV = channel & 0x3F;
    NRF_RADIO->PACKETPTR   = (uint32_t)g_rxBuffer;

    NRF_RADIO->EVENTS_READY = 0;
    NRF_RADIO->EVENTS_END = 0;
    NRF_RADIO->EVENTS_DISABLED = 0;
    NRF_RADIO->SHORTS = RADIO_SHORTS_READY_START_Msk;
    NRF_RADIO->TASKS_RXEN = 1;

    const uint32_t periodUs = g_config.periodMs * 1000;
    const uint32_t binUs = periodUs / AIRTIME_BINS;
    const uint32_t started = micros();
    const uint32_t dwellUs = g_config.senseMs * 1000;
    uint32_t nextEnergyUs = started;

    while ((uint32_t)(micros() - started) < dwellUs) {
        if (NRF_RADIO->EVENTS_END) {
            NRF_RADIO->EVENTS_END = 0;
            const uint32_t at = micros();

            // The radio is parked in RXIDLE until we start it again, so the
            // length byte belongs to the packet that just finished and cannot
            // have been overwritten by the next one.
            const uint8_t payloadLen = g_rxBuffer[1];
            const bool crcOk =
                (NRF_RADIO->CRCSTATUS & RADIO_CRCSTATUS_CRCSTATUS_Msk) != 0;

            NRF_RADIO->TASKS_START = 1;  // back on air before anything slower

            out.packets++;
            if (!crcOk) {
                out.crcBad++;
            }
            out.airtimeUs += airtime::packetAirtimeUs(payloadLen);
            if (binUs > 0) {
                out.occupancy[((at - started) % periodUs) / binUs]++;
            }
            continue;
        }

        // §2's load is a fraction of time, so the energy detector samples at a
        // fixed cadence: an occupancy estimate weighted by how busy the poll
        // loop happened to be would measure this firmware, not the channel.
        const uint32_t now = micros();
        if ((int32_t)(now - nextEnergyUs) >= 0) {
            nextEnergyUs = now + 200;
            const int8_t rssi = sampleRssi();
            if (rssi != 0) {
                out.energySamples++;
                if (rssi > out.peakRssi) {
                    out.peakRssi = rssi;
                }
                if (rssi >= g_config.busyDbm) {
                    out.busySamples++;
                }
            }
        }
    }

    out.listenUs = (uint32_t)(micros() - started);

    NRF_RADIO->SHORTS = 0;
    NRF_RADIO->EVENTS_DISABLED = 0;
    NRF_RADIO->TASKS_DISABLE = 1;
    for (uint32_t spin = 0; spin < 100000 && !NRF_RADIO->EVENTS_DISABLED; spin++) {
        __NOP();
    }
    NRF_RADIO->EVENTS_DISABLED = 0;
    NRF_RADIO->EVENTS_END = 0;
}

/** §2 and §3, from the counts a dwell produced. */
void derive(AirtimeChannel &c, uint32_t ownAirtimeUs) {
    if (c.listenUs == 0) {
        return;
    }
    // lambda in arrivals per second, kept as milli-arrivals to stay integral.
    c.lambdaE3 = (uint32_t)(((uint64_t)c.packets * 1000000000ULL) / c.listenUs);
    c.meanServiceUs = c.packets ? (c.airtimeUs / c.packets) : 0;

    // rho_k = lambda_k E[S_k] is just the fraction of the dwell that was
    // occupied by foreign air-time, which is what was measured directly.
    c.rhoE6 = (uint32_t)(((uint64_t)c.airtimeUs * 1000000ULL) / c.listenUs);

    // G_k = lambda_k T_pkt^own: the offered load our own packet represents
    // against this channel's arrival rate. §6's constraint is written in it.
    c.loadE6 = (uint32_t)(((uint64_t)c.packets * ownAirtimeUs * 1000000ULL) / c.listenUs);

    // §3, the unslotted-ALOHA vulnerability window, ahead and behind.
    const float lambdaPerUs = (float)c.packets / (float)c.listenUs;
    const float window = (float)ownAirtimeUs + (float)c.meanServiceUs;
    c.pSuccE6 = (uint32_t)(expf(-lambdaPerUs * window) * 1000000.0f + 0.5f);

    c.busyE6 = c.energySamples
                   ? (uint32_t)(((uint64_t)c.busySamples * 1000000ULL) / c.energySamples)
                   : 0;

    // The queueing model can only count occupancy it can demodulate. When the
    // energy detector says the channel is busier than the decoded arrivals
    // account for, the difference is real interference that lambda_k is blind
    // to, and planning against the smaller of two estimates of the same
    // fraction is planning against the one that is known to be incomplete.
    //
    // rho_eff carries back to G by the same ratio that relates them:
    // G = lambda T_own and rho = lambda E[S], so G = rho T_own / E[S].
    if (g_config.useBusy && c.busyE6 > c.rhoE6) {
        const uint32_t meanS = c.meanServiceUs ? c.meanServiceUs : ownAirtimeUs;
        c.loadEffE6 = (uint32_t)(((uint64_t)c.busyE6 * ownAirtimeUs) / meanS);
    } else {
        c.loadEffE6 = c.loadE6;
    }
}

/** §7: circular box filter of the advDelay width, then the emptiest bin. */
uint32_t bestPhaseUs(const AirtimeChannel &c) {
    const uint32_t periodUs = g_config.periodMs * 1000;
    const uint32_t binUs = periodUs / AIRTIME_BINS;
    if (binUs == 0) {
        return 0;
    }
    // The kernel is U[0, 10 ms] regardless of the period, so its width in bins
    // follows the bin size rather than being a constant.
    uint32_t kernel = ADV_DELAY_MAX_US / binUs;
    if (kernel == 0) kernel = 1;
    if (kernel > AIRTIME_BINS) kernel = AIRTIME_BINS;

    uint32_t best = 0;
    uint32_t bestSum = 0xFFFFFFFFUL;
    for (uint32_t start = 0; start < AIRTIME_BINS; start++) {
        uint32_t sum = 0;
        for (uint32_t j = 0; j < kernel; j++) {
            sum += c.occupancy[(start + j) % AIRTIME_BINS];
        }
        if (sum < bestSum) {
            bestSum = sum;
            best = start;
        }
    }
    return best * binUs;
}

}  // namespace

namespace airtime {

void init() {
    memset(g_measured, 0, sizeof(g_measured));
    memset(&g_plan, 0, sizeof(g_plan));
    memset(g_smoothedLambdaE3, 0, sizeof(g_smoothedLambdaE3));
    memset(g_haveSmoothed, 0, sizeof(g_haveSmoothed));
    g_maxRelativeMoveE3 = 0;

    for (int i = 0; i < 3; i++) {
        g_measured[i].channel = CHANNELS[i];
    }

    g_config.senseMs       = 200;
    g_config.gStarE3       = 100;    // G* = 0.1, the §3 reliability target
    g_config.tAMinMs       = 20;     // the specification's floor
    g_config.tAMaxMs       = 100;    // bounds the wall clock of a fixed-count run
    g_config.periodMs      = 100;
    g_config.c1            = 100;    // 1.00
    g_config.c2            = 100;
    g_config.c3            = 100;
    g_config.alphaE3       = 300;    // 0.3
    g_config.replanE3      = 250;    // re-plan on a 25% move in lambda
    g_config.busyDbm       = -75;
    g_config.useBusy       = 0;
    g_config.resenseEvents = 500;

    NRF_RNG->CONFIG = RNG_CONFIG_DERCEN_Enabled << RNG_CONFIG_DERCEN_Pos;

    // A plan that transmits on everything at the floor interval, so a run that
    // starts before any sensing behaves like the plain firmware rather than
    // like whatever zeroed memory happens to mean.
    g_plan.q = 32;
    g_plan.intervalUs = 32 * SLOT_US;
    g_plan.channelMask = AIRTIME_ALL;
}

AirtimeConfig &config() {
    return g_config;
}

uint32_t packetAirtimeUs(uint8_t payloadLen) {
    // §2: 1 B preamble + 4 access address + 2 header + L + 3 CRC, at 1 Mbit.
    return 8UL * (10UL + (uint32_t)payloadLen);
}

uint32_t advDelayUs() {
    // Two bytes of entropy scaled to [0, 10 ms]. The scaling is a multiply
    // rather than a modulus so the distribution stays flat.
    const uint32_t raw = (rng8() << 8) | rng8();
    return (uint32_t)(((uint64_t)raw * ADV_DELAY_MAX_US) / 65536ULL);
}

void sense(uint8_t mask, uint32_t ownPayloadLen) {
    const uint32_t ownAirtimeUs = packetAirtimeUs((uint8_t)ownPayloadLen);
    for (int i = 0; i < 3; i++) {
        if (mask & (1u << i)) {
            dwell(g_measured[i], CHANNELS[i]);
            derive(g_measured[i], ownAirtimeUs);
        }
    }
}

const AirtimeChannel *measured(uint8_t channel) {
    const int index = indexOf(channel);
    return index < 0 ? nullptr : &g_measured[index];
}

void update(uint8_t mask, uint32_t ownPayloadLen) {
    sense(mask, ownPayloadLen);
    for (int i = 0; i < 3; i++) {
        if (!(mask & (1u << i))) {
            continue;
        }
        const uint32_t observed = g_measured[i].lambdaE3;
        if (!g_haveSmoothed[i]) {
            g_smoothedLambdaE3[i] = observed;
            g_haveSmoothed[i] = true;
            continue;
        }
        const uint32_t previous = g_smoothedLambdaE3[i];
        const uint32_t a = g_config.alphaE3;
        g_smoothedLambdaE3[i] =
            (uint32_t)(((uint64_t)a * observed + (uint64_t)(1000 - a) * previous) / 1000);

        // §9's re-plan trigger, measured against the smoothed value rather than
        // the raw one so a single noisy dwell cannot force a re-plan.
        if (previous > 0) {
            const uint32_t delta = g_smoothedLambdaE3[i] > previous
                                       ? g_smoothedLambdaE3[i] - previous
                                       : previous - g_smoothedLambdaE3[i];
            const uint32_t relative = (uint32_t)(((uint64_t)delta * 1000ULL) / previous);
            if (relative > g_maxRelativeMoveE3) {
                g_maxRelativeMoveE3 = relative;
            }
        }
    }
}

bool shouldReplan() {
    return g_maxRelativeMoveE3 >= g_config.replanE3;
}

AirtimePlan plan(uint8_t wanted, uint32_t ownPayloadLen) {
    const uint32_t ownAirtimeUs = packetAirtimeUs((uint8_t)ownPayloadLen);

    AirtimePlan p;
    memset(&p, 0, sizeof(p));

    // §6's channel map: a channel whose foreign load already meets the design
    // target has no room for us, and the document is explicit that the answer
    // is to stop using it rather than to slow down for it.
    uint8_t active = 0;
    for (int i = 0; i < 3; i++) {
        if (!(wanted & (1u << i))) {
            continue;
        }
        if (g_measured[i].loadEffE6 / 1000 < g_config.gStarE3) {
            active |= (uint8_t)(1u << i);
        } else {
            p.droppedMask |= (uint8_t)(1u << i);
        }
    }

    // Dropping every channel would end the experiment rather than adapt it, so
    // the quietest one is kept and the fact that it should not have been is
    // reported instead of being buried.
    if (active == 0 && wanted != 0) {
        int quietest = -1;
        for (int i = 0; i < 3; i++) {
            if (!(wanted & (1u << i))) continue;
            if (quietest < 0 || g_measured[i].loadEffE6 < g_measured[quietest].loadEffE6) {
                quietest = i;
            }
        }
        active = (uint8_t)(1u << quietest);
        p.droppedMask &= (uint8_t)~active;
        p.forced = true;
    }
    p.channelMask = active;

    // §6's floor: T_a >= 3 T_pkt / (G* - G_foreign) on every channel still in
    // use, taken as the tightest of them.
    uint32_t floorUs = g_config.tAMinMs * 1000;
    uint32_t activeCount = 0;
    for (int i = 0; i < 3; i++) {
        if (!(active & (1u << i))) continue;
        activeCount++;
        const uint32_t foreignE3 = g_measured[i].loadEffE6 / 1000;
        if (foreignE3 >= g_config.gStarE3) {
            floorUs = g_config.tAMaxMs * 1000;  // forced channel: back all the way off
            continue;
        }
        const uint32_t headroomE3 = g_config.gStarE3 - foreignE3;
        const uint32_t required =
            (uint32_t)(((uint64_t)3 * ownAirtimeUs * 1000ULL) / headroomE3);
        if (required > floorUs) {
            floorUs = required;
        }
    }
    if (activeCount == 0) activeCount = 1;
    p.floorUs = floorUs;

    // §6's cost function over the admissible periods. Every term is made
    // dimensionless before the weights are applied - the document writes J up
    // to the scaling of c1, c2 and c3, and leaving them in mixed units would
    // mean the weights expressed nothing an operator could reason about.
    float pBar = 0.0f;
    for (int i = 0; i < 3; i++) {
        if (active & (1u << i)) {
            pBar += (float)g_measured[i].pSuccE6 / 1000000.0f;
        }
    }
    pBar /= (float)activeCount;
    if (pBar < 0.001f) pBar = 0.001f;

    const uint32_t eventEnergyUs =
        activeCount * ownAirtimeUs + (activeCount - 1) * SWITCHOVER_US;

    uint32_t qLow = (uint32_t)((floorUs + SLOT_US - 1) / SLOT_US);
    if (qLow < Q_MIN) qLow = Q_MIN;
    uint32_t qHigh = (g_config.tAMaxMs * 1000) / SLOT_US;
    if (qHigh > Q_MAX) qHigh = Q_MAX;
    if (qHigh < qLow) qHigh = qLow;  // the floor outranks the budget; say so by obeying it

    const float latencyScale = (float)(qHigh * SLOT_US) + 5000.0f;
    uint16_t bestQ = 0;
    float bestCost = 0.0f;

    for (uint32_t q = qLow; q <= qHigh; q++) {
        // §6's anti-lock-out rule. A prime q is coprime to every foreign period
        // that is not a multiple of it, which maximises lcm(T_a, T_j) and makes
        // the relative phase drift through the whole cycle instead of parking
        // on a collision.
        if (!isPrime(q)) {
            continue;
        }
        const float tA = (float)(q * SLOT_US);
        const float latency = ((tA + 5000.0f) / pBar) / latencyScale;
        const float medium  = (float)activeCount * (float)ownAirtimeUs / tA;
        const float energy  = (float)eventEnergyUs / tA;
        const float cost = ((float)g_config.c1 * latency +
                            (float)g_config.c2 * medium +
                            (float)g_config.c3 * energy) / 100.0f;
        if (bestQ == 0 || cost < bestCost) {
            bestQ = (uint16_t)q;
            bestCost = cost;
        }
    }
    if (bestQ == 0) {
        // No prime in range at all only happens for a degenerate window; the
        // floor is the safe answer because it is the constraint, not the
        // preference.
        bestQ = (uint16_t)qLow;
        bestCost = 0.0f;
    }

    p.q = bestQ;
    p.intervalUs = (uint32_t)bestQ * SLOT_US;
    p.costE3 = (uint32_t)(bestCost * 1000.0f + 0.5f);

    for (int i = 0; i < 3; i++) {
        p.phaseUs[i] = (active & (1u << i)) ? bestPhaseUs(g_measured[i]) : 0;
    }

    g_plan = p;
    g_maxRelativeMoveE3 = 0;
    return p;
}

const AirtimePlan &current() {
    return g_plan;
}

}  // namespace airtime
