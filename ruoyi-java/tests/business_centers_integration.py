"""Business-center acceptance against an explicitly isolated database and real worker."""
import json
import os
import re
import subprocess
import time
from pathlib import Path

import requests

assert os.environ.get('ACCESS_QA_ALLOW_MUTATION') == '1'
config_path = Path(os.environ['CENTERS_QA_CONFIG'])
config = json.loads(config_path.read_text())
assert re.fullmatch(r'drilling_ops_qa_centers_[0-9]+', config['database'])
base = os.environ.get('ACCESS_QA_BASE', 'http://127.0.0.1:18080')
checks = []
session = requests.Session()
session.headers['X-Requested-With'] = 'XMLHttpRequest'


def check(ok, name, detail=None):
    checks.append({'name': name, 'passed': bool(ok)})
    assert ok, (name, detail)
    print('PASS', name, flush=True)


def call(method, path, expected=200, client=session, **kwargs):
    r = client.request(method, base + path, timeout=150, **kwargs)
    try:
        d = r.json()
    except ValueError:
        d = r.content
    check(r.status_code == expected, f'{method} {path} {expected}', str(d)[:350])
    if expected < 400 and isinstance(d, dict) and 'code' in d:
        check(d['code'] in (0, 200), 'successful business response ' + path, str(d)[:350])
    return d


def sql(statement):
    return subprocess.check_output(['mysql', '-uroot', '--batch', '--skip-column-names', config['database'], '-e', statement], env=os.environ, text=True).strip()


call('POST', '/login', data={'username': 'admin', 'password': config['adminPassword'], 'rememberMe': 'false'})
tag = str(int(time.time()))
if 'fixture' not in config:
    b = call('POST', '/api/drilling/blocks', 201, json={'blockCode': 'CENTERS_' + tag, 'blockName': '业务中心测试区块'})
    fields = []
    for i in range(2):
        p = call('POST', '/api/drilling/fields', 201, json={'blockId': b['block_id'], 'fieldCode': 'CENTERS_' + tag + '_' + str(i), 'fieldName': '业务中心验收井场' + str(i)})
        fields.append(p['field_id'])
    wells, versions, trajectories = [], [], []
    for i in range(2):
        w = call('POST', f'/api/drilling/fields/{fields[0]}/wells', 201, json={'wellCode': 'C-' + str(i), 'wellName': ['正钻井', '邻井'][i], 'wellType': 'DRILLING' if i == 0 else 'OFFSET'})
        w = w['well_id']; wells.append(w)
        calc = call('POST', '/api/algorithm/survey/calculate', json={'stations': [{'md': 0, 'inclination': 0, 'azimuth': 0}, {'md': 100, 'inclination': 5, 'azimuth': 90}], 'origin_north': 0, 'origin_east': i * 20, 'origin_tvd': 0})
        v = call('POST', f'/api/drilling/fields/{fields[0]}/wells/{w}/surveys', 201, json={'configVersion': 1, 'sourceFormat': 'CSV', 'stations': calc['stations']})
        versions.append(v['survey_version_id']); trajectories.append(calc['stations'])
    config['fixture'] = {'fieldId': fields[0], 'otherFieldId': fields[1], 'wells': wells, 'versions': versions, 'trajectories': trajectories}
    config_path.write_text(json.dumps(config))
fixture = config['fixture']; field = fixture['fieldId']; other = fixture['otherFieldId']; wells = fixture['wells']


def endpoint(module, action=''):
    return f'/api/drilling/centers/{module}' + ('/' + action if action else '') + f'?fieldId={field}'


