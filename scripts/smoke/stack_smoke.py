#!/usr/bin/env python3
"""Phase 3A automated smoke test for the full Docker Compose stack.

Brings the stack up (optionally from a clean state) and verifies, in order:
  AC9   all services healthy; app health checks use readiness
  AC10  30 requests through Nginx reach all 3 instances
  AC12  /actuator/** blocked at Nginx; Prometheus targets (3 apps + exporter) up
  AC11  client IP preserved through Nginx; spoofed X-Forwarded-For ignored
  AC13  no secret value in the Compose file, Dockerfile, configs, scripts or the image
  AC15  Grafana dashboard provisioned; datasource healthy
  AC14  1,000 concurrent requests, one identity, deterministic policy (capacity 100) -> exactly 100 accepted
  AC18  60 s functional load at 1,000 req/s: k6 = Nginx log = raw per-instance counters (summed)
        = Prometheus after the final scrape = /stats after the final flush
  AC16  every required dashboard panel returns data; AC17 percentile queries aggregate buckets

It is a functional check, not a benchmark: no latency or throughput number from it is a result.
Exit code 0 = all checks passed. Artifacts: benchmarks/smoke/<run-id>/.
"""
import argparse
import base64
import datetime
import json
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
NGINX = "http://localhost:8080"
PROM = "http://localhost:9090"
GRAFANA = "http://localhost:3000"
NGINX_IP = "172.28.0.10"
APPS = ["app1", "app2", "app3"]
UPSTREAM_APP = {"172.28.0.11": "app1", "172.28.0.12": "app2", "172.28.0.13": "app3"}   # static Compose addresses
PROBE_UA = "ratelimiter-probe/"
SERVICES = ["nginx", "app1", "app2", "app3", "redis", "mysql", "prometheus", "grafana", "redis-exporter"]

RUN_ID = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
BURST_ROUNDS = 4
OUT = ROOT / "benchmarks" / "smoke" / RUN_ID
report = {"runId": RUN_ID, "checks": [], "evidence": {}}


class SmokeFailure(Exception):
    pass


def log(msg):
    print(f"[smoke] {msg}", flush=True)


def check(ac, name, ok, detail, fatal=False):
    """Records a check. Failures are collected so every check runs; fatal=True stops (later checks depend on it)."""
    report["checks"].append({"ac": ac, "check": name, "passed": bool(ok), "detail": detail})
    log(f"{'PASS' if ok else 'FAIL'}  {ac:<5} {name}: {detail}")
    if not ok and fatal:
        raise SmokeFailure(f"{ac} {name}: {detail}")


def sh(*args, timeout=900, check_rc=True, env=None):
    p = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=timeout, env=env)
    if check_rc and p.returncode != 0:
        raise SmokeFailure(f"command failed ({p.returncode}): {' '.join(args)}\n{p.stdout[-2000:]}\n{p.stderr[-2000:]}")
    return p


# Direct connections only: urllib would otherwise use the operating system's proxy settings (on macOS
# it reads the system configuration even when no *_proxy variable is set), and the proxy, not this
# script, would then be the client Nginx sees.
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def http(method, url, token=None, body=None, headers=None, basic=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if basic:
        req.add_header("Authorization", "Basic " + base64.b64encode(basic.encode()).decode())
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with OPENER.open(req, timeout=15) as r:
            return r.status, dict(r.headers), r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode()


def nginx_upstreams(markers, since):
    """Which instance served each request sent with User-Agent PROBE_UA + marker, read from Nginx's access log
    (upstream=<addr>). Nginx no longer returns an X-Upstream header (security review SEC-6). With a retry the
    address list is "a, b"; the last entry answered."""
    want, found = set(markers), {}
    deadline = time.time() + 5
    pattern = re.compile(r'upstream=(\S+(?:, \S+)*) upstream_time=.* "' + re.escape(PROBE_UA) + r'([^"]+)"$')
    while True:
        out = sh("docker", "compose", "logs", "--no-log-prefix", "--since", str(int(since) - 2), "nginx").stdout
        for line in out.splitlines():
            m = pattern.search(line.strip())
            if m and m.group(2) in want:
                addr = m.group(1).split(",")[-1].strip().split(":")[0]
                found[m.group(2)] = UPSTREAM_APP.get(addr, addr)
        if want <= found.keys() or time.time() > deadline:
            return found
        time.sleep(0.2)


def read_env():
    env = {}
    for line in (ROOT / ".env").read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()
    return env


def prom_query(expr, at=None):
    params = {"query": expr}
    if at is not None:
        params["time"] = f"{at:.3f}"
    status, _, body = http("GET", f"{PROM}/api/v1/query?" + urllib.parse.urlencode(params))
    data = json.loads(body)
    if status != 200 or data.get("status") != "success":
        raise SmokeFailure(f"Prometheus query failed: {expr}: {body[:300]}")
    return data["data"]["result"]


SAMPLE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)\{([^}]*)\}\s+(\S+)', re.M)


