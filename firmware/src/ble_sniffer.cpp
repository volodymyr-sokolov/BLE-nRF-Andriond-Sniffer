#include "ble_sniffer.h"

#include <nrf.h>

namespace {

// --- BLE physical layer constants -------------------------------------------

// Every advertising PDU on the primary channels uses this fixed access address.
const uint32_t ADV_ACCESS_ADDRESS = 0x8E89BED6;

// CRC-24 as specified for BLE, seeded with 0x555555 on advertising channels.
const uint32_t BLE_CRC_POLY = 0x0000065B;
const uint32_t BLE_CRC_INIT = 0x00555555;

const uint8_t ADV_MAX_PAYLOAD = 37;  // excludes the 2 byte PDU header

// How long we dwell on one channel before hopping in CHANNEL_ALL mode. An
// advertising event bursts across 37/38/39 within a few hundred microseconds,
// so a short dwell keeps us from systematically missing any one channel while
// still leaving the radio enough settled time to be useful.
const uint32_t HOP_DWELL_US = 4000;

// Free while the SoftDevice is dormant.
const uint8_t PPI_CH_TIMESTAMP = 10;
const uint8_t PPI_CH_END_COUNT = 11;

// --- Packet queue ------------------------------------------------------------

// Single producer (RADIO ISR), single consumer (main loop). Power-of-two size
// so the masking stays a single AND.
const uint32_t QUEUE_SIZE = 128;
const uint32_t QUEUE_MASK = QUEUE_SIZE - 1;

SniffPacket        g_queue[QUEUE_SIZE];
volatile uint32_t  g_queueHead = 0;  // written by the ISR
volatile uint32_t  g_queueTail = 0;  // written by the main loop

// EasyDMA writes straight into these, so they must be word aligned. Two buffers
// let the ISR hand the radio a fresh one before it copies out the finished
// packet, which matters because END_START restarts RX immediately.
uint8_t g_rxBuffer[2][SNIFF_RAW_LEN] __attribute__((aligned(4)));
volatile uint8_t g_rxIndex = 0;

volatile SniffStats g_stats = {0, 0, 0, 0};

// TIMER3 free-runs from boot, so counts are taken relative to this baseline.
volatile uint32_t g_endBaseline = 0;

volatile bool         g_rawMode = false;
volatile bool         g_running = false;
volatile SniffChannel g_selected = CHANNEL_37;
volatile uint8_t      g_activeChannel = 37;  // the physical channel right now
uint32_t              g_lastHopUs = 0;

// --- 64 bit microsecond clock ------------------------------------------------

// TIMER4 free-runs at 1 MHz and is the timebase for packet timestamps. CC[0] is
// latched by PPI on RADIO EVENTS_END, so a packet's time is taken by hardware
// rather than wherever the interrupt happened to land; CC[1] serves software
// reads of "now".
//
// TIMER2 is deliberately avoided: the Adafruit bootloader hands its version to
// the application in TIMER2->CC[0] (see the core's wiring.c), and sharing a
// register with that convention is asking for trouble.
//
// Only micros64() advances the wrap counter, and it does so from a strictly
// monotonic source. An earlier version let the capture path move the counter
// too, which meant a capture taken just before a wrap could drag it backwards
// and throw every later timestamp out by the full 32 bit range.
volatile uint32_t g_clockLastNow = 0;
volatile uint64_t g_clockHigh = 0;

// Widens a hardware capture using the wrap state, without modifying it. The
// capture is always in the past, so a value far *ahead* of the newest "now"
// can only mean it was taken before the most recent wrap.
uint64_t extendCapture(uint32_t raw) {
    const uint32_t primask = __get_PRIMASK();
    __disable_irq();

    uint64_t high = g_clockHigh;
    if (raw > g_clockLastNow && (raw - g_clockLastNow) > 0x80000000UL) {
        high -= 0x100000000ULL;
    }
    const uint64_t result = high + raw;

    if (!primask) {
        __enable_irq();
    }
    return result;
}

void startTimer() {
    NRF_TIMER4->TASKS_STOP = 1;
    NRF_TIMER4->MODE       = TIMER_MODE_MODE_Timer;
    NRF_TIMER4->BITMODE    = TIMER_BITMODE_BITMODE_32Bit;
    NRF_TIMER4->PRESCALER  = 4;  // 16 MHz >> 4 == 1 MHz
    NRF_TIMER4->TASKS_CLEAR = 1;
    NRF_TIMER4->TASKS_START = 1;

    // TIMER3 counts RADIO END events in hardware. Comparing it with the number
    // of packets we actually serviced is the only way to notice that two
    // packets finished before the interrupt ran - the second overwrites the
    // first in the receive buffer, and without this the loss is invisible.
    NRF_TIMER3->TASKS_STOP = 1;
    NRF_TIMER3->MODE       = TIMER_MODE_MODE_Counter;
    NRF_TIMER3->BITMODE    = TIMER_BITMODE_BITMODE_32Bit;
    NRF_TIMER3->TASKS_CLEAR = 1;
    NRF_TIMER3->TASKS_START = 1;
}

uint32_t endEventCount() {
    NRF_TIMER3->TASKS_CAPTURE[0] = 1;
    return NRF_TIMER3->CC[0];
}

void startHfClock() {
    // The radio needs the crystal oscillator; USB may already have started it.
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

uint8_t frequencyFor(uint8_t channel) {
    switch (channel) {
        case 37: return 2;   // 2402 MHz
        case 38: return 26;  // 2426 MHz
        default: return 80;  // 2480 MHz, channel 39
    }
}

void configureRadio() {
    NRF_RADIO->POWER = 0;
    NRF_RADIO->POWER = 1;

    NRF_RADIO->MODE = RADIO_MODE_MODE_Ble_1Mbit << RADIO_MODE_MODE_Pos;
    NRF_RADIO->TXPOWER = RADIO_TXPOWER_TXPOWER_0dBm << RADIO_TXPOWER_TXPOWER_Pos;

    // Fast ramp-up shortens the dead time around a channel hop.
    NRF_RADIO->MODECNF0 = (RADIO_MODECNF0_RU_Fast << RADIO_MODECNF0_RU_Pos) |
                          (RADIO_MODECNF0_DTX_Center << RADIO_MODECNF0_DTX_Pos);

    if (g_rawMode) {
        // F2: no length field at all, a fixed 42 bytes every time, so a
        // corrupted Length cannot move the boundary. F4: whitening off, so the
        // host receives the bytes as transmitted and can dewhiten and verify the
        // CRC itself - and recompute both if it wants to.
        NRF_RADIO->PCNF0 = (0UL << RADIO_PCNF0_S0LEN_Pos) |
                           (0UL << RADIO_PCNF0_LFLEN_Pos) |
                           (0UL << RADIO_PCNF0_S1LEN_Pos);

        NRF_RADIO->PCNF1 = (SNIFF_RAW_LEN << RADIO_PCNF1_MAXLEN_Pos) |
                           (SNIFF_RAW_LEN << RADIO_PCNF1_STATLEN_Pos) |
                           (3UL << RADIO_PCNF1_BALEN_Pos) |
                           (RADIO_PCNF1_ENDIAN_Little << RADIO_PCNF1_ENDIAN_Pos) |
                           (RADIO_PCNF1_WHITEEN_Disabled << RADIO_PCNF1_WHITEEN_Pos);
    } else {
        // One byte of S0 (the PDU header) plus an 8 bit length field, no S1.
        NRF_RADIO->PCNF0 = (1UL << RADIO_PCNF0_S0LEN_Pos) |
                           (8UL << RADIO_PCNF0_LFLEN_Pos) |
                           (0UL << RADIO_PCNF0_S1LEN_Pos);

        NRF_RADIO->PCNF1 = (ADV_MAX_PAYLOAD << RADIO_PCNF1_MAXLEN_Pos) |
                           (0UL << RADIO_PCNF1_STATLEN_Pos) |
                           (3UL << RADIO_PCNF1_BALEN_Pos) |
                           (RADIO_PCNF1_ENDIAN_Little << RADIO_PCNF1_ENDIAN_Pos) |
                           (RADIO_PCNF1_WHITEEN_Enabled << RADIO_PCNF1_WHITEEN_Pos);
    }

    // Access address split into a 1 byte prefix and a 3 byte base.
    NRF_RADIO->BASE0   = (ADV_ACCESS_ADDRESS << 8) & 0xFFFFFF00UL;
    NRF_RADIO->PREFIX0 = (ADV_ACCESS_ADDRESS >> 24) & 0xFF;
    NRF_RADIO->RXADDRESSES = 1UL;  // listen on logical address 0 only

    if (g_rawMode) {
        // The CRC bytes are part of the captured data now, so the radio must not
        // consume or check them. crc_ok is recomputed on the host.
        NRF_RADIO->CRCCNF = (RADIO_CRCCNF_LEN_Disabled << RADIO_CRCCNF_LEN_Pos);
    } else {
        NRF_RADIO->CRCCNF = (RADIO_CRCCNF_LEN_Three << RADIO_CRCCNF_LEN_Pos) |
                            (RADIO_CRCCNF_SKIPADDR_Skip << RADIO_CRCCNF_SKIPADDR_Pos);
        NRF_RADIO->CRCPOLY = BLE_CRC_POLY;
        NRF_RADIO->CRCINIT = BLE_CRC_INIT;
    }

    NRF_RADIO->TIFS = 0;
}

void enterRx(uint8_t channel) {
    g_activeChannel = channel;

    NRF_RADIO->FREQUENCY   = frequencyFor(channel);
    NRF_RADIO->DATAWHITEIV = channel & 0x3F;  // whitening is seeded per channel

    g_rxIndex = 0;
    NRF_RADIO->PACKETPTR = (uint32_t)g_rxBuffer[0];

    NRF_RADIO->EVENTS_READY = 0;
    NRF_RADIO->EVENTS_END = 0;
    NRF_RADIO->EVENTS_DISABLED = 0;

    // READY_START gets us receiving as soon as ramp-up finishes and the RSSI
    // shorts sample the carrier while the payload is still arriving.
    //
    // END_START is deliberately absent. With it, the radio resumed receiving the
    // instant a packet ended, so a second packet could finish - and have PPI
    // overwrite the capture register - before the interrupt had read the first
    // packet's timestamp. The first packet then carried the second one's time,
    // which showed up as consecutive packets closer together than their own air
    // time allows. Restarting from the handler instead means the radio cannot
    // produce another END until we have taken everything we need. From RXIDLE a
    // START resumes reception with no ramp-up, so the cost is only the interrupt
    // latency, which is why this handler runs at the highest priority.
    NRF_RADIO->SHORTS = RADIO_SHORTS_READY_START_Msk |
                        RADIO_SHORTS_ADDRESS_RSSISTART_Msk |
                        RADIO_SHORTS_DISABLED_RSSISTOP_Msk;

    NRF_RADIO->INTENSET = RADIO_INTENSET_END_Msk;
    NVIC_ClearPendingIRQ(RADIO_IRQn);
    NVIC_EnableIRQ(RADIO_IRQn);

    NRF_RADIO->TASKS_RXEN = 1;
}

void leaveRx() {
    NRF_RADIO->SHORTS = 0;
    NRF_RADIO->INTENCLR = RADIO_INTENCLR_END_Msk;
    NVIC_DisableIRQ(RADIO_IRQn);

    NRF_RADIO->EVENTS_DISABLED = 0;
    NRF_RADIO->TASKS_DISABLE = 1;
    for (uint32_t spin = 0; spin < 100000 && !NRF_RADIO->EVENTS_DISABLED; spin++) {
        __NOP();
    }
    NRF_RADIO->EVENTS_DISABLED = 0;

    NRF_RADIO->EVENTS_END = 0;
    NVIC_ClearPendingIRQ(RADIO_IRQn);
}

uint8_t nextChannel(uint8_t current) {
    return (current >= 39) ? 37 : (uint8_t)(current + 1);
}

}  // namespace

// The SoftDevice would normally own this vector, but we never enable it.
extern "C" void RADIO_IRQHandler(void) {
    if (!NRF_RADIO->EVENTS_END) {
        return;
    }
    NRF_RADIO->EVENTS_END = 0;

    // The radio is parked in RXIDLE until we start it again, so everything
    // belonging to this packet can be read without racing the next one.
    const uint32_t rawTime = NRF_TIMER4->CC[0];  // latched by PPI at END
    // With hardware CRC disabled in raw mode, CRCSTATUS means nothing; the host
    // decides. Reported as false so nothing downstream mistakes it for a check.
    const bool crcOk = g_rawMode
                           ? false
                           : (NRF_RADIO->CRCSTATUS & RADIO_CRCSTATUS_CRCSTATUS_Msk) != 0;
    const int8_t rssi = -(int8_t)(NRF_RADIO->RSSISAMPLE & 0x7F);

    // Hand the radio the other buffer and put it back on air before doing
    // anything slower, so the deaf window is as short as it can be.
    const uint8_t finished = g_rxIndex;
    g_rxIndex = finished ^ 1;
    NRF_RADIO->PACKETPTR = (uint32_t)g_rxBuffer[g_rxIndex];
    NRF_RADIO->TASKS_START = 1;

    sniffer::micros64();  // keep the wrap counter current before widening rawTime

    const uint32_t head = g_queueHead;
    const uint32_t tail = g_queueTail;

    if ((head - tail) >= QUEUE_SIZE) {
        g_stats.dropped++;
        return;
    }

    SniffPacket &slot = g_queue[head & QUEUE_MASK];
    slot.timestampUs = extendCapture(rawTime);
    slot.channel     = g_activeChannel;
    slot.rssi        = rssi;
    slot.crcOk       = crcOk;

    // In raw mode the capture is always the full fixed length; in normal mode
    // the header's length byte says how much of the buffer is real.
    uint8_t total;
    if (g_rawMode) {
        total = SNIFF_RAW_LEN;
    } else {
        const uint8_t payloadLen = g_rxBuffer[finished][1];
        total = (uint8_t)(2 + (payloadLen > ADV_MAX_PAYLOAD ? ADV_MAX_PAYLOAD : payloadLen));
    }
    slot.raw = g_rawMode;
    slot.length = total;
    for (uint8_t i = 0; i < total; i++) {
        slot.pdu[i] = g_rxBuffer[finished][i];
    }

    g_stats.received++;
    if (!crcOk) {
        g_stats.crcErrors++;
    }

    __DMB();  // publish the slot before the consumer can see the new head
    g_queueHead = head + 1;
}

namespace sniffer {

void init() {
    startHfClock();
    startTimer();

    NRF_PPI->CH[PPI_CH_TIMESTAMP].EEP = (uint32_t)&NRF_RADIO->EVENTS_END;
    NRF_PPI->CH[PPI_CH_TIMESTAMP].TEP = (uint32_t)&NRF_TIMER4->TASKS_CAPTURE[0];
    NRF_PPI->CH[PPI_CH_END_COUNT].EEP = (uint32_t)&NRF_RADIO->EVENTS_END;
    NRF_PPI->CH[PPI_CH_END_COUNT].TEP = (uint32_t)&NRF_TIMER3->TASKS_COUNT;
    NRF_PPI->CHENSET = (1UL << PPI_CH_TIMESTAMP) | (1UL << PPI_CH_END_COUNT);

    // Highest priority: this handler has to copy the receive buffer before the
    // next packet lands in it, and every microsecond it waits is jitter on the
    // timestamp. Nothing here calls into FreeRTOS, so pre-empting it is safe.
    NVIC_SetPriority(RADIO_IRQn, 0);
}

void start(SniffChannel channel) {
    if (g_running) {
        leaveRx();
    }

    g_selected = channel;
    startHfClock();
    configureRadio();

    const uint8_t physical = (channel == CHANNEL_ALL) ? 37 : (uint8_t)channel;
    g_lastHopUs = micros64() & 0xFFFFFFFFULL;
    g_running = true;
    enterRx(physical);
}

void stop() {
    if (!g_running) {
        return;
    }
    g_running = false;
    leaveRx();
    NRF_RADIO->POWER = 0;
}

void setRawMode(bool raw) {
    g_rawMode = raw;
    if (g_running) {
        start(g_selected);   // reconfigure the radio for the new mode
    }
}

bool rawMode() {
    return g_rawMode;
}

void select(SniffChannel channel) {
    if (g_running) {
        start(channel);
    } else {
        g_selected = channel;
    }
}

bool isRunning() {
    return g_running;
}

SniffChannel channel() {
    return g_selected;
}

uint32_t queued() {
    return g_queueHead - g_queueTail;
}

void poll() {
    // Keep the wrap counter fresh even during long quiet stretches.
    const uint32_t now = (uint32_t)micros64();

    if (!g_running || g_selected != CHANNEL_ALL) {
        return;
    }
    if ((now - g_lastHopUs) < HOP_DWELL_US) {
        return;
    }
    g_lastHopUs = now;

    const uint8_t next = nextChannel(g_activeChannel);
    leaveRx();
    enterRx(next);
}

bool read(SniffPacket &out) {
    const uint32_t tail = g_queueTail;
    if (tail == g_queueHead) {
        return false;
    }
    __DMB();
    out = g_queue[tail & QUEUE_MASK];
    __DMB();
    g_queueTail = tail + 1;
    return true;
}

SniffStats stats() {
    SniffStats copy;
    const uint32_t primask = __get_PRIMASK();
    __disable_irq();
    copy.received  = g_stats.received;
    copy.crcErrors = g_stats.crcErrors;
    copy.dropped   = g_stats.dropped;
    // Every END the radio signalled, minus the ones we managed to service.
    const uint32_t serviced = g_stats.received + g_stats.dropped;
    const uint32_t ends = endEventCount() - g_endBaseline;
    copy.missed = (ends > serviced) ? (ends - serviced) : 0;
    if (!primask) {
        __enable_irq();
    }
    return copy;
}

void resetStats() {
    const uint32_t primask = __get_PRIMASK();
    __disable_irq();
    g_stats.received = 0;
    g_stats.crcErrors = 0;
    g_stats.dropped = 0;
    g_endBaseline = endEventCount();
    if (!primask) {
        __enable_irq();
    }
}

uint64_t micros64() {
    const uint32_t primask = __get_PRIMASK();
    __disable_irq();

    NRF_TIMER4->TASKS_CAPTURE[1] = 1;
    const uint32_t now = NRF_TIMER4->CC[1];
    if (now < g_clockLastNow) {
        g_clockHigh += 0x100000000ULL;
    }
    g_clockLastNow = now;
    const uint64_t result = g_clockHigh + now;

    if (!primask) {
        __enable_irq();
    }
    return result;
}

}  // namespace sniffer