# Seed real algorithms through the authenticated gateway; no canned successful task records.
ref = {'well_id': wells[0], 'survey_version_id': fixture['versions'][0], 'stations': [{k: s[k] for k in ['md', 'inclination', 'azimuth']} for s in fixture['trajectories'][0]]}
neighbor = {'well_id': wells[1], 'survey_version_id': fixture['versions'][1], 'stations': [{k: s[k] for k in ['md', 'inclination', 'azimuth']} for s in fixture['trajectories'][1]], 'origin_east': 20}
scan = call('POST', '/api/algorithm/anti-collision/network-scan', json={'field_id': field, 'reference_well_id': wells[0], 'reference_survey_version_id': fixture['versions'][0], 'reference': ref, 'neighbors': [neighbor], 'step': 10, 'mode': 'BOTH'})
# A model-produced station produces its own predicted-segment alarm, retaining
# the measured/predicted boundary and exact neighbor for the replanning link.
future_ref = dict(ref, stations=ref['stations'] + [{'md': 110, 'inclination': 5, 'azimuth': 90, 'source': 'PREDICTED_TRAINED_MODEL', 'predicted': True}])
if 'nearNeighbor' not in fixture:
    close_well = call('POST', f'/api/drilling/fields/{field}/wells', 201, json={'wellCode': 'NEAR-' + tag, 'wellName': '近邻井', 'wellType': 'OFFSET'})['well_id']
    close_stations = [{'md': 0, 'inclination': 0, 'azimuth': 0}, {'md': 120, 'inclination': 5, 'azimuth': 90}]
    calculated = call('POST', '/api/algorithm/survey/calculate', json={'stations': close_stations, 'origin_east': 2})
    close_version = call('POST', f'/api/drilling/fields/{field}/wells/{close_well}/surveys', 201, json={'configVersion': 1, 'sourceFormat': 'CSV', 'stations': calculated['stations']})['survey_version_id']
    fixture['nearNeighbor'] = {'well_id': close_well, 'survey_version_id': close_version, 'stations': close_stations, 'origin_east': 2}
    config_path.write_text(json.dumps(config))
call('POST', '/api/algorithm/anti-collision/network-scan', json={'field_id': field, 'reference_well_id': wells[0], 'reference_survey_version_id': fixture['versions'][0], 'reference': future_ref, 'neighbors': [neighbor, fixture['nearNeighbor']], 'step': 1, 'mode': 'BOTH'})
predicted = call('GET', endpoint('alerts'), params={'type': 'PREDICTED_COLLISION'})['rows']
check(predicted and predicted[0]['neighbor_well_id'] == fixture['nearNeighbor']['well_id'], 'predicted-segment alarm retains correct neighbor')
check(not any(x['neighbor_well_id'] == wells[1] and x['status'] == 'OPEN' for x in predicted), 'safe neighbor does not acquire another pairs predicted alarm')
boundary = json.loads(predicted[0]['rule_snapshot_json'])
check(boundary['measured_md'] == 100 and boundary['predicted_md'] == 110, 'predicted alarm retains measured and extrapolated MD boundary')
prediction = call('POST', '/api/drilling/predictions', json={'field_id': field, 'well_id': wells[0], 'survey_version_id': fixture['versions'][0], 'prediction_step': 9})
check(prediction['status'] == 'MODEL_NOT_CONFIGURED', 'prediction retains explicit unconfigured state')
for module in ['alerts', 'history', 'quality', 'reports', 'connectors', 'versions']:
    r = call('GET', endpoint(module), params={'pageNum': 1, 'pageSize': 1})
    check(len(r['rows']) <= 1 and isinstance(r['total'], int), module + ' server pagination')
    if r['total'] > 1:
        second = call('GET', endpoint(module), params={'pageNum': 2, 'pageSize': 1})
        check(second['rows'][0]['record_key'] != r['rows'][0]['record_key'], module + ' page two advances records')
    literal = call('GET', endpoint(module), params={'keyword': '%_not_a_wildcard_' + tag})
    check(literal['total'] == 0, module + ' literal keyword filter')
    call('GET', endpoint(module), 400, params={'sort': 'created_at DESC; DROP TABLE sys_user'})
    call('GET', endpoint(module), 400, params={'order': 'desc;--'})
    call('GET', endpoint(module), 400, params={'pageSize': 101})
    call('GET', endpoint(module), 400, params={'beginTime': 'not-a-date'})

tasks = call('GET', endpoint('history'), params={'pageSize': 100})['rows']
collision = max((x for x in tasks if x['record_type'] == 'COLLISION'), key=lambda x: x['record_id'])
check(collision['status']=='SUCCESS','unified history preserves native status column')
call('GET', endpoint('history'),params={'status':'SUCCESS','sort':'status'})
detail = call('GET', endpoint('history', collision['record_key']))
check(detail['snapshot'] and detail['snapshot']['input_json'] and len(detail['surveys']) == 3, 'collision actual input/config and all survey versions retained')
call('GET', endpoint('history'), params={'type': 'PREDICTION', 'keyword': '姿态预测', 'sort': 'record_id', 'order': 'asc'})
export = call('POST', endpoint('history', 'export'), json={'ids': [x['record_key'] for x in tasks]})
check(isinstance(export, list) and len(export) == len(tasks), 'history batch export contains actual records')
call('POST', endpoint('history', 'export'), 400, json={'ids': []})

