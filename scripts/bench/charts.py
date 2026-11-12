#!/usr/bin/env python3
"""Dependency-free SVG charts for the README and docs, generated ONLY from saved benchmark runs (decision O1: no
plotting dependency). Palette: reference categorical slots 1–3, validated (light surface #fcfcfb; the aqua slot
has < 3:1 contrast, so every series is also direct-labelled). Each chart: one y-axis per panel (small multiples),
2 px lines, >= 8 px markers, recessive grid, native <title> tooltips per point.

  python3 scripts/bench/charts.py [--out docs/assets]
"""
import argparse
import csv
import pathlib
import statistics
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import benchlib as bl  # noqa: E402
import report  # noqa: E402

SURFACE, INK, INK2, GRID, AXIS = "#fcfcfb", "#0b0b0b", "#52514e", "#e6e5e1", "#b9b8b2"
SERIES = {"token_bucket": "#2a78d6", "fixed_window": "#eb6834", "sliding_window": "#1baf7a"}
NAMES = {"token_bucket": "Token bucket", "fixed_window": "Fixed window", "sliding_window": "Sliding window"}
FONT = "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif"


def esc(s):
    return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


class Panel:
    def __init__(self, x, y, w, h, xmin, xmax, ymin, ymax, title, ylabel, xlabel, xticks, yticks):
        self.x, self.y, self.w, self.h = x, y, w, h
        self.xmin, self.xmax, self.ymin, self.ymax = xmin, xmax, ymin, ymax
        self.title, self.ylabel, self.xlabel, self.xticks, self.yticks = title, ylabel, xlabel, xticks, yticks
        self.parts = []

    def px(self, v):
        return self.x + (v - self.xmin) / (self.xmax - self.xmin) * self.w

    def py(self, v):
        return self.y + self.h - (v - self.ymin) / (self.ymax - self.ymin) * self.h

    def frame(self, fmt_x=lambda v: f"{v:,.0f}", fmt_y=lambda v: f"{v:,.0f}" if v >= 100 else f"{v:g}"):
        p = self.parts
        p.append(f'<text x="{self.x}" y="{self.y - 26}" style="{FONT};font-size:14px;font-weight:600" fill="{INK}">{esc(self.title)}</text>')
        p.append(f'<text x="{self.x}" y="{self.y - 10}" style="{FONT};font-size:11px" fill="{INK2}">{esc(self.ylabel)}</text>')
        for t in self.yticks:
            yy = self.py(t)
            p.append(f'<line x1="{self.x}" y1="{yy:.1f}" x2="{self.x + self.w}" y2="{yy:.1f}" stroke="{GRID}" stroke-width="1"/>')
            p.append(f'<text x="{self.x - 6}" y="{yy + 4:.1f}" text-anchor="end" style="{FONT};font-size:11px" fill="{INK2}">{esc(fmt_y(t))}</text>')
        p.append(f'<line x1="{self.x}" y1="{self.y + self.h}" x2="{self.x + self.w}" y2="{self.y + self.h}" stroke="{AXIS}" stroke-width="1"/>')
        for t in self.xticks:
            xx = self.px(t)
            p.append(f'<text x="{xx:.1f}" y="{self.y + self.h + 16}" text-anchor="middle" style="{FONT};font-size:11px" fill="{INK2}">{esc(fmt_x(t))}</text>')
        p.append(f'<text x="{self.x + self.w / 2}" y="{self.y + self.h + 34}" text-anchor="middle" style="{FONT};font-size:11px" fill="{INK2}">{esc(self.xlabel)}</text>')

    def hline(self, v, label, below=False):
        yy = self.py(v)
        self.parts.append(f'<line x1="{self.x}" y1="{yy:.1f}" x2="{self.x + self.w}" y2="{yy:.1f}" stroke="{INK2}" stroke-width="1" stroke-dasharray="4 3"/>')
        ty = yy + 15 if below else yy - 5
        self.parts.append(f'<text x="{self.x + 6}" y="{ty:.1f}" style="{FONT};font-size:11px" fill="{INK2}">{esc(label)}</text>')

    def band(self, x0, x1, label):
        a, b = self.px(x0), self.px(x1)
        self.parts.append(f'<rect x="{a:.1f}" y="{self.y}" width="{b - a:.1f}" height="{self.h}" fill="#efeeea"/>')
        self.parts.append(f'<text x="{(a + b) / 2:.1f}" y="{self.y + 13}" text-anchor="middle" style="{FONT};font-size:11px" fill="{INK2}">{esc(label)}</text>')

    def line(self, pts, color, label, tip, label_dy=0):
        pts = [(x, y) for x, y in pts if y is not None]
        if not pts:
            return
        d = " ".join(f"{'M' if i == 0 else 'L'}{self.px(x):.1f},{self.py(y):.1f}" for i, (x, y) in enumerate(pts))
        self.parts.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>')
        dense = len(pts) > 20                      # dense series: no visible markers, invisible hover targets
        for x, y in pts:
            if dense:
                self.parts.append(f'<circle cx="{self.px(x):.1f}" cy="{self.py(y):.1f}" r="5" fill="transparent">'
                                  f'<title>{esc(tip(x, y))}</title></circle>')
            else:
                self.parts.append(f'<circle cx="{self.px(x):.1f}" cy="{self.py(y):.1f}" r="4" fill="{color}" stroke="{SURFACE}" stroke-width="2">'
                                  f'<title>{esc(tip(x, y))}</title></circle>')
        lx, ly = pts[-1]
        self.parts.append(f'<text x="{self.px(lx) + 8:.1f}" y="{self.py(ly) + 4 + label_dy:.1f}" style="{FONT};font-size:11px;font-weight:600" fill="{INK}">{esc(label)}</text>')

    def svg(self):
        return "\n".join(self.parts)


