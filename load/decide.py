#!/usr/bin/env python3
"""Apply predeclared ADR-014/101/104 numeric gates to official CSVs."""

import csv
import json
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parent / "results"


def rows(run_id, workload, vus=50):
    path = ROOT / run_id / "summary.csv"
    data = [row for row in csv.DictReader(path.open())
            if row["workload"] == workload and int(row["vus"]) == vus]
    if len(data) != 3:
        raise ValueError(f"expected three runs: {run_id}/{workload}/{vus}, found {len(data)}")
    return data


def median(data, key):
    return statistics.median(float(row[key]) for row in data)


def main():
    prefix = "official-mysql-"
    a = {workload: rows(prefix + "a-5000k-baseline", workload)
         for workload in ("W1", "W2", "W3", "W4", "W5")}
    b = {workload: rows(prefix + "b-5000k-baseline", workload)
         for workload in ("W1", "W2", "W3", "W4", "W5")}
    read_ratios = {workload: median(b[workload], "p99_ms") / median(a[workload], "p99_ms")
                   for workload in ("W2", "W3", "W4")}
    write_ratio = median(b["W1"], "p99_ms") / median(a["W1"], "p99_ms")
    adopt_b = all(value <= 0.7 for value in read_ratios.values()) and write_ratio <= 1.1
    mysql_schema = "b" if adopt_b else "a"
    comparison = {}
    for db, schema in (("mysql", mysql_schema), ("postgres", "a")):
        baseline = rows(f"official-{db}-{schema}-5000k-baseline", "W5")
        concurrency = rows(f"official-{db}-{schema}-5000k-concurrency", "W5", 200)
        comparison[db] = {
            "schema": schema.upper(),
            "w5_p99_ms": median(baseline, "p99_ms"),
            "w5_p99_range_ms": [min(float(row["p99_ms"]) for row in baseline),
                                max(float(row["p99_ms"]) for row in baseline)],
            "w5_rps": median(baseline, "rps"),
            "w5_error_rate": median(baseline, "error_rate"),
            "vu200_error_rate": median(concurrency, "error_rate"),
        }
    candidates = []
    for db, other in (("mysql", "postgres"), ("postgres", "mysql")):
        me, them = comparison[db], comparison[other]
        if (me["w5_p99_ms"] <= 0.8 * them["w5_p99_ms"]
                and me["w5_rps"] >= 0.9 * them["w5_rps"]
                and me["w5_error_rate"] <= 0.001
                and me["w5_error_rate"] <= them["w5_error_rate"]
                and me["vu200_error_rate"] <= 0.01):
            candidates.append(db)
    result = {"mysql_b_read_p99_ratios": read_ratios, "mysql_b_write_p99_ratio": write_ratio,
              "mysql_b_adopt": adopt_b, "db_comparison": comparison,
              "numeric_db_candidates": candidates,
              "note": "ADR-101 also defers selection if repeat ranges overlap greatly; inspect ranges manually."}
    path = ROOT / "official-decision.json"
    path.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
