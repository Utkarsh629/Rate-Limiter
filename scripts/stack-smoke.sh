#!/usr/bin/env bash
# Phase 3A smoke test of the full Docker Compose stack. See scripts/smoke/stack_smoke.py.
#   scripts/stack-smoke.sh            # against the current stack (brought up if needed)
#   scripts/stack-smoke.sh --clean    # from a clean state: deletes this project's containers and volumes first
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "Missing .env (copy .env.example and set every change-me value)"; exit 1; }
exec python3 scripts/smoke/stack_smoke.py "$@"
