#!/usr/bin/env python3
"""Generates the sample deliverables from templates plus results/*.

  sample-deliverable/src/<name>.tmpl  ->  sample-deliverable/<name>

Templates hold prose only. Every number, table and data-dependent claim arrives through a {{placeholder}}
computed from results/*/result.json, env.txt, explain.txt, the service configuration or load scripts.
lint_report_numbers.py fails if a template (or findings.json) contains a number or number word.

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
EFFORT_X = {"S": 0.2, "M": 0.62, "L": 0.85}
EFFORT_ORDER = "SML"
RISK_ORDER = ["Low", "Medium", "High"]
IMPACT_HIGH = 4
IMPACT_MAX = 5
TOP_N = 3
PHASES = ("Quick wins", "Next sprint", "Structural")


# ------------------------------------------------------------------ config facts

def read_yml(service: str, kind: str) -> str:
    path = os.path.join(L.ROOT, "services", service, "src", "main", "resources", f"{service}-{kind}.yml")
    with open(path) as fh:
        return fh.read()


def yml_int(text: str, key: str):
    m = re.search(rf"^\s*{re.escape(key)}:\s*(\d+)\s*(?:#.*)?$", text, re.M)
    return int(m.group(1)) if m else None


def cfg_facts(runs: dict) -> dict:
    """Baseline values come from the (unchanged) baseline files; tuned values from the configuration
    recorded in the tuned run's own env.txt, so they cannot drift from the results."""
    tuned = L.tuned_blocks(runs["tuned_steady"])
    baseline_ledger = read_yml("ledger-service", "baseline")
    return {
        "baseline_concurrency": yml_int(baseline_ledger, "concurrency"),
        "baseline_partitions": yml_int(baseline_ledger, "partitions"),
        "baseline_linger_ms": yml_int(read_yml("payment-gateway", "baseline"), "linger.ms"),
        "tuned_concurrency": yml_int(tuned["ledger-service"], "concurrency"),
        "tuned_partitions": yml_int(tuned["ledger-service"], "partitions"),
        "tuned_pool_gateway": yml_int(tuned["payment-gateway"], "maximum-pool-size"),
        "tuned_pool_validation": yml_int(tuned["validation-service"], "maximum-pool-size"),
        "tuned_pool_ledger": yml_int(tuned["ledger-service"], "maximum-pool-size"),
        "tuned_linger_ms": yml_int(tuned["payment-gateway"], "linger.ms"),
        "tuned_batch_size": yml_int(tuned["payment-gateway"], "batch-size"),
        "tuned_max_poll_records": yml_int(tuned["ledger-service"], "max-poll-records"),
        "cache_ttl_seconds": yml_int(tuned["validation-service"], "ttl-seconds"),
    }


def load_mix() -> dict:
    """Payload mix and merchant skew, read from the k6 library rather than restated."""
    with open(os.path.join(L.ROOT, "load", "k6", "lib.js")) as fh:
        js = fh.read()
    dup = float(re.search(r"roll < ([0-9.]+) && last", js).group(1))
    failing_from = float(re.search(r"roll >= ([0-9.]+)", js).group(1))
    skew = float(re.search(r"Math\.random\(\) < ([0-9.]+) \? 'MER-001'", js).group(1))
    return {"duplicate": dup, "failing": 1 - failing_from, "normal": 1 - dup - (1 - failing_from), "skew": skew}


def pct(x: float) -> str:
    return f"{round(x * 100)}%"


def service_count() -> int:
    services = os.path.join(L.ROOT, "services")
    return len([d for d in os.listdir(services)
                if d not in ("common", "e2e-tests") and os.path.exists(os.path.join(services, d, "pom.xml"))])


# ---------------------------------------------------------------------- findings

def findings() -> list:
    with open(os.path.join(SRC, "findings.json")) as fh:
        return json.load(fh)


def quadrant(f: dict) -> str:
    high = f["impact"] >= IMPACT_HIGH
    small = f["effort"] == "S"
    if high and small:
        return "Quick win"
    if high:
        return "Major project"
    if small:
        return "Fill-in"
    return "Defer or avoid"


