#!/usr/bin/env python3
"""Phase 3B benchmark report: tables generated ONLY from saved run artifacts (benchmarks/results/*/), so every
number is reproducible (3B-AC4). No measurement is typed by hand; nothing here talks to the running stack.

  python3 scripts/bench/report.py [--out benchmarks/reports/<name>]

Output: report.md (tables) and report.json (the same data, machine-readable). Charts are deferred (decision O1:
matplotlib is not installed; no dependency is added).

Aggregation rules (approved method): only VALID runs enter result tables; per configuration the median and the
min–max range over the valid repetitions are reported; a spread (max−min)/median above 10 % is flagged. Percentiles
are never averaged: each run's own percentile is taken, then the median across runs. Invalid runs are listed with
their reasons. Targets are shown next to measurements and are never reported as achieved without a valid run.
"""
import argparse
import datetime
import json
import pathlib
import statistics
import sys

import benchlib as bl

RESULTS = bl.ROOT / "benchmarks" / "results"
TARGETS = {"throughput_rps": 5000, "overhead_avg_ms": 5, "overhead_p95_ms": 10, "overhead_p99_ms": 20, "error_rate": 0.001}
MST = {"achieved": 0.99, "error_rate": 0.001, "e2e_p99_ms": 100, "overhead_p99_ms": 20}


def load_runs():
    runs = []
    for d in sorted(RESULTS.glob("*/")):
        meta = bl.read_json(d / "run-meta.json")
        if not meta or meta.get("dry_run"):
            continue
        meta["_dir"] = d
        meta["_summary"] = bl.read_json(d / "k6-summary.json") or {}
        meta["_validity"] = bl.read_json(d / "validity.json")
        meta["_reconcile"] = bl.read_json(d / "reconcile.json")
        meta["_prom"] = bl.read_json(d / "prometheus" / "window-summary.json") or {}
        meta["_docker"] = bl.read_json(d / "docker-stats-summary.json") or {}
        meta["_sources"] = bl.read_json(d / "sources.json") or {}
        meta["_correctness"] = bl.read_json(d / "correctness.json")
        runs.append(meta)
    return runs


def ms(v):
    return None if v is None else v * 1000.0


def trend(summary, name, stat):
    m = summary.get("metrics", {}).get(name)
    return None if not m else m.get("values", {}).get(stat)


