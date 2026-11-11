#!/usr/bin/env bash
# Builds and starts the whole stack, waiting until every service is healthy.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "Missing .env (copy .env.example and set every change-me value)"; exit 1; }
docker compose up -d --build --wait
docker compose ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}'
echo "API: http://localhost:${NGINX_HOST_PORT:-8080}   Grafana: http://localhost:3000   Prometheus: http://localhost:9090"
