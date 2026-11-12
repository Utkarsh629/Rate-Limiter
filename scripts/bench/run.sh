#!/usr/bin/env bash
# Phase 3B benchmark runner entry point. Executes ONE benchmark or failure run end to end, or a helper command.
# The orchestration lives in scripts/bench/runner.py (Python stdlib); see its docstring for the full sequence.
#
#   scripts/bench/run.sh run --scenario B1 --algorithm token_bucket --rate 1000 --rep 1 [--dry-run]
#   scripts/bench/run.sh run --scenario B4 --algorithm fixed_window --rate 5000 --profile throttled --rep 1
#   scripts/bench/run.sh run --scenario B7 --algorithm sliding_window --rate 5000 --target direct --rep 1
#   scripts/bench/run.sh run --scenario B3 --algorithm token_bucket --rate 1000 --variant limiter-off --rep 1
#   scripts/bench/run.sh run --scenario F1 --algorithm fixed_window --rate 2000 --rep 1
#   scripts/bench/run.sh variant <default|limiter-off|pool-8|pool-16|pool-32>   # recreate app1-3 with a stack variant
#   scripts/bench/run.sh redis-safety                                            # identity + safety verdict, read-only
#   scripts/bench/run.sh jit-warmup                                               # 2 min at 1,000 RPS (not a result)
#
# Results: benchmarks/results/<UTC time>-<scenario>-<algorithm>-<rps>[-profile][-variant][-target]-r<n>/
# Dry runs: benchmarks/dry-runs/<run-id>/ (no Redis reset, `k6 inspect` instead of `k6 run`: no request is sent).
set -euo pipefail
cd "$(dirname "$0")/../.."
[ -f .env ] || { echo "Missing .env" >&2; exit 2; }
command -v docker >/dev/null || { echo "docker not found" >&2; exit 2; }
exec python3 -u scripts/bench/runner.py "$@"
