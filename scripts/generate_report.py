#!/usr/bin/env python3
"""Generates the sample deliverables from templates plus results/*.

  sample-deliverable/src/<name>.tmpl  ->  sample-deliverable/<name>

Templates hold prose only. Every number, table and comparison arrives through a {{placeholder}} that is
computed from results/*/result.json, env.txt, explain.txt or the service config files. The companion
lint (lint_report_numbers.py) fails if a template contains a bare number.

Usage: generate_report.py            write the reports
       generate_report.py --check    exit 1 if the committed reports differ from what would be generated
"""
from __future__ import annotations

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(__file__))
import report_lib as L  # noqa: E402
from collect_results import SLO_ERROR_RATE, SLO_P99_MS, SLO_RECOVERY_SECONDS  # noqa: E402

SRC = os.path.join(L.ROOT, "sample-deliverable", "src")
OUT = os.path.join(L.ROOT, "sample-deliverable")
TEMPLATES = ["AUDIT_REPORT_SAMPLE.md", "QUICK_AUDIT_ledger-service.md"]
QUADRANT_X = {"S": 0.2, "M": 0.5, "L": 0.8}


def read_yml(service: str, kind: str) -> str:
    path = os.path.join(L.ROOT, "services", service, "src", "main", "resources", f"{service}-{kind}.yml")
    with open(path) as fh:
        return fh.read()


def yml_int(text: str, key: str):
    m = re.search(rf"^\s*{re.escape(key)}:\s*(\d+)\s*(?:#.*)?$", text, re.M)
    return int(m.group(1)) if m else None


def cfg_facts() -> dict:
    return {
        "baseline_concurrency": yml_int(read_yml("ledger-service", "baseline"), "concurrency"),
        "baseline_partitions": yml_int(read_yml("ledger-service", "baseline"), "partitions"),
        "tuned_concurrency": yml_int(read_yml("ledger-service", "tuned"), "concurrency"),
        "tuned_partitions": yml_int(read_yml("ledger-service", "tuned"), "partitions"),
        "tuned_pool_gateway": yml_int(read_yml("payment-gateway", "tuned"), "maximum-pool-size"),
        "tuned_pool_validation": yml_int(read_yml("validation-service", "tuned"), "maximum-pool-size"),
        "tuned_pool_ledger": yml_int(read_yml("ledger-service", "tuned"), "maximum-pool-size"),
        "tuned_linger_ms": yml_int(read_yml("payment-gateway", "tuned"), "linger.ms"),
        "tuned_batch_size": yml_int(read_yml("payment-gateway", "tuned"), "batch-size"),
        "tuned_max_poll_records": yml_int(read_yml("ledger-service", "tuned"), "max-poll-records"),
        "cache_ttl_seconds": yml_int(read_yml("validation-service", "tuned"), "ttl-seconds"),
        "baseline_linger_ms": yml_int(read_yml("payment-gateway", "baseline"), "linger.ms"),
    }


def findings() -> list:
    with open(os.path.join(SRC, "findings.json")) as fh:
        return json.load(fh)


def quadrant(f: dict) -> str:
    high = f["impact"] >= 4
    small = f["effort"] == "S"
    if high and small:
        return "Quick win"
    if high:
        return "Major project"
    if small:
        return "Fill-in"
    return "Defer or avoid"


def matrix_block(fs: list) -> str:
    lines = ["```mermaid", "quadrantChart", "    title Impact versus effort (assessed, not measured per finding)",
             "    x-axis Low effort --> High effort", "    y-axis Low impact --> High impact",
             "    quadrant-1 Major projects", "    quadrant-2 Quick wins", "    quadrant-3 Fill-ins",
             "    quadrant-4 Defer or avoid"]
    seen = {}
    for f in fs:
        x = QUADRANT_X[f["effort"]]
        y = round(f["impact"] / 6 + 0.05, 2)
        # nudge points that would overlap so labels stay readable
        n = seen.get((x, y), 0)
        seen[(x, y)] = n + 1
        lines.append(f"    {f['id']}: [{x + 0.04 * n:.2f}, {y:.2f}]")
    lines.append("```")
    return "\n".join(lines)


def findings_table(fs: list) -> str:
    rows = [[f["id"], f["title"], f["area"], f"{f['impact']} of 5", f["effort"], f["risk"], quadrant(f)] for f in fs]
    ordered = sorted(rows, key=lambda r: (-int(r[3].split()[0]), "SML".index(r[4])))
    return L.table(["ID", "Finding", "Area", "Assessed impact", "Effort", "Risk", "Quadrant"], ordered)


def plan_table(fs: list) -> str:
    rows = []
    for phase in ("Quick wins", "Next sprint", "Structural"):
        for f in fs:
            if f["plan"] == phase:
                dep = ", ".join(f["depends_on"]) or "none"
                rows.append([phase, f["id"], f["title"], f["effort"], f["risk"], dep])
    return L.table(["Sequence", "ID", "Change", "Effort", "Risk", "Depends on"], rows)


