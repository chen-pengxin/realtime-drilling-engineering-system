"""Build immutable survey snapshots from full, appended or resurveyed data.

Stored parents are already metres/degrees/grid north. Only incoming measurements
are converted; both sides are then reduced together with the parent's anchor.
"""
from bisect import bisect_left
from copy import deepcopy
import math

from ...adapters.source_documents import clean_rows
from .reduction import grid_azimuth, reduce_trajectory, standard_table
from .min_curvature import SurveyStation


def _incoming(rows, reference):
    unit = reference.get("length_unit", "m")
    angle = reference.get("angle_unit", "deg")
    if unit not in ("m", "ft") or angle not in ("deg", "rad"):
        raise ValueError("不支持的测斜单位")
    scale = 0.3048 if unit == "ft" else 1.0
    angular_scale = 180 / math.pi if angle == "rad" else 1.0
    result = []
    for row in rows:
        candidate = deepcopy(row)
        try:
            candidate.update(md=round(float(row["md"]) * scale, 6),
                             inclination=round(float(row["inclination"]) * angular_scale, 6),
                             azimuth=float(row["azimuth"]) * angular_scale)
            # Do not silently wrap a corrupt source angle into a valid angle.
            if not 0 <= candidate["azimuth"] <= 360:
                raise ValueError("方位角超出有效范围")
            candidate["azimuth"] = round(grid_azimuth(
                candidate["azimuth"], reference.get("north_reference", "GRID_NORTH"),
                float(reference.get("magnetic_declination", 0)),
                float(reference.get("meridian_convergence", 0))), 6) % 360
        except (KeyError, TypeError, ValueError):
            # clean_rows reports the offending source row as a blocking issue.
            candidate.update(md=None, inclination=None, azimuth=None)
        result.append(candidate)
    return result


def station_changes(before, after):
    """Measurement changes, plus the preceding segment that needs recalculation."""
    old = {float(r["md"]): r for r in before}
    new = {float(r["md"]): r for r in after}
    depths = sorted(old.keys() | new.keys())
    changes = []
    for md in depths:
        a, b = old.get(md), new.get(md)
        if a is None:
            change = "ADDED"
        elif b is None:
            change = "REMOVED"
        elif (abs(float(a["inclination"]) - float(b["inclination"])) > 1e-8 or
              abs((float(a["azimuth"]) - float(b["azimuth"]) + 180) % 360 - 180) > 1e-8):
            change = "RESURVEY"
        else:
            continue
        changes.append({"md": md, "change": change, "before": a, "after": b})
    counts = {k.lower(): sum(c["change"] == k for c in changes)
              for k in ("ADDED", "REMOVED", "RESURVEY")}
    first = changes[0]["md"] if changes else None
    index = bisect_left(depths, first) if changes else 0
    affected = depths[max(0, index - 1)] if changes else None
    return changes, {**counts, "changed_points": len(changes), "changed_from_md": first,
                     "changed_to_md": changes[-1]["md"] if changes else None,
                     "affected_from_md": affected,
                     "affected_to_md": max(new) if changes and new else None}


