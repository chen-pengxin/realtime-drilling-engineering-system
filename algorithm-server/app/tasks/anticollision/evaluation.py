"""wellpath-engine adapter; numerical kernels and source budgets live in the vendor package.

The durable cache stores independent fixed-MD blocks, keyed by *both* geometry and
propagated uncertainty. New measurements reuse unchanged blocks; a historical edit
invalidates downstream covariance blocks even where geometry happens to coincide.
"""
from dataclasses import dataclass,field,asdict,replace
from pathlib import Path
import hashlib,json,math,os,sqlite3,time
import numpy as np
from wellpath.api import calculate_uncertainty,evaluate_collision
from wellpath.anticollision.error_models.registry import load_model_settings,MODEL_NAMES
from wellpath.anticollision.error_models.schema import _model_type
from wellpath.anticollision.uncertainty import interpolate_cov
from wellpath.geometry.arc import tangent
from wellpath.geometry.minimum_curvature import interpolate_minimum_curvature
from app.shared.trajectory.min_curvature import SurveyStation,resample_trajectory

@dataclass(frozen=True)
class ErrorModelConfig:
    mode:str='ISCWSA'
    model_version:str='iscwsa_rev0'
    k:float=1.0
    sf_method:str='major_axis_sum'
    settings:dict=field(default_factory=dict)
    radii_margin_m:float=0.0
    sigma_pa_m:float=0.0
    curve_tolerance_m:float=0.01
    def for_survey(self,profile):
        # K and the separation rule are one common engineering policy. Each
        # immutable survey supplies its own instrument/error budget.
        if profile is None:return self
        if not profile.get('model_version') or not profile.get('settings'):
            raise ValueError('测斜版本未提供完整的误差模型参数')
        model=replace(self,model_version=profile['model_version'],settings=profile['settings'])
        model.resolved()
        return model
    def resolved(self):
        # Configuration is explicit; no legacy diagonal/envelope formula is used.
        if self.sf_method not in ('major_axis_sum','directional_sum','project_rss'):raise ValueError('未知分离系数计算方法')
        if not math.isfinite(self.k) or self.k<=0:raise ValueError('K必须大于0')
        if not math.isfinite(self.curve_tolerance_m) or self.curve_tolerance_m<=0:raise ValueError('曲线容差必须大于0')
        if any(not math.isfinite(v) or v<0 for v in (self.radii_margin_m,self.sigma_pa_m)):raise ValueError('误差边界余量和PA误差必须为非负有限数')
        settings=self.settings or load_model_settings(self.model_version)
        _model_type(settings)
        return settings

@dataclass(frozen=True)
class CollisionThresholds:
    red_sf:float=1.0
    orange_sf:float=1.5
    yellow_sf:float=2.5
    red_distance:float=5.0
    error_net_ratio_orange:float=0.5
    error_net_ratio_yellow:float=1.0

class CollisionResult:
    def __init__(self,payload):self.payload=payload
    def to_dict(self):return self.payload
    def __getattr__(self,key):return self.payload[key]

def clean_json(value):
    if isinstance(value,np.ndarray):return clean_json(value.tolist())
    if isinstance(value,np.generic):return clean_json(value.item())
    if isinstance(value,float) and not math.isfinite(value):return None
    if isinstance(value,dict):return {str(k):clean_json(v) for k,v in value.items()}
    if isinstance(value,(list,tuple)):return [clean_json(v) for v in value]
    return value

def risk_level(distance,sf,thresholds):
    if distance<=thresholds.red_distance:return 'RED'
    if sf is None or not math.isfinite(sf):return 'BLUE' if sf is not None and sf>0 else 'UNKNOWN'
    return 'RED' if sf<=thresholds.red_sf else 'ORANGE' if sf<=thresholds.orange_sf else 'YELLOW' if sf<=thresholds.yellow_sf else 'BLUE'

