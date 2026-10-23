# nRF52840 advertising sniffer firmware

Turns an Adafruit Feather nRF52840 Express into a BLE advertising sniffer that
streams packets to a host over its native USB CDC port.

## How it works

The sketch never calls `Bluefruit.begin()`, so the SoftDevice stays dormant and
the RADIO peripheral can be programmed directly. That is what makes this a
sniffer rather than a scanner: the radio sits in promiscuous receive on an
advertising channel and reports **every** PDU it demodulates, including ones
whose CRC does not check out. A stack-based scanner would have discarded those.

Radio configuration (`src/ble_sniffer.cpp`):

- BLE 1 Mbit mode, access address `0x8E89BED6`.
- CRC-24, polynomial `0x65B`, initial value `0x555555`.
- Data whitening seeded with the channel index, as the specification requires.
- `READY_START` + `END_START` shortcuts keep the radio receiving back to back;
  the END interrupt hands the radio a second buffer before copying the finished
  one out, because the next packet can start landing immediately.
- A PPI channel latches TIMER2 on `EVENTS_END`, so each packet's timestamp is
  taken by hardware rather than wherever the interrupt happened to be serviced.
  The Android app uses this to reconstruct true inter-packet spacing, which USB
  arrival times cannot give it.

`ch all` round-robins across 37/38/39 with a 4 ms dwell. One radio cannot listen
on three frequencies simultaneously, so this is a genuine sampling compromise
rather than parallel capture - an advertising event that bursts across all three
channels will usually be caught on one of them.

Packets are queued in a 128-entry ring buffer and drained in `loop()`. The drain
only pulls a packet when there is room in the USB FIFO for a whole line, so a
host that stops reading applies backpressure instead of stalling the radio; if
the queue does overflow, the loss is counted and reported rather than hidden.

## Why output does not go through `Serial`

`Adafruit_USBD_CDC::write()` is written as

```cpp
while (remain && tud_cdc_n_connected(_instance)) { ... }
```

and `tud_cdc_n_connected()` is false until the host asserts DTR through
SET_CONTROL_LINE_STATE. A host that does not raise DTR gets *complete silence*
from the board - no banner, no status lines, no packets - with no error anywhere
to explain it. Android's USB stack turned out to be such a host, which is why
the app originally saw nothing.

So output is written into TinyUSB's FIFO directly (`tud_cdc_write` /
`tud_cdc_write_flush`, wrapped in `cdcLine()`), and free space is read with
`tud_cdc_write_available()`. Backpressure is unchanged - the FIFO still fills up
if nobody is reading - but the board no longer depends on a control signal that
some hosts never send. Input needed no equivalent: `tud_cdc_available()` and
`read()` never consulted DTR.

`tools/nodtr_test.py` guards this. It drives the board with both control lines
held low and fails if nothing arrives:

```
~/.platformio/penv/Scripts/python tools/nodtr_test.py
```

Run it after any change to how the firmware writes to the port.

## Protocol

Line oriented ASCII over USB CDC, deliberately readable so the board can be
driven from `pio device monitor` with no host software.

Host to device:

| Command         | Effect                                      |
| --------------- | ------------------------------------------- |
| `start`         | begin receiving on the selected channel     |
| `stop`          | power the radio down, stop emitting packets |
| `ch 37\|38\|39` | listen on one advertising channel           |
| `ch all`        | round-robin across all three                |
| `ver`           | re-emit the identification banner           |
| `reset`         | zero the packet counters                    |
| `ping`          | liveness check                              |

Device to host:

```
$I,BLESNIFF,<fw>,<proto>,<board>
$P,<seq>,<tUs>,<channel>,<rssi>,<crc>,<hexPdu>
$S,<running>,<channel>,<received>,<crcErrors>,<dropped>,<queued>,<tUs>
$A,<command>,<ok|err>,<detail>
```

`$P` carries a monotonic sequence number, a 64-bit microsecond timestamp latched
at the end of the packet, the channel it actually arrived on, RSSI in dBm, `1`
or `0` for CRC, and the raw PDU as hex starting at its two byte header. `$S` is a
once-per-second heartbeat.

## Build and flash

There are two environments, for two boards:

```
pio run                                  # both
pio run -e adafruit_feather_nrf52840     # the sniffer
pio run -e beacon                        # the reference beacon

pio run -e adafruit_feather_nrf52840 -t upload
pio run -e beacon -t upload --upload-port COM10
pio device monitor
```

Upload uses a 1200 bps touch to drop the board into its bootloader, so no button
press is needed. With two boards attached, name the port — the touch resets
whichever board it is pointed at, and pointing it at the wrong one reflashes the
wrong board.

Do not rename the `adafruit_feather_nrf52840` environment: the Android build
looks for `.pio/build/adafruit_feather_nrf52840/firmware.zip` to bundle into the
APK.

---

# Reference beacon (`-e beacon`)

A **second** nRF52840 that transmits a payload the receiver already knows.

This is the hardware half of §6 of the specification, and the only thing that
can answer a question no amount of injected damage can. Every recovery figure in
`RECOVERY.md` §5 comes from taking clean packets and corrupting them under a
model — a binary symmetric channel, or a burst — and those models are
assumptions. Real interference is under no obligation to resemble either. With a
transmitter sending known bytes, every received frame can be compared against
what was actually sent, so the *shape* of real errors is measured.

