# BLE advertising sniffer and interference bench

An Android app that captures BLE advertising packets from either the phone's own
radio or an nRF52840 board over USB, the firmware that turns the board into a
sniffer or a reference beacon, and a host-side harness that drives both ends to
measure how an adaptive advertiser behaves under interference.

```
firmware/   PlatformIO project for the Adafruit Feather nRF52840 Express
            -e adafruit_feather_nrf52840   the sniffer
            -e beacon                      a reference beacon for a second board
            -e beacon_adaptive             the beacon with air-time adaptation
    tools/  host-side Python: beacon control, app automation, the experiment
android/    Android app (Kotlin, Jetpack Compose)
```

The two ends are deliberately separate programs. The board transmits a payload
the receiver already knows, so every error is measured against ground truth
rather than against an assumed channel model.

## Hardware requirements

The bench is three devices, and only one of them is on the PC's USB.

| | What | Notes |
|---|---|---|
| Transmitter | [Adafruit Feather nRF52840 Express](https://www.adafruit.com/product/4062) | On the **PC**, appears as a COM port (Adafruit USB VID `0x239A`) |
| Receiver | A second Feather nRF52840 Express | On the **phone**, in USB host mode. Never appears on the PC |
| Control | An Android phone, Android 8.0 (API 26) or later | Runs the sniffer app; driven over `adb` |
| | [USB-C OTG adapter or C-to-C cable](https://developer.android.com/develop/connectivity/usb/host) | Connects the second board to the phone |

Both boards are the same part and run the same toolchain; which one is which is
decided only by what is flashed to it. The host tools find the transmitter by
Adafruit's vendor ID alone and take the **first** match, so if you have another
Adafruit board plugged into the same PC, unplug it or pass `--port` explicitly.

The board must be an nRF52840 with a **native USB port** — the firmware sends packets over USB CDC, which is what the
app opens in host mode. `platformio.ini` targets the Feather; another nRF52840
board needs the `board` line changed and, without the Adafruit bootloader, a
different `upload_protocol`.

The [nRF52840](https://www.nordicsemi.com/Products/nRF52840) is required rather
than incidental: the sketch never calls `Bluefruit.begin()`, so the SoftDevice
stays dormant and the RADIO peripheral is driven directly. That is what makes
CRC-failed frames and the arrival channel visible at all.

### Optional: interference sources

Only needed to run the experiment under interference. Any 2.4 GHz source that
can be parked on or swept across the advertising channels (2402, 2426, 2480 MHz)
will do. The measurements this harness produced used:

- A generic swept source covering 2402–2480 MHz. These are sold under various
  names — the unit here was branded YSGM — and have no canonical product page,
  so treat the model name in the data as a label rather than a specification.
- A module based on the [Analog Devices HMC833](https://www.analog.com/en/products/hmc833.html)
  fractional-N synthesiser, emitting an unmodulated carrier at 2402 MHz.
- A module based on the [Analog Devices ADF4351](https://www.analog.com/en/products/adf4351.html),
  used in the earlier sessions in `bench/`.

These are bare synthesiser modules, not calibrated instruments. The harness
records the generator name and frequency you declare, and asks you to confirm it
is radiating, because a generator that was never switched on produces a session
that looks exactly like a clean one.

> **Transmit only into a controlled environment.** These sources deny the
> advertising channels to every BLE device in range, which in most jurisdictions
> is lawful only in a shielded enclosure or under a licence. Check what applies
> to you before powering one up.

## Python environment

Developed on **Python 3.14**; anything from **3.11** is expected to work (the
tools use `X | Y` type syntax and `from __future__ import annotations`).

```bash
python -m venv .venv
.venv\Scripts\activate            # Windows
# source .venv/bin/activate       # Linux / macOS
python -m pip install -r requirements.txt
```

| Package | Needed by |
|---|---|
| [pyserial](https://pypi.org/project/pyserial/) `>=3.5` | everything that talks to a board |
| [matplotlib](https://matplotlib.org/) `>=3.8` | `experiment.py plot` |
| [numpy](https://numpy.org/) `>=1.26` | `experiment.py plot` |

Everything else is standard library. A bench-only install can stop at `pyserial`
— running and scoring an experiment needs no plotting stack.

Two external tools are not Python packages and must be on `PATH`:

- **[PlatformIO Core](https://docs.platformio.org/en/latest/core/installation/index.html)**
  to build and flash firmware. `experiment.py` shells out to `pio` to reflash
  between arms.
- **[Android platform-tools](https://developer.android.com/tools/releases/platform-tools)**
  for `adb`. The tools look for it at
  `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`; edit the `ADB` constant
  if yours lives elsewhere.

### Pointing the tools at your phone

```bash
adb connect <phone-ip>:5555
export BLESNIFF_DEVICE=<phone-ip>:5555     # set BLESNIFF_DEVICE=... on Windows
```

`adb` over Wi-Fi does not survive a reboot, and Android picks a **new random
port** each time wireless debugging is re-enabled — so `:5555` is only ever a
starting guess. `experiment.py` falls back to any single device `adb devices`
reports, but the other tools read `BLESNIFF_DEVICE` directly. Check with
`adb devices` rather than assuming.

## Running

### Drive the beacon by hand

```bash
python firmware/tools/beacon.py --list        # what is attached
python firmware/tools/beacon.py --levels      # what transmit powers it supports

# 500 packets at full power, all three channels, 20 ms apart
python firmware/tools/beacon.py --count 500

# A known payload from a known address, one channel, at -12 dBm
python firmware/tools/beacon.py \
    --address C0:FF:EE:BE:AC:01 \
    --data 0201060B09524546434845434B \
    --length 31 --channel 37 --power -12 --count 1000

# Step the power from maximum to minimum, 200 packets at each level
python firmware/tools/beacon.py --sweep --count 200 --interval 10 --log run.csv
```

Or skip Python entirely and type at the board: `pio device monitor`, then
`ch all` and `send 1000 20`.

### Drive the app

```bash
python firmware/tools/appctl.py state     # source, run state, packet count
python firmware/tools/appctl.py usb       # select the board and connect
python firmware/tools/appctl.py phone     # select the phone's own radio
python firmware/tools/appctl.py both      # both sources at once
python firmware/tools/appctl.py raw       # board reports bytes as received
python firmware/tools/appctl.py std       # board checks and strips the CRC
python firmware/tools/appctl.py clear
python firmware/tools/appctl.py start
python firmware/tools/appctl.py stop
python firmware/tools/rxchan.py 37        # or 38, 39, all
```

Every action finds its control in the view hierarchy and verifies the result, so
a mistap fails loudly instead of recording a scenario on the wrong channel.

### Run an experiment

Four physical runs per scale, scored as six conditions. Prove the chain first —
a crash five hours into a long run costs the cell in flight:

```bash
# Smoke pass: one repetition, 200 events, ~10 minutes
python firmware/tools/experiment.py run --scale single --reps 1 --count 200

# A full clean series
python firmware/tools/experiment.py run --scale triple --reps 10 --count 1000

# The same under interference. Prompts you to confirm the generator is
# radiating, and records the name and frequency in every row.
python firmware/tools/experiment.py run --scale triple --reps 10 --count 1000 \
    --generator hmc833 --mhz 2402 --interferer-channel 37

# Re-run a single cell: builtin, nrf, builtin-adapt, nrf-adapt
python firmware/tools/experiment.py run --scale triple --reps 10 --cell nrf-adapt

python firmware/tools/experiment.py analyse   # score captures -> bench/experiment-results.csv
python firmware/tools/experiment.py plot      # figures (needs matplotlib)
```

`--scale single` sends on channel 37 only; `--scale triple` sends on all three,
so an event becomes three packets.

> `experiment.py` writes its output to `bench/experiment/`, creating it on first
> run. That directory is not part of this repository and is git-ignored.
>
> **`analyse` reads every `*.json` in `bench/experiment/`, and a run overwrites
> `<scale>-<cell>.*` from any previous series.** Copy a finished series into its
> own subdirectory before starting the next one, or the two will be scored as
> one. Subdirectories are not scanned, so a backup there is safe:
>
> ```bash
> mkdir -p bench/experiment/series1-clean
> cp bench/experiment/triple-*.json bench/experiment/triple-*.db bench/experiment/series1-clean/
> cp bench/experiment-results.csv bench/experiment/series1-clean/series1-results.csv
> ```

## What the app does

- **Two sources.** The phone's Bluetooth radio, or an nRF52840 attached over USB.
- **Channel selection.** With the nRF52840 you choose advertising channel 37, 38,
  39, or all three. The board hops between them in "all" mode - one radio cannot
  physically sit on three frequencies at once. Pausing the capture tells the
  board to stop, so it is not filling the USB pipe with packets nobody wants.
- **Two views.** *Sequential* lists every packet as it arrives. *Grouped*
  accumulates per advertiser address, ordered by packet count, showing the count,
  the most recent RSSI and how long ago that packet arrived. Tapping a group
  drills into its packets and the back arrow returns.
- **Packet detail.** Tapping a packet expands it to a decoded view (PDU header,
  address, advertising data structures), the raw payload, and a copy button.
  Packets that fail CRC are shown, flagged rather than hidden.
- **Reading without losing the stream.** Scrolling the list or opening a packet
  pins it in place while packets keep accumulating. A bar with a triangular arrow
  appears at the top; tapping it jumps back to the newest packet.
- **Files.** Save a session as JSON or CSV and reopen either format later in a
  read-only viewer that behaves like a live capture.
- **Filters.** Narrow both views by advertiser address and by packet type. The
  type menu offers what the capture has actually produced, because the two
  sources name PDU types differently.
- **Storage.** Packets go to SQLite as they arrive, so memory holds only a window
  of recent ones and a long capture cannot exhaust it.

## Documents

The reference documents are included below, in full.

| | |
|---|---|
| [BLE Advertising Packet Analysis and Recovery System](#ble-advertising-packet-analysis-and-recovery-system) | the specification this implements |
| [Deduplication and packet recovery](#deduplication-and-packet-recovery) | deduplication and the repair pipeline, with measurements |
| [Learning from packets: a re-evaluation](#learning-from-packets-a-re-evaluation) | what to learn from packets, and the model that does it |
| [Queueing-Theoretic Planning of BLE Advertising Transmission](#queueing-theoretic-planning-of-ble-advertising-transmission) | the queueing model the adaptive beacon plans from |

## Build

### Firmware

```bash
cd firmware
pio run                                  # build every environment
pio run -e beacon -t upload              # flash the plain beacon
pio run -e beacon_adaptive -t upload     # flash the adaptive beacon
pio run -e adafruit_feather_nrf52840 -t upload   # flash the sniffer
pio device monitor                       # drive it by hand
```

Upload uses a 1200 bps touch to enter the bootloader. `experiment.py` issues
these same commands when it swaps arms between cells.

### Android app

```bash
cd android
./gradlew assembleDebug
./gradlew installDebug        # or: adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest   # export/import round-trip tests
```

Toolchain: AGP 8.13.2, Gradle 8.14.5, Kotlin 2.3.21, compileSdk 36, minSdk 26.

The build needs a JDK of **21 or below**. If `JAVA_HOME` points at JDK 25, this
Kotlin version cannot parse the version string and fails during configuration
with a bare `IllegalArgumentException: 25.0.1` and no mention of Java at all.
Android Studio's bundled runtime works:

```bash
JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew assembleDebug
```

## Using the nRF52840 source

The board plugs into the **phone**, not the PC - a USB-C OTG adapter, or a
USB-C-to-USB-C cable if the board has one. Then in the app:

1. Set the source selector to *nRF52840 (USB)*.
2. Grant the USB permission when prompted, then press **Connect**.
3. Choose a channel and press **Start**.

Android will offer to launch the app automatically when the board is plugged in.

## Notes on what each source can tell you

The two sources do not see the same thing, and the app does not pretend
otherwise:

|                    | Phone radio                | nRF52840                |
| ------------------ | -------------------------- | ----------------------- |
| Channel per packet | not reported, shown as `—` | 37 / 38 / 39            |
| CRC failures       | dropped by the controller  | captured and flagged    |
| Raw data           | advertising data only      | full link layer PDU     |
| Timing             | arrival time               | hardware timestamp      |

The phone's controller hands up a parsed scan result, so the channel a packet
arrived on and any packet that failed CRC are gone before the app could see them.
The nRF52840 drives the radio directly and reports both.

This asymmetry is why measurements are reported per receiver rather than pooled:
a delivery figure from the phone and one from the board are not the same
quantity.

## Troubleshooting

| Symptom | Cause |
|---|---|
| `no phone on adb` | Wireless debugging dropped on reboot and came back on a new port. Run `adb devices`, then set `BLESNIFF_DEVICE` |
| `the control did not change state` | Usually Bluetooth is off, not a UI bug. `adb shell svc bluetooth enable` |
| `no nRF52840 beacon on this PC's USB` | Only the transmitter is on the PC; the sniffer board hangs off the phone. Seeing one COM port is correct |
| Board missing from `/dev/bus/usb` on the phone | The first `Connect` after a source switch can sit behind Android's USB permission prompt |
| A capture of zeros | Indistinguishable from total jamming in the totals. Check packet timestamps — a dead capture truncates early, a denied channel stays sparse throughout |
| `no console available: this scenario needs an operator` | `--generator` prompts for confirmation and needs a terminal |

## Environment workaround on this machine

Avast Web Shield intercepts HTTPS and re-signs it with a locally generated CA.
Windows, curl and Node trust that CA; the JVM does not, so Gradle's downloads
fail with `PKIX path building failed`. Two places point the JVM at a truststore
that includes it:

- `android/gradle.properties` - `org.gradle.jvmargs`, for the build itself.
- `android/gradlew[.bat]` - `DEFAULT_JVM_OPTS`, for the wrapper's own download,
  which runs before any project configuration is read.

Regenerate the truststore with `android/gradle/setup-truststore.ps1` if the
antivirus is reinstalled. On a machine without TLS interception, delete the
`-Djavax.net.ssl.*` flags from both files.


---

## BLE Advertising Packet Analysis and Recovery System

### Concept, Work Plan and Technical Specification

---

### 1. Starting point and core idea

There are two data sources, and they are fundamentally different:

| | Internal receiver (host BLE stack) | nRF52 (sniffer firmware) |
|---|---|---|
| Data level | Parsed ScanResult | Raw PDU + PHY metadata |
| Corrupted packets | Invisible (dropped by the stack) | **Visible** (CRC-fail) |
| Channel 37/38/39 | Usually unknown | Known |
| Timestamp | ms, with OS jitter | µs, hardware capture |
| Deduplication | Done by the stack (information lost) | None |

This leads to the main architectural conclusion: **nRF52 is the primary research source, the internal receiver is the source of reference labels and cross-validation.** Packets seen by both sources provide a free correctness check for parsing and recovery.

Second key principle: **a neural network must not do what algebra already does.** Recovery starts with a deterministic layer (CRC-24 syndrome decoding, majority voting over repetitions); only the residual errors are handed to an ML model. ML acts as the *prior*, CRC as the *verifier*.

---

### 2. System architecture

```
[nRF52 x1..3] --USB CDC--> ┐
                           ├--> Ingest --> Normalize --> Storage (Parquet + PCAP)
[Internal receiver] -------┘                  |
                                              v
                  ┌───────────────────────────┴──────────────────────────┐
                  |                                                      |
           Recovery layer                                   Analytics layer
     (CRC decoder → voting →                      (classification, fingerprinting,
      device template → ML prior)                       anomaly detection)
                  |                                                      |
                  └───────────────────> Reports / UI / API <─────────────┘
```

#### 2.1 Modules

1. **fw-nrf52** — sniffer firmware.
2. **ingest** — stream reception, framing, time synchronization.
3. **normalize** — dewhitening, PDU/AD structure parsing, unified record schema.
4. **store** — storage (Parquet/DuckDB) + PCAP export.
5. **recover** — corrupted packet recovery pipeline.
6. **features** — aggregation into advertising events and device tracks, feature computation.
7. **ml** — model training and inference.
8. **api/ui** — browsing, labeling, reporting.

---

### 3. nRF52 firmware requirements (module `fw-nrf52`)

This is the highest-risk component — whether corrupted packets are available at all depends entirely on it.

**Mandatory:**

- **F1.** Do not discard packets with `CRCSTATUS = 0`. Pass them up with a `crc_ok=false` flag.
- **F2.** Fixed-length reception (`STATLEN`, disable dependency on the `Length` field). Reason: if the length field is corrupted, the radio misplaces the packet boundary and drops the tail together with the CRC. The firmware must always capture the maximum byte count (39 + 3 CRC); the true length is determined in software by candidate search.
- **F3.** Hardware timestamping: `TIMER` + `PPI` on the `RADIO->EVENTS_ADDRESS` event, resolution ≤ 1 µs.
- **F4.** Report per packet: `channel`, `rssi`, `crc_ok`, `timestamp_us`, `aa` (access address), and raw bytes **before** dewhitening (dewhiten on the host — this preserves the ability to recompute).
- **F5.** Periodic sync frame (once per second) carrying the current timer value, for synchronization with the host clock.
- **F6.** Drop counter (buffer overflow) in every sync frame. Without it, reception statistics cannot be assessed honestly.
- **F7.** USB CDC stream framing (COBS or SLIP) with a per-frame CRC-16.

**Desirable:**

- **F8.** Round-robin mode across channels 37/38/39 with configurable dwell time, or a 3-board configuration (one per channel).
- **F9.** Export of `RSSISAMPLE` at several points during packet reception.

**Limitations to be recorded in project documentation:**

- Errors in the **Access Address** make a packet completely undetectable. Consequently, observed error statistics are biased: only packets with an intact AA are visible. This underestimates BER.
- The nRF52 in packet mode provides **no soft decisions** (LLRs) on bits. Soft-decision decoding is unavailable; this is a fundamental limitation compared to an SDR. If recovery quality proves insufficient, the only way up is adding an SDR receiver.
- One radio listens to one channel at a time.

---

### 4. Synchronizing the two sources (module `ingest`)

Goal: bring nRF52 and host timestamps onto a common scale with ≤ 1 ms accuracy.

**Method:**
1. Coarse alignment via sync frames (F5).
2. Refinement: find packets received by both sources (matching `AdvA` + payload hash within a ±200 ms window).
3. Estimate offset and drift over the match set by linear regression `t_host = a·t_nrf + b`, using a robust estimator (RANSAC/Huber) — OS jitter produces heavy tails.
4. Recompute `a, b` over a sliding window (the nRF52 crystal drifts with temperature).

**Acceptance:** residual synchronization error (median |residual|) < 1 ms over a one-hour capture.

---

### 5. Data schema (module `normalize`)

Unified `packet` record:

| Field | Type | Description |
|---|---|---|
| `pkt_id` | uint64 | Global ID |
| `source` | enum | `nrf52_<n>` / `host` |
| `t_ns` | int64 | Time on the common scale |
| `t_raw_us` | int64 | Original source timestamp |
| `channel` | uint8 | 37/38/39, null for host |
| `rssi_dbm` | int8 | |
| `crc_ok` | bool | |
| `raw_whitened` | bytes | Raw bytes as received |
| `pdu` | bytes | After dewhitening |
| `pdu_type` | uint8 | ADV_IND, ADV_NONCONN_IND, … |
| `tx_add`/`rx_add` | bool | Address type (public/random) |
| `adv_a` | bytes(6) | null if not recovered |
| `ad_structs` | list | Parsed LTVs |
| `parse_ok` | bool | LTV chain tiles the payload exactly |
| `recovery` | struct | see below |

`recovery` struct: `{method, n_bits_corrected, confidence, candidates_evaluated, original_crc_ok}`.

**Requirement:** recovered packets **never** overwrite the originals. Recovery lives in separate fields/tables. Every analytics path must be able to run in "original CRC-OK only" mode for comparison.

**Export:** PCAP with DLT `BLUETOOTH_LE_LL_WITH_PHDR` (251), so any capture opens in Wireshark without proprietary tooling.

---

### 6. Packet recovery (module `recover`)

A five-layer pipeline applied in order. Each layer runs only on what the previous one left behind.

#### Layer 1. CRC-24 syndrome decoding

BLE uses CRC-24 (polynomial `x²⁴+x¹⁰+x⁹+x⁶+x⁴+x³+x+1`, init `0x555555` for advertising). The CRC is linear, so the syndrome `S = CRC(received) ⊕ received_CRC_field` depends **only on the error vector**, not on the data.

- **1 bit:** precompute a "syndrome → position" table for all ≈336 bit positions (2 header bytes + 37 payload + 3 CRC). Correction is an O(1) hash lookup. Guaranteed correct.
- **2 bits:** search ≈56,000 pairs against the table. False-acceptance probability ≈ 56000/2²⁴ ≈ 0.33 %, reduced further by the Layer 3 filter.
- **3 bits:** ≈6.3 M combinations. Offline only; keep behind a flag and mark results low-confidence.

Separate branch: if the **Length field** is corrupted, the CRC boundary is unknown. Iterate all lengths 6…37 and check the CRC for each. Requirement F2 exists specifically for this case.

#### Layer 2. Majority voting over repetitions

Highest yield for the lowest complexity.

- **Across channels:** a single advertising event is transmitted up to 3 times (ch 37/38/39) within a few milliseconds. With 3 receivers, do 2-of-3 bitwise voting.
- **Across time:** beacons repeat an identical payload every 100 ms…1 s. Group by `adv_a` and take a bitwise median over a window of N receptions.
- **Across sources:** a packet received cleanly by the host serves as ground truth for a corrupted nRF52 reception.

Event grouping: same `adv_a` + payload Hamming distance below threshold + time window. The threshold is tuned on data.

**Important:** do not vote on counter fields or any field that changes packet to packet. Hence the next layer.

#### Layer 3. Device template (structural validation)

For each `adv_a` (or stable fingerprint), accumulate a per-byte profile: entropy of each position across packet history. Positions are classified as:

- **constant** (vendor prefix, UUID, flags) — trivially restored;
- **monotonic counters** — predicted from neighbouring packets;
- **random/encrypted** (e.g. randomized MAC, RPA) — not recoverable, flagged as such.

Plus a hard structural filter: the AD structure chain `[Length][Type][Data]` must tile the payload **exactly**. Candidates failing this check are rejected — this drastically reduces the false-recovery rate of Layers 1 and 2.

#### Layer 4. ML prior over the byte sequence

For the residual only: 3+ bit errors, no repetitions available, unknown device.

- **Model:** byte-level masked language model — a small transformer (4–6 layers, d=128–256, vocabulary 256 + special tokens) or a dilated 1D CNN. Trained on a corpus of valid PDUs from the same environment; task is masked-byte reconstruction. Conditioned on channel, length and PDU type as extra tokens.
- **Inference:** the model produces a distribution over candidates; candidates are **always** checked against the CRC. The accepted candidate is the one with maximum `P(candidate)` among those passing both the CRC and the structural check.
- This is a "generative prior + algebraic verifier" scheme; the model is never treated as a source of truth on its own.

#### Layer 5. Flagged as unrecoverable

The packet is stored with a flag and contributes only to channel statistics (BER estimation, interference diagnostics).

#### Evaluating recovery quality

Real errors have no ground truth, so **two** independent methodologies are required:

1. **Synthetic.** Take CRC-OK packets, inject errors under a BSC model (and separately under a burst model, closer to real fading), run the pipeline, compare with the original. Yields "recovery rate vs BER" curves.
2. **Hardware.** A reference beacon with a known fixed payload, separated by distance or an attenuator until errors appear. Yields real error statistics with known ground truth.

**Acceptance metrics:**

| Metric | Target |
|---|---|
| Share of corrupted packets recovered, BER=10⁻³ | ≥ 80 % |
| Share recovered, BER=10⁻² | ≥ 30 % |
| **False recovery rate** (accepted but wrong) | **< 10⁻³** |
| Pipeline throughput | ≥ 5000 packets/s (Layers 1–3) |

A false recovery is worse than a refusal: an incorrectly "fixed" packet poisons all downstream analytics and training sets. This metric takes priority over recall.

---

### 7. Analytics tasks (module `ml`)

#### 7.1 Device type / vendor classification
- **Input:** tabular features (AD Type set and order, lengths, Service UUIDs, Company ID, TX Power, PDU type, address type) plus payload bytes.
- **Model:** baseline — gradient boosting on tabular features. A neural network (byte embedding → 1D CNN) only if the dataset exceeds tens of thousands of packets and boosting plateaus.
- **Metric:** macro-F1. **Split the dataset by device, not by packet** (otherwise you get leakage and fictitious 99 %).

#### 7.2 Device fingerprinting across MAC rotation
- **Input:** time series — inter-packet intervals, jitter, channel traversal pattern, RSSI drift; plus the structural "handwriting" of AD fields.
- **Model:** siamese network / metric learning (triplet loss) over a GRU or Temporal CNN → device embedding; track linking by distance threshold.
- **Metric:** rank-1 accuracy, mAP, share of correctly linked rotations; ROC for the link/no-link decision.
- Intervals and jitter are often more informative than the payload — they reflect stack configuration and crystal drift.

#### 7.3 Anomaly and spoofing detection
- **Model:** autoencoder or One-Class SVM trained on normal traffic; no attack labels required.
- **Validation scenarios:** cloned beacon (same payload, different RSSI profile and jitter), advertising flood, atypical payloads.
- **Metric:** AUC-PR on injected scenarios.

#### 7.4 Proprietary format reverse engineering
- Payload clustering by vendor, per-byte entropy and autocorrelation analysis → hypotheses about counters, state fields, checksums. An analyst's assistant tool, not an automated conclusion.

---

### 8. Phased work plan

| Phase | Content | Deliverable / acceptance criterion | Estimate |
|---|---|---|---|
| **0. Infrastructure** | fw-nrf52 (F1–F7), ingest, synchronization, data schema, PCAP export | One-hour capture opens in Wireshark; sync error < 1 ms; drop counter works | 3–4 weeks |
| **1. Parsing + deterministic recovery** | Dewhitening, LTV parser, pipeline Layers 1–3, synthetic error harness | Recovery-rate vs BER curves; false recovery < 10⁻³ | 3–4 weeks |
| **2. Dataset and baseline classification** | Labeled corpus collection (≥ 50 devices), boosting baseline | Baseline macro-F1 fixed as reference point | 2–3 weeks |
| **3. ML recovery** | Byte-level MLM, integration as Layer 4 | At least +10 pp recovery rate over Layers 1–3 at BER=10⁻², with the false-recovery threshold held | 4–5 weeks |
| **4. Fingerprinting** | Metric learning, track linking | rank-1 ≥ target on held-out devices | 4–5 weeks |
| **5. Anomalies and productionization** | Autoencoder, API, UI, monitoring | Real-time operation, AUC-PR on scenarios | 3–4 weeks |

Phases 2 and 3 partially parallelize; Phase 4 depends on data from Phase 2.

**Team:** 1 embedded engineer (Phase 0, part-time thereafter), 1–2 backend/data engineers, 1 ML engineer, 1 analyst for labeling.

---

### 9. Risks

| Risk | Mitigation |
|---|---|
| Firmware does not deliver CRC-fail packets | Verify **before** Phase 1 begins — this is a blocking requirement; the whole project rests on it |
| USB CDC drops distort statistics | Drop counter (F6); if needed, log to the board's SD card |
| No soft decisions → ceiling on recovery quality | Design in the ability to attach an SDR as a third source |
| Fingerprinting model overfits to specific units | Mandatory split by device, held-out device set |
| Too few corrupted packets under lab conditions | Attenuator/shielding, controlled bench with a reference beacon |

---

### 10. Legal and ethical scope

BLE device fingerprinting effectively circumvents the privacy mechanism that MAC randomization was introduced to provide. This is acceptable for research and defensive work, but the specification should record:

- collection in a controlled environment or with informed consent from participants;
- pseudonymized storage of `adv_a` and device embeddings, with the key held separately;
- retention period for raw captures and a deletion procedure;
- prohibition on exporting raw tracks outside the security perimeter.


---

## Deduplication and packet recovery

How the app reconciles two receivers and repairs corrupt packets, what the
algorithms actually are, and what they measure at.

Implements sections 4, 5 and 6 of [the specification](#ble-advertising-packet-analysis-and-recovery-system). Where this falls
short of that specification, it says so and why.

---

### 0. What is and is not here

The specification's recovery pipeline has five layers. Three of them run.

| Layer | Spec | Status |
|---|---|---|
| 1 | CRC-24 syndrome decoding | **Implemented and working on real packets** (§8.2) |
| 2 | Majority voting over repetitions | **Implemented**, including across receivers (§8.4) |
| 3 | Device template + structural validation | **Implemented**, as a gate on layer 2 |
| 4 | Prior over the byte sequence | **Implemented** — an order-1 context model, not a transformer |
| 5 | Flagged unrecoverable | **Implemented** |

Layers 1 and 4 both require the received CRC bytes. In the firmware's normal mode
the radio verifies and strips them, so both are skipped rather than allowed to
run without the check they depend on. Requirements **F2/F4** — fixed-length
capture including the CRC, whitening left to the host — make them reachable, and
the firmware implements both.

They were nonetheless inert until recently, for a reason nothing in this document
predicted: the CRC seed was bit-reversed, so *every* raw-mode packet failed its
check and layer 1 corrected noise. See §8.2.

**There is still no neural network in this app.** The specification is explicit about
why that ordering matters (§1): *"a neural network must not do what algebra
already does… ML acts as the prior, CRC as the verifier."* Layer 4 sits last, for residual errors only. The specification names a byte-level
masked language model — a small transformer or dilated CNN. What is implemented
is an **order-1 context model with per-position statistics**, trained by counting
on the traffic already captured:

```
    log P(x) = Σᵢ [ log P(xᵢ | xᵢ₋₁) + log P(xᵢ | i) ] / 2
```

with Laplace smoothing (α = 0.5). It trains in one pass on the phone, ships no
corpus, and can be swapped for the specified model without changing anything
around it. A transformer would capture longer-range structure; this captures
which bytes follow which and what belongs at each offset, which is most of what
advertising payloads exhibit. The gap is stated rather than glossed.

[Learning from packets](#learning-from-packets-a-re-evaluation) re-examines that choice and argues it was the right one for
reasons better than expedience — and adds what this pipeline has no answer for
at all: what *kind* of error a corrupt packet is carrying (a burst, a collision
between two transmissions, damage to the length field), and who sent a packet
that could not be repaired. Neither is wired into the capture path yet; that
document says what it would cost.

What *is* learned from data is the **per-device byte template** (§3 below): a
statistical profile built from observed traffic that classifies each byte
position as stable or varying. That is a model fitted to data, not a neural one,
and the distinction is kept sharp throughout this document.

Deduplication uses no model at all, by design. Two receptions of one
transmission are identified by exact equality of advertiser address and payload.
A learned matcher could only be less accurate than `==`.

---

### 1. Deduplication

#### 1.1 The problem

The two receivers are not equivalent:

| | Host BLE stack | nRF52840 |
|---|---|---|
| Data level | parsed `ScanResult` | raw PDU |
| CRC failures | discarded by the controller | delivered, flagged |
| Channel | not reported | 37 / 38 / 39 |
| Timestamp | OS clock, jittered | hardware capture, µs |

So the nRF52840 is the primary source and the host is corroboration — the spec's
central architectural conclusion (§1).

#### 1.2 Matching key

Two receptions are the same transmission when

```
key(p) = advertiser_address(p) ‖ payload(p)
```

where `payload` is the advertising-data region only. The host delivers just that
region; the board delivers the whole PDU including a 2-byte header and the
6-byte address. Comparing whole PDUs would never match. RSSI and channel are
excluded — the receivers measure different signal strengths for one
transmission, and the host has no channel to compare.

#### 1.3 Clock alignment

The board's clock counts from whenever capture started; the host's is wall time.
Alignment is `t_host ≈ t_nrf + δ`, and δ must be estimated from the data.

Pairs are matched, and each contributes a sample

```
δᵢ = t_host,i − t_nrf,i
```

with the estimate being the **median**

```
δ̂ = median{ δᵢ : i ∈ last 64 pairs }
```

Median rather than mean because OS scheduling gives host timestamps a heavy
right tail — a single 30-second outlier would drag a mean arbitrarily far. This
is the robust estimator §4 calls for, in its cheapest form.

**Bootstrapping.** There is a chicken-and-egg problem the spec solves with
firmware sync frames (requirement F5) that this firmware does not send: matching
compares *aligned* times, so until δ is known, a board whose timeline starts
500 ms away from the host's produces no pairs inside a 200 ms window, and δ can
never be learned. Two separate windows resolve it:

- **measurement** searches ±5 s on *raw* timestamps and only records samples;
- **merging** uses ±200 ms on *aligned* timestamps and actually folds packets.

**Corroboration.** δ̂ is not applied until 5 pairs agree. For a beacon repeating
one payload, a single pair is ambiguous — the host's copy of *this* transmission
and of the *next* one are indistinguishable without the very clock being
measured. One coincidence must not shift the whole timeline.

#### 1.4 Which copy survives

```
keep(a, b) = a                    if source(a) = source(b)
           = the nRF52840 copy    otherwise
```

The board's copy carries the channel, the hardware timestamp and the true CRC
status. The survivor records `sources = {nrf52840, host}`, so nothing about the
host's observation is lost except a duplicate row.

#### 1.5 Cost

Deduplication delays display by the 200 ms matching window, because a duplicate
can only be recognised once its twin has had time to arrive. This is paid only
in *Both together* mode; single-source capture passes straight through.

---

### 2. Recovery pipeline

```
   corrupt packet (crc_ok = false)
              │
              ▼
   ┌──────────────────────────┐
   │ Layer 2: voting          │   clean receptions of the same frame,
   │  references within 30 s  │   from either receiver
   └──────────┬───────────────┘
              │  candidate
              ▼
   ┌──────────────────────────┐
   │ Layer 3: gate            │   (a) every differing byte must be at a
   │  template + structure    │       position the device never varies
   └──────────┬───────────────┘   (b) AD chain must tile the payload exactly
              │  accepted
              ▼
   ┌──────────────────────────┐
   │ record repair alongside  │   original bytes are never overwritten
   │ the original             │
   └──────────────────────────┘

   anything rejected at any step  ──▶  Layer 5: kept, flagged, counted
```

#### 2.1 Layer 2 — voting over repetitions

For a damaged packet `d` and clean references `R` from the same advertiser
within the history window, using byte-level Hamming distance

```
D(x, y) = |{ i : xᵢ ≠ yᵢ }|
```

the candidate set is

```
C = { r ∈ R : |r| = |d| ∧ 1 ≤ D(r, d) ≤ 4 }
```

and the winner `r*` is accepted only if all of:

1. **Unique.** `r*` is the strict minimiser of `D(·, d)`. Two distinct
   references equally close means the evidence does not single out an answer;
   picking one would be a coin toss recorded as a repair.
2. **Corroborated.** `r*` was received at least twice. A frame seen once could
   itself have been a one-off.
3. **Stable differences.** Every position where `r*` differs from `d` is one the
   template marks stable (§3).
4. **Structurally valid.** §4.

#### 2.2 Layer 3 is a gate, not a repair

Restoring only the positions a device never varies *cannot* reconstruct a packet
whose errors also landed on a byte that does change: the output differs from the
truth while looking perfectly well-formed. Measurement bore this out — as a
standalone repair path it produced wrong answers on **14.3%** of heavily
corrupted packets, worse than refusing. It was removed as a repair and kept as
the gate in step 3 above, which is the role §6.2 actually assigns it: *"do not
vote on counter fields or any field that changes packet to packet."*

#### 2.3 Confidence

Reported for every accepted repair, never reaching certainty:

```
conf(b, n) = 0.05 + 0.35·min(n,8)/8 + 0.60·(1 − min(b,16)/16)
```

with `b` the bits changed and `n` the number of references. More corroboration
and fewer changed bits both raise it. The ceiling of 0.95 is deliberate: nothing
in this pipeline is proof.

---

### 3. Device byte template

The learned component. For advertiser `a`, each byte position `i` accumulates the
set of values observed:

```
V_a(i) = { xᵢ : x ∈ observed clean packets of a }
```

capped at 5 distinct values per position (beyond which the position is varying
regardless). A position is **stable** when

```
stable_a(i)  ⟺  |V_a(i)| = 1  ∧  samples(a) ≥ 8
```

The sample floor matters: below it, "has only ever held one value" is
indistinguishable from "has not been seen to vary yet".

This recovers the classification §6.3 asks for without needing entropy
thresholds — vendor prefixes, UUIDs and flags land in the stable set; counters
and randomised addresses do not, and are therefore never rewritten.

**Worked example.** A beacon whose 4th byte counts:

```
observed:  11 22 33 00
           11 22 33 01
           11 22 33 02          →  V(0)={11} V(1)={22} V(2)={33} V(3)={00,01,02}
           …                       stable:  0,1,2        varying: 3

damaged:   99 22 33 07
candidate: 11 22 33 07   ← byte 0 restored (stable), byte 3 untouched (varying)
```

Had byte 3 also been corrupted, the differences would include a varying position
and the whole repair would be refused, not half-applied.

---

### 4. Structural validation

Advertising data is a chain of `[length][type][data…]`. Valid data tiles its
region exactly:

```
i ← ad_offset
while i < n:
    L ← x[i]
    if L = 0:  return all remaining bytes are zero      (end-of-data padding)
    i ← i + L + 1
return i = n                                            (exact tiling)
```

A chain that overruns or underruns its region cannot be what the device sent.
This is the filter §6.3 credits with drastically reducing false repairs.

---

### 5. Measured results

Method per §6: take packets that arrived intact, inject known errors, check
whether the pipeline reproduces the original **exactly**.

- **Corpus** — 2,854 real PDUs from 18 advertisers, captured with the nRF52840 in
  this environment. Real payload structure matters: the template and structural
  layers exploit it, and synthetic payloads would flatter them.
- **Protocol** — two thirds of each advertiser's packets build history, one third
  is damaged and measured.
- **Error models** — binary symmetric channel at several BERs, and single
  contiguous bursts.
- Reproduce with `./gradlew testDebugUnitTest --tests '*RecoveryEvaluationTest*' -i`.

| Condition | Damaged | Recovered | False | Refused |
|---|---:|---:|---:|---:|
| BSC, BER = 10⁻⁴ | 16 | 13 (81.3%) | 0 (0.00%) | 3 |
| BSC, BER = 10⁻³ | 153 | 113 (73.9%) | 1 (0.65%) | 39 |
| BSC, BER = 3×10⁻³ | 402 | 297 (73.9%) | 1 (0.25%) | 104 |
| BSC, BER = 10⁻² | 761 | 479 (62.9%) | 1 (0.13%) | 281 |
| BSC, BER = 3×10⁻² | 881 | 130 (14.8%) | 0 (0.00%) | 751 |
| Burst, 2 bits | 888 | 632 (71.2%) | 1 (0.11%) | 255 |
| Burst, 4 bits | 888 | 634 (71.4%) | 1 (0.11%) | 253 |
| Burst, 8 bits | 888 | 619 (69.7%) | 1 (0.11%) | 268 |
| Burst, 16 bits | 888 | 610 (68.7%) | 1 (0.11%) | 277 |

#### 5.1 With the CRC field present — all four layers

Repeating the same harness with each frame carrying its CRC, as raw capture mode
would deliver it:

| Condition | Damaged | Recovered | False | Refused |
|---|---:|---:|---:|---:|
| BSC, BER = 10⁻⁴ | 19 | 19 (100.0%) | 0 (0.00%) | 0 |
| BSC, BER = 10⁻³ | 170 | 154 (90.6%) | **0 (0.00%)** | 16 |
| BSC, BER = 3×10⁻³ | 428 | 386 (90.2%) | 1 (0.23%) | 41 |
| BSC, BER = 10⁻² | 779 | 551 (70.7%) | 8 (1.03%) | 220 |
| Burst, 2 bits | 888 | 772 (86.9%) | 0 (0.00%) | 116 |
| Burst, 4 bits | 888 | 624 (70.3%) | 0 (0.00%) | 264 |
| Burst, 8 bits | 888 | 607 (68.4%) | 0 (0.00%) | 281 |

The CRC is worth roughly **+17 percentage points** of recall at BER 10⁻³ (73.9%
→ 90.6%) and takes false repairs there to zero.

#### 5.2 Against the specification's acceptance targets

| Metric | Target | No CRC | With CRC | |
|---|---|---|---|---|
| Recovered at BER = 10⁻³ | ≥ 80% | 73.9% | **90.6%** | met with CRC |
| Recovered at BER = 10⁻² | ≥ 30% | 62.9% | **70.7%** | met |
| False recovery, BER ≤ 10⁻³ | < 10⁻³ | 6.5×10⁻³ | **0** | met with CRC |
| False recovery, BER = 10⁻² | < 10⁻³ | 1.3×10⁻³ | 1.0×10⁻² | not met |
| Throughput (layers 1–3) | ≥ 5000 pkt/s | not measured | not measured | — |

The residual at BER 10⁻² is intrinsic to two-bit syndrome decoding: at that error
rate most packets carry more than two flipped bits, and a two-bit "correction"
that happens to satisfy the CRC cannot be told from a real one. The
specification quotes ~0.33% false acceptance for the pair search and relies on
layer 3 to trim it, which the structural filter does; what remains is the price
of decoding two bits at all. Restricting layer 1 to single-bit corrections would
remove it at a cost in recall.

#### 5.3 Two defects the harness found

Neither was visible from the code or from a passing build.

- **Layer 3 as a standalone repair.** Restoring only stable positions cannot fix
  a packet whose errors also hit a varying byte; it emits a well-formed wrong
  answer. It produced wrong results on **14.3%** of heavily corrupted packets —
  worse than refusing. Removed as a repair, kept as the gate.
- **Structural check over the CRC bytes.** Once raw mode supplied them, layer 2
  validated the AD chain across `body ‖ crc`, which never tiles. Burst recall
  collapsed from 71% to **6.6%** until the check was given the body alone.

A third was caught by unit test rather than measurement: the CRC-24
implementation initially omitted the x²⁴ feedback term and ORed the tap mask
instead of XORing it, producing a checksum that was perfectly self-consistent and
not BLE's. The linearity property test found it; nothing else would have.

#### 5.2 Why the false-recovery target is not reached

Not a tuning problem. Tightening to `D ≤ 2` with three corroborations was tried:
recall at BER = 10⁻² fell from 62.9% to 36.8%, and the worst false rate did not
move. Recall was given up for nothing.

The cause is structural. The specification's design has the model propose and
**the CRC dispose** — §6.4: *"candidates are always checked against the CRC."*
In the firmware's normal mode the radio verifies the CRC in hardware and strips
it, so the received CRC field never reaches the host. There is no independent
verifier, and the structural check is far too weak a substitute: a wrong
candidate that happens to tile correctly is accepted with nothing left to catch
it.

Raw capture mode supplies the CRC and lifts that floor — the "with CRC" column
of §5.2 is the same pipeline with a verifier attached, and it meets the target at
BER ≤ 10⁻³. The figures in this section are what the pipeline does *without* one,
which is what the board delivers in its default mode.

Note that until the seed was corrected (§8.2) the "with CRC" numbers were the
only ones that could have been produced at all, because they are generated by a
harness that computes its own CRCs and therefore agreed with itself. On real raw
captures layer 1 was decoding noise.

#### 5.4 Evidence from real traffic

From a 59,229-packet session (27,517 from the board, 31,712 from the host):

- **660 of 27,187** inter-packet gaps were shorter than the packet's own air
  time — physically impossible, and the symptom that found a firmware bug where
  `END_START` let a second packet overwrite the capture register before the
  first packet's timestamp had been read.
- Gap distribution for one advertiser seen by both receivers:

  | gap | host | nRF52840 |
  |---|---:|---:|
  | < 50 ms | 45.7% | 80.8% |
  | 500–999 ms | 14.7% | 0.0% |
  | ≥ 1 s | 3.7% | 0.1% |

  The host's long tail is OS scan throttling, not the radio.

**In that session the deduplicator never ran on real simultaneous data.** The
two sources ran at different times (35 s inter-quartile spread between apparent
pairs), so only 12 payload keys appeared from both, and everything in §1 was
verified against synthetic pairs only.

A later session finally captured both receivers running together for 630
seconds. Deduplication found nothing at all in it, for two reasons synthetic
pairs are constitutionally unable to reproduce. See §8.5.

---

### 6. Data schema

§5 requires that a repair never overwrites what was received. Every packet
carries:

| Field | Meaning |
|---|---|
| `raw_hex` | bytes exactly as received — never modified |
| `sources` | every receiver that saw this transmission |
| `rec_method` | `cross_source` / `cross_time` / `template` / `unrecoverable` |
| `rec_hex` | repaired bytes, alongside the original |
| `rec_bits` | bits changed |
| `rec_conf` | confidence, §2.3 |
| `rec_candidates` | candidates evaluated |

All of it reaches the database, the JSON export and the CSV export, so any
analysis can be re-run over received bytes only.

---

### 7. Known gaps

All seven are closed. What closed them was finally pointing the boards at a room
and looking at what came back, which is recorded in §8. Three of the seven were
not gaps in coverage at all — they were defects that no test could have caught,
because every test in the suite was checking this project against itself.

| | Gap | Status |
|---|---|---|
| 1 | Layers 1 and 4 unreachable — firmware F2/F4 not written | **Closed.** The firmware had them all along; §8.1 |
| 2 | The CRC has never met a real BLE packet | **Closed**, and it failed — §8.2 |
| 3 | Layer 4 only breaks ties the CRC already settled | **Closed** — §8.3 |
| 4 | Cross-source repair mislabelled, best evidence discarded | **Closed** — §8.4 |
| 5 | Deduplication unvalidated on real data | **Closed**, and it did not work — §8.5 |
| 6 | Throughput unmeasured against ≥ 5000 packets/s | **Closed** — 42,000/s, §8.6 |
| 7 | Hardware evaluation with a reference beacon | **Closed.** Built, flashed, run — and neither error model survived it; §8.7 |

---

### 8. What real traffic changed

Everything below came out of one 630 second capture with both receivers running,
2,532 packets, kept as `app/src/test/resources/real-session.csv`, plus the 501
raw-mode frames in `real-raw-frames.csv`. Both are now part of the test suite.

#### 8.1 The firmware was never the blocker

`ble_sniffer.cpp` implements F2 and F4 in full: raw mode sets `STATLEN` to a
fixed 42 bytes with `LFLEN` zero, so a corrupted Length field cannot move the
frame boundary, and disables both whitening and the hardware CRC so the host
receives the bytes as they arrived. It has done so all along. The gap was
bookkeeping, and it hid the next one.

#### 8.2 The CRC met a real packet and got it wrong

**0 of 501** captured frames verified. Every packet in raw mode was reported
corrupt, which is what a user sees as "raw mode is broken".

The seed was bit-reversed. The specification states 0x555555; this
implementation shifts its register right, so the seed has to be reversed to
match — `rev24(0x555555) = 0xAAAAAA`. The polynomial mask and the byte order
were already right.

Nothing caught it because nothing could. Every test built its frames with
`Crc24.compute` and then checked them with `Crc24.compute`, and a wrong seed is
perfectly self-consistent: the linearity property still holds, single-bit
correction still works, every syndrome table is still exact, because syndromes
use a zero seed and never touch this constant at all. The suite was internally
flawless and disagreed with every BLE radio on earth.

With the seed corrected, **481 of 501** verify. The other 20 are genuine
reception errors, which is what a sniffer in a busy room is supposed to find.

That the bodies were right was established independently before the seed was
touched: they dewhiten into well-formed PDUs whose AD chains tile exactly, and a
PDU caught on two different channels dewhitens to identical bytes — which it can
only do if the whitening is correct, since the whitening key is the channel.

`RealCrcTest` now checks the CRC against those captured frames. It is the only
test here whose expected values this project did not produce.

#### 8.3 Layer 4 now decides something

It could not before: the prior was consulted only when exactly one candidate
survived the CRC, and a single survivor needs no ranking. It was decorative.

Now it may choose among several, but only when its favourite leads by 25 nats.
Measured at BER 1e-2, that recovers three more packets and produces **no**
additional false repairs (8 either way). At a margin of 8 it recovered the same
three and produced a ninth false repair, which is the wrong side of the trade the
specification settles explicitly, so the margin sits where the gain is free.

It remains an order-1 context model rather than the specified transformer, for
reasons re-examined in [Learning from packets](#learning-from-packets-a-re-evaluation) §1.

#### 8.4 Cross-source repair now happens at all

It never had. Two independent reasons, and the mislabelling was the symptom
rather than the disease:

- The deduplicator pairs receptions on address **plus payload**, and a corrupt
  copy has a different payload by definition. The one pair it most wants to make
  is the one pair it cannot make.
- Inside the pipeline the two receivers' frames were compared with
  `size == size`, which is never true. Android delivers advertising data alone;
  the board delivers a whole PDU with a two byte header and a six byte address
  in front of it. The host's clean copy was never a candidate for anything.

So `CROSS_SOURCE` could only ever be inferred from the packet being corroborated
— a fact about the packet, not about the evidence used to repair it.

Repairs now align on the advertising-data region, so the host's copy can be
spliced into the board's damaged frame. Only that region is written: the header
and address are left exactly as received, because the host never saw them.
When the frame carries its CRC the result is *verified* rather than believed,
and reported at 0.99 confidence. `CrossSourceRepairTest` covers it.

#### 8.5 Deduplication did not work on real data

Not "was unvalidated". Did not work: **zero** pairs across 2,532 packets from a
genuinely simultaneous capture.

Two causes, neither reproducible with synthetic pairs, which is exactly why
synthetic pairs had reported success:

- Raw capture appends the received CRC to the board's frame. Those three bytes
  are ones the host has never had and never will, so the payloads could not be
  equal. Now stripped before comparison.
- **Android does not deliver a PDU.** It merges an advertisement with the scan
  response that followed it into a single `ScanRecord`, so for any scannable
  advertiser the host's payload is the board's PDU with the scan response's
  structures appended:

  ```
  board:  020106 020A04 0302F018
  host:   020106 020A04 0302F018 0F09 "vLinker MC-IOS"
  ```

  Exact equality can never hold. Matching is now prefix containment with a
  minimum shared length of 8 bytes — below that, "matching" is the Flags
  structure that opens nearly every advertisement in existence.

Five cross-source merges result, from 2,532 packets. That is a small number and
worth being plain about why: in this session the board spent most of its time
capturing `SCAN_REQ` frames, which carry no advertising data at all, and only 36
of its 1,488 packets carried any. Five is a floor established on real traffic,
not a rate.

#### 8.6 Throughput

**42,000 packets/s** through the pipeline over the real capture, against the
specification's ≥ 5,000 target. Measured in `RealSessionTest`, which asserts only
2,000 — it runs on whatever machine is building, beside a Gradle daemon, and a
tight bound there would fail for reasons that have nothing to do with the code.

#### 8.7 The hardware bench, run

§6 of the specification asks for a reference beacon transmitting a known fixed
payload, separated by distance or an attenuator until errors appear. That is the
only independent check on the synthetic error models — it is the one measurement
that can say whether real interference resembles a binary symmetric channel or a
burst at all, and no amount of injected damage can answer it.

**The beacon now exists.** `firmware/src/beacon/` is a second firmware for a
second nRF52840, driven from the host by `firmware/tools/beacon.py`. It fixes the
advertiser address, the payload content, the payload length and the PDU type, and
transmits a chosen number of packets at a chosen power — or sweeps all fourteen
levels the radio supports, +8 dBm down to −40 dBm. That 48 dB is the attenuator,
built into the transmitter.

It is flashed and verified transmitting: 280 packets across seven power levels,
with the board reporting each burst and the PDU it sent.

```
$U,022501ACBEEEFFC00201060B09524546434845434B0000000000000000000000000000
```

`ADV_NONCONN_IND` by default, so scanners do not answer it and fill the capture
with `SCAN_REQ` traffic — which is exactly what drowned the useful data in the
session behind §8.5.

The procedure is written down in `firmware/README.md`.

##### The run

100 bursts on each of the three channels at each of the fourteen levels, 10 ms
apart — 4,200 transmissions in 14.0 seconds — with the sniffer capturing in raw
mode on `ch all`. It received **1,224** of them; it round-robins channels on a
4 ms dwell, so it is listening to roughly one channel in three.

Mean RSSI fell from −39.4 dBm at +8 to −86.6 dBm at −40: **47.2 dB observed for
48 dB commanded**, which is what confirms the received packets were correctly
attributed to the power level that sent them.

| TX power | received | damaged | mean RSSI |
|---:|---:|---:|---:|
| +8 … −20 dBm | 87–90 per level | **0** | −39.4 … −66.0 |
| −40 dBm | 78 | **17** | −86.6 |

Nothing broke until the last step. Every error in the run arrived at −40 dBm, at
the edge of the receiver's sensitivity — which is exactly the regime the
attenuation sweep exists to find.

##### What the channel actually does

This is the question §6 was posed to answer, and the answer is that **neither
error model is right**.

| bits differing | frames | | longest run of adjacent bits | frames |
|---:|---:|---|---:|---:|
| 1 | 4 | | 1 | 4 |
| 2 | 7 | | 2 | 9 |
| 3 | 1 | | 3 | 2 |
| 4 | 2 | | 6 | 1 |
| 6 | 1 | | 7 | 1 |
| 82 | 1 | | | |
| 118 | 1 | | | |

A binary symmetric channel produces isolated flips — longest run 1. Only 4 of
the 15 ordinary frames look like that. The dominant mode is damage in **adjacent
pairs**, 9 of 15. And the burst model injects 12–96 contiguous bits, which never
happened once.

Two frames are a third thing entirely, and belong to no bit-error model:

```
sent     022501ACBEEEFFC0 0201060B0952456E ...
received 022501ACBEEEFFC0 0201060B0952456E CA4AA62AACE93007 ...
                          ^ byte-perfect to here, then nothing matches at all
```

Byte-perfect for 9 bytes in one case and 15 in the other, then complete
divergence with **zero** of the remaining bytes matching by coincidence. That is
the demodulator losing lock partway through a packet and reporting noise for the
rest of it. It is not corruption of a transmitted packet; after the divergence
point, nothing that was transmitted is present.

##### Repair, checked against what was sent

The first time this project has measured recovery rather than modelled it.

| | |
|---|---:|
| Damaged frames with known ground truth | 17 |
| Repaired **correctly** | 14 |
| Repaired **wrongly** | **0** |
| Refused | 3 |
| Recovery rate | 82.4% |
| **False recovery rate** | **0.0%** (target < 0.1%) |

Ten by CRC syndrome decoding, four by voting over repeats. The three refusals
are both sync-loss frames — for which there is no correct repair, since the
bytes were never transmitted — and one other.

Two-bit syndrome decoding turns out to be well matched to this channel, which is
why it did most of the work: the dominant real error is an adjacent pair, and
that is precisely what it corrects exactly. The §5 synthetic figures are
pessimistic in a specific and now-identifiable way — they spend much of their
damage budget on long bursts this channel does not produce.

The capture is kept as `app/src/test/resources/reference-beacon.csv` and
`ReferenceBeaconTest` asserts the result, including that the false-repair count
stays at zero.

##### What this still does not cover

One bench, one room, one pair of boards, one afternoon. The error structure
above is what *this* channel did at *this* separation; a different room with
different multipath may well produce the bursts the model assumes. What the run
establishes is that the assumption needed checking, and how to check it again.


---

## Learning from packets: a re-evaluation

What kind of model this project should actually build, given that it has to
train on a phone from traffic it captures itself, keep learning while it runs,
never learn from its own repairs, classify what went wrong with a corrupt
packet, and name the sender of a packet it cannot fix.

Revises §6.4 and §7 of [the specification](#ble-advertising-packet-analysis-and-recovery-system). Companion to [Deduplication and packet recovery](#deduplication-and-packet-recovery),
which covers the deterministic layers this sits behind.

---

### 0. The short version

| | Specification (§6.4, §7) | This re-evaluation |
|---|---|---|
| Recovery model | byte-level masked LM, 4–6 layer transformer | counted per-device profiles + order-1 prior |
| Training | offline, on a corpus | one pass, on the device, continuously |
| Error classification | not addressed | five-class online logistic regression |
| Device identification | fingerprinting across MAC rotation (§7.2) | required to work on *damaged* frames, from bytes not addresses |
| Labels | manual, per device | manufactured damage on intact packets |
| Recovered packets | not addressed | **never used as training data**, enforced in code |

Implemented in `com.blesniffer.ml`, measured against the same 2,854-packet real
corpus the recovery pipeline is measured against, tests in `PacketModelTest`.

---

### 1. Why the specified model is the wrong one here

§6.4 names a byte-level masked language model — a small transformer or a dilated
1D CNN — trained on a corpus of valid PDUs. Nothing about that is wrong as
*modelling*. It is wrong as *engineering* for this system, for reasons that only
become visible once the constraints are written down together:

- **The corpus is the capture.** There is no separate training set. Advertising
  traffic is intensely local: the devices in this room, with these vendors,
  these service UUIDs and these advertising intervals. A model trained anywhere
  else is a model of somewhere else. So training has to happen where the capture
  happens.
- **The capture never ends.** A sniffer left running is handed a new
  distribution every time it moves. A model fitted once and frozen is
  progressively wrong, and it has no way to notice.
- **The device is a phone.** Backpropagating a transformer over a stream at
  5,000 packets/s, alongside the SQLite writer and a Compose UI, is not a
  budget decision — it is out of the question.
- **The residual is small and the verifier is strong.** Layers 1–3 already
  handle most of what is recoverable, and §6.4 is emphatic that the CRC decides.
  A model that only ever ranks candidates a 24-bit checksum has already admitted
  does not need to be able to *generate* — it needs to be able to *score*.

What those four constraints select for is not a smaller neural network. It is a
different family: models whose parameters are **sufficient statistics**, so an
update is exact arithmetic on a single example, one pass is enough, and merging
or ageing them is addition. Counting, in other words, plus a linear model where
counting cannot express the question.

That is a genuine trade and the cost is stated in §8, not buried.

---

### 2. What the models are

Three, sharing one training signal because they need the same one.

#### 2.1 Device profile — counted, per advertiser

For each advertiser, each byte position keeps a small histogram of the values it
has held, capped at 12 distinct values, past which the position is declared
**varying**.

```
    log P_dev(x) = (1/|I|) · Σ_{i ∈ I} log ( (n_i(x_i) + α) / (n_i + 256α) )
```

with α = 0.05 and `I` the retained positions (see trimming below).

α is small on purpose. The conventional α = 0.5 puts 128 counts of imaginary
evidence behind every position, and with 256 symbols that is enough to drown a
real device signature under a hundred real observations.

This subsumes and replaces `ByteTemplate` in the recovery pipeline: it answers
the same "does this position ever vary" question, and additionally says *what*
belongs there and how strongly.

**Trimming is what makes it work on damaged frames.** A corrupted byte
contributes about −7 to the sum where a correct byte contributes about −0.1, so
an untrimmed likelihood is dominated entirely by the damage — the right device
loses to whichever profile happens to be vaguest. Discarding the worst quarter
of positions scores the frame on the part that survived. This is a trimmed
likelihood, the standard robust-statistics answer to contamination, and it is
the single reason identification works at all on the packets that need it.

#### 2.2 Byte prior — counted, across all advertisers

Unchanged from [Deduplication and packet recovery](#deduplication-and-packet-recovery) §0: an order-1 context model with per-position
statistics, `log P(x) = Σ [log P(xᵢ|xᵢ₋₁) + log P(xᵢ|i)] / 2`. It ranks repair
candidates the CRC has already admitted.

It now does decide between them, but only when its favourite leads the runner-up
by 25 nats; below that the pipeline refuses rather than guessing. That threshold
was tuned by measurement, not argued — [Deduplication and packet recovery](#deduplication-and-packet-recovery) §8.3. Until the CRC seed was
corrected this layer could never have run on a real packet at all, because every
raw-mode frame failed its check (§8.2 there).

The prior is fed **only board receptions**. The two receivers frame packets
differently, so mixing the host's advertising-data blobs into a model whose
positions mean "byte 0 is a PDU type" would teach it that byte 0 is sometimes a
flags length instead.

#### 2.3 Error classifier — online logistic regression

Multinomial logistic regression over 14 features, fitted by SGD. Five classes,
fourteen features: **70 numbers**, the entire model.

The decision boundary is linear in the features, so the features carry the
structure. That is not a concession — a burst and a scatter differ in one
number (§4), and no amount of depth finds a cleaner separation than the physics
already provides.

The learning rate decays as `η₀/(1 + t·10⁻⁴)` with a floor, rather than
annealing to zero. A model that stops being able to move goes on confidently
reporting the room it was trained in.

---

### 3. The error taxonomy

Chosen by what each class implies for repair, not by what is easy to tell apart.

| Class | What it is | What the pipeline should do |
|---|---|---|
| `scattered` | a few isolated bit flips | syndrome decoding; voting also works |
| `burst` | one contiguous damaged run | too many bits for syndrome decoding — vote against a repeat |
| `splice` | head of one transmission, tail of another | **not repairable as one frame**; attribute each half |
| `header` | damage in the PDU header or length field | the frame boundary is wrong; search the length first |
| `noise` | no coherent frame at all | count it towards the error rate and refuse |

`splice` is the class that earns the whole exercise. A collision produces a frame
of *entirely valid bytes* that simply came from two senders — nothing is
corrupted in the ordinary sense, so every bit-pattern test calls it clean, and a
voting layer will happily rewrite one half into the other and record a
successful repair. It is detected structurally instead: find a cut point where
the two halves fit two different device profiles better than the whole frame
fits any one of them.

`header` is separated from ordinary scatter because the consequence differs in
kind. Every other error corrupts data; this one moves the frame boundary, so the
CRC is computed over the wrong bytes and nothing downstream is where it should
be. This is exactly the case firmware requirement **F2** exists for, and which
the firmware already implements.

---

### 4. Features

All 14 are computable from the received frame plus what the model has already
learned. None needs the original bytes — which by definition do not exist for a
real corrupt packet. That constraint is easy to violate by accident and is worth
stating.

The damage pattern is estimated by diffing against the best-matching device's
**modal frame**: the most common byte at each position, with varying positions
excluded. A counter that legitimately advanced is not damage, and counting it as
damage is precisely how a device with a sequence number gets classified as noise.

| # | Feature | What it separates |
|---|---|---|
| 1 | `damaged_fraction` | how much went wrong |
| 2 | `contiguity` = damaged / span | burst vs scatter |
| 3 | `run_density` | fragmentation of the damage |
| 4 | `longest_run` | burst length |
| 5 | `splice_gain` | collision vs damage |
| 6 | `splice_split_device` | the two halves want different senders |
| 7 | `tiling_residual` | AD chain does not tile its region |
| 8 | `length_mismatch` | header length field vs bytes received |
| 9 | `device_fit` | does *any* known device fit |
| 10 | `prior_fit` | does it look like advertising traffic at all |
| 11 | `stable_violation` | damage on positions that never vary |
| 12 | `bit_density` | bits changed per damaged byte |
| 13 | `damage_reaches_end` | splice tails run to the end of the frame |

Feature 12 does more work than the four above it, and §6 explains why it had to
be added.

---

### 5. Where the labels come from

A real corrupt packet has no ground truth. That is the entire problem, and it is
not solved by labelling: looking at a corrupt frame and declaring "this one is a
collision" is the very judgement the model is being asked to make.

So the damage is **manufactured**. Take a packet that arrived intact, apply one
of the five error kinds, and the label is known because we caused it. The
payloads are real traffic from the same environment, and the features are
computed by exactly the code path that will see real packets.

This is the same methodology §6 of the specification already requires for
evaluating recovery, used for training rather than only for measurement.

**The ceiling this imposes is real:** the classifier can only recognise error
modes that are modelled here. A failure mode nobody thought of will be sorted
into whichever of the five it resembles, confidently. That is a limitation of
the method, not a tuning problem, and §8 keeps it on the list.

#### 5.1 The rule about recovered packets

> Training data is packets that arrived intact. Nothing else. Ever.

Enforced in `PacketModel.observe`, which refuses anything with `crcOk = false`
**or** a non-null `recovery`. Either check alone would do today; both are there
because a future change that starts marking accepted repairs as CRC-OK would
silently defeat the first one, and there is a test for exactly that.

The reason is worth being precise about, because "don't train on your own
output" sounds like ordinary hygiene and this is worse than that. A repaired
packet is a hypothesis the pipeline emitted. Feed it back and the failure is not
noisy but **self-confirming**: a wrong repair teaches the device profile that
the wrong bytes are what that device sends, which makes the next identical wrong
repair score *better* than the one before it. The feedback loop has positive
gain. Measured false-recovery rates would improve while the true rate got worse,
and every instrument that could have detected it is downstream of the model.

---

### 6. Measured results

Corpus: 2,854 real PDUs from 18 advertisers, captured with the nRF52840 in this
environment — the same corpus [Deduplication and packet recovery](#deduplication-and-packet-recovery) §5 uses. Two thirds of each
advertiser's packets train; one third is held out and damaged. 958 damaged test
frames. Reproduce with:

```
./gradlew testDebugUnitTest --tests '*PacketModelTest*' -i
```

#### 6.1 Error classification

| Class | Test examples | Recall |
|---|---:|---:|
| `scattered` | 183 | 67.2% |
| `burst` | 175 | 90.9% |
| `splice` | 191 | 82.2% |
| `header` | 208 | 70.7% |
| `noise` | 201 | 77.6% |
| **overall** | **958** | **77.5%** |

Against a five-class uniform baseline of 20%.

`scattered` is the weakest and the confusion is intrinsic: a single bit flip and
the shortest modelled burst differ by one bit in one byte, and no feature
separates them because there is nothing there to separate. It also matters
least — both classes route to the same repair layers.

#### 6.2 Initial vs incremental training

The same data, fitted two ways: three shuffled passes (bootstrap), against one
pass, one packet at a time, never handed back.

| | Accuracy |
|---|---:|
| Bootstrapped, 3 passes | 71.5% |
| Incremental, single pass | 75.6% |

Incremental is not worse. This is the claim the requirement rests on and it is
the one that took the most work to make true — see §6.4.

#### 6.3 Device identification

The requirement: a packet that cannot be repaired should still name its sender.

| Condition | Attributed correctly |
|---|---:|
| Heavy burst damage | **90.8%** of 958 |
| Advertiser address bytes destroyed | **80.6%** of 958 |

The second row is the one that matters. The address lives at bytes 2–7 of the
PDU; corrupting it is what mints the phantom advertisers a noisy capture fills
up with — around 600 of them in a nine-minute session (`SnifferViewModel`
collapses them into one row for exactly this reason). Reading the address field
gives a confident wrong answer there. Identification never touches it: it works
from the vendor prefix, the service UUIDs and the layout, which is what survives.

Note what this does *not* claim. The posterior is over devices in this capture.
With an open world there is no calibrated "some device I have never seen" from
counting alone; read `DeviceMatch.score` for whether anything fits at all.

#### 6.4 Three defects measurement found

None was visible from the code or from passing tests.

- **Trimmed against untrimmed.** The splice feature compared a *trimmed*
  whole-frame score against *untrimmed* half-frame scores. Trimming discards the
  worst quarter of positions, so the whole-frame score was higher by a roughly
  constant margin, the "gain" was uniformly negative, and the feature carried no
  signal at all. Splice recall 57% → 82% once both sides were scored the same
  way. The feature was present, plausible, and inert.
- **Contiguity cannot see a single flip.** Burst recall sat at 46%. One flipped
  bit occupies one byte: count 1, span 1, contiguity 1.0 — identical to a
  perfectly contiguous burst. Adding bits-changed-per-damaged-byte (a burst
  rolls through the bits it covers and changes about half; scatter changes
  exactly one) took burst recall to 91%.
- **One-pass learning reached 55% where bootstrap reached 78%.** Two compounding
  causes. The first few hundred packets are scored against device profiles that
  barely exist, so their features describe a model state that will never recur;
  and a single pass is too few gradient steps for a five-way problem whatever
  the features say. Fixed by gating training until the device's own profile is
  trained, and replaying two earlier frames per packet from a 256-frame window —
  which also re-scores those frames against the profiles *as they are now*.

  A fourth cause turned out to be the measurement rather than the model: the
  corpus on disk is grouped by device, so feeding it in file order meant the
  model spent its first thousand packets in a world containing exactly one
  device, where a collision cannot even be constructed. A live capture
  interleaves advertisers. The test now does too, and says why.

---

### 7. What is not wired in

The model runs, trains and is measured. It is **not connected to the live
capture path**, and that is a deliberate stopping point rather than an oversight.

Wiring it costs, specifically:

1. **Schema.** `error_class`, `class_confidence`, `attributed_address` and
   `attribution_confidence` columns on `packets`, plus a migration. The store's
   `onUpgrade` already refuses to drop the table, so this is additive. Note that
   `has_crc` is *also* missing from the schema today, so a raw-mode capture
   loses that flag across a restart — worth fixing in the same migration.
2. **Pipeline order.** `RecoveryPipeline.process` would call `analyse` *before*
   attempting repair and use `worthRepairing` to skip the three-bit candidate
   enumeration on frames classified `splice` or `noise` — the saving that
   motivates the classifier in the first place. Throughput is currently 42,000
   packets/s ([Deduplication and packet recovery](#deduplication-and-packet-recovery) §8.6), so there is room, but that is measured
   without this.
3. **Attribution vs address.** A packet attributed to a device it does not parse
   as raises a question the UI has no answer for today: which address does the
   accumulation view group it under? Grouping by attribution would fold the
   phantom advertisers into their real senders, which is the *point*, and would
   also mean the device list is showing an inference. That is a product
   decision, not an implementation detail.
4. **Cost.** Splice detection is O(n²·devices) per corrupt packet, and is not
   included in the throughput figure above.

`PacketModel.snapshot`/`restore` carry the classifier across a restart. The
counted models are not serialised: they are exact functions of the traffic, and
the app already reloads the stored capture on startup, so replaying it rebuilds
them precisely. One format fewer to keep in step with the schema.

---

### 8. Known gaps

1. **No neural network, still.** The specified byte-level MLM would model
   longer-range structure than an order-1 prior. The interface is the same, so
   it can be dropped in — but it cannot be trained on the phone, which is what
   §1 concluded, so it would arrive with a corpus and the locality problem back
   in tow.
2. **The label set is the ceiling.** Only modelled error modes can be
   recognised. Anything else is sorted confidently into the nearest of five.
3. **Never seen a real collision** — but a close relative has now turned up.
   Every splice measured here was manufactured. The reference-beacon bench
   ([Deduplication and packet recovery](#deduplication-and-packet-recovery) §8.7) produced two frames that were byte-perfect for 9 and 15
   bytes and then diverged completely, with none of the remaining bytes matching
   by chance: the demodulator losing lock mid-packet. Structurally that is the
   `splice` class — a clean head followed by an unrelated tail — with noise in
   place of a second advertiser, and the *detector* built here should fire on it,
   since it looks for a cut point past which the frame stops fitting its sender.
   That has not been checked, and two frames would not settle it if it had. A
   genuine collision, with two radios and an AGC tracking one of them, remains
   unmeasured.
4. **Open-world identification is uncalibrated.** §6.3.
5. **`ByteTemplate` and `DeviceProfile` overlap.** The recovery pipeline still
   uses the older, cruder one. Merging them is straightforward and is held back
   only by not wanting to change the pipeline's measured behaviour in the same
   step as adding a model.


---

## Queueing-Theoretic Planning of BLE Advertising Transmission

*Channel scheduling, periodicity selection, and adaptive multi-channel listening*

---

### 1. Decomposition into three coupled queueing subsystems

| Subsystem | Queueing analogue | Output metric |
|---|---|---|
| Air interface on channel *k* | M/G/1/1 **loss** system (unslotted ALOHA) | collision prob. `P_c,k` |
| Advertiser radio | **cyclic polling server**, 3 stations, switchover δ | access delay, jitter |
| Scanner radio (one radio, 3 channels) | M/G/1 with **multiple vacations**, but *lossy* (arrivals during vacation vanish) | detection prob., estimator variance |

The essential asymmetry: on the transmit side the medium is a *delay* system; on the receive side it is a *loss* system. Anything not heard while dwelling elsewhere is gone forever, so the scanner's optimisation is a **sampling-allocation** problem, not a waiting-time problem.

---

### 2. Air-time and load

1M PHY, legacy PDU:

```
T_pkt(L) = 8·(10 + L) µs        (1 B preamble + 4 AA + 2 hdr + L + 3 CRC)
```

→ 128 µs (L = 6) … 376 µs (L = 37).

Advertising event:

```
T_ev = 3·T_pkt + 2·δ,           δ ≈ 150 µs switch time
```

Event period: `T_a + D`, with `advInterval = T_a ∈ [20 ms, 10.24 s]` in 0.625 ms steps and `D ~ U[0, 10 ms]`.

Per channel, from the sensing phase you estimate arrival rate `λ_k` and mean air-time `E[S_k]`:

```
ρ_k = λ_k · E[S_k]              (offered load, Erlang)
G_k = λ_k · T_pkt               (normalised traffic)
```

---

### 3. Collision model (the "server" is single, blocking)

Busy fraction from the M/G/1/1 loss system (Erlang-B, m = 1):

```
B_k = ρ_k / (1 + ρ_k)
```

For a packet launched at a uniformly random instant, the classical unslotted-ALOHA vulnerability window is `T_own + E[S_k]`, ahead and behind:

```
P_succ,k = exp( −λ_k · (T_pkt^own + E[S_k]) )   ≈ e^(−2G_k)
```

If the number of contenders `N` is small and known, replace the Poisson form with the Engset/binomial form:

```
P_succ = (1 − (T_pkt + E[S]) / T̄_j)^(N−1)
```

Throughput of the channel `S_k = G_k·e^(−2G_k)` peaks at `G_k = 0.5`; for a reliability-driven design target `G_k ≤ 0.1` (≥ 82 % success).

---

### 4. Advertiser as a polling system

When the device has a backlog of payloads to disseminate (not a single static beacon), the radio is a cyclic server visiting 3 stations with switchover `δ` and cycle `C = T_a + D`.

**Exhaustive service:**

```
W_k = Σ_j λ_j E[S_j²] / (2(1−ρ))  +  σ_Δ²/(2Δ)  +  Δ(1−ρ_k)/(2(1−ρ))
```

with `Δ = Σ δ_j`, `ρ = Σ ρ_j`. Gated service replaces `(1−ρ_k)` with `(1+ρ_k)`.

BLE's one-PDU-per-visit rule is **1-limited** polling; use the symmetric approximation

```
W ≈ [ λE[S²] + Δ(1 + …) ] / ( 2(1 − ρ − λΔ) )
```

and note the stability condition `ρ + λΔ < 1` — this is what caps how fast `T_a` can be pushed.

---

### 5. Scanner side: detection and estimation

Let `f_k` = long-run fraction of time dwelling on channel *k*, `Σ f_k = 1 − o` (o = switching overhead), dwell length `d_k`.

Probability that a given advertising event is captured on channel *k*:

```
p_k = f_k · (1 − T_pkt/d_k) · P_succ,k
```

The `(1 − T_pkt/d_k)` term is the edge loss for packets straddling a dwell boundary — it is why very short dwells are wasteful.

Discovery latency (geometric number of events):

```
E[T_disc,k] = (T_a + 5 ms) / p_k
```

> **Key structural result:** a 3-channel advertiser is hit with probability `Σ_k f_k·P_succ,k ≈ 1 − P̄_c` *regardless* of how you split `f_k`. The allocation therefore barely affects discovery of well-behaved advertisers — it matters for (a) channel-selective advertisers, (b) traffic-estimation quality, (c) collision-map accuracy. Optimise for those.

#### Estimator variance

Counting on channel *k* for `f_k·T`:

```
Var(λ̂_k) = λ_k / (f_k T),      relative variance = 1 / (λ_k f_k T)
```

Minimising `Σ_k 1/(λ_k f_k T)` under `Σ f_k = F` gives a Neyman-type allocation:

```
f_k = F · λ_k^(−1/2) / Σ_j λ_j^(−1/2)
```

Minimising the *worst* relative error instead gives `f_k ∝ 1/λ_k`. Maximising raw captured volume gives all time to the busiest channel. In practice use a blended objective with a floor:

```
f_k = max( f_min , normalise( a·λ_k^(−1/2) + b·λ_k ) )
```

`f_min` enforces a staleness bound: no channel unobserved longer than the traffic coherence time.

---

### 6. Choosing the periodicity `T_a`

Cost function combining latency, medium impact and energy:

```
J(T_a) = c₁·(T_a + 5 ms)/p̄  +  c₂·Σ_k G_k^own(T_a)  +  c₃·E_ev/T_a
```

with `G_k^own = T_pkt / T_a`. Constraints:

```
T_a ≥ 3·T_pkt / (G* − G_k^foreign)     for every k,   G* ≈ 0.1
ρ + λΔ < 1                              (polling stability)
```

If `G_k^foreign ≥ G*` on some channel, do not lower `T_a` — instead drop that channel from `advChannelMap`.

**Anti-lock-out constraint.** Periodic interferers cause persistent collisions when `T_a` and a foreign period `T_j` are near-commensurate (`T_a/T_j ≈ m/n` with small *n*). Since all values are multiples of 0.625 ms, choose `T_a = q·0.625 ms` with *q* **prime** or coprime to the detected `q_j` of dominant sources — this maximises `lcm(T_a, T_j)` and makes the relative phase drift through the whole cycle.

---

### 7. Choosing the start phase φ_k

From the sensing phase build a circular occupancy histogram `u_k(τ)`, `τ ∈ [0, P)`, where *P* is the detected dominant period (autocorrelation / FFT peak of the arrival train). Then:

```
φ_k* = argmin_φ  ∫_φ^(φ+T_pkt) ũ_k(τ) dτ
```

where `ũ_k` is `u_k` **convolved with the advDelay kernel** `U[0, 10 ms]`:

```
ũ_k(φ) = (1/Δ)·∫_0^Δ u_k(φ + x) dx,      Δ = 10 ms
```

This convolution is the practical punchline: 10 ms of mandatory jitter smears any structure finer than ~10 ms, so **sub-millisecond phase optimisation is meaningless for legacy advertising**. Only coarse duty structure (e.g. a periodic gateway that hogs 40 ms out of every 200 ms) is exploitable.

If genuine fine phase control is required, move to Extended Advertising: `ADV_EXT_IND` + deterministic `AuxPtr` offset to `AUX_ADV_IND`, where you place the actual payload at a phase you choose.

---

### 8. Alternation schedule

Realise `f_k` with **Deficit Round Robin plus low-discrepancy jitter**:

1. Quantum `Q_k ∝ f_k`; deficit counter `DC_k += Q_k` each round, dwell while `DC_k > 0`.
2. Offset each dwell by a Weyl sequence: `θ_n = frac(n·0.6180339887)`, dwell start `= t_n + θ_n·P̂`. An irrational rotation samples the phase of *any* periodic interferer with discrepancy `O(log n / n)`, eliminating the aliasing blind spots that fixed-period scanning creates when `d + gap ≈ (m/n)·T_j`.
3. Override with an index rule when a channel goes stale or volatile:

   ```
   I_k = w_k · (t − t_k^last) · σ̂_k²
   ```

   serve `argmax I_k`; this is a Whittle-style relaxation of the restless-bandit formulation of "which channel to look at next."
4. Dwell length floor `d_k ≥ 10·T_pkt` to keep edge loss `T_pkt/d_k < 10 %`; ceiling `d_k ≤ P̂/2` so no single channel monopolises a coherence window.

---

### 9. Closed loop

```
sense (equal dwell, ≥ 3 dominant periods)
 → estimate λ_k, u_k(τ), P̂, N_k
 → compute ρ_k, P_succ,k; choose T_a (prime multiple), φ_k*, channel map
 → transmit; in inter-event gaps run DRR + Weyl listening
 → EWMA update  λ̂_k ← α·λ_k^obs/f_k + (1−α)·λ̂_k     (inverse-probability weighted!)
 → re-plan when |Δλ̂_k|/λ̂_k > threshold or CUSUM alarm
```

Note the inverse-probability weighting: raw counts on channel *k* must be divided by `f_k` before comparison, otherwise the allocation biases its own input and the loop self-reinforces.

**Validation metrics:** empirical `P_succ` per channel, discovery-latency CDF (not just the mean — it is heavy-tailed), estimator relative error vs. a reference full-time sniffer, and energy per successful reception.
