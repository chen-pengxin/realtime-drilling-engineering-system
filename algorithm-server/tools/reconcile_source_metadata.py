#!/usr/bin/env python3
"""Attach evidence extracted from the supplied Chinese engineering package.

The script only updates JSON metadata and wellhead fields.  It never rewrites
source files, survey stations, version numbers, or current heads.  Conflicting
values are retained under ``source_conflicts`` and are not silently resolved.
"""
from __future__ import annotations

import json, os, re
from collections import Counter
from pathlib import Path
import pymysql
from pymysql.cursors import DictCursor

from app.adapters.source_documents import inspect_document
from app.domain.chinese_engineering import describe_chinese, WELL

ROOT = Path(os.environ.get(
    "COALGAS_ROOT",
    "/Users/ermoon/Desktop/系统启动/系统实现/数据集/采气二厂煤岩气完井资料+设计（11口）",
))
FIELDS = (
    "basin", "structural_position", "geological_structure", "oilfield",
    "development_block", "location", "administrative_location",
    "coordinate_system_text", "coordinate_datum_name", "wellhead_north",
    "wellhead_east", "ground_elevation_m", "kelly_bushing_elevation_m",
    "latitude", "longitude", "design_depth_m", "actual_depth_m",
    "formation", "target_metadata",
)


def js(x):
    return json.dumps(x, ensure_ascii=False, default=str)


def well_from_path(path: str) -> str:
    # A directory is the strongest package-level binding for files whose body
    # is a scanned card or an attachment with no text identity.
    parts = Path(path).parts
    for part in reversed(parts[:-1]):
        m = re.search(WELL, part)
        if m:
            return re.sub(r"\s+", "", m.group())
    return ""


def nonempty(value):
    return value not in (None, "", "未确认", "UNKNOWN")


