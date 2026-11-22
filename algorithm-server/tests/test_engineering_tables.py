"""Regression tests for separating source appendix records without inventing data."""
from app.adapters.engineering_tables import engineering_tables

def test_nodes_and_details_remain_separate_and_targets_use_explicit_wellhead():
    headers=['测深','井斜','网格方位','垂深','北坐标','东坐标','全角变化率']
    document={'blocks':[
        {'type':'TABLE','semantic_type':'TARGETS','locator':'table:1','rows':[['控制点','纵坐标X','横坐标Y','海拔H'],['井口',4200000,19400000,1107.8],['A',4200100,19400050,-1400]]},
        {'type':'TABLE','semantic_type':'DESIGN_NODES','locator':'table:2','rows':[headers,[0,0,0,0,0,0,0],[100,10,90,99,0,8.7,3]]},
        {'type':'TABLE','semantic_type':'DESIGN_STATIONS','locator':'table:3','rows':[headers,[0,0,0,0,0,0,0],[50,5,90,49.9,0,2.2,3],[100,10,90,99,0,8.7,3]]}]}
    data=engineering_tables(document,'地面海拔：1100\n补心距：7.8',{})
    assert len(data['design_nodes'])==2 and len(data['design_details'])==3
    assert data['design_nodes'][1]['is_key_node']
    assert data['basic']['north']==4200000 and data['basic']['kelly_height']==7.8
    assert data['targets'][0]['tvd']==2507.8
    assert data['target_points']==[]  # Targets are not observations of an actual hit.

def test_compass_targets_use_absolute_columns_and_actuals_use_offsets():
    data=engineering_tables({'blocks':[
      {'type':'TABLE','semantic_type':'TARGETS','records':[{'name':'T','offset_north':10,'offset_east':20,'northing':6500010,'easting':435020,'tvd':2000}]},
      {'type':'TABLE','semantic_type':'SURVEY_STATIONS','records':[{'md':100,'inclination':1,'azimuth':90,'offset_north':10,'offset_east':20,'vertical_section':18}]}]},'',{})
    assert data['targets'][0]['north']==6500010
    assert data['survey'][0]['north']==10
    assert data['survey'][0]['apparent_horizontal_length']==18

def test_unknown_values_are_not_zero_and_templates_do_not_create_records():
    data=engineering_tables({'blocks':[{'type':'TABLE','semantic_type':'TARGETS','template':True,'rows':[['控制点','纵坐标X','横坐标Y'],['A',10,20]]}]},'',{})
    assert data['basic']=={} and data['targets']==[] and data['target_points']==[]

def test_merged_basic_cells_and_west_declination_are_read_with_their_sign():
    document={'blocks':[{'type':'TABLE','rows':[['地面海拔','','1150.902m'],['补心距','','7.8m'],['磁偏角','','(西偏)3°41']]}]}
    data=engineering_tables(document,'',{})
    assert data['basic']['elevation']==1150.902 and data['basic']['kelly_height']==7.8
    assert abs(data['basic']['magnetic_declination']+3+41/60)<1e-10
    assert data['basic']['depth_datum_elevation_m']==1158.702