# Mixed valid/invalid packets: valid extension survives and the illegal row is isolated.
max_md=float(sql(f"SELECT MAX(s.md) FROM drilling_survey_station s JOIN drilling_survey_version v ON v.survey_version_id=s.survey_version_id WHERE v.well_id={wells[0]} AND v.is_current='1'")); next_md=max_md+10; source='CENTERS-QA-'+tag
ingested = call('POST', '/api/algorithm/realtime/ingest', json={'field_id': field, 'well_id': wells[0], 'source': source, 'events': [{'md': next_md, 'inc': 6, 'azi': 90}, {'md': 'bad', 'inc': -2, 'azi': 90}], 'flush': True})
check(ingested.get('persisted_survey_version_id'), 'valid station committed despite malformed neighbor packet')
call('POST', '/api/algorithm/realtime/ingest', json={'field_id': field, 'well_id': wells[0], 'source': source, 'events': [{'md': next_md, 'inc': 6, 'azi': 90}], 'flush': True})
call('POST', endpoint('quality', 'check'))
quality = call('GET', endpoint('quality'), params={'pageSize': 100})['rows']
check({'INVALID', 'DUPLICATE'} <= {r['record_type'] for r in quality}, 'invalid and duplicate quality issues captured')
call('POST', endpoint('quality', 'resolve'), 400, json={'ids': [quality[0]['record_key']], 'status': 'RESOLVED', 'note': ''})
call('POST', endpoint('quality', 'resolve'), json={'ids': [r['record_key'] for r in quality], 'status': 'RESOLVED', 'note': '测试：已检查原始数据，保留审计记录'})
resolved = call('GET', endpoint('quality'), params={'status': 'RESOLVED'})
check(resolved['total'] == len(quality), 'quality bulk resolution and filter')
call('POST', endpoint('quality', 'export'), json={'ids': [r['record_key'] for r in quality]})

alarms = call('GET', endpoint('alerts'), params={'pageSize': 100,'status':'OPEN'})['rows']
check(any(r['record_type'] == 'DATA_QUALITY' for r in alarms), 'quality issues feed alarm center')
ids = [r['record_key'] for r in alarms]
call('POST', endpoint('alerts', 'ack'), json={'ids': ids})
call('POST', endpoint('alerts', 'resolve'), json={'ids': ids})
check(call('GET', endpoint('alerts'), params={'status': 'RESOLVED'})['total'] >= len(ids), 'alarm batch transitions and history')
call('POST', endpoint('alerts', 'ack'), 400, json={'ids': ids})

versions = call('GET', endpoint('versions'), params={'type': 'SURVEY', 'wellId': wells[0], 'pageSize': 100})['rows']
check(len(versions) >= 2, 'incremental commit appears in version center')
diff = call('POST', endpoint('versions', 'compare'), json={'ids': [x['record_key'] for x in versions[:2]]})
check(len(diff['differences']) > 0, 'real version comparison')
check(any('stations[' in x['field'] for x in diff['differences']), 'version comparison includes actual station values')
vdetail = call('GET', endpoint('versions', 'SURVEY:' + str(fixture['versions'][0])))
check(any(x['record_type'] == 'COLLISION' for x in vdetail['used_by']), 'version-to-task trace links')
call('POST', endpoint('versions', 'export'), json={'ids': [x['record_key'] for x in versions]})
call('GET', endpoint('versions', 'changes'), params={'pageSize': 2})

sources = call('GET', endpoint('reports', 'sources'), params={'type': 'COLLISION'})
check(collision['record_key'] in [x['record_key'] for x in sources['rows']], 'report source choices are field actual tasks')
reports = []
for fmt in ['xlsx', 'pdf', 'docx']:
    report = call('POST', endpoint('reports'), json={'title': '防碰评价验收报告-' + fmt, 'type': 'COLLISION', 'sourceKey': collision['record_key'], 'format': fmt})
    reports.append(report['record_key'])
    metadata = call('GET', endpoint('reports', report['record_key']))
    check('object_key' not in metadata and metadata['snapshot_json'] and metadata['sha256'], 'report provenance retained without storage path')
    data = call('GET', endpoint('reports', report['record_key'] + '/download'))
    check(data.startswith(b'%PDF') if fmt == 'pdf' else data.startswith(b'PK'), 'valid ' + fmt + ' report artifact')
