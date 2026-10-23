#include "ble_beacon.h"

#include <nrf.h>
#include <string.h>

namespace {

// The same physical layer the sniffer listens on, necessarily: these two have
// to agree exactly or the measurement is of the disagreement.
const uint32_t ADV_ACCESS_ADDRESS = 0x8E89BED6;
const uint32_t BLE_CRC_POLY = 0x0000065B;
const uint32_t BLE_CRC_INIT = 0x00555555;

/**
 * Every level the nRF52840's TXPOWER register accepts, strongest first.
 *
 * The whole sweep is 48 dB, which is a factor of about 63,000 in power, and
 * that range is the attenuator §6 asks for - built into the radio rather than
 * screwed onto an antenna. It cannot replace physical separation entirely,
 * because it changes only the transmitter and not the multipath between the two
 * boards, but it costs nothing and it is repeatable, which a person walking
 * away from a bench is not.
 */
const int8_t POWER_LEVELS[] = {8, 7, 6, 5, 4, 3, 2, 0, -4, -8, -12, -16, -20, -40};
const uint8_t POWER_LEVEL_COUNT = sizeof(POWER_LEVELS) / sizeof(POWER_LEVELS[0]);

// PDU as it goes on air, word aligned for EasyDMA.
uint8_t g_pdu[BEACON_MAX_PDU] __attribute__((aligned(4)));
uint8_t g_pduLength = 2 + BEACON_ADDRESS_LEN;

uint8_t g_pduType = 0x02;  // ADV_NONCONN_IND
bool g_randomAddress = false;
uint8_t g_adLength = 0;

BeaconChannel g_channel = BEACON_CH_ALL;
int8_t g_power = 0;

uint8_t frequencyFor(uint8_t channel) {
    switch (channel) {
        case 37: return 2;   // 2402 MHz
        case 38: return 26;  // 2426 MHz
        default: return 80;  // 2480 MHz, channel 39
    }
}

void startHfClock() {
    if ((NRF_CLOCK->HFCLKSTAT &
         (CLOCK_HFCLKSTAT_SRC_Msk | CLOCK_HFCLKSTAT_STATE_Msk)) ==
        ((CLOCK_HFCLKSTAT_SRC_Xtal << CLOCK_HFCLKSTAT_SRC_Pos) |
         (CLOCK_HFCLKSTAT_STATE_Running << CLOCK_HFCLKSTAT_STATE_Pos))) {
        return;
    }
    NRF_CLOCK->EVENTS_HFCLKSTARTED = 0;
    NRF_CLOCK->TASKS_HFCLKSTART = 1;
    for (uint32_t spin = 0; spin < 1000000 && !NRF_CLOCK->EVENTS_HFCLKSTARTED; spin++) {
        __NOP();
    }
}

/** Rebuilds the header from the current type, address flag and data length. */
void rebuildHeader() {
    // Low nibble is the PDU type; bit 6 is TxAdd, set for a random address.
    g_pdu[0] = (uint8_t)((g_pduType & 0x0F) | (g_randomAddress ? 0x40 : 0x00));
    g_pdu[1] = (uint8_t)(BEACON_ADDRESS_LEN + g_adLength);
    g_pduLength = (uint8_t)(2 + BEACON_ADDRESS_LEN + g_adLength);
}

void configureRadio() {
    NRF_RADIO->POWER = 0;
    NRF_RADIO->POWER = 1;

    NRF_RADIO->MODE = RADIO_MODE_MODE_Ble_1Mbit << RADIO_MODE_MODE_Pos;
    NRF_RADIO->MODECNF0 = (RADIO_MODECNF0_RU_Fast << RADIO_MODECNF0_RU_Pos) |
                          (RADIO_MODECNF0_DTX_Center << RADIO_MODECNF0_DTX_Pos);

    // One byte of S0 (the PDU header's first byte) plus an 8 bit length field.
    NRF_RADIO->PCNF0 = (1UL << RADIO_PCNF0_S0LEN_Pos) |
                       (8UL << RADIO_PCNF0_LFLEN_Pos) |
                       (0UL << RADIO_PCNF0_S1LEN_Pos);

    NRF_RADIO->PCNF1 = ((uint32_t)(BEACON_ADDRESS_LEN + BEACON_MAX_AD) << RADIO_PCNF1_MAXLEN_Pos) |
                       (0UL << RADIO_PCNF1_STATLEN_Pos) |
                       (3UL << RADIO_PCNF1_BALEN_Pos) |
                       (RADIO_PCNF1_ENDIAN_Little << RADIO_PCNF1_ENDIAN_Pos) |
                       (RADIO_PCNF1_WHITEEN_Enabled << RADIO_PCNF1_WHITEEN_Pos);

    NRF_RADIO->BASE0 = (ADV_ACCESS_ADDRESS << 8) & 0xFFFFFF00UL;
    NRF_RADIO->PREFIX0 = (ADV_ACCESS_ADDRESS >> 24) & 0xFF;
    NRF_RADIO->TXADDRESS = 0;

    NRF_RADIO->CRCCNF = (RADIO_CRCCNF_LEN_Three << RADIO_CRCCNF_LEN_Pos) |
                        (RADIO_CRCCNF_SKIPADDR_Skip << RADIO_CRCCNF_SKIPADDR_Pos);
    NRF_RADIO->CRCPOLY = BLE_CRC_POLY;
    NRF_RADIO->CRCINIT = BLE_CRC_INIT;

    NRF_RADIO->TIFS = 0;
}

/** One transmission on one physical channel. Returns when the radio is idle. */
void transmitOn(uint8_t channel) {
    NRF_RADIO->FREQUENCY = frequencyFor(channel);
    NRF_RADIO->DATAWHITEIV = channel & 0x3F;
    NRF_RADIO->TXPOWER = (uint32_t)(uint8_t)g_power;
    NRF_RADIO->PACKETPTR = (uint32_t)g_pdu;

    NRF_RADIO->EVENTS_READY = 0;
    NRF_RADIO->EVENTS_END = 0;
    NRF_RADIO->EVENTS_DISABLED = 0;

    // READY_START begins the transmission the moment ramp-up finishes;
    // END_DISABLE powers the radio back down as soon as it is over, so nothing
    // is left keying the carrier between packets.
    NRF_RADIO->SHORTS = RADIO_SHORTS_READY_START_Msk | RADIO_SHORTS_END_DISABLE_Msk;

    NRF_RADIO->TASKS_TXEN = 1;
    for (uint32_t spin = 0; spin < 1000000 && !NRF_RADIO->EVENTS_DISABLED; spin++) {
        __NOP();
    }
    NRF_RADIO->EVENTS_DISABLED = 0;
}

}  // namespace

