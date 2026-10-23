"""Per-run statistics for a repeated scenario, with confidence intervals.

Reports two different uncertainties, because they answer different questions
and only one of them is usually the honest one:

  * The **binomial** interval is what a single run of n independent trials
    would give if the channel were stationary: se = sqrt(p(1-p)/n).
  * The **between-run** interval is the spread actually observed across
    repetitions: t(0.975, k-1) * s / sqrt(k).

If the channel drifts - and a radio channel in a room with people in it does -
the between-run spread is wider than binomial, and quoting the binomial figure
would understate the uncertainty by claiming the runs were repetitions of the
same experiment when they were repetitions of slightly different ones. Both are
printed so the difference is visible rather than assumed away.

    python firmware/tools/stats.py ysgm-low
"""
import json
import sqlite3
import sys
from math import sqrt
from pathlib import Path

BENCH = Path(__file__).resolve().parents[2] / "bench"
ADDRESS = "C0:FF:EE:BE:AC:01"

# Student's t, two-sided 95%, by degrees of freedom.
T95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447,
       7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228, 11: 2.201, 12: 2.179}


def mean(values):
    return sum(values) / len(values)


def stdev(values):
    if len(values) < 2:
        return 0.0
    m = mean(values)
    return sqrt(sum((v - m) ** 2 for v in values) / (len(values) - 1))


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    name = sys.argv[1]

    blob = json.loads((BENCH / f"{name}.json").read_text())
    sent_pdu = bytes.fromhex(blob["pdu"])
    db = sqlite3.connect(BENCH / "capture.db")

    print(f"{'run':<8} {'sent':>6} {'ambient':>8} {'recv':>6} {'rate %':>7} "
          f"{'intact':>7} {'dmg':>5} {'rep':>4} {'false':>6} {'rssi':>6}")
    print("-" * 72)

    rates, damaged_shares, rows = [], [], []
    for run in blob["runs"]:
        lo, hi = run["start_ms"], run["end_ms"]
        window = db.execute(
            "select address, rssi, raw_hex, rec_method, rec_hex from packets "
            "where ts_ms between ? and ?", (lo, hi)).fetchall()
        beacon = [r for r in window if r[0] == ADDRESS]

        intact = damaged = ok = bad = 0
        rssi = []
        for _, r_rssi, hexs, method, rec in beacon:
            got = bytes.fromhex(hexs)
            body = got[: len(sent_pdu)] if len(got) == len(sent_pdu) + 3 else got
            rssi.append(r_rssi)
            if body == sent_pdu:
                intact += 1
                continue
            damaged += 1
            if method and method != "unrecoverable" and rec:
                fixed = bytes.fromhex(rec)
                fb = fixed[: len(sent_pdu)] if len(fixed) == len(sent_pdu) + 3 else fixed
                ok += fb == sent_pdu
                bad += fb != sent_pdu

        n = run["sent"]
        rate = 100.0 * len(beacon) / n
        rates.append(rate)
        if beacon:
            damaged_shares.append(100.0 * damaged / len(beacon))
        ambient = len(window) - len(beacon)

        # A window with no ambient traffic at all is a window in which the
        # sniffer was not recording; it must not be averaged in as a zero.
        flag = "  <-- no ambient: capture may have been down" if ambient == 0 else ""
        rows.append(flag)
        print(f"{run['label']:<8} {n:>6} {ambient:>8} {len(beacon):>6} {rate:>7.1f} "
              f"{intact:>7} {damaged:>5} {ok:>4} {bad:>6} "
              f"{(mean(rssi) if rssi else 0):>6.1f}{flag}")

    k = len(rates)
    m = mean(rates)
    s = stdev(rates)
    t = T95.get(k - 1, 1.96)
    between = t * s / sqrt(k)

    total_sent = sum(r["sent"] for r in blob["runs"])
    p = m / 100.0
    binomial = 1.96 * sqrt(p * (1 - p) / total_sent) * 100.0

    print("-" * 72)
    print(f"runs                       {k}")
    print(f"frames transmitted         {total_sent}")
    print(f"reception rate, mean       {m:.2f} %")
    print(f"  standard deviation       {s:.2f} pp")
    print(f"  95% CI, between-run      +/- {between:.2f} pp   "
          f"[{m - between:.2f}, {m + between:.2f}]")
    print(f"  95% CI, binomial         +/- {binomial:.2f} pp   "
          f"(assumes a stationary channel)")
    if damaged_shares:
        dm, ds = mean(damaged_shares), stdev(damaged_shares)
        dci = T95.get(len(damaged_shares) - 1, 1.96) * ds / sqrt(len(damaged_shares))
        print(f"damaged share of received  {dm:.1f} % +/- {dci:.1f} pp")
    return 0


if __name__ == "__main__":
    sys.exit(main())
