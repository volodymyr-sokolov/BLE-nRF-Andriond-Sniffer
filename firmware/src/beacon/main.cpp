// Reference beacon for the nRF52840, driven from the host over USB CDC.
//
// Implements the hardware half of §6 of the specification: a transmitter
// sending a payload the receiver already knows, so recovery can be measured
// against ground truth instead of against injected damage.
//
// Host -> device (one command per line, LF or CRLF terminated):
//   addr <aa:bb:cc:dd:ee:ff> [random]  advertiser address
//   data <hex>                         advertising data bytes
//   len <n>                            pad or truncate the data to n bytes
//   fill <hh>                          padding byte used by `len`
//   type <n>                           PDU type nibble; 2 = ADV_NONCONN_IND
//   ch 37|38|39|all                    channel, or all three per packet
//   power <dbm>                        one of +8..-40, see `levels`
//   levels                             list the supported power levels
//   send <count> [interval_ms]         transmit count packets at the set power
//   sweep <count> [interval_ms] [step] count packets at every level, high->low
//   stop                               abandon a run in progress
//   pdu                                re-emit the PDU that will be sent
//   ver / ping
//
// Device -> host:
//   $I,BLEBEACON,<fw>,<proto>,<board>
//   $U,<hexPdu>                        exactly what goes on air, header first
//   $L,<dbm>,<dbm>,...                 supported power levels
//   $B,<seq>,<power>,<channel>,<tUs>   one transmission burst went out
//   $R,<power>,<sent>                  a run at one power level finished
//   $E,<sent>                          the whole run finished
//   $A,<command>,<ok|err>,<detail>
//
// A burst is one packet per selected channel, so `ch all` sends three.

#include <Arduino.h>
// Pulls in the native USB CDC implementation behind `Serial`; without it the
// core's Adafruit_USBD_CDC symbols are never linked in.
#include <Adafruit_TinyUSB.h>

#include "ble_beacon.h"

#if BEACON_ADAPTIVE
// Only the `beacon_adaptive` environment compiles this. Everything the option
// adds lives behind it, so the plain `beacon` binary is the same transmitter it
// was before the experiment existed rather than the same transmitter with a
// switch in it that somebody has to be trusted to have left alone.
#include "airtime.h"
#endif

