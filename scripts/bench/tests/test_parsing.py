"""Parsers used by collection: Prometheus text, Redis INFO, Nginx access log, docker stats, k6 summary."""
import json
import unittest

from helpers import FIX
import benchlib as bl
import collect


class PrometheusText(unittest.TestCase):
    def test_requests_and_breaker(self):
        samples = bl.parse_prometheus_text((FIX / "actuator.prom").read_text())
        req = bl.requests_by_algorithm(samples)
        self.assertEqual(req["token_bucket"], {"allowed": 1500.0, "rejected": 25.0, "fail_open": 0.0, "fail_closed": 0.0})
        self.assertEqual(req["sliding_window"]["allowed"], 7.0)
        self.assertEqual(req["fixed_window"], {"allowed": 0.0, "rejected": 0.0, "fail_open": 0.0, "fail_closed": 0.0})
        self.assertEqual(bl.breaker_state(samples), ("closed", 2.0))

    def test_missing_metrics_give_zero_not_error(self):
        req = bl.requests_by_algorithm(bl.parse_prometheus_text("# nothing here\nprocess_cpu_usage 0.5\n"))
        self.assertEqual(sum(sum(r.values()) for r in req.values()), 0.0)
        self.assertEqual(bl.breaker_state([]), (None, 0.0))

    def test_counters_delta(self):
        def snap(v):
            return {a: {"requests": {alg: {r: v for r in bl.RESULTS} for alg in bl.ALGORITHMS}} for a in bl.APPS}
        d = collect.counters_delta(snap(10.0), snap(13.0))
        self.assertEqual(d["total"]["token_bucket"]["allowed"], 9)
        self.assertEqual(d["per_app"]["app2"]["fixed_window"]["rejected"], 3)


class RedisInfo(unittest.TestCase):
    def test_sections(self):
        info = bl.parse_redis_info((FIX / "redis-info.txt").read_text())
        self.assertEqual(info["server"]["redis_version"], "7.4.7")
        self.assertEqual(bl.command_calls(info, "evalsha"), 15025)
        self.assertEqual(bl.command_calls(info, "eval"), 12)
        self.assertEqual(bl.command_calls(info, "flushdb"), 0)
        self.assertEqual(info["latencystats"]["latency_percentiles_usec_evalsha"]["p99"], "61.183")
        self.assertEqual(info["keyspace"]["db0"]["keys"], "10103")


class NginxLog(unittest.TestCase):
    def setUp(self):
        self.lines = (FIX / "nginx-access.log").read_text().splitlines()

    def test_run_marker_phases_and_statuses(self):
        s = bl.nginx_summary(self.lines, "R1")
        self.assertEqual(s["total"], {"200": 3, "429": 1, "502": 1})
        self.assertEqual(s["phases"]["warmup"], {"200": 1, "429": 1})
        self.assertEqual(s["phases"]["measure"], {"200": 2, "502": 1})
        self.assertEqual(s["upstream"], {"app1": 2, "app2": 2, "app3": 1})
        self.assertEqual(s["unparsed_lines"], 1)            # the [warn] error-log line
        self.assertEqual(s["other_lines"], 1 + 1 + 3)       # other run, admin call, the R10 lines
        self.assertEqual(bl.status_buckets(s["total"]), {"200": 3, "429": 1, "503": 0, "other": 1})

    def test_per_user_and_timeline(self):
        s = bl.nginx_summary(self.lines, "R10", per_user=True, timeline=True)
        u = s["users"]["t1"]
        self.assertEqual((u["accepted"], u["rejected"], u["other"]), (2, 1, 0))
        self.assertEqual(u["last"] - u["first"], 9)
        self.assertEqual(len(s["timeline"]), 3)

    def test_parse_rejects_garbage(self):
        self.assertIsNone(bl.parse_nginx_line("garbage"))
        self.assertEqual(bl.parse_nginx_line(self.lines[6])["ua_fields"], {})   # non-k6 UA: no fields


class DockerStats(unittest.TestCase):
    def test_parse_and_k6_window(self):
        rows = [r for r in map(bl.parse_docker_stats_line, (FIX / "docker-stats.jsonl").read_text().splitlines()) if r]
        self.assertEqual(len(rows), 60)
        self.assertEqual(rows[0]["mem_bytes"], 300 * 1024 ** 2)
        s = collect.summarize_docker_rows(rows, 1000, 1030)
        self.assertAlmostEqual(s["k6"]["cpu_pct_of_limit_max_5s"], 95.0)        # 190 % of 2 CPUs
        self.assertAlmostEqual(s["containers"]["ratelimiter-app1-1"]["cpu_pct_avg"], 75.5)
        quiet = collect.summarize_docker_rows(rows, 1000, 1019)
        self.assertAlmostEqual(quiet["k6"]["cpu_pct_of_limit_max_5s"], 60.0)

    def test_empty(self):
        self.assertIsNone(bl.rolling_max_mean([], 5))
        self.assertEqual(collect.summarize_docker_rows([], 0, 10)["k6"]["samples"], 0)


class K6Summary(unittest.TestCase):
    def test_metric_lookup(self):
        s = json.loads((FIX / "k6-summary.json").read_text())
        self.assertEqual(bl.k6_metric(s, "http_reqs{phase:measure}"), 180000)
        self.assertIsNone(bl.k6_metric(s, "dropped_iterations"))


if __name__ == "__main__":
    unittest.main()
