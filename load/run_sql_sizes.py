#!/usr/bin/env python3
"""F15: measure offset/cursor SQL at 500K on each C1 configuration."""

import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main():
    for db, schema in (("mysql", "A"), ("mysql", "B"), ("postgres", "A")):
        prepare = ["python3", "load/prepare.py", "--db", db, "--schema", schema,
                   "--size", "500000", "--fresh"]
        if db == "postgres":
            prepare.append("--member-index")
        print("RUN", " ".join(prepare), flush=True)
        subprocess.run(prepare, cwd=ROOT, check=True)
        probe = ["python3", "load/sql_probe.py", "--db", db, "--schema", schema,
                 "--label", f"official-{db}-{schema.lower()}-500k-offset-index-on"]
        print("RUN", " ".join(probe), flush=True)
        subprocess.run(probe, cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
