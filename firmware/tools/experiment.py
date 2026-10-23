"""The air-time adaptation experiment: run it, score it, plot it.

Six conditions, at two scales, ten repetitions each. The conditions vary three
things independently:

    receiver          the phone's own controller, or the nRF52840 sniffer
    error correction  whether the receiver's repair pipeline is allowed to count
    transmitter       plain firmware, or the air-time option of
                      `ble-advertising-queueing-theory.md`

                                receiver   correction  adaptive
    2.1 / 3.1  builtin          phone      no          no
    2.2 / 3.2  nrf              nRF52840   no          no
    2.3 / 3.3  nrf-fec          nRF52840   yes         no
    2.4 / 3.4  builtin-adapt    phone      no          yes
    2.5 / 3.5  nrf-adapt        nRF52840   no          yes
    2.6 / 3.6  nrf-fec-adapt    nRF52840   yes         yes

Scale `single` sends 10 000 advertising events on channel 37, so 10 000 packets.
Scale `triple` sends 10 000 events on all three channels, so 30 000 packets.

Four physical runs, six scored conditions
-----------------------------------------

The app's recovery pipeline has no off switch, and it does not need one: a
repair is recorded *alongside* the bytes that actually arrived and never over
them (`RECOVERY.md` §2). So "with correction" and "without correction" are two
scorings of one capture, not two captures - which is the stronger design
anyway, because the pair is measured on the same packets through the same air
rather than on two runs the channel was free to differ between. Conditions 2 and
3 come from one run, and so do 5 and 6.

That leaves four runs per scale: {phone, nRF52840} x {plain, adaptive}.

Why the plain arm transmits at 20 ms
------------------------------------

The adaptive planner will not go below the specification's 20 ms floor, so
running the plain arm at the 10 ms the old bench used would hand the comparison
a rate difference to explain the results with. Both arms are spec-legal and
start from the same floor; what the adaptive arm then does with T_a, the channel
map and the start phase is the thing being measured. Every run records the T_a
it actually used, so this is checkable rather than assumed.

Usage
-----

    # One short pass through all four cells, to prove the harness end to end.
    python firmware/tools/experiment.py run --scale single --reps 1 --count 200

    # The real thing.
    python firmware/tools/experiment.py run --scale single --reps 10
    python firmware/tools/experiment.py run --scale triple --reps 10

    python firmware/tools/experiment.py analyse
    python firmware/tools/experiment.py plot
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import beacon as beacon_tool  # noqa: E402  - same directory, shared serial helpers


def _run(*args, **kwargs):
    """subprocess.run, but never inheriting this process's stdin.

    adb shell forwards stdin to the device and drains it. Inherited, that eats
    the operator's answer to ask()/confirm() before input() is ever reached, so
    a redirected or piped stdin dies as "no console available" - the harness
    only ever worked with a human at a tty. The prompts still have to be
    answered; they just are not swallowed by a subprocess any more.
    """
    kwargs.setdefault("stdin", subprocess.DEVNULL)
    return subprocess.run(*args, **kwargs)


ROOT = Path(__file__).resolve().parents[2]
FIRMWARE = ROOT / "firmware"
OUT = ROOT / "bench" / "experiment"

# The beacon's identity, fixed so the analysis can find it in ambient traffic.
ADDRESS = "C0:FF:EE:BE:AC:01"
DATA = "0201060B09524546434845434B"
LENGTH = 31
POWER = 0

# See the module docstring: the specification's floor, for both arms.
BASELINE_INTERVAL_MS = 20

# Advertising events per run. `triple` sends one packet per channel per event,
# so the same number of events is three times the packets.
DEFAULT_COUNT = 10000

SCALES = {
    "single": {"channel": "37", "rx_channel": "37", "packets_per_event": 1},
    "triple": {"channel": "all", "rx_channel": "all", "packets_per_event": 3},
}

# The four physical cells. Ordered so the firmware alternates rather than
# running every plain cell first: a session is hours long and the room does not
# hold still for it, so an ordering that puts all of one arm early would let
# drift in the ambient traffic masquerade as an effect of adaptation.
CELLS = [
    {"name": "builtin",       "source": "phone", "adaptive": False},
    {"name": "builtin-adapt", "source": "phone", "adaptive": True},
    {"name": "nrf",           "source": "usb",   "adaptive": False},
    {"name": "nrf-adapt",     "source": "usb",   "adaptive": True},
]

# How a physical cell is scored into the numbered conditions. `corrected` says
# whether the repair columns are allowed to count.
CONDITIONS = [
    {"id": "2.1", "cell": "builtin",       "corrected": False, "label": "phone, no FEC, plain"},
    {"id": "2.2", "cell": "nrf",           "corrected": False, "label": "nRF52840, no FEC, plain"},
    {"id": "2.3", "cell": "nrf",           "corrected": True,  "label": "nRF52840, FEC, plain"},
    {"id": "2.4", "cell": "builtin-adapt", "corrected": False, "label": "phone, no FEC, adaptive"},
    {"id": "2.5", "cell": "nrf-adapt",     "corrected": False, "label": "nRF52840, no FEC, adaptive"},
    {"id": "2.6", "cell": "nrf-adapt",     "corrected": True,  "label": "nRF52840, FEC, adaptive"},
]

METHODS = ["crc_syndrome", "cross_source", "cross_time", "template", "ml_prior"]

# Advertising channel centre frequencies, for pointing a generator at one.
CHANNEL_MHZ = {"37": 2402, "38": 2426, "39": 2480}

ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
DEVICE = os.environ.get("BLESNIFF_DEVICE", "192.168.178.172:5555")


# --- host side plumbing -------------------------------------------------------


def adb(*args: str) -> subprocess.CompletedProcess:
    return _run(
        [ADB, "-s", DEVICE, *args], capture_output=True, encoding="utf-8", errors="replace"
    )


def attached() -> list[str]:
    result = _run([ADB, "devices"], capture_output=True,
                            encoding="utf-8", errors="replace")
    return [line.split("\t")[0] for line in (result.stdout or "").splitlines()[1:]
            if line.strip().endswith("device")]


def resolve_device() -> str:
    """Finds the phone, however it happens to be attached today.

    adb over Wi-Fi does not survive a reboot, so the address in these tools has
    already gone stale once. USB is tried, then the remembered address, then any
    single device that answers - and if none of that works the message says what
    to do rather than leaving a hundred-run session to fail on the first tap.
    """
    global DEVICE

    devices = attached()
    if DEVICE in devices:
        return DEVICE
    if len(devices) == 1:
        DEVICE = devices[0]
        os.environ["BLESNIFF_DEVICE"] = DEVICE
        print(f"using device {DEVICE}")
        return DEVICE

    _run([ADB, "connect", DEVICE], capture_output=True)
    devices = attached()
    if DEVICE in devices:
        return DEVICE
    if len(devices) == 1:
        DEVICE = devices[0]
        os.environ["BLESNIFF_DEVICE"] = DEVICE
        print(f"using device {DEVICE}")
        return DEVICE

    raise SystemExit(
        "no phone on adb.\n"
        f"  tried: {DEVICE}, and any device 'adb devices' reports\n"
        "  found: " + (", ".join(devices) if devices else "nothing") + "\n\n"
        "  Wireless debugging is dropped when the phone reboots. Either plug it\n"
        "  in over USB, or re-enable it and reconnect:\n"
        "      adb connect <phone-ip>:5555\n"
        "      BLESNIFF_DEVICE=<phone-ip>:5555 python firmware/tools/experiment.py ..."
    )


def ask(prompt: str) -> None:
    """Blocks until the operator confirms. Part of this bench is a human."""
    print()
    print("=" * 72)
    print(prompt)
    print("=" * 72)
    try:
        input("  press Enter when ready (Ctrl-C to abandon the run) ... ")
    except EOFError:
        raise SystemExit("no console available: this scenario needs an operator")


def confirm(prompt: str) -> None:
    """Demands a positive answer, because silence here corrupts the session.

    A generator that was never switched on produces a session that looks exactly
    like a clean one, and would be written up as evidence that interference does
    not matter. It has to be confirmed, not assumed.
    """
    print()
    print("=" * 72)
    print(prompt)
    print("=" * 72)
    try:
        answer = input("  type y to confirm, anything else to abort: ").strip().lower()
    except EOFError:
        raise SystemExit("no console available: this scenario needs an operator")
    if not answer.startswith("y"):
        raise SystemExit("operator did not confirm; nothing recorded")


def appctl(command: str) -> None:
    """Runs one app action and refuses to continue if it did not take.

    Every one of these actions is a silent failure if it is only attempted: a
    source that did not change records the wrong receiver, a capture that did not
    clear records the previous cell's packets, and both look like data.
    """
    result = _run(
        [sys.executable, str(Path(__file__).parent / "appctl.py"), command],
        capture_output=True, encoding="utf-8", errors="replace",
    )
    print(f"    app {command}: {(result.stdout or '').strip()}")
    if result.returncode != 0:
        raise SystemExit(f"app control failed: {command}")


def set_rx_channel(channel: str) -> None:
    result = _run(
        [sys.executable, str(Path(__file__).parent / "rxchan.py"), channel],
        capture_output=True, encoding="utf-8", errors="replace",
    )
    print(f"    app channel: {(result.stdout or '').strip()}")
    if result.returncode != 0:
        raise SystemExit(f"could not set the receive channel to {channel}")


def flash(environment: str) -> None:
    """Flashes one of the two beacon firmwares onto the transmitter board.

    The board is named explicitly. With two boards on the bench the 1200 bps
    touch resets whichever port it is pointed at, and pointing it at the sniffer
    would reflash the receiver in the middle of an experiment about the
    transmitter.
    """
    port = beacon_tool.find_port()
    if not port:
        raise SystemExit("no nRF52840 beacon found on USB")
    print(f"    flashing {environment} to {port} ...")
    result = _run(
        [str(Path.home() / ".platformio" / "penv" / "Scripts" / "pio.exe"),
         "run", "-e", environment, "-t", "upload", "--upload-port", port],
        cwd=str(FIRMWARE), capture_output=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        print(result.stdout[-2000:] if result.stdout else "")
        raise SystemExit(f"flashing {environment} failed")
    time.sleep(4.0)  # the board re-enumerates after the bootloader hands over


def open_beacon(want_adaptive: bool):
    """Opens the transmitter and checks it is running the firmware we asked for.

    The banner carries the capability field, so which arm is running is read off
    the board rather than inferred from which flash command was issued last.
    Getting this wrong silently swaps two conditions.
    """
    port = beacon_tool.find_port()
    if not port:
        raise SystemExit("no nRF52840 beacon found on USB")
    ser = beacon_tool.open_port(port)

    identity = beacon_tool.command(ser, "ver", wait=1.0)
    banner = next((l for l in identity if l.startswith("$I,BLEBEACON")), None)
    if banner is None:
        ser.close()
        raise SystemExit(f"{port} is not running the beacon firmware")
    is_adaptive = banner.strip().endswith(",adaptive")
    if is_adaptive != want_adaptive:
        ser.close()
        raise SystemExit(
            f"the board reports {'adaptive' if is_adaptive else 'plain'} firmware "
            f"but this cell needs {'adaptive' if want_adaptive else 'plain'}"
        )

    beacon_tool.command(ser, f"addr {ADDRESS}")
    beacon_tool.command(ser, f"data {DATA}")
    beacon_tool.command(ser, f"len {LENGTH}")
    beacon_tool.command(ser, f"power {POWER}")

    pdu = next((l for l in beacon_tool.command(ser, "pdu") if l.startswith("$U,")), None)
    if pdu is None:
        ser.close()
        raise SystemExit("the beacon did not report its PDU")
    return ser, pdu[3:], banner


# --- one run ------------------------------------------------------------------


def transmit(ser, count: int, adaptive: bool, timeout_s: float) -> dict:
    """One run, and everything the transmitter said while it happened.

    The sensing and planning lines are kept, not just counted. Whether adaptation
    changed anything is a question about what it measured and what it decided,
    and a run that only records how many packets went out cannot answer it.
    """
    started = time.time()
    ser.reset_input_buffer()
    interval = 0 if adaptive else BASELINE_INTERVAL_MS
    ser.write(f"send {count} {interval}\n".encode("ascii"))
    ser.flush()

    sent = 0
    measurements: list[str] = []
    plans: list[str] = []
    while True:
        raw = ser.readline()
        if not raw:
            if time.time() - started > timeout_s:
                print(f"    TIMED OUT after {sent} packets")
                break
            continue
        line = raw.decode("ascii", "replace").strip()
        if line.startswith("$B,"):
            sent += 1
            if sent % 2000 == 0:
                print(f"      {sent}/{count}")
        elif line.startswith("$K,"):
            measurements.append(line)
        elif line.startswith("$N,"):
            plans.append(line)
        elif line.startswith("$E,"):
            sent = int(line[3:])
            break
    ended = time.time()

    return {
        "start_ms": int(started * 1000),
        "end_ms": int(ended * 1000),
        "sent_events": sent,
        "interval_ms": interval,
        "measurements": measurements,
        "plans": plans,
    }


def pull_capture(path: Path) -> None:
    """Copies the live capture off the phone, write-ahead log included.

    The app runs SQLite in WAL mode and is still writing, so the main database
    on its own is a torn snapshot and opening it alone reports "database disk
    image is malformed". All three files let SQLite replay the log.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    for suffix in ("", "-wal", "-shm"):
        target = path.with_name(path.name + suffix)
        with open(target, "wb") as handle:
            result = _run(
                [ADB, "-s", DEVICE, "exec-out", "run-as", "com.blesniffer",
                 "cat", f"databases/capture.db{suffix}"],
                stdout=handle,
            )
        if result.returncode != 0 or target.stat().st_size == 0:
            target.unlink(missing_ok=True)  # -wal and -shm vanish after a checkpoint
            if suffix == "":
                raise SystemExit("could not read the capture database")
    print(f"    pulled {path.stat().st_size / 1e6:.1f} MB to {path.name}")


