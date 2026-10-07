#!/usr/bin/env python3
"""Record the message table and index footprint for a prepared bench DB."""

import argparse
import json
from pathlib import Path

from sql_probe import query

ROOT = Path(__file__).resolve().parent / "results"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", choices=("mysql", "postgres"), required=True)
    parser.add_argument("--schema", choices=("A", "B"), required=True)
    parser.add_argument("--label", required=True)
    args = parser.parse_args()
    table = "messages" if args.schema == "A" else "messages_b"
    if args.db == "mysql":
        sql = ("SELECT JSON_OBJECT('data_bytes',DATA_LENGTH,'index_bytes',INDEX_LENGTH,"
               "'total_bytes',DATA_LENGTH+INDEX_LENGTH) FROM information_schema.TABLES "
               f"WHERE TABLE_SCHEMA='chat' AND TABLE_NAME='{table}'")
    else:
        sql = ("SELECT json_build_object('data_bytes',pg_relation_size('" + table + "'),"
               "'index_bytes',pg_indexes_size('" + table + "'),"
               "'total_bytes',pg_total_relation_size('" + table + "'))")
    result = {"db": args.db, "schema": args.schema, "table": table,
              "bytes": json.loads(query(args.db, sql))}
    ROOT.mkdir(exist_ok=True)
    (ROOT / f"official-{args.label}-size.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2), flush=True)


if __name__ == "__main__":
    main()
