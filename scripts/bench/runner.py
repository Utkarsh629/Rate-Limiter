#!/usr/bin/env python3
"""Phase 3B benchmark runner: executes ONE benchmark or failure run end to end (invoked through run.sh).

Sequence for a run (decisions O2–O5):
  1. preflight   fresh Nginx container (empty access log); all 9 services running and healthy; Prometheus targets
                 up; every circuit breaker CLOSED; the expected stack variant and the 250 ms Redis timeout on all
                 three instances; tokens valid for the whole run
  2. policy      create/switch the benchmark policy and verify it (and its algorithm) on all three instances
                 (policy probes happen here, before any snapshot)
  3. idle 6 s    pending /stats flushes (5 s interval) complete
  4. reset       Redis identity verified (Compose project, service, image, working dir, config fingerprint, key
                 prefixes), then FLUSHDB + CONFIG RESETSTAT + SLOWLOG RESET on that exact container
  5. idle 11 s   Prometheus scrapes the idle state (>= 2 scrapes at 5 s)
  6. snapshots   raw per-instance counters, Prometheus counts, Redis INFO/latency/SLOWLOG, /stats
  7. k6          run directory created first; the exact script + library are copied into it and k6 runs the copy;
                 docker stats sampled every 1 s; faults injected for F1–F5
  8. cooldown    >= 12 s: final scrape (2 × 5 s) and final stats flush (5 s)
  9. collect     snapshots again, Prometheus measure-window queries, Nginx summary, /stats, environment
 10. evaluate    reconcile.json, validity.json, correctness.json (B5, B6, B10), run-meta.json

--dry-run executes steps 1–2 and the Redis safety evaluation, but never resets Redis and replaces `k6 run` with
`k6 inspect` (no request is sent). Its output goes to benchmarks/dry-runs/<run-id>/.
"""
import argparse
import datetime
import json
import os
import pathlib
import shutil
import signal
import subprocess
import sys
import threading
import time

import benchlib as bl
import collect
import reconcile
import validity

K6_DIR = bl.ROOT / "load-tests" / "k6"
RESULTS = bl.ROOT / "benchmarks" / "results"
DRY = bl.ROOT / "benchmarks" / "dry-runs"
WARMUPS = bl.ROOT / "benchmarks" / "warmups"
TOKENS = K6_DIR / ".tokens" / "bench.json"
ENT_TOKENS = K6_DIR / ".tokens" / "bench-enterprise.json"
EXPECTED_TIMEOUT = "250ms"            # decision D42 (Compose deployment)
ALL_ALGS = bl.ALGORITHMS

# Approved scenario definitions (plan: campaign B0–B10, failure scenarios F1–F5). "rates" are the approved levels;
# other rates are refused unless --nonstandard-rate is given (B2 knee confirmation, B9 at the knee).
SCENARIOS = {
    "B0": dict(script="b0-baseline.js", kind="constant", rates=[100], algorithms=ALL_ALGS, profiles=["mixed"]),
    "B1": dict(script="b1-load-levels.js", kind="constant", rates=[1000, 2500, 5000, 7500, 10000], algorithms=ALL_ALGS, profiles=["mixed"]),
    "B2": dict(script="b2-stress.js", kind="ramp", rates=[15000], algorithms=ALL_ALGS, profiles=["mixed"]),
    "B3": dict(script="b3-overhead.js", kind="constant", rates=[1000, 5000], algorithms=["token_bucket"], profiles=["mixed"],
               variants=["default", "limiter-off"]),
    "B4": dict(script="b4-headroom-throttled.js", kind="constant", rates=[5000], algorithms=ALL_ALGS, profiles=["headroom", "throttled"]),
    "B5": dict(script="b5-burst.js", kind="burst", rates=[10000], algorithms=ALL_ALGS, profiles=["hotkey"]),
    "B6": dict(script="b6-concurrency.js", kind="concurrency", rates=[1000], algorithms=["token_bucket"], profiles=["hotkey"],
               policy_variant="concurrency"),
    "B7": dict(script="b7-distributed.js", kind="constant", rates=[5000], algorithms=ALL_ALGS, profiles=["mixed"],
               targets=["nginx", "direct"]),
    "B8": dict(script="b8-hot-key.js", kind="constant", rates=[2000, 5000], algorithms=ALL_ALGS, profiles=["hotkey"]),
    "B9": dict(script="b9-pooling.js", kind="constant", rates=[1000, 5000], algorithms=ALL_ALGS, profiles=["mixed"],
               variants=["default", "pool-16", "pool-8", "pool-32"]),
    "B10": dict(script="b10-correctness.js", kind="constant", rates=[2000], algorithms=ALL_ALGS, profiles=["throttled"]),
}
for f in ("F1", "F2", "F3", "F4", "F5"):
    SCENARIOS[f] = dict(script="f-failure.js", kind="failure", rates=[2000], algorithms=["fixed_window"], profiles=["mixed"])

VARIANTS = {
    "default": dict(files=[], env={}, expect={"RATELIMITER_FILTER_ENABLED": None, "REDIS_POOL_ENABLED": "false"}),
    "limiter-off": dict(files=["scripts/bench/compose/limiter-off.yml"], env={},
                        expect={"RATELIMITER_FILTER_ENABLED": "false", "REDIS_POOL_ENABLED": "false"}),
}
for n in (8, 16, 32):
    VARIANTS[f"pool-{n}"] = dict(files=["scripts/bench/compose/pool.yml"], env={"REDIS_POOL_ENABLED": "true", "BENCH_POOL_MAX_ACTIVE": str(n)},
                                 expect={"REDIS_POOL_ENABLED": "true", "REDIS_POOL_MAX_ACTIVE": str(n), "RATELIMITER_FILTER_ENABLED": None})

