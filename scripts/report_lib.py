#!/usr/bin/env python3
"""Loads benchmark results and turns them into report facts and Markdown tables.

Everything a report says with a number comes through this module from results/*/result.json,
env.txt and explain.txt; nothing is typed by hand. The wording rules here encode what the data does and
does not support (step-resolution bounds, unrecovered spikes, cold-start steps, no per-finding effect).
"""
from __future__ import annotations

import glob
import json
import os
import re
from dataclasses import dataclass, field

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
RUN_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})_(baseline|tuned)_([a-z]+)(?:_(.+))?$")


@dataclass
class Run:
    key: str
    dir: str
    date: str
    profile: str
    scenario: str
    suffix: str | None
    result: dict
    env: dict = field(default_factory=dict)
    limits: list = field(default_factory=list)
    topics: dict = field(default_factory=dict)
    tuned_config: str = ""
    topic_details: dict = field(default_factory=dict)
    profile_summary: dict = field(default_factory=dict)
    explain: str | None = None

    @property
    def phases(self):
        return self.result.get("phases", [])


def _parse_env(text: str):
    env, limits, topics, tuned, mode = {}, [], {}, [], None
    details = {}
    for line in text.splitlines():
        if line.startswith("tuned_config:"):
            mode = "tuned"
            continue
        if line.startswith("topics:"):
            mode = "topics"
            continue
        if mode == "tuned":
            tuned.append(line)
            continue
        if mode == "topics":
            m = re.search(r"Topic: (\S+).*PartitionCount: (\d+)\s+ReplicationFactor: (\d+)(?:\s+Configs: (.*))?", line)
            if m:
                topics[m.group(1)] = int(m.group(2))
                isr = re.search(r"min\.insync\.replicas=(\d+)", m.group(4) or "")
                details[m.group(1)] = {"partitions": int(m.group(2)), "replication": int(m.group(3)),
                                       "min_isr": int(isr.group(1)) if isr else None}
            continue
        if line.startswith("limit "):
            limits.append(line[len("limit "):])
        elif "=" in line:
            k, v = line.split("=", 1)
            env[k] = v
    return env, limits, topics, "\n".join(tuned), details


def load_runs(results_dir: str | None = None) -> dict:
    results_dir = results_dir or os.path.join(ROOT, "results")
    runs = {}
    for path in sorted(glob.glob(os.path.join(results_dir, "*"))):
        m = RUN_RE.match(os.path.basename(path))
        if not m or not os.path.exists(os.path.join(path, "result.json")):
            continue
        date, profile, scenario, suffix = m.groups()
        key = f"{profile}_{scenario}" + (f"_{suffix}" if suffix else "")
        with open(os.path.join(path, "result.json")) as fh:
            result = json.load(fh)
        with open(os.path.join(path, "env.txt")) as fh:
            env, limits, topics, tuned, details = _parse_env(fh.read())
        profile_summary = {}
        if os.path.exists(os.path.join(path, "profile-summary.json")):
            with open(os.path.join(path, "profile-summary.json")) as fh:
                profile_summary = json.load(fh)
        explain = None
        if os.path.exists(os.path.join(path, "explain.txt")):
            with open(os.path.join(path, "explain.txt")) as fh:
                explain = fh.read()
        runs[key] = Run(key, path, date, profile, scenario, suffix, result, env, limits, topics, tuned,
                        explain=explain, topic_details=details, profile_summary=profile_summary)
    return runs


# ---------------------------------------------------------------- formatting

def ms(v) -> str:
    if v is None:
        return "n/a"
    return f"{v / 1000:.1f} s" if v >= 1000 else f"{v:.0f} ms"


def rate(v) -> str:
    return "n/a" if v is None else f"{v:.0f}"


def yes_no(v: bool) -> str:
    return "yes" if v else "no"


def table(headers, rows) -> str:
    out = ["| " + " | ".join(headers) + " |", "|" + "|".join("---" for _ in headers) + "|"]
    out += ["| " + " | ".join(str(c) for c in row) + " |" for row in rows]
    return "\n".join(out)


