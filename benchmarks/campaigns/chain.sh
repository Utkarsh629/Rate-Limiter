#!/usr/bin/env bash
# Resume chain (replaces queue2.sh/queue3.sh): waits until the stopped failure campaign has finished its current run,
# clears STOP, then: F1–F5 with one completed run each (no retries, by decision), the 9,000 RPS confirmation,
# B4 top-ups, B9 at 9,000 RPS, and the B3 re-run.
cd "$(dirname "$0")/../.."
while pgrep -f "campaign.py" >/dev/null || pgrep -f "queue.sh" >/dev/null; do sleep 15; done
rm -f benchmarks/campaigns/STOP
python3 -u scripts/bench/campaign.py failures --reps 1 --max-attempts 1 --count-complete --no-jit >> benchmarks/campaigns/failures.console.log 2>&1
for plan in knee b4 b9knee b3; do
  [ -e benchmarks/campaigns/STOP ] && break
  python3 -u scripts/bench/campaign.py "$plan" --reps 3 --max-attempts 5 >> "benchmarks/campaigns/$plan.console.log" 2>&1
done
echo "chain done $(date)" >> benchmarks/campaigns/chain.log