def run_cell(cell: dict, scale: str, reps: int, count: int, current_fw: str | None,
             args_generator: str = "", args_mhz: int = 0) -> str:
    spec = SCALES[scale]
    environment = "beacon_adaptive" if cell["adaptive"] else "beacon"

    print(f"\n{'=' * 72}")
    print(f"  cell {scale}/{cell['name']}  -  {reps} x {count} events on ch {spec['channel']}")
    print(f"{'=' * 72}")

    if current_fw != environment:
        flash(environment)

    ser, pdu, banner = open_beacon(cell["adaptive"])
    runs = []
    try:
        with ser:
            print(f"    transmitting: {pdu}")
            print(f"    firmware:     {banner}")
            beacon_tool.command(ser, f"ch {spec['channel']}")
            if cell["adaptive"]:
                beacon_tool.command(ser, "adaptive on")
                if args_generator:
                    # A carrier occupies the channel and never becomes a packet,
                    # so lambda_k cannot see it. Without this the planner
                    # measures a clear channel, correctly by its own model, and
                    # correctly decides to do nothing about a channel that is in
                    # fact unusable.
                    beacon_tool.command(ser, "cfg usebusy 1")
                # A dwell long enough that lambda_k is an estimate rather than a
                # coin toss: at the ambient rate this room runs, 200 ms sees on
                # the order of ten packets and 1 s sees seventy.
                beacon_tool.command(ser, "cfg dwell 1000")
                config = beacon_tool.command(ser, "cfg", wait=1.5)
            else:
                config = []

            # Receiver first, then clear, then start: clearing before the source
            # is settled throws away packets from whichever source was running.
            appctl("usb" if cell["source"] == "usb" else "phone")
            if cell["source"] == "usb":
                appctl("raw")
                set_rx_channel(spec["rx_channel"])
            appctl("clear")
            appctl("start")
            time.sleep(2.0)

            # Generous: an adaptive run at the 100 ms ceiling would take
            # count x 0.1 s, and a run killed by its own timeout is a lost hour.
            timeout_s = max(300.0, count * 0.30)

            for rep in range(1, reps + 1):
                print(f"    rep {rep}/{reps}")
                run = transmit(ser, count, cell["adaptive"], timeout_s)
                run.update(
                    scale=scale, cell=cell["name"], rep=rep, source=cell["source"],
                    adaptive=cell["adaptive"], channel=spec["channel"],
                    packets_per_event=spec["packets_per_event"],
                    sent_packets=run["sent_events"] * spec["packets_per_event"],
                )
                print(f"      sent {run['sent_events']} events "
                      f"({run['sent_packets']} packets) in "
                      f"{(run['end_ms'] - run['start_ms']) / 1000:.0f} s")
                runs.append(run)
                time.sleep(1.0)

            appctl("stop")
    finally:
        if runs:
            OUT.mkdir(parents=True, exist_ok=True)
            pull_capture(OUT / f"{scale}-{cell['name']}.db")
            (OUT / f"{scale}-{cell['name']}.json").write_text(json.dumps({
                "scale": scale, "cell": cell["name"], "pdu": pdu,
                "banner": banner, "firmware": environment,
                "config": config, "runs": runs,
                "generator": args_generator, "generator_mhz": args_mhz,
            }, indent=2))
            print(f"    wrote {scale}-{cell['name']}.json")

    return environment