def run_label(run: Run) -> str:
    label = f"`{os.path.basename(run.dir)}`"
    if run.suffix == "recorded":
        label += " (recorded during the dashboard capture)"
    if is_failover(run):
        label += " (brokers stopped during the load)"
    if is_profiled(run):
        label += " (profiled with Java Flight Recorder)"
    if run.suffix == "zgc":
        label += " (ZGC instead of G1)"
    if run.suffix and run.suffix.startswith("ablate"):
        label += " (one tuned change switched off)"
    return label


def rel(path: str) -> str:
    return os.path.relpath(path, ROOT)


def tuned_blocks(run: Run) -> dict:
    """The tuned configuration recorded in a run's env.txt, split per service."""
    blocks, name = {}, None
    for line in run.tuned_config.splitlines():
        m = re.match(r"\s*--- (\S+?)-tuned\.yml", line)
        if m:
            name = m.group(1)
            blocks[name] = []
        elif name is not None:
            blocks[name].append(line[2:] if line.startswith("  ") else line)
    return {k: "\n".join(v) for k, v in blocks.items()}


# --------------------------------------------------------------------- facts

def is_failover(run: Run) -> bool:
    return bool(run.suffix) and run.suffix.startswith("failover")


def is_profiled(run: Run) -> bool:
    return bool(run.suffix) and run.suffix.startswith("flame")


VARIANT_PREFIXES = ("failover", "flame", "zgc", "ablate")


def is_variant(run: Run) -> bool:
    """A run of a modified setup (broker failure, profiling, another collector, one change switched off). It is compared
    against the standard run in its own table and never feeds the capacity bounds."""
    return bool(run.suffix) and run.suffix.startswith(VARIANT_PREFIXES)


def is_ladder(run: Run) -> bool:
    """A steady run of the standard setup that climbs a ladder of offered rates (standard or extended)."""
    return run.scenario == "steady" and not is_variant(run)


def pass_through(run: Run):
    """(highest offered rate up to which every step met the SLO, first rate that did not) for one run."""
    passed, failed = None, None
    for p in run.phases:
        if p["meets_slo"] and failed is None:
            passed = p["target_req_per_s"]
        elif not p["meets_slo"] and failed is None:
            failed = p["target_req_per_s"]
    return passed, failed


def steady_bounds(runs: dict, profile: str):
    """Step-resolution bounds for a profile: (holds through, misses at) across all its steady runs."""
    standard = runs.get(f"{profile}_steady")
    passed, failed = pass_through(standard) if standard else (None, None)
    if passed is None:
        return None, None
    fails = []
    for key, run in runs.items():
        if run.profile == profile and is_ladder(run):
            fails += [p["target_req_per_s"] for p in run.phases
                      if not p["meets_slo"] and p["target_req_per_s"] > passed]
    return passed, (min(fails) if fails else None)


def steady_fail_source(runs: dict, profile: str):
    """Name of the run in which the profile's first missed step above its sustained rate was offered."""
    passed, failed = steady_bounds(runs, profile)
    if failed is None:
        return None
    for run in runs.values():
        if run.profile == profile and is_ladder(run):
            for p in run.phases:
                if p["target_req_per_s"] == failed and not p["meets_slo"]:
                    return os.path.basename(run.dir)
    return None


def cold_first_step_note(runs: dict) -> str:
    """Sentence about first steps that missed the SLO although the same rate passed when reached later."""
    passing = {(r.profile, p["target_req_per_s"]) for r in runs.values()
               if is_ladder(r) for i, p in enumerate(r.phases) if i > 0 and p["meets_slo"]}
    notes = []
    for r in runs.values():
        if not is_ladder(r) or not r.phases:
            continue
        first = r.phases[0]
        if not first["meets_slo"] and (r.profile, first["target_req_per_s"]) in passing:
            notes.append(f"`{os.path.basename(r.dir)}` (first step {rate(first['target_req_per_s'])} req/s)")
    if not notes:
        return ""
    return ("In " + " and ".join(notes) + " the first step missed the SLO although the same offered rate "
            "met it when reached after lighter steps in another run. That is consistent with warm-up "
            "(JIT compilation, connection pools, caches); it was observed once per run and is not proven.")


