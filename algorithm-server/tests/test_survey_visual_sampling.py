from fastapi.testclient import TestClient
import pytest
from app.main import app

client=TestClient(app)

def test_plot_sampling_uses_curved_geometry_and_identifies_measured_stations():
    response=client.post('/api/algorithm/survey/sample',json={'step':10,'stations':[
        {'md':0,'inclination':10,'azimuth':0},
        {'md':100,'inclination':60,'azimuth':45}]})
    assert response.status_code==200
    data=response.json();rows=data['stations']
    assert data['geometry_method']=='minimum-curvature'
    assert sum(r['measured'] for r in rows)==2
    assert rows[5]['north']!=pytest.approx(rows[-1]['north']/2)

@pytest.mark.parametrize('step',[0,-1,101,None])
def test_plot_sampling_rejects_invalid_step(step):
    response=client.post('/api/algorithm/survey/sample',json={'step':step,'stations':[{'md':0,'inclination':1,'azimuth':0}]})
    assert response.status_code==422

def test_dense_measurements_do_not_defeat_view_sampling_interval():
    response=client.post('/api/algorithm/survey/sample',json={'step':10,'stations':[
        {'md':md,'inclination':20,'azimuth':45} for md in range(101)]})
    data=response.json()
    assert data['measured_count']==101
    assert len(data['stations'])==11
    assert data['stations'][-1]['md']==100
