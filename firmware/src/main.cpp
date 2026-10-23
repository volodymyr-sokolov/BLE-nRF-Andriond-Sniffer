// BLE advertising sniffer - Adafruit Feather nRF52840 Express.
//
// Drives the RADIO peripheral directly (the SoftDevice is never enabled) and
// streams every advertising PDU it hears over the native USB CDC port using a
// line oriented ASCII protocol. The protocol is deliberately human readable so
// the board can be driven from `pio device monitor` without the Android app.
//
// Host -> device (one command per line, LF or CRLF terminated):
//   start          begin receiving on the currently selected channel
//   stop           power the radio down; no further $P lines are emitted
//   ch 37|38|39    listen on a single advertising channel
//   ch all         round-robin across 37/38/39
//   ver            re-emit the identification banner
//   reset          zero the packet counters
//   raw on|off     raw capture mode (see below); default off
//   ping           liveness check
//
// Device -> host:
//   $I,BLESNIFF,<fw>,<proto>,<board>,<mode>
//   $P,<seq>,<tUs>,<channel>,<rssi>,<crc>,<hexPdu>
//   $R,<seq>,<tUs>,<channel>,<rssi>,<hexRaw>
//   $S,<running>,<channel>,<received>,<crcErrors>,<dropped>,<queued>,<tUs>,<missed>
//   $A,<command>,<ok|err>,<detail>
//
// $P fields: seq is a monotonic counter, tUs is a 64 bit microsecond timestamp
// latched in hardware at the end of the packet, rssi is dBm, crc is 1 when the
// CRC matched and 0 when it did not (bad packets are still reported), and
// hexPdu is the raw PDU starting at the 2 byte header.
//
// $R is raw capture mode (specification requirements F2 and F4): a fixed 42
// bytes - 2 header, 37 payload, 3 CRC - captured with whitening and CRC checking
// both disabled. The bytes are exactly as they arrived, still whitened, so the
// host dewhitens with the channel index and computes the CRC itself. There is no
// crc field in the line because the board no longer knows: that is the point.
// Everything algebraic about recovery needs these bytes.

#include <Arduino.h>
// Pulls in the native USB CDC implementation behind `Serial`. Without this the
// core's Adafruit_USBD_CDC symbols are never linked in.
#include <Adafruit_TinyUSB.h>

#include "ble_sniffer.h"

