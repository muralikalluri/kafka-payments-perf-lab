#!/usr/bin/env python3
"""Fails if a report template contains a number in its prose that is not a generated placeholder.

Numbers must arrive through {{placeholders}} generated from results/* or the configuration files.
Checked: digits, number words (two, eighty, percent, twice, half ...), in prose, headings, table cells,
link text and inline code alike, and the string values of findings.json. Only these are exempt:
placeholders, fenced code blocks and HTML comments, link targets, finding/ADR/milestone ids, a
single-digit "section N" or "Appendix X" cross-reference, list numbering, section-heading numbers,
and a few fixed technical names (p50/p95/p99, lz4, N+1, "answers 503", "HTTP 404" style status codes).
"""
from __future__ import annotations

import glob
import json
import os
import re
import sys
import unicodedata

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

ALLOWED = [
    r"\{\{[^}]*\}\}",                                   # generated placeholder
    r"\]\([^)]*\)", r"https?://\S+",                    # link targets and bare URLs
    r"\bF-(?:0[1-9]|1[0-3])\b", r"\bADR-000[1-9]\b", r"\bM[0-7]\b", r"\bQ-[A-G]\b",
    r"\b[Ss]ections? [1-9]\b", r"\bAppendix [A-C]\b",
    r"\bdouble-entry\b", r"\bk6\b", r"\bISO 20022\b", r"\bp(?:50|95|99)\b", r"\blz4\b", r"\bN\+1\b", r"\bSHA-256\b", r"\bUUIDv5\b",
    r"\bHTTP (?:202|404|422|503)\b(?!\s*(?:req|ms|s\b|/))", r"\banswers 503\b(?!\s*(?:req|ms|s\b|/))", r"\bpacs\.008\b",
    r"^\s*\d{1,2}\.\s", r"^#{1,4} (?:[1-9]\. |Appendix [A-C]\. )",
]

NUMBER_WORDS = (
    "two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|"
    "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|million|dozen|"
    "seventeen|eighteen|nineteen|hundreds|thousands|dozens|zero|percent|per cent|twice|thrice|double|triple|"
    "doubled|tripled|quadrupled|halved|half|halves|quarter|third|times|order of magnitude|\\w*fold"
)
NUMBER_WORD_RE = re.compile(rf"\b(?:{NUMBER_WORDS})\b", re.I)


def _blank(match: re.Match) -> str:
    return "\n" * match.group(0).count("\n")


def strip(text: str) -> str:
    """Blank out fenced code and comments (keeping newlines so reported line numbers stay right), then
    remove the exempt patterns line by line."""
    text = re.sub(r"```.*?```", _blank, text, flags=re.S)
    text = re.sub(r"<!--.*?-->", _blank, text, flags=re.S)
    out = []
    for line in text.splitlines():
        for pattern in ALLOWED:
            line = re.sub(pattern, " ", line)
        out.append(line)
    return "\n".join(out)


def _numeric_char(line: str):
    """Any character Unicode considers a number (superscripts, Roman numeral signs, fractions ...)."""
    for ch in line:
        if unicodedata.numeric(ch, None) is not None:
            return re.match(re.escape(ch), ch)
    return None


def lint_text(text: str, name: str) -> list:
    problems = []
    original = text.splitlines()
    for i, line in enumerate(strip(text).splitlines()):
        found = re.search(r"\d", line) or NUMBER_WORD_RE.search(line) or _numeric_char(line)
        if found:
            src = original[i] if i < len(original) else line
            problems.append(f"{name}:{i + 1}: number in prose ({found.group(0)!r}); use a generated placeholder: {src.strip()[:90]}")
    return problems


def lint_json(path: str) -> list:
    """findings.json strings end up in the reports, so they are held to the same rule."""
    problems = []
    with open(path) as fh:
        data = json.load(fh)

    def walk(node, where):
        if isinstance(node, str):
            problems.extend(lint_text(node, f"{os.path.relpath(path, ROOT)}[{where}]"))
        elif isinstance(node, list):
            for i, item in enumerate(node):
                walk(item, f"{where}.{i}")
        elif isinstance(node, dict):
            for key, value in node.items():
                if key not in ("impact",):  # assessed scores are structured data, not prose
                    walk(value, f"{where}.{key}")
    walk(data, "$")
    return problems


def lint(path: str) -> list:
    if path.endswith(".json"):
        return lint_json(path)
    with open(path) as fh:
        return lint_text(fh.read(), os.path.relpath(path, ROOT))


def main() -> int:
    paths = sys.argv[1:] or (sorted(glob.glob(os.path.join(ROOT, "sample-deliverable", "src", "*.tmpl")))
                             + sorted(glob.glob(os.path.join(ROOT, "sample-deliverable", "src", "*.json")))
                             + sorted(glob.glob(os.path.join(ROOT, "docs", "src", "*.tmpl"))))
    problems = [p for path in paths for p in lint(path)]
    for p in problems:
        print(p, file=sys.stderr)
    if problems:
        print(f"{len(problems)} problem(s): move each number behind a generated placeholder.", file=sys.stderr)
        return 1
    print(f"ok: no hand-typed numbers in {len(paths)} file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
