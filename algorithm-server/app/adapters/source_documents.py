"""Source-aware multi-document trajectory extraction. Originals are never rewritten.

Every extracted row retains table/sheet/page, source row and cell coordinates.
Unrecognized documents remain managed attachments with explicit diagnostics, not
fabricated surveys. Word and PDF engineering documents may contain many unrelated
tables; a trajectory table needs an unambiguous MD/INC/AZI header.
"""
from __future__ import annotations
from dataclasses import dataclass,field,asdict
from pathlib import Path
import csv,hashlib,io,json,math,re,subprocess,tempfile,zipfile
from copy import copy, deepcopy
from typing import Any
from app.shared.trajectory.min_curvature import compute_minimum_curvature

ALIASES={
 'md':('measureddepth','md','测深','井深','测量深度'),
 'inclination':('inclination','incl','inc','井斜角','井斜','斜度'),
 'azimuth':('azimuth','azim','azi','网格方位','井眼方位','方位角','方位'),
 'north':('northing','north','utmn/s','北坐标','北位移'),
 'east':('easting','east','utme/w','东坐标','东位移'),
 'tvd':('tvd','垂深','垂直深度'),
}
def norm(x):return re.sub(r'[\s_（）()°/\\\-\r\n\[\]]','',str(x or '')).lower()
def columns(row):
 out={}
 for key,aliases in ALIASES.items():
  for i,v in enumerate(row):
   text=norm(v)
   if not text:continue
   if any(text==norm(a) or text.startswith(norm(a)) and (len(norm(a))>3 or any('\u4e00'<=c<='\u9fff' for c in a) or text[len(norm(a)):].startswith(('m','deg','rad','rkb'))) for a in aliases):out[key]=i;break
 return out

def number(x):
 if isinstance(x,(float,int)):return float(x)
 s=str(x).strip().replace(',','').replace('−','-').replace('–','-')
 if re.fullmatch(r'[+-]?\d+(?:\.\d+)?\s*[°º]\s*\d+(?:\.\d+)?[′\']?(?:\s*\d+(?:\.\d+)?[″"]?)?',s):
  nums=list(map(float,re.findall(r'[+-]?\d+(?:\.\d+)?',s)));return nums[0]+(1 if nums[0]>=0 else -1)*(nums[1]/60+(nums[2]/3600 if len(nums)>2 else 0))
 return float(re.sub(r'[°º]$','',s))

def decode(data):
 from .document_structure import decode_text
 text,encoding=decode_text(data)
 return text,('utf-8' if encoding=='utf-8-sig' and not data.startswith(b'\xef\xbb\xbf') else encoding)

@dataclass
class ExtractedTrajectory:
 locator:str
 headers:list
 rows:list
 mapping:dict
 reference:dict=field(default_factory=dict)
 kind:str='ACTUAL'
 label:str=''
 def to_dict(self):return asdict(self)

def extract_tables(rows,locator,metadata=None,kind='ACTUAL'):
 from .survey_metadata import survey_metadata
 tables=[];active=None;context=[]
 for ri,row in enumerate(rows):
  row=list(row);mapping=columns(row)
  if {'md','inclination','azimuth'}<=mapping.keys():
   if active and active.rows:tables.append(active)
   header=' '.join(str(v) for v in row)
   ref={**(metadata or {}),**survey_metadata('\n'.join(context))}
   context=[]
   if '网格' in header:ref['north_reference']='GRID_NORTH'
   elif '磁方位' in header:ref['north_reference']='MAGNETIC_NORTH'
   elif '真方位' in header:ref['north_reference']='TRUE_NORTH'
   active=ExtractedTrajectory(f'{locator}:header:{ri+1}',[str(v or '') for v in row],[],mapping,ref,kind,locator);continue
  if not active:
   context.append(' | '.join(str(v or '') for v in row));context=context[-100:];continue
  try:
   values={k:number(row[c]) for k,c in active.mapping.items() if c<len(row) and str(row[c]).strip() not in ('','None')}
   if not {'md','inclination','azimuth'}<=values.keys():
    context.append(' | '.join(str(v or '') for v in row));context=context[-100:];continue
   active.rows.append({**values,'source_row':ri+1,'raw_cells':[str(v or '') for v in row]})
   context=[]
  except (ValueError,TypeError):
   context.append(' | '.join(str(v or '') for v in row));context=context[-100:];continue
 if active and active.rows:tables.append(active)
 return tables

