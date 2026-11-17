"""HTTP regression for the operations console. Use an isolated QA schema only."""
import os,json,time,subprocess,re
from pathlib import Path
from datetime import datetime,timedelta
from zoneinfo import ZoneInfo
import requests
assert os.environ.get('ACCESS_QA_ALLOW_MUTATION')=='1','An isolated schema and explicit QA mutation opt-in are required'
BASE=os.environ.get('ACCESS_QA_BASE','http://127.0.0.1:18080')
results=[]
def call(s,method,path,**kw):
 r=s.request(method,BASE+path,headers={'X-Requested-With':'XMLHttpRequest'},timeout=20,**kw)
 try:d=r.json()
 except ValueError:d={'raw':r.text[:500]}
 return r,d
def ok(d):return isinstance(d,dict) and d.get('code') in [0,200]
def check(name,value):
 results.append({'name':name,'passed':bool(value)})
 assert value,name
 print('PASS',name,flush=True)
def login(name,pwd):
 s=requests.Session();r,d=call(s,'POST','/login',data={'username':name,'password':pwd,'rememberMe':'false'});check('login '+name,ok(d));return s
admin=login('admin',os.environ['ACCESS_QA_ADMIN_PASSWORD'])
for res in ['login-logs','sessions','audit','settings','dictionaries','notices','jobs','job-logs','monitor']:
 r,d=call(admin,'GET','/api/operations/'+res);check('administrator can list '+res,r.status_code==200 and ok(d))
_,d=call(admin,'GET','/api/operations/settings');settings={x['configKey']:x for x in d['rows']}
for key,bad in [('drilling.session.timeoutMinutes','4'),('drilling.monitor.refreshSeconds','301'),('drilling.maintenance.retentionDays','0')]:
 row=dict(settings[key],configValue=bad);r,d=call(admin,'PUT',f"/api/operations/settings/{row['configId']}",json=row);check('setting range '+key,r.status_code==400)
