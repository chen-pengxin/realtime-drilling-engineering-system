"""Organization/data-scope/menu acceptance. Run ONLY against a disposable QA schema."""
import os,json,time,subprocess,re
from pathlib import Path
from datetime import datetime,timedelta
import requests
assert os.environ.get('ACCESS_QA_ALLOW_MUTATION')=='1','Disposable database required'
BASE=os.environ.get('ACCESS_QA_BASE','http://127.0.0.1:18080');checks=[];tag=str(int(time.time()))
def call(s,method,path,**kw):
 r=s.request(method,BASE+path,headers={'X-Requested-With':'XMLHttpRequest'},timeout=30,**kw)
 try:d=r.json()
 except ValueError:d=r.content
 return r,d
def check(name,passed,detail=None):
 checks.append({'name':name,'passed':bool(passed)})
 assert passed,(name,detail)
 print('PASS',name,flush=True)
def success(d):return isinstance(d,dict) and d.get('code') in (0,200)
def login(name,pwd):
 s=requests.Session();r,d=call(s,'POST','/login',data={'username':name,'password':pwd,'rememberMe':'false'});check('login '+name,success(d),d);return s
a=login('admin',os.environ['ACCESS_QA_ADMIN_PASSWORD'])
O='/api/operations/organization'
def get(path):
 r,d=call(a,'GET',path);check('GET '+path,r.status_code==200,d);return d['data']
def put(path,b,expected=200):
 r,d=call(a,'PUT',path,json=b);check('PUT '+path+' '+str(expected),r.status_code==expected,d);return d
opts=get('/api/access/options');perms=[x['menuId'] for x in opts['permissions']];modules=list(opts['modules'])
def dept(name,parent=0):
 body={'deptName':name+tag,'parentId':parent,'orderNum':0,'leader':'工程负责人','phone':'','email':'','status':'0'}
 r,d=call(a,'POST',O+'/departments',json=body);check('create department '+name,success(d),d)
 return next(x for x in get(O+'/departments') if x['deptName']==body['deptName'])
d1=dept('工程一部');d2=dept('一部现场组',d1['deptId']);d3=dept('工程二部');d4=dept('一部质量组',d1['deptId'])
put(O+f"/departments/{d1['deptId']}",dict(d1,parentId=d2['deptId']),400)
put(O+f"/departments/{d1['deptId']}",dict(d1,status='1'),400)
put(O+f"/departments/{d2['deptId']}",dict(d2,parentId=d3['deptId']))
changed=next(x for x in get(O+'/departments') if x['deptId']==d2['deptId']);check('move rebuilds department ancestors',str(d3['deptId']) in changed['ancestors'].split(','))
put(O+f"/departments/{d2['deptId']}",d2)
post={'postCode':'driller_'+tag,'postName':'定向工程师'+tag,'postSort':1,'status':'0','remark':'QA'}
r,d=call(a,'POST',O+'/posts',json=post);check('create position',success(d),d)
p=next(x for x in get(O+'/posts') if x['postCode']==post['postCode'])
r,d=call(a,'POST',O+'/posts',json=post);check('position code/name unique',r.status_code==400,d)
role={'roleName':'部门工程师'+tag,'roleKey':'org_'+tag,'accountTier':'ENGINEER','status':'0','allowedModules':modules,'menuIds':perms,'dataScope':'6','deptIds':[]}
r,d=call(a,'POST','/api/access/roles',json=role);check('create role with specified-field scope',success(d),d)
role=next(x for x in get('/api/access/roles') if x['roleKey']==role['roleKey'])
user={'accountTier':'ENGINEER','roleIds':[role['roleId']],'deptId':d1['deptId'],'postIds':[p['postId']],'status':'0','allowedModules':modules,'fieldIds':[]}
r,d=call(a,'POST','/api/access/users',json=user);check('create user with department and position',success(d),d);u=d['data'];uid=u['userId'];e=login(u['accountId'],'123456');r,d=call(e,'PUT','/api/access/me/password',json={'oldPassword':'123456','newPassword':'Engineer#2026'});check('password gate retained',success(d),d)
saved=get(f'/api/access/users/{uid}');check('account organization persisted',saved['deptId']==d1['deptId'] and saved['postIds']==[p['postId']])
r,d=call(a,'DELETE',O+f"/posts/{p['postId']}");check('assigned position cannot be deleted',r.status_code==400,d)
r,d=call(a,'DELETE',O+f"/departments/{d1['deptId']}");check('referenced department cannot be deleted',r.status_code==400,d)
r,d=call(a,'GET','/api/access/users',params={'deptId':d1['deptId']});check('account department filter',uid in [x['userId'] for x in d['rows']])
r,d=call(a,'GET','/api/access/users',params={'deptId':d3['deptId']});check('account filter excludes other department',uid not in [x['userId'] for x in d['rows']])
# Separate hierarchy ensures names from inaccessible fields do not leak through parent lists.
r,b=call(a,'POST','/api/drilling/blocks',json={'blockCode':'ORG_'+tag,'blockName':'组织权限区块'+tag});check('create QA block',r.status_code==201,b)
fields=[]
for idx,department in enumerate([d1,d2,d3,d4]):
 r,d=call(a,'POST','/api/drilling/fields',json={'blockId':b['block_id'],'fieldCode':f'ORG_{tag}_{idx}','fieldName':f'权限井场{idx}'});check('create field '+str(idx),r.status_code==201,d);fields.append(d['field_id'])
 put(O+f'/fields/{d["field_id"]}',{'deptId':department['deptId'],'ownerUserId':1})