def preflight(scale: str) -> None:
    """Checks the whole chain before anything is flashed or transmitted.

    Every one of these was worth a separate check: the run that failed first
    time round had already reflashed the transmitter and opened a serial port
    before finding out there was no phone to receive anything. The order is
    cheapest and most likely to be wrong, first.
    """
    resolve_device()

    if not _run([ADB, "-s", DEVICE, "shell", "pm", "list", "packages"],
                          capture_output=True, encoding="utf-8", errors="replace"
                          ).stdout.count("com.blesniffer"):
        raise SystemExit(f"the sniffer app is not installed on {DEVICE}")

    port = beacon_tool.find_port()
    if not port:
        raise SystemExit("no nRF52840 beacon on this PC's USB")

    # The sniffer board hangs off the *phone*, so it cannot be checked from
    # here directly; selecting it as the source is what proves it is there, and
    # that happens per cell. Say so rather than implying it was verified.
    print(f"preflight: phone {DEVICE}, transmitter {port}, scale {scale}")
    print("           (the sniffer board is checked when its cell selects it)")


def command_run(args) -> None:
    preflight(args.scale)

    if args.generator:
        mhz = args.mhz or CHANNEL_MHZ.get(args.interferer_channel, 2402)
        ask(f"{args.generator}: set the interference frequency to {mhz} MHz "
            f"(BLE advertising channel {args.interferer_channel})")
        confirm(f"{args.generator}: is it powered up and radiating at {mhz} MHz?\n"
                "  It must stay on and unchanged for every cell of this scale -\n"
                "  an interferer present for only one arm is not a control, it is\n"
                "  the difference being measured.")
        args.mhz = mhz

    current_fw = None
    for cell in CELLS:
        if args.cell and cell["name"] != args.cell:
            continue
        current_fw = run_cell(cell, args.scale, args.reps, args.count, current_fw,
                              args.generator, args.mhz)
    print("\nall cells done. next: python firmware/tools/experiment.py analyse")


