#!/usr/bin/env python3
"""Run C2/C3/F1 at 5M rows after the official C1 baselines."""

import argparse
import json
import subprocess
from pathlib import Path
from run_baselines import message_count

ROOT = Path(__file__).resolve().parent.parent
CASES = (("mysql", "A"), ("mysql", "B"), ("postgres", "A"))


def run(argv):
    print("RUN", " ".join(argv), flush=True)
    subprocess.run(argv, cwd=ROOT, check=True)


def count_file(run_id, name, db, schema):
    path = ROOT / "load" / "results" / run_id
    path.mkdir(parents=True, exist_ok=True)
    (path / name).write_text(json.dumps({"messages": message_count(db, schema)}, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--start", type=int, choices=range(1, 4), default=1)
    args = parser.parse_args()
    for number, (db, schema) in enumerate(CASES, 1):
        if number < args.start:
            continue
        base = f"official-{db}-{schema.lower()}-5000k"
        prepare = ["python3", "load/prepare.py", "--db", db, "--schema", schema,
                   "--size", "5000000", "--fresh"]
        if db == "postgres":
            prepare.append("--member-index")
        run(prepare)
        run(["python3", "load/sql_probe.py", "--db", db, "--schema", schema,
             "--label", f"{base}-offset-index-on"])
        common = ["python3", "load/run_matrix.py", "--db", db, "--schema", schema,
                  "--size", "5000000", "--duration", "30s", "--warmup", "15s",
                  "--repeats", "3"]
        for suffix, options in (
            ("concurrency", ["--suite", "custom", "--case", "W5:10:0:0", "--case", "W5:200:0:0"]),
            ("hot", ["--suite", "hot"]),
            ("polling", ["--suite", "polling"]),
        ):
            run_id = base + "-" + suffix
            count_file(run_id, "dataset_start.json", db, schema)
            run(common + options + ["--run-id", run_id])
            count_file(run_id, "dataset_end.json", db, schema)
        print("DONE", base, flush=True)


if __name__ == "__main__":
    main()