def raw_counters(app):
    """ratelimiter_requests_total{algorithm="token_bucket"} by result, read from the instance itself."""
    text = sh("docker", "compose", "exec", "-T", app, "curl", "-s", "http://localhost:8080/actuator/prometheus").stdout
    out = {}
    for name, labels, value in SAMPLE.findall(text):
        if name == "ratelimiter_requests_total" and 'algorithm="token_bucket"' in labels:
            result = re.search(r'result="([^"]+)"', labels).group(1)
            out[result] = out.get(result, 0) + float(value)
    return out


def instance_state(app):
    """From the instance itself: fail-open decisions (all algorithms), CLOSED->OPEN transitions, breaker state."""
    text = sh("docker", "compose", "exec", "-T", app, "curl", "-s", "http://localhost:8080/actuator/prometheus").stdout
    fail_open = opened = 0.0
    state = None
    for name, labels, value in SAMPLE.findall(text):
        if name == "ratelimiter_requests_total" and 'result="fail_open"' in labels:
            fail_open += float(value)
        elif name == "ratelimiter_circuit_breaker_transitions_total" and 'to="open"' in labels:
            opened += float(value)
        elif name == "resilience4j_circuitbreaker_state" and float(value) == 1.0:
            state = re.search(r'state="([a-z_]+)"', labels).group(1)
    return {"failOpen": int(fail_open), "openings": int(opened), "breaker": state}


def timeouts(expected):
    values = {a: sh("docker", "compose", "exec", "-T", a, "sh", "-c", "echo $REDIS_COMMAND_TIMEOUT").stdout.strip() for a in APPS}
    report["evidence"]["redisCommandTimeout"] = values
    check("D14/3A", f"Redis command timeout is {expected} on all 3 instances (Compose override)",
          all(v == expected for v in values.values()), values)


def prom_counters():
    rows = prom_query('sum by (instance, result) (ratelimiter_requests_total{algorithm="token_bucket"})')
    return {(r["metric"]["instance"], r["metric"]["result"]): float(r["value"][1]) for r in rows}


def wait_for_scrape():
    time.sleep(11)  # > 2 scrape intervals (5 s): the final values are in Prometheus


def k6(script, run_env, timeout=300):
    env = {"RUN_ID": RUN_ID, **run_env}
    (OUT.parent / env["RUN_ID"]).mkdir(parents=True, exist_ok=True)
    args = ["docker", "compose", "--profile", "loadtest", "run", "--rm"]
    for k, v in env.items():
        args += ["-e", f"{k}={v}"]
    args += ["k6", "run", "--quiet", f"/scripts/{script}"]
    p = sh(*args, timeout=timeout, check_rc=False)
    (OUT.parent / env["RUN_ID"] / f"{script}.log").write_text(p.stdout + p.stderr)
    return p


# ------------------------------------------------------------------------------------------------

def stack_up(clean):
    if clean:
        log("clean state: docker compose down -v (this project's containers and volumes only)")
        sh("docker", "compose", "--profile", "loadtest", "down", "-v", "--remove-orphans", timeout=300)
    log("docker compose up -d --build --wait")
    sh("docker", "compose", "up", "-d", "--build", "--wait", timeout=1200)
    ps = [json.loads(l) for l in sh("docker", "compose", "ps", "--format", "json").stdout.splitlines() if l.strip()]
    health = {p["Service"]: p.get("Health") or p.get("State") for p in ps}
    report["evidence"]["services"] = health
    check("AC9", "all services healthy", all(health.get(s) == "healthy" for s in SERVICES), health, fatal=True)
    hc = sh("docker", "inspect", "--format", "{{json .Config.Healthcheck.Test}}", "ratelimiter-app1-1").stdout.strip()
    check("AC9", "app health check uses readiness", "/actuator/health/readiness" in hc, hc)