def parse_statoil(text):
 meta={}
 for line in text.splitlines():
  if ':' in line:
   k,v=line.split(':',1);meta[k.strip()]=v.strip()
 if 'SURVEY LIST' not in text:return []
 rows=[]
 for i,line in enumerate(text.splitlines()):
  cells=line.split()
  if len(cells)<3:continue
  try:
   values=list(map(number,cells))
   if len(values)<3:continue
   row=dict(zip(('md','inclination','azimuth','tvd','offset_e','offset_n','east','north','dls'),values));row['source_row']=i+1;row['raw_cells']=cells;rows.append(row)
  except ValueError:continue
 ref={'north_reference':'GRID_NORTH' if meta.get('North Reference','').lower()=='grid' else 'TRUE_NORTH','length_unit':'m','angle_unit':'deg','coordinate_system':f"{meta.get('Geo Datum','UNKNOWN')} / {meta.get('Map-Zone','UNKNOWN')}",'depth_datum':meta.get('Datum Name'),'vertical_section_azimuth':None,'metadata':meta}
 try:ref['vertical_section_azimuth']=number(meta.get('Vertical Section Direction','0').replace('deg',''))
 except ValueError:pass
 if rows:
  ref['origin_north']=rows[0].get('north',0.);ref['origin_east']=rows[0].get('east',0.);ref['origin_tvd']=rows[0].get('tvd',0.);ref['origin_md']=rows[0]['md'];ref['reference_status']='READY' if rows[0]['md']==0 else 'NEEDS_MD0_HISTORY'
 return [ExtractedTrajectory('SURVEY LIST',list(ALIASES),rows,{'md':0,'inclination':1,'azimuth':2},ref,'ACTUAL',meta.get('Survey Name',''))] if rows else []

def _text_tables(text,kind='ACTUAL'):
 native=parse_statoil(text)
 if native:
  for t in native:t.kind=kind
  return native
 lines=text.splitlines();rows=[]
 for line in lines:
  if re.match(r'^\s*[^\d+\-].*[:：=]',line):rows.append([line])
  elif '\t' in line:rows.append(next(csv.reader([line],delimiter='\t')))
  elif ',' in line:rows.append(next(csv.reader([line])))
  elif ';' in line:rows.append(next(csv.reader([line],delimiter=';')))
  else:rows.append(line.split())
 return extract_tables(rows,'text',{},kind)

