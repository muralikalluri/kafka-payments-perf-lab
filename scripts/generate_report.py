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

import hashlib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(__file__))
import report_lib as L  # noqa: E402
from collect_results import SLO_ERROR_RATE, SLO_P99_MS, SLO_RECOVERY_SECONDS  # noqa: E402

SRC = os.path.join(L.ROOT, "sample-deliverable", "src")
OUT = os.path.join(L.ROOT, "sample-deliverable")
DOCS_SRC = os.path.join(L.ROOT, "docs", "src")
# (template directory, file name, output directory)
TARGETS = [(SRC, "AUDIT_REPORT_SAMPLE.md", OUT), (SRC, "QUICK_AUDIT_ledger-service.md", OUT),
           (DOCS_SRC, "README.md", L.ROOT)]
TEMPLATES = [name for _, name, _ in TARGETS]
GIF_PATH = "docs/img/grafana-spike.gif"
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


def read_base_yml(service: str) -> str:
    with open(os.path.join(L.ROOT, "services", service, "src", "main", "resources", f"{service}.yml")) as fh:
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
        "baseline_in_flight": yml_int(read_yml("payment-gateway", "baseline"), "max.in.flight.requests.per.connection"),
        # F-02 is not in the recorded tuned runs, so this one is read from the repository's tuned file, not from env.txt
        "tuned_in_flight": yml_int(read_yml("payment-gateway", "tuned"), "max.in.flight.requests.per.connection"),
        "settlement_shards": yml_int(read_base_yml("ledger-service"), "shards"),
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


def top_three(fs: list, runs: dict) -> str:
    ranked = sorted(fs, key=lambda f: (-f["impact"], EFFORT_ORDER.index(f["effort"]), RISK_ORDER.index(f["risk"])))[:TOP_N]
    ids = {f["id"] for f in ranked}
    parts = []
    for f in ranked:
        needs = [d for d in f["depends_on"] if d not in ids]
        label = annotate_unmeasured(runs, [f["id"]])[0]
        parts.append(label + (f" (needs {', '.join(needs)} first)" if needs else ""))
    return ", ".join(parts)


def quadrants_sentence(fs: list, runs: dict) -> str:
    wins = annotate_unmeasured(runs, [f["id"] for f in fs if quadrant(f) == "Quick win"])
    majors = annotate_unmeasured(runs, [f["id"] for f in fs if quadrant(f) == "Major project"])
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
                "step lists are coarse, so no single \"times faster\" figure is given.")
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
    """How the baseline spike recordings relate. The earlier recording is a fact from git history (commit eecb939)."""
    standard = runs["baseline_spike"].result["spike"]["recovered_within_run"]
    recorded = runs.get("baseline_spike_recorded")
    text = ("An earlier recording of the baseline spike scenario (commit `eecb939`), made before the baseline-affecting "
            "fixes, recovered within the window; the standard recording used in the tables above "
            + ("also did." if standard else "did not."))
    outcomes = [True, standard]
    if recorded is not None:
        rec = recorded.result["spike"]["recovered_within_run"]
        outcomes.append(rec)
        text += (f" A further recording, taken while the dashboard was being captured (`{os.path.basename(recorded.dir)}`), "
                 + ("also recovered." if rec else "did not recover."))
    if len(set(outcomes)) > 1:
        text += (" The recordings disagree, which shows run-to-run variance in this scenario for the baseline; no cause was "
                 "attributed and no single recovery time is claimed.")
    return text


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


def jaeger_port() -> str:
    """Host port of the Jaeger UI, read from the compose file."""
    with open(os.path.join(L.ROOT, "docker-compose.yml")) as fh:
        m = re.search(r'"(\d+):16686"', fh.read())
    return m.group(1) if m else "unknown"


