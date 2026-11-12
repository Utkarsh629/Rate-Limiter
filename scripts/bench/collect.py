#!/usr/bin/env python3
"""Phase 3B benchmark collection: per-instance raw counters, Prometheus (whole-run counts and measure-window
latency/resource queries), Redis INFO/latency/SLOWLOG/key memory, /stats, the Nginx access-log summary, the
docker-stats summary and the environment record.

Used by runner.py during a run. Standalone, it re-runs the Prometheus window queries of a saved run (while
Prometheus still retains the data):   python3 scripts/bench/collect.py benchmarks/results/<run-id>
"""
import csv
import gzip
import json
import platform
import statistics
import subprocess
import sys

import benchlib as bl


# ------------------------------------------------------------------------------------------------ instances

def instance_snapshot(out_dir=None, label=None):
    """Raw /actuator/prometheus of every instance, read from inside its container (not through the limiter)."""
    snap = {}
    for app in bl.APPS:
        text = bl.sh("docker", "compose", "exec", "-T", app, "curl", "-s", "http://localhost:8080/actuator/prometheus").stdout
        samples = bl.parse_prometheus_text(text)
        state, openings = bl.breaker_state(samples)
        snap[app] = {"requests": bl.requests_by_algorithm(samples), "breaker": state, "openings": openings}
        if out_dir is not None:
            p = out_dir / "raw-counters" / f"{label}-{app}.prom.gz"
            p.parent.mkdir(parents=True, exist_ok=True)
            with gzip.open(p, "wt") as f:
                f.write(text)
    return snap


def counters_delta(before, after):
    """{app: {algorithm: {result: after - before}}} plus totals per algorithm/result."""
    per_app, total = {}, {a: {r: 0 for r in bl.RESULTS} for a in bl.ALGORITHMS}
    for app in bl.APPS:
        per_app[app] = {}
        for a in bl.ALGORITHMS:
            per_app[app][a] = {}
            for r in bl.RESULTS:
                d = int(round(after[app]["requests"][a][r] - before[app]["requests"][a][r]))
                per_app[app][a][r] = d
                total[a][r] += d
    return {"per_app": per_app, "total": total}


def breaker_summary(before, after):
    return {app: {"state_before": before[app]["breaker"], "state_after": after[app]["breaker"],
                  "openings": int(after[app]["openings"] - before[app]["openings"])} for app in bl.APPS}


# ------------------------------------------------------------------------------------------------ Prometheus

def prom_counts_at(t):
    """ratelimiter_requests_total per instance/algorithm/result and the sample timestamps, at time t."""
    data = bl.prom_query("sum by (instance, algorithm, result) (ratelimiter_requests_total)", at=t)
    ts = bl.prom_query("max by (instance) (timestamp(ratelimiter_requests_total))", at=t)
    counts = {}
    for row in data["result"]:
        m = row["metric"]
        counts.setdefault(m.get("instance"), {}).setdefault(m.get("algorithm"), {})[m.get("result")] = float(row["value"][1])
    stamps = {row["metric"].get("instance"): float(row["value"][1]) for row in ts["result"]}
    return {"counts": counts, "sample_timestamps": stamps, "at": t}


def prom_counts_delta(before, after):
    per_instance, total = {}, {a: {r: 0 for r in bl.RESULTS} for a in bl.ALGORITHMS}
    for inst in sorted(set(before["counts"]) | set(after["counts"])):
        per_instance[inst] = {}
        for a in bl.ALGORITHMS:
            per_instance[inst][a] = {}
            for r in bl.RESULTS:
                d = after["counts"].get(inst, {}).get(a, {}).get(r, 0.0) - before["counts"].get(inst, {}).get(a, {}).get(r, 0.0)
                per_instance[inst][a][r] = int(round(d))
                total[a][r] += int(round(d))
    return {"per_instance": per_instance, "total": total}


def _scalar(data):
    res = data.get("result", [])
    if not res:
        return None
    v = res[0]["value"][1]
    return None if v in ("NaN", "+Inf", "-Inf") else float(v)


