#!/usr/bin/env python3
"""Read DB and Hikari counters around a k6 measurement."""

import json
import os
import subprocess
import threading
import time
import urllib.request


def snapshot(db):
    if db == "mysql":
        query = """SHOW GLOBAL STATUS WHERE Variable_name IN
          ('Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests',
           'Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_deadlocks')"""
        argv = ["mysql", "-h127.0.0.1", "-P23306", "-uchat", "-pchat", "-N", "-e", query, "chat"]
        output = subprocess.check_output(argv, text=True, stderr=subprocess.DEVNULL)
        counters = {key: int(value) for key, value in (line.split("\t") for line in output.splitlines())}
    elif db == "postgres":
        query = """SELECT json_build_object(
          'blks_read',d.blks_read,'blks_hit',d.blks_hit,'deadlocks',d.deadlocks,
          'temp_bytes',d.temp_bytes,'xact_commit',d.xact_commit,
          'rooms_hot_updates',r.n_tup_hot_upd,'rooms_updates',r.n_tup_upd,
          'rooms_dead_tuples',r.n_dead_tup)
          FROM pg_stat_database d JOIN pg_stat_all_tables r ON r.relname='rooms'
          WHERE d.datname='chat'"""
        env = os.environ.copy()
        env["PGPASSWORD"] = "chat"
        output = subprocess.check_output(["psql", "-h127.0.0.1", "-p25432", "-Uchat", "-dchat", "-Atc", query],
                                         env=env, text=True)
        counters = json.loads(output)
    else:
        raise ValueError(db)
    with urllib.request.urlopen("http://localhost:18080/actuator/prometheus", timeout=10) as response:
        metrics = response.read().decode()
    hikari = {}
    for line in metrics.splitlines():
        if line.startswith("hikaricp_connections_") and not line.startswith("#"):
            key, value = line.split(" ", 1)
            hikari[key] = float(value)
    return {"captured_at_epoch": time.time(), "db": counters, "hikari": hikari}


class HikariSampler:
    """Sample gauge peaks during a run; before/after snapshots miss brief queueing."""

    def __init__(self):
        self.stop = threading.Event()
        self.peak = {"active": 0.0, "pending": 0.0, "acquire_seconds_max": 0.0}
        self.samples = 0
        self.failures = 0
        self.thread = threading.Thread(target=self._sample, daemon=True)

    def _sample(self):
        names = {
            "hikaricp_connections_active": "active",
            "hikaricp_connections_pending": "pending",
            "hikaricp_connections_acquire_seconds_max": "acquire_seconds_max",
        }
        while not self.stop.is_set():
            try:
                with urllib.request.urlopen("http://localhost:18080/actuator/prometheus", timeout=5) as response:
                    metrics = response.read().decode()
                for line in metrics.splitlines():
                    name = line.split("{", 1)[0]
                    if name in names and not line.startswith("#"):
                        self.peak[names[name]] = max(self.peak[names[name]], float(line.rsplit(" ", 1)[1]))
                self.samples += 1
            except (OSError, ValueError):
                self.failures += 1
            self.stop.wait(1)

    def start(self):
        self.thread.start()

    def finish(self):
        self.stop.set()
        self.thread.join(timeout=10)
        return {"peak": self.peak, "samples": self.samples, "failures": self.failures}


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", choices=("mysql", "postgres"), required=True)
    args = parser.parse_args()
    print(json.dumps(snapshot(args.db), indent=2))