namespace {

const char FW_VERSION[] = "1.0.0";
const char PROTO_VERSION[] = "1";
const char BOARD_NAME[] = "feather_nrf52840";

const size_t MAX_LINE = 160;

char g_command[128];
size_t g_commandLen = 0;

const char HEX_DIGITS[] = "0123456789ABCDEF";

// --- run state ---------------------------------------------------------------

bool g_running = false;
bool g_sweeping = false;
uint32_t g_perLevel = 0;      // packets to send at each power level
uint32_t g_sentAtLevel = 0;
uint32_t g_sentTotal = 0;
uint32_t g_intervalMs = 20;
uint32_t g_nextDueMs = 0;
uint8_t g_levelIndex = 0;
uint8_t g_levelStep = 1;
uint32_t g_sequence = 0;

#if BEACON_ADAPTIVE

// --- adaptive run state ------------------------------------------------------

bool     g_adaptive = false;         // the option itself, off until asked for
uint8_t  g_advMask = AIRTIME_ALL;    // channels the operator asked for
uint32_t g_advNextDueUs = 0;         // microseconds: T_a is a multiple of 625 us
uint32_t g_advSinceSense = 0;        // events since the last §9 sensing pass
uint32_t g_advReplans = 0;

/** The payload length L that §2's T_pkt(L) is a function of: AdvA plus data. */
uint8_t ownPayloadLen() {
    uint8_t bytes[BEACON_MAX_PDU];
    return (uint8_t)(beacon::pdu(bytes) - 2);
}

uint8_t maskFor(BeaconChannel channel) {
    switch (channel) {
        case BEACON_CH_37: return AIRTIME_CH37;
        case BEACON_CH_38: return AIRTIME_CH38;
        case BEACON_CH_39: return AIRTIME_CH39;
        default: return AIRTIME_ALL;
    }
}

#endif  // BEACON_ADAPTIVE

// Output goes through TinyUSB's FIFO rather than through Serial, for the reason
// set out at length in the sniffer's main.cpp: Adafruit_USBD_CDC::write() loops
// on tud_cdc_n_connected(), which stays false until the host asserts DTR, so a
// host that never raises it gets total silence with nothing to explain it.
void cdcLine(const char *text) {
    const size_t length = strlen(text);
    size_t written = 0;
    while (written < length) {
        const uint32_t n = tud_cdc_write(text + written, (uint32_t)(length - written));
        if (n == 0) {
            break;
        }
        written += n;
    }
    tud_cdc_write_char('\n');
    tud_cdc_write_flush();
}

size_t appendU32(char *dst, uint32_t value) {
    char tmp[11];
    size_t n = 0;
    do {
        tmp[n++] = (char)('0' + (value % 10));
        value /= 10;
    } while (value != 0);
    for (size_t i = 0; i < n; i++) {
        dst[i] = tmp[n - 1 - i];
    }
    return n;
}

size_t appendI32(char *dst, int32_t value) {
    if (value < 0) {
        dst[0] = '-';
        return 1 + appendU32(dst + 1, (uint32_t)(-value));
    }
    return appendU32(dst, (uint32_t)value);
}

void sendAck(const char *command, bool ok, const char *detail) {
    char line[MAX_LINE];
    snprintf(line, sizeof(line), "$A,%s,%s,%s", command, ok ? "ok" : "err", detail);
    cdcLine(line);
}

void sendBanner() {
    char line[MAX_LINE];
    // The adaptive build appends a capability field rather than changing the
    // name. A host that only knows the old banner still matches on BLEBEACON
    // and still works; a host that cares which firmware it is talking to can
    // read the field instead of being told by whoever ran the flash command.
    snprintf(line, sizeof(line), "$I,BLEBEACON,%s,%s,%s%s",
             FW_VERSION, PROTO_VERSION, BOARD_NAME,
#if BEACON_ADAPTIVE
             ",adaptive"
#else
             ""
#endif
    );
    cdcLine(line);
}

/**
 * Emits the exact bytes that will go on air.
 *
 * The single most important line this firmware prints. Everything the
 * measurement concludes rests on the receiver being compared against what was
 * genuinely transmitted, so the transmitter states it rather than leaving the
 * host to reconstruct it from the settings it thinks it sent.
 */
void sendPdu() {
    uint8_t bytes[BEACON_MAX_PDU];
    const uint8_t length = beacon::pdu(bytes);

    char line[MAX_LINE];
    size_t n = 0;
    memcpy(line, "$U,", 3);
    n += 3;
    for (uint8_t i = 0; i < length; i++) {
        line[n++] = HEX_DIGITS[bytes[i] >> 4];
        line[n++] = HEX_DIGITS[bytes[i] & 0x0F];
    }
    line[n] = '\0';
    cdcLine(line);
}

void sendLevels() {
    uint8_t count = 0;
    const int8_t *levels = beacon::powerLevels(count);

    char line[MAX_LINE];
    size_t n = 0;
    memcpy(line, "$L", 2);
    n += 2;
    for (uint8_t i = 0; i < count; i++) {
        line[n++] = ',';
        n += appendI32(line + n, levels[i]);
    }
    line[n] = '\0';
    cdcLine(line);
}

const char *channelName(BeaconChannel channel) {
    switch (channel) {
        case BEACON_CH_37: return "37";
        case BEACON_CH_38: return "38";
        case BEACON_CH_39: return "39";
        default: return "all";
    }
}

#if BEACON_ADAPTIVE

// --- adaptive reporting ------------------------------------------------------
//
// Every quantity crosses as a scaled integer. The receiver of these lines is
// the analysis that the paper is written from, and a printed float is whatever
// two libm implementations happen to agree on, whereas an integer and a stated
// scale is the same number on both ends.

char *appendField(char *p, uint32_t value) {
    *p++ = ',';
    return p + appendU32(p, value);
}

char *appendSigned(char *p, int32_t value) {
    *p++ = ',';
    return p + appendI32(p, value);
}

/** What one channel's dwell measured, and what §2/§3 make of it. */
void sendMeasurement(uint8_t channel) {
    const AirtimeChannel *c = airtime::measured(channel);
    if (!c) {
        return;
    }
    char line[224];
    char *p = line;
    memcpy(p, "$K", 2);
    p += 2;
    p = appendField(p, c->channel);
    p = appendField(p, c->listenUs);
    p = appendField(p, c->packets);
    p = appendField(p, c->crcBad);
    p = appendField(p, c->airtimeUs);
    p = appendField(p, c->lambdaE3);      // arrivals/s x 1e3
    p = appendField(p, c->meanServiceUs); // E[S_k]
    p = appendField(p, c->rhoE6);         // rho_k  x 1e6
    p = appendField(p, c->loadE6);        // G_k    x 1e6
    p = appendField(p, c->pSuccE6);       // P_succ x 1e6
    p = appendField(p, c->busyE6);        // energy-detect busy fraction x 1e6
    p = appendSigned(p, c->peakRssi);
    // Appended last, so a host written against the earlier field list keeps
    // working: the G the planner acted on, which equals G_k unless the energy
    // detector was allowed to raise it.
    p = appendField(p, c->loadEffE6);
    *p = '\0';
    cdcLine(line);
}

/** The plan, and the constraint that produced it. */
void sendPlan() {
    const AirtimePlan &p = airtime::current();
    char line[224];
    char *q = line;
    memcpy(q, "$N", 2);
    q += 2;
    q = appendField(q, p.q);
    q = appendField(q, p.intervalUs);
    q = appendField(q, p.channelMask);
    q = appendField(q, p.droppedMask);
    q = appendField(q, p.phaseUs[0]);
    q = appendField(q, p.phaseUs[1]);
    q = appendField(q, p.phaseUs[2]);
    q = appendField(q, p.costE3);
    q = appendField(q, p.floorUs);
    q = appendField(q, p.forced ? 1u : 0u);
    *q = '\0';
    cdcLine(line);
}

void sendSetting(const char *key, int32_t value) {
    char line[MAX_LINE];
    snprintf(line, sizeof(line), "$G,%s,%ld", key, (long)value);
    cdcLine(line);
}

void sendConfig() {
    AirtimeConfig &c = airtime::config();
    sendSetting("adaptive", g_adaptive ? 1 : 0);
    sendSetting("dwell", (int32_t)c.senseMs);
    sendSetting("gstar", (int32_t)c.gStarE3);
    sendSetting("tamin", (int32_t)c.tAMinMs);
    sendSetting("tamax", (int32_t)c.tAMaxMs);
    sendSetting("period", (int32_t)c.periodMs);
    sendSetting("c1", (int32_t)c.c1);
    sendSetting("c2", (int32_t)c.c2);
    sendSetting("c3", (int32_t)c.c3);
    sendSetting("alpha", (int32_t)c.alphaE3);
    sendSetting("replan", (int32_t)c.replanE3);
    sendSetting("busy", (int32_t)c.busyDbm);
    sendSetting("resense", (int32_t)c.resenseEvents);
    sendSetting("usebusy", (int32_t)c.useBusy);
}

/**
 * Senses, plans, and anchors the first event on the chosen start phase.
 *
 * §7 calls phi_k the *start* phase, and that is the only place it can be
 * applied: §6 picks a prime T_a precisely so the transmission drifts through
 * every phase of any periodic interferer, so re-aligning to phi on every event
 * would undo the anti-lock-out rule that chose T_a in the first place. It is
 * applied once, to the first event of the run.
 */
void senseAndPlan(bool announce) {
    airtime::update(g_advMask, ownPayloadLen());
    if (announce) {
        for (uint8_t ch = 37; ch <= 39; ch++) {
            if (g_advMask & maskFor((BeaconChannel)ch)) {
                sendMeasurement(ch);
            }
        }
    }
    const AirtimePlan p = airtime::plan(g_advMask, ownPayloadLen());
    sendPlan();

    uint32_t phase = 0;
    for (int i = 0; i < 3; i++) {
        if (p.channelMask & (1u << i)) {
            phase = p.phaseUs[i];
            break;
        }
    }
    const uint32_t periodUs = airtime::config().periodMs * 1000;
    const uint32_t now = micros();
    const uint32_t delta = periodUs ? ((phase + periodUs - (now % periodUs)) % periodUs) : 0;
    g_advNextDueUs = now + delta;
    g_advSinceSense = 0;
}

/** Names the active channel set for the `$B` line; "all" keeps its meaning. */
const char *maskName(uint8_t mask) {
    switch (mask) {
        case AIRTIME_CH37: return "37";
        case AIRTIME_CH38: return "38";
        case AIRTIME_CH39: return "39";
        case (AIRTIME_CH37 | AIRTIME_CH38): return "37+38";
        case (AIRTIME_CH37 | AIRTIME_CH39): return "37+39";
        case (AIRTIME_CH38 | AIRTIME_CH39): return "38+39";
        case AIRTIME_ALL: return "all";
        default: return "none";
    }
}

#endif  // BEACON_ADAPTIVE

// --- parsing -----------------------------------------------------------------

int hexValue(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/** Reads hex, skipping ':' and '-' so an address can be pasted either way. */
int parseHex(const char *text, uint8_t *out, uint8_t maxBytes) {
    uint8_t count = 0;
    int high = -1;
    for (const char *p = text; *p; p++) {
        if (*p == ':' || *p == '-' || *p == ' ') continue;
        const int value = hexValue(*p);
        if (value < 0) return -1;
        if (high < 0) {
            high = value;
        } else {
            if (count >= maxBytes) return -1;
            out[count++] = (uint8_t)((high << 4) | value);
            high = -1;
        }
    }
    return high < 0 ? count : -1;  // an odd number of digits is a typo
}

void normalize(char *text, size_t &length) {
    while (length > 0 && (text[length - 1] == '\r' || text[length - 1] == ' ' ||
                          text[length - 1] == '\t')) {
        length--;
    }
    text[length] = '\0';
}

/** Splits "verb rest" and lowercases the verb only; hex keeps its case. */
char *splitVerb(char *text) {
    char *space = strchr(text, ' ');
    if (space) {
        *space = '\0';
        space++;
        while (*space == ' ') space++;
    }
    for (char *p = text; *p; p++) {
        *p = (char)tolower((unsigned char)*p);
    }
    return space;
}

// --- run control -------------------------------------------------------------

void beginRun(uint32_t perLevel, uint32_t intervalMs, bool sweep, uint8_t step) {
    g_running = true;
    g_sweeping = sweep;
    g_perLevel = perLevel;
    g_sentAtLevel = 0;
    g_sentTotal = 0;
    g_intervalMs = intervalMs;
    g_levelIndex = 0;
    g_levelStep = step == 0 ? 1 : step;
    g_nextDueMs = millis();

    if (sweep) {
        uint8_t count = 0;
        const int8_t *levels = beacon::powerLevels(count);
        beacon::setPower(levels[0]);
    }

#if BEACON_ADAPTIVE
    if (g_adaptive) {
        // The channel set the operator asked for is captured here, because the
        // run itself will be moving beacon::channel() around one channel at a
        // time to realise whatever map the planner chose.
        g_advMask = maskFor(beacon::channel());
        g_advReplans = 0;
        senseAndPlan(true);
    }
#endif
}

void finishRun() {
    g_running = false;
    char line[MAX_LINE];
    size_t n = 0;
    memcpy(line, "$E,", 3);
    n += 3;
    n += appendU32(line + n, g_sentTotal);
    line[n] = '\0';
    cdcLine(line);
}

void reportLevelDone() {
    char line[MAX_LINE];
    size_t n = 0;
    memcpy(line, "$R,", 3);
    n += 3;
    n += appendI32(line + n, beacon::power());
    line[n++] = ',';
    n += appendU32(line + n, g_sentAtLevel);
    line[n] = '\0';
    cdcLine(line);
}

#if BEACON_ADAPTIVE

/**
 * One advertising event under the plan, then the next one scheduled.
 *
 * Scheduling is in microseconds rather than milliseconds because T_a is a
 * multiple of 0.625 ms and rounding it to the millisecond would quietly undo
 * the prime-q choice of §6 - 31 slots is 19.375 ms, and 19 ms is not coprime to
 * anything in particular.
 */
void stepAdaptiveRun() {
    if ((int32_t)(micros() - g_advNextDueUs) < 0) {
        return;
    }
    if (tud_cdc_write_available() < 48) {
        return;
    }

    const AirtimePlan &plan = airtime::current();
    const BeaconChannel requested = beacon::channel();

    for (int i = 0; i < 3; i++) {
        if (!(plan.channelMask & (1u << i))) {
            continue;
        }
        // One channel at a time: transmitOnce() on BEACON_CH_ALL would send on
        // all three regardless of what the map decided, and the map deciding
        // is the entire option.
        beacon::setChannel((BeaconChannel)(37 + i));
        beacon::transmitOnce();
    }
    beacon::setChannel(requested);

    g_sentAtLevel++;
    g_sentTotal++;
    g_advSinceSense++;

    // §2's mandatory advDelay, U[0, 10 ms], on top of T_a. It is what makes the
    // train aperiodic enough that two advertisers cannot stay locked together,
    // and leaving it out would be measuring something the specification does
    // not permit a real device to do.
    g_advNextDueUs += plan.intervalUs + airtime::advDelayUs();

    char line[MAX_LINE];
    size_t n = 0;
    memcpy(line, "$B,", 3);
    n += 3;
    n += appendU32(line + n, g_sequence++);
    line[n++] = ',';
    n += appendI32(line + n, beacon::power());
    line[n++] = ',';
    const char *ch = maskName(plan.channelMask);
    memcpy(line + n, ch, strlen(ch));
    n += strlen(ch);
    line[n++] = ',';
    n += appendU32(line + n, micros());
    line[n] = '\0';
    cdcLine(line);

    if (g_sentTotal >= g_perLevel) {
        reportLevelDone();
        finishRun();
        return;
    }

    // §9's closed loop. Sensing costs a dwell per channel, which is a gap in
    // the transmission; that is the honest price of the option and it is paid
    // where the analysis can see it, in the timestamps.
    if (airtime::config().resenseEvents &&
        g_advSinceSense >= airtime::config().resenseEvents) {
        const uint32_t due = g_advNextDueUs;
        airtime::update(g_advMask, ownPayloadLen());
        g_advSinceSense = 0;
        if (airtime::shouldReplan()) {
            airtime::plan(g_advMask, ownPayloadLen());
            sendPlan();
            g_advReplans++;
        }
        // The sensing dwell has moved the clock on; re-anchor rather than fire
        // a burst of back-to-back events catching up on a schedule that ran
        // while the radio was listening.
        if ((int32_t)(micros() - due) > 0) {
            g_advNextDueUs = micros() + airtime::current().intervalUs;
        }
    }
}

#endif  // BEACON_ADAPTIVE

void stepRun() {
    if (!g_running) {
        return;
    }
#if BEACON_ADAPTIVE
    // Sweeping steps power levels and adapting steps the interval; combining
    // them would confound the two and neither the specification nor this
    // experiment asks for it, so adaptive runs are plain runs at one power.
    if (g_adaptive && !g_sweeping) {
        stepAdaptiveRun();
        return;
    }
#endif
    const uint32_t now = millis();
    if ((int32_t)(now - g_nextDueMs) < 0) {
        return;
    }
    // Leave room for the report line before transmitting, so a host that has
    // stopped reading slows the run down instead of losing the record of it.
    if (tud_cdc_write_available() < 48) {
        return;
    }
    g_nextDueMs = now + g_intervalMs;

    beacon::transmitOnce();
    g_sentAtLevel++;
    g_sentTotal++;

    char line[MAX_LINE];
    size_t n = 0;
    memcpy(line, "$B,", 3);
    n += 3;
    n += appendU32(line + n, g_sequence++);
    line[n++] = ',';
    n += appendI32(line + n, beacon::power());
    line[n++] = ',';
    const char *ch = channelName(beacon::channel());
    memcpy(line + n, ch, strlen(ch));
    n += strlen(ch);
    line[n++] = ',';
    n += appendU32(line + n, micros());
    line[n] = '\0';
    cdcLine(line);

    if (g_sentAtLevel < g_perLevel) {
        return;
    }

    reportLevelDone();
    g_sentAtLevel = 0;

    if (!g_sweeping) {
        finishRun();
        return;
    }

    uint8_t count = 0;
    const int8_t *levels = beacon::powerLevels(count);
    const uint16_t next = (uint16_t)g_levelIndex + g_levelStep;
    if (next >= count) {
        finishRun();
        return;
    }
    g_levelIndex = (uint8_t)next;
    beacon::setPower(levels[g_levelIndex]);
}

// --- commands ----------------------------------------------------------------

void handleCommand(char *text, size_t length) {
    normalize(text, length);
    if (length == 0) {
        return;
    }

    char *rest = splitVerb(text);

    if (strcmp(text, "ver") == 0) {
        sendBanner();
        sendPdu();
        return;
    }
    if (strcmp(text, "ping") == 0) {
        sendAck("ping", true, "pong");
        return;
    }
    if (strcmp(text, "pdu") == 0) {
        sendPdu();
        return;
    }
    if (strcmp(text, "levels") == 0) {
        sendLevels();
        return;
    }

    if (strcmp(text, "addr") == 0) {
        if (!rest) {
            sendAck("addr", false, "missing address");
            return;
        }
        // "random" may follow the address; find and strip it first.
        bool randomAddress = false;
        char *flag = strchr(rest, ' ');
        if (flag) {
            *flag = '\0';
            randomAddress = strstr(flag + 1, "random") != nullptr;
        }
        uint8_t bytes[BEACON_ADDRESS_LEN];
        if (parseHex(rest, bytes, BEACON_ADDRESS_LEN) != BEACON_ADDRESS_LEN) {
            sendAck("addr", false, "need 6 bytes");
            return;
        }
        beacon::setAddress(bytes, randomAddress);
        sendAck("addr", true, randomAddress ? "random" : "public");
        sendPdu();
        return;
    }

    if (strcmp(text, "data") == 0) {
        uint8_t bytes[BEACON_MAX_AD];
        const int n = rest ? parseHex(rest, bytes, BEACON_MAX_AD) : 0;
        if (n < 0) {
            sendAck("data", false, "bad hex");
            return;
        }
        if (!beacon::setData(bytes, (uint8_t)n, (uint8_t)n, 0x00)) {
            sendAck("data", false, "too long");
            return;
        }
        sendAck("data", true, "set");
        sendPdu();
        return;
    }

    if (strcmp(text, "len") == 0) {
        const int value = rest ? atoi(rest) : -1;
        if (value < 0 || value > BEACON_MAX_AD) {
            sendAck("len", false, "0..31");
            return;
        }
        // Re-pad the data already held, keeping whatever is still in range.
        uint8_t current[BEACON_MAX_PDU];
        const uint8_t pduLength = beacon::pdu(current);
        const uint8_t have = (uint8_t)(pduLength - 2 - BEACON_ADDRESS_LEN);
        beacon::setData(current + 2 + BEACON_ADDRESS_LEN, have, (uint8_t)value, 0x00);
        sendAck("len", true, "set");
        sendPdu();
        return;
    }

    if (strcmp(text, "type") == 0) {
        const int value = rest ? atoi(rest) : -1;
        if (value < 0 || value > 15) {
            sendAck("type", false, "0..15");
            return;
        }
        beacon::setPduType((uint8_t)value);
        sendAck("type", true, "set");
        sendPdu();
        return;
    }

    if (strcmp(text, "ch") == 0) {
        if (!rest) {
            sendAck("ch", false, "missing channel");
            return;
        }
        if (strcmp(rest, "all") == 0) {
            beacon::setChannel(BEACON_CH_ALL);
        } else {
            const int value = atoi(rest);
            if (value < 37 || value > 39) {
                sendAck("ch", false, "37|38|39|all");
                return;
            }
            beacon::setChannel((BeaconChannel)value);
        }
        sendAck("ch", true, channelName(beacon::channel()));
        return;
    }

    if (strcmp(text, "power") == 0) {
        const int value = rest ? atoi(rest) : 127;
        if (value < -128 || value > 127 || !beacon::powerSupported((int8_t)value)) {
            sendAck("power", false, "unsupported, see levels");
            return;
        }
        beacon::setPower((int8_t)value);
        sendAck("power", true, "set");
        return;
    }

    if (strcmp(text, "send") == 0 || strcmp(text, "sweep") == 0) {
        const bool sweep = text[1] == 'w';
        uint32_t count = 100;
        uint32_t interval = 20;
        uint32_t step = 1;
        if (rest) {
            count = (uint32_t)atoi(rest);
            char *second = strchr(rest, ' ');
            if (second) {
                interval = (uint32_t)atoi(second + 1);
                char *third = strchr(second + 1, ' ');
                if (third) {
                    step = (uint32_t)atoi(third + 1);
                }
            }
        }
        if (count == 0) {
            sendAck(sweep ? "sweep" : "send", false, "count must be > 0");
            return;
        }
        beginRun(count, interval, sweep, (uint8_t)step);
        sendAck(sweep ? "sweep" : "send", true, "started");
        return;
    }

    if (strcmp(text, "stop") == 0) {
        if (g_running) {
            g_running = false;
            finishRun();
        }
        sendAck("stop", true, "stopped");
        return;
    }

#if BEACON_ADAPTIVE

    if (strcmp(text, "adaptive") == 0) {
        if (!rest) {
            sendAck("adaptive", true, g_adaptive ? "on" : "off");
            return;
        }
        if (strcmp(rest, "on") == 0) {
            g_adaptive = true;
        } else if (strcmp(rest, "off") == 0) {
            g_adaptive = false;
        } else {
            sendAck("adaptive", false, "on|off");
            return;
        }
        sendAck("adaptive", true, g_adaptive ? "on" : "off");
        return;
    }

    if (strcmp(text, "sense") == 0) {
        // Sensing outside a run, so the medium can be characterised before
        // anything is decided from it - which is also how the "does adaptation
        // have anything to adapt to" question gets answered honestly.
        const uint8_t mask = maskFor(beacon::channel());
        if (rest) {
            const int ms = atoi(rest);
            if (ms > 0) {
                airtime::config().senseMs = (uint32_t)ms;
            }
        }
        airtime::update(mask, ownPayloadLen());
        for (uint8_t ch = 37; ch <= 39; ch++) {
            if (mask & maskFor((BeaconChannel)ch)) {
                sendMeasurement(ch);
            }
        }
        sendAck("sense", true, "done");
        return;
    }

    if (strcmp(text, "plan") == 0) {
        airtime::plan(maskFor(beacon::channel()), ownPayloadLen());
        sendPlan();
        sendAck("plan", true, "done");
        return;
    }

    if (strcmp(text, "cfg") == 0) {
        if (!rest) {
            sendConfig();
            sendAck("cfg", true, "listed");
            return;
        }
        char *value = strchr(rest, ' ');
        if (!value) {
            sendAck("cfg", false, "need key and value");
            return;
        }
        *value = '\0';
        const int32_t v = atol(value + 1);
        AirtimeConfig &c = airtime::config();
        if (strcmp(rest, "dwell") == 0)        c.senseMs = (uint32_t)v;
        else if (strcmp(rest, "gstar") == 0)   c.gStarE3 = (uint32_t)v;
        else if (strcmp(rest, "tamin") == 0)   c.tAMinMs = (uint32_t)v;
        else if (strcmp(rest, "tamax") == 0)   c.tAMaxMs = (uint32_t)v;
        else if (strcmp(rest, "period") == 0)  c.periodMs = (uint32_t)v;
        else if (strcmp(rest, "c1") == 0)      c.c1 = (uint32_t)v;
        else if (strcmp(rest, "c2") == 0)      c.c2 = (uint32_t)v;
        else if (strcmp(rest, "c3") == 0)      c.c3 = (uint32_t)v;
        else if (strcmp(rest, "alpha") == 0)   c.alphaE3 = (uint32_t)v;
        else if (strcmp(rest, "replan") == 0)  c.replanE3 = (uint32_t)v;
        else if (strcmp(rest, "busy") == 0)    c.busyDbm = (int8_t)v;
        else if (strcmp(rest, "resense") == 0) c.resenseEvents = (uint32_t)v;
        else if (strcmp(rest, "usebusy") == 0) c.useBusy = (uint32_t)v;
        else {
            sendAck("cfg", false, "unknown key");
            return;
        }
        sendSetting(rest, v);
        sendAck("cfg", true, "set");
        return;
    }

#endif  // BEACON_ADAPTIVE

    sendAck(text, false, "unknown command");
}

void pollCommands() {
    while (tud_cdc_available()) {
        const int c = tud_cdc_read_char();
        if (c < 0) {
            break;
        }
        if (c == '\n') {
            handleCommand(g_command, g_commandLen);
            g_commandLen = 0;
            continue;
        }
        if (g_commandLen + 1 < sizeof(g_command)) {
            g_command[g_commandLen++] = (char)c;
        }
    }
}

}  // namespace

void setup() {
    Serial.begin(115200);
    beacon::init();
#if BEACON_ADAPTIVE
    // After beacon::init(), which owns the radio configuration the sensing
    // dwells borrow.
    airtime::init();
#endif

    pinMode(LED_BUILTIN, OUTPUT);
    digitalWrite(LED_BUILTIN, LOW);

    // The host may not be listening yet; the banner is re-requestable with
    // `ver`, which is what the control script actually relies on.
    delay(200);
    sendBanner();
    sendPdu();
}

void loop() {
    pollCommands();
    stepRun();
    digitalWrite(LED_BUILTIN, g_running ? HIGH : LOW);
}
