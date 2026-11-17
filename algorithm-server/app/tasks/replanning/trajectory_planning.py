"""Business-contract adapter for the supplied wellpath planning engine only."""
from dataclasses import dataclass,field,asdict
import time,math
import numpy as np
from app.tasks.replanning.native_profiles import ensure_native_profiles

# Register the application profile before importing the engine classes.  The
# wheel's planning modules bind their resolver at import time.
ensure_native_profiles()
from wellpath.api import optimize_trajectory
from wellpath.planning.problem import PlanningProblem
from wellpath.planning.geometry_problem import samples
from app.tasks.anticollision.evaluation import ErrorModelConfig,clean_json,scan_network
from app.shared.trajectory.min_curvature import SurveyStation

@dataclass(frozen=True)
class SteeringState:
    md:float
    north:float
    east:float
    tvd:float
    inclination:float
    azimuth:float
@dataclass(frozen=True)
class Target:
    kind:str='POINT'
    north:float=0.
    east:float=0.
    tvd:float=0.
    tolerance_north:float=.5
    tolerance_east:float=.5
    tolerance_tvd:float=.5
    shape_north:float|None=None
    shape_east:float|None=None
    shape_tvd:float|None=None
@dataclass(frozen=True)
class PlanningConstraints:
    max_dogleg_deg_per_30m:float=6.
    min_dogleg_deg_per_30m:float=0.
    min_segment_length:float=3.
    max_segment_length:float=30.
    constraint_mode:str='GLOBAL'
    max_toolface_change_deg:float=180.
    scan_step:float=1.
    collision_constraint:str='SEPARATION_FACTOR'
    minimum_sf:float=1.5
    error_model:ErrorModelConfig=field(default_factory=ErrorModelConfig)
    target_tolerance_m:float=.5
class PlanningResult:
    def __init__(self,payload):self.payload=payload
    def to_dict(self):return clean_json(self.payload)
    def __getattr__(self,k):return self.payload[k]

def _survey(stations,settings):
    return {**settings,'md_m':[s.md for s in stations],'inclination_deg':[s.inclination for s in stations],'azimuth_deg':[s.azimuth for s in stations],'positions_m':[[s.north,s.east,s.tvd] for s in stations],'north_reference':'grid','length_unit':'m','angle_unit':'deg'}

