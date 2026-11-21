package com.ruoyi.web.controller.drilling;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** File operations use their engineering target without creating any well. */
class EngineeringLibraryFilesTest {
  static final long FIELD_NODE=-2_000_000_000_001L;

  @Test void attachingAtAFieldCreatesOnlyItsFileFolder(){
    var library=new Fixture();
    var controller=new EngineeringLibraryController(library,null);
    controller.attachDocument(1L,FIELD_NODE,Map.of("sourceId",73L,"documentRole","REFERENCE"));
    assertEquals(1,library.creates.size());
    assertEquals("INBOX",library.creates.get(0).get("nodeType"));
    assertEquals(FIELD_NODE,library.creates.get(0).get("parentId"));
    assertEquals(1L,library.creates.get(0).get("fieldId"));
    assertEquals(901L,library.attachedNode);
    assertEquals(73L,library.attachedSource);
    assertEquals(1,library.jdbc.locks);
  }

  @Test void repeatedFieldFileOperationsReuseTheSameFolder(){
    var library=new Fixture();library.jdbc.folderId=901L;
    assertEquals(901L,library.fileTarget(1L,FIELD_NODE).get("node_id"));
    assertEquals(901L,library.uploadTarget(1L,FIELD_NODE,null,"ENGINEERING_REPORT","").get("node_id"));
    assertTrue(library.creates.isEmpty());assertEquals(2,library.jdbc.locks);
  }

  @Test void existingFileFoldersAndWellboresNeverCreateOtherEngineeringNodes(){
    var library=new Fixture();
    assertEquals(901L,library.uploadTarget(1L,901L,null,"REFERENCE","").get("node_id"));
    assertEquals(902L,library.uploadTarget(1L,902L,31L,"TRAJECTORY_DATA","ACTUAL").get("node_id"));
    assertTrue(library.creates.isEmpty());assertEquals(0,library.jdbc.locks);
  }

  @Test void referencesAndArchivedTargetsCannotReceiveFileMutations(){
    var library=new Fixture();
    library.nodes.get(902L).put("properties_json","{\"reference_only\":true}");
    assertThrows(IllegalArgumentException.class,()->library.fileTarget(1L,902L));
    library.nodes.get(902L).put("properties_json","{}");library.nodes.get(902L).put("status","ARCHIVED");
    assertThrows(IllegalArgumentException.class,()->library.fileTarget(1L,902L));
    assertThrows(IllegalArgumentException.class,()->library.fileTarget(2L,901L));
    assertTrue(library.creates.isEmpty());
  }

  @Test void uploadingOrLinkingTrajectoryDataRequiresTheSameClassification(){
    var field=Map.<String,Object>of("node_type","INBOX");
    var bore=Map.<String,Object>of("node_type","WELLBORE","wellbore_id",31L);
    var plan=Map.<String,Object>of("node_type","PLAN","wellbore_id",31L);
    assertDoesNotThrow(()->EngineeringLibraryService.validateFileUsage(field,"ENGINEERING_REPORT",""));
    assertThrows(IllegalArgumentException.class,()->EngineeringLibraryService.validateFileUsage(field,"TRAJECTORY_DATA","ACTUAL"));
    assertThrows(IllegalArgumentException.class,()->EngineeringLibraryService.validateFileUsage(bore,"TRAJECTORY_DATA",""));
    assertThrows(IllegalArgumentException.class,()->EngineeringLibraryService.validateFileUsage(plan,"TRAJECTORY_DATA","ACTUAL"));
    assertDoesNotThrow(()->EngineeringLibraryService.validateFileUsage(plan,"TRAJECTORY_DATA","DESIGN"));
  }

  static final class Fixture extends EngineeringLibraryService {
    final RecordingJdbc jdbc;final Map<Long,Map<String,Object>> nodes=new HashMap<>();
    final List<Map<String,Object>> creates=new ArrayList<>();Long attachedNode,attachedSource;
    Fixture(){this(new RecordingJdbc());}
    Fixture(RecordingJdbc jdbc){
      super(new SourceRegistrySupport(jdbc),null);this.jdbc=jdbc;
      nodes.put(FIELD_NODE,new HashMap<>(Map.of("node_id",FIELD_NODE,"field_id",1L,"node_type","FIELD","status","ACTIVE")));
      nodes.put(901L,new HashMap<>(Map.of("node_id",901L,"field_id",1L,"node_type","INBOX","status","ACTIVE")));
      nodes.put(902L,new HashMap<>(Map.of("node_id",902L,"field_id",1L,"node_type","WELLBORE","wellbore_id",31L,"status","ACTIVE")));
    }
    @Override public Map<String,Object> nodeInField(Long fieldId,Long nodeId,boolean write){
      var node=nodes.get(nodeId);if(node==null||!fieldId.equals(node.get("field_id")))throw new IllegalArgumentException("节点不属于所选井场");
      return new HashMap<>(node);
    }
    @Override public Map<String,Object> createNode(Map<String,Object> body){creates.add(new HashMap<>(body));jdbc.folderId=901L;return nodeInField(1L,901L,true);}
    @Override public Map<String,Object> attachFile(Long nodeId,Long sourceId,Map<String,Object> body){attachedNode=nodeId;attachedSource=sourceId;return Map.of("documentId",81L);}
    @Override void access(Long fieldId,boolean write){assertEquals(1L,fieldId);}
  }
  static final class RecordingJdbc extends JdbcTemplate {
    Long folderId;int locks;
    @Override public List<Map<String,Object>> queryForList(String sql,Object...args){
      if(sql.contains("FOR UPDATE")){locks++;return List.of(Map.of("field_id",1L));}
      if(sql.contains("node_type='INBOX'"))return folderId==null?List.of():List.of(Map.of("node_id",folderId));
      throw new AssertionError("Unexpected database read: "+sql);
    }
  }
}
