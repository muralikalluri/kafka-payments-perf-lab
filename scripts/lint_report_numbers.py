#!/usr/bin/env python3
"""Fails if a report template (or the README template) contains a bare number in its prose.

Numbers must arrive through {{placeholders}} generated from results/*. Allowed without a placeholder:
finding and ADR ids, milestone ids, HTTP status codes and other fixed technical names listed below,
anything inside code fences or inline code, and list numbering.
"""
from __future__ import annotations

import glob
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ALLOWED = [
    r"\{\{[^}]*\}\}", r"`[^`]*`", r"F-\d+", r"ADR-\d+", r"\bM\d\b", r"§\s*\d+(?:\.\d+)?",
    r"\bp\d{2}\b", r"\blz4\b", r"\bJava 21\b", r"\bPostgres 16\b", r"\bRedis 7\b", r"\bKafka 3(?:\.\d+)?\b",
    r"\bSpring Boot 3(?:\.\d+)?\b", r"\bk6\b", r"\bSHA-256\b", r"\bUUIDv5\b", r"pacs\.008", r"\bRF=1\b",
    r"\b(?:202|404|422|503)\b", r"\bx-axis\b", r"\bN\+1\b", r"\b[Ss]ections? \d+(?:\.\d+)?\b", r"\bAppendix [A-C]\b", r"\bQ-[A-Z]\b", r"\bv0\.1\.0\b",
    r"^\s*\d+\.\s", r"^#+ [\dA-Z]*\.? ?", r"\[[^\]]*\]\([^)]*\)", r"https?://\S+",
]


def strip(text: str) -> str:
    # Blank out fenced code and comments but keep their newlines so reported line numbers stay right.
    text = re.sub(r"```.*?```", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    text = re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    out = []
    for line in text.splitlines():
        for pattern in ALLOWED:
            line = re.sub(pattern, " ", line)
        out.append(line)
    return "\n".join(out)


def lint(path: str) -> list:
    problems = []
    with open(path) as fh:
        text = fh.read()
    original = text.splitlines()
    for i, line in enumerate(strip(text).splitlines()):
        m = re.search(r"\d", line)
        if m:
            src = original[i] if i < len(original) else line
            problems.append(f"{os.path.relpath(path, ROOT)}:{i + 1}: bare number in prose: {src.strip()[:100]}")
    return problems


def main() -> int:
    paths = sys.argv[1:] or sorted(glob.glob(os.path.join(ROOT, "sample-deliverable", "src", "*.tmpl"))
                                    + glob.glob(os.path.join(ROOT, "report", "src", "*.tmpl")))
    problems = [p for path in paths for p in lint(path)]
    for p in problems:
        print(p, file=sys.stderr)
    if problems:
        print(f"{len(problems)} bare number(s): move them behind a generated placeholder.", file=sys.stderr)
        return 1
    print(f"ok: no bare numbers in {len(paths)} template(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
