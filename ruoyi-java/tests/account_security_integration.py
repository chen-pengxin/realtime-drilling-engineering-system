"""Real HTTP regression for an isolated RuoYi database.
Run only against a disposable database with ACCESS_QA_ALLOW_MUTATION=1.
The server must use shiro.user.captchaEnabled=false for automated login.
"""
import json
import os
from pathlib import Path
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
import requests

BASE=os.environ.get('ACCESS_QA_BASE','http://127.0.0.1:18080')
assert os.environ.get('ACCESS_QA_ALLOW_MUTATION')=='1', 'Use an isolated QA schema and explicitly enable test account mutations'
ADMIN_PASSWORD=os.environ['ACCESS_QA_ADMIN_PASSWORD']
results=[]
prefix='qa_'+datetime.now().strftime('%H%M%S')

def call(s,method,path,**kwargs):
    r=s.request(method,BASE+path,headers={'X-Requested-With':'XMLHttpRequest'},timeout=20,**kwargs)
    try: data=r.json()
    except ValueError: data={'raw':r.text[:100]}
    return r,data

def check(name,condition):
    results.append({'name':name,'passed':bool(condition)})
    assert condition,name

def success(data):return isinstance(data,dict) and data.get('code') in (0,200)

def login(name,password):
    s=requests.Session();r,d=call(s,'POST','/login',data={'username':name,'password':password,'rememberMe':'false'});return s,d

admin,d=login('admin',ADMIN_PASSWORD);check('administrator session login',success(d))
_,opts=call(admin,'GET','/api/access/options');check('administrator options and existing drilling permission catalog',success(opts) and len(opts['data']['permissions'])>20)
opts=opts['data'];roleids={x['roleKey']:x['roleId'] for x in opts['roles']}
newpass='QaChanged#2026'

def account(suffix,tier,**kw):
    b={'loginName':prefix+suffix,'userName':'测试'+{'ADMIN':'管理员','ENGINEER':'工程师','GUEST':'游客'}[tier],'accountTier':tier,'roleIds':[roleids[tier.lower()]],'status':'0','allowedModules':['realtime','anticollision'],'fieldIds':[]}
    b.update(kw)
    r,d=call(admin,'POST','/api/access/users',json=b);check('create '+tier+' with fixed initial password',success(d))
    generated=d['data']['accountId'];check('random immutable ID and identical initial name '+tier,len(generated)==10 and generated.isdigit() and generated[0]!='0' and d['data']['accountName']==generated)
    b.update(userId=d['data']['userId'],loginName=generated,userName=generated);return b

engineer=account('_eng','ENGINEER',password='client-attempted-password')
e,d=login(engineer['loginName'],'123456');check('server ignores supplied password and uses 123456',success(d))
_,d=call(e,'GET','/getInfo');check('initial password flag',d['mustChangePassword'] is True)
for method,path in [('GET','/api/drilling/fields'),('POST','/api/algorithm/anti-collision/network-scan'),('POST','/system/user/profile/resetPwd')]:
    r,d=call(e,method,path,json={});check('initial password backend gate '+path,r.status_code==403 and d.get('code')==460)
for b in [{'oldPassword':'wrong','newPassword':newpass}]+[{'oldPassword':'123456','newPassword':p} for p in ['123456','1','abcdefgh','12345678','Aa12345 ','汉字123456','a1'+'x'*19]]:
    r,d=call(e,'PUT','/api/access/me/password',json=b);check('invalid password change rejected '+str(len(b['newPassword'])),r.status_code==400 and not success(d))
_,d=call(e,'PUT','/api/access/me/password',json={'oldPassword':'123456','newPassword':newpass});check('password change unlocks only on success',success(d))
_,d=call(e,'GET','/getInfo');check('initial password flag cleared',d['mustChangePassword'] is False)
for value in ['单','汉'*21,'<script>','含 空格','😀账号','']:
    r,d=call(e,'PUT','/api/access/me/name',json={'userName':value});check('invalid mutable account name '+str(len(value)),r.status_code==400)
engineer['userName']='Engineer'+datetime.now().strftime('%H%M%S')
_,d=call(e,'PUT','/api/access/me/name',json={'userName':engineer['userName']});check('user can rename own account',success(d))
_,d=login(engineer['userName'],newpass);check('login by mutable account name',success(d))
_,d=login(engineer['loginName'],newpass);check('immutable ID still logs in after name change',success(d))
r,d=call(e,'PUT','/api/access/me/name',json={'userName':'admin'});check('name cannot collide with another immutable login ID',r.status_code==400)
oldname=engineer['userName'];engineer['userName']='工程师'+datetime.now().strftime('%H%M%S')
_,d=call(e,'PUT','/api/access/me/name',json={'userName':engineer['userName']});check('Chinese account name accepted',success(d))
_,d=login(oldname,newpass);check('old mutable name no longer logs in after rename',not success(d))
_,d=login(engineer['userName'],newpass);check('Chinese account name login works',success(d))
_,d=call(e,'GET','/getInfo');check('active session shows current account name',d['user']['nickName']==engineer['userName'])