def matrix_points(fs: list) -> list:
    """(id, x, y) with y centred on the high/low impact boundary and points with the same score nudged apart along x only, so equal impact stays equal on the chart."""
    points, seen = [], {}
    for f in fs:
        x = EFFORT_X[f["effort"]]
        y = round(0.5 + (f["impact"] - (IMPACT_HIGH - 0.5)) * 0.2, 2)
        n = seen.get((x, y), 0)
        seen[(x, y)] = n + 1
        points.append((f["id"], round(x + 0.07 * n, 2), y))
    return points


def point_quadrant(x: float, y: float) -> str:
    high, right = y > 0.5, x > 0.5
    if high and not right:
        return "Quick win"
    if high and right:
        return "Major project"
    if not right:
        return "Fill-in"
    return "Defer or avoid"


def matrix_block(fs: list) -> str:
    lines = ["```mermaid", "quadrantChart", "    title Impact versus effort",
             "    x-axis Low effort --> High effort", "    y-axis Low impact --> High impact",
             "    quadrant-1 Major projects", "    quadrant-2 Quick wins", "    quadrant-3 Fill-ins",
             "    quadrant-4 Defer or avoid"]
    lines += [f"    {fid}: [{x:.2f}, {y:.2f}]" for fid, x, y in matrix_points(fs)]
    lines.append("```")
    return "\n".join(lines)


def findings_table(fs: list) -> str:
    ordered = sorted(fs, key=lambda f: (-f["impact"], EFFORT_ORDER.index(f["effort"]), RISK_ORDER.index(f["risk"])))
    rows = [[f["id"], f["title"], f["area"], f"{f['impact']} of {IMPACT_MAX}", f["effort"], f["risk"], quadrant(f)]
            for f in ordered]
    return L.table(["ID", "Finding", "Area", "Assessed impact", "Effort", "Risk", "Quadrant"], rows)


def plan_order(fs: list) -> list:
    """Findings grouped by plan phase; within a phase, dependencies come first (stable topological order)."""
    by_id = {f["id"]: f for f in fs}
    ordered = []
    for phase in PHASES:
        pending = [f for f in fs if f["plan"] == phase]
        placed = set(x["id"] for x in ordered)
        while pending:
            progressed = False
            for f in list(pending):
                deps_in_phase = [d for d in f["depends_on"] if by_id[d]["plan"] == phase and d not in placed]
                if not deps_in_phase:
                    ordered.append(f)
                    placed.add(f["id"])
                    pending.remove(f)
                    progressed = True
            if not progressed:  # cycle: keep the remaining order rather than loop forever
                ordered += pending
                break
    return ordered


def plan_table(fs: list) -> str:
    rows = [[f["plan"], f["id"], f["title"], f["effort"], f["risk"], ", ".join(f["depends_on"]) or "none"]
            for f in plan_order(fs)]
    return L.table(["Sequence", "ID", "Change", "Effort", "Risk", "Depends on"], rows)


def top_three(fs: list) -> str:
    ranked = sorted(fs, key=lambda f: (-f["impact"], EFFORT_ORDER.index(f["effort"]), RISK_ORDER.index(f["risk"])))[:TOP_N]
    ids = {f["id"] for f in ranked}
    parts = []
    for f in ranked:
        needs = [d for d in f["depends_on"] if d not in ids]
        parts.append(f"{f['id']}" + (f" (needs {', '.join(needs)} first)" if needs else ""))
    return ", ".join(parts)


def quadrants_sentence(fs: list) -> str:
    wins = [f["id"] for f in fs if quadrant(f) == "Quick win"]
    majors = [f["id"] for f in fs if quadrant(f) == "Major project"]
    return f"quick wins {', '.join(wins)}; major projects {', '.join(majors)}"


# ----------------------------------------------------------- data-dependent claims

