"""Business-contract adapter for the supplied wellpath planning engine only."""
from dataclasses import dataclass,field,asdict
import contextlib
import io
import sys
import time,math
import numpy as np
from scipy.spatial import cKDTree
from app.tasks.replanning.native_profiles import ensure_native_profiles

# Register the application profile before importing the engine classes.  The
# wheel's planning modules bind their resolver at import time.
ensure_native_profiles()
from wellpath.api import optimize_trajectory
from wellpath.planning.problem import PlanningProblem
from wellpath.planning.geometry_problem import samples
from wellpath.planning.replanning_problem import ReplanningProblem
from wellpath.workflows.scenario_replanning import make_initial as native_make_initial
from wellpath.optimization.runner import solve_algorithm as native_solve_algorithm
from wellpath.anticollision.error_models.propagation import rev0_covariance
from wellpath.anticollision.uncertainty import interpolate_cov
from app.tasks.anticollision.evaluation import (
    CollisionThresholds, ErrorModelConfig, clean_json, risk_level, scan_network,
)
from app.shared.trajectory.min_curvature import (
    SurveyStation, interpolate_minimum_curvature, interpolate_segment,
)

@dataclass(frozen=True)
class SteeringState:
    md:float
    north:float
    east:float
    tvd:float
    inclination:float
    azimuth:float
@dataclass(frozen=True)
class Target:
    kind:str='POINT'
    north:float=0.
    east:float=0.
    tvd:float=0.
    tolerance_north:float=.5
    tolerance_east:float=.5
    tolerance_tvd:float=.5
    shape_north:float|None=None
    shape_east:float|None=None
    shape_tvd:float|None=None
@dataclass(frozen=True)
class PlanningConstraints:
    max_dogleg_deg_per_30m:float=6.
    min_dogleg_deg_per_30m:float=0.
    min_segment_length:float=3.
    max_segment_length:float=30.
    constraint_mode:str='GLOBAL'
    max_toolface_change_deg:float=180.
    scan_step:float=1.
    collision_constraint:str='SEPARATION_FACTOR'
    minimum_sf:float=1.5
    error_model:ErrorModelConfig=field(default_factory=ErrorModelConfig)
    target_tolerance_m:float=.5
class PlanningResult:
    def __init__(self,payload):self.payload=payload
    def to_dict(self):return clean_json(self.payload)
    def __getattr__(self,k):return self.payload[k]

def _survey(stations,settings,md_origin=None):
    rows=list(stations); origin=float(md_origin if md_origin is not None else (rows[0].md if rows else 0.))
    return {**settings,'md_m':[s.md-origin for s in rows],'inclination_deg':[s.inclination for s in rows],'azimuth_deg':[s.azimuth for s in rows],'positions_m':[[s.north,s.east,s.tvd] for s in rows],'north_reference':'grid','length_unit':'m','angle_unit':'deg'}

def _planning_history(stations, interval_m):
    """Select existing measured stations for native error propagation.

    Dense survey rows remain available to the final 1 m collision scan.  The
    native planner only needs the configured propagation interval; selecting
    existing rows avoids inventing measurements while keeping its workload
    consistent with the supplied well_control_iscwsa workflow.
    """
    if not stations:return []
    rows = sorted(stations, key=lambda station: station.md)
    interval = max(float(interval_m), 1e-9)
    first, last = rows[0].md, rows[-1].md
    # Match the supplied engine's preparation contract: covariance is
    # propagated on a fixed interval grid and on retained fractional source
    # stations.  The dense source rows remain available to the final scan.
    targets = list(np.arange(first, last, interval)) + [first, last]
    targets.extend(
        station.md
        for station in rows[1:-1]
        if abs(station.md - round(station.md)) > 1e-7
    )
    selected = []
    md_values = np.asarray([station.md for station in rows], dtype=float)
    for md in sorted(set(float(value) for value in targets)):
        if md < first - 1e-8 or md > last + 1e-8:
            continue
        index = int(np.searchsorted(md_values, md, side='left'))
        if index < len(rows) and abs(rows[index].md - md) <= 1e-7:
            selected.append(rows[index])
            continue
        if index <= 0 or index >= len(rows):
            continue
        a, b = rows[index - 1], rows[index]
        position, inclination, azimuth = interpolate_minimum_curvature(
            [a.md, b.md], [a.inclination, b.inclination],
            [a.azimuth, b.azimuth],
            [[a.north, a.east, a.tvd], [b.north, b.east, b.tvd]], [md]
        )
        selected.append(SurveyStation(
            md, float(inclination[0]), float(azimuth[0]),
            *map(float, position[0])
        ))
    return selected


