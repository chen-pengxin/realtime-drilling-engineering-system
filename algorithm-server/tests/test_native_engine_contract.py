import math
import pytest

from app.shared.trajectory.min_curvature import compute_minimum_curvature, minimum_curvature_increment
from app.schemas import PlanningInput
from app.tasks.anticollision.evaluation import CollisionThresholds, ErrorModelConfig, scan_network, scan_pair
from app.tasks.replanning.trajectory_planning import PlanningConstraints, SteeringState, Target, plan_trajectory


def parallel(offset=10.0):
    reference = compute_minimum_curvature([(0, 90, 0), (100, 90, 0)])
    neighbor = compute_minimum_curvature([(0, 90, 0), (100, 90, 0)], origin=(0, offset, 0))
    return reference, neighbor


def test_native_scan_always_returns_spacing_and_separation_results():
    reference, neighbor = parallel()
    result = scan_pair(reference, neighbor, step=1)
    assert result.mode == "BOTH"
    assert result.evaluations["spacing"]["method"] == "SPACING"
    assert result.evaluations["separation_factor"]["method"] == "SEPARATION_FACTOR"
    assert result.error_model["model_version"] == "iscwsa_rev0"
    assert result.interpolation_method == "wellpath-minimum-curvature"
    assert result.normal_distance == pytest.approx(10)
    assert all(r['normal_distance']==pytest.approx(10) for r in result.profile)

def test_overlapping_trajectory_keeps_zero_sf_and_risk_minimum_distance(tmp_path,monkeypatch):
    monkeypatch.setenv('DRILLING_SCAN_CACHE',str(tmp_path/'scan.sqlite3'))
    reference=compute_minimum_curvature([(0,90,0),(100,90,0)])
    neighbor=compute_minimum_curvature([(0,90,0),(100,90,0)])
    result=scan_pair(reference,neighbor,step=5).to_dict()
    assert result['sf']==0 and result['risk_level']=='RED'
    assert result['risk_segments'][0]['min_sf']==0
    assert result['risk_segments'][0]['min_center_distance']==pytest.approx(0)

def test_risk_segment_minimum_center_distance_updates_as_wells_converge(tmp_path,monkeypatch):
    monkeypatch.setenv('DRILLING_SCAN_CACHE',str(tmp_path/'scan.sqlite3'))
    reference=compute_minimum_curvature([(0,0,0),(40,0,0)])
    neighbor=compute_minimum_curvature([(0,10,270),(40,10,270)],origin=(0,4,0))
    result=scan_pair(reference,neighbor,step=1).to_dict()
    segment=result['risk_segments'][0]
    rows=[r for r in result['profile'] if segment['start_md']<=r['md']<=segment['end_md']]
    assert segment['min_center_distance']==min(r['center_distance'] for r in rows)


def test_native_network_keeps_incremental_ranges_and_safe_screening():
    reference, neighbor = parallel(offset=1000)
    _, farther = parallel(offset=2000)
    result = scan_network(reference, [(7, 70, neighbor), (8, 80, farther)], step=1, incremental_from_md=30, incremental_to_md=40)
    assert result["mode"] == "BOTH"
    assert result["incremental"]["dependency_checked"] is True
    assert result["screened_pairs"]
    assert result["screened_pair_count"] == 1
    assert result["pairs"][0]["neighbor_well_id"] == 7


def test_native_planner_uses_separation_factor_and_reports_solver_metadata():
    constraints = PlanningConstraints(minimum_sf=1.5, scan_step=1, max_segment_length=60)
    result = plan_trajectory(
        SteeringState(0, 0, 0, 0, 1, 0),
        Target(north=0, east=0, tvd=100),
        constraints,
        solver="SLSQP",
        native_options={'trajectory_mode':'arc-straight','n_segments':2},
    ).to_dict()
    assert result["solver_metadata"]["engine"] == "wellpath-engine-1.0.0"
    assert result["solver_metadata"]["actual_solver"] == "SLSQP"
    assert result["solver_metadata"]["objective"] == "minimum_total_length"
    assert result['solver_metadata']['configuration']['trajectory_mode']=='arc-straight'
    assert result['solver_metadata']['configuration']['n_segments']==2
    assert all(row["geometry_method"] == "minimum-curvature" for row in result["segments"])


@pytest.mark.parametrize('solver',['SLSQP','trust-constr','COBYLA','COBYQA'])
def test_each_supplied_native_solver_produces_verified_feasible_plan(solver):
    point=minimum_curvature_increment(30,10,0,10,0)[0]
    result=plan_trajectory(SteeringState(0,0,0,0,10,0),
        Target(north=point[0],east=point[1],tvd=point[2]),
        solver=solver,native_options={'trajectory_mode':'arc','n_segments':1},max_iterations=100).to_dict()
    assert result['status']=='SUCCESS'
    assert result['solver_metadata']['actual_solver']==solver
    assert result['solver_metadata']['constraint_audit']['feasible']
    assert result['total_length']<=30


def test_planning_contract_has_native_geometry_selectors_and_no_scenario_field():
    assert 'scenario' not in PlanningInput.model_fields
    assert 'segment_count' not in PlanningInput.model_fields
    with pytest.raises(Exception):
        PlanningInput(start_md=0,start_north=0,start_east=0,start_tvd=0,
            start_inclination=10,start_azimuth=0,
            target={'north':0,'east':0,'tvd':100},scenario='OBSTACLE_AVOIDANCE')


