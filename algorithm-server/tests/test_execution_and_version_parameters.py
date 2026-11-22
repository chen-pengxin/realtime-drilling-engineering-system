"""Regression checks for bounded jobs, parameter-only review and per-well budgets."""
import asyncio
from copy import deepcopy
import json
import os
from pathlib import Path
import sys
import time

import httpx
import pytest

from app.adapters.source_documents import inspect_document
from app.main import app
from app.tasks.anticollision.evaluation import ErrorModelConfig, scan_pair, scan_network
from app.tasks.anticollision.parameter_validation import validate_settings
from app.tasks.replanning.execution import PlanningDeadline, _run_worker, execute_planning
from app.schemas import PlanningInput
from app.tasks.replanning.trajectory_planning import plan_trajectory, SteeringState, Target
from app.shared.trajectory.min_curvature import compute_minimum_curvature, minimum_curvature_increment
from app.shared.trajectory.revisions import preview_revision
from app.shared.trajectory.reduction import reduce_trajectory
from wellpath.anticollision.error_models.registry import MODEL_NAMES, load_model_settings


def profile(sigma):
    return {'model_version':'constant_isotropic','settings':{'uncertainty_model':{'type':'constant_isotropic','sigma_m':sigma}},'parameter_source':'VERSION_CONFIRMED','tool':{'name':f'tool-{sigma}'}}


def test_each_well_uses_its_own_budget_and_invalidates_cached_covariance(tmp_path,monkeypatch):
    monkeypatch.setenv('DRILLING_SCAN_CACHE',str(tmp_path/'scan.sqlite3'))
    a=compute_minimum_curvature([(0,90,0),(30,90,0)])
    b=compute_minimum_curvature([(0,90,0),(30,90,0)],origin=(0,10,0))
    one=scan_pair(a,b,step=5,reference_uncertainty=profile(1),neighbor_uncertainty=profile(1)).to_dict()
    two=scan_pair(a,b,step=5,reference_uncertainty=profile(1),neighbor_uncertainty=profile(2)).to_dict()
    again=scan_pair(a,b,step=5,reference_uncertainty=profile(1),neighbor_uncertainty=profile(2)).to_dict()
    assert one['sf']==pytest.approx(5)
    assert two['sf']==pytest.approx(10/3)
    assert two['survey_models']['neighbor']['tool']['name']=='tool-2'
    assert two['incremental']['reused_blocks']==0
    assert again['incremental']['reused_blocks']>0
    network=scan_network(a,[(8,80,b)],step=5,reference_uncertainty=profile(1),neighbor_uncertainties=[profile(2)])
    assert network['minimum_sf']==pytest.approx(two['sf'])


def test_unreachable_target_rejected_before_native_optimizer(monkeypatch):
    def forbidden(*args,**kwargs):raise AssertionError('optimizer must not run')
    monkeypatch.setattr('app.tasks.replanning.trajectory_planning.optimize_trajectory',forbidden)
    start=time.monotonic()
    with pytest.raises(ValueError,match='超过.*总长上限'):
        plan_trajectory(SteeringState(0,0,0,0,10,0),Target(tvd=1000),native_options={'trajectory_mode':'arc','n_segments':1})
    assert time.monotonic()-start<1


def test_deadline_kills_worker_instead_of_abandoning_a_thread(tmp_path):
    marker=tmp_path/'pid'
    script='import os,pathlib,time; pathlib.Path('+repr(str(marker))+').write_text(str(os.getpid())); time.sleep(60)'
    start=time.monotonic()
    with pytest.raises(PlanningDeadline,match='计算已终止'):
        _run_worker({},.3,[sys.executable,'-c',script])
    assert time.monotonic()-start<3
    pid=int(marker.read_text())
    with pytest.raises(ProcessLookupError):os.kill(pid,0)


