const c=(key,label,unit='',width=112)=>({key,label:label+(unit?'\n'+unit:''),width})
export const basicColumns=[
  {label:'参考系统',children:[c('coordinate_system','大地坐标系'),c('north_reference','指北方向'),c('depth_datum','深度基准')]},
  {label:'井口坐标',children:[c('elevation','海拔高度','m'),c('north','纵坐标 X','m'),c('east','横坐标 Y','m')]},
  {label:'相关数据',children:[c('meridian_convergence','子午线收敛角','(°)'),c('magnetic_declination','磁偏角','(°)'),c('kelly_height','补心高度','m'),c('pocket_length','口袋长度','m')]}
]
export const targetColumns=[c('target_name','靶点名称','',150),{label:'靶点坐标',children:[c('elevation','海拔高度','m'),c('north','纵坐标 X','m'),c('east','横坐标 Y','m'),c('tvd','垂深','m'),c('closure_distance','水平位移','m'),c('closure_azimuth','平移方位','(°)')]},{label:'靶区几何',children:[c('shape','形状','',95),c('radius','半径','m'),c('height','高度','m'),c('width','宽度','m')]}]
export const trajectoryColumns=actual=>[c('md','井深','m'),c('inclination','井斜角','(°)'),c('azimuth','方位角','(°)'),c('tvd','垂深','m'),c('north','北坐标','m'),c('east','东坐标','m'),c('closure_distance','水平位移','m'),c('closure_azimuth','平移方位','(°)'),c(actual?'apparent_horizontal_length':'horizontal_length',actual?'视平长':'水平长度','m'),c('dogleg','井眼曲率','(°)/30m'),c('remark','备注','',170)]
export const targetPointColumns=[c('target_no','靶点名称','',150),...trajectoryColumns(false).slice(0,4),c('closure_distance','水平位移','m'),c('closure_azimuth','平移方位','(°)'),c('offset','靶心距','m'),c('longitudinal_offset','纵偏','m'),c('lateral_offset','横偏','m'),c('remark','备注','',180)]
export const appendixNotes={targetData:['注：靶区几何中的几何尺寸数据应依靶区形状填写。'],designNodes:['注：备注中宜注明井口点、造斜点、靶点等主要节点。'],designDetails:['注：宜包含输出井段内的节点，并在备注中注明。'],surveyData:['注：如有插入点，应在备注中注明。'],targetPoints:['注 1：术语纵偏和横偏宜随靶平面的摆放姿态而变化；如水平靶，宜用北偏和东偏。','注 2：备注中宜列出中靶或脱靶等信息。']}
export function sortMd(rows=[]){return [...rows].sort((a,b)=>Number(a.md)-Number(b.md))}
// Integrate the horizontal speed of the same spherical tangent interpolation
// used by minimum curvature; trapezoidal sin(INC) is inaccurate on long arcs.
export function horizontalSegmentLength(a,b){
 const length=Number(b.md)-Number(a.md);if(!(length>0))return 0
 const tangent=p=>{const i=Number(p.inclination)*Math.PI/180,z=Number(p.azimuth)*Math.PI/180;return [Math.sin(i)*Math.cos(z),Math.sin(i)*Math.sin(z),Math.cos(i)]}
 const u=tangent(a),v=tangent(b),angle=Math.acos(Math.max(-1,Math.min(1,u.reduce((sum,x,i)=>sum+x*v[i],0))))
 if(angle<1e-10)return length*Math.hypot(u[0],u[1])
 const denominator=Math.sin(angle);if(Math.abs(denominator)<1e-12)return NaN
 const speed=q=>{const x=Math.sin((1-q)*angle)/denominator,y=Math.sin(q*angle)/denominator;return Math.hypot(x*u[0]+y*v[0],x*u[1]+y*v[1])}
 const recurse=(left,right,fa,fm,fb,area,tolerance,depth)=>{const middle=(left+right)/2,fl=speed((left+middle)/2),fr=speed((middle+right)/2),l=(middle-left)*(fa+4*fl+fm)/6,r=(right-middle)*(fm+4*fr+fb)/6,delta=l+r-area;if(depth===0||Math.abs(delta)<15*tolerance)return l+r+delta/15;return recurse(left,middle,fa,fl,fm,l,tolerance/2,depth-1)+recurse(middle,right,fm,fr,fb,r,tolerance/2,depth-1)}
 const fa=speed(0),fm=speed(.5),fb=speed(1)
 return length*recurse(0,1,fa,fm,fb,(fa+4*fm+fb)/6,1e-9,12)
}
export function standardRows(rows=[],reference={},sourceOffsets=false){
  const n0=Number(reference.wellhead_north??reference.source_reference?.wellhead_north??reference.origin_north??0),e0=Number(reference.wellhead_east??reference.source_reference?.wellhead_east??reference.origin_east??0)
  let h=0,previous
  return sortMd(rows).map(row=>{
    let extra={};try{extra=typeof row.raw_json==='string'?JSON.parse(row.raw_json):row.raw_json||{}}catch{}
    const r={...extra,...row}; const n=Number(r.north),e=Number(r.east)
    if(!sourceOffsets){if(r.curvature!=null)r.dogleg=Number(r.curvature)*180/Math.PI*30;if(r.north!=null)r.north=n-n0;if(r.east!=null)r.east=e-e0}
    if(r.north!=null&&r.east!=null){r.closure_distance??=Math.hypot(Number(r.north),Number(r.east));r.closure_azimuth??=(Math.atan2(Number(r.east),Number(r.north))*180/Math.PI+360)%360}
    if(previous)h+=horizontalSegmentLength(previous,r)
    r.horizontal_length??=h;previous=r
    return r
  })
}
