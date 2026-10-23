"""Interference bench: run scenarios, then join the capture against ground truth.

The sniffer records one continuous capture for a whole session; this tool marks
out the wall-clock window each scenario occupied and, afterwards, assigns every
received packet to the window it fell in. That is deliberate. Starting and
stopping the capture between scenarios would mean driving the phone's UI dozens
of times, and a mistap halfway through a two-hour bench session loses the run.
One start, one stop, and arithmetic in between.

Ground truth comes from the beacon (`beacon.py`), which reports the exact PDU it
transmits. Every received frame from the beacon's address that differs from that
PDU is a real channel error whose correct value is known.

Usage
-----

    # 1. On the phone: clear history, select nRF52840 (USB), RAW mode, Start.

    # 2. Scenarios that need nobody at the bench:
    python firmware/tools/bench.py run quiet
    python firmware/tools/bench.py run perchannel

    # 3. Scenarios with an external generator. These prompt at the console
    #    before every frequency and every level change:
    python firmware/tools/bench.py run hmc833
    python firmware/tools/bench.py run adf4351
    python firmware/tools/bench.py run ysgm232508

    # 4. On the phone: Stop. Then:
    python firmware/tools/bench.py analyse
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

BENCH_DIR = Path(__file__).resolve().parents[2] / "bench"

# The beacon's identity. Fixed so the analysis can find it among the ambient
# traffic without guessing.
ADDRESS = "C0:FF:EE:BE:AC:01"
DATA = "0201060B09524546434845434B"
LENGTH = 31

# Advertising channel centre frequencies. The operator needs these to point a
# generator at the channel under test.
CHANNEL_MHZ = {37: 2402, 38: 2426, 39: 2480}

# Four levels, strongest first. The operator is asked to start high and work
# down, because the interesting transition is where errors first appear and
# approaching it from above means passing through it rather than guessing.
LEVELS = ["L1 (highest)", "L2", "L3", "L4 (lowest)"]

DEFAULT_COUNT = 100
DEFAULT_INTERVAL = 10


def adb(*args: str) -> str:
    exe = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
    result = subprocess.run([exe, *args], capture_output=True, text=True)
    return result.stdout.strip()


def ask(prompt: str) -> None:
    """Blocks until the operator confirms. The bench is half human."""
    print()
    print("=" * 72)
    print(prompt)
    print("=" * 72)
    try:
        input("  press Enter when ready (Ctrl-C to abandon the run) ... ")
    except EOFError:
        # Running without a console attached; nobody is there to answer, and
        # continuing would silently record a scenario that never happened.
        raise SystemExit("no console available: this scenario needs an operator")


def confirm(prompt: str) -> bool:
    print()
    print("=" * 72)
    print(prompt)
    print("=" * 72)
    try:
        answer = input("  type y to confirm, anything else to abort: ").strip().lower()
    except EOFError:
        raise SystemExit("no console available: this scenario needs an operator")
    return answer.startswith("y")


def transmit(ser, channel, count: int, interval: int, power: int | None) -> dict:
    """Runs one burst and returns the window it occupied, in host wall clock."""
    beacon_tool.command(ser, f"ch {channel}")
    if power is not None:
        beacon_tool.command(ser, f"power {power}")

    started = time.time()
    ser.write(f"send {count} {interval} 1\n".encode("ascii"))
    ser.flush()

    sent = 0
    while True:
        raw = ser.readline()
        if not raw:
            if time.time() - started > 60:
                break
            continue
        line = raw.decode("ascii", "replace").strip()
        if line.startswith("$B,"):
            sent += 1
        elif line.startswith("$E,"):
            sent = int(line[3:])
            break
    ended = time.time()

    return {
        "start_ms": int(started * 1000),
        "end_ms": int(ended * 1000),
        "sent": sent,
        "channel": str(channel),
        "power_dbm": power,
    }


def open_beacon():
    port = beacon_tool.find_port()
    if not port:
        raise SystemExit("no nRF52840 beacon found on USB")
    ser = beacon_tool.open_port(port)

    identity = beacon_tool.command(ser, "ver", wait=0.8)
    if not any("BLEBEACON" in line for line in identity):
        raise SystemExit(f"{port} is not running the beacon firmware")

    beacon_tool.command(ser, f"addr {ADDRESS}")
    beacon_tool.command(ser, f"data {DATA}")
    beacon_tool.command(ser, f"len {LENGTH}")

    pdu = next(
        (l for l in beacon_tool.command(ser, "pdu") if l.startswith("$U,")), None
    )
    if pdu is None:
        raise SystemExit("the beacon did not report its PDU")
    return ser, pdu[3:]


def save(name: str, pdu: str, runs: list[dict]) -> Path:
    BENCH_DIR.mkdir(exist_ok=True)
    path = BENCH_DIR / f"{name}.json"
    path.write_text(json.dumps({"scenario": name, "pdu": pdu, "runs": runs}, indent=2))
    print(f"\nwrote {len(runs)} run(s) to {path}")
    return path


# --- scenarios ---------------------------------------------------------------


def scenario_quiet(args) -> None:
    """8.1 - ambient traffic only, the beacon silent.

    Establishes what the room does on its own, which every later scenario is
    measured against. Without it, an error rate under interference has nothing
    to be an increase over.
    """
    seconds = args.seconds
    print(f"observing ambient traffic for {seconds} s with the beacon silent ...")
    started = time.time()
    time.sleep(seconds)
    save("quiet", "", [{
        "start_ms": int(started * 1000),
        "end_ms": int(time.time() * 1000),
        "sent": 0,
        "channel": "none",
        "power_dbm": None,
        "label": "ambient only",
    }])


def scenario_perchannel(args) -> None:
    """8.2 - the beacon alone, each channel separately and then all three."""
    ser, pdu = open_beacon()
    runs = []
    with ser:
        print(f"transmitting: {pdu}")
        for channel in (37, 38, 39, "all"):
            print(f"\n-- channel {channel} at {args.power} dBm --")
            run = transmit(ser, channel, args.count, args.interval, args.power)
            run["label"] = f"ch {channel}"
            run["generator"] = "none"
            print(f"   sent {run['sent']}")
            runs.append(run)
    save("perchannel", pdu, runs)


def scenario_generator(args, name: str, pretty: str) -> None:
    """8.3 - one channel at a time, against an external generator.

    Three frequencies, four levels at each: twelve runs. The operator is
    prompted before every frequency change and before every level change, and
    levels descend from the highest, so the run passes through the threshold at
    which errors begin rather than having to find it.
    """
    ser, pdu = open_beacon()
    runs = []
    try:
        with ser:
            print(f"transmitting: {pdu}")
            for channel in (37, 38, 39):
                ask(
                    f"{pretty}: set the interference frequency to "
                    f"{CHANNEL_MHZ[channel]} MHz  (BLE advertising channel {channel})"
                )
                for index, level in enumerate(LEVELS):
                    ask(
                        f"{pretty} @ {CHANNEL_MHZ[channel]} MHz: "
                        f"set the signal level to {level}"
                        + ("   [start here - highest first]" if index == 0 else "")
                    )
                    print(f"-- ch {channel}, {level} --")
                    run = transmit(ser, channel, args.count, args.interval, args.power)
                    run["label"] = f"ch {channel} / {level}"
                    run["generator"] = name
                    run["generator_mhz"] = CHANNEL_MHZ[channel]
                    run["level"] = level
                    print(f"   sent {run['sent']}")
                    runs.append(run)
    finally:
        if runs:
            save(name, pdu, runs)


def scenario_step(args) -> None:
    """One configuration, run on somebody else's confirmation.

    The interactive scenarios above prompt at this console. When the operator is
    being instructed elsewhere - and has already confirmed the generator is set
    - there is nobody here to answer a prompt, and asking would only stall the
    run. This does exactly one burst and appends it to the named manifest.
    """
    ser, pdu = open_beacon()
    with ser:
        run = transmit(ser, args.channel, args.count, args.interval, args.power)
        run["label"] = args.label
        run["generator"] = args.generator
        if args.mhz:
            run["generator_mhz"] = args.mhz
        if args.level:
            run["level"] = args.level

    path = BENCH_DIR / f"{args.set}.json"
    if path.exists():
        blob = json.loads(path.read_text())
        if blob["pdu"] != pdu:
            raise SystemExit(
                "the beacon is transmitting a different PDU than earlier runs in "
                "this set; the ground truth would not be comparable"
            )
        runs = blob["runs"] + [run]
    else:
        runs = [run]

    print(f"sent {run['sent']} on channel {args.channel}  [{args.label}]")
    save(args.set, pdu, runs)


def scenario_broadband(args) -> None:
    """8.4 - all three channels at once, against a broadband generator.

    The YSGM232508 covers the whole band, so there is no frequency to step and
    the beacon can use all three channels as a real advertiser would. The
    generator being on is *confirmed* rather than assumed: a run against a
    generator that was never switched on looks exactly like a clean run, and
    would be recorded as evidence that interference does not matter.
    """
    ser, pdu = open_beacon()
    runs = []
    try:
        with ser:
            print(f"transmitting: {pdu}")

            print("\nfirst a reference run with the generator OFF")
            ask("YSGM232508: make sure the generator is switched OFF")
            run = transmit(ser, "all", args.count, args.interval, args.power)
            run.update(label="all channels, generator off", generator="none")
            print(f"   sent {run['sent']}")
            runs.append(run)

            if not confirm(
                "YSGM232508: switch the generator ON now.\n"
                "  Is it powered up and radiating across the 2.4 GHz band?"
            ):
                raise SystemExit("operator did not confirm the generator; nothing recorded")

            for index, level in enumerate(LEVELS):
                ask(
                    f"YSGM232508: set the output level to {level}"
                    + ("   [start here - highest first]" if index == 0 else "")
                )
                print(f"-- all channels, {level} --")
                run = transmit(ser, "all", args.count, args.interval, args.power)
                run.update(
                    label=f"all channels / {level}",
                    generator="ysgm232508",
                    level=level,
                )
                print(f"   sent {run['sent']}")
                runs.append(run)
    finally:
        if runs:
            save("ysgm232508", pdu, runs)


# --- analysis ----------------------------------------------------------------


def pull_capture(path: Path) -> None:
    """Copies the live capture off the phone, write-ahead log included.

    The app runs SQLite in WAL mode and is still writing while the bench runs,
    so the main database file on its own is a torn snapshot - recent packets
    live in the -wal and opening it alone reports "database disk image is
    malformed". Pulling all three files lets SQLite replay the log on open.
    """
    exe = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
    for suffix in ("", "-wal", "-shm"):
        target = path.with_name(path.name + suffix)
        with open(target, "wb") as handle:
            result = subprocess.run(
                [exe, "exec-out", "run-as", "com.blesniffer",
                 "cat", f"databases/capture.db{suffix}"],
                stdout=handle,
            )
        if result.returncode != 0 or target.stat().st_size == 0:
            # -wal and -shm legitimately vanish after a checkpoint.
            target.unlink(missing_ok=True)
            if suffix == "":
                raise SystemExit("could not read the capture database")
    print(f"pulled {path.stat().st_size} bytes to {path}")


def analyse(args) -> None:
    import csv
    import sqlite3

    db_path = BENCH_DIR / "capture.db"
    if args.pull or not db_path.exists():
        BENCH_DIR.mkdir(exist_ok=True)
        pull_capture(db_path)

    db = sqlite3.connect(db_path)
    rows = db.execute(
        "select ts_ms, channel, rssi, crc_ok, address, raw_hex, rec_method, rec_hex "
        "from packets order by ts_ms"
    ).fetchall()
    print(f"capture holds {len(rows)} packets\n")

    results = []
    for manifest in sorted(BENCH_DIR.glob("*.json")):
        blob = json.loads(manifest.read_text())
        sent_hex = blob["pdu"].upper()
        sent = bytes.fromhex(sent_hex) if sent_hex else b""

        for run in blob["runs"]:
            lo, hi = run["start_ms"], run["end_ms"]
            window = [r for r in rows if lo <= r[0] <= hi]
            beacon_rx = [r for r in window if r[4] == ADDRESS]

            intact = damaged = repaired_ok = repaired_bad = refused = 0
            rssi_values = []
            for _, ch, rssi, ok, _, hexs, method, rec in beacon_rx:
                got = bytes.fromhex(hexs)
                body = got[: len(sent)] if len(got) == len(sent) + 3 else got
                rssi_values.append(rssi)
                if sent and body == sent:
                    intact += 1
                    continue
                damaged += 1
                if method and method != "unrecoverable" and rec:
                    fixed = bytes.fromhex(rec)
                    fixed_body = fixed[: len(sent)] if len(fixed) == len(sent) + 3 else fixed
                    if fixed_body == sent:
                        repaired_ok += 1
                    else:
                        repaired_bad += 1
                else:
                    refused += 1

            results.append({
                "scenario": blob["scenario"],
                "label": run.get("label", ""),
                "generator": run.get("generator", ""),
                "generator_mhz": run.get("generator_mhz", ""),
                "level": run.get("level", ""),
                "channel": run["channel"],
                "power_dbm": run.get("power_dbm", ""),
                "sent": run["sent"],
                "ambient": len(window) - len(beacon_rx),
                "received": len(beacon_rx),
                "intact": intact,
                "damaged": damaged,
                "repaired_ok": repaired_ok,
                "repaired_bad": repaired_bad,
                "refused": refused,
                "mean_rssi": round(sum(rssi_values) / len(rssi_values), 1) if rssi_values else "",
            })

    out = BENCH_DIR / "results.csv"
    with open(out, "w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(results[0].keys()))
        writer.writeheader()
        writer.writerows(results)

    header = f"{'scenario':<12} {'label':<26} {'sent':>5} {'rx':>5} {'dmg':>4} {'ok':>4} {'bad':>4} {'rssi':>6}"
    print(header)
    print("-" * len(header))
    for r in results:
        print(
            f"{r['scenario']:<12} {r['label']:<26} {r['sent']:>5} {r['received']:>5} "
            f"{r['damaged']:>4} {r['repaired_ok']:>4} {r['repaired_bad']:>4} {str(r['mean_rssi']):>6}"
        )
    print(f"\nwrote {out}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)

    run = sub.add_parser("run", help="run a scenario set")
    run.add_argument("scenario",
                     choices=["quiet", "perchannel", "hmc833", "adf4351", "ysgm232508"])
    run.add_argument("--count", type=int, default=DEFAULT_COUNT)
    run.add_argument("--interval", type=int, default=DEFAULT_INTERVAL)
    run.add_argument("--power", type=int, default=0,
                     help="beacon power in dBm. 0 is chosen so the clean link is "
                          "reliable: the whole point of the interference runs is "
                          "that any loss is attributable to the generator, and a "
                          "beacon already at its sensitivity limit confounds the "
                          "link budget with the thing being measured")
    run.add_argument("--seconds", type=int, default=60, help="quiet scenario only")

    step = sub.add_parser("step", help="run one configuration, no prompting")
    step.add_argument("--set", required=True, help="manifest to append to")
    step.add_argument("--channel", required=True, help="37, 38, 39 or all")
    step.add_argument("--label", required=True)
    step.add_argument("--generator", default="none")
    step.add_argument("--mhz", type=int, default=0)
    step.add_argument("--level", default="")
    step.add_argument("--count", type=int, default=DEFAULT_COUNT)
    step.add_argument("--interval", type=int, default=DEFAULT_INTERVAL)
    step.add_argument("--power", type=int, default=0)

    ana = sub.add_parser("analyse", help="join the capture against the manifests")
    ana.add_argument("--pull", action="store_true", help="re-pull the capture first")

    args = parser.parse_args()
    if args.cmd == "analyse":
        analyse(args)
        return 0
    if args.cmd == "step":
        scenario_step(args)
        return 0

    if args.scenario == "quiet":
        scenario_quiet(args)
    elif args.scenario == "perchannel":
        scenario_perchannel(args)
    elif args.scenario == "hmc833":
        scenario_generator(args, "hmc833", "HMC833 PLL generator")
    elif args.scenario == "adf4351":
        scenario_generator(args, "adf4351", "ADF4351 PLL generator")
    else:
        scenario_broadband(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
