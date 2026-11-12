#!/usr/bin/env python3
"""Cold-start investigation (Phase 3B sign-off): recreate the three app instances, optionally warm them up, then run
1,000-concurrent single-shot bursts (the existing smoke-concurrency.js, one fresh user per round, against the
bench-concurrency policy: token bucket capacity 100) and record, per instance and round:
  * CPU quota throttling (cgroup v2 cpu.stat nr_throttled / throttled_usec deltas),
  * JIT compilation time (jvm_compilation_time_ms_total delta), live threads, Tomcat threads,
  * fail-open decisions, breaker openings, max Redis script duration (ratelimiter_redis_duration_seconds_max).
Not a benchmark scenario: results go to benchmarks/investigations/cold-start/<label>-<time>.json only.

  python3 benchmarks/investigations/cold-start/burst_probe.py <label> [--warmup-seconds N] [--warmup-rate R] [--rounds 4]
"""
import argparse, datetime, json, pathlib, re, subprocess, sys, time

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "bench"))
import benchlib as bl  # noqa: E402

def app_metrics(app):
    stat = bl.sh("docker", "compose", "exec", "-T", app, "cat", "/sys/fs/cgroup/cpu.stat").stdout
    s = dict(l.split() for l in stat.splitlines() if l.strip())
    text = bl.sh("docker", "compose", "exec", "-T", app, "curl", "-s", "http://localhost:8080/actuator/prometheus").stdout
    samples = bl.parse_prometheus_text(text)
    g = lambda name, cond=lambda l: True: sum(v for n, l, v in samples if n == name and cond(l))  # noqa: E731
    state, openings = bl.breaker_state(samples)
    return {"nr_throttled": int(s["nr_throttled"]), "throttled_usec": int(s["throttled_usec"]), "usage_usec": int(s["usage_usec"]),
            "jit_ms": g("jvm_compilation_time_ms_total"), "threads": g("jvm_threads_live_threads"),
            "tomcat_threads": g("tomcat_threads_current_threads"), "fail_open": g("ratelimiter_requests_total", lambda l: l.get("result") == "fail_open"),
            "redis_max_s": max([v for n, l, v in samples if n == "ratelimiter_redis_duration_seconds_max"] or [0]),
            "gc_pause_s": g("jvm_gc_pause_seconds_sum"), "openings": openings, "breaker": state}

def snapshot():
    return {a: app_metrics(a) for a in bl.APPS}

def delta(b, a):
    keys = ("nr_throttled", "throttled_usec", "usage_usec", "jit_ms", "fail_open", "gc_pause_s", "openings")
    return {app: {**{k: round(a[app][k] - b[app][k], 3) for k in keys}, "threads_after": a[app]["threads"],
                  "tomcat_threads_after": a[app]["tomcat_threads"], "redis_max_ms_after": round(a[app]["redis_max_s"] * 1000, 1),
                  "breaker_after": a[app]["breaker"]} for app in bl.APPS}

def k6(args, timeout=600):
    return bl.sh("docker", "compose", "--profile", "loadtest", "run", "--rm", "--no-deps", *args, timeout=timeout, check=False)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("label")
    ap.add_argument("--warmup-seconds", type=int, default=0)
    ap.add_argument("--warmup-rate", type=int, default=1000)
    ap.add_argument("--rounds", type=int, default=4)
    a = ap.parse_args()
    out = {"label": a.label, "started": datetime.datetime.now().isoformat(timespec="seconds"), "warmup_seconds": a.warmup_seconds,
           "warmup_rate": a.warmup_rate, "rounds": []}
    print("recreating app1-3 (cold JVMs) ...", flush=True)
    bl.sh("docker", "compose", "up", "-d", "--no-deps", "--force-recreate", "--wait", "app1", "app2", "app3", timeout=600)
    bl.sh("docker", "compose", "up", "-d", "--no-deps", "--force-recreate", "--wait", "nginx", timeout=180)
    out["after_start"] = snapshot()
    if a.warmup_seconds:
        bl.sh("scripts/bench/setup-policies.sh", "delete", env={"POLICY_VARIANT": "concurrency"})
        bl.sh("scripts/bench/setup-policies.sh", "apply", "token_bucket")
        b = snapshot(); t = time.time()
        p = k6(["-v", f"{ROOT}/benchmarks/investigations/cold-start:/out-inv", "-e", "RUN_ID=inv-warmup", "-e", f"RATE={a.warmup_rate}",
                "-e", "ALGORITHM=token_bucket", "-e", "WARMUP_S=0", "-e", f"MEASURE_S={a.warmup_seconds}", "-e", "OUT_DIR=/tmp",
                "-e", "GOMEMLIMIT=850MiB", "k6", "run", "--quiet", "--no-thresholds", "--no-summary", "/scripts/b1-load-levels.js"])
        out["warmup"] = {"seconds": round(time.time() - t), "rc": p.returncode, "delta": delta(b, snapshot())}
        bl.sh("scripts/bench/setup-policies.sh", "delete")
    bl.sh("scripts/bench/setup-policies.sh", "delete")
    bl.sh("scripts/bench/setup-policies.sh", "apply", "token_bucket", env={"POLICY_VARIANT": "concurrency"})
    for r in range(1, a.rounds + 1):
        tok = bl.sh("python3", "scripts/bench/mint-tokens.py", "--print-user", f"inv-{a.label}-{int(time.time())}-r{r}", "FREE").stdout.strip()
        b = snapshot(); t = time.time()
        p = k6(["-e", f"TOKEN={tok}", "-e", f"RUN_ID=inv-{a.label}-r{r}", "-v", f"{ROOT}/benchmarks/investigations/cold-start:/out",
                "k6", "run", "--quiet", "/scripts/smoke-concurrency.js"])
        m = re.search(r"accepted=(\d+) rejected=(\d+) unexpected=(\d+)", p.stdout)
        time.sleep(1)
        rec = {"round": r, "seconds": round(time.time() - t, 1), "k6": m.group(0) if m else (p.stdout + p.stderr)[-300:], "delta": delta(b, snapshot())}
        out["rounds"].append(rec)
        tot = {k: sum(rec["delta"][x][k] for x in bl.APPS) for k in ("throttled_usec", "jit_ms", "fail_open", "openings")}
        print(f"round {r}: {rec['k6']} | throttled {tot['throttled_usec']/1000:.0f} ms | JIT {tot['jit_ms']:.0f} ms | fail_open {tot['fail_open']:.0f} "
              f"| openings {tot['openings']:.0f} | redis max ms {[rec['delta'][x]['redis_max_ms_after'] for x in bl.APPS]}", flush=True)
        for _ in range(20):                       # let a breaker that opened close again before the next round
            if all(app_metrics(x)["breaker"] == "closed" for x in bl.APPS):
                break
            time.sleep(1)
    bl.sh("scripts/bench/setup-policies.sh", "delete", env={"POLICY_VARIANT": "concurrency"}, check=False)
    path = ROOT / "benchmarks" / "investigations" / "cold-start" / f"{a.label}-{datetime.datetime.now().strftime('%Y%m%dT%H%M%S')}.json"
    path.write_text(json.dumps(out, indent=2))
    print("saved", path.relative_to(ROOT))

if __name__ == "__main__":
    main()
