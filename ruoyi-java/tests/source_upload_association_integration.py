"""Exercise uploads in a disposable database on an isolated backend."""
import json
import os
from pathlib import Path
import uuid

import httpx

assert os.environ.get('DRILLING_QA_ALLOW_MUTATION') == '1', 'Use a disposable QA database.'
base = os.environ.get('DRILLING_QA_BACKEND','http://127.0.0.1:18080')
assert ':18080' in base, 'The upload regression is restricted to the isolated backend.'
client = httpx.Client(base_url=base, headers={'X-Requested-With':'XMLHttpRequest'}, timeout=120)
client.cookies.set('JSESSIONID',os.environ['DRILLING_SESSION_ID'],domain='127.0.0.1',path='/')
checks = []
def check(condition,name):
    assert condition,name
    checks.append(name)
    print('PASS '+name,flush=True)
def get(path):
    response = client.get(path); response.raise_for_status(); return response.json()

tree = get('/api/drilling/library/tree')
field = int(os.environ.get('DRILLING_QA_FIELD','13'))
bore = next(n for n in tree if n.get('field_id')==field and n['node_type']=='WELLBORE')
well = next(n for n in tree if n['node_id']==bore['parent_id'])
other = next(n for n in tree if n.get('field_id')!=field and n['node_type']=='WELLBORE')
plan = next(n for n in tree if n.get('field_id')==field and n['node_type']=='PLAN')
root = f'/api/drilling/fields/{field}/registry'
library = f'/api/drilling/fields/{field}/library'
before_versions = get(root+f"/wellbores/{bore['wellbore_id']}/versions")
token = uuid.uuid4().hex
content = b'MD,Inc,Azi\n0,10,20\n30,12,22\n60,15,25\n# '+token.encode()
def upload(data,filename='upload-association.csv',payload=content):
    return client.post(root+'/sources',data=data,files={'file':(filename,payload,'text/csv')})

body = {'nodeId':bore['node_id'],'documentRole':'TRAJECTORY_DATA','trajectoryKind':'ACTUAL'}
response = upload(body)
check(response.status_code==200,'井眼关联文件可通过真实上传接口入库：'+str(response.status_code))
source = response.json()
association = next(a for a in source['associations'] if a['node_id']==bore['node_id'])
check(association['wellbore_id']==bore['wellbore_id'] and association['well_id']==bore['well_id'],'上传结果包含明确的井和井眼标识')
check(len(association['engineering_path'].split(' / '))==4,'上传结果返回区块、井场、井、井眼完整路径')
check(association['trajectory_kind']=='ACTUAL' and association['document_role']=='TRAJECTORY_DATA','文件用途和设计/实钻用途被持久保存')
check(source['review_status']=='PENDING','上传关联不冒充来源资料人工确认')
detail = get(library+f"/nodes/{bore['node_id']}")
file_rows = [r for r in detail['files'] if r['source_id']==source['source_id']]
check(len(file_rows)==1 and file_rows[0]['engineering_path']==association['engineering_path'],'资料列表立即显示持久化关联层级')
repeat = upload(body)
check(repeat.status_code==200 and repeat.json()['source_id']==source['source_id'],'重复上传复用原始来源记录')
detail = get(library+f"/nodes/{bore['node_id']}")
check(len([r for r in detail['files'] if r['source_id']==source['source_id']])==1,'相同井眼、用途和原件不会重复创建文件版次')
check(get(root+f"/wellbores/{bore['wellbore_id']}/versions")==before_versions,'来源入库不会直接生成或启用轨迹数据版本')

for data,name in [
    ({**body,'nodeId':well['node_id']},'轨迹数据只选择到井时拒绝上传'),
    ({**body,'nodeId':other['node_id']},'跨井场节点拒绝上传'),
    ({**body,'wellboreId':other['wellbore_id']},'节点和井眼不一致时拒绝上传'),
    ({**body,'documentRole':'UNKNOWN'},'未知文件用途被拒绝'),
    ({**body,'nodeId':plan['node_id'],'trajectoryKind':'ACTUAL'},'实钻资料不能挂在设计成果下')]:
    response = upload(data)
    check(response.status_code==422,name)

