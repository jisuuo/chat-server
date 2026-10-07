#!/usr/bin/env python3
"""Build one isolated chat-bench dataset; never touches infra/compose.db.yml."""

import argparse
import json
import os
import subprocess
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COMPOSE = ROOT / "infra" / "compose.bench.yml"


def command(argv, env=None, input_file=None, capture=False, cwd=ROOT):
    started = time.monotonic()
    with open(input_file, "rb") if input_file else open(os.devnull, "rb") as source:
        result = subprocess.run(argv, cwd=cwd, env=env, stdin=source,
                                stdout=subprocess.PIPE if capture else None,
                                stderr=subprocess.PIPE if capture else None, check=True)
    return time.monotonic() - started, result.stdout.decode() if capture else ""


def ready():
    for _ in range(90):
        try:
            with urllib.request.urlopen("http://localhost:18080/actuator/health", timeout=2) as response:
                if response.status == 200 and json.load(response).get("status") == "UP":
                    return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError("bench app did not become healthy; inspect chat-bench app logs")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", choices=("mysql", "postgres"), required=True)
    parser.add_argument("--schema", choices=("A", "B"), required=True)
    parser.add_argument("--size", type=int, choices=(500000, 5000000), required=True)
    parser.add_argument("--member-index", action="store_true")
    parser.add_argument("--fresh", action="store_true", required=True,
                        help="remove only the chat-bench project's volumes before loading")
    args = parser.parse_args()
    if args.db == "postgres" and args.schema == "B":
        parser.error("PostgreSQL B is outside the comparison matrix")
    env = os.environ.copy()
    env.update({
        "BENCH_DB": args.db,
        "BENCH_SCHEMA": args.schema,
        "BENCH_JDBC_URL": f"jdbc:{'mysql' if args.db == 'mysql' else 'postgresql'}://{args.db}:"
                          f"{'3306' if args.db == 'mysql' else '5432'}/chat",
    })
    compose_base = ["docker", "compose", "-f", str(COMPOSE)]
    compose = compose_base + ["--profile", args.db]
    if not (ROOT / "load" / "artifacts" / "chat-bench.jar").is_file():
        raise RuntimeError("frozen bench jar missing: load/artifacts/chat-bench.jar")
    command(compose_base + ["--profile", "mysql", "--profile", "postgres",
                            "down", "-v", "--remove-orphans"], env=env)
    command(compose + ["up", "-d", "--wait", args.db], env=env)
    command(compose + ["up", "-d", "app"], env=env)
    ready()
    command(compose + ["stop", "app"], env=env)

    if args.db == "mysql":
        client = compose + ["exec", "-T", "mysql", "mysql", "--default-character-set=utf8mb4",
                            "-N", "-uchat", "-pchat", "chat"]
        base = ROOT / "db" / "bulk" / "mysql" / "base.sql"
        messages = ROOT / "db" / "bulk" / "mysql" / f"messages_{args.schema.lower()}.sql"
        base_sec, _ = command(client, env=env, input_file=base)
        message_sec, _ = command(client[:-1] + [f"--init-command=SET @messages={args.size}", "chat"],
                                 env=env, input_file=messages)
        _, raw_check = command(client, env=env, input_file=ROOT / "db" / "queries" / "mysql" / "check_invariants.sql", capture=True)
        _, count = command(client + ["-e", f"SELECT COUNT(*) FROM {'messages' if args.schema == 'A' else 'messages_b'}"], env=env, capture=True)
    else:
        client = compose + ["exec", "-T", "postgres", "psql", "-U", "chat", "-d", "chat", "-At", "-v", "ON_ERROR_STOP=1"]
        base = ROOT / "db" / "bulk" / "postgresql" / "base.sql"
        messages = ROOT / "db" / "bulk" / "postgresql" / f"messages_{args.schema.lower()}.sql"
        base_sec, _ = command(client, env=env, input_file=base)
        if args.member_index:
            command(client + ["-c", "CREATE INDEX idx_room_members_user_id ON room_members(user_id)"], env=env)
        message_sec, _ = command(client + ["-v", f"messages={args.size}"], env=env, input_file=messages)
        _, raw_check = command(client, env=env, input_file=ROOT / "db" / "queries" / "postgresql" / "check_invariants.sql", capture=True)
        _, count = command(client + ["-c", f"SELECT COUNT(*) FROM {'messages' if args.schema == 'A' else 'messages_b'}"], env=env, capture=True)

    violations = {}
    for line in raw_check.splitlines():
        parts = line.split("\t") if args.db == "mysql" else line.split("|")
        if len(parts) == 2 and parts[0].startswith("I") and parts[0][1:].isdigit():
            violations[parts[0]] = int(parts[1])
    if len(violations) != 7 or any(violations.values()):
        raise RuntimeError(f"invariants failed: {violations}")
    total = int(count.strip().splitlines()[-1])
    if total != args.size:
        raise RuntimeError(f"expected {args.size} messages, found {total}")
    command(compose + ["up", "-d", "app"], env=env)
    ready()
    manifest = {
        "db": args.db, "schema": args.schema, "messages": total,
        "member_index": args.member_index, "base_seconds": base_sec,
        "messages_seconds": message_sec, "invariants": violations,
    }
    path = ROOT / "load" / "results" / "prepared.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2), flush=True)


if __name__ == "__main__":
    main()
