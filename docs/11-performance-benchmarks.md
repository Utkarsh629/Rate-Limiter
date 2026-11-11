# 11 — Performance Benchmarks

> Status: **methodology fixed; no measurements yet (Phase 8).** No number appears here until it exists as a raw file in `benchmarks/results/`.

## Methodology rules (approved)
- **Throughput tests use k6 `constant-arrival-rate`** (open model). A closed model hides tail latency through coordinated omission.
- **Correctness and performance are reported separately.** Correctness = exact accepted/rejected counts. Performance = latency and throughput.
- **Limiter overhead is measured in-process** (Micrometer timer around the decision only), separately from **end-to-end HTTP latency** (k6). Redis script latency is a third, separate timer.
- **HTTP 429 is an expected outcome**, never counted as an error. The error rate is non-429 failures only.
- Targets and measured results are in separate columns. A missed target is reported as missed, with analysis.

## Same-machine limitation
The load generator, Nginx, three JVMs, Redis, MySQL, Prometheus and Grafana all run on one laptop
(Apple M4, 10 cores, 16 GB RAM; Docker Desktop VM: 10 CPUs, ~8 GB). Consequences:
- k6 competes with the system under test for CPU, so end-to-end throughput is a lower bound for the design and an upper bound for this laptop.
- The containers share one kernel and one network stack inside the Docker VM, so network latency is far below a real deployment.
- Host-to-container port forwarding on macOS adds overhead; k6 runs inside the Compose network to avoid it, which in turn puts it on the same CPUs.
- These results do not predict production capacity. They show relative differences (algorithms, pool vs no pool) and limiter overhead.

## Planned scenarios
Baseline 100 RPS · 1,000 RPS · 5,000 RPS · stress ramp to 10,000 RPS · burst 10,000 requests · 1,000 simultaneous · 3-instance distributed · Redis failure during load. Each runs for all three algorithms, with two key distributions (10k uniform users; one hot user).

## Environment record (to fill per run)
Hardware, OS, Docker resources, Java version and JVM flags, Redis config, instance count, k6 version, duration, RPS, avg/P50/P95/P99/max, error rate, CPU and memory per container, Redis memory.