def webhook_latency_default() -> str:
    """Default latency of the webhook simulator, read from its configuration (F-05 is not in the recorded runs)."""
    with open(os.path.join(L.ROOT, "services", "notification-service", "src", "main", "resources",
                           "notification-service.yml")) as fh:
        m = re.search(r"latency-ms:\s*\$\{WEBHOOK_LATENCY_MS:(\d+)\}", fh.read())
    if not m:
        raise SystemExit("webhook simulator latency default not found in notification-service.yml")
    return m.group(1)


def tuned_jvm_opts() -> str:
    """Tuned JVM options (G1 case), read from the benchmark runner (F-10 is not in the recorded runs)."""
    with open(os.path.join(L.ROOT, "scripts", "run-benchmark.sh")) as fh:
        m = re.search(r'^\s*g1\)\s+JVM_OPTS="([^"]+)"', fh.read(), re.M)
    if not m:
        raise SystemExit("tuned JVM options not found in run-benchmark.sh")
    return m.group(1)


def compose_topology() -> str:
    """Broker layout of the default compose file (what a reader gets when starting the stack today)."""
    with open(os.path.join(L.ROOT, "docker-compose.yml")) as fh:
        brokers = len(re.findall(r"^  kafka(?:-\d+)?:\s*$", fh.read(), re.M))
    rf = None
    env = os.path.join(L.ROOT, ".env.example")
    if os.path.exists(env):
        m = re.search(r"^LAB_TOPIC_REPLICAS=(\d+)", open(env).read(), re.M)
        rf = int(m.group(1)) if m else None
    if brokers <= 1:
        return "a single Kafka broker"
    return f"a {brokers}-broker Kafka cluster" + (f" (replication factor {rf})" if rf else "")


def java_version() -> str:
    with open(os.path.join(L.ROOT, "pom.xml")) as fh:
        return re.search(r"<java.version>(\d+)</java.version>", fh.read()).group(1)


def recorded_runs(runs: dict) -> list:
    return [runs[k] for k in ("baseline_spike_recorded", "tuned_spike_recorded") if k in runs]


def gif_caption(runs: dict) -> str:
    rec = recorded_runs(runs)
    spike = runs["baseline_spike"].result["params"]
    text = (f"Grafana during the spike scenario (offered rate raised from {spike['base_req_per_s']} to "
            f"{spike['burst_req_per_s']} req/s for {spike['burst_seconds']} s); baseline on the left, tuned on the right. "
            "Captured from the dashboard in this repository while the two runs stored in "
            + ", ".join(f"`{os.path.basename(r.dir)}`" for r in rec)
            + " executed (the capture adds some load, so they are not the runs used in the comparison tables). "
            "Small negative lag values are most likely an artefact of how the exporter samples offsets.")
    if len(rec) == 2:
        text += (f" Spike recovery in these recordings: baseline, {L.spike_recovery_text(rec[0])}; "
                 f"tuned, {L.spike_recovery_text(rec[1])}.")
    return text


def headline_table(runs: dict, b_pass, b_fail, t_pass, t_fail, source: str) -> str:
    b, t = runs["baseline_steady"], runs["tuned_steady"]
    rows = [
        ["Highest offered rate that met the objective (req/s)", b_pass, t_pass],
        ["First offered rate that missed it (req/s)", b_fail if b_fail is not None else "not reached",
         (f"{t_fail} (offered only in an additional run)" if t_fail is not None and source and not source.endswith("tuned_steady")
          else (t_fail if t_fail is not None else "not reached"))],
        [f"End-to-end p50 at the lowest steady step ({b.phases[0]['target_req_per_s']} req/s)",
         L.ms(b.phases[0]["e2e_ms"]["p50"]), L.ms(t.phases[0]["e2e_ms"]["p50"])],
        ["Spike recovery, standard run", L.spike_recovery_text(runs["baseline_spike"]),
         L.spike_recovery_text(runs["tuned_spike"])],
    ]
    explain = {(r[0], r[1]): r for r in L.explain_rows(runs)}
    for query in ("idempotency lookup", "daily outflow"):
        bq, tq = explain.get(("baseline", query)), explain.get(("tuned", query))
        if bq and tq:
            rows.append([f"Query plan, {query} (F-06)", f"{bq[2]}, {bq[4]}", f"{tq[2]}, {tq[4]}"])
    return L.table(["", "Baseline", "Tuned"], rows)