FAULT_OFFSET_S = 30
# k6 keeps every latency sample in memory for its end-of-run percentiles; without a soft limit Go's heap target
# (GOGC=100) runs into the 1 GiB container limit at >= 7,500 RPS and the load generator stalls. GOMEMLIMIT makes the
# Go runtime collect garbage before that. Container limits stay as approved (2 CPU, 1 GiB).
K6_GOMEMLIMIT = "850MiB"


def log(msg):
    print(f"[{datetime.datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


# ------------------------------------------------------------------------------------------------ planning

def plan(args):
    sc = SCENARIOS.get(args.scenario)
    if sc is None:
        raise bl.BenchError(f"unknown scenario {args.scenario}; approved: {', '.join(SCENARIOS)}")
    if args.algorithm not in sc["algorithms"]:
        raise bl.BenchError(f"{args.scenario} runs {sc['algorithms']}, not {args.algorithm}")
    if args.rate not in sc["rates"] and not args.nonstandard_rate:
        raise bl.BenchError(f"{args.scenario} approved rates are {sc['rates']}; use --nonstandard-rate for knee confirmation")
    profile = args.profile or sc["profiles"][0]
    if profile not in sc["profiles"]:
        raise bl.BenchError(f"{args.scenario} profiles are {sc['profiles']}")
    variant = args.variant
    if variant != "default" and variant not in sc.get("variants", []):
        raise bl.BenchError(f"{args.scenario} does not use stack variant {variant}")
    target = args.target
    if target != "nginx" and target not in sc.get("targets", []):
        raise bl.BenchError(f"{args.scenario} does not use target {target}")
    kind = sc["kind"]
    warmup = 0 if kind == "concurrency" else args.warmup
    measure = args.measure
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    parts = [stamp, args.scenario.lower(), args.algorithm, str(args.rate)]
    if profile != sc["profiles"][0]:
        parts.append(profile)
    if variant != "default":
        parts.append(variant)
    if target != "nginx":
        parts.append(target)
    parts.append(f"r{args.rep}")
    run_id = "-".join(parts)
    bench = args.scenario if kind != "failure" else args.scenario
    env = {"GOMEMLIMIT": K6_GOMEMLIMIT,
           "RUN_ID": run_id, "RATE": str(args.rate), "ALGORITHM": args.algorithm, "PROFILE": profile,
           "WARMUP_S": str(warmup), "MEASURE_S": str(measure), "TOKENS_FILE": "/tokens/bench.json", "OUT_DIR": "/results"}
    burst_s, vus = 1, 1000
    if args.scenario in ("B5", "B6", "B8"):
        env["HOT_TOKEN"] = "<minted at run time>"
    if args.scenario in ("B5", "B10"):
        env["UA_USER"] = "1"
    if args.scenario == "B5":
        env.update({"WARMUP_RATE": "1000", "BURST_S": str(burst_s)})
    if args.scenario == "B6":
        env["VUS"] = str(vus)
    if target == "direct":
        env["BASE_URLS"] = "http://app1:8080,http://app2:8080,http://app3:8080"
    if kind == "failure":
        env.update({"FAULT": args.scenario, "ENT_TOKENS_FILE": "/tokens/bench-enterprise.json"})
    if kind == "ramp":
        env.update({"START_RATE": "1000", "STEP_RATE": "1000", "STEP_S": "30"})
    for kv in args.k6_env or []:
        k, _, v = kv.partition("=")
        env[k] = v
    if kind == "constant":
        expected = args.rate * measure
        measure_scenarios = [f"{bench.lower()}_measure"]
    elif kind == "failure":
        expected = args.rate * measure
        measure_scenarios = ["f_profile_measure", "f_enterprise_measure"]
    elif kind == "burst":
        expected = args.rate * burst_s
        measure_scenarios = [f"{bench.lower()}_measure"]
    elif kind == "concurrency":
        expected = vus
        measure_scenarios = [f"{bench.lower()}_measure"]
    else:
        expected = None
        measure_scenarios = [f"{bench.lower()}_measure"]
    algorithms_used = [args.algorithm] + (["sliding_window"] if kind == "failure" else [])
    return {
        "run_id": run_id, "scenario": args.scenario, "kind": kind, "script": sc["script"], "algorithm": args.algorithm,
        "rate": args.rate, "rep": args.rep, "profile": profile, "variant": variant, "target": target,
        "warmup_s": warmup, "measure_s": measure, "burst_s": burst_s if kind == "burst" else None,
        "expected_requests": expected, "measure_scenarios": measure_scenarios, "k6_env": env,
        "policy_variant": sc.get("policy_variant", "default"), "algorithms_used": algorithms_used,
        "filter_enabled": variant != "limiter-off", "nonstandard_rate": args.rate not in sc["rates"],
        "dry_run": args.dry_run, "planned_at": time.time(),
    }


# ------------------------------------------------------------------------------------------------ preflight

def app_env(app, names):
    out = bl.sh("docker", "compose", "exec", "-T", app, "sh", "-c", "env").stdout
    env = dict(line.split("=", 1) for line in out.splitlines() if "=" in line)
    return {n: env.get(n) for n in names}


def preflight(meta, fresh_nginx=True):
    report = {"at": time.time()}
    if fresh_nginx:
        log("preflight: fresh Nginx container (empty access log for this run)")
        bl.sh("docker", "compose", "up", "-d", "--no-deps", "--force-recreate", "--wait", "nginx", timeout=180)
    problems = bl.stack_problems(bl.compose_ps())
    report["stack_problems"] = problems
    if problems:
        raise bl.BenchError("stack is not healthy: " + "; ".join(problems))
    up = bl.prom_query('up{job=~"app|redis"}')["result"]
    report["prometheus_targets"] = {r["metric"].get("instance"): r["value"][1] for r in up}
    if sorted(i for i, v in report["prometheus_targets"].items() if v == "1") != ["app-1", "app-2", "app-3", "redis"]:
        raise bl.BenchError(f"Prometheus targets not all up: {report['prometheus_targets']}")
    snap = collect.instance_snapshot()
    report["breakers"] = {a: snap[a]["breaker"] for a in bl.APPS}
    if any(s != "closed" for s in report["breakers"].values()):
        raise bl.BenchError(f"a circuit breaker is not CLOSED: {report['breakers']}")
    want = VARIANTS[meta["variant"]]["expect"]
    names = sorted(set(want) | {"REDIS_COMMAND_TIMEOUT", "SERVER_TOMCAT_THREADS_MAX", "REDIS_POOL_MAX_ACTIVE"})
    envs = {a: app_env(a, names) for a in bl.APPS}
    report["app_env"] = envs
    for a in bl.APPS:
        if envs[a]["REDIS_COMMAND_TIMEOUT"] != EXPECTED_TIMEOUT:
            raise bl.BenchError(f"{a}: REDIS_COMMAND_TIMEOUT={envs[a]['REDIS_COMMAND_TIMEOUT']}, expected {EXPECTED_TIMEOUT} (D42)")
        for k, v in want.items():
            if envs[a].get(k) != v:
                raise bl.BenchError(f"{a}: {k}={envs[a].get(k)!r}, stack variant {meta['variant']} expects {v!r}; "
                                    f"apply it first: scripts/bench/run.sh variant {meta['variant']}")
    report["tokens"] = token_check(meta)
    return report


def token_check(meta):
    import base64
    need = time.time() + meta["warmup_s"] + meta["measure_s"] + 600
    out = {}
    files = [TOKENS] + ([ENT_TOKENS] if meta["kind"] == "failure" else [])
    for f in files:
        doc = bl.read_json(f)
        if not doc:
            raise bl.BenchError(f"token file missing: {f} (mint with scripts/bench/mint-tokens.py)")
        exps = []
        for u in doc["users"]:
            payload = u["token"].split(".")[1]
            exps.append(json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))["exp"])
        groups = sorted({u["group"] for u in doc["users"]})
        out[f.name] = {"users": len(exps), "groups": groups, "earliest_exp": min(exps)}
        if min(exps) < need:
            raise bl.BenchError(f"tokens in {f.name} expire before this run ends; mint them again with a longer --ttl")
    return out


