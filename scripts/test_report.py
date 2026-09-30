#!/usr/bin/env python3
"""Tests for the report tooling: the number lint, the derived facts and generated-report freshness.

Run: python3 scripts/test_report.py
"""
import copy
import glob
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import generate_report as G  # noqa: E402
import lint_report_numbers as N  # noqa: E402
import normalize_results as NR  # noqa: E402
import report_lib as L  # noqa: E402


def lint_text(text: str):
    return N.lint_text(text, "probe")


class LintTest(unittest.TestCase):
    def assertFlagged(self, text):
        self.assertTrue(lint_text(text), f"expected to be flagged: {text!r}")

    def test_digits_and_number_words_in_prose_are_rejected(self):
        for text in [
            "Throughput was 1200 requests per second.",
            "It is three times faster.",
            "About twelve hundred requests.",
            "Roughly ninety percent of requests.",
            "Half of all traffic.",
            "A twofold gain, or double the rate.",
        ]:
            self.assertFlagged(text + "\n")

    def test_previous_loopholes_are_closed(self):
        for text in [
            "Use `4x` here.",                      # inline code
            "Result `1200 req/s` was reached.",    # inline code with a figure
            "See [the 1200 req/s result](x.md).",  # link text
            "## 1200 req/s headline",              # heading number
            "Section 1200 shows it.",              # bogus section reference
            "It served 503 req/s and 404 ms.",     # status codes as figures
            "Kafka 3.7 was used.",                 # version numbers
            "See F-1200 for details.",             # bogus finding id
            "| Header | 1200 |\n|---|---|\n| cell | 12 |",  # table cells
        ]:
            self.assertFlagged(text + "\n")

    def test_placeholders_ids_and_fixed_names_are_allowed(self):
        text = ("Held to {{fact.tuned_pass}} req/s, see F-06 and ADR-0004 in section 7 and Appendix A.\n"
                "1. numbered item\n## 3. Architecture\n```yaml\nlinger.ms: 10\n```\n"
                "<!-- 42 -->\np99, p50, lz4 and N+1 are names; the gateway answers 503; double-entry postings.\n"
                "[link]({{fact.x}}) and [doc](https://example.com/1200)\n")
        self.assertEqual(lint_text(text), [])

    def test_line_numbers_survive_code_fences(self):
        problems = lint_text("ok\n```\n1\n2\n```\nbad 5\n")
        self.assertEqual(len(problems), 1)
        self.assertIn(":6:", problems[0])

    def test_findings_json_strings_are_linted(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as fh:
            json.dump([{"id": "F-01", "title": "Twice as slow at 40 ms", "impact": 3}], fh)
        try:
            self.assertTrue(N.lint(fh.name))
        finally:
            os.unlink(fh.name)

    def test_committed_sources_are_clean(self):
        paths = (glob.glob(os.path.join(L.ROOT, "sample-deliverable", "src", "*.tmpl"))
                 + glob.glob(os.path.join(L.ROOT, "sample-deliverable", "src", "*.json")))
        self.assertTrue(paths)
        for path in paths:
            self.assertEqual(N.lint(path), [], path)


class FactsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runs = L.load_runs()

    def test_runs_are_loaded_at_clean_revisions(self):
        self.assertGreaterEqual(len(self.runs), 8)
        for run in self.runs.values():
            self.assertFalse(run.result["git_dirty"], run.key)

    def test_unrecovered_spike_is_never_reported_as_a_time(self):
        seen = False
        for run in self.runs.values():
            if run.scenario == "spike" and not run.result["spike"]["recovered_within_run"]:
                seen = True
                self.assertIsNone(run.result["spike"]["recovery_seconds_after_burst"], run.key)
                text = L.spike_recovery_text(run)
                self.assertIn("not recovered", text)
                self.assertNotRegex(text, r"\d+ s after")
        self.assertTrue(seen, "expected at least one unrecovered spike in the recorded results")

    def test_migration_is_idempotent_and_changes_no_measurement(self):
        result = {"spike": {"recovered_within_run": False, "recovery_seconds_after_burst": 120.0}}
        self.assertTrue(NR.migrate(result))
        self.assertIsNone(result["spike"]["recovery_seconds_after_burst"])
        self.assertEqual(result["spike"]["recovery_at_least_seconds"], 120.0)
        self.assertFalse(NR.migrate(result))
        self.assertEqual(len(result["result_migrations"]), 1)

    def test_steady_bounds_are_ordered(self):
        for profile in ("baseline", "tuned"):
            passed, failed = L.steady_bounds(self.runs, profile)
            self.assertIsNotNone(passed)
            if failed is not None:
                self.assertGreater(failed, passed)

    def test_unknown_placeholder_is_an_error(self):
        with self.assertRaises(SystemExit):
            G.render("{{nope.nothing}}", {})

    def test_correctness_sentence_follows_the_data(self):
        self.assertIn("no balance went negative", G.correctness_sentence(self.runs))
        broken = copy.deepcopy(self.runs)
        run = next(iter(broken.values()))
        run.result["invariants"]["all_hold"] = False
        run.result["invariants"]["negative_balances"] = 3
        sentence = G.correctness_sentence(broken)
        self.assertIn("failed", sentence)
        self.assertNotIn("no balance went negative", sentence)

    def test_low_load_sentence_follows_the_data(self):
        b_pass, b_fail = L.steady_bounds(self.runs, "baseline")
        t_pass, _ = L.steady_bounds(self.runs, "tuned")
        self.assertIn("higher median", G.low_load_sentence(self.runs, b_fail, t_pass))
        flipped = copy.deepcopy(self.runs)
        flipped["tuned_steady"].phases[0]["e2e_ms"]["p50"] = 0.0
        self.assertIn("did not show", G.low_load_sentence(flipped, b_fail, t_pass))

    def test_ranges_sentence_is_conditional(self):
        values = G.build_values(self.runs)
        self.assertIn("do not overlap", values["fact.ranges_sentence"])


class RoadmapTest(unittest.TestCase):
    def setUp(self):
        self.fs = G.findings()

    def test_every_matrix_point_is_in_the_quadrant_the_table_says(self):
        by_id = {f["id"]: f for f in self.fs}
        points = G.matrix_points(self.fs)
        self.assertEqual(len(points), len(self.fs))
        for fid, x, y in points:
            self.assertNotEqual(x, 0.5, fid)
            self.assertNotEqual(y, 0.5, fid)
            self.assertEqual(G.point_quadrant(x, y), G.quadrant(by_id[fid]), fid)

    def test_matrix_points_do_not_coincide(self):
        coords = [(x, y) for _, x, y in G.matrix_points(self.fs)]
        self.assertEqual(len(coords), len(set(coords)))

    def test_plan_lists_dependencies_first(self):
        order = [f["id"] for f in G.plan_order(self.fs)]
        self.assertEqual(sorted(order), sorted(f["id"] for f in self.fs))
        for f in self.fs:
            for dep in f["depends_on"]:
                self.assertLess(order.index(dep), order.index(f["id"]), f"{dep} must precede {f['id']}")


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
