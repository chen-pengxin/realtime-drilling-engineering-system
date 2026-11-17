import math
import numpy as np
import pytest
from app.shared.trajectory.horizontal_bounds import horizontal_bounds
from app.shared.trajectory.min_curvature import compute_minimum_curvature, interpolate_segment


def test_encloses_arc_bulge_between_equal_east_endpoints():
    # Horizontal semicircle-like arc: east first increases then decreases.
    st = compute_minimum_curvature([(0, 90, 30), (100, 90, 330)])
    box = horizontal_bounds(st)
    assert box[3] > max(p.east for p in st) + 10
    dense = [interpolate_segment(st[0], st[1], md) for md in np.linspace(0, 100, 1001)]
    assert all(box[0] <= p.north <= box[1] and box[2] <= p.east <= box[3] for p in dense)
    assert box[3] == pytest.approx(max(p.east for p in dense), abs=1e-6)


def test_vertical_and_whole_trajectory_not_last_segment():
    st = compute_minimum_curvature([(0, 0, 0), (200, 0, 0), (400, 90, 90), (500, 90, 90)], (1000, 2000, 0))
    box = horizontal_bounds(st)
    assert box[0] <= 1000 <= box[1]
    assert box[2] <= 2000
    assert box[3] >= st[-1].east


def test_random_arc_bounds_contain_native_interpolation():
    rng = np.random.default_rng(5435)
    for _ in range(50):
        st = compute_minimum_curvature([(0, *rng.uniform([0, 0], [170, 360])), (100, *rng.uniform([0, 0], [170, 360]))])
        box = horizontal_bounds(st)
        for md in np.linspace(0, 100, 101):
            p = interpolate_segment(st[0], st[1], md)
            assert box[0]-1e-7 <= p.north <= box[1]+1e-7
            assert box[2]-1e-7 <= p.east <= box[3]+1e-7


def test_empty_rejected():
    with pytest.raises(ValueError): horizontal_bounds([])
