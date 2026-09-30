#!/usr/bin/env python3
"""Builds results/<run>/result.json from the raw k6 summary and the pipeline's database.

Every number in docs and reports is generated from these result.json files (never typed by hand).
Latency percentiles for the pipeline come from the database (updated_at - created_at of the
gateway row), see docs/adr/0003-metrics-semantics.md.
"""
import argparse
import json
import os
import subprocess
import sys

COMPOSE = ["docker", "compose", "-f", "docker-compose.yml", "-f", "docker-compose.resources.yml"]
SLO_P99_MS = 500
SLO_ERROR_RATE = 0.001
SLO_RECOVERY_SECONDS = 120
DEMO_TOTAL_BALANCE_MINOR = 1000 * 100_000_000


def psql(sql):
    out = subprocess.run(
        COMPOSE + ["exec", "-T", "postgres", "psql", "-U", "payments", "-d", "payments", "-At", "-F", "|", "-c", sql],
        check=True, capture_output=True, text=True).stdout.strip()
    return [row.split("|") for row in out.splitlines()] if out else []


def one(sql):
    rows = psql(sql)
    return rows[0] if rows else []


def num(v):
    return None if v in ("", None) else float(v)


def e2e_stats(where="true"):
    row = one(f"""
        select count(*),
               percentile_cont(0.5)  within group (order by ms),
               percentile_cont(0.95) within group (order by ms),
               percentile_cont(0.99) within group (order by ms),
               max(ms)
        from (select extract(epoch from (updated_at - created_at)) * 1000 as ms
              from gateway.payments where status_rank = 3 and {where}) t""")
    n = int(row[0])
    return {"count": n, "p50": num(row[1]), "p95": num(row[2]), "p99": num(row[3]), "max": num(row[4])}


def k6_metric(summary, name):
    m = summary["metrics"].get(name)
    return m["values"] if m else None


def round_or_none(v, digits=2):
    return None if v is None else round(v, digits)


def k6_section(summary):
    dur = k6_metric(summary, "http_req_duration")
    failed = k6_metric(summary, "http_req_failed")
    reqs = k6_metric(summary, "http_reqs")
    dropped = k6_metric(summary, "dropped_iterations")
    checks = k6_metric(summary, "checks")
    return {
        "http_reqs": reqs["count"], "req_per_s": round_or_none(reqs["rate"]),
        "http_req_failed_rate": failed["rate"],
        "post_latency_ms": {"p50": round_or_none(dur["med"]), "p95": round_or_none(dur["p(95)"]),
                            "p99": round_or_none(dur["p(99)"]), "max": round_or_none(dur["max"])},
        "dropped_iterations": dropped["count"] if dropped else 0,
        "checks_pass_rate": checks["rate"] if checks else None,
    }