def correctness_sentence(runs: dict) -> str:
    total = sum(r.result["pipeline"]["payments_created"] for r in runs.values())
    bad = [os.path.basename(r.dir) for r in runs.values()
           if not (r.result["invariants"]["all_hold"] and r.result["invariants"]["negative_balances"] == 0
                   and r.result["invariants"]["debits_minus_credits_minor"] == 0
                   and r.result["invariants"]["non_terminal_payments"] == 0)]
    if not bad:
        return (f"Across all {len(runs)} recorded runs ({total:,} payments created in total), no balance went negative, "
                "debits equalled credits, and every payment reached a terminal state (sections 4 and 8).")
    return (f"**Correctness invariants failed in {len(bad)} of {len(runs)} recorded runs** ({', '.join(bad)}); "
            "performance figures from those runs must not be relied on (sections 4 and 8).")


def low_load_sentence(runs: dict, b_fail, t_pass) -> str:
    b, t = runs.get("baseline_steady"), runs.get("tuned_steady")
    bs, ts = runs.get("baseline_smoke"), runs.get("tuned_smoke")
    higher = (b and t and t.phases[0]["e2e_ms"]["p50"] > b.phases[0]["e2e_ms"]["p50"]
              and bs and ts and ts.result["pipeline"]["e2e_ms"]["p50"] > bs.result["pipeline"]["e2e_ms"]["p50"])
    if not higher:
        return "The tuned profile did not show a higher median end-to-end latency than the baseline at low load in these runs."
    text = ("The tuned profile has a higher median end-to-end latency than the baseline at low load (the smoke run "
            "and the lowest steady step). The likely causes are the outbox polling on the gateway and the ledger and "
            "producer lingering, but that was not verified.")
    if b_fail is not None and t_pass is not None and b_fail <= t_pass:
        text += " It is a real trade-off: a higher latency floor at low load in exchange for capacity at high load."
    return text


def migration_note(runs: dict) -> str:
    names = [os.path.basename(r.dir) for r in runs.values() if r.result.get("result_migrations")]
    if not names:
        return ""
    return ("- " + ", ".join(f"`{n}`" for n in names) + " was updated by `scripts/normalize_results.py`, which moves an "
            "unrecovered spike's recovery value from `recovery_seconds_after_burst` to `recovery_at_least_seconds` after "
            "the collector was changed to record it that way. The change is recorded in that file under "
            "`result_migrations`; no measured value was altered.")


def capacity_sentence(label: str, passed, failed) -> str:
    if passed is None:
        return f"the {label} pipeline did not meet the objective at any offered step."
    if failed is None:
        return (f"the {label} pipeline met the objective at every offered step, up to {passed} req/s; "
                "the step lists did not find its limit.")
    return (f"the {label} pipeline met the objective at every offered rate up to {passed} req/s and missed it at "
            f"{failed} req/s. The true limit lies between those steps.")


def ranges_sentence(b_fail, t_pass) -> str:
    if b_fail is None or t_pass is None:
        return "The step lists did not bracket both limits, so no comparison of sustained load is made here."
    if b_fail <= t_pass:
        return ("The two step ranges do not overlap, so the tuned profile sustains a clearly higher load, but the "
                "step lists are coarse and this report deliberately gives no single \"times faster\" figure.")
    return "The step ranges overlap, so the data does not show a clear difference in sustained load."


def spike_caveat(runs: dict, t_pass) -> str:
    spike = runs["tuned_spike"]
    burst = spike.result["params"]["burst_req_per_s"]
    if not spike.result["spike"]["recovered_within_run"]:
        return "The tuned pipeline had not recovered by the end of the run, so its behaviour after the burst is not established."
    if t_pass is not None and burst < t_pass:
        return ("The burst rate is below the highest step the tuned profile sustained in the steady runs, so the tuned "
                "system was never overloaded by the burst: this shows no degradation under the same load, not a faster "
                "recovery.")
    return "The burst rate reached or exceeded the tuned profile's sustained rate."


def earlier_spike_note(runs: dict) -> str:
    recovered = runs["baseline_spike"].result["spike"]["recovered_within_run"]
    if recovered:
        return ("An earlier recording of the baseline spike scenario (commit `eecb939`), made before the "
                "baseline-affecting fixes, also recovered within the window; the two recordings' recovery times are "
                "not compared here.")
    return ("An earlier recording of the baseline spike scenario (commit `eecb939`), made before the baseline-affecting "
            "fixes, did recover within the window; the current one did not. The difference was not attributed to a "
            "cause and may be run-to-run variance.")


