"""Drives the sniffer app by locating controls, not by tapping coordinates.

Fixed coordinates were good enough for a handful of taps and are not good
enough for a bench session of twenty-five runs: a button edge missed by ten
pixels opens the keyboard instead of clearing the capture, and the run carries
on recording into a capture that was never reset. Every action here finds its
control in the view hierarchy, taps the centre of it, and verifies the result.

`state` reports the capture source as well as the run state. That is not
decoration: mid-session the source reverted from the board to the phone's own
radio, and because the phone's controller reports no CRC failures and no
channel, the affected scenario recorded nothing at all - which is
indistinguishable from perfect jamming unless the source is checked.

    python firmware/tools/appctl.py state
    python firmware/tools/appctl.py clear
    python firmware/tools/appctl.py start
    python firmware/tools/appctl.py stop
    python firmware/tools/appctl.py usb      (select the board and connect)
    python firmware/tools/appctl.py phone    (select the phone's own radio)
    python firmware/tools/appctl.py raw      (board sends bytes as received)
    python firmware/tools/appctl.py std      (board checks and strips the CRC)

Set BLESNIFF_DEVICE to talk to a phone at a different address.
"""
import os
import re
import subprocess
import sys
import time

# The phone this bench runs on. Overridable, because an experiment that has
# to be re-run on another device should not need the tool edited.
DEVICE = os.environ.get("BLESNIFF_DEVICE", "192.168.178.172:5555")
ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")

# The one control with no text of its own. The source and channel buttons are
# not here on purpose: both are found by their own label, because the row is
# laid out left to right and the source button's width follows its text. A
# coordinate calibrated with "nRF52840" selected lands on the *channel* button
# once the label narrows to "Phone", which opens nothing and reports that the
# source menu did not open.
TRASH = (1123, 537)

# Exactly the labels the app renders, which are the ones in `CaptureSource`
# (android/.../model/Types.kt) and not the longer names they were guessed to be:
# a label that does not match makes every control here read the source as "?",
# and select_source then blames the menu for not opening.
SOURCES = ("nRF52840", "Phone", "Both")


def adb(*args: str) -> str:
    """Runs adb and returns stdout, never None and never raising on encoding.

    The device's dumps carry whatever text happens to be on screen, which on a
    phone with a Ukrainian keyboard installed is not decodable as the host's
    default code page. Left to the default, one keyboard layout is enough to
    crash every control action with a UnicodeDecodeError from a reader thread.
    """
    result = subprocess.run(
        [ADB, "-s", DEVICE, *args],
        capture_output=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.stdout or ""


def keyboard_up() -> bool:
    return "mInputShown=true" in adb("shell", "dumpsys", "input_method")


def focus() -> None:
    """Puts the app in front and clears anything covering it.

    Two things repeatedly got in the way over a long bench session. The
    notification shade sliding down makes every reading return "?", which a
    caller watching for trouble reads as the capture having died - it has not,
    it is simply not the window on top, and aborting a good run costs the whole
    series. And a tap that lands in the address filter raises the soft keyboard,
    which then covers the controls the next tap is aiming for, so one mistap
    silently turns into several.
    """
    if keyboard_up():
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.8)

    current = adb("shell", "dumpsys", "window")
    if "com.blesniffer/com.blesniffer.MainActivity" in current.split("mCurrentFocus")[-1][:200]:
        return
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.8)
    adb("shell", "am", "start", "-n", "com.blesniffer/.MainActivity")
    time.sleep(2.0)


def dump(refocus: bool = True) -> str:
    """The view hierarchy as XML.

    Pass refocus=False whenever a menu or a dialog is meant to be on screen.
    focus() dismisses whatever is not the main activity, and a popup is exactly
    that: reading one through focus() closes the popup and then reports that it
    never opened, which sends the operator looking for a mistap that did not
    happen. The two callers that open something are the only ones that need it.
    """
    if refocus:
        focus()
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml")


def find(xml: str, text: str):
    """Centre of the node whose text is exactly [text], or None."""
    for match in re.finditer(
        r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml
    ):
        if match.group(1) == text:
            x1, y1, x2, y2 = (int(match.group(i)) for i in range(2, 6))
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def tap(point) -> None:
    adb("shell", "input", "tap", str(point[0]), str(point[1]))


def packet_count(xml: str) -> int:
    # The badge reads "1 234 packets", the separators being U+2009 thin spaces.
    # Matching a short arbitrary run rather than a literal space class avoids
    # depending on which space character survives the XML round trip; spelling
    # it out returned -1 and made the state read "unknown" indefinitely.
    match = re.search(r'text="([\d\D]{0,20}?) packets"', xml)
    if not match:
        return -1
    digits = re.sub(r"\D", "", match.group(1))
    return int(digits) if digits else -1


