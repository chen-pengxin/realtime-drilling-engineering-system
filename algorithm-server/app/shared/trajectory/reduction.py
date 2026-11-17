"""SY/T 5435-2012 §4, §10.5 and A.4/A.5 output around native MC geometry."""
import math
import numpy as np
from scipy.integrate import quad
from scipy.optimize import minimize_scalar
from .min_curvature import compute_minimum_curvature,interpolate_segment

def grid_azimuth(azimuth,north_reference='GRID_NORTH',magnetic_declination=0.,meridian_convergence=0.):
    if north_reference not in ('GRID_NORTH','TRUE_NORTH','MAGNETIC_NORTH'):raise ValueError('必须明确输入指北方向')
    correction=0 if north_reference=='GRID_NORTH' else -meridian_convergence+(magnetic_declination if north_reference=='MAGNETIC_NORTH' else 0)
    return (float(azimuth)+correction)%360

def reduce_trajectory(rows,reference,*,target_reference=None,preserve_stored=False):
    source=dict(reference);unit=source.get('length_unit','m');angle=source.get('angle_unit','deg')
    if unit not in ('m','ft') or angle not in ('deg','rad'):raise ValueError('不支持的单位')
    scale=.3048 if unit=='ft' else 1.;ascale=180/math.pi if angle=='rad' else 1.
    normalized=[{**r,'md':float(r['md'])*scale,'inclination':float(r['inclination'])*ascale,'azimuth':grid_azimuth(float(r['azimuth'])*ascale,source.get('north_reference','GRID_NORTH'),float(source.get('magnetic_declination',0)),float(source.get('meridian_convergence',0)))} for r in rows]
    origin=tuple(float(source.get(k,0))*scale for k in ('origin_north','origin_east','origin_tvd'))
    st=compute_minimum_curvature(normalized,origin)
    if preserve_stored:
        from dataclasses import replace
        if unit!='m' or angle!='deg' or source.get('north_reference','GRID_NORTH')!='GRID_NORTH':raise ValueError('仅规范网格北米制版本可复用已归算坐标')
        if any(not all(k in r and math.isfinite(float(r[k])) for k in ('north','east','tvd')) for r in rows):raise ValueError('已归算坐标不完整')
        st=[replace(s,north=float(r['north']),east=float(r['east']),tvd=float(r['tvd'])) for s,r in zip(st,rows)]
    if target_reference:
        # Cross-reference transforms require declared CRS and elevation of
        # the depth datum. LOCAL/unknown coordinates cannot be mixed silently.
        src=source.get('crs') or None;dst=target_reference.get('crs') or None
        # A field using one declared local frame may leave CRS empty. In
        # that case the stored coordinates are already in the common frame;
        # cross-frame transforms still require both CRS and datum elevations.
        if not src and not dst:
            src=dst=None
            # Existing confirmed local data remains valid, but two explicitly
            # different local grids must not be treated as one by omission.
            for key in ('local_frame_id','coordinate_system','coordinate_system_text'):
                a,b=source.get(key),target_reference.get(key)
                if a and b and str(a).strip().casefold()!=str(b).strip().casefold():
                    raise ValueError('所选版本使用不同的局部坐标基准，请先明确可转换的CRS或统一局部坐标基准')
        elif not src or not dst: raise ValueError('跨井归算缺少明确源/目标CRS')
        if src is not None and (source.get('depth_datum_elevation_m') is None or target_reference.get('depth_datum_elevation_m') is None):raise ValueError('跨井归算缺少深度基准高程')
        from pyproj import Transformer
        transformer=Transformer.from_crs(src,dst,always_xy=True) if src!=dst else None
        from dataclasses import replace
        converted=[]
        for s in st:
            e,n=transformer.transform(s.east,s.north) if transformer else (s.east,s.north)
            source_height=source.get('depth_datum_elevation_m');target_height=target_reference.get('depth_datum_elevation_m')
            if (source_height is None or target_height is None) and source.get('depth_datum') and target_reference.get('depth_datum') and source['depth_datum']!=target_reference['depth_datum']:
                raise ValueError('不同深度基准之间缺少可归算的高程')
            d=s.tvd+(float(target_height)-float(source_height) if source_height is not None and target_height is not None else 0.)
            # Recompute azimuth in target grid from source true heading and
            # source/target meridian convergence; not from two noisy points.
            az=(s.azimuth+float(source.get('meridian_convergence',0))-float(target_reference.get('meridian_convergence',0)))%360
            converted.append(replace(s,east=float(e),north=float(n),tvd=d,azimuth=az))
        st=converted
    return st