def inspect_document(path,*,original_name=None,default_kind=None):
 p=Path(path);data=p.read_bytes();name=original_name or p.name;suffix=Path(name).suffix.lower();kind=default_kind or ('DESIGN' if any(x in name.upper() for x in ('PLAN','PROTOTYPE','设计')) and 'ACTUAL' not in name.upper() else 'ACTUAL')
 result={'name':name,'sha256':hashlib.sha256(data).hexdigest(),'size':len(data),'format':suffix.lstrip('.') or 'text','trajectories':[],'diagnostics':[],'metadata':{},'text_excerpt':''}
 tables=[];alltext=''
 try:
  from .document_structure import inspect_structure
  document,specialized=inspect_structure(data,name,kind)
  result['document']=document
  result['format']=document['format']
  result['metadata'].update(document.get('metadata',{}))
  result['diagnostics'].extend(document.get('warnings',[]))
  texts=[]
  for block in document['blocks']:
   if block['type']=='TEXT':texts.append(block.get('text',''))
   if block['type']=='TABLE':
    texts.extend(' | '.join(str(c) for c in row) for row in block.get('rows',[]))
    if not specialized and not block.get('template') and not block['locator'].startswith('text:') and block['locator']!='text':
     table_kind='DESIGN' if block.get('semantic_type') in ('DESIGN_NODES','DESIGN_STATIONS') else kind
     tables+=extract_tables(block.get('rows',[]),block['locator'],{},table_kind)
  alltext='\n'.join(texts)
  tables += [ExtractedTrajectory(**t) for t in specialized]
  if result['format'] in ('xls','xlsx'):
   result['metadata']['workbook_sheets']=list(dict.fromkeys(b['sheet'] for b in document['blocks'] if b.get('sheet')))
  if result['format'] in ('text','txt','csv','tsv','asc','dat','mwd','survey','md'):
   alltext,enc=decode(data);result['metadata']['encoding']=enc;tables=_text_tables(alltext,kind)
  elif suffix in ('.xml','.witsml'):
   import xml.etree.ElementTree as ET
   root=ET.fromstring(data)
   containers=[e for e in root.iter() if e.tag.rsplit('}',1)[-1].lower()=='trajectory'] or [root]
   for ti,container in enumerate(containers,1):
    rows=[];reference={};units={}
    for si,station in enumerate(e for e in container.iter() if e.tag.rsplit('}',1)[-1].lower()=='trajectorystation'):
     values={}
     for child in station:
      local=child.tag.rsplit('}',1)[-1].lower()
      key={'md':'md','measureddepth':'md','incl':'inclination','inc':'inclination','inclination':'inclination','azi':'azimuth','azimuth':'azimuth','tvd':'tvd','north':'north','east':'east'}.get(local)
      if key and child.text and child.text.strip():values[key]=number(child.text);units[key]=child.attrib.get('uom','')
     if {'md','inclination','azimuth'}<=values.keys():rows.append({**values,'source_row':si+1})
    if rows:
     reference={'length_unit':'ft' if units.get('md') in ('ft','ftUS') else 'm','angle_unit':'rad' if units.get('inclination')=='rad' else 'deg'}
     tables.append(ExtractedTrajectory(f'xml:trajectory:{ti}',list(ALIASES),rows,{'md':0,'inclination':1,'azimuth':2},reference,kind,container.attrib.get('uid','')))
   if not tables:raise ValueError('WITSML XML contains no trajectory stations')
  elif document['status']=='UNSUPPORTED':result['diagnostics'].append({'code':'ATTACHMENT_ONLY','message':'原件归档；此文件类型未提取正文或表格'})
 except Exception as exc:
  from .document_structure import PARSER_VERSION
  diagnostic={'code':'PARSE_ERROR','message':str(exc)[:500]}
  result['diagnostics'].append(diagnostic)
  result['document']={'parser_version':PARSER_VERSION,'format':result['format'],'title':Path(name).stem,'source_sha256':result['sha256'],'status':'FAILED','blocks':[],'warnings':[diagnostic]}
 # Extract explicit reference metadata, never assume a projection/height datum.
 from .survey_metadata import survey_metadata
 result['metadata'].update(survey_metadata(alltext))
 for key,pattern in {'wellhead_north':r'纵坐标\s*[（(]?X[)）]?\s*[：:]\s*([\d.]+)','wellhead_east':r'横坐标\s*[（(]?Y[)）]?\s*[：:]\s*([\d.]+)','magnetic_declination':r'磁偏角\s*[：:]\s*([+-]?[\d.]+)','meridian_convergence':r'子午线收敛角\s*[：:]\s*([+-]?[\d.]+)'}.items():
  found=re.search(pattern,alltext,re.I)
  if found:result['metadata'][key]=float(found.group(1))
 result['text_excerpt']=alltext[:250000]
 from app.domain.engineering_documents import describe_document
 result['engineering']=describe_document(name,result['text_excerpt'],str(p))
 # Engineering documents carry the coordinate/elevation evidence alongside
 # trajectory rows.  Preserve it as source metadata; a missing CRS or vertical
 # datum is intentionally left unresolved rather than guessed from coordinate
 # magnitudes.
 engineering=result['engineering']
 for key in ('basin','structural_position','geological_structure','oilfield',
             'development_block','location','administrative_location',
             'coordinate_system_text','coordinate_datum_name','wellhead_north',
             'wellhead_east','ground_elevation_m','kelly_bushing_elevation_m',
             'latitude','longitude','design_depth_m','actual_depth_m',
             'formation','target_metadata'):
  value=engineering.get(key)
  if value not in (None,''):
   result['metadata'][key]=value
 for table in tables:
  table.reference={**result['metadata'],**table.reference}
  if engineering.get('coordinate_system_text'):
   table.reference.setdefault('coordinate_system_text',engineering['coordinate_system_text'])
  if engineering.get('coordinate_datum_name'):
   table.reference.setdefault('coordinate_datum_name',engineering['coordinate_datum_name'])
 # Identical node/detail tables are separate source locators; import policy
 # selects/labels them, rather than silently concatenating trajectories.
 result['trajectories']=[t.to_dict() for t in tables]
 attachment_codes={'ATTACHMENT_ONLY','BINARY_ATTACHMENT'}
 result['status']='REVIEW_REQUIRED' if any(d['code'] not in attachment_codes for d in result['diagnostics']) else 'PARSED' if tables or result.get('document',{}).get('blocks') else 'ATTACHMENT'
 return result

