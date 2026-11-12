"""Safety: destructive Redis commands only against the verified Compose Redis; the runner refuses an unhealthy stack."""
import unittest
from unittest import mock

from helpers import FIX  # noqa: F401
import benchlib as bl
import runner

GOOD = {"container_id": "abc123", "project": "ratelimiter", "service": "redis", "image": "redis:7.4-alpine",
        "working_dir": str(bl.ROOT), "running": True, "maxmemory": "268435456", "save": "", "appendonly": "no",
        "tcp_port": "6379", "redis_version": "7.4.7", "key_count": 3, "foreign_key_count": 0, "foreign_keys_sample": []}


class RedisSafety(unittest.TestCase):
    def test_verified_target_is_safe(self):
        self.assertEqual(bl.redis_safety_violations(GOOD), [])

    def test_each_identity_mismatch_is_refused(self):
        cases = {"project": "otherproject", "service": "cache", "image": "redis:latest", "working_dir": "/tmp/elsewhere",
                 "maxmemory": "0", "save": "3600 1", "appendonly": "yes", "tcp_port": "6380", "redis_version": "6.2.1"}
        for field, bad in cases.items():
            with self.subTest(field=field):
                v = bl.redis_safety_violations({**GOOD, field: bad})
                self.assertTrue(v, f"{field}={bad} must be refused")

    def test_not_running_foreign_keys_and_ambiguous_container(self):
        self.assertTrue(bl.redis_safety_violations({**GOOD, "running": False}))
        self.assertTrue(bl.redis_safety_violations({**GOOD, "foreign_key_count": 2, "foreign_keys_sample": ["session:1", "cart:9"]}))
        self.assertTrue(bl.redis_safety_violations({"container_id": "a\nb", "error": "expected exactly one redis container"}))

    def test_admin_refuses_unsafe_target_without_running_anything(self):
        with mock.patch.object(bl, "sh") as sh:
            with self.assertRaises(bl.BenchError) as ctx:
                bl.redis_admin({**GOOD, "project": "production"}, "FLUSHDB")
            self.assertIn("refusing destructive Redis command FLUSHDB", str(ctx.exception))
            sh.assert_not_called()

    def test_admin_refuses_commands_outside_the_allow_list(self):
        with mock.patch.object(bl, "sh") as sh:
            for cmd in (("FLUSHALL",), ("CONFIG", "SET", "save", ""), ("DEBUG", "SLEEP", "1"), ("SHUTDOWN",)):
                with self.subTest(cmd=cmd):
                    with self.assertRaises(bl.BenchError):
                        bl.redis_admin(GOOD, *cmd)
            sh.assert_not_called()

    def test_admin_refuses_when_container_changed(self):
        with mock.patch.object(bl, "sh") as sh:
            sh.return_value = mock.Mock(stdout="different-container\n")
            with self.assertRaises(bl.BenchError) as ctx:
                bl.redis_admin(GOOD, "FLUSHDB")
            self.assertIn("changed since verification", str(ctx.exception))
            self.assertEqual(sh.call_count, 1)                     # only the identity re-check, no FLUSHDB

    def test_admin_targets_the_verified_container_id(self):
        with mock.patch.object(bl, "sh") as sh:
            sh.side_effect = [mock.Mock(stdout="abc123\n"), mock.Mock(stdout="OK\n")]
            self.assertEqual(bl.redis_admin(GOOD, "FLUSHDB"), "OK")
            self.assertEqual(sh.call_args_list[1].args, ("docker", "exec", "abc123", "redis-cli", "FLUSHDB"))


class StackHealth(unittest.TestCase):
    def rows(self, **override):
        rows = [{"Service": s, "State": "running", "Health": "healthy"} for s in bl.SERVICES]
        for r in rows:
            r.update(override.get(r["Service"], {}))
        return rows

    def test_problems(self):
        self.assertEqual(bl.stack_problems(self.rows()), [])
        p = bl.stack_problems(self.rows(redis={"State": "paused"}, app2={"Health": "unhealthy"}))
        self.assertEqual(p, ["app2: health=unhealthy", "redis: state=paused"])
        self.assertIn("mysql: missing", bl.stack_problems([r for r in self.rows() if r["Service"] != "mysql"]))

    def test_runner_refuses_unhealthy_stack(self):
        meta = {"variant": "default", "kind": "constant", "warmup_s": 60, "measure_s": 180}
        with mock.patch.object(bl, "compose_ps", return_value=self.rows(mysql={"Health": "starting"})), \
                mock.patch.object(bl, "prom_query") as pq:
            with self.assertRaises(bl.BenchError) as ctx:
                runner.preflight(meta, fresh_nginx=False)
            self.assertIn("mysql: health=starting", str(ctx.exception))
            pq.assert_not_called()                                 # refused before anything else ran


class Planning(unittest.TestCase):
    def args(self, **kw):
        base = dict(scenario="B1", algorithm="token_bucket", rate=1000, rep=1, profile=None, variant="default",
                    target="nginx", warmup=60, measure=180, nonstandard_rate=False, k6_env=None, dry_run=True)
        base.update(kw)
        return mock.Mock(**base)

    def test_approved_definitions_enforced(self):
        self.assertEqual(runner.plan(self.args())["expected_requests"], 180000)
        for bad in (dict(scenario="B11"), dict(rate=3000), dict(scenario="B6", algorithm="fixed_window"),
                    dict(profile="throttled"), dict(variant="limiter-off"), dict(target="direct")):
            with self.subTest(bad=bad):
                with self.assertRaises(bl.BenchError):
                    runner.plan(self.args(**bad))

    def test_scenario_specific_plans(self):
        b6 = runner.plan(self.args(scenario="B6", rate=1000))
        self.assertEqual((b6["kind"], b6["warmup_s"], b6["expected_requests"], b6["policy_variant"]), ("concurrency", 0, 1000, "concurrency"))
        f1 = runner.plan(self.args(scenario="F1", algorithm="fixed_window", rate=2000))
        self.assertEqual(f1["algorithms_used"], ["fixed_window", "sliding_window"])
        self.assertEqual(runner.fault_plan("F1")[0][1:], ("compose", ["pause", "redis"]))
        self.assertTrue(all(a == "redis_block_us" for _, a, _ in runner.fault_plan("F5")))


if __name__ == "__main__":
    unittest.main()
