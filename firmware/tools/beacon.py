"""Drive the nRF52840 reference beacon from the command line.

Implements the host half of §6 of the specification: a transmitter sending a
payload the receiver already knows, so packet recovery can be measured against
ground truth instead of against damage this project injected itself. Run the
beacon on one board, capture with the sniffer on another, and every received
frame can be compared byte for byte with what was actually sent.

The beacon prints the exact PDU it will transmit (`$U,...`) before sending
anything. Record that line - it is the ground truth the whole measurement rests
on, and reconstructing it afterwards from the arguments you think you passed is
how a measurement quietly becomes a guess.

Examples
--------

    # What is attached, and what can it do?
    python firmware/tools/beacon.py --list
    python firmware/tools/beacon.py --levels

    # 500 packets at full power, all three channels, 20 ms apart.
    python firmware/tools/beacon.py --count 500

    # A fixed 31 byte payload from a known address, one channel, at -12 dBm.
    python firmware/tools/beacon.py \
        --address C0:FF:EE:BE:AC:01 \
        --data 0201060909524546424541434F4E \
        --length 31 --channel 37 --power -12 --count 1000

    # The measurement §6 actually asks for: 200 packets at every power level
    # from +8 dBm down to -40 dBm, so the error rate can be plotted against it.
    python firmware/tools/beacon.py --sweep --count 200 --interval 10

    # A faster sweep that visits every other level.
    python firmware/tools/beacon.py --sweep --count 100 --step 2

    # Save the run for later analysis alongside the sniffer's capture.
    python firmware/tools/beacon.py --sweep --count 200 --log run.csv
"""
from __future__ import annotations

import argparse
import csv
import sys
import time

try:
    import serial
    import serial.tools.list_ports
except ImportError:  # pragma: no cover - environment problem, not a code path
    print("pyserial is required:  pip install pyserial", file=sys.stderr)
    raise SystemExit(2)

ADAFRUIT_VID = 0x239A


def find_port() -> str | None:
    for port in serial.tools.list_ports.comports():
        if port.vid == ADAFRUIT_VID:
            return port.device
    return None


def open_port(port: str) -> serial.Serial:
    """Opens the CDC port without depending on DTR.

    The firmware writes into TinyUSB's FIFO directly rather than through
    Serial, precisely so it does not go silent on a host that never raises DTR.
    Opening the same way here keeps this script honest about that.
    """
    ser = serial.Serial(dsrdtr=False, rtscts=False, timeout=0.2)
    ser.port = port
    ser.baudrate = 115200
    ser.open()
    ser.dtr = True
    ser.rts = True
    time.sleep(0.6)
    ser.reset_input_buffer()
    return ser


def command(ser: serial.Serial, text: str, wait: float = 0.4) -> list[str]:
    """Sends one command and returns whatever the board said about it."""
    ser.write((text + "\n").encode("ascii"))
    ser.flush()
    lines = []
    deadline = time.time() + wait
    while time.time() < deadline:
        raw = ser.readline()
        if not raw:
            continue
        line = raw.decode("ascii", "replace").strip()
        if line:
            lines.append(line)
    return lines


def expect_ok(lines: list[str], what: str) -> None:
    for line in lines:
        if line.startswith("$A,") and ",err," in line:
            raise SystemExit(f"beacon rejected {what}: {line}")


def parse_address(text: str) -> str:
    cleaned = text.replace(":", "").replace("-", "").strip()
    if len(cleaned) != 12:
        raise argparse.ArgumentTypeError("address must be 6 bytes, e.g. C0:FF:EE:BE:AC:01")
    int(cleaned, 16)  # raises if it is not hex
    return text


