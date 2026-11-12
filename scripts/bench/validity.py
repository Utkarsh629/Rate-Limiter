#!/usr/bin/env python3
"""Phase 3B run validity and correctness.

validity.json — the approved invalid-run rules, each with an explicit status (pass | fail | n/a) and reason:
  dropped_iterations   k6 dropped_iterations in the measure phase == 0
  achieved_rate        measure-phase requests >= 99 % of the target (rate × duration; burst/concurrency totals)
  k6_cpu               the k6 container's CPU <= 85 % of its 2-CPU limit in every 5-second window of the measure phase
  unexpected_statuses  measure-phase statuses other than 200/429 <= 0.1 %   (failure campaigns: an outcome, n/a)
  counts_reconcile     reconcile.json ok (decision O2)
  prometheus_complete  every app instance scraped for >= 90 % of the expected samples in the measure window
  warmup_separated     the measure scenario starts exactly when warm-up ends and both phases are reported apart
  redis_reset          FLUSHDB on the verified Compose Redis succeeded and left 0 keys
A run is VALID only when every required criterion passes. Missing data is a failure, never a pass.

correctness.json — B5 burst bound, B6 exactly 100/900, B10 per-user bounds (zero over-admission):
  token bucket   accepted <= C + floor(R * D) + 1           (C = 120, R = 2/s, D = first..last request seconds)
  fixed window   accepted <= L * windows touched            (L = 120, 60 s windows aligned to the epoch)
  sliding window accepted <= L * windows touched

Standalone: python3 scripts/bench/validity.py <run-dir>   (re-evaluates from the saved files)
"""
import math
import sys

import benchlib as bl

K6_CPU_MAX = 85.0
ACHIEVED_MIN = 0.99
UNEXPECTED_MAX = 0.001
SCRAPE_MIN = 0.90


def crit(name, status, value=None, threshold=None, reason=None, required=True):
    return {"name": name, "status": status, "value": value, "threshold": threshold, "reason": reason, "required": required}


def _count(summary, name):
    return bl.k6_metric(summary, name, "count")