def test_execute_planning_returns_structured_timeout(monkeypatch):
    def timeout(*args, **kwargs):
        raise PlanningDeadline('再规划已达到 30 秒最大等待时间')
    monkeypatch.setattr('app.tasks.replanning.execution._run_worker', timeout)
    payload = PlanningInput(start_md=0, start_north=0, start_east=0, start_tvd=0,
                            start_inclination=10, start_azimuth=0,
                            target={'north': 0, 'east': 0, 'tvd': 10})
    result = execute_planning(payload)
    assert result['status'] == 'TIMEOUT'
    assert result['solver_converged'] is False
    assert result['feasible_solution'] is False
    assert result['stop_reason'] == 'MAX_WAIT_SECONDS'
    assert result['execution']['timeout_seconds'] == 30


def test_all_solver_selection_keeps_first_valid_parallel_result(monkeypatch):
    calls=[]
    def fake_worker(payload, seconds, command=None, pool=None):
        calls.append(payload['solver'])
        time.sleep({'SLSQP':0.02,'trust-constr':0.15,'COBYLA':0.2,'COBYQA':0.25}[payload['solver']])
        return {'status':'SUCCESS','solver_converged':True,'feasible_solution':True,
                'solver_metadata':{'actual_solver':payload['solver']},
                'total_length':float({'SLSQP':40,'trust-constr':30,'COBYLA':50,'COBYQA':35}[payload['solver']]),
                'target_error':0.1,'trajectory':[{'md':1}],'segments':[],'timing':{'execution_total_seconds':0.01}}
    monkeypatch.setattr('app.tasks.replanning.execution._run_worker', fake_worker)
    payload=PlanningInput(start_md=0,start_north=0,start_east=0,start_tvd=0,
                          start_inclination=10,start_azimuth=0,
                          target={'north':0,'east':0,'tvd':10},solver='ALL')
    result=execute_planning(payload)
    assert set(calls)=={'SLSQP','trust-constr','COBYLA','COBYQA'}
    assert result['solver']=='ALL'
    assert result['selected_solver']=='SLSQP'
    assert 'algorithm_runs' not in result
    assert result['timing']['parallel_algorithms'] is True


def test_real_worker_returns_a_validated_plan():
    point=minimum_curvature_increment(20,20,0,20,0)[0]
    result=_run_worker({'start_md':0,'start_north':0,'start_east':0,'start_tvd':0,'start_inclination':20,'start_azimuth':0,'target':dict(zip(('north','east','tvd'),point)),'native_options':{'trajectory_mode':'straight-arc','n_segments':1}},10)
    assert result['status']=='SUCCESS'
    assert result['solver_metadata']['actual_solver']=='SLSQP'
    assert result['timing']['worker_json_serialization_seconds'] >= 0
    assert result['timing']['input_preparation_seconds'] >= 0


@pytest.mark.skipif(os.name!='posix',reason='POSIX spawn regression')
def test_worker_uses_spawn_without_forking_the_threaded_service(monkeypatch):
    import subprocess
    from concurrent.futures import ThreadPoolExecutor
    calls=[]
    real=subprocess.Popen._posix_spawn
    def spawn(self,*args,**kwargs):
        calls.append(True)
        return real(self,*args,**kwargs)
    monkeypatch.setattr(subprocess.Popen,'_posix_spawn',spawn)
    script='import json,os;print(json.dumps({"result":{"session":os.getsid(0)==os.getpid()}}))'
    with ThreadPoolExecutor(max_workers=2) as pool:
        results=list(pool.map(lambda _: _run_worker({},5,[sys.executable,'-c',script]),range(4)))
    assert len(calls)==4
    assert all(r['session'] for r in results)


@pytest.mark.parametrize('name',MODEL_NAMES)
def test_all_catalog_parameter_schemas_validate_without_trajectory(name):
    assert validate_settings(load_model_settings(name))


def test_confirmation_validation_does_not_invoke_geometry_or_covariance(monkeypatch):
    def forbidden(*args,**kwargs):raise AssertionError('parameter review must not compute a trajectory')
    monkeypatch.setattr('wellpath.api.calculate_uncertainty',forbidden)
    monkeypatch.setattr('app.main.plan_trajectory',forbidden)
    async def call():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://test') as client:
            return await client.post('/api/algorithm/engine/validate',json={'error_model':{'settings':load_model_settings('iscwsa_rev0')}})
    response=asyncio.run(call())
    assert response.status_code==200,response.text
    assert response.json()['valid']


