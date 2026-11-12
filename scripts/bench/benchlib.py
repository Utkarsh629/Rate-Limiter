"""Shared helpers for the Phase 3B benchmark tooling (Python stdlib only).

Pure parsing/evaluation functions are kept separate from functions that talk to Docker, Redis, Prometheus or
HTTP, so the parsing, reconciliation, validity and safety logic is unit-testable without a running stack
(scripts/bench/tests).
"""
import json
import os
import pathlib
import re
import subprocess
import time
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
PROJECT = "ratelimiter"                      # docker-compose.yml: name: ratelimiter
APPS = ["app1", "app2", "app3"]
APP_INSTANCE = {"app1": "app-1", "app2": "app-2", "app3": "app-3"}   # Prometheus "instance" label
UPSTREAM_APP = {"172.28.0.11": "app1", "172.28.0.12": "app2", "172.28.0.13": "app3"}
SERVICES = ["nginx", "app1", "app2", "app3", "redis", "mysql", "redis-exporter", "prometheus", "grafana"]
NGINX = "http://localhost:8080"
PROM = "http://localhost:9090"
ALGORITHMS = ["token_bucket", "fixed_window", "sliding_window"]
RESULTS = ["allowed", "rejected", "fail_open", "fail_closed"]
K6_CPU_LIMIT = 2.0                           # docker-compose.yml k6 deploy.resources.limits.cpus

# The isolated Compose Redis that FLUSHDB may target. Every field is checked before any destructive command.
EXPECTED_REDIS = {
    "project": PROJECT,
    "service": "redis",
    "image": "redis:7.4-alpine",
    "working_dir": str(ROOT),
    "maxmemory": "268435456",                # infra/redis/redis.conf: maxmemory 256mb
    "save": "",                              # infra/redis/redis.conf: save ""
    "appendonly": "no",
    "key_prefixes": ("rl:",),                # rate-limit state rl:{...} and stats rl:stats:{...}
}

_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class BenchError(Exception):
    """A precondition or safety check failed; the run must not continue."""


# ------------------------------------------------------------------------------------------------ process / HTTP

def sh(*args, timeout=600, check=True, env=None, cwd=ROOT, input_text=None):
    p = subprocess.run(list(args), capture_output=True, text=True, timeout=timeout, cwd=cwd, input=input_text,
                       env={**os.environ, **(env or {})})
    if check and p.returncode != 0:
        raise BenchError(f"command failed ({p.returncode}): {' '.join(args)}\n{p.stdout[-2000:]}{p.stderr[-2000:]}")
    return p