def spike_recovery_text(run: Run) -> str:
    spike = run.result["spike"]
    if not spike["recovered_within_run"]:
        window = run.result["params"]["recover_seconds"]
        return (f"not recovered within the observed window (the recovery phase lasted {window} s); "
                "the true recovery time is unknown")
    seconds = spike["recovery_seconds_after_burst"]
    if seconds == 0:
        return "no degradation was observed, and payments created after the burst all met the SLO"
    return f"recovered {seconds:.0f} s after the burst ended"


def _short_java(text: str) -> str:
    m = re.search(r'"([\d.]+)"', text)
    return f"Java {m.group(1)}" if m else text


def _short_k6(text: str) -> str:
    m = re.match(r"k6 (v[\d.]+)", text)
    return f"k6 {m.group(1)}" if m else text


def env_summary(runs: dict) -> dict:
    any_run = next(iter(runs.values()))
    e = any_run.env
    shas = sorted({r.result["git_sha"][:7] for r in runs.values()})
    return {
        "cpu_model": e["cpu_model"], "cores": e["cpu_cores"],
        "ram_gb": f"{int(e['ram_bytes']) / 2**30:.0f}",
        "docker_vm": e["docker_vm"].replace("mem_bytes=", "memory bytes ="),
        "java": _short_java(e["java"]), "k6": _short_k6(e["k6"]), "os": e["os"],
        "git_shas": ", ".join(f"`{s}`" for s in shas),
        "git_revisions_label": "revision" if len(shas) == 1 else "revisions",
        "date": any_run.date,
        "note": e["note"][:1].upper() + e["note"][1:],
    }


# -------------------------------------------------------------------- tables

def steady_table(run: Run) -> str:
    rows = []
    for p in run.phases:
        e = p["e2e_ms"]
        rows.append([rate(p["target_req_per_s"]), rate(p["achieved_req_per_s"]), ms(p["post_p99_ms"]),
                     ms(e["p50"]), ms(e["p99"]), p["dropped_iterations"], yes_no(p["meets_slo"])])
    return table(["Offered (req/s)", "Achieved (req/s)", "POST p99", "End-to-end p50", "End-to-end p99",
                  "Dropped iterations", "SLO met"], rows)


def steady_comparison(runs: dict) -> str:
    b, t = runs.get("baseline_steady"), runs.get("tuned_steady")
    if not b or not t:
        return "_(steady results missing)_"
    rows = []
    for pb, pt in zip(b.phases, t.phases):
        rows.append([rate(pb["target_req_per_s"]),
                     ms(pb["e2e_ms"]["p50"]), ms(pb["e2e_ms"]["p99"]), yes_no(pb["meets_slo"]),
                     ms(pt["e2e_ms"]["p50"]), ms(pt["e2e_ms"]["p99"]), yes_no(pt["meets_slo"])])
    return table(["Offered (req/s)", "Baseline e2e p50", "Baseline e2e p99", "Baseline SLO",
                  "Tuned e2e p50", "Tuned e2e p99", "Tuned SLO"], rows)


def spike_comparison(runs: dict) -> str:
    b, t = runs.get("baseline_spike"), runs.get("tuned_spike")
    if not b or not t:
        return "_(spike results missing)_"
    rows = []
    for pb, pt in zip(b.phases, t.phases):
        rows.append([pb["phase"], rate(pb["target_req_per_s"]),
                     ms(pb["e2e_ms"]["p50"]), ms(pb["e2e_ms"]["p99"]), pb["dropped_iterations"],
                     ms(pt["e2e_ms"]["p50"]), ms(pt["e2e_ms"]["p99"]), pt["dropped_iterations"]])
    return table(["Phase", "Offered (req/s)", "Baseline e2e p50", "Baseline e2e p99", "Baseline dropped",
                  "Tuned e2e p50", "Tuned e2e p99", "Tuned dropped"], rows)


