#!/usr/bin/env python3
"""Synthetic records exercise accounting independently of the clock implementation."""
import importlib.util
from pathlib import Path
import tempfile
import sys
import subprocess

sys.dont_write_bytecode = True
import unittest

spec = importlib.util.spec_from_file_location("timing_report", Path(__file__).with_name("timing-report.py"))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)

VALID = """format\tflixw-suite-timing-v1
meta\tos\tLinux
meta\tjdk\t21.0.9
meta\ttree\tsource
meta\tcompiler_cache\tempty-after-suite-reset
meta\trestored_cache\ttrue
meta\tprecision_seconds\t1
group\tfixtures\t2\t1\t0\t1
group\tcompiler\t6\t2\t1\t0
phase\tacquisition\t3\t./flixw pin
total\t8\t3\t1\t1\t1\t10\t18
"""


class TimingReportCheck(unittest.TestCase):
    def read(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "suite.tsv"
            path.write_text(text)
            return report.read_report(path)

    def test_known_accounting_and_nested_phase(self):
        result = self.read(VALID)
        self.assertEqual(result["seconds"], 8)  # not 11: phase is already inside groups
        self.assertEqual(result["cases"], [3, 1, 1])

    def test_group_duration_must_reconcile(self):
        with self.assertRaisesRegex(ValueError, "group seconds"):
            self.read(VALID.replace("fixtures\t2", "fixtures\t3"))

    def test_case_counts_must_reconcile(self):
        with self.assertRaisesRegex(ValueError, "case counts"):
            self.read(VALID.replace("fixtures\t2\t1", "fixtures\t2\t2"))

    def test_timestamps_must_reconcile(self):
        with self.assertRaisesRegex(ValueError, "timestamps"):
            self.read(VALID.replace("\t10\t18", "\t10\t19"))

    def test_negative_duration_rejected(self):
        with self.assertRaisesRegex(ValueError, "nonnegative"):
            self.read(VALID.replace("acquisition\t3", "acquisition\t-3"))

    def test_missing_or_duplicate_total_rejected(self):
        for text in (VALID.split("total\t")[0], VALID + VALID.splitlines()[-1] + "\n"):
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, "one total"):
                self.read(text)

    def test_missing_metadata_rejected(self):
        with self.assertRaisesRegex(ValueError, "metadata"):
            self.read(VALID.replace("meta\ttree\tsource\n", ""))

    def test_cli_rejects_missing_matrix_report(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "suite.tsv"
            path.write_text(VALID)
            result = subprocess.run([sys.executable, str(Path(__file__).with_name("timing-report.py")),
                                     "--expected", "2", str(path)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("expected 2 reports, received 1", result.stderr)
        self.assertEqual(result.stdout, "")

    def test_distinguish_runner_sum_from_wall_span(self):
        first = self.read(VALID)
        second = self.read(VALID.replace("\t10\t18", "\t12\t20"))
        text = report.render([first, second])
        self.assertIn("Summed suite runner time: **16 s**", text)
        self.assertIn("Suite measurement window: **10 s**", text)
        self.assertIn("Linux | 21.0.9 | source | empty-after-suite-reset | true | 2 | 16", text)


if __name__ == "__main__":
    unittest.main()
