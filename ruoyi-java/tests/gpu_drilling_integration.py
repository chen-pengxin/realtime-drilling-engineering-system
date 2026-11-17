"""Monitor and complete network-response regression. Run only against a disposable QA schema."""
import json
import os
import re
from pathlib import Path

import requests

assert os.environ.get('ACCESS_QA_ALLOW_MUTATION') == '1', 'Explicit QA mutation opt-in required'
config = json.loads(Path(os.environ['GPU_QA_CONFIG']).read_text())
assert re.fullmatch(r'drilling_ops_qa_gpu_[0-9]+', config['database']), 'Disposable GPU/UI QA schema required'
base = os.environ.get('ACCESS_QA_BASE', 'http://127.0.0.1:18080')
session = requests.Session()
session.headers['X-Requested-With'] = 'XMLHttpRequest'
checks = []


def check(condition, name):
    checks.append({'name': name, 'passed': bool(condition)})
    assert condition, name
    print('PASS', name, flush=True)


def call(method, path, **kwargs):
    response = session.request(method, base + path, timeout=25, **kwargs)
    response.raise_for_status()
    return response


response = call('POST', '/login', data={'username': 'admin', 'password': config['adminPassword'], 'rememberMe': 'false'})
check(response.json().get('code') in [0, 200], 'administrator login')
monitor = call('GET', '/api/operations/monitor').json()['data']
check(set(monitor['gpu']) == {'business', 'algorithm'}, 'separate business and algorithm GPU nodes')
for key in ['business', 'algorithm']:
    source = monitor['gpu'][key]
    check(isinstance(source['devices'], list) and bool(source['status']), key + ' explicit collection status')
    for device in source['devices']:
        check(bool(device.get('name')) and bool(device.get('status')), key + ' device identity and status')
        if device.get('backend') == 'Metal':
            check(not device.get('available') and device.get('utilization_percent') is None, 'Metal inventory does not fabricate CUDA or utilization')
field = config['fixture']['fieldId']
surveys = []
for well in config['fixture']['wells']:
    versions = call('GET', f'/api/drilling/fields/{field}/wells/{well}/surveys').json()
    version = next((row for row in versions if str(row['is_current']) == '1'), versions[0])
    detail = call('GET', f'/api/drilling/fields/{field}/wells/{well}/surveys/{version["survey_version_id"]}').json()
    first = detail['stations'][0]
    surveys.append({'well_id': well, 'survey_version_id': version['survey_version_id'],
                    'origin_north': first['north'], 'origin_east': first['east'], 'origin_tvd': first['tvd'],
                    'stations': [{key: row[key] for key in ['md', 'inclination', 'azimuth']} for row in detail['stations']]})
reference = surveys[0]
response = call('POST', '/api/algorithm/anti-collision/network-scan', json={
    'field_id': field, 'reference_well_id': reference['well_id'],
    'reference_survey_version_id': reference['survey_version_id'], 'reference': reference,
    'neighbors': surveys[1:], 'step': 10, 'mode': 'BOTH'})
# Requests reads the complete response: a stale upstream Content-Length used to hang here.
check(int(response.headers['Content-Length']) == len(response.content), 'rewritten network response has exact byte length')
result = response.json()
check(result['neighbor_count'] == len(surveys) - 1, 'complete full-neighbor scan reaches client')
check(all('spacing' in row and 'separation_factor' in row for row in result['results']), 'both collision evaluations survive gateway serialization')
out = Path(config['root']) / 'artifacts/gpu-drilling-ui'
out.mkdir(parents=True, exist_ok=True)
(out / 'integration-results.json').write_text(json.dumps({'passed': len(checks), 'checks': checks}, ensure_ascii=False, indent=2))