def map_source_table(data: bytes, original_name: str, selection: dict | None = None) -> dict:
 """Offer original cells and create an immutable, explicitly mapped extraction.

 No rows are accepted from the browser. Row/column coordinates are validated
 against the archived source bytes so a manual mapping retains real provenance.
 """
 suffix=Path(original_name).suffix.lower();tables=[]
 def add(locator,rows):
  cells=[[str(v) if v is not None else '' for v in row] for row in rows]
  if cells:tables.append((locator,cells))
 if data.startswith(b'PK') and suffix in ('.xlsx','.xlsm','.xls'):
  from openpyxl import load_workbook
  book=load_workbook(io.BytesIO(data),read_only=True,data_only=True)
  try:
   for sheet in book:add('sheet:'+sheet.title,sheet.iter_rows(values_only=True))
  finally:book.close()
 elif suffix=='.xls' and data.startswith(bytes.fromhex('D0CF11E0')):
  import xlrd
  book=xlrd.open_workbook(file_contents=data,on_demand=True)
  try:
   for sheet in book.sheets():add('sheet:'+sheet.name,(sheet.row_values(i) for i in range(sheet.nrows)))
  finally:book.release_resources()
 elif suffix=='.docx':
  from docx import Document
  for i,table in enumerate(Document(io.BytesIO(data)).tables,1):add(f'table:{i}',([c.text for c in row.cells] for row in table.rows))
 elif suffix in ('.doc','.wps'):
  with tempfile.TemporaryDirectory() as td:
   source=Path(td)/('source'+suffix);source.write_bytes(data)
   converted=Path(td)/'source.docx'
   import shutil
   if shutil.which('textutil'):subprocess.run(['textutil','-convert','docx','-output',str(converted),str(source)],check=True,capture_output=True,timeout=90)
   elif shutil.which('libreoffice'):subprocess.run(['libreoffice','-env:UserInstallation=file://'+td+'/profile','--headless','--convert-to','docx','--outdir',td,str(source)],check=True,capture_output=True,timeout=90)
   else:raise ValueError('旧Word解析需要 textutil 或 LibreOffice')
   return map_source_table(converted.read_bytes(),'converted.docx',selection)
 elif suffix=='.pdf':
  import pdfplumber
  with pdfplumber.open(io.BytesIO(data)) as pdf:
   for i,page in enumerate(pdf.pages,1):
    for j,rows in enumerate(page.extract_tables() or [],1):add(f'page:{i}:table:{j}',rows)
 elif suffix in ('','.txt','.csv','.tsv','.asc','.dat','.mwd','.survey','.md'):
  text,_=decode(data);rows=[]
  for line in text.splitlines():
   delimiter=next((d for d in ('\t',',',';') if d in line),None)
   rows.append(next(csv.reader([line],delimiter=delimiter)) if delimiter else line.split())
  add('text',rows)
 else:raise ValueError('此格式不支持手工列映射；请下载原件复核或提供可读表格')
 catalog=[{'locator':loc,'row_count':len(rows),'column_count':max(map(len,rows)),
           'preview':[{'source_row':i+1,'cells':r} for i,r in enumerate(rows[:100])]} for loc,rows in tables]
 if not selection:return {'tables':catalog}
 lookup=dict(tables);loc=selection.get('locator');rows=lookup.get(loc)
 if rows is None:raise ValueError('来源表定位不存在')
 mapping=selection.get('mapping') or {}
 if not {'md','inclination','azimuth'}<=mapping.keys() or not set(mapping)<=set(ALIASES):raise ValueError('必须选择 MD、井斜、方位列')
 if any(type(v) is not int or v<0 or v>=max(map(len,rows)) for v in mapping.values()) or len(set(mapping.values()))!=len(mapping):raise ValueError('字段列号越界或重复')
 header=selection.get('header_row',1);start=selection.get('start_row',header+1);end=selection.get('end_row',len(rows))
 if any(type(v) is not int for v in (header,start,end)) or not 1<=header<start<=end<=len(rows):raise ValueError('表头和数据起止行无效')
 measurements=[]
 for i in range(start-1,end):
  row=rows[i];values={key:row[column] if column<len(row) else '' for key,column in mapping.items()}
  if not any(str(values[k]).strip() for k in ('md','inclination','azimuth')):continue
  for key,value in list(values.items()):
   try:values[key]=number(value)
   except (ValueError,TypeError):pass  # preview/cleaning reports invalid cells
  measurements.append({**values,'source_row':i+1,'raw_cells':row})
 if not measurements:raise ValueError('所选范围没有测点')
 token=hashlib.sha256(json.dumps(selection,sort_keys=True).encode()).hexdigest()[:12]
 extraction=ExtractedTrajectory(f'{loc}:header:{header}:manual:{token}',rows[header-1],measurements,mapping,{},'ACTUAL','人工字段映射').to_dict()
 extraction['manual_mapping']=selection
 return {'extraction':extraction,'count':len(measurements)}


