"""Rebuild appendix projections from archived source content without changing heads.

Connection uses MYSQL_HOST/PORT/DATABASE/USER/PASSWORD and RUOYI_PROFILE.
Run with --apply to persist; default is a read-only report. Originals and
immutable trajectory versions are never overwritten.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import pymysql
from app.adapters.engineering_tables import engineering_tables
from app.adapters.source_documents import inspect_document

def obj(value):
    return json.loads(value) if isinstance(value,str) else value or {}

def rebuild(db,profile,apply=False):
    report=[]
    with db.cursor() as q:
        q.execute("SELECT source_id FROM drilling_source_document ORDER BY source_id")
        sources=q.fetchall()
    for source in sources:
        with db.cursor() as q:
            q.execute("SELECT s.*,f.object_key,f.original_name FROM drilling_source_document s JOIN data_file f ON f.file_id=s.file_id WHERE s.source_id=%s",(source['source_id'],));source=q.fetchone()
            q.execute("SELECT * FROM drilling_document_parse WHERE source_id=%s ORDER BY parse_id DESC LIMIT 1",(source['source_id'],))
            parsed=q.fetchone();metadata=obj(source['metadata_json'])
            # Re-read known engineering office formats to include all source columns.
            fresh=not parsed
            inspected=None
            if fresh:
                path=profile/source['object_key']
                if path.is_file():inspected=inspect_document(path,original_name=source['original_name'])
            if inspected and inspected['document']['status']!='FAILED':
                document=inspected['document'];text=inspected['text_excerpt']
                metadata.update(inspected['metadata'])
                if apply:
                    manifest={k:v for k,v in document.items() if k!='blocks'}
                    hash_=document.get('content_sha256') or hashlib.sha256(json.dumps(document,ensure_ascii=False).encode()).hexdigest()
                    q.execute("INSERT IGNORE INTO drilling_document_parse(source_id,parser_version,source_sha256,content_sha256,parse_status,manifest_json) VALUES(%s,%s,%s,%s,%s,%s)",(source['source_id'],document['parser_version'],inspected['sha256'],hash_,document['status'],json.dumps(manifest,ensure_ascii=False)))
                    q.execute("SELECT parse_id FROM drilling_document_parse WHERE source_id=%s AND parser_version=%s AND content_sha256=%s",(source['source_id'],document['parser_version'],hash_));parse_id=q.fetchone()['parse_id']
                    for order,block in enumerate(document.get('blocks',[])):
                        q.execute("INSERT IGNORE INTO drilling_document_section(parse_id,locator,section_order,title,content_type,semantic_type,payload_json) VALUES(%s,%s,%s,%s,%s,%s,%s)",(parse_id,block['locator'],order,block.get('title',''),block['type'],block.get('semantic_type','TEXT'),json.dumps(block,ensure_ascii=False)))
            elif parsed and parsed['parse_status'] not in ('FAILED','UNSUPPORTED'):
                document=obj(parsed['manifest_json']);q.execute("SELECT payload_json FROM drilling_document_section WHERE parse_id=%s ORDER BY section_order",(parsed['parse_id'],));document['blocks']=[obj(r['payload_json']) for r in q.fetchall()]
                text='\n'.join(b.get('text','') if b['type']=='TEXT' else '\n'.join(' | '.join(map(str,row)) for row in b.get('rows',[])) for b in document['blocks'])
            else:
                report.append({'source_id':source['source_id'],'name':source['original_name'],'error':inspected.get('diagnostics') if inspected else ('原件结构损坏，历史解析失败' if parsed else '原件缺失')})
                continue
            tables=engineering_tables(document,text,metadata);metadata['engineering_tables']=tables
            if apply:
                q.execute("UPDATE drilling_source_document SET metadata_json=%s WHERE source_id=%s",(json.dumps(metadata,ensure_ascii=False),source['source_id']))
                q.execute("SELECT DISTINCT n.field_id,n.well_id FROM drilling_engineering_document d JOIN drilling_engineering_document_file f ON f.document_id=d.document_id JOIN drilling_engineering_node n ON n.node_id=d.node_id WHERE f.source_id=%s AND n.well_id IS NOT NULL",(source['source_id'],));links=q.fetchall()
                for link in links:
                    for t in tables['targets']:
                        q.execute("SELECT COUNT(*) n FROM drilling_target WHERE field_id=%s AND well_id=%s AND target_name=%s",(link['field_id'],link['well_id'],t['name']))
                        if not q.fetchone()['n']:q.execute("INSERT INTO drilling_target(field_id,well_id,target_name,target_type,geometry_json,tolerance_json,status,create_by) VALUES(%s,%s,%s,'POINT',%s,'{}','ACTIVE','source-parser')",(link['field_id'],link['well_id'],t['name'],json.dumps(t,ensure_ascii=False)))
                    # Completion cards carry measured hit results in the A.6
                    # target block.  Attach those observations to the
                    # current actual version of the same wellbore; design
                    # target rows are never used as measured observations.
                    if tables['target_points']:
                        q.execute("SELECT b.wellbore_id,h.survey_version_id FROM drilling_wellbore b JOIN drilling_trajectory_head h ON h.wellbore_id=b.wellbore_id AND h.trajectory_kind='ACTUAL' WHERE b.well_id=%s ORDER BY h.survey_version_id DESC LIMIT 1", (link['well_id'],))
                        head=q.fetchone()
                        if head:
                            for point in tables['target_points']:
                                metrics={k:point[k] for k in ('closure_distance','closure_azimuth','offset','longitudinal_offset','lateral_offset','vertical_offset','target_azimuth') if k in point}
                                q.execute("INSERT IGNORE INTO drilling_trajectory_target_point(field_id,wellbore_id,survey_version_id,target_no,md,north,east,tvd,inclination,azimuth,remark,metrics_json,status,create_by) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'ACTIVE','source-backfill')",(link['field_id'],head['wellbore_id'],head['survey_version_id'],point.get('target_no'),point.get('md'),point.get('north'),point.get('east'),point.get('tvd'),point.get('inclination'),point.get('azimuth'),point.get('remark',''),json.dumps(metrics,ensure_ascii=False)))
                db.commit()
            report.append({'source_id':source['source_id'],'tables':{k:len(v) for k,v in tables.items()},'reparsed':bool(inspected)})
    return report

if __name__=='__main__':
    args=argparse.ArgumentParser();args.add_argument('--apply',action='store_true');opt=args.parse_args()
    db=pymysql.connect(host=os.getenv('MYSQL_HOST','127.0.0.1'),port=int(os.getenv('MYSQL_PORT','3306')),user=os.environ['MYSQL_USER'],password=os.environ['MYSQL_PASSWORD'],database=os.environ['MYSQL_DATABASE'],cursorclass=pymysql.cursors.DictCursor)
    print(json.dumps(rebuild(db,Path(os.environ['RUOYI_PROFILE']),opt.apply),ensure_ascii=False,indent=2))