def quick_wins_sentence(fs: list) -> str:
    wins = [f["id"] for f in fs if quadrant(f) == "Quick win"]
    majors = [f["id"] for f in fs if quadrant(f) == "Major project"]
    return f"quick wins {', '.join(wins)}; major projects {', '.join(majors)}"


def build_values(runs: dict) -> dict:
    env = L.env_summary(runs)
    cfg = cfg_facts()
    fs = findings()
    b_pass, b_fail = L.steady_bounds(runs, "baseline")
    t_pass, t_fail = L.steady_bounds(runs, "tuned")
    tuned_block = "```yaml\n" + runs["tuned_steady"].tuned_config.strip("\n") + "\n```"
    values = {}
    for k, v in env.items():
        values[f"env.{k}"] = str(v)
    for k, v in cfg.items():
        values[f"cfg.{k}"] = str(v)
    values.update({
        "slo.p99_ms": str(SLO_P99_MS), "slo.error_rate": f"{SLO_ERROR_RATE * 100:.1f}%",
        "slo.recovery_s": str(SLO_RECOVERY_SECONDS),
        "fact.baseline_pass": str(b_pass), "fact.baseline_fail": str(b_fail),
        "fact.tuned_pass": str(t_pass), "fact.tuned_fail": str(t_fail),
        "fact.cold_first_step": L.cold_first_step_note(runs) or "No first-step warm-up effect was observed.",
        "fact.spike_baseline": L.spike_recovery_text(runs["baseline_spike"]),
        "fact.spike_tuned": L.spike_recovery_text(runs["tuned_spike"]),
        "fact.spike_burst_rate": str(runs["baseline_spike"].result["params"]["burst_req_per_s"]),
        "fact.spike_burst_seconds": str(runs["baseline_spike"].result["params"]["burst_seconds"]),
        "fact.spike_base_rate": str(runs["baseline_spike"].result["params"]["base_req_per_s"]),
        "fact.tuned_spike_caveat": (
            "The burst rate is below the highest step the tuned profile sustained in the steady runs, so the tuned "
            "system was never overloaded by the burst: this shows no degradation under the same load, not a faster "
            "recovery." if runs["tuned_spike"].result["params"]["burst_req_per_s"] < (t_pass or 0)
            else "The burst rate reached or exceeded the tuned profile's sustained rate."),
        "fact.run_count": str(len(runs)),
        "fact.payments_total": f"{sum(r.result['pipeline']['payments_created'] for r in runs.values()):,}",
        "fact.quadrants": quick_wins_sentence(fs),
        "table.steady_baseline": L.steady_table(runs["baseline_steady"]),
        "table.steady_tuned": L.steady_table(runs["tuned_steady"]),
        "table.steady_comparison": L.steady_comparison(runs),
        "table.spike_comparison": L.spike_comparison(runs),
        "table.smoke": L.smoke_comparison(runs),
        "table.extra_runs": L.extra_runs_table(runs),
        "table.invariants": L.invariants_table(runs),
        "table.explain": L.explain_table(runs),
        "table.partitions": L.partition_table(runs),
        "table.partitions_baseline": L.partition_table(runs, ("baseline",)),
        "fact.ranges_sentence": (
            "The two step ranges do not overlap, so the tuned profile sustains a clearly higher load, but the step "
            "lists are coarse and this report deliberately gives no single \"times faster\" figure."
            if (b_fail is not None and t_pass is not None and b_fail <= t_pass)
            else "The step ranges overlap, so the data does not show a clear difference in sustained load."),
        "table.limits": L.limits_table(runs),
        "table.params": L.params_table(runs),
        "table.results_index": L.results_index(runs),
        "table.findings": findings_table(fs),
        "table.plan": plan_table(fs),
        "block.tuned_config": tuned_block,
        "block.matrix": matrix_block(fs),
    })
    return values


PLACEHOLDER = re.compile(r"\{\{\s*([a-z_]+\.[a-z0-9_]+)\s*\}\}")


def render(template: str, values: dict) -> str:
    missing = set()

    def sub(m):
        key = m.group(1)
        if key not in values:
            missing.add(key)
            return m.group(0)
        return values[key]

    out = PLACEHOLDER.sub(sub, template)
    if missing:
        raise SystemExit(f"unknown placeholders: {sorted(missing)}")
    return out


def main() -> int:
    check = "--check" in sys.argv
    runs = L.load_runs()
    values = build_values(runs)
    stale = []
    for name in TEMPLATES:
        with open(os.path.join(SRC, name + ".tmpl")) as fh:
            rendered = render(fh.read(), values)
        target = os.path.join(OUT, name)
        if check:
            current = open(target).read() if os.path.exists(target) else None
            if current != rendered:
                stale.append(name)
        else:
            with open(target, "w") as fh:
                fh.write(rendered)
            print(f"wrote {os.path.relpath(target, L.ROOT)}")
    if stale:
        print("out of date (run scripts/generate_report.py): " + ", ".join(stale), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
