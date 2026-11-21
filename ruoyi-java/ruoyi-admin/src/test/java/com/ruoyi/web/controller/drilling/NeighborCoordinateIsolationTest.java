package com.ruoyi.web.controller.drilling;

import static org.junit.jupiter.api.Assertions.*;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.framework.security.DrillingDataScope;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** A linked local test trajectory must not abort discovery for projected field data. */
class NeighborCoordinateIsolationTest {
  @Test void incompatibleReferenceStaysVisibleButNeverReachesGeometryOrBecomesSafe() throws Exception {
    var beanFactoryField=SpringUtils.class.getDeclaredField("beanFactory");
    beanFactoryField.setAccessible(true);var previous=beanFactoryField.get(null);
    var factory=new DefaultListableBeanFactory();
    factory.registerSingleton("scope",new DrillingDataScope(new JdbcTemplate(),null){
      @Override public void requireAccess(Long fieldId){assertEquals(1L,fieldId);}
    });
    new SpringUtils().postProcessBeanFactory(factory);
    try {
      var jdbc=new NeighborJdbc();var engine=new BoundsEngine();
      var inputs=new TrajectoryInputSupport(jdbc,engine){
        @Override public Map<String,Object> version(Long fieldId,Long versionId){
          var ref=versionId==309L?Map.<String,Object>of("crs","","depth_datum_elevation_m",0):
              Map.<String,Object>of("crs","EPSG:23031","depth_datum_elevation_m",versionId==100L?54.9:25.0);
          return Map.of("reference",ref,"stations",List.of(
              Map.of("md",109.0,"north",6477887.0,"east",437506.0,"tvd",109.0),
              Map.of("md",139.0,"north",6477888.0,"east",437507.0,"tvd",139.0)));
        }
      };
      var controller=new SourceRegistryController(new SourceRegistrySupport(jdbc),engine,null,null,inputs,null,null,new DataSourceTransactionManager());
      var neighbors=controller.neighbors(1L,31L);
      assertEquals(2,neighbors.size());
      var valid=neighbors.get(0);assertEquals(true,valid.get("included"));
      assertEquals(true,valid.get("coordinate_compatible"));assertEquals(true,valid.get("relationship_confirmed"));
      var invalid=neighbors.get(1);assertEquals(false,invalid.get("included"));
      assertEquals(false,invalid.get("usable"));assertEquals(false,invalid.get("coordinate_compatible"));
      assertEquals(true,invalid.get("requested_included"));assertEquals("MANUAL",invalid.get("relationship_source"));
      assertTrue(invalid.get("evaluation_message").toString().contains("坐标基准不一致"));
      assertEquals(2,engine.calls); // reference and valid neighbor, never the incompatible local test.
    } finally {beanFactoryField.set(null,previous);}
  }

  static final class BoundsEngine extends NativeEngineSupport {
    int calls;BoundsEngine(){super(new JdbcTemplate());}
    @Override public Map<String,Object> config(Long fieldId){return Map.of();}
    @Override public Map<String,Object> json(String path,Object body){
      assertEquals("/survey/horizontal-bounds",path);calls++;
      var input=(Map<?,?>)body;
      assertEquals("EPSG:23031",((Map<?,?>)input.get("reference")).get("crs"));
      assertEquals("EPSG:23031",((Map<?,?>)input.get("target_reference")).get("crs"));
      return Map.of("bounds",List.of(6477887.0,6477888.0,437506.0,437507.0));
    }
  }
  static final class NeighborJdbc extends JdbcTemplate {
    @Override public List<Map<String,Object>> queryForList(String sql,Object...args){
      if(sql.contains("SELECT b.*"))return List.of(Map.of("wellbore_id",31L));
      if(sql.contains("WITH available")){
        var valid=new HashMap<String,Object>(Map.of("wellbore_id",32L,"survey_version_id",101L,"quality_status","READY","source_review_status","CONFIRMED"));
        var invalid=new HashMap<String,Object>(Map.of("wellbore_id",227L,"survey_version_id",309L,"quality_status","READY","source_review_status","CONFIRMED","selection_included",1));
        return List.of(valid,invalid);
      }
      if(sql.contains("SELECT h.survey_version_id"))return List.of(Map.of("survey_version_id",100L));
      throw new AssertionError("Unexpected query: "+sql);
    }
  }
}