def http(method, url, token=None, body=None, headers=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with _OPENER.open(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode()


def prom_query(expr, at=None):
    params = {"query": expr}
    if at is not None:
        params["time"] = f"{at:.3f}"
    status, _, body = http("GET", f"{PROM}/api/v1/query?" + urllib.parse.urlencode(params))
    data = json.loads(body)
    if status != 200 or data.get("status") != "success":
        raise BenchError(f"Prometheus query failed: {expr}: {body[:300]}")
    return data["data"]


def prom_range(expr, start, end, step):
    params = {"query": expr, "start": f"{start:.3f}", "end": f"{end:.3f}", "step": str(step)}
    status, _, body = http("GET", f"{PROM}/api/v1/query_range?" + urllib.parse.urlencode(params))
    data = json.loads(body)
    if status != 200 or data.get("status") != "success":
        raise BenchError(f"Prometheus range query failed: {expr}: {body[:300]}")
    return data["data"]


# ------------------------------------------------------------------------------------------------ Compose health

def compose_ps():
    out = sh("docker", "compose", "ps", "--all", "--format", "json").stdout.strip()
    if not out:
        return []
    if out.startswith("["):
        return json.loads(out)
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def stack_problems(ps_rows, required=SERVICES):
    """Pure: which required services are missing, not running or not healthy (all have health checks)."""
    by_service = {r.get("Service"): r for r in ps_rows}
    problems = []
    for s in required:
        r = by_service.get(s)
        if r is None:
            problems.append(f"{s}: missing")
        elif r.get("State") != "running":
            problems.append(f"{s}: state={r.get('State')}")
        elif r.get("Health") not in ("healthy",):
            problems.append(f"{s}: health={r.get('Health') or 'none'}")
    return problems


# ------------------------------------------------------------------------------------------------ parsers (pure)

SAMPLE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+(\S+)', re.M)
LABEL = re.compile(r'(\w+)="((?:[^"\\]|\\.)*)"')


def parse_prometheus_text(text):
    """Prometheus exposition text -> list of (name, {labels}, float)."""
    out = []
    for name, labels, value in SAMPLE.findall(text):
        if name.startswith("#"):
            continue
        try:
            v = float(value)
        except ValueError:
            continue
        out.append((name, dict(LABEL.findall(labels or "")), v))
    return out


def requests_by_algorithm(samples):
    """ratelimiter_requests_total -> {algorithm: {result: value}} (all four results, missing = 0)."""
    out = {a: {r: 0.0 for r in RESULTS} for a in ALGORITHMS}
    for name, labels, v in samples:
        if name == "ratelimiter_requests_total":
            a, r = labels.get("algorithm"), labels.get("result")
            bucket = out.setdefault(a, {x: 0.0 for x in RESULTS})
            bucket[r] = bucket.get(r, 0.0) + v
    return out


def breaker_state(samples):
    state, openings = None, 0.0
    for name, labels, v in samples:
        if name == "resilience4j_circuitbreaker_state" and v == 1.0:
            state = labels.get("state")
        elif name == "ratelimiter_circuit_breaker_transitions_total" and labels.get("to") == "open":
            openings += v
    return state, openings


def parse_redis_info(text):
    """`INFO` output -> {section: {key: value}}; commandstats/latencystats values parsed to dicts."""
    sections, current = {}, "default"
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#"):
            current = line[1:].strip().lower()
            sections.setdefault(current, {})
            continue
        if ":" not in line:
            continue
        k, v = line.split(":", 1)
        if current in ("commandstats", "latencystats", "keyspace") or (current == "errorstats"):
            v = dict(kv.split("=", 1) for kv in v.split(",") if "=" in kv)
        sections.setdefault(current, {})[k] = v
    return sections


def command_calls(info, command):
    stats = info.get("commandstats", {}).get(f"cmdstat_{command}")
    return int(stats["calls"]) if stats else 0


# $time_iso8601 $remote_addr "$request" $status $request_time upstream=$upstream_addr
# upstream_time=$upstream_response_time "$http_user_agent"            (infra/nginx/nginx.conf)
NGINX_LINE = re.compile(
    r'^(?P<time>\S+) (?P<addr>\S+) "(?P<method>\S+) (?P<path>\S+) [^"]*" (?P<status>\d{3}) (?P<rt>\S+) '
    r'upstream=(?P<upstream>\S+) upstream_time=(?P<ut>\S+) "(?P<ua>[^"]*)"')
UA_FIELD = re.compile(r'(\w+)=(\S+)')


def parse_nginx_line(line):
    m = NGINX_LINE.match(line.strip())
    if not m:
        return None
    d = m.groupdict()
    d["ua_fields"] = dict(UA_FIELD.findall(d["ua"])) if d["ua"].startswith("k6/") else {}
    return d


def iso_to_epoch(ts):
    import datetime
    return datetime.datetime.fromisoformat(ts.replace("Z", "+00:00")).timestamp()


def nginx_summary(lines, run_id, per_user=False, timeline=False):
    """Counts the run's requests (User-Agent marker run=<run_id>) by phase and status, plus upstream split,
    optional per-second timeline (by path) and per-user aggregates (UA field user=)."""
    out = {"total": {}, "phases": {}, "upstream": {}, "paths": {}, "unparsed_lines": 0, "other_lines": 0}
    users, tl = {}, {}
    for line in lines:
        d = parse_nginx_line(line)
        if d is None:
            out["unparsed_lines"] += 1
            continue
        f = d["ua_fields"]
        if f.get("run") != run_id:
            out["other_lines"] += 1
            continue
        st, phase = d["status"], f.get("phase", "unknown")
        out["total"][st] = out["total"].get(st, 0) + 1
        out["phases"].setdefault(phase, {})
        out["phases"][phase][st] = out["phases"][phase].get(st, 0) + 1
        up = UPSTREAM_APP.get(d["upstream"].split(":")[0].split(",")[0], d["upstream"])
        out["upstream"][up] = out["upstream"].get(up, 0) + 1
        out["paths"].setdefault(d["path"], {})
        out["paths"][d["path"]][st] = out["paths"][d["path"]].get(st, 0) + 1
        if timeline:
            sec = int(iso_to_epoch(d["time"]))
            tl.setdefault(sec, {}).setdefault(d["path"], {})
            tl[sec][d["path"]][st] = tl[sec][d["path"]].get(st, 0) + 1
        if per_user and "user" in f:
            u = users.setdefault(f["user"], {"accepted": 0, "rejected": 0, "other": 0, "first": None, "last": None,
                                             "first_accepted": None, "last_accepted": None})
            t = int(iso_to_epoch(d["time"]))
            key = "accepted" if st == "200" else "rejected" if st == "429" else "other"
            u[key] += 1
            u["first"] = t if u["first"] is None else min(u["first"], t)
            u["last"] = t if u["last"] is None else max(u["last"], t)
            if st == "200":
                u["first_accepted"] = t if u["first_accepted"] is None else min(u["first_accepted"], t)
                u["last_accepted"] = t if u["last_accepted"] is None else max(u["last_accepted"], t)
    if per_user:
        out["users"] = users
    if timeline:
        out["timeline"] = {str(k): tl[k] for k in sorted(tl)}
    return out


def status_buckets(counts):
    """{status: n} -> {'200': n, '429': n, '503': n, 'other': n}."""
    b = {"200": 0, "429": 0, "503": 0, "other": 0}
    for st, n in counts.items():
        b[st if st in ("200", "429", "503") else "other"] += n
    return b


ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
SIZE = re.compile(r"([\d.]+)\s*([KMGT]?i?B)")
UNITS = {"B": 1, "kB": 1e3, "KB": 1e3, "MB": 1e6, "GB": 1e9, "TB": 1e12, "KiB": 1024, "MiB": 1024 ** 2,
         "GiB": 1024 ** 3, "TiB": 1024 ** 4}


def to_bytes(s):
    m = SIZE.search(s or "")
    return float(m.group(1)) * UNITS.get(m.group(2), 1) if m else None


def parse_docker_stats_line(line):
    """One sampler line {"ts": epoch, "stats": {...docker stats json...}} -> flat row or None."""
    try:
        rec = json.loads(ANSI.sub("", line).strip())
    except ValueError:
        return None
    s = rec.get("stats", {})
    if not s.get("Name"):
        return None
    try:
        cpu = float(s.get("CPUPerc", "0").rstrip("%") or 0)
    except ValueError:
        return None
    used, _, limit = (s.get("MemUsage") or "").partition("/")
    return {"ts": rec["ts"], "name": s["Name"], "cpu_pct": cpu, "mem_bytes": to_bytes(used),
            "mem_limit_bytes": to_bytes(limit)}


def rolling_max_mean(points, window_s):
    """points: sorted [(ts, value)] -> max over all windows [t, t+window) of the mean value (None if empty)."""
    if not points:
        return None
    best, j, total = None, 0, 0.0
    for i, (t, v) in enumerate(points):
        total += v
        while points[j][0] <= t - window_s:
            total -= points[j][1]
            j += 1
        if t - points[0][0] >= window_s - 1.0:      # only full windows count
            mean = total / (i - j + 1)
            best = mean if best is None else max(best, mean)
    if best is None:                                 # shorter than one window: use the mean of what exists
        best = sum(v for _, v in points) / len(points)
    return best


def k6_metric(summary, name, field="count"):
    m = summary.get("metrics", {}).get(name)
    if not m:
        return None
    return m.get("values", {}).get(field)


# ------------------------------------------------------------------------------------------------ Redis safety

def redis_identity():
    """Collects everything the safety check needs about the Compose Redis (no destructive command)."""
    cid = sh("docker", "compose", "ps", "-q", "redis").stdout.strip()
    ident = {"container_id": cid}
    if not cid or "\n" in cid:
        ident["error"] = f"expected exactly one redis container, got {cid!r}"
        return ident
    inspect = json.loads(sh("docker", "inspect", cid).stdout)[0]
    labels = inspect["Config"].get("Labels") or {}
    ident.update({
        "project": labels.get("com.docker.compose.project"),
        "service": labels.get("com.docker.compose.service"),
        "working_dir": labels.get("com.docker.compose.project.working_dir"),
        "image": inspect["Config"].get("Image"),
        "running": inspect["State"].get("Running"),
        "name": inspect.get("Name"),
    })
    cli = lambda *a: sh("docker", "exec", cid, "redis-cli", *a).stdout   # noqa: E731
    info = parse_redis_info(cli("INFO", "server"))
    ident["redis_version"] = info.get("server", {}).get("redis_version")
    ident["tcp_port"] = info.get("server", {}).get("tcp_port")
    for key in ("maxmemory", "save", "appendonly"):
        lines = cli("CONFIG", "GET", key).splitlines()
        ident[key] = lines[1] if len(lines) > 1 else None
    keys = [k for k in cli("--scan", "--count", "1000").splitlines() if k]
    ident["key_count"] = len(keys)
    ident["foreign_keys_sample"] = [k for k in keys if not k.startswith(EXPECTED_REDIS["key_prefixes"])][:10]
    ident["foreign_key_count"] = sum(1 for k in keys if not k.startswith(EXPECTED_REDIS["key_prefixes"]))
    return ident


def redis_safety_violations(ident, expected=EXPECTED_REDIS):
    """Pure: every reason the target must NOT receive a destructive command. Empty list = safe."""
    if ident.get("error"):
        return [ident["error"]]
    v = []
    for field in ("project", "service", "image", "working_dir"):
        want = expected[field]
        got = ident.get(field)
        if field == "working_dir" and got:
            got = os.path.realpath(got)
            want = os.path.realpath(want)
        if got != want:
            v.append(f"{field}: expected {want!r}, found {got!r}")
    if ident.get("running") is not True:
        v.append("container is not running")
    for field in ("maxmemory", "save", "appendonly"):
        if ident.get(field) != expected[field]:
            v.append(f"CONFIG {field}: expected {expected[field]!r}, found {ident.get(field)!r} (not this project's redis.conf)")
    if ident.get("tcp_port") != "6379":
        v.append(f"tcp_port: expected '6379', found {ident.get('tcp_port')!r}")
    if not str(ident.get("redis_version", "")).startswith("7.4"):
        v.append(f"redis_version: expected 7.4.x, found {ident.get('redis_version')!r}")
    if ident.get("foreign_key_count", 0):
        v.append(f"{ident['foreign_key_count']} keys outside the rate-limiter prefixes, e.g. {ident.get('foreign_keys_sample')}")
    return v


def redis_admin(ident, *command, expected=EXPECTED_REDIS):
    """Runs a state-changing Redis command (FLUSHDB, CONFIG RESETSTAT, SLOWLOG RESET) only against the verified
    container id, re-verifying identity first. Raises BenchError when any check fails."""
    allowed = {("FLUSHDB",), ("CONFIG", "RESETSTAT"), ("SLOWLOG", "RESET")}
    if tuple(c.upper() for c in command) not in allowed:
        raise BenchError(f"refusing Redis command {command}: not on the allow-list {sorted(allowed)}")
    violations = redis_safety_violations(ident, expected)
    if violations:
        raise BenchError("refusing destructive Redis command " + " ".join(command) + ": " + "; ".join(violations))
    current = sh("docker", "compose", "ps", "-q", "redis").stdout.strip()
    if current != ident["container_id"]:
        raise BenchError(f"redis container changed since verification ({ident['container_id'][:12]} -> {current[:12]})")
    return sh("docker", "exec", ident["container_id"], "redis-cli", *command).stdout.strip()


def redis_cli(*args):
    """Read-only Redis queries (INFO, SLOWLOG GET, DBSIZE, CONFIG GET)."""
    return sh("docker", "compose", "exec", "-T", "redis", "redis-cli", *args).stdout


# ------------------------------------------------------------------------------------------------ misc

def write_json(path, data):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True, default=str))


def read_json(path, default=None):
    try:
        return json.loads(pathlib.Path(path).read_text())
    except (OSError, ValueError):
        return default


def now():
    return time.time()