def clean_rows(rows,policy=None,*,check_anomalies=True):
 policy={'duplicate_tolerance':1e-6,'max_dls_deg_per_30m':30.,'remove_anomalies':False,**(policy or {})};accepted=[];issues=[]
 for key in ('duplicate_tolerance','max_dls_deg_per_30m'):
  try:policy[key]=float(policy[key])
  except (TypeError,ValueError):raise ValueError('清洗容差和狗腿度阈值必须为正有限数值')
  if not math.isfinite(policy[key]) or policy[key]<=0:raise ValueError('清洗容差和狗腿度阈值必须为正有限数值')
 if not isinstance(policy['remove_anomalies'],bool):raise ValueError('剔除异常点必须为布尔值')
 valid=[]
 for row in rows:
  try:
   r={**row,**{k:number(row[k]) for k in ('md','inclination','azimuth')}}
   if not all(math.isfinite(r[k]) for k in ('md','inclination','azimuth')) or r['md']<0 or not 0<=r['inclination']<=180 or not 0<=r['azimuth']<=360:raise ValueError('测深或角度超出有效范围')
  except (ValueError,TypeError,KeyError) as exc:issues.append({'type':'INVALID_POINT','severity':'ERROR','row':row,'action':'EXCLUDED','message':str(exc)});continue
  valid.append(r)
 for r in sorted(valid,key=lambda r:r['md']):
  old=accepted[-1] if accepted and r['md']-accepted[-1]['md']<=policy['duplicate_tolerance'] else None
  if old:
   same=abs(old['inclination']-r['inclination'])<=1e-7 and abs((old['azimuth']-r['azimuth']+180)%360-180)<=1e-7
   issues.append({'type':'DUPLICATE_POINT' if same else 'MD_CONFLICT','severity':'INFO' if same else 'ERROR','row':r,'action':'EXCLUDED' if same else 'QUARANTINED'});continue
  r['azimuth']%=360;accepted.append(r)
 accepted.sort(key=lambda r:r['md']);out=[]
 for r in accepted:
  if out and check_anomalies:
   from app.shared.trajectory.min_curvature import minimum_curvature_increment
   try:_,dogleg,_=minimum_curvature_increment(r['md']-out[-1]['md'],out[-1]['inclination'],out[-1]['azimuth'],r['inclination'],r['azimuth']);dls=dogleg*30/(r['md']-out[-1]['md'])
   except ValueError:dls=math.inf
   if dls>policy['max_dls_deg_per_30m']:
    issues.append({'type':'DLS_OUTLIER','severity':'WARNING','row':r,'dls':dls if math.isfinite(dls) else None,'action':'EXCLUDED' if policy['remove_anomalies'] else 'REVIEW'})
    if policy['remove_anomalies']:continue
  out.append(r)
 return out,issues


