"""Verify the supplied Rev.0 package, not the removed in-house covariance model."""
from copy import deepcopy
import numpy as np
import pytest
from wellpath.api import calculate_uncertainty
from wellpath.anticollision.error_models.registry import load_model_settings, MODEL_NAMES
from app.tasks.anticollision.evaluation import ErrorModelConfig,scan_pair
from app.shared.trajectory.min_curvature import compute_minimum_curvature
from app.schemas import ErrorModelInput,ThresholdInput


def covariance(settings):
    return calculate_uncertainty([0,30,60],[1,2,4],[0,10,20],[[0,0,0],[1,0,30],[2,1,60]],settings)['covariance_nev_m2']


def test_default_rev0_covariance_is_symmetric_positive_semidefinite():
    c=covariance(ErrorModelConfig().resolved())
    assert np.isfinite(c).all()
    assert np.allclose(c,np.transpose(c,(0,2,1)),atol=1e-12)
    assert np.linalg.eigvalsh(c).min()>=-1e-10


def test_rev0_parameter_change_reaches_native_propagation():
    a=ErrorModelConfig().resolved();b=deepcopy(a)
    b['uncertainty_model']['parameters']['accel_bias_mps2']*=10
    assert not np.allclose(covariance(a),covariance(b))


def test_selecting_model_version_without_explicit_settings_loads_that_model():
    p=ErrorModelInput(model_version='constant_isotropic')
    settings=ErrorModelConfig(**p.model_dump()).resolved()
    assert settings['uncertainty_model']['type']=='constant_isotropic'


def test_source_native_scalar_model_remains_configurable():
    m=ErrorModelConfig(model_version='md_scalar_radius')
    assert m.resolved()==load_model_settings('md_scalar_radius')
    a=compute_minimum_curvature([(0,1,0),(100,10,20)])
    b=compute_minimum_curvature([(0,1,0),(100,10,20)],(0,20,0))
    r=scan_pair(a,b,step=10,error_model=m)
    assert r.error_model['native_model']=='md_scalar_radius'
    assert r.mode=='BOTH'


@pytest.mark.parametrize('method',['major_axis_sum','directional_sum','project_rss'])
def test_all_native_sf_methods_execute(method,tmp_path,monkeypatch):
    monkeypatch.setenv('DRILLING_SCAN_CACHE',str(tmp_path/'cache.db'))
    a=compute_minimum_curvature([(0,1,0),(100,10,20)])
    b=compute_minimum_curvature([(0,1,0),(100,10,20)],(0,10,0))
    r=scan_pair(a,b,step=10,error_model=ErrorModelConfig(sf_method=method))
    assert r.sf is not None and r.sf>=0
    assert r.error_model['sf_method']==method


def test_sf_threshold_order_is_validated():
    with pytest.raises(ValueError):ThresholdInput(red_sf=2,orange_sf=1,yellow_sf=3)


@pytest.mark.parametrize('name', MODEL_NAMES)
@pytest.mark.parametrize('method', ['major_axis_sum', 'directional_sum', 'project_rss'])
def test_every_registered_model_reaches_both_evaluations(name, method, tmp_path, monkeypatch):
    monkeypatch.setenv('DRILLING_SCAN_CACHE', str(tmp_path / 'models.sqlite3'))
    reference = compute_minimum_curvature([(0, 5, 0), (100, 10, 20)])
    neighbor = compute_minimum_curvature([(0, 5, 0), (100, 10, 20)], origin=(0, 20, 0))
    model = ErrorModelConfig(model_version=name, sf_method=method, settings=load_model_settings(name))
    result = scan_pair(reference, neighbor, step=10, error_model=model)
    assert result.error_model['native_model'] == name
    assert result.sf is not None and np.isfinite(result.sf) and result.sf >= 0
    assert result.evaluations['spacing']['min_distance'] > 0
    assert result.evaluations['separation_factor']['sf'] == result.sf


@pytest.mark.parametrize('name,path', [
    ('constant_covariance', ('uncertainty_model', 'cov_nev_m2', 0, 0)),
    ('constant_isotropic', ('uncertainty_model', 'sigma_m')),
    ('linear_std', ('uncertainty_model', 'slope_nev_per_m', 0)),
    ('random_walk', ('uncertainty_model', 'q_nev_m2_per_m', 0)),
    ('tabulated_covariance', ('uncertainty_model', 'cov_nev_m2', 1, 0, 0)),
    ('md_scalar_radius', ('uncertainty_model', 'radius_low_m')),
    ('simplified_mwd', ('uncertainty_model', 'sigma_md_m')),
    ('iscwsa_rev0', ('uncertainty_model', 'parameters', 'accel_bias_mps2')),
    ('wdw', ('uncertainty_model', 'parameters', 'compass_sigma_deg')),
    ('iscwsa', ('start_cov_nev_m2', 0, 0)),
])
def test_each_model_uses_modified_calculation_parameters(name, path):
    original = load_model_settings(name)
    modified = deepcopy(original)
    target = modified
    for key in path[:-1]:
        target = target[key]
    target[path[-1]] = target[path[-1]] * 10 + 1
    args = ([0, 30, 60], [1, 2, 4], [0, 10, 20], [[0, 0, 0], [1, 0, 30], [2, 1, 60]])
    before = calculate_uncertainty(*args, original)
    after = calculate_uncertainty(*args, modified)
    key = 'radius_m' if name == 'md_scalar_radius' else 'covariance_nev_m2'
    assert not np.allclose(before[key], after[key])
