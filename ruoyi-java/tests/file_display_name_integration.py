"""Original filenames and mutable display names, checked only on a disposable backend."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
from urllib.parse import unquote
import uuid
import io
import zipfile
import httpx

assert os.environ.get('DRILLING_QA_ALLOW_MUTATION')=='1'
base=os.environ.get('DRILLING_QA_BACKEND','http://127.0.0.1:18080');assert ':18080' in base
session=os.environ['DRILLING_SESSION_ID']
s=httpx.Client(base_url=base,headers={'X-Requested-With':'XMLHttpRequest'},timeout=90)
s.cookies.set('JSESSIONID',session,domain='127.0.0.1',path='/')
root='/api/drilling/fields/13/registry';library='/api/drilling/fields/13/library';files='/api/drilling/fields/13/files';checks=[]
def check(value,name):
 assert value,name
 checks.append(name);print('PASS '+name,flush=True)
def success(r):
 assert r.status_code in [200,201],r.text[:700]
 v=r.json();assert not isinstance(v,dict) or v.get('code',200) in [0,200],v
 return v
def get(path,**params):return success(s.get(path,params=params))
nonce=uuid.uuid4().hex[:10];raw=f'Standard Survey Report (Design) + 井1 [复测] & {nonce}.csv';alias='井1 实钻测斜资料 '+nonce
payload=f'MD,Inc,Azi\n0,10,20\n30,12,22\n60,15,25\n# {nonce}'.encode()
body={'nodeId':778,'documentRole':'TRAJECTORY_DATA','trajectoryKind':'ACTUAL'}
before=get(root+'/wellbores/208/versions');bores_before=get(root+'/wellbores')
def upload(name,data=payload,extra=None):return s.post(root+'/sources',data={**body,**(extra or {})},files={'file':(name,data,'text/csv')})
v=success(upload(raw,extra={'displayName':alias}));fid=v['file_id'];sid=v['source_id'];did=v['document_id']
check(v['original_name']==raw,'上传保留原始名称的空格、括号、加号、方括号、中文和&符号')
check(v['display_name']==alias,'系统显示名称单独保存')
row=next(x for x in get(library+'/nodes/778',keyword=alias)['files'] if x['source_id']==sid)
check(row['original_name']==raw and row['display_name']==alias,'工程资料同时返回系统名称和原始文件名')
check(any(x['file_id']==fid for x in get(files)),'系统文件列表可查询同一原件')
check(any(x['source_id']==sid for x in get(root+'/sources',keyword=alias)['rows']),'来源资料支持按系统名称查询')
check(any(x['source_id']==sid for x in get(root+'/sources',keyword=nonce)['rows']),'来源资料支持按原始名称查询')

def original(source_id,name,content=payload):
 r=s.get(root+f'/sources/{source_id}/original');h=r.headers.get('content-disposition','')
 check(r.status_code==200 and r.content==content,'原件下载字节保持一致：'+name)
 check(h.isascii() and "filename*=UTF-8''" in h and unquote(h.split("filename*=UTF-8''",1)[1])==name,'下载响应保留原始文件名：'+name)
original(sid,raw)
new_alias='井1 实钻测斜（人工命名） '+nonce
r=success(s.put(files+f'/{fid}/display-name',json={'displayName':new_alias,'expectedDisplayName':alias}))
check(r['original_name']==raw and r['display_name']==new_alias,'更改系统名称不改变原始文件名')
check(next(x for x in get(library+'/nodes/778',keyword=new_alias)['files'] if x['source_id']==sid)['display_name']==new_alias,'工程资料列表立即使用新系统名称')
check(next(x for x in get(files) if x['file_id']==fid)['display_name']==new_alias,'系统文件与工程资料共用同一显示名称')
check(next(x for x in get('/api/drilling/centers/connectors/files',fieldId=13) if x['file_id']==fid)['display_name']==new_alias,'数据接入文件选择共用系统名称，仍以原文件ID关联')
history=get(library+f'/documents/{did}/history')
check(len(history)==1 and history[0]['display_name']==new_alias and history[0]['original_name']==raw,'修改显示名称不增加文件版次')
check(get(root+'/wellbores/208/versions')==before and get(root+'/wellbores')==bores_before,'修改显示名称不产生或启用任何轨迹数据版本')
original(sid,raw)
for bad in ['x'*256,'a\nb\nc','a\tb']:
 check(s.put(files+f'/{fid}/display-name',json={'displayName':bad,'expectedDisplayName':new_alias}).status_code==422,'无效显示名称被拒绝：'+str(len(bad))+'字符')
check(s.put(files+f'/{fid}/display-name',json={'displayName':'wrong','expectedDisplayName':alias}).status_code==409,'过期显示名称返回并发冲突')
check(s.put('/api/drilling/fields/14/files/'+str(fid)+'/display-name',json={'displayName':'wrong','expectedDisplayName':new_alias}).status_code==422,'无法通过其他井场路径修改文件名称')
check(s.put(files+f'/{fid}/display-name',json={'displayName':'wrong'}).status_code==422,'修改显示名称必须提供原显示名称')

def concurrent(value):
 with httpx.Client(base_url=base,headers={'X-Requested-With':'XMLHttpRequest'},timeout=90) as c:
  c.cookies.set('JSESSIONID',session,domain='127.0.0.1',path='/')
  return c.put(files+f'/{fid}/display-name',json={'displayName':value,'expectedDisplayName':new_alias})
with ThreadPoolExecutor(max_workers=2) as pool:responses=list(pool.map(concurrent,['并发A '+nonce,'并发B '+nonce]))
check(sorted(r.status_code for r in responses)==[200,409],'并发修改显示名称时仅一份修改成功')
current=next(x for x in get(files) if x['file_id']==fid)['display_name']
r=success(s.put(files+f'/{fid}/display-name',json={'displayName':'','expectedDisplayName':current}))
check(r['display_name'] is None and r['original_name']==raw,'清空系统名称恢复默认展示且原始名称不变')

# Different raw names remain distinct originals even if the bytes match.
other_raw=f'Standard Survey Report (Plan) + {nonce}.csv';other=success(upload(other_raw))
check(other['source_id']!=sid and other['original_name']==other_raw,'不同原始文件名、相同内容分别保留来源身份')
original(other['source_id'],other_raw)
case_raw=other_raw.replace('Standard','standard');case=success(upload(case_raw))
check(case['source_id']!=other['source_id'] and case['original_name']==case_raw,'原始名称的大小写区别得到保留')
repeat=success(upload(other_raw));check(repeat['source_id']==other['source_id'],'同名同内容重复上传继续复用原件')

# A repeated edition is still idempotent; it does not silently rename history.
repeat=success(upload('本地改名文件 '+nonce+'.csv',extra={'documentId':did,'expectedEditionNo':1,'displayName':'不应覆盖'}))
check(repeat['file_unchanged'] and repeat['original_name']==raw and len(get(library+f'/documents/{did}/history'))==1,'相同内容的文件版次重传不改名、不新增版次')
new_raw=f'Standard Survey Report (Actual) + {nonce}.csv';new_content=payload+b'\n# second edition'
updated=success(upload(new_raw,new_content,{'documentId':did,'expectedEditionNo':1,'displayName':new_alias}))
check(updated['edition_no']==2 and updated['original_name']==new_raw and updated['display_name']==new_alias,'新文件版次保留该版原始名称并可沿用系统名称')
history=get(library+f'/documents/{did}/history');check([x['original_name'] for x in history]==[new_raw,raw],'历史文件分别保留各自原始名称')
original(updated['source_id'],new_raw,new_content);original(sid,raw)

# Generic file uploads also keep the new local filename when parentFileId is set.
generic_name='系统附件 (原件) + '+nonce+'.txt'
g=success(s.post(files,data={'category':'OTHER'},files={'file':(generic_name,payload,'text/plain')}))
check(g['original_name']==generic_name,'通用系统文件上传保留原始名称')
next_name='系统附件 (下一版) + '+nonce+'.txt'
g2=success(s.post(files,data={'category':'OTHER','parentFileId':g['file_id']},files={'file':(next_name,payload+b'\n# next','text/plain')}))
check(g2['original_name']==next_name and g2['parent_file_id']==g['file_id'],'通用文件新版本不再被改为父文件名')
success(s.put(files+f"/{g['file_id']}/display-name",json={'displayName':'系统附件名称 '+nonce,'expectedDisplayName':None}))
r=s.get(files+f"/{g['file_id']}/download")
check(r.content==payload and unquote(r.headers['content-disposition'].split("filename*=UTF-8''",1)[1])==generic_name,'通用系统文件修改显示名称后下载仍为原名和原始字节')
no_ext='测斜原始记录（无后缀） '+nonce
plain=success(upload(no_ext,payload+b'\n# no extension'))
check(plain['original_name']==no_ext,'无后缀原件保留原始名称且不补扩展名')
original(plain['source_id'],no_ext,payload+b'\n# no extension')
check(upload('x'*256+'.csv',payload+b'\n# long').status_code==422,'过长原始文件名明确拒绝而非截断更名')
double_dot='复测..记录 (原件) + '+nonce+'.csv'
double=success(upload(double_dot,payload+b'\n# dots'))
# Same basename and different contents must survive a package without renaming.
same_name=success(upload(raw,payload+b'\n# distinct original'))
r=s.get(library+'/nodes/778/original-package');assert r.status_code==200,r.text[:700]
with zipfile.ZipFile(io.BytesIO(r.content)) as archive:
 manifest=json.loads(archive.read('manifest.json'))['originals']
 for source_id,name,data in [(sid,raw,payload),(same_name['source_id'],raw,payload+b'\n# distinct original'),(double['source_id'],double_dot,payload+b'\n# dots')]:
  item=next(x for x in manifest if x['sourceId']==source_id)
  check(Path(item['archivePath']).name==name and archive.read(item['archivePath'])==data,'原件资料包保留名称与字节（包括同名及连续句点）：'+name)
Path(os.environ.get('DRILLING_QA_REPORT','/tmp/file-display-name-results.json')).write_text(json.dumps({'passed':True,'checks':checks,'count':len(checks)},ensure_ascii=False,indent=2)+'\n')