def preview_revision(payload):
    revision = payload.get("revision_type", "FULL")
    kind = payload.get("kind", "ACTUAL")
    if revision not in ("FULL", "INCREMENTAL", "RESURVEY"):
        raise ValueError("导入方式必须为完整、增量或复测修订")
    if kind not in ("ACTUAL", "DESIGN") or (revision == "RESURVEY" and kind != "ACTUAL"):
        raise ValueError("复测修订只适用于实钻测斜数据")
    rows = payload.get("rows", [])
    existing = deepcopy(payload.get("existing", []))
    if len(rows) > 100000 or len(existing) > 100000:
        raise ValueError("单个版本最多100000测点")
    if revision != "FULL" and len(existing) < 2:
        raise ValueError("增量和复测导入必须选择有效的父版本")
    if revision == "FULL" and existing:
        raise ValueError("完整导入不得隐式合并父版本")
    reference = dict(payload.get("reference", {}))
    clean, issues = clean_rows(_incoming(rows, reference), payload.get("policy"))
    if not clean:
        raise ValueError("没有有效的测斜点")
    incoming_mds={r['md'] for r in clean}
    normalized = {**reference, "source_reference": deepcopy(reference), "source_north_reference": reference.get("north_reference", "GRID_NORTH"),
                  "north_reference": "GRID_NORTH", "length_unit": "m", "angle_unit": "deg"}
    if payload.get('extracted_reference') is not None:
        normalized['extracted_reference'] = deepcopy(payload['extracted_reference'])
    replacement = None
    if existing:
        parent_reference = dict(payload.get("parent_reference", {}))
        for field in ("crs", "depth_datum", "depth_datum_elevation_m"):
            a, b = reference.get(field), parent_reference.get(field)
            same = (math.isclose(float(a), float(b), abs_tol=1e-6, rel_tol=0)
                    if field == "depth_datum_elevation_m" and a is not None and b is not None
                    else str(a).casefold() == str(b).casefold())
            if a is not None and b is not None and not same:
                raise ValueError("复测/增量数据必须沿用父版本的坐标和深度基准")
        # Validate, but never clean or discard points of the immutable parent.
        parent_origin = tuple(float(existing[0][k]) for k in ("north", "east", "tvd"))
        reduce_trajectory(existing, {"origin_north": parent_origin[0],
                                   "origin_east": parent_origin[1], "origin_tvd": parent_origin[2]})
        normalized.update(parent_reference)
        # Coordinate anchors belong to the parent; explicitly reviewed tool
        # metadata belongs to the new immutable version, not its parent.
        for key in ('survey_tool', 'uncertainty', 'survey_metadata_evidence', 'uncertainty_parameters'):
            if key in reference:
                normalized[key] = deepcopy(reference[key])
        if payload.get('extracted_reference') is not None:
            normalized['extracted_reference'] = deepcopy(payload['extracted_reference'])
        normalized['source_reference'] = deepcopy(reference)
        normalized.update(north_reference="GRID_NORTH", length_unit="m", angle_unit="deg",
                          origin_north=parent_origin[0], origin_east=parent_origin[1], origin_tvd=parent_origin[2])
        if revision == "RESURVEY":
            start = float(payload["replace_from_md"]) if payload.get("replace_from_md") is not None else clean[0]["md"]
            end = float(payload["replace_to_md"]) if payload.get("replace_to_md") is not None else clean[-1]["md"]
            if not all(map(math.isfinite, (start, end))) or start > end:
                raise ValueError("复测替换范围无效")
            if start < float(existing[0]["md"]) or end > float(existing[-1]["md"]):
                raise ValueError("复测范围必须在父版本井深内；新增钻进测点请使用增量导入")
            if any(not start <= r["md"] <= end for r in clean):
                raise ValueError("复测点必须全部位于声明的替换范围内")
            removed = [r for r in existing if start <= float(r["md"]) <= end]
            replacement = {"from_md": start, "to_md": end, "replaced_points": len(removed)}
            merged = [r for r in existing if not start <= float(r["md"]) <= end] + clean
            # The operator's cleaning policy applies only to incoming rows.
            # Merging may identify MD conflicts, but must never filter or
            # coalesce measurements inherited from the immutable parent.
            clean, merge_issues = clean_rows(merged, {"duplicate_tolerance": 1e-7}, check_anomalies=False)
            issues += merge_issues
            if not clean or clean[0]["md"] != float(existing[0]["md"]) or clean[-1]["md"] != float(existing[-1]["md"]):
                raise ValueError("复测修订必须保留父版本的首测点和末测点井深")
        else:
            # Duplicate identical historical stations are harmless. Conflicting
            # measurements are blocked instead of being overwritten by append.
            old_md = {float(r["md"]) for r in existing}
            if any(r["md"] < float(existing[-1]["md"]) and r["md"] not in old_md for r in clean):
                raise ValueError("增量导入仅用于新增井段；历史测点修订请选择复测修订")
            clean, merge_issues = clean_rows([*existing, *clean], {"duplicate_tolerance": 1e-7}, check_anomalies=False)
            issues += merge_issues
    else:
        scale = 0.3048 if reference.get("length_unit") == "ft" else 1.0
        for key in ("origin_north", "origin_east", "origin_tvd"):
            normalized[key] = float(reference.get(key, 0)) * scale
    changes, summary = station_changes(existing, clean)
    if normalized.get('uncertainty'):
        profile = normalized['uncertainty']
        from app.tasks.anticollision.evaluation import ErrorModelConfig
        ErrorModelConfig().for_survey(profile)
        from app.tasks.anticollision.parameter_validation import validate_settings
        validate_settings(profile['settings'])
        parent_profile = payload.get('parent_reference', {}).get('uncertainty')
        if existing and parent_profile:
            def budget(p):return {k:p.get(k) for k in ('model_version','settings')}
            if (budget(parent_profile)!=budget(profile) or payload.get('parent_reference',{}).get('survey_tool',{})!=normalized.get('survey_tool',{})) and not {float(r['md']) for r in existing}<=incoming_mds:
                raise ValueError('局部井段更换测斜工具或误差模型不能覆盖未复测的历史井段；请提交完整测量程序的新版本')
        if existing and parent_profile and any(parent_profile.get(k)!=profile.get(k) for k in ('model_version','settings')):
            summary['metadata_changed']=True
            summary['affected_from_md']=float(clean[0]['md'])
            summary['affected_to_md']=float(clean[-1]['md'])
    if existing and summary["changed_from_md"] == float(existing[0]["md"]):
        # Correcting the first measurement also changes the initial tangent.
        stations = reduce_trajectory(clean, normalized)
    elif existing:
        # The database stores six decimal places. Preserve the already-reduced
        # unaffected prefix exactly, including its rounded coordinate anchor;
        # re-running the entire prefix would accumulate rounding drift.
        affected = summary["affected_from_md"]
        prefix = [r for r in existing if affected is None or float(r["md"]) <= affected]
        stations = [SurveyStation(**{key: float(row.get(key) or 0)
                     for key in SurveyStation.__dataclass_fields__}) for row in prefix]
        if affected is not None:
            anchor = prefix[-1]
            suffix = [r for r in clean if float(r["md"]) >= affected]
            local_reference = {**normalized, "origin_north": anchor["north"],
                               "origin_east": anchor["east"], "origin_tvd": anchor["tvd"]}
            stations.extend(reduce_trajectory(suffix, local_reference)[1:])
    else:
        stations = reduce_trajectory(clean, normalized)
    if len(stations) < 2:
        raise ValueError("至少需要两个有效测斜点")
    stored = [s.to_dict() for s in stations]
    # Return calculated positions in the diff without recomputing its MD scope.
    positions = {r["md"]: r for r in stored}
    for change in changes:
        change["after"] = positions.get(change["md"])
    return {"stations": stored, "normalized_reference": normalized,
            "table": standard_table(stations, kind=kind), "issues": issues,
            "has_error": any(i["severity"] == "ERROR" for i in issues),
            "count": len(stations), "calculation_method": "wellpath minimum curvature",
            "incremental_from_md": summary["affected_from_md"],
            "changed_to_md": summary["changed_to_md"], "revision_type": revision,
            "replacement_range": replacement, "change_summary": summary,
            "changes": changes[:500], "changes_truncated": len(changes) > 500,
            "downstream_recalculation": {"from_md": summary["affected_from_md"],
                "to_md": summary["affected_to_md"],
                "reason": "minimum-curvature segments and propagated covariance depend on previous stations"}}