# ------------------------------------------------------------------------------------------------ policies

def setup_policies(*args, variant="default"):
    return bl.sh("scripts/bench/setup-policies.sh", *args, env={"POLICY_VARIANT": variant}, timeout=300)


def bench_policy_id(variant="default"):
    out = setup_policies("show", variant=variant).stdout
    return json.loads(out)["id"] if out.strip().startswith("{") else None


def policy_id_by_name(admin, name):
    status, _, body = bl.http("GET", f"{bl.NGINX}/api/v1/policies?size=100", token=admin)
    for p in json.loads(body)["content"]:
        if p["name"] == name:
            return p["id"]
    raise bl.BenchError(f"policy {name} not found")


def prepare_policy(meta):
    if meta["policy_variant"] == "concurrency":
        setup_policies("delete")                                   # bench-profile would conflict
        out = setup_policies("apply", "token_bucket", variant="concurrency").stdout
    else:
        setup_policies("delete", variant="concurrency")
        if not meta["filter_enabled"]:
            # No decisions are made with the filter off, so no policy can be verified through rate-limit headers;
            # verify_filter_off() checks all three instances instead.
            out = "limiter disabled: policy probes not applicable; filter-off verified on all three instances"
        else:
            out = setup_policies("apply", meta["algorithm"]).stdout
    return out


def verify_filter_off():
    seen = {}
    for i in range(30):
        tok = mint_user(f"filteroff-probe-{int(time.time())}-{i}", "FREE")
        status, h, _ = bl.http("GET", f"{bl.NGINX}/api/v1/demo/profile", token=tok)
        seen[bl.UPSTREAM_APP.get(h.get("X-Upstream", "").split(":")[0], "?")] = {
            "status": status, "ratelimit_header": h.get("X-RateLimit-Limit")}
        if len([a for a, v in seen.items() if a in bl.APPS and v["status"] == 200 and v["ratelimit_header"] is None]) == 3:
            return seen
    raise bl.BenchError(f"could not confirm the limiter is off on all instances: {seen}")


def mint_admin():
    return bl.sh("python3", "scripts/bench/mint-tokens.py", "--print-admin", "--ttl", "7200").stdout.strip()