def mvp_status(runs: dict) -> list:
    """(item, done, evidence) for every SPEC MVP item, checked against the repository itself (files, config,
    results), never against prose. Kept free of git commands so it gives the same answer in a shallow CI clone."""
    root = L.ROOT

    def path(*parts):
        return os.path.join(root, *parts)

    def exists(*parts):
        return os.path.exists(path(*parts))

    def contains(rel: str, needle: str) -> bool:
        return exists(rel) and needle in open(path(rel)).read()

    java = "services/{svc}/src/main/java/lab/payments/{pkg}/{cls}"
    tuned = {svc: read_yml(svc, "tuned") for svc in ("payment-gateway", "validation-service", "ledger-service")}
    baseline = {svc: read_yml(svc, "baseline") for svc in tuned}
    services = ["payment-gateway", "validation-service", "ledger-service"]
    compose = open(path("docker-compose.yml")).read()
    dashboards = [f for f in os.listdir(path("grafana", "dashboards")) if f.endswith(".json")]
    cfg_b = yml_int(baseline["ledger-service"], "concurrency")
    cfg_t = yml_int(tuned["ledger-service"], "concurrency")

    def flag(svc, name):
        return f"{name}: true" in tuned[svc]

    findings = [
        ("F-01", flag("payment-gateway", "f01") and "linger.ms" in tuned["payment-gateway"],
         "gateway and ledger tuned config; async publishers"),
        ("F-03", cfg_t is not None and cfg_b is not None and cfg_t > cfg_b, "consumer concurrency in *-tuned.yml"),
        ("F-04", flag("ledger-service", "f04") and exists("services", "ledger-service", "src", "main", "java", "lab",
                                                          "payments", "ledgerservice", "BatchLedgerProcessor.java"),
         "BatchLedgerProcessor"),
        ("F-06", any(f.startswith("V100") for f in os.listdir(path("services", "ledger-service", "src", "main",
                                                                  "resources", "db", "ledger-tuned")))
         and any(f.startswith("V100") for f in os.listdir(path("services", "payment-gateway", "src", "main",
                                                               "resources", "db", "gateway-tuned"))),
         "db/*-tuned migrations"),
        ("F-07", flag("payment-gateway", "f07") and exists("services", "payment-gateway", "src", "main", "java", "lab",
                                                           "payments", "paymentgateway", "GatewayOutboxPublisher.java"),
         "GatewayOutboxPublisher, pool sizes"),
        ("F-08", flag("validation-service", "f08") and exists("services", "validation-service", "src", "main", "java",
                                                              "lab", "payments", "validationservice",
                                                              "ProjectionReferenceData.java"),
         "ProjectionReferenceData"),
        ("F-12", all(flag(s, "f12") for s in services) and yml_int(tuned["ledger-service"], "partitions")
         > (yml_int(baseline["ledger-service"], "partitions") or 0), "record key flag, partitions"),
        ("F-13", flag("validation-service", "f13") and exists("services", "validation-service", "src", "main", "java",
                                                              "lab", "payments", "validationservice", "AccountCache.java"),
         "AccountCache, CachedReferenceData"),
    ]
    baseline_flags_off = all(f"{f}: false" in open(path("services", svc, "src", "main", "resources", f"{svc}.yml")).read()
                             for svc, f in [("payment-gateway", "f07"), ("ledger-service", "f04"),
                                            ("validation-service", "f13")])

    rows = [
        ("Gateway, validation and ledger services", all(exists("services", s, "pom.xml") for s in services), "services/"),
        ("Notification handled (stub or service)",
         exists("services", "payment-gateway", "src", "main", "java", "lab", "payments", "paymentgateway",
                "NotificationStub.java") or exists("services", "notification-service", "pom.xml"),
         "services/notification-service"),
        ("Kafka broker(s) defined in compose", "\n  kafka" in compose, "docker-compose.yml"),
        ("Baseline anti-patterns kept behind default-off tuning flags", baseline_flags_off, "base *.yml flags"),
    ]
    rows += [(f"{fid} tuned implementation", ok, ev) for fid, ok, ev in findings]
    rows += [
        ("k6 smoke, steady and spike scenarios",
         all(exists("load", "k6", f"{n}.js") for n in ("smoke", "steady", "spike")), "load/k6/"),
        ("Benchmark runner and results format", exists("scripts", "run-benchmark.sh") and exists("scripts", "collect_results.py"),
         "scripts/"),
        ("Prometheus and exactly one Grafana dashboard", exists("monitoring", "prometheus.yml") and len(dashboards) == 1,
         "monitoring/, grafana/dashboards/"),
        ("Results recorded for smoke, steady and spike, both profiles",
         all(f"{p}_{n}" in runs for p in ("baseline", "tuned") for n in ("smoke", "steady", "spike")), "results/"),
        ("Quick audit and full audit report samples",
         exists("sample-deliverable", "QUICK_AUDIT_ledger-service.md") and exists("sample-deliverable", "AUDIT_REPORT_SAMPLE.md"),
         "sample-deliverable/"),
        ("Tuned profile marked complete (gate in the runner)", "complete: true" in tuned["payment-gateway"],
         "payment-gateway-tuned.yml"),
    ]
    return rows