def smoke_comparison(runs: dict) -> str:
    rows = []
    for prof in ("baseline", "tuned"):
        r = runs.get(f"{prof}_smoke")
        if r:
            e = r.result["pipeline"]["e2e_ms"]
            rows.append([prof, r.result["pipeline"]["payments_created"], ms(e["p50"]), ms(e["p95"]), ms(e["p99"]),
                         f"{r.result['k6']['http_req_failed_rate'] * 100:.2f}%",
                         yes_no(r.result["slo"].get("p99_e2e_under_500ms", False))])
    return table(["Profile", "Payments created", "End-to-end p50", "p95", "p99", "HTTP error rate", "p99 under SLO"], rows)


def extra_runs_table(runs: dict) -> str:
    rows = []
    for key in sorted(k for k, r in runs.items() if is_ladder(r) and r.suffix):
        r = runs[key]
        for i, p in enumerate(r.phases):
            rows.append([f"`{os.path.basename(r.dir)}`", "yes" if i == 0 else "no", rate(p["target_req_per_s"]),
                         rate(p["achieved_req_per_s"]), ms(p["post_p99_ms"]), ms(p["e2e_ms"]["p50"]),
                         ms(p["e2e_ms"]["p99"]), p["dropped_iterations"], yes_no(p["meets_slo"])])
    return table(["Run", "First step", "Offered (req/s)", "Achieved (req/s)", "POST p99", "End-to-end p50",
                  "End-to-end p99", "Dropped iterations", "SLO met"], rows)


def invariants_table(runs: dict, profile=None) -> str:
    rows = []
    for key in sorted(runs):
        r = runs[key]
        if profile and r.profile != profile:
            continue
        i = r.result["invariants"]
        rows.append([run_label(r), r.result["pipeline"]["payments_created"],
                     yes_no(i["all_hold"]), i["negative_balances"], i["debits_minus_credits_minor"],
                     i["non_terminal_payments"], i["sequence_conflicts"]])
    return table(["Run", "Payments created", "All invariants hold", "Negative balances",
                  "Debits minus credits (minor units)", "Non-terminal payments", "Sequence conflicts"], rows)


def explain_rows(runs: dict) -> list:
    """[(profile, query, plan node, rows removed, execution time)] from the steady runs' explain.txt."""
    rows = []
    for prof in ("baseline", "tuned"):
        r = runs.get(f"{prof}_steady")
        if not r or not r.explain:
            continue
        for title, section in re.findall(r"== (F-06 [^\n]+)\n(.*?)(?=\n\n==|\Z)", r.explain, re.S):
            scans = re.findall(r"([A-Za-z][A-Za-z ]*Scan[^(]*?)\s+\(cost", section)  # deepest scan node
            node = scans[-1] if scans else None
            exec_ms = re.search(r"Execution Time: ([\d.]+) ms", section)
            removed = re.search(r"Rows Removed by Filter: (\d+)", section)
            rows.append([prof, title.split(" (")[0].replace("F-06 ", ""),
                         node.strip() if node else "n/a",
                         removed.group(1) if removed else "none reported",
                         f"{float(exec_ms.group(1)):.2f} ms" if exec_ms else "n/a"])
    return rows


def explain_table(runs: dict) -> str:
    return table(["Profile", "Query", "Plan node", "Rows removed by filter", "Execution time"], explain_rows(runs))


def partition_table(runs: dict, profiles=("baseline", "tuned")) -> str:
    rows = []
    for prof in profiles:
        r = runs.get(f"{prof}_steady")
        if r:
            t = r.topics
            rows.append([prof, t.get("payments.initiated"), t.get("payments.validated"), t.get("payments.posted"),
                         t.get("payments.validated.DLT")])
    return table(["Profile", "payments.initiated", "payments.validated", "payments.posted", "payments.validated.DLT"], rows)


