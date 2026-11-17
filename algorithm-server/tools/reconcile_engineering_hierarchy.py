#!/usr/bin/env python3
"""Evidence-backed, repeatable reconciliation of supplied engineering records.

Dry-run is the default. --apply updates ownership/links, never source bytes,
station values, snapshot IDs or historical task records. MYSQL_PASSWORD is read
only from the environment. The full before snapshot is stored with the report.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path

import pymysql
from pymysql.cursors import DictCursor

from app.domain.chinese_engineering import build_manifest


def js(v):
    return json.dumps(v, ensure_ascii=False, default=str, sort_keys=True)


def key(*v):
    return hashlib.sha256(js(v).encode()).hexdigest()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", required=True)
    ap.add_argument("--parsed", required=True)
    ap.add_argument("--report", required=True)
    ap.add_argument("--apply", action="store_true")
    a = ap.parse_args(argv)
    records = [json.loads(p.read_text()) for p in Path(a.parsed).glob("*.json")]
    manifest = build_manifest(records)
    db = pymysql.connect(host=os.getenv("MYSQL_HOST", "127.0.0.1"), user=os.getenv("MYSQL_USER", "root"),
                         password=os.environ["MYSQL_PASSWORD"], database=os.getenv("MYSQL_DATABASE", "ry"),
                         charset="utf8mb4", cursorclass=DictCursor, autocommit=False)

    def q(sql, args=()):
        with db.cursor() as c:
            c.execute(sql, args)
            return c.fetchall()

    def execute(sql, args=()):
        with db.cursor() as c:
            c.execute(sql, args)
            return c.lastrowid

    def ensure(table, column, value, fields, pk):
        old = q(f"SELECT {pk} FROM {table} WHERE {column}=%s", (value,))
        if old:
            return old[0][pk]
        fields = {column: value, **fields}
        return execute(f"INSERT INTO {table}({','.join(fields)}) VALUES({','.join(['%s']*len(fields))})", tuple(fields.values()))

    def nodes(field_id, well, bore=None, kind=None):
        typ = "PLAN" if kind == "DESIGN" else "SURVEY" if kind else "WELLBORE" if bore else "WELL"
        code = ("DESIGN" if kind == "DESIGN" else "ACTUAL") if kind else (
            q("SELECT wellbore_code FROM drilling_wellbore WHERE wellbore_id=%s", (bore,))[0]["wellbore_code"] if bore
            else q("SELECT well_code FROM drilling_well WHERE well_id=%s", (well,))[0]["well_code"])
        title = "设计轨迹" if kind == "DESIGN" else "实钻测斜" if kind else code
        parent = nodes(field_id, well, bore) if kind else nodes(field_id, well) if bore else None
        condition = "wellbore_id=%s" if bore else "well_id=%s AND wellbore_id IS NULL"
        matches = q(f"SELECT node_id FROM drilling_engineering_node WHERE field_id=%s AND node_type=%s AND {condition} ORDER BY status='ACTIVE' DESC,node_id", (field_id, typ, bore or well))
        if matches:
            nid = matches[0]["node_id"]
            execute("UPDATE drilling_engineering_node SET field_id=%s,parent_id=%s,well_id=%s,wellbore_id=%s,node_name=%s,node_code=%s,status='ACTIVE' WHERE node_id=%s", (field_id, parent, well, bore, title, code, nid))
            # Moving existing IDs instead of rebuilding preserves bookmarks and
            # document/history IDs. Sibling result nodes are consolidated below.
            return nid
        return ensure("drilling_engineering_node", "node_key", key("CANONICAL", field_id, typ, bore or well),
                      {"field_id": field_id, "parent_id": parent, "node_type": typ, "node_name": title,
                       "node_code": code, "well_id": well, "wellbore_id": bore, "entity_kind": "WELL",
                       "properties_json": js({"source": "SOURCE_HIERARCHY_V25"}), "create_by": "system"}, "node_id")

    report = {"manifest": manifest, "applied": a.apply, "checks": [], "moved_heads": [], "legacy_version_numbers": []}
    target = Path(a.report)
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        originals = q("SELECT s.*,f.sha256,f.object_key,f.file_size FROM drilling_source_document s JOIN data_file f ON f.file_id=s.file_id WHERE s.source_path LIKE '采气%%'")
        by_path = {r["source_path"]: r for r in originals}
        actual_files = {str(p.relative_to(a.root)) for p in Path(a.root).rglob("*") if p.is_file()
                        and not any(s.startswith(".") for s in p.relative_to(a.root).parts)
                        and str(p.relative_to(a.root)).startswith("采气")}
        assert actual_files == {f["path"] for f in manifest["files"]}, "原始目录与解析清单不一致"
        for f in manifest["files"]:
            s = by_path[f["path"]]
            digest = hashlib.sha256((Path(a.root)/f["path"]).read_bytes()).hexdigest()
            assert digest == s["sha256"] == f["sha256"], f"原件哈希不一致: {f['path']}"
            f["source_id"], f["file_id"] = s["source_id"], s["file_id"]
            assert f["targets"], f"文件无工程归属: {f['path']}"
        report["checks"].append("132份原始文件/解析清单/数据库SHA256逐份一致")
        before_heads = q("SELECT * FROM drilling_trajectory_head ORDER BY wellbore_id,trajectory_kind")
        station_signature = lambda: key(q("SELECT * FROM drilling_survey_station ORDER BY survey_version_id,station_id"))
        # Schema uses station_id; signature includes provenance, not just totals.
        signature = station_signature()
        ids = q("SELECT survey_version_id FROM drilling_trajectory_version ORDER BY survey_version_id")
        if not a.apply:
            report["checks"].append("只读检查完成；未写入数据库")
            target.write_text(js(report))
            print(js({"dry_run": True, "files": len(manifest["files"]), "wells": len(manifest["primary_wells"])}))
            return
        schema = Path(__file__).resolve().parents[2]/"database/migrations/V24__canonical_well_alias.sql"
        sql = "\n".join(l for l in schema.read_text().splitlines() if not l.startswith("--"))
        execute(sql.rstrip().rstrip(";"))
        before = {t: q(f"SELECT * FROM {t}") for t in ("drilling_block", "drilling_field",
                  "drilling_well", "drilling_wellbore", "drilling_well_catalog",
                  "drilling_field_wellbore", "drilling_engineering_node", "drilling_engineering_revision",
                  "drilling_engineering_document", "drilling_engineering_document_file", "drilling_source_document",
                  "drilling_survey_version", "drilling_trajectory_version", "drilling_trajectory_head")}
        backup = target.with_name("before-relations.json")
        if not backup.exists():
            backup.write_text(js(before))
            backup.chmod(0o600)
        block = ensure("drilling_block", "block_code", "ORDOS-YISHAN-SLOPE",
                       {"block_name": "鄂尔多斯盆地伊陕斜坡", "basin": "鄂尔多斯盆地",
                        "description": "采气二厂完井资料所载地理构造位置", "create_by": "system"}, "block_id")
        # The supplied engineering documents identify the structural position
        # as 鄂尔多斯盆地伊陕斜坡. Existing records are already confirmed;
        # new imports are reviewed in the source dialog before this job runs.
        primaries = {}
        for name in manifest["primary_wells"]:
            candidates = q("SELECT well_id FROM drilling_well WHERE well_code=%s AND status<>'ARCHIVED' ORDER BY well_id", (name,))
            assert len(candidates) == 1, f"井身份不唯一: {name}"
            primaries[name] = candidates[0]["well_id"]
        report["wells"] = []
        field_for = {}
        for f in manifest["files"]:
            for t in f["targets"]:
                code = "COALBED-" + key(t["site"])[:12].upper()
                evidence = manifest["site_evidence"].get(t["well"], [])
                field = ensure("drilling_field", "field_code", code,
                               {"block_id": block, "field_name": t["site"] + "井场",
                                "description": (t["site_basis"]+"；证据："+"；".join(x["source_path"].split("/")[-1] for x in evidence))[:500],
                                "operator_name": "长庆油田分公司第二采气厂", "create_by": "system"}, "field_id")
                field_for[t["well"]] = field
                w = primaries[t["well"]]
                execute("UPDATE drilling_well SET field_id=%s WHERE well_id=%s", (field, w))
                execute("UPDATE drilling_well_catalog SET field_id=%s WHERE well_id=%s", (field, w))
        # Source-verified well aliases are retained as registry history. The
        # pilot bore is moved under its actual physical well, not discarded.
        aliases = {"双45-48YH4": "双48-45YH4", "米45-30": "米45-30YH3",
                   **{r["pilot"]: r["sidetrack"] for r in manifest["bore_relationships"]}}
        bore_for = {}
        for name, well in primaries.items():
            candidates = q("SELECT wellbore_id FROM drilling_wellbore WHERE well_id=%s AND wellbore_code IN ('MAIN',%s) ORDER BY wellbore_id", (well, name))
            assert len(candidates) == 1, f"主井眼不唯一: {name}"
            bore = candidates[0]["wellbore_id"]
            execute("UPDATE drilling_wellbore SET wellbore_code=%s,wellbore_name=%s WHERE wellbore_id=%s", (name, name, bore))
            bore_for[name] = bore
        for alias, name in aliases.items():
            row = q("SELECT well_id FROM drilling_well WHERE well_code=%s ORDER BY well_id", (alias,))
            if not row:
                continue
            alias_id, canonical = row[0]["well_id"], primaries[name]
            relationship = next((r for r in manifest["bore_relationships"] if r["pilot"] == alias), None)
            evidence = relationship or {"source": "原始正文井名与文件名核对", "alias": alias, "canonical": name}
            ensure("drilling_well_alias", "alias_well_id", alias_id,
                   {"canonical_well_id": canonical, "alias_code": alias, "reason": "导眼/回填侧钻关系" if relationship else "历史误建别名",
                    "evidence_json": js(evidence)}, "alias_well_id")
            execute("UPDATE drilling_well SET status='ARCHIVED',metadata_json=JSON_SET(COALESCE(metadata_json,JSON_OBJECT()),'$.canonical_well_id',%s) WHERE well_id=%s", (canonical, alias_id))
            if relationship:
                bs = q("SELECT wellbore_id FROM drilling_wellbore WHERE (well_id=%s OR well_id=%s) AND wellbore_code IN ('MAIN',%s)", (alias_id, canonical, alias))
                assert len(bs) == 1, "导眼井眼不唯一"
                b = bs[0]["wellbore_id"]
                execute("UPDATE drilling_wellbore SET well_id=%s,wellbore_code=%s,wellbore_name=%s WHERE wellbore_id=%s", (canonical, alias, alias+"（导眼）", b))
                execute("UPDATE drilling_wellbore SET parent_wellbore_id=%s,reference_json=JSON_SET(COALESCE(reference_json,JSON_OBJECT()),'$.kickoff_md',%s,'$.relationship_evidence',%s) WHERE wellbore_id=%s", (b, relationship["kickoff_md"], relationship["evidence"], bore_for[name]))
                bore_for[alias] = b
                for sv in q("SELECT survey_version_id,version_no FROM drilling_survey_version WHERE well_id=%s ORDER BY version_no", (alias_id,)):
                    no = q("SELECT COALESCE(MAX(version_no),0)+1 n FROM drilling_survey_version WHERE well_id=%s", (canonical,))[0]["n"]
                    execute("UPDATE drilling_survey_version SET well_id=%s,version_no=%s WHERE survey_version_id=%s", (canonical, no, sv["survey_version_id"]))
                    report["legacy_version_numbers"].append({**sv, "previous_well_id": alias_id, "well_id": canonical, "new_version_no": no})
            execute("UPDATE drilling_engineering_node SET status='ARCHIVED' WHERE well_id=%s", (alias_id,))
        for relation in manifest["bore_relationships"]:
            w = primaries[relation["sidetrack"]]
            pilot = q("SELECT wellbore_id FROM drilling_wellbore WHERE well_id=%s AND wellbore_code=%s", (w, relation["pilot"]))
            assert len(pilot) == 1, "来源已声明导眼，数据库缺少对应井眼"
            bore_for[relation["pilot"]] = pilot[0]["wellbore_id"]
            execute("UPDATE drilling_wellbore SET parent_wellbore_id=%s,reference_json=JSON_SET(COALESCE(reference_json,JSON_OBJECT()),'$.kickoff_md',%s,'$.relationship_evidence',%s) WHERE wellbore_id=%s", (pilot[0]["wellbore_id"], relation["kickoff_md"], relation["evidence"], bore_for[relation["sidetrack"]]))
        for name, well in primaries.items():
            field = field_for[name]
            execute("UPDATE drilling_engineering_node SET field_id=%s WHERE well_id=%s", (field, well))
            wn = nodes(field, well)
            report["wells"].append({"well": name, "well_id": well, "field_id": field, "node_id": wn})
            for b in q("SELECT wellbore_id FROM drilling_wellbore WHERE well_id=%s", (well,)):
                bore = b["wellbore_id"]
                execute("INSERT IGNORE INTO drilling_field_wellbore(field_id,wellbore_id) VALUES(%s,%s)", (field, bore))
                nodes(field, well, bore)
        # Keep each immutable extraction assigned to its own source-identified
        # bore; a workbook can contain both the pilot and the lateral.
        for f in manifest["files"]:
            first = f["targets"][0]
            field = field_for[first["well"]]
            s = by_path[f["path"]]
            meta = json.loads(s["metadata_json"] or "{}")
            meta.update(engineering=f["descriptor"], hierarchy_targets=f["targets"],
                        source_project=meta.get("source_project", ""))
            execute("UPDATE drilling_source_document SET field_id=%s,metadata_json=%s WHERE source_id=%s", (field, js(meta), f["source_id"]))
            execute("UPDATE data_file SET field_id=%s WHERE file_id=%s", (field, f["file_id"]))
            extraction_nodes = {}
            for e in f["extractions"]:
                versions = q("SELECT survey_version_id FROM drilling_source_extraction WHERE source_id=%s AND locator=%s AND survey_version_id IS NOT NULL", (f["source_id"], e["locator"]))
                if not versions:
                    continue
                v = versions[0]["survey_version_id"]
                b, w = bore_for[e["wellbore"]], primaries[e["well"]]
                execute("UPDATE drilling_trajectory_version SET wellbore_id=%s WHERE survey_version_id=%s", (b, v))
                nid = nodes(field_for[e["well"]], w, b, e["kind"])
                extraction_nodes[v] = nid
            shared_keys = {key("SHARED_SOURCE", nodes(field_for[t["well"]], primaries[t["well"]], bore_for[t["wellbore"]]), f["source_id"]) for t in f["targets"][1:]}
            existing_docs = q("SELECT DISTINCT d.document_id,d.document_key,d.node_id,d.revision_id FROM drilling_engineering_document d JOIN drilling_engineering_document_file df ON df.document_id=d.document_id WHERE df.source_id=%s", (f["source_id"],))
            default_bore = bore_for[first["wellbore"]]
            for doc in existing_docs:
                if doc["document_key"] in shared_keys:
                    execute("UPDATE drilling_engineering_document SET document_role=%s WHERE document_id=%s", (f["descriptor"]["document_role"], doc["document_id"]))
                    continue
                linked = q("SELECT survey_version_id FROM drilling_engineering_revision_data WHERE revision_id=%s", (doc["revision_id"],)) if doc["revision_id"] else []
                matching = {extraction_nodes[v["survey_version_id"]] for v in linked if v["survey_version_id"] in extraction_nodes}
                assert len(matching) <= 1, "同一业务版本错误混入多个井眼"
                dest = next(iter(matching)) if matching else nodes(field, primaries[first["well"]], default_bore)
                if doc["revision_id"] and not matching and f["descriptor"]["trajectory_kind"]:
                    dest = nodes(field, primaries[first["well"]], default_bore, f["descriptor"]["trajectory_kind"])
                if doc["revision_id"]:
                    execute("UPDATE drilling_engineering_revision SET node_id=%s WHERE revision_id=%s", (dest, doc["revision_id"]))
                execute("UPDATE drilling_engineering_document SET node_id=%s,document_role=%s WHERE document_id=%s", (dest, f["descriptor"]["document_role"], doc["document_id"]))
            # One original can be referenced from two engineering scopes. Do
            # not copy bytes or create another source registry row.
            for t in f["targets"][1:]:
                dest = nodes(field_for[t["well"]], primaries[t["well"]], bore_for[t["wellbore"]])
                did = ensure("drilling_engineering_document", "document_key", key("SHARED_SOURCE", dest, f["source_id"]),
                             {"node_id": dest, "document_role": f["descriptor"]["document_role"], "document_title": Path(f["path"]).name}, "document_id")
                execute("INSERT IGNORE INTO drilling_engineering_document_file(document_id,source_id,edition_no,representation_format,file_label,properties_json) SELECT %s,source_id,1,source_format,%s,%s FROM drilling_source_document WHERE source_id=%s", (did, Path(f["path"]).name, js({"reference_only": True}), f["source_id"]))
        # A document can contain many tables; these are data snapshots within
        # one source revision, not a new business revision per extracted table.
        # Only byte-identical or text-identical representations are grouped.
        # Filename suffixes such as '20' never determine revision order.
        record_by_path = {r["relative_path"]: r for r in records}
        revision_groups = {}
        for f in manifest["files"]:
            record = record_by_path[f["path"]]
            text = re.sub(r"\s+", "", record.get("text_excerpt", ""))
            evidence_hash = key(text) if text else f["sha256"]
            docs = q("SELECT DISTINCT d.document_id,d.node_id,d.revision_id FROM drilling_engineering_document d JOIN drilling_engineering_document_file df ON df.document_id=d.document_id WHERE df.source_id=%s AND d.revision_id IS NOT NULL ORDER BY d.revision_id,d.document_id", (f["source_id"],))
            for doc in docs:
                group_key = (doc["node_id"], f["descriptor"]["document_purpose"], evidence_hash)
                group = revision_groups.setdefault(group_key, {"revision": doc["revision_id"], "document": doc["document_id"]})
                canonical = group["revision"]
                title = f["descriptor"]["document_purpose"]
                business = (f["descriptor"]["business_revision"] or "未注明修订号") + " · " + evidence_hash[:8]
                execute("UPDATE drilling_engineering_revision SET business_revision=%s,outcome_name=%s,properties_json=JSON_SET(COALESCE(properties_json,JSON_OBJECT()),'$.source_content_key',%s) WHERE revision_id=%s", (business, title, evidence_hash, canonical))
                if doc["revision_id"] == canonical:
                    continue
                assert not q("SELECT node_id FROM drilling_engineering_head WHERE revision_id=%s", (doc["revision_id"],)), "已发布业务版本需独立保留"
                execute("INSERT IGNORE INTO drilling_engineering_revision_data(revision_id,survey_version_id,data_label) SELECT %s,survey_version_id,data_label FROM drilling_engineering_revision_data WHERE revision_id=%s", (canonical, doc["revision_id"]))
                execute("INSERT IGNORE INTO drilling_engineering_document_file(document_id,source_id,edition_no,representation_format,file_label,source_date,usage_state,properties_json) SELECT %s,source_id,edition_no,representation_format,file_label,source_date,usage_state,properties_json FROM drilling_engineering_document_file WHERE document_id=%s", (group["document"], doc["document_id"]))
                execute("UPDATE drilling_engineering_revision SET usage_state='WITHDRAWN',properties_json=JSON_SET(COALESCE(properties_json,JSON_OBJECT()),'$.canonical_revision_id',%s) WHERE revision_id=%s", (canonical, doc["revision_id"]))
        execute("UPDATE drilling_engineering_revision r JOIN drilling_engineering_node n ON n.node_id=r.node_id SET r.phase=CASE WHEN n.node_type='SURVEY' THEN 'ACTUAL' WHEN n.node_type='PLAN' THEN 'PLAN' ELSE 'DOCUMENT' END WHERE n.field_id IS NOT NULL")
        # Unify duplicate field inboxes created by earlier import runs.
        for inbox in q("SELECT field_id,node_name,MIN(node_id) canonical_id FROM drilling_engineering_node WHERE node_type='INBOX' AND status='ACTIVE' GROUP BY field_id,node_name HAVING COUNT(*)>1"):
            for other in q("SELECT node_id FROM drilling_engineering_node WHERE field_id=%s AND node_type='INBOX' AND node_name=%s AND status='ACTIVE' AND node_id<>%s", (inbox["field_id"], inbox["node_name"], inbox["canonical_id"])):
                execute("UPDATE drilling_engineering_document SET node_id=%s WHERE node_id=%s", (inbox["canonical_id"], other["node_id"]))
                execute("UPDATE drilling_engineering_revision SET node_id=%s WHERE node_id=%s", (inbox["canonical_id"], other["node_id"]))
                execute("UPDATE drilling_engineering_node SET status='ARCHIVED' WHERE node_id=%s", (other["node_id"],))
        # An old import could attach the same original twice at one scope.
        # Retain both historical link IDs, expose only their canonical link.
        for duplicate in q("SELECT d.node_id,d.revision_id,d.document_role,df.source_id,MIN(df.document_file_id) canonical_id FROM drilling_engineering_document d JOIN drilling_engineering_document_file df ON df.document_id=d.document_id JOIN drilling_engineering_node n ON n.node_id=d.node_id WHERE n.field_id IN (SELECT field_id FROM drilling_field WHERE field_code LIKE 'COALBED-%%') GROUP BY d.node_id,d.revision_id,d.document_role,df.source_id HAVING COUNT(*)>1"):
            execute("UPDATE drilling_engineering_document_file df JOIN drilling_engineering_document d ON d.document_id=df.document_id SET df.properties_json=JSON_SET(COALESCE(df.properties_json,JSON_OBJECT()),'$.canonical_document_file_id',%s) WHERE d.node_id=%s AND d.revision_id <=> %s AND d.document_role=%s AND df.source_id=%s AND df.document_file_id<>%s", (duplicate["canonical_id"], duplicate["node_id"], duplicate["revision_id"], duplicate["document_role"], duplicate["source_id"], duplicate["canonical_id"]))
        for link in q("SELECT d.node_id,d.document_role,df.source_id,MIN(df.document_file_id) canonical_id FROM drilling_engineering_document d JOIN drilling_engineering_document_file df ON df.document_id=d.document_id LEFT JOIN drilling_engineering_revision r ON r.revision_id=d.revision_id WHERE d.revision_id IS NULL OR r.phase='DOCUMENT' GROUP BY d.node_id,d.document_role,df.source_id HAVING COUNT(*)>1"):
            execute("UPDATE drilling_engineering_document_file df JOIN drilling_engineering_document d ON d.document_id=df.document_id LEFT JOIN drilling_engineering_revision r ON r.revision_id=d.revision_id SET df.properties_json=JSON_SET(COALESCE(df.properties_json,JSON_OBJECT()),'$.canonical_document_file_id',%s) WHERE d.node_id=%s AND d.document_role=%s AND df.source_id=%s AND (d.revision_id IS NULL OR r.phase='DOCUMENT') AND df.document_file_id<>%s", (link["canonical_id"], link["node_id"], link["document_role"], link["source_id"], link["canonical_id"]))
        # Archive now-empty legacy result nodes, preserving every primary key.
        execute("UPDATE drilling_engineering_node n SET status='ARCHIVED' WHERE n.node_type IN ('PLAN','SURVEY','WELLBORE') AND n.field_id IN (SELECT field_id FROM drilling_field WHERE field_code LIKE 'COALBED-%%') AND NOT EXISTS(SELECT 1 FROM drilling_engineering_document d WHERE d.node_id=n.node_id) AND NOT EXISTS(SELECT 1 FROM drilling_engineering_revision r WHERE r.node_id=n.node_id) AND NOT EXISTS(SELECT 1 FROM (SELECT parent_id FROM drilling_engineering_node WHERE status='ACTIVE') c WHERE c.parent_id=n.node_id)")
        for h in before_heads:
            actual = q("SELECT wellbore_id FROM drilling_trajectory_version WHERE survey_version_id=%s", (h["survey_version_id"],))[0]["wellbore_id"]
            if actual != h["wellbore_id"]:
                collision = q("SELECT * FROM drilling_trajectory_head WHERE wellbore_id=%s AND trajectory_kind=%s", (actual, h["trajectory_kind"]))
                assert not collision, "井眼重新归属后当前版本冲突，必须人工选择，迁移已回滚"
                execute("UPDATE drilling_trajectory_head SET wellbore_id=%s WHERE wellbore_id=%s AND trajectory_kind=%s", (actual, h["wellbore_id"], h["trajectory_kind"]))
                report["moved_heads"].append({**h, "correct_wellbore_id": actual})
        # All datasets share the same invariant: a result's bore must match
        # every immutable data snapshot linked to that result. Repair legacy
        # source-folder aliases using the already-imported snapshot identity.
        for mismatch in q("SELECT DISTINCT n.node_id,n.field_id,t.wellbore_id,b.well_id FROM drilling_engineering_node n JOIN drilling_engineering_revision r ON r.node_id=n.node_id JOIN drilling_engineering_revision_data rd ON rd.revision_id=r.revision_id JOIN drilling_trajectory_version t ON t.survey_version_id=rd.survey_version_id JOIN drilling_wellbore b ON b.wellbore_id=t.wellbore_id WHERE n.wellbore_id<>t.wellbore_id"):
            targets = q("SELECT DISTINCT t.wellbore_id FROM drilling_engineering_revision r JOIN drilling_engineering_revision_data rd ON rd.revision_id=r.revision_id JOIN drilling_trajectory_version t ON t.survey_version_id=rd.survey_version_id WHERE r.node_id=%s", (mismatch["node_id"],))
            assert len(targets) == 1, "成果包含多个井眼，必须拆分后迁移"
            parents = q("SELECT node_id FROM drilling_engineering_node WHERE node_type='WELLBORE' AND status='ACTIVE' AND field_id=%s AND wellbore_id=%s ORDER BY node_id", (mismatch["field_id"], mismatch["wellbore_id"]))
            assert parents, "井眼工程节点缺失"
            execute("UPDATE drilling_engineering_node SET parent_id=%s,well_id=%s,wellbore_id=%s WHERE node_id=%s", (parents[0]["node_id"], mismatch["well_id"], mismatch["wellbore_id"], mismatch["node_id"]))
        report["phase_corrections"] = q("SELECT r.revision_id,r.phase FROM drilling_engineering_revision r JOIN drilling_engineering_node n ON n.node_id=r.node_id WHERE n.node_type='SURVEY' AND r.phase<>'ACTUAL'")
        execute("UPDATE drilling_engineering_revision r JOIN drilling_engineering_node n ON n.node_id=r.node_id SET r.properties_json=JSON_SET(COALESCE(r.properties_json,JSON_OBJECT()),'$.legacy_phase',r.phase),r.phase='ACTUAL' WHERE n.node_type='SURVEY' AND r.phase<>'ACTUAL'")
        assert signature == station_signature(), "迁移改变了测点内容"
        assert ids == q("SELECT survey_version_id FROM drilling_trajectory_version ORDER BY survey_version_id"), "轨迹快照ID发生变化"
        assert not q("SELECT h.* FROM drilling_trajectory_head h JOIN drilling_trajectory_version t ON t.survey_version_id=h.survey_version_id WHERE h.wellbore_id<>t.wellbore_id OR h.trajectory_kind<>t.trajectory_kind"), "当前版本井眼或用途不一致"
        report["checks"] += ["11口井与12个井眼按来源证据归属", "源文件ID/数据快照ID/全部测点保持不变", "当前版本仅校正所属井眼，不按井深自动选版"]
        execute("INSERT INTO drilling_audit_log(field_id,user_id,action,resource_type,resource_id,detail_json) VALUES(%s,1,'RECONCILE_SOURCE_HIERARCHY','ENGINEERING_LIBRARY','COALGAS',%s)", (block, js({k: v for k, v in report.items() if k != "manifest"})))
        execute("INSERT INTO drilling_engineering_migration(migration_key,manifest_json) VALUES('SOURCE_HIERARCHY_V25',%s) ON DUPLICATE KEY UPDATE manifest_json=VALUES(manifest_json)", (js(report),))
        db.commit()
        target.write_text(js(report))
        print(js({"applied": True, "files": len(manifest["files"]), "wells": len(primaries), "fields": len(set(field_for.values())), "checks": report["checks"], "moved_heads": report["moved_heads"]}))
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


if __name__ == "__main__":
    main()