p1,p2,p3,p4=fields
put(O+f'/fields/{p3}',{'deptId':d3['deptId'],'ownerUserId':uid})
# Only scope 6 uses explicit field assignment; department scopes must not be bypassed by membership.
saved['fieldIds']=[p2];put(f'/api/access/users/{uid}',saved)
fileids={}
for pid in [p1,p3]:
 r,d=call(a,'POST',f'/api/drilling/fields/{pid}/files',files={'file':('qa.csv','md,inc,azi\n0,0,0\n1,0,0\n','text/csv')},data={'category':'DATASET'});check('upload real scoped file '+str(pid),r.status_code in (200,201),d);fileids[pid]=d['file_id']
# Seed completed local history in an explicitly isolated schema. No model is trained by these fixtures.
qa_schema=os.environ.get('OPS_QA_DATABASE');history={};archived=None
if qa_schema:
 assert re.fullmatch(r'drilling_ops_qa_org_[0-9]+',qa_schema) and os.environ.get('MYSQL_PWD')
 def sql(statement):return subprocess.check_output(['mysql','-uroot','--batch','--skip-column-names',qa_schema,'-e',statement],env=os.environ).decode().strip()
 r,d=call(a,'POST','/api/drilling/fields',json={'blockId':b['block_id'],'fieldCode':'ORG_ARCHIVE_'+tag,'fieldName':'归档历史井场'});check('create history archive fixture',r.status_code==201,d);archived=d['field_id']
 for pid in [p1,p3,archived]:
  r,w=call(a,'POST',f'/api/drilling/fields/{pid}/wells',json={'wellCode':'HISTORY_'+tag,'wellName':'历史权限测试井'});check('create history well '+str(pid),r.status_code==201,w)
  fid=fileids.get(pid,fileids[p1]);pred=f'scope-{tag}-{pid}'
  statement=f"INSERT INTO prediction_task(task_id,field_id,well_id,status,result_json,create_by) VALUES('{pred}',{pid},{w['well_id']},'MODEL_NOT_CONFIGURED','{{}}','admin');INSERT INTO training_dataset(field_id,file_id,dataset_name) VALUES({pid},{fid},'scope-history');SET @ds=LAST_INSERT_ID();INSERT INTO training_template(field_id,file_id,template_name,hyperparam_schema_json) VALUES({pid},{fid},'scope-template','{{}}');SET @tpl=LAST_INSERT_ID();INSERT INTO training_task(field_id,dataset_id,template_id,hyperparams_json,status,create_by) VALUES({pid},@ds,@tpl,'{{}}','FAILED','admin');SELECT LAST_INSERT_ID();"
  history[pid]={'prediction':pred,'training':int(sql(statement))}
 r,d=call(a,'DELETE',f'/api/drilling/fields/{archived}');check('archive history field',r.status_code==204,d)
