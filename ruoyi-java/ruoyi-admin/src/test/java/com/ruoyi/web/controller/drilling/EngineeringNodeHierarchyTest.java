package com.ruoyi.web.controller.drilling;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EngineeringNodeHierarchyTest {
  private final Map<String,Object> field = Map.of("node_id",-2_000_000_000_001L,"field_id",1L,"node_type","FIELD");
  private final Map<String,Object> well = Map.of("node_id",15L,"field_id",1L,"node_type","WELL");
  private final Map<String,Object> bore = Map.of("node_id",16L,"field_id",1L,"node_type","WELLBORE");

  @Test void wellIsStoredAtItsFieldRootAndCanBeEditedWithNullStoredParent() {
    assertNull(EngineeringNodeHierarchy.creationParent("WELL",1L,field));
    var old=new HashMap<String,Object>(well);old.put("parent_id",null);
    assertNull(EngineeringNodeHierarchy.updateParent(old,field));
  }

  @Test void legacySyntheticFieldParentHasTheSameOwnershipAsNullStoredParent() {
    var old=new HashMap<String,Object>(well);old.put("parent_id",field.get("node_id"));
    assertNull(EngineeringNodeHierarchy.updateParent(old,field));
  }

  @Test void wellAndWellboreAlwaysRequireTheCorrectParent() {
    assertThrows(IllegalArgumentException.class,()->EngineeringNodeHierarchy.creationParent("WELL",1L,null));
    assertThrows(IllegalArgumentException.class,()->EngineeringNodeHierarchy.creationParent("WELLBORE",1L,field));
    assertEquals(15L,EngineeringNodeHierarchy.creationParent("WELLBORE",1L,well));
    assertEquals(16L,EngineeringNodeHierarchy.creationParent("PLAN",1L,bore));
    assertEquals(16L,EngineeringNodeHierarchy.creationParent("SURVEY",1L,bore));
  }

  @Test void missingOrCrossFieldOwnershipIsRejectedBeforeAnyWrites() {
    assertThrows(IllegalArgumentException.class,()->EngineeringNodeHierarchy.creationParent("WELL",null,field));
    assertThrows(IllegalArgumentException.class,()->EngineeringNodeHierarchy.creationParent("WELL",2L,field));
    assertThrows(IllegalArgumentException.class,()->EngineeringNodeHierarchy.creationParent("WELLBORE",2L,well));
  }

  @Test void ordinaryEditingCannotReparentAnEngineeringObject() {
    var old=new HashMap<String,Object>(bore);old.put("parent_id",15L);
    assertEquals(15L,EngineeringNodeHierarchy.updateParent(old,well));
    assertThrows(IllegalArgumentException.class,()->EngineeringNodeHierarchy.updateParent(old,Map.of("node_id",17L,"node_type","WELL","field_id",1L)));
    assertThrows(IllegalArgumentException.class,()->EngineeringNodeHierarchy.updateParent(old,null));
  }

  @Test void wellboreStatusDefaultsAndAllEngineeringStatesRemainAvailable() {
    assertEquals("UNPLANNED",EngineeringNodeHierarchy.engineeringStatus(null,"UNPLANNED"));
    for(String status:new String[]{"UNPLANNED","PLANNED","DRILLING","SUSPENDED","COMPLETED","ABANDONED","UNKNOWN"})
      assertEquals(status,EngineeringNodeHierarchy.engineeringStatus(status,"UNKNOWN"));
    assertThrows(IllegalArgumentException.class,()->EngineeringNodeHierarchy.engineeringStatus("ACTIVE","UNKNOWN"));
  }
}
