#!/usr/bin/env python3
"""Print medians and repeat ranges from official k6 summary files."""

import argparse
import csv
import statistics
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent / "results"
KEYS = ("run_id", "db", "schema", "size", "suite", "workload", "vus", "hot_only", "poll_seconds")
NUMERIC = ("p50_ms", "p95_ms", "p99_ms", "rps", "error_rate", "errors", "requests")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run_ids", nargs="+")
    args = parser.parse_args()
    grouped = defaultdict(list)
    for run_id in args.run_ids:
        for row in csv.DictReader((ROOT / run_id / "summary.csv").open()):
            grouped[tuple(row[k] for k in KEYS)].append(row)
    print("| " + " | ".join(KEYS) + " | p99 ms median [min,max] | RPS median [min,max] | error % median |")
    print("|" + "---|" * (len(KEYS) + 3))
    for key, rows in sorted(grouped.items()):
        if len(rows) != 3:
            raise ValueError(f"expected 3 repeats for {key}, found {len(rows)}")
        p99 = [float(row["p99_ms"]) for row in rows]
        rps = [float(row["rps"]) for row in rows]
        errors = [float(row["error_rate"]) * 100 for row in rows]
        print("| " + " | ".join(key) + " | "
              f"{statistics.median(p99):.1f} [{min(p99):.1f},{max(p99):.1f}] | "
              f"{statistics.median(rps):.0f} [{min(rps):.0f},{max(rps):.0f}] | "
              f"{statistics.median(errors):.3f} |")


if __name__ == "__main__":
    main()