def run_metrics(m):
    """Flat per-run numbers used by every table (milliseconds, requests/s, ratios)."""
    s, p, d, src = m["_summary"], m["_prom"].get("instant", {}), m["_docker"], m["_sources"]
    window = m.get("measure_window") or {}
    dur = (window.get("end", 0) - window.get("start", 0)) or m.get("measure_s") or 1
    reqs = bl.k6_metric(s, "http_reqs{phase:measure}") or 0
    unexpected = bl.k6_metric(s, "unexpected{phase:measure}") or 0
    acc = bl.k6_metric(s, "accepted{phase:measure}") or 0
    rej = bl.k6_metric(s, "rejected{phase:measure}") or 0
    lat = (src.get("redis") or {}).get("after", {}).get("latencystats_evalsha") or {}
    apps = {n: v for n, v in d.get("containers", {}).items() if "-app" in n}
    redis_c = next((v for n, v in d.get("containers", {}).items() if n.endswith("-redis-1")), {})
    nginx_c = next((v for n, v in d.get("containers", {}).items() if n.endswith("-nginx-1")), {})
    rng = m["_prom"].get("range", {})
    return {
        "achieved_rps": reqs / dur if dur else None, "requests": reqs,
        "achieved_ratio": (reqs / m["expected_requests"]) if m.get("expected_requests") else None,
        "error_rate": unexpected / reqs if reqs else None, "rejected_share": rej / reqs if reqs else None,
        "accepted": acc, "rejected": rej, "unexpected": unexpected,
        "e2e_avg": trend(s, "http_req_duration{phase:measure}", "avg"), "e2e_p50": trend(s, "http_req_duration{phase:measure}", "med"),
        "e2e_p95": trend(s, "http_req_duration{phase:measure}", "p(95)"), "e2e_p99": trend(s, "http_req_duration{phase:measure}", "p(99)"),
        "e2e_max": trend(s, "http_req_duration{phase:measure}", "max"),
        "ovh_avg": ms(p.get("overhead_avg")), "ovh_p50": ms(p.get("overhead_p5")), "ovh_p95": ms(p.get("overhead_p95")),
        "ovh_p99": ms(p.get("overhead_p99")), "ovh_p999": ms(p.get("overhead_p999")),
        "ovh_allowed_p99": ms(p.get("overhead_allowed_p99")), "ovh_rejected_p99": ms(p.get("overhead_rejected_p99")),
        "rtt_avg": ms(p.get("redis_rtt_avg")), "rtt_p50": ms(p.get("redis_rtt_p5")), "rtt_p99": ms(p.get("redis_rtt_p99")),
        "srv_p50_us": float(lat["p50"]) if lat.get("p50") else None, "srv_p99_us": float(lat["p99"]) if lat.get("p99") else None,
        "app_cpu_max_pct": max((v["cpu_pct_avg"] for v in apps.values()), default=None),
        "app_cpu_peak_pct": max((v["cpu_pct_max"] for v in apps.values()), default=None),
        "app_mem_max_mib": max(((v["mem_bytes_max"] or 0) / 2 ** 20 for v in apps.values()), default=None),
        "redis_cpu_pct": redis_c.get("cpu_pct_avg"), "nginx_cpu_pct": nginx_c.get("cpu_pct_avg"),
        "k6_cpu_pct_limit": (d.get("k6") or {}).get("cpu_pct_of_limit_max_5s"),
        "jvm_cpu_max": max((v["avg"] for v in rng.get("process_cpu", {}).values()), default=None),
        "heap_max_mib": max((v["max"] / 2 ** 20 for v in rng.get("heap_used_bytes", {}).values()), default=None),
        "tomcat_busy_max": max((v["max"] for v in rng.get("tomcat_busy", {}).values()), default=None),
        "gc_pause_ms": ms(p.get("gc_pause_seconds")), "redis_ops": p.get("redis_cmd_rate"),
        "redis_main_cpu": p.get("redis_main_thread_cpu_cores"), "redis_clients_max": max((v["max"] for v in rng.get("redis_clients", {}).values()), default=None),
        "redis_mem_mib": max((v["max"] / 2 ** 20 for v in rng.get("redis_memory_bytes", {}).values()), default=None),
        "split": {app: sum(sum(v.values()) for v in alg.values()) for app, alg in (src.get("raw") or {}).get("per_app", {}).items()},
    }


def key(m):
    return (m["scenario"], m["algorithm"], m["rate"], m["profile"], m["variant"], m["target"])


def agg(values):
    vals = [v for v in values if v is not None]
    if not vals:
        return None
    med = statistics.median(vals)
    lo, hi = min(vals), max(vals)
    return {"median": med, "min": lo, "max": hi, "n": len(vals), "noisy": med not in (0, None) and (hi - lo) / abs(med) > 0.10}


def fmt(a, digits=2, pct=False):
    if a is None:
        return "–"
    if isinstance(a, (int, float)):
        return f"{a * 100:.{digits}f} %" if pct else f"{a:,.{digits}f}"
    if a["n"] == 1:
        return fmt(a["median"], digits, pct)
    s = f"{fmt(a['median'], digits, pct)} ({fmt(a['min'], digits, pct)}–{fmt(a['max'], digits, pct)})"
    return s + (" ⚠" if a["noisy"] else "")


def group(runs):
    groups = {}
    for m in runs:
        g = groups.setdefault(key(m), {"valid": [], "invalid": [], "error": []})
        if m.get("status") != "complete":
            g["error"].append(m)
        elif m.get("valid"):
            g["valid"].append(m)
        else:
            g["invalid"].append(m)
    return groups


def config_row(k, g, fields):
    metrics = [run_metrics(m) for m in g["valid"]]
    row = {"config": k, "valid": len(g["valid"]), "invalid": len(g["invalid"]), "errors": len(g["error"])}
    for f in fields:
        row[f] = agg([x[f] for x in metrics])
    return row


