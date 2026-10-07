#!/usr/bin/env python3
"""Run one prepared benchmark database without changing its schema or data setup."""

import argparse
import csv
import json
import os
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from capture import HikariSampler, snapshot

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "load" / "benchmark.js"
FIELDS = (
    "run_id", "db", "schema", "size", "suite", "workload", "vus", "hot_only",
    "poll_seconds", "repeat", "requests", "rps", "errors", "error_rate",
    "p50_ms", "p95_ms", "p99_ms",
)


def scenarios(suite):
    if suite == "baseline":
        return [(w, 50, 0, 0) for w in ("W2", "W3", "W4", "W5", "W1")]
    if suite == "concurrency":
        return [("W5", vus, 0, 0) for vus in (10, 50, 200)]
    if suite == "hot":
        return [("W1", 50, hot, 0) for hot in (0, 1)]
    if suite == "polling":
        return [("W4", vus, 0, period) for vus in (10, 50, 200)
                for period in (0.5, 1, 2, 5, 0)]
    raise ValueError(suite)


def values(summary, metric):
    return summary.get("metrics", {}).get(metric, {}).get("values", {})


def run_one(args, run_dir, scenario, repeat, duration, warmup=False):
    workload, vus, hot_only, period = scenario
    tag = f"{args.suite}-{workload}-u{vus}-h{hot_only}-p{period}-r{repeat}"
    tag += "-warmup" if warmup else ""
    summary = run_dir / f"{tag}.json"
    log = run_dir / f"{tag}.log"
    env = os.environ.copy()
    env.update({
        "BASE_URL": args.base_url,
        "WORKLOAD": workload,
        "VUS": str(vus),
        "DURATION": duration,
        "HOT_ONLY": str(hot_only),
        "HOT_ROOM": str(args.hot_room),
        "BEFORE_ID": str(args.before_id or args.size // 10),
        "POLL_SECONDS": str(period),
        "SUMMARY_PATH": str(summary),
    })
    with log.open("w") as out:
        before = snapshot(args.db) if not warmup else None
        sampler = HikariSampler() if not warmup else None
        if sampler:
            sampler.start()
        completed = subprocess.run(["k6", "run", str(SCRIPT)], cwd=ROOT, env=env,
                                   stdout=out, stderr=subprocess.STDOUT, check=False)
        peaks = sampler.finish() if sampler else None
        after = snapshot(args.db) if not warmup else None
    if before is not None:
        (run_dir / f"{tag}-db-before.json").write_text(json.dumps(before, indent=2))
        (run_dir / f"{tag}-db-after.json").write_text(json.dumps(after, indent=2))
        (run_dir / f"{tag}-hikari-peaks.json").write_text(json.dumps(peaks, indent=2))
    if completed.returncode or not summary.exists():
        raise RuntimeError(f"k6 failed: {log}")
    return json.loads(summary.read_text())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", choices=("mysql", "postgres"), required=True)
    parser.add_argument("--schema", choices=("A", "B"), required=True)
    parser.add_argument("--size", type=int, choices=(500000, 5000000), required=True)
    parser.add_argument("--suite", choices=("baseline", "concurrency", "hot", "polling", "custom"), required=True)
    parser.add_argument("--case", action="append", default=[],
                        help="custom suite: WORKLOAD:VU:HOT_ONLY:POLL_SECONDS; repeat for each case")
    parser.add_argument("--duration", default="30s")
    parser.add_argument("--warmup", default="10s")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--base-url", default="http://localhost:18080")
    parser.add_argument("--hot-room", type=int, default=1)
    parser.add_argument("--before-id", type=int)
    parser.add_argument("--run-id", default=datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
    args = parser.parse_args()
    cases = scenarios(args.suite) if args.suite != "custom" else [
        (parts[0], int(parts[1]), int(parts[2]), float(parts[3]))
        for parts in (case.split(":") for case in args.case)
    ]
    if not cases:
        parser.error("custom suite requires at least one --case")
    run_dir = ROOT / "load" / "results" / args.run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    prepared = ROOT / "load" / "results" / "prepared.json"
    if prepared.exists():
        manifest = json.loads(prepared.read_text())
        if (manifest["db"], manifest["schema"], manifest["messages"]) != (args.db, args.schema, args.size):
            parser.error("prepared dataset does not match --db, --schema, --size")
        shutil.copy2(prepared, run_dir / "prepared.json")
    (run_dir / "invocation.json").write_text(json.dumps(vars(args), indent=2) + "\n")
    csv_file = run_dir / "summary.csv"
    with csv_file.open("w", newline="") as out:
        writer = csv.DictWriter(out, fieldnames=FIELDS)
        writer.writeheader()
        for scenario in cases:
            run_one(args, run_dir, scenario, 0, args.warmup, warmup=True)
            for repeat in range(1, args.repeats + 1):
                summary = run_one(args, run_dir, scenario, repeat, args.duration)
                requests = values(summary, "http_reqs")
                latency = values(summary, "http_req_duration" if scenario[0] == "W5"
                                 else f"chat_{scenario[0].lower()}_ms")
                failed = values(summary, "http_req_failed")
                custom_errors = values(summary, "chat_errors")
                row = dict(zip(("workload", "vus", "hot_only", "poll_seconds"), scenario))
                row.update({
                    "run_id": args.run_id, "db": args.db, "schema": args.schema,
                    "size": args.size, "suite": args.suite, "repeat": repeat,
                    "requests": requests.get("count", 0), "rps": requests.get("rate", 0),
                    "errors": custom_errors.get("count", 0),
                    "error_rate": failed.get("rate", 0),
                    "p50_ms": latency.get("med", 0), "p95_ms": latency.get("p(95)", 0),
                    "p99_ms": latency.get("p(99)", 0),
                })
                writer.writerow(row)
                out.flush()
                print(row, flush=True)


if __name__ == "__main__":
    main()