def parse_hex(text: str) -> str:
    cleaned = text.replace(":", "").replace(" ", "").strip()
    if len(cleaned) % 2:
        raise argparse.ArgumentTypeError("payload needs an even number of hex digits")
    int(cleaned or "0", 16)
    return cleaned


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Drive the nRF52840 reference beacon.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("Examples", 1)[1] if "Examples" in __doc__ else None,
    )
    parser.add_argument("--port", help="serial port; autodetected when omitted")
    parser.add_argument("--list", action="store_true", help="list serial ports and exit")
    parser.add_argument("--levels", action="store_true",
                        help="ask the board which power levels it supports, and exit")

    parser.add_argument("--address", type=parse_address,
                        help="advertiser address, e.g. C0:FF:EE:BE:AC:01")
    parser.add_argument("--random", action="store_true",
                        help="mark the address as random rather than public")
    parser.add_argument("--data", type=parse_hex,
                        help="advertising data as hex, e.g. 0201060909...")
    parser.add_argument("--length", type=int,
                        help="pad or truncate the advertising data to exactly N bytes (0..31)")
    parser.add_argument("--type", type=int, default=None,
                        help="PDU type nibble; 2 = ADV_NONCONN_IND (default on the board)")

    parser.add_argument("--channel", default="all", help="37, 38, 39 or all (default: all)")
    parser.add_argument("--power", type=int, help="transmit power in dBm; see --levels")
    parser.add_argument("--count", type=int, default=100,
                        help="packets to send, or packets per level when sweeping")
    parser.add_argument("--interval", type=int, default=20,
                        help="milliseconds between packets (default: 20)")
    parser.add_argument("--sweep", action="store_true",
                        help="step the power from maximum to minimum, --count at each level")
    parser.add_argument("--step", type=int, default=1,
                        help="visit every Nth power level when sweeping (default: 1)")
    parser.add_argument("--log", help="write the transmitted packets to this CSV")
    parser.add_argument("--quiet", action="store_true", help="only print the summary")

    args = parser.parse_args()

    if args.list:
        ports = list(serial.tools.list_ports.comports())
        if not ports:
            print("no serial ports found")
        for port in ports:
            vid = f"{port.vid:04x}" if port.vid else "----"
            pid = f"{port.pid:04x}" if port.pid else "----"
            mark = "  <- nRF52840" if port.vid == ADAFRUIT_VID else ""
            print(f"{port.device:8s} {vid}:{pid}  {port.description}{mark}")
        return 0

    port = args.port or find_port()
    if not port:
        print("No nRF52840 found. Plug one in, or pass --port.", file=sys.stderr)
        return 2

    with open_port(port) as ser:
        banner = command(ser, "ver", wait=0.8)
        identity = next((l for l in banner if l.startswith("$I,")), None)
        if identity is None:
            print(f"{port} did not answer. Is the beacon firmware flashed?", file=sys.stderr)
            return 2
        if "BLEBEACON" not in identity:
            print(f"{port} is not running the beacon firmware: {identity}", file=sys.stderr)
            return 2
        print(identity)

        if args.levels:
            for line in command(ser, "levels"):
                print(line)
            return 0

        # Configure. Order matters: --data resets the length to the data's own
        # size, so --length has to be applied after it to take effect.
        if args.address:
            suffix = " random" if args.random else ""
            expect_ok(command(ser, f"addr {args.address}{suffix}"), "address")
        if args.type is not None:
            expect_ok(command(ser, f"type {args.type}"), "pdu type")
        if args.data is not None:
            expect_ok(command(ser, f"data {args.data}"), "data")
        if args.length is not None:
            expect_ok(command(ser, f"len {args.length}"), "length")

        expect_ok(command(ser, f"ch {args.channel}"), "channel")
        if args.power is not None and not args.sweep:
            expect_ok(command(ser, f"power {args.power}"), "power")

        pdu = next((l for l in command(ser, "pdu") if l.startswith("$U,")), None)
        if pdu is None:
            print("the board did not report its PDU", file=sys.stderr)
            return 2
        transmitted = pdu[3:]
        print(f"transmitting: {transmitted}   ({len(transmitted) // 2} bytes)")

        verb = "sweep" if args.sweep else "send"
        started = time.time()

        # Written straight to the port rather than through command(), which
        # would spend its read window swallowing output. The board starts
        # transmitting immediately, so at a 10 ms interval a 300 ms window eats
        # thirty $B lines and the run under-reports itself by half. The ack is
        # picked up by the loop below instead.
        ser.write(f"{verb} {args.count} {args.interval} {args.step}\n".encode("ascii"))
        ser.flush()

        rows = []
        per_level: dict[int, int] = {}
        sent = 0
        try:
            while True:
                raw = ser.readline()
                if not raw:
                    if time.time() - started > 6 and sent == 0:
                        print("no packets reported; giving up", file=sys.stderr)
                        return 1
                    continue
                line = raw.decode("ascii", "replace").strip()
                if not line:
                    continue

                if line.startswith("$B,"):
                    seq, power, channel, micros = line[3:].split(",")
                    sent += 1
                    per_level[int(power)] = per_level.get(int(power), 0) + 1
                    rows.append((seq, power, channel, micros, transmitted))
                    if not args.quiet and sent % 50 == 0:
                        print(f"  {sent} packets, currently {power} dBm")
                elif line.startswith("$R,"):
                    power, count = line[3:].split(",")
                    if not args.quiet:
                        print(f"  finished {count} packets at {power} dBm")
                elif line.startswith("$E,"):
                    reported = int(line[3:])
                    if reported != sent:
                        # The board counts what it transmitted; this script
                        # counts what it managed to read. A mismatch means USB
                        # backpressure dropped report lines, not that packets
                        # went missing - but it has to be said out loud, since
                        # the whole point here is knowing exactly what was sent.
                        print(f"note: board reported {reported} sent, this script saw {sent}")
                        sent = reported
                    break
                elif line.startswith("$A,") and ",err," in line:
                    print(line, file=sys.stderr)
                    return 1
        except KeyboardInterrupt:
            command(ser, "stop")
            print("\ninterrupted; the board has been told to stop")

        elapsed = time.time() - started
        print(f"\nsent {sent} packets in {elapsed:.1f}s across {len(per_level)} power level(s)")
        for power in sorted(per_level, reverse=True):
            print(f"  {power:>4} dBm  {per_level[power]} packets")

        if args.log:
            with open(args.log, "w", newline="") as handle:
                writer = csv.writer(handle)
                writer.writerow(["seq", "power_dbm", "channel", "device_us", "pdu_hex"])
                writer.writerows(rows)
            print(f"\nwrote {len(rows)} rows to {args.log}")
            print("Compare against the sniffer's capture: every received frame whose")
            print("bytes differ from pdu_hex is a real error with known ground truth.")

    return 0


if __name__ == "__main__":
    sys.exit(main())
