#!/usr/bin/env bash
# Third follow-on queue: waits for queue2.sh, then re-runs the B3 campaign (its limiter-off half aborted on a driver
# bug in the JIT warm-up, fixed). Completed B3 configurations are skipped automatically.
cd "$(dirname "$0")/../.."
while pgrep -f "queue2.sh" >/dev/null || pgrep -f "queue.sh" >/dev/null || pgrep -f "campaign.py" >/dev/null; do sleep 30; done
[ -e benchmarks/campaigns/STOP ] || python3 -u scripts/bench/campaign.py b3 --reps 3 --max-attempts 5 >> benchmarks/campaigns/b3.console.log 2>&1
echo "queue3 done $(date)" >> benchmarks/campaigns/queue3.log