def _native_array(stations, md_origin, position_origin):
    """Convert application stations to the well_control_iscwsa array contract."""
    rows = list(stations)
    if not rows:
        return {
            'md': np.asarray([], dtype=float), 'inc': np.asarray([], dtype=float),
            'azi': np.asarray([], dtype=float), 'positions': np.empty((0, 3), dtype=float),
        }
    return {
        'md': np.asarray([float(s.md - md_origin) for s in rows], dtype=float),
        'inc': np.asarray([float(s.inclination) for s in rows], dtype=float),
        'azi': np.asarray([float(s.azimuth) for s in rows], dtype=float),
        'positions': np.asarray([[float(s.north - position_origin[0]),
                                  float(s.east - position_origin[1]),
                                  float(s.tvd - position_origin[2])] for s in rows], dtype=float),
    }


def _uniform_station_grid(stations, step):
    """Sample a path on a global MD grid while retaining fractional source stations."""
    rows = list(stations)
    if len(rows) < 2:
        return rows
    interval = max(float(step), 1e-6)
    first, last = float(rows[0].md), float(rows[-1].md)
    target_mds = set(float(value) for value in np.arange(first, last, interval))
    target_mds.update((first, last))
    target_mds.update(
        float(row.md) for row in rows[1:-1]
        if abs(row.md - round(row.md)) > 1e-7
    )
    source_md = np.asarray([row.md for row in rows], dtype=float)
    sampled = []
    for md in sorted(target_mds):
        index = int(np.searchsorted(source_md, md, side='left'))
        if index < len(rows) and abs(rows[index].md - md) <= 1e-7:
            sampled.append(rows[index])
        else:
            sampled.append(interpolate_segment(rows[index - 1], rows[index], md))
    return sampled


def _native_radius(stations, step, model, propagation_interval, *, position_origin=None):
    """Propagate Rev.0 on survey stations, then interpolate to the scan grid."""
    stations = list(stations)
    propagation = _planning_history(stations, propagation_interval)
    if len(propagation) < 2:
        propagation = stations
    origin_md = float(propagation[0].md)
    propagation_array = _native_array(
        propagation, origin_md,
        (propagation[0].north, propagation[0].east, propagation[0].tvd))
    params = model.resolved().get('uncertainty_model', {}).get('parameters', {})
    cov = rev0_covariance(propagation_array['md'], propagation_array['inc'],
                          propagation_array['azi'], propagation_array['positions'], params)['cov']
    sampled = _uniform_station_grid(stations, step)
    # All trajectories passed to the native planner must share one engineering
    # coordinate frame.  The planned well is translated by ``base_pos`` at the
    # local tie-on; neighbors receive that same translation.  Subtracting each
    # neighbor's own first point would make every well start at (0, 0, 0) and
    # incorrectly turn the initial collision constraints into an overlap.
    frame_origin = (
        position_origin
        if position_origin is not None
        else (propagation[0].north, propagation[0].east, propagation[0].tvd)
    )
    dense = _native_array(sampled, origin_md, frame_origin)
    dense_cov = interpolate_cov(propagation_array['md'], cov, dense['md'])
    radius = np.sqrt(np.maximum(np.linalg.eigvalsh(dense_cov)[:, -1], 0.0))
    return {**dense, 'md': np.asarray([station.md for station in sampled], dtype=float),
            'cov': dense_cov, 'radius': radius}


