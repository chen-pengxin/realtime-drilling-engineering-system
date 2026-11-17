"""CSV/Excel/TXT、SQLite 和 WITSML 测斜数据适配。"""
from __future__ import annotations

import csv
import io
import json
import math
import sqlite3
import xml.etree.ElementTree as ET
from dataclasses import replace
from pathlib import Path
from typing import Iterable, Sequence

from ..shared.trajectory.min_curvature import SurveyStation, compute_minimum_curvature


FIELD_ALIASES = {
    "md": {"md", "measured_depth", "measureddepth", "井深", "测深"},
    "inclination": {"inclination", "inc", "井斜", "井斜角"},
    "azimuth": {"azimuth", "azi", "方位", "方位角"},
}

# External drilling packages use different column labels for the same survey
# values.  These are deliberately kept as presets rather than hard-coded
# parser branches: a project can send ``field_mapping`` to override any label
# while the internal representation remains the canonical lower-case schema.
EXPORT_FIELD_MAPPINGS = {
    "CANONICAL": {
        "md": "md", "inclination": "inclination", "azimuth": "azimuth",
        "north": "north", "east": "east", "tvd": "tvd",
        "dogleg": "dogleg", "curvature": "curvature",
    },
    "COMPASS": {
        "md": "MD", "inclination": "INC", "azimuth": "AZI",
        "north": "NORTH", "east": "EAST", "tvd": "TVD",
        "dogleg": "DLS", "curvature": "CURVATURE",
    },
    "WELLPLAN": {
        "md": "MeasuredDepth", "inclination": "Inclination", "azimuth": "Azimuth",
        "north": "Northing", "east": "Easting", "tvd": "TVD",
        "dogleg": "DoglegSeverity", "curvature": "Curvature",
    },
}


def export_field_mapping(schema: str = "CANONICAL", custom: dict[str, str] | None = None) -> dict[str, str]:
    """Resolve a documented export preset plus optional project overrides.

    Compass and WellPlan labels are integration presets, not claims about a
    vendor's complete proprietary file format.  A project can supply exact
    labels through ``custom`` and still receive the same canonical values.
    """
    key = str(schema or "CANONICAL").upper()
    if key not in EXPORT_FIELD_MAPPINGS:
        raise ValueError("schema must be CANONICAL, COMPASS or WELLPLAN")
    mapping = dict(EXPORT_FIELD_MAPPINGS[key])
    if custom:
        for source, target in custom.items():
            if source not in mapping:
                raise ValueError(f"unsupported survey export field: {source}")
            if not str(target).strip():
                raise ValueError(f"survey export label for {source} cannot be empty")
            mapping[source] = str(target).strip()
    return mapping


def _normalise_header(value: str) -> str:
    return value.strip().lower().replace(" ", "").replace("_", "")


