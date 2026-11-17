#!/usr/bin/env python3
"""Register immutable system reference files.

The reference standard is deliberately kept outside the block/field/well tree.
This command is idempotent by SHA-256 and stores only a catalog of appendix
tables; the PDF itself remains the authoritative, byte-for-byte source.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
import shutil
from pathlib import Path

import pymysql
from pymysql.cursors import DictCursor


APPENDIX_A = [
    {"code": "A.1", "title": "井基本数据表", "source_title": "Preliminary data for Well × × ×", "pdf_page": 50,
     "source_fields": ["Geodetic coordinate system", "North reference", "Depth datum", "ASL (m)", "Vertical coordinate X (m)", "Horizontal coordinate Y (m)", "Grid convergence (°)", "Magnetic declination (°)", "Rotary Kelly Bushing (RKB) (m)", "Pocket length (m)"],
     "fields": ["地理坐标系", "北向基准", "深度基准", "海拔", "井口纵坐标 X", "井口横坐标 Y", "子午线收敛角", "磁偏角", "转盘补心高程", "鼠洞长度"], "source_notes": []},
    {"code": "A.2", "title": "靶区数据表", "source_title": "Target area data for Well × × ×", "pdf_page": 50,
     "source_fields": ["Target name", "ASL (m)", "Vertical coordinate X (m)", "Horizontal coordinate Y (m)", "TVD (m)", "Closure distance (m)", "Closure azimuth (°)", "Shape", "Radius (m)", "Height (m)", "Width (m)"],
     "fields": ["靶点名称", "海拔", "垂直坐标 X", "水平坐标 Y", "垂深 TVD", "闭合距", "闭合方位", "形状", "半径", "高度", "宽度"],
     "source_notes": ["The geometrical dimensions in Target geometry section shall be filled out as per target shape."]},
    {"code": "A.3", "title": "设计轨道节点数据表", "source_title": "Node points data of planned wellpath for Well × × ×", "pdf_page": 50,
     "source_fields": ["MD (m)", "Inc (°)", "Azi (°)", "TVD (m)", "N/S (m)", "E/W (m)", "Closure distance (m)", "Closure azimuth (°)", "Apparent horizontal length (m)", "Wellbore curvature (°)/30m", "Remarks"],
     "fields": ["MD", "井斜角", "方位角", "TVD", "北位移 N/S", "东位移 E/W", "闭合距", "闭合方位", "水平投影长度", "井眼曲率", "备注"],
     "source_notes": ["The node points shall include the wellhead, kick-off point and target points and shall be noted in Remarks column."]},
    {"code": "A.4", "title": "设计轨道插值点数据表", "source_title": "Interpolated points data of planned wellpath for Well × × ×", "pdf_page": 51,
     "source_fields": ["MD (m)", "Inc (°)", "Azi (°)", "TVD (m)", "N/S (m)", "E/W (m)", "Closure distance (m)", "Closure azimuth (°)", "Apparent horizontal length (m)", "Wellbore curvature (°)/30m", "Remarks"],
     "fields": ["MD", "井斜角", "方位角", "TVD", "北位移 N/S", "东位移 E/W", "闭合距", "闭合方位", "水平投影长度", "井眼曲率", "备注"],
     "source_notes": ["Node points within the output hole section shall be included and noted in the Remarks column."]},
    {"code": "A.5", "title": "实钻轨迹测斜数据表", "source_title": "Survey data of drilled trajectory for Well × × ×", "pdf_page": 51,
     "source_fields": ["Calculation method", "MD (m)", "Inc (°)", "Azi (°)", "TVD (m)", "N/S (m)", "E/W (m)", "Closure distance (m)", "Closure azimuth (°)", "Apparent horizontal length (m)", "Wellbore curvature (°)/30m", "Remarks"],
     "fields": ["计算方法", "MD", "井斜角", "方位角", "TVD", "北位移 N/S", "东位移 E/W", "闭合距", "闭合方位", "水平投影长度", "井眼曲率", "备注"],
     "source_notes": ["Interpolated points, if any, shall be noted in the Remarks column."]},
    {"code": "A.6", "title": "实钻轨迹靶点数据表", "source_title": "Target entry data of drilled trajectory for Well × × ×", "pdf_page": 51,
     "source_fields": ["Target name", "MD (m)", "Inc (°)", "Azi (°)", "TVD (m)", "Closure distance (m)", "Closure azimuth (°)", "Offset distance (m)", "Longitudinal offset distance (m)", "Latitudinal offset distance (m)", "Remarks"],
     "fields": ["靶点名称", "MD", "井斜角", "方位角", "TVD", "闭合距", "闭合方位", "偏移距", "纵向偏移距", "横向偏移距", "备注"],
     "source_notes": ["The two terms Longitudinal Offset Distance and Latitudinal Offset Distance may be changed in accordance with the orientation of target plane. For instance, northward offset and eastward offset should be used for a horizontal target.", "Information of hitting a target or missing a target should be noted in the Remarks column."]},
]


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", required=True, type=Path)
    parser.add_argument("--profile", default=str(Path.home() / ".ruoyi/uploadPath"))
    parser.add_argument("--source-note", default="用户提供的 SY/T 5435-2012 参考原件")
    args = parser.parse_args(argv)
    source = args.file.expanduser().resolve()
    if not source.is_file():
        raise FileNotFoundError(source)
    content = source.read_bytes()
    digest = sha256(content)
    profile = Path(args.profile).expanduser().resolve()
    object_dir = profile / "reference-materials"
    object_dir.mkdir(parents=True, exist_ok=True)
    suffix = source.suffix.lower() or ".bin"
    object_key = f"reference-materials/{digest}{suffix}"
    destination = profile / object_key
    if not destination.exists():
        shutil.copy2(source, destination)
    if sha256(destination.read_bytes()) != digest:
        raise RuntimeError("参考资料归档字节校验失败")

    db = pymysql.connect(
        host=os.getenv("MYSQL_HOST", "127.0.0.1"),
        port=int(os.getenv("MYSQL_PORT", "3306")),
        user=os.getenv("MYSQL_USER", "root"),
        password=os.environ["MYSQL_PASSWORD"],
        database=os.getenv("MYSQL_DATABASE", "ry"),
        charset="utf8mb4", cursorclass=DictCursor, autocommit=False,
    )
    try:
        with db.cursor() as cur:
            cur.execute("SELECT file_id FROM data_file WHERE sha256=%s AND field_id IS NULL ORDER BY file_id LIMIT 1", (digest,))
            row = cur.fetchone()
            if row:
                file_id = row["file_id"]
            else:
                cur.execute(
                    """INSERT INTO data_file(field_id,owner_user_id,file_category,business_type,
                    original_name,object_key,content_type,file_size,sha256,create_by)
                    VALUES(NULL,1,'REFERENCE','STANDARD',%s,%s,%s,%s,%s,'admin')""",
                    (source.name, object_key, mimetypes.guess_type(source.name)[0] or "application/pdf", len(content), digest),
                )
                file_id = cur.lastrowid
            cur.execute("SELECT reference_id FROM drilling_reference_material WHERE material_code=%s", ("SY-T-5435-2012",))
            existing = cur.fetchone()
            payload = json.dumps(APPENDIX_A, ensure_ascii=False)
            if existing:
                cur.execute(
                    """UPDATE drilling_reference_material
                       SET file_id=%s,title=%s,standard_no=%s,material_type='STANDARD',
                           page_count=%s,appendix_tables_json=%s,source_note=%s,status='ACTIVE'
                     WHERE reference_id=%s""",
                    (file_id, "定向井轨道设计与轨迹计算", "SY/T 5435-2012", 60, payload, args.source_note, existing["reference_id"]),
                )
                reference_id = existing["reference_id"]
            else:
                cur.execute(
                    """INSERT INTO drilling_reference_material
                    (material_code,title,standard_no,material_type,file_id,page_count,appendix_tables_json,source_note,create_by)
                    VALUES('SY-T-5435-2012',%s,%s,'STANDARD',%s,60,%s,%s,'admin')""",
                    ("定向井轨道设计与轨迹计算", "SY/T 5435-2012", file_id, payload, args.source_note),
                )
                reference_id = cur.lastrowid
        db.commit()
        print(json.dumps({"reference_id": reference_id, "file_id": file_id, "sha256": digest,
                          "appendix_tables": APPENDIX_A}, ensure_ascii=False))
    finally:
        db.close()


if __name__ == "__main__":
    main()