def _native_collision_summary(final, neighbors, obstacle_identities, thresholds=None):
    """Adapt the native 1 m final verification to the application result shape.

    The native verifier already evaluates every new planned point against every
    1 m neighbor point and performs the continuous centerline check. Re-running
    the application scanner here would duplicate the same O(n*m) work and make
    an otherwise fast plan appear to hang.
    """
    thresholds = thresholds or CollisionThresholds()
    check = final.get('check', {})
    reference_md = np.asarray(final.get('md', []), dtype=float)
    reference_points = np.asarray(final.get('positions', []), dtype=float)
    profiles = final.get('profiles') or []
    details = check.get('separation_details') or []
    centers = {str(row.get('neighbor')): row for row in check.get('centerline_details') or []}
    pairs = []
    all_risks = []
    for index, detail in enumerate(details):
        neighbor_index = next(
            (i for i, row in enumerate(profiles)
             if str(row.get('neighbor')) == str(detail.get('neighbor'))), index
        )
        profile = profiles[neighbor_index] if neighbor_index < len(profiles) else {}
        neighbor = neighbors[neighbor_index] if neighbor_index < len(neighbors) else {}
        neighbor_md = np.asarray(neighbor.get('scan', {}).get('md', []), dtype=float)
        neighbor_points = np.asarray(neighbor.get('scan', {}).get('positions', []), dtype=float)
        nearest_distance = np.full(len(reference_points), np.nan)
        nearest_index = np.full(len(reference_points), -1, dtype=int)
        if len(reference_points) and len(neighbor_points):
            nearest_distance, nearest_index = cKDTree(neighbor_points).query(reference_points)
        sf_values = np.asarray(profile.get('minimum_sf', []), dtype=float)
        matching = np.asarray(profile.get('matching_index', []), dtype=int)
        rows = []
        for row_index, md in enumerate(reference_md):
            sf = float(sf_values[row_index]) if row_index < len(sf_values) else None
            distance = float(nearest_distance[row_index]) if np.isfinite(nearest_distance[row_index]) else None
            level = risk_level(distance if distance is not None else math.inf,
                               sf if sf is not None else math.inf, thresholds)
            offset_index = int(matching[row_index]) if row_index < len(matching) else int(nearest_index[row_index])
            offset_md = float(neighbor_md[offset_index]) if 0 <= offset_index < len(neighbor_md) else None
            item = {
                'md': float(md), 'neighbor_md': offset_md,
                'reference_point': reference_points[row_index].tolist(),
                'neighbor_point': neighbor_points[offset_index].tolist()
                if 0 <= offset_index < len(neighbor_points) else None,
                'sf': sf, 'operating_sf': sf,
                'center_distance': distance,
                'horizontal_distance': None,
                'normal_distance': None,
                'risk_level': level, 'operating_risk_level': level,
                'spacing_risk_level': 'RED' if distance is not None and distance <= thresholds.red_distance else 'BLUE',
                'separation_factor_risk_level': risk_level(math.inf, sf if sf is not None else math.inf, thresholds),
            }
            rows.append(item)
            if level != 'BLUE':
                if (all_risks and all_risks[-1]['neighbor_well_id'] ==
                        (obstacle_identities[neighbor_index][0] if neighbor_index < len(obstacle_identities) else neighbor_index + 1)
                        and all_risks[-1]['risk_level'] == level
                        and md - all_risks[-1]['end_md'] <= 2.0):
                    all_risks[-1]['end_md'] = float(md)
                    sf_values_in_segment = [
                        value for value in (all_risks[-1]['min_sf'], sf)
                        if value is not None
                    ]
                    all_risks[-1]['min_sf'] = min(sf_values_in_segment) if sf_values_in_segment else None
                    distances_in_segment = [
                        value for value in (all_risks[-1]['min_center_distance'], distance)
                        if value is not None
                    ]
                    all_risks[-1]['min_center_distance'] = min(distances_in_segment) if distances_in_segment else None
                else:
                    all_risks.append({
                        'neighbor_well_id': obstacle_identities[neighbor_index][0]
                        if neighbor_index < len(obstacle_identities) else neighbor_index + 1,
                        'start_md': float(md), 'end_md': float(md),
                        'risk_level': level, 'min_sf': sf,
                        'min_center_distance': distance,
                    })
        center = centers.get(str(detail.get('neighbor')), {})
        center_distance = center.get('distance_lower_bound_m', detail.get('center_distance_at_sf_min_m'))
        identity = obstacle_identities[neighbor_index] if neighbor_index < len(obstacle_identities) else (neighbor_index + 1, None)
        detail_md = detail.get('reference_md_m')
        detail_row = int(np.argmin(np.abs(reference_md - float(detail_md)))) if len(reference_md) and detail_md is not None else -1
        detail_offset = int(matching[detail_row]) if 0 <= detail_row < len(matching) else -1
        pair_level = risk_level(
            float(center_distance) if center_distance is not None else math.inf,
            float(detail.get('minimum_sf')) if detail.get('minimum_sf') is not None else math.inf,
            thresholds,
        )
        pairs.append({
            'neighbor_well_id': identity[0],
            'neighbor_survey_version_id': identity[1],
            'sf': detail.get('minimum_sf'), 'operating_sf': detail.get('minimum_sf'),
            'min_distance': center_distance,
            'risk_level': pair_level, 'operating_risk_level': pair_level,
            'spacing_risk_level': 'RED' if center_distance is not None and center_distance <= thresholds.red_distance else 'BLUE',
            'separation_factor_risk_level': risk_level(math.inf, detail.get('minimum_sf') or math.inf, thresholds),
            'sf_reference_md': detail.get('reference_md_m'), 'sf_neighbor_md': detail.get('offset_md_m'),
            'reference_md': detail.get('reference_md_m'), 'neighbor_md': detail.get('offset_md_m'),
            'reference_point': reference_points[detail_row].tolist() if detail_row >= 0 else None,
            'neighbor_point': neighbor_points[detail_offset].tolist()
            if 0 <= detail_offset < len(neighbor_points) else None,
            'center_distance_at_sf_min_m': detail.get('center_distance_at_sf_min_m'),
            'error_envelope_radius': detail.get('combined_boundary_m'),
            'profile': rows,
            'risk_segments': [r for r in all_risks if r['neighbor_well_id'] == identity[0]],
            'native_verification': center,
        })
    rank = {'RED': 0, 'ORANGE': 1, 'YELLOW': 2, 'BLUE': 3, 'UNKNOWN': 4}
    nearest = min(pairs, key=lambda row: row['min_distance'] if row['min_distance'] is not None else math.inf, default=None)
    level = min((row['risk_level'] for row in pairs), key=rank.get, default='UNKNOWN')
    return {
        'status': 'COMPLETED', 'mode': 'BOTH', 'risk_level': level,
        'operating_risk_level': level, 'operational_risk_level': level,
        'pairs': pairs, 'results': pairs, 'candidate_count': len(pairs),
        'prefiltered_count': 0, 'neighbor_count': len(neighbors),
        'scanned_pair_count': len(pairs), 'screened_pair_count': 0,
        'failed_pairs': [], 'nearest_pair': nearest,
        'minimum_sf': min((p['sf'] for p in pairs if p['sf'] is not None), default=None),
        'min_distance': nearest.get('min_distance') if nearest else None,
        'risk_segments': all_risks,
        'incremental': {'reused_blocks': 0, 'recomputed_blocks': len(pairs),
                        'dependency': 'native 1m final verification'},
        'screening': {'method': 'native-final-1m-verification',
                      'note': '原生规划器已对新增规划段执行全部邻井 1 m SF 与连续中心线复核'},
        'engine': 'well_control_iscwsa ReplanningProblem',
    }


