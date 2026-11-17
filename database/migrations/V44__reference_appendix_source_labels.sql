-- Keep the scanned standard PDF authoritative while making every Appendix A
-- heading, unit and printed note searchable in the reference-material view.
UPDATE drilling_reference_material
SET appendix_tables_json = JSON_ARRAY(
  JSON_OBJECT('code','A.1','title','井基本数据表','source_title','Preliminary data for Well × × ×','pdf_page',50,
    'source_fields',JSON_ARRAY('Geodetic coordinate system','North reference','Depth datum','ASL (m)','Vertical coordinate X (m)','Horizontal coordinate Y (m)','Grid convergence (°)','Magnetic declination (°)','Rotary Kelly Bushing (RKB) (m)','Pocket length (m)'),
    'fields',JSON_ARRAY('地理坐标系','北向基准','深度基准','海拔','井口纵坐标 X','井口横坐标 Y','子午线收敛角','磁偏角','转盘补心高程','鼠洞长度'),'source_notes',JSON_ARRAY()),
  JSON_OBJECT('code','A.2','title','靶区数据表','source_title','Target area data for Well × × ×','pdf_page',50,
    'source_fields',JSON_ARRAY('Target name','ASL (m)','Vertical coordinate X (m)','Horizontal coordinate Y (m)','TVD (m)','Closure distance (m)','Closure azimuth (°)','Shape','Radius (m)','Height (m)','Width (m)'),
    'fields',JSON_ARRAY('靶点名称','海拔','垂直坐标 X','水平坐标 Y','垂深 TVD','闭合距','闭合方位','形状','半径','高度','宽度'),
    'source_notes',JSON_ARRAY('The geometrical dimensions in Target geometry section shall be filled out as per target shape.')),
  JSON_OBJECT('code','A.3','title','设计轨道节点数据表','source_title','Node points data of planned wellpath for Well × × ×','pdf_page',50,
    'source_fields',JSON_ARRAY('MD (m)','Inc (°)','Azi (°)','TVD (m)','N/S (m)','E/W (m)','Closure distance (m)','Closure azimuth (°)','Apparent horizontal length (m)','Wellbore curvature (°)/30m','Remarks'),
    'fields',JSON_ARRAY('MD','井斜角','方位角','TVD','北位移 N/S','东位移 E/W','闭合距','闭合方位','水平投影长度','井眼曲率','备注'),
    'source_notes',JSON_ARRAY('The node points shall include the wellhead, kick-off point and target points and shall be noted in Remarks column.')),
  JSON_OBJECT('code','A.4','title','设计轨道插值点数据表','source_title','Interpolated points data of planned wellpath for Well × × ×','pdf_page',51,
    'source_fields',JSON_ARRAY('MD (m)','Inc (°)','Azi (°)','TVD (m)','N/S (m)','E/W (m)','Closure distance (m)','Closure azimuth (°)','Apparent horizontal length (m)','Wellbore curvature (°)/30m','Remarks'),
    'fields',JSON_ARRAY('MD','井斜角','方位角','TVD','北位移 N/S','东位移 E/W','闭合距','闭合方位','水平投影长度','井眼曲率','备注'),
    'source_notes',JSON_ARRAY('Node points within the output hole section shall be included and noted in the Remarks column.')),
  JSON_OBJECT('code','A.5','title','实钻轨迹测斜数据表','source_title','Survey data of drilled trajectory for Well × × ×','pdf_page',51,
    'source_fields',JSON_ARRAY('Calculation method','MD (m)','Inc (°)','Azi (°)','TVD (m)','N/S (m)','E/W (m)','Closure distance (m)','Closure azimuth (°)','Apparent horizontal length (m)','Wellbore curvature (°)/30m','Remarks'),
    'fields',JSON_ARRAY('计算方法','MD','井斜角','方位角','TVD','北位移 N/S','东位移 E/W','闭合距','闭合方位','水平投影长度','井眼曲率','备注'),
    'source_notes',JSON_ARRAY('Interpolated points, if any, shall be noted in the Remarks column.')),
  JSON_OBJECT('code','A.6','title','实钻轨迹靶点数据表','source_title','Target entry data of drilled trajectory for Well × × ×','pdf_page',51,
    'source_fields',JSON_ARRAY('Target name','MD (m)','Inc (°)','Azi (°)','TVD (m)','Closure distance (m)','Closure azimuth (°)','Offset distance (m)','Longitudinal offset distance (m)','Latitudinal offset distance (m)','Remarks'),
    'fields',JSON_ARRAY('靶点名称','MD','井斜角','方位角','TVD','闭合距','闭合方位','偏移距','纵向偏移距','横向偏移距','备注'),
    'source_notes',JSON_ARRAY('The two terms Longitudinal Offset Distance and Latitudinal Offset Distance may be changed in accordance with the orientation of target plane. For instance, northward offset and eastward offset should be used for a horizontal target.','Information of hitting a target or missing a target should be noted in the Remarks column.'))
)
WHERE material_code='SY-T-5435-2012';

INSERT IGNORE INTO drilling_schema_version
  (version_code,script_name,status,applied_by,notes)
VALUES
  ('V44','V44__reference_appendix_source_labels.sql','APPLIED','migration','补充 SY/T 5435-2012 附录 A 原文表头、单位和注释');
