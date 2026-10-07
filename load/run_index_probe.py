#!/usr/bin/env python3
"""F16: run indexed and unindexed W2–W4 on an isolated 5M row A schema."""

import argparse
import json
import subprocess
from pathlib import Path

from sql_probe import explain, query

ROOT = Path(__file__).resolve().parent.parent


def run(argv):
    print("RUN", " ".join(argv), flush=True)
    subprocess.run(argv, cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", choices=("mysql", "postgres"), required=True)
    args = parser.parse_args()
    db = args.db
    prepare = ["python3", "load/prepare.py", "--db", db, "--schema", "A",
               "--size", "5000000", "--fresh"]
    if db == "postgres":
        prepare.append("--member-index")
    run(prepare)
    before = int(query(db, "SELECT MAX(id) FROM messages WHERE room_id=1").strip()) // 2
    after = int(query(db, "SELECT MAX(id) FROM messages WHERE room_id=1").strip()) - 10
    sql = {
        "W2": "SELECT id,room_id,sender_id,content,created_at FROM messages WHERE room_id=1 ORDER BY id DESC LIMIT 51",
        "W3": f"SELECT id,room_id,sender_id,content,created_at FROM messages WHERE room_id=1 AND id<{before} ORDER BY id DESC LIMIT 51",
        "W4": f"SELECT id,room_id,sender_id,content,created_at FROM messages WHERE room_id=1 AND id>{after} ORDER BY id ASC LIMIT 101",
    }
    base = f"official-{db}-a-5000k-index"
    common = ["python3", "load/run_matrix.py", "--db", db, "--schema", "A", "--size", "5000000",
              "--suite", "custom", "--case", "W2:50:0:0", "--case", "W3:50:0:0",
              "--case", "W4:50:0:0", "--duration", "30s", "--warmup", "15s", "--repeats", "3"]
    plans = {}
    dropped = False
    try:
        plans["on"] = {name: explain(db, statement) for name, statement in sql.items()}
        run(common + ["--run-id", base + "-on"])
        query(db, "ALTER TABLE messages DROP INDEX idx_messages_room_id_id" if db == "mysql"
              else "DROP INDEX idx_messages_room_id_id")
        dropped = True
        plans["off"] = {name: explain(db, statement) for name, statement in sql.items()}
        run(common + ["--run-id", base + "-off"])
    finally:
        if dropped:
            if db == "mysql":
                query(db, "ALTER TABLE messages ADD INDEX idx_messages_room_id_id (room_id,id)")
            else:
                query(db, "CREATE INDEX idx_messages_room_id_id ON messages (room_id,id)")
                query(db, "ANALYZE messages")
    path = ROOT / "load" / "results" / f"{base}-plans.json"
    path.write_text(json.dumps(plans, indent=2) + "\n")
    print("DONE", base, flush=True)


if __name__ == "__main__":
    main()