# --- scoring ------------------------------------------------------------------


def hamming(a: bytes, b: bytes) -> int:
    return sum(1 for x, y in zip(a, b) if x != y) + abs(len(a) - len(b))


def strip_pad(data: bytes) -> bytes:
    return data.rstrip(b"\x00")


def expected_for(source: str, sent: bytes) -> bytes:
    """What a receiver of this kind should hand up for our transmitted PDU.

    The two receivers are not looking at the same object and the comparison has
    to say so. The board reports the link layer PDU; the phone's controller
    reports only the advertising data, because the stack has already consumed
    the header and the address by the time the app is told anything.
    """
    return sent if source == "usb" else sent[2 + 6:]


def score_run(rows, run, sent: bytes, corrected: bool) -> dict:
    """Assigns one run's window to the categories §4 of the task asks for."""
    lo, hi = run["start_ms"], run["end_ms"]
    window = [r for r in rows if lo <= r[0] <= hi]
    expected = expected_for(run["source"], sent)

    mine = [r for r in window if r[4] == ADDRESS]
    others = [r for r in window if r[4] != ADDRESS]

    intact = damaged = repaired_ok = repaired_bad = refused = 0
    by_method = {m: 0 for m in METHODS}
    by_method_bad = {m: 0 for m in METHODS}
    rssi_values = []

    for _, _ch, rssi, _ok, _addr, hexs, method, rec in mine:
        got = bytes.fromhex(hexs)
        # RAW mode appends the received CRC; STD and the phone do not.
        body = got[: len(expected)] if len(got) >= len(expected) else got
        if rssi is not None:
            rssi_values.append(rssi)

        if strip_pad(body) == strip_pad(expected):
            intact += 1
            continue

        damaged += 1
        if not corrected:
            # Not "no repair happened" but "no repair is allowed to count", so
            # the packet stays in the corrupt-with-address-intact category. Left
            # unincremented, this condition's share of the transmitted packets
            # silently failed to add up to one.
            refused += 1
            continue
        if method and method != "unrecoverable" and rec:
            fixed = bytes.fromhex(rec)
            fixed_body = fixed[: len(expected)] if len(fixed) >= len(expected) else fixed
            if strip_pad(fixed_body) == strip_pad(expected):
                repaired_ok += 1
                if method in by_method:
                    by_method[method] += 1
            else:
                repaired_bad += 1
                if method in by_method_bad:
                    by_method_bad[method] += 1
        else:
            refused += 1

    # §4 asks specifically about corrupt packets whose *address* survived, which
    # is a category that only means something against the ones whose address did
    # not. A frame whose address bytes were hit is unattributable by address, so
    # it is attributed by content instead: same length, and close enough to the
    # transmitted bytes that no other advertiser in the room would be.
    address_damaged = 0
    for _, _ch, _rssi, ok, _addr, hexs, _m, _r in others:
        if ok:
            continue
        got = bytes.fromhex(hexs)
        body = got[: len(expected)] if len(got) >= len(expected) else got
        if len(body) == len(expected) and hamming(body, expected) <= 6:
            address_damaged += 1

    received = len(mine)
    sent_packets = run["sent_packets"]

    # These six categories are meant to partition what was transmitted, and the
    # arithmetic has to be written so that they do. A packet whose address was
    # damaged did arrive - it simply could not be attributed by address - so it
    # is not also missing, and counting it in both puts the total over 100%.
    #
    # `never_received` can only go negative if the content-based attribution
    # above claimed more frames than are unaccounted for, which would mean the
    # Hamming threshold is picking up somebody else's traffic. It is clamped so
    # the figure stays readable, and `attribution_excess` records that it
    # happened rather than letting the clamp hide it.
    never_received = sent_packets - received - address_damaged
    return {
        "sent": sent_packets,
        "received": received,
        "lost": max(0, never_received),
        "attribution_excess": max(0, -never_received),
        "intact": intact,
        "damaged": damaged,
        "repaired_ok": repaired_ok,
        "repaired_bad": repaired_bad,
        "refused": refused,
        "address_damaged": address_damaged,
        "ambient": len(others),
        "delivered": intact + repaired_ok,
        "mean_rssi": round(sum(rssi_values) / len(rssi_values), 1) if rssi_values else "",
        **{f"m_{m}": by_method[m] for m in METHODS},
        **{f"mbad_{m}": by_method_bad[m] for m in METHODS},
    }


