"""The supplied test wells are ordinary editable engineering inputs."""
import pytest
from tools.import_test_cases import load_case, case_defaults, case_reference, matches_stored_case
from app.shared.trajectory.reduction import reduce_trajectory


@pytest.mark.parametrize('letter', list('ABC'))
def test_case_has_ten_actual_and_design_tracks_and_authoritative_start(letter):
    problem, tracks = load_case(letter)
    assert len(tracks) == 20
    assert {t['number'] for t in tracks} == set(range(1, 11))
    assert all({t['kind'] for t in tracks if t['number'] == n} == {'ACTUAL', 'DESIGN'} for n in range(1, 11))
    actual = next(t['rows'] for t in tracks if t['number'] == 1 and t['kind'] == 'ACTUAL')
    design = next(t['rows'] for t in tracks if t['number'] == 1 and t['kind'] == 'DESIGN')
    assert actual[-1]['md'] < design[-1]['md']
    assert [actual[-1][k] for k in ('north', 'east', 'tvd')] == pytest.approx(problem['start']['position_m'])
    assert actual[-1]['md'] == pytest.approx(problem['iscwsa']['planned']['start_md_m'])
    # The last measured attitude has deviated from the original design.
    nearest = min(design, key=lambda r: abs(r['md'] - actual[-1]['md']))
    assert abs(nearest['inclination'] - actual[-1]['inclination']) > 0.1 or abs(nearest['azimuth'] - actual[-1]['azimuth']) > 0.1


@pytest.mark.parametrize('letter', list('ABC'))
def test_case_defaults_keep_native_input_and_allow_real_replanning(letter):
    problem, _ = load_case(letter)
    config = case_defaults(problem)
    planning = config['planning']
    assert planning['solver'] == 'SLSQP'
    assert config['errorModel']['modelVersion'] == 'iscwsa_rev0'
    assert planning['nativeOptions']['n_segments'] == problem['n_segments']
    assert [planning['target'][k] for k in ('north', 'east', 'tvd')] == problem['target']['position_m']
    assert len(planning['segmentBounds']) == problem['n_segments']
    assert planning['segmentBounds'][1]['maxLength'] == 2200
    assert planning['constraintMode'] == 'PER_SEGMENT'


@pytest.mark.parametrize('letter', list('ABC'))
def test_same_case_local_surveys_share_a_frame_without_changing_stored_geometry(letter):
    _, tracks = load_case(letter)
    reference = next(t['rows'] for t in tracks if t['number'] == 1 and t['kind'] == 'ACTUAL')
    neighbor = next(t['rows'] for t in tracks if t['number'] == 2 and t['kind'] == 'ACTUAL')
    source, target = case_reference(letter, neighbor), case_reference(letter, reference)
    result = reduce_trajectory(neighbor, source, target_reference=target, preserve_stored=True)
    assert [[s.north, s.east, s.tvd] for s in result] == [[r[k] for k in ('north', 'east', 'tvd')] for r in neighbor]
    assert not source['crs']
    assert source['local_frame_id'] == target['local_frame_id']
    other = 'B' if letter == 'A' else 'A'
    with pytest.raises(ValueError, match='不同的局部坐标基准'):
        reduce_trajectory(neighbor, source, target_reference=case_reference(other, reference), preserve_stored=True)
    with pytest.raises(ValueError, match='CRS'):
        reduce_trajectory(neighbor, source, target_reference={'crs':'EPSG:23031','depth_datum_elevation_m':25}, preserve_stored=True)


def test_legacy_case_frames_require_all_points_to_match_at_database_precision():
    _, tracks = load_case('A')
    source = tracks[0]['rows']
    stored = [{k:round(v,6) for k,v in row.items()} for row in source]
    assert matches_stored_case(stored, source)
    changed = [dict(row) for row in stored]
    changed[-1]['north'] += 0.000002
    assert not matches_stored_case(changed, source)
    assert not matches_stored_case(stored[:-1], source)
    assert not matches_stored_case([], [])