def window_queries(algorithm, window_s, filter_enabled=True):
    """Named PromQL instant queries evaluated at the END of the measure window, over [end - window_s, end]."""
    w = f"{int(window_s)}s"
    a = f'algorithm="{algorithm}"'
    q = {}
    if filter_enabled:
        for name, metric in (("overhead", "ratelimiter_request_duration_seconds"), ("redis_rtt", "ratelimiter_redis_duration_seconds")):
            for p in (0.5, 0.95, 0.99, 0.999):
                q[f"{name}_p{str(p)[2:]}"] = f"histogram_quantile({p}, sum by (le) (increase({metric}_bucket{{{a}}}[{w}])))"
            q[f"{name}_avg"] = f"sum(increase({metric}_sum{{{a}}}[{w}])) / sum(increase({metric}_count{{{a}}}[{w}]))"
            q[f"{name}_count"] = f"sum(increase({metric}_count{{{a}}}[{w}]))"
            q[f"{name}_max"] = f"max(max_over_time({metric}_max{{{a}}}[{w}]))"
        for result in ("allowed", "rejected"):
            q[f"overhead_{result}_p99"] = (f"histogram_quantile(0.99, sum by (le) (increase(ratelimiter_request_duration_seconds_bucket"
                                           f"{{{a},result=\"{result}\"}}[{w}])))")
            q[f"overhead_{result}_p50"] = (f"histogram_quantile(0.5, sum by (le) (increase(ratelimiter_request_duration_seconds_bucket"
                                           f"{{{a},result=\"{result}\"}}[{w}])))")
    q["http_server_p99"] = f'histogram_quantile(0.99, sum by (le) (increase(http_server_requests_seconds_bucket{{uri!~"/actuator.*"}}[{w}])))'
    q["gc_pause_seconds"] = f"sum(increase(jvm_gc_pause_seconds_sum[{w}]))"
    q["gc_pauses"] = f"sum(increase(jvm_gc_pause_seconds_count[{w}]))"
    q["gc_pause_max"] = f"max(max_over_time(jvm_gc_pause_seconds_max[{w}]))"
    q["redis_cmd_rate"] = f"rate(redis_commands_processed_total[{w}])"
    q["redis_cpu_cores"] = f"rate(redis_cpu_sys_seconds_total[{w}]) + rate(redis_cpu_user_seconds_total[{w}])"
    q["redis_main_thread_cpu_cores"] = (f"rate(redis_cpu_sys_main_thread_seconds_total[{w}]) + "
                                        f"rate(redis_cpu_user_main_thread_seconds_total[{w}])")
    return q


RANGE_QUERIES = {   # per-instance series over the measure window (5 s steps): avg and max are reported
    "process_cpu": "process_cpu_usage{job=\"app\"}",
    "heap_used_bytes": "sum by (instance) (jvm_memory_used_bytes{job=\"app\",area=\"heap\"})",
    "threads_live": "jvm_threads_live_threads{job=\"app\"}",
    "tomcat_busy": "tomcat_threads_busy_threads{job=\"app\"}",
    "tomcat_current": "tomcat_threads_current_threads{job=\"app\"}",
    "redis_cpu_cores": "rate(redis_cpu_sys_seconds_total[15s]) + rate(redis_cpu_user_seconds_total[15s])",
    "redis_memory_bytes": "redis_memory_used_bytes",
    "redis_clients": "redis_connected_clients",
    "redis_ops": "rate(redis_commands_processed_total[15s])",
    "breaker_state_open": "resilience4j_circuitbreaker_state{state=\"open\"}",
    "breaker_state_half_open": "resilience4j_circuitbreaker_state{state=\"half_open\"}",
}


def summarize_range(data):
    out = {}
    for series in data.get("result", []):
        key = series["metric"].get("instance", "all")
        vals = [float(v) for _, v in series.get("values", []) if v not in ("NaN", "+Inf", "-Inf")]
        if vals:
            out[key] = {"avg": statistics.fmean(vals), "max": max(vals), "min": min(vals), "samples": len(vals)}
    return out


