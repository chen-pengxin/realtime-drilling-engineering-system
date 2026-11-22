package com.ruoyi.web.controller.drilling;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.framework.security.DrillingDataScope;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Appendix A is a data view of the selected immutable version and source tables. */
@Service
public class EngineeringTableService {
  private final JdbcTemplate db;
  public EngineeringTableService(SourceRegistrySupport schema){db=schema.jdbc;}
  private static Map<String,Object> obj(Object x){return SourceRegistryController.obj(x);}
  private static Long id(Object x){return SourceRegistryController.id(x);}
  private Map<String,Object> bore(Long field,Long bore,boolean write){
    if(write)DrillingDataScope.current().requireRole(field,"OWNER","ENGINEER");else DrillingDataScope.current().requireAccess(field);
    var rows=db.queryForList("SELECT b.*,w.field_id AS owner_field_id,w.well_name,w.wellhead_n,w.wellhead_e,w.wellhead_tvd FROM drilling_wellbore b JOIN drilling_well w ON w.well_id=b.well_id JOIN drilling_field_wellbore x ON x.wellbore_id=b.wellbore_id WHERE x.field_id=? AND b.wellbore_id=? AND w.status<>'ARCHIVED'",field,bore);
    if(rows.isEmpty())throw new IllegalArgumentException("井眼不存在或已删除");
    if(write)DrillingDataScope.current().requireRole(id(rows.get(0).get("owner_field_id")),"OWNER","ENGINEER");
    return rows.get(0);
  }
  public Map<String,Object> data(Long field,Long boreId,Long versionId){
    var b=bore(field,boreId,false);var basic=new LinkedHashMap<String,Object>();
    var sources=db.queryForList("SELECT DISTINCT s.source_id FROM drilling_source_document s JOIN drilling_engineering_document_file df ON df.source_id=s.source_id JOIN drilling_engineering_document d ON d.document_id=df.document_id JOIN drilling_engineering_node n ON n.node_id=d.node_id WHERE n.well_id=? AND (n.wellbore_id IS NULL OR n.wellbore_id=?) AND n.status='ACTIVE' AND df.usage_state NOT IN ('DELETED','FORBIDDEN','WITHDRAWN') ORDER BY s.source_id",b.get("well_id"),boreId);
    for(var source:sources){var values=obj(db.queryForObject("SELECT JSON_EXTRACT(metadata_json,'$.engineering_tables.basic') FROM drilling_source_document WHERE source_id=?",String.class,source.get("source_id")));basic.putAll(values);}
    Long basicVersion=versionId;
    if(basicVersion==null){var heads=db.queryForList("SELECT survey_version_id FROM drilling_trajectory_head WHERE wellbore_id=? ORDER BY (trajectory_kind='ACTUAL') DESC LIMIT 1",boreId);if(!heads.isEmpty())basicVersion=id(heads.get(0).get("survey_version_id"));}
    if(basicVersion!=null){var versionRef=obj(db.queryForObject("SELECT reference_json FROM drilling_trajectory_version WHERE survey_version_id=? AND wellbore_id=?",String.class,basicVersion,boreId));
      if(versionRef.get("north_reference")!=null)basic.put("north_reference",versionRef.get("north_reference"));
      Object coordinate=versionRef.getOrDefault("crs",versionRef.get("coordinate_system"));if(coordinate!=null)basic.putIfAbsent("coordinate_system",coordinate);
      Object datum=versionRef.get("depth_datum");if(datum!=null&&!Set.of("未确认","UNKNOWN","未注明").contains(datum.toString()))basic.putIfAbsent("depth_datum",datum);
    }
    var reference=obj(b.get("reference_json"));basic.putAll(obj(reference.get("engineering_basic")));
    var result=new LinkedHashMap<String,Object>();result.put("basic",basic);result.put("well_name",b.get("well_name"));
    result.put("drilled_md",db.queryForObject("SELECT COALESCE(MAX(s.max_md),0) FROM drilling_trajectory_head h JOIN drilling_survey_version s ON s.survey_version_id=h.survey_version_id WHERE h.wellbore_id=? AND h.trajectory_kind='ACTUAL'",Double.class,boreId));
    if(versionId!=null){
      var version=db.queryForList("SELECT t.*,s.source_file_id FROM drilling_trajectory_version t JOIN drilling_survey_version s ON s.survey_version_id=t.survey_version_id WHERE t.survey_version_id=? AND t.wellbore_id=?",versionId,boreId);
      if(version.isEmpty())throw new IllegalArgumentException("版本不属于当前井眼");
      var v=version.get(0);var savedRef=obj(v.get("reference_json"));
      var source=db.queryForList("SELECT JSON_EXTRACT(metadata_json,'$.engineering_tables') AS engineering_tables FROM drilling_source_document WHERE source_id=(SELECT MAX(source_id) FROM drilling_source_document WHERE file_id=?)",v.get("source_file_id"));
      var tables=source.isEmpty()?Map.<String,Object>of():obj(source.get(0).get("engineering_tables"));
      result.put("design_nodes",savedRef.containsKey("design_nodes")?savedRef.get("design_nodes"):tables.getOrDefault("design_nodes",List.of()));
      result.put("nodes_are_offsets",savedRef.getOrDefault("design_nodes_are_offsets",true));
      result.put("design_details",tables.getOrDefault("design_details",List.of()));
      result.put("survey",tables.getOrDefault("survey",List.of()));
      result.put("locators",tables.getOrDefault("locators",Map.of()));
      result.put("table_role",savedRef.getOrDefault("engineering_table_role","SOURCE"));
      result.put("version_id",versionId);
    }
    return result;
  }
  @Transactional public void saveBasic(Long field,Long boreId,Map<String,Object> body){
    var b=bore(field,boreId,true);var ref=obj(b.get("reference_json"));var clean=new LinkedHashMap<String,Object>();
    for(String key:List.of("coordinate_system","north_reference","depth_datum","elevation","north","east","meridian_convergence","magnetic_declination","kelly_height","pocket_length")){
      Object value=body.get(key);if(value==null||Objects.toString(value).isBlank())continue;
      if(!Set.of("coordinate_system","north_reference","depth_datum").contains(key)){
        double number;try{number=Double.parseDouble(value.toString());}catch(Exception e){throw new IllegalArgumentException("基础数据必须填写有效数值");}
        if(!Double.isFinite(number))throw new IllegalArgumentException("基础数据必须填写有限数值");value=number;
      }
      clean.put(key,value);
    }
    ref.put("engineering_basic",clean);
    db.update("UPDATE drilling_wellbore SET reference_json=?,update_time=NOW() WHERE wellbore_id=?",JSON.toJSONString(ref),boreId);
    db.update("INSERT INTO drilling_audit_log(field_id,action,resource_type,resource_id,detail_json,user_id) VALUES(?,'UPDATE_BASIC_DATA','WELLBORE',?,?,?)",field,boreId,JSON.toJSONString(clean),ShiroUtils.getUserId());
  }
  /** Materialize only explicit source targets; no synthetic actual hitting records. */
  public void importTargets(Long field,Long boreId,Map<String,Object> metadata){
    var b=bore(field,boreId,false);var tables=obj(metadata.get("engineering_tables"));
    if(!(tables.get("targets") instanceof List<?> values))return;
    for(Object value:values){var row=obj(value);String name=Objects.toString(row.get("name"),"");if(name.isBlank())continue;
      if(db.queryForObject("SELECT COUNT(*) FROM drilling_target WHERE field_id=? AND well_id=? AND target_name=?",Integer.class,field,b.get("well_id"),name)>0)continue;
      db.update("INSERT INTO drilling_target(field_id,well_id,target_name,target_type,geometry_json,tolerance_json,status,create_by) VALUES(?,?,?,'POINT',?,'{}','ACTIVE',?)",field,b.get("well_id"),name,JSON.toJSONString(row),ShiroUtils.getLoginName());
    }
  }
  /** Explicit observations from an actual hitting table, never design geometry. */
  public void importActualTargets(Long field,Long bore,Long version,Map<String,Object> metadata){
    var rows=obj(metadata.get("engineering_tables")).get("target_points");if(!(rows instanceof List<?> points))return;
    for(Object item:points){var p=obj(item);if(p.get("target_no")==null||p.get("md")==null)continue;
      var metrics=new LinkedHashMap<String,Object>();for(String k:List.of("closure_distance","closure_azimuth","offset","longitudinal_offset","lateral_offset"))if(p.get(k)!=null)metrics.put(k,p.get(k));
      db.update("INSERT IGNORE INTO drilling_trajectory_target_point(field_id,wellbore_id,survey_version_id,target_no,md,tvd,inclination,azimuth,remark,metrics_json,status,create_by) VALUES(?,?,?,?,?,?,?,?,?,?,'ACTIVE',?)",field,bore,version,p.get("target_no"),p.get("md"),p.get("tvd"),p.get("inclination"),p.get("azimuth"),p.get("remark"),JSON.toJSONString(metrics),ShiroUtils.getLoginName());
    }
  }

}
