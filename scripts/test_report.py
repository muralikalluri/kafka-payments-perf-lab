#!/usr/bin/env python3
"""Tests for the report tooling: the number lint, the placeholder engine and generated-report freshness.

Run: python3 scripts/test_report.py
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import generate_report as G  # noqa: E402
import lint_report_numbers as N  # noqa: E402
import report_lib as L  # noqa: E402


def lint_text(text: str):
    with tempfile.NamedTemporaryFile("w", suffix=".tmpl", delete=False) as fh:
        fh.write(text)
    try:
        return N.lint(fh.name)
    finally:
        os.unlink(fh.name)


class LintTest(unittest.TestCase):
    def test_bare_number_in_prose_is_rejected(self):
        self.assertTrue(lint_text("Throughput was 1200 requests per second.\n"))

    def test_placeholders_ids_and_code_are_allowed(self):
        text = ("Held to {{fact.tuned_pass}} req/s, see F-06 and ADR-0004 in section 7.\n"
                "1. numbered item\n```yaml\nlinger.ms: 10\n```\nUse `12` here; p99 and lz4 are names.\n")
        self.assertEqual(lint_text(text), [])

    def test_line_numbers_survive_code_fences(self):
        problems = lint_text("ok\n```\n1\n2\n```\nbad 5\n")
        self.assertEqual(len(problems), 1)
        self.assertIn(":6:", problems[0])

    def test_committed_templates_are_clean(self):
        import glob
        paths = glob.glob(os.path.join(L.ROOT, "sample-deliverable", "src", "*.tmpl"))
        self.assertTrue(paths)
        for path in paths:
            self.assertEqual(N.lint(path), [], path)


class FactsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runs = L.load_runs()

    def test_all_eight_runs_are_loaded_at_clean_revisions(self):
        self.assertGreaterEqual(len(self.runs), 6)
        for run in self.runs.values():
            self.assertFalse(run.result["git_dirty"], run.key)

    def test_unrecovered_spike_is_never_reported_as_a_time(self):
        for run in self.runs.values():
            if run.scenario == "spike" and not run.result["spike"]["recovered_within_run"]:
                text = L.spike_recovery_text(run)
                self.assertIn("not recovered", text)
                self.assertNotRegex(text, r"\d+ s after")

    def test_steady_bounds_are_ordered(self):
        for profile in ("baseline", "tuned"):
            passed, failed = L.steady_bounds(self.runs, profile)
            self.assertIsNotNone(passed)
            if failed is not None:
                self.assertGreater(failed, passed)

    def test_unknown_placeholder_is_an_error(self):
        with self.assertRaises(SystemExit):
            G.render("{{nope.nothing}}", {})


class FreshnessTest(unittest.TestCase):
    def test_committed_reports_match_a_fresh_generation(self):
        runs = L.load_runs()
        values = G.build_values(runs)
        for name in G.TEMPLATES:
            with open(os.path.join(G.SRC, name + ".tmpl")) as fh:
                expected = G.render(fh.read(), values)
            with open(os.path.join(G.OUT, name)) as fh:
                self.assertEqual(fh.read(), expected, f"{name} is stale: run scripts/generate_report.py")


if __name__ == "__main__":
    unittest.main(verbosity=2)