def prometheus_window(run_dir, t_start, t_end, algorithm, filter_enabled=True, step=5):
    """Runs and saves every measure-window query; returns {"instant": {...}, "range": {...}, "scrape": {...}}."""
    window_s = max(1, round(t_end - t_start))
    out = {"window": {"start": t_start, "end": t_end, "seconds": window_s}, "instant": {}, "range": {}, "errors": []}
    raw = {}
    for name, expr in window_queries(algorithm, window_s, filter_enabled).items():
        try:
            data = bl.prom_query(expr, at=t_end)
            raw[name] = {"expr": expr, "data": data}
            if name in ("redis_cmd_rate", "redis_cpu_cores", "redis_main_thread_cpu_cores"):
                out["instant"][name] = _scalar(data)
            else:
                out["instant"][name] = _scalar(data)
        except bl.BenchError as e:
            out["errors"].append(str(e))
    for name, expr in RANGE_QUERIES.items():
        try:
            data = bl.prom_range(expr, t_start, t_end, step)
            raw[f"range_{name}"] = {"expr": expr, "data": data}
            out["range"][name] = summarize_range(data)
        except bl.BenchError as e:
            out["errors"].append(str(e))
    # Scrape completeness: samples of "up" per app instance inside the window vs the expected count.
    up = bl.prom_query(f'count_over_time(up{{job="app"}}[{window_s}s])', at=t_end)
    raw["scrape_up_samples"] = {"data": up}
    expected = window_s / 5.0
    out["scrape"] = {"expected_per_instance": expected,
                     "samples": {r["metric"].get("instance"): float(r["value"][1]) for r in up.get("result", [])}}
    bl.write_json(run_dir / "prometheus" / "queries.json", raw)
    return out


def ramp_steps_prometheus(run_dir, steps, algorithm):
    """B2: overhead quantiles per ramp step window [(rate, start, end)]."""
    out = {}
    for rate, start, end in steps:
        w = max(5, int(end - start))
        a = f'algorithm="{algorithm}"'
        res = {}
        for p in (0.5, 0.95, 0.99):
            res[f"overhead_p{str(p)[2:]}"] = _scalar(bl.prom_query(
                f"histogram_quantile({p}, sum by (le) (increase(ratelimiter_request_duration_seconds_bucket{{{a}}}[{w}s])))", at=end))
        res["redis_cpu_cores"] = _scalar(bl.prom_query(
            f"rate(redis_cpu_sys_seconds_total[{w}s]) + rate(redis_cpu_user_seconds_total[{w}s])", at=end))
        res["app_cpu_max"] = _scalar(bl.prom_query(f'max(avg_over_time(process_cpu_usage{{job="app"}}[{w}s]))', at=end))
        out[str(rate)] = {"start": start, "end": end, **res}
    bl.write_json(run_dir / "prometheus" / "ramp-steps.json", out)
    return out


# ------------------------------------------------------------------------------------------------ Redis

def redis_snapshot(run_dir, label):
    info_text = bl.redis_cli("INFO", "everything")
    slowlog = bl.redis_cli("SLOWLOG", "GET", "128")
    dbsize = bl.redis_cli("DBSIZE").strip()
    (run_dir / "redis").mkdir(parents=True, exist_ok=True)
    (run_dir / "redis" / f"info-{label}.txt").write_text(info_text)
    (run_dir / "redis" / f"slowlog-{label}.txt").write_text(slowlog)
    info = bl.parse_redis_info(info_text)
    return {"dbsize": int(dbsize) if dbsize.isdigit() else None,
            "evalsha_calls": bl.command_calls(info, "evalsha"),
            "eval_calls": bl.command_calls(info, "eval"),
            "latencystats_evalsha": info.get("latencystats", {}).get("latency_percentiles_usec_evalsha"),
            "used_memory": info.get("memory", {}).get("used_memory"),
            "used_cpu_sys": info.get("cpu", {}).get("used_cpu_sys"), "used_cpu_user": info.get("cpu", {}).get("used_cpu_user"),
            "used_cpu_sys_main_thread": info.get("cpu", {}).get("used_cpu_sys_main_thread"),
            "used_cpu_user_main_thread": info.get("cpu", {}).get("used_cpu_user_main_thread"),
            "connected_clients": info.get("clients", {}).get("connected_clients"),
            "slowlog_len": bl.redis_cli("SLOWLOG", "LEN").strip(),
            "total_commands_processed": info.get("stats", {}).get("total_commands_processed")}


