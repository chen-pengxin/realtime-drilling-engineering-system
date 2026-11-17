"""Whole continuous minimum-curvature trajectory AABB in the common N/E frame."""
import math
import numpy as np
from wellpath.geometry.arc import tangent
from .min_curvature import interpolate_segment


def horizontal_bounds(stations):
    if not stations:
        raise ValueError('轨迹没有测点')
    points = [(s.north, s.east) for s in stations]
    for a, b in zip(stations, stations[1:]):
        if b.md <= a.md:
            raise ValueError('测深必须严格递增')
        t0, t1 = tangent(a.inclination, a.azimuth), tangent(b.inclination, b.azimuth)
        beta = math.atan2(float(np.linalg.norm(np.cross(t0, t1))), float(np.dot(t0, t1)))
        if beta < 1e-10:
            continue
        if math.pi - beta < 1e-8:
            raise ValueError('相邻测点切向反向，不能唯一确定最小曲率圆弧')
        u = (t1 - t0 * math.cos(beta)) / math.sin(beta)
        # A coordinate extremum occurs when its tangent component is zero.
        for axis in (0, 1):
            root = math.atan2(-float(t0[axis]), float(u[axis]))
            for k in (-1, 0, 1, 2):
                angle = root + k * math.pi
                if 0 < angle < beta:
                    p = interpolate_segment(a, b, a.md + (b.md - a.md) * angle / beta)
                    points.append((p.north, p.east))
    values = np.asarray(points, dtype=float)
    if not np.isfinite(values).all():
        raise ValueError('轨迹坐标必须为有限数')
    # Outward rounding prevents a touching pair being lost to floating-point error.
    return [float(np.nextafter(values[:, 0].min(), -np.inf)),
            float(np.nextafter(values[:, 0].max(), np.inf)),
            float(np.nextafter(values[:, 1].min(), -np.inf)),
            float(np.nextafter(values[:, 1].max(), np.inf))]