def source(xml: str) -> str:
    for label in SOURCES:
        if find(xml, label):
            return label
    return "?"


def running(xml: str) -> bool:
    return find(xml, "Stop") is not None


def state() -> None:
    xml = dump()
    channel = re.search(r'text="Ch ([^"]+)"', xml)
    print(f"source    = {source(xml)}")
    print(f"capturing = {running(xml)}")
    print(f"channel   = {channel.group(1) if channel else '?'}")
    print(f"packets   = {packet_count(xml)}")


def clear() -> int:
    tap(TRASH)
    time.sleep(1.5)
    xml = dump(refocus=False)
    button = find(xml, "Clear")
    if button is None:
        print("FAILED: no Clear button; the dialog did not open")
        return 1
    tap(button)
    time.sleep(2.0)

    count = packet_count(dump())
    if count > 50:
        # A live capture refills immediately, so demand only that it collapsed.
        print(f"FAILED: {count} packets remain")
        return 1
    print(f"cleared (now {count} packets)")
    return 0


def select_source(wanted: str) -> int:
    """Selects a capture source and, for the board, connects to it.

    The source is read back off the screen afterwards rather than assumed. The
    module docstring explains why: mid-session the source reverted on its own,
    and a phone-radio capture recorded under an nRF52840 label is not a failed
    run, it is a wrong one that looks fine.
    """
    xml = dump()
    current = source(xml)
    if current != wanted:
        # The button carries the current source as its text, so the label node
        # is both how the control is found and proof of what it is showing.
        control = find(xml, current)
        if control is None:
            print(f"FAILED: no source control on screen (it reads '{current}')")
            return 1
        tap(control)
        time.sleep(1.5)
        item = find(dump(refocus=False), wanted)
        if item is None:
            print("FAILED: the source menu did not open")
            return 1
        tap(item)
        time.sleep(2.5)

    xml = dump()
    if source(xml) != wanted:
        print(f"FAILED: source reads '{source(xml)}'")
        return 1

    if wanted == "Phone":
        print("source = Phone")
        return 0

    # Attaching the board is not instant and is not always uniform: the first
    # connect after a source switch can sit behind Android's USB permission
    # prompt, and one four second window judged that a failure while it was
    # still in progress. Waited out instead, and the tap repeated only while
    # the button is genuinely still on offer.
    for attempt in range(3):
        connect = find(dump(), "Connect")
        if connect is None:
            print(f"source = {wanted}, connected")
            return 0
        tap(connect)
        for _ in range(6):
            time.sleep(2.0)
            if find(dump(), "Connect") is None:
                print(f"source = {wanted}, connected")
                return 0

    print("FAILED: the board is still offering Connect; is it plugged in?")
    return 1


def capture_mode(want_raw: bool) -> int:
    """Switches the board between RAW and STD.

    RAW is what puts the received CRC in front of the recovery pipeline, so the
    two are not interchangeable for anything this experiment measures: in STD
    the radio checks and strips the CRC and a damaged frame arrives already
    judged. The toggle renders its own state as literal text, so the control is
    both found and verified by reading it.
    """
    want = "RAW" if want_raw else "STD"
    other = "STD" if want_raw else "RAW"

    xml = dump()
    if find(xml, want) is not None:
        print(f"capture mode = {want}")
        return 0
    button = find(xml, other)
    if button is None:
        print("FAILED: no RAW/STD control; is the board the selected source?")
        return 1
    tap(button)
    time.sleep(2.0)

    if find(dump(), want) is None:
        print(f"FAILED: the control did not switch to {want}")
        return 1
    print(f"capture mode = {want}")
    return 0


def toggle(want_running: bool) -> int:
    xml = dump()
    if running(xml) == want_running:
        print(f"already {'running' if want_running else 'stopped'}")
        return 0
    button = find(xml, "Stop") or find(xml, "Start")
    if button is None:
        print("FAILED: no Start/Stop control found")
        return 1
    tap(button)
    time.sleep(2.5)
    if running(dump()) != want_running:
        print("FAILED: the control did not change state")
        return 1
    print("capturing" if want_running else "stopped")
    return 0


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    command = sys.argv[1]
    if command == "state":
        state()
        return 0
    if command == "clear":
        return clear()
    if command == "start":
        return toggle(True)
    if command == "stop":
        return toggle(False)
    if command == "usb":
        return select_source("nRF52840")
    if command == "phone":
        return select_source("Phone")
    if command == "both":
        return select_source("Both")
    if command == "raw":
        return capture_mode(True)
    if command == "std":
        return capture_mode(False)
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
