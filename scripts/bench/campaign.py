#!/usr/bin/env python3
"""Phase 3B campaign driver: runs a list of configurations through run.sh until each has the required number of
VALID repetitions, recording every attempt (valid or not) in benchmarks/campaigns/<campaign>/progress.jsonl.

  python3 scripts/bench/campaign.py <plan> [--reps 3] [--max-attempts 5]
  touch benchmarks/campaigns/STOP     # stops gracefully after the current run

Rules:
  * Configurations are taken from the approved scenario definitions (runner.SCENARIOS); nothing is invented here.
  * Existing valid runs in benchmarks/results count towards the target (the campaign resumes; nothing is rerun
    or overwritten). Invalid runs are never deleted; they stay in benchmarks/results with their reasons.
  * A configuration stops after `reps` valid runs, after `max_attempts` attempts, or after 3 consecutive invalid
    attempts that failed for load-generator reasons (k6 CPU / dropped iterations / achieved rate): further
    attempts cannot become valid on this machine; that is recorded, never hidden.
  * Stack variants (B3 limiter-off, B9 pool-n) are applied before their runs and reverted to default afterwards;
    every instance restart is followed by the 2-minute JIT warm-up (not a result).
"""
import argparse
import datetime
import json
import pathlib
import subprocess
import sys
import time

import benchlib as bl
import runner

CAMPAIGNS = bl.ROOT / "benchmarks" / "campaigns"
STOP = CAMPAIGNS / "STOP"
ALGS = bl.ALGORITHMS
LOADGEN = ("dropped_iterations", "achieved_rate", "k6_cpu")


def cfg(scenario, algorithm, rate, profile=None, variant="default", target="nginx", nonstandard=False, reps=None):
    return dict(scenario=scenario, algorithm=algorithm, rate=rate, profile=profile, variant=variant, target=target,
                nonstandard=nonstandard, reps=reps)


PLANS = {
    "validate": [cfg("B1", "token_bucket", 1000)],
    # 10,000 RPS was attempted 11 times (all invalid, recorded with reasons); by decision it is not retried on this
    # laptop. 7,500 RPS is the highest validated B1 level here (not a production capacity claim).
    "b1": [cfg("B1", a, r) for r in (1000, 2500, 5000, 7500) for a in ALGS],
    "b0": [cfg("B0", a, 100) for a in ALGS],
    "b6": [cfg("B6", "token_bucket", 1000)],
    "b10": [cfg("B10", a, 2000) for a in ALGS],
    "b5": [cfg("B5", a, 10000) for a in ALGS],
    "b4": [cfg("B4", a, 5000, profile=p) for p in ("headroom", "throttled") for a in ALGS],
    "b8": [cfg("B8", a, r) for r in (2000, 5000) for a in ALGS],
    "b7": [cfg("B7", a, 5000, target=t) for t in ("nginx", "direct") for a in ALGS],
    "b2": [cfg("B2", "token_bucket", 15000)],
    "b3": [cfg("B3", "token_bucket", r, variant=v) for r in (1000, 5000) for v in ("default", "limiter-off")],
    "b9": [cfg("B9", "token_bucket", r, variant=v) for r in (1000, 5000) for v in ("default", "pool-16")],
    "failures": [cfg(f, "fixed_window", 2000, reps=1) for f in ("F1", "F2", "F3", "F4", "F5")],
    # Maximum-sustainable-throughput confirmation (plan: B2 knee, then constant-rate B1-style runs). All three B2 ramps
    # put the knee at 11,000 RPS (k6-bound); by decision the constant-rate confirmation uses 9,000 RPS, between the
    # validated 7,500 and the invalid 10,000. Also covers "the other algorithms at the knee".
    "knee": [cfg("B1", a, 9000, nonstandard=True) for a in ALGS],
    # B9 "at the knee": the 9,000 RPS constant-rate confirmation produced no valid run (8 attempts, iterations dropped),
    # so the highest constant rate that sustains valid runs, 7,500 RPS, is used for the pooling comparison.
    "b9knee": [cfg("B9", "token_bucket", 7500, variant=v, nonstandard=True) for v in ("default", "pool-16")],
}
PLANS["benchmarks"] = PLANS["b6"] + PLANS["b5"] + PLANS["b10"] + PLANS["b0"] + PLANS["b1"] + PLANS["b4"] + PLANS["b8"] + PLANS["b7"] + PLANS["b2"]


def key(c):
    return (c["scenario"], c["algorithm"], c["rate"], c["profile"] or runner.SCENARIOS[c["scenario"]]["profiles"][0], c["variant"], c["target"])


