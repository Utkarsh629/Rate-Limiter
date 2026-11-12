#!/usr/bin/env python3
"""Phase 3B count reconciliation (decision O2): exact request counts over the WHOLE k6 run (warm-up + measure).

Sources, all covering the same run because the instances are idle at both counter snapshots:
  k6        accepted (200) / rejected (429) / unexpected (anything else), all phases
  nginx     the run's access-log lines (User-Agent marker run=<run-id>) by status        (n/a in direct mode)
  raw       per-instance ratelimiter_requests_total deltas, read from each instance        (limiter decisions)
  prom      the same counters from Prometheus at two idle instants after their final scrape
  stats     /api/v1/policies/{id}/stats after the final flush (shards were emptied by the Redis reset)
  redis     EVALSHA calls (rate-limit scripts only; stats flushes use EVAL, decision D41)

Rules (required unless noted): k6 = nginx; k6 = raw (200 = allowed + fail_open, 429 = rejected); raw = prom (in
total and per instance); stats = raw per policy; nginx 503 = raw fail_closed; no decisions for algorithms the run
does not use; EVALSHA = allowed + rejected when no request failed open/closed (informational otherwise).

Standalone: python3 scripts/bench/reconcile.py <run-dir>   (re-evaluates sources.json, rewrites reconcile.json)
"""
import sys

import benchlib as bl

INSTANCE_OF = bl.APP_INSTANCE


def check(name, expected, observed, required=True, explanation=None):
    ok = expected == observed
    return {"name": name, "required": required, "status": "pass" if ok else "fail", "expected": expected,
            "observed": observed, "explanation": None if ok else (explanation or "mismatch: see the sources")}


def na(name, reason, required=True):
    return {"name": name, "required": required, "status": "n/a", "explanation": reason}


def sum_results(totals, algorithms, results):
    return sum(totals.get(a, {}).get(r, 0) for a in algorithms for r in results)


