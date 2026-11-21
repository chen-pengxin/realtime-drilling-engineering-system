import math
import pytest

from app.shared.trajectory.min_curvature import SurveyStation, compute_minimum_curvature, minimum_curvature_increment
from app.schemas import PlanningInput
from app.tasks.anticollision.evaluation import CollisionThresholds, ErrorModelConfig, scan_network, scan_pair
from app.tasks.replanning.trajectory_planning import (PlanningConstraints, SteeringState, Target,
    plan_trajectory, _compact_realtime_response, _realtime_native_options, _solver_outcome)
from tools.import_test_cases import case_defaults, load_case


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


def test_native_planner_uses_combined_collision_constraint_and_reports_solver_metadata():
    constraints = PlanningConstraints(minimum_sf=1.5, centerline_min_distance_m=5.0, scan_step=1, max_segment_length=60)
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
    assert result['solver_metadata']['configuration']['collision_constraint'] == 'SEPARATION_FACTOR_AND_CENTER_DISTANCE'
    assert result['solver_metadata']['configuration']['centerline_min_distance_m'] == 5.0
    assert all(row["geometry_method"] == "minimum-curvature" for row in result["segments"])


def test_realtime_profile_preserves_configured_wait_without_reducing_iteration_budgets():
    offline = _realtime_native_options({
        'solver': {'max_seconds_per_run': 15, 'maxiter': 500},
        'optimization_cells_per_segment': 24,
    })
    assert offline['solver']['max_seconds_per_run'] == 15
    assert offline['optimization_cells_per_segment'] == 24

    realtime = _realtime_native_options({
        'realtime_mode': True,
        'realtime_solver_budget_seconds': 3.5,
        'solver': {'max_seconds_per_run': 15, 'maxiter': 500,
                   'max_function_evaluations': 5000, 'optimization_starts': 3},
        'optimization_cells_per_segment': 24,
    })
    assert realtime['solver']['max_seconds_per_run'] == pytest.approx(30)
    assert realtime['solver']['maxiter'] == 500
    assert realtime['solver']['max_function_evaluations'] == 5000
    assert realtime['solver']['optimization_starts'] == 1
    assert realtime['optimization_cells_per_segment'] == 24
    assert realtime['solver']['retain_feasible_iterates'] is True

    configured = _realtime_native_options({
        'realtime_mode': True,
        'realtime_max_wait_seconds': 600,
        'solver': {'max_seconds_per_run': 15},
    })
    assert configured['solver']['max_seconds_per_run'] == pytest.approx(600)

    # The public contract accepts values above the former 10/300 second
    # implementation limits and forwards them unchanged to the native solver.
    assert PlanningInput(
        start_md=0, start_north=0, start_east=0, start_tvd=0,
        start_inclination=10, start_azimuth=0,
        target={'north': 0, 'east': 0, 'tvd': 10},
        execution_timeout_seconds=601,
    ).execution_timeout_seconds == 601


def test_solver_outcome_does_not_call_a_feasible_incumbent_converged():
    report = {
        'algorithm': 'SLSQP',
        'selected_candidate': 'SLSQP run 1',
        'selected_candidate_solver_success': False,
        'runs': [{'run': 1, 'solver_success': False, 'interrupted': True,
                  'objective_calls': 10, 'iterations': 4,
                  'elapsed_seconds': 30.0, 'message': 'budget reached'}],
    }
    result = _solver_outcome(report, True, {'max_seconds_per_run': 30, 'maxiter': 500,
                                            'max_function_evaluations': 5000})
    assert result['status'] == 'FEASIBLE_NOT_CONVERGED'
    assert result['solver_converged'] is False
    assert result['feasible_solution'] is True
    assert result['stop_reason'] == 'MAX_WAIT_SECONDS'


def test_solver_outcome_reports_convergence_only_for_selected_successful_run():
    report = {'algorithm': 'SLSQP', 'selected_candidate': 'SLSQP run 1',
              'selected_candidate_solver_success': True,
              'runs': [{'run': 1, 'solver_success': True, 'interrupted': False,
                        'objective_calls': 3, 'iterations': 2,
                        'elapsed_seconds': .1, 'message': 'Optimization terminated successfully'}]}
    result = _solver_outcome(report, True, {'max_seconds_per_run': 30})
    assert result['status'] == 'SUCCESS'
    assert result['solver_converged'] is True
    assert result['stop_reason'] == 'CONVERGED'


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
    with pytest.raises(ValueError,match='arc_segment_model'):
        plan_trajectory(SteeringState(0,0,0,0,10,0),Target(tvd=10),
            native_options={'trajectory_mode':'arc','n_segments':1,'arc_segment_model':'unknown'})