The board prints the exact PDU it will transmit before sending anything:

```
$U,022501ACBEEEFFC00201060B09524546434845434B0000000000000000000000000000
```

Keep that line with the capture. It is the ground truth, and reconstructing it
afterwards from the arguments you believe you passed is how a measurement
quietly turns into a guess.

## What it fixes about the payload

- **Address** — set explicitly, written most-significant byte first as it is
  displayed. The radio transmits it the other way round; the firmware reverses
  it so the address the sniffer decodes reads the same as the one you typed.
- **Content** — the advertising data, byte for byte.
- **Length** — padded or truncated to exactly N bytes, so "every packet in this
  run was 39 bytes" is a fact. That matters because a receiver that gets the
  length field wrong misplaces the whole frame boundary, and separating that
  failure from a corrupted payload needs the length to be known.
- **PDU type** — `ADV_NONCONN_IND` by default, so scanners do not answer it and
  fill the capture with `SCAN_REQ` traffic that has nothing to do with the
  measurement.
- **Power** — one of the fourteen levels the radio supports, +8 dBm down to
  −40 dBm. That 48 dB range is an attenuator built into the transmitter. It does
  not replace physical separation, because it changes only the transmitter and
  not the multipath between the boards, but it is free and it is repeatable,
  which a person walking away from a bench is not.

## Protocol

Host to device:

| Command | Effect |
| --- | --- |
| `addr <aa:bb:cc:dd:ee:ff> [random]` | advertiser address |
| `data <hex>` | advertising data bytes |
| `len <n>` | pad or truncate the data to exactly n bytes |
| `type <n>` | PDU type nibble; 2 = `ADV_NONCONN_IND` |
| `ch 37\|38\|39\|all` | channel; `all` sends one packet per channel |
| `power <dbm>` | transmit power, see `levels` |
| `levels` | list the supported power levels |
| `send <count> [interval_ms]` | transmit count packets at the set power |
| `sweep <count> [interval_ms] [step]` | count packets at every level, high to low |
| `stop` | abandon a run |
| `pdu` / `ver` / `ping` | |

Device to host:

```
$I,BLEBEACON,<fw>,<proto>,<board>
$U,<hexPdu>                       exactly what goes on air, header first
$L,<dbm>,<dbm>,...                supported power levels
$B,<seq>,<power>,<channel>,<tUs>  one burst went out
$R,<power>,<sent>                 a run at one power level finished
$E,<sent>                         the whole run finished
$A,<command>,<ok|err>,<detail>
```

A *burst* is one packet per selected channel, so `ch all` transmits three.

## Driving it: `tools/beacon.py`

```
# What is attached, and what can it do?
python tools/beacon.py --list
python tools/beacon.py --levels

# 500 packets at full power, all three channels, 20 ms apart.
python tools/beacon.py --count 500

# A fixed 31 byte payload from a known address, one channel, at -12 dBm.
python tools/beacon.py \
    --address C0:FF:EE:BE:AC:01 \
    --data 0201060B09524546434845434B \
    --length 31 --channel 37 --power -12 --count 1000

# The measurement §6 asks for: 200 packets at every level, +8 dBm to -40 dBm.
python tools/beacon.py --sweep --count 200 --interval 10 --log run.csv

# A faster sweep visiting every other level.
python tools/beacon.py --sweep --count 100 --step 2
```

`--log` writes one row per transmitted packet — sequence, power, channel, device
timestamp and the PDU — which is what gets joined against the sniffer's capture
afterwards.

## Running the measurement

1. Flash the beacon onto one board and the sniffer onto the other.
2. Put the sniffer in **raw** capture mode (the `RAW`/`STD` control in the app).
   Raw mode is what delivers the received CRC, and without it a damaged frame
   cannot be told from a correctly received one at the byte level.
3. Separate the boards, or leave them close and let `--sweep` do the attenuating.
4. Start the capture, run the beacon, stop the capture, export it.
5. Join on the payload: every received frame from the beacon's address whose
   bytes differ from `$U` is a **real** error with known ground truth. Their bit
   patterns are the answer — scattered, bursty, or something the models miss.

## Checking it without the app

```
$ pio device monitor
$I,BLESNIFF,1.0.0,1,feather_nrf52840
ch all
$A,ch,ok,all
start
$A,start,ok,all
$P,0,27788271,39,-83,1,0012062420B1E59002010608FF4D310000000000
$P,1,27820831,38,-72,1,0222C16F7AAE1C641BFF75004204018060641CAE...
$S,1,all,307,89,0,0,35088435
```

A CRC error rate of a few tens of percent is normal and healthy - it is the tail
of weak advertisers at the edge of reception, which a sniffer is supposed to show
you.

## Porting to another nRF52840 board

Change `board` in `platformio.ini`. Boards without the Adafruit UF2 bootloader
also need a different `upload_protocol` (`nrfjprog` or `jlink` for a Nordic DK).
The radio code itself is board independent, but the board must expose the
nRF52840's **native** USB port - a debugger's virtual COM port will not do,
because the Android app talks to the chip's own USB device.
