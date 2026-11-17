#!/usr/bin/env python3
"""Measure a repeatable local trajectory workload.

This is a development harness for the paper-first implementation.  It
generates deterministic synthetic wells, computes minimum-curvature stations,
and optionally scans a bounded number of pairs with the production
``scan_pair`` routine.  It records timings and workload sizes; it does not
claim the acceptance targets in the requirements document because those need
the agreed Windows hardware, real data and a 72-hour run.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.domain.evaluation import scan_pair  # noqa: E402
from app.domain.min_curvature import compute_minimum_curvature  # noqa: E402


def synthetic_rows(well_index: int, points: int, interval: float) -> list[tuple[float, float, float]]:
    """Create a gently turning, deterministic survey for one synthetic well."""
    rows: list[tuple[float, float, float]] = []
    for index in range(points):
        md = index * interval
        inclination = 2.0 + 0.006 * index + 0.02 * well_index
        azimuth = (80.0 + 0.015 * index + 0.1 * well_index) % 360.0
        rows.append((md, inclination, azimuth))
    return rows


def run(well_count: int, points_per_well: int, scan_pairs: int, step: float) -> dict[str, object]:
    if well_count < 1 or points_per_well < 2 or scan_pairs < 0 or step <= 0:
        raise ValueError("well_count>=1, points_per_well>=2, scan_pairs>=0 and step>0 are required")
    interval = 1000.0 / (points_per_well - 1)
    started = time.perf_counter()
    trajectories = [
        compute_minimum_curvature(synthetic_rows(index, points_per_well, interval), origin=(0.0, index * 12.0, 0.0))
        for index in range(well_count)
    ]
    trajectory_seconds = time.perf_counter() - started
    actual_pairs = min(scan_pairs, max(0, well_count - 1))
    scan_started = time.perf_counter()
    scans = []
    for index in range(1, actual_pairs + 1):
        result = scan_pair(trajectories[0], trajectories[index], step=step)
        scans.append({
            "neighbor_index": index,
            "min_distance_m": result.min_distance,
            "operating_sf": result.operating_sf,
            "operating_risk_level": result.operating_risk_level,
        })
    scan_seconds = time.perf_counter() - scan_started
    return {
        "workload": {
            "well_count": well_count,
            "points_per_well": points_per_well,
            "total_points": well_count * points_per_well,
            "scan_pairs_requested": scan_pairs,
            "scan_pairs_completed": actual_pairs,
            "scan_step_m": step,
        },
        "timing_seconds": {
            "minimum_curvature_all_wells": trajectory_seconds,
            "scan_pairs": scan_seconds,
            "total": trajectory_seconds + scan_seconds,
        },
        "scans": scans,
        "assessment": "LOCAL_DEVELOPMENT_WORKLOAD_ONLY",
        "limitations": [
            "合成轨迹仅用于检查算法链路和量级，不代表现场数据或商业软件对标结果。",
            "扫描对数可配置；若要复现需求中的50/100口全井网压力测试，请在约定硬件上逐步提高scan_pairs并记录资源曲线。",
            "三维渲染、数据库写入和网络链路不在本脚本计时范围内。",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wells", type=int, default=50, help="synthetic well count")
    parser.add_argument("--points", type=int, default=2000, help="stations per well")
    parser.add_argument("--scan-pairs", type=int, default=5, help="neighbors scanned against well 0")
    parser.add_argument("--step", type=float, default=5.0, help="scan resampling step in metres")
    parser.add_argument("--output", type=Path, help="optional JSON output path")
    args = parser.parse_args()
    result = run(args.wells, args.points, args.scan_pairs, args.step)
    encoded = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded + "\n", encoding="utf-8")
    print(encoded)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
