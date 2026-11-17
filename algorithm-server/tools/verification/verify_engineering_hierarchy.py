"""Read-only verification for the block/field/well/wellbore catalog."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path

import pymysql
from pymysql.cursors import DictCursor

from app.domain.chinese_engineering import build_manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True)
    parser.add_argument("--parsed", required=True)
    parser.add_argument("--profile", default=str(Path.home() / ".ruoyi/uploadPath"))
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    records = [json.loads(p.read_text()) for p in Path(args.parsed).glob("*.json")]
    manifest = build_manifest(records)
    db = pymysql.connect(host=os.getenv("MYSQL_HOST", "127.0.0.1"), user=os.getenv("MYSQL_USER", "root"),
                         password=os.environ["MYSQL_PASSWORD"], database=os.getenv("MYSQL_DATABASE", "ry"),
                         cursorclass=DictCursor, charset="utf8mb4")

    def q(sql, parameters=()):
        with db.cursor() as cursor:
            cursor.execute(sql, parameters)
            return cursor.fetchall()

    checked = []
    for item in manifest["files"]:
        rows = q("SELECT s.source_id,s.field_id,f.object_key,f.sha256 FROM drilling_source_document s "
                 "JOIN data_file f ON f.file_id=s.file_id WHERE s.source_path=%s", (item["path"],))
        assert len(rows) == 1, item["path"]
        source = rows[0]
        original = hashlib.sha256((Path(args.root) / item["path"]).read_bytes()).hexdigest()
        archived = hashlib.sha256((Path(args.profile) / source["object_key"]).read_bytes()).hexdigest()
        assert archived == original == source["sha256"] == item["sha256"], item["path"]
        for target in item["targets"]:
            path = q("SELECT n.node_id,n.well_id,n.wellbore_id,n.parent_id,f.field_name,b.block_name "
                     "FROM drilling_engineering_document_file df JOIN drilling_engineering_document d ON d.document_id=df.document_id "
                     "JOIN drilling_engineering_node n ON n.node_id=d.node_id JOIN drilling_well w ON w.well_id=n.well_id "
                     "JOIN drilling_field f ON f.field_id=n.field_id JOIN drilling_block b ON b.block_id=f.block_id "
                     "LEFT JOIN drilling_engineering_revision r ON r.revision_id=d.revision_id "
                     "WHERE df.source_id=%s AND w.well_code=%s AND n.status='ACTIVE' "
                     "AND JSON_EXTRACT(r.properties_json,'$.canonical_revision_id') IS NULL",
                     (source["source_id"], target["well"]))
            assert path, (item["path"], target)
            assert all(row["field_name"] == target["site"] + "井场" for row in path), path
        checked.append({"source_id": source["source_id"], "field_id": source["field_id"],
                        "path": item["path"], "sha256": original})

    checks = {
        "invalid_node_type": "SELECT node_id FROM drilling_engineering_node WHERE node_type NOT IN ('BLOCK','FIELD','WELL','WELLBORE','PLAN','SURVEY','INBOX')",
        "invalid_parent": "SELECT n.node_id FROM drilling_engineering_node n LEFT JOIN drilling_engineering_node p ON p.node_id=n.parent_id WHERE n.status='ACTIVE' AND ((n.node_type='WELL' AND n.parent_id IS NOT NULL) OR (n.node_type='WELLBORE' AND (p.node_type IS NULL OR p.node_type<>'WELL' OR p.well_id<>n.well_id)) OR (n.node_type IN ('PLAN','SURVEY') AND (p.node_type IS NULL OR p.node_type<>'WELLBORE' OR p.wellbore_id<>n.wellbore_id)))",
        "result_bore_mismatch": "SELECT n.node_id FROM drilling_engineering_node n JOIN drilling_engineering_revision r ON r.node_id=n.node_id JOIN drilling_engineering_revision_data rd ON rd.revision_id=r.revision_id JOIN drilling_trajectory_version t ON t.survey_version_id=rd.survey_version_id WHERE n.wellbore_id<>t.wellbore_id OR (n.node_type='PLAN' AND t.trajectory_kind<>'DESIGN') OR (n.node_type='SURVEY' AND t.trajectory_kind<>'ACTUAL')",
        "duplicate_active_bore": "SELECT field_id,wellbore_id FROM drilling_engineering_node WHERE status='ACTIVE' AND node_type='WELLBORE' GROUP BY field_id,wellbore_id HAVING COUNT(*)>1",
        "duplicate_active_well": "SELECT field_id,well_id FROM drilling_engineering_node WHERE status='ACTIVE' AND node_type='WELL' GROUP BY field_id,well_id HAVING COUNT(*)>1",
        "current_version_mismatch": "SELECT h.survey_version_id FROM drilling_trajectory_head h JOIN drilling_trajectory_version t ON t.survey_version_id=h.survey_version_id WHERE h.wellbore_id<>t.wellbore_id OR h.trajectory_kind<>t.trajectory_kind",
        "duplicate_original_link": "SELECT d.node_id,d.revision_id,d.document_role,df.source_id FROM drilling_engineering_document d JOIN drilling_engineering_document_file df ON df.document_id=d.document_id WHERE JSON_EXTRACT(df.properties_json,'$.canonical_document_file_id') IS NULL GROUP BY d.node_id,d.revision_id,d.document_role,df.source_id HAVING COUNT(*)>1",
    }
    invariant_errors = {name: q(sql) for name, sql in checks.items()}
    assert not any(invariant_errors.values()), invariant_errors
    summary = q("SELECT f.field_name,GROUP_CONCAT(DISTINCT w.well_code ORDER BY w.well_code) wells,COUNT(DISTINCT b.wellbore_id) wellbores FROM drilling_field f JOIN drilling_well w ON w.field_id=f.field_id JOIN drilling_wellbore b ON b.well_id=w.well_id WHERE w.status<>'ARCHIVED' GROUP BY f.field_id ORDER BY f.field_name")
    report = {"success": True, "source_files": len(checked), "summary": summary,
              "invariants": invariant_errors, "files": checked,
              "scope_order": ["区块", "井场", "井", "井眼"]}
    Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str))
    db.close()
    print(json.dumps({"success": True, "source_files": len(checked), "invariants": invariant_errors}, ensure_ascii=False))


if __name__ == "__main__":
    main()