def _export_number(value: Any) -> str:
    """Render a stored station value without changing source units/labels."""
    if value is None:
        return ""
    if isinstance(value, float):
        if not math.isfinite(value):
            return ""
        return format(value, ".12g")
    return str(value)


def _station_values(row: dict, mapping: dict) -> dict:
    return {
        key: _export_number(row.get(key))
        for key in mapping
        if key in {"md", "inclination", "azimuth", "north", "east", "tvd"}
    }


def _select_extraction(extraction: dict | None, trajectories: list[dict]) -> dict:
    if extraction:
        return extraction
    if len(trajectories) != 1:
        raise ValueError("源文件包含多个测斜表，必须明确 locator")
    return trajectories[0]


def _patch_rows(rows: list[list[Any]], extraction: dict, stations: list[dict], *, numeric: bool = False) -> list[list[Any]]:
    """Patch only the selected source table, retaining all unrelated cells."""
    mapping = {str(k): int(v) for k, v in (extraction.get("mapping") or {}).items()}
    source_rows = extraction.get("rows") or []
    if not mapping or not source_rows:
        raise ValueError("来源提取表没有可回写的字段映射")
    # source_row is one-based as presented to the operator; convert once to
    # the zero-based list index used by csv/worksheet rows.
    positions = [int(row['source_row']) - 1 for row in source_rows]
    if positions != sorted(set(positions)) or not positions or positions[0] < 0 or positions[-1] >= len(rows):
        raise ValueError('来源行定位失效，请重新解析原件')
    # Address actual source rows. Notes between measurements are never used as
    # data slots. Extra rows are inserted before the footer/next table.
    extra = max(0, len(stations) - len(positions))
    insertion = positions[-1] + 1
    rows[insertion:insertion] = [[] for _ in range(extra)]
    positions.extend(range(insertion, insertion + extra))
    for index, station in enumerate(stations):
        target_index = positions[index]
        target = list(rows[target_index])
        width = max(len(target), max(mapping.values()) + 1)
        target.extend([""] * (width - len(target)))
        values = _station_values(station, mapping)
        for key, column in mapping.items():
            if key in values:
                target[column] = float(values[key]) if numeric and values[key] != "" else values[key]
        rows[target_index] = target
    # Clear stale rows when the new immutable snapshot is shorter. Keep raw
    # non-trajectory columns (remarks/source IDs) and remove mapped values only.
    for index in positions[len(stations):]:
        target = list(rows[index])
        for column in mapping.values():
            if column < len(target):
                target[column] = ""
        rows[index] = target
    return rows


def _source_units(stations, reference):
    """Invert the import normalization; source headers must describe the values."""
    unit = reference.get('length_unit', 'm')
    angle = reference.get('angle_unit', 'deg')
    scale = .3048 if unit == 'ft' else 1.
    angular_scale = math.pi / 180 if angle == 'rad' else 1.
    north = reference.get('north_reference', 'GRID_NORTH')
    correction = 0 if north == 'GRID_NORTH' else -float(reference.get('meridian_convergence', 0)) + (float(reference.get('magnetic_declination', 0)) if north == 'MAGNETIC_NORTH' else 0)
    result = []
    for row in stations:
        item = dict(row)
        for key in ('md', 'north', 'east', 'tvd'):
            if item.get(key) is not None:item[key] = float(item[key]) / scale
        if item.get('inclination') is not None:item['inclination'] = float(item['inclination']) * angular_scale
        if item.get('azimuth') is not None:item['azimuth'] = ((float(item['azimuth']) - correction) % 360) * angular_scale
        result.append(item)
    return result


