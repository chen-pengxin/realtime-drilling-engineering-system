"""Chinese source identities. Titles and explicit relationships precede folders.

Dataset/organization/geological structure are provenance, not an asset identity.
The catalog builder exposes only block -> field -> well -> wellbore.
"""
from __future__ import annotations

import re
from pathlib import PurePosixPath

WELL = r"(?:米|双|麒)\s*\d+(?:\s*-\s*\d+)?(?:\s*(?:YH|TH|H)\s*\d*)?"


def well_names(text: str) -> list[str]:
    return list(dict.fromkeys(re.sub(r"\s+", "", x) for x in re.findall(WELL, text)))


def describe_chinese(name: str, text: str, source_path: str) -> dict:
    parts = PurePosixPath(source_path or name).parts
    folder = next((p for p in reversed(parts[:-1]) if re.search(WELL, p)), "")
    folder_well = (well_names(folder) or [""])[0]
    filename_well = (well_names(name) or [""])[0]
    # A neighbouring-well list or a numeric table is not a document title.
    title = text.lstrip()[:1400]
    body_titles = re.findall(
        "(" + WELL + r")\s*井?\s*(?=井?钻井|井?基本数据|井?井斜|井?固井|井?完井|工程设计|地质设计|井?套管|井?工程班)",
        title,
    )
    body_names = [re.sub(r"\s+", "", x) for x in body_titles]
    multi_title = re.match(r"[^\n]{0,35}[（(]([^\n）)]+)[）)]\s*井", title)
    multi = well_names(multi_title.group(1)) if multi_title else []
    if len(multi) > 1:
        targets = multi
    else:
        # Full well names in a title beat truncated file names. Otherwise keep
        # folder evidence when it is a strict extension of a title/file alias.
        cover_names = [n for n in well_names(title[:350]) if re.search(r"(?:YH|H)\d*$", n)]
        chosen = body_names[0] if body_names else (cover_names[0] if cover_names else filename_well or folder_well)
        if (folder_well and chosen and folder_well.startswith(chosen) and chosen != folder_well
                and not re.search(re.escape(chosen) + r"\s*井", name)):
            chosen = folder_well
        targets = [chosen] if chosen else []
    conflicts = []
    for field, value in (("filename", filename_well), ("directory", folder_well)):
        if value and targets and value not in targets:
            conflicts.append({"field": field, "value": value, "resolved_to": targets,
                              "evidence": "正文标题" if body_names or multi else "完整井名/来源目录"})
    group = re.search("(" + WELL + r")\s*井组", text[:1800])
    site = re.sub(r"\s+", "", group.group(1)) if group else ""
    location = re.search(r"(?:井场位于|地理位置\s*[：:|]?\s*)(陕西省[^\n。；;|]{5,120})", text)
    location_value = location.group(1).strip() if location else ""
    if location_value:
        location_value = re.split(r"\s+(?:\d+[、.]|井场|本井|井口|地面|构造|坐标)", location_value, maxsplit=1)[0].strip(" ，,；;")
    structure = re.search(r"鄂\s*尔\s*多\s*斯\s*盆\s*地\s*伊\s*陕\s*斜\s*坡", text)
    gasfield = re.search(r"(神木气田|米脂气田)", text)
    development_block = re.search(r"(米脂北[—\-至]佳县区块|米脂北[—\-至]佳县)", text)

    def scalar(patterns):
        for pattern in patterns:
            for found in re.finditer(pattern, text, re.I):
                # Ranges such as "850m～1260m" describe regional relief, not
                # the wellhead value.  DMS latitude/longitude is retained as
                # source text and is not reduced to the first degree number.
                tail = text[found.end(1):found.end(1) + 12]
                if re.match(r"\s*(?:m|米)?\s*[～\-—至]", tail, re.I):
                    continue
                if "计算" in tail[:8] or "范围" in tail[:8]:
                    continue
                value = found.group(1).replace(",", "").replace("，", "").strip()
                try:
                    return float(value)
                except ValueError:
                    continue
        return None

    north = scalar((
        r"(?:纵坐标\s*[（(]?\s*X\s*[)）]?|\bX)\s*[：:]?\s*([+-]?\d+(?:\.\d+)?)",
        r"(?:北坐标|北位移)\s*[：:]?\s*([+-]?\d+(?:\.\d+)?)",
    ))
    east = scalar((
        r"(?:横坐标\s*[（(]?\s*Y\s*[)）]?|\bY)\s*[：:]?\s*([+-]?\d+(?:\.\d+)?)",
        r"(?:东坐标|东位移)\s*[：:]?\s*([+-]?\d+(?:\.\d+)?)",
    ))
    ground = scalar((
        r"地面(?:海拔|高程|高度)\s*[：:]?\s*([+-]?\d+(?:\.\d+)?)",
        r"(?<!地面)海拔(?:高度)?\s*[：:]?\s*([+-]?\d+(?:\.\d+)?)",
    ))
    kb = scalar((r"补心(?:海拔|高程|高度|高)\s*[：:]?\s*([+-]?\d+(?:\.\d+)?)",))
    coordinate_system_text = ""
    if re.search(r"北京\s*54\s*坐标|北京54", text):
        coordinate_system_text = "北京54"
    epsg = re.search(r"EPSG\s*[:：]?\s*(\d{4,6})", text, re.I)
    if epsg:
        coordinate_system_text = "EPSG:" + epsg.group(1)
    latitude = scalar((r"(?:北纬|纬度)\s*[：:]?\s*([+-]?\d+(?:\.\d+)?)",))
    longitude = scalar((r"(?:东经|经度)\s*[：:]?\s*([+-]?\d+(?:\.\d+)?)",))
    design_depth = scalar((
        r"设计井深\s*[：:]?\s*([+-]?\d+(?:\.\d+)?)",
        r"设计完钻井深\s*[：:]?\s*([+-]?\d+(?:\.\d+)?)",
    ))
    actual_depth = scalar((
        r"(?:实际井深|完井井深|完钻井深)\s*[：:]?\s*([+-]?\d+(?:\.\d+)?)",
    ))
    formation_match = re.search(r"((?:本溪组|太原组|山西组|延安组|盒\s*\d+|\d+#\s*煤))", text)
    formation = formation_match.group(1).strip() if formation_match else ""
    target_match = re.search(r"(?:靶点|目标点)\s*[：:]\s*[^\n]{1,160}", text)
    target_text = target_match.group(0).strip() if target_match else ""
    neighbours = []
    for match in re.finditer(r"(?:邻井|井场中有|同井组其它井口)[^\n。；;]{0,180}", text):
        neighbours.extend(well_names(match.group(0)))
    neighbours = [x for x in dict.fromkeys(neighbours) if x not in targets]
    role = "REFERENCE"
    purpose = "参考资料"
    classify = name if re.search(r"井斜|测斜|设计|靶点|井史|基本数据|完井|固井|套管|班报|申请|指令|审核|审批", name) else name + title[:160]
    for pattern, r, p in (
        (r"井斜|测斜", "TRAJECTORY_DATA", "实钻测斜"),
        (r"申请|指令|审核|审批", "ENGINEERING_REPORT", "工程指令与审批"),
        (r"变更设计|设计[（(]变更|优化", "ENGINEERING_REPORT", "工程设计变更"),
        (r"地质设计", "ENGINEERING_REPORT", "地质设计"),
        (r"工程设计|钻井设计", "ENGINEERING_REPORT", "钻井工程设计"),
        (r"靶点", "ENGINEERING_REPORT", "靶点资料"),
        (r"井史|基本数据|完井卡片|完井总结", "WELL_SUMMARY", "完井资料"),
        (r"固井", "ENGINEERING_REPORT", "固井资料"),
        (r"套管", "ENGINEERING_REPORT", "套管资料"),
        (r"班报", "ENGINEERING_REPORT", "钻井班报"),
    ):
        if re.search(pattern, classify):
            role, purpose = r, p
            break
    phase = "ACTUAL" if role == "TRAJECTORY_DATA" else "PLAN" if re.search(r"设计|靶点|优化", name) and purpose != "工程指令与审批" else ""
    revision = "变更" if re.search(r"变更", name) else "三开优化" if "三开优化" in name else ""
    relations = []
    compact = re.sub(r"\s+", "", text)
    relation = re.search("(" + WELL + r")井变更为(" + WELL + r")井的导眼井", compact)
    if relation:
        kickoff = re.search(r"侧钻点[（(](\d+(?:\.\d+)?)m[）)]", compact)
        relations.append({"pilot": relation.group(1), "sidetrack": relation.group(2),
                          "kickoff_md": float(kickoff.group(1)) if kickoff else None,
                          "evidence": compact[max(0, relation.start()-10):relation.end()+40]})
    return {"well": targets[0] if targets else "", "source_wells": targets,
            "wellbore": "", "directory_well": folder_well,
            "filename_well": filename_well, "site": site,
            "geological_structure": re.sub(r"\s+", "", structure.group()) if structure else "",
            "basin": "鄂尔多斯盆地" if structure else "",
            "structural_position": "鄂尔多斯盆地伊陕斜坡" if structure else "",
            "oilfield": gasfield.group(1) if gasfield else "",
            "development_block": development_block.group(1) if development_block else "",
            "location": location_value,
            "administrative_location": location_value,
            "coordinate_system_text": coordinate_system_text,
            "coordinate_datum_name": coordinate_system_text if coordinate_system_text == "北京54" else "",
            "wellhead_north": north,
            "wellhead_east": east,
            "ground_elevation_m": ground,
            "kelly_bushing_elevation_m": kb,
            "latitude": latitude,
            "longitude": longitude,
            "design_depth_m": design_depth,
            "actual_depth_m": actual_depth,
            "formation": formation,
            "target_metadata": target_text,
            "neighbor_candidates": neighbours,
            "document_role": role, "document_purpose": purpose, "phase": phase,
            "trajectory_kind": "ACTUAL" if phase == "ACTUAL" else "DESIGN" if phase == "PLAN" else None,
            "business_revision": revision, "series_name": purpose,
            "outcome_name": purpose, "conflicts": conflicts, "bore_relationships": relations,
            "identity_evidence": "正文标题" if body_names or multi or (not multi and cover_names) else "原始文件名" if filename_well else "来源目录"}