report = upload({'nodeId':well['node_id'],'documentRole':'WELL_SUMMARY'},'well-summary.txt',b'Well summary\n'+token.encode())
check(report.status_code==200 and any(a['node_id']==well['node_id'] and a['wellbore_id'] is None for a in report.json()['associations']),'井级汇总报告允许关联到井，不强行猜测主井眼')
bore_upload = upload({'wellboreId':bore['wellbore_id'],'documentRole':'TRAJECTORY_DATA','trajectoryKind':'DESIGN'},'design.csv',content+b'\n# design')
check(bore_upload.status_code==200 and any(a['wellbore_id']==bore['wellbore_id'] and a['trajectory_kind']=='DESIGN' for a in bore_upload.json()['associations']),'轨迹版本导入入口也能在文件上传时完成井眼关联')
original = client.get(root+f"/sources/{source['source_id']}/original")
check(original.status_code==200 and original.content==content,'井眼关联不改变原件字节内容')
review = get(root+f"/sources/{source['source_id']}/review")
check(review['status']=='PENDING' and any(a['node_id']==bore['node_id'] for a in review['associations']),'人工核对弹窗可读取同一文件关联层级')

batch = client.post(root+'/sources/batch',data=body,files=[('files',('batch-one.csv',content+b'\n# batch one','text/csv')),('files',('batch-two.csv',content+b'\n# batch two','text/csv'))])
check(batch.status_code==200 and batch.json()['succeeded']==2 and batch.json()['failed']==0,'批量上传接口的逐文件事务可正常提交')
check(all(any(a['wellbore_id']==bore['wellbore_id'] for a in r['associations']) for r in batch.json()['rows']),'批量上传返回每个文件的关联井眼')
rejected = client.post(root+'/sources/batch',data={**body,'nodeId':other['node_id']},files=[('files',('invalid.csv',content+b'\n# invalid','text/csv'))])
check(rejected.status_code==422,'批量上传在入库前校验工程归属')

listed = get(root+'/sources?includeAccessible=true&pageSize=100')
check(all(r.get('review_status') in ['PENDING','CONFIRMED','REJECTED'] for r in listed['rows']),'来源列表一次返回确认状态，不逐文件发起跨井场核对请求')
check(next(r for r in listed['rows'] if r['source_id']==source['source_id'])['review_status']=='PENDING','上传原件的待确认状态在列表中保持一致')
extractions=get(root+f"/sources/{source['source_id']}/extractions")
assert extractions,'The test CSV must produce an extraction'
other_root=f"/api/drilling/fields/{other['field_id']}/registry"
other_bore=get(other_root+'/wellbores')[0]
preview=client.post(other_root+f"/extractions/{extractions[0]['extraction_id']}/preview",json={'wellboreId':other_bore['wellbore_id'],'kind':'ACTUAL','importMode':'FULL','reference':{'length_unit':'m','angle_unit':'deg','north_reference':'GRID_NORTH','origin_north':0,'origin_east':0,'origin_tvd':0}})
check(preview.status_code==200 and 'stations' in preview.json(),'有权访问的原件可直接用于其他井场井眼的导入预览，无须重复上传')
check(get(root+f"/wellbores/{bore['wellbore_id']}/versions")==before_versions,'跨井场资料预览不改写原件所属井眼的轨迹版本')

output = Path(os.environ.get('DRILLING_QA_REPORT','/tmp/source-upload-association-results.json'))
output.parent.mkdir(parents=True,exist_ok=True)
output.write_text(json.dumps({'passed':True,'checks':checks},ensure_ascii=False,indent=2)+'\n')