def document(width, height, title, desc, panels, note):
    body = "\n".join(p.svg() for p in panels)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" aria-labelledby="t d">\n'
            f'<title id="t">{esc(title)}</title><desc id="d">{esc(desc)}</desc>\n'
            f'<rect width="{width}" height="{height}" fill="{SURFACE}"/>\n{body}\n'
            f'<text x="16" y="{height - 10}" style="{FONT};font-size:10px" fill="{INK2}">{esc(note)}</text>\n</svg>\n')


def nice_max(v):
    """Smallest 1 / 2 / 2.5 / 5 × 10^k that is >= v, so the 4 grid steps are round numbers."""
    import math
    k = 10 ** math.floor(math.log10(v)) if v > 0 else 1
    for m in (1, 2, 2.5, 5, 10):
        if v <= m * k:
            return m * k
    return 10 * k


def b1_chart(groups):
    rates = [1000, 2500, 5000, 7500]
    data = {}
    for alg in bl.ALGORITHMS:
        rows = []
        for r in rates:
            g = groups.get(("B1", alg, r, "mixed", "default", "nginx"))
            if not g or len(g["valid"]) < 3:
                rows.append((r, None, None))
                continue
            ms = [report.run_metrics(m) for m in g["valid"]]
            rows.append((r, statistics.median(x["e2e_p99"] for x in ms), statistics.median(x["ovh_p99"] for x in ms)))
        data[alg] = rows
    e2e_max = nice_max(max(y for rows in data.values() for _, y, _ in rows if y) * 1.1)
    ovh_max = nice_max(max(z for rows in data.values() for _, _, z in rows if z) * 1.1)
    p1 = Panel(70, 60, 300, 210, 1000, 7500, 0, e2e_max, "End-to-end P99 (k6)", "milliseconds", "requests per second",
               rates, [e2e_max * i / 4 for i in range(5)])
    p2 = Panel(480, 60, 300, 210, 1000, 7500, 0, ovh_max, "Rate-limiter overhead P99 (in-process)", "milliseconds",
               "requests per second", rates, [ovh_max * i / 4 for i in range(5)])
    p1.frame()
    p2.frame()
    offsets = {"token_bucket": 6, "fixed_window": 0, "sliding_window": -6}
    for alg, rows in data.items():
        p1.line([(r, y) for r, y, _ in rows], SERIES[alg], NAMES[alg],
                lambda x, y, a=alg: f"{NAMES[a]}, {x:,.0f} RPS: E2E P99 {y:.1f} ms (median of 3 valid runs)", offsets[alg])
        p2.line([(r, z) for r, _, z in rows], SERIES[alg], NAMES[alg],
                lambda x, y, a=alg: f"{NAMES[a]}, {x:,.0f} RPS: limiter P99 {y:.2f} ms (median of 3 valid runs)", offsets[alg])
    return document(900, 330, "B1 latency by load level", "Median of 3 valid runs per point; P99 end-to-end latency and P99 in-process limiter overhead for three algorithms at 1,000 to 7,500 requests per second.",
                    [p1, p2], "Median of 3 valid runs per point. Apple M4 laptop, Docker Desktop, all services and k6 on one machine. Source: benchmarks/results (B1).")