r,d=call(e,'GET','/api/drilling/fields');check('engineer business list works',r.status_code==200 and isinstance(d,list))
for path in ['/api/access/users','/system/user/list','/system/role/list']:
    r,d=call(e,'GET',path);check('engineer cannot manage identities '+path,r.status_code==403)
# Custom roles use the original sys_role/sys_role_menu tables.
field_list=next(x['menuId'] for x in opts['permissions'] if x['perms']=='drilling:field:list')
role={'roleName':'QA 井场只读','roleKey':prefix+'_reader','accountTier':'ENGINEER','allowedModules':['catalog'],'menuIds':[field_list],'status':'0'}
_,d=call(admin,'POST','/api/access/roles',json=role);check('administrator creates custom role',success(d))
_,d=call(admin,'GET','/api/access/roles');role.update(next(x for x in d['data'] if x['roleKey']==role['roleKey']))
engineer['roleIds']=[role['roleId']]
_,d=call(admin,'PUT',f"/api/access/users/{engineer['userId']}",json=engineer);check('assign custom role to existing account',success(d))
_,d=call(e,'GET','/getInfo');check('existing session receives exact role permissions',set(d['permissions'])=={'drilling:field:list'} and d['allowedModules']==['catalog'])
r,d=call(e,'GET','/api/algorithm/anti-collision/tasks',params={'fieldId':1});check('module denied after live authorization update',r.status_code==403)
r,d=call(e,'POST','/api/drilling/fields',json={'fieldCode':'forbidden'});check('granular role cannot create field',not (r.status_code<300 and isinstance(d,dict) and 'field_id' in d))
role['allowedModules']=[]
_,d=call(admin,'PUT',f"/api/access/roles/{role['roleId']}",json=role);check('custom role permission edit',success(d))
r,d=call(e,'GET','/api/drilling/fields');check('role revocation takes effect in active session',r.status_code==403)
now=datetime.now(ZoneInfo('Asia/Shanghai')).replace(tzinfo=None)
guest=account('_guest','GUEST',validFrom=(now-timedelta(hours=1)).isoformat(timespec='seconds'),validUntil=(now+timedelta(hours=2)).isoformat(timespec='seconds'))
g,d=login(guest['loginName'],'123456');check('guest requires real account login',success(d))
_,d=call(g,'PUT','/api/access/me/password',json={'oldPassword':'123456','newPassword':newpass});check('guest can change initial password',success(d))
r,d=call(g,'PUT','/api/access/me/name',json={'userName':engineer['userName']});check('account names are globally unique',r.status_code==400)
guest['userName']='游客'+datetime.now().strftime('%H%M%S')
_,d=call(g,'PUT','/api/access/me/name',json={'userName':guest['userName']});check('guest can rename own account',success(d))
_,d=login(guest['userName'],newpass);check('guest login by current account name',success(d))

for mod in ['realtime','anticollision']:
    r,d=call(g,'GET','/api/access/demo/'+mod);check('authorized isolated guest demo '+mod,success(d) and d['data']['demoOnly'] is True)
for method,path in [('GET','/api/access/demo/config'),('GET','/api/drilling/fields'),('GET','/api/drilling/fields/1/files/1/download'),('GET','/api/algorithm/realtime/status'),('POST','/api/algorithm/anti-collision/network-scan'),('POST','/api/drilling/fields/1/files'),('PUT','/api/drilling/fields/1/configs/1/activate'),('GET','/system/user/profile/checkPassword'),('GET','/api/access/users')]:
    r,d=call(g,method,path,json={});check('guest real-operation boundary '+method+' '+path,r.status_code==403)