row=dict(settings['drilling.system.name']);row['configValue']='钻井运维测试';r,d=call(admin,'PUT',f"/api/operations/settings/{row['configId']}",json=row);check('update built-in global name',ok(d))
_,d=call(admin,'GET','/api/reference/settings');check('global name actually consumed',d['data']['systemName']==row['configValue'])
call(admin,'PUT',f"/api/operations/settings/{row['configId']}",json=settings['drilling.system.name'])
row=dict(settings['drilling.session.timeoutMinutes'],configValue='15');call(admin,'PUT',f"/api/operations/settings/{row['configId']}",json=row)
_,d=call(admin,'GET','/api/operations/sessions');check('session timeout updated on real request',any(x['current'] and x['timeoutMinutes']==15 for x in d['rows']))
call(admin,'PUT',f"/api/operations/settings/{row['configId']}",json=settings['drilling.session.timeoutMinutes'])
r,d=call(admin,'DELETE',f"/api/operations/settings/{row['configId']}");check('built-in config protected',r.status_code==400)
custom={'configName':'测试扩展参数','configKey':'drilling.custom.qa','configValue':'value','remark':'integration'}
r,d=call(admin,'POST','/api/operations/settings',json=custom);check('custom config creation',ok(d))
_,d=call(admin,'GET','/api/operations/settings',params={'keyword':'drilling.custom.qa'});cid=d['rows'][0]['configId']
r,d=call(admin,'POST','/api/operations/settings',json=custom);check('duplicate config rejected',r.status_code==400)
call(admin,'DELETE',f'/api/operations/settings/{cid}');_,d=call(admin,'POST','/api/operations/settings/refresh');check('global cache refreshed',ok(d))
r,d=call(admin,'POST','/api/operations/dictionaries',json={'dictName':'测试字典','dictType':'drilling_qa_types','status':'0'});check('create drilling dictionary',ok(d))
_,d=call(admin,'GET','/api/operations/dictionaries',params={'keyword':'drilling_qa_types'});tid=d['rows'][0]['dictId']
item={'dictLabel':'测试类别','dictValue':'QA_VALUE','dictSort':1,'status':'0'}
_,d=call(admin,'POST',f'/api/operations/dictionaries/{tid}/items',json=item);check('create dictionary item',ok(d))
r,d=call(admin,'POST',f'/api/operations/dictionaries/{tid}/items',json=item);check('dictionary value unique',r.status_code==400)
_,d=call(admin,'GET',f'/api/reference/dictionaries/drilling_qa_types');check('dictionary values available to business',d['data'][0]['dictLabel']=='测试类别')
_,d=call(admin,'GET',f'/api/operations/dictionaries/{tid}/items');iid=d['rows'][0]['dictCode']
_,d=call(admin,'PUT',f'/api/operations/dictionary-items/{iid}',json=dict(item,dictLabel='更新显示名称'));check('update dictionary item',ok(d))
_,d=call(admin,'GET','/api/reference/dictionaries/drilling_qa_types');check('dictionary cache updates immediately',d['data'][0]['dictLabel']=='更新显示名称')
r,d=call(admin,'DELETE',f'/api/operations/dictionaries/{tid}');check('nonempty dictionary protected',r.status_code==400)
call(admin,'DELETE',f'/api/operations/dictionary-items/{iid}');call(admin,'DELETE',f'/api/operations/dictionaries/{tid}')
_,d=call(admin,'GET','/api/operations/dictionaries',params={'keyword':'drilling_data_source'});builtin=d['rows'][0]['dictId']
_,d=call(admin,'GET',f'/api/operations/dictionaries/{builtin}/items');item=d['rows'][0]
r,d=call(admin,'PUT',f"/api/operations/dictionary-items/{item['dictCode']}",json=dict(item,dictValue='INVALID'));check('protocol enum immutable',r.status_code==400)
_,d=call(admin,'PUT',f"/api/operations/dictionary-items/{item['dictCode']}",json=dict(item,dictLabel='导入文件'));check('protocol label editable',ok(d))
call(admin,'PUT',f"/api/operations/dictionary-items/{item['dictCode']}",json=item)
r,d=call(admin,'DELETE',f'/api/operations/dictionaries/{builtin}');check('built-in dictionary protected',r.status_code==400)
_,d=call(admin,'POST','/api/operations/dictionaries/refresh');check('dictionary cache refresh',ok(d))
_,options=call(admin,'GET','/api/access/options');roles={r['roleKey']:r['roleId'] for r in options['data']['roles']}
accounts=[]
def account(tier):
 now=datetime.now(ZoneInfo('Asia/Shanghai')).replace(tzinfo=None)
 body={'accountTier':tier,'roleIds':[roles[tier.lower()]],'allowedModules':['realtime'],'status':'0','fieldIds':[],'validFrom':(now-timedelta(hours=1)).isoformat(timespec='seconds'),'validUntil':(now+timedelta(hours=1)).isoformat(timespec='seconds')}
 _,d=call(admin,'POST','/api/access/users',json=body);a=d['data'];accounts.append(a);return a
engineer=account('ENGINEER');guest=account('GUEST')
e=login(engineer['accountId'],'123456')
r,d=call(e,'GET','/api/notices');check('forced initial password also blocks notices',r.status_code==403 and d['code']==460)
for a in [engineer,guest]:
 s=login(a['accountId'],'123456');call(s,'PUT','/api/access/me/password',json={'oldPassword':'123456','newPassword':'Operations#2026'});a['session']=s
e=engineer['session'];g=guest['session']
for s,tier in [(e,'engineer'),(g,'guest')]:
 for path in ['login-logs','sessions','audit','settings','dictionaries','notices','jobs','job-logs','monitor']:
  r,d=call(s,'GET','/api/operations/'+path);check(tier+' denied '+path,r.status_code==403)
_,d=call(e,'GET','/api/reference/settings');check('engineer reads safe global settings',ok(d))
_,d=call(e,'GET','/api/reference/dictionaries/drilling_data_source');check('engineer reads dictionary labels',ok(d))
for path in ['/api/reference/settings','/api/notices','/api/reference/dictionaries/drilling_data_source']:
 r,d=call(g,'GET',path);check('guest real data denied '+path,r.status_code==403)