def key_memory_samples(policy_id, algorithm):
    """MEMORY USAGE of a few rate-limit keys of this run's policy and algorithm (per-key memory)."""
    suffix = {"token_bucket": "tb", "fixed_window": "fw", "sliding_window": "sw"}[algorithm]
    keys = [k for k in bl.redis_cli("--scan", "--pattern", f"rl:{{{policy_id}:*}}:{suffix}", "--count", "1000").splitlines() if k][:5]
    out = {}
    for k in keys:
        v = bl.redis_cli("MEMORY", "USAGE", k).strip()
        out[k] = int(v) if v.isdigit() else v
    return out


# ------------------------------------------------------------------------------------------------ /stats

def stats_api(policy_id, admin_token):
    status, _, body = bl.http("GET", f"{bl.NGINX}/api/v1/policies/{policy_id}/stats", token=admin_token)
    return {"status": status, "body": json.loads(body) if body.startswith("{") else body}


# ------------------------------------------------------------------------------------------------ Nginx

def nginx_collect(run_dir, run_id, per_user=False, timeline=False, keep_raw=False):
    """Streams the (fresh, per-run) Nginx container log and summarizes the run's requests by UA marker."""
    p = subprocess.Popen(["docker", "compose", "logs", "nginx", "--no-log-prefix"], cwd=bl.ROOT,
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    (run_dir / "nginx").mkdir(parents=True, exist_ok=True)
    raw_f = gzip.open(run_dir / "nginx" / "access-run.log.gz", "wt") if keep_raw else None

    def lines():
        for line in p.stdout:
            if raw_f is not None and f"run={run_id}" in line:
                raw_f.write(line)
            yield line
    try:
        summary = bl.nginx_summary(lines(), run_id, per_user=per_user, timeline=timeline)
    finally:
        p.wait()
        if raw_f is not None:
            raw_f.close()
    bl.write_json(run_dir / "nginx-summary.json", {k: v for k, v in summary.items() if k != "users"})
    if per_user:
        bl.write_json(run_dir / "nginx" / "per-user.json", summary.get("users", {}))
    return summary


# ------------------------------------------------------------------------------------------------ docker stats

def docker_stats_summary(jsonl_path, csv_path, t_start, t_end, k6_cpu_limit=bl.K6_CPU_LIMIT):
    rows = []
    try:
        with gzip.open(jsonl_path, "rt") if str(jsonl_path).endswith(".gz") else open(jsonl_path) as f:
            for line in f:
                r = bl.parse_docker_stats_line(line)
                if r:
                    rows.append(r)
    except OSError:
        return {"error": f"docker stats file missing: {jsonl_path}", "samples": 0}
    with open(csv_path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["ts", "name", "cpu_pct", "mem_bytes", "mem_limit_bytes"])
        for r in rows:
            w.writerow([f"{r['ts']:.3f}", r["name"], r["cpu_pct"], r["mem_bytes"], r["mem_limit_bytes"]])
    return summarize_docker_rows(rows, t_start, t_end, k6_cpu_limit)


def summarize_docker_rows(rows, t_start, t_end, k6_cpu_limit=bl.K6_CPU_LIMIT):
    """Pure: per-container CPU/memory over [t_start, t_end] and the k6 worst 5-second CPU (% of its limit)."""
    win = [r for r in rows if t_start <= r["ts"] <= t_end]
    out = {"samples": len(win), "containers": {}}
    by = {}
    for r in win:
        by.setdefault(r["name"], []).append(r)
    for name, rs in sorted(by.items()):
        cpus = [r["cpu_pct"] for r in rs]
        mems = [r["mem_bytes"] for r in rs if r["mem_bytes"] is not None]
        out["containers"][name] = {"cpu_pct_avg": statistics.fmean(cpus), "cpu_pct_max": max(cpus),
                                   "mem_bytes_max": max(mems) if mems else None, "samples": len(rs)}
    k6 = sorted(((r["ts"], r["cpu_pct"] / k6_cpu_limit) for r in win if "-k6-" in r["name"] or r["name"].startswith("k6")),
                key=lambda x: x[0])
    out["k6"] = {"samples": len(k6), "cpu_pct_of_limit_max_5s": bl.rolling_max_mean(k6, 5.0),
                 "cpu_limit_cores": k6_cpu_limit}
    return out


# ------------------------------------------------------------------------------------------------ environment

def environment(extra):
    def cmd(*a):
        try:
            p = subprocess.run(list(a), capture_output=True, text=True, timeout=60, cwd=bl.ROOT)
            return (p.stdout + p.stderr).strip()
        except (OSError, subprocess.TimeoutExpired) as e:
            return f"unavailable: {e}"
    env = {
        "git": {"commit": cmd("git", "rev-parse", "HEAD"), "branch": cmd("git", "branch", "--show-current"),
                "status_porcelain": cmd("git", "status", "--porcelain")},
        "host": {"platform": platform.platform(), "sw_vers": cmd("sw_vers"),
                 "cpu": cmd("sysctl", "-n", "machdep.cpu.brand_string"), "ncpu": cmd("sysctl", "-n", "hw.ncpu"),
                 "memsize": cmd("sysctl", "-n", "hw.memsize"), "pmset": cmd("pmset", "-g"),
                 "pmset_therm": cmd("pmset", "-g", "therm"), "uptime": cmd("uptime"), "python": sys.version},
        "docker": {"version": cmd("docker", "version", "--format", "{{.Server.Version}}"),
                   "info": cmd("docker", "info", "--format", "{{.NCPU}} CPUs, {{.MemTotal}} bytes, {{.OperatingSystem}}"),
                   "images": cmd("docker", "compose", "--profile", "loadtest", "images", "--format", "json")},
        "compose_limits": {}, "apps": {}, "redis_config": {},
    }
    try:
        cfg = json.loads(cmd("docker", "compose", "--profile", "loadtest", "config", "--format", "json"))
        for name, svc in cfg.get("services", {}).items():
            env["compose_limits"][name] = ((svc.get("deploy") or {}).get("resources") or {}).get("limits")
    except ValueError:
        env["compose_limits"] = "unavailable"
    for app in bl.APPS:
        env["apps"][app] = cmd("docker", "compose", "exec", "-T", app, "sh", "-c",
                               "tr '\\0' ' ' < /proc/1/cmdline; echo; env | grep -E "
                               "'^(JAVA_TOOL_OPTIONS|JAVA_VERSION|REDIS_|SERVER_TOMCAT|RATELIMITER_|SPRING_PROFILES_ACTIVE|INSTANCE_ID)' | sort")
        env["apps"][app + "_image_id"] = cmd("docker", "inspect", "--format", "{{.Image}}",
                                             bl.sh("docker", "compose", "ps", "-q", app).stdout.strip())
    for key in ("maxmemory", "maxmemory-policy", "save", "appendonly", "io-threads", "slowlog-log-slower-than"):
        env["redis_config"][key] = bl.redis_cli("CONFIG", "GET", key).splitlines()[1:2]
    env.update(extra)
    return env


def environment_text(env):
    lines = []

    def walk(prefix, v):
        if isinstance(v, dict):
            for k in v:
                walk(f"{prefix}.{k}" if prefix else k, v[k])
        else:
            text = str(v)
            lines.append(f"{prefix}:" + ("\n  " + text.replace("\n", "\n  ") if "\n" in text else f" {text}"))
    walk("", env)
    return "\n".join(lines) + "\n"


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: collect.py <run-dir>   (re-runs the measure-window Prometheus queries of a saved run)")
    import pathlib
    run_dir = pathlib.Path(sys.argv[1])
    meta = bl.read_json(run_dir / "run-meta.json")
    if not meta:
        sys.exit(f"no run-meta.json in {run_dir}")
    w = meta["measure_window"]
    out = prometheus_window(run_dir, w["start"], w["end"], meta["algorithm"], meta.get("filter_enabled", True))
    bl.write_json(run_dir / "prometheus" / "window-summary.json", out)
    print(json.dumps(out["instant"], indent=2))


if __name__ == "__main__":
    main()