def mint_user(user_id, tier, ttl=7200):
    return bl.sh("python3", "scripts/bench/mint-tokens.py", "--print-user", user_id, tier, "--ttl", str(ttl)).stdout.strip()


# ------------------------------------------------------------------------------------------------ faults (F1–F5)

# F5 degradation without DEBUG (disabled by this project's Redis: enable-debug-command no) and without weakening any
# setting: a pure-CPU Lua loop sent with the standard EVAL command blocks Redis's single command thread for a
# calibrated time. Redis 7 freezes TIME inside scripts, so the loop length is calibrated beforehand from Redis's own
# per-command microsecond counter (INFO commandstats, cmdstat_eval usec).
SLOW_LUA = "local n=tonumber(ARGV[1]) local x=0 for i=1,n do x=x+1 end return 1"


def calibrate_busy_loop(ident, n=40_000_000, rounds=3):
    if bl.redis_safety_violations(ident):
        raise bl.BenchError("Redis identity check failed; F5 calibration not sent")
    per_iter = []
    for _ in range(rounds):
        before = bl.parse_redis_info(bl.redis_cli("INFO", "commandstats"))["commandstats"].get("cmdstat_eval", {})
        bl.sh("docker", "exec", ident["container_id"], "redis-cli", "EVAL", SLOW_LUA, "0", str(n))
        after = bl.parse_redis_info(bl.redis_cli("INFO", "commandstats"))["commandstats"]["cmdstat_eval"]
        usec = int(after["usec"]) - int(before.get("usec", 0))
        per_iter.append(usec / n)
        time.sleep(0.5)
    return {"iterations_probe": n, "usec_per_iteration": sorted(per_iter)[len(per_iter) // 2], "samples": per_iter}


def fault_plan(scenario):
    """[(offset seconds from measure start, action, args)]. All targets are this project's Compose services."""
    o = FAULT_OFFSET_S
    if scenario == "F1":
        return [(o, "compose", ["pause", "redis"]), (o + 20, "compose", ["unpause", "redis"])]
    if scenario == "F2":
        return [(o, "compose", ["restart", "redis"])]
    if scenario == "F3":
        return [(o, "compose", ["kill", "app2"]), (o + 30, "compose", ["start", "app2"])]
    if scenario == "F4":
        return [(o, "compose", ["stop", "mysql"]), (o + 30, "check_mysql_down", []), (o + 60, "compose", ["start", "mysql"])]
    if scenario == "F5":
        ev = []
        for k in range(60):                      # 30 s: a 150 ms block every 500 ms (below the 250 ms timeout)
            ev.append((o + k * 0.5, "redis_block_us", [150_000]))
        for k in range(30):                      # 30 s: a 400 ms block every 1 s (above the 250 ms timeout)
            ev.append((o + 40 + k, "redis_block_us", [400_000]))
        return ev
    return []


def run_faults(meta, t_measure_start, events, stop_flag, redis_ident, admin):
    for offset, action, args in fault_plan(meta["scenario"]):
        delay = t_measure_start + offset - time.time()
        if delay > 0 and stop_flag.wait(delay):
            return
        rec = {"offset_s": offset, "action": action, "args": args, "at": time.time()}
        try:
            if action == "compose":
                p = bl.sh("docker", "compose", *args, check=False, timeout=120)
                rec["rc"], rec["output"] = p.returncode, (p.stdout + p.stderr)[-500:]
            elif action == "redis_block_us":
                violations = bl.redis_safety_violations(redis_ident)
                if violations:
                    rec["skipped"] = "redis identity check failed: " + "; ".join(violations)
                else:
                    n = int(args[0] / meta["f5_calibration"]["usec_per_iteration"])
                    rec["iterations"] = n
                    usec = lambda: int(bl.parse_redis_info(bl.redis_cli("INFO", "commandstats"))["commandstats"]  # noqa: E731
                                       .get("cmdstat_eval", {}).get("usec", 0))
                    u0 = usec()
                    p = bl.sh("docker", "exec", redis_ident["container_id"], "redis-cli", "EVAL", SLOW_LUA, "0", str(n),
                              check=False, timeout=30)
                    rec["redis_measured_block_ms"] = round((usec() - u0) / 1000.0, 1)   # includes any stats-flush EVAL (µs)
                    rec["rc"], rec["output"] = p.returncode, (p.stdout + p.stderr).strip()[-200:]
            elif action == "check_mysql_down":
                status, _, body = bl.http("GET", f"{bl.NGINX}/api/v1/policies?size=1", token=admin)
                rec["policy_api_status"] = status
                rec["readiness"] = {a: bl.sh("docker", "compose", "exec", "-T", a, "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                                             "http://localhost:8080/actuator/health/readiness", check=False).stdout for a in bl.APPS}
        except Exception as e:  # noqa: BLE001 - recorded, never fatal for the run
            rec["error"] = str(e)
        rec["done"] = time.time()
        events.append(rec)


def restore_after_faults(meta):
    """Always bring every fault target back, whatever happened during the run."""
    if meta["kind"] != "failure":
        return []
    out = []
    for args in (["unpause", "redis"], ["start", "redis"], ["start", "app2"], ["start", "mysql"]):
        p = bl.sh("docker", "compose", *args, check=False, timeout=120)
        out.append({"cmd": args, "rc": p.returncode})
    bl.sh("docker", "compose", "up", "-d", "--wait", "--no-recreate", timeout=300, check=False)
    return out


# ------------------------------------------------------------------------------------------------ k6

def k6_args(meta, run_dir, base, mode):
    args = ["docker", "compose", "--profile", "loadtest", "run", "--rm", "--no-deps", "-v", f"{base}:/results"]
    for k, v in meta["k6_env_actual"].items():
        args += ["-e", f"{k}={v}"]
    script = f"/results/{meta['run_id']}/k6/{meta['script']}"
    if mode == "inspect":
        return args + ["k6", "inspect", "--include-system-env-vars", "--execution-requirements", script]
    return args + ["k6", "run", "--quiet", script]


def copy_k6(run_dir, meta):
    dst = run_dir / "k6"
    (dst / "lib").mkdir(parents=True, exist_ok=True)
    shutil.copy2(K6_DIR / meta["script"], dst / meta["script"])
    shutil.copy2(K6_DIR / "lib" / "common.js", dst / "lib" / "common.js")
    safe_env = {k: ("<redacted bearer token>" if "TOKEN" in k and k.endswith("TOKEN") else v) for k, v in meta["k6_env_actual"].items()}
    bl.write_json(dst / "k6-env.json", safe_env)


def k6_inspect(meta, run_dir, base):
    p = bl.sh(*k6_args(meta, run_dir, base, "inspect"), check=False, timeout=180)
    text = p.stdout
    try:
        data = json.loads(text[text.index("{"):])
    except ValueError:
        raise bl.BenchError(f"k6 inspect failed ({p.returncode}): {(p.stdout + p.stderr)[-1500:]}")
    bl.write_json(run_dir / "k6" / "k6-inspect.json", data)
    return data


def start_sampler(path):
    return subprocess.Popen(["scripts/bench/docker-stats.sh", str(path)], cwd=bl.ROOT, stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, start_new_session=True)


def stop_sampler(p):
    if p and p.poll() is None:
        os.killpg(p.pid, signal.SIGTERM)
        try:
            p.wait(10)
        except subprocess.TimeoutExpired:
            os.killpg(p.pid, signal.SIGKILL)


# ------------------------------------------------------------------------------------------------ the run

def execute(meta):
    dry = meta["dry_run"]
    base = DRY if dry else RESULTS
    run_dir = base / meta["run_id"]
    if run_dir.exists():
        raise bl.BenchError(f"{run_dir} exists; results are never overwritten")
    run_dir.mkdir(parents=True)
    meta["run_dir"] = str(run_dir.relative_to(bl.ROOT))
    meta["status"] = "started"
    bl.write_json(run_dir / "run-meta.json", meta)
    events = {"steps": []}

    def step(name, **kw):
        events["steps"].append({"step": name, "at": time.time(), **kw})
        log(name)

    sampler = None
    fault_events, stop_flag, fault_thread = [], threading.Event(), None
    try:
        step("preflight")
        meta["preflight"] = preflight(meta)

        step("policy setup and verification on all three instances (probes)")
        admin = mint_admin()
        meta["policy_setup_output"] = prepare_policy(meta)
        if not meta["filter_enabled"]:
            meta["filter_off_probe"] = verify_filter_off()
        pid = bench_policy_id(meta["policy_variant"]) if meta["filter_enabled"] else None
        meta["policies"] = {} if pid is None else {str(pid): {
            "name": "bench-concurrency" if meta["policy_variant"] == "concurrency" else "bench-profile", "algorithm": meta["algorithm"]}}
        if meta["kind"] == "failure":
            meta["policies"][str(policy_id_by_name(admin, "demo-enterprise-endpoint"))] = {
                "name": "demo-enterprise-endpoint", "algorithm": "sliding_window"}

        step("idle 6 s: pending statistics flushes complete")
        time.sleep(6)

        step("Redis identity and safety check")
        ident = bl.redis_identity()
        violations = bl.redis_safety_violations(ident)
        meta["redis_safety"] = {"identity": ident, "violations": violations}
        if violations:
            raise bl.BenchError("Redis safety check failed; no destructive command was sent: " + "; ".join(violations))
        if dry:
            meta["redis_reset"] = {"dry_run": "would run FLUSHDB, CONFIG RESETSTAT, SLOWLOG RESET on container "
                                              + ident["container_id"][:12]}
        else:
            step("Redis reset: FLUSHDB + CONFIG RESETSTAT + SLOWLOG RESET on the verified container")
            reset = {"container_id": ident["container_id"], "flushdb": bl.redis_admin(ident, "FLUSHDB")}
            reset["dbsize_after"] = int(bl.redis_cli("DBSIZE").strip())
            reset["resetstat"] = bl.redis_admin(ident, "CONFIG", "RESETSTAT")
            reset["slowlog_reset"] = bl.redis_admin(ident, "SLOWLOG", "RESET")
            meta["redis_reset"] = reset

        if meta["scenario"] == "F5" and not dry:
            step("F5: calibrating the Lua busy loop (before the idle snapshots)")
            meta["f5_calibration"] = calibrate_busy_loop(ident)
        env = dict(meta["k6_env"])
        if "HOT_TOKEN" in env:
            env["HOT_TOKEN"] = mint_user(f"{meta['run_id'][:40]}-hot", "FREE")
        meta["k6_env_actual"] = env
        copy_k6(run_dir, meta)
        inspect = k6_inspect(meta, run_dir, base)
        sc = inspect.get("scenarios", {})
        mstart = sc.get(meta["measure_scenarios"][0], {}).get("startTime")
        meta["measure_start_offset_s"] = parse_duration(mstart)
        meta["k6_total_duration"] = inspect.get("totalDuration")

        if dry:
            step("dry run: k6 inspect only (no requests sent); docker-stats sampler test")
            sampler = start_sampler(run_dir / "docker-stats.jsonl")
            time.sleep(4)
            stop_sampler(sampler)
            meta["sampler_test"] = collect.summarize_docker_rows(
                [r for r in map(bl.parse_docker_stats_line, open(run_dir / "docker-stats.jsonl")) if r], 0, 1e12)
            meta["environment"] = collect.environment({"run_id": meta["run_id"], "dry_run": True})
            (run_dir / "environment.txt").write_text(collect.environment_text(meta["environment"]))
            meta["status"] = "dry-run complete"
            return finish(run_dir, meta, events)

        stats_before = {p: collect.stats_api(p, admin) for p in meta["policies"]}
        step("idle 11 s: Prometheus scrapes the idle state")
        time.sleep(11)
        step("snapshots before the run (raw counters, Prometheus, Redis)")
        raw_before = collect.instance_snapshot(run_dir, "before")
        t_before = time.time()
        prom_before = collect.prom_counts_at(t_before)
        redis_before = collect.redis_snapshot(run_dir, "before")

        sampler = start_sampler(run_dir / "docker-stats.jsonl")
        step("k6 run", script=meta["script"])
        t_k6_start = time.time()
        proc = subprocess.Popen(k6_args(meta, run_dir, base, "run"), cwd=bl.ROOT, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True)
        t0 = None
        with open(run_dir / "k6.log", "w") as klog:
            for line in proc.stdout:
                klog.write(line)
                if t0 is None and "BENCH_T0=" in line:
                    t0 = int(line.split("BENCH_T0=")[1].split('"')[0].split()[0]) / 1000.0
                    if meta["kind"] == "failure":
                        fault_thread = threading.Thread(target=run_faults, daemon=True, args=(
                            meta, t0 + meta["warmup_s"], fault_events, stop_flag, ident, admin))
                        fault_thread.start()
        rc = proc.wait()
        t_k6_end = time.time()
        stop_flag.set()
        if fault_thread:
            fault_thread.join(5)
        meta.update({"k6_exit_code": rc, "k6_start": t_k6_start, "k6_end": t_k6_end, "t0": t0})
        if meta["kind"] == "failure":
            meta["faults"] = fault_events
            meta["fault_restore"] = restore_after_faults(meta)

        step("cooldown 12 s: final Prometheus scrape (2 × 5 s) and final statistics flush (5 s)")
        time.sleep(12)
        step("snapshots after the run")
        raw_after = collect.instance_snapshot(run_dir, "after")
        t_after = time.time()
        prom_after = collect.prom_counts_at(t_after)
        redis_after = collect.redis_snapshot(run_dir, "after")
        stop_sampler(sampler)
        sampler = None
        stats_after = {p: collect.stats_api(p, admin) for p in meta["policies"]}
        try:
            meta["key_memory_usage"] = collect.key_memory_samples(pid, meta["algorithm"]) if pid else "n/a (limiter off)"
        except bl.BenchError as e:
            meta["key_memory_usage"] = f"unavailable: {e}"

        summary = bl.read_json(run_dir / "k6-summary.json")
        if t0 is None and summary:
            t0 = (summary.get("setup_data") or {}).get("t0", 0) / 1000.0 or None
            meta["t0"] = t0
        window = measure_window(meta, t0, t_k6_end)
        meta["measure_window"] = window

        step("collect: Prometheus measure window, Nginx, docker stats")
        promw = collect.prometheus_window(run_dir, window["start"], window["end"], meta["algorithm"], meta["filter_enabled"])
        bl.write_json(run_dir / "prometheus" / "window-summary.json", promw)
        if meta["kind"] == "ramp":
            meta["ramp_steps"] = ramp_windows(meta, t0)
            collect.ramp_steps_prometheus(run_dir, meta["ramp_steps"], meta["algorithm"])
        nginx = None
        if meta["target"] == "nginx":
            nginx = collect.nginx_collect(run_dir, meta["run_id"], per_user=meta["scenario"] in ("B5", "B10"),
                                          timeline=meta["kind"] == "failure", keep_raw=meta["scenario"] in ("B10",) or meta["kind"] == "failure")
        docker = collect.docker_stats_summary(run_dir / "docker-stats.jsonl", run_dir / "docker-stats.csv", window["start"], window["end"])
        bl.write_json(run_dir / "docker-stats-summary.json", docker)
        gzip_file(run_dir / "docker-stats.jsonl")       # raw samples kept, compressed (docker-stats.csv is the table)
        if meta["kind"] == "failure":
            for app in bl.APPS + ["redis", "mysql"]:
                p = bl.sh("docker", "compose", "logs", app, "--no-log-prefix", "--since", iso(t_k6_start - 5), check=False)
                (run_dir / "logs").mkdir(exist_ok=True)
                (run_dir / "logs" / f"{app}.log").write_text(p.stdout + p.stderr)
            meta["breaker_timeline"] = breaker_timeline(t_k6_start, t_k6_end + 12)

        sources = {
            "k6": k6_counts(summary),
            "nginx": nginx and {"total": nginx["total"], "phases": nginx["phases"], "upstream": nginx["upstream"],
                                "paths": nginx["paths"]},
            "raw": collect.counters_delta(raw_before, raw_after),
            "prom": {**collect.prom_counts_delta(prom_before, prom_after),
                     "sample_timestamps_after": prom_after["sample_timestamps"]},
            "stats": {p: {**stats_after[p], "algorithm": meta["policies"][p]["algorithm"], "before": stats_before[p]}
                      for p in meta["policies"]},
            "redis": {"evalsha_delta": redis_after["evalsha_calls"] - redis_before["evalsha_calls"],
                      "before": redis_before, "after": redis_after},
            "breakers": collect.breaker_summary(raw_before, raw_after),
            "config": {"algorithms": meta["algorithms_used"], "filter_enabled": meta["filter_enabled"], "k6_end": t_k6_end},
        }
        bl.write_json(run_dir / "sources.json", sources)
        rec = reconcile.reconcile(sources)
        bl.write_json(run_dir / "reconcile.json", rec)
        val = validity.evaluate(meta, summary, docker, rec, meta.get("redis_reset"), promw)
        bl.write_json(run_dir / "validity.json", val)
        users = bl.read_json(run_dir / "nginx" / "per-user.json")
        corr = validity.correctness(meta, summary, users)
        if corr is not None:
            bl.write_json(run_dir / "correctness.json", corr)
        meta["valid"] = val["valid"]
        meta["invalid_reasons"] = val["reasons"]
        meta["correctness_pass"] = None if corr is None else corr["pass"]
        meta["environment"] = collect.environment({"run_id": meta["run_id"]})
        (run_dir / "environment.txt").write_text(collect.environment_text(meta["environment"]))
        meta["status"] = "complete"
        return finish(run_dir, meta, events)
    except Exception as e:
        meta["status"] = "error"
        meta["error"] = f"{type(e).__name__}: {e}"
        log(f"ERROR: {meta['error']}")
        finish(run_dir, meta, events)
        raise
    finally:
        stop_flag.set()
        stop_sampler(sampler)
        if meta["kind"] == "failure" and "fault_restore" not in meta and not dry:
            restore_after_faults(meta)


JWT_RE = __import__("re").compile(r"eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")


def redacted(meta):
    """run-meta.json never contains bearer tokens (the in-memory meta keeps them for k6)."""
    return json.loads(JWT_RE.sub("<redacted bearer token>", json.dumps(meta, default=str)))


def finish(run_dir, meta, events):
    meta["finished_at"] = time.time()
    bl.write_json(run_dir / "run-meta.json", redacted(meta))
    bl.write_json(run_dir / "run-steps.json", events)
    return meta


def gzip_file(path):
    import gzip
    with open(path, "rb") as src, gzip.open(str(path) + ".gz", "wb") as dst:
        shutil.copyfileobj(src, dst)
    path.unlink()


def parse_duration(s):
    if not s:
        return 0
    total, num = 0.0, ""
    for part in __import__("re").findall(r"(\d+(?:\.\d+)?)(ms|h|m|s)", s):
        total += float(part[0]) * {"h": 3600, "m": 60, "s": 1, "ms": 0.001}[part[1]]
    return int(total) if total == int(total) else total


def measure_window(meta, t0, t_end):
    if t0 is None:
        return {"start": t_end - meta["measure_s"], "end": t_end, "source": "k6 end (t0 unknown)"}
    start = t0 + meta["warmup_s"]
    if meta["kind"] in ("constant", "failure"):
        return {"start": start, "end": start + meta["measure_s"], "source": "setup t0 + warm-up"}
    if meta["kind"] == "ramp":
        steps = ramp_windows(meta, t0)
        return {"start": start, "end": steps[-1][2], "source": "setup t0 + warm-up, all ramp steps"}
    return {"start": start, "end": t_end, "source": "setup t0 + warm-up to k6 end"}


def ramp_windows(meta, t0):
    env = meta["k6_env"]
    start_rate, step_rate, step_s = int(env["START_RATE"]), int(env["STEP_RATE"]), int(env["STEP_S"])
    out, r, k = [], start_rate, 0
    while r <= meta["rate"]:
        s = t0 + meta["warmup_s"] + k * step_s
        out.append((r, s + 2, s + step_s))          # skip the 1 s ramp between steps
        r += step_rate
        k += 1
    return out


def k6_counts(summary):
    if not summary:
        return None
    c = lambda n: bl.k6_metric(summary, n, "count") or 0  # noqa: E731
    return {"accepted": c("accepted"), "rejected": c("rejected"), "unexpected": c("unexpected"), "requests": c("http_reqs"),
            "phases": {p: {"accepted": c(f"accepted{{phase:{p}}}"), "rejected": c(f"rejected{{phase:{p}}}"),
                           "unexpected": c(f"unexpected{{phase:{p}}}"), "requests": c(f"http_reqs{{phase:{p}}}")}
                       for p in ("warmup", "measure")}}


def breaker_timeline(start, end):
    out = {}
    for state in ("closed", "open", "half_open"):
        data = bl.prom_range(f'resilience4j_circuitbreaker_state{{state="{state}"}}', start, end, 1)
        for s in data["result"]:
            out.setdefault(s["metric"].get("instance"), {})[state] = [[float(t), float(v)] for t, v in s["values"]]
    trans = bl.prom_range("ratelimiter_circuit_breaker_transitions_total", start, end, 1)
    out["_transitions"] = [{"labels": s["metric"], "values": [[float(t), float(v)] for t, v in s["values"]]} for s in trans["result"]]
    return out


def iso(t):
    return datetime.datetime.fromtimestamp(t, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# ------------------------------------------------------------------------------------------------ other commands

def apply_variant(name):
    v = VARIANTS[name]
    files = ["-f", "docker-compose.yml"] + [x for f in v["files"] for x in ("-f", f)]
    log(f"applying stack variant {name}: recreating app1, app2, app3")
    bl.sh("docker", "compose", *files, "up", "-d", "--no-deps", "--wait", "app1", "app2", "app3", env=v["env"], timeout=600)
    bl.sh("docker", "compose", "up", "-d", "--no-deps", "--force-recreate", "--wait", "nginx", timeout=180)
    for a in bl.APPS:
        got = app_env(a, list(v["expect"]))
        if got != v["expect"]:
            raise bl.BenchError(f"{a}: variant {name} not applied: {got} != {v['expect']}")
    log(f"variant {name} active on all three instances")


def jit_warmup(seconds=120, rate=1000):
    """Before the first run of a session (and after any instance restart): 2 minutes at 1,000 RPS (plan). Not a
    result; saved under benchmarks/warmups/."""
    run_id = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-jit-warmup"
    d = WARMUPS / run_id
    (d / "k6" / "lib").mkdir(parents=True)
    shutil.copy2(K6_DIR / "b1-load-levels.js", d / "k6" / "b1-load-levels.js")
    shutil.copy2(K6_DIR / "lib" / "common.js", d / "k6" / "lib" / "common.js")
    if app_env("app1", ["RATELIMITER_FILTER_ENABLED"])["RATELIMITER_FILTER_ENABLED"] == "false":
        # limiter-off variant (B3): no rate-limit headers exist, so the policy cannot (and need not) be verified
        log("JIT warm-up with the limiter disabled: policy setup skipped")
    else:
        setup_policies("delete", variant="concurrency")
        setup_policies("apply", "token_bucket")
    args = ["docker", "compose", "--profile", "loadtest", "run", "--rm", "--no-deps", "-v", f"{WARMUPS}:/results",
            "-e", f"GOMEMLIMIT={K6_GOMEMLIMIT}", "-e", f"RUN_ID={run_id}", "-e", f"RATE={rate}", "-e", "ALGORITHM=token_bucket", "-e", "WARMUP_S=0",
            "-e", f"MEASURE_S={seconds}", "k6", "run", "--quiet", f"/results/{run_id}/k6/b1-load-levels.js"]
    log(f"JIT warm-up: {seconds} s at {rate} RPS (not a result)")
    p = bl.sh(*args, check=False, timeout=seconds + 120)
    (d / "k6.log").write_text(p.stdout + p.stderr)
    log("JIT warm-up done: " + (p.stdout.strip().splitlines() or ["no output"])[-1])


def main():
    ap = argparse.ArgumentParser(description="Phase 3B benchmark runner (see the module docstring)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run", help="execute one run")
    r.add_argument("--scenario", required=True)
    r.add_argument("--algorithm", required=True, choices=ALL_ALGS)
    r.add_argument("--rate", required=True, type=int)
    r.add_argument("--rep", required=True, type=int)
    r.add_argument("--profile")
    r.add_argument("--variant", default="default", choices=sorted(VARIANTS))
    r.add_argument("--target", default="nginx", choices=["nginx", "direct"])
    r.add_argument("--warmup", type=int, default=60)
    r.add_argument("--measure", type=int, default=180)
    r.add_argument("--nonstandard-rate", action="store_true")
    r.add_argument("--k6-env", action="append", help="extra KEY=VALUE for k6 (recorded)")
    r.add_argument("--dry-run", action="store_true")
    v = sub.add_parser("variant", help="apply a stack variant (recreates the three instances)")
    v.add_argument("name", choices=sorted(VARIANTS))
    sub.add_parser("redis-safety", help="print the Redis identity and safety verdict (no destructive command)")
    j = sub.add_parser("jit-warmup", help="2 minutes at 1,000 RPS before the first run of a session (not a result)")
    j.add_argument("--seconds", type=int, default=120)
    a = ap.parse_args()
    try:
        if a.cmd == "run":
            meta = execute(plan(a))
            status = meta["status"]
            if status == "complete":
                corr = "" if meta.get("correctness_pass") is None else f" correctness={'PASS' if meta['correctness_pass'] else 'FAIL'}"
                print(f"RESULT {meta['run_id']}: {'VALID' if meta['valid'] else 'INVALID'}{corr}"
                      + ("" if meta["valid"] else " — " + "; ".join(meta["invalid_reasons"])))
            else:
                print(f"RESULT {meta['run_id']}: {status}")
        elif a.cmd == "variant":
            apply_variant(a.name)
        elif a.cmd == "redis-safety":
            ident = bl.redis_identity()
            v = bl.redis_safety_violations(ident)
            print(json.dumps({"identity": ident, "violations": v, "safe": not v}, indent=2))
            sys.exit(0 if not v else 3)
        elif a.cmd == "jit-warmup":
            jit_warmup(a.seconds)
    except bl.BenchError as e:
        print(f"REFUSED: {e}", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
