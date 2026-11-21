"""One native calculation per process; stdout is reserved for the result."""
import contextlib
import faulthandler
import json
import sys
import time


def calculate(payload):
    started = time.perf_counter()
    from app.schemas import PlanningInput
    from app.tasks.anticollision.evaluation import ErrorModelConfig
    from app.shared.trajectory.min_curvature import SurveyStation, compute_minimum_curvature
    from .trajectory_planning import SteeringState, Target, PlanningConstraints, plan_trajectory
    p = PlanningInput.model_validate(payload)

    def stations(value):
        # The Java gateway has already reduced immutable source versions with
        # the minimum-curvature kernel and marks the result canonical. Reusing
        # those coordinates avoids a second O(points) reduction in every
        # replanning request; downstream interpolation still uses the same
        # minimum-curvature kernel for continuous trajectory evaluation.
        if value.canonical_coordinates:
            if any(s.north is None or s.east is None or s.tvd is None for s in value.stations):
                raise ValueError('归算坐标不完整')
            return [SurveyStation(float(s.md), float(s.inclination), float(s.azimuth),
                                   float(s.north), float(s.east), float(s.tvd))
                    for s in value.stations]
        result = compute_minimum_curvature([s.model_dump() for s in value.stations],
            (value.origin_north, value.origin_east, value.origin_tvd))
        return result

    constraints = PlanningConstraints(**{k: getattr(p, k) for k in PlanningConstraints.__dataclass_fields__ if k != 'error_model'},
                                      error_model=ErrorModelConfig(**p.error_model.model_dump()))
    neighbors = [stations(o) for o in p.obstacles]
    history = stations(p.history) if p.history else None
    input_preparation_seconds = time.perf_counter() - started
    result = plan_trajectory(SteeringState(p.start_md, p.start_north, p.start_east, p.start_tvd, p.start_inclination, p.start_azimuth),
        Target(**p.target.model_dump()), constraints, p.solver,
        obstacles=neighbors, max_iterations=p.max_iterations,
        random_seed=p.random_seed, history=history,
        segment_bounds=p.segment_bounds, native_options=p.native_options,
        target_inclination=p.target_inclination, target_azimuth=p.target_azimuth,
        obstacle_identities=[(o.well_id, o.survey_version_id) for o in p.obstacles],
        reference_uncertainty=p.history.uncertainty.model_dump() if p.history and p.history.uncertainty else None,
        obstacle_uncertainties=[o.uncertainty.model_dump() if o.uncertainty else None for o in p.obstacles]).to_dict()
    result.setdefault('timing', {})['input_preparation_seconds'] = input_preparation_seconds
    return result


if __name__ == '__main__':
    faulthandler.enable()
    payload = json.load(sys.stdin)
    with contextlib.redirect_stdout(sys.stderr):
        try:
            response = {'result': calculate(payload)}
        except (ValueError, RuntimeError) as exc:
            response = {'error': str(exc)}
    if 'result' in response:
        started = time.perf_counter()
        result_json = json.dumps(response['result'], allow_nan=False)
        # An outer protocol field avoids serializing the large result twice
        # just to include the duration of its own JSON encoding.
        worker_timing = json.dumps({'worker_json_serialization_seconds': time.perf_counter() - started})
        print('{"result":' + result_json + ',"worker_timing":' + worker_timing + '}')
    else:
        print(json.dumps(response, allow_nan=False))