def extended_scope(runs: dict) -> list:
    """(item, done, evidence) for the items SPEC marks Later, each checked against the repository. An item
    turns 'done' only when its files and configuration exist; nothing here is asserted by hand."""
    root = L.ROOT

    def exists(*parts):
        return os.path.exists(os.path.join(root, *parts))

    def text(*parts):
        p = os.path.join(root, *parts)
        return open(p).read() if os.path.exists(p) else ""

    def res(svc, kind):
        return text("services", svc, "src", "main", "resources", f"{svc}-{kind}.yml")

    compose = text("docker-compose.yml")
    java = ("services", "{svc}", "src", "main", "java", "lab", "payments")
    return [
        ("F-02 producer idempotence, acks and in-flight", "enable.idempotence: false" in res("payment-gateway", "baseline")
         and "enable.idempotence: true" in res("payment-gateway", "tuned"), "*-baseline.yml, *-tuned.yml"),
        ("F-05 blocking notification call replaced by a hand-off (notification-service)",
         exists("services", "notification-service", "pom.xml") and "f05: true" in res("payment-gateway", "tuned"),
         "services/notification-service"),
        ("F-09 serialization and INFO logging", "f09: true" in res("payment-gateway", "tuned"), "lab.tuning.f09"),
        ("F-10 JVM sizing, GC choice and virtual threads", "f10: true" in res("payment-gateway", "tuned"), "lab.tuning.f10"),
        ("F-11 hot settlement account", "f11: true" in res("ledger-service", "tuned"), "lab.tuning.f11"),
        ("Three-broker Kafka cluster", "\n  kafka-3:" in compose, "docker-compose.yml"),
        ("Soak scenario", exists("load", "k6", "soak.js"), "load/k6/soak.js"),
        ("Gatling scenarios", exists("load", "gatling", "pom.xml"), "load/gatling/"),
        ("Distributed tracing to Jaeger", "jaeger" in compose.lower(), "docker-compose.yml"),
        ("JFR recordings and flame graphs", exists("scripts", "flamegraph.py"), "scripts/flamegraph.py"),
        ("PDF export of the reports", exists("scripts", "export_pdf.js") and exists("scripts", "pdf-style.css")
         and exists("sample-deliverable", "AUDIT_REPORT_SAMPLE.pdf") and exists("sample-deliverable", "QUICK_AUDIT_ledger-service.pdf"),
         "scripts/export_pdf.js, sample-deliverable/*.pdf"),
        ("Reusable report template folder", exists("report", "template", "REPORT_TEMPLATE.md")
         and exists("report", "template", "QUICK_AUDIT_TEMPLATE.md") and exists("report", "template", "README.md"),
         "report/template/"),
    ]