def export_source_document(data: bytes, original_name: str, stations: list[dict],
                           extraction: dict | None = None, *, mode: str = "PATCHED", source_reference: dict | None = None) -> tuple[bytes, str, str]:
    """Export a trajectory while preserving the source document family.

    ``ORIGINAL`` is byte-for-byte and is used for audit/download. ``PATCHED``
    rewrites only the selected trajectory table and keeps sheets, paragraphs,
    delimiters, encodings and unrelated content. PDF and legacy binary XLS
    have no safe in-place writer in the service; those formats require the
    original audit copy and are rejected for a lossy rewrite.
    """
    if mode.upper() == "ORIGINAL":
        return data, original_name, "application/octet-stream"
    if mode.upper() != "PATCHED":
        raise ValueError("export mode must be ORIGINAL or PATCHED")
    suffix = Path(original_name).suffix.lower()
    # The Java gateway passes the already selected extraction. Do not parse the
    # source again here: parsing can be expensive and could choose a different
    # table after an operator has explicitly selected a locator.
    if not extraction:
        raise ValueError("PATCHED 导出必须明确来源表 locator、headers 和 mapping")
    locator = str(extraction.get("locator", ""))
    mapping = extraction.get("mapping") or {}
    if not locator or not mapping:
        raise ValueError("来源提取表缺少 locator 或字段映射")
    stations = _source_units(stations, source_reference or extraction.get('reference') or {})

    if suffix in (".xlsx", ".xlsm") or data.startswith(b"PK") and suffix in (".xlsx", ".xlsm"):
        from openpyxl import load_workbook
        keep_vba = suffix == ".xlsm"
        wb = load_workbook(io.BytesIO(data), keep_vba=keep_vba)
        if not locator.startswith("sheet:"):
            raise ValueError("Excel 来源表 locator 无效")
        sheet_name = locator.split(":", 1)[1].split(":header", 1)[0]
        if sheet_name not in wb.sheetnames:
            raise ValueError("Excel 来源工作表不存在")
        ws = wb[sheet_name]
        values = [[cell.value for cell in row] for row in ws.iter_rows()]
        _patch_rows(values, extraction, stations, numeric=True)
        old_rows = extraction.get('rows') or []
        extra = max(0, len(stations) - len(old_rows))
        if extra:
            last = int(old_rows[-1]['source_row'])
            ws.insert_rows(last + 1, extra)
            for r in range(last + 1, last + extra + 1):
                ws.row_dimensions[r].height = ws.row_dimensions[last].height
                for c in range(1, ws.max_column + 1):
                    ws.cell(r,c)._style = copy(ws.cell(last,c)._style)
        for r, row in enumerate(values, 1):
            for c, value in enumerate(row, 1):
                if ws.cell(r,c).value != value:ws.cell(r, c).value = value
        out = io.BytesIO(); wb.save(out)
        return out.getvalue(), original_name, (
            "application/vnd.ms-excel.sheet.macroEnabled.12" if keep_vba
            else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")

    if suffix == ".docx" or (data.startswith(b"PK") and b"word/" in data[:10000]):
        from docx import Document
        document = Document(io.BytesIO(data))
        if not locator.startswith("table:"):
            raise ValueError("Word 来源表 locator 无效")
        table_index = int(locator.split(":", 1)[1].split(":header", 1)[0]) - 1
        if table_index < 0 or table_index >= len(document.tables):
            raise ValueError("Word 来源表不存在")
        table = document.tables[table_index]
        values = [[cell.text for cell in row.cells] for row in table.rows]
        _patch_rows(values, extraction, stations)
        old_rows = extraction.get('rows') or []
        extra = max(0, len(stations) - len(old_rows))
        if extra:
            anchor = table.rows[int(old_rows[-1]['source_row']) - 1]._tr
            for _ in range(extra):
                clone=deepcopy(anchor);anchor.addnext(clone);anchor=clone
        for r, row in enumerate(values):
            cells = table.rows[r].cells
            for c, value in enumerate(row):
                if c < len(cells) and cells[c].text != str(value):
                    # Keep cell borders, paragraph properties and first-run
                    # font formatting instead of replacing the whole cell.
                    paragraphs = cells[c].paragraphs
                    first = paragraphs[0]
                    runs = first.runs
                    if runs:runs[0].text=str(value)
                    else:first.add_run(str(value))
                    for run in first.runs[1:]:run.text=''
                    for paragraph in paragraphs[1:]:
                        for run in paragraph.runs:run.text=''
        out = io.BytesIO(); document.save(out)
        return out.getvalue(), original_name, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    if suffix in (".csv", ".tsv", ".txt", ".asc", ".dat", ".mwd", ".survey", ""):
        text, encoding = decode(data)
        newline = "\r\n" if "\r\n" in text else "\n"
        raw_lines = text.splitlines()
        # Detect from the mapped table rather than the suffix: field exports
        # commonly use semicolons in .csv or tabs in .txt. Notes outside the
        # table must not influence the dialect used for newly inserted rows.
        header_index = int((extraction.get('rows') or [{}])[0].get('source_row', 1)) - 1
        sample = raw_lines[header_index] if 0 <= header_index < len(raw_lines) else ''
        minimum_columns=max(int(v) for v in extraction['mapping'].values())+1
        delimiter = next((d for d in ('\t', ',', ';') if d in sample and len(next(csv.reader([sample],delimiter=d)))>=minimum_columns), None)
        rows: list[list[str]] = []
        for line in raw_lines:
            if delimiter:
                rows.append(next(csv.reader([line], delimiter=delimiter)))
            else:
                rows.append(line.split())
        original_rows = deepcopy(rows)
        _patch_rows(rows, extraction, stations)
        old_positions=[int(r['source_row'])-1 for r in extraction.get('rows',[])]
        extra=max(0,len(stations)-len(old_positions));insert_at=old_positions[-1]+1
        output_lines: list[str] = []
        for index,row in enumerate(rows):
            original_index=index-extra if index>=insert_at+extra else index
            if (index<insert_at or index>=insert_at+extra) and original_index<len(original_rows) and row==original_rows[original_index]:
                output_lines.append(raw_lines[original_index]);continue
            if delimiter:
                stream = io.StringIO(); csv.writer(stream, delimiter=delimiter, lineterminator="").writerow(row)
                output_lines.append(stream.getvalue())
            else:
                # Retain each existing whitespace delimiter, including leading
                # indentation, when the field count is unchanged.
                original_line=raw_lines[original_index] if index<insert_at or index>=insert_at+extra else ''
                parts=re.split(r'(\s+)',original_line)
                slots=[i for i,v in enumerate(parts) if v and not v.isspace()]
                if len(slots)==len(row):
                    for slot,value in zip(slots,row):parts[slot]=str(value)
                    output_lines.append(''.join(parts))
                else:output_lines.append("  ".join(row))
        return (newline.join(output_lines) + (newline if text.endswith(("\n", "\r")) else "")).encode(encoding), original_name, "text/plain; charset=" + encoding

    if suffix in (".xml", ".witsml"):
        import xml.etree.ElementTree as ET
        root = ET.fromstring(data)
        containers=[e for e in root.iter() if e.tag.rsplit('}',1)[-1].lower()=='trajectory'] or [root]
        container_index=int(locator.rsplit(':',1)[-1])-1 if locator.startswith('xml:trajectory:') else 0
        if not 0<=container_index<len(containers):raise ValueError('WITSML 来源轨迹不存在')
        container=containers[container_index]
        candidates = [e for e in container.iter() if e.tag.rsplit("}", 1)[-1].lower() == "trajectorystation"]
        if not candidates:
            raise ValueError("WITSML 未发现 trajectoryStation")
        parents={child:parent for parent in container.iter() for child in parent}
        used_uids = {e.get('uid') for e in container.iter() if e.get('uid')}
        for index,row in enumerate(stations):
            if index<len(candidates):station=candidates[index]
            else:
                station=deepcopy(candidates[-1]);parent=parents[candidates[-1]];parent.insert(list(parent).index(candidates[-1])+1+(index-len(candidates)),station)
                # A cloned measurement is a new WITSML object. Reusing its
                # predecessor's uid makes consumers overwrite the old station.
                uid = f"station-{index + 1}"
                while uid in used_uids:
                    uid += '-new'
                station.set('uid', uid)
                used_uids.add(uid)
            def child_value(names):
                for child in station:
                    if child.tag.rsplit("}", 1)[-1].lower() in {name.lower() for name in names}:
                        return child
                return None
            for key, names in {"md": {"md", "measuredDepth"}, "inclination": {"incl", "inc", "inclination"}, "azimuth": {"azi", "azimuth"}}.items():
                node = child_value(names)
                if node is not None and key in row:
                    node.text = _export_number(row[key])
        for station in candidates[len(stations):]:parents[station].remove(station)
        return ET.tostring(root, encoding="utf-8", xml_declaration=True), original_name, "application/xml"

    raise ValueError("PDF、旧 XLS、旧 Word 只支持原件下载；请导出标准化轨道归算表")