def limits_table(runs: dict) -> str:
    any_run = next(iter(runs.values()))
    rows = []
    for line in any_run.limits:
        m = re.match(r"(\S+) cpus=([\d.]+) mem_bytes=(\d+)", line)
        if m:
            rows.append([m.group(1), m.group(2), f"{int(m.group(3)) / 2**20:.0f} MiB"])
    return table(["Container", "CPU limit", "Memory limit"], rows)


def results_index(runs: dict) -> str:
    rows = []
    for key in sorted(runs):
        r = runs[key]
        base = rel(r.dir)
        links = [f"[result.json](../{base}/result.json)", f"[summary.json](../{base}/summary.json)",
                 f"[env.txt](../{base}/env.txt)"]
        if r.explain:
            links.append(f"[explain.txt](../{base}/explain.txt)")
        rows.append([run_label(r), r.result["git_sha"][:7], " · ".join(links)])
    return table(["Run", "Git sha", "Files"], rows)


def params_table(runs: dict) -> str:
    rows = []
    for key in sorted(runs):
        r = runs[key]
        p = r.result["params"]
        if r.scenario == "steady":
            desc = f"steps {', '.join(str(x) for x in p['steps_req_per_s'])} req/s, {p['step_seconds']} s each"
        elif r.scenario == "spike":
            desc = (f"base {p['base_req_per_s']} req/s, burst {p['burst_req_per_s']} req/s for {p['burst_seconds']} s "
                    f"after {p['warm_seconds']} s, then {p['recover_seconds']} s recovery")
        elif r.scenario == "soak":
            desc = f"{p['req_per_s']} req/s for {p['minutes']} minutes"
        elif r.scenario == "gatling":
            desc = f"Gatling, {p['req_per_s']} req/s for {p['seconds']} s"
        else:
            desc = f"{p['vus']} virtual users for {p['duration_seconds']} s"
        rows.append([run_label(r), desc])
    return table(["Run", "Scenario parameters"], rows)


def adr_table() -> str:
    """Links to every ADR, titled from its own first heading."""
    rows = []
    for path in sorted(glob.glob(os.path.join(ROOT, "docs", "adr", "[0-9]*.md"))):
        with open(path) as fh:
            title = fh.readline().lstrip("# ").strip()
        name = os.path.basename(path)
        rows.append([f"[{title.split(':')[0]}](docs/adr/{name})", title.split(":", 1)[1].strip() if ":" in title else title])
    return table(["ADR", "Decision"], rows)


def kafka_topology(runs: dict) -> str:
    """The broker layout the recorded runs actually used, from each run's env.txt."""
    seen = set()
    for r in runs.values():
        brokers = int(r.env.get("kafka_brokers", "1"))
        rf = max((d["replication"] for d in r.topic_details.values()), default=1)
        isr = next((d["min_isr"] for d in r.topic_details.values() if d.get("min_isr")), None)
        seen.add((brokers, rf, isr))
    if len(seen) != 1:
        return "Kafka with differing broker layouts across the recorded runs (see each run's env.txt)"
    brokers, rf, isr = seen.pop()
    if brokers == 1:
        return "a single Kafka broker with replication factor " + str(rf)
    return (f"a {brokers}-broker Kafka cluster with replication factor {rf}"
            + (f" and min.insync.replicas {isr}" if isr else ""))


def failover_table(runs: dict) -> str:
    rows = []
    for key in sorted(k for k, r in runs.items() if is_failover(r)):
        r = runs[key]
        p = r.phases[0]
        mode = "two brokers" if r.suffix.endswith("two") else "one broker"
        i = r.result["invariants"]
        rows.append([r.profile, mode, rate(p["target_req_per_s"]), f"{r.result['k6']['http_req_failed_rate'] * 100:.2f}%",
                     ms(p["e2e_ms"]["p50"]), ms(p["e2e_ms"]["p99"]), p["dropped_iterations"],
                     yes_no(i["all_hold"]), i["sequence_conflicts"]])
    if not rows:
        return "_(no broker-failure runs are recorded)_"
    return table(["Profile", "Stopped", "Offered (req/s)", "HTTP error rate", "End-to-end p50", "End-to-end p99",
                  "Dropped iterations", "Invariants hold", "Sequence conflicts"], rows)


