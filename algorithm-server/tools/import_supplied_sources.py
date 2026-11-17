#!/usr/bin/env python3
"""Explicit operator CLI for the supplied data. Runtime imports use scoped Java APIs.

MYSQL_PASSWORD must come from the environment. Existing sources/versions are reused
by SHA256, making a repeat run safe. Source bytes are copied, never changed.
"""
import argparse,hashlib,json,mimetypes,os,re,shutil,sys,traceback
from pathlib import Path
import pymysql
from pymysql.cursors import DictCursor
from app.adapters.source_documents import clean_rows
from app.shared.trajectory.reduction import reduce_trajectory
from app.tasks.anticollision.evaluation import clean_json
from app.domain.engineering_documents import describe_document,identity_name
from app.domain.chinese_engineering import build_manifest

def js(x):return json.dumps(clean_json(x),ensure_ascii=False,allow_nan=False,default=str)
def sha(x):return hashlib.sha256(x).hexdigest()
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--root',required=True);ap.add_argument('--parsed',required=True);ap.add_argument('--profile',default=str(Path.home()/'.ruoyi/uploadPath'));ap.add_argument('--report',required=True);a=ap.parse_args();root=Path(a.root);profile=Path(a.profile)
 db=pymysql.connect(host=os.getenv('MYSQL_HOST','127.0.0.1'),user=os.getenv('MYSQL_USER','root'),password=os.environ['MYSQL_PASSWORD'],database=os.getenv('MYSQL_DATABASE','ry'),charset='utf8mb4',cursorclass=DictCursor,autocommit=False)
 defaults=json.loads((Path(__file__).resolve().parents[2]/'ruoyi-java/ruoyi-admin/src/main/resources/drilling/native-defaults.json').read_text())
 def execute(sql,*values):
  args=values[0] if len(values)==1 and isinstance(values[0],(tuple,list)) else values
  with db.cursor() as c:c.execute(sql,args);return c.lastrowid
 def query(sql,args=()):
  with db.cursor() as c:c.execute(sql,args);return c.fetchall()
 def upsert(table,codecol,code,values,idcol):
  old=query(f'SELECT {idcol} FROM {table} WHERE {codecol}=%s',(code,))
  if old:return old[0][idcol]
  fields={codecol:code,**values};return execute(f"INSERT INTO {table}({','.join(fields)}) VALUES({','.join(['%s']*len(fields))})",tuple(fields.values()))
 # Build the persisted catalog from engineering identities, never from source
 # package names or document-level COMPASS Project metadata.
 records=[json.loads(p.read_text()) for p in sorted(Path(a.parsed).glob('*.json'))]
 coal_manifest={f['path']:f for f in build_manifest(records)['files']}
 def ensure_field(field_code,field_name,block_code,block_name,coordinate_system):
  bid=upsert('drilling_block','block_code',block_code,{'block_name':block_name,'description':'来源资料确认的地理区块','create_by':'admin'},'block_id')
  fid=upsert('drilling_field','field_code',field_code,{'block_id':bid,'field_name':field_name,'coordinate_system':coordinate_system,'config_json':js(defaults),'create_by':'admin'},'field_id')
  execute("INSERT IGNORE INTO drilling_field_config(field_id,config_version,coordinate_system,north_reference,algorithm_defaults_json,create_by) VALUES(%s,1,%s,'GRID_NORTH',%s,'admin')",(fid,coordinate_system,js(defaults)))
  execute("INSERT IGNORE INTO drilling_field_member(field_id,user_id,role_code) VALUES(%s,1,'OWNER')",(fid,))
  return fid
 datasets={'VOLVE':ensure_field('VOLVE-F','Volve F','15/9','15/9区块','EPSG:23031'),
           'VOLVE_LOKE':ensure_field('VOLVE-LOKE-C','15/09-Loke C','15/9','15/9区块','EPSG:23031')}
 coal_fields={}
 for f in coal_manifest.values():
  for t in f['targets']:
   code='COALBED-'+sha(js(t['site']).encode())[:12].upper()
   coal_fields[t['well']]=ensure_field(code,t['site']+'井场','ORDOS-YISHAN-SLOPE','鄂尔多斯盆地伊陕斜坡','UNCONFIRMED')
 db.commit();report={'files':[],'versions':[],'issues':[],'datasets':{**datasets,'COALGAS':sorted(set(coal_fields.values()))},'reused_sources':0,'reused_versions':0,'auto_activation':'DISABLED_REQUIRES_EXPLICIT_RELEASE'}
 engineering_nodes={};engineering_revisions={};engineering_docs={}
 def ensure_node(field,parent,node_type,name,code='',well_id=None,wellbore_id=None,entity_kind='UNKNOWN',properties=None):
  if node_type in ('PLAN','SURVEY','INBOX'):
   existing=query("SELECT node_id FROM drilling_engineering_node WHERE field_id=%s AND wellbore_id <=> %s AND node_type=%s AND node_name=%s AND status='ACTIVE' ORDER BY node_id",(field,wellbore_id,node_type,name))
   if existing:return existing[0]['node_id']
  key=sha(js([field,parent,node_type,name,code,well_id,wellbore_id]).encode())
  found=query('SELECT node_id FROM drilling_engineering_node WHERE node_key=%s',(key,))
  if found:return found[0]['node_id']
  return execute('INSERT INTO drilling_engineering_node(node_key,field_id,parent_id,node_type,node_name,node_code,well_id,wellbore_id,entity_kind,properties_json,create_by) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,\'admin\')',key,field,parent,node_type,name,code or name,well_id,wellbore_id,entity_kind,js(properties or {}))
 def ensure_well_node(field,parent,well_id,desc):
  row=query('SELECT well_code,well_name FROM drilling_well WHERE well_id=%s',(well_id,))[0]
  identity=identity_name(desc.get('well') or row['well_code']) or identity_name(row['well_code'])
  display=desc.get('well') or re.sub(r'^15[/_]9[-_]?','',row['well_name'],flags=re.I)
  key=sha(js([field,parent,'WELL',identity]).encode())
  canonical=query("SELECT node_id FROM drilling_engineering_node WHERE field_id=%s AND well_id=%s AND node_type='WELL' AND status='ACTIVE' ORDER BY node_id",(field,well_id))
  if canonical:return canonical[0]['node_id']
  old=query('SELECT node_id,properties_json FROM drilling_engineering_node WHERE node_key=%s',(key,))
  if old:
   props=json.loads(old[0]['properties_json'] or '{}');ids=set(props.get('source_well_ids',[]));ids.add(well_id)
   execute('UPDATE drilling_engineering_node SET properties_json=%s WHERE node_id=%s',(js({**props,'source_well_ids':sorted(ids)}),old[0]['node_id']))
   return old[0]['node_id']
  return execute('INSERT INTO drilling_engineering_node(node_key,field_id,parent_id,node_type,node_name,node_code,well_id,entity_kind,properties_json,create_by) VALUES(%s,%s,%s,\'WELL\',%s,%s,%s,%s,%s,\'admin\')',key,field,parent,display,identity,well_id,desc.get('entity_kind','WELL'),js({'source_well_ids':[well_id],'source_aliases':[row['well_code'],row['well_name']]}))
 def ensure_document(field,node,revision,sourceid,role,title,properties=None):
  linked=query("SELECT d.document_id FROM drilling_engineering_document d JOIN drilling_engineering_document_file df ON df.document_id=d.document_id LEFT JOIN drilling_engineering_revision r ON r.revision_id=d.revision_id WHERE d.node_id=%s AND (d.revision_id <=> %s OR (%s IS NULL AND r.phase='DOCUMENT')) AND df.source_id=%s ORDER BY d.document_id",(node,revision,revision,sourceid))
  if linked:return linked[0]['document_id']
  dkey=sha(js([node,revision,role,title]).encode());old=query('SELECT document_id FROM drilling_engineering_document WHERE document_key=%s',(dkey,))
  did=old[0]['document_id'] if old else execute('INSERT INTO drilling_engineering_document(document_key,node_id,revision_id,document_role,document_title) VALUES(%s,%s,%s,%s,%s)',dkey,node,revision,role,title)
  existing=query('SELECT document_file_id FROM drilling_engineering_document_file WHERE document_id=%s AND source_id=%s',(did,sourceid))
  if not existing:
   edition=query('SELECT COALESCE(MAX(edition_no),0)+1 AS n FROM drilling_engineering_document_file WHERE document_id=%s',(did,))[0]['n']
   execute('INSERT INTO drilling_engineering_document_file(document_id,source_id,edition_no,representation_format,file_label,usage_state,properties_json) SELECT %s,source_id,%s,source_format,%s,%s,%s FROM drilling_source_document WHERE source_id=%s',(did,edition,title,'HISTORICAL',js(properties or {}),sourceid))
  return did
 def ensure_revision(node,desc,version_id=None):
  if version_id is not None:
   linked=query("SELECT r.revision_id FROM drilling_engineering_revision r JOIN drilling_engineering_revision_data rd ON rd.revision_id=r.revision_id WHERE r.node_id=%s AND rd.survey_version_id=%s AND JSON_EXTRACT(r.properties_json,'$.canonical_revision_id') IS NULL ORDER BY r.revision_id",(node,version_id))
  else:
   linked=query("SELECT r.revision_id FROM drilling_engineering_revision r JOIN drilling_engineering_document d ON d.revision_id=r.revision_id JOIN drilling_engineering_document_file df ON df.document_id=d.document_id WHERE r.node_id=%s AND df.source_id=%s AND JSON_EXTRACT(r.properties_json,'$.canonical_revision_id') IS NULL ORDER BY r.revision_id",(node,sourceid))
  if linked:return linked[0]['revision_id']
  node_type=query('SELECT node_type FROM drilling_engineering_node WHERE node_id=%s',(node,))[0]['node_type']
  phase=desc.get('phase') or ('ACTUAL' if node_type=='SURVEY' else 'PLAN' if node_type=='PLAN' else 'DOCUMENT')
  outcome=desc.get('outcome_name') or desc.get('series_name') or '未命名成果'
  version=query('SELECT t.trajectory_version_no AS version_no,s.parent_version_id FROM drilling_survey_version s JOIN drilling_trajectory_version t ON t.survey_version_id=s.survey_version_id WHERE s.survey_version_id=%s',(version_id,))[0] if version_id is not None else None
  business=desc.get('business_revision') or (f"DATA-v{version['version_no']}" if version else ('ACTUAL-'+str(desc.get('survey_date') or 'UNDATED') if phase=='ACTUAL' else outcome))
  rkey=sha(js([node,'DATA',version_id] if version_id is not None else [node,'SOURCE',business,outcome,phase]).encode());old=query('SELECT revision_id FROM drilling_engineering_revision WHERE node_id=%s AND revision_key=%s',(node,rkey))
  parent_revision=None
  if version and version['parent_version_id']:
   prior=query('SELECT rd.revision_id FROM drilling_engineering_revision_data rd JOIN drilling_engineering_revision r ON r.revision_id=rd.revision_id WHERE r.node_id=%s AND rd.survey_version_id=%s',(node,version['parent_version_id']))
   if prior:parent_revision=prior[0]['revision_id']
  state=desc.get('usage_state') or ('DRAFT' if phase=='PROTOTYPE' else 'HISTORICAL')
  if state not in ('DRAFT','HISTORICAL','ACTIVE','WITHDRAWN','FORBIDDEN','UNASSIGNED'):state='HISTORICAL'
  rid=old[0]['revision_id'] if old else execute('INSERT INTO drilling_engineering_revision(node_id,revision_key,business_revision,outcome_name,phase,usage_state,parent_revision_id,revision_note,source_date,properties_json,create_by) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,\'admin\')',node,rkey,business,outcome,phase,state,parent_revision,'来源文件导入；未自动发布',desc.get('survey_date'),js(desc))
  if version_id is not None:execute('INSERT IGNORE INTO drilling_engineering_revision_data(revision_id,survey_version_id,data_label) VALUES(%s,%s,%s)',rid,version_id,outcome)
  return rid
 def engineering_context(field,desc,well_id=None,bore_id=None):
  # Source-level Project labels remain provenance metadata only. The persisted
  # engineering hierarchy starts at the confirmed field and has no extra tier.
  source_node=None
  site_node=None
  if not well_id:
   inbox=ensure_node(field,None,'INBOX','未归类来源资料','FIELD-FILES',properties={'note':'来源文件未提供井名'})
   return source_node,site_node,inbox,None
  well_node=ensure_well_node(field,None,well_id,desc)
  if not bore_id:return source_node,site_node,well_node,None
  bore_name=desc.get('wellbore') or query('SELECT wellbore_name FROM drilling_wellbore WHERE wellbore_id=%s',(bore_id,))[0]['wellbore_name']
  bkey=sha(js([field,'WELLBORE',bore_id]).encode())
  # Keep actual wellbore IDs independent even when source aliases normalize to
  # the same logical well in the parent tree.
  existing_bore=query('SELECT node_id FROM drilling_engineering_node WHERE field_id=%s AND node_type=\'WELLBORE\' AND wellbore_id=%s',(field,bore_id))
  if existing_bore:
   bore_node=existing_bore[0]['node_id']
  else:
   bore_node=execute('INSERT INTO drilling_engineering_node(node_key,field_id,parent_id,node_type,node_name,node_code,well_id,wellbore_id,entity_kind,properties_json,create_by) VALUES(%s,%s,%s,\'WELLBORE\',%s,%s,%s,%s,\'WELL\',%s,\'admin\')',bkey,field,well_node,bore_name,bore_name,well_id,bore_id,js({'directory_wellbore':desc.get('directory_wellbore',''),'parent_wellbore':desc.get('parent_wellbore',''),'conflicts':desc.get('conflicts',[])}))
  return source_node,site_node,well_node,bore_node
 # Explicit coordinates in design documents are evidence, not invented datums.
 coalmeta={}
 for r in records:
  if not Path(r['relative_path']).parts[0].startswith('采气'):continue
  match=re.search(r'(?:米|双|麒)\d+(?:-\d+)?(?:Y?H\d*)?',Path(r['relative_path']).name)
  if match:coalmeta.setdefault(match.group(),{}).update(r.get('metadata',{}))
 for r in records:
  rel=r['relative_path'];p=root/rel;code='VOLVE' if Path(rel).parts[0]=='Volve数据集' else 'COALGAS';
  engineering=describe_document(p.name,r.get('text_excerpt',''),rel);r['engineering']=engineering
  source_project=engineering.get('source_project','')
  dataset_key='VOLVE_LOKE' if code=='VOLVE' and (source_project=='Q0015 SLEIPNER' or '/15_9-19/' in rel) else code
  fid=datasets[dataset_key] if dataset_key in datasets else None
  coal_file=coal_manifest.get(rel)
  if coal_file:
   primary=coal_file['targets'][0]
   fid=coal_fields.get(primary['well'])
  if fid is None:
   raise ValueError(f'来源文件未能关联到已确认井场，需先解析并确认归属：{rel}')
  try:
   engineering=engineering
   source_metadata={**r.get('metadata',{}),'engineering':engineering}
   data=p.read_bytes();digest=sha(data);sourcekey=sha((rel+'\0'+digest).encode());old=query('SELECT source_id,file_id,metadata_json FROM drilling_source_document WHERE source_key=%s ORDER BY source_id',(sourcekey,))
   if old:
    sourceid=old[0]['source_id'];fileid=old[0]['file_id'];report['reused_sources']+=1
    execute('UPDATE drilling_source_document SET field_id=%s,metadata_json=%s WHERE source_id=%s',(fid,js({**json.loads(old[0]['metadata_json'] or '{}'),**source_metadata}),sourceid))
    execute('UPDATE data_file SET field_id=%s WHERE file_id=%s',(fid,fileid))
   else:
    existing=query('SELECT file_id FROM data_file WHERE field_id=%s AND sha256=%s LIMIT 1',(fid,digest))
    if existing:fileid=existing[0]['file_id']
    else:
     ext=p.suffix if len(p.suffix)<12 else '';key=f'drilling-files/{fid}/{digest}{ext}';dest=profile/key;dest.parent.mkdir(parents=True,exist_ok=True)
     if not dest.exists():shutil.copy2(p,dest)
     if sha(dest.read_bytes())!=digest:raise ValueError('归档字节校验失败')
     fileid=execute("INSERT INTO data_file(field_id,owner_user_id,file_category,business_type,original_name,object_key,content_type,file_size,sha256,create_by) VALUES(%s,1,'SURVEY','SOURCE_DOCUMENT',%s,%s,%s,%s,%s,'admin')",(fid,p.name[:255],key,mimetypes.guess_type(p.name)[0] or 'application/octet-stream',len(data),digest))
    sourceid=execute('INSERT INTO drilling_source_document(file_id,field_id,source_path,source_key,source_format,parse_status,metadata_json,diagnostics_json) VALUES(%s,%s,%s,%s,%s,%s,%s,%s)',(fileid,fid,rel,sourcekey,r['format'],r['status'],js(source_metadata),js(r.get('diagnostics',[]))))
   parts=Path(rel).parts
   if code=='VOLVE':
    try:idx=parts.index('WellWellbore',parts.index('WellWellbore')+1);wellcode=parts[idx+1];borecode=parts[idx+2] if len(parts)>idx+3 else wellcode
    except ValueError:wellcode='';borecode=''
   else:
    wellcode=coal_file['targets'][0]['well'];borecode=coal_file['targets'][0]['wellbore']
   if code=='VOLVE':
    wellcode=engineering.get('well') or wellcode
    borecode=engineering.get('wellbore') or borecode
   if engineering.get('document_role')=='WELL_SUMMARY' and code=='VOLVE':borecode=''
   if not wellcode or wellcode in ('Relief Well Locations',):well=None;bo=None
   else:
    candidates_wells=query('SELECT w.well_id,w.well_code,w.well_name,COUNT(DISTINCT t.survey_version_id) AS versions FROM drilling_well w LEFT JOIN drilling_wellbore b ON b.well_id=w.well_id LEFT JOIN drilling_trajectory_version t ON t.wellbore_id=b.wellbore_id WHERE w.field_id=%s GROUP BY w.well_id,w.well_code,w.well_name',(fid,))
    candidates=[]
    identity=identity_name(wellcode)
    matching=[x for x in candidates_wells if identity_name(x['well_code'])==identity or identity_name(x['well_name'])==identity]
    existing=sorted(matching,key=lambda x:(-int(x['versions'] or 0),x['well_id']))
    if existing:
     well=existing[0]['well_id']
     candidates=query('SELECT wellbore_id,wellbore_code,wellbore_name FROM drilling_wellbore WHERE well_id=%s',(well,))
    else:
     well=execute("INSERT INTO drilling_well(field_id,well_code,well_name,status,metadata_json,create_by) VALUES(%s,%s,%s,%s,%s,'admin')",(fid,wellcode,wellcode,'COMPLETED' if code=='COALGAS' else 'UNKNOWN',js({'dataset':code,'engineering_status_source':'完井资料' if code=='COALGAS' else '未提供明确施工状态'})))
     execute('INSERT INTO drilling_well_catalog(well_id,field_id,identity_key) VALUES(%s,%s,%s)',(well,fid,wellcode))
     candidates=query('SELECT wellbore_id,wellbore_code,wellbore_name FROM drilling_wellbore WHERE well_id=%s',(well,))
    if not borecode:
     bo=None
    else:
     same=[b for b in candidates if identity_name(b['wellbore_code'])==identity_name(borecode) or identity_name(b['wellbore_name'])==identity_name(borecode)]
     if not same and coal_file and borecode==coal_file['targets'][0]['well']:
      # Older installations bootstrap a MAIN bore. Reuse its stable ID
      # instead of creating a second primary bore before reconciliation.
      same=[b for b in candidates if b['wellbore_code']=='MAIN']
      if len(same)>1:raise ValueError('主井眼身份不唯一，不能自动导入')
      if same:execute('UPDATE drilling_wellbore SET wellbore_code=%s,wellbore_name=%s WHERE wellbore_id=%s',(borecode,borecode,same[0]['wellbore_id']))
     bo=same[0]['wellbore_id'] if same else execute('INSERT INTO drilling_wellbore(well_id,wellbore_code,wellbore_name,engineering_status) VALUES(%s,%s,%s,%s)',(well,borecode,borecode,'COMPLETED' if code=='COALGAS' else 'UNKNOWN'))
    if bo and code=='VOLVE' and engineering.get('parent_wellbore') and engineering.get('entity_kind')!='PLANNED_RELIEF':
     parent_name=engineering['parent_wellbore'];parent_name=re.sub(r'^NO\\s*','',parent_name,flags=re.I)
     parent_match=next((b for b in candidates if identity_name(b['wellbore_code'])==identity_name(parent_name) or identity_name(b['wellbore_name'])==identity_name(parent_name)),None)
     if parent_match and parent_match['wellbore_id']!=bo:execute('UPDATE drilling_wellbore SET parent_wellbore_id=%s WHERE wellbore_id=%s',(parent_match['wellbore_id'],bo))
    if bo:execute('INSERT IGNORE INTO drilling_field_wellbore(field_id,wellbore_id) VALUES(%s,%s)',(fid,bo))
   for t in r.get('trajectories',[]):
    if not well:continue
    if coal_file:
     target=next(x for x in coal_file['extractions'] if x['locator']==t['locator'])
     match=query('SELECT wellbore_id FROM drilling_wellbore WHERE well_id=%s AND wellbore_code=%s',(well,target['wellbore']))
     bo=match[0]['wellbore_id'] if match else execute("INSERT INTO drilling_wellbore(well_id,wellbore_code,wellbore_name,engineering_status) VALUES(%s,%s,%s,'COMPLETED')",(well,target['wellbore'],target['wellbore']))
     execute('INSERT IGNORE INTO drilling_field_wellbore(field_id,wellbore_id) VALUES(%s,%s)',(fid,bo))
    kind=t['kind']
    execute('INSERT IGNORE INTO drilling_source_extraction(source_id,locator,payload_json) VALUES(%s,%s,%s)',(sourceid,t['locator'][:255],js(t)))
    exrow=query('SELECT extraction_id,survey_version_id FROM drilling_source_extraction WHERE source_id=%s AND locator=%s',(sourceid,t['locator'][:255]))[0];ex=exrow['extraction_id']
    if exrow['survey_version_id']:
     v=exrow['survey_version_id'];report['reused_versions']+=1
     mapped=query('SELECT t.wellbore_id,w.well_id FROM drilling_trajectory_version t JOIN drilling_wellbore b ON b.wellbore_id=t.wellbore_id JOIN drilling_well w ON w.well_id=b.well_id WHERE t.survey_version_id=%s',(v,))[0]
     bore_node=engineering_context(fid,engineering,mapped['well_id'],mapped['wellbore_id'])[3]
     if bore_node:
      series=('设计轨迹' if kind=='DESIGN' else '实钻测斜') if coal_file else engineering.get('series_name') or engineering.get('outcome_name') or t.get('label') or p.name
      result_node=ensure_node(fid,bore_node,'PLAN' if kind=='DESIGN' else 'SURVEY',series,series,well_id=mapped['well_id'],wellbore_id=mapped['wellbore_id'],entity_kind=engineering.get('entity_kind','WELL'),properties=engineering)
      rid=ensure_revision(result_node,engineering,v)
      ensure_document(fid,result_node,rid,sourceid,engineering.get('document_role') or 'TRAJECTORY_DATA',p.name,engineering)
     continue
    rows,issues=clean_rows(t['rows']);ref={**t.get('reference',{})};kind=t['kind'];ref.setdefault('north_reference','GRID_NORTH' if code=='COALGAS' else 'UNCONFIRMED');ref.setdefault('length_unit','m');ref.setdefault('angle_unit','deg')
    # Carry document-level engineering evidence into every trajectory
    # version.  These are observations from the source, not inferred CRS
    # values.  The same keys are also kept in drilling_source_document for
    # audit and display in the hierarchy tree.
    for key in ('basin','structural_position','geological_structure','oilfield',
                'development_block','location','administrative_location',
                'coordinate_system_text','coordinate_datum_name','wellhead_north',
                'wellhead_east','ground_elevation_m','kelly_bushing_elevation_m',
                'latitude','longitude','design_depth_m','actual_depth_m',
                'formation','target_metadata'):
     if engineering.get(key) not in (None,''):
      ref.setdefault(key,engineering[key])
    if code=='VOLVE' and ref.get('metadata',{}).get('Geo Datum','').startswith('European 1950') and '31N' in ref.get('metadata',{}).get('Map-Zone',''):ref['crs']='EPSG:23031'
    meta=ref.get('metadata',{});datum=meta.get('KB-MSL');ref['depth_datum']=meta.get('Datum Name',ref.get('depth_datum','未确认'))
    if datum:
     try:ref['depth_datum_elevation_m']=float(datum.replace('m',''))
     except ValueError:pass
    if code=='COALGAS':
     cm=coalmeta.get(wellcode,{})
     for key in ('magnetic_declination','meridian_convergence'):
      if key in cm:ref.setdefault(key,cm[key])
     if 'wellhead_north' in cm and 'wellhead_east' in cm:
      ref.update(origin_north=cm['wellhead_north'],origin_east=cm['wellhead_east']);execute('UPDATE drilling_well SET wellhead_n=%s,wellhead_e=%s WHERE well_id=%s',(ref['origin_north'],ref['origin_east'],well))
     if ref.get('coordinate_system_text') == '北京54':
      ref.setdefault('coordinate_datum_name','北京54')
      ref.setdefault('reference_status','DATUM_NAMED_PROJECTION_REQUIRED')
     else:
      ref.setdefault('reference_status','COORDINATE_REFERENCE_UNCONFIRMED')
    if len(rows)<2:continue
    if 'origin_tvd' not in ref:ref['origin_tvd']=rows[0].get('tvd',0.)
    stations=reduce_trajectory(rows,ref);points=[s.to_dict() for s in stations];fingerprint=sha(js([points,ref]).encode())
    oldv=query('SELECT survey_version_id FROM drilling_trajectory_version WHERE wellbore_id=%s AND trajectory_kind=%s AND content_sha256=%s',(bo,kind,fingerprint))
    if oldv:v=oldv[0]['survey_version_id'];report['reused_versions']+=1
    else:
     query('SELECT well_id FROM drilling_well WHERE well_id=%s FOR UPDATE',(well,))
     no=(query('SELECT version_no AS n FROM drilling_survey_version WHERE well_id=%s ORDER BY version_no DESC LIMIT 1 FOR UPDATE',(well,)) or [{'n':0}])[0]['n']+1;parent=query('SELECT survey_version_id FROM drilling_trajectory_head WHERE wellbore_id=%s AND trajectory_kind=%s',(bo,kind));parent=parent[0]['survey_version_id'] if parent else None
     v=execute("INSERT INTO drilling_survey_version(well_id,version_no,parent_version_id,source_file_id,config_version,import_mode,source_format,max_md,quality_summary_json,config_snapshot_json,create_by) VALUES(%s,%s,%s,%s,1,'FULL',%s,%s,%s,%s,'admin')",(well,no,parent,fileid,r['format'],stations[-1].md,js({'issues':issues,'source':rel,'locator':t['locator']}),js(defaults)))
     with db.cursor() as c:c.executemany('INSERT INTO drilling_survey_station(survey_version_id,md,inclination,azimuth,north,east,tvd,dogleg,curvature,raw_json) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)',[(v,s.md,s.inclination,s.azimuth,s.north,s.east,s.tvd,s.dogleg,s.curvature,js(raw)) for s,raw in zip(stations,rows)])
     ready=bool(ref.get('crs') and ref.get('depth_datum_elevation_m') is not None and stations[0].md==0 and not any(i['severity']=='ERROR' for i in issues));quality='READY' if ready else 'REFERENCE_REQUIRED'
     execute('INSERT INTO drilling_trajectory_version(survey_version_id,wellbore_id,trajectory_kind,trajectory_version_no,source_locator,content_sha256,reference_json,quality_status,label) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s)',(v,bo,kind,(query('SELECT trajectory_version_no AS n FROM drilling_trajectory_version WHERE wellbore_id=%s AND trajectory_kind=%s ORDER BY trajectory_version_no DESC LIMIT 1 FOR UPDATE',(bo,kind)) or [{'n':0}])[0]['n']+1,t['locator'][:255],fingerprint,js(ref),quality,(p.name+' / '+t['locator'])[:255]))
    execute("UPDATE drilling_source_extraction SET import_status='IMPORTED',survey_version_id=%s WHERE extraction_id=%s",(v,ex))
    report['versions'].append({'field_id':fid,'well':wellcode,'wellbore':borecode,'kind':kind,'version_id':v,'points':len(stations),'issues':len(issues),'source':rel,'locator':t['locator'],'usage_state':engineering.get('usage_state'),'business_revision':engineering.get('business_revision'),'auto_activated':False})
    # Link the immutable trajectory snapshot to an explicit plan/survey
    # business result. It never becomes current merely because it was imported.
    _,_,well_node,bore_node=engineering_context(fid,engineering,well,bo)
    if bore_node:
     series=('设计轨迹' if kind=='DESIGN' else '实钻测斜') if coal_file else engineering.get('series_name') or engineering.get('outcome_name') or t.get('label') or p.name
     result_node=ensure_node(fid,bore_node,'PLAN' if kind=='DESIGN' else 'SURVEY',series,series,well_id=well,wellbore_id=bo,entity_kind=engineering.get('entity_kind','WELL'),properties=engineering)
     rid=ensure_revision(result_node,engineering,v)
     engineering_revisions[(bo,kind,engineering.get('outcome_name') or t.get('label') or p.name)]=rid
     ensure_document(fid,result_node,rid,sourceid,engineering.get('document_role') or 'TRAJECTORY_DATA',p.name,engineering)
   if not r.get('trajectories') or not well:
    # Attach reports, summaries, production files and archives even when they
    # contain no trajectory table.  A file without measurements is still a
    # first-class source representation in the same hierarchy.
    _,_,well_node,bore_node=engineering_context(fid,engineering,well,bo)
    if bore_node and engineering.get('trajectory_kind') and engineering.get('phase') in ('PLAN','PROTOTYPE','ACTUAL'):
     kind='ACTUAL' if engineering.get('phase')=='ACTUAL' else 'DESIGN'
     series=('设计轨迹' if kind=='DESIGN' else '实钻测斜') if coal_file else engineering.get('series_name') or engineering.get('outcome_name') or p.name
     target_node=ensure_node(fid,bore_node,'SURVEY' if kind=='ACTUAL' else 'PLAN',series,series,well_id=well,wellbore_id=bo,entity_kind=engineering.get('entity_kind','WELL'),properties=engineering)
     rid=ensure_revision(target_node,engineering)
    else:
     target_node=well_node if engineering.get('document_role')=='WELL_SUMMARY' else (bore_node or well_node)
     rid=None
    if target_node:
     ensure_document(fid,target_node,rid,sourceid,engineering.get('document_role') or 'REFERENCE',p.name,engineering)
   db.commit();report['files'].append({'source_id':sourceid,'path':rel,'sha256':digest,'status':r['status'],'file_id':fileid})
  except Exception as e:db.rollback();report['issues'].append({'path':rel,'error':str(e)});print('ERROR',p.name,str(e),flush=True);traceback.print_exc()
 # Materialize the complete engineering hierarchy even for a well or wellbore
 # whose supplied package contains no attachable document. This keeps the
 # library a management tree, not merely a filtered list of files.
 for fid in sorted(set(datasets.values()) | set(coal_fields.values())):
  for w in query("SELECT well_id,well_code,well_name FROM drilling_well WHERE field_id=%s AND status<>'ARCHIVED'",(fid,)):
   desc={'well':w['well_code']}
   engineering_context(fid,desc,w['well_id'],None)
   for b in query('SELECT wellbore_id,wellbore_code,wellbore_name FROM drilling_wellbore WHERE well_id=%s',(w['well_id'],)):
    engineering_context(fid,{**desc,'wellbore':b['wellbore_code'] or b['wellbore_name']},w['well_id'],b['wellbore_id'])
 # Earlier catalog imports created alias-only wellbores as if they were
 # separate branches. Fold empty aliases into the data-bearing sibling while
 # retaining the original registry rows and their IDs.
 sibling_groups=query("SELECT field_id,parent_id,GROUP_CONCAT(node_id ORDER BY node_id) node_ids FROM drilling_engineering_node WHERE node_type='WELLBORE' AND parent_id IS NOT NULL GROUP BY field_id,parent_id")
 for group in sibling_groups:
  ids=[int(v) for v in group['node_ids'].split(',')]
  by_identity={}
  for node_id in ids:
   row=query('SELECT * FROM drilling_engineering_node WHERE node_id=%s',(node_id,))[0]
   identity=identity_name(row['node_code'] or row['node_name'])
   if identity:by_identity.setdefault(identity,[]).append(row)
  for matches in by_identity.values():
   if len(matches)<2:continue
   ranked=[]
   for row in matches:
    node_id=row['node_id'];bore=row['wellbore_id']
    versions=query('SELECT COUNT(*) n FROM drilling_trajectory_version WHERE wellbore_id=%s',(bore,))[0]['n'] if bore else 0
    docs=query('SELECT COUNT(*) n FROM drilling_engineering_document WHERE node_id=%s',(node_id,))[0]['n']
    children=query('SELECT COUNT(*) n FROM drilling_engineering_node WHERE parent_id=%s',(node_id,))[0]['n']
    revisions=query('SELECT COUNT(*) n FROM drilling_engineering_revision WHERE node_id=%s',(node_id,))[0]['n']
    ranked.append((int(versions)+int(docs)+int(children)+int(revisions),int(node_id),row,not any((versions,docs,children,revisions))))
   ranked.sort(key=lambda x:(-x[0],x[1]));canonical=ranked[0][2]
   source_ids=set(json.loads(canonical.get('properties_json') or '{}').get('source_wellbore_ids',[]))
   for _,node_id,row,empty in ranked[1:]:
    if not empty:continue
    source_ids.add(int(row['wellbore_id']))
    execute('DELETE FROM drilling_engineering_node WHERE node_id=%s',(node_id,))
   if source_ids:
    props=json.loads(canonical.get('properties_json') or '{}');props['source_wellbore_ids']=sorted(source_ids)
    execute('UPDATE drilling_engineering_node SET properties_json=%s WHERE node_id=%s',(js(props),canonical['node_id']))
 db.commit()
 for fid in sorted(set(datasets.values()) | set(coal_fields.values())):execute("INSERT INTO drilling_import_batch(field_id,status,manifest_json,summary_json,create_by) VALUES(%s,'COMPLETED',%s,%s,'admin')",(fid,js({'root':str(root),'idempotency':'source path + file SHA256; trajectory + reference SHA256'}),js({'files':sum(f['file_id']>0 for f in report['files']),'versions':len(report['versions']),'issues':report['issues']})))
 db.commit();report['database_counts']={table:query(f'SELECT COUNT(*) AS n FROM {table}')[0]['n'] for table in ('data_file','drilling_source_document','drilling_source_extraction','drilling_well','drilling_wellbore','drilling_trajectory_version','drilling_survey_station','drilling_data_quality_issue')};Path(a.report).write_text(js(report));print(js({'counts':report['database_counts'],'import_errors':report['issues'],'reused_sources':report['reused_sources'],'reused_versions':report['reused_versions']}));db.close()
 if coal_manifest and not report['issues']:
  from reconcile_engineering_hierarchy import main as reconcile
  reconcile(['--root',str(root),'--parsed',a.parsed,'--report',str(Path(a.report).with_suffix('.hierarchy.json')),'--apply'])
if __name__=='__main__':main()