def distribution(tokens):
    since, markers = time.time(), [f"{RUN_ID}-dist-{i}" for i in range(30)]
    for i, marker in enumerate(markers):
        http("GET", f"{NGINX}/api/v1/demo/profile", token=tokens["users"][i]["token"],
             headers={"User-Agent": PROBE_UA + marker})
    served = nginx_upstreams(markers, since)
    upstreams = [served.get(m) for m in markers]
    counts = {u: upstreams.count(u) for u in sorted(set(upstreams), key=str)}
    report["evidence"]["distribution"] = counts
    check("AC10", "30 requests reach all 3 instances (Nginx access log)",
          sorted(u for u in counts if u in APPS) == APPS and None not in counts, counts)


def actuator_and_targets():
    statuses = {p: http("GET", NGINX + p)[0] for p in ("/actuator/health", "/actuator/health/readiness", "/actuator/prometheus")}
    check("AC12", "/actuator/** blocked at Nginx", all(s == 404 for s in statuses.values()), statuses)
    deadline = time.time() + 60
    while True:
        targets = json.loads(http("GET", f"{PROM}/api/v1/targets")[2])["data"]["activeTargets"]
        up = {t["labels"]["instance"]: t["health"] for t in targets}
        if all(up.get(i) == "up" for i in ("app-1", "app-2", "app-3", "redis")) or time.time() > deadline:
            break
        time.sleep(2)
    report["evidence"]["prometheusTargets"] = up
    check("AC12", "Prometheus targets up (3 apps + redis exporter)",
          all(up.get(i) == "up" for i in ("app-1", "app-2", "app-3", "redis")), up)


def client_ip():
    """An anonymous request from a container with a known address (the redis container) on the Compose network:
    the per-IP quota key must carry that container's address, not Nginx's, and a client-supplied
    X-Forwarded-For must not change it."""
    redis_ip = sh("docker", "inspect", "--format", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
                  "ratelimiter-redis-1").stdout.strip()
    scan = lambda: sorted(sh("docker", "compose", "exec", "-T", "redis", "redis-cli", "--scan", "--pattern",  # noqa: E731
                             "rl:{1:ip:*").stdout.split())
    sh("docker", "compose", "exec", "-T", "redis", "redis-cli", "FLUSHDB")
    sh("docker", "compose", "exec", "-T", "redis", "wget", "-q", "-O", "/dev/null", "http://nginx:8080/api/v1/demo/public")
    keys = scan()
    sh("docker", "compose", "exec", "-T", "redis", "wget", "-q", "-O", "/dev/null", "--header", "X-Forwarded-For: 6.6.6.6",
       "http://nginx:8080/api/v1/demo/public")
    keys_after = scan()
    report["evidence"]["clientIp"] = {"callerContainerIp": redis_ip, "nginxIp": NGINX_IP, "keys": keys_after}
    check("AC11", "client IP preserved through Nginx", keys == [f"rl:{{1:ip:{redis_ip}}}:sw"],
          {"caller": redis_ip, "keys": keys})
    check("AC11", "spoofed X-Forwarded-For ignored", keys_after == keys, keys_after)


def secrets(env):
    values = {k: env[k] for k in ("MYSQL_PASSWORD", "MYSQL_ROOT_PASSWORD", "JWT_SECRET", "GRAFANA_ADMIN_PASSWORD") if env.get(k)}
    files = [ROOT / "Dockerfile", ROOT / "docker-compose.yml", ROOT / ".dockerignore"]
    for d in ("infra", "scripts", "load-tests"):
        files += [p for p in (ROOT / d).rglob("*") if p.is_file() and ".tokens" not in p.parts]
    leaks = [f"{k} in {p.relative_to(ROOT)}" for p in files for k, v in values.items()
             if v and v in p.read_text(errors="ignore")]
    listing = sh("docker", "run", "--rm", "--entrypoint", "sh", "ratelimiter-app:local", "-c", "ls -A /app; env").stdout
    leaks += [f"{k} in image" for k, v in values.items() if v in listing]
    leaks += [".env in image"] if re.search(r"(^|\s)\.env(\s|$)", listing) else []
    check("AC13", "no secret value in Compose, Dockerfile, configs, scripts or image", not leaks,
          leaks or f"{len(values)} secrets checked against {len(files)} files and the image")