def score_order_note(fs: list) -> str:
    odd = [f for f in fs if quadrant(f) == "Quick win" and f["plan"] != "Quick wins" and f["depends_on"]]
    if not odd:
        return ""
    parts = [f"{f['id']} scores as a quick win but depends on {', '.join(f['depends_on'])}" for f in odd]
    return "Where the score and the recommended order disagree (" + "; ".join(parts) + "), the sequenced plan follows the dependency."


def queueing_note(runs: dict) -> str:
    b = runs.get("baseline_steady")
    if b and any((not p["meets_slo"]) and p["e2e_ms"]["p50"] > SLO_P99_MS for p in b.phases):
        return ("Steps that missed the objective in the baseline column are dominated by queueing: those numbers describe an "
                "overloaded pipeline, not its service time. Baseline steps are also not independent, because its cost grows as "
                "the tables fill during a run.")
    return "Baseline steps are not independent, because its cost grows as the tables fill during a run."


def payment_share(runs: dict) -> str:
    created = sum(r.result["pipeline"]["payments_created"] for r in runs.values() if r.scenario in ("steady", "spike"))
    sent = sum(r.result["k6"]["http_reqs"] for r in runs.values() if r.scenario in ("steady", "spike"))
    return pct(created / sent)


def config_diff_table(cfg: dict) -> str:
    rows = [
        ["Record key", "merchant identifier", "debtor account identifier", "F-12"],
        ["Partitions per topic", cfg["baseline_partitions"], cfg["tuned_partitions"], "F-12"],
        ["Consumer threads per service", cfg["baseline_concurrency"], cfg["tuned_concurrency"], "F-03"],
        ["Producer linger (gateway)", f"{cfg['baseline_linger_ms']} ms", f"{cfg['tuned_linger_ms']} ms", "F-01"],
        ["Producer batch size / compression", "Kafka default / none", f"{cfg['tuned_batch_size']} bytes / lz4", "F-01"],
        ["Gateway publishing", "blocking send inside the request transaction", "outbox row, asynchronous publisher", "F-07, F-01"],
        ["Database pool (gateway / validation / ledger)", "driver default",
         f"{cfg['tuned_pool_gateway']} / {cfg['tuned_pool_validation']} / {cfg['tuned_pool_ledger']}", "F-07"],
        ["Ledger processing", "one record per transaction",
         f"batches of up to {cfg['tuned_max_poll_records']} records, JDBC batch writes", "F-04"],
        ["Indexes", "primary keys only", "idempotency key, postings by account and time, limits by account", "F-06, F-08"],
        ["Limits lookup", "lazy query per account", "one projection query, cached rates", "F-08"],
        ["Account data source", "Postgres on every validation",
         f"Redis cache, {cfg['cache_ttl_seconds']} s time-to-live, event invalidation", "F-13"],
    ]
    return L.table(["Setting", "Baseline", "Tuned", "Finding"], rows)


def tuned_config_blocks(runs: dict) -> str:
    out = []
    for service, text in L.tuned_blocks(runs["tuned_steady"]).items():
        out.append(f"**{service}** (`{service}-tuned.yml`, comments removed by the recorder)\n\n```yaml\n{text.strip()}\n```")
    return "\n\n".join(out)


# ------------------------------------------------------------------------ values