def build_manifest(records: list[dict]) -> dict:
    """Resolve a whole package without confusing its folders with its entities."""
    coal = [r for r in records if r["relative_path"].split("/")[0].startswith("采气")]
    descriptors = {r["relative_path"]: describe_chinese(PurePosixPath(r["relative_path"]).name,
                   r.get("text_excerpt", ""), r["relative_path"]) for r in coal}
    relations = {x["pilot"]: {**x, "source_path": path} for path, d in descriptors.items()
                 for x in d["bore_relationships"]}
    primaries = sorted({d["directory_well"] for d in descriptors.values() if d["directory_well"]})
    identities = set(primaries) | set(relations)
    # Shared well groups are evidence from the cover of a basic table/card.
    sites, evidence = {}, {}
    for path, d in descriptors.items():
        for source_well in d["source_wells"]:
            if source_well not in identities or not d["site"]:
                continue
            canonical = relations.get(source_well, {}).get("sidetrack", source_well)
            sites.setdefault(canonical, d["site"])
            evidence.setdefault(canonical, []).append({"source_path": path, "site": d["site"],
                                                       "location": d["location"]})
    # No shared group is invented from a numeric prefix. Single-well site
    # labels are derived administrative labels, clearly marked as such.
    files = []
    for r in coal:
        path = r["relative_path"]
        d = descriptors[path]
        targets = []
        for name in d["source_wells"]:
            if name not in identities:
                # A shortened alias (e.g. 米45-30指令) is scoped by its source
                # folder, never turned into another physical well.
                name = d["directory_well"]
            if not name:
                continue
            canonical = relations.get(name, {}).get("sidetrack", name)
            targets.append({"well": canonical, "wellbore": name,
                            "site": sites.get(canonical, canonical),
                            "site_basis": "井组资料" if canonical in sites else "独立井场管理名称（待确认）"})
        extractions = []
        for t in r.get("trajectories", []):
            target = dict(targets[0])
            # The package has one workbook containing both pilot and lateral.
            # Sheet purpose is explicit and independently retained per locator.
            relation = next((x for x in relations.values() if x["sidetrack"] == target["well"]), None)
            if relation and ("先导眼" in t["locator"] or "导眼" in t.get("label", "")):
                target["wellbore"] = relation["pilot"]
            extractions.append({"locator": t["locator"], "kind": t["kind"], **target})
        files.append({"path": path, "sha256": r.get("sha256"), "descriptor": d,
                      "targets": targets, "extractions": extractions})
    return {"primary_wells": primaries, "bore_relationships": list(relations.values()),
            "site_evidence": evidence, "files": sorted(files, key=lambda f: f["path"]),
            "block_status": "UNCONFIRMED",
            "scope_order": ["BLOCK", "FIELD", "WELL", "WELLBORE", "PLAN/SURVEY"]}
