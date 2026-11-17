#!/usr/bin/env python3
"""Compare the application with a read-only copy of the supplied engine.

Run with the same Python interpreter as the algorithm service. This is a
consistency check, not a commercial accuracy benchmark.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from zipfile import ZipFile

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

# The isolated process imports only the supplied source, without app's mode
# extension or service adapters. Identical final inputs are sent over stdin.
WORKER = r'''
import contextlib, json, sys, warnings
import numpy as np
from wellpath.api import calculate_uncertainty,evaluate_collision,optimize_trajectory
from wellpath.planning.problem import PlanningProblem
from wellpath.planning.geometry_problem import samples
warnings.filterwarnings('ignore')
def clean(x):
    if isinstance(x,np.ndarray):return clean(x.tolist())
    if isinstance(x,np.generic):return clean(x.item())
    if isinstance(x,float) and not np.isfinite(x):return None
    if isinstance(x,dict):return {k:clean(v) for k,v in x.items()}
    if isinstance(x,(list,tuple)):return [clean(v) for v in x]
    return x
results=[]
for item in json.load(sys.stdin):
    op=item['op'];p=item['input']
    if op=='uncertainty':
        r=calculate_uncertainty(p['md'],p['inc'],p['azi'],np.asarray(p['positions']),p['settings'])
        r={k:r[k] for k in ['model','radius_m','covariance_nev_m2']}
    elif op=='collision':
        for key in ['uncertainty_a','uncertainty_b']:
            for name in ['radius_m','covariance_nev_m2']:
                if p[key].get(name) is not None:p[key][name]=np.asarray(p[key][name])
        r=evaluate_collision(np.asarray(p['a']),np.asarray(p['b']),p['uncertainty_a'],p['uncertainty_b'],**p['options'])
        r={'sf':r['separation']['minimum_sf'],'min_distance':r['center_distance']['distance_m']}
    elif op=='plan':
        with contextlib.redirect_stdout(sys.stderr):
            r=optimize_trajectory(p['configuration'],algorithm=p['solver'])
        problem=PlanningProblem(p['configuration'])
        z=r['normalized_controls'];controls=r['controls']
        data=[] if z is None else samples(problem,z,p['step'])
        points=[]
        for row in data:
            point=[float(row[2]+p['start_md']),*map(float,row[3:10])]
            if not points or point[0]>points[-1][0]+1e-7:points.append(point)
        r={'controls':controls,'total_length':None if controls is None else float(controls[:,2].sum()),'feasible':False if z is None else bool(problem.check(z,dense=True)['feasible']),'points':points}
    results.append(clean(r))
json.dump(results,sys.stdout,allow_nan=False)
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    source = args.source_root.resolve()
    wheel = ROOT / 'vendor/wellpath_engine-1.0.0-py3-none-any.whl'
    original_wheel = source / 'dist' / wheel.name
    import wellpath
    installed = Path(wellpath.__file__).resolve().parent.parent
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    hashes = {'supplied': digest(original_wheel), 'vendored': digest(wheel)}
    assert hashes['supplied'] == hashes['vendored'], 'supplied and vendor wheel differ'
    manifests = {}
    with ZipFile(wheel) as package:
        names = [n for n in package.namelist() if n.startswith('wellpath/') and not n.endswith('/')]
        for label, directory in [('source', source / 'src'), ('runtime', installed)]:
            mismatches = [n for n in names if not (directory / n).is_file() or (directory / n).read_bytes() != package.read(n)]
            assert not mismatches, f'{label} differs: {mismatches}'
            manifests[label] = {'files_checked': len(names), 'differences': mismatches}

    from app.shared.trajectory.min_curvature import compute_minimum_curvature, minimum_curvature_increment
    from app.tasks.anticollision.evaluation import ErrorModelConfig, _prepare, clean_json, scan_pair
    from app.tasks.replanning.trajectory_planning import PlanningConstraints, SteeringState, Target, plan_trajectory
    from wellpath.anticollision.error_models.registry import MODEL_NAMES, load_model_settings
    from wellpath.api import calculate_uncertainty

    cases = []
    expected = []
    trajectory = compute_minimum_curvature([(0,10,20),(30,18,22),(60,25,25),(100,20,30),(160,30,40)])
    neighbor = compute_minimum_curvature([(s.md,s.inclination,s.azimuth) for s in trajectory], (25,35,5))
    positions = [[s.north,s.east,s.tvd] for s in trajectory]
    measured = {'md':[s.md for s in trajectory], 'inc':[s.inclination for s in trajectory], 'azi':[s.azimuth for s in trajectory], 'positions':positions}
    for model in MODEL_NAMES:
        settings = load_model_settings(model)
        result = calculate_uncertainty(measured['md'],measured['inc'],measured['azi'],np.asarray(positions),settings)
        cases.append({'name':f'uncertainty:{model}', 'op':'uncertainty', 'input':{**measured,'settings':settings}})
        expected.append(clean_json({k:result[k] for k in ['model','radius_m','covariance_nev_m2']}))
    with tempfile.TemporaryDirectory(prefix='drilling-engine-audit-') as temporary:
        previous_cache = os.environ.get('DRILLING_SCAN_CACHE')
        os.environ['DRILLING_SCAN_CACHE'] = str(Path(temporary)/'scan.sqlite3')
        try:
            for model in ['iscwsa_rev0','md_scalar_radius']:
                for formula in ['major_axis_sum','directional_sum','project_rss']:
                    config = ErrorModelConfig(model_version=model, sf_method=formula, k=1.5)
                    _,a,_,_,ua = _prepare(trajectory,1,config)
                    _,b,_,_,ub = _prepare(neighbor,1,config)
                    result = scan_pair(trajectory,neighbor,1,config).to_dict()
                    cases.append({'name':f'collision:{model}:{formula}', 'op':'collision', 'input':clean_json({'a':a,'b':b,'uncertainty_a':ua,'uncertainty_b':ub,'options':{'k_sigma':config.k,'sf_method':formula}})})
                    expected.append({'sf':result['sf'],'min_distance':result['min_distance']})
            for mode in ['arc','arc-straight']:
                for solver in ['SLSQP','trust-constr','COBYLA','COBYQA']:
                    delta = minimum_curvature_increment(40,20,30,20,30)[0]
                    result = plan_trajectory(SteeringState(0,0,0,0,20,30),Target(north=delta[0],east=delta[1],tvd=delta[2]),
                        PlanningConstraints(min_segment_length=1,max_segment_length=60),solver=solver,
                        native_options={'trajectory_mode':mode,'n_segments':2},max_iterations=100).to_dict()
                    config = result['solver_metadata']['configuration']
                    controls = [[s['dogleg'],s['start_toolface_deg'],s['length_m']] for s in result['segments']] if result['segments'] else None
                    points = [[r[k] for k in ['md','north','east','tvd','inclination','azimuth','toolface','dogleg_deg_per_30m']] for r in result['trajectory']]
                    cases.append({'name':f'plan:{mode}:{solver}', 'op':'plan', 'input':{'configuration':config,'solver':solver,'step':1,'start_md':0}})
                    expected.append({'controls':controls,'total_length':result['total_length'],'feasible':result['status']=='SUCCESS','points':points})
        finally:
            if previous_cache is None: os.environ.pop('DRILLING_SCAN_CACHE',None)
            else: os.environ['DRILLING_SCAN_CACHE'] = previous_cache

    env = dict(os.environ, PYTHONPATH=str(source/'src'))
    process = subprocess.run([sys.executable,'-c',WORKER],input=json.dumps(cases,allow_nan=False),text=True,capture_output=True,cwd=source,env=env,timeout=180)
    if process.returncode: raise RuntimeError(process.stderr)
    actual = json.loads(process.stdout)
    assert len(actual) == len(expected)
    checks = []
    for case,want,got in zip(cases,expected,actual):
        errors = {}
        unavailable = {}
        for key,value in want.items():
            if value is None or isinstance(value,(str,bool)):
                assert value == got[key], (case['name'],key,value,got[key])
            else:
                left_values,right_values = np.asarray(value,dtype=object),np.asarray(got[key],dtype=object)
                assert left_values.shape == right_values.shape, (case['name'],key,'shape mismatch')
                left_null,right_null = np.equal(left_values,None),np.equal(right_values,None)
                np.testing.assert_array_equal(left_null,right_null,err_msg=case['name']+':'+key+' null positions')
                left,right = left_values[~left_null].astype(float),right_values[~right_null].astype(float)
                assert np.isfinite(left).all() and np.isfinite(right).all(), (case['name'],key,'nonfinite numeric result')
                np.testing.assert_allclose(left,right,rtol=1e-9,atol=1e-8,equal_nan=False,err_msg=case['name']+':'+key)
                errors[key] = float(np.max(np.abs(left-right))) if left.size else 0.0
                if left_null.any(): unavailable[key] = int(left_null.sum())
        if case['op']=='plan': assert want['feasible'], case['name']+' did not find a feasible plan'
        checks.append({'case':case['name'],'passed':True,'maximum_absolute_differences':errors,**({'matched_unavailable_values':unavailable} if unavailable else {})})
    report = {'passed':True,'python':sys.version,'runtime_package':str(installed/'wellpath'),'wheel_sha256':hashes,'file_checks':manifests,'case_count':len(checks),'checks':checks,
        'scope':'Core source identity and same-final-input consistency; no accuracy claim or A/B/C full-scenario acceptance.',
        'application_extensions':['straight-arc mode using native straight/arc segment kernels','business input/version adapters','whole-well screening and incremental cache','task timeout and status handling']}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2,allow_nan=False)+'\n')
    print(json.dumps({'passed':True,'file_checks':manifests,'case_count':len(checks),'report':str(args.output)},ensure_ascii=False))


if __name__=='__main__':
    main()