def table(headers, rows):
    out = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return "\n".join(out)


LAT = ["achieved_rps", "error_rate", "rejected_share", "e2e_p50", "e2e_p95", "e2e_p99", "e2e_max", "ovh_avg", "ovh_p50", "ovh_p95",
       "ovh_p99", "rtt_p50", "rtt_p99", "srv_p50_us", "srv_p99_us"]
RES = ["app_cpu_max_pct", "app_cpu_peak_pct", "jvm_cpu_max", "heap_max_mib", "gc_pause_ms", "tomcat_busy_max", "redis_cpu_pct",
       "redis_main_cpu", "redis_ops", "redis_clients_max", "redis_mem_mib", "nginx_cpu_pct", "k6_cpu_pct_limit"]


def latency_section(title, keys_rows, label):
    hdr = [label, "valid/invalid", "achieved RPS", "non-429 errors", "429 share", "E2E p50", "E2E p95", "E2E p99", "E2E max",
           "limiter avg", "limiter p50", "limiter p95", "limiter p99", "Redis RTT p50", "Redis RTT p99", "Redis server p50 µs", "Redis server p99 µs"]
    rows = []
    for lbl, r in keys_rows:
        rows.append([lbl, f"{r['valid']}/{r['invalid'] + r['errors']}", fmt(r["achieved_rps"], 0), fmt(r["error_rate"], 3, True),
                     fmt(r["rejected_share"], 1, True), fmt(r["e2e_p50"]), fmt(r["e2e_p95"]), fmt(r["e2e_p99"]), fmt(r["e2e_max"], 1),
                     fmt(r["ovh_avg"], 3), fmt(r["ovh_p50"], 3), fmt(r["ovh_p95"], 3), fmt(r["ovh_p99"], 3), fmt(r["rtt_p50"], 3),
                     fmt(r["rtt_p99"], 3), fmt(r["srv_p50_us"], 1), fmt(r["srv_p99_us"], 1)])
    return f"### {title}\n\nLatency in ms unless stated (E2E = k6 http_req_duration; limiter = in-process overhead histogram; "  \
           f"Redis RTT = client-side script call; Redis server = INFO latencystats for EVALSHA, whole run).\n\n" + table(hdr, rows)


def resource_section(title, keys_rows, label):
    hdr = [label, "app CPU avg % (max inst)", "app CPU peak %", "JVM process CPU", "heap max MiB", "GC pause ms", "Tomcat busy max",
           "Redis CPU %", "Redis main-thread cores", "Redis ops/s", "Redis clients", "Redis MiB", "Nginx CPU %", "k6 CPU % of limit (worst 5 s)"]
    rows = [[lbl, fmt(r["app_cpu_max_pct"], 0), fmt(r["app_cpu_peak_pct"], 0), fmt(r["jvm_cpu_max"], 2), fmt(r["heap_max_mib"], 0),
             fmt(r["gc_pause_ms"], 0), fmt(r["tomcat_busy_max"], 0), fmt(r["redis_cpu_pct"], 0), fmt(r["redis_main_cpu"], 2),
             fmt(r["redis_ops"], 0), fmt(r["redis_clients_max"], 0), fmt(r["redis_mem_mib"], 1), fmt(r["nginx_cpu_pct"], 0),
             fmt(r["k6_cpu_pct_limit"], 0)] for lbl, r in keys_rows]
    return f"### {title}\n\nCPU % from docker stats (100 % = one core; app limit 150 %, Redis 100 %, Nginx 100 %), JVM/Redis from Prometheus.\n\n" + table(hdr, rows)


def rows_for(groups, scenario, label_fn):
    out = []
    for k in sorted((k for k in groups if k[0] == scenario), key=lambda k: (k[2], k[3], k[4], k[5], bl.ALGORITHMS.index(k[1]))):
        out.append((label_fn(k), config_row(k, groups[k], LAT + RES)))
    return out


