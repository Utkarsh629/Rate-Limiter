#!/usr/bin/env bash
# Runs the follow-on campaigns after the main B0–B10 campaign process exits (sequentially; STOP file honoured).
cd "$(dirname "$0")/../.."
while pgrep -f "campaign.py benchmarks" >/dev/null; do sleep 30; done
for plan in b6 b3 b9 failures; do
  [ -e benchmarks/campaigns/STOP ] && break
  python3 -u scripts/bench/campaign.py "$plan" --reps 3 --max-attempts 5 >> "benchmarks/campaigns/$plan.console.log" 2>&1
done
echo "queue done $(date)" >> benchmarks/campaigns/queue.log