def extended_scope_table(runs: dict) -> str:
    rows = extended_scope(runs)
    return L.table(["Item marked Later in SPEC.md", "Status", "Checked in"],
                   [[i, "done" if ok else "not built", ev] for i, ok, ev in rows])


def failover_note(runs: dict) -> str:
    fo = [r for r in runs.values() if L.is_failover(r)]
    if not fo:
        return "Broker-failure behaviour was not tested in the recorded runs."
    tuned = [r for r in fo if r.profile == "tuned"]
    baseline = [r for r in fo if r.profile == "baseline"]
    ok = all(r.result["invariants"]["all_hold"] for r in fo)
    text = ("Brokers were stopped under load and restarted in " + str(len(fo)) + " recorded runs; "
            + ("the ledger invariants held in all of them." if ok else "the ledger invariants FAILED in at least one of them."))
    if tuned and baseline:
        t_fail = max(r.result["k6"]["http_req_failed_rate"] for r in tuned)
        b_fail = max(r.result["k6"]["http_req_failed_rate"] for r in baseline)
        if t_fail == 0 and b_fail > 0:
            text += " The baseline gateway rejected requests while the broker was unavailable; the tuned gateway kept accepting them."
    return text


def ablated_findings(runs: dict) -> list:
    ids = []
    for r in runs.values():
        if r.suffix and r.suffix.startswith("ablate-"):
            ids.append(r.suffix[len("ablate-"):].upper())
    return sorted(set(ids))


def measured_findings(runs: dict) -> list:
    """Findings whose tuned change was active in the recorded tuned runs, read from the configuration recorded in
    their own env.txt: every `fNN: true` flag, plus F-03 (more consumer threads than the baseline) and F-06 (the tuned
    migrations). A finding implemented after the runs were recorded is not in this list."""
    run = runs["tuned_steady"]
    blocks = L.tuned_blocks(run)
    ids = {m.upper() for text in blocks.values() for m in re.findall(r"^\s*(f\d\d):\s*true\b", text, re.M)}
    ids = {f"F-{i[1:]}" for i in ids}
    base = yml_int(read_yml("ledger-service", "baseline"), "concurrency")
    tuned = yml_int(blocks.get("ledger-service", ""), "concurrency")
    if base is not None and tuned is not None and tuned > base:
        ids.add("F-03")
    if any("-tuned" in line for text in blocks.values() for line in text.splitlines() if "locations:" in line):
        ids.add("F-06")
    return sorted(ids)


def measured_changes_phrase(runs: dict) -> str:
    ids = measured_findings(runs)
    return f"{len(ids)} changes ({', '.join(ids)})"


def unmeasured_findings(runs: dict) -> list:
    measured = set(measured_findings(runs))
    return [f["id"] for f in findings() if f["id"] not in measured]


def unmeasured_note(runs: dict) -> str:
    ids = unmeasured_findings(runs)
    if not ids:
        return "Every finding in this report was part of the recorded tuned runs."
    return (f"{', '.join(ids)} are implemented in the repository but were not part of any recorded benchmark run: the recorded "
            "tuned runs carry only the changes listed above, and the recorded baseline runs predate the baseline "
            "anti-patterns that go with the others. The text on those findings rests on code, tests and mechanism, and no "
            "measured figure in this report is attributed to them.")


def annotate_unmeasured(runs: dict, ids: list) -> list:
    unmeasured = set(unmeasured_findings(runs))
    return [f"{i} (not benchmarked)" if i in unmeasured else i for i in ids]


def isolation_sentence(runs: dict) -> str:
    ablated = ablated_findings(runs)
    text = f"The combination of the {measured_changes_phrase(runs)} in the recorded tuned runs was measured. "
    if ablated:
        text += (f"The effect of {', '.join(ablated)} alone was isolated with an ablation run (the tuned profile with that one "
                 "change switched off); the effect of the other changes alone was not.")
    else:
        text += "The effect of each change alone was not isolated."
    return text