def b2_chart(runs):
    ramps = [m for m in runs if m["scenario"] == "B2" and m.get("valid")]
    per_step = {}
    for m in ramps:
        s = m["_summary"]
        steps = bl.read_json(m["_dir"] / "prometheus" / "ramp-steps.json") or {}
        rows = list(csv.DictReader(open(m["_dir"] / "docker-stats.csv"))) if (m["_dir"] / "docker-stats.csv").exists() else []
        step_s = int(m["k6_env"]["STEP_S"])
        for rate_s, st in steps.items():
            rate = int(rate_s)
            n = bl.k6_metric(s, f"http_reqs{{step:{rate}}}") or 0
            pts = sorted((float(r["ts"]), float(r["cpu_pct"]) / bl.K6_CPU_LIMIT) for r in rows if "-k6-" in r["name"] and st["start"] <= float(r["ts"]) <= st["end"])
            per_step.setdefault(rate, {"ach": [], "cpu": []})
            per_step[rate]["ach"].append(100 * n / (rate * step_s))
            cpu = bl.rolling_max_mean(pts, 5.0)
            if cpu is not None:
                per_step[rate]["cpu"].append(cpu)
    rates = sorted(per_step)
    ach = [(r, statistics.median(per_step[r]["ach"])) for r in rates]
    cpu = [(r, statistics.median(per_step[r]["cpu"]) if per_step[r]["cpu"] else None) for r in rates]
    p1 = Panel(70, 60, 300, 210, 1000, 15000, 50, 105, "Achieved / target rate per step", "percent", "target requests per second",
               [1000, 5000, 10000, 15000], [50, 60, 70, 80, 90, 100])
    p2 = Panel(480, 60, 300, 210, 1000, 15000, 0, 100, "k6 load-generator CPU per step", "percent of its 2-CPU limit (worst 5 s)",
               "target requests per second", [1000, 5000, 10000, 15000], [0, 25, 50, 75, 100])
    p1.frame()
    p2.frame()
    p1.hline(99, "99 % validity threshold", below=True)
    p2.hline(85, "85 % validity threshold")
    p1.line(ach, SERIES["token_bucket"], "Token bucket", lambda x, y: f"{x:,.0f} RPS step: {y:.1f} % of target achieved (median of 3 ramps)")
    p2.line(cpu, SERIES["token_bucket"], "Token bucket", lambda x, y: f"{x:,.0f} RPS step: k6 at {y:.0f} % of its CPU limit (median of 3 ramps)")
    return document(900, 330, "B2 stress ramp", "Median of three valid ramps from 1,000 to 15,000 requests per second in 30-second steps: share of the target rate achieved, and k6 CPU.",
                    [p1, p2], "Knee: 11,000 RPS in all 3 ramps, set by the load generator (k6 CPU), not a sustainable-throughput claim. Source: benchmarks/results (B2).")


def f1_chart(runs):
    m = next((r for r in runs if r["scenario"] == "F1" and r.get("status") == "complete"), None)
    if m is None:
        return None
    tl = (bl.read_json(m["_dir"] / "nginx-summary.json") or {}).get("timeline", {})
    faults = m["faults"]
    f0 = min(f["at"] for f in faults)
    f_end = max(f["done"] for f in faults)
    secs = [s for s in sorted(tl, key=int) if -10 <= int(s) - f0 <= 40]
    series = {}
    for sec in secs:
        t = int(sec) - int(f0)
        for path, key in (("/api/v1/demo/profile", "prof"), ("/api/v1/demo/enterprise", "ent")):
            c = tl[sec].get(path, {})
            for st in ("200", "429", "503"):
                series.setdefault((key, st), []).append((t, c.get(st, 0)))
    pmax = nice_max(max(v for (k, _), pts in series.items() if k == "prof" for _, v in pts) * 1.1)
    emax = nice_max(max(v for (k, _), pts in series.items() if k == "ent" for _, v in pts) * 1.1)
    ticks = [-10, 0, 10, 20, 30, 40]
    p1 = Panel(70, 60, 300, 210, -10, 40, 0, pmax, "Fail-open endpoint (/profile)", "responses per second", "seconds from the Redis pause",
               ticks, [pmax * i / 4 for i in range(5)])
    p2 = Panel(480, 60, 300, 210, -10, 40, 0, emax, "Fail-closed endpoint (/enterprise)", "responses per second", "seconds from the Redis pause",
               ticks, [emax * i / 4 for i in range(5)])
    for p in (p1, p2):
        p.band(0, f_end - f0, "Redis paused")
        p.frame(fmt_x=lambda v: f"{v:+.0f}" if v else "0")
    colors = {"200": "#2a78d6", "429": "#eb6834", "503": "#1baf7a"}
    labels = {"200": "200 OK", "429": "429 throttled", "503": "503 fail-closed"}
    for st in ("200", "429"):
        p1.line(series[("prof", st)], colors[st], labels[st], lambda x, y, s=st: f"{x:+d} s: {y} × {s}")
    for st in ("200", "503"):
        p2.line(series[("ent", st)], colors[st], labels[st], lambda x, y, s=st: f"{x:+d} s: {y} × {s}", -8 if st == "503" else 6)
    return document(900, 330, "F1 Redis pause timeline", "Responses per second on the fail-open and fail-closed endpoints from 10 s before to 40 s after a 20-second Redis pause at 2,000 RPS.",
                    [p1, p2], f"Run {m['run_id']}. No 429 while Redis was down: fail-open answered 200 without limits, fail-closed answered 503. Source: Nginx access log.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(bl.ROOT / "docs" / "assets"))
    a = ap.parse_args()
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    runs = report.load_runs()
    groups = report.group(runs)
    for name, svg in (("b1-latency.svg", b1_chart(groups)), ("b2-knee.svg", b2_chart(runs)), ("f1-timeline.svg", f1_chart(runs))):
        if svg:
            (out / name).write_text(svg)
            print("wrote", out / name)


if __name__ == "__main__":
    main()
