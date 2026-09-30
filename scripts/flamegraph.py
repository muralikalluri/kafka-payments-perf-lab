#!/usr/bin/env python3
"""Turns Java Flight Recorder files into SVG flame graphs and a small top-frames summary.

For every raw/<service>.jfr in a result folder it writes:
  flamegraph-cpu-<service>.svg     from jdk.ExecutionSample (threads running Java code)
  flamegraph-native-<service>.svg  from jdk.NativeMethodSample (threads in native calls: socket reads, waits)
  profile-summary.json             the hottest leaf frames per service and view, as shares of the samples

The raw .jfr files are large and not committed; the SVGs and the summary are. JFR samples running threads on a fixed
interval, so a flame graph shows where time went in code that was running or blocked in native calls, not everything a
thread waited on. Requires the JDK's `jfr` tool.

Usage: flamegraph.py --run-dir results/<run>            (all raw/*.jfr)
       flamegraph.py --jfr file.jfr --out out.svg [--event jdk.ExecutionSample] [--title text]
"""
import argparse
import glob
import hashlib
import html
import json
import os
import shutil
import subprocess
import sys
from collections import Counter

VIEWS = {"cpu": "jdk.ExecutionSample", "native": "jdk.NativeMethodSample"}
MIN_FRACTION = 0.002       # frames narrower than this share of the samples are folded away
FRAME_HEIGHT = 16
WIDTH = 1200


def find_jfr() -> str:
    """The JDK's jfr tool: on the PATH, under JAVA_HOME, or next to the java binary that is on the PATH."""
    found = shutil.which("jfr")
    if found:
        return found
    home = os.environ.get("JAVA_HOME")
    if home and os.path.exists(os.path.join(home, "bin", "jfr")):
        return os.path.join(home, "bin", "jfr")
    java = shutil.which("java")
    if java:
        candidate = os.path.join(os.path.dirname(os.path.realpath(java)), "jfr")
        if os.path.exists(candidate):
            return candidate
    raise SystemExit("the JDK's jfr tool was not found (put a JDK's bin directory on the PATH)")


def load_stacks(jfr_path: str, event: str) -> Counter:
    """Counter of root-to-leaf stacks (tuples of 'Class.method') for one event type."""
    out = subprocess.run([find_jfr(), "print", "--json", "--events", event, jfr_path], capture_output=True, text=True, check=True).stdout
    stacks = Counter()
    for ev in json.loads(out)["recording"]["events"]:
        frames = (ev["values"].get("stackTrace") or {}).get("frames") or []
        names = []
        for f in reversed(frames):            # JFR lists the leaf first
            m = f["method"]
            names.append(f"{m['type']['name']}.{m['name']}")
        if names:
            stacks[tuple(names)] += 1
    return stacks


def build_tree(stacks: Counter) -> dict:
    root = {"name": "all", "count": 0, "children": {}}
    for stack, n in stacks.items():
        root["count"] += n
        node = root
        for name in stack:
            child = node["children"].setdefault(name, {"name": name, "count": 0, "children": {}})
            child["count"] += n
            node = child
    return root


def colour(name: str) -> str:
    h = int(hashlib.md5(name.encode()).hexdigest()[:6], 16)
    return f"rgb({205 + h % 50},{80 + (h >> 8) % 120},{(h >> 16) % 55})"   # warm palette


def render_svg(root: dict, title: str) -> str:
    total = root["count"]
    if total == 0:
        return f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="40"><text x="10" y="25">{html.escape(title)}: no samples</text></svg>'
    rects = []
    max_depth = 0

    def walk(node, depth, x):
        nonlocal max_depth
        w = node["count"] / total * WIDTH
        max_depth = max(max_depth, depth)
        rects.append((node["name"], node["count"], depth, x, w))
        cx = x
        for child in sorted(node["children"].values(), key=lambda c: -c["count"]):
            if child["count"] / total < MIN_FRACTION:
                continue
            walk(child, depth + 1, cx)
            cx += child["count"] / total * WIDTH

    walk(root, 0, 0.0)
    height = (max_depth + 2) * FRAME_HEIGHT + 30
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{height}" font-family="monospace" font-size="11">',
             f'<text x="10" y="18" font-size="13" font-weight="bold">{html.escape(title)} ({total} samples)</text>']
    for name, count, depth, x, w in rects:
        y = height - (depth + 1) * FRAME_HEIGHT - 6
        label = html.escape(name)
        parts.append(f'<g><title>{label} ({count} samples, {count / total * 100:.1f}%)</title>'
                     f'<rect x="{x:.1f}" y="{y}" width="{max(w - 0.5, 0.5):.1f}" height="{FRAME_HEIGHT - 1}" fill="{colour(name)}"/>')
        chars = int(w / 6.5)
        if chars > 3:
            parts.append(f'<text x="{x + 3:.1f}" y="{y + 11}">{label[-chars:] if len(name) > chars else label}</text>')
        parts.append("</g>")
    parts.append("</svg>")
    return "\n".join(parts)


def top_leaves(stacks: Counter, n: int = 8) -> list:
    total = sum(stacks.values()) or 1
    leaves = Counter()
    for stack, c in stacks.items():
        leaves[stack[-1]] += c
    return [{"frame": name, "samples": c, "share": round(c / total, 4)} for name, c in leaves.most_common(n)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run-dir")
    ap.add_argument("--jfr")
    ap.add_argument("--out")
    ap.add_argument("--event", default="jdk.ExecutionSample")
    ap.add_argument("--title", default="flame graph")
    a = ap.parse_args()

    if a.jfr:
        stacks = load_stacks(a.jfr, a.event)
        with open(a.out, "w") as fh:
            fh.write(render_svg(build_tree(stacks), a.title))
        print(f"wrote {a.out}")
        return 0

    summary = {}
    for jfr in sorted(glob.glob(os.path.join(a.run_dir, "raw", "*.jfr"))):
        service = os.path.basename(jfr)[:-4]
        for view, event in VIEWS.items():
            stacks = load_stacks(jfr, event)
            svg = os.path.join(a.run_dir, f"flamegraph-{view}-{service}.svg")
            with open(svg, "w") as fh:
                fh.write(render_svg(build_tree(stacks), f"{service} ({view})"))
            summary.setdefault(service, {})[view] = {"samples": sum(stacks.values()), "top_frames": top_leaves(stacks)}
            print(f"wrote {svg}")
    with open(os.path.join(a.run_dir, "profile-summary.json"), "w") as fh:
        json.dump(summary, fh, indent=1)
        fh.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
