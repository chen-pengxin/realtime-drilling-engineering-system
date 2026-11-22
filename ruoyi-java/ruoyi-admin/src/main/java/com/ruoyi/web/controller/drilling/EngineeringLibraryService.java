package com.ruoyi.web.controller.drilling;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.framework.security.DrillingDataScope;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Stream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Source hierarchy, document representations and explicit business revisions. */
@Service
public class EngineeringLibraryService {
  // Catalog entities are shown in the same library as documents. Negative
  // ids are synthetic tree ids and cannot collide with AUTO_INCREMENT nodes.
  private static final long BLOCK_SCOPE = -1_000_000_000_000L;
  private static final long FIELD_SCOPE = -2_000_000_000_000L;
  private static final String FILE_KIND_SQL="(CASE WHEN d.document_role IN ('TRAJECTORY_DATA','TRAJECTORY_REPORT') THEN COALESCE(NULLIF(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(df.properties_json,'$.trajectory_kind')),'null'),''),CASE n.node_type WHEN 'PLAN' THEN 'DESIGN' WHEN 'SURVEY' THEN 'ACTUAL' END,CASE r.phase WHEN 'PLAN' THEN 'DESIGN' WHEN 'ACTUAL' THEN 'ACTUAL' END) END)";
  final JdbcTemplate db;
  private final TrajectoryVersionSupport trajectories;
  public EngineeringLibraryService(SourceRegistrySupport schema, TrajectoryVersionSupport trajectories) {
    this.db=schema.jdbc; this.trajectories=trajectories;
  }
  @PostConstruct public void initialize() {
    db.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)c->{
      ScriptUtils.executeSqlScript(c,new ClassPathResource("drilling/engineering-library.sql"));return null;
    });
  }
  static Long id(Object v){return SourceRegistryController.id(v);}
  static Map<String,Object> obj(Object v){return SourceRegistryController.obj(v);}
  static String str(Map<String,Object>b,String k,String fallback){return Objects.toString(b.get(k),fallback).trim();}
  static String required(Map<String,Object>b,String k,int max){return SourceRegistryController.required(b,k,max);}
  private static long scopeId(long base,long id){return base-id;}
  private static boolean isScope(Long id){return id!=null&&id<0;}
  private static String scopeType(long id){
    if(id<=BLOCK_SCOPE-1 && id>FIELD_SCOPE) return "BLOCK";
    if(id<=FIELD_SCOPE-1) return "FIELD";
    return "";
  }
  private static long scopeValue(long id,long base){return -(id-base);}
  private Map<String,Object> scopeNode(Long fieldId,Long id){
    String type=scopeType(id); if(type.isEmpty()) return null;
    Map<String,Object> row=new LinkedHashMap<>();
    row.put("node_id",id); row.put("field_id",fieldId); row.put("status","ACTIVE");
    row.put("row_version",1); row.put("scope_type",type); row.put("node_type",type);
    row.put("parent_id",null); row.put("entity_kind","UNKNOWN");
    if("BLOCK".equals(type)){
      var rows=db.queryForList("SELECT block_id,block_code,block_name,basin,location,operator_name,structural_position,remark,source_reference,description,province,authorized_user,coordinate_system,wellhead_n,wellhead_e,magnetic_declination,meridian_convergence,construction_status,enabled FROM drilling_block WHERE block_id=? AND status='0'",scopeValue(id,BLOCK_SCOPE));
      if(rows.isEmpty()) throw new IllegalArgumentException("区块不存在"); var b=rows.get(0);
      row.put("block_id",b.get("block_id")); row.put("node_code",b.get("block_code")); row.put("node_name",b.get("block_name"));
      row.put("basin",b.get("basin")); row.put("location",b.get("location")); row.put("operator_name",b.get("operator_name"));
      row.put("structural_position",b.get("structural_position")); row.put("remark",b.get("remark")); row.put("source_reference",b.get("source_reference")); row.put("description",b.get("description")); row.put("province",b.get("province")); row.put("authorized_user",b.get("authorized_user")); row.put("coordinate_system",b.get("coordinate_system")); row.put("wellhead_n",b.get("wellhead_n")); row.put("wellhead_e",b.get("wellhead_e")); row.put("magnetic_declination",b.get("magnetic_declination")); row.put("meridian_convergence",b.get("meridian_convergence")); row.put("construction_status",b.get("construction_status")); row.put("enabled",b.get("enabled"));
    } else if("FIELD".equals(type)){
      var rows=db.queryForList("SELECT field_id,block_id,field_code,field_name,operator_name,location,structural_position,remark,source_reference,description,province,authorized_user,coordinate_system,wellhead_n,wellhead_e,magnetic_declination,meridian_convergence,construction_status,enabled FROM drilling_field WHERE field_id=? AND status='0'",scopeValue(id,FIELD_SCOPE));
      if(rows.isEmpty()) throw new IllegalArgumentException("井场不存在"); var f=rows.get(0);
      row.put("field_id",f.get("field_id")); row.put("block_id",f.get("block_id")); row.put("node_code",f.get("field_code")); row.put("node_name",f.get("field_name"));
      row.put("operator_name",f.get("operator_name")); row.put("location",f.get("location")); row.put("structural_position",f.get("structural_position"));
      row.put("remark",f.get("remark")); row.put("source_reference",f.get("source_reference")); row.put("description",f.get("description")); row.put("province",f.get("province")); row.put("authorized_user",f.get("authorized_user")); row.put("coordinate_system",f.get("coordinate_system")); row.put("wellhead_n",f.get("wellhead_n")); row.put("wellhead_e",f.get("wellhead_e")); row.put("magnetic_declination",f.get("magnetic_declination")); row.put("meridian_convergence",f.get("meridian_convergence")); row.put("construction_status",f.get("construction_status")); row.put("enabled",f.get("enabled"));
      row.put("parent_id",scopeId(BLOCK_SCOPE,((Number)f.get("block_id")).longValue()));
    } else return null;
    row.put("file_count",db.queryForObject("SELECT COUNT(*) FROM drilling_engineering_document d JOIN drilling_engineering_document_file df ON df.document_id=d.document_id JOIN drilling_engineering_node n ON n.node_id=d.node_id WHERE n.field_id=?",Integer.class,fieldId));
    return row;
  }
  void access(Long fieldId,boolean write){
    if(write)DrillingDataScope.current().requireRole(fieldId,"OWNER","ENGINEER");
    else DrillingDataScope.current().requireAccess(fieldId);
  }
  public Map<String,Object> node(Long id,boolean write){
    if(isScope(id)){
      var n=scopeNode(null,id); if(n==null)throw new IllegalArgumentException("工程节点不存在");
      return n;
    }
    var rows=db.queryForList("SELECT n.*,b.engineering_status,w.well_code,w.well_name,w.well_type,w.location AS well_location,w.operator_name AS well_operator_name,w.structural_position AS well_structural_position,w.remark AS well_remark,w.source_reference AS well_source_reference,w.wellhead_n,w.wellhead_e,w.wellhead_tvd,w.magnetic_declination,w.meridian_convergence,w.metadata_json AS well_metadata_json FROM drilling_engineering_node n LEFT JOIN drilling_wellbore b ON b.wellbore_id=n.wellbore_id LEFT JOIN drilling_well w ON w.well_id=n.well_id WHERE n.node_id=?",id);
    if(rows.isEmpty())throw new IllegalArgumentException("工程节点不存在");
    var n=rows.get(0);access(id(n.get("field_id")),write);return n;
  }
  public Map<String,Object> nodeInField(Long fieldId,Long nodeId,boolean write){
    if(!isScope(nodeId)){
      var n=node(nodeId,write);
      if(!fieldId.equals(id(n.get("field_id"))))throw new IllegalArgumentException("节点不属于所选井场");
      return n;
    }
    access(fieldId,write);
    var n=scopeNode(fieldId,nodeId);
    if(n==null)throw new IllegalArgumentException("工程节点不存在");
    var field=db.queryForMap("SELECT f.field_id,f.block_id FROM drilling_field f WHERE f.field_id=?",fieldId);
    boolean belongs=switch(scopeType(nodeId)){
      case "BLOCK" -> Objects.equals(id(field.get("block_id")),id(n.get("block_id")));
      case "FIELD" -> fieldId.equals(id(n.get("field_id")));
      default -> false;
    };
    if(!belongs)throw new IllegalArgumentException("目录节点不属于所选井场的工程层级");
    return n;
  }
  Map<String,Object> revision(Long id,boolean write){
    var rows=db.queryForList("SELECT * FROM drilling_engineering_revision WHERE revision_id=?",id);
    if(rows.isEmpty())throw new IllegalArgumentException("业务版本不存在");
    var r=rows.get(0);node(id(r.get("node_id")),write);return r;
  }
  void audit(Long p,String action,Object detail){
    db.update("INSERT INTO drilling_audit_log(field_id,user_id,action,resource_type,detail_json) VALUES(?,?,?,'ENGINEERING_LIBRARY',?)",p,ShiroUtils.getUserId(),action,JSON.toJSONString(detail));
  }
  public List<Map<String,Object>> tree(Long fieldId){
    access(fieldId,false);
    var context=db.queryForMap("SELECT f.field_id,f.block_id FROM drilling_field f WHERE f.field_id=?",fieldId);
    long blockId=((Number)context.get("block_id")).longValue();
    var rows=new ArrayList<Map<String,Object>>();
    rows.add(scopeNode(fieldId,scopeId(BLOCK_SCOPE,blockId)));
    rows.add(scopeNode(fieldId,scopeId(FIELD_SCOPE,fieldId)));
    var library=db.queryForList("SELECT n.*,b.engineering_status,w.well_code,w.well_name,w.well_type,w.location AS well_location,w.operator_name AS well_operator_name,w.structural_position AS well_structural_position,w.remark AS well_remark,w.source_reference AS well_source_reference,w.wellhead_n,w.wellhead_e,w.wellhead_tvd,w.magnetic_declination,w.meridian_convergence,w.metadata_json AS well_metadata_json,(SELECT COUNT(*) FROM drilling_engineering_document d JOIN drilling_engineering_document_file f ON f.document_id=d.document_id WHERE d.node_id=n.node_id) file_count FROM drilling_engineering_node n LEFT JOIN drilling_wellbore b ON b.wellbore_id=n.wellbore_id LEFT JOIN drilling_well w ON w.well_id=n.well_id WHERE n.status='ACTIVE' AND n.field_id=? ORDER BY n.node_id",fieldId);
    for(var row:library){
      row.put("properties",obj(row.remove("properties_json"))); row.put("well_metadata",obj(row.remove("well_metadata_json")));
      String type=Objects.toString(row.get("node_type"),"");
      if(Set.of("BLOCK","FIELD").contains(type))continue;
      Object parent=row.get("parent_id");
      if(parent==null || (parent instanceof Number n && n.longValue()<0))row.put("parent_id",scopeId(FIELD_SCOPE,fieldId));
      rows.add(row);
    }
    return rows;
  }
  public List<Map<String,Object>> catalogTree(){
    String allowed=DrillingDataScope.current().predicate("f.field_id");
    var allowedFields=db.queryForList("SELECT f.field_id,f.block_id FROM drilling_field f WHERE f.status='0' AND ("+allowed+") ORDER BY f.field_name");
    var rows=new ArrayList<Map<String,Object>>();
    boolean administrator=ShiroUtils.isAdmin(ShiroUtils.getUserId());
    if(allowedFields.isEmpty()&&!administrator)return rows;
    Set<Long> fieldIds=new LinkedHashSet<>(),blockIds=new LinkedHashSet<>();
    for(var f:allowedFields){fieldIds.add(id(f.get("field_id")));blockIds.add(id(f.get("block_id")));}
    if(administrator){
      db.queryForList("SELECT block_id FROM drilling_block WHERE status='0'",Long.class).forEach(blockIds::add);
      db.queryForList("SELECT field_id FROM drilling_field WHERE status='0'",Long.class).forEach(fieldIds::add);
    }
    Map<Long,Map<String,Object>> blocks=new LinkedHashMap<>(),fields=new LinkedHashMap<>();
    if(blockIds.isEmpty())return rows;
    String blockMarks=String.join(",",Collections.nCopies(blockIds.size(),"?"));
    for(var b:db.queryForList("SELECT block_id,block_code,block_name,basin,location,operator_name,structural_position,remark,source_reference,description,province,authorized_user,coordinate_system,wellhead_n,wellhead_e,magnetic_declination,meridian_convergence,construction_status,enabled FROM drilling_block WHERE status='0' AND block_id IN ("+blockMarks+") ORDER BY block_name",blockIds.toArray())){
      Long id=id(b.get("block_id"));var n=new LinkedHashMap<String,Object>();n.put("node_id",scopeId(BLOCK_SCOPE,id));n.put("field_id",null);n.put("parent_id",null);n.put("scope_type","BLOCK");n.put("node_type","BLOCK");n.put("node_code",b.get("block_code"));n.put("node_name",b.get("block_name"));n.put("block_id",id);n.put("basin",b.get("basin"));n.put("location",b.get("location"));n.put("operator_name",b.get("operator_name"));n.put("structural_position",b.get("structural_position"));n.put("remark",b.get("remark"));n.put("source_reference",b.get("source_reference"));n.put("description",b.get("description"));n.put("province",b.get("province"));n.put("authorized_user",b.get("authorized_user"));n.put("coordinate_system",b.get("coordinate_system"));n.put("wellhead_n",b.get("wellhead_n"));n.put("wellhead_e",b.get("wellhead_e"));n.put("magnetic_declination",b.get("magnetic_declination"));n.put("meridian_convergence",b.get("meridian_convergence"));n.put("construction_status",b.get("construction_status"));n.put("enabled",b.get("enabled"));n.put("status","ACTIVE");blocks.put(id,n);rows.add(n);
    }
    String allowedFieldMarks=fieldIds.isEmpty()?"NULL":String.join(",",Collections.nCopies(fieldIds.size(),"?"));
    for(var f:db.queryForList("SELECT f.field_id,f.block_id,f.field_code,f.field_name,f.operator_name,f.location,f.structural_position,f.remark,f.source_reference,f.description,f.province,f.authorized_user,f.coordinate_system,f.wellhead_n,f.wellhead_e,f.magnetic_declination,f.meridian_convergence,f.construction_status,f.enabled FROM drilling_field f WHERE f.status='0' AND f.field_id IN ("+allowedFieldMarks+") ORDER BY f.field_name",fieldIds.toArray()) ){
      Long id=id(f.get("field_id")),block=id(f.get("block_id"));var n=new LinkedHashMap<String,Object>();n.put("node_id",scopeId(FIELD_SCOPE,id));n.put("field_id",null);n.put("parent_id",scopeId(BLOCK_SCOPE,block));n.put("scope_type","FIELD");n.put("node_type","FIELD");n.put("node_code",f.get("field_code"));n.put("node_name",f.get("field_name"));n.put("field_id",id);n.put("block_id",block);n.put("operator_name",f.get("operator_name"));n.put("location",f.get("location"));n.put("structural_position",f.get("structural_position"));n.put("remark",f.get("remark"));n.put("source_reference",f.get("source_reference"));n.put("description",f.get("description"));n.put("province",f.get("province"));n.put("authorized_user",f.get("authorized_user"));n.put("coordinate_system",f.get("coordinate_system"));n.put("wellhead_n",f.get("wellhead_n"));n.put("wellhead_e",f.get("wellhead_e"));n.put("magnetic_declination",f.get("magnetic_declination"));n.put("meridian_convergence",f.get("meridian_convergence"));n.put("construction_status",f.get("construction_status"));n.put("enabled",f.get("enabled"));n.put("status","ACTIVE");fields.put(id,n);rows.add(n);
    }
    if(fieldIds.isEmpty())return rows;
    String fieldMarks=String.join(",",Collections.nCopies(fieldIds.size(),"?"));
    // Prefer the real owner's directory entry over an older neighbor alias.
    // The former field-id ordering could move a test well under Volve F merely
    // because its cross-field reference happened to have a smaller field id.
    var library=db.queryForList("SELECT n.*,b.engineering_status,w.status AS well_status,w.well_code,w.well_name,w.well_type,w.location AS well_location,w.operator_name AS well_operator_name,w.structural_position AS well_structural_position,w.remark AS well_remark,w.source_reference AS well_source_reference,w.wellhead_n,w.wellhead_e,w.wellhead_tvd,w.magnetic_declination,w.meridian_convergence,w.metadata_json AS well_metadata_json FROM drilling_engineering_node n LEFT JOIN drilling_wellbore b ON b.wellbore_id=n.wellbore_id LEFT JOIN drilling_well w ON w.well_id=n.well_id LEFT JOIN drilling_well_catalog wc ON wc.well_id=w.well_id WHERE n.status='ACTIVE' AND n.field_id IN ("+fieldMarks+") ORDER BY CASE WHEN n.field_id=COALESCE(wc.field_id,w.field_id) THEN 0 ELSE 1 END,n.field_id,n.node_id",fieldIds.toArray());
    Map<Long,Long> canonicalNodes=new HashMap<>();
    Map<String,Long> canonicalAssets=new HashMap<>();
    for(var item:library){
      String type=Objects.toString(item.get("node_type"),"");
      Object identity="WELL".equals(type)?item.get("well_id"):"WELLBORE".equals(type)?item.get("wellbore_id"):null;
      if(identity!=null){
        String key=type+":"+identity;
        canonicalNodes.put(id(item.get("node_id")),canonicalAssets.computeIfAbsent(key,k->id(item.get("node_id"))));
      }else canonicalNodes.put(id(item.get("node_id")),id(item.get("node_id")));
    }
    Set<Long> emitted=new HashSet<>();
    for(var row:library){
      row.put("properties",obj(row.remove("properties_json"))); row.put("well_metadata",obj(row.remove("well_metadata_json")));
      Long ownerField=id(row.get("field_id"));
      String type=Objects.toString(row.get("node_type"),"");
      if(Set.of("BLOCK","FIELD").contains(type))continue;
      if("WELL".equals(row.get("node_type"))){
        Long canonical=canonicalNodes.get(id(row.get("node_id")));
        if(!emitted.add(canonical)) continue;
        row.put("parent_id",scopeId(FIELD_SCOPE,ownerField));
      } else if("WELLBORE".equals(row.get("node_type"))){
        Long canonical=canonicalNodes.get(id(row.get("node_id")));
        if(!emitted.add(canonical)) continue;
        Long parent=id(row.get("parent_id"));
        row.put("parent_id",canonicalNodes.getOrDefault(parent,parent));
      } else if(row.get("parent_id")==null || (row.get("parent_id") instanceof Number n && n.longValue()<0)) {
        row.put("parent_id",scopeId(FIELD_SCOPE,ownerField));
      } else if(canonicalNodes.containsKey(id(row.get("parent_id")))) {
        row.put("parent_id",canonicalNodes.get(id(row.get("parent_id"))));
      } else if("INBOX".equals(type)) {
        row.put("parent_id",scopeId(FIELD_SCOPE,ownerField));
      }
      rows.add(row);
    }
    return rows;
  }
  public Map<String,Object> engineeringNode(Long nodeId){ return node(nodeId,false); }
  String descendants(){return "WITH RECURSIVE scope AS (SELECT node_id FROM drilling_engineering_node WHERE node_id=? UNION ALL SELECT n.node_id FROM drilling_engineering_node n JOIN scope s ON n.parent_id=s.node_id WHERE n.status='ACTIVE') ";}
  /** Resolve paths in one query for a whole page, including uploaded files. */
  private void decorateDocumentPaths(List<Map<String,Object>> rows){
    var ids=rows.stream().map(row->id(row.get("node_id"))).filter(Objects::nonNull).filter(x->x>0).distinct().toList();
    if(ids.isEmpty())return;
    String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
    var ancestors=db.queryForList("WITH RECURSIVE lineage AS ("
        + "SELECT node_id AS root_id,node_id,parent_id,field_id,node_name,0 AS depth FROM drilling_engineering_node WHERE node_id IN ("+marks+") "
        + "UNION ALL SELECT l.root_id,n.node_id,n.parent_id,n.field_id,n.node_name,l.depth+1 FROM drilling_engineering_node n JOIN lineage l ON n.node_id=l.parent_id WHERE l.depth<20) "
        + "SELECT l.*,f.field_name,b.block_name FROM lineage l JOIN drilling_field f ON f.field_id=l.field_id LEFT JOIN drilling_block b ON b.block_id=f.block_id ORDER BY l.root_id,l.depth DESC",ids.toArray());
    var paths=new HashMap<Long,List<String>>();
    for(var ancestor:ancestors){
      var path=paths.computeIfAbsent(id(ancestor.get("root_id")),ignored->{
        var names=new ArrayList<String>();
        for(String key:List.of("block_name","field_name")){String name=Objects.toString(ancestor.get(key),"").trim();if(!name.isBlank())names.add(name);}
        return names;
      });
      String name=Objects.toString(ancestor.get("node_name"),"").trim();
      if(!name.isBlank())path.add(name);
    }
    for(var row:rows)row.put("engineering_path",String.join(" / ",paths.getOrDefault(id(row.get("node_id")),List.of())));
  }

  public List<Map<String,Object>> sourceAssociations(Long sourceId){
    var rows=db.queryForList("SELECT DISTINCT n.node_id,n.node_type,n.node_name,n.field_id,n.well_id,n.wellbore_id,"
        + "w.well_code,w.well_name,b.wellbore_code,b.wellbore_name,d.document_role,"
        + "JSON_UNQUOTE(JSON_EXTRACT(df.properties_json,'$.trajectory_kind')) AS trajectory_kind "
        + "FROM drilling_engineering_document_file df JOIN drilling_engineering_document d ON d.document_id=df.document_id "
        + "JOIN drilling_engineering_node n ON n.node_id=d.node_id LEFT JOIN drilling_well w ON w.well_id=n.well_id "
        + "LEFT JOIN drilling_wellbore b ON b.wellbore_id=n.wellbore_id "
        + "WHERE df.source_id=? AND n.status='ACTIVE' AND JSON_EXTRACT(df.properties_json,'$.canonical_document_file_id') IS NULL AND ("
        + DrillingDataScope.current().predicate("n.field_id")+") ORDER BY n.node_id,d.document_role",sourceId);
    decorateDocumentPaths(rows);
    return rows;
  }

  /** Validate explicit upload ownership before parsing or storing file bytes. */
  @Transactional public Map<String,Object> fileTarget(Long fieldId,Long nodeId){
    var target=nodeInField(fieldId,nodeId,true);
    if(!"ACTIVE".equals(target.get("status"))||Boolean.TRUE.equals(obj(target.getOrDefault("properties",target.get("properties_json"))).get("reference_only")))
      throw new IllegalArgumentException("请在所属井场选择可编辑的资料目录");
    if("FIELD".equals(target.get("node_type"))){
      // Serialize first use of the field's file folder. File operations must
      // not require permission to create wells or change engineering parents.
      db.queryForList("SELECT field_id FROM drilling_field WHERE field_id=? FOR UPDATE",fieldId);
      var folders=db.queryForList("SELECT node_id FROM drilling_engineering_node WHERE field_id=? AND node_type='INBOX' AND well_id IS NULL AND parent_id IS NULL AND status='ACTIVE' ORDER BY node_id LIMIT 1",fieldId);
      if(!folders.isEmpty())return fileTarget(fieldId,id(folders.get(0).get("node_id")));
      return createNode(Map.of("fieldId",fieldId,"parentId",nodeId,"nodeType","INBOX","name","井场资料","code","INBOX-"+UUID.randomUUID()));
    }
    if(nodeId<0||!Set.of("INBOX","WELL","WELLBORE","PLAN","SURVEY").contains(target.get("node_type")))
      throw new IllegalArgumentException("请选择井场、井、井眼或资料目录");
    return target;
  }

  public Map<String,Object> uploadTarget(Long fieldId,Long nodeId,Long wellboreId,String role,String kind){
    return uploadTarget(fieldId,nodeId,wellboreId,role,kind,null,null);
  }
  public Map<String,Object> uploadTarget(Long fieldId,Long nodeId,Long wellboreId,String role,String kind,Long documentId,Integer expectedEditionNo){
    access(fieldId,true);
    if(!Set.of("TRAJECTORY_DATA","TRAJECTORY_REPORT","WELL_SUMMARY","ENGINEERING_REPORT","PRODUCTION_DATA","ARCHIVE","REFERENCE").contains(role))throw new IllegalArgumentException("文件用途无效");
    Map<String,Object> document=null;
    if(documentId!=null){
      document=documentContext(fieldId,documentId,true);
      if(expectedEditionNo==null||expectedEditionNo<1)throw new IllegalArgumentException("更新文件必须提供原文件的当前版次");
      if(nodeId!=null&&!nodeId.equals(id(document.get("node_id"))))throw new IllegalArgumentException("文件新版本必须保留原工程归属");
      nodeId=id(document.get("node_id"));
      if(!role.equals(document.get("document_role")))throw new IllegalArgumentException("文件新版本不能更改资料用途");
      String originalKind=Objects.toString(document.get("trajectory_kind"),"");
      if(!originalKind.isBlank()&&!originalKind.equals(kind))throw new IllegalArgumentException("文件新版本不能更改设计或实钻类别");
    }
    if(nodeId==null&&wellboreId==null)return null; // Legacy staging imports remain supported.
    if(nodeId==null){
      var bore=db.queryForList("SELECT b.well_id,w.field_id FROM drilling_wellbore b JOIN drilling_well w ON w.well_id=b.well_id JOIN drilling_field_wellbore x ON x.wellbore_id=b.wellbore_id WHERE x.field_id=? AND b.wellbore_id=? AND w.status<>'ARCHIVED'",fieldId,wellboreId);
      if(bore.isEmpty())throw new IllegalArgumentException("井眼不属于当前井场");
      access(id(bore.get(0).get("field_id")),true);
      registerWellboreReference(fieldId,wellboreId);
      nodeId=db.queryForObject("SELECT MIN(node_id) FROM drilling_engineering_node WHERE field_id=? AND wellbore_id=? AND node_type='WELLBORE' AND status='ACTIVE'",Long.class,fieldId,wellboreId);
    }
    var n=fileTarget(fieldId,nodeId);
    if(wellboreId!=null&&!wellboreId.equals(id(n.get("wellbore_id"))))throw new IllegalArgumentException("上传节点与所选井眼不一致");
    validateFileUsage(n,role,kind);
    if(n.get("well_id")!=null)access(db.queryForObject("SELECT field_id FROM drilling_well WHERE well_id=?",Long.class,n.get("well_id")),true);
    if(document!=null){n.put("upload_document_id",documentId);n.put("upload_expected_edition_no",expectedEditionNo);n.put("upload_revision_id",document.get("revision_id"));}
    return n;
  }

  static void validateFileUsage(Map<String,Object> node,String role,String kind){
    if("TRAJECTORY_DATA".equals(role)&&(node.get("wellbore_id")==null||kind==null||kind.isBlank()))
      throw new IllegalArgumentException("轨迹数据必须选择具体井眼，并指定设计轨道或实钻测斜");
    if(kind!=null&&!kind.isBlank()){
      if(!Set.of("TRAJECTORY_DATA","TRAJECTORY_REPORT").contains(role)||!Set.of("DESIGN","ACTUAL").contains(kind)||node.get("wellbore_id")==null)
        throw new IllegalArgumentException("设计或实钻资料必须关联到具体井眼");
      if("PLAN".equals(node.get("node_type"))&&!"DESIGN".equals(kind)||"SURVEY".equals(node.get("node_type"))&&!"ACTUAL".equals(kind))
        throw new IllegalArgumentException("文件用途与所选资料目录不一致");
    }
  }

  public Map<String,Object> documentContext(Long fieldId,Long documentId,boolean write){
    var documents=db.queryForList("SELECT d.*,n.field_id,n.node_type,n.wellbore_id,r.phase FROM drilling_engineering_document d JOIN drilling_engineering_node n ON n.node_id=d.node_id LEFT JOIN drilling_engineering_revision r ON r.revision_id=d.revision_id WHERE d.document_id=?",documentId);
    if(documents.isEmpty())throw new IllegalArgumentException("资料文件不存在");
    var result=new LinkedHashMap<String,Object>(documents.get(0));
    nodeInField(fieldId,id(result.get("node_id")),write);
    var kinds=new LinkedHashSet<String>();
    if(Set.of("TRAJECTORY_DATA","TRAJECTORY_REPORT").contains(result.get("document_role"))){
      if("PLAN".equals(result.get("node_type")))kinds.add("DESIGN");
      if("SURVEY".equals(result.get("node_type")))kinds.add("ACTUAL");
      for(var row:db.queryForList("SELECT DISTINCT JSON_UNQUOTE(JSON_EXTRACT(properties_json,'$.trajectory_kind')) AS kind FROM drilling_engineering_document_file WHERE document_id=?",documentId)){
        String value=Objects.toString(row.get("kind"),"");if(Set.of("DESIGN","ACTUAL").contains(value))kinds.add(value);
      }
      if(kinds.isEmpty()&&"PLAN".equals(result.get("phase")))kinds.add("DESIGN");
      if(kinds.isEmpty()&&"ACTUAL".equals(result.get("phase")))kinds.add("ACTUAL");
    }
    if(kinds.size()>1)throw new IllegalArgumentException("该资料的设计与实钻类别冲突，请先核对资料归属");
    result.put("trajectory_kind",kinds.isEmpty()?null:kinds.iterator().next());
    result.put("latest_edition_no",db.queryForObject("SELECT COALESCE(MAX(edition_no),0) FROM drilling_engineering_document_file WHERE document_id=?",Integer.class,documentId));
    var latestFile=db.queryForList("SELECT f.original_name,f.display_name,df.file_label FROM drilling_engineering_document_file df JOIN drilling_source_document s ON s.source_id=df.source_id JOIN data_file f ON f.file_id=s.file_id WHERE df.document_id=? ORDER BY df.edition_no DESC LIMIT 1",documentId);
    if(!latestFile.isEmpty())result.putAll(latestFile.get(0));
    decorateDocumentPaths(List.of(result));return result;
  }

  /** Run before parsing/storing bytes. Hold the document lock until upload commits. */
  public void validateFileEdition(Map<String,Object> target,String sha256){
    if(target==null||target.get("upload_document_id")==null)return;
    Long document=id(target.get("upload_document_id"));
    db.queryForList("SELECT document_id FROM drilling_engineering_document WHERE document_id=? FOR UPDATE",document);
    var duplicate=db.queryForList("SELECT df.edition_no FROM drilling_engineering_document_file df JOIN drilling_source_document s ON s.source_id=df.source_id JOIN data_file f ON f.file_id=s.file_id WHERE df.document_id=? AND f.sha256=?",document,sha256);
    if(!duplicate.isEmpty())return;
    var latest=db.queryForList("SELECT edition_no FROM drilling_engineering_document_file WHERE document_id=? ORDER BY edition_no DESC LIMIT 1 FOR UPDATE",document);
    int current=latest.isEmpty()?0:((Number)latest.get(0).get("edition_no")).intValue();
    if(current!=((Number)target.get("upload_expected_edition_no")).intValue())throw new ResponseStatusException(HttpStatus.CONFLICT,"该文件已有新版本，请刷新文件版次后重新上传");
  }
  public Map<String,Object> contents(Long nodeId,String keyword,String role,String trajectoryKind,int page,int size,String sort,String order){
    var n=node(nodeId,false);size=Math.max(1,Math.min(size,100));page=Math.max(1,page);
    String where=" FROM drilling_engineering_document d JOIN drilling_engineering_document_file df ON df.document_id=d.document_id JOIN drilling_source_document s ON s.source_id=df.source_id JOIN data_file f ON f.file_id=s.file_id LEFT JOIN drilling_engineering_revision r ON r.revision_id=d.revision_id JOIN drilling_engineering_node n ON n.node_id=d.node_id WHERE df.usage_state<>'DELETED' AND df.edition_no=(SELECT MAX(current_file.edition_no) FROM drilling_engineering_document_file current_file WHERE current_file.document_id=d.document_id) AND JSON_EXTRACT(df.properties_json,'$.canonical_document_file_id') IS NULL AND JSON_EXTRACT(r.properties_json,'$.canonical_revision_id') IS NULL AND d.node_id IN(SELECT node_id FROM scope) AND (?='' OR f.original_name LIKE ? OR d.document_title LIKE ? OR f.display_name LIKE ?) AND (?='' OR d.document_role=?) AND (?='' OR "+FILE_KIND_SQL+"=?)";
    Object[] args={nodeId,keyword,"%"+keyword+"%","%"+keyword+"%","%"+keyword+"%",role,role,trajectoryKind,trajectoryKind};
    long total=db.queryForObject(descendants()+"SELECT COUNT(*)"+where,Long.class,args);
    String column=Map.of("name","f.original_name","date","df.create_time","role","d.document_role").getOrDefault(sort,"df.create_time");
    var params=new ArrayList<Object>(Arrays.asList(args));params.add(size);params.add((page-1)*size);
    var files=db.queryForList(descendants()+"SELECT d.*,df.document_file_id,df.edition_no,df.representation_format,df.usage_state AS file_usage_state,df.file_label,df.properties_json AS file_properties,s.source_id,s.field_id AS source_field_id,s.source_path,s.parse_status,f.file_id,f.original_name,f.display_name,f.sha256,f.file_size,df.create_time,r.business_revision,r.outcome_name,r.phase,n.wellbore_id,n.node_type,"+FILE_KIND_SQL+" AS trajectory_kind,(SELECT MAX(history.edition_no) FROM drilling_engineering_document_file history WHERE history.document_id=d.document_id) AS latest_edition_no"+where+" ORDER BY "+column+("asc".equalsIgnoreCase(order)?" ASC":" DESC")+",df.document_file_id DESC LIMIT ? OFFSET ?",params.toArray());
    decorateDocumentPaths(files);
    var revisions=db.queryForList(descendants()+"SELECT r.*,n.node_name,n.node_type,n.wellbore_id,h.revision_id AS published_revision_id,h.survey_version_id AS published_data_id,(SELECT COUNT(*) FROM drilling_engineering_revision_data rd WHERE rd.revision_id=r.revision_id) dataset_count,EXISTS(SELECT 1 FROM drilling_engineering_revision_data rd JOIN drilling_trajectory_head th ON th.survey_version_id=rd.survey_version_id WHERE rd.revision_id=r.revision_id) selected_for_calculation FROM drilling_engineering_revision r JOIN drilling_engineering_node n ON n.node_id=r.node_id LEFT JOIN drilling_engineering_head h ON h.node_id=r.node_id AND h.revision_id=r.revision_id WHERE r.node_id IN(SELECT node_id FROM scope) AND JSON_EXTRACT(r.properties_json,'$.canonical_revision_id') IS NULL ORDER BY r.source_date DESC,r.revision_id DESC",nodeId);
    return Map.of("node",n,"files",files,"total",total,"revisions",revisions,"trajectoryVersions",trajectoryVersions(id(n.get("field_id")),nodeId,false),"children",db.queryForList("SELECT * FROM drilling_engineering_node WHERE parent_id=? AND status='ACTIVE' ORDER BY node_type,node_name",nodeId));
  }
  private List<Map<String,Object>> trajectoryVersions(Long fieldId,Long nodeId,boolean fieldScope){
    Collection<?> boreIds;
    if(fieldScope){
      boreIds=db.queryForList("SELECT DISTINCT b.wellbore_id FROM drilling_field_wellbore x JOIN drilling_wellbore b ON b.wellbore_id=x.wellbore_id WHERE x.field_id=?",Long.class,fieldId);
    }else{
      boreIds=db.queryForList("WITH RECURSIVE scope AS (SELECT node_id FROM drilling_engineering_node WHERE node_id=? UNION ALL SELECT n.node_id FROM drilling_engineering_node n JOIN scope s ON n.parent_id=s.node_id WHERE n.status='ACTIVE') SELECT DISTINCT n.wellbore_id FROM drilling_engineering_node n JOIN scope s ON s.node_id=n.node_id WHERE n.wellbore_id IS NOT NULL",Long.class,nodeId);
      if(boreIds.isEmpty()){
        var row=db.queryForList("SELECT well_id,node_type FROM drilling_engineering_node WHERE node_id=?",nodeId);
        if(!row.isEmpty()&&"WELL".equals(row.get(0).get("node_type")))boreIds=db.queryForList("SELECT wellbore_id FROM drilling_wellbore WHERE well_id=?",Long.class,row.get(0).get("well_id"));
      }
    }
    if(boreIds.isEmpty())return List.of();
    String marks=String.join(",",Collections.nCopies(boreIds.size(),"?"));
    var args=new ArrayList<Object>();args.add(fieldId);args.addAll(boreIds);
    String kindFilter="";
    if(!fieldScope){
      var selected=node(nodeId,false);
      if(Set.of("PLAN","SURVEY").contains(selected.get("node_type"))){
        kindFilter=" AND t.trajectory_kind=?";
        args.add("PLAN".equals(selected.get("node_type"))?"DESIGN":"ACTUAL");
      }
    }
    return db.queryForList("SELECT t.survey_version_id,t.wellbore_id,t.trajectory_kind,t.source_locator,t.content_sha256,t.label,t.revision_note,t.create_time,t.trajectory_version_no AS version_no,t.quality_status,s.parent_version_id,(SELECT pv.trajectory_version_no FROM drilling_trajectory_version pv WHERE pv.survey_version_id=s.parent_version_id) AS parent_version_no,s.import_mode,s.source_format,s.max_md,COALESCE(h.survey_version_id=t.survey_version_id,0) is_current,(SELECT COUNT(*) FROM drilling_survey_station st WHERE st.survey_version_id=t.survey_version_id) station_count,w.well_id,w.well_code,w.well_name,b.wellbore_code,b.wellbore_name,k.block_name,fld.field_name,f.original_name,f.display_name,COALESCE(sr.status,CASE WHEN s.source_file_id IS NULL THEN 'CONFIRMED' ELSE 'PENDING' END) AS source_review_status FROM drilling_trajectory_version t JOIN drilling_survey_version s ON s.survey_version_id=t.survey_version_id JOIN drilling_wellbore b ON b.wellbore_id=t.wellbore_id JOIN drilling_well w ON w.well_id=b.well_id LEFT JOIN drilling_well_catalog wc ON wc.well_id=w.well_id LEFT JOIN drilling_field fld ON fld.field_id=wc.field_id LEFT JOIN drilling_block k ON k.block_id=fld.block_id LEFT JOIN drilling_trajectory_head h ON h.wellbore_id=t.wellbore_id AND h.trajectory_kind=t.trajectory_kind LEFT JOIN data_file f ON f.file_id=s.source_file_id LEFT JOIN drilling_source_review sr ON sr.source_id=(SELECT sd.source_id FROM drilling_source_document sd WHERE sd.file_id=s.source_file_id ORDER BY sd.source_id DESC LIMIT 1) WHERE EXISTS(SELECT 1 FROM drilling_field_wellbore x WHERE x.field_id=? AND x.wellbore_id=t.wellbore_id) AND t.wellbore_id IN ("+marks+")"+kindFilter+" ORDER BY k.block_name,fld.field_name,w.well_name,b.wellbore_name,t.trajectory_kind,(t.quality_status='READY') DESC,t.trajectory_version_no DESC",args.toArray());
  }
  public Map<String,Object> scopedContents(Long fieldId,Long nodeId,String keyword,String role,String trajectoryKind,int page,int size,String sort,String order){
    if(!isScope(nodeId))return contents(nodeId,keyword,role,trajectoryKind,page,size,sort,order);
    var n=nodeInField(fieldId,nodeId,false);size=Math.max(1,Math.min(size,100));page=Math.max(1,page);
    String where=" FROM drilling_engineering_document d JOIN drilling_engineering_document_file df ON df.document_id=d.document_id JOIN drilling_source_document s ON s.source_id=df.source_id JOIN data_file f ON f.file_id=s.file_id LEFT JOIN drilling_engineering_revision r ON r.revision_id=d.revision_id JOIN drilling_engineering_node n ON n.node_id=d.node_id WHERE df.usage_state<>'DELETED' AND df.edition_no=(SELECT MAX(current_file.edition_no) FROM drilling_engineering_document_file current_file WHERE current_file.document_id=d.document_id) AND JSON_EXTRACT(df.properties_json,'$.canonical_document_file_id') IS NULL AND JSON_EXTRACT(r.properties_json,'$.canonical_revision_id') IS NULL AND n.field_id=? AND (?='' OR f.original_name LIKE ? OR d.document_title LIKE ? OR f.display_name LIKE ?) AND (?='' OR d.document_role=?) AND (?='' OR "+FILE_KIND_SQL+"=?)";
    Object[] args={fieldId,keyword,"%"+keyword+"%","%"+keyword+"%","%"+keyword+"%",role,role,trajectoryKind,trajectoryKind};
    long total=db.queryForObject("SELECT COUNT(*)"+where,Long.class,args);
    String column=Map.of("name","f.original_name","date","df.create_time","role","d.document_role").getOrDefault(sort,"df.create_time");
    var params=new ArrayList<Object>(Arrays.asList(args));params.add(size);params.add((page-1)*size);
    var files=db.queryForList("SELECT d.*,df.document_file_id,df.edition_no,df.representation_format,df.usage_state AS file_usage_state,df.file_label,df.properties_json AS file_properties,s.source_id,s.field_id AS source_field_id,s.source_path,s.parse_status,f.file_id,f.original_name,f.display_name,f.sha256,f.file_size,df.create_time,r.business_revision,r.outcome_name,r.phase,n.wellbore_id,n.node_type,"+FILE_KIND_SQL+" AS trajectory_kind,(SELECT MAX(history.edition_no) FROM drilling_engineering_document_file history WHERE history.document_id=d.document_id) AS latest_edition_no"+where+" ORDER BY "+column+("asc".equalsIgnoreCase(order)?" ASC":" DESC")+",df.document_file_id DESC LIMIT ? OFFSET ?",params.toArray());
    decorateDocumentPaths(files);
    var revisions=db.queryForList("SELECT r.*,n.node_name,n.node_type,n.wellbore_id,h.revision_id AS published_revision_id,h.survey_version_id AS published_data_id,(SELECT COUNT(*) FROM drilling_engineering_revision_data rd WHERE rd.revision_id=r.revision_id) dataset_count,EXISTS(SELECT 1 FROM drilling_engineering_revision_data rd JOIN drilling_trajectory_head th ON th.survey_version_id=rd.survey_version_id WHERE rd.revision_id=r.revision_id) selected_for_calculation FROM drilling_engineering_revision r JOIN drilling_engineering_node n ON n.node_id=r.node_id LEFT JOIN drilling_engineering_head h ON h.node_id=n.node_id AND h.revision_id=r.revision_id WHERE n.field_id=? AND JSON_EXTRACT(r.properties_json,'$.canonical_revision_id') IS NULL ORDER BY r.source_date DESC,r.revision_id DESC",fieldId);
    var children=tree(fieldId).stream().filter(x->Objects.equals(x.get("parent_id"),nodeId)).toList();
    return Map.of("node",n,"files",files,"total",total,"revisions",revisions,"trajectoryVersions",trajectoryVersions(fieldId,nodeId,true),"children",children);
  }

  public List<Map<String,Object>> targets(Long fieldId, Long wellboreId) {
    access(fieldId, false);
    var rows = db.queryForList("SELECT target_id,field_id,well_id,target_name,target_type,geometry_json,tolerance_json,status,create_by,create_time,update_time FROM drilling_target WHERE field_id=? AND well_id=(SELECT well_id FROM drilling_wellbore WHERE wellbore_id=?) AND status='ACTIVE' ORDER BY target_id", fieldId, wellboreId);
    for (var row : rows) {
      var geometry=obj(row.remove("geometry_json"));
      var tolerance=obj(row.remove("tolerance_json"));
      row.put("geometry", geometry); row.put("tolerance", tolerance);
      for (String key : List.of("elevation","north","east","tvd","closure_distance","closure_azimuth","shape","radius","height","width","remark"))
        if (geometry.containsKey(key)) row.put(key, geometry.get(key));
      if (row.get("radius") == null && tolerance.get("radius") != null) row.put("radius", tolerance.get("radius"));
    }
    return rows;
  }

  @Transactional public Map<String,Object> createTarget(Long fieldId, Long wellboreId, Map<String,Object> body) {
    access(fieldId, true);
    var bore = db.queryForList("SELECT b.well_id FROM drilling_wellbore b JOIN drilling_well w ON w.well_id=b.well_id WHERE b.wellbore_id=? AND w.field_id=?", wellboreId, fieldId);
    if (bore.isEmpty()) throw new IllegalArgumentException("井眼不属于当前井场");
    String name = required(body, "name", 128), type = required(body, "type", 16);
    if (!Set.of("POINT","RECTANGLE","ELLIPSE").contains(type)) throw new IllegalArgumentException("靶区类型无效");
    Object geometry = body.get("geometry"), tolerance = body.get("tolerance");
    if (geometry == null) throw new IllegalArgumentException("几何参数不能为空");
    db.update("INSERT INTO drilling_target(field_id,well_id,target_name,target_type,geometry_json,tolerance_json,status,create_by) VALUES(?,?,?,?,?,?,'ACTIVE',?)", fieldId, id(bore.get(0).get("well_id")), name, type, JSON.toJSONString(geometry), tolerance == null ? "{}" : JSON.toJSONString(tolerance), ShiroUtils.getLoginName());
    Long targetId = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    return db.queryForMap("SELECT * FROM drilling_target WHERE target_id=?", targetId);
  }

  @Transactional public Map<String,Object> updateTarget(Long fieldId, Long targetId, Map<String,Object> body) {
    access(fieldId, true);
    var current = db.queryForList("SELECT * FROM drilling_target WHERE target_id=? AND field_id=? AND status='ACTIVE'", targetId, fieldId);
    if (current.isEmpty()) throw new IllegalArgumentException("靶区不存在");
    String name = required(body, "name", 128), type = required(body, "type", 16);
    db.update("UPDATE drilling_target SET target_name=?,target_type=?,geometry_json=?,tolerance_json=?,update_time=NOW() WHERE target_id=? AND field_id=?", name, type, JSON.toJSONString(body.get("geometry")), JSON.toJSONString(body.getOrDefault("tolerance", Map.of())), targetId, fieldId);
    return db.queryForMap("SELECT * FROM drilling_target WHERE target_id=?", targetId);
  }

  @Transactional public void archiveTarget(Long fieldId, Long targetId) {
    access(fieldId, true);
    if (db.update("UPDATE drilling_target SET status='ARCHIVED',update_time=NOW() WHERE target_id=? AND field_id=? AND status='ACTIVE'", targetId, fieldId) != 1) throw new IllegalArgumentException("靶区不存在");
  }

  /** Return the structured target points attached to the selected actual survey version. */
  public List<Map<String,Object>> trajectoryTargetPoints(Long fieldId, Long wellboreId, Long versionId) {
    access(fieldId, false);
    var bore = db.queryForList("SELECT b.well_id FROM drilling_wellbore b JOIN drilling_field_wellbore x ON x.wellbore_id=b.wellbore_id WHERE x.field_id=? AND b.wellbore_id=?", fieldId, wellboreId);
    if (bore.isEmpty()) throw new IllegalArgumentException("井眼不属于当前工程");
    Long selected = versionId;
    if (selected == null) selected = db.queryForObject("SELECT h.survey_version_id FROM drilling_trajectory_head h JOIN drilling_trajectory_version v ON v.survey_version_id=h.survey_version_id WHERE h.wellbore_id=? AND h.trajectory_kind='ACTUAL'", Long.class, wellboreId);
    if (selected == null) return List.of();
    var points=db.queryForList("SELECT target_point_id,field_id,wellbore_id,survey_version_id,target_no,md,north,east,tvd,inclination,azimuth,tolerance,remark,status,metrics_json FROM drilling_trajectory_target_point WHERE field_id=? AND wellbore_id=? AND survey_version_id=? AND status='ACTIVE' ORDER BY md,target_no", fieldId,wellboreId,selected);
    if(!points.isEmpty()){
      enrichTargetPointRows(points);
      return points;
    }
    return List.of();
  }

  private void enrichTargetPointRows(List<Map<String,Object>> rows){
    for(var row:rows){
      row.putAll(obj(row.remove("metrics_json")));
      Double north=numberOrNull(row.get("north")), east=numberOrNull(row.get("east"));
      if(north!=null&&east!=null){
        row.putIfAbsent("closure_distance",Math.hypot(north,east));
        row.putIfAbsent("closure_azimuth",(Math.toDegrees(Math.atan2(east,north))+360)%360);
      }

    }
  }
  private static Double numberOrNull(Object value){try{return value==null?null:Double.valueOf(value.toString());}catch(Exception e){return null;}}
  private static Double number(Object value,String label){Double n=numberOrNull(value);if(n==null||!Double.isFinite(n))throw new IllegalArgumentException(label+"必须为有效数值");return n;}

  @Transactional public Map<String,Object> updateTrajectoryTargetPoint(Long fieldId,Long wellboreId,Long targetPointId,Map<String,Object> body){
    access(fieldId,true);
    var bore=db.queryForList("SELECT b.well_id FROM drilling_wellbore b JOIN drilling_field_wellbore x ON x.wellbore_id=b.wellbore_id WHERE x.field_id=? AND b.wellbore_id=?",fieldId,wellboreId);
    if(bore.isEmpty())throw new IllegalArgumentException("井眼不属于当前工程");
    Long version=id(body.get("versionId"));
    var point=db.queryForList("SELECT * FROM drilling_trajectory_target_point WHERE target_point_id=? AND field_id=? AND wellbore_id=? AND status='ACTIVE'",targetPointId,fieldId,wellboreId);
    requireActualEditable(fieldId,wellboreId,version);
    if(point.isEmpty())throw new IllegalArgumentException("入靶数据不存在");
    double md=number(body.get("md"),"井深");double maxMd=db.queryForObject("SELECT max_md FROM drilling_survey_version WHERE survey_version_id=?",Double.class,version);
    if(md<0||md>maxMd)throw new IllegalArgumentException("入靶井深必须位于所选实测井段内");
    if(!Objects.equals(id(point.get(0).get("survey_version_id")),version))throw new IllegalArgumentException("入靶记录不属于所选版本");
    db.update("UPDATE drilling_trajectory_target_point SET target_no=?,md=?,north=?,east=?,tvd=?,inclination=?,azimuth=?,remark=?,metrics_json=?,update_time=NOW() WHERE target_point_id=? AND field_id=? AND wellbore_id=?",required(body,"targetNo",64),number(body.get("md"),"井深"),numberOrNull(body.get("north")),numberOrNull(body.get("east")),numberOrNull(body.get("tvd")),numberOrNull(body.get("inclination")),numberOrNull(body.get("azimuth")),Objects.toString(body.getOrDefault("remark","")),targetMetrics(body),targetPointId,fieldId,wellboreId);
    return db.queryForMap("SELECT * FROM drilling_trajectory_target_point WHERE target_point_id=?",targetPointId);
  }
  @Transactional public void archiveTrajectoryTargetPoint(Long fieldId,Long wellboreId,Long targetPointId,Long versionId){
    access(fieldId,true);
    requireActualEditable(fieldId,wellboreId,versionId);
    if(db.update("UPDATE drilling_trajectory_target_point SET status='ARCHIVED',update_time=NOW() WHERE target_point_id=? AND field_id=? AND wellbore_id=? AND survey_version_id=? AND status='ACTIVE'",targetPointId,fieldId,wellboreId,versionId)!=1)throw new IllegalArgumentException("入靶数据不存在或为来源靶区转换记录，请先编辑保存");
  }
  private void requireActualEditable(Long field,Long bore,Long version){
    if(version==null)throw new IllegalArgumentException("请选择实测测斜版本");
    if(db.queryForObject("SELECT COUNT(*) FROM drilling_trajectory_version t JOIN drilling_wellbore b ON b.wellbore_id=t.wellbore_id JOIN drilling_field_wellbore x ON x.wellbore_id=b.wellbore_id WHERE x.field_id=? AND b.wellbore_id=? AND t.survey_version_id=? AND t.trajectory_kind='ACTUAL' AND b.engineering_status IN ('DRILLING','SUSPENDED')",Integer.class,field,bore,version)!=1)throw new IllegalArgumentException("所选版本或施工状态不允许修改实钻入靶数据");
  }
  private String targetMetrics(Map<String,Object> body){var metrics=new LinkedHashMap<String,Object>();for(String key:List.of("offset","longitudinal_offset","lateral_offset","closure_distance","closure_azimuth"))if(body.get(key)!=null)metrics.put(key,number(body.get(key),key));return JSON.toJSONString(metrics);}
  @Transactional public Map<String,Object> createTrajectoryTargetPoint(Long field,Long bore,Map<String,Object> body){
    access(field,true);Long version=id(body.get("versionId"));requireActualEditable(field,bore,version);
    double md=number(body.get("md"),"井深");if(md<0)throw new IllegalArgumentException("井深不能为负数");
    double drilled=db.queryForObject("SELECT max_md FROM drilling_survey_version WHERE survey_version_id=?",Double.class,version);if(md>drilled)throw new IllegalArgumentException("入靶井深不能超过当前实测井深");
    db.update("INSERT INTO drilling_trajectory_target_point(field_id,wellbore_id,survey_version_id,target_no,md,north,east,tvd,inclination,azimuth,remark,metrics_json,status,create_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?)",field,bore,version,required(body,"targetNo",64),md,numberOrNull(body.get("north")),numberOrNull(body.get("east")),numberOrNull(body.get("tvd")),numberOrNull(body.get("inclination")),numberOrNull(body.get("azimuth")),Objects.toString(body.get("remark"),""),targetMetrics(body),ShiroUtils.getLoginName());
    return db.queryForMap("SELECT * FROM drilling_trajectory_target_point WHERE target_point_id=LAST_INSERT_ID()");
  }
  public Map<String,Object> detail(Long revisionId){
    var r=revision(revisionId,false);
    r.put("datasets",db.queryForList("SELECT rd.*,t.wellbore_id,t.trajectory_kind,t.trajectory_version_no AS version_no,s.max_md,s.parent_version_id,f.original_name,f.display_name,COALESCE(h.survey_version_id=t.survey_version_id,0) is_current FROM drilling_engineering_revision_data rd JOIN drilling_trajectory_version t ON t.survey_version_id=rd.survey_version_id JOIN drilling_survey_version s ON s.survey_version_id=t.survey_version_id LEFT JOIN data_file f ON f.file_id=s.source_file_id LEFT JOIN drilling_trajectory_head h ON h.wellbore_id=t.wellbore_id AND h.trajectory_kind=t.trajectory_kind WHERE rd.revision_id=? ORDER BY s.survey_version_id",revisionId));
    r.put("files",db.queryForList("SELECT d.document_role,df.*,s.source_path,f.original_name,f.display_name,f.sha256 FROM drilling_engineering_document d JOIN drilling_engineering_document_file df ON df.document_id=d.document_id JOIN drilling_source_document s ON s.source_id=df.source_id JOIN data_file f ON f.file_id=s.file_id WHERE d.revision_id=? ORDER BY d.document_role,df.edition_no",revisionId));
    r.put("properties",obj(r.remove("properties_json")));return r;
  }
  @Transactional public Map<String,Object> createNode(Map<String,Object>b){
    Long fieldId=id(b.get("fieldId")),parentId=id(b.get("parentId"));
    if(fieldId==null||fieldId<=0)throw new IllegalArgumentException("请先选择所属井场");
    access(fieldId,true);
    String type=required(b,"nodeType",16),name=required(b,"name",255),code=str(b,"code","");
    if(code.isBlank())code=name;
    if(code.length()>128)throw new IllegalArgumentException("节点编码最长128字");
    Map<String,Object> parent=parentId==null?null:nodeInField(fieldId,parentId,true);
    Long storedParent=EngineeringNodeHierarchy.creationParent(type,fieldId,parent);
    String boreStatus=EngineeringNodeHierarchy.engineeringStatus(b.get("engineeringStatus"),"UNPLANNED");
    Long well=null,bore=null;
    if("WELL".equals(type)){
      well=id(b.get("wellId"));
      if(well==null){
        var registered=db.queryForList("SELECT c.well_id FROM drilling_well_catalog c WHERE c.field_id=? AND c.identity_key=?",fieldId,code);
        if(!registered.isEmpty())throw new IllegalArgumentException("同一井场已有该井，请在目录中选择，不能重复创建");
        db.update("INSERT INTO drilling_well(field_id,well_code,well_name,status,metadata_json,create_by) VALUES(?,?,?,'UNKNOWN',?,?)",fieldId,code,name,JSON.toJSONString(Map.of("source","ENGINEERING_LIBRARY")),ShiroUtils.getLoginName());
        well=db.queryForObject("SELECT well_id FROM drilling_well WHERE field_id=? AND well_code=?",Long.class,fieldId,code);
        db.update("INSERT INTO drilling_well_catalog(well_id,field_id,identity_key) VALUES(?,?,?)",well,fieldId,code);
      }else{
        Long owner=db.queryForObject("SELECT field_id FROM drilling_well WHERE well_id=?",Long.class,well);
        if(!fieldId.equals(owner))throw new IllegalArgumentException("井不属于所选井场");
        if(db.queryForObject("SELECT COUNT(*) FROM drilling_engineering_node WHERE field_id=? AND node_type='WELL' AND well_id=? AND status='ACTIVE'",Integer.class,fieldId,well)>0)throw new IllegalArgumentException("目录中已有该井，请直接选择");
      }
    }else if("WELLBORE".equals(type)){
      well=id(parent.get("well_id"));Long parentBore=id(b.get("parentWellboreId"));
      if(db.queryForObject("SELECT COUNT(*) FROM drilling_wellbore WHERE well_id=? AND wellbore_code=?",Integer.class,well,code)>0)throw new IllegalArgumentException("该井下已有同编码井眼，请使用已有井眼");
      if(parentBore!=null&&db.queryForObject("SELECT COUNT(*) FROM drilling_wellbore WHERE wellbore_id=? AND well_id=?",Integer.class,parentBore,well)!=1)throw new IllegalArgumentException("父井眼必须属于同一口井");
      db.update("INSERT INTO drilling_wellbore(well_id,parent_wellbore_id,wellbore_code,wellbore_name,engineering_status) VALUES(?,?,?,?,?)",well,parentBore,code,name,boreStatus);
      bore=db.queryForObject("SELECT wellbore_id FROM drilling_wellbore WHERE well_id=? AND wellbore_code=?",Long.class,well,code);
      db.update("INSERT IGNORE INTO drilling_field_wellbore(field_id,wellbore_id) VALUES(?,?)",fieldId,bore);
    }else if(parent!=null){well=id(parent.get("well_id"));bore=id(parent.get("wellbore_id"));}
    String key=NativeEngineSupport.hash(fieldId+":"+storedParent+":"+type+":"+code);
    db.update("INSERT INTO drilling_engineering_node(node_key,field_id,parent_id,node_type,node_name,node_code,well_id,wellbore_id,entity_kind,properties_json,create_by) VALUES(?,?,?,?,?,?,?,?,?,?,?)",key,fieldId,storedParent,type,name,code,well,bore,str(b,"entityKind","WELL".equals(type)?"WELL":"UNKNOWN"),JSON.toJSONString(obj(b.get("properties"))),ShiroUtils.getLoginName());
    Long result=db.queryForObject("SELECT node_id FROM drilling_engineering_node WHERE node_key=?",Long.class,key);audit(fieldId,"CREATE_ENGINEERING_NODE",Map.of("nodeId",result,"request",b));return node(result,false);
  }
  @Transactional public Map<String,Object> updateNode(Long nodeId,Map<String,Object>b){
    var old=node(nodeId,true);db.queryForList("SELECT node_id FROM drilling_engineering_node WHERE node_id=? FOR UPDATE",nodeId);
    old=node(nodeId,true);
    Long fieldId=id(old.get("field_id"));
    Long requestedParent=b.containsKey("parentId")?id(b.get("parentId")):id(old.get("parent_id"));
    // Wells are stored at the field root (NULL parent_id). The tree uses a
    // synthetic field id; both forms represent the same, valid ownership.
    if(requestedParent==null&&Set.of("WELL","INBOX").contains(old.get("node_type")))requestedParent=scopeId(FIELD_SCOPE,fieldId);
    var parentNode=requestedParent==null?null:nodeInField(fieldId,requestedParent,true);
    Long parent=EngineeringNodeHierarchy.updateParent(old,parentNode);
    String name=b.containsKey("name")?required(b,"name",255):old.get("node_name").toString();
    String code=b.containsKey("code")?required(b,"code",128):Objects.toString(old.get("node_code"),name);
    String status=str(b,"status",old.get("status").toString());
    if(!Set.of("ACTIVE","ARCHIVED").contains(status))throw new IllegalArgumentException("资料状态无效");
    if(id(b.get("expectedVersion"))==null)throw new IllegalArgumentException("请刷新后重新保存");
    if(Boolean.TRUE.equals(obj(old.get("properties_json")).get("reference_only")))throw new IllegalArgumentException("请在所属井场编辑该井眼");
    String boreStatus=EngineeringNodeHierarchy.engineeringStatus(b.get("engineeringStatus"),Objects.toString(old.get("engineering_status"),"UNKNOWN"));
    if("ARCHIVED".equals(status)||("ABANDONED".equals(boreStatus)&&!"ABANDONED".equals(old.get("engineering_status")))){
      var bores=db.queryForList("SELECT DISTINCT wellbore_id FROM drilling_engineering_node WHERE (node_id=? OR well_id=?) AND wellbore_id IS NOT NULL",Long.class,nodeId,old.get("well_id"));
      for(Long bore:bores)EngineeringUsageGuard.requireBoreAvailable(db,bore);
    }
    if("WELLBORE".equals(old.get("node_type")))EngineeringUsageGuard.requireStatusChange(db,id(old.get("wellbore_id")),Objects.toString(old.get("engineering_status"),"UNKNOWN"),boreStatus);
    if(b.containsKey("engineeringStatus")&&!"WELLBORE".equals(old.get("node_type")))throw new IllegalArgumentException("仅井眼可以设置施工状态");
    if("ARCHIVED".equals(b.get("status"))&&db.queryForObject("SELECT COUNT(*) FROM drilling_engineering_node WHERE parent_id=? AND status='ACTIVE'",Integer.class,nodeId)>0)throw new IllegalArgumentException("请先归档下级节点");
    var props=obj(old.get("properties_json"));props.putAll(obj(b.get("properties")));
    int changed=db.update("UPDATE drilling_engineering_node SET parent_id=?,node_name=?,node_code=?,properties_json=?,status=?,row_version=row_version+1,update_time=NOW() WHERE node_id=? AND row_version=?",parent,name,code,JSON.toJSONString(props),status,nodeId,id(b.get("expectedVersion")));
    if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"节点已被他人修改，请刷新");
    if("WELL".equals(old.get("node_type"))){
      Long owner=db.queryForObject("SELECT field_id FROM drilling_well WHERE well_id=?",Long.class,old.get("well_id"));access(owner,true);
      var metadata=obj(old.get("well_metadata_json"));
      for(String key:List.of("province","coordinateSystem","horizontalDatum")){Object value=b.get(key);if(value!=null){String metadataKey="province".equals(key)?"province":"coordinateSystem".equals(key)?"coordinate_system":"horizontal_datum";metadata.put(metadataKey,value);}}
      db.update("UPDATE drilling_well SET well_name=?,well_type=COALESCE(?,well_type),location=COALESCE(?,location),operator_name=COALESCE(?,operator_name),structural_position=COALESCE(?,structural_position),wellhead_n=COALESCE(?,wellhead_n),wellhead_e=COALESCE(?,wellhead_e),wellhead_tvd=COALESCE(?,wellhead_tvd),magnetic_declination=COALESCE(?,magnetic_declination),meridian_convergence=COALESCE(?,meridian_convergence),remark=COALESCE(?,remark),source_reference=COALESCE(?,source_reference),metadata_json=?,update_time=NOW() WHERE well_id=?",name,b.get("wellType"),b.get("location"),b.get("operatorName"),b.get("structuralPosition"),b.get("wellheadN"),b.get("wellheadE"),b.get("wellheadTvd"),b.get("magneticDeclination"),b.get("meridianConvergence"),b.get("remark"),b.get("sourceReference"),JSON.toJSONString(metadata),old.get("well_id"));
    }else if("WELLBORE".equals(old.get("node_type"))){
      Long owner=db.queryForObject("SELECT w.field_id FROM drilling_well w JOIN drilling_wellbore wb ON wb.well_id=w.well_id WHERE wb.wellbore_id=?",Long.class,old.get("wellbore_id"));access(owner,true);
      db.update("UPDATE drilling_wellbore SET wellbore_name=?,wellbore_code=?,engineering_status=?,update_time=NOW() WHERE wellbore_id=?",name,code,boreStatus,old.get("wellbore_id"));
    }
    audit(id(old.get("field_id")),"UPDATE_ENGINEERING_NODE",Map.of("before",old,"request",b));return node(nodeId,false);
  }
  /** Soft-delete an engineering node after its descendants have been removed.
   * Source files and trajectory versions are retained for audit; deleting a
   * directory only removes its active engineering association. */
  @Transactional public void archiveNode(Long nodeId){
    var old=node(nodeId,true); if(nodeId<0) throw new IllegalArgumentException("区块和井场请在工程目录中删除");
    var impact=deletionImpact(id(old.get("field_id")),nodeId);
    if(Boolean.TRUE.equals(impact.get("blocked")))throw new IllegalArgumentException(Objects.toString(impact.get("blockReason"),"该工程节点禁止删除"));
    var ids=db.queryForList("WITH RECURSIVE scope AS (SELECT node_id FROM drilling_engineering_node WHERE node_id=? UNION ALL SELECT n.node_id FROM drilling_engineering_node n JOIN scope s ON n.parent_id=s.node_id) SELECT node_id FROM scope",Long.class,nodeId);
    if(ids.isEmpty())throw new IllegalArgumentException("工程节点不存在");
    String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
    db.update("UPDATE drilling_engineering_node SET status='ARCHIVED',update_time=NOW() WHERE node_id IN ("+marks+")",ids.toArray());
    db.update("UPDATE drilling_wellbore b JOIN drilling_engineering_node n ON n.wellbore_id=b.wellbore_id SET b.engineering_status='ABANDONED',b.update_time=NOW() WHERE n.node_id IN ("+marks+")",ids.toArray());
    db.update("UPDATE drilling_well w JOIN drilling_engineering_node n ON n.well_id=w.well_id SET w.status='ARCHIVED',w.update_time=NOW() WHERE n.node_id IN ("+marks+") AND n.node_type='WELL' AND w.status<>'ARCHIVED'",ids.toArray());
    audit(id(old.get("field_id")),"ARCHIVE_ENGINEERING_NODE",Map.of("nodeId",nodeId,"nodeType",old.get("node_type"),"descendantCount",ids.size()));
  }
  public Map<String,Object> deletionImpact(Long fieldId,Long nodeId){
    var n=nodeInField(fieldId,nodeId,false);
    var ids=db.queryForList("WITH RECURSIVE scope AS (SELECT node_id FROM drilling_engineering_node WHERE node_id=? UNION ALL SELECT n.node_id FROM drilling_engineering_node n JOIN scope s ON n.parent_id=s.node_id) SELECT node_id FROM scope",Long.class,nodeId);
    String marks=String.join(",",Collections.nCopies(Math.max(ids.size(),1),"?"));
    var args=ids.isEmpty()?new Object[]{-1L}:ids.toArray();
    Integer wells=db.queryForObject("SELECT COUNT(DISTINCT well_id) FROM drilling_engineering_node WHERE node_id IN ("+marks+") AND well_id IS NOT NULL",Integer.class,args);
    Integer drilled=db.queryForObject("SELECT COUNT(DISTINCT w.well_id) FROM drilling_well w LEFT JOIN drilling_wellbore b ON b.well_id=w.well_id WHERE (w.status='COMPLETED' OR b.engineering_status='COMPLETED' OR (b.engineering_status='ABANDONED' AND NOT EXISTS(SELECT 1 FROM drilling_engineering_node hidden WHERE hidden.wellbore_id=b.wellbore_id AND hidden.node_type='WELLBORE' AND hidden.status='ARCHIVED'))) AND w.well_id IN (SELECT DISTINCT well_id FROM drilling_engineering_node WHERE node_id IN ("+marks+") AND well_id IS NOT NULL)",Integer.class,args);
    Integer risky=db.queryForObject("SELECT COUNT(*) FROM anti_collision_result r JOIN anti_collision_task t ON t.task_id=r.task_id WHERE r.sf<=1.5 AND (t.reference_well_id IN (SELECT DISTINCT well_id FROM drilling_engineering_node WHERE node_id IN ("+marks+") AND well_id IS NOT NULL) OR r.neighbor_well_id IN (SELECT DISTINCT well_id FROM drilling_engineering_node WHERE node_id IN ("+marks+") AND well_id IS NOT NULL))",Integer.class,Stream.concat(Arrays.stream(args),Arrays.stream(args)).toArray());
    var bores=db.queryForList("SELECT DISTINCT wellbore_id FROM drilling_engineering_node WHERE node_id IN ("+marks+") AND wellbore_id IS NOT NULL",Long.class,args);
    String dependencyReason="";for(Long bore:bores){try{EngineeringUsageGuard.requireBoreAvailable(db,bore);}catch(ResponseStatusException e){dependencyReason=e.getReason();break;}}
    boolean blocked=(drilled!=null&&drilled>0)||(risky!=null&&risky>0)||!dependencyReason.isBlank();
    String reason=drilled!=null&&drilled>0?"下辖已有已钻井，禁止删除":risky!=null&&risky>0?"下辖井参与了分离系数≤1.5的防碰关系，禁止删除":dependencyReason;
    return Map.of("node",n,"descendantCount",ids.size(),"wellCount",Objects.requireNonNullElse(wells,0),"drilledWellCount",Objects.requireNonNullElse(drilled,0),"riskRelationCount",Objects.requireNonNullElse(risky,0),"blocked",blocked,"blockReason",reason);
  }
  @Transactional public Map<String,Object> createRevision(Long nodeId,Map<String,Object>b){
    var n=node(nodeId,true);if(!Set.of("PLAN","SURVEY").contains(n.get("node_type")))throw new IllegalArgumentException("请先选择具体方案或测斜成果");
    String label=required(b,"businessRevision",128),note=required(b,"note",1000);
    String phase=str(b,"phase","SURVEY".equals(n.get("node_type"))?"ACTUAL":"PLAN");
    if(!Set.of("ACTUAL","PLAN","PROTOTYPE").contains(phase)||("SURVEY".equals(n.get("node_type"))!=phase.equals("ACTUAL")))throw new IllegalArgumentException("来源阶段与成果用途不一致");
    Long parent=id(b.get("parentRevisionId"));
    if(parent!=null&&!nodeId.equals(id(revision(parent,false).get("node_id"))))throw new IllegalArgumentException("父修订必须属于同一方案或测斜成果");
    String key=NativeEngineSupport.hash("MANUAL:"+label);
    db.update("INSERT INTO drilling_engineering_revision(node_id,revision_key,business_revision,outcome_name,phase,usage_state,parent_revision_id,revision_note,source_date,properties_json,create_by) VALUES(?,?,?,?,?,'DRAFT',?,?,?,?,?)",nodeId,key,label,str(b,"outcomeName",n.get("node_name").toString()),phase,parent,note,b.get("sourceDate"),JSON.toJSONString(Map.of("source","MANUAL")),ShiroUtils.getLoginName());
    Long rid=db.queryForObject("SELECT revision_id FROM drilling_engineering_revision WHERE node_id=? AND revision_key=?",Long.class,nodeId,key);audit(id(n.get("field_id")),"CREATE_BUSINESS_REVISION",Map.of("revisionId",rid,"request",b));return detail(rid);
  }
  @Transactional public void attachData(Long revisionId,Long version,String label){
    var r=revision(revisionId,true);var n=node(id(r.get("node_id")),true);
    var rows=db.queryForList("SELECT t.*,w.field_id owner_field FROM drilling_trajectory_version t JOIN drilling_survey_version s ON s.survey_version_id=t.survey_version_id JOIN drilling_well w ON w.well_id=s.well_id WHERE t.survey_version_id=?",version);
    if(rows.isEmpty())throw new IllegalArgumentException("数据快照不存在");var t=rows.get(0);access(id(t.get("owner_field")),false);
    String kind="PLAN".equals(n.get("node_type"))?"DESIGN":"ACTUAL";
    if(!Objects.equals(n.get("wellbore_id"),t.get("wellbore_id"))||!kind.equals(t.get("trajectory_kind")))throw new IllegalArgumentException("数据井眼或用途与业务版本不一致");
    db.update("INSERT IGNORE INTO drilling_engineering_revision_data(revision_id,survey_version_id,data_label) VALUES(?,?,?)",revisionId,version,label==null?"轨迹数据":label);
    audit(id(n.get("field_id")),"LINK_REVISION_DATA",Map.of("revisionId",revisionId,"versionId",version));
  }
  @Transactional public void registerImportedTrajectory(Long fieldId,Long wellId,Long wellboreId,Long versionId,Long sourceId,String kind,String sourceLabel,String businessRevision,String sourceRole){
    access(fieldId,true);
    var asset=db.queryForMap("SELECT w.well_id,w.well_code,w.well_name,b.wellbore_id,b.wellbore_code,b.wellbore_name FROM drilling_well w JOIN drilling_wellbore b ON b.well_id=w.well_id JOIN drilling_field_wellbore x ON x.wellbore_id=b.wellbore_id WHERE x.field_id=? AND w.well_id=? AND b.wellbore_id=?",fieldId,wellId,wellboreId);
    String nodeType="DESIGN".equals(kind)?"PLAN":"SURVEY";
    String boreKey=NativeEngineSupport.hash(fieldId+":WELLBORE:"+wellboreId);
    String wellKey=NativeEngineSupport.hash(fieldId+":WELL:"+wellId);
    // Importers and interactive management resolve the same registry entity,
    // irrespective of the key format used by an earlier importer.
    var existingWells=db.queryForList("SELECT node_id FROM drilling_engineering_node WHERE field_id=? AND node_type='WELL' AND well_id=? AND status='ACTIVE' ORDER BY node_id",fieldId,wellId);
    if(existingWells.isEmpty())db.update("INSERT IGNORE INTO drilling_engineering_node(node_key,field_id,parent_id,node_type,node_name,node_code,well_id,wellbore_id,entity_kind,properties_json,create_by) VALUES(?,?,NULL,'WELL',?,?,?,NULL,'WELL','{}',?)",wellKey,fieldId,asset.get("well_name"),asset.get("well_code"),wellId,ShiroUtils.getLoginName());
    Long wellNode=existingWells.isEmpty()?db.queryForObject("SELECT node_id FROM drilling_engineering_node WHERE node_key=?",Long.class,wellKey):id(existingWells.get(0).get("node_id"));
    var existingBores=db.queryForList("SELECT node_id FROM drilling_engineering_node WHERE field_id=? AND node_type='WELLBORE' AND wellbore_id=? AND status='ACTIVE' ORDER BY node_id",fieldId,wellboreId);
    if(existingBores.isEmpty())db.update("INSERT IGNORE INTO drilling_engineering_node(node_key,field_id,parent_id,node_type,node_name,node_code,well_id,wellbore_id,entity_kind,properties_json,create_by) VALUES(?,?,?,'WELLBORE',?,?,?,?, 'WELL','{}',?)",boreKey,fieldId,wellNode,asset.get("wellbore_name"),asset.get("wellbore_code"),wellId,wellboreId,ShiroUtils.getLoginName());
    Long boreNode=existingBores.isEmpty()?db.queryForObject("SELECT node_id FROM drilling_engineering_node WHERE node_key=?",Long.class,boreKey):id(existingBores.get(0).get("node_id"));
    var version=db.queryForMap("SELECT t.trajectory_version_no AS version_no,s.parent_version_id,t.label,t.trajectory_kind FROM drilling_survey_version s JOIN drilling_trajectory_version t ON t.survey_version_id=s.survey_version_id WHERE s.survey_version_id=? AND t.wellbore_id=? AND t.trajectory_kind=?",versionId,wellboreId,kind);
    String title=sourceLabel==null||sourceLabel.isBlank()?Objects.toString(version.get("label"),asset.get("wellbore_name").toString()):sourceLabel;
    // A file label is not a new engineering result. Explicit separate plans
    // use createRevision/createNode; ordinary re-surveys stay in one series.
    String outcome="DESIGN".equals(kind)?"设计轨迹":"实钻测斜";
    String nodeKey=NativeEngineSupport.hash(fieldId+":"+wellboreId+":"+nodeType+":"+outcome);
    var existingResults=db.queryForList("SELECT DISTINCT n.node_id FROM drilling_engineering_node n LEFT JOIN drilling_engineering_revision r ON r.node_id=n.node_id LEFT JOIN drilling_engineering_revision_data rd ON rd.revision_id=r.revision_id WHERE n.field_id=? AND n.wellbore_id=? AND n.node_type=? AND n.status='ACTIVE' AND (rd.survey_version_id=? OR n.node_name=?) ORDER BY n.node_id",fieldId,wellboreId,nodeType,versionId,outcome);
    if(existingResults.isEmpty())db.update("INSERT IGNORE INTO drilling_engineering_node(node_key,field_id,parent_id,node_type,node_name,node_code,well_id,wellbore_id,entity_kind,properties_json,create_by) VALUES(?,?,?,?,?,?,?,?, 'WELL',?,?)",nodeKey,fieldId,boreNode,nodeType,outcome,outcome,wellId,wellboreId,JSON.toJSONString(Map.of("source","SURVEY_IMPORT")),ShiroUtils.getLoginName());
    Long resultNode=existingResults.isEmpty()?db.queryForObject("SELECT node_id FROM drilling_engineering_node WHERE node_key=?",Long.class,nodeKey):id(existingResults.get(0).get("node_id"));
    // Reuse the business revision for a repeated snapshot, while retaining
    // an additional original representation when a different source is supplied.
    var registered=db.queryForList("SELECT r.revision_id FROM drilling_engineering_revision_data rd JOIN drilling_engineering_revision r ON r.revision_id=rd.revision_id WHERE r.node_id=? AND rd.survey_version_id=? AND JSON_EXTRACT(r.properties_json,'$.canonical_revision_id') IS NULL ORDER BY r.revision_id",resultNode,versionId);
    if(!registered.isEmpty()){
      if(sourceId!=null){var body=new LinkedHashMap<String,Object>();body.put("documentRole",sourceRole==null?"TRAJECTORY_DATA":sourceRole);body.put("revisionId",id(registered.get(0).get("revision_id")));body.put("title",title);body.put("fileLabel",title);attachFile(resultNode,sourceId,body);}
      return;
    }
    String business=businessRevision==null||businessRevision.isBlank()?"DATA-v"+version.get("version_no"):businessRevision;
    String revisionKey=NativeEngineSupport.hash("SURVEY_VERSION:"+versionId);
    Long parentRevision=null;Long parentVersion=id(version.get("parent_version_id"));
    if(parentVersion!=null){var prior=db.queryForList("SELECT rd.revision_id FROM drilling_engineering_revision_data rd JOIN drilling_engineering_revision r ON r.revision_id=rd.revision_id WHERE r.node_id=? AND rd.survey_version_id=?",resultNode,parentVersion);if(!prior.isEmpty())parentRevision=id(prior.get(0).get("revision_id"));}
    db.update("INSERT IGNORE INTO drilling_engineering_revision(node_id,revision_key,business_revision,outcome_name,phase,usage_state,parent_revision_id,properties_json,create_by) VALUES(?,?,?,?,?,'HISTORICAL',?,?,?)",resultNode,revisionKey,business,title,"DESIGN".equals(kind)?"PLAN":"ACTUAL",parentRevision,JSON.toJSONString(Map.of("survey_version_id",versionId,"trajectory_kind",kind)),ShiroUtils.getLoginName());
    Long revisionId=db.queryForObject("SELECT revision_id FROM drilling_engineering_revision WHERE node_id=? AND revision_key=?",Long.class,resultNode,revisionKey);
    db.update("INSERT IGNORE INTO drilling_engineering_revision_data(revision_id,survey_version_id,data_label) VALUES(?,?,?)",revisionId,versionId,title);
    if(sourceId!=null){var body=new LinkedHashMap<String,Object>();body.put("documentRole",sourceRole==null?"TRAJECTORY_DATA":sourceRole);body.put("revisionId",revisionId);body.put("title",title);body.put("fileLabel",title);attachFile(resultNode,sourceId,body);}
  }
  @Transactional public void registerWellboreReference(Long fieldId,Long wellboreId){
    access(fieldId,true);
    var asset=db.queryForMap("SELECT w.well_id,w.field_id,w.well_code,w.well_name,b.wellbore_code,b.wellbore_name FROM drilling_wellbore b JOIN drilling_well w ON w.well_id=b.well_id JOIN drilling_field_wellbore x ON x.wellbore_id=b.wellbore_id WHERE x.field_id=? AND b.wellbore_id=?",fieldId,wellboreId);
    access(id(asset.get("field_id")),false);
    Long wellId=id(asset.get("well_id"));
    var wellNodes=db.queryForList("SELECT node_id FROM drilling_engineering_node WHERE field_id=? AND well_id=? AND node_type='WELL' AND status='ACTIVE' ORDER BY node_id",fieldId,wellId);
    String wellKey=NativeEngineSupport.hash(fieldId+":WELL:"+wellId),boreKey=NativeEngineSupport.hash(fieldId+":WELLBORE:"+wellboreId);
    String properties=JSON.toJSONString(Map.of("reference_only",!fieldId.equals(id(asset.get("field_id"))),"source_field_id",asset.get("field_id")));
    if(wellNodes.isEmpty())db.update("INSERT INTO drilling_engineering_node(node_key,field_id,node_type,node_name,node_code,well_id,entity_kind,properties_json,create_by) VALUES(?,?,'WELL',?,?,?,'WELL',?,?) ON DUPLICATE KEY UPDATE status='ACTIVE'",wellKey,fieldId,asset.get("well_name"),asset.get("well_code"),wellId,properties,ShiroUtils.getLoginName());
    Long parent=wellNodes.isEmpty()?db.queryForObject("SELECT node_id FROM drilling_engineering_node WHERE node_key=?",Long.class,wellKey):id(wellNodes.get(0).get("node_id"));
    if(db.queryForObject("SELECT COUNT(*) FROM drilling_engineering_node WHERE field_id=? AND wellbore_id=? AND node_type='WELLBORE' AND status='ACTIVE'",Integer.class,fieldId,wellboreId)==0)db.update("INSERT INTO drilling_engineering_node(node_key,field_id,parent_id,node_type,node_name,node_code,well_id,wellbore_id,entity_kind,properties_json,create_by) VALUES(?,?,?,'WELLBORE',?,?,?,?,'WELL',?,?) ON DUPLICATE KEY UPDATE status='ACTIVE'",boreKey,fieldId,parent,asset.get("wellbore_name"),asset.get("wellbore_code"),wellId,wellboreId,properties,ShiroUtils.getLoginName());
  }
  @Transactional public Map<String,Object> activate(Long revisionId,Map<String,Object>b){
    var r=revision(revisionId,true);var n=node(id(r.get("node_id")),true);Long nid=id(n.get("node_id"));
    db.queryForList("SELECT node_id FROM drilling_engineering_node WHERE node_id=? FOR UPDATE",nid);
    if(Set.of("FORBIDDEN","UNASSIGNED").contains(r.get("usage_state")))throw new IllegalArgumentException("禁止使用或归属冲突的资料不能发布");
    Long expected=id(b.get("expectedRevisionId"));
    var heads=db.queryForList("SELECT * FROM drilling_engineering_head WHERE node_id=?",nid);
    Long current=heads.isEmpty()?null:id(heads.get(0).get("revision_id"));
    if(!b.containsKey("expectedRevisionId")||!Objects.equals(current,expected))throw new ResponseStatusException(HttpStatus.CONFLICT,"当前业务版本已变化，请刷新后重新选择");
    String note=required(b,"note",1000);Long version=id(b.get("surveyVersionId"));
    if(version!=null){
      if(db.queryForObject("SELECT COUNT(*) FROM drilling_engineering_revision_data WHERE revision_id=? AND survey_version_id=?",Integer.class,revisionId,version)!=1)throw new IllegalArgumentException("所选数据不属于该业务版本");
      var t=db.queryForMap("SELECT t.*,b.well_id FROM drilling_trajectory_version t JOIN drilling_wellbore b ON b.wellbore_id=t.wellbore_id WHERE t.survey_version_id=?",version);
      if(!Objects.equals(n.get("wellbore_id"),t.get("wellbore_id")))throw new IllegalArgumentException("井眼归属未确认，不能用于计算");
      trajectories.lockWell(id(t.get("well_id")));
      trajectories.setHead(id(t.get("wellbore_id")),t.get("trajectory_kind").toString(),version,id(t.get("well_id")));
    }
    if(current!=null)db.update("UPDATE drilling_engineering_revision SET usage_state='HISTORICAL',row_version=row_version+1 WHERE revision_id=?",current);
    db.update("INSERT INTO drilling_engineering_head(node_id,revision_id,survey_version_id,activated_by) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE revision_id=VALUES(revision_id),survey_version_id=VALUES(survey_version_id),activated_by=VALUES(activated_by),activated_at=NOW()",nid,revisionId,version,ShiroUtils.getLoginName());
    db.update("UPDATE drilling_engineering_revision SET usage_state='ACTIVE',row_version=row_version+1,update_time=NOW() WHERE revision_id=?",revisionId);
    audit(id(n.get("field_id")),"PUBLISH_BUSINESS_REVISION",Map.of("revisionId",revisionId,"previous",current==null?0:current,"dataVersionId",version==null?0:version,"note",note));return detail(revisionId);
  }
  @Transactional public Map<String,Object> attachFile(Long nodeId,Long sourceId,Map<String,Object>b){
    var n=node(nodeId,true);Long p=id(n.get("field_id"));
    if(!"ACTIVE".equals(n.get("status"))||Boolean.TRUE.equals(obj(n.getOrDefault("properties",n.get("properties_json"))).get("reference_only")))throw new IllegalArgumentException("请在所属井场选择可编辑的资料目录");
    var sources=db.queryForList("SELECT s.*,f.original_name,f.display_name,f.sha256 FROM drilling_source_document s JOIN data_file f ON f.file_id=s.file_id WHERE s.source_id=?",sourceId);
    if(sources.isEmpty())throw new IllegalArgumentException("来源文件不存在");var s=sources.get(0);access(id(s.get("field_id")),false);
    // Linking an accessible original is read-only with respect to its owner;
    // shared multiwell reports do not need another upload or another source.
    boolean referenceOnly=!p.equals(id(s.get("field_id")));
    String role=required(b,"documentRole",32);
    if(!Set.of("TRAJECTORY_DATA","TRAJECTORY_REPORT","WELL_SUMMARY","ENGINEERING_REPORT","PRODUCTION_DATA","ARCHIVE","REFERENCE").contains(role))throw new IllegalArgumentException("文件用途无效");
    String trajectoryKind=str(b,"trajectoryKind","");
    if(trajectoryKind.isBlank()&&Set.of("TRAJECTORY_DATA","TRAJECTORY_REPORT").contains(role)){
      if("PLAN".equals(n.get("node_type")))trajectoryKind="DESIGN";
      if("SURVEY".equals(n.get("node_type")))trajectoryKind="ACTUAL";
    }
    validateFileUsage(n,role,trajectoryKind);
    Long rid=id(b.get("revisionId"));
    if(rid!=null&&!nodeId.equals(id(revision(rid,true).get("node_id"))))throw new IllegalArgumentException("文件版本与所选成果不属于同一节点");
    String title=str(b,"title",s.get("original_name").toString());if(title.length()>255)throw new IllegalArgumentException("资料名称过长");
    Long document=id(b.get("documentId"));
    if(document!=null){
      db.queryForList("SELECT document_id FROM drilling_engineering_document WHERE document_id=? FOR UPDATE",document);
      var d=documentContext(p,document,true);
      if(!nodeId.equals(id(d.get("node_id")))||b.containsKey("revisionId")&&!Objects.equals(rid,id(d.get("revision_id"))))throw new IllegalArgumentException("文件历史必须属于同一节点和业务修订");
      if(!role.equals(d.get("document_role")))throw new IllegalArgumentException("文件新版本不能更改资料用途");
      String originalKind=Objects.toString(d.get("trajectory_kind"),"");
      if(!originalKind.isBlank()&&!originalKind.equals(trajectoryKind))throw new IllegalArgumentException("文件新版本不能更改设计或实钻类别");
      rid=id(d.get("revision_id"));
    }
    else{
      var linked=db.queryForList("SELECT d.document_id FROM drilling_engineering_document d JOIN drilling_engineering_document_file df ON df.document_id=d.document_id WHERE d.node_id=? AND d.revision_id <=> ? AND d.document_role=? AND df.source_id=? AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(df.properties_json,'$.trajectory_kind')),'')=? AND JSON_EXTRACT(df.properties_json,'$.canonical_document_file_id') IS NULL ORDER BY d.document_id",nodeId,rid,role,sourceId,trajectoryKind);
      if(!linked.isEmpty())document=id(linked.get(0).get("document_id"));
      else{
        String key=NativeEngineSupport.hash(nodeId+":"+rid+":"+role+":"+title+(trajectoryKind.isBlank()?"":":"+trajectoryKind));
        db.update("INSERT IGNORE INTO drilling_engineering_document(document_key,node_id,revision_id,document_role,document_title) VALUES(?,?,?,?,?)",key,nodeId,rid,role,title);
        document=db.queryForObject("SELECT document_id FROM drilling_engineering_document WHERE document_key=?",Long.class,key);
      }
    }
    db.queryForList("SELECT document_id FROM drilling_engineering_document WHERE document_id=? FOR UPDATE",document);
    var existing=db.queryForList("SELECT df.document_file_id,df.edition_no FROM drilling_engineering_document_file df JOIN drilling_source_document sd ON sd.source_id=df.source_id JOIN data_file original ON original.file_id=sd.file_id WHERE df.document_id=? AND original.sha256=?",document,s.get("sha256"));
    if(!existing.isEmpty())return Map.of("documentId",document,"sourceId",sourceId,"editionNo",existing.get(0).get("edition_no"),"alreadyLinked",true);
    var latest=db.queryForList("SELECT edition_no FROM drilling_engineering_document_file WHERE document_id=? ORDER BY edition_no DESC LIMIT 1 FOR UPDATE",document);
    int current=latest.isEmpty()?0:((Number)latest.get(0).get("edition_no")).intValue();
    if(b.containsKey("documentId")&&(!b.containsKey("expectedEditionNo")||id(b.get("expectedEditionNo"))==null||id(b.get("expectedEditionNo")).intValue()!=current))throw new ResponseStatusException(HttpStatus.CONFLICT,"该文件已有新版本，请刷新文件版次后重新上传");
    int edition=current+1;
    var properties=new LinkedHashMap<String,Object>();properties.put("assignedBy",ShiroUtils.getLoginName());properties.put("note",str(b,"note",""));properties.put("reference_only",referenceOnly);
    if(!trajectoryKind.isBlank())properties.put("trajectory_kind",trajectoryKind);
    db.update("INSERT IGNORE INTO drilling_engineering_document_file(document_id,source_id,edition_no,representation_format,file_label,properties_json) VALUES(?,?,?,?,?,?)",document,sourceId,edition,s.get("source_format"),str(b,"fileLabel",s.get("original_name").toString()),JSON.toJSONString(properties));
    audit(p,"ATTACH_ENGINEERING_FILE",Map.of("nodeId",nodeId,"documentId",document,"sourceId",sourceId,"editionNo",edition));return Map.of("documentId",document,"sourceId",sourceId,"editionNo",edition,"alreadyLinked",false);
  }
  public List<Map<String,Object>> documentHistory(Long docId){
    var d=db.queryForMap("SELECT * FROM drilling_engineering_document WHERE document_id=?",docId);node(id(d.get("node_id")),false);
    return db.queryForList("SELECT df.*,s.field_id AS source_field_id,s.source_path,f.original_name,f.display_name,f.sha256 FROM drilling_engineering_document_file df JOIN drilling_source_document s ON s.source_id=df.source_id JOIN data_file f ON f.file_id=s.file_id WHERE document_id=? ORDER BY edition_no DESC",docId);
  }

  @Transactional public void updateFileStatus(Long fieldId, Long documentId, String usageState){
    access(fieldId,true);
    if(!Set.of("ACTIVE","HISTORICAL","WITHDRAWN","FORBIDDEN").contains(usageState))
      throw new IllegalArgumentException("文件状态无效");
    var doc=documentContext(fieldId,documentId,true);
    if(!"ACTIVE".equals(usageState))for(var file:db.queryForList("SELECT s.file_id FROM drilling_engineering_document_file df JOIN drilling_source_document s ON s.source_id=df.source_id WHERE df.document_id=?",documentId))EngineeringUsageGuard.requireFileUnused(db,id(file.get("file_id")));
    int changed=db.update("UPDATE drilling_engineering_document_file SET usage_state=? WHERE document_id=? AND edition_no=(SELECT latest.edition_no FROM (SELECT MAX(edition_no) edition_no FROM drilling_engineering_document_file WHERE document_id=?) latest)",usageState,documentId,documentId);
    if(changed!=1)throw new IllegalArgumentException("文件版次不存在");
    audit(fieldId,"UPDATE_FILE_STATUS",Map.of("documentId",documentId,"usageState",usageState));
  }

  public Map<String,Object> fileImpact(Long fieldId,Long documentId){
    documentContext(fieldId,documentId,false);var rows=db.queryForList("SELECT s.file_id FROM drilling_engineering_document_file df JOIN drilling_source_document s ON s.source_id=df.source_id WHERE df.document_id=? ORDER BY df.edition_no DESC",documentId);
    for(var row:rows){var impact=EngineeringUsageGuard.file(db,id(row.get("file_id")));if(Boolean.TRUE.equals(impact.get("blocked")))return impact;}
    return Map.of("blocked",false,"reason","");
  }
  @Transactional public void deleteDocument(Long fieldId,Long documentId){
    documentContext(fieldId,documentId,true);
    for(var row:db.queryForList("SELECT s.file_id FROM drilling_engineering_document_file df JOIN drilling_source_document s ON s.source_id=df.source_id WHERE df.document_id=?",documentId))EngineeringUsageGuard.requireFileUnused(db,id(row.get("file_id")));
    db.update("UPDATE drilling_engineering_document_file SET usage_state='DELETED' WHERE document_id=?",documentId);
    audit(fieldId,"DELETE_ENGINEERING_DOCUMENT",Map.of("documentId",documentId));
  }

  /** Return each distinct immutable source object below an engineering node. */
  public List<Map<String,Object>> sourceFilesForExport(Long fieldId, Long nodeId) {
    nodeInField(fieldId, nodeId, false);
    if (isScope(nodeId)) {
      return db.queryForList(
          "SELECT DISTINCT s.source_id,s.field_id AS storage_field_id,f.file_id,f.original_name,f.content_type,f.file_size,f.sha256,s.source_path "
              + "FROM drilling_engineering_document d JOIN drilling_engineering_document_file df ON df.document_id=d.document_id "
              + "JOIN drilling_source_document s ON s.source_id=df.source_id JOIN data_file f ON f.file_id=s.file_id "
              + "JOIN drilling_engineering_node n ON n.node_id=d.node_id WHERE n.field_id=? "
              + "AND JSON_EXTRACT(df.properties_json,'$.canonical_document_file_id') IS NULL ORDER BY s.source_id",
          fieldId);
    }
    return db.queryForList(
        descendants()
            + "SELECT DISTINCT s.source_id,s.field_id AS storage_field_id,f.file_id,f.original_name,f.content_type,f.file_size,f.sha256,s.source_path "
            + "FROM drilling_engineering_document d JOIN drilling_engineering_document_file df ON df.document_id=d.document_id "
            + "JOIN drilling_source_document s ON s.source_id=df.source_id JOIN data_file f ON f.file_id=s.file_id "
            + "WHERE d.node_id IN (SELECT node_id FROM scope) "
            + "AND JSON_EXTRACT(df.properties_json,'$.canonical_document_file_id') IS NULL ORDER BY s.source_id",
        nodeId);
  }
}
