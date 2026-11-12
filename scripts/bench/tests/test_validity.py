"""Invalid-run rules and correctness bounds."""
import copy
import json
import unittest

from helpers import FIX
import validity


def sub(n):
    return {"values": {"count": n}}


META = {"kind": "constant", "scenario": "B1", "algorithm": "token_bucket", "measure_scenarios": ["b1_measure"],
        "expected_requests": 180000, "warmup_s": 60, "measure_start_offset_s": 60}
SUMMARY = {"metrics": {"http_reqs{phase:measure}": sub(180000), "http_reqs{phase:warmup}": sub(60000),
                       "unexpected{phase:measure}": sub(0)}}
DOCKER = {"k6": {"samples": 180, "cpu_pct_of_limit_max_5s": 60.0}}
RECON = {"ok": True, "failed": []}
RESET = {"flushdb": "OK", "dbsize_after": 0}
PROM = {"scrape": {"expected_per_instance": 36, "samples": {"app-1": 36, "app-2": 36, "app-3": 35}}}


def ev(meta=META, summary=SUMMARY, docker=DOCKER, recon=RECON, reset=RESET, prom=PROM):
    return validity.evaluate(meta, summary, docker, recon, reset, prom)


def status(rep, name):
    return next(c for c in rep["criteria"] if c["name"] == name)["status"]


class Validity(unittest.TestCase):
    def test_valid_run(self):
        rep = ev()
        self.assertTrue(rep["valid"], rep["reasons"])

    def test_dropped_iterations(self):
        s = copy.deepcopy(SUMMARY)
        s["metrics"]["dropped_iterations"] = sub(12)
        s["metrics"]["dropped_iterations{scenario:b1_measure}"] = sub(12)
        rep = ev(summary=s)
        self.assertFalse(rep["valid"])
        self.assertEqual(status(rep, "dropped_iterations"), "fail")

    def test_dropped_metric_present_but_submetric_missing_fails(self):
        s = copy.deepcopy(SUMMARY)
        s["metrics"]["dropped_iterations"] = sub(3)
        self.assertEqual(status(ev(summary=s), "dropped_iterations"), "fail")

    def test_achieved_rate_below_99_percent(self):
        s = copy.deepcopy(SUMMARY)
        s["metrics"]["http_reqs{phase:measure}"] = sub(178199)        # 99 % of 180,000 = 178,200
        self.assertEqual(status(ev(summary=s), "achieved_rate"), "fail")
        s["metrics"]["http_reqs{phase:measure}"] = sub(178200)
        self.assertEqual(status(ev(summary=s), "achieved_rate"), "pass")

    def test_k6_cpu(self):
        self.assertEqual(status(ev(docker={"k6": {"samples": 10, "cpu_pct_of_limit_max_5s": 85.1}}), "k6_cpu"), "fail")
        self.assertEqual(status(ev(docker={"k6": {"samples": 0}}), "k6_cpu"), "fail")
        self.assertEqual(status(ev(docker=None), "k6_cpu"), "fail")

    def test_unexpected_statuses(self):
        s = copy.deepcopy(SUMMARY)
        s["metrics"]["unexpected{phase:measure}"] = sub(181)          # > 0.1 % of 180,000
        self.assertEqual(status(ev(summary=s), "unexpected_statuses"), "fail")

    def test_missing_metrics_fail(self):
        s = {"metrics": {}}
        rep = ev(summary=s)
        self.assertFalse(rep["valid"])
        for name in ("achieved_rate", "unexpected_statuses"):
            self.assertEqual(status(rep, name), "fail")
        self.assertFalse(ev(summary=None)["valid"])

    def test_incomplete_scrapes(self):
        rep = ev(prom={"scrape": {"expected_per_instance": 36, "samples": {"app-1": 36, "app-2": 20}}})
        self.assertEqual(status(rep, "prometheus_complete"), "fail")

    def test_reconcile_and_reset(self):
        self.assertEqual(status(ev(recon={"ok": False, "failed": ["k6_vs_nginx"]}), "counts_reconcile"), "fail")
        self.assertEqual(status(ev(recon=None), "counts_reconcile"), "fail")
        self.assertEqual(status(ev(reset={"flushdb": "OK", "dbsize_after": 3}), "redis_reset"), "fail")
        self.assertEqual(status(ev(reset=None), "redis_reset"), "fail")

    def test_warmup_overlap(self):
        m = dict(META, measure_start_offset_s=30)
        self.assertEqual(status(ev(meta=m), "warmup_separated"), "fail")

    def test_failure_kind_unexpected_is_outcome(self):
        m = dict(META, kind="failure", measure_scenarios=["f_profile_measure", "f_enterprise_measure"])
        s = copy.deepcopy(SUMMARY)
        s["metrics"]["unexpected{phase:measure}"] = sub(5000)
        rep = ev(meta=m, summary=s)
        self.assertEqual(status(rep, "unexpected_statuses"), "n/a")
        self.assertTrue(rep["valid"], rep["reasons"])

    def test_fixture_summary(self):
        s = json.loads((FIX / "k6-summary.json").read_text())
        self.assertTrue(ev(summary=s)["valid"])


class Bounds(unittest.TestCase):
    def test_token_bucket(self):
        self.assertEqual(validity.bound("token_bucket", 100, 100), 120 + 2 + 1)        # D rounded up to 1 s
        self.assertEqual(validity.bound("token_bucket", 100, 159), 120 + 120 + 1)

    def test_windows(self):
        self.assertEqual(validity.bound("fixed_window", 120, 179), 120)                  # one window
        self.assertEqual(validity.bound("fixed_window", 179, 180), 240)                  # crosses a boundary
        self.assertEqual(validity.bound("sliding_window", 0, 299), 600)

    def test_over_admission_detected(self):
        users = {"t1": {"accepted": 125, "rejected": 0, "other": 0, "first": 120, "last": 125},
                 "t2": {"accepted": 100, "rejected": 50, "other": 0, "first": 120, "last": 125}}
        r = validity.per_user_correctness("fixed_window", users)
        self.assertEqual(r["over_admitted_users"], ["t1"])
        self.assertEqual(r["over_admitted_requests"], 5)

    def test_b6_and_b10(self):
        s = {"metrics": {"accepted{phase:measure}": sub(100), "rejected{phase:measure}": sub(900), "unexpected{phase:measure}": sub(0)}}
        self.assertTrue(validity.correctness({"scenario": "B6"}, s, None)["pass"])
        s["metrics"]["accepted{phase:measure}"] = sub(101)
        self.assertFalse(validity.correctness({"scenario": "B6"}, s, None)["pass"])
        self.assertFalse(validity.correctness({"scenario": "B10", "algorithm": "token_bucket"}, s, None)["pass"])


if __name__ == "__main__":
    unittest.main()