@pytest.mark.parametrize('model',['minimum_curvature','constant_toolface'])
def test_generic_planning_preserves_selected_nominal_geometry(model):
    import numpy as np
    from wellpath.geometry.arc import tangent
    from wellpath.geometry.segments import segment_state
    # Fixed curvature/toolface isolate the geometry from optimizer choices.
    # A general 45-degree toolface distinguishes a spatial curve from a plane.
    expected,tangents,_=segment_state(np.zeros(3),tangent(40,25),4,45,np.array([60.]),model)
    result=plan_trajectory(SteeringState(0,0,0,0,40,25),
        Target(north=expected[0,0],east=expected[0,1],tvd=expected[0,2]),
        PlanningConstraints(constraint_mode='PER_SEGMENT',max_segment_length=70),
        segment_bounds=[{'dls_deg_per_30m':[4,4],'start_toolface_deg':[45,45],'length_m':[59.99,60.01]}],
        native_options={'trajectory_mode':'arc','n_segments':1,'arc_segment_model':model},
        max_iterations=50).to_dict()
    assert result['status']=='SUCCESS'
    assert result['solver_metadata']['configuration']['arc_segment_model']==model
    end=result['trajectory'][-1]
    actual=np.array([end['north'],end['east'],end['tvd']])
    assert np.linalg.norm(actual-expected[0]) < .02
    if model=='constant_toolface':
        assert all(row['toolface']==pytest.approx(45) for row in result['trajectory'])
    else:
        assert abs(end['toolface']-45) > .1


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


@pytest.mark.parametrize('arc_model,length', [
    ('minimum_curvature', 1861.554634),
    ('constant_toolface', 1861.561237),
])
def test_case_a_replanning_uses_native_workflow_and_adapts_all_nine_neighbors(arc_model,length):
    problem, tracks = load_case('A')
    config = case_defaults(problem)
    plan = config['planning']
    by_track = {(row['number'], row['kind']): row['rows'] for row in tracks}

    def stations(rows):
        return [SurveyStation(
            row['md'], row['inclination'], row['azimuth'],
            row['north'], row['east'], row['tvd'],
        ) for row in rows]

    history = stations(by_track[(1, 'ACTUAL')])
    obstacles = [stations(by_track[(number, 'ACTUAL')]) for number in range(2, 11)]
    start = history[-1]
    target = plan['target']
    error = config['errorModel']
    error_model = ErrorModelConfig(
        model_version=error['modelVersion'], k=error['k'],
        sf_method=error['sfMethod'], settings=error['settings'],
        radii_margin_m=error['radiiMarginM'], sigma_pa_m=error['sigmaPaM'],
        curve_tolerance_m=error['curveToleranceM'],
    )
    bounds = [
        {'length_m': [row['minLength'], row['maxLength']],
         'dls_deg_per_30m': [row['minDls'], row['maxDls']]}
        for row in plan['segmentBounds']
    ]
    result = plan_trajectory(
        SteeringState(start.md, start.north, start.east, start.tvd,
                      start.inclination, start.azimuth),
        Target(north=target['north'], east=target['east'], tvd=target['tvd']),
        PlanningConstraints(
            max_dogleg_deg_per_30m=plan['maxDoglegDegPer30m'],
            min_dogleg_deg_per_30m=plan['minDoglegDegPer30m'],
            min_segment_length=plan['minSegmentLength'],
            max_segment_length=plan['maxSegmentLength'],
            constraint_mode=plan['constraintMode'],
            scan_step=config['scanStep'], minimum_sf=config['thresholds']['orangeSf'],
            error_model=error_model,
        ),
        solver=plan['solver'], obstacles=obstacles, history=history,
        segment_bounds=bounds, native_options={**plan['nativeOptions'],'arc_segment_model':arc_model},
        target_inclination=target['inclination'], target_azimuth=target['azimuth'],
        obstacle_identities=[(number, number) for number in range(2, 11)],
    ).to_dict()

    assert result['status'] == 'SUCCESS'
    assert result['solver_metadata']['engine'] == 'well_control_iscwsa ReplanningProblem'
    assert result['total_length'] == pytest.approx(length, abs=0.02)
    assert result['solver_metadata']['configuration']['arc_segment_model'] == arc_model
    assert result['timing']['collision_review_seconds'] == 0
    assert 'native_final' not in result
    stages = ['preparation_seconds','feasibility_construction_seconds',
              'optimization_wall_seconds','final_verification_seconds',
              'collision_result_adaptation_seconds','trajectory_table_seconds',
              'result_serialization_seconds']
    assert all(result['timing'][key] >= 0 for key in stages)
    assert sum(result['timing'][key] for key in stages) <= result['timing']['algorithm_total_seconds']
    collision = result['collision_evaluation']
    assert collision['status'] == 'COMPLETED'
    assert collision['mode'] == 'BOTH'
    assert len(collision['pairs']) == 9
    assert {pair['neighbor_well_id'] for pair in collision['pairs']} == set(range(2, 11))
    assert all(pair['profile'] for pair in collision['pairs'])


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


def test_realtime_response_keeps_audit_summary_without_duplicate_transport_arrays():
    profile = [{'md': float(md), 'sf': 2.0, 'center_distance': 10.0} for md in range(0, 21)]
    pair = {
        'neighbor_well_id': 7, 'neighbor_survey_version_id': 70,
        'profile': profile, 'reference_point': [1, 2, 3],
        'neighbor_point': [4, 5, 6], 'risk_level': 'BLUE', 'sf': 2.0,
    }
    result = {'trajectory': [{'md': 0}, {'md': 20}], 'collision_evaluation': {
        'pairs': [pair], 'results': [pair], 'nearest_pair': pair,
    }}
    _compact_realtime_response(result)
    collision = result['collision_evaluation']
    assert 'results' not in collision
    assert len(collision['pairs'][0]['profile']) == 5
    assert collision['verified_step_m'] == 1.0
    assert set(collision['nearest_pair']) == {
        'neighbor_well_id', 'neighbor_survey_version_id', 'sf', 'risk_level',
        'reference_point', 'neighbor_point',
    }