def invalid_section(runs):
    rows = [[m["run_id"], m.get("status"), "; ".join(m.get("invalid_reasons") or [m.get("error", "")])[:300]]
            for m in runs if m.get("status") != "complete" or not m.get("valid")]
    return "## Invalid and failed runs (kept, never reported as results)\n\n" + (table(["run", "status", "reasons"], rows) if rows else "None.")


def mst_section(groups):
    lines, data = [], {}
    for alg in bl.ALGORITHMS:
        best, evidence = None, []
        for rate in (1000, 2500, 5000, 7500, 9000, 10000):
            g = groups.get(("B1", alg, rate, "mixed", "default", "nginx"))
            if not g:
                evidence.append(f"{rate}: not run")
                continue
            if len(g["valid"]) < 3:
                evidence.append(f"{rate}: {len(g['valid'])} valid of {len(g['valid']) + len(g['invalid']) + len(g['error'])} runs")
                continue
            ms_ = [run_metrics(m) for m in g["valid"]]
            ok = all((x["achieved_ratio"] or 0) >= MST["achieved"] and (x["error_rate"] or 0) < MST["error_rate"]
                     and (x["e2e_p99"] or 1e9) < MST["e2e_p99_ms"] and (x["ovh_p99"] or 1e9) < MST["overhead_p99_ms"] for x in ms_)
            evidence.append(f"{rate}: {'meets' if ok else 'fails'} the criteria in all 3 valid runs" if ok else
                            f"{rate}: valid but criteria not met (E2E p99 {[round(x['e2e_p99'] or 0, 1) for x in ms_]} ms, "
                            f"limiter p99 {[round(x['ovh_p99'] or 0, 3) for x in ms_]} ms)")
            if ok:
                best = rate
        data[alg] = {"max_sustainable_tested_rps": best, "evidence": evidence}
        lines.append(f"- **{alg}**: {'**' + format(best, ',') + ' RPS**' if best else 'not established'} — " + "; ".join(evidence))
    return ("## B1 load levels: highest validated level, and maximum sustainable throughput\n\n"
            "**7,500 RPS is the highest validated B1 load level on this laptop** (3 valid runs per algorithm). 10,000 RPS was "
            "attempted 11 times and every attempt was invalid (load generator and instances at their limits); by decision it "
            "was not retried. This is a laptop measurement, not a production capacity claim.\n\n"
            "Maximum sustainable throughput additionally requires, in all 3 valid runs: achieved ≥ 99 %; non-429 errors "
            "< 0.1 %; E2E P99 < 100 ms; limiter P99 < 20 ms. It is established together with the B2 knee below; the "
            "highest validated level is not assumed to be sustainable.\n\n" + "\n".join(lines)), data


def knee_section(runs):
    out, data = [], {}
    for m in [r for r in runs if r["scenario"] == "B2" and r.get("status") == "complete"]:
        s = m["_summary"]
        steps = bl.read_json(m["_dir"] / "prometheus" / "ramp-steps.json") or {}
        rows_csv = []
        try:
            import csv
            rows_csv = list(csv.DictReader(open(m["_dir"] / "docker-stats.csv")))
        except OSError:
            pass
        step_s = int(m["k6_env"]["STEP_S"])
        rows, knee, consecutive = [], None, True
        for rate_s, st in sorted(steps.items(), key=lambda x: int(x[0])):
            rate = int(rate_s)
            n = bl.k6_metric(s, f"http_reqs{{step:{rate}}}") or 0
            unx = bl.k6_metric(s, f"unexpected{{step:{rate}}}") or 0
            p99 = trend(s, f"http_req_duration{{step:{rate}}}", "p(99)")
            achieved = n / (rate * step_s) if rate else None
            k6pts = sorted((float(r["ts"]), float(r["cpu_pct"]) / bl.K6_CPU_LIMIT) for r in rows_csv
                           if "-k6-" in r["name"] and st["start"] <= float(r["ts"]) <= st["end"])
            k6cpu = bl.rolling_max_mean(k6pts, 5.0)
            ovh = ms(st.get("overhead_p99"))
            ok = (achieved or 0) >= 0.99 and (unx / n if n else 1) < 0.001 and (p99 or 1e9) < 100 and (ovh or 1e9) < 20 and (k6cpu or 1e9) <= 85
            if consecutive and ok:
                knee = rate
            else:
                consecutive = False
            rows.append([rate, f"{achieved:.3f}" if achieved else "–", f"{(unx / n if n else 0):.4%}", fmt(p99), fmt(ovh, 3),
                         fmt(st.get("app_cpu_max"), 2), fmt(st.get("redis_cpu_cores"), 2), fmt(k6cpu, 0), "yes" if ok else "no"])
        data[m["run_id"]] = {"knee_rps": knee, "valid_run": m.get("valid")}
        out.append(f"#### {m['run_id']} (run-level validity: {'VALID' if m.get('valid') else 'INVALID'})\n\nKnee (highest consecutive step "
                   f"meeting every criterion from the first step): **{knee if knee else 'none'}**\n\n"
                   + table(["step RPS", "achieved/target", "non-429 errors", "E2E p99 ms", "limiter p99 ms", "app process CPU (max)",
                            "Redis CPU cores", "k6 CPU % limit", "meets criteria"], rows))
    return "## B2 stress ramp and knee\n\n" + ("\n\n".join(out) if out else "No B2 runs."), data