def plan_summary(run: dict) -> dict:
    """The transmitter's own view of the medium, folded into one row.

    Without these columns a null result is unreadable: "adaptation made no
    difference" and "adaptation correctly decided there was nothing to adapt to"
    are the same numbers on the receiving end and completely different findings.
    """
    lambdas, rhos, loads, psuccs, busy, effective = [], [], [], [], [], []
    for line in run.get("measurements", []):
        f = line[3:].split(",")
        if len(f) < 12:
            continue
        lambdas.append(int(f[5]) / 1e3)
        rhos.append(int(f[7]) / 1e6)
        loads.append(int(f[8]) / 1e6)
        psuccs.append(int(f[9]) / 1e6)
        busy.append(int(f[10]) / 1e6)
        # Appended after peak RSSI, so a capture from the firmware before the
        # energy detector could drive the plan simply does not carry it.
        effective.append(int(f[12]) / 1e6 if len(f) > 12 else int(f[8]) / 1e6)

    t_a_ms, active, dropped, forced = "", "", "", ""
    if run.get("plans"):
        f = run["plans"][0][3:].split(",")
        if len(f) >= 10:
            t_a_ms = round(int(f[1]) / 1000, 3)
            active = bin(int(f[2])).count("1")
            dropped = bin(int(f[3])).count("1")
            forced = int(f[9])

    mean = lambda xs: round(sum(xs) / len(xs), 4) if xs else ""  # noqa: E731
    return {
        "t_a_ms": t_a_ms if t_a_ms != "" else run["interval_ms"],
        "replans": max(0, len(run.get("plans", [])) - 1),
        "channels_active": active,
        "channels_dropped": dropped,
        "map_forced": forced,
        "lambda_hz": mean(lambdas),
        "rho": mean(rhos),
        "g_foreign": mean(loads),
        "g_effective": mean(effective),
        "p_succ_model": mean(psuccs),
        "busy_fraction": mean(busy),
    }