guest['allowedModules']=['realtime']
_,d=call(admin,'PUT',f"/api/access/users/{guest['userId']}",json=guest);check('administrator edits guest module scope',success(d))
r,d=call(g,'GET','/api/access/demo/anticollision');check('guest feature revocation applies to current session',r.status_code==403)
# Existing session expiry and a future access window are both enforced.
guest['validFrom']=(now-timedelta(hours=2)).isoformat(timespec='seconds');guest['validUntil']=(now-timedelta(hours=1)).isoformat(timespec='seconds')
_,d=call(admin,'PUT',f"/api/access/users/{guest['userId']}",json=guest);check('administrator updates guest access window',success(d))
r,d=call(g,'GET','/api/access/demo/realtime');check('already logged in guest expires immediately',r.status_code==401)
_,d=login(guest['loginName'],newpass);check('expired guest cannot log in',not success(d))
guest['validFrom']=(now+timedelta(hours=1)).isoformat(timespec='seconds');guest['validUntil']=(now+timedelta(hours=2)).isoformat(timespec='seconds')
call(admin,'PUT',f"/api/access/users/{guest['userId']}",json=guest)
_,d=login(guest['loginName'],newpass);check('future guest cannot log in',not success(d))
# Account disable and reset revoke existing sessions.
_,d=call(admin,'PUT',f"/api/access/users/{engineer['userId']}/reset-password",json={'password':'not-the-initial'});check('administrator reset to fixed initial password',success(d))
r,d=call(e,'GET','/getInfo');check('reset revokes old session',r.status_code==401)
e,d=login(engineer['loginName'],'123456');check('reset password login works',success(d))
_,d=call(e,'GET','/getInfo');check('reset requires password change again',d['mustChangePassword'] is True)
engineer['status']='1';call(admin,'PUT',f"/api/access/users/{engineer['userId']}",json=engineer)
r,d=call(e,'GET','/getInfo');check('disabled account existing session rejected',r.status_code==401)
_,d=call(admin,'GET','/api/access/users',params={'keyword':engineer['loginName'],'status':'1'});check('RuoYi user status search filter',len(d['rows'])==1 and d['rows'][0]['status']=='1')
_,d=call(admin,'GET','/api/access/users',params={'keyword':engineer['loginName'],'status':'0'});check('RuoYi user status search excludes disabled',len(d['rows'])==0)
_,d=login(engineer['loginName'],'123456');check('disabled account cannot log in',not success(d))
# Additional administrators must have the same business administrator scope as user_id=1.
boundary=account('_max','ENGINEER')
bc,d=login(boundary['loginName'],'123456');check('generated account ID can log in',success(d))
_,d=call(bc,'PUT','/api/access/me/password',json={'oldPassword':'123456','newPassword':'a1'+'x'*18});check('20 character password accepted',success(d))
_,d=login(boundary['loginName'],'a1'+'x'*18);check('20 character password login works',success(d))
_,d=call(bc,'PUT','/api/access/me/name',json={'userName':'汉'*20});check('20 character Chinese account name accepted',success(d))
r,d=call(admin,'PUT',f"/api/access/users/{boundary['userId']}",json=dict(boundary,loginName='changed_id'));check('administrator cannot change immutable ID',r.status_code==400)
other=account('_admin','ADMIN')
a,d=login(other['loginName'],'123456');call(a,'PUT','/api/access/me/password',json={'oldPassword':'123456','newPassword':newpass})
r,d=call(a,'GET','/api/drilling/fields');check('additional administrator has business access',r.status_code==200 and isinstance(d,list))
_,d=call(a,'GET','/api/access/options');check('additional administrator can manage users and roles',success(d))
for method,path,b in [('PUT','/api/access/users/1',dict(engineer,accountTier='ENGINEER')),('DELETE','/api/access/users/1',None),('POST','/system/user/add',{'loginName':'bypass'}),('POST','/system/user/resetPwd',{'userId':engineer['userId'],'password':'bypass'})]:
    r,d=call(admin,method,path,json=b);check('protected administrator / legacy mutation '+path,not success(d))
public=requests.Session()
for method in ['GET','POST']:
    _,d=call(public,method,'/register',json={'loginName':'public_attempt','password':'123456'});check('registration permanently disabled '+method,d.get('code')==403)
r,d=call(public,'GET','/api/access/demo/realtime');check('anonymous cannot browse demo',r.status_code==401)
_,d=call(admin,'GET','/api/access/audit');check('identity audit records changes without password material',success(d) and len(d['data'])>10 and 'QaChanged' not in json.dumps(d))
# Cleanup test-created records through audited APIs.
for b in [engineer,guest,other,boundary]:
    _,d=call(admin,'DELETE',f"/api/access/users/{b['userId']}");check('delete test account '+b['accountTier'],success(d))
_,d=call(admin,'DELETE',f"/api/access/roles/{role['roleId']}");check('delete unassigned custom role',success(d))
output=Path(os.environ.get('ACCESS_QA_OUTPUT','artifacts/account-security/integration-result.json'));output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps({'passed':len(results),'cases':results},ensure_ascii=False,indent=2))
print(f'{len(results)} security checks passed')