def existing_runs():
    out = {}
    for meta_path in sorted((bl.ROOT / "benchmarks" / "results").glob("*/run-meta.json")):
        m = bl.read_json(meta_path)
        if not m or m.get("dry_run"):
            continue
        k = (m["scenario"], m["algorithm"], m["rate"], m["profile"], m["variant"], m["target"])
        out.setdefault(k, []).append(m)
    return out


def current_variant():
    env = runner.app_env("app1", ["RATELIMITER_FILTER_ENABLED", "REDIS_POOL_ENABLED", "REDIS_POOL_MAX_ACTIVE"])
    for name, v in runner.VARIANTS.items():
        if all(env.get(k) == val for k, val in v["expect"].items()):
            return name
    return "unknown"


def log(progress, **rec):
    rec["at"] = datetime.datetime.now().isoformat(timespec="seconds")
    with open(progress, "a") as f:
        f.write(json.dumps(rec) + "\n")
    print(f"[{rec['at']}] " + " ".join(f"{k}={v}" for k, v in rec.items() if k != "at"), flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("plan", choices=sorted(PLANS))
    ap.add_argument("--reps", type=int, default=3)
    ap.add_argument("--max-attempts", type=int, default=5)
    ap.add_argument("--no-jit", action="store_true", help="skip the initial JIT warm-up (stack already warm)")
    ap.add_argument("--count-complete", action="store_true",
                    help="count every completed run (valid or not) towards the target; used for F1–F5, whose deliverable "
                         "is the timeline (decision: no failure-campaign retries)")
    a = ap.parse_args()
    cid = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + f"-{a.plan}"
    progress = CAMPAIGNS / cid / "progress.jsonl"
    progress.parent.mkdir(parents=True, exist_ok=True)
    log(progress, event="campaign_start", plan=a.plan, configs=len(PLANS[a.plan]), reps=a.reps)
    variant = current_variant()
    if variant != "default":
        runner.apply_variant("default")
        variant = "default"
        runner.jit_warmup()
    elif not a.no_jit:
        runner.jit_warmup()
    for c in PLANS[a.plan]:
        if STOP.exists():
            log(progress, event="stopped", reason="STOP file present")
            break
        k = key(c)
        need = c["reps"] or a.reps
        runs = existing_runs().get(k, [])
        valid = [m for m in runs if m.get("valid") or (a.count_complete and m.get("status") == "complete")]
        if len(valid) >= need:
            log(progress, event="skip", config=list(k), valid=len(valid), reason="already has enough valid runs")
            continue
        if c["variant"] != variant:
            runner.apply_variant(c["variant"])
            variant = c["variant"]
            runner.jit_warmup()
        attempts, loadgen_streak = 0, 0
        while len(valid) < need and attempts < a.max_attempts and loadgen_streak < 3 and not STOP.exists():
            attempts += 1
            rep = len(runs) + 1
            args = ["scripts/bench/run.sh", "run", "--scenario", c["scenario"], "--algorithm", c["algorithm"], "--rate", str(c["rate"]),
                    "--rep", str(rep), "--variant", c["variant"], "--target", c["target"]]
            if c["profile"]:
                args += ["--profile", c["profile"]]
            if c["nonstandard"]:
                args.append("--nonstandard-rate")
            t = time.time()
            p = subprocess.run(args, cwd=bl.ROOT, capture_output=True, text=True)
            result = [l for l in (p.stdout + p.stderr).splitlines() if l.startswith(("RESULT", "REFUSED"))]
            runs = existing_runs().get(k, [])
            valid = [m for m in runs if m.get("valid") or (a.count_complete and m.get("status") == "complete")]
            last = runs[-1] if runs else {}
            reasons = last.get("invalid_reasons", [])
            loadgen_streak = loadgen_streak + 1 if reasons and all(r.split(":")[0] in LOADGEN for r in reasons) else 0
            log(progress, event="run", config=list(k), attempt=attempts, rc=p.returncode, seconds=round(time.time() - t),
                result=result[-1] if result else (p.stdout + p.stderr)[-300:], valid_so_far=len(valid))
            if p.returncode not in (0,) and not result:
                (progress.parent / f"error-{len(runs)}.log").write_text(p.stdout + p.stderr)
            if p.returncode == 2 and "REFUSED" in "".join(result):
                time.sleep(30)                       # e.g. stack still recovering; the next attempt re-checks
        log(progress, event="config_done", config=list(k), valid=len(valid), attempts=attempts,
            outcome="complete" if len(valid) >= need else "incomplete: see the invalid runs' reasons")
    if variant != "default":
        runner.apply_variant("default")
    log(progress, event="campaign_end")


if __name__ == "__main__":
    sys.path.insert(0, str(pathlib.Path(__file__).parent))
    main()