notice={'noticeTitle':'现场操作通知','noticeType':'1','noticeContent':'维护时间：03:00\n<script>alert(1)</script>','status':'0'}
_,d=call(admin,'POST','/api/operations/notices',json=notice);check('publish notice',ok(d));nid=d['data']['noticeId']
_,d=call(e,'GET','/api/notices',params={'unreadOnly':'true','keyword':'现场操作通知'});check('engineer sees published unread notice',len(d['rows'])==1 and not d['rows'][0]['isRead'])
_,d=call(e,'GET',f'/api/notices/{nid}');check('engineer notice details',d['data']['noticeContent']==notice['noticeContent'])
for _ in range(2):call(e,'POST',f'/api/notices/{nid}/read')
_,d=call(admin,'GET',f'/api/operations/notices/{nid}/readers');check('read receipt is idempotent',d['total']==1)
_,d=call(admin,'PUT',f'/api/operations/notices/{nid}',json=dict(notice,noticeContent='更新后的通知内容'));check('notice edit works',ok(d))
_,d=call(e,'GET','/api/notices',params={'keyword':'现场操作通知'});check('notice content edit resets read receipt',not d['rows'][0]['isRead'])
call(e,'POST','/api/notices/read-all');_,d=call(e,'GET','/api/notices',params={'unreadOnly':'true'});check('mark all notices read',d['unreadCount']==0)
call(admin,'PUT',f'/api/operations/notices/{nid}',json=dict(notice,status='1'))
r,d=call(e,'GET',f'/api/notices/{nid}');check('withdrawn notice hidden from engineers',r.status_code==400)
r,d=call(e,'POST',f'/api/notices/{nid}/read');check('withdrawn notice read forbidden',r.status_code==400)
call(admin,'DELETE',f'/api/operations/notices/{nid}')
# Session IDs are opaque hashes, never authentication cookies.
_,d=call(admin,'GET','/api/operations/sessions',params={'account':engineer['accountId']});check('online engineer present',d['total']>=1);session=d['rows'][0]
check('no reusable session credential exposed',len(session['sessionKey'])==64 and not any(k.lower() in ['sessionid','sessiondata','password'] for k in session))
_,d=call(admin,'GET','/api/operations/sessions');current=next(x for x in d['rows'] if x['current'])
r,d=call(admin,'POST',f"/api/operations/sessions/{current['sessionKey']}/revoke");check('current session cannot force itself out',r.status_code==400)
qa_schema=os.environ.get('OPS_QA_DATABASE')
if qa_schema:
 assert re.fullmatch(r'drilling_ops_qa_[a-zA-Z0-9_]+',qa_schema),'Database replay check requires an isolated schema'
 assert os.environ.get('MYSQL_PWD'),'Pass test database credentials in the environment'
 raw_session=e.cookies.get('JSESSIONID');assert raw_session and re.fullmatch(r'[a-zA-Z0-9-]+',raw_session)
 def sql(statement):
  subprocess.run(['mysql','-uroot','-h127.0.0.1',qa_schema,'-e',statement],env=os.environ.copy(),check=True,stdout=subprocess.DEVNULL)
 sql("CREATE TABLE drilling_qa_session_snapshot AS SELECT * FROM sys_user_online WHERE sessionId='"+raw_session+"'")
 count=subprocess.check_output(['mysql','-uroot','-h127.0.0.1','-N',qa_schema,'-e','SELECT COUNT(*) FROM drilling_qa_session_snapshot'],env=os.environ.copy()).decode().strip()
 check('captured authenticated persisted session for delayed-sync regression',count=='1')
_,d=call(admin,'POST',f"/api/operations/sessions/{session['sessionKey']}/revoke");check('administrator revokes engineer session',ok(d))
r,d=call(e,'GET','/getInfo');check('revoked session actually fails',r.status_code==401)
if qa_schema:
 sql('REPLACE INTO sys_user_online SELECT * FROM drilling_qa_session_snapshot; DROP TABLE drilling_qa_session_snapshot;')
 replay=requests.Session();replay.cookies.set('JSESSIONID',raw_session)
 r,d=call(replay,'GET','/getInfo');check('persisted revoked session cannot be restored after delayed sync',r.status_code==401 and '管理员退出' in d.get('msg',''))

_,d=call(admin,'POST','/api/operations/login-logs/unlock',json={'account':engineer['accountId']});check('administrator unlock endpoint',ok(d))
r,d=call(admin,'GET','/api/operations/login-logs',params={'pageNum':0});check('invalid pagination rejected',r.status_code==400)
_,d=call(admin,'GET','/api/operations/jobs');jobs={x['taskCode']:x for x in d['data']};check('three jobs paused by default',len(jobs)==3 and all(x['status']=='1' for x in jobs.values()))
job=jobs['SERVICE_CHECK'];jid=job['jobId']
for body,name in [({'cronExpression':'bad cron','status':'0'},'invalid cron rejected'),({'cronExpression':'* * * * * ?','status':'0'},'too frequent job rejected'),({'cronExpression':'0 0/5 * * * ?','status':'1','invokeTarget':'arbitrary.method()'},'job target immutable')]:
 r,d=call(admin,'PUT',f'/api/operations/jobs/{jid}',json=body);check(name,r.status_code==400)