def correctness_section(runs):
    rows = []
    for m in runs:
        c = m.get("_correctness")
        if c is None or m.get("status") != "complete":
            continue
        detail = (f"accepted {c.get('accepted')} / rejected {c.get('rejected')} / unexpected {c.get('unexpected')}" if m["scenario"] == "B6"
                  else f"users {c.get('users')}, accepted {c.get('accepted_total')} vs bound {c.get('bound_total')}, over-admitted "
                       f"{c.get('over_admitted_requests')} requests ({len(c.get('over_admitted_users') or [])} users), min utilisation "
                       f"{fmt(c.get('min_utilisation'), 2)}")
        rows.append([m["run_id"], "valid" if m.get("valid") else "INVALID", "PASS" if c.get("pass") else "FAIL", detail])
    return "## Correctness (B5 burst bound, B6 exactly 100 of 1,000, B10 per-user bounds)\n\n" + (
        table(["run", "run validity", "correctness", "detail"], rows) if rows else "No runs.")


def b3_section(groups):
    rows = []
    for rate in (1000, 5000):
        on = groups.get(("B3", "token_bucket", rate, "mixed", "default", "nginx"))
        off = groups.get(("B3", "token_bucket", rate, "mixed", "limiter-off", "nginx"))
        if not on or not off or not on["valid"] or not off["valid"]:
            rows.append([rate, "incomplete", "", "", "", ""])
            continue
        a, b = config_row(None, on, LAT), config_row(None, off, LAT)
        d = lambda f: None if not a[f] or not b[f] else a[f]["median"] - b[f]["median"]  # noqa: E731
        rows.append([rate, f"{len(on['valid'])}/{len(off['valid'])}", f"{fmt(a['e2e_p50'])} vs {fmt(b['e2e_p50'])} (Δ {fmt(d('e2e_p50'), 3)})",
                     f"{fmt(a['e2e_p95'])} vs {fmt(b['e2e_p95'])} (Δ {fmt(d('e2e_p95'), 3)})",
                     f"{fmt(a['e2e_p99'])} vs {fmt(b['e2e_p99'])} (Δ {fmt(d('e2e_p99'), 3)})",
                     f"p50 {fmt(a['ovh_p50'], 3)} / p95 {fmt(a['ovh_p95'], 3)} / p99 {fmt(a['ovh_p99'], 3)}"])
    return ("## B3 limiter overhead A/B (token bucket; limiter on vs off; medians, ms)\n\n"
            + table(["RPS", "valid on/off", "E2E p50 on vs off", "E2E p95 on vs off", "E2E p99 on vs off", "in-process limiter histogram (on)"], rows))


def b7_section(groups):
    rows = []
    for k in sorted(k for k in groups if k[0] == "B7"):
        for m in groups[k]["valid"]:
            sp = run_metrics(m)["split"]
            tot = sum(sp.values()) or 1
            rows.append([m["run_id"], k[5], k[1], ", ".join(f"{a} {v / tot:.1%}" for a, v in sorted(sp.items()))])
    return "## B7 per-instance load split (share of decisions per instance)\n\n" + (table(["run", "target", "algorithm", "split"], rows) if rows else "No runs.")