def grafana(env):
    auth = "admin:" + env["GRAFANA_ADMIN_PASSWORD"]
    status, _, body = http("GET", f"{GRAFANA}/api/dashboards/uid/ratelimiter", basic=auth)
    dashboard = json.loads(body)["dashboard"] if status == 200 else {}
    ds_status = http("GET", f"{GRAFANA}/api/datasources/uid/prometheus/health", basic=auth)
    check("AC15", "dashboard provisioned and datasource healthy",
          status == 200 and ds_status[0] == 200 and len(dashboard.get("panels", [])) >= 8,
          {"dashboard": status, "panels": len(dashboard.get("panels", [])), "datasourceHealth": ds_status[0]})
    return dashboard


def concurrency(admin, tokens_file_env):
    status, _, body = http("POST", f"{NGINX}/api/v1/policies", token=admin, body={
        "name": "smoke-concurrency", "httpMethod": "GET", "endpointPattern": "/api/v1/demo/profile", "tier": "FREE",
        "keyBy": "USER", "algorithm": "TOKEN_BUCKET", "limit": 100, "windowMs": 86_400_000, "refillTokens": 1,
        "description": "3A smoke: deterministic (capacity 100, refill 1/day)"})
    if status == 409:  # left over from an aborted run
        existing = [p for p in json.loads(http("GET", f"{NGINX}/api/v1/policies?size=100", token=admin)[2])["content"]
                    if p["name"] == "smoke-concurrency"][0]
        http("DELETE", f"{NGINX}/api/v1/policies/{existing['id']}", token=admin)
        return concurrency(admin, tokens_file_env)
    policy_id = json.loads(body)["id"]
    try:
        # Wait until every instance applies the new policy (pub/sub), probing with throwaway users.
        seen = {}
        deadline = time.time() + 40
        i = 0
        while len([u for u, lim in seen.items() if lim == "100"]) < 3 and time.time() < deadline:
            marker, since = f"{RUN_ID}-probe-{i}", time.time()
            probe = mint_user(f"smoke-probe-{RUN_ID}-{i}", "FREE")
            _, h, _ = http("GET", f"{NGINX}/api/v1/demo/profile", token=probe, headers={"User-Agent": PROBE_UA + marker})
            seen[nginx_upstreams([marker], since).get(marker)] = h.get("X-RateLimit-Limit")
            i += 1
        check("AC14", "deterministic policy active on all 3 instances", len([v for v in seen.values() if v == "100"]) == 3, seen)
        rounds = []
        for r in range(1, BURST_ROUNDS + 1):
            before = {a: instance_state(a) for a in APPS}
            check("AC14", f"round {r}: all breakers CLOSED before the burst",
                  all(st["breaker"] == "closed" for st in before.values()), {a: st["breaker"] for a, st in before.items()})
            token = mint_user(f"smoke-concurrency-{RUN_ID}-r{r}", "FREE")  # fresh identity: full bucket of 100
            p = k6("smoke-concurrency.js", {"TOKEN": token, "RUN_ID": f"{RUN_ID}/burst-{r}"})
            match = re.search(r"accepted=\d+ rejected=\d+ unexpected=\d+", p.stdout)
            line = match.group(0) if match else (p.stdout + p.stderr)[-300:]
            after = {a: instance_state(a) for a in APPS}
            fail_open = sum(after[a]["failOpen"] - before[a]["failOpen"] for a in APPS)
            openings = sum(after[a]["openings"] - before[a]["openings"] for a in APPS)
            rounds.append({"round": r, "k6": line, "failOpen": fail_open, "breakerOpenings": openings,
                           "breakersAfter": {a: st["breaker"] for a, st in after.items()}})
            check("AC14", f"round {r}: 1,000 concurrent -> exactly 100 accepted, 900 rejected",
                  p.returncode == 0 and line == "accepted=100 rejected=900 unexpected=0", line)
            check("AC14", f"round {r}: zero fail-open responses", fail_open == 0, fail_open)
            check("AC14", f"round {r}: no breaker opened", openings == 0 and all(st["breaker"] == "closed" for st in after.values()),
                  {"openings": openings, "breakers": rounds[-1]["breakersAfter"]})
            time.sleep(1)
        report["evidence"]["concurrency"] = rounds
    finally:
        http("DELETE", f"{NGINX}/api/v1/policies/{policy_id}", token=admin)


