#!/usr/bin/env python3
"""F20: PostgreSQL hot-room W1 with and without a rooms sort index."""

import json
import subprocess
from pathlib import Path

from sql_probe import query

ROOT = Path(__file__).resolve().parent.parent
BASE = "official-postgres-a-5000k-rooms-index"


def run(argv):
    print("RUN", " ".join(argv), flush=True)
    subprocess.run(argv, cwd=ROOT, check=True)


def state():
    sql = """SELECT json_build_object('rooms_bytes',pg_relation_size('rooms'),
      'rooms_total_bytes',pg_total_relation_size('rooms'),
      'hot_updates',n_tup_hot_upd,'updates',n_tup_upd,'dead_tuples',n_dead_tup)
      FROM pg_stat_all_tables WHERE relname='rooms'"""
    return json.loads(query("postgres", sql))


def main():
    run(["python3", "load/prepare.py", "--db", "postgres", "--schema", "A",
         "--size", "5000000", "--member-index", "--fresh"])
    common = ["python3", "load/run_matrix.py", "--db", "postgres", "--schema", "A",
              "--size", "5000000", "--suite", "custom", "--case", "W1:50:1:0",
              "--duration", "30s", "--warmup", "15s", "--repeats", "3"]
    states = {"before_off": state()}
    run(common + ["--run-id", BASE + "-off"])
    states["after_off"] = state()
    query("postgres", "CREATE INDEX idx_rooms_last_message_id ON rooms(last_message_id)")
    states["before_on"] = state()
    try:
        run(common + ["--run-id", BASE + "-on"])
        states["after_on"] = state()
    finally:
        query("postgres", "DROP INDEX idx_rooms_last_message_id")
    path = ROOT / "load" / "results" / (BASE + "-stats.json")
    path.write_text(json.dumps(states, indent=2) + "\n")
    print("DONE", BASE, flush=True)


if __name__ == "__main__":
    main()
