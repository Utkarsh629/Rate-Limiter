#!/usr/bin/env bash
# Second follow-on queue: waits for queue.sh (B6, B3, B9, F1–F5), then the MST confirmation at 9,000 RPS, the B4
# headroom top-ups and B9 at the knee (sequentially; STOP file honoured).
cd "$(dirname "$0")/../.."
while pgrep -f "queue.sh" >/dev/null || pgrep -f "campaign.py" >/dev/null; do sleep 30; done
for plan in knee b4 b9knee; do
  [ -e benchmarks/campaigns/STOP ] && break
  python3 -u scripts/bench/campaign.py "$plan" --reps 3 --max-attempts 5 >> "benchmarks/campaigns/$plan.console.log" 2>&1
done
echo "queue2 done $(date)" >> benchmarks/campaigns/queue2.log