def command_analyse(args) -> None:
    import csv
    import sqlite3

    if not OUT.exists():
        raise SystemExit(f"no results in {OUT}; run the experiment first")

    caches: dict[str, list] = {}

    def rows_for(scale: str, cell: str) -> list:
        key = f"{scale}-{cell}"
        if key not in caches:
            db_path = OUT / f"{key}.db"
            if not db_path.exists():
                caches[key] = []
            else:
                db = sqlite3.connect(db_path)
                caches[key] = db.execute(
                    "select ts_ms, channel, rssi, crc_ok, address, raw_hex, "
                    "rec_method, rec_hex from packets order by ts_ms"
                ).fetchall()
                db.close()
                print(f"{key}: {len(caches[key])} packets in the capture")
        return caches[key]

    results = []
    for scale in SCALES:
        for condition in CONDITIONS:
            manifest = OUT / f"{scale}-{condition['cell']}.json"
            if not manifest.exists():
                continue
            blob = json.loads(manifest.read_text())
            sent = bytes.fromhex(blob["pdu"])
            rows = rows_for(scale, condition["cell"])

            for run in blob["runs"]:
                scored = score_run(rows, run, sent, condition["corrected"])
                results.append({
                    "scale": scale,
                    "condition": f"{'2' if scale == 'single' else '3'}.{condition['id'][2]}",
                    "label": condition["label"],
                    "cell": condition["cell"],
                    "receiver": "phone" if run["source"] == "phone" else "nrf52840",
                    "correction": int(condition["corrected"]),
                    "adaptive": int(run["adaptive"]),
                    "channel": run["channel"],
                    "rep": run["rep"],
                    "generator": blob.get("generator", ""),
                    "generator_mhz": blob.get("generator_mhz", "") or "",
                    "duration_s": round((run["end_ms"] - run["start_ms"]) / 1000, 1),
                    **plan_summary(run),
                    **scored,
                })

    if not results:
        raise SystemExit("nothing to score: no manifests found")

    OUT.parent.mkdir(exist_ok=True)
    out = ROOT / "bench" / "experiment-results.csv"
    with open(out, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(results[0].keys()))
        writer.writeheader()
        writer.writerows(results)

    header = (f"{'cond':<5} {'label':<28} {'sent':>7} {'rx':>7} {'ok':>7} "
              f"{'dmg':>6} {'fix':>5} {'bad':>4} {'addr!':>5} {'T_a':>7} {'del%':>6}")
    print()
    print(header)
    print("-" * len(header))
    seen = {}
    for r in results:
        key = (r["scale"], r["condition"])
        seen.setdefault(key, []).append(r)
    for key, group in seen.items():
        total = lambda f: sum(g[f] for g in group)  # noqa: E731
        pct = 100.0 * total("delivered") / total("sent") if total("sent") else 0.0
        t_a = group[0]["t_a_ms"]
        print(f"{group[0]['condition']:<5} {group[0]['label']:<28} "
              f"{total('sent'):>7} {total('received'):>7} {total('intact'):>7} "
              f"{total('damaged'):>6} {total('repaired_ok'):>5} "
              f"{total('repaired_bad'):>4} {total('address_damaged'):>5} "
              f"{str(t_a):>7} {pct:>5.2f}%")
    print(f"\nwrote {out}  ({len(results)} rows)")


