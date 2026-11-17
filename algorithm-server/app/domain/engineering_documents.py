"""Business identity of engineering documents, independent of file encoding.

Source headings are evidence. Folder names are aliases/candidates, never a
replacement for a conflicting Well/Wellbore heading. No current revision is
chosen by file time, depth or station count.
"""
from __future__ import annotations

import re
from datetime import datetime
from pathlib import PurePosixPath


def identity_name(value: str) -> str:
    value = re.sub(r"^NO\s*", "", value.strip(), flags=re.I)
    value = re.sub(r"^15[/_]9[-_]?", "", value, flags=re.I)
    return re.sub(r"[^\w\u4e00-\u9fff]", "", value).casefold()


def _date(value):
    for fmt in ("%d.%m.%Y", "%d/%m/%Y", "%Y-%m-%d", "%Y.%m.%d"):
        try:
            return datetime.strptime(value.strip(), fmt).date().isoformat()
        except (ValueError, AttributeError):
            pass
    return None


def describe_document(name: str, text: str = "", source_path: str = "") -> dict:
    if re.search(r"(?:米|双|麒)\s*\d", name + text[:1400] + source_path):
        from app.domain.chinese_engineering import describe_chinese
        return {"source_project": "", "block_code": "", "source_namespace": {},
                "directory_wellbore": "", "entity_kind": "WELL", "usage_state": "HISTORICAL",
                "parent_wellbore": "", "survey_date": None, "extraction_date": None,
                **describe_chinese(name, text, source_path)}
    path = PurePosixPath(source_path or name)
    # Some native files have dots inside revision labels, not an extension.
    stem = re.sub(r"\.(?:pdf|txt|csv|tsv|xls[xm]?|docx?|xml|witsml|zip|md)$", "", name, flags=re.I)
    native = {}
    for line in text.splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
            native.setdefault(key.strip().upper(), value.strip())

    def heading(label, stops):
        found = re.search(r"(?im)^\s*" + label + r"\s*:\s*(.+)$", text)
        if not found:
            return ""
        value = found.group(1).strip()
        return re.split(r"\s+(?:" + stops + r")\s*:", value, maxsplit=1, flags=re.I)[0].strip()

    source_project = heading("Project", "TVD Reference|Local Co-ordinate Reference")
    site = heading("Site", "MD Reference|TVD Reference|North Reference")
    well = native.get("WELL NAME") or heading("Well", "North Reference|TVD Reference|MD Reference")
    bore = native.get("WELLBORE NAME") or heading("Wellbore", "Survey Calculation Method|Magnetics")
    outcome = native.get("SURVEY NAME") or heading("Design", "Database")
    # This Statoil export calls the COMPASS Project FIELD. Keep the alias and
    # the raw heading instead of interpreting it as geographic field/block.
    if not source_project and "SURVEY LIST" in text:
        source_project = native.get("FIELD", "")
    phase_match = re.search(r"_(ACTUAL|PLAN|PROTOTYPE)$", stem, re.I)
    phase = phase_match.group(1).upper() if phase_match else ""
    explicit_phase = re.search(r"\bPhase\s*:\s*(ACTUAL|PLAN|PROTOTYPE)\b", text, re.I)
    if explicit_phase:
        phase = explicit_phase.group(1).upper()
    if not phase and "SURVEY LIST" in text:
        phase = "ACTUAL"
    if not phase and re.search(r"(?:设计|设计方案|设计轨迹)", name):
        phase = "PLAN"
    if not phase and re.search(r"(?:实测|实钻|完井测斜|测斜数据)", name):
        phase = "ACTUAL"

    directory_well = directory_bore = ""
    parts = path.parts
    indexes = [i for i, value in enumerate(parts) if value == "WellWellbore"]
    if indexes:
        i = indexes[-1]
        if len(parts) > i + 1:
            directory_well = parts[i + 1]
        if len(parts) > i + 3:
            directory_bore = parts[i + 2]
    conflicts = []
    for key, body_value, folder_value in (("well", well, directory_well), ("wellbore", bore, directory_bore)):
        if body_value and folder_value and identity_name(body_value) != identity_name(folder_value):
            conflicts.append({"field": key, "body": body_value, "directory": folder_value})
    # Known hierarchy folders may identify a source even when a document has
    # no identity heading. Record that provenance, not an invented heading.
    well = well or directory_well
    bore = bore or directory_bore
    if not well:
        chinese = re.search(r"(?:米|双|麒)\d+(?:-\d+)?(?:Y?H\d*)?", name)
        if chinese:
            well = chinese.group()
    if not outcome and phase_match:
        tokens = stem.rsplit("_", 1)[0].split("_")
        outcome = tokens[-1] if len(tokens) > 1 else stem
    revision = re.search(r"\brev(?:ision)?\s*[#p]?\s*\d+(?:[.,]\d+)*", outcome, re.I)
    revision_label = revision.group().strip() if revision else ""
    family = outcome[:revision.start()].strip(" _-") if revision else outcome
    family = family or (bore + " 设计方案" if bore else stem)
    forbidden = bool(re.search(r"DO\s+NOT\s+USE|TO\s+BE\s+ERASED", stem, re.I))
    relief = bool(re.search(r"relief\s*well|救援井|救险井", " ".join((name, well, bore, outcome)), re.I))
    lower = name.lower()
    if lower.endswith(".zip"):
        role = "ARCHIVE"
    elif "wellsummary" in lower.replace(" ", "") or "Well Level Summary Report" in text:
        role = "WELL_SUMMARY"
    elif re.search(r"production[ _-]*data|生产数据", name, re.I):
        role = "PRODUCTION_DATA"
    elif "Standard Survey" in name or "Survey Report" in text:
        role = "TRAJECTORY_REPORT"
    elif phase:
        role = "TRAJECTORY_DATA"
    elif re.search(r"discovery|\bpud\b|勘探|开发方案|工程设计|完井报告|钻井设计", name, re.I):
        role = "ENGINEERING_REPORT"
    else:
        role = "REFERENCE"
    if role == "WELL_SUMMARY":
        bore = ""
    if not source_project:
        source_project = heading("工程名称|项目名称", "建设单位|井号")
    block_match = re.search(r"(?im)^\s*Site\s+[^\n,]+,\s*(\d+/\d+)\s*$", text)
    return {
        "source_project": source_project, "site": site,
        "block_code": block_match.group(1) if block_match else "",
        "well": well, "wellbore": bore,
        "directory_well": directory_well, "directory_wellbore": directory_bore,
        "outcome_name": outcome, "series_name": family,
        "business_revision": revision_label, "phase": phase,
        "trajectory_kind": "ACTUAL" if phase == "ACTUAL" else "DESIGN" if phase in ("PLAN", "PROTOTYPE") else None,
        "document_role": role, "entity_kind": "PLANNED_RELIEF" if relief else "WELL",
        "usage_state": "FORBIDDEN" if forbidden else "DRAFT" if phase == "PROTOTYPE" else "HISTORICAL",
        "parent_wellbore": native.get("DRILLED FROM", ""),
        "kickoff_depth_raw": native.get("KICK OFF DEPTH", ""),
        "survey_date": _date(native.get("SURVEY DATE")),
        "extraction_date": _date(native.get("EXTRACTION DATE")),
        "conflicts": conflicts,
        "source_namespace": {k: native[k] for k in ("FIELD", "COMPANY") if k in native},
    }