namespace {

const char FW_VERSION[] = "1.2.0";
const char PROTO_VERSION[] = "1";
const char BOARD_NAME[] = "feather_nrf52840";

const uint32_t STATUS_INTERVAL_MS = 1000;

// Worst case $P line: prefix + counters + 39 bytes of hex + separators.
const size_t MAX_LINE = 176;

char g_command[64];
size_t g_commandLen = 0;

uint32_t g_sequence = 0;
uint32_t g_lastStatusMs = 0;
uint32_t g_ledOffAtMs = 0;

// Not named HEX: Arduino's Print.h already defines that as a numeric base.
const char HEX_DIGITS[] = "0123456789ABCDEF";

// Output goes through TinyUSB's FIFO rather than through Serial.
//
// Adafruit_USBD_CDC::write() loops on `tud_cdc_n_connected()`, which is only
// true once the host has asserted DTR, so it silently transmits nothing to a
// host that never raises it - no banner, no status, no packets. Android's USB
// stack is such a host. Writing into the FIFO directly keeps the backpressure
// (it still fills up if nobody reads) without that dependency.
//
// Input needs no equivalent: tud_cdc_available()/read() never consulted DTR.
size_t cdcSpace() {
    return tud_cdc_write_available();
}

void cdcLine(const char *text) {
    const size_t length = strlen(text);
    size_t written = 0;
    while (written < length) {
        const uint32_t n = tud_cdc_write(text + written, (uint32_t)(length - written));
        if (n == 0) {
            break;  // FIFO full; the caller checked for space, so give up quietly
        }
        written += n;
    }
    tud_cdc_write_char('\n');
    tud_cdc_write_flush();
}

// Appends an unsigned 64 bit value; Print::print() has no uint64_t overload on
// this core, and going through String would allocate on every packet.
size_t appendU64(char *dst, uint64_t value) {
    char tmp[21];
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

size_t appendU32(char *dst, uint32_t value) {
    return appendU64(dst, value);
}

size_t appendI32(char *dst, int32_t value) {
    size_t n = 0;
    if (value < 0) {
        dst[n++] = '-';
        return n + appendU64(dst + n, (uint64_t)(-(int64_t)value));
    }
    return appendU64(dst, (uint64_t)value);
}

void sendBanner() {
    char line[96];
    snprintf(line, sizeof(line), "$I,BLESNIFF,%s,%s,%s,%s",
             FW_VERSION, PROTO_VERSION, BOARD_NAME,
             sniffer::rawMode() ? "raw" : "parsed");
    cdcLine(line);
}

void sendAck(const char *command, bool ok, const char *detail) {
    char line[128];
    snprintf(line, sizeof(line), "$A,%s,%s,%s", command, ok ? "ok" : "err", detail);
    cdcLine(line);
}

const char *channelName(SniffChannel channel) {
    switch (channel) {
        case CHANNEL_37: return "37";
        case CHANNEL_38: return "38";
        case CHANNEL_39: return "39";
        default:         return "all";
    }
}

void sendStatus() {
    const SniffStats stats = sniffer::stats();

    char line[MAX_LINE];
    size_t n = 0;
    memcpy(line + n, "$S,", 3);
    n += 3;
    line[n++] = sniffer::isRunning() ? '1' : '0';
    line[n++] = ',';

    const char *ch = channelName(sniffer::channel());
    const size_t chLen = strlen(ch);
    memcpy(line + n, ch, chLen);
    n += chLen;

    line[n++] = ',';
    n += appendU32(line + n, stats.received);
    line[n++] = ',';
    n += appendU32(line + n, stats.crcErrors);
    line[n++] = ',';
    n += appendU32(line + n, stats.dropped);
    line[n++] = ',';
    n += appendU32(line + n, sniffer::queued());
    line[n++] = ',';
    n += appendU64(line + n, sniffer::micros64());
    line[n++] = ',';
    n += appendU32(line + n, stats.missed);
    line[n] = '\0';

    cdcLine(line);
}

void sendPacket(const SniffPacket &packet) {
    char line[MAX_LINE];
    size_t n = 0;

    memcpy(line + n, packet.raw ? "$R," : "$P,", 3);
    n += 3;
    n += appendU32(line + n, g_sequence++);
    line[n++] = ',';
    n += appendU64(line + n, packet.timestampUs);
    line[n++] = ',';
    n += appendU32(line + n, packet.channel);
    line[n++] = ',';
    n += appendI32(line + n, packet.rssi);
    line[n++] = ',';
    if (!packet.raw) {
        // Raw mode has no crc field: the board did not check one.
        line[n++] = packet.crcOk ? '1' : '0';
        line[n++] = ',';
    }

    for (uint8_t i = 0; i < packet.length; i++) {
        line[n++] = HEX_DIGITS[packet.pdu[i] >> 4];
        line[n++] = HEX_DIGITS[packet.pdu[i] & 0x0F];
    }
    line[n] = '\0';

    cdcLine(line);
}

// Trims trailing whitespace and lowercases in place.
void normalize(char *text, size_t &length) {
    while (length > 0 && (text[length - 1] == '\r' || text[length - 1] == ' ' ||
                          text[length - 1] == '\t')) {
        length--;
    }
    text[length] = '\0';
    for (size_t i = 0; i < length; i++) {
        text[i] = (char)tolower((unsigned char)text[i]);
    }
}

void handleCommand(char *text, size_t length) {
    normalize(text, length);
    if (length == 0) {
        return;
    }

    if (strcmp(text, "start") == 0) {
        sniffer::start(sniffer::channel());
        sendAck("start", true, channelName(sniffer::channel()));
        return;
    }

    if (strcmp(text, "stop") == 0) {
        sniffer::stop();
        // Drop anything still queued so a restart does not replay stale packets.
        SniffPacket discard;
        while (sniffer::read(discard)) {
        }
        sendAck("stop", true, "");
        return;
    }

    if (strncmp(text, "ch", 2) == 0) {
        const char *arg = text + 2;
        while (*arg == ' ' || *arg == '=') {
            arg++;
        }

        SniffChannel selected;
        if (strcmp(arg, "37") == 0) {
            selected = CHANNEL_37;
        } else if (strcmp(arg, "38") == 0) {
            selected = CHANNEL_38;
        } else if (strcmp(arg, "39") == 0) {
            selected = CHANNEL_39;
        } else if (strcmp(arg, "all") == 0) {
            selected = CHANNEL_ALL;
        } else {
            sendAck("ch", false, "expected 37, 38, 39 or all");
            return;
        }

        sniffer::select(selected);
        sendAck("ch", true, channelName(selected));
        return;
    }

    if (strcmp(text, "ver") == 0) {
        sendBanner();
        return;
    }

    if (strcmp(text, "reset") == 0) {
        sniffer::resetStats();
        g_sequence = 0;
        sendAck("reset", true, "");
        return;
    }

    if (strncmp(text, "raw", 3) == 0) {
        const char *arg = text + 3;
        while (*arg == ' ' || *arg == '=') {
            arg++;
        }
        if (strcmp(arg, "on") == 0) {
            sniffer::setRawMode(true);
            sendAck("raw", true, "on");
        } else if (strcmp(arg, "off") == 0) {
            sniffer::setRawMode(false);
            sendAck("raw", true, "off");
        } else {
            sendAck("raw", false, "expected on or off");
        }
        return;
    }

    if (strcmp(text, "ping") == 0) {
        sendAck("ping", true, "");
        return;
    }

    sendAck("?", false, "unknown command");
}

void readCommands() {
    while (Serial.available() > 0) {
        const int c = Serial.read();
        if (c < 0) {
            break;
        }

        if (c == '\n') {
            handleCommand(g_command, g_commandLen);
            g_commandLen = 0;
            continue;
        }

        if (g_commandLen < sizeof(g_command) - 1) {
            g_command[g_commandLen++] = (char)c;
        } else {
            // Overlong line - discard it rather than half-executing something.
            g_commandLen = 0;
        }
    }
}

}  // namespace

void setup() {
    pinMode(LED_BUILTIN, OUTPUT);
    digitalWrite(LED_BUILTIN, LOW);

    Serial.begin(115200);  // baud is ignored on native USB CDC

    sniffer::init();

    // Wait for USB enumeration, not for DTR: a phone may never raise it, and
    // tud_mounted() is what actually determines whether we can transmit.
    const uint32_t deadline = millis() + 2000;
    while (!tud_mounted() && millis() < deadline) {
        delay(10);
    }

    sendBanner();
}

void loop() {
    sniffer::poll();
    readCommands();

    if (sniffer::isRunning()) {
        SniffPacket packet;
        // Only pull a packet when there is room for a full line, so a host that
        // stops reading applies backpressure instead of stalling us mid-write.
        while (cdcSpace() >= MAX_LINE && sniffer::read(packet)) {
            sendPacket(packet);
            digitalWrite(LED_BUILTIN, HIGH);
            g_ledOffAtMs = millis() + 20;
            sniffer::poll();
        }
    }

    if (g_ledOffAtMs != 0 && (int32_t)(millis() - g_ledOffAtMs) >= 0) {
        digitalWrite(LED_BUILTIN, LOW);
        g_ledOffAtMs = 0;
    }

    const uint32_t now = millis();
    if ((uint32_t)(now - g_lastStatusMs) >= STATUS_INTERVAL_MS &&
        cdcSpace() >= MAX_LINE) {
        g_lastStatusMs = now;
        sendStatus();
    }
}
