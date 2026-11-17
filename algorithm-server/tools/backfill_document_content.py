#!/usr/bin/env python3
"""Parse archived originals without changing their files or approved versions.

Run a dry audit by default. --apply adds immutable content snapshots and new
candidate extractions only. It does not approve data or activate trajectories.
"""
from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import sys
import time

import pymysql

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.adapters.source_documents import inspect_document
from app.adapters.document_structure import PARSER_VERSION


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), sort_keys=True)


def invariant(cursor):
    result = {}
    for table in ('data_file', 'drilling_survey_version', 'drilling_survey_station', 'drilling_trajectory_version'):
        cursor.execute('SELECT COUNT(*) AS n FROM ' + table)
        result[table] = cursor.fetchone()['n']
    cursor.execute('SELECT wellbore_id,trajectory_kind,survey_version_id FROM drilling_trajectory_head ORDER BY wellbore_id,trajectory_kind')
    result['trajectory_heads_sha256'] = hashlib.sha256(encode(cursor.fetchall()).encode()).hexdigest()
    cursor.execute('SELECT file_id,original_name,sha256,object_key FROM data_file ORDER BY file_id')
    result['original_registry_sha256'] = hashlib.sha256(encode(cursor.fetchall()).encode()).hexdigest()
    return result


def save(cursor, inspected, source_id=None, reference_id=None):
    document = inspected.get('document') or {}
    blocks = document.get('blocks') or []
    if not document:
        return None
    content_hash = document.get('content_sha256') or hashlib.sha256(encode(document).encode()).hexdigest()
    manifest = {k: v for k, v in document.items() if k != 'blocks'}
    cursor.execute('INSERT IGNORE INTO drilling_document_parse(source_id,reference_id,parser_version,source_sha256,content_sha256,parse_status,manifest_json) VALUES(%s,%s,%s,%s,%s,%s,%s)',
                   (source_id, reference_id, PARSER_VERSION, inspected['sha256'], content_hash, document['status'], encode(manifest)))
    scope, owner = ('source_id', source_id) if source_id is not None else ('reference_id', reference_id)
    cursor.execute(f'SELECT parse_id FROM drilling_document_parse WHERE {scope}=%s AND parser_version=%s AND content_sha256=%s', (owner, PARSER_VERSION, content_hash))
    parse_id = cursor.fetchone()['parse_id']
    for order, block in enumerate(blocks):
        cursor.execute('INSERT IGNORE INTO drilling_document_section(parse_id,locator,section_order,title,content_type,semantic_type,payload_json) VALUES(%s,%s,%s,%s,%s,%s,%s)',
                       (parse_id, block['locator'], order, block['title'], block['type'], block.get('semantic_type', 'TEXT'), encode(block)))
    if source_id is not None:
        for trajectory in inspected['trajectories']:
            cursor.execute('INSERT IGNORE INTO drilling_source_extraction(source_id,locator,payload_json) VALUES(%s,%s,%s)',
                           (source_id, trajectory['locator'], encode(trajectory)))
        cursor.execute('UPDATE drilling_source_document SET parse_status=%s,diagnostics_json=%s WHERE source_id=%s',
                       (inspected['status'], encode(inspected['diagnostics']), source_id))
    return parse_id


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', default=os.environ.get('MYSQL_DATABASE', 'ry'))
    parser.add_argument('--profile', type=Path, default=Path.home()/'.ruoyi/uploadPath')
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--source-id', type=int, action='append')
    parser.add_argument('--limit', type=int)
    parser.add_argument('--output', type=Path, default=Path('docs/verification/document-content-audit.json'))
    args = parser.parse_args()
    password = os.environ.get('MYSQL_PASSWORD')
    if not password:
        raise SystemExit('请通过 MYSQL_PASSWORD 环境变量提供数据库密码')
    connection = pymysql.connect(host=os.environ.get('MYSQL_HOST', '127.0.0.1'), user=os.environ.get('MYSQL_USER', 'root'),
                                 port=int(os.environ.get('MYSQL_PORT', '3306')), password=password, database=args.database,
                                 charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor)
    rows, cache, started = [], {}, time.monotonic()
    with connection.cursor() as cursor:
        before = invariant(cursor)
        cursor.execute("SELECT s.source_id,NULL AS reference_id,s.source_format,f.original_name,f.object_key,f.sha256 FROM drilling_source_document s JOIN data_file f ON f.file_id=s.file_id WHERE f.status='ACTIVE' ORDER BY s.source_id")
        sources = cursor.fetchall()
        cursor.execute("SELECT NULL AS source_id,r.reference_id,'pdf' AS source_format,f.original_name,f.object_key,f.sha256 FROM drilling_reference_material r JOIN data_file f ON f.file_id=r.file_id WHERE r.status='ACTIVE' AND f.status='ACTIVE'")
        sources += cursor.fetchall()
    connection.rollback()
    if args.source_id:
        sources = [s for s in sources if s['source_id'] in args.source_id]
    if args.limit:
        sources = sources[:args.limit]
    for index, source in enumerate(sources, 1):
        row = dict(source_id=source['source_id'], reference_id=source['reference_id'], name=source['original_name'], format=source['source_format'])
        try:
            root = args.profile.resolve()
            path = (root/source['object_key']).resolve()
            if not path.is_relative_to(root) or not path.is_file():
                raise ValueError('归档原件不存在或存储位置无效')
            if hashlib.sha256(path.read_bytes()).hexdigest() != source['sha256']:
                raise ValueError('归档原件校验值不一致')
            key = (source['sha256'], source['original_name'])
            if key not in cache:
                cache[key] = inspect_document(path, original_name=source['original_name'])
            inspected = cache[key]
            document = inspected.get('document') or {}
            if not document.get('blocks'):
                if args.apply and document:
                    with connection.cursor() as cursor:
                        row['parse_id']=save(cursor,inspected,source['source_id'],source['reference_id'])
                    connection.commit()
                raise ValueError(encode(inspected['diagnostics']) or '文档无可读内容')
            blocks = document['blocks']
            row.update(status=inspected['status'], sections=len(blocks),
                       tables=sum(b['type'] == 'TABLE' for b in blocks),
                       figures=sum(b['type'] in ('FIGURE', 'CHART') for b in blocks),
                       table_rows=sum(b.get('row_count', 0) for b in blocks),
                       trajectories=len(inspected['trajectories']),
                       stations=sum(len(t['rows']) for t in inspected['trajectories']),
                       warnings=inspected['diagnostics'])
            if args.apply:
                with connection.cursor() as cursor:
                    row['parse_id'] = save(cursor, inspected, source['source_id'], source['reference_id'])
                connection.commit()
        except Exception as error:
            connection.rollback()
            row.update(status='FAILED', error=str(error))
        rows.append(row)
        if index % 20 == 0 or row['status'] != 'PARSED':
            print(json.dumps(dict(done=index, total=len(sources), file=row['name'], status=row['status']), ensure_ascii=False), flush=True)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(dict(parser_version=PARSER_VERSION, applied=args.apply, rows=rows), ensure_ascii=False, indent=2))
    with connection.cursor() as cursor:
        after = invariant(cursor)
    connection.rollback()
    connection.close()
    assert before == after, '文档解析不应改变文件原名、已确认轨迹版本或当前轨迹'
    report = dict(parser_version=PARSER_VERSION, applied=args.apply, elapsed_seconds=round(time.monotonic()-started, 2),
                  summary=dict(Counter(r['status'] for r in rows)), formats=dict(Counter(r['format'] for r in rows)),
                  totals={k:sum(r.get(k, 0) for r in rows) for k in ('sections','tables','figures','table_rows','trajectories','stations')},
                  invariants_preserved=True, before=before, after=after, rows=rows)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ('rows','before','after')}, ensure_ascii=False), flush=True)
    if report['summary'].get('FAILED'):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