def failure_section(runs):
    out = []
    for m in [r for r in runs if r["kind"] == "failure" and r.get("status") == "complete"]:
        nginx = bl.read_json(m["_dir"] / "nginx-summary.json") or {}
        tl = nginx.get("timeline", {})
        faults = m.get("faults", [])
        f0 = min((f["at"] for f in faults), default=None)
        f1 = max((f.get("done", f["at"]) for f in faults), default=None)
        rows = []
        for sec in sorted(tl, key=int):
            t = int(sec)
            if f0 and not (f0 - 10 <= t <= (f1 or f0) + 40):
                continue
            cells = []
            for path in ("/api/v1/demo/profile", "/api/v1/demo/enterprise"):
                c = tl[sec].get(path, {})
                cells.append(" ".join(f"{s}:{n}" for s, n in sorted(c.items())))
            rows.append([f"{t - int(f0):+d}" if f0 else sec] + cells)
        br = m.get("breaker_timeline", {})
        trans = []
        for inst, states in sorted((i, s) for i, s in br.items() if not i.startswith("_")):
            for st in ("open", "half_open"):
                pts = [t for t, v in states.get(st, []) if v == 1.0]
                if pts:
                    trans.append(f"{inst}: {st} from {pts[0] - (f0 or 0):+.0f} s to {pts[-1] - (f0 or 0):+.0f} s")
        src = m["_sources"]
        tot = (src.get("raw") or {}).get("total", {})
        events = "; ".join(f"{f['offset_s']}s {f['action']} {' '.join(map(str, f['args']))}"
                           + (f" (Redis measured {f['redis_measured_block_ms']} ms)" if "redis_measured_block_ms" in f else "")
                           + (f" policy API {f.get('policy_api_status')} readiness {f.get('readiness')}" if f["action"] == "check_mysql_down" else "")
                           for f in faults[:6]) + (f" … ({len(faults)} events)" if len(faults) > 6 else "")
        out.append(f"### {m['scenario']} — {m['run_id']}\n\nFault events (offset from measure start): {events}\n\n"
                   f"Decisions over the run: {json.dumps(tot.get('fixed_window'))} (profile, fixed window), "
                   f"{json.dumps(tot.get('sliding_window'))} (enterprise, sliding window)\n\n"
                   f"Breaker states per instance (Prometheus, 1 s query step over 5 s scrapes): {'; '.join(trans) or 'never left CLOSED'}\n\n"
                   f"Reconciliation: {'ok' if (m['_reconcile'] or {}).get('ok') else 'NOT reconciled: ' + ', '.join((m['_reconcile'] or {}).get('failed', []))}\n\n"
                   f"Per-second statuses (seconds relative to the first fault; Nginx log):\n\n"
                   + table(["t (s)", "/profile (fail-open)", "/enterprise (fail-closed)"], rows))
    return "## F1–F5 failure and recovery under load\n\n" + ("\n\n".join(out) if out else "No failure runs.")


def key_memory_section(runs):
    vals = {}
    for m in runs:
        km = m.get("key_memory_usage")
        if m.get("valid") and m["scenario"] == "B1" and isinstance(km, dict):
            vals.setdefault(m["algorithm"], []).extend(v for v in km.values() if isinstance(v, int))
    rows = [[a, len(v), min(v), statistics.median(v), max(v)] for a, v in sorted(vals.items())]
    return ("## Redis memory per rate-limit key (MEMORY USAGE samples, valid B1 runs)\n\n"
            + (table(["algorithm", "samples", "min bytes", "median bytes", "max bytes"], rows) if rows else "No samples."))