def test_native_geometry_rejects_modes_and_counts_outside_algorithm_catalog():
    with pytest.raises(ValueError,match='trajectory_mode'):
        plan_trajectory(SteeringState(0,0,0,0,10,0),Target(tvd=100),
            native_options={'trajectory_mode':'business-preset','n_segments':1})
    with pytest.raises(ValueError,match='n_segments'):
        plan_trajectory(SteeringState(0,0,0,0,10,0),Target(tvd=100),
            native_options={'trajectory_mode':'arc','n_segments':6})


@pytest.mark.parametrize('count,expected', [
    (1, ['straight']),
    (2, ['straight', 'arc']),
    (3, ['straight', 'arc', 'straight']),
    (4, ['straight', 'arc', 'straight', 'arc']),
    (5, ['straight', 'arc', 'straight', 'arc', 'straight']),
])
def test_native_straight_arc_geometry_sequence(count, expected):
    from wellpath.planning.configuration import resolve_trajectory
    mode, segment_types = resolve_trajectory({'trajectory_mode': 'straight-arc', 'n_segments': count})
    assert mode == 'straight-arc'
    assert segment_types == expected


def test_single_straight_segment_preserves_attitude_and_has_zero_curvature():
    point = minimum_curvature_increment(30, 20, 45, 20, 45)[0]
    result = plan_trajectory(SteeringState(100, 0, 0, 0, 20, 45),
        Target(north=point[0], east=point[1], tvd=point[2]),
        native_options={'trajectory_mode':'straight-arc','n_segments':1}, max_iterations=50).to_dict()
    assert result['status'] == 'SUCCESS'
    assert result['segments'][0]['type'] == '直线'
    assert result['segments'][0]['dogleg'] == pytest.approx(0)
    assert result['segments'][0]['start_md'] == pytest.approx(100)
    assert all(p['inclination'] == pytest.approx(20) for p in result['trajectory'])
    assert all(p['azimuth'] == pytest.approx(45) for p in result['trajectory'])


def test_multi_segment_plan_scans_history_and_neighbors_without_duplicate_joint_depths(tmp_path, monkeypatch):
    monkeypatch.setenv('DRILLING_SCAN_CACHE',str(tmp_path/'scan.sqlite3'))
    history=compute_minimum_curvature([(0,20,0),(10,20,0)])
    last=history[-1]
    delta=minimum_curvature_increment(60,20,0,20,0)[0]
    neighbor=compute_minimum_curvature([(0,20,0),(100,20,0)],origin=(0,100,0))
    result=plan_trajectory(SteeringState(last.md,last.north,last.east,last.tvd,20,0),
        Target(north=last.north+delta[0],east=delta[1],tvd=last.tvd+delta[2]),
        history=history,obstacles=[neighbor],
        native_options={'trajectory_mode':'straight-arc','n_segments':2},max_iterations=80).to_dict()
    assert len(result['segments'])==2
    assert all(b['md']>a['md'] for a,b in zip(result['trajectory'],result['trajectory'][1:]))
    assert result['trajectory'][0]['md']==pytest.approx(last.md)
    assert result['collision_evaluation']['mode']=='BOTH'
    assert not result['collision_evaluation']['failed_pairs']


def test_planning_constraint_modes_are_mutually_exclusive():
    common = dict(start_md=0,start_north=0,start_east=0,start_tvd=0,
                  start_inclination=10,start_azimuth=0,
                  target={'north':0,'east':0,'tvd':100})
    with pytest.raises(ValueError, match='全局约束模式'):
        PlanningInput(**common, constraint_mode='GLOBAL',
                      segment_bounds=[{'length_m':[1,20],'dls_deg_per_30m':[0,6]}],
                      native_options={'trajectory_mode':'arc','n_segments':1})
    with pytest.raises(ValueError, match='分段约束模式'):
        PlanningInput(**common, constraint_mode='PER_SEGMENT',
                      native_options={'trajectory_mode':'arc','n_segments':2})
    accepted = PlanningInput(**common, constraint_mode='PER_SEGMENT',
        segment_bounds=[{'length_m':[1,20],'dls_deg_per_30m':[0,6]}],
        native_options={'trajectory_mode':'arc','n_segments':1})
    assert accepted.constraint_mode == 'PER_SEGMENT'


def test_cache_invalidates_only_affected_reference_blocks_and_changed_dependencies(tmp_path,monkeypatch):
    monkeypatch.setenv('DRILLING_SCAN_CACHE',str(tmp_path/'scan.sqlite3'))
    reference=compute_minimum_curvature([(0,20,0),(100,20,0),(200,20,0),(300,20,0)])
    neighbor=compute_minimum_curvature([(0,20,0),(300,20,0)],(0,30,0))
    first=scan_pair(reference,neighbor,step=10).to_dict()
    assert first['incremental']['recomputed_blocks']==3
    appended=compute_minimum_curvature([(0,20,0),(100,20,0),(200,20,0),(300,20,0),(330,21,1)])
    second=scan_pair(appended,neighbor,step=10).to_dict()
    assert second['incremental']['reused_blocks']==3
    assert second['incremental']['recomputed_blocks']==1
    changed=scan_pair(appended,neighbor,step=10,error_model=ErrorModelConfig(k=2)).to_dict()
    assert changed['incremental']['reused_blocks']==0
    changed_neighbor=compute_minimum_curvature([(0,20,0),(300,20,1)],(0,30,0))
    assert scan_pair(appended,changed_neighbor,step=10).incremental['reused_blocks']==0
