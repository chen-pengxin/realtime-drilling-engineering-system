"""Application records around the supplied wellpath minimum-curvature kernel.

Coordinates are N/E/TVD positive down, azimuth clockwise from PROJECT GRID NORTH.
No numerical trajectory implementation is maintained in this application.
"""
from dataclasses import dataclass, asdict
import math
import numpy as np
from wellpath.geometry.arc import tangent, minimum_curvature_increment as _increment
from wellpath.geometry.minimum_curvature import interpolate_minimum_curvature

@dataclass(frozen=True)
class SurveyStation:
    md: float
    inclination: float
    azimuth: float
    north: float = 0.0
    east: float = 0.0
    tvd: float = 0.0
    dogleg: float = 0.0
    curvature: float = 0.0
    def to_dict(self): return asdict(self)

_direction = tangent

def minimum_curvature_increment(md_delta, inclination_1, azimuth_1, inclination_2, azimuth_2):
    if not math.isfinite(md_delta) or md_delta < 0: raise ValueError('段长必须是非负有限数')
    a,b=tangent(inclination_1,azimuth_1),tangent(inclination_2,azimuth_2)
    angle=float(np.arctan2(np.linalg.norm(np.cross(a,b)),np.dot(a,b)))
    return _increment(a,b,md_delta), math.degrees(angle), angle/md_delta if md_delta else 0.0

def compute_minimum_curvature(rows, origin=(0.,0.,0.)):
    records=[]
    for row in rows:
        md,inc,azi=(float(row['md']),float(row.get('inclination',row.get('inc'))),float(row.get('azimuth',row.get('azi')))) if isinstance(row,dict) else map(float,row[:3])
        if not all(map(math.isfinite,(md,inc,azi))) or md<0 or not 0<=inc<=180: raise ValueError('测点必须为有限数，MD≥0、井斜0～180°')
        if records and md<=records[-1][0]: raise ValueError('MD values must be strictly increasing')
        records.append((md,inc,azi%360))
    if not records:return []
    p=np.asarray(origin,dtype=float)
    if p.shape!=(3,) or not np.isfinite(p).all():raise ValueError('井口/首测点N/E/TVD必须为有限数')
    out=[SurveyStation(*records[0],*p)]
    for a,b in zip(records,records[1:]):
        delta,dogleg,k=minimum_curvature_increment(b[0]-a[0],a[1],a[2],b[1],b[2]);p=p+delta
        out.append(SurveyStation(*b,*p,dogleg,k))
    return out

def interpolate_segment(a,b,md):
    p,i,z=interpolate_minimum_curvature([a.md,b.md],[a.inclination,b.inclination],[a.azimuth,b.azimuth],[[a.north,a.east,a.tvd],[b.north,b.east,b.tvd]],[md])
    _,d,k=minimum_curvature_increment(b.md-a.md,a.inclination,a.azimuth,b.inclination,b.azimuth)
    return SurveyStation(float(md),float(i[0]),float(z[0]),*map(float,p[0]),d,k)

def resample_trajectory(stations,step=1.):
    if not math.isfinite(step) or step<=0:raise ValueError('扫描步长必须为正有限数')
    if len(stations)<2:return list(stations)
    out=[stations[0]]
    for a,b in zip(stations,stations[1:]):
        count=max(1,math.ceil((b.md-a.md)/step))
        out.extend(interpolate_segment(a,b,a.md+(b.md-a.md)*j/count) for j in range(1,count+1))
    return out
