"""Regression check: the board must transmit without DTR.

Adafruit_USBD_CDC::write() drops everything until the host asserts DTR, so the
firmware writes into TinyUSB's FIFO directly instead. This script reproduces a
host that never raises DTR - which is what Android's USB stack does - and fails
if the board goes silent.

    python firmware/tools/nodtr_test.py
"""
import sys
import time

import serial
import serial.tools.list_ports

CAPTURE_SECONDS = 6


def find_port():
    for port in serial.tools.list_ports.comports():
        if port.vid == 0x239A:  # Adafruit
            return port.device
    return None


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else find_port()
    if not port:
        print("No nRF52840 CDC port found.")
        return 2

    # dsrdtr=False stops pyserial raising DTR on open; forcing both control
    # lines low leaves the board seeing line_state == 0, exactly as it would
    # from a host that skips SET_CONTROL_LINE_STATE entirely.
    ser = serial.Serial(dsrdtr=False, rtscts=False, timeout=0.3)
    ser.port = port
    ser.baudrate = 115200
    ser.dtr = False
    ser.rts = False
    ser.open()
    ser.dtr = False
    ser.rts = False

    with ser:
        print(f"port {port}, dtr={ser.dtr}, rts={ser.rts}")
        time.sleep(1.0)
        ser.reset_input_buffer()

        ser.write(b"ver\n")
        time.sleep(0.3)
        ser.write(b"ch all\n")
        time.sleep(0.2)
        ser.write(b"start\n")

        packets, others = [], []
        deadline = time.time() + CAPTURE_SECONDS
        while time.time() < deadline:
            line = ser.readline().decode("ascii", "replace").strip()
            if not line:
                continue
            (packets if line.startswith("$P,") else others).append(line)

        ser.write(b"stop\n")
        time.sleep(0.2)

    for line in others[:6]:
        print(" ", line)
    print(f"\n{len(packets)} packets captured with DTR low")
    for line in packets[:3]:
        print(" ", line)

    if not packets:
        print("\nFAIL: the board sent nothing without DTR.")
        return 1

    print("\nPASS: the board transmits without DTR.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