def scoped(value,depts,expected):
 role.update(dataScope=value,deptIds=depts);put(f'/api/access/roles/{role["roleId"]}',role)
 r,d=call(e,'GET','/api/drilling/fields');check('scope '+value+' field list',r.status_code==200 and set(x['field_id'] for x in d)&set(fields)==set(expected),d)
 for pid in fields:
  r,d=call(e,'GET',f'/api/drilling/fields/{pid}');check(f'scope {value} detail {pid}',(r.status_code==200)==(pid in expected),d)
  r,d=call(e,'GET',f'/api/drilling/fields/{pid}/wells');check(f'scope {value} wells {pid}',(r.status_code==200)==(pid in expected),d)
  r,d=call(e,'GET',f'/api/drilling/fields/{pid}/configs');check(f'scope {value} config {pid}',(r.status_code==200)==(pid in expected),d)
 for pid,fid in fileids.items():
  r,d=call(e,'GET',f'/api/drilling/fields/{pid}/files/{fid}/download');check(f'scope {value} actual file download {pid}',(r.status_code==200)==(pid in expected),d if r.status_code!=200 else None)
 for path in ['/api/drilling/predictions','/api/train/task/list']:
  r,d=call(e,'GET',path,params={'fieldId':p1,'field_id':p1});check(f'scope {value} task route {path}',(r.status_code==200)==(p1 in expected),d)
 if history:
  r,d=call(e,'GET','/api/drilling/predictions');actual={x['field_id'] for x in d if x.get('task_id') in {h['prediction'] for h in history.values()}};check('scope '+value+' global prediction history',actual==set(expected)&{p1,p3},d)
  r,d=call(e,'GET','/api/train/task/list');actual={x['field_id'] for x in d['tasks'] if int(x['local_task_id']) in {h['training'] for h in history.values()}};check('scope '+value+' global training history',actual==set(expected)&{p1,p3},d)
  r,d=call(e,'GET','/api/drilling/predictions/export');csv=r.content.decode('utf-8-sig');check('scope '+value+' prediction CSV export',r.status_code==200 and all((h['prediction'] in csv)==(pid in expected) for pid,h in history.items()),csv)
  for pid,h in history.items():
   for path in ['/api/drilling/predictions/'+h['prediction'],'/api/train/task/'+str(h['training'])]:
    r,d=call(e,'GET',path);check(f'scope {value} history detail {path}',(r.status_code==200)==(pid in expected),d)