def ablation_recommendation(runs: dict) -> str:
    ablated = ablated_findings(runs)
    if ablated:
        return (f"An ablation was run for {', '.join(ablated)}; the same method (the tuned profile with one change reverted) is "
                "recommended for the others before investing in the larger items.")
    return ("An ablation (tuned with one change reverted) is the way to measure each change on its own and is recommended "
            "before investing in the larger items.")


def stage_notes(runs: dict) -> dict:
    if L.all_have_stage_metrics(runs):
        return {
            "short": ("Per-stage metrics (consumer lag, CPU, garbage collection, connection pools, locks) are stored with each run "
                      "but cover the whole run rather than each load step, so this report does not attribute a limit to one "
                      "component."),
            "lag": ("The recovery check uses end-to-end latency as a stand-in for consumer lag; consumer lag over each run is "
                    "stored in that run's `metrics.json`."),
            "limits": ("Stage metrics (consumer lag, CPU, garbage collection, connection pools, database locks) are stored per run "
                       "but span the whole run rather than each load step, so which stage limits either profile is not "
                       "established from them alone; the stage observations in section 8 show where pressure appeared."),
        }
    return {
        "short": ("No per-stage measurements (consumer lag, CPU, garbage collection, lock waits) were captured for these runs, so no "
                  "single component is named as the bottleneck."),
        "lag": "The recovery check uses end-to-end latency as a stand-in for consumer lag; consumer lag itself was not recorded.",
        "limits": ("No consumer lag, CPU, garbage-collection or lock-wait data was captured for these runs, so which stage limits "
                   "either profile is not established."),
    }


def findings_covered(fs: list) -> str:
    return "The findings covered: " + ", ".join(f"{f['id']} ({f['area'].lower()})" for f in fs) + "."


def load_scenarios() -> str:
    k6 = sorted(f[:-3] for f in os.listdir(os.path.join(L.ROOT, "load", "k6")) if f.endswith(".js") and f != "lib.js")
    text = "Load scenarios in k6 (" + ", ".join(k6) + ")"
    if os.path.exists(os.path.join(L.ROOT, "load", "gatling", "pom.xml")):
        text += " and the same workload in Gatling"
    return text


def known_gaps_sentence(runs: dict) -> str:
    """Known gaps, each included only while it is still true in the repository or the results."""
    gaps = []
    gateway = os.path.join(L.ROOT, "services", "payment-gateway", "src", "main", "java")
    if not any("LoadShed" in f for _, _, files in os.walk(gateway) for f in files):
        gaps.append("no load shedding on the gateway outbox backlog")
    if any(not os.path.exists(os.path.join(r.dir, "metrics.json")) for r in runs.values()):
        gaps.append("some results predate the per-stage metric snapshots (consumer lag, CPU, garbage collection, "
                    "connection pools, locks), so those runs have none stored")
    gaps.append("FX rates are cached in process rather than in Redis")
    gaps.append("connection-pool sizes are unswept lab choices")
    gaps.append("the cache accepts bounded staleness (a blocked account can be approved until its cached copy is "
                "invalidated or expires)")
    return "Known gaps in what was built: " + "; ".join(gaps) + "."


def not_built_sentence(runs: dict) -> str:
    missing = [i for i, ok, _ in extended_scope(runs) if not ok]
    if not missing:
        return "Everything marked Later in SPEC.md has been built."
    return "Not built (marked Later in SPEC.md): " + "; ".join(missing) + "."


