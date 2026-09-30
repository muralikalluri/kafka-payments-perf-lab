#!/usr/bin/env python3
"""Stores per-stage metric snapshots with a benchmark result: results/<run>/metrics.json.

Queries Prometheus over the run window (consumer lag, JVM heap and GC, process CPU, threads, connection
pools, Postgres commits/locks/deadlocks, outbox backlogs) and summarises the per-container CPU and memory
sampled by run-benchmark.sh (raw/docker-stats.jsonl). Series are downsampled so the file stays small.
These are observations to explain a result, not inputs to the pass/fail checks.

Usage: capture_metrics.py --run-dir DIR --start EPOCH --end EPOCH [--prometheus URL]
"""
import argparse
import json
import math
import os
import re
import sys
import urllib.parse
import urllib.request

QUERIES = {
    "consumer_lag": ("sum by (consumergroup) (kafka_consumergroup_lag)", "consumergroup"),
    "jvm_heap_used_bytes": ('sum by (application) (jvm_memory_used_bytes{area="heap"})', "application"),
    "process_cpu_usage": ("process_cpu_usage", "application"),
    "jvm_threads_live": ("jvm_threads_live_threads", "application"),
    "gc_pause_seconds_per_second": ("sum by (application) (rate(jvm_gc_pause_seconds_sum[30s]))", "application"),
    "hikari_active": ("hikaricp_connections_active", "application"),
    "hikari_pending": ("hikaricp_connections_pending", "application"),
    "pg_commits_per_second": ('sum(rate(pg_stat_database_xact_commit{datname="payments"}[30s]))', None),
    "pg_locks": ("sum(pg_locks_count)", None),
    "pg_deadlocks_per_second": ('sum(rate(pg_stat_database_deadlocks{datname="payments"}[30s]))', None),
    "ledger_outbox_backlog": ("ledger_outbox_backlog", None),
    "gateway_outbox_backlog": ("gateway_outbox_backlog", None),
}
MAX_POINTS = 120
COMPOSE_PREFIX = "kafka-payments-perf-lab-"


def query_range(base, expr, start, end, step):
    qs = urllib.parse.urlencode({"query": expr, "start": start, "end": end, "step": step})
    with urllib.request.urlopen(f"{base}/api/v1/query_range?{qs}", timeout=30) as resp:
        return json.load(resp)["data"]["result"]


def summarise(values):
    nums = [float(v) for _, v in values if v not in ("NaN", "+Inf", "-Inf")]
    if not nums:
        return None
    stride = max(1, math.ceil(len(values) / MAX_POINTS))
    points = [[round(float(t), 0), round(float(v), 4)] for i, (t, v) in enumerate(values)
              if i % stride == 0 and v not in ("NaN", "+Inf", "-Inf")]
    return {"avg": round(sum(nums) / len(nums), 4), "max": round(max(nums), 4), "min": round(min(nums), 4),
            "last": round(nums[-1], 4), "points": points}


def container_stats(path):
    """Average and peak CPU % and memory per container from `docker stats --format json` samples."""
    if not os.path.exists(path):
        return {}
    acc = {}
    units = {"B": 1, "KiB": 1024, "MiB": 1024 ** 2, "GiB": 1024 ** 3, "kB": 1000, "MB": 1000 ** 2, "GB": 1000 ** 3}
    with open(path) as fh:
        for line in fh:
            try:
                row = json.loads(line)
            except ValueError:
                continue
            raw_name = row.get("Name", "")
            if not raw_name.startswith(COMPOSE_PREFIX):
                continue  # never record containers that are not part of this lab
            name = re.sub(rf"^{COMPOSE_PREFIX}|-\d+$", "", raw_name)
            cpu = float(row["CPUPerc"].rstrip("%") or 0)
            m = re.match(r"([\d.]+)\s*([A-Za-z]+)", row.get("MemUsage", ""))
            mem = float(m.group(1)) * units.get(m.group(2), 1) if m else 0
            acc.setdefault(name, []).append((cpu, mem))
    return {name: {"samples": len(v), "cpu_percent_avg": round(sum(c for c, _ in v) / len(v), 1),
                   "cpu_percent_max": round(max(c for c, _ in v), 1),
                   "memory_bytes_max": int(max(m for _, m in v))} for name, v in acc.items()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run-dir", required=True)
    ap.add_argument("--start", type=float, required=True)
    ap.add_argument("--end", type=float, required=True)
    ap.add_argument("--prometheus", default="http://localhost:9090")
    a = ap.parse_args()

    duration = max(1.0, a.end - a.start)
    step = max(5, math.ceil(duration / 240))
    out = {"window": {"start": round(a.start), "end": round(a.end), "step_seconds": step},
           "note": "Observations captured from Prometheus and docker stats during the run; not used for pass/fail.",
           "series": {}, "containers": container_stats(os.path.join(a.run_dir, "raw", "docker-stats.jsonl"))}
    for name, (expr, label) in QUERIES.items():
        try:
            result = query_range(a.prometheus, expr, a.start, a.end, step)
        except Exception as exc:  # Prometheus unreachable: record that instead of failing the run
            out["series"][name] = {"error": str(exc)}
            continue
        series = {}
        for r in result:
            key = r["metric"].get(label, "all") if label else "all"
            s = summarise(r["values"])
            if s:
                series[key] = s
        if series:
            out["series"][name] = series
    with open(os.path.join(a.run_dir, "metrics.json"), "w") as fh:
        json.dump(out, fh, indent=1)
        fh.write("\n")
    print(f"wrote {os.path.join(a.run_dir, 'metrics.json')} ({len(out['series'])} metrics, "
          f"{len(out['containers'])} containers)")


if __name__ == "__main__":
    sys.exit(main())