def test_source_tool_and_parameter_evidence_are_kept_without_inventing_accuracy(tmp_path):
    path=tmp_path/'survey.txt'
    path.write_text('Survey Tool: MWD-Example\nTool Serial Number: SN-007\nError Model: ISCWSA Rev.0\nmag_bias_nt=45\nMD INC AZI\n0 1 0\n30 2 5\n')
    ref=inspect_document(path)['trajectories'][0]['reference']
    assert ref['survey_tool']['name']=='MWD-Example'
    assert ref['survey_tool']['serial_number']=='SN-007'
    assert ref['uncertainty_parameters']=={'mag_bias_nt':45}
    assert ref['survey_metadata_evidence']
    assert 'uncertainty' not in ref  # template selection still requires review


def test_tool_metadata_is_scoped_to_its_source_sheet(tmp_path):
    from openpyxl import Workbook
    wb=Workbook();first=wb.active;second=wb.create_sheet('second')
    for sheet,tool in [(first,'Tool A'),(second,'Tool B')]:
        sheet.append(['Survey Tool',tool]);sheet.append(['MD','INC','AZI']);sheet.append([0,1,0]);sheet.append([30,2,5])
    path=tmp_path/'different-instruments.xlsx';wb.save(path)
    tables=inspect_document(path)['trajectories']
    assert [t['reference']['survey_tool']['name'] for t in tables]==['Tool A','Tool B']


def test_conflicting_global_metadata_does_not_pick_first_coordinate_system(tmp_path):
    from app.adapters.survey_metadata import survey_metadata
    meta=survey_metadata('EPSG:32631\nEPSG:23031\nError Model: ISCWSA Rev.0\nError Model: Random walk')
    assert 'crs' not in meta and 'uncertainty_model_hint' not in meta
    assert meta['metadata_conflicts']['crs']==['EPSG:32631','EPSG:23031']
    path=tmp_path/'programs.txt'
    path.write_text('Survey Tool: Tool A\nEPSG:32631\nMD INC AZI\n0 1 0\n30 2 5\nSurvey Tool: Tool B\nEPSG:23031\nMD INC AZI\n0 1 0\n30 2 5\n')
    tables=inspect_document(path)['trajectories']
    assert [t['reference']['survey_tool']['name'] for t in tables]==['Tool A','Tool B']
    assert [t['reference']['crs'] for t in tables]==['EPSG:32631','EPSG:23031']


def test_resurvey_parameter_revision_does_not_inherit_over_new_budget():
    existing=[s.to_dict() for s in compute_minimum_curvature([(0,1,0),(30,5,10),(60,10,20)])]
    old={**profile(1),'confirmed':True};new={**profile(2),'confirmed':True}
    result=preview_revision({'revision_type':'RESURVEY','kind':'ACTUAL','existing':existing,'rows':existing,'replace_from_md':0,'replace_to_md':60,'parent_reference':{'uncertainty':old},'reference':{'uncertainty':new}})
    assert result['normalized_reference']['uncertainty']['settings']==new['settings']
    assert result['incremental_from_md']==0
    assert old['settings']['uncertainty_model']['sigma_m']==1
    with pytest.raises(ValueError,match='局部井段更换'):
        preview_revision({'revision_type':'RESURVEY','kind':'ACTUAL','existing':existing,'rows':[existing[1]],'replace_from_md':30,'replace_to_md':30,'parent_reference':{'uncertainty':old},'reference':{'uncertainty':new}})


def test_coordinate_reduction_preserves_source_and_adjusts_datum():
    source={'crs':'EPSG:32631','depth_datum':'KB','depth_datum_elevation_m':25,'meridian_convergence':1,'north_reference':'GRID_NORTH','origin_north':6500000,'origin_east':500000}
    original=deepcopy(source)
    target={**source,'crs':'EPSG:23031','depth_datum':'MSL','depth_datum_elevation_m':0,'meridian_convergence':2}
    stations=reduce_trajectory([{'md':0,'inclination':10,'azimuth':20},{'md':30,'inclination':10,'azimuth':20}],source,target_reference=target)
    assert source==original
    assert stations[0].tvd==pytest.approx(-25)
    assert stations[0].azimuth==pytest.approx(19)
    assert abs(stations[0].east-500000)>1
