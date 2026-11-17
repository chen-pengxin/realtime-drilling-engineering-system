"""Validate real model output and recover positions with minimum curvature."""
from __future__ import annotations
import math
from typing import Any
from app.shared.trajectory.min_curvature import compute_minimum_curvature


def normalize_pose_result(result: dict[str, Any], payload: dict[str, Any]) -> dict[str, Any]:
    latest = payload.get("current_pose") or {}
    required = ("md", "inclination", "azimuth", "north", "east", "tvd")
    if any(latest.get(key) is None or not math.isfinite(float(latest[key])) for key in required):
        raise ValueError("current_pose must contain finite MD, attitude and position")
    rows = result.get("predicted_stations") or ([result["predicted_pose"]] if result.get("predicted_pose") else [])
    if not rows:
        raise ValueError("model must return predicted_pose or predicted_stations")
    normalized = []
    previous = dict(latest)
    for raw in sorted(rows, key=lambda row: float(row.get("md", float(latest["md"]) + float(payload.get("prediction_step", 9))))):
        point = dict(raw)
        point.setdefault("md", float(latest["md"]) + float(payload.get("prediction_step", 9)))
        for key in ("md", "inclination", "azimuth"):
            if point.get(key) is None or not math.isfinite(float(point[key])):
                raise ValueError(f"predicted {key} must be finite")
            point[key] = float(point[key])
        if point["md"] <= float(previous["md"]) or not 0 <= point["inclination"] <= 180:
            raise ValueError("predicted MD must increase and inclination must be in [0, 180]")
        point["azimuth"] %= 360
        # Coordinates always follow the same minimum-curvature transfer as scans.
        station = compute_minimum_curvature([
            {key: previous[key] for key in ("md", "inclination", "azimuth")},
            {key: point[key] for key in ("md", "inclination", "azimuth")},
        ], (float(previous["north"]), float(previous["east"]), float(previous["tvd"])))[-1]
        point.update(north=station.north, east=station.east, tvd=station.tvd)
        normalized.append(point)
        previous = point
    return {**result, "predicted_stations": normalized, "predicted_pose": normalized[-1], "source": "TRAINED_MODEL"}