data = call('POST', endpoint('reports', 'download'), json={'ids': reports})
check(data.startswith(b'PK'), 'report batch ZIP download')
call('POST', endpoint('reports', 'archive'), json={'ids': reports})
call('GET', endpoint('reports', reports[0] + '/download'), 400)
check(call('GET', endpoint('reports'), params={'status': 'ARCHIVED'})['total'] >= 3, 'report archive preserves metadata')

# File connector executes actual normalization and creates another incremental survey version.
file = call('POST', f'/api/drilling/fields/{field}/files', files={'file': ('connector.csv', f'md,inc,azi\n{next_md+10},7,90\n{next_md+20},8,90\n', 'text/csv')}, data={'category': 'DATASET'})
connector = call('POST', endpoint('connectors'), json={'name': 'CSV接入验收-' + tag, 'type': 'CSV', 'wellId': wells[0], 'config': {'fileId': file['file_id'], 'pollSeconds': 3600}})
cid = str(connector['id'])
call('POST', endpoint('connectors', 'state'), json={'ids': [cid], 'action': 'enable'})
poll = call('POST', endpoint('connectors', 'poll'), json={'ids': [cid]})
check(poll['results'][0]['outcome'] == 'SUCCESS', 'file connector real successful read', poll)
c = call('GET', endpoint('connectors', cid))
check(c['status'] == 'CONNECTED' and c['last_heartbeat'] and c['runs'][0]['survey_version_id'], 'connector heartbeat, run and survey commit are real')
call('POST', endpoint('connectors', 'state'), json={'ids': [cid], 'action': 'disable'})
bad = call('POST', endpoint('connectors'), json={'name': '离线TCP-' + tag, 'type': 'WITS_TCP', 'wellId': wells[0], 'endpoint': 'tcp://127.0.0.1:19999', 'config': {}})
badid = str(bad['id'])
call('POST', endpoint('connectors', 'state'), json={'ids': [badid], 'action': 'enable'})
poll = call('POST', endpoint('connectors', 'poll'), json={'ids': [badid]})
check(poll['results'][0]['outcome'] == 'FAILED', 'offline connector never fabricates a successful connection')
c = call('GET', endpoint('connectors', badid))
check(c['status'] == 'RECONNECTING' and c['last_error'], 'failure records exponential reconnect state')
call('POST', endpoint('connectors', 'state'), json={'ids': [badid], 'action': 'disable'})
call('POST', endpoint('connectors'), 400, json={'name': '明文凭据', 'type': 'WITSML_2_0', 'wellId': wells[0], 'endpoint': 'https://user:secret@example.com/data', 'config': {}})
call('POST', endpoint('connectors'), 400, json={'name': '明文配置', 'type': 'WITS_TCP', 'wellId': wells[0], 'endpoint': 'tcp://localhost:1000', 'config': {'password': 'secret'}})
call('POST', endpoint('connectors'), 400, json={'name': 'SQL注入', 'type': 'MYSQL', 'wellId': wells[0], 'endpoint': 'jdbc:mysql://localhost:3306/test', 'config': {'table': 'stations; DROP TABLE sys_user'}})

# A cross-field row in a batch rejects the entire change before the first row is changed.
foreign = sql(f"INSERT INTO drilling_alarm(field_id,alarm_code,current_level,message) VALUES({other},'QA','YELLOW','foreign'); SELECT LAST_INSERT_ID();")
local = sql(f"INSERT INTO drilling_alarm(field_id,alarm_code,current_level,message) VALUES({field},'QA','YELLOW','local'); SELECT LAST_INSERT_ID();")
call('POST', endpoint('alerts', 'ack'), 400, json={'ids': [local, foreign]})
check(sql('SELECT status FROM drilling_alarm WHERE alarm_id=' + local) == 'OPEN', 'mixed-field batch is atomic')
opts = call('GET', '/api/access/options')['data']
modules = opts['modules']
check(all(k in modules for k in ['alerts', 'history', 'quality', 'reports', 'connectors', 'versions']), 'six modules available in role permissions')
# A valid binding with malformed trajectory is rejected by the worker and remains in task history.
bad_ref=dict(ref,stations=[{'md':0,'inclination':181,'azimuth':0},{'md':100,'inclination':5,'azimuth':90}])
call('POST','/api/algorithm/anti-collision/network-scan',422,json={'field_id':field,'reference_well_id':wells[0],'reference_survey_version_id':fixture['versions'][0],'reference':bad_ref,'neighbors':[neighbor],'step':10,'mode':'BOTH'})
failed=call('GET',endpoint('history'),params={'status':'FAILED'})['rows']
check(any(x['record_key'].startswith('EXECUTION:') for x in failed),'failed algorithm calls retain their history and input snapshot')