def plan_trajectory(start,target,constraints=None,solver='SLSQP',obstacles=(),max_iterations=500,random_seed=17,*,history=None,segment_bounds=None,native_options=None,target_inclination=None,target_azimuth=None,obstacle_identities=None,reference_uncertainty=None,obstacle_uncertainties=None):
    c=constraints or PlanningConstraints();settings=c.error_model.for_survey(reference_uncertainty).resolved()
    if solver not in ('SLSQP','trust-constr','COBYLA','COBYQA'):raise ValueError('新引擎支持 SLSQP、trust-constr、COBYLA、COBYQA；旧GA/IPOPT已移除')
    if c.collision_constraint!='SEPARATION_FACTOR':raise ValueError('只支持分离系数防碰约束')
    if c.constraint_mode not in ('GLOBAL','PER_SEGMENT'):raise ValueError('constraint_mode must be GLOBAL or PER_SEGMENT')
    if c.constraint_mode=='GLOBAL' and segment_bounds:raise ValueError('全局约束模式不能同时提交分段约束')
    if not 0<start.inclination<180:raise ValueError('所提供引擎的规划入口要求起点井斜在0～180°之间（不含端点），请选取有效定向段起点')
    if settings.get('uncertainty_model',{}).get('type') in ('iscwsa_rev0','wdw') and obstacles and start.md>0 and not history:raise ValueError('Rev.0再规划必须传入从MD=0到当前井底的真实测量历史')
    planned={**settings,'start_md_m':start.md,'survey_interval_m':10.,'radius_m':0.}
    if history: planned['history']={k:v for k,v in _survey(history,{}).items() if k in ('md_m','inclination_deg','azimuth_deg','positions_m')}
    # All editable native settings are captured. Identity/geometric inputs and
    # neighbor datasets below are always bound to the selected task versions.
    options=dict(native_options or {})
    from wellpath.planning.configuration import TRAJECTORY_MODES
    trajectory_mode=options.get('trajectory_mode')
    n_segments=options.get('n_segments')
    if trajectory_mode not in ensure_native_profiles():raise ValueError(f'trajectory_mode must be one of {ensure_native_profiles()}')
    if not isinstance(n_segments,int) or isinstance(n_segments,bool) or n_segments not in range(1,6):raise ValueError('n_segments must be an integer from 1 to 5')
    if segment_bounds and len(segment_bounds)!=n_segments:raise ValueError('segment bounds must match the selected native segment count')
    if c.constraint_mode=='PER_SEGMENT' and (not segment_bounds or len(segment_bounds)!=n_segments):raise ValueError('分段约束模式必须为每个轨迹段提供约束')
    maximum_length=sum(float(row.get('length_m',[c.min_segment_length,c.max_segment_length])[1]) for row in segment_bounds) if segment_bounds else n_segments*c.max_segment_length
    distance=math.dist((start.north,start.east,start.tvd),(target.north,target.east,target.tvd))
    tolerance=math.sqrt(target.tolerance_north**2+target.tolerance_east**2+target.tolerance_tvd**2) if target.kind.upper() in ('BOX','RECTANGLE') else c.target_tolerance_m
    if distance-tolerance>maximum_length+1e-6:
        raise ValueError(f'目标直线距离 {distance:.2f} m，超过 {n_segments} 段允许的总长上限 {maximum_length:.2f} m（目标容差 {tolerance:.2f} m）。请调整目标点或段长上限。')
    neighbor_models=[c.error_model.for_survey(profile) for profile in (obstacle_uncertainties or [None]*len(obstacles))]
    if len(neighbor_models)!=len(obstacles):raise ValueError('邻井误差参数与所选版本数不一致')
    segment_max_dls=max((float(row.get('dls_deg_per_30m',[0.,c.max_dogleg_deg_per_30m])[1]) for row in (segment_bounds or [])),default=c.max_dogleg_deg_per_30m)
    default_bounds={'dls_deg_per_30m':[c.min_dogleg_deg_per_30m,c.max_dogleg_deg_per_30m],'start_toolface_deg':[-c.max_toolface_change_deg,c.max_toolface_change_deg],'length_m':[c.min_segment_length,c.max_segment_length]}
    if c.constraint_mode=='PER_SEGMENT':
        # The native engine needs finite base bounds; segment rows become the
        # sole effective length/DLS constraints in this mode.
        default_bounds={'dls_deg_per_30m':[0.,segment_max_dls],'start_toolface_deg':[-c.max_toolface_change_deg,c.max_toolface_change_deg],'length_m':[1e-6,1e9]}
    cfg={
      'trajectory_mode':trajectory_mode,'arc_segment_model':'minimum_curvature','n_segments':n_segments,
      'start':{'position_m':[start.north,start.east,start.tvd],'inclination_deg':start.inclination,'azimuth_deg':start.azimuth},
      'target':{'position_m':[target.north,target.east,target.tvd],'inclination_deg':target_inclination if target_inclination is not None else start.inclination,'azimuth_deg':target_azimuth if target_azimuth is not None else start.azimuth,'position_mode':'box' if target.kind.upper() in ('BOX','RECTANGLE') else 'sphere','position_tolerance_m':[target.tolerance_north,target.tolerance_east,target.tolerance_tvd] if target.kind.upper() in ('BOX','RECTANGLE') else c.target_tolerance_m,'inclination_tolerance_deg':options.get('target_inclination_tolerance_deg',179.9 if target_inclination is None else .5),'azimuth_tolerance_deg':options.get('target_azimuth_tolerance_deg',180. if target_azimuth is None else .5)},
      'constraint_mode':c.constraint_mode,'default_segment_bounds':default_bounds,
      'segment_bounds':segment_bounds or [],'inclination_bounds_deg':options.get('inclination_bounds_deg',[.000001,179.999999]),'max_segment_bend_deg':options.get('max_segment_bend_deg',150.),'max_dls_deg_per_30m':segment_max_dls if c.constraint_mode=='PER_SEGMENT' else c.max_dogleg_deg_per_30m,'collision_cells_per_arc':options.get('collision_cells_per_arc',12),'planned_well_radius_m':0.,'output_sample_step_m':c.scan_step,
      'solver':{'random_seed':random_seed,'initial_search_attempts':6,'optimization_starts':1,'feasibility_max_nfev':200,'max_function_evaluations':5000,'max_seconds_per_run':30,**options.get('solver',{}),'maxiter':max_iterations},
      'iscwsa':{'sf_threshold':c.minimum_sf,'sf_method':c.error_model.sf_method,'k_sigma':c.error_model.k,'sigma_pa_m':c.error_model.sigma_pa_m,'surface_margin_m':c.error_model.radii_margin_m,'scan_stations_per_arc':5,'offset_scan_step_m':c.scan_step,'validation_scan_factor':4,'independent_wells':True,'centerline_min_distance_m':options.get('centerline_min_distance_m',0.),'centerline_red_distance_m':5.,'planned':planned,'neighbors':[{'name':f'neighbor-{i+1}','survey':_survey(s,neighbor_models[i].resolved())} for i,s in enumerate(obstacles)]}
    }
    # Supplied engine resolves 'planar_arc', not a separate numerical method.
    cfg.pop('arc_segment_model',None)
    t=time.perf_counter();out=optimize_trajectory(cfg,algorithm=solver);controls=out['controls'];report=out['report'];trajectory=[];segments=[];check={}
    if controls is not None:
        problem=PlanningProblem(cfg);z=out['normalized_controls'];check=problem.check(z,dense=True);data=samples(problem,z,c.scan_step)
        for row in data:
            trajectory.append({'segment':int(row[0]),'md':float(row[2]+start.md),'north':float(row[3]),'east':float(row[4]),'tvd':float(row[5]),'inclination':float(row[6]),'azimuth':float(row[7]),'toolface':float(row[8]),'dogleg_deg_per_30m':float(row[9])})
        md=start.md
        for i,(dls,tf,length) in enumerate(controls):
            md+=float(length);last=next(row for row in reversed(trajectory) if row['segment']==i+1)
            # Native geometry supplies toolface. Guidance ratio is an explicitly
            # configured DLS capacity rule, not a claimed actuator measurement.
            slide=float(np.clip(dls/max(c.max_dogleg_deg_per_30m,1e-12),0,1))
            segments.append({**last,'type':'直线' if problem.segment_types[i]=='straight' else '圆弧','segment':i+1,'segment_no':i+1,'start_md':md-float(length),'end_md':md,'segment_length':float(length),'length':float(length),'length_m':float(length),'dogleg':float(dls),'curvature':math.radians(dls)/30,'start_toolface_deg':float(tf),'toolface_deg':last['toolface'],'toolface':last['toolface'],'slide_ratio':slide,'sliding_ratio':slide,'composite_ratio':1-slide,'constraint_status':'PASS' if check.get('feasible') else 'FAIL','geometry_method':'minimum-curvature','guidance_basis':'DLS / configured max DLS; steering suggestion'})
    # Native samples include each segment's start and end. Adjacent segments
    # therefore share one MD; the survey/covariance contract requires unique,
    # increasing depths. Segment end records above retain every joint.
    trajectory=[row for i,row in enumerate(trajectory)
                if i==0 or row['md']>trajectory[i-1]['md']+1e-7]
    feasible=bool(check.get('feasible'));errors=[] if feasible else ['未得到通过密集校验的可行方案；不代表问题已证明无解']
    collision=None
    if trajectory and obstacles:
        planned_rows=[SurveyStation(**{k:float(r.get(k,0)) for k in SurveyStation.__dataclass_fields__}) for r in trajectory]
        combined=[s for s in (history or []) if s.md<start.md-1e-7]+planned_rows
        identities=obstacle_identities or [(i+1,None) for i in range(len(obstacles))]
        collision=scan_network(combined,[(identities[i][0] or i+1,identities[i][1],s) for i,s in enumerate(obstacles)],c.scan_step,c.error_model,reference_uncertainty=reference_uncertainty,neighbor_uncertainties=obstacle_uncertainties)
        collision['scope']='已钻历史与再规划方案；约束是否通过以求解器密集复核为准'
    return PlanningResult({'status':'SUCCESS' if feasible else 'INFEASIBLE','solver':solver,'total_length':float(np.sum(controls[:,2])) if controls is not None else None,'terminal_error':check.get('position_error_m'),'target_error':check.get('position_error_m'),'elapsed_seconds':time.perf_counter()-t,'iterations':sum(r.get('iterations',0) for r in report.get('runs',[])),'constraint_violations':errors,'trajectory':trajectory,'segments':segments,'collision_evaluation':collision,'solver_metadata':{'actual_solver':solver,'engine':'wellpath-engine-1.0.0','objective':'minimum_total_length','initialization':out['initialization'],'report':report,'configuration':cfg,'constraint_audit':check,'minimum_sf':check.get('minimum_sf')},'best_effort':None,'failure_reason':None if feasible else report.get('status')})
