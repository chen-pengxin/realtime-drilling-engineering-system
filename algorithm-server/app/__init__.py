"""井眼轨迹算法服务。

The supplied numerical wheel is kept unchanged.  The application owns the
trajectory-mode contract and patches the wheel's resolver at import time so
local runs and the container expose the same modes.
"""

import sys
from wellpath.planning import configuration as _trajectory_configuration

_native_resolve_trajectory = _trajectory_configuration.resolve_trajectory


def _resolve_trajectory_with_leading_straight(config):
    """Resolve the native alternating segment family."""
    mode = config.get("trajectory_mode", "arc")
    if mode not in ("straight-arc", "直线-圆弧"):
        return _native_resolve_trajectory(config)
    if "segment_types" in config:
        raise ValueError(
            "segment_types is generated internally. Configure trajectory_mode "
            "(arc, arc-straight or straight-arc) and n_segments instead."
        )
    n = config.get("n_segments")
    if not isinstance(n, int) or isinstance(n, bool) or n < 1:
        raise ValueError("n_segments must be an integer >= 1 (total number of segments).")
    return "straight-arc", ["straight" if i % 2 == 0 else "arc" for i in range(n)]


def ensure_native_profiles():
    """Register the one application profile in a single import-time hook.

    The wheel remains the numerical implementation.  Keeping this resolver
    here avoids a second monkey patch in the replanning adapter and guarantees
    that direct ``PlanningProblem`` imports and API calls see the same profile
    set.
    """
    _trajectory_configuration.TRAJECTORY_MODES = ("arc", "arc-straight", "straight-arc")
    _trajectory_configuration.resolve_trajectory = _resolve_trajectory_with_leading_straight
    loaded_problem = sys.modules.get("wellpath.planning.problem")
    if loaded_problem is not None:
        loaded_problem.resolve_trajectory = _resolve_trajectory_with_leading_straight
    return _trajectory_configuration.TRAJECTORY_MODES


ensure_native_profiles()

from .tasks.anticollision.tool_parameters import install_tool_parameter_adapter
install_tool_parameter_adapter()
