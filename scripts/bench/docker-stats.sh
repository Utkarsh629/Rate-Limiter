#!/usr/bin/env bash
# Phase 3B benchmark infrastructure: samples CPU and memory of every running container about once per second
# (the streaming mode of `docker stats`) until it receives SIGTERM/SIGINT. Each output line is JSON:
#   {"ts": <epoch seconds when the sample was read>, "stats": {<docker stats --format '{{json .}}' fields>}}
# run.sh starts it before k6 and stops it after the cooldown; collect.py turns it into docker-stats.csv and
# per-container summaries (including the k6 container's worst 5-second CPU for the validity check).
#
#   scripts/bench/docker-stats.sh <output.jsonl>
set -euo pipefail
out=${1:?usage: docker-stats.sh <output.jsonl>}
exec python3 -u - "$out" <<'PY'
import json, re, signal, subprocess, sys, time

ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
proc = subprocess.Popen(["docker", "stats", "--format", "{{json .}}"], stdout=subprocess.PIPE,
                        stderr=subprocess.DEVNULL, text=True)

def stop(*_):
    proc.terminate()
    sys.exit(0)

signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
with open(sys.argv[1], "w") as f:
    for raw in proc.stdout:
        line = ANSI.sub("", raw).strip()
        if not line.startswith("{"):
            continue
        try:
            stats = json.loads(line)
        except ValueError:
            continue
        f.write(json.dumps({"ts": time.time(), "stats": stats}) + "\n")
        f.flush()
PY