_,d=call(admin,'PUT',f'/api/operations/jobs/{jid}',json={'cronExpression':'0 0/15 * * * ?','status':'0'});check('enable maintenance schedule',ok(d))
_,d=call(admin,'GET','/api/operations/jobs');check('enabled job next run available',next(x for x in d['data'] if x['jobId']==jid)['nextRun'] is not None)
call(admin,'PUT',f'/api/operations/jobs/{jid}',json={'cronExpression':job['cronExpression'],'status':'1'})
qa_dir=Path(os.environ.get('OPS_QA_DIRECTORY','/tmp/drilling-operations-qa'))
def run_job(code):
 j=jobs[code];_,before=call(admin,'GET','/api/operations/job-logs',params={'taskCode':code});n=before['total']
 _,d=call(admin,'POST',f"/api/operations/jobs/{j['jobId']}/run");check('submit '+code,ok(d))
 for _ in range(50):
  time.sleep(.2);_,logs=call(admin,'GET','/api/operations/job-logs',params={'taskCode':code})
  if logs['total']>n:return logs['rows'][0]
 raise AssertionError('Job did not finish '+code)
_,d=call(admin,'GET','/api/operations/monitor');check('healthy Java, database and test algorithm shown',all(v['status']=='UP' for v in d['data']['services'].values()))
check('monitor includes server, disk, connectors and model task state',all(k in d['data'] for k in ['server','connectors','tasks','checkedAt','refreshSeconds']))
check('health task successful',run_job('SERVICE_CHECK')['status']=='0')
(qa_dir/'algorithm-down').touch()
_,d=call(admin,'GET','/api/operations/monitor');check('unavailable algorithm visible',d['data']['services']['algorithm']['status']=='DOWN')
check('failed health task recorded as failed',run_job('SERVICE_CHECK')['status']=='1');(qa_dir/'algorithm-down').unlink()
upload=qa_dir/'upload';(upload/'download').mkdir(parents=True,exist_ok=True);(upload/'drilling-files').mkdir(exist_ok=True)
old=upload/'download/old.csv';old.write_text('old');fresh=upload/'download/fresh.csv';fresh.write_text('fresh');field=upload/'drilling-files/original.csv';field.write_text('field')
oldtime=time.time()-86400*40;os.utime(old,(oldtime,oldtime));os.utime(field,(oldtime,oldtime))
check('temporary cleanup job succeeds',run_job('TEMP_CLEANUP')['status']=='0');check('only expired temporary exports removed',not old.exists() and fresh.exists() and field.exists())
check('session cleanup succeeds',run_job('SESSION_CLEANUP')['status']=='0')
_,d=call(admin,'GET','/api/operations/audit',params={'source':'REQUEST','action':'operations','pageSize':100});check('unified audit records operations',d['total']>10)
check('unified audit has failure outcomes',any(x['outcome']=='FAILURE' for x in d['rows']))
check('audit has no password or session-cookie material','Operations#2026' not in json.dumps(d) and 'OpsQa#2026' not in json.dumps(d))
for source in ['ACCOUNT','BUSINESS','LEGACY']:
 _,d=call(admin,'GET','/api/operations/audit',params={'source':source});check('legacy source compatible '+source,ok(d))
for path in ['login-logs','audit','job-logs']:
 r=admin.get(BASE+f'/api/operations/{path}/export',headers={'X-Requested-With':'XMLHttpRequest'},timeout=20);check('CSV export '+path,r.status_code==200 and r.content.startswith(b'\xef\xbb\xbf') and 'text/csv' in r.headers['Content-Type'])
public=requests.Session()
for path in ['/api/operations/monitor','/api/operations/jobs','/api/notices','/api/reference/settings']:
 r,d=call(public,'GET',path);check('anonymous denied '+path,r.status_code==401)
for a in accounts:call(admin,'DELETE',f"/api/access/users/{a['userId']}")
output=Path(os.environ.get('OPS_QA_OUTPUT','artifacts/operations-console/integration-result.json'));output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps({'passed':len(results),'cases':results},ensure_ascii=False,indent=2));print(f'{len(results)} operations checks passed')