def _native_local_plan(start, target, c, solver, obstacles, *, history, segment_bounds,
                       native_options, target_inclination, target_azimuth,
                       reference_uncertainty, obstacle_uncertainties,
                       obstacle_identities):
    """Run the supplied A/B/C scenario ReplanningProblem workflow."""
    if not history or not obstacles or len(history) < 2:
        return None
    model = c.error_model.for_survey(reference_uncertainty)
    if model.model_version != 'iscwsa_rev0' or model.sf_method != 'major_axis_sum':
        return None
    if model.radii_margin_m or model.sigma_pa_m:
        return None
    if any(profile and profile.get('model_version') != 'iscwsa_rev0'
           for profile in (obstacle_uncertainties or [])):
        return None
    begin = time.perf_counter()
    options = dict(native_options or {})
    planned_interval = float(options.get('planned_survey_max_step_m', 30.0))
    history_rows = sorted([s for s in (history or []) if s.md <= start.md + 1e-8], key=lambda s: s.md)
    if not history_rows or history_rows[-1].md < start.md - 1e-7:
        history_rows.append(start)
    history_rows = _planning_history(history_rows, planned_interval)
    base_md = float(start.md)
    h = _native_array(history_rows, 0.0, (0.0, 0.0, 0.0))
    mode = options['trajectory_mode']; n_segments = int(options['n_segments'])
    default_bounds = {'dls_deg_per_30m': [c.min_dogleg_deg_per_30m, c.max_dogleg_deg_per_30m],
                      'start_toolface_deg': [-c.max_toolface_change_deg, c.max_toolface_change_deg],
                      'length_m': [c.min_segment_length, c.max_segment_length]}
    if c.constraint_mode == 'PER_SEGMENT':
        default_bounds = {'dls_deg_per_30m': [0.0, c.max_dogleg_deg_per_30m],
                          'start_toolface_deg': [-c.max_toolface_change_deg, c.max_toolface_change_deg],
                          'length_m': [1e-6, 1e9]}
    local_cfg = {
        'trajectory_mode': mode, 'n_segments': n_segments, 'arc_segment_model': 'minimum_curvature',
        'start': {'position_m': [start.north, start.east, start.tvd],
                  'inclination_deg': start.inclination, 'azimuth_deg': start.azimuth},
        'target': {'position_m': [target.north, target.east, target.tvd],
                   'inclination_deg': target_inclination if target_inclination is not None else start.inclination,
                   'azimuth_deg': target_azimuth if target_azimuth is not None else start.azimuth,
                   'position_mode': 'box' if target.kind.upper() in ('BOX', 'RECTANGLE') else 'sphere',
                   'position_tolerance_m': [target.tolerance_north, target.tolerance_east, target.tolerance_tvd]
                   if target.kind.upper() in ('BOX', 'RECTANGLE') else c.target_tolerance_m,
                   'inclination_tolerance_deg': options.get('target_inclination_tolerance_deg', 179.9 if target_inclination is None else .5),
                   'azimuth_tolerance_deg': options.get('target_azimuth_tolerance_deg', 180.0 if target_azimuth is None else .5)},
        'default_segment_bounds': default_bounds, 'segment_bounds': segment_bounds or [],
        'inclination_bounds_deg': options.get('inclination_bounds_deg', [1e-6, 179.999999]),
        'max_segment_bend_deg': options.get('max_segment_bend_deg', 150.0),
        'max_dls_deg_per_30m': c.max_dogleg_deg_per_30m,
        'collision_cells_per_arc': int(options.get('collision_cells_per_arc', 12)),
        'solver': {'random_seed': options.get('random_seed', 17), 'initial_search_attempts': 6,
                   'optimization_starts': 1, 'feasibility_max_nfev': 200,
                   'max_function_evaluations': 5000, 'max_seconds_per_run': 30,
                   'retain_feasible_iterates': True, 'maxiter': 500, **options.get('solver', {})},
    }
    params = model.resolved().get('uncertainty_model', {}).get('parameters', {})
    settings = {'k_sigma': c.error_model.k, 'sf_threshold': c.minimum_sf,
                'optimization_sf_buffer': float(options.get('optimization_sf_buffer', .03)),
                'centerline_min_distance_m': float(options.get('centerline_min_distance_m', 0.0)),
                'centerline_buffer_m': float(options.get('centerline_buffer_m', .1)),
                'optimization_cells_per_segment': int(options.get('optimization_cells_per_segment', 24)),
                'planned_survey_max_step_m': planned_interval, 'final_scan_step_m': c.scan_step,
                'optimization_target_radius_m': float(options.get(
                    'optimization_target_radius_m', max(c.target_tolerance_m - .01, .0001))),
                'start_cov_nev_m2': np.zeros((3, 3)).tolist(), 'parameters': params}
    neighbor_data = []
    for index, stations in enumerate(obstacles):
        profile = (obstacle_uncertainties or [None] * len(obstacles))[index]
        neighbor_model = model.for_survey(profile)
        scan = _native_radius(
            stations, c.scan_step, neighbor_model, planned_interval,
            position_origin=(0.0, 0.0, 0.0),
        )
        opt = _native_radius(
            stations,
            # The supplied well_control_iscwsa experiment uses a 10 m
            # optimization grid.  The final safety review remains at the
            # configured scan step (normally 1 m).
            max(float(options.get('optimization_offset_scan_step_m', 10.0)), c.scan_step),
            neighbor_model,
            planned_interval,
            position_origin=(0.0, 0.0, 0.0),
        )
        name = f'neighbor-{index + 1}'
        scan['name'] = name; opt['name'] = name
        neighbor_data.append({'name': name, 'scan': scan, 'opt': opt})
    data = {'original': h, 'history': h, 'neighbors': neighbor_data,
            'parameters': params, 'audit': {'tie_in': {'md_m': start.md}}}
    problem = ReplanningProblem(local_cfg, data, settings)
    # The native workflow logs progress to stdout; stdout is the worker's JSON
    # protocol, so route diagnostics to stderr.
    with contextlib.redirect_stdout(sys.stderr):
        starts, initialization = native_make_initial(problem)
        selected, report = native_solve_algorithm(problem, starts, solver)
    if selected is None:
        # Keep the established application solver contract for cases where the
        # native geometric initializer cannot produce a verified start. This is
        # a fallback, never a claim that the native problem is infeasible.
        return None
    final = problem.final_verify(selected)
    check = final['check']
    controls = problem.unpack(selected)
    sampled = samples(problem, selected, c.scan_step)
    trajectory = []
    for row in sampled:
        trajectory.append({'segment': int(row[0]), 'md': float(row[2] + base_md),
                           'north': float(row[3]), 'east': float(row[4]),
                           'tvd': float(row[5]), 'inclination': float(row[6]),
                           'azimuth': float(row[7]), 'toolface': float(row[8]),
                           'dogleg_deg_per_30m': float(row[9])})
    trajectory = [row for i, row in enumerate(trajectory) if i == 0 or row['md'] > trajectory[i - 1]['md'] + 1e-7]
    segments = []; md = start.md
    for index, (dls, toolface, length) in enumerate(controls):
        md_next = md + float(length)
        last = next((row for row in reversed(trajectory) if row['segment'] == index + 1), trajectory[-1])
        slide = float(np.clip(dls / max(c.max_dogleg_deg_per_30m, 1e-12), 0, 1))
        segments.append({**last, 'type': '直线' if problem.segment_types[index] == 'straight' else '圆弧',
                         'segment': index + 1, 'segment_no': index + 1, 'start_md': md,
                         'end_md': md_next, 'segment_length': float(length), 'length': float(length),
                         'length_m': float(length), 'dogleg': float(dls),
                         'curvature': math.radians(float(dls)) / 30, 'start_toolface_deg': float(toolface),
                         'toolface_deg': last['toolface'], 'toolface': last['toolface'],
                         'slide_ratio': slide, 'sliding_ratio': slide, 'composite_ratio': 1 - slide,
                         'constraint_status': 'PASS' if check.get('feasible') else 'FAIL',
                         'geometry_method': 'minimum-curvature', 'guidance_basis': 'well_control_iscwsa Rev.0'})
        md = md_next
    return {'status': 'SUCCESS' if check.get('feasible') else 'INFEASIBLE', 'solver': solver,
            'total_length': float(np.sum(controls[:, 2])), 'terminal_error': check.get('position_error_m'),
            'target_error': check.get('position_error_m'), 'elapsed_seconds': time.perf_counter() - begin,
            'timing': {'initialization_seconds': float(initialization.get('elapsed_seconds', 0)),
                       'optimization_seconds': float(report.get('optimization_seconds', 0)),
                       'collision_review_seconds': 0.0, 'total_seconds': time.perf_counter() - begin},
            'iterations': sum(int(r.get('iterations', 0)) for r in report.get('runs', [])),
            'constraint_violations': [] if check.get('feasible') else ['原生规划器密集复核未通过'],
            'trajectory': trajectory, 'segments': segments, 'native_final': final,
            '_native_neighbors': neighbor_data,
            'solver_metadata': {'actual_solver': solver, 'engine': 'well_control_iscwsa ReplanningProblem',
                                'objective': 'minimum_total_length', 'initialization': initialization,
                                'report': report, 'configuration': local_cfg, 'constraint_audit': check,
                                'minimum_sf': check.get('minimum_sf')},
            'best_effort': None, 'failure_reason': None if check.get('feasible') else report.get('status')}

