"""One native calculation per process; stdout is reserved for the result."""
import contextlib
import faulthandler
import json
import sys
from dataclasses import replace


def calculate(payload):
    from app.schemas import PlanningInput
    from app.tasks.anticollision.evaluation import ErrorModelConfig
    from app.shared.trajectory.min_curvature import compute_minimum_curvature
    from .trajectory_planning import SteeringState, Target, PlanningConstraints, plan_trajectory
    p = PlanningInput.model_validate(payload)

    def stations(value):
        result = compute_minimum_curvature([s.model_dump() for s in value.stations],
            (value.origin_north, value.origin_east, value.origin_tvd))
        if value.canonical_coordinates:
            if any(s.north is None or s.east is None or s.tvd is None for s in value.stations):
                raise ValueError('归算坐标不完整')
            result = [replace(r, north=s.north, east=s.east, tvd=s.tvd) for r, s in zip(result, value.stations)]
        return result

    constraints = PlanningConstraints(**{k: getattr(p, k) for k in PlanningConstraints.__dataclass_fields__ if k != 'error_model'},
                                      error_model=ErrorModelConfig(**p.error_model.model_dump()))
    return plan_trajectory(SteeringState(p.start_md, p.start_north, p.start_east, p.start_tvd, p.start_inclination, p.start_azimuth),
        Target(**p.target.model_dump()), constraints, p.solver,
        obstacles=[stations(o) for o in p.obstacles], max_iterations=p.max_iterations,
        random_seed=p.random_seed, history=stations(p.history) if p.history else None,
        segment_bounds=p.segment_bounds, native_options=p.native_options,
        target_inclination=p.target_inclination, target_azimuth=p.target_azimuth,
        obstacle_identities=[(o.well_id, o.survey_version_id) for o in p.obstacles],
        reference_uncertainty=p.history.uncertainty.model_dump() if p.history and p.history.uncertainty else None,
        obstacle_uncertainties=[o.uncertainty.model_dump() if o.uncertainty else None for o in p.obstacles]).to_dict()


if __name__ == '__main__':
    faulthandler.enable()
    payload = json.load(sys.stdin)
    with contextlib.redirect_stdout(sys.stderr):
        try:
            response = {'result': calculate(payload)}
        except (ValueError, RuntimeError) as exc:
            response = {'error': str(exc)}
    print(json.dumps(response, allow_nan=False))