def evaluate(meta, summary, docker, reconcile_rep, reset, prom_window):
    kind = meta["kind"]
    out = []
    if not summary or "metrics" not in summary:
        out.append(crit("k6_summary", "fail", reason="k6-summary.json missing or unreadable"))
        return finish(out)

    # dropped iterations (measure scenarios); k6 creates the metric only on the first drop
    if kind == "ramp":
        out.append(crit("dropped_iterations", "n/a", reason="ramp: drops are evaluated per step in the knee analysis", required=False))
    else:
        drops = []
        for s in meta["measure_scenarios"]:
            v = _count(summary, f"dropped_iterations{{scenario:{s}}}")
            drops.append(0 if v is None and "dropped_iterations" not in summary["metrics"] else v)
        if any(d is None for d in drops):
            out.append(crit("dropped_iterations", "fail", reason="dropped_iterations recorded but the measure sub-metric is missing"))
        else:
            total = sum(drops)
            out.append(crit("dropped_iterations", "pass" if total == 0 else "fail", total, "== 0",
                            None if total == 0 else f"k6 dropped {total} iterations: the load generator could not keep the arrival rate"))

    measured = _count(summary, "http_reqs{phase:measure}")
    expected = meta.get("expected_requests")
    if kind == "ramp":
        out.append(crit("achieved_rate", "n/a", reason="ramp: evaluated per step", required=False))
    elif measured is None or not expected:
        out.append(crit("achieved_rate", "fail", measured, expected, "measure-phase request count missing"))
    else:
        ratio = measured / expected
        need = 1.0 if kind == "concurrency" else ACHIEVED_MIN
        ok = ratio >= need
        out.append(crit("achieved_rate", "pass" if ok else "fail", round(ratio, 5), f">= {need}",
                        None if ok else f"{measured} of {expected} expected requests ({ratio:.2%})"))

    k6c = (docker or {}).get("k6", {})
    if kind == "concurrency":
        # B6: 1,000 VUs send one request each and the run lasts about a second, shorter than one 5-second window and
        # often shorter than the sampler's 1 s cadence; the load generator's sustained CPU has no bearing on the
        # closed-model correctness result (exactly 100 of 1,000), which correctness.json checks.
        out.append(crit("k6_cpu", "n/a", k6c.get("cpu_pct_of_limit_max_5s"), f"<= {K6_CPU_MAX}",
                        "single-shot closed-model test (~1 s): no 5-second window exists", required=False))
    elif kind == "ramp":
        out.append(crit("k6_cpu", "n/a", k6c.get("cpu_pct_of_limit_max_5s"), f"<= {K6_CPU_MAX}",
                        "ramp: evaluated per step", required=False))
    elif not k6c.get("samples"):
        out.append(crit("k6_cpu", "fail", None, f"<= {K6_CPU_MAX}", "no docker-stats samples for the k6 container"))
    else:
        v = k6c.get("cpu_pct_of_limit_max_5s")
        ok = v is not None and v <= K6_CPU_MAX
        out.append(crit("k6_cpu", "pass" if ok else "fail", None if v is None else round(v, 1), f"<= {K6_CPU_MAX} % of 2 CPUs",
                        None if ok else "the load generator was saturated (k6 CPU above 85 % in a 5-second window)"))

    if kind == "failure":
        out.append(crit("unexpected_statuses", "n/a", reason="failure campaign: non-200/429 responses are the measured outcome", required=False))
    else:
        unexpected = _count(summary, "unexpected{phase:measure}")
        if measured is None or unexpected is None:
            out.append(crit("unexpected_statuses", "fail", reason="unexpected or request count missing"))
        else:
            ratio = unexpected / measured if measured else 0.0
            ok = ratio <= UNEXPECTED_MAX
            out.append(crit("unexpected_statuses", "pass" if ok else "fail", round(ratio, 6), f"<= {UNEXPECTED_MAX}",
                            None if ok else f"{unexpected} responses were neither 200 nor 429"))

    if reconcile_rep is None:
        out.append(crit("counts_reconcile", "fail", reason="reconcile.json missing"))
    else:
        out.append(crit("counts_reconcile", "pass" if reconcile_rep["ok"] else "fail", reconcile_rep.get("failed"),
                        reason=None if reconcile_rep["ok"] else "failed checks: " + ", ".join(reconcile_rep.get("failed", []))))

    scrape = (prom_window or {}).get("scrape")
    if not scrape:
        out.append(crit("prometheus_complete", "fail", reason="no Prometheus scrape data for the window"))
    elif scrape["expected_per_instance"] < 3:
        # B5 burst / B6 concurrency: the measure window (~1 s) is shorter than three 5 s scrape intervals, so a
        # per-window sample count says nothing. Their counts are still reconciled exactly through Prometheus at the
        # idle instants before and after the run (counts_reconcile).
        out.append(crit("prometheus_complete", "n/a", scrape.get("samples"),
                        reason="measure window shorter than three scrape intervals; Prometheus counts are reconciled "
                               "at the idle instants instead", required=False))
    else:
        exp = scrape["expected_per_instance"]
        samples = scrape.get("samples", {})
        low = {i: n for i, n in samples.items() if n < SCRAPE_MIN * exp}
        missing = [i for i in ("app-1", "app-2", "app-3") if i not in samples]
        ok = not low and not missing
        out.append(crit("prometheus_complete", "pass" if ok else "fail", samples, f">= {SCRAPE_MIN:.0%} of {exp:.0f}",
                        None if ok else f"incomplete scrapes: low={low} missing={missing}"))

    if kind == "concurrency":
        out.append(crit("warmup_separated", "n/a", reason="concurrency test has no warm-up (closed model)", required=False))
    else:
        wu, ms = meta.get("warmup_s"), meta.get("measure_start_offset_s")
        warm_reqs = _count(summary, "http_reqs{phase:warmup}")
        ok = wu == ms and (wu == 0 or (warm_reqs or 0) > 0) and measured is not None
        out.append(crit("warmup_separated", "pass" if ok else "fail", {"warmup_s": wu, "measure_starts_at_s": ms,
                                                                        "warmup_requests": warm_reqs},
                        reason=None if ok else "the measure phase did not start exactly after the warm-up"))

    if not reset:
        out.append(crit("redis_reset", "fail", reason="no Redis reset record"))
    else:
        ok = reset.get("flushdb") == "OK" and reset.get("dbsize_after") == 0
        out.append(crit("redis_reset", "pass" if ok else "fail", reset, reason=None if ok else "Redis was not reset"))
    return finish(out)


