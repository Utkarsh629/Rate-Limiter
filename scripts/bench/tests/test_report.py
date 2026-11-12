"""Report aggregation rules: median and min–max over valid repetitions, spread flag above 10 %."""
import unittest

from helpers import FIX  # noqa: F401
import report


class Aggregation(unittest.TestCase):
    def test_median_range_and_noise_flag(self):
        a = report.agg([10.0, 10.5, 9.8])
        self.assertEqual((a["median"], a["min"], a["max"], a["n"], a["noisy"]), (10.0, 9.8, 10.5, 3, False))
        self.assertTrue(report.agg([10.0, 12.0, 9.0])["noisy"])          # (12 - 9) / 10 = 30 %

    def test_missing_values_are_ignored_not_zero(self):
        self.assertEqual(report.agg([None, 4.0, None])["median"], 4.0)
        self.assertIsNone(report.agg([None, None]))

    def test_format(self):
        self.assertEqual(report.fmt(None), "–")
        self.assertEqual(report.fmt(report.agg([1.0, 2.0, 3.0])), "2.00 (1.00–3.00) ⚠")
        self.assertEqual(report.fmt(0.0012, 2, pct=True), "0.12 %")


if __name__ == "__main__":
    unittest.main()
