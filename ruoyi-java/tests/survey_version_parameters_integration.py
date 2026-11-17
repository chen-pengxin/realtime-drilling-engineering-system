"""Version metadata regression against a disposable database clone, never production."""
import json,copy,httpx,os,math
from pathlib import Path
assert os.environ.get('DRILLING_QA_ALLOW_MUTATION')=='1', 'Use an isolated backend; the test creates trajectory revisions.'
base=os.environ.get('DRILLING_QA_BACKEND','http://127.0.0.1:18080')
assert ':18080' in base, 'This integration test is reserved for the isolated backend on port 18080.'
s=httpx.Client(base_url=base,headers={'X-Requested-With':'XMLHttpRequest'},timeout=30)
s.cookies.set('JSESSIONID',os.environ['DRILLING_SESSION_ID'],domain='127.0.0.1',path='/')
root='/api/drilling/fields/'+os.environ.get('DRILLING_QA_FIELD','13')+'/registry';checks=[]
def check(value,name):
 assert value,name
 checks.append(name);print('PASS',name,flush=True)
def get(path):
 r=s.get(root+path);r.raise_for_status();return r.json()
bore=next(b for b in get('/wellbores') if b['well_name']=='井1');vid=bore['actual_version_id'];before=get(f'/versions/{vid}')
body={'surveyTool':{'name':'接口回归测试工具','code':'TEST-ONLY','serial_number':'QA-001'},'uncertainty':{'model_version':'constant_isotropic','settings':{'uncertainty_model':{'type':'constant_isotropic','sigma_m':float(before['effective_uncertainty']['settings']['uncertainty_model'].get('sigma_m',1))+1}},'parameter_source':'MANUAL','confirmed':True},'note':'隔离数据库中验证测量参数版本修订','activate':False}
r=s.post(root+f'/versions/{vid}/measurement-parameters',json=body);check(r.status_code==200 and 'survey_version_id' in r.json(),'修订接口返回成功: '+str(r.status_code)+' '+r.text[:200]);new=r.json();newid=new['survey_version_id'];check(newid!=vid,'生成独立测斜版本')
check(new['reference']['survey_tool']==body['surveyTool'],'测斜工具按版本保存')
check(new['reference']['uncertainty']['settings']==body['uncertainty']['settings'],'完整误差参数按版本保存')
check(bool(new['reference']['uncertainty']['confirmed_by']) and bool(new['reference']['uncertainty']['confirmed_at']),'确认人和确认时间保存')
geometry=('md','inclination','azimuth','north','east','tvd','dogleg','curvature')
check([[x.get(k) for k in geometry] for x in new['stations']]==[[x.get(k) for k in geometry] for x in before['stations']],'修订参数不改变或丢失原测点')
check(get(f'/versions/{vid}')['reference']==before['reference'],'父版本参考基准和参数未被覆盖')
check(next(b for b in get('/wellbores') if b['well_name']=='井1')['actual_version_id']==vid,'不启用时当前测斜版本不变')
r=s.post(root+f'/versions/{vid}/measurement-parameters',json=body);check(r.status_code==200 and r.json().get('duplicate') and r.json()['survey_version_id']==newid,'相同参数不产生重复版本')
bad=copy.deepcopy(body);bad['uncertainty']['confirmed']=False;r=s.post(root+f'/versions/{vid}/measurement-parameters',json=bad);check(r.status_code==422,'未确认的误差参数拒绝保存')
bad=copy.deepcopy(body);bad.update(activate=True,expectedCurrentVersionId=-1);r=s.post(root+f'/versions/{vid}/measurement-parameters',json=bad);check(r.status_code==409,'启用版本的并发冲突可识别')
confirmation=get('/engine-confirmation')
config=s.get(base+'/api/drilling/fields/'+os.environ.get('DRILLING_QA_FIELD','13')+'/configs/current').json()
defaults=json.loads(config['algorithm_defaults_json']);defaults['errorModel']['curveToleranceM']=defaults['errorModel'].get('curveToleranceM',.02)+.01
r=s.post('/api/drilling/fields/'+os.environ.get('DRILLING_QA_FIELD','13')+'/configs',json={'algorithmDefaults':defaults});check(r.status_code==201,'隔离井场配置更新成功')
unchanged=get('/engine-confirmation');check(unchanged['configHash']==confirmation['configHash'],'仅修改几何插值容差不触发参数重确认')
body.update(activate=True,expectedCurrentVersionId=vid)
r=s.post(root+f'/versions/{vid}/measurement-parameters',json=body);check(r.status_code==200 and r.json().get('survey_version_id')==newid,'重复修订可安全启用已有新版本')
changed=get('/engine-confirmation');check(changed['configHash']!=unchanged['configHash'],'启用不同工具预算后需要重新确认')
r=s.post(root+'/engine-confirmation',json={'configHash':changed['configHash']});check(r.status_code==200 and get('/engine-confirmation')['confirmed'],'新工具预算可完成独立参数确认')
version=get(f'/versions/{newid}');last=version['stations'][-1];inc=math.radians(last['inclination']);azi=math.radians(last['azimuth'])
payload={'field_id':int(os.environ.get('DRILLING_QA_FIELD','13')),'well_id':version['well_id'],'current_survey_version_id':newid,'target':{'kind':'POINT','north':last['north']+20*math.sin(inc)*math.cos(azi),'east':last['east']+20*math.sin(inc)*math.sin(azi),'tvd':last['tvd']+20*math.cos(inc)},'constraint_mode':'GLOBAL','min_segment_length':1,'max_segment_length':30,'native_options':{'trajectory_mode':'straight-arc','n_segments':1},'obstacle_versions':[]}
r=s.post('/api/algorithm/trajectory-plan/solve',json=payload);check(r.status_code==200 and r.json().get('status')=='SUCCESS','真实网关可完成简单规划并结束等待')
tasks=s.get('/api/algorithm/trajectory-plan/tasks',params={'fieldId':payload['field_id']}).json();task=tasks[0];check(task['current_survey_version_id']==newid and task['status']=='SUCCESS','规划结果和输入版本写入任务历史')
snapshot=json.loads(task['constraint_json']);check(snapshot['input_versions'][0]['uncertainty']['settings']==body['uncertainty']['settings'],'任务快照保留实际使用的版本误差参数')
Path(os.environ.get('DRILLING_QA_REPORT','/tmp/drilling-version-parameter-results.json')).write_text(json.dumps({'success':True,'checks':checks,'parent_version':vid,'new_version':newid},ensure_ascii=False,indent=2))