def soak_tables(runs: dict) -> str:
    """Per-window latency and resource growth for every soak run."""
    soaks = [runs[k] for k in sorted(runs) if runs[k].scenario == "soak"]
    if not soaks:
        return "_(no soak runs are recorded)_"
    out = []
    for r in soaks:
        soak = r.result["soak"]
        p = r.result["params"]
        rows = [[w["window"], w["payments"], ms(w["e2e_p50_ms"]), ms(w["e2e_p99_ms"])] for w in soak["windows"]]
        out.append(f"**{r.profile}**: {p['req_per_s']} req/s for {p['minutes']} minutes, in windows of "
                   f"{soak['window_seconds']} s\n\n" + table(["Window", "Payments created", "End-to-end p50", "End-to-end p99"], rows))
        growth = soak["last_quarter_over_first_quarter"]
        if growth:
            out.append(table(["Series (last quarter average over first quarter average)", "Ratio"],
                             [[k, f"{v:.2f}"] for k, v in sorted(growth.items())]))
    return "\n\n".join(out)


def profile_tables(runs: dict) -> str:
    """Hottest leaf frames per service and view from JFR, with links to the flame graphs."""
    profiled = [runs[k] for k in sorted(runs) if runs[k].profile_summary]
    if not profiled:
        return "_(no profiling runs are recorded)_"
    out = []
    for r in profiled:
        rows = []
        links = []
        for service, views in sorted(r.profile_summary.items()):
            for view, data in views.items():
                top = "; ".join(f"`{f['frame'].rsplit('.', 2)[-2] + '.' + f['frame'].rsplit('.', 1)[-1] if f['frame'].count('.') > 1 else f['frame']}` "
                                f"{f['share'] * 100:.0f}%" for f in data["top_frames"][:3])
                rows.append([service, view, data["samples"], top or "no samples"])
                links.append(f"[{service} {view}](../{rel(r.dir)}/flamegraph-{view}-{service}.svg)")
        out.append(f"**{r.profile}** ({run_label(r)}); flame graphs: " + ", ".join(links) + "\n\n"
                   + table(["Service", "View", "Samples", "Hottest frames (share of samples)"], rows))
    return "\n\n".join(out)


def gatling_table(runs: dict) -> str:
    rows = []
    for key in sorted(k for k, r in runs.items() if r.scenario == "gatling"):
        r = runs[key]
        k = r.result["k6"]
        e = r.result["pipeline"]["e2e_ms"]
        rows.append([r.profile, k["http_reqs"], f"{k['http_req_failed_rate'] * 100:.2f}%", ms(k["post_latency_ms"]["p50"]),
                     ms(k["post_latency_ms"]["p99"]), ms(e["p50"]), ms(e["p99"]), yes_no(r.result["invariants"]["all_hold"])])
    if not rows:
        return "_(no Gatling runs are recorded)_"
    return table(["Profile", "Requests", "HTTP error rate", "POST p50", "POST p99", "End-to-end p50", "End-to-end p99",
                  "Invariants hold"], rows)


def ladder_compare(runs: dict, key_a: str, key_b: str, label_a: str, label_b: str) -> str:
    """Two steady runs with the same offered rates, side by side."""
    a, b = runs.get(key_a), runs.get(key_b)
    if not a or not b:
        return (f"_(no recorded run pairs {label_a} with {label_b}: the change was implemented after the recorded "
                "benchmark runs, so this comparison has no data)_")
    if [p["target_req_per_s"] for p in a.phases] != [p["target_req_per_s"] for p in b.phases]:
        return "_(the two runs offered different rates, so they are not compared side by side)_"
    rows = []
    for pa, pb in zip(a.phases, b.phases):
        rows.append([rate(pa["target_req_per_s"]),
                     ms(pa["e2e_ms"]["p50"]), ms(pa["e2e_ms"]["p99"]), yes_no(pa["meets_slo"]),
                     ms(pb["e2e_ms"]["p50"]), ms(pb["e2e_ms"]["p99"]), yes_no(pb["meets_slo"])])
    return table(["Offered (req/s)", f"{label_a} e2e p50", f"{label_a} e2e p99", f"{label_a} SLO",
                  f"{label_b} e2e p50", f"{label_b} e2e p99", f"{label_b} SLO"], rows)


