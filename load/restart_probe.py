#!/usr/bin/env python3
"""C4: compare requests just after each DB restart with a stable run."""

import argparse
import csv
import json
import shutil
import subprocess
import time
import urllib.request
from pathlib import Path
from types import SimpleNamespace

from run_matrix import run_one, values

ROOT = Path(__file__).resolve().parent.parent


def healthy(name):
    for _ in range(120):
        result = subprocess.run(["docker", "inspect", "--format", "{{.State.Health.Status}}", name],
                                capture_output=True, text=True)
        if result.returncode == 0 and result.stdout.strip() == "healthy":
            try:
                with urllib.request.urlopen("http://localhost:18080/actuator/health", timeout=2) as response:
                    if json.load(response).get("status") == "UP":
                        return
            except (OSError, ValueError):
                pass
        time.sleep(1)
    raise RuntimeError("DB/app did not recover within 120s")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", choices=("mysql", "postgres"), required=True)
    parser.add_argument("--schema", choices=("A", "B"), required=True)
    parser.add_argument("--size", type=int, required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    name = f"chat-bench-{args.db}-1"
    run_dir = ROOT / "load" / "results" / args.run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "invocation.json").write_text(json.dumps(vars(args), indent=2) + "\n")
    prepared = ROOT / "load" / "results" / "prepared.json"
    if prepared.exists():
        shutil.copy2(prepared, run_dir / "prepared.json")
    settings = SimpleNamespace(db=args.db, schema=args.schema, size=args.size, suite="restart",
                               base_url="http://localhost:18080", hot_room=1, before_id=None)
    fields = ("db", "schema", "size", "workload", "repeat", "phase", "recovery_seconds",
              "requests", "rps", "errors", "error_rate", "p50_ms", "p95_ms", "p99_ms")
    with (run_dir / "summary.csv").open("w", newline="") as out:
        writer = csv.DictWriter(out, fieldnames=fields)
        writer.writeheader()
        for workload in ("W2", "W4"):
            for repeat in range(1, 4):
                started = time.monotonic()
                subprocess.run(["docker", "restart", name], cwd=ROOT, check=True,
                               stdout=subprocess.DEVNULL)
                healthy(name)
                recovery = time.monotonic() - started
                for phase, tag in (("after_restart", repeat), ("stable", repeat + 10)):
                    summary = run_one(settings, run_dir, (workload, 50, 0, 0), tag, "30s")
                    requests = values(summary, "http_reqs")
                    latency = values(summary, f"chat_{workload.lower()}_ms")
                    failed = values(summary, "http_req_failed")
                    errors = values(summary, "chat_errors")
                    row = {"db": args.db, "schema": args.schema, "size": args.size,
                           "workload": workload, "repeat": repeat, "phase": phase,
                           "recovery_seconds": recovery, "requests": requests.get("count", 0),
                           "rps": requests.get("rate", 0), "errors": errors.get("count", 0),
                           "error_rate": failed.get("rate", 0), "p50_ms": latency.get("med", 0),
                           "p95_ms": latency.get("p(95)", 0), "p99_ms": latency.get("p(99)", 0)}
                    writer.writerow(row)
                    out.flush()
                    print(row, flush=True)


if __name__ == "__main__":
    main()