def reconcile(src):
    """src: {"k6", "nginx" (or None), "raw", "prom", "stats" ({policy_id: body}), "redis", "config"} -> report."""
    cfg = src["config"]
    algs = cfg["algorithms"]                       # algorithms the run's traffic uses
    filter_enabled = cfg.get("filter_enabled", True)
    k6, nginx, raw, prom = src.get("k6") or {}, src.get("nginx"), src.get("raw") or {}, src.get("prom") or {}
    checks, notes = [], []

    missing = [s for s in ("k6", "raw", "prom") if not src.get(s)]
    if missing:
        checks.append({"name": "sources_present", "required": True, "status": "fail", "expected": "k6, raw, prom",
                       "observed": f"missing: {', '.join(missing)}",
                       "explanation": "a required source could not be collected; the run cannot be reconciled"})

    # --- k6 vs Nginx (same requests seen by the client and by the proxy)
    if nginx is None:
        checks.append(na("k6_vs_nginx", "requests bypass Nginx in this run (direct mode); Nginx is not a source"))
    elif k6:
        nb = bl.status_buckets(nginx.get("total", {}))
        exp = {"200": k6.get("accepted"), "429": k6.get("rejected"), "non_200_429": k6.get("unexpected")}
        obs = {"200": nb["200"], "429": nb["429"], "non_200_429": nb["503"] + nb["other"]}
        expl = None
        if exp != obs:
            extra = {s: n for s, n in nginx.get("total", {}).items() if s not in ("200", "429")}
            expl = (f"Nginx logged {sum(obs.values())} requests, k6 counted {sum(v or 0 for v in exp.values())}; "
                    f"non-200/429 statuses in the log: {extra}. 499 = client closed the connection (k6 timeout); "
                    f"502/504 = upstream failure; a k6 request without a log line never reached Nginx.")
        checks.append(check("k6_vs_nginx", exp, obs, explanation=expl))

    if not filter_enabled:
        # Limiter disabled (B3 variant): no decisions may be recorded at all.
        tot = sum(raw.get("total", {}).get(a, {}).get(r, 0) for a in bl.ALGORITHMS for r in bl.RESULTS) if raw else None
        checks.append(check("no_decisions_with_limiter_off", 0, tot,
                            explanation="decisions were recorded although the filter is disabled"))
        checks.append(check("all_requests_accepted_with_limiter_off", k6.get("requests"), k6.get("accepted"),
                            explanation="with the limiter off every request should return 200"))
        return finish(checks, notes)

    if raw and k6:
        rt = raw.get("total", {})
        exp = {"accepted": k6.get("accepted"), "rejected": k6.get("rejected")}
        obs = {"accepted": sum_results(rt, algs, ("allowed", "fail_open")), "rejected": sum_results(rt, algs, ("rejected",))}
        checks.append(check("k6_vs_raw_counters", exp, obs,
                            explanation="k6 responses and the instances' own decision counters differ "
                                        "(lost responses, timeouts or traffic from another client)"))
    if nginx is not None and raw:
        nb = bl.status_buckets(nginx.get("total", {}))
        checks.append(check("nginx_503_vs_fail_closed", nb["503"], sum_results(raw["total"], algs, ("fail_closed",)),
                            explanation="503s in the access log that are not fail-closed decisions (or vice versa)"))

    if raw and prom:
        checks.append(check("raw_vs_prometheus_total", {a: raw["total"][a] for a in bl.ALGORITHMS},
                            {a: prom["total"][a] for a in bl.ALGORITHMS},
                            explanation=prom_explanation(prom, cfg)))
        per_raw = {INSTANCE_OF[app]: raw["per_app"][app] for app in raw.get("per_app", {})}
        per_prom = {inst: prom["per_instance"].get(inst) for inst in per_raw}
        checks.append(check("raw_vs_prometheus_per_instance", per_raw, per_prom, explanation=prom_explanation(prom, cfg)))

    if raw:
        foreign = {a: raw["total"][a] for a in bl.ALGORITHMS if a not in algs and any(raw["total"][a].values())}
        checks.append(check("no_foreign_decisions", {}, foreign,
                            explanation="decisions for an algorithm this run does not use: another client (admin, "
                                        "probe) used the limiter between the idle snapshots"))

    stats = src.get("stats") or {}
    for policy_id, info in sorted(stats.items()):
        alg = info["algorithm"]
        body = info.get("body") or {}
        if not isinstance(body, dict) or "allowed" not in body:
            checks.append({"name": f"stats_vs_raw_policy_{policy_id}", "required": True, "status": "fail",
                           "expected": "a /stats body", "observed": info.get("status"),
                           "explanation": "the /stats API did not answer"})
            continue
        exp = {r: raw["total"][alg][r] for r in bl.RESULTS} if raw else None
        obs = {"allowed": body.get("allowed"), "rejected": body.get("rejected"), "fail_open": body.get("failOpen"),
               "fail_closed": body.get("failClosed")}
        expl = None
        if exp != obs:
            flushed = {i.get("instanceId"): i.get("lastFlushAt") for i in body.get("instances", [])}
            expl = (f"/stats differs from the instances' counters; last flush per instance: {flushed}. Causes: a "
                    f"flush still pending (each instance flushes every 5 s), a crashed instance (unflushed counts are "
                    f"lost by design), or counts flushed before the Redis reset.")
        checks.append(check(f"stats_vs_raw_policy_{policy_id}", exp, obs, explanation=expl))

    redis = src.get("redis")
    if redis and raw:
        failed = sum_results(raw["total"], bl.ALGORITHMS, ("fail_open", "fail_closed"))
        decided = sum_results(raw["total"], bl.ALGORITHMS, ("allowed", "rejected"))
        if failed:
            checks.append(na("redis_evalsha_vs_decisions",
                             f"{failed} requests failed open/closed: timed-out commands may still have executed",
                             required=False))
        else:
            checks.append(check("redis_evalsha_vs_decisions", decided, redis.get("evalsha_delta"), required=False,
                                explanation="EVALSHA calls differ from Redis-decided requests (NOSCRIPT retries after "
                                            "a script-cache loss use EVAL, so they are not counted here)"))
    return finish(checks, notes)


def prom_explanation(prom, cfg):
    stamps = prom.get("sample_timestamps_after", {})
    end = cfg.get("k6_end")
    late = {i: t for i, t in stamps.items() if end and t < end}
    if late:
        return f"Prometheus has no sample after the end of the run for {sorted(late)} (final scrape missing)"
    return "Prometheus and the instances disagree (a scrape failed or an instance restarted: counters reset)"


def finish(checks, notes):
    required = [c for c in checks if c["required"]]
    ok = all(c["status"] in ("pass", "n/a") for c in required) and bool(required)
    return {"ok": ok, "checks": checks, "notes": notes,
            "failed": [c["name"] for c in required if c["status"] == "fail"]}


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: reconcile.py <run-dir>")
    import pathlib
    run_dir = pathlib.Path(sys.argv[1])
    src = bl.read_json(run_dir / "sources.json")
    if src is None:
        sys.exit(f"no sources.json in {run_dir}")
    rep = reconcile(src)
    bl.write_json(run_dir / "reconcile.json", rep)
    print(("RECONCILED" if rep["ok"] else "NOT RECONCILED: " + ", ".join(rep["failed"])))


if __name__ == "__main__":
    main()