def _resolve_columns(headers: Iterable[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for original in headers:
        key = _normalise_header(original)
        for target, aliases in FIELD_ALIASES.items():
            if key in {_normalise_header(alias) for alias in aliases}:
                result[target] = original
    missing = set(FIELD_ALIASES) - set(result)
    if missing:
        raise ValueError(f"survey file is missing columns: {sorted(missing)}")
    return result


def parse_delimited(content: str, delimiter: str | None = None) -> list[SurveyStation]:
    sample = content[:2048]
    try:
        dialect = csv.Sniffer().sniff(sample, delimiters=delimiter or ",\t; ")
    except csv.Error:
        # 单列空格分隔和只有一行的文件常常无法由 Sniffer 判断，
        # 先按逗号读取并给出明确的缺列错误，而不是返回难以定位的索引异常。
        dialect = csv.get_dialect("excel")
        if delimiter:
            dialect = csv.excel_tab if delimiter == "\t" else csv.get_dialect("excel")
    reader = csv.DictReader(io.StringIO(content), dialect=dialect)
    columns = _resolve_columns(reader.fieldnames or [])
    rows = []
    for line_no, row in enumerate(reader, start=2):
        if not any(str(v).strip() for v in row.values() if v is not None):
            continue
        try:
            rows.append({k: float(row[v]) for k, v in columns.items()})
        except (TypeError, ValueError) as exc:
            raise ValueError(f"invalid numeric value at line {line_no}") from exc
    return compute_minimum_curvature(rows)


def inspect_survey_rows(rows: Iterable[dict | tuple[float, float, float]],
                        frozen_tolerance: float = 1e-12,
                        jump_threshold_deg: float = 45.0) -> list[dict[str, object]]:
    """返回导入前质量问题；不会静默修改或丢弃原始站点。

    质量问题供 Java 的隔离表使用。真正计算仍由 ``compute_minimum_curvature``
    执行，调用方可以在审核后重新导入修正后的版本。
    """
    parsed: list[tuple[float, float, float]] = []
    issues: list[dict[str, object]] = []
    for index, row in enumerate(rows, start=1):
        try:
            if isinstance(row, dict):
                values = (float(row["md"]), float(row.get("inclination", row.get("inc"))),
                          float(row.get("azimuth", row.get("azi"))))
            else:
                values = tuple(map(float, row[:3]))
        except (KeyError, TypeError, ValueError) as exc:
            issues.append({"row": index, "type": "MISSING_OR_INVALID", "severity": "ERROR",
                           "message": str(exc)})
            continue
        md, inc, azi = values
        if not all(math.isfinite(value) for value in values):
            issues.append({"row": index, "type": "NON_FINITE", "severity": "ERROR"})
            continue
        if inc < 0 or inc > 180:
            issues.append({"row": index, "type": "INCLINATION_RANGE", "severity": "ERROR", "value": inc})
        if parsed:
            pmd, pinc, pazi = parsed[-1]
            if md == pmd:
                issues.append({"row": index, "type": "DUPLICATE_MD", "severity": "ERROR", "md": md})
            elif md < pmd:
                issues.append({"row": index, "type": "OUT_OF_ORDER_MD", "severity": "ERROR", "md": md})
            if abs(inc - pinc) < frozen_tolerance and abs(((azi - pazi + 180) % 360) - 180) < frozen_tolerance:
                issues.append({"row": index, "type": "FROZEN_SURVEY", "severity": "WARNING", "md": md})
            if abs(inc - pinc) > jump_threshold_deg:
                issues.append({"row": index, "type": "INCLINATION_JUMP", "severity": "WARNING", "md": md})
        parsed.append(values)
    return issues


def merge_incremental_survey(
    existing: Sequence[SurveyStation],
    incoming: Iterable[dict | tuple[float, float, float]],
    md_tolerance: float = 1e-6,
) -> tuple[list[SurveyStation], list[dict[str, object]]]:
    """Merge a delta import without silently replacing an existing station.

    A repeated MD with identical INC/AZI is idempotent and is ignored.  A
    repeated MD with different values is isolated as a conflict; the caller
    must create a reviewed replacement version instead of guessing which
    measurement is authoritative.  The returned trajectory is recalculated
    with minimum curvature from the first station's origin.
    """
    if not existing:
        raise ValueError("existing trajectory is required for an incremental import")
    base = {(round(float(s.md) / md_tolerance) * md_tolerance): (float(s.inclination), float(s.azimuth)) for s in existing}
    rows = [(float(s.md), float(s.inclination), float(s.azimuth)) for s in existing]
    issues: list[dict[str, object]] = []
    for index, row in enumerate(incoming, start=1):
        if isinstance(row, dict):
            values = (float(row["md"]), float(row.get("inclination", row.get("inc"))), float(row.get("azimuth", row.get("azi"))))
        else:
            values = tuple(map(float, row[:3]))
        md, inc, azi = values
        if not all(math.isfinite(value) for value in values) or md < 0 or not 0 <= inc <= 180:
            issues.append({"row": index, "type": "INVALID_INCREMENT", "severity": "ERROR", "values": values})
            continue
        key = round(md / md_tolerance) * md_tolerance
        previous = base.get(key)
        if previous is not None:
            angle_delta = abs(((azi - previous[1] + 180) % 360) - 180)
            if abs(inc - previous[0]) <= 1e-9 and angle_delta <= 1e-9:
                issues.append({"row": index, "type": "DUPLICATE_IDEMPOTENT", "severity": "INFO", "md": md})
            else:
                issues.append({"row": index, "type": "MD_CONFLICT", "severity": "ERROR", "md": md})
            continue
        base[key] = (inc, azi)
        rows.append(values)
    rows.sort(key=lambda row: row[0])
    if any(issue["severity"] == "ERROR" for issue in issues):
        # Return the unchanged base version for safe preview; Java can persist
        # the quality issues and create a new version after review.
        return list(existing), issues
    origin = (existing[0].north, existing[0].east, existing[0].tvd)
    return compute_minimum_curvature(rows, origin), issues


def _apply_project_reference(stations: list[SurveyStation], origin: tuple[float, float, float],
                             length_unit: str, angle_unit: str, north_reference: str,
                             tvd_positive: str, magnetic_declination: float,
                             meridian_convergence: float) -> list[SurveyStation]:
    unit = length_unit.lower()
    if unit not in {"m", "meter", "metre", "ft", "foot", "feet"}:
        raise ValueError("length_unit must be m or ft")
    angle = angle_unit.lower()
    if angle not in {"deg", "degree", "degrees", "rad", "radian", "radians"}:
        raise ValueError("angle_unit must be deg or rad")
    north = north_reference.upper()
    if north not in {"TRUE_NORTH", "GRID_NORTH", "MAGNETIC_NORTH"}:
        raise ValueError("north_reference must be TRUE_NORTH, GRID_NORTH or MAGNETIC_NORTH")
    tvd_direction = tvd_positive.upper()
    if tvd_direction not in {"D", "U"}:
        raise ValueError("tvd_positive must be D or U")
    scale = 0.3048 if unit in {"ft", "foot", "feet"} else 1.0
    angle_scale = 180.0 / math.pi if angle in {"rad", "radian", "radians"} else 1.0
    # Bearings are clockwise from the declared north.  East-positive
    # declination/convergence rotate those bearings onto true north.  Only the
    # correction belonging to the selected reference is applied.
    if north == "MAGNETIC_NORTH":
        azimuth_offset = float(magnetic_declination) * angle_scale
    elif north == "GRID_NORTH":
        azimuth_offset = float(meridian_convergence) * angle_scale
    else:
        azimuth_offset = 0.0
    origin_north, origin_east, origin_tvd = map(float, origin)
    rows = [
        (station.md * scale, station.inclination * angle_scale,
         station.azimuth * angle_scale + azimuth_offset)
        for station in stations
    ]
    converted = compute_minimum_curvature(
        rows,
        (origin_north * scale, origin_east * scale, origin_tvd * scale),
    )
    if tvd_direction == "U":
        # ``compute_minimum_curvature`` uses the internal convention TVD
        # positive down.  An external survey declared positive up therefore
        # needs the complete coordinate reflected, including both the origin
        # and every computed increment.  Negating only the origin would leave
        # a downward-positive increment attached to an upward-positive value.
        return [replace(station, tvd=-station.tvd) for station in converted]
    return converted


def parse_file(path: str | Path, origin: tuple[float, float, float] = (0, 0, 0),
               length_unit: str = "m", angle_unit: str = "deg",
               north_reference: str = "TRUE_NORTH", tvd_positive: str = "D",
               magnetic_declination: float = 0, meridian_convergence: float = 0) -> list[SurveyStation]:
    path = Path(path)
    suffix = path.suffix.lower()
    if suffix in {".csv", ".txt", ".tsv"}:
        stations = parse_delimited(path.read_text(encoding="utf-8-sig"), "\t" if suffix == ".tsv" else None)
        return _apply_project_reference(stations, origin, length_unit, angle_unit, north_reference,
                                        tvd_positive, magnetic_declination, meridian_convergence)
    if suffix in {".xlsx", ".xls"}:
        try:
            import pandas as pd
        except ImportError as exc:
            raise RuntimeError("Excel import requires pandas") from exc
        frame = pd.read_excel(path)
        columns = _resolve_columns(frame.columns)
        rows = [{k: float(row[v]) for k, v in columns.items()} for _, row in frame.iterrows()]
        return _apply_project_reference(compute_minimum_curvature(rows), origin, length_unit, angle_unit,
                                        north_reference, tvd_positive, magnetic_declination,
                                        meridian_convergence)
    if suffix in {".xml", ".witsml"}:
        return _apply_project_reference(parse_witsml(path.read_text(encoding="utf-8")), origin, length_unit,
                                        angle_unit, north_reference, tvd_positive, magnetic_declination,
                                        meridian_convergence)
    if suffix in {".sqlite", ".db"}:
        return _apply_project_reference(parse_sqlite(path), origin, length_unit, angle_unit,
                                        north_reference, tvd_positive, magnetic_declination,
                                        meridian_convergence)
    raise ValueError(f"unsupported survey format: {suffix}")


def parse_sqlite(path: str | Path, table: str = "survey") -> list[SurveyStation]:
    if not table.replace("_", "").isalnum():
        raise ValueError("invalid SQLite table name")
    with sqlite3.connect(path) as conn:
        rows = conn.execute(f"SELECT md, inclination, azimuth FROM {table} ORDER BY md").fetchall()
    return compute_minimum_curvature(rows)


def parse_witsml(content: str) -> list[SurveyStation]:
    """解析 WITSML trajectoryStation 的常见字段，兼容命名空间。"""
    root = ET.fromstring(content)
    rows = []
    for node in root.iter():
        children = {_normalise_header(child.tag.rsplit("}", 1)[-1]): child.text for child in node}
        def get(*names):
            for name in names:
                for key, value in children.items():
                    if _normalise_header(name) == key and value is not None:
                        return float(value)
            return None
        # WITSML 1.4.1 commonly uses ``incl``/``azi`` while some producers
        # emit the longer ``inclination``/``azimuth`` names.  Accept both
        # forms at the parser boundary and keep one canonical internal shape.
        md, inc, azi = get("md", "measuredDepth"), get("incl", "inclination", "inc"), get("azi", "azimuth")
        if md is not None and inc is not None and azi is not None:
            rows.append((md, inc, azi))
    if not rows:
        raise ValueError("WITSML XML contains no trajectory stations")
    rows.sort(key=lambda x: x[0])
    return compute_minimum_curvature(rows)


def _mapped_station(station: SurveyStation, mapping: dict[str, str]) -> dict[str, object]:
    values = station.to_dict()
    return {mapping.get(key, key): value for key, value in values.items()}


def stations_to_json(stations: list[SurveyStation], schema: str = "CANONICAL",
                     custom_mapping: dict[str, str] | None = None) -> str:
    mapping = export_field_mapping(schema, custom_mapping)
    return json.dumps([_mapped_station(s, mapping) for s in stations], ensure_ascii=False)


def stations_to_csv(stations: list[SurveyStation], schema: str = "CANONICAL",
                    custom_mapping: dict[str, str] | None = None) -> str:
    mapping = export_field_mapping(schema, custom_mapping)
    fields = ["md", "inclination", "azimuth", "north", "east", "tvd", "dogleg", "curvature"]
    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow([mapping[field] for field in fields])
    for station in stations:
        values = station.to_dict()
        writer.writerow([values[field] for field in fields])
    return output.getvalue()


def stations_to_witsml(stations: list[SurveyStation]) -> str:
    root = ET.Element("trajectory", {"version": "1.4.1.1"})
    for station in stations:
        node = ET.SubElement(root, "trajectoryStation")
        for key, value in (("md", station.md), ("inclination", station.inclination), ("azimuth", station.azimuth)):
            child = ET.SubElement(node, key); child.text = f"{value:.9g}"
    return ET.tostring(root, encoding="unicode")
