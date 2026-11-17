"""File editions and independently numbered trajectory snapshots on an isolated backend."""
import copy
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import uuid

import httpx

assert os.environ.get('DRILLING_QA_ALLOW_MUTATION') == '1', 'Use a disposable QA database.'
base = os.environ.get('DRILLING_QA_BACKEND', 'http://127.0.0.1:18080')
assert ':18080' in base, 'Mutating regression is restricted to the isolated backend.'
session = os.environ['DRILLING_SESSION_ID']
client = httpx.Client(base_url=base, headers={'X-Requested-With': 'XMLHttpRequest'}, timeout=90)
client.cookies.set('JSESSIONID', session, domain='127.0.0.1', path='/')
field = int(os.environ.get('DRILLING_QA_FIELD', '13'))
root = f'/api/drilling/fields/{field}/registry'
library = f'/api/drilling/fields/{field}/library'
checks = []

def check(value, name):
    assert value, name
    checks.append(name)
    print('PASS ' + name, flush=True)

def get(path, **params):
    r = client.get(path, params=params)
    r.raise_for_status()
    result = r.json()
    assert not isinstance(result, dict) or result.get('code', 200) in [0, 200], result
    return result

def successful(response):
    assert response.status_code in [200, 201], response.text[:800]
    result = response.json()
    assert result.get('code', 200) in [0, 200], result
    return result

tree = get('/api/drilling/library/tree')
bore = next(n for n in tree if n.get('field_id') == field and n['node_type'] == 'WELLBORE' and n['wellbore_id'] == 208)
other = next(n for n in tree if n.get('field_id') == field and n['node_type'] == 'WELLBORE' and n['wellbore_id'] != bore['wellbore_id'])
node = bore['node_id']; bid = bore['wellbore_id']
versions_path = root + f'/wellbores/{bid}/versions'
before = get(versions_path)
heads_before = [(b['wellbore_id'], b['actual_version_id'], b['design_version_id']) for b in get(root+'/wellbores')]
contents_before = get(library + f'/nodes/{node}')
revisions_before = contents_before['revisions']
nonce = uuid.uuid4().hex
payload = f'MD,Inc,Azi\n0,10,20\n30,12,22\n60,15,25\n# {nonce}\n'.encode()
body = {'nodeId': node, 'documentRole': 'TRAJECTORY_DATA', 'trajectoryKind': 'ACTUAL'}

def upload(data, name, content, http=client):
    return http.post(root+'/sources', data=data, files={'file': (name, content, 'text/csv')})

original = successful(upload(body, '测斜初版-'+nonce+'.csv', payload))
doc = original['document_id']; source = original['source_id']
check(original['edition_no'] == 1 and not original['file_unchanged'], '首次文件入库为第1版')
context = get(library+f'/documents/{doc}')
check(context['trajectory_kind']=='ACTUAL' and context['wellbore_id']==bid and len(context['engineering_path'].split(' / '))==4, '更新上下文包含完整工程归属和实钻类别')

new_payload = payload.replace(b'60,15,25', b'60,16,26')
update = dict(body, documentId=doc, expectedEditionNo=1)
updated = successful(upload(update, '复测文件改名-'+nonce+'.csv', new_payload))
check(updated['document_id']==doc and updated['edition_no']==2, '改名上传保留同一资料身份并生成第2版')
check(updated['review_status']=='PENDING', '新文件版次仍需工程人员核对')
rows = get(library+f'/nodes/{node}')
listed = [r for r in rows['files'] if r['document_id']==doc]
check(len(listed)==1 and listed[0]['source_id']==updated['source_id'] and listed[0]['trajectory_kind']=='ACTUAL', '资料列表只显示该文件最新一版及实钻类别')
history = get(library+f'/documents/{doc}/history')
check([r['edition_no'] for r in history]==[2,1], '历史版次按倒序保留')
for row, content in zip(history, [new_payload,payload]):
    response = client.get(root+f"/sources/{row['source_id']}/original")
    check(response.status_code==200 and response.content==content and hashlib.sha256(content).hexdigest()==row['sha256'], f"第{row['edition_no']}版原件字节与摘要一致")
    disposition=response.headers.get('content-disposition','')
    check('filename*=' in disposition and disposition.isascii(), f"第{row['edition_no']}版中文原件下载名称使用有效编码")
retry = successful(upload(update, '重复文件改名.csv', new_payload))
check(retry['file_unchanged'] and retry['edition_no']==2 and len(get(library+f'/documents/{doc}/history'))==2, '相同内容重试不新增文件版次')
reverted = successful(upload(update, '重复旧内容.csv', payload))
check(reverted['file_unchanged'] and reverted['edition_no']==1 and get(library+f'/documents/{doc}')['latest_edition_no']==2, '重传历史内容不回退最新文件版次')
conflict = upload(update, '过期更新.csv', new_payload+b'# stale\n')
check(conflict.status_code==409 and len(get(library+f'/documents/{doc}/history'))==2, '过期文件版次返回409且不写入新版本')
for invalid, name in [
    ({**update,'nodeId':other['node_id']},'禁止更改原文件所属井眼'),
    ({**update,'trajectoryKind':'DESIGN'},'禁止把实钻文件版次改成设计类别'),
    ({**update,'documentRole':'REFERENCE'},'禁止改变原资料用途'),
    ({k:v for k,v in update.items() if k!='expectedEditionNo'},'更新文件必须携带原文件版次')]:
    check(upload(invalid,'invalid.csv',payload+b'# invalid').status_code==422, name)

