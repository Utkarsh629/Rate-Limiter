"""Decision O2 reconciliation: exact whole-run counts across k6, Nginx, raw counters, Prometheus, /stats."""
import copy
import unittest

from helpers import FIX  # noqa: F401  (sets sys.path)
import benchlib as bl
import reconcile


def zero():
    return {a: {r: 0 for r in bl.RESULTS} for a in bl.ALGORITHMS}


def sources(allowed=900, rejected=100, fail_open=0, alg="token_bucket"):
    tot = zero()
    tot[alg].update({"allowed": allowed, "rejected": rejected, "fail_open": fail_open})
    per_app = {app: zero() for app in bl.APPS}
    per_app["app1"][alg].update({"allowed": allowed - 2 * (allowed // 3), "rejected": rejected - 2 * (rejected // 3), "fail_open": fail_open})
    for app in ("app2", "app3"):
        per_app[app][alg].update({"allowed": allowed // 3, "rejected": rejected // 3})
    per_inst = {bl.APP_INSTANCE[a]: copy.deepcopy(v) for a, v in per_app.items()}
    return {
        "k6": {"accepted": allowed + fail_open, "rejected": rejected, "unexpected": 0, "requests": allowed + rejected + fail_open},
        "nginx": {"total": {"200": allowed + fail_open, "429": rejected}},
        "raw": {"total": copy.deepcopy(tot), "per_app": per_app},
        "prom": {"total": copy.deepcopy(tot), "per_instance": per_inst, "sample_timestamps_after": {"app-1": 200.0}},
        "stats": {"7": {"algorithm": alg, "status": 200, "body": {"allowed": allowed, "rejected": rejected,
                                                                    "failOpen": fail_open, "failClosed": 0, "instances": []}}},
        "redis": {"evalsha_delta": allowed + rejected},
        "config": {"algorithms": [alg], "filter_enabled": True, "k6_end": 100.0},
    }


class Reconcile(unittest.TestCase):
    def test_exact_match(self):
        rep = reconcile.reconcile(sources())
        self.assertTrue(rep["ok"], rep["failed"])
        self.assertEqual({c["status"] for c in rep["checks"]}, {"pass"})

    def test_k6_vs_nginx_mismatch_is_explained(self):
        s = sources()
        s["nginx"]["total"] = {"200": 899, "429": 100, "499": 1}
        rep = reconcile.reconcile(s)
        self.assertFalse(rep["ok"])
        self.assertIn("k6_vs_nginx", rep["failed"])
        c = next(c for c in rep["checks"] if c["name"] == "k6_vs_nginx")
        self.assertIn("499", c["explanation"])

    def test_incomplete_scrape_detected(self):
        s = sources()
        s["prom"]["total"]["token_bucket"]["allowed"] -= 50
        s["prom"]["sample_timestamps_after"] = {"app-1": 90.0}          # last sample before the end of k6
        rep = reconcile.reconcile(s)
        self.assertIn("raw_vs_prometheus_total", rep["failed"])
        c = next(c for c in rep["checks"] if c["name"] == "raw_vs_prometheus_total")
        self.assertIn("final scrape missing", c["explanation"])

    def test_stats_pending_flush(self):
        s = sources()
        s["stats"]["7"]["body"]["allowed"] -= 10
        rep = reconcile.reconcile(s)
        self.assertIn("stats_vs_raw_policy_7", rep["failed"])

    def test_foreign_traffic(self):
        s = sources()
        s["raw"]["total"]["fixed_window"]["allowed"] = 1
        self.assertIn("no_foreign_decisions", reconcile.reconcile(s)["failed"])

    def test_missing_source(self):
        s = sources()
        s["prom"] = None
        rep = reconcile.reconcile(s)
        self.assertFalse(rep["ok"])
        self.assertIn("sources_present", rep["failed"])

    def test_direct_mode_nginx_not_applicable(self):
        s = sources()
        s["nginx"] = None
        rep = reconcile.reconcile(s)
        self.assertTrue(rep["ok"])
        self.assertEqual(next(c for c in rep["checks"] if c["name"] == "k6_vs_nginx")["status"], "n/a")

    def test_fail_open_makes_evalsha_informational(self):
        s = sources(fail_open=5)
        rep = reconcile.reconcile(s)
        self.assertTrue(rep["ok"], rep["failed"])
        self.assertEqual(next(c for c in rep["checks"] if c["name"] == "redis_evalsha_vs_decisions")["status"], "n/a")

    def test_limiter_off(self):
        s = sources()
        s["raw"]["total"] = zero()
        s["config"]["filter_enabled"] = False
        s["k6"] = {"accepted": 1000, "rejected": 0, "unexpected": 0, "requests": 1000}
        s["nginx"] = {"total": {"200": 1000}}
        self.assertTrue(reconcile.reconcile(s)["ok"])
        s["raw"]["total"]["token_bucket"]["allowed"] = 3
        self.assertIn("no_decisions_with_limiter_off", reconcile.reconcile(s)["failed"])


if __name__ == "__main__":
    unittest.main()
