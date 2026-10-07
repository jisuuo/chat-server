#!/usr/bin/env python3
"""F15/F16 SQL plans and server execution times for the current bench dataset."""

import argparse
import json
import os
import re
import statistics
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent / "results"


def query(db, sql):
    if db == "mysql":
        argv = ["mysql", "-h127.0.0.1", "-P23306", "-uchat", "-pchat", "-N", "-B", "chat", "-e", sql]
        return subprocess.check_output(argv, text=True, stderr=subprocess.DEVNULL)
    env = os.environ.copy()
    env["PGPASSWORD"] = "chat"
    argv = ["psql", "-h127.0.0.1", "-p25432", "-Uchat", "-dchat", "-Atc", sql]
    return subprocess.check_output(argv, env=env, text=True)


def explain(db, sql):
    prefix = "EXPLAIN ANALYZE " if db == "mysql" else "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "
    raw = query(db, prefix + sql)
    if db == "mysql":
        match = re.search(r"actual time=([0-9.]+)\.\.([0-9.]+)", raw)
        if not match:
            raise ValueError(raw)
        ms = float(match.group(2))
    else:
        ms = float(json.loads(raw)[0]["Execution Time"])
    return {"ms": ms, "plan": raw}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", choices=("mysql", "postgres"), required=True)
    parser.add_argument("--schema", choices=("A", "B"), required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args()
    table = "messages" if args.schema == "A" else "messages_b"
    count = int(query(args.db, f"SELECT COUNT(*) FROM {table} WHERE room_id=1").strip())
    offsets = sorted({0, 100, 1000, count // 2, max(0, count - 51)})
    output = {"db": args.db, "schema": args.schema, "label": args.label,
              "room_1_messages": count, "cases": []}
    for offset in offsets:
        boundary = query(args.db, f"SELECT id FROM {table} WHERE room_id=1 ORDER BY id DESC LIMIT 1 OFFSET {offset}").strip()
        queries = {
            "offset": f"SELECT id,room_id,sender_id,content,created_at FROM {table} WHERE room_id=1 ORDER BY id DESC LIMIT 51 OFFSET {offset}",
            "cursor": f"SELECT id,room_id,sender_id,content,created_at FROM {table} WHERE room_id=1 AND id<={int(boundary)} ORDER BY id DESC LIMIT 51",
        }
        for method, sql in queries.items():
            runs = [explain(args.db, sql) for _ in range(args.repeats)]
            output["cases"].append({"method": method, "offset": offset, "boundary": int(boundary),
                                    "median_ms": statistics.median(run["ms"] for run in runs),
                                    "runs": runs, "sql": sql})
    path = ROOT / f"sql-{args.label}.json"
    path.parent.mkdir(exist_ok=True)
    path.write_text(json.dumps(output, indent=2) + "\n")
    for case in output["cases"]:
        print(case["method"], case["offset"], f"{case['median_ms']:.3f} ms", flush=True)


if __name__ == "__main__":
    main()