def plan_trajectory(start,target,constraints=None,solver='SLSQP',obstacles=(),max_iterations=500,random_seed=17,*,history=None,segment_bounds=None,native_options=None,target_inclination=None,target_azimuth=None,obstacle_identities=None,reference_uncertainty=None,obstacle_uncertainties=None):
    plan_started = time.perf_counter()
    c=constraints or PlanningConstraints();settings=c.error_model.for_survey(reference_uncertainty).resolved()
    if solver not in ('SLSQP','trust-constr','COBYLA','COBYQA'):raise ValueError('新引擎支持 SLSQP、trust-constr、COBYLA、COBYQA；旧GA/IPOPT已移除')
    if c.collision_constraint!='SEPARATION_FACTOR':raise ValueError('只支持分离系数防碰约束')
    if c.constraint_mode not in ('GLOBAL','PER_SEGMENT'):raise ValueError('constraint_mode must be GLOBAL or PER_SEGMENT')
    if c.constraint_mode=='GLOBAL' and segment_bounds:raise ValueError('全局约束模式不能同时提交分段约束')
    if not 0<start.inclination<180:raise ValueError('所提供引擎的规划入口要求起点井斜在0～180°之间（不含端点），请选取有效定向段起点')
    # Error propagation is tied to the real current station.  A nonzero
    # absolute start MD is valid; the native model receives a local tie-on
    # while the returned trajectory and collision scan retain absolute MD.
    planned={**settings,'start_md_m':start.md,'uncertainty_origin_md_m':start.md,'survey_interval_m':float((native_options or {}).get('planned_survey_interval_m',30.)),'radius_m':0.}
    history_origin=float(history[0].md) if history else start.md
    if history:
        native_history=_planning_history(history,planned['survey_interval_m'])
        planned['history']={k:v for k,v in _survey(native_history,{},history_origin).items() if k in ('md_m','inclination_deg','azimuth_deg','positions_m')}
        planned['start_md_m']=start.md-history_origin
        planned['uncertainty_origin_md_m']=0.0
    # All editable native settings are captured. Identity/geometric inputs and
    # neighbor datasets below are always bound to the selected task versions.
    options=dict(native_options or {})
    from wellpath.planning.configuration import TRAJECTORY_MODES
    trajectory_mode=options.get('trajectory_mode')
    n_segments=options.get('n_segments')
    if trajectory_mode not in ensure_native_profiles():raise ValueError(f'trajectory_mode must be one of {ensure_native_profiles()}')
    if not isinstance(n_segments,int) or isinstance(n_segments,bool) or n_segments not in range(1,6):raise ValueError('n_segments must be an integer from 1 to 5')
    if segment_bounds and len(segment_bounds)!=n_segments:raise ValueError('segment bounds must match the selected native segment count')
    if c.constraint_mode=='PER_SEGMENT' and (not segment_bounds or len(segment_bounds)!=n_segments):raise ValueError('分段约束模式必须为每个轨迹段提供约束')
    maximum_length=sum(float(row.get('length_m',[c.min_segment_length,c.max_segment_length])[1]) for row in segment_bounds) if segment_bounds else n_segments*c.max_segment_length
    distance=math.dist((start.north,start.east,start.tvd),(target.north,target.east,target.tvd))
    tolerance=math.sqrt(target.tolerance_north**2+target.tolerance_east**2+target.tolerance_tvd**2) if target.kind.upper() in ('BOX','RECTANGLE') else c.target_tolerance_m
    if distance-tolerance>maximum_length+1e-6:
        raise ValueError(f'目标直线距离 {distance:.2f} m，超过 {n_segments} 段允许的总长上限 {maximum_length:.2f} m（目标容差 {tolerance:.2f} m）。请调整目标点或段长上限。')
    neighbor_models=[c.error_model.for_survey(profile) for profile in (obstacle_uncertainties or [None]*len(obstacles))]
    if len(neighbor_models)!=len(obstacles):raise ValueError('邻井误差参数与所选版本数不一致')
    native_result = _native_local_plan(
        start, target, c, solver, obstacles, history=history,
        segment_bounds=segment_bounds, native_options=native_options,
        target_inclination=target_inclination, target_azimuth=target_azimuth,
        reference_uncertainty=reference_uncertainty,
        obstacle_uncertainties=obstacle_uncertainties,
        obstacle_identities=obstacle_identities or [(i + 1, None) for i in range(len(obstacles))])
    if native_result is not None:
        if native_result.get('trajectory') and obstacles and native_result.get('native_final') is not None:
            collision = _native_collision_summary(
                native_result['native_final'],
                native_result.get('_native_neighbors', []),
                obstacle_identities or [(i + 1, None) for i in range(len(obstacles))],
            )
            collision['scope'] = '新增再规划段；固定实钻历史由所选实钻版本与既有防碰评价记录管理'
            native_result['collision_evaluation'] = collision
            native_result['timing']['collision_review_seconds'] = 0.0
        else:
            native_result['collision_evaluation'] = None
        native_result['elapsed_seconds'] = time.perf_counter() - plan_started
        native_result['timing']['total_seconds'] = native_result['elapsed_seconds']
        native_result.pop('_native_neighbors', None)
        return PlanningResult(clean_json(native_result))
    segment_max_dls=max((float(row.get('dls_deg_per_30m',[0.,c.max_dogleg_deg_per_30m])[1]) for row in (segment_bounds or [])),default=c.max_dogleg_deg_per_30m)
    default_bounds={'dls_deg_per_30m':[c.min_dogleg_deg_per_30m,c.max_dogleg_deg_per_30m],'start_toolface_deg':[-c.max_toolface_change_deg,c.max_toolface_change_deg],'length_m':[c.min_segment_length,c.max_segment_length]}
    if c.constraint_mode=='PER_SEGMENT':
        # The native engine needs finite base bounds; segment rows become the
        # sole effective length/DLS constraints in this mode.
        default_bounds={'dls_deg_per_30m':[0.,segment_max_dls],'start_toolface_deg':[-c.max_toolface_change_deg,c.max_toolface_change_deg],'length_m':[1e-6,1e9]}
    cfg={
      'trajectory_mode':trajectory_mode,'arc_segment_model':'minimum_curvature','n_segments':n_segments,
      'start':{'position_m':[start.north,start.east,start.tvd],'inclination_deg':start.inclination,'azimuth_deg':start.azimuth},
      'target':{'position_m':[target.north,target.east,target.tvd],'inclination_deg':target_inclination if target_inclination is not None else start.inclination,'azimuth_deg':target_azimuth if target_azimuth is not None else start.azimuth,'position_mode':'box' if target.kind.upper() in ('BOX','RECTANGLE') else 'sphere','position_tolerance_m':[target.tolerance_north,target.tolerance_east,target.tolerance_tvd] if target.kind.upper() in ('BOX','RECTANGLE') else c.target_tolerance_m,'inclination_tolerance_deg':options.get('target_inclination_tolerance_deg',179.9 if target_inclination is None else .5),'azimuth_tolerance_deg':options.get('target_azimuth_tolerance_deg',180. if target_azimuth is None else .5)},
      'constraint_mode':c.constraint_mode,'default_segment_bounds':default_bounds,
      'segment_bounds':segment_bounds or [],'inclination_bounds_deg':options.get('inclination_bounds_deg',[.000001,179.999999]),'max_segment_bend_deg':options.get('max_segment_bend_deg',150.),'max_dls_deg_per_30m':segment_max_dls if c.constraint_mode=='PER_SEGMENT' else c.max_dogleg_deg_per_30m,'collision_cells_per_arc':options.get('collision_cells_per_arc',12),'planned_well_radius_m':0.,'output_sample_step_m':c.scan_step,
      'solver':{'random_seed':random_seed,'initial_search_attempts':6,'optimization_starts':1,'feasibility_max_nfev':200,'max_function_evaluations':5000,'max_seconds_per_run':30,**options.get('solver',{}),'maxiter':max_iterations},
      'iscwsa':{'sf_threshold':c.minimum_sf,'sf_method':c.error_model.sf_method,'k_sigma':c.error_model.k,'sigma_pa_m':c.error_model.sigma_pa_m,'surface_margin_m':c.error_model.radii_margin_m,'scan_stations_per_arc':int(options.get('scan_stations_per_arc',5)),'offset_scan_step_m':float(options.get('optimization_offset_scan_step_m',5.)),'validation_scan_factor':4,'independent_wells':True,'centerline_min_distance_m':options.get('centerline_min_distance_m',0.),'centerline_red_distance_m':5.,'planned':planned,'neighbors':[{'name':f'neighbor-{i+1}','survey':_survey(s,neighbor_models[i].resolved(),s[0].md if s else 0.)} for i,s in enumerate(obstacles)]}
    }
    # Supplied engine resolves 'planar_arc', not a separate numerical method.
    cfg.pop('arc_segment_model',None)
    t=time.perf_counter();out=optimize_trajectory(cfg,algorithm=solver);controls=out['controls'];report=out['report'];trajectory=[];segments=[];check={}
    if controls is not None:
        problem=PlanningProblem(cfg);z=out['normalized_controls'];check=problem.check(z,dense=True);data=samples(problem,z,c.scan_step)
        for row in data:
            trajectory.append({'segment':int(row[0]),'md':float(row[2]+start.md),'north':float(row[3]),'east':float(row[4]),'tvd':float(row[5]),'inclination':float(row[6]),'azimuth':float(row[7]),'toolface':float(row[8]),'dogleg_deg_per_30m':float(row[9])})
        md=start.md
        for i,(dls,tf,length) in enumerate(controls):
            md+=float(length);last=next(row for row in reversed(trajectory) if row['segment']==i+1)
            # Native geometry supplies toolface. Guidance ratio is an explicitly
            # configured DLS capacity rule, not a claimed actuator measurement.
            slide=float(np.clip(dls/max(c.max_dogleg_deg_per_30m,1e-12),0,1))
            segments.append({**last,'type':'直线' if problem.segment_types[i]=='straight' else '圆弧','segment':i+1,'segment_no':i+1,'start_md':md-float(length),'end_md':md,'segment_length':float(length),'length':float(length),'length_m':float(length),'dogleg':float(dls),'curvature':math.radians(dls)/30,'start_toolface_deg':float(tf),'toolface_deg':last['toolface'],'toolface':last['toolface'],'slide_ratio':slide,'sliding_ratio':slide,'composite_ratio':1-slide,'constraint_status':'PASS' if check.get('feasible') else 'FAIL','geometry_method':'minimum-curvature','guidance_basis':'DLS / configured max DLS; steering suggestion'})
    # Native samples include each segment's start and end. Adjacent segments
    # therefore share one MD; the survey/covariance contract requires unique,
    # increasing depths. Segment end records above retain every joint.
    trajectory=[row for i,row in enumerate(trajectory)
                if i==0 or row['md']>trajectory[i-1]['md']+1e-7]
    feasible=bool(check.get('feasible'));errors=[] if feasible else ['未得到通过密集校验的可行方案；不代表问题已证明无解']
    collision=None
    if trajectory and obstacles:
        planned_rows=[SurveyStation(**{k:float(r.get(k,0)) for k in SurveyStation.__dataclass_fields__}) for r in trajectory]
        combined=[s for s in (history or []) if s.md<start.md-1e-7]+planned_rows
        identities=obstacle_identities or [(i+1,None) for i in range(len(obstacles))]
        collision=scan_network(combined,[(identities[i][0] or i+1,identities[i][1],s) for i,s in enumerate(obstacles)],c.scan_step,c.error_model,reference_uncertainty=reference_uncertainty,neighbor_uncertainties=obstacle_uncertainties)
        collision['scope']='已钻历史与再规划方案；约束是否通过以求解器密集复核为准'
    total_elapsed=time.perf_counter()-t
    return PlanningResult({'status':'SUCCESS' if feasible else 'INFEASIBLE','solver':solver,'total_length':float(np.sum(controls[:,2])) if controls is not None else None,'terminal_error':check.get('position_error_m'),'target_error':check.get('position_error_m'),'elapsed_seconds':total_elapsed,'timing':{'initialization_seconds':float(out.get('initialization',{}).get('elapsed_seconds',0) or 0),'optimization_seconds':float(report.get('optimization_seconds',sum(r.get('elapsed_seconds',0) for r in report.get('runs',[]))) or 0),'collision_review_seconds':float(collision.get('elapsed_seconds',0) if collision else 0),'total_seconds':total_elapsed},'iterations':sum(r.get('iterations',0) for r in report.get('runs',[])),'constraint_violations':errors,'trajectory':trajectory,'segments':segments,'collision_evaluation':collision,'solver_metadata':{'actual_solver':solver,'engine':'wellpath-engine-1.0.0','objective':'minimum_total_length','initialization':out['initialization'],'report':report,'configuration':cfg,'constraint_audit':check,'minimum_sf':check.get('minimum_sf')},'best_effort':None,'failure_reason':None if feasible else report.get('status')})
