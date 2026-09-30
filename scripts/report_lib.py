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
    explain: str | None = None

    @property
    def phases(self):
        return self.result.get("phases", [])


def _parse_env(text: str):
    env, limits, topics, tuned, mode = {}, [], {}, [], None
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
            m = re.search(r"Topic: (\S+).*PartitionCount: (\d+)", line)
            if m:
                topics[m.group(1)] = int(m.group(2))
            continue
        if line.startswith("limit "):
            limits.append(line[len("limit "):])
        elif "=" in line:
            k, v = line.split("=", 1)
            env[k] = v
    return env, limits, topics, "\n".join(tuned)


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
            env, limits, topics, tuned = _parse_env(fh.read())
        explain = None
        if os.path.exists(os.path.join(path, "explain.txt")):
            with open(os.path.join(path, "explain.txt")) as fh:
                explain = fh.read()
        runs[key] = Run(key, path, date, profile, scenario, suffix, result, env, limits, topics, tuned, explain)
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
        if run.profile == profile and run.scenario == "steady":
            fails += [p["target_req_per_s"] for p in run.phases
                      if not p["meets_slo"] and p["target_req_per_s"] > passed]
    return passed, (min(fails) if fails else None)


def steady_fail_source(runs: dict, profile: str):
    """Name of the run in which the profile's first missed step above its sustained rate was offered."""
    passed, failed = steady_bounds(runs, profile)
    if failed is None:
        return None
    for run in runs.values():
        if run.profile == profile and run.scenario == "steady":
            for p in run.phases:
                if p["target_req_per_s"] == failed and not p["meets_slo"]:
                    return os.path.basename(run.dir)
    return None


def cold_first_step_note(runs: dict) -> str:
    """Sentence about first steps that missed the SLO although the same rate passed when reached later."""
    passing = {(r.profile, p["target_req_per_s"]) for r in runs.values()
               if r.scenario == "steady" for i, p in enumerate(r.phases) if i > 0 and p["meets_slo"]}
    notes = []
    for r in runs.values():
        if r.scenario != "steady" or not r.phases:
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
    for key in sorted(k for k, r in runs.items() if r.scenario == "steady" and r.suffix):
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
        rows.append([f"`{os.path.basename(r.dir)}`", r.result["pipeline"]["payments_created"],
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
        rows.append([f"`{os.path.basename(r.dir)}`", r.result["git_sha"][:7], " · ".join(links)])
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
        else:
            desc = f"{p['vus']} virtual users for {p['duration_seconds']} s"
        rows.append([f"`{os.path.basename(r.dir)}`", desc])
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