def mint_user(user_id, tier):
    return sh(sys.executable, "scripts/bench/mint-tokens.py", "--print-user", user_id, tier).stdout.strip()


def load_and_reconcile(admin):
    policies = json.loads(http("GET", f"{NGINX}/api/v1/policies?size=100", token=admin)[2])["content"]
    free_tier = [p for p in policies if p["name"] == "free-tier"][0]["id"]
    time.sleep(6)  # earlier traffic flushed to /stats (5 s interval)
    stats_before = json.loads(http("GET", f"{NGINX}/api/v1/policies/{free_tier}/stats", token=admin)[2])
    wait_for_scrape()  # the admin calls above are rate-limited requests too: let them settle first
    raw_before = {a: raw_counters(a) for a in APPS}
    prom_before = prom_counters()

    log("k6 functional load: 60 s at 1,000 requests/s through Nginx")
    since = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    time.sleep(1)
    p = k6("smoke-load.js", {}, timeout=240)
    summary = json.loads((OUT / "smoke-load-summary.json").read_text())
    m = summary["metrics"]
    count = lambda k: int(m[k]["values"]["count"]) if k in m else 0  # noqa: E731
    k6_counts = {"accepted": count("accepted"), "rejected": count("rejected"), "unexpected": count("unexpected"),
                 "requests": count("http_reqs"), "dropped_iterations": count("dropped_iterations")}
    report["evidence"]["k6"] = k6_counts
    check("AC18", "k6 run valid (no dropped iterations, no unexpected status)",
          p.returncode == 0 and k6_counts["dropped_iterations"] == 0 and k6_counts["unexpected"] == 0, k6_counts)

    wait_for_scrape()
    raw_after = {a: raw_counters(a) for a in APPS}
    prom_after = prom_counters()
    time.sleep(1)  # stats flush (5 s) has also passed during wait_for_scrape
    stats_after = json.loads(http("GET", f"{NGINX}/api/v1/policies/{free_tier}/stats", token=admin)[2])

    nginx_log = sh("docker", "compose", "logs", "nginx", "--since", since, "--no-log-prefix").stdout
    nginx = {"200": 0, "429": 0, "other": 0}
    for status in re.findall(r'"GET /api/v1/demo/public HTTP/1\.1" (\d{3}) .*k6/', nginx_log):  # UA "Grafana k6/x.y"
        nginx[status if status in nginx else "other"] += 1

    raw_delta = {a: {r: raw_after[a].get(r, 0) - raw_before[a].get(r, 0) for r in ("allowed", "rejected", "fail_open", "fail_closed")}
                 for a in APPS}
    raw_sum = {r: int(sum(raw_delta[a][r] for a in APPS)) for r in ("allowed", "rejected", "fail_open", "fail_closed")}
    prom_delta = {}
    for (inst, res), v in prom_after.items():
        prom_delta[res] = prom_delta.get(res, 0) + v - prom_before.get((inst, res), 0)
    prom_sum = {r: int(prom_delta.get(r, 0)) for r in ("allowed", "rejected", "fail_open", "fail_closed")}
    stats_delta = {k: stats_after[k] - stats_before[k] for k in ("allowed", "rejected", "failOpen", "failClosed")}

    recon = {"k6": {"allowed": k6_counts["accepted"], "rejected": k6_counts["rejected"]},
             "nginx_log": {"allowed": nginx["200"], "rejected": nginx["429"], "other": nginx["other"]},
             "raw_counters_per_instance": {a: {r: int(v) for r, v in d.items()} for a, d in raw_delta.items()},
             "raw_counters_sum": raw_sum, "prometheus_after_final_scrape": prom_sum, "stats_api": stats_delta}
    report["evidence"]["reconciliation"] = recon
    # HTTP 200 = allowed by Redis OR passed by fail-open (Redis unavailable or timed out); 429 = rejected.
    accepted = (raw_sum["allowed"] + raw_sum["fail_open"], raw_sum["rejected"])
    expected = (k6_counts["accepted"], k6_counts["rejected"])
    report["evidence"]["failOpenDuringLoad"] = raw_sum["fail_open"]
    check("AC18", "Nginx access log = k6", (nginx["200"], nginx["429"]) == expected and nginx["other"] == 0, recon["nginx_log"])
    check("AC18", "raw counters of the 3 instances, summed (allowed + fail_open, rejected) = k6 (200, 429)",
          accepted == expected and raw_sum["fail_closed"] == 0, raw_sum)
    check("AC18", "every instance served part of the load", all(d["allowed"] + d["rejected"] > 0 for d in raw_delta.values()),
          recon["raw_counters_per_instance"])
    check("AC18", "Prometheus (after final scrape) = raw counters", prom_sum == raw_sum, prom_sum)
    check("AC18", "/stats (after final flush) = raw counters",
          (stats_delta["allowed"], stats_delta["rejected"], stats_delta["failOpen"], stats_delta["failClosed"])
          == (raw_sum["allowed"], raw_sum["rejected"], raw_sum["fail_open"], raw_sum["fail_closed"]), stats_delta)
    check("AC18", "zero fail-open responses during the load", raw_sum["fail_open"] == 0, raw_sum["fail_open"])


