#!/usr/bin/env python3
"""F19: delete the last membership, room and its messages in one transaction."""

import argparse
import json
import re
import subprocess
import time
from pathlib import Path

from sql_probe import query

ROOT = Path(__file__).resolve().parent.parent


def metrics(db, table):
    if db == "mysql":
        status = subprocess.check_output(
            ["docker", "exec", "chat-bench-mysql-1", "mysql", "-uroot", "-proot",
             "-N", "-B", "-e", "SHOW ENGINE INNODB STATUS"],
            text=True, stderr=subprocess.DEVNULL)
        match = re.search(r"History list length (\d+)", status)
        return {"history_list_length": int(match.group(1)) if match else None}
    sql = """SELECT json_build_object('dead_tuples',n_dead_tup,'deleted',n_tup_del,
      'relation_bytes',pg_relation_size('messages'))
      FROM pg_stat_all_tables WHERE relname='{table}'""".format(table=table)
    return json.loads(query(db, sql))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", choices=("mysql", "postgres"), required=True)
    parser.add_argument("--schema", choices=("A", "B"), required=True)
    args = parser.parse_args()
    db, schema = args.db, args.schema
    table = "messages" if schema == "A" else "messages_b"
    prepare = ["python3", "load/prepare.py", "--db", db, "--schema", schema,
               "--size", "5000000", "--fresh"]
    if db == "postgres":
        prepare.append("--member-index")
    subprocess.run(prepare, cwd=ROOT, check=True)
    subprocess.run(["python3", "load/size_probe.py", "--db", db, "--schema", schema,
                    "--label", f"{db}-{schema.lower()}-5000k"], cwd=ROOT, check=True)
    messages = int(query(db, f"SELECT COUNT(*) FROM {table} WHERE room_id=1").strip())
    members = int(query(db, "SELECT COUNT(*) FROM room_members WHERE room_id=1").strip())
    keep = int(query(db, "SELECT MIN(user_id) FROM room_members WHERE room_id=1").strip())
    query(db, f"DELETE FROM room_members WHERE room_id=1 AND user_id<>{keep}")
    before = metrics(db, table)
    sql = (f"BEGIN; DELETE FROM room_members WHERE room_id=1 AND user_id={keep}; "
           f"DELETE FROM {table} WHERE room_id=1; DELETE FROM rooms WHERE id=1; COMMIT;")
    started = time.monotonic()
    query(db, sql)
    elapsed = time.monotonic() - started
    after = metrics(db, table)
    remaining = [int(query(db, f"SELECT COUNT(*) FROM {table} WHERE room_id=1").strip()),
                 int(query(db, "SELECT COUNT(*) FROM room_members WHERE room_id=1").strip()),
                 int(query(db, "SELECT COUNT(*) FROM rooms WHERE id=1").strip())]
    result = {"db": db, "schema": schema, "initial_messages_room_1": messages,
              "initial_members_room_1": members, "measured_last_member": keep,
              "client_transaction_seconds": elapsed, "before": before, "after": after,
              "remaining_messages_members_rooms": remaining}
    if any(remaining):
        raise RuntimeError(result)
    path = ROOT / "load" / "results" / f"official-{db}-{schema.lower()}-5000k-delete.json"
    path.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2), flush=True)


if __name__ == "__main__":
    main()
