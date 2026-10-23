"""Sets the sniffer's receive channel from the host, by driving the app.

The app has no remote control interface, so this taps the channel menu. The
coordinates are for the device the bench runs on and are the one part of the
harness that is not portable; they are checked by reading the control back off
the screen rather than assumed, because a mistap here does not fail loudly - it
silently records a whole scenario on the wrong channel.

    python firmware/tools/rxchan.py 37|38|39|all
"""
import os
import re
import subprocess
import sys
import time

# Overridable, and the experiment harness sets it: adb over Wi-Fi is dropped on
# reboot and comes back on a different port, and a whole bench session should
# not be blocked on a constant in a source file.
DEVICE = os.environ.get("BLESNIFF_DEVICE", "192.168.178.172:5555")
ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")

MENU_BUTTON = (744, 211)
ITEMS = {"37": (680, 379), "38": (680, 524), "39": (680, 664), "all": (680, 813)}


def adb(*args: str) -> str:
    # utf-8 with replacement: the screen dump carries whatever text is on the
    # device, which need not be decodable as the host's default code page.
    result = subprocess.run(
        [ADB, "-s", DEVICE, *args],
        capture_output=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.stdout or ""


def tap(x: int, y: int) -> None:
    adb("shell", "input", "tap", str(x), str(y))


def current_label() -> str:
    """Reads the channel button's label out of the view hierarchy."""
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    found = re.findall(r'text="Ch ([^"]+)"', xml)
    return found[0].strip().lower() if found else ""


def main() -> int:
    if len(sys.argv) != 2 or sys.argv[1].lower() not in ITEMS:
        print(__doc__)
        return 2
    want = sys.argv[1].lower()

    tap(*MENU_BUTTON)
    time.sleep(1.2)
    tap(*ITEMS[want])
    time.sleep(1.5)

    label = current_label()
    if label != want:
        print(f"FAILED: the channel control reads '{label}', wanted '{want}'")
        return 1
    print(f"receiver channel = {label}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