def panels(dashboard):
    results = {}
    for panel in dashboard["panels"]:
        exprs = [t["expr"] for t in panel["targets"]]
        series = sum(len(prom_query(e)) for e in exprs)
        results[panel["title"]] = series
        if "histogram_quantile" in "".join(exprs):
            check("AC17", f"percentiles aggregate buckets across instances: {panel['title'][:40]}",
                  all("sum by (le)" in e for e in exprs if "histogram_quantile" in e), "sum by (le) before histogram_quantile")
    report["evidence"]["panelSeries"] = results
    required = {t: n for t, n in results.items() if re.match(r"^[1-8]\. ", t)}
    check("AC16", "all 8 required panels return data", len(required) == 8 and all(n > 0 for n in required.values()), required)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--clean", action="store_true", help="docker compose down -v first (deletes this project's volumes)")
    ap.add_argument("--burst-rounds", type=int, default=4, help="repetitions of the 1,000-concurrent burst (AC14)")
    ap.add_argument("--expected-timeout", default="250ms", help="Redis command timeout the stack must run with")
    args = ap.parse_args()
    global BURST_ROUNDS
    BURST_ROUNDS = args.burst_rounds
    OUT.mkdir(parents=True, exist_ok=True)
    env = read_env()
    started = time.time()
    try:
        stack_up(args.clean)
        timeouts(args.expected_timeout)
        tokens_path = ROOT / "load-tests/k6/.tokens/smoke-load.json"
        sh(sys.executable, "scripts/bench/mint-tokens.py", "--free", "2000", "--hot", "10", "--prefix", f"smoke-{RUN_ID}",
           "--out", str(tokens_path))
        tokens = json.loads(tokens_path.read_text())
        distribution(tokens)
        actuator_and_targets()
        client_ip()
        secrets(env)
        dashboard = grafana(env)
        concurrency(tokens["admin"], None)
        load_and_reconcile(tokens["admin"])
        panels(dashboard)
        failed = [c for c in report["checks"] if not c["passed"]]
        report["result"] = "PASSED" if not failed else "FAILED"
        if failed:
            report["failure"] = [f"{c['ac']} {c['check']}" for c in failed]
    except SmokeFailure as e:
        report["result"] = "FAILED"
        report["failure"] = str(e)
        log(f"FAILED: {e}")
    finally:
        report["durationSeconds"] = round(time.time() - started, 1)
        (OUT / "report.json").write_text(json.dumps(report, indent=2))
        log(f"{report['result']} in {report['durationSeconds']} s; report: {OUT.relative_to(ROOT)}/report.json")
    sys.exit(0 if report["result"] == "PASSED" else 1)


if __name__ == "__main__":
    main()