# Custom read-only role and field scope must apply to all six centers.
center_modules=['alerts','history','quality','reports','connectors','versions']
permission_ids=[x['menuId'] for x in opts['permissions'] if x['perms'] in [f'drilling:{m}:list' for m in center_modules]+['drilling:field:list','drilling:field:view','drilling:well:list']]
role={'roleName':'业务中心只读'+tag,'roleKey':'centers_reader_'+tag,'accountTier':'ENGINEER','allowedModules':center_modules,'menuIds':permission_ids,'status':'0','dataScope':'6','deptIds':[]}
call('POST','/api/access/roles',json=role)
rid=next(x['roleId'] for x in call('GET','/api/access/roles')['data'] if x['roleKey']==role['roleKey'])
def create_account(tier,role_id):
 body={'accountTier':tier,'roleIds':[role_id],'status':'0','allowedModules':center_modules,'fieldIds':[field]}
 if tier=='GUEST':
  from datetime import datetime,timedelta
  now=datetime.now();body.update(validFrom=(now-timedelta(hours=1)).isoformat(timespec='seconds'),validUntil=(now+timedelta(hours=1)).isoformat(timespec='seconds'))
 return call('POST','/api/access/users',json=body)['data']
engineer=create_account('ENGINEER',rid)
e=requests.Session();e.headers['X-Requested-With']='XMLHttpRequest'
call('POST','/login',client=e,data={'username':engineer['accountId'],'password':'123456'})
call('GET',endpoint('history'),403,client=e)
call('PUT','/api/access/me/password',client=e,json={'oldPassword':'123456','newPassword':'CenterRead#2026'})
for module in center_modules:
 call('GET',endpoint(module),client=e)
 call('GET',f'/api/drilling/centers/{module}?fieldId={other}',403,client=e)
call('POST',endpoint('alerts','ack'),403,client=e,json={'ids':[local]})
call('POST',endpoint('quality','resolve'),403,client=e,json={'ids':[quality[0]['record_key']],'status':'RESOLVED','note':'forbidden'})
call('POST',endpoint('connectors','state'),403,client=e,json={'ids':[cid],'action':'enable'})
call('POST',endpoint('reports'),403,client=e,json={'title':'forbidden','type':'FIELD','format':'pdf'})
call('POST',endpoint('history','export'),403,client=e,json={'ids':[collision['record_key']]})
# One permission revoked in the current session takes effect immediately.
role.update(roleId=rid,allowedModules=['history'])
call('PUT',f'/api/access/roles/{rid}',json=role)
call('GET',endpoint('alerts'),403,client=e)
call('GET',endpoint('history'),client=e)
guest_role={'roleName':'业务中心游客'+tag,'roleKey':'centers_guest_'+tag,'accountTier':'GUEST','allowedModules':center_modules,'menuIds':[],'status':'0','dataScope':'6','deptIds':[]}
call('POST','/api/access/roles',json=guest_role)
grid=next(x['roleId'] for x in call('GET','/api/access/roles')['data'] if x['roleKey']==guest_role['roleKey'])
guest=create_account('GUEST',grid)
g=requests.Session();g.headers['X-Requested-With']='XMLHttpRequest';call('POST','/login',client=g,data={'username':guest['accountId'],'password':'123456'})
call('PUT','/api/access/me/password',client=g,json={'oldPassword':'123456','newPassword':'CenterGuest#2026'})
for module in center_modules:
 demo=call('GET','/api/access/demo/'+module,client=g)
 check(demo['data']['demoOnly'],'isolated guest demo '+module)
 call('GET',endpoint(module),403,client=g)
call('POST',endpoint('connectors','poll'),403,client=g,json={'ids':[cid]})
config['reader']={'accountId':engineer['accountId'],'password':'CenterRead#2026'}
config['guest']={'accountId':guest['accountId'],'password':'CenterGuest#2026'}
config['fixture']['collisionKey'] = collision['record_key']; config['fixture']['connectorId'] = cid
config_path.write_text(json.dumps(config))
out = Path(__file__).resolve().parents[2] / 'artifacts' / 'business-centers'; out.mkdir(parents=True, exist_ok=True)
(out / 'http-checks.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2))
print('CHECKS', len(checks))