# --- plots --------------------------------------------------------------------


def command_plot(args) -> None:
    import csv
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        raise SystemExit("matplotlib is not installed:\n"
                         "  ~/.platformio/penv/Scripts/python -m pip install matplotlib")

    path = ROOT / "bench" / "experiment-results.csv"
    if not path.exists():
        raise SystemExit(f"{path} does not exist; run analyse first")
    with open(path, encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))

    fig_dir = ROOT / "paper" / "fig"
    fig_dir.mkdir(parents=True, exist_ok=True)

    # A palette that survives greyscale printing, because the figures are for a
    # paper and a reviewer's printer is not obliged to have toner.
    colours = ["#4269d0", "#efb118", "#ff725c", "#6cc5b0", "#3ca951", "#a463f2"]

    def group(rows, scale):
        out = {}
        for r in rows:
            if r["scale"] != scale:
                continue
            out.setdefault(r["condition"], []).append(r)
        return dict(sorted(out.items()))

    def rate(items, numerator):
        sent = sum(int(i["sent"]) for i in items)
        return 100.0 * sum(int(i[numerator]) for i in items) / sent if sent else 0.0

    def per_run_rates(items, numerator):
        return [100.0 * int(i[numerator]) / int(i["sent"]) for i in items if int(i["sent"])]

    # 1. Delivery rate by condition, both scales, with the spread over the ten
    #    repetitions shown rather than averaged away.
    fig, axes = plt.subplots(1, 2, figsize=(13, 5), sharey=True)
    for ax, scale, title in zip(axes, ("single", "triple"),
                                ("10 000 packets, channel 37",
                                 "30 000 packets, channels 37/38/39")):
        g = group(rows, scale)
        if not g:
            continue
        names = list(g)
        values = [rate(g[n], "delivered") for n in names]
        spreads = [per_run_rates(g[n], "delivered") for n in names]
        bars = ax.bar(names, values, color=colours[: len(names)], zorder=2)
        for x, points in enumerate(spreads):
            ax.scatter([x] * len(points), points, s=14, color="#33333366", zorder=3)
        for bar, value in zip(bars, values):
            ax.text(bar.get_x() + bar.get_width() / 2, value + 0.4,
                    f"{value:.2f}", ha="center", fontsize=9)
        ax.set_title(title)
        ax.set_xlabel("condition")
        ax.grid(axis="y", alpha=0.3, zorder=0)
    axes[0].set_ylabel("delivered (error-free + repaired), % of sent")
    fig.suptitle("Delivery rate by condition, ten repetitions each")
    fig.tight_layout()
    fig.savefig(fig_dir / "experiment-delivery.png", dpi=160)
    plt.close(fig)

    # 2. The §4 breakdown: what happened to every packet that was sent.
    fig, axes = plt.subplots(1, 2, figsize=(13, 5), sharey=True)
    parts = [("intact", "error-free", "#3ca951"),
             ("repaired_ok", "corrected", "#4269d0"),
             ("repaired_bad", "wrongly corrected", "#a463f2"),
             ("refused", "corrupt, address intact", "#efb118"),
             ("address_damaged", "corrupt, address lost", "#ff725c"),
             ("lost", "not received", "#cccccc")]
    for ax, scale, title in zip(axes, ("single", "triple"),
                                ("single channel", "three channels")):
        g = group(rows, scale)
        if not g:
            continue
        names = list(g)
        bottom = [0.0] * len(names)
        for field, label, colour in parts:
            values = [rate(g[n], field) for n in names]
            ax.bar(names, values, bottom=bottom, label=label, color=colour, zorder=2)
            bottom = [b + v for b, v in zip(bottom, values)]
        # The six categories partition the transmitted packets, so the stack has
        # to reach 100. Drawing the line makes a scoring bug visible in the
        # figure instead of leaving it to be believed.
        ax.axhline(100, color="#666666", linewidth=0.8, linestyle=":", zorder=4)
        ax.set_ylim(0, 108)
        ax.set_title(title)
        ax.set_xlabel("condition")
        ax.grid(axis="y", alpha=0.3, zorder=0)
    axes[0].set_ylabel("% of transmitted packets")
    axes[1].legend(loc="center left", bbox_to_anchor=(1.02, 0.5), frameon=False)
    fig.suptitle("What happened to every packet")
    fig.tight_layout()
    fig.savefig(fig_dir / "experiment-breakdown.png", dpi=160)
    plt.close(fig)

    # 3. Which repair method did the work. Only the corrected conditions have
    #    anything to show, which is itself the point of plotting it separately.
    fig, ax = plt.subplots(figsize=(9, 5))
    corrected = [r for r in rows if r["correction"] == "1"]
    if corrected:
        keys = sorted({(r["scale"], r["condition"]) for r in corrected})
        names = [f"{s[:3]} {c}" for s, c in keys]
        bottom = [0.0] * len(keys)
        for index, method in enumerate(METHODS):
            values = []
            for scale, condition in keys:
                items = [r for r in corrected
                         if r["scale"] == scale and r["condition"] == condition]
                values.append(sum(int(i[f"m_{method}"]) for i in items))
            ax.bar(names, values, bottom=bottom, label=method,
                   color=colours[index % len(colours)], zorder=2)
            bottom = [b + v for b, v in zip(bottom, values)]
        ax.legend(frameon=False)
    ax.set_ylabel("packets corrected")
    ax.set_xlabel("scale and condition")
    ax.set_title("Correction method, by condition")
    ax.grid(axis="y", alpha=0.3, zorder=0)
    fig.tight_layout()
    fig.savefig(fig_dir / "experiment-methods.png", dpi=160)
    plt.close(fig)

    # 4. What the transmitter measured and what it decided. This is the figure
    #    that says whether adaptation had anything to adapt to.
    adaptive = [r for r in rows if r["adaptive"] == "1" and r["g_foreign"]]
    if adaptive:
        fig, (left, right) = plt.subplots(1, 2, figsize=(13, 5))
        for index, scale in enumerate(("single", "triple")):
            items = [r for r in adaptive if r["scale"] == scale]
            if not items:
                continue
            left.plot(range(1, len(items) + 1), [float(r["g_foreign"]) for r in items],
                      marker="o", color=colours[index], label=f"{scale}: G foreign")
            right.plot(range(1, len(items) + 1), [float(r["t_a_ms"]) for r in items],
                       marker="o", color=colours[index], label=f"{scale}: T_a")
        left.axhline(0.1, color="#ff725c", linestyle="--",
                     label="G* = 0.1, the design target")
        left.set_ylabel("measured foreign load G")
        left.set_xlabel("run, in order")
        left.legend(frameon=False, fontsize=9)
        left.grid(alpha=0.3)
        right.axhline(20, color="#999999", linestyle="--", label="20 ms floor")
        right.set_ylabel("chosen T_a, ms")
        right.set_xlabel("run, in order")
        right.legend(frameon=False, fontsize=9)
        right.grid(alpha=0.3)
        fig.suptitle("What the adaptive transmitter measured, and what it chose")
        fig.tight_layout()
        fig.savefig(fig_dir / "experiment-adaptation.png", dpi=160)
        plt.close(fig)

    print(f"wrote figures to {fig_dir}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)

    run = sub.add_parser("run", help="run the four physical cells of one scale")
    run.add_argument("--scale", choices=list(SCALES), required=True)
    run.add_argument("--reps", type=int, default=10)
    run.add_argument("--count", type=int, default=DEFAULT_COUNT,
                     help="advertising events per repetition")
    run.add_argument("--cell", default="", help="run only this cell")
    run.add_argument("--generator", default="",
                     help="name of the interference generator in use, e.g. hmc833. "
                          "Prompts to set it, records it, and lets the adaptive "
                          "planner act on the energy detector as well as on "
                          "decoded arrivals")
    run.add_argument("--mhz", type=int, default=0,
                     help="generator centre frequency; defaults to the channel under test")
    run.add_argument("--interferer-channel", default="37",
                     help="which advertising channel the generator sits on")

    sub.add_parser("analyse", help="score the captures and write the CSV")
    sub.add_parser("plot", help="draw the figures from the CSV")

    args = parser.parse_args()
    if args.cmd == "run":
        command_run(args)
    elif args.cmd == "analyse":
        command_analyse(args)
    else:
        command_plot(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
