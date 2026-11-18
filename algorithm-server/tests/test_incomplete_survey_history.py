"""A late first survey keeps its absolute MD while using a local error tie-on."""
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.shared.trajectory.min_curvature import compute_minimum_curvature
from app.tasks.anticollision.evaluation import ErrorModelConfig, scan_network, scan_pair


@pytest.mark.parametrize('model', ['iscwsa_rev0', 'wdw'])
def test_nonzero_reference_start_is_evaluated_without_fabricating_md_zero(model):
    reference = compute_minimum_curvature([(109, 1, 0), (139, 2, 5)], origin=(1, 2, 109))
    neighbor = compute_minimum_curvature([(0, 1, 0), (150, 2, 5)], origin=(100, 100, 0))
    result = scan_pair(reference, neighbor, error_model=ErrorModelConfig(model_version=model)).to_dict()
    assert reference[0].md == 109 and reference[0].tvd == 109
    assert result['profile'][0]['md'] == 109
    assert result['uncertainty_origin_md']['reference'] == 109


def test_network_with_nonzero_reference_start_returns_a_real_evaluation():
    reference = compute_minimum_curvature([(109, 1, 0), (139, 2, 5)])
    neighbor = compute_minimum_curvature([(0, 1, 0), (150, 2, 5)])
    result = scan_network(reference, [(7, 70, neighbor)])
    assert result['status'] == 'COMPLETED'
    assert result['operational_risk_level'] != 'UNKNOWN'
    assert result['minimum_sf'] is not None and result['min_distance'] is not None
    assert result['results']


def test_nonzero_neighbor_start_is_evaluated_without_shifting_its_md(tmp_path, monkeypatch):
    monkeypatch.setenv('DRILLING_SCAN_CACHE', str(tmp_path/'scan.db'))
    reference = compute_minimum_curvature([(0, 0, 0), (20, 0, 0)])
    safe = compute_minimum_curvature([(0, 0, 0), (20, 0, 0)], origin=(1000, 0, 0))
    missing = compute_minimum_curvature([(109, 1, 0), (139, 2, 5)])
    result = scan_network(reference, [(7, 70, safe), (8, 80, missing)], step=10)
    assert result['pairs']
    assert result['status'] == 'COMPLETED'
    assert result['pairs'][0]['uncertainty_origin_md']['neighbor'] == 109


def test_non_propagated_radius_model_accepts_nonzero_md_without_rewriting_it(tmp_path, monkeypatch):
    monkeypatch.setenv('DRILLING_SCAN_CACHE', str(tmp_path/'scan.db'))
    reference = compute_minimum_curvature([(109, 0, 0), (139, 0, 0)])
    neighbor = compute_minimum_curvature([(109, 0, 0), (139, 0, 0)], origin=(100, 0, 0))
    result = scan_pair(reference, neighbor, step=10, error_model=ErrorModelConfig(model_version='md_scalar_radius')).to_dict()
    assert result['profile'][0]['md'] == 109
    assert result['sf'] is not None


def test_visual_sampling_is_independent_of_covariance_history():
    response = TestClient(app).post('/api/algorithm/survey/sample', json={'stations': [
        {'md': 109, 'inclination': 1, 'azimuth': 0},
        {'md': 139, 'inclination': 2, 'azimuth': 5}], 'step': 10})
    assert response.status_code == 200
    rows = response.json()['stations']
    assert rows[0]['md'] == 109 and rows[-1]['md'] == 139
    assert all(row['md'] >= 109 for row in rows)