def mvp_status_table(runs: dict) -> str:
    rows = mvp_status(runs)
    return L.table(["MVP item", "Status", "Checked in"], [[i, "done" if ok else "**NOT DONE**", ev] for i, ok, ev in rows])


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
    values.update({f"cfg.{k}": ("not recorded" if v is None else str(v)) for k, v in cfg.items()})
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
        "fact.top_n": str(TOP_N), "fact.top3": top_three(fs, runs),
        "fact.run_count": str(len(runs)),
        "fact.payments_total": f"{sum(r.result['pipeline']['payments_created'] for r in runs.values()):,}",
        "fact.java_version": java_version(),
        "fact.jaeger_port": jaeger_port(),
        "fact.gif_path": GIF_PATH,
        "fact.gif_caption": gif_caption(runs),
        "table.headline": headline_table(runs, b_pass, b_fail, t_pass, t_fail, fail_source),
        "table.adrs": L.adr_table(),
        "table.mvp_status": mvp_status_table(runs),
        "fact.kafka_topology": L.kafka_topology(runs),
        "table.failover": L.failover_table(runs),
        "table.soak": L.soak_tables(runs),
        "table.gatling": L.gatling_table(runs),
        "fact.isolation": isolation_sentence(runs),
        "fact.measured_changes": measured_changes_phrase(runs),
        "fact.unmeasured_note": unmeasured_note(runs),
        "fact.compose_topology": compose_topology(),
        "fact.ablation_recommendation": ablation_recommendation(runs),
        "fact.stage_short": stage_notes(runs)["short"],
        "fact.stage_lag": stage_notes(runs)["lag"],
        "fact.stage_limits": stage_notes(runs)["limits"],
        "fact.findings_covered": findings_covered(fs),
        "fact.load_scenarios": load_scenarios(),
        "table.stage_evidence": L.stage_evidence(runs),
        "table.gc": L.ladder_compare(runs, "tuned_steady", "tuned_steady_zgc", "G1", "ZGC"),
        "table.ablation_f11": L.ladder_compare(runs, "tuned_steady", "tuned_steady_ablate-f11", "Tuned", "Tuned without F-11"),
        "fact.jvm_opts_tuned": tuned_jvm_opts(),
        "fact.webhook_latency": webhook_latency_default(),
        "table.profiles": L.profile_tables(runs),
        "fact.failover_note": failover_note(runs),
        "table.extended_scope": extended_scope_table(runs),
        "fact.not_built": not_built_sentence(runs),
        "fact.known_gaps": known_gaps_sentence(runs),
        "fact.quadrants": quadrants_sentence(fs, runs),
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


PDF_STAMP = os.path.join(L.ROOT, "sample-deliverable", "pdf-sources.sha256")
PDF_SOURCES = ["AUDIT_REPORT_SAMPLE.md", "QUICK_AUDIT_ledger-service.md"]


def pdf_source_hashes() -> str:
    """sha256 of each report the PDFs are exported from. A PDF cannot be compared byte for byte (it embeds a
    timestamp), so the hash of its source is stored when the PDF is exported and checked here."""
    lines = []
    for name in PDF_SOURCES:
        with open(os.path.join(L.ROOT, "sample-deliverable", name), "rb") as fh:
            lines.append(f"{hashlib.sha256(fh.read()).hexdigest()}  {name}")
    return "\n".join(lines) + "\n"


def main() -> int:
    if "--stamp-pdfs" in sys.argv:
        with open(PDF_STAMP, "w") as fh:
            fh.write(pdf_source_hashes())
        print(f"wrote {os.path.relpath(PDF_STAMP, L.ROOT)}")
        return 0
    check = "--check" in sys.argv
    runs = L.load_runs()
    values = build_values(runs)
    stale = []
    for src_dir, name, out_dir in TARGETS:
        with open(os.path.join(src_dir, name + ".tmpl")) as fh:
            rendered = render(fh.read(), values)
        target = os.path.join(out_dir, name)
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
    if check:
        current = open(PDF_STAMP).read() if os.path.exists(PDF_STAMP) else None
        if current != pdf_source_hashes():
            print("PDFs out of date: run node scripts/export_pdf.js sample-deliverable/*.md, then "
                  "python3 scripts/generate_report.py --stamp-pdfs", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