def _digest(value):return hashlib.sha256(json.dumps(clean_json(value),sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()

def _prepare(stations,step,model):
    if len(stations)<2:raise ValueError('防碰至少需要两个真实测点')
    raw=np.asarray([[s.md,s.inclination,s.azimuth,s.north,s.east,s.tvd] for s in stations],dtype=float)
    if not np.isfinite(raw).all() or np.any(np.diff(raw[:,0])<=0) or np.any(raw[:,0]<0) or np.any((raw[:,1]<0)|(raw[:,1]>180)):
        raise ValueError('测斜数据须为有限数，井深非负且严格递增，井斜角为0～180°')
    settings=model.resolved()
    kind=_model_type(settings)
    # Propagated models use the first real station as a local tie-on. This
    # preserves absolute MD and coordinates without inventing a surface point.
    uncertainty_origin_md=float(raw[0,0])
    tie_on_models=('iscwsa_rev0','wdw','iscwsa')
    propagation_md=raw[:,0]-uncertainty_origin_md if kind in tie_on_models else raw[:,0]
    uncertainty=calculate_uncertainty(propagation_md,raw[:,1],raw[:,2],raw[:,3:6],settings)
    # Densify using the engine's MC chord-height bound as well as max step.
    from wellpath.geometry.minimum_curvature import resample_minimum_curvature
    md,p,inc,azi=resample_minimum_curvature(raw[:,0],raw[:,1],raw[:,2],raw[:,3:6],max_step_m=step,tolerance_m=model.curve_tolerance_m)
    cov=uncertainty['covariance_nev_m2']
    dense_relative_md=md-uncertainty_origin_md if kind in tie_on_models else md
    dense={'model':uncertainty['model'],'covariance_nev_m2':None if cov is None else interpolate_cov(propagation_md,cov,dense_relative_md),'radius_m':np.interp(dense_relative_md,propagation_md,uncertainty['radius_m']),'uncertainty_origin_md':uncertainty_origin_md}
    return md,p,inc,azi,dense

def _slice(u,ids):return {'model':u['model'],'covariance_nev_m2':None if u['covariance_nev_m2'] is None else u['covariance_nev_m2'][ids],'radius_m':u['radius_m'][ids],'uncertainty_origin_md':u.get('uncertainty_origin_md')}

def _cache():
    path=Path(os.getenv('DRILLING_SCAN_CACHE','data/native_scan_cache.sqlite3'));path.parent.mkdir(parents=True,exist_ok=True)
    db=sqlite3.connect(path,timeout=30)
    db.execute('PRAGMA journal_mode=WAL');db.execute('CREATE TABLE IF NOT EXISTS scan_block(cache_key TEXT PRIMARY KEY,payload TEXT NOT NULL,created REAL NOT NULL)')
    return db

def _normal_projection(delta, direction):
    """Connecting-vector component in the reference trajectory's normal plane.

    This is a derived display distance, not a replacement collision metric or
    an intersection of the entire neighboring well with that plane.
    """
    normal=delta-np.dot(delta,direction)*direction
    return float(np.linalg.norm(normal))

def _block(md,p,inc,azi,u,neighbor,model,thresholds,db):
    nmd,np_,nu=neighbor
    key=_digest({'engine':'wellpath-engine-1.0.0','derived_metrics_version':2,'md':md,'p':p,'inc':inc,'azi':azi,'u':u,'neighbor':_digest([nmd,np_,nu]),'model':asdict(model),'thresholds':asdict(thresholds)})
    cached=db.execute('SELECT payload FROM scan_block WHERE cache_key=?',(key,)).fetchone()
    if cached:return json.loads(cached[0]),True
    result=evaluate_collision(p,np_,u,nu,k_sigma=model.k,sf_method=model.sf_method,screening=True,sf_levels=(thresholds.red_sf,thresholds.orange_sf,thresholds.yellow_sf),centerline_red_distance_m=thresholds.red_distance,radii_margin_m=model.radii_margin_m,sigma_pa_m=model.sigma_pa_m)
    sf=result['separation'];center=result['center_distance'];rows=[]
    for i,(sf_i,j) in enumerate(zip(sf['minimum_by_a'],sf['offset_indices_by_a'])):
        j=int(j);d=float(np.linalg.norm(p[i]-np_[j]));level=risk_level(d,float(sf_i),thresholds)
        rows.append({'md':float(md[i]),'neighbor_md':float(nmd[j]),'reference_point':p[i].tolist(),'neighbor_point':np_[j].tolist(),'sf':float(sf_i),'operating_sf':float(sf_i),'center_distance':d,'horizontal_distance':float(np.linalg.norm((p[i]-np_[j])[:2])),'normal_distance':_normal_projection(p[i]-np_[j],tangent(inc[i],azi[i])),'risk_level':level,'operating_risk_level':level,'spacing_risk_level':'RED' if d<=thresholds.red_distance else 'BLUE','separation_factor_risk_level':risk_level(math.inf,float(sf_i),thresholds)})
    i=int(sf['reference_index']);j=int(sf['offset_index']);s=int(center['segment_a']);pa=np.asarray(center['point_a_m']);a,b=p[s],p[min(s+1,len(p)-1)];t=float(np.clip(np.dot(pa-a,b-a)/max(np.dot(b-a,b-a),1e-30),0,1));ns=int(center['segment_b']);na,nb=np_[ns],np_[min(ns+1,len(np_)-1)];nt=float(np.clip(np.dot(np.asarray(center['point_b_m'])-na,nb-na)/max(np.dot(nb-na,nb-na),1e-30),0,1))
    reference_md=md[s]+t*(md[min(s+1,len(md)-1)]-md[s])
    _,closest_inc,closest_azi=interpolate_minimum_curvature(md,inc,azi,p,[reference_md])
    normal_distance=_normal_projection(pa-np.asarray(center['point_b_m']),tangent(closest_inc[0],closest_azi[0]))
    out=clean_json({'sf':sf['minimum_sf'],'sf_reference_md':md[i],'sf_neighbor_md':nmd[j],'sf_reference_point':p[i],'sf_neighbor_point':np_[j],'error_envelope_radius':sf.get('combined_error_boundary_m'),'min_distance':center['distance_m'],'reference_md':reference_md,'neighbor_md':nmd[ns]+nt*(nmd[min(ns+1,len(nmd)-1)]-nmd[ns]),'reference_point':center['point_a_m'],'neighbor_point':center['point_b_m'],'normal_distance':normal_distance,'profile':rows,'native_diagnostics':{'screening':sf.get('screening'),'center_distance':center}})
    db.execute('INSERT OR REPLACE INTO scan_block VALUES(?,?,?)',(key,json.dumps(out,allow_nan=False),time.time()))
    return out,False

def scan_pair(reference,neighbor,step=1.,error_model=None,thresholds=None,mode='BOTH',*,reference_uncertainty=None,neighbor_uncertainty=None,**kwargs):
    model=error_model or ErrorModelConfig();th=thresholds or CollisionThresholds()
    reference_model=model.for_survey(reference_uncertainty);neighbor_model=model.for_survey(neighbor_uncertainty)
    prepared=kwargs.pop('_prepared',None)
    if prepared is not None:
        if not isinstance(prepared,(tuple,list)) or len(prepared)!=2:
            raise ValueError('内部预处理轨迹必须包含正钻井和邻井两组数据')
        amd,ap,ai,aa,au=prepared[0];bmd,bp,bi,ba,bu=prepared[1]
    else:
        amd,ap,ai,aa,au=_prepare(reference,step,reference_model);bmd,bp,bi,ba,bu=_prepare(neighbor,step,neighbor_model)
    blocks=[];reused=[];computed=[]
    # Fixed MD bands keep completed blocks stable as the well grows. Each
    # segment is included in a band, including its endpoint across the boundary.
    starts=np.floor(amd[:-1]/100).astype(int)
    with _cache() as db:
        for band in np.unique(starts):
            ids=np.flatnonzero(starts==band);idx=np.arange(ids[0],ids[-1]+2)
            r,hit=_block(amd[idx],ap[idx],ai[idx],aa[idx],_slice(au,idx),(bmd,bp,bu),model,th,db);blocks.append(r)
            (reused if hit else computed).append([float(amd[idx[0]]),float(amd[idx[-1]])])
    ds=min(blocks,key=lambda r:r['min_distance']);ss=min(blocks,key=lambda r:math.inf if r['sf'] is None else r['sf'])
    profile=list({r['md']:r for block in blocks for r in block['profile']}.values());profile.sort(key=lambda r:r['md'])
    risks=[]
    for r in profile:
        if r['risk_level']=='BLUE':continue
        if risks and risks[-1]['risk_level']==r['risk_level'] and r['md']-risks[-1]['end_md']<=step*2+1e-8:
            last=risks[-1];last['end_md']=r['md']
            last['min_sf']=min((v for v in (last['min_sf'],r['sf']) if v is not None),default=None)
            last['min_center_distance']=min(last['min_center_distance'],r['center_distance'])
        else:risks.append({'start_md':r['md'],'end_md':r['md'],'risk_level':r['risk_level'],'min_sf':r['sf'],'min_center_distance':r['center_distance']})
    level=risk_level(ds['min_distance'],ss['sf'] if ss['sf'] is not None else math.inf,th)
    payload={k:v for k,v in ds.items() if k not in ('profile','native_diagnostics')};payload.update({k:v for k,v in ss.items() if k.startswith('sf_') or k in ('sf','error_envelope_radius')})
    payload.update(mode='BOTH',risk_level=level,operating_risk_level=level,operating_sf=ss['sf'],spacing_risk_level='RED' if ds['min_distance']<=th.red_distance else 'BLUE',separation_factor_risk_level=risk_level(math.inf,ss['sf'] if ss['sf'] is not None else math.inf,th),profile=profile,risk_segments=risks,error_model={**asdict(model),'engine':'wellpath-engine-1.0.0','native_model':au['model']},uncertainty_origin_md={'reference':au.get('uncertainty_origin_md'),'neighbor':bu.get('uncertainty_origin_md')},interpolation_method='wellpath-minimum-curvature',normal_distance=ds['normal_distance'],normal_distance_definition='连接向量在正钻井原生最小曲率切向法平面上的投影长度；不是邻井与法平面的交点距离',horizontal_distance=float(np.linalg.norm(np.asarray(ds['reference_point'])[:2]-np.asarray(ds['neighbor_point'])[:2])),net_distance=None,net_distance_ratio=None,incremental={'reused_ranges':reused,'recomputed_ranges':computed,'reused_blocks':len(reused),'recomputed_blocks':len(computed),'dependency':'geometry+propagated_covariance+neighbor+configuration','cache':'persistent-sqlite'},evaluations={'spacing':{'method':'SPACING','min_distance':ds['min_distance'],'reference_md':ds['reference_md'],'neighbor_md':ds['neighbor_md']},'separation_factor':{'method':'SEPARATION_FACTOR','sf':ss['sf'],'reference_md':ss['sf_reference_md'],'neighbor_md':ss['sf_neighbor_md']}},geometry_tolerance_m=model.curve_tolerance_m)
    payload['spacing']=payload['evaluations']['spacing'];payload['separation_factor']=payload['evaluations']['separation_factor']
    payload['survey_models']={'reference':{'model_version':reference_model.model_version,'settings':reference_model.resolved(),'parameter_source':(reference_uncertainty or {}).get('parameter_source','FIELD_CONFIGURATION'),'tool':(reference_uncertainty or {}).get('tool',{})},'neighbor':{'model_version':neighbor_model.model_version,'settings':neighbor_model.resolved(),'parameter_source':(neighbor_uncertainty or {}).get('parameter_source','FIELD_CONFIGURATION'),'tool':(neighbor_uncertainty or {}).get('tool',{})}}
    return CollisionResult(clean_json(payload))

def scan_network(reference,neighbors,step=1.,error_model=None,thresholds=None,incremental_from_md=None,incremental_to_md=None,*,reference_uncertainty=None,neighbor_uncertainties=None):
    t=time.perf_counter();pairs=[];failed=[];screened=[]
    model=error_model or ErrorModelConfig(); th=thresholds or CollisionThresholds()
    coarse_step=max(float(step),10.)
    reference_model=model.for_survey(reference_uncertainty)
    def bound(rows,survey_model):
        if len(rows)<2: raise ValueError('防碰至少需要两个真实测点')
        # End-station boxes do not enclose curved segments (a returning arc
        # can extend outside both endpoints). The supplied MC sampler bounds
        # chord height; inflate its box by that bound before excluding wells.
        prepared=_prepare(rows,coarse_step,survey_model)
        _,pts,_,_,u=prepared
        radius=float(np.max(np.asarray(u.get('radius_m',0),dtype=float)))
        return pts.min(axis=0)-model.curve_tolerance_m,pts.max(axis=0)+model.curve_tolerance_m,radius,prepared
    profiles=neighbor_uncertainties or [None]*len(neighbors)
    if len(profiles)!=len(neighbors):raise ValueError('邻井误差参数与所选版本数不一致')
    reference_error=None
    try:
        rlo,rhi,rr,rprepared=bound(reference,reference_model)
    except ValueError as e:
        reference_error=str(e)
        failed.append({'scope':'REFERENCE','reason':reference_error})
    ordered=[]
    for (well_id,version_id,stations),profile in zip(neighbors if reference_error is None else [],profiles):
        try:
            survey_model=model.for_survey(profile)
            nlo,nhi,nr,nprepared=bound(stations,survey_model)
            gap=np.maximum(0,np.maximum(rlo-nhi,nlo-rhi))
            lower=float(np.linalg.norm(gap))
            # Sum is an upper bound for all three native SF denominator
            # conventions, including the optional independent PA component.
            boundary=model.k*(rr+nr+model.radii_margin_m+model.sigma_pa_m)+model.radii_margin_m
            risk_limit=boundary*max(th.yellow_sf,1.0)+th.red_distance
            sf_lower=lower/boundary if boundary>0 else math.inf
            ordered.append((lower,well_id,version_id,stations,risk_limit,sf_lower,profile,nprepared,survey_model))
        except ValueError as e: failed.append({'neighbor_well_id':well_id,'neighbor_survey_version_id':version_id,'reason':str(e)})
    ordered.sort(key=lambda x:x[0])
    best_distance=best_sf=math.inf
    reference_fine=rprepared if not reference_error and math.isclose(coarse_step,float(step)) else None
    for lower,well_id,version_id,stations,risk_limit,sf_lower,profile,nprepared,survey_model in ordered:
        # A box gap larger than every possible yellow threshold is a safe,
        # conservative exclusion.  The lower bound is recorded for audit.
        if pairs and lower>risk_limit and lower>best_distance and sf_lower>best_sf:
            screened.append({'neighbor_well_id':well_id,'neighbor_survey_version_id':version_id,'lower_bound_distance':lower,'risk_limit_distance':risk_limit,'reason':'uncertainty-expanded N/E/TVD boxes disjoint'})
            continue
        try:
            if reference_fine is None:
                reference_fine=_prepare(reference,step,reference_model)
            neighbor_fine=nprepared if math.isclose(coarse_step,float(step)) else _prepare(stations,step,survey_model)
            pair={**scan_pair(reference,stations,step,error_model,thresholds,reference_uncertainty=reference_uncertainty,neighbor_uncertainty=profile,_prepared=(reference_fine,neighbor_fine)).to_dict(),'neighbor_well_id':well_id,'neighbor_survey_version_id':version_id}
            pairs.append(pair)
            best_distance=min(best_distance,pair['min_distance'])
            if pair['sf'] is not None:best_sf=min(best_sf,pair['sf'])
        except ValueError as e:failed.append({'neighbor_well_id':well_id,'neighbor_survey_version_id':version_id,'reason':str(e)})
    nearest=min(pairs,key=lambda r:r['min_distance']) if pairs else None
    rank={'RED':0,'ORANGE':1,'YELLOW':2,'BLUE':3,'UNKNOWN':4}
    level=min((p['risk_level'] for p in pairs),key=rank.get,default=('BLUE' if screened and not failed else 'UNKNOWN'))
    # A partial network cannot certify uncomputed well pairs as safe. Preserve
    # any proven danger; otherwise expose an explicitly unevaluated aggregate.
    if failed and level=='BLUE':level='UNKNOWN'
    reused_ranges=[{'from_md':r[0],'to_md':r[1],'neighbor_well_id':p['neighbor_well_id']} for p in pairs for r in p['incremental']['reused_ranges']]
    recomputed_ranges=[{'from_md':r[0],'to_md':r[1],'neighbor_well_id':p['neighbor_well_id']} for p in pairs for r in p['incremental']['recomputed_ranges']]
    return {'status':'INCOMPLETE' if failed else 'COMPLETED','mode':'BOTH','risk_level':level,'operating_risk_level':level,'pairs':pairs,'results':pairs,'candidate_count':len(pairs),'prefiltered_count':len(screened),'operational_risk_level':level,'nearest_pair':nearest,'candidate_pairs':[p['neighbor_well_id'] for p in pairs],'candidate_well_ids':[p['neighbor_well_id'] for p in pairs],'screened_well_ids':[p['neighbor_well_id'] for p in screened],'screened_pairs':screened,'neighbor_count':len(neighbors),'scanned_pair_count':len(pairs),'screened_pair_count':len(screened),'failed_pairs':failed,'minimum_sf':min((p['sf'] for p in pairs if p['sf'] is not None),default=None),'min_distance':nearest['min_distance'] if nearest else None,'risk_segments':[{**s,'neighbor_well_id':p['neighbor_well_id']} for p in pairs for s in p['risk_segments']],'reused_md_ranges':reused_ranges,'recomputed_md_ranges':recomputed_ranges,'affected_md_range':[incremental_from_md,incremental_to_md],'incremental':{'requested_from_md':incremental_from_md,'requested_to_md':incremental_to_md,'reused_blocks':sum(p['incremental']['reused_blocks'] for p in pairs),'recomputed_blocks':sum(p['incremental']['recomputed_blocks'] for p in pairs),'dependency_checked':True},'elapsed_seconds':time.perf_counter()-t,'screening':{'method':'native-safe-uncertainty-expanded-aabb','note':'仅当不可能达到黄色阈值时排除；候选井对进入连续最小曲率详细评价'},'engine':'wellpath-engine-1.0.0'}