# Both requests begin with the same edition. A document row lock and optimistic
# edition check must permit exactly one to append, even if parsing overlaps.
def concurrent_upload(suffix):
    with httpx.Client(base_url=base,headers={'X-Requested-With':'XMLHttpRequest'},timeout=90) as http:
        http.cookies.set('JSESSIONID',session,domain='127.0.0.1',path='/')
        return upload({**update,'expectedEditionNo':2},f'并发{suffix}.csv',new_payload+f'# concurrent {suffix}'.encode(),http)
with ThreadPoolExecutor(max_workers=2) as pool:
    concurrent = list(pool.map(concurrent_upload,['A','B']))
check(sorted(r.status_code for r in concurrent)==[200,409] and len(get(library+f'/documents/{doc}/history'))==3, '并发更新同一文件时仅一个请求生成第3版')
check(get(versions_path)==before and [(b['wellbore_id'],b['actual_version_id'],b['design_version_id']) for b in get(root+'/wellbores')]==heads_before, '文件更新不生成轨迹数据版本，也不切换设计或实钻当前版本')
check(get(library+f'/nodes/{node}')['revisions']==revisions_before, '上传新文件不改写或发布业务修订')

# The same display filename may represent two purposes; they must not share
# document identity or file-edition sequence.
same_name='两类轨迹-'+nonce+'.csv'
a = successful(upload(body,same_name,payload+b'# actual'))
d = successful(upload({**body,'trajectoryKind':'DESIGN'},same_name,payload+b'# design'))
check(a['document_id']!=d['document_id'] and a['edition_no']==d['edition_no']==1, '同名设计与实钻文件分别建立文件版次链')
for selected, document in [('ACTUAL',a['document_id']),('DESIGN',d['document_id'])]:
    result=get(library+f'/nodes/{node}',trajectoryKind=selected,pageSize=100)
    check(result['files'] and all(r['trajectory_kind']==selected for r in result['files']) and any(r['document_id']==document for r in result['files']), '资料列表按'+selected+'类别过滤')
    result=get(library+f'/nodes/{-2000000000000-field}',trajectoryKind=selected,pageSize=1)
    check(result['files'] and all(r['trajectory_kind']==selected for r in result['files']) and result['total']>=1, '井场范围的'+selected+'过滤在分页前执行')

# Create DATA versions in interleaved order. Source confirmation is explicit;
# no upload is treated as an accepted engineering snapshot automatically.
actual = next(v for v in before if v['trajectory_kind']=='ACTUAL' and v['is_active'])
reference = copy.deepcopy(get(root+f"/versions/{actual['survey_version_id']}")['reference'])
reference['survey_tool']={'name':'版本控制回归工具','code':'QA-ONLY','serial_number':nonce}
reference['uncertainty']={'model_version':'constant_isotropic','settings':{'uncertainty_model':{'type':'constant_isotropic','sigma_m':1}},'parameter_source':'MANUAL','confirmed':True}
reference.update(length_unit='m',angle_unit='deg',north_reference='GRID_NORTH',origin_north=0,origin_east=0,origin_tvd=0)
max_no={kind:max((v['version_no'] for v in before if v['trajectory_kind']==kind),default=0) for kind in ['DESIGN','ACTUAL']}
created=[]

def create_data(kind, seq, mode='FULL', parent=None):
    tail='90,18,28\n' if mode=='INCREMENTAL' else f'0,10,20\n30,{12+seq/100},22\n60,{15+seq/100},25\n'
    source=successful(upload({**body,'trajectoryKind':kind},f'data-{kind}-{nonce}-{seq}.csv',('MD,Inc,Azi\n'+tail).encode()))
    confirmed=successful(client.put(root+f"/sources/{source['source_id']}/review",json={'status':'CONFIRMED','confirmed':{'well':'井1','wellbore':'MAIN','neighbor_candidates':[]}}))
    assert confirmed['status']=='CONFIRMED'
    extraction=get(root+f"/sources/{source['source_id']}/extractions")[0]['extraction_id']
    data={'wellboreId':bid,'kind':kind,'importMode':mode,'reference':reference,'activate':False,'note':'隔离数据库版本控制回归'}
    if parent is not None:data['parentVersionId']=parent
    if mode=='RESURVEY':data.update(replaceFromMd=0,replaceToMd=60)
    result=successful(client.post(root+f'/extractions/{extraction}/commit',json=data))
    max_no[kind]+=1
    check(result['version_no']==max_no[kind] and result['trajectory_kind']==kind, f'{kind} {mode} 独立连续分配v{max_no[kind]}')
    created.append(result)
    return result

