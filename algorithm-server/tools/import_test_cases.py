#!/usr/bin/env python3
"""Import supplied A/B/C into the ordinary engineering registry, idempotently.

No precomputed solver outputs are imported as task results. Existing heads,
configuration and neighbor choices are preserved on subsequent runs.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import shutil
import time
import urllib.request
from pathlib import Path

import pymysql
from pymysql.cursors import DictCursor

ROOT = Path(__file__).resolve().parents[2]
CASES = ROOT / 'algorithm-server/data/replanning_demo'


def js(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def case_reference(letter, rows):
    if letter not in 'ABC' or len(letter) != 1:
        raise ValueError('Unknown test case')
    return dict(crs='', coordinate_system='LOCAL_ENU', local_frame_id=f'TEST_CASE_{letter}',
                depth_datum='测试统一深度基准', depth_datum_elevation_m=0,
                north_reference='GRID_NORTH', length_unit='m', angle_unit='deg', reference_status='READY',
                origin_north=rows[0]['north'], origin_east=rows[0]['east'], origin_tvd=rows[0]['tvd'])


def matches_stored_case(stored, source):
    # MySQL stores all six station fields to six decimal places. Legacy
    # source CSVs used that same precision, so their byte hashes differ from
    # the supplied full-precision CSV even when their geometry is identical.
    keys = ('md', 'inclination', 'azimuth', 'north', 'east', 'tvd')
    return bool(stored) and len(stored) == len(source) and all(
        abs(float(a[k]) - float(b[k])) <= 5.001e-7
        for a, b in zip(stored, source) for k in keys)


def load_case(letter):
    root = CASES / f'scene_{letter}'
    problem = json.loads((root / 'problem.json').read_text())
    tracks = []
    for number in range(1, 11):
        for kind, directory in [('ACTUAL', 'actual_input'), ('DESIGN', 'design_input')]:
            path = root / directory / f'well_{number}.csv'
            with path.open(encoding='utf-8-sig', newline='') as stream:
                raw = list(csv.DictReader(stream))
            rows = [{key: float(row[column]) for key, column in {
                'md': 'MD_m', 'inclination': 'inclination_deg',
                'azimuth': 'azimuth_grid_deg', 'north': 'N_m', 'east': 'E_m', 'tvd': 'D_m',
            }.items()} for row in raw]
            if any(b['md'] <= a['md'] for a, b in zip(rows, rows[1:])):
                raise ValueError(f'{path}: measured depths must increase')
            tracks.append(dict(number=number, kind=kind, path=path, rows=rows, raw=raw))
    end = tracks[0]['rows'][-1]
    expected = problem['start']
    assert all(abs(end[k] - v) < 1e-7 for k, v in zip(('north', 'east', 'tvd'), expected['position_m']))
    assert abs(end['inclination'] - expected['inclination_deg']) < 1e-7
    assert abs(end['azimuth'] - expected['azimuth_deg']) < 1e-7
    return problem, tracks


def case_defaults(problem):
    defaults_path = Path(os.getenv('DRILLING_NATIVE_DEFAULTS', str(ROOT / 'ruoyi-java/ruoyi-admin/src/main/resources/drilling/native-defaults.json')))
    defaults = json.loads(defaults_path.read_text())
    p = defaults['planning']
    bounds = problem['default_segment_bounds']
    p.update(solver='SLSQP', minSegmentLength=bounds['length_m'][0], maxSegmentLength=bounds['length_m'][1],
             minDoglegDegPer30m=bounds['dls_deg_per_30m'][0], maxDoglegDegPer30m=bounds['dls_deg_per_30m'][1],
             maxIterations=problem['solver']['maxiter'], constraintMode='PER_SEGMENT',
             targetToleranceM=problem['target']['position_tolerance_m'],
             target={'north': problem['target']['position_m'][0], 'east': problem['target']['position_m'][1],
                     'tvd': problem['target']['position_m'][2], 'inclination': problem['target']['inclination_deg'],
                     'azimuth': problem['target']['azimuth_deg']})
    p['segmentBounds'] = [dict(segment=i + 1,
        minLength=problem['segment_bounds'].get(str(i + 1), {}).get('length_m', bounds['length_m'])[0],
        maxLength=problem['segment_bounds'].get(str(i + 1), {}).get('length_m', bounds['length_m'])[1],
        minDls=bounds['dls_deg_per_30m'][0], maxDls=bounds['dls_deg_per_30m'][1]) for i in range(problem['n_segments'])]
    p['nativeOptions'].update(trajectory_mode=problem['trajectory_mode'], n_segments=problem['n_segments'],
        solver=problem['solver'], inclination_bounds_deg=problem['inclination_bounds_deg'],
        max_segment_bend_deg=problem['max_segment_bend_deg'],
        centerline_min_distance_m=problem['iscwsa']['centerline_min_distance_m'])
    defaults['errorModel']['settings']['uncertainty_model'] = problem['iscwsa']['planned']['uncertainty_model']
    return defaults


def import_cases(db, profile):
    from app.shared.trajectory.reduction import reduce_trajectory

    def query(sql, args=()):
        with db.cursor() as c:
            c.execute(sql, args)
            return c.fetchall()

    def insert(table, values):
        with db.cursor() as c:
            c.execute(f"INSERT INTO {table}({','.join(values)}) VALUES({','.join(['%s'] * len(values))})", tuple(values.values()))
            return c.lastrowid

    def ensure(table, key, values, id_column):
        rows = query(f"SELECT {id_column} FROM {table} WHERE " + ' AND '.join(f'{k}=%s' for k in key), tuple(key.values()))
        return rows[0][id_column] if rows else insert(table, {**key, **values})

    def tag_local_frame(version, ref):
        with db.cursor() as c:
            c.execute("""UPDATE drilling_trajectory_version SET reference_json=JSON_SET(reference_json,
                '$.coordinate_system','LOCAL_ENU','$.local_frame_id',%s)
                WHERE survey_version_id=%s AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(reference_json,'$.crs')),'')=''
                AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(reference_json,'$.coordinate_system')),'LOCAL_ENU')='LOCAL_ENU'
                AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(reference_json,'$.local_frame_id')),%s)=%s""",
                (ref['local_frame_id'],version,ref['local_frame_id'],ref['local_frame_id']))
            c.execute("""UPDATE drilling_source_extraction SET payload_json=JSON_SET(payload_json,
                '$.reference.coordinate_system','LOCAL_ENU','$.reference.local_frame_id',%s)
                WHERE survey_version_id=%s AND locator='CSV'
                AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.reference.crs')),'')=''
                AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.reference.coordinate_system')),'LOCAL_ENU')='LOCAL_ENU'
                AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.reference.local_frame_id')),%s)=%s""",
                (ref['local_frame_id'],version,ref['local_frame_id'],ref['local_frame_id']))

    summaries = []
    block = ensure('drilling_block', {'block_code': 'TEST-CASES'}, {'block_name': '测试区块', 'create_by': 'system-test'}, 'block_id')
    for letter in 'ABC':
        problem, tracks = load_case(letter)
        defaults = case_defaults(problem)
        field = ensure('drilling_field', {'field_code': f'TEST-{letter}'}, dict(block_id=block,
            field_name=f'测试案例 {letter}', coordinate_system='LOCAL_ENU', config_json=js(defaults), create_by='system-test'), 'field_id')
        ensure('drilling_field_config', {'field_id': field, 'config_version': 1}, dict(coordinate_system='LOCAL_ENU',
            north_reference='GRID_NORTH', algorithm_defaults_json=js(defaults), create_by='system-test'), 'field_id')
        ensure('drilling_field_member', {'field_id': field, 'user_id': 1}, {'role_code': 'OWNER'}, 'field_id')
        target = problem['target']
        ensure('drilling_target', {'field_id': field, 'target_name': '井1目标点'}, dict(target_type='POINT',
            geometry_json=js(dict(zip(('north', 'east', 'tvd'), target['position_m']))),
            tolerance_json=js({'position_m': target['position_tolerance_m']}), create_by='system-test'), 'target_id')
        bores = []
        for number in range(1, 11):
            status = 'DRILLING' if number == 1 else 'COMPLETED'
            first = next(t['rows'][0] for t in tracks if t['number'] == number)
            well = ensure('drilling_well', {'field_id': field, 'well_code': f'井{number}'}, dict(well_name=f'井{number}',
                status=status, wellhead_n=first['north'], wellhead_e=first['east'], wellhead_tvd=first['tvd'],
                metadata_json=js({'test_case': letter}), create_by='system-test'), 'well_id')
            ensure('drilling_well_catalog', {'well_id': well}, dict(field_id=field, identity_key=f'井{number}'), 'well_id')
            bore = ensure('drilling_wellbore', {'well_id': well, 'wellbore_code': 'MAIN'},
                          dict(wellbore_name='主井眼', engineering_status=status), 'wellbore_id')
            ensure('drilling_field_wellbore', {'field_id': field, 'wellbore_id': bore}, {}, 'field_id')
            bores.append(bore)
            parent = None
            for node_type, name in [('WELL', f'井{number}'), ('WELLBORE', '主井眼')]:
                parent = ensure('drilling_engineering_node', {'node_key': digest(f'TEST:{letter}:{number}:{node_type}'.encode())},
                    dict(field_id=field, parent_id=parent, node_type=node_type, node_name=name, well_id=well,
                         wellbore_id=bore if node_type == 'WELLBORE' else None, entity_kind='WELL', create_by='system-test'), 'node_id')
            for track in (t for t in tracks if t['number'] == number):
                kind, path, rows = track['kind'], track['path'], track['rows']
                data = path.read_bytes()
                sha = digest(data)
                name = f'井{number}-' + ('实钻测斜' if kind == 'ACTUAL' else '原始设计') + '.csv'
                object_key = f'drilling-files/{field}/{number}-{kind}-{sha}.csv'
                destination = profile / object_key
                destination.parent.mkdir(parents=True, exist_ok=True)
                if not destination.exists():
                    shutil.copyfile(path, destination)
                if digest(destination.read_bytes()) != sha:
                    raise ValueError('Source archive hash mismatch')
                file = ensure('data_file', {'field_id': field, 'sha256': sha, 'original_name': name}, dict(owner_user_id=1,
                    file_category='SURVEY', business_type='SOURCE_DOCUMENT', object_key=object_key,
                    content_type='text/csv', file_size=len(data), create_by='system-test'), 'file_id')
                source = ensure('drilling_source_document', {'field_id': field, 'source_key': digest(f'{letter}:{number}:{kind}:{sha}'.encode())},
                    dict(file_id=file, source_path=f'测试案例 {letter}/井{number}/{name}', source_format='CSV', parse_status='PARSED',
                         metadata_json=js({'test_case': letter, 'trajectory_kind': kind}), diagnostics_json='[]'), 'source_id')
                ensure('drilling_source_review', {'source_id': source}, dict(field_id=field, status='CONFIRMED',
                    proposed_json='{}', confirmed_json=js({'test_case': letter}), reviewed_by='system-test',
                    review_note='用户确认的算法测试数据'), 'review_id')
                ref = case_reference(letter, rows)
                existing = query('SELECT survey_version_id FROM drilling_trajectory_version WHERE wellbore_id=%s AND trajectory_kind=%s AND content_sha256=%s', (bore, kind, sha))
                if existing:
                    version = existing[0]['survey_version_id']
                    with db.cursor() as c:
                        c.execute("UPDATE drilling_trajectory_version SET reference_json=JSON_SET(reference_json,'$.reference_status','READY') WHERE survey_version_id=%s AND JSON_UNQUOTE(JSON_EXTRACT(reference_json,'$.reference_status'))='CONFIRMED'", (version,))
                    tag_local_frame(version, ref)
                else:
                    query('SELECT well_id FROM drilling_well WHERE well_id=%s FOR UPDATE',(well,))
                    no = (query('SELECT version_no AS n FROM drilling_survey_version WHERE well_id=%s ORDER BY version_no DESC LIMIT 1 FOR UPDATE',(well,)) or [{'n':0}])[0]['n']+1
                    version = insert('drilling_survey_version', dict(well_id=well, version_no=no, source_file_id=file,
                        config_version=1, source_format='CSV', max_md=rows[-1]['md'], is_current='1' if kind=='ACTUAL' else '0',
                        config_snapshot_json=js(defaults), quality_summary_json=js({'test_case': letter}), create_by='system-test'))
                    stations = reduce_trajectory(rows, ref, preserve_stored=True)
                    with db.cursor() as c:
                        c.executemany('INSERT INTO drilling_survey_station(survey_version_id,md,inclination,azimuth,north,east,tvd,dogleg,curvature,raw_json) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)',
                            [(version, s.md, s.inclination, s.azimuth, s.north, s.east, s.tvd, s.dogleg, s.curvature, js(raw)) for s, raw in zip(stations, track['raw'])])
                    insert('drilling_trajectory_version', dict(survey_version_id=version, wellbore_id=bore, trajectory_kind=kind,
                        trajectory_version_no=(query('SELECT trajectory_version_no AS n FROM drilling_trajectory_version WHERE wellbore_id=%s AND trajectory_kind=%s ORDER BY trajectory_version_no DESC LIMIT 1 FOR UPDATE',(bore,kind)) or [{'n':0}])[0]['n']+1, content_sha256=sha, source_locator='CSV', reference_json=js(ref), quality_status='READY', label=name))
                    insert('drilling_trajectory_revision', dict(survey_version_id=version, revision_type='FULL', changed_to_md=rows[-1]['md']))
                ensure('drilling_trajectory_head', {'wellbore_id': bore, 'trajectory_kind': kind}, {'survey_version_id': version}, 'survey_version_id')
                # Preserve an active legacy version instead of activating a
                # newer seed solely because CSV precision changed. Only name
                # its frame after checking source provenance and every point.
                legacy = query("""SELECT t.survey_version_id FROM drilling_trajectory_head h
                    JOIN drilling_trajectory_version t ON t.survey_version_id=h.survey_version_id
                    JOIN drilling_survey_version sv ON sv.survey_version_id=t.survey_version_id
                    JOIN drilling_source_document sd ON sd.file_id=sv.source_file_id
                    WHERE h.wellbore_id=%s AND h.trajectory_kind=%s AND t.survey_version_id<>%s
                    AND JSON_UNQUOTE(JSON_EXTRACT(sd.metadata_json,'$.test_case'))=%s
                    AND JSON_UNQUOTE(JSON_EXTRACT(sd.metadata_json,'$.trajectory_kind'))=%s
                    AND JSON_EXTRACT(t.reference_json,'$.local_frame_id') IS NULL""", (bore,kind,version,letter,kind))
                for active in legacy:
                    active_id = active['survey_version_id']
                    stored = query('SELECT md,inclination,azimuth,north,east,tvd FROM drilling_survey_station WHERE survey_version_id=%s ORDER BY md', (active_id,))
                    if matches_stored_case(stored, rows):
                        tag_local_frame(active_id, ref)
                extraction_payload = js({'kind': kind, 'locator': 'CSV', 'label': name,
                    'headers': list(track['raw'][0]), 'reference': ref,
                    'rows': [{**row, 'source_row': i + 2, 'raw_cells': list(raw.values())}
                             for i, (row, raw) in enumerate(zip(rows, track['raw']))],
                    'mapping': {'md':1,'inclination':2,'azimuth':3,'north':4,'east':5,'tvd':6},
                    'test_seed_schema': 3})
                ensure('drilling_source_extraction', {'source_id': source, 'locator': 'CSV'}, dict(payload_json=extraction_payload,
                    import_status='IMPORTED', survey_version_id=version), 'extraction_id')
                # Keep immutable source-table locators compatible with the ordinary
                # preview and format-preserving export adapters.
                with db.cursor() as c:
                    c.execute("UPDATE drilling_source_extraction SET payload_json=%s WHERE source_id=%s AND locator='CSV' AND COALESCE(JSON_EXTRACT(payload_json,'$.test_seed_schema'),0)<3", (extraction_payload, source))
                node = ensure('drilling_engineering_node', {'node_key': digest(f'TEST:{letter}:{number}:{kind}'.encode())},
                    dict(field_id=field, parent_id=parent, node_type='SURVEY' if kind=='ACTUAL' else 'PLAN',
                         node_name='实钻测斜' if kind=='ACTUAL' else '设计轨道', well_id=well, wellbore_id=bore, entity_kind='WELL'), 'node_id')
                revision = ensure('drilling_engineering_revision', {'node_id': node, 'revision_key': sha},
                    dict(business_revision='v1', outcome_name=name[:-4], phase='ACTUAL' if kind=='ACTUAL' else 'PLAN', usage_state='ACTIVE'), 'revision_id')
                ensure('drilling_engineering_revision_data', {'revision_id': revision, 'survey_version_id': version}, {'data_label': name}, 'revision_id')
                ensure('drilling_engineering_head', {'node_id': node}, dict(revision_id=revision, survey_version_id=version, activated_by='system-test'), 'node_id')
                document = ensure('drilling_engineering_document', {'document_key': digest(f'TEST:{source}'.encode())},
                    dict(node_id=node, revision_id=revision, document_role='TRAJECTORY_DATA', document_title=name), 'document_id')
                ensure('drilling_engineering_document_file', {'document_id': document, 'source_id': source},
                    dict(edition_no=1, representation_format='CSV', file_label=name, usage_state='ACTIVE'), 'document_file_id')
        for reference in bores:
            for neighbor in bores:
                if reference != neighbor:
                    ensure('drilling_neighbor_selection', {'field_id': field, 'reference_wellbore_id': reference,
                        'neighbor_wellbore_id': neighbor}, {'included': True, 'version_policy': 'CURRENT_ACTUAL'}, 'field_id')
        db.commit()
        summaries.append({'case': letter, 'field_id': field, 'wellbores': len(bores), 'versions': 20,
                          'drilling_wellbore_id': bores[0], 'start_md': tracks[0]['rows'][-1]['md']})
    return summaries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', default=os.getenv('RUOYI_PROFILE', str(Path.home() / '.ruoyi/uploadPath')))
    parser.add_argument('--wait-url', help='Wait for Java initialization before importing in a container')
    args = parser.parse_args()
    if args.wait_url:
        deadline = time.monotonic() + 180
        while True:
            try:
                with urllib.request.urlopen(args.wait_url, timeout=3) as response:
                    if response.status == 200: break
            except OSError:
                pass
            if time.monotonic() >= deadline: raise RuntimeError('Java initialization timed out')
            time.sleep(1)
    db = pymysql.connect(host=os.getenv('MYSQL_HOST', '127.0.0.1'), port=int(os.getenv('MYSQL_PORT', '3306')),
        user=os.getenv('MYSQL_USER', 'root'), password=os.environ['MYSQL_PASSWORD'], database=os.getenv('MYSQL_DATABASE', 'ry'),
        charset='utf8mb4', cursorclass=DictCursor, autocommit=False)
    try:
        print(js(import_cases(db, Path(args.profile))))
    except BaseException:
        db.rollback()
        raise
    finally:
        db.close()


if __name__ == '__main__':
    main()
