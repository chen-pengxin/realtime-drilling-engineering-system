"""Exercise A/B/C through authenticated ordinary field/task/version endpoints.

Creates collision task history and, with --solve, real planning task history.
Set DRILLING_SESSION_ID to an administrator's current JSESSIONID; credentials
and session IDs are never written to the report. No account settings change.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import os
import time
from pathlib import Path

import httpx

from tools.import_test_cases import load_case


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8080')
    parser.add_argument('--cases', default='ABC')
    parser.add_argument('--solve', action='store_true')
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    token = os.environ['DRILLING_SESSION_ID']
    report = {'cases': [], 'success': False}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with httpx.Client(base_url=args.url, cookies={'JSESSIONID': token}, timeout=600,
                      headers={'X-Requested-With': 'XMLHttpRequest'}) as client:
        def request(method, path, **kwargs):
            response = client.request(method, path, **kwargs)
            if response.is_error:
                raise RuntimeError(f'{path}: HTTP {response.status_code}: {response.text[:1000]}')
            if 'json' not in response.headers.get('content-type', ''):
                return response.content
            data = response.json()
            if isinstance(data, dict) and data.get('code') not in (None, 0, 200):
                raise RuntimeError(f'{path}: {data}')
            return data

        fields = request('GET', '/api/drilling/fields')
        for letter in args.cases:
            assert letter in 'ABC'
            case = next(f for f in fields if f['field_code'] == f'TEST-{letter}')
            fid = case['field_id']
            root = f'/api/drilling/fields/{fid}/registry'
            bores = request('GET', root + '/wellbores')
            assert len(bores) == 10
            drilling = [b for b in bores if b['engineering_status'] == 'DRILLING']
            assert len(drilling) == 1 and drilling[0]['well_code'] == '井1'
            assert sum(b['engineering_status'] == 'COMPLETED' for b in bores) == 9
            assert all(b['actual_version_id'] and b['design_version_id'] for b in bores)
            assert all(b['block_name'] and b['field_name'] and b['well_name'] for b in bores)
            bore = drilling[0]
            neighbors = request('GET', root + f"/wellbores/{bore['wellbore_id']}/neighbors")
            assert len(neighbors) == 9
            assert all(n['included'] and n['relationship_confirmed'] for n in neighbors)
            confirmation = request('GET', root + '/engine-confirmation')
            request('POST', root + '/engine-confirmation', json={'configHash': confirmation['configHash']})
            actual = request('GET', root + f"/versions/{bore['actual_version_id']}", params={'viewStep': 10})
            last = actual['stations'][-1]
            _, tracks = load_case(letter)
            source_checks = []
            for kind, key in [('ACTUAL', 'actual_version_id'), ('DESIGN', 'design_version_id')]:
                source = next(t for t in tracks if t['number'] == 1 and t['kind'] == kind)
                path = root + f"/versions/{bore[key]}/source-export"
                original = request('GET', path, params={'mode': 'ORIGINAL'})
                assert original == source['path'].read_bytes()
                patched = request('GET', path, params={'mode': 'PATCHED'})
                parsed = list(csv.DictReader(io.StringIO(patched.decode('utf-8-sig'))))
                assert len(parsed) == len(source['rows'])
                assert list(parsed[0]) == list(source['raw'][0])
                for row, expected in zip(parsed, source['rows']):
                    for k, column in [('md','MD_m'),('inclination','inclination_deg'),('azimuth','azimuth_grid_deg')]:
                        assert abs(float(row[column]) - expected[k]) < 1e-5
                source_checks.append({'kind': kind, 'rows': len(parsed),
                                      'original_sha256': hashlib.sha256(original).hexdigest()})
            payload = {'field_id': fid, 'reference_well_id': bore['well_id'],
                       'reference_survey_version_id': bore['actual_version_id'],
                       'neighbors': [{'well_id': n['well_id'], 'survey_version_id': n['survey_version_id']}
                                     for n in neighbors], 'step': 1, 'mode': 'BOTH'}
            started = time.monotonic()
            network = request('POST', '/api/algorithm/anti-collision/network-scan', json=payload)
            assert not network.get('failed_pairs') and network['mode'] == 'BOTH'
            again = request('POST', '/api/algorithm/anti-collision/network-scan', json=payload)
            assert again['incremental']['recomputed_blocks'] == 0
            row = {'case': letter, 'field_id': fid, 'wellbores': 10, 'confirmed_neighbors': 9,
                   'actual_version': bore['actual_version_id'], 'design_version': bore['design_version_id'],
                   'start_md': last['md'], 'source_exports': source_checks,
                   'scan_status': network['status'], 'scan_and_repeat_seconds': round(time.monotonic()-started, 2),
                   'repeat_scan_incremental': again['incremental']}
            report['cases'].append(row)
            args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2))
            print(f'{letter}: ten wells, nine neighbors, source exports and incremental scans passed', flush=True)
            if args.solve:
                cfg = confirmation['configuration']['planning']
                target = cfg['target']
                options = cfg['nativeOptions']
                plan = {'field_id': fid, 'well_id': bore['well_id'],
                        'current_survey_version_id': bore['actual_version_id'],
                        'target': {'kind':'POINT', **{k:target[k] for k in ('north','east','tvd')}},
                        'target_inclination': target['inclination'], 'target_azimuth': target['azimuth'],
                        'constraint_mode': 'PER_SEGMENT',
                        'segment_bounds': [{'length_m':[r['minLength'],r['maxLength']],
                                            'dls_deg_per_30m':[r['minDls'],r['maxDls']]} for r in cfg['segmentBounds']],
                        'native_options': options, 'solver': 'SLSQP',
                        'collision_constraint':'SEPARATION_FACTOR_AND_CENTER_DISTANCE', 'obstacle_versions': payload['neighbors']}
                started = time.monotonic()
                result = request('POST', '/api/algorithm/trajectory-plan/solve', json=plan)
                metadata = result['solver_metadata']
                assert metadata['actual_solver'] == 'SLSQP'
                assert metadata['configuration']['start']['position_m'] == [last[k] for k in ('north','east','tvd')]
                assert len(metadata['configuration']['iscwsa']['neighbors']) == 9
                if result['trajectory']:
                    assert abs(result['trajectory'][0]['md']-last['md']) < 1e-5
                    assert all(b['md'] > a['md'] for a,b in zip(result['trajectory'],result['trajectory'][1:]))
                    assert not result['collision_evaluation'].get('failed_pairs')
                row.update(planning_status=result['status'], planning_seconds=round(time.monotonic()-started,2),
                           total_length=result['total_length'], solver_budget=metadata['configuration']['solver'],
                           feasible=metadata['constraint_audit'].get('feasible'), segments=len(result['segments']))
                print(f"{letter}: real planning returned {result['status']}", flush=True)
                args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2))
        report['success'] = True
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