def standard_table(stations,*,kind='ACTUAL',design=None,inserted_md=()):
    if not stations:return []
    origin=stations[0];horizontal=[0.]
    for a,b in zip(stations,stations[1:]):
        value=quad(lambda md:math.sin(math.radians(interpolate_segment(a,b,md).inclination)),a.md,b.md,epsabs=1e-5)[0]
        horizontal.append(horizontal[-1]+value)
    design_h=None
    if design:
        design_h=standard_table(design,kind='DESIGN')
    out=[]
    for i,s in enumerate(stations):
        n=s.north-origin.north;e=s.east-origin.east;apparent=None
        if design and len(design)>1:
            best=None
            for j,(a,b) in enumerate(zip(design,design[1:])):
                def dist(md):
                    p=interpolate_segment(a,b,md);return (p.north-s.north)**2+(p.east-s.east)**2
                opt=minimize_scalar(dist,bounds=(a.md,b.md),method='bounded',options={'xatol':1e-7});candidates=[(a.md,dist(a.md)),(b.md,dist(b.md)),(opt.x,opt.fun)];md,d=min(candidates,key=lambda x:x[1])
                if best is None or d<best[0]:
                    value=quad(lambda q:math.sin(math.radians(interpolate_segment(a,b,q).inclination)),a.md,md,epsabs=1e-5)[0]
                    best=(d,design_h[j]['horizontal_length']+value)
            apparent=best[1]
        out.append({**s.to_dict(),'horizontal_displacement':math.hypot(n,e),'closure_azimuth':math.degrees(math.atan2(e,n))%360 if n or e else None,'horizontal_length':horizontal[i],'apparent_horizontal_length':apparent,'dogleg_deg_per_30m':math.degrees(s.curvature)*30,'remark':'插入点' if s.md in inserted_md else ('井口/参考点' if i==0 else ''),'kind':kind})
    return out

COLUMNS=[('md','井深\nm'),('inclination','井斜角\n(°)'),('azimuth','方位角（网格）\n(°)'),('tvd','垂深\nm'),('north','北坐标\nm'),('east','东坐标\nm'),('horizontal_displacement','水平位移\nm'),('closure_azimuth','平移方位\n(°)'),('apparent_horizontal_length','视平长\nm'),('dogleg_deg_per_30m','井眼曲率\n(°)/30m'),('remark','备注')]

def export_standard_xlsx(stations,name='井眼',kind='ACTUAL',design=None):
    from openpyxl import Workbook
    from openpyxl.styles import Alignment,Font,Border,Side
    import io
    wb=Workbook();ws=wb.active;ws.title='轨道归算';ws.append([f'{name} '+('设计轨道分点数据（A.4）' if kind=='DESIGN' else '实钻轨迹测斜数据（A.5）')]);ws.merge_cells('A1:K1');ws.append(['计算方法：最小曲率法；指北方向：网格北']);ws.merge_cells('A2:K2')
    cols=[(k if k!='apparent_horizontal_length' or kind!='DESIGN' else 'horizontal_length',v if k!='apparent_horizontal_length' or kind!='DESIGN' else '水平长度\nm') for k,v in COLUMNS];ws.append([v for k,v in cols])
    for r in standard_table(stations,kind=kind,design=design):ws.append([r.get(k) for k,v in cols])
    for row in ws:
        for c in row:
            c.alignment=Alignment(horizontal='center',vertical='center',wrap_text=True);c.font=Font(name='宋体',size=11,bold=c.row in (1,3));c.border=Border(*( [Side(style='thin',color='999999')]*4))
            if isinstance(c.value,(int,float)):c.number_format='0.000'
    for col in 'ABCDEFGHIJK':ws.column_dimensions[col].width=16
    ws.row_dimensions[1].height=30;ws.row_dimensions[3].height=38;ws.freeze_panes='A4';ws.print_title_rows='1:3';ws.sheet_properties.pageSetUpPr.fitToPage=True;ws.page_setup.orientation='landscape';ws.page_setup.paperSize=ws.PAPERSIZE_A4;ws.page_setup.fitToWidth=1;ws.page_setup.fitToHeight=0
    out=io.BytesIO();wb.save(out);return out.getvalue()