def main():
    db = pymysql.connect(
        host=os.getenv("MYSQL_HOST", "127.0.0.1"),
        user=os.getenv("MYSQL_USER", "root"),
        password=os.environ["MYSQL_PASSWORD"],
        database=os.getenv("MYSQL_DATABASE", "ry"),
        charset="utf8mb4", cursorclass=DictCursor, autocommit=False,
    )
    try:
        with db.cursor() as c:
            c.execute("SELECT * FROM drilling_source_document WHERE source_path LIKE '采气二厂%%'")
            sources = c.fetchall()
        evidence = {}
        parsed = 0
        for source in sources:
            path = ROOT.parent.parent / source["source_path"]
            # The supplied root itself starts at the dataset directory.  The
            # database path includes that directory name as its first segment.
            if not path.exists():
                path = ROOT / "/".join(Path(source["source_path"]).parts[1:])
            if not path.exists():
                continue
            try:
                result = inspect_document(path, original_name=path.name)
            except Exception as exc:
                result = {"metadata": {}, "engineering": {"well": well_from_path(source["source_path"])},
                          "diagnostics": [{"code": "METADATA_PARSE_ERROR", "message": str(exc)[:300]}]}
            eng = result.get("engineering") or {}
            well = eng.get("well") or well_from_path(source["source_path"])
            if not well:
                continue
            payload = {k: eng.get(k) for k in FIELDS if nonempty(eng.get(k))}
            # Legacy imports already contain X/Y in top-level metadata.
            for k in ("wellhead_north", "wellhead_east"):
                if k not in payload and nonempty((result.get("metadata") or {}).get(k)):
                    payload[k] = result["metadata"][k]
            payload["source_path"] = source["source_path"]
            bucket = evidence.setdefault(well, {"values": {}, "sources": []})
            bucket["sources"].append(source["source_path"])
            for key in FIELDS:
                value = payload.get(key)
                if not nonempty(value):
                    continue
                bucket["values"].setdefault(key, []).append(value)
            with db.cursor() as c:
                old = json.loads(source.get("metadata_json") or "{}")
                old["engineering"] = {**old.get("engineering", {}), **eng}
                old["source_evidence"] = payload
                old["metadata_extraction"] = "DOCUMENT_EVIDENCE_V1"
                c.execute("UPDATE drilling_source_document SET metadata_json=? WHERE source_id=?".replace("?", "%s"),
                          (js(old), source["source_id"]))
            parsed += 1

        def canonical(values):
            out = {}
            conflicts = {}
            for key, raw in values.items():
                unique = []
                raw_counts = Counter(json.dumps(x, ensure_ascii=False, sort_keys=True, default=str) for x in raw)
                for value in raw:
                    marker = json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)
                    if marker not in {json.dumps(x, ensure_ascii=False, sort_keys=True, default=str) for x in unique}:
                        unique.append(value)
                if unique:
                    # Prefer the modal value when the same field is repeated
                    # across summary/design files.  Every non-modal value is
                    # still retained in source_conflicts for manual review.
                    selected = max(unique, key=lambda x: (raw_counts[json.dumps(x, ensure_ascii=False, sort_keys=True, default=str)],
                                                          1 if key in ("ground_elevation_m", "kelly_bushing_elevation_m") and isinstance(x, (int,float)) and x > 100 else 0))
                    out[key] = selected
                if len(unique) > 1:
                    conflicts[key] = unique
            return out, conflicts

        with db.cursor() as c:
            for well, item in evidence.items():
                fields, conflicts = canonical(item["values"])
                c.execute("SELECT metadata_json FROM drilling_well WHERE well_code=%s ORDER BY well_id LIMIT 1", (well,))
                row = c.fetchone()
                if not row:
                    continue
                metadata = json.loads(row.get("metadata_json") or "{}")
                metadata["source_evidence"] = fields
                metadata["source_conflicts"] = conflicts
                metadata["source_paths"] = item["sources"]
                metadata["geography_status"] = "DOCUMENT_EVIDENCE" if fields else "UNCONFIRMED"
                updates = [js(metadata), well]
                sql = "UPDATE drilling_well SET metadata_json=%s"
                if fields.get("wellhead_north") is not None and fields.get("wellhead_east") is not None:
                    sql += ",wellhead_n=%s,wellhead_e=%s"
                    updates = [js(metadata), float(fields["wellhead_north"]), float(fields["wellhead_east"]), well]
                sql += " WHERE well_code=%s"
                c.execute(sql, tuple(updates))

                # Merge the evidence into every immutable trajectory reference
                # for the well.  Version rows remain unchanged except for the
                # explanatory reference metadata and eligibility status.
                c.execute("""
                    SELECT t.survey_version_id,t.reference_json,t.quality_status
                    FROM drilling_trajectory_version t
                    JOIN drilling_wellbore b ON b.wellbore_id=t.wellbore_id
                    JOIN drilling_well w ON w.well_id=b.well_id
                    WHERE w.well_code=%s
                """, (well,))
                versions = c.fetchall()
                for version in versions:
                    ref = json.loads(version.get("reference_json") or "{}")
                    for key in FIELDS:
                        ref.pop(key, None)
                    ref.update({k: v for k, v in fields.items() if k not in ("wellhead_north", "wellhead_east")})
                    if fields.get("wellhead_north") is not None:
                        ref.setdefault("origin_north", fields["wellhead_north"])
                    if fields.get("wellhead_east") is not None:
                        ref.setdefault("origin_east", fields["wellhead_east"])
                    if conflicts:
                        ref["source_conflicts"] = conflicts
                    ref["reference_status"] = (
                        "DATUM_NAMED_PROJECTION_REQUIRED" if ref.get("coordinate_datum_name") and not ref.get("crs")
                        else "COORDINATE_REFERENCE_UNCONFIRMED" if not ref.get("crs")
                        else "VERTICAL_DATUM_UNCONFIRMED" if not ref.get("depth_datum_elevation_m")
                        else "MD0_HISTORY_REQUIRED" if ref.get("origin_md") not in (0, 0.0)
                        else "READY"
                    )
                    ref["evaluation_eligibility"] = "ELIGIBLE" if ref["reference_status"] == "READY" else "BLOCKED"
                    c.execute("UPDATE drilling_trajectory_version SET reference_json=%s,quality_status=%s WHERE survey_version_id=%s",
                              (js(ref), ref["reference_status"], version["survey_version_id"]))
        db.commit()
        print(json.dumps({"sources_updated": parsed, "wells_updated": len(evidence),
                          "root": str(ROOT)}, ensure_ascii=False))
    finally:
        db.close()


if __name__ == "__main__":
    main()
