package com.ruoyi.web.controller.drilling;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Exercise the service's actual edit path without connecting to business data. */
class EngineeringLibraryEditingTest {
  @Test void editingAFieldRootWellUpdatesBothDirectoryAndRegistry() {
    var service=new Fixture("WELL",null);
    service.updateNode(901L,Map.of("name","更新井名","status","ACTIVE","expectedVersion",1));
    assertEquals(2,service.jdbc.writes.size());
    assertNull(service.jdbc.writes.get(0).args()[0]);
    assertEquals("更新井名",service.jdbc.writes.get(0).args()[1]);
    assertTrue(service.jdbc.writes.get(1).sql().contains("UPDATE drilling_well SET well_name"));
    assertEquals(1,service.audits);
  }

  @Test void editingAWellboreSavesNameAndEngineeringStatusTogether() {
    var service=new Fixture("WELLBORE",900L);
    service.updateNode(901L,Map.of("name","侧钻井眼","engineeringStatus","COMPLETED","expectedVersion",1));
    var registryWrite=service.jdbc.writes.get(1);
    assertTrue(registryWrite.sql().contains("wellbore_name=?,wellbore_code=?,engineering_status=?"));
    assertArrayEquals(new Object[]{"侧钻井眼","侧钻井眼","COMPLETED",99L},registryWrite.args());
  }

  @Test void staleVersionStopsBeforeChangingTheRegistry() {
    var service=new Fixture("WELL",null);service.jdbc.stale=true;
    var error=assertThrows(ResponseStatusException.class,()->service.updateNode(901L,Map.of("name","更新井名","expectedVersion",1)));
    assertEquals(409,error.getStatusCode().value());
    assertEquals(1,service.jdbc.writes.size());assertEquals(0,service.audits);
  }

  @Test void invalidOwnershipAndStateDoNotWriteAnything() {
    var service=new Fixture("WELLBORE",900L);
    assertThrows(IllegalArgumentException.class,()->service.updateNode(901L,Map.of("name","井眼","parentId",902L,"expectedVersion",1)));
    assertThrows(IllegalArgumentException.class,()->service.updateNode(901L,Map.of("name","井眼","engineeringStatus","ACTIVE","expectedVersion",1)));
    assertThrows(IllegalArgumentException.class,()->service.updateNode(901L,Map.of("name","井眼")));
    assertTrue(service.jdbc.writes.isEmpty());
  }

  static final class Fixture extends EngineeringLibraryService {
    final RecordingJdbc jdbc;final Map<String,Object> record=new HashMap<>();int audits;
    Fixture(String type,Long parent){this(new RecordingJdbc(),type,parent);}
    private Fixture(RecordingJdbc jdbc,String type,Long parent){
      super(new SourceRegistrySupport(jdbc),null);this.jdbc=jdbc;
      record.putAll(Map.of("node_id",901L,"field_id",1L,"node_type",type,"node_name","原名称","status","ACTIVE","row_version",1L,"well_id",98L,"wellbore_id",99L,"engineering_status","DRILLING"));
      record.put("parent_id",parent);
    }
    @Override public Map<String,Object> node(Long id,boolean write){return new HashMap<>(record);}
    @Override public Map<String,Object> nodeInField(Long fieldId,Long nodeId,boolean write){return Map.of("node_id",nodeId,"field_id",fieldId,"node_type",nodeId<0?"FIELD":"WELL");}
    @Override void access(Long fieldId,boolean write){assertEquals(1L,fieldId);}
    @Override void audit(Long fieldId,String action,Object detail){audits++;}
  }

  static final class RecordingJdbc extends JdbcTemplate {
    record Write(String sql,Object[] args){}
    final List<Write> writes=new ArrayList<>();boolean stale;
    @Override public List<Map<String,Object>> queryForList(String sql,Object...args){return List.of(Map.of("node_id",901L));}
    @Override public <T>T queryForObject(String sql,Class<T> type,Object...args){return type.cast(type==Integer.class?Integer.valueOf(0):(Object)1L);}
    @Override public int update(String sql,Object...args){writes.add(new Write(sql,args));return stale?0:1;}
  }
}