def phase_rows(summary, phases, t0):
    """phases: list of (name, target_rate, start_offset_s, duration_s)."""
    rows = []
    for name, rate, start, dur in phases:
        d = k6_metric(summary, f"http_req_duration{{scenario:{name}}}")
        f = k6_metric(summary, f"http_req_failed{{scenario:{name}}}")
        r = k6_metric(summary, f"http_reqs{{scenario:{name}}}")
        x = k6_metric(summary, f"dropped_iterations{{scenario:{name}}}")
        lo, hi = t0 + start, t0 + start + dur
        e2e = e2e_stats(f"created_at >= to_timestamp({lo}) and created_at < to_timestamp({hi})")
        row = {
            "phase": name, "target_req_per_s": rate,
            "achieved_req_per_s": round_or_none(r["count"] / dur) if r else None,  # count over the phase, not k6's whole-run rate
            "post_p99_ms": round_or_none(d["p(99)"]) if d else None,
            "http_req_failed_rate": f["rate"] if f else None,
            "dropped_iterations": x["count"] if x else 0,
            "e2e_ms": {k: round_or_none(v) if k != "count" else v for k, v in e2e.items()},
        }
        row["meets_slo"] = bool(
            row["post_p99_ms"] is not None and row["post_p99_ms"] < SLO_P99_MS
            and (row["http_req_failed_rate"] or 0) < SLO_ERROR_RATE
            and row["e2e_ms"]["p99"] is not None and row["e2e_ms"]["p99"] < SLO_P99_MS
            and row["dropped_iterations"] == 0)
        rows.append(row)
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run-dir", required=True)
    ap.add_argument("--profile", required=True)
    ap.add_argument("--scenario", required=True)
    ap.add_argument("--k6-exit", type=int, required=True)
    ap.add_argument("--started-at", type=float, required=True)
    ap.add_argument("--finished-at", type=float, required=True)
    a = ap.parse_args()

    with open(os.path.join(a.run_dir, "summary.json")) as fh:
        summary = json.load(fh)

    counts = one("""select count(*), count(*) filter (where status_rank = 3),
                    count(*) filter (where status = 'POSTED'), count(*) filter (where status = 'REJECTED')
                    from gateway.payments""")
    created, terminal, posted, rejected = (int(x) for x in counts)
    t0, tmax = (float(x) for x in one(
        "select extract(epoch from min(created_at)), extract(epoch from max(updated_at)) from gateway.payments"))
    reasons = {r[0]: int(r[1]) for r in psql(
        "select reason_code, count(*) from ledger.ledger_payments where outcome = 'REJECTED' group by 1 order by 1")}

    params = {}
    if a.scenario == "steady":
        steps = [int(x) for x in os.environ.get("STEPS", "100,200,400,800").split(",")]
        secs = int(os.environ.get("STEP_SECONDS", "60"))
        params = {"steps_req_per_s": steps, "step_seconds": secs}
        phases = [(f"step_{r}", r, i * secs, secs) for i, r in enumerate(steps)]
    elif a.scenario == "spike":
        base = int(os.environ.get("BASE_RATE", "50"))
        warm = int(os.environ.get("WARM_SECONDS", "30"))
        burst = int(os.environ.get("BURST_SECONDS", "60"))
        recover = int(os.environ.get("RECOVER_SECONDS", "120"))
        params = {"base_req_per_s": base, "burst_req_per_s": base * 10, "warm_seconds": warm,
                  "burst_seconds": burst, "recover_seconds": recover}
        phases = [("warm", base, 0, warm), ("burst", base * 10, warm, burst),
                  ("recover", base, warm + burst, recover)]
    else:
        params = {"vus": 10, "duration_seconds": 60}
        phases = []

    invariants = {
        "negative_balances": int(one("select count(*) from ledger.accounts where balance_minor < 0")[0]),
        "debits_minus_credits_minor": int(one(
            "select coalesce(sum(case direction when 'D' then amount_minor else -amount_minor end), 0) from ledger.postings")[0]),
        "demo_total_balance_minor": int(one("select sum(balance_minor) from ledger.accounts where id like 'ACC-%'")[0]),
        "pending_rows": int(one("select count(*) from ledger.pending_payments")[0]),
        "outbox_rows": int(one("select count(*) from ledger.outbox")[0]),
        "non_terminal_payments": created - terminal,
        "ledger_payments_minus_gateway_payments": int(one("select count(*) from ledger.ledger_payments")[0]) - created,
        "sequence_conflicts": reasons.get("SEQUENCE_CONFLICT", 0),
    }
    invariants["all_hold"] = (
        invariants["negative_balances"] == 0 and invariants["debits_minus_credits_minor"] == 0
        and invariants["demo_total_balance_minor"] == DEMO_TOTAL_BALANCE_MINOR
        and invariants["pending_rows"] == 0 and invariants["outbox_rows"] == 0
        and invariants["non_terminal_payments"] == 0
        and invariants["ledger_payments_minus_gateway_payments"] == 0)

    overall = e2e_stats()
    result = {
        "schema_version": 1,
        "profile": a.profile, "scenario": a.scenario,
        "git_sha": subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip(),
        "params": params,
        "k6": k6_section(summary),
        "k6_thresholds_passed": a.k6_exit == 0,
        "pipeline": {
            "payments_created": created, "terminal": terminal, "posted": posted, "rejected": rejected,
            "rejected_by_reason": reasons,
            "e2e_ms": {k: round_or_none(v) if k != "count" else v for k, v in overall.items()},
        },
        "phases": phase_rows(summary, phases, t0) if phases else [],
        "invariants": invariants,
    }

    slo = {
        "p99_e2e_under_500ms": overall["p99"] is not None and overall["p99"] < SLO_P99_MS,
        "error_rate_under_0_1pct": result["k6"]["http_req_failed_rate"] < SLO_ERROR_RATE,
    }
    if a.scenario == "steady":
        ok = [p for p in result["phases"] if p["meets_slo"]]
        result["max_sustainable_req_per_s"] = max((p["achieved_req_per_s"] for p in ok), default=None)
    if a.scenario == "spike":
        burst_end = t0 + params["warm_seconds"] + params["burst_seconds"]
        run_end = burst_end + params["recover_seconds"]
        # Recovery = how long after the burst payments were still completing slower than the SLO.
        last_slow = one(f"""
            select extract(epoch from max(created_at)) from gateway.payments
            where status_rank = 3 and created_at >= to_timestamp({burst_end})
              and extract(epoch from (updated_at - created_at)) * 1000 > {SLO_P99_MS}""")
        last_slow = float(last_slow[0]) if last_slow and last_slow[0] else None
        recovery = 0.0 if last_slow is None else max(0.0, last_slow - burst_end)
        recovered = last_slow is None or last_slow < run_end - 5
        result["spike"] = {
            "recovery_seconds_after_burst": round(recovery, 1),
            "recovered_within_run": recovered,
            "recovery_definition": "seconds after the burst ends until payments created afterwards "
                                   "complete under the 500 ms SLO again (last slow payment's creation time)",
        }
        slo["lag_recovers_within_2min"] = recovered and recovery <= SLO_RECOVERY_SECONDS
    result["slo"] = slo

    with open(os.path.join(a.run_dir, "result.json"), "w") as fh:
        json.dump(result, fh, indent=2)
        fh.write("\n")
    print(json.dumps({"invariants_hold": invariants["all_hold"], "slo": slo, "payments": created}, indent=2))
    sys.exit(0 if invariants["all_hold"] else 1)


if __name__ == "__main__":
    main()