namespace beacon {

void init() {
    startHfClock();
    configureRadio();

    // A recognisable default so a board that has just been flashed transmits
    // something identifiable rather than six zero bytes.
    static const uint8_t defaultAddress[BEACON_ADDRESS_LEN] = {
        0xC0, 0xFF, 0xEE, 0xBE, 0xAC, 0x01,
    };
    setAddress(defaultAddress, false);

    // Flags (LE General Discoverable, BR/EDR not supported) then a short name.
    static const uint8_t defaultData[] = {
        0x02, 0x01, 0x06,
        0x07, 0x09, 'R', 'E', 'F', 'B', 'C', 'N',
    };
    setData(defaultData, sizeof(defaultData), sizeof(defaultData), 0x00);
}

void setAddress(const uint8_t address[BEACON_ADDRESS_LEN], bool randomAddress) {
    // Written down most significant byte first; transmitted least significant
    // byte first. Reversing here means the address the sniffer decodes reads
    // the same way round as the one the operator typed.
    for (uint8_t i = 0; i < BEACON_ADDRESS_LEN; i++) {
        g_pdu[2 + i] = address[BEACON_ADDRESS_LEN - 1 - i];
    }
    g_randomAddress = randomAddress;
    rebuildHeader();
}

bool setData(const uint8_t *data, uint8_t dataLength, uint8_t length, uint8_t filler) {
    if (length > BEACON_MAX_AD) {
        return false;
    }
    for (uint8_t i = 0; i < length; i++) {
        g_pdu[2 + BEACON_ADDRESS_LEN + i] = (i < dataLength) ? data[i] : filler;
    }
    g_adLength = length;
    rebuildHeader();
    return true;
}

void setPduType(uint8_t type) {
    g_pduType = type;
    rebuildHeader();
}

void setChannel(BeaconChannel channel) {
    g_channel = channel;
}

BeaconChannel channel() {
    return g_channel;
}

bool powerSupported(int8_t dbm) {
    for (uint8_t i = 0; i < POWER_LEVEL_COUNT; i++) {
        if (POWER_LEVELS[i] == dbm) {
            return true;
        }
    }
    return false;
}

void setPower(int8_t dbm) {
    g_power = dbm;
}

int8_t power() {
    return g_power;
}

const int8_t *powerLevels(uint8_t &count) {
    count = POWER_LEVEL_COUNT;
    return POWER_LEVELS;
}

uint8_t pdu(uint8_t *out) {
    memcpy(out, g_pdu, g_pduLength);
    return g_pduLength;
}

void transmitOnce() {
    startHfClock();
    if (g_channel == BEACON_CH_ALL) {
        // A real advertising event repeats across all three primary channels
        // within a few hundred microseconds. Doing the same means a sniffer
        // hopping channels still sees the run, and means an error rate can be
        // compared between channels from one command.
        transmitOn(37);
        transmitOn(38);
        transmitOn(39);
    } else {
        transmitOn((uint8_t)g_channel);
    }
}

}  // namespace beacon
