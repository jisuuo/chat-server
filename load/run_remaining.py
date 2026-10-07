#!/usr/bin/env python3
"""Run the remaining isolated Plan 5b experiments in a resumable order."""

import argparse
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def run(argv):
    print("RUN", " ".join(argv), flush=True)
    subprocess.run(argv, cwd=ROOT, check=True)


def restart(db, schema):
    base = f"official-{db}-{schema.lower()}-5000k"
    prepare = ["python3", "load/prepare.py", "--db", db, "--schema", schema,
               "--size", "5000000", "--fresh"]
    if db == "postgres":
        prepare.append("--member-index")
    run(prepare)
    run(["python3", "load/restart_probe.py", "--db", db, "--schema", schema,
         "--size", "5000000", "--run-id", base + "-restart"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--start", type=int, default=1, choices=range(1, 12),
                        help="resume at a numbered phase printed below")
    args = parser.parse_args()
    phases = [
        ("F15 500K SQL", lambda: run(["python3", "load/run_sql_sizes.py"])),
        ("F19 MySQL-A", lambda: run(["python3", "load/delete_probe.py", "--db", "mysql", "--schema", "A"])),
        ("F19 MySQL-B", lambda: run(["python3", "load/delete_probe.py", "--db", "mysql", "--schema", "B"])),
        ("F19 PostgreSQL-A", lambda: run(["python3", "load/delete_probe.py", "--db", "postgres", "--schema", "A"])),
        ("F16 MySQL-A", lambda: run(["python3", "load/run_index_probe.py", "--db", "mysql"])),
        ("F16 PostgreSQL-A", lambda: run(["python3", "load/run_index_probe.py", "--db", "postgres"])),
        ("F20 PostgreSQL rooms index", lambda: run(["python3", "load/run_hot_index.py"])),
        ("C4 MySQL-A", lambda: restart("mysql", "A")),
        ("C4 MySQL-B", lambda: restart("mysql", "B")),
        ("C4 PostgreSQL-A", lambda: restart("postgres", "A")),
        ("archive", lambda: run(["python3", "load/archive_results.py"])),
    ]
    for number, (name, action) in enumerate(phases, 1):
        if number < args.start:
            continue
        print(f"PHASE {number}/{len(phases)}: {name}", flush=True)
        action()


if __name__ == "__main__":
    main()