def finish(criteria):
    required = [c for c in criteria if c["required"]]
    valid = bool(required) and all(c["status"] == "pass" for c in required)
    return {"valid": valid, "criteria": criteria,
            "reasons": [f"{c['name']}: {c['reason']}" for c in required if c["status"] != "pass"]}


# ------------------------------------------------------------------------------------------------ correctness

LIMIT, WINDOW_S, TB_CAPACITY, TB_RATE = 120, 60, 120, 2.0


def bound(algorithm, first, last, limit=LIMIT, window_s=WINDOW_S, capacity=TB_CAPACITY, rate=TB_RATE):
    """Maximum admissible requests for one fresh user whose requests span [first, last] (epoch seconds, logged with
    1 s resolution, so the duration is rounded UP by one second: the bound can only get looser, never stricter)."""
    if algorithm == "token_bucket":
        d = (last - first) + 1
        return capacity + math.floor(rate * d) + 1
    windows = math.floor(last / window_s) - math.floor(first / window_s) + 1
    return limit * windows


def per_user_correctness(algorithm, users):
    rows, over = [], []
    for uid, u in sorted(users.items()):
        if u["first"] is None:
            continue
        b = bound(algorithm, u["first"], u["last"])
        rows.append({"user": uid, "accepted": u["accepted"], "rejected": u["rejected"], "other": u["other"],
                     "first": u["first"], "last": u["last"], "bound": b, "over_admitted": max(0, u["accepted"] - b)})
        if u["accepted"] > b:
            over.append(uid)
    return {"users": len(rows), "over_admitted_users": over, "over_admitted_requests": sum(r["over_admitted"] for r in rows),
            "accepted_total": sum(r["accepted"] for r in rows), "bound_total": sum(r["bound"] for r in rows),
            "min_utilisation": min((r["accepted"] / r["bound"] for r in rows), default=None), "rows": rows}


def correctness(meta, summary, users):
    scen = meta["scenario"]
    if scen == "B6":
        acc = _count(summary, "accepted{phase:measure}")
        rej = _count(summary, "rejected{phase:measure}")
        unx = _count(summary, "unexpected{phase:measure}")
        ok = acc == 100 and rej == 900 and (unx or 0) == 0
        return {"check": "exactly 100 of 1,000 accepted across 3 instances", "pass": ok,
                "accepted": acc, "rejected": rej, "unexpected": unx}
    if scen in ("B5", "B10"):
        if not users:
            return {"check": "per-user bound", "pass": False, "reason": "no per-user data from the Nginx log"}
        if scen == "B5":
            users = {k: v for k, v in users.items() if k == "hot"}
        res = per_user_correctness(meta["algorithm"], users)
        res["check"] = "accepted <= algorithm bound for every user (zero over-admission)"
        res["pass"] = res["users"] > 0 and not res["over_admitted_users"]
        return res
    return None


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: validity.py <run-dir>")
    import pathlib
    d = pathlib.Path(sys.argv[1])
    meta = bl.read_json(d / "run-meta.json")
    rep = evaluate(meta, bl.read_json(d / "k6-summary.json"), bl.read_json(d / "docker-stats-summary.json"),
                   bl.read_json(d / "reconcile.json"), meta.get("redis_reset"), bl.read_json(d / "prometheus" / "window-summary.json"))
    bl.write_json(d / "validity.json", rep)
    print("VALID" if rep["valid"] else "INVALID: " + "; ".join(rep["reasons"]))


if __name__ == "__main__":
    main()