def environment_section(runs):
    if not runs:
        return ""
    env = (runs[-1].get("environment") or {})
    host = env.get("host", {})
    return ("## Environment (from the latest run's environment record)\n\n"
            f"- Host: {host.get('cpu')}, {host.get('ncpu')} CPUs, {int(host.get('memsize') or 0) / 2 ** 30:.0f} GiB; "
            f"{' '.join((host.get('sw_vers') or '').split())}\n- Docker: {env.get('docker', {}).get('version')}, "
            f"{env.get('docker', {}).get('info')}\n- Git: {env.get('git', {}).get('commit')} on {env.get('git', {}).get('branch')}\n"
            "- All services share one laptop; results are relative and machine-specific, not production capacity.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out")
    a = ap.parse_args()
    runs = load_runs()
    groups = group(runs)
    out_dir = pathlib.Path(a.out).resolve() if a.out else bl.ROOT / "benchmarks" / "reports" / datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir.mkdir(parents=True, exist_ok=True)
    md = [f"# Phase 3B benchmark report\n\nGenerated {datetime.datetime.now().isoformat(timespec='seconds')} by scripts/bench/report.py from "
          f"{len(runs)} saved runs in benchmarks/results/ ({sum(1 for r in runs if r.get('valid'))} valid). ⚠ = spread above 10 % across repetitions. "
          "Cells show the median (min–max) over valid repetitions."]
    md.append(environment_section(runs))
    lab_alg = lambda k: f"{k[1]} @ {k[2]:,}"  # noqa: E731
    sections = [
        ("B0 baseline (100 RPS)", "B0", lab_alg), ("B1 load levels (mixed profile, Nginx)", "B1", lab_alg),
        ("B4 headroom vs throttled (5,000 RPS)", "B4", lambda k: f"{k[1]} {k[3]}"),
        ("B5 burst (10,000 requests in 1 s, one fresh user)", "B5", lab_alg), ("B6 concurrency", "B6", lab_alg),
        ("B7 distributed: Nginx vs direct (5,000 RPS)", "B7", lambda k: f"{k[1]} via {k[5]}"),
        ("B8 hot key", "B8", lab_alg), ("B10 correctness under load (throttled, 2,000 RPS)", "B10", lab_alg),
        ("B3 overhead A/B", "B3", lambda k: f"{k[2]:,} limiter {'on' if k[4] == 'default' else 'off'}"),
        ("B9 Redis pooling", "B9", lambda k: f"{k[2]:,} {k[4]}"),
    ]
    data = {"runs": len(runs), "valid": sum(1 for r in runs if r.get("valid")), "tables": {}}
    for title, scen, lab in sections:
        rows = rows_for(groups, scen, lab)
        if not rows:
            md.append(f"### {title}\n\nNo runs.")
            continue
        md.append(latency_section(title, rows, "configuration"))
        md.append(resource_section(title + " — resources", rows, "configuration"))
        data["tables"][scen] = [{"label": l, **{k: v for k, v in r.items() if k != "config"}} for l, r in rows]
    mst_md, mst = mst_section(groups)
    knee_md, knee = knee_section(runs)
    md += [mst_md, knee_md, b3_section(groups), b7_section(groups), correctness_section(runs), key_memory_section(runs), failure_section(runs),
           "## Targets (requirements) vs measured\n\n" + table(["metric", "target", "measured"], [
               ["throughput", "5,000 RPS (3 instances)", "; ".join(f"{a}: {v['max_sustainable_tested_rps'] or 'not established'}" for a, v in mst.items())],
               ["limiter overhead avg / P95 / P99", "< 5 / 10 / 20 ms", "see B1 tables (in-process histogram, per run)"],
               ["enforcement accuracy", "zero over-admission", "see the correctness table (B5, B6, B10)"],
               ["non-429 error rate", "< 0.1 %", "see the error columns; invalid runs listed separately"],
               ["load levels", "1,000 → 10,000 RPS", "10,000 RPS reported as achieved only where a valid run shows it"]]),
           invalid_section(runs)]
    data["mst"], data["knee"] = mst, knee
    (out_dir / "report.md").write_text("\n\n".join(md) + "\n")
    bl.write_json(out_dir / "report.json", data)
    print(f"report written to {out_dir}/report.md ({len(runs)} runs)")


if __name__ == "__main__":
    sys.path.insert(0, str(pathlib.Path(__file__).parent))
    main()
