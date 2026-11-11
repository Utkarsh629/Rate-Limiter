#!/usr/bin/env python3
"""Generates infra/grafana/dashboards/ratelimiter.json (provisioned into Grafana).

Panels whose title starts with a number 1-8 are the eight required panels (Phase 3A plan, 3A.4);
the smoke test checks that each of them returns data. Every query uses literal [1m] ranges so the
smoke test can run it directly against Prometheus.
"""
import json
import pathlib

DS = {"type": "prometheus", "uid": "prometheus"}
Q = lambda q: [(0.5, "p50"), (0.95, "p95"), (0.99, "p99")]  # noqa: E731


def quantiles(metric, selector=""):
    sel = "{" + selector + "}" if selector else ""
    return [(f'histogram_quantile({q}, sum by (le) (rate({metric}_bucket{sel}[1m])))', label) for q, label in Q(None)]


PANELS = [
    ("1. Incoming requests per second (rate-limit decisions)", "reqps", [
        ("sum(rate(ratelimiter_requests_total[1m]))", "total"),
        ("sum by (instance) (rate(ratelimiter_requests_total[1m]))", "{{instance}}")]),
    ("2. Accepted vs rejected", "reqps", [
        ("sum by (result) (rate(ratelimiter_requests_total[1m]))", "{{result}}")]),
    ("3. Latency P50 / P95 / P99: rate-limiter overhead and server time", "s",
        [(q, "limiter " + l) for q, l in quantiles("ratelimiter_request_duration_seconds")] +
        [(q, "server " + l) for q, l in quantiles("http_server_requests_seconds", 'uri!~"/actuator.*"')]),
    ("4. Redis operation latency", "s",
        [(q, "client " + l) for q, l in quantiles("ratelimiter_redis_duration_seconds", 'outcome="success"')] +
        [('sum(rate(redis_commands_duration_seconds_total{cmd="evalsha"}[1m])) / sum(rate(redis_commands_total{cmd="evalsha"}[1m]))',
          "server avg EVALSHA")]),
    ("5. Redis failures and circuit breaker", "short", [
        ("sum by (type) (rate(ratelimiter_redis_errors_total[1m]))", "errors/s {{type}}"),
        ('max by (instance, state) (resilience4j_circuitbreaker_state{state=~"closed|open|half_open"})', "{{instance}} {{state}}"),
        ("sum by (from, to) (increase(ratelimiter_circuit_breaker_transitions_total[5m]))", "{{from}} -> {{to}}")]),
    ("6. Algorithm-wise distribution", "reqps", [
        ("sum by (algorithm) (rate(ratelimiter_algorithm_executions_total[1m]))", "{{algorithm}}")]),
    ("7. Backend instance health", "short", [
        ('up{job="app"}', "{{instance}} up"),
        ('up{job="redis"}', "redis exporter up"),
        ("redis_up", "redis up")]),
    ("8. CPU and memory", "short", [
        ('process_cpu_usage{job="app"}', "{{instance}} CPU (0-1)"),
        ('sum by (instance) (jvm_memory_used_bytes{area="heap"}) / 1048576', "{{instance}} heap MiB"),
        ("redis_memory_used_bytes / 1048576", "redis memory MiB"),
        ("rate(redis_cpu_sys_seconds_total[1m]) + rate(redis_cpu_user_seconds_total[1m])", "redis CPU (cores)")]),
    ("Tomcat busy threads", "short", [("tomcat_threads_busy_threads", "{{instance}}")]),
    ("JVM GC pause time per second", "s", [("sum by (instance) (rate(jvm_gc_pause_seconds_sum[1m]))", "{{instance}}")]),
    ("Redis commands per second and connected clients", "short", [
        ("rate(redis_commands_processed_total[1m])", "commands/s"),
        ("redis_connected_clients", "clients")]),
    ("Redis keys", "short", [("sum(redis_db_keys)", "keys")]),
    ("Hikari active connections (policy database)", "short", [("hikaricp_connections_active", "{{instance}}")]),
    ("Statistics flushes", "short", [("sum by (instance, result) (increase(ratelimiter_stats_flushes_total[5m]))", "{{instance}} {{result}}")]),
]


def panel(i, title, unit, targets):
    return {
        "id": i + 1, "type": "timeseries", "title": title, "datasource": DS,
        "gridPos": {"h": 8, "w": 12, "x": (i % 2) * 12, "y": (i // 2) * 8},
        "fieldConfig": {"defaults": {"unit": unit}, "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}},
        "targets": [{"refId": chr(65 + j), "datasource": DS, "expr": expr, "legendFormat": legend}
                    for j, (expr, legend) in enumerate(targets)],
    }


dashboard = {
    "uid": "ratelimiter", "title": "Distributed Rate Limiter", "tags": ["ratelimiter"],
    "timezone": "browser", "schemaVersion": 39, "version": 1, "refresh": "5s",
    "time": {"from": "now-15m", "to": "now"},
    "panels": [panel(i, *p) for i, p in enumerate(PANELS)],
}

out = pathlib.Path(__file__).resolve().parents[2] / "infra/grafana/dashboards/ratelimiter.json"
out.write_text(json.dumps(dashboard, indent=2) + "\n")
print(f"wrote {out} ({len(PANELS)} panels)")