scoped('6',[],[p2]);scoped('3',[],[p1]);scoped('4',[],[p1,p2,p4]);scoped('2',[d3['deptId']],[p3]);scoped('5',[],[p3]);scoped('1',[],fields)
# Multiple active roles combine data ranges; disabling a role removes its grant.
role.update(dataScope='5',deptIds=[]);put(f'/api/access/roles/{role["roleId"]}',role)
extraRole=dict(role,roleName='现场部门角色'+tag,roleKey='org_extra_'+tag,dataScope='3');extraRole.pop('roleId',None)
r,d=call(a,'POST','/api/access/roles',json=extraRole);check('create secondary department role',success(d),d)
extraRole=next(x for x in get('/api/access/roles') if x['roleKey']==extraRole['roleKey'])
current=get(f'/api/access/users/{uid}');put(f'/api/access/users/{uid}',dict(current,roleIds=[role['roleId'],extraRole['roleId']]))
r,d=call(e,'GET','/api/drilling/fields');check('multiple active scope union',set(x['field_id'] for x in d)&set(fields)=={p1,p3},d)
put(f'/api/access/roles/{extraRole["roleId"]}',dict(extraRole,status='1'))
r,d=call(e,'GET','/api/drilling/fields');check('disabled role immediately removes data grant',set(x['field_id'] for x in d)&set(fields)=={p3},d)
put(f'/api/access/users/{uid}',current);call(a,'DELETE',f'/api/access/roles/{extraRole["roleId"]}')
role.update(dataScope='3');put(f'/api/access/roles/{role["roleId"]}',role)
put(f'/api/access/users/{uid}',dict(current,deptId=d3['deptId']))
r,d=call(e,'GET','/api/drilling/fields');check('department transfer updates existing session scope',set(x['field_id'] for x in d)&set(fields)=={p3},d)
put(f'/api/access/users/{uid}',current);scoped('1',[],fields)
# A VIEWER membership remains read-only even with all-data role.
call(a,'POST',f'/api/drilling/fields/{p1}/members',json={'userId':uid,'roleCode':'VIEWER'})
r,d=call(e,'POST',f'/api/drilling/fields/{p1}/wells',json={'wellCode':'VIEWER_QA','wellName':'只读验证'});check('VIEWER cannot mutate through broad scope',r.status_code==403,d)
call(a,'DELETE',f'/api/drilling/fields/{p1}/members/{uid}')
r,d=call(e,'POST',f'/api/drilling/fields/{p1}/wells',json={'wellCode':'WRITE_QA','wellName':'工程写入验证'});check('department scope plus function permission can write',r.status_code==201,d)
r,d=call(e,'PUT',f'/api/drilling/fields/{p1}',json={'fieldName':'forbidden'});check('engineer scope cannot acquire OWNER operations',r.status_code==403,d)
# Department disabled grants disappear in an existing session.
scoped('2',[d4['deptId']],[p4]);put(O+f"/departments/{d4['deptId']}",dict(d4,status='1'))
r,d=call(e,'GET',f'/api/drilling/fields/{p4}');check('disabled department revokes real data immediately',r.status_code==403,d)
put(O+f"/departments/{d4['deptId']}",d4)
put(f'/api/access/roles/{role["roleId"]}',dict(role,dataScope='2',deptIds=[]),400)
# Menu metadata drives actual navigation, disabling module revokes backend use.
ms=get('/api/operations/menus');check('system management has four submenus',len([x for x in ms if x['parentId']==7014 and x['menuType']=='M'])==4);menu=next(x for x in ms if x['routePath']=='/drilling/tasks/replanning')
put(f'/api/operations/menus/{menu["menuId"]}',dict(menu,menuName='绕障方案',orderNum=9,visible='1'))
nav=get('/api/reference/navigation');check('navigation consumes menu metadata',any(x['menuId']==menu['menuId'] and x['menuName']=='绕障方案' and x['visible']=='1' for x in nav))
r,d=call(e,'GET','/getInfo');check('hidden menu retains function', 'replanning' in d['policy']['allowedModules'] if 'policy' in d else 'replanning' in d.get('allowedModules',[]),d)
put(f'/api/operations/menus/{menu["menuId"]}',dict(menu,status='1'))
r,d=call(e,'GET','/getInfo');pol=d.get('policy',d);check('disabled menu removes engineer module','replanning' not in pol.get('allowedModules',[]),d)
r,d=call(e,'POST','/api/algorithm/trajectory-plan',json={'field_id':p1});check('disabled module blocks algorithm gateway',r.status_code==403,d)
put(f'/api/operations/menus/{menu["menuId"]}',menu)
# Essential admin entrances cannot be disabled or hidden.
critical=next(x for x in ms if x['routePath']=='/access/menus');put(f'/api/operations/menus/{critical["menuId"]}',dict(critical,status='1'),400)
criticalParent=next(x for x in ms if x['routePath']=='/access/permissions');put(f'/api/operations/menus/{criticalParent["menuId"]}',dict(criticalParent,status='1'),400)
put(f'/api/operations/menus/{menu["menuId"]}',dict(menu,parentId=menu['menuId']),400)
put(f'/api/operations/menus/{menu["menuId"]}',dict(menu,parentId=2000),400)
# Custom directory participates in navigation and safely contains an existing page.
custom={'menuName':'现场工具'+tag,'menuType':'M','parentId':7000,'orderNum':99,'visible':'0','status':'0','icon':'tree','perms':''}
r,d=call(a,'POST','/api/operations/menus',json=custom);check('create custom navigation directory',success(d),d)
custom=next(x for x in get('/api/operations/menus') if x['menuName']==custom['menuName'])
put(f'/api/operations/menus/{menu["menuId"]}',dict(menu,parentId=custom['menuId']))
put(f'/api/operations/menus/{custom["menuId"]}',dict(custom,status='1'))
r,d=call(e,'GET','/getInfo');check('disabled reparented ancestor revokes module','replanning' not in d.get('policy',d).get('allowedModules',[]),d)
put(f'/api/operations/menus/{menu["menuId"]}',menu)
r,d=call(a,'DELETE',f'/api/operations/menus/{custom["menuId"]}');check('delete unused directory',success(d),d)
# Button removal applies even with duplicate permission menu rows.
button=next(x for x in ms if x['menuType']=='F' and x['perms']=='drilling:field:list')
put(f'/api/operations/menus/{button["menuId"]}',dict(button,status='1'))
r,d=call(e,'GET','/api/drilling/fields');check('disabled operation revokes active session permission',r.status_code==403,d)
put(f'/api/operations/menus/{button["menuId"]}',button)
for endpoint in [O+'/departments',O+'/posts',O+'/fields','/api/operations/menus']:
 r,d=call(e,'GET',endpoint);check('engineer cannot manage '+endpoint,r.status_code==403,d)
# Remove user assignments to verify supported CRUD delete paths.
saved=get(f'/api/access/users/{uid}');put(f'/api/access/users/{uid}',dict(saved,postIds=[],deptId=None))
r,d=call(a,'DELETE',O+f'/posts/{p["postId"]}');check('unassigned position deletion',success(d),d)
r,d=call(a,'DELETE',f'/api/access/users/{uid}');check('remove QA account',success(d),d)
r,d=call(a,'DELETE',f'/api/access/roles/{role["roleId"]}');check('remove QA role',success(d),d)
r,d=call(a,'DELETE',O+f'/departments/{d4["deptId"]}');check('field-referenced department deletion denied',r.status_code==400,d)
out=Path(__file__).resolve().parents[2]/'artifacts/organization-permissions';out.mkdir(parents=True,exist_ok=True);(out/'http-results.json').write_text(json.dumps({'passed':len(checks),'checks':checks},ensure_ascii=False,indent=2));print('PASSED',len(checks))
