"""WITSML 1.4.1/2.0 boundary adapter.

The upstream PDS.WITSMLstudio project is a .NET WITSML Store implementation.
This project uses its published WITSML object/API semantics through a small
Python boundary adapter rather than copying its runtime into the Java/Python
services.  The adapter is deliberately read-only at the XML boundary: source
bytes are retained by the engineering file registry and canonical stations are
handed to the existing versioned import transaction.
"""
from __future__ import annotations

import math
import re
import xml.etree.ElementTree as ET
from typing import Any


_DOCTYPE = re.compile(br"<!\s*(?:DOCTYPE|ENTITY)\b", re.I)


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].lower()


def _number(node: ET.Element | None, *, angle: bool = False) -> float | None:
    if node is None or node.text is None or not node.text.strip():
        return None
    value = float(node.text.strip().replace(",", ""))
    unit = str(node.attrib.get("uom") or ("dega" if angle else "m")).lower()
    if angle:
        if unit in {"rad", "radian", "radians"}:
            value = math.degrees(value)
        elif unit not in {"deg", "dega", "degree", "degrees"}:
            raise ValueError(f"unsupported trajectory unit: {unit}")
    elif unit in {"ft", "ftus", "feet"}:
        value *= 0.3048
    elif unit not in {"m", "meter", "meters"}:
        raise ValueError(f"unsupported trajectory unit: {unit}")
    return value


def _version(root: ET.Element) -> str:
    namespace = root.tag.split("}", 1)[0].lstrip("{") if "}" in root.tag else ""
    if "witsmlv2" in namespace.lower() or "energistics.org" in namespace.lower():
        return "2.0"
    if "witsml" in namespace.lower():
        return "1.4.1.1"
    return "UNKNOWN"


def inspect_witsml(content: bytes | str) -> dict[str, Any]:
    """Inspect a WITSML document without resolving external entities."""
    raw = content.encode("utf-8") if isinstance(content, str) else bytes(content)
    if not raw or len(raw) > 256 * 1024 * 1024:
        raise ValueError("WITSML 原文为空或超过256MiB")
    if _DOCTYPE.search(raw):
        raise ValueError("WITSML 禁止包含 DOCTYPE 或 ENTITY")
    root = ET.fromstring(raw)
    version = _version(root)
    objects: list[dict[str, Any]] = []
    trajectories: list[dict[str, Any]] = []
    implicit_stations: list[dict[str, Any]] = []
    has_explicit_trajectory = any(_local(element.tag) == "trajectory" for element in root.iter())
    for node in root.iter():
        kind = _local(node.tag)
        if kind == "station" or (kind == "trajectorystation" and not has_explicit_trajectory):
            values: dict[str, Any] = {}
            for child in node:
                field = _local(child.tag)
                key = {"md": "md", "measureddepth": "md", "incl": "inclination", "inc": "inclination",
                       "inclination": "inclination", "azi": "azimuth", "azimuth": "azimuth",
                       "tvd": "tvd", "north": "north", "east": "east"}.get(field)
                if key:
                    parsed = _number(child, angle=key in {"inclination", "azimuth"})
                    if parsed is not None:
                        values[key] = parsed
            if {"md", "inclination", "azimuth"} <= values.keys():
                implicit_stations.append(values)
            continue
        if kind not in {"well", "wellbore", "trajectory", "log", "mudlog"}:
            continue
        obj = {"type": kind, "uid": node.attrib.get("uid"), "uid_witsml": node.attrib.get("uidWellbore") or node.attrib.get("uidWell")}
        name = next((child.text.strip() for child in node if _local(child.tag) in {"name", "wellname", "wellborename"} and child.text and child.text.strip()), None)
        if name:
            obj["name"] = name
        if kind != "trajectory":
            objects.append(obj)
            continue
        stations: list[dict[str, Any]] = []
        for station in node.iter():
            if _local(station.tag) != "trajectorystation":
                continue
            values: dict[str, Any] = {}
            for child in station:
                field = _local(child.tag)
                key = {"md": "md", "measureddepth": "md", "incl": "inclination", "inc": "inclination",
                       "inclination": "inclination", "azi": "azimuth", "azimuth": "azimuth",
                       "tvd": "tvd", "north": "north", "east": "east"}.get(field)
                if key:
                    parsed = _number(child, angle=key in {"inclination", "azimuth"})
                    if parsed is not None:
                        values[key] = parsed
            if {"md", "inclination", "azimuth"} <= values.keys():
                values["uid"] = station.attrib.get("uid")
                stations.append(values)
        stations.sort(key=lambda row: row["md"])
        obj["stations"] = stations
        obj["station_count"] = len(stations)
        trajectories.append(obj)
    if implicit_stations:
        implicit_stations.sort(key=lambda row: row["md"])
        trajectories.append({"type": "trajectory", "uid": root.attrib.get("uid"), "stations": implicit_stations, "station_count": len(implicit_stations)})
    if not trajectories:
        raise ValueError("WITSML XML 中没有 trajectoryStation")
    return {"version": version, "namespace": root.tag.split("}", 1)[0].lstrip("{") if "}" in root.tag else "", "objects": objects, "trajectories": trajectories}


def stations_from_witsml(content: bytes | str) -> list[dict[str, Any]]:
    """Return all trajectory stations in canonical field names."""
    result = inspect_witsml(content)
    rows: list[dict[str, Any]] = []
    for trajectory in result["trajectories"]:
        for row in trajectory["stations"]:
            rows.append(dict(row, trajectory_uid=trajectory.get("uid"), witsml_version=result["version"]))
    rows.sort(key=lambda row: row["md"])
    return rows
