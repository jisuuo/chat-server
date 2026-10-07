#!/usr/bin/env python3
"""Run the predeclared C1 baseline matrix in isolated chat-bench volumes."""

import argparse
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CASES = (
    ("mysql", "A", 500000), ("mysql", "B", 500000),
    ("postgres", "A", 500000), ("mysql", "A", 5000000),
    ("mysql", "B", 5000000), ("postgres", "A", 5000000),
)


def run(argv):
    print("RUN", " ".join(map(str, argv)), flush=True)
    subprocess.run(argv, cwd=ROOT, check=True)


def message_count(db, schema):
    table = "messages" if schema == "A" else "messages_b"
    if db == "mysql":
        argv = ["mysql", "-h127.0.0.1", "-P23306", "-uchat", "-pchat", "-N", "chat", "-e",
                f"SELECT COUNT(*) FROM {table}"]
        output = subprocess.check_output(argv, cwd=ROOT, text=True, stderr=subprocess.DEVNULL)
    else:
        env = os.environ.copy()
        env["PGPASSWORD"] = "chat"
        argv = ["psql", "-h127.0.0.1", "-p25432", "-Uchat", "-dchat", "-Atc",
                f"SELECT COUNT(*) FROM {table}"]
        output = subprocess.check_output(argv, cwd=ROOT, env=env, text=True)
    return int(output.strip())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--start", type=int, choices=range(1, 7), default=1)
    args = parser.parse_args()
    for number, (db, schema, size) in enumerate(CASES, 1):
        if number < args.start:
            continue
        run_id = f"official-{db}-{schema.lower()}-{size // 1000}k-baseline"
        prepared = ["python3", "load/prepare.py", "--db", db, "--schema", schema,
                    "--size", str(size), "--fresh"]
        if db == "postgres":
            prepared.append("--member-index")
        run(prepared)
        run(["python3", "load/run_matrix.py", "--db", db, "--schema", schema,
             "--size", str(size), "--suite", "baseline", "--duration", "30s",
             "--warmup", "15s", "--repeats", "3", "--run-id", run_id])
        result = ROOT / "load" / "results" / run_id
        (result / "dataset_end.json").write_text(json.dumps({
            "initial_messages": size, "final_messages": message_count(db, schema)}, indent=2) + "\n")
        print("DONE", run_id, flush=True)


if __name__ == "__main__":
    main()
