package com.ruoyi.web.controller.drilling;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TrajectoryReferenceFramesTest {
  static Map<String,Object> projected(String crs,double height){return Map.of("crs",crs,"depth_datum_elevation_m",height,"reference_status","READY");}

  @Test void aLocalTestCaseCannotBeMixedWithProjectedBusinessData(){
    var local=Map.<String,Object>of("crs","","local_frame_id","TEST_B","depth_datum_elevation_m",0);
    assertNotNull(TrajectoryReferenceFrames.incompatibility(local,projected("EPSG:23031",54.9)));
    assertNotNull(TrajectoryReferenceFrames.incompatibility(projected("EPSG:23031",54.9),local));
    assertFalse(TrajectoryReferenceFrames.sameFrame(local,projected("EPSG:23031",54.9)));
  }
  @Test void compatibleProjectedFramesMayStillRequireHorizontalOrVerticalConversion(){
    assertNull(TrajectoryReferenceFrames.incompatibility(projected("EPSG:23031",25),projected("EPSG:32631",54.9)));
    assertFalse(TrajectoryReferenceFrames.sameFrame(projected("EPSG:23031",25),projected("EPSG:32631",54.9)));
    assertFalse(TrajectoryReferenceFrames.sameFrame(projected("EPSG:23031",25),projected("EPSG:23031",54.9)));
    assertTrue(TrajectoryReferenceFrames.sameFrame(projected("EPSG:23031",25),projected("EPSG:23031",25)));
  }
  @Test void localFramesAndDepthDatumsMustMatchTheirDeclaredEvidence(){
    assertNotNull(TrajectoryReferenceFrames.incompatibility(Map.of("local_frame_id","A"),Map.of("local_frame_id","B")));
    assertNotNull(TrajectoryReferenceFrames.incompatibility(Map.of("depth_datum","RT"),Map.of("depth_datum","MSL")));
    assertNotNull(TrajectoryReferenceFrames.incompatibility(Map.of("crs","EPSG:23031"),Map.of("crs","EPSG:23031")));
    assertTrue(TrajectoryReferenceFrames.sameFrame(Map.of("local_frame_id","A"),Map.of("local_frame_id","A")));
  }
  @Test void confirmedStoredStationsAreNotReusedAcrossDifferentDatums(){
    var engine=new RecordingEngine();var service=new TrajectoryInputSupport(new JdbcTemplate(),engine);
    var version=version(projected("EPSG:23031",25));
    var prepared=service.prepare(version,projected("EPSG:23031",54.9));
    assertEquals(1,engine.calls);
    var stations=(List<?>)prepared.get("stations");
    assertEquals(129.9,((Map<?,?>)stations.get(0)).get("tvd"));
    assertEquals(100.0,((Map<?,?>)((List<?>)version.get("stations")).get(0)).get("tvd"));
  }
  @Test void identicalConfirmedFramesReuseImmutableStationsWithoutAnEngineRoundTrip(){
    var engine=new RecordingEngine();var service=new TrajectoryInputSupport(new JdbcTemplate(),engine);
    var source=projected("EPSG:23031",25);var version=version(source);
    service.prepare(version,source);service.prepare(version,source);
    assertEquals(0,engine.calls);
  }
  @Test void declaredAndConfirmedLocalTestFramesRemainUsableWithoutAnInventedProjection(){
    var ref=new HashMap<String,Object>(Map.of("coordinate_system","LOCAL_ENU","local_frame_id","TEST_CASE_A",
        "reference_status","READY","origin_north",30.0,"origin_east",-4.0,"depth_datum","测试统一深度基准","depth_datum_elevation_m",0));
    var rows=(List<Map<String,Object>>)version(ref).get("stations");
    assertEquals("READY",SourceRegistryController.referenceStatus(ref,rows));
    var other=new HashMap<>(ref);other.put("local_frame_id","TEST_CASE_B");
    assertNotNull(TrajectoryReferenceFrames.incompatibility(ref,other));
  }
  @Test void namingALocalFrameDoesNotConfirmNewOrIncompleteSurveyMetadata(){
    var ref=new HashMap<String,Object>(Map.of("coordinate_system","LOCAL_ENU","local_frame_id","NEW_LOCAL_FRAME",
        "reference_status","PENDING","origin_north",30.0,"origin_east",-4.0,"depth_datum","RT","depth_datum_elevation_m",0));
    var rows=(List<Map<String,Object>>)version(ref).get("stations");
    assertNotEquals("READY",SourceRegistryController.referenceStatus(ref,rows));
    ref.put("reference_status","READY");ref.remove("local_frame_id");
    assertNotEquals("READY",SourceRegistryController.referenceStatus(ref,rows));
    ref.put("local_frame_id","NEW_LOCAL_FRAME");ref.remove("depth_datum_elevation_m");
    assertEquals("VERTICAL_DATUM_UNCONFIRMED",SourceRegistryController.referenceStatus(ref,rows));
  }
  static Map<String,Object> version(Map<String,Object> reference){
    return Map.of("survey_version_id",143L,"reference",reference,"stations",List.of(
        Map.of("md",100.0,"inclination",0.0,"azimuth",0.0,"north",6477887.468,"east",437506.858,"tvd",100.0),
        Map.of("md",130.0,"inclination",0.0,"azimuth",0.0,"north",6477887.468,"east",437506.858,"tvd",130.0)));
  }
  static final class RecordingEngine extends NativeEngineSupport {
    int calls;RecordingEngine(){super(new JdbcTemplate());}
    @Override public Map<String,Object> json(String path,Object body){
      assertEquals("/survey/reduce",path);calls++;
      return Map.of("stations",List.of(Map.of("md",100.0,"tvd",129.9),Map.of("md",130.0,"tvd",159.9)));
    }
  }
}