p=create_data('DESIGN',1)
m=create_data('ACTUAL',2)
p2=create_data('DESIGN',3,parent=p['survey_version_id'])
m2=create_data('ACTUAL',4,mode='INCREMENTAL',parent=m['survey_version_id'])
m3=create_data('ACTUAL',5,mode='RESURVEY',parent=m2['survey_version_id'])
check(m2['max_md']==90 and m3['max_md']==90, '实钻增量延续父版本，复测替换区间并保留其后测点')
for child,parent in [(p2,p),(m2,m),(m3,m2)]:
    row=next(v for v in get(versions_path) if v['survey_version_id']==child['survey_version_id'])
    check(row['parent_version_no']==parent['version_no'] and row['parent_version_id']==parent['survey_version_id'], '父版本显示本类别编号且保留原始版本ID：'+str(child['survey_version_id']))
check([(b['wellbore_id'],b['actual_version_id'],b['design_version_id']) for b in get(root+'/wellbores')]==heads_before, '保存非当前分支不改变任一类别当前版本')
check(all(get(root+f"/versions/{v['survey_version_id']}")['version_no']==v['version_no'] for v in created), '轨迹详情与版本列表的独立编号一致')
check(all(get(root+f"/versions/{v['survey_version_id']}")['stations'] for v in created), '各新数据版本均保存独立测点')
for kind in ['ACTUAL','DESIGN']:
    numbers=sorted(v['version_no'] for v in get(versions_path,kind=kind))
    check(numbers==list(range(1,len(numbers)+1)), kind+'编号自1连续且无重复')
# Separate files can be committed at the same time without reusing a number.
commit_inputs=[]
for offset in [7,8]:
    content=f'MD,Inc,Azi\n0,10,20\n30,{12+offset/100},22\n60,{15+offset/100},25\n'.encode()
    source=successful(upload(body,f'parallel-{nonce}-{offset}.csv',content))
    successful(client.put(root+f"/sources/{source['source_id']}/review",json={'status':'CONFIRMED','confirmed':{'well':'井1','neighbor_candidates':[]}}))
    extraction=get(root+f"/sources/{source['source_id']}/extractions")[0]['extraction_id']
    commit_inputs.append(extraction)
def parallel_commit(extraction):
    with httpx.Client(base_url=base,headers={'X-Requested-With':'XMLHttpRequest'},timeout=90) as http:
        http.cookies.set('JSESSIONID',session,domain='127.0.0.1',path='/')
        return successful(http.post(root+f'/extractions/{extraction}/commit',json={'wellboreId':bid,'kind':'ACTUAL','importMode':'FULL','reference':reference,'activate':False}))
with ThreadPoolExecutor(max_workers=2) as pool:
    parallel_versions=list(pool.map(parallel_commit,commit_inputs))
check(sorted(v['version_no'] for v in parallel_versions)==[max_no['ACTUAL']+1,max_no['ACTUAL']+2],'并发保存实钻轨迹分配不同且连续的版本号')
check(sorted(v['version_no'] for v in get(versions_path,kind='ACTUAL'))==list(range(1,max_no['ACTUAL']+3)),'并发写入后实钻版本编号仍连续无冲突')

# Explicitly activating one category leaves the other category unchanged.
original_actual=next(v['survey_version_id'] for v in before if v['trajectory_kind']=='ACTUAL' and v['is_active'])
original_design=next(v['survey_version_id'] for v in before if v['trajectory_kind']=='DESIGN' and v['is_active'])
successful(client.put(root+f"/versions/{p2['survey_version_id']}/activate",json={'expectedCurrentVersionId':original_design}))
current=next(b for b in get(root+'/wellbores') if b['wellbore_id']==bid)
check(current['design_version_id']==p2['survey_version_id'] and current['actual_version_id']==original_actual,'切换设计当前版本不影响实钻当前版本')
successful(client.put(root+f"/versions/{m3['survey_version_id']}/activate",json={'expectedCurrentVersionId':original_actual}))
current=next(b for b in get(root+'/wellbores') if b['wellbore_id']==bid)
check(current['actual_version_id']==m3['survey_version_id'] and current['design_version_id']==p2['survey_version_id'],'切换实钻当前版本不影响设计当前版本')
for version,current_id in [(original_actual,m3['survey_version_id']),(original_design,p2['survey_version_id'])]:
    successful(client.put(root+f'/versions/{version}/activate',json={'expectedCurrentVersionId':current_id}))
Path(os.environ.get('DRILLING_QA_REPORT','/tmp/file-version-control-results.json')).write_text(json.dumps({'passed':True,'checks':checks,'count':len(checks)},ensure_ascii=False,indent=2)+'\n')