def env_value(runs: dict, key: str, field: str, default: str = "unknown") -> str:
    r = runs.get(key)
    return r.env.get(field, default) if r else default


def _metric_max(run: Run, name: str, exclude=()):
    """Highest value of a stored series over the run, across its labels."""
    path = os.path.join(run.dir, "metrics.json")
    if not os.path.exists(path):
        return None
    series = json.load(open(path)).get("series", {}).get(name, {})
    values = [v["max"] for k, v in series.items() if isinstance(v, dict) and "max" in v and not k.startswith(exclude)]
    return max(values) if values else None


def _container_max(run: Run, name: str):
    path = os.path.join(run.dir, "metrics.json")
    if not os.path.exists(path):
        return None
    c = json.load(open(path)).get("containers", {}).get(name)
    return c["cpu_percent_max"] if c else None


def all_have_stage_metrics(runs: dict) -> bool:
    standard = [runs.get(f"{p}_{s}") for p in ("baseline", "tuned") for s in ("smoke", "steady", "spike")]
    return all(r is not None and os.path.exists(os.path.join(r.dir, "metrics.json")) for r in standard)


def stage_evidence(runs: dict) -> str:
    """Peaks over the whole steady run (all steps, including overloaded ones), baseline against tuned."""
    b, t = runs.get("baseline_steady"), runs.get("tuned_steady")
    if not b or not t or not all_have_stage_metrics(runs):
        return "_(no stage metrics are recorded for these runs)_"

    def cell(run, value, fmt):
        return "n/a" if value is None else fmt(value)

    rows = []
    specs = [
        ("Peak consumer lag (records, worst group)", lambda r: _metric_max(r, "consumer_lag", ("validation-cache-",)), lambda v: f"{v:,.0f}"),
        ("Peak pending connection requests (worst service)", lambda r: _metric_max(r, "hikari_pending"), lambda v: f"{v:.0f}"),
        ("Peak heap in use (worst service)", lambda r: _metric_max(r, "jvm_heap_used_bytes"), lambda v: f"{v / 2**20:.0f} MiB"),
        ("Peak process CPU (worst service)", lambda r: _metric_max(r, "process_cpu_usage"), lambda v: f"{v * 100:.0f}%"),
        ("Peak garbage-collection time (share of time, worst service)", lambda r: _metric_max(r, "gc_pause_seconds_per_second"), lambda v: f"{v * 100:.1f}%"),
        ("Peak database locks held or awaited", lambda r: _metric_max(r, "pg_locks"), lambda v: f"{v:.0f}"),
        ("Deadlocks per second (peak)", lambda r: _metric_max(r, "pg_deadlocks_per_second"), lambda v: f"{v:.2f}"),
        ("Peak ledger outbox backlog (rows)", lambda r: _metric_max(r, "ledger_outbox_backlog"), lambda v: f"{v:,.0f}"),
        ("Peak gateway outbox backlog (rows)", lambda r: _metric_max(r, "gateway_outbox_backlog"), lambda v: f"{v:,.0f}"),
        ("Postgres container peak CPU", lambda r: _container_max(r, "postgres"), lambda v: f"{v:.0f}%"),
    ]
    for label, get, fmt in specs:
        rows.append([label, cell(b, get(b), fmt), cell(t, get(t), fmt)])
    return table(["Observation over the whole steady run", "Baseline", "Tuned"], rows)
