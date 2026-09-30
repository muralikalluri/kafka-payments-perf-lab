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
            "A twofold gain.", "A tenfold burst.", "About seventeen of them.", "Hundreds of payments.",
            "It doubled overnight.", "An order of magnitude more.", "Only a third of them.",
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
            "The gateway answers 503 req/s.", "HTTP 503 ms latency.",  # status-code exemption must not hide figures
            "1200. requests were served.",          # list-number exemption limited to short numbers
            "## 99. heading", "Cost was 5\u00b2 units.", "Section \u216b applies.",  # unicode numerals
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
        b_pass, b_fail = L.steady_bounds(self.runs, "baseline")
        t_pass, _ = L.steady_bounds(self.runs, "tuned")
        self.assertIn("do not overlap", G.ranges_sentence(b_fail, t_pass))
        self.assertIn("overlap", G.ranges_sentence(400, 200).replace("do not overlap", ""))  # overlapping ranges
        self.assertIn("did not bracket", G.ranges_sentence(None, t_pass))
        self.assertIn("did not bracket", G.ranges_sentence(b_fail, None))

    def test_capacity_sentences_never_print_none(self):
        for passed, failed in [(None, None), (None, 100), (200, None), (200, 400)]:
            text = G.capacity_sentence("baseline", passed, failed)
            self.assertNotIn("None", text)
        self.assertIn("did not find its limit", G.capacity_sentence("tuned", 800, None))
        self.assertIn("any offered step", G.capacity_sentence("tuned", None, 100))

    def test_bounds_follow_meets_slo(self):
        mutated = copy.deepcopy(self.runs)
        for phase in mutated["baseline_steady"].phases:
            if phase["target_req_per_s"] == 400:
                phase["meets_slo"] = True
        passed, failed = L.steady_bounds(mutated, "baseline")
        self.assertEqual((passed, failed), (400, 800))

    def test_spike_statements_follow_the_data(self):
        mutated = copy.deepcopy(self.runs)
        spike = mutated["baseline_spike"].result["spike"]
        spike["recovered_within_run"], spike["recovery_seconds_after_burst"] = True, 42.0
        self.assertIn("also did", G.earlier_spike_note(mutated))
        self.assertIn("42 s after", L.spike_recovery_text(mutated["baseline_spike"]))
        self.assertIn("did not", G.earlier_spike_note(self.runs))
        self.assertIn("disagree", G.earlier_spike_note(self.runs))
        agreeing = copy.deepcopy(self.runs)
        for key in ("baseline_spike", "baseline_spike_recorded"):
            agreeing[key].result["spike"]["recovered_within_run"] = True
        self.assertNotIn("disagree", G.earlier_spike_note(agreeing))
        tuned = copy.deepcopy(self.runs)
        tuned["tuned_spike"].result["spike"]["recovered_within_run"] = False
        self.assertIn("had not recovered", G.spike_caveat(tuned, 800))
        self.assertIn("never overloaded", G.spike_caveat(self.runs, 800))
        self.assertIn("reached or exceeded", G.spike_caveat(self.runs, 100))

    def test_queueing_note_is_conditional(self):
        self.assertIn("dominated by queueing", G.queueing_note(self.runs))
        calm = copy.deepcopy(self.runs)
        for phase in calm["baseline_steady"].phases:
            phase["meets_slo"] = True
        self.assertNotIn("dominated by queueing", G.queueing_note(calm))

    def test_score_order_note_names_only_real_disagreements(self):
        self.assertIn("F-03", G.score_order_note(G.findings()))
        agreed = [dict(f, depends_on=[]) for f in G.findings()]
        self.assertEqual(G.score_order_note(agreed), "")


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
    def test_committed_documents_match_a_fresh_generation(self):
        runs = L.load_runs()
        values = G.build_values(runs)
        for src_dir, name, out_dir in G.TARGETS:
            with open(os.path.join(src_dir, name + ".tmpl")) as fh:
                expected = G.render(fh.read(), values)
            with open(os.path.join(out_dir, name)) as fh:
                self.assertEqual(fh.read(), expected, f"{name} is stale: run scripts/generate_report.py")


class ScopeTest(unittest.TestCase):
    def test_every_mvp_item_is_actually_done(self):
        runs = L.load_runs()
        rows = G.mvp_status(runs)
        self.assertGreaterEqual(len(rows), 19)  # a floor, so removing a row from the check is noticed
        missing = [item for item, ok, _ in rows if not ok]
        self.assertEqual(missing, [], f"MVP items not done: {missing}")

    def test_scope_check_notices_a_missing_item(self):
        runs = L.load_runs()
        broken = {k: v for k, v in runs.items() if k != "tuned_spike"}
        self.assertTrue([i for i, ok, _ in G.mvp_status(broken) if not ok])


class ReadmeTest(unittest.TestCase):
    """The global README order: pitch and badges, GIF, results, architecture, quickstart, features, design
    decisions, sample deliverable, hire-me link. Plus link integrity and the insecure-code banner."""

    @classmethod
    def setUpClass(cls):
        with open(os.path.join(L.ROOT, "README.md")) as fh:
            cls.readme = fh.read()

    def test_sections_are_in_the_required_order(self):
        markers = ["![CI]", "![Grafana during the spike", "## Results", "```mermaid", "## Quickstart", "## Features",
                   "## Design decisions", "## Sample deliverables", "## Hire me"]
        positions = [self.readme.find(m) for m in markers]
        self.assertNotIn(-1, positions, dict(zip(markers, positions)))
        self.assertEqual(positions, sorted(positions))
        self.assertTrue(self.readme.rstrip().splitlines()[-1].startswith("If you need an audit"))

    def test_relative_links_resolve(self):
        import re
        for name in ("README.md", os.path.join("sample-deliverable", "AUDIT_REPORT_SAMPLE.md"),
                     os.path.join("sample-deliverable", "QUICK_AUDIT_ledger-service.md")):
            path = os.path.join(L.ROOT, name)
            with open(path) as fh:
                text = fh.read()
            for target in re.findall(r"\]\(([^)#\s]+)", text):
                if target.startswith(("http://", "https://", "mailto:")):
                    continue
                self.assertTrue(os.path.exists(os.path.normpath(os.path.join(os.path.dirname(path), target))),
                                f"{name} links to a missing file: {target}")

    def test_banner_and_hire_me_link(self):
        self.assertIn("Deliberately insecure for demonstration. Do not deploy.", self.readme)
        self.assertIn("https://www.upwork.com/freelancers/", self.readme)

    def test_insecure_lab_code_carries_the_banner(self):
        for rel in ("services/validation-service/src/main/java/lab/payments/validationservice/LabAdminController.java",
                    "services/payment-gateway/src/main/java/lab/payments/paymentgateway/PaymentController.java"):
            with open(os.path.join(L.ROOT, rel)) as fh:
                self.assertIn("Deliberately insecure for demonstration. Do not deploy.", fh.read(), rel)

    def test_no_secrets_or_local_paths(self):
        self.assertNotIn("/Users/", self.readme)
        import re
        self.assertIsNone(re.search(r"[\w.+-]+@[\w-]+\.[\w.]+", self.readme), "no email address in the README")


if __name__ == "__main__":
    unittest.main(verbosity=2)