def build_values(runs: dict) -> dict:
    env = L.env_summary(runs)
    cfg = cfg_facts(runs)
    fs = findings()
    mix = load_mix()
    b_pass, b_fail = L.steady_bounds(runs, "baseline")
    t_pass, t_fail = L.steady_bounds(runs, "tuned")
    fail_source = L.steady_fail_source(runs, "tuned")
    spike = runs["baseline_spike"].result["params"]
    extra = [r for r in runs.values() if r.scenario == "steady" and r.suffix]
    smoke_vus = runs["baseline_smoke"].result["params"]["vus"]
    values = {f"env.{k}": str(v) for k, v in env.items()}
    values.update({f"cfg.{k}": str(v) for k, v in cfg.items()})
    values.update({
        "slo.p99_ms": str(SLO_P99_MS), "slo.error_rate": f"{SLO_ERROR_RATE * 100:.1f}%",
        "slo.recovery_s": str(SLO_RECOVERY_SECONDS),
        "fact.service_count": str(service_count()),
        "fact.findings_count": str(len(fs)),
        "fact.impact_scale": f"1 to {IMPACT_MAX}", "fact.impact_high": str(IMPACT_HIGH),
        "fact.baseline_capacity": capacity_sentence("baseline", b_pass, b_fail),
        "fact.tuned_capacity": capacity_sentence("tuned", t_pass, t_fail),
        "fact.tuned_fail_source": (f" The step that missed it was offered only in the additional run `{fail_source}`."
                                   if fail_source and not fail_source.endswith("tuned_steady") else ""),
        "fact.extra_run_count": str(len(extra)),
        "fact.cold_first_step": L.cold_first_step_note(runs) or "No first-step warm-up effect was observed.",
        "fact.spike_baseline": L.spike_recovery_text(runs["baseline_spike"]),
        "fact.spike_tuned": L.spike_recovery_text(runs["tuned_spike"]),
        "fact.spike_burst_rate": str(spike["burst_req_per_s"]),
        "fact.spike_burst_seconds": str(spike["burst_seconds"]),
        "fact.spike_base_rate": str(spike["base_req_per_s"]),
        "fact.tuned_spike_caveat": spike_caveat(runs, t_pass),
        "fact.ranges_sentence": ranges_sentence(b_fail, t_pass),
        "fact.earlier_spike_note": earlier_spike_note(runs),
        "fact.score_order_note": score_order_note(fs),
        "fact.queueing_note": queueing_note(runs),
        "fact.correctness": correctness_sentence(runs),
        "fact.migration_note": migration_note(runs),
        "fact.low_load_latency": low_load_sentence(runs, b_fail, t_pass),
        "fact.payment_share": payment_share(runs),
        "fact.mix_normal": pct(mix["normal"]), "fact.mix_duplicate": pct(mix["duplicate"]),
        "fact.mix_failing": pct(mix["failing"]), "fact.merchant_skew": pct(mix["skew"]),
        "fact.smoke_vus": str(smoke_vus),
        "fact.top_n": str(TOP_N), "fact.top3": top_three(fs),
        "fact.quadrants": quadrants_sentence(fs),
        "table.steady_baseline": L.steady_table(runs["baseline_steady"]),
        "table.steady_tuned": L.steady_table(runs["tuned_steady"]),
        "table.steady_comparison": L.steady_comparison(runs),
        "table.spike_comparison": L.spike_comparison(runs),
        "table.smoke": L.smoke_comparison(runs),
        "table.extra_runs": L.extra_runs_table(runs),
        "table.invariants_baseline": L.invariants_table(runs, "baseline"),
        "table.invariants_tuned": L.invariants_table(runs, "tuned"),
        "table.explain": L.explain_table(runs),
        "table.partitions": L.partition_table(runs),
        "table.partitions_baseline": L.partition_table(runs, ("baseline",)),
        "table.limits": L.limits_table(runs),
        "table.params": L.params_table(runs),
        "table.results_index": L.results_index(runs),
        "table.findings": findings_table(fs),
        "table.plan": plan_table(fs),
        "table.config_diff": config_diff_table(cfg),
        "block.tuned_config": tuned_config_blocks(runs),
        "block.matrix": matrix_block(fs),
    })
    return values


PLACEHOLDER = re.compile(r"\{\{\s*([a-z_]+\.[a-z0-9_]+)\s*\}\}")


def render(template: str, values: dict) -> str:
    values = dict(values)
    # Per-template count of quick-audit findings (headings that start with "### Q-").
    values["fact.section_findings"] = str(len(re.findall(r"^### Q-", template, re.M)))
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
