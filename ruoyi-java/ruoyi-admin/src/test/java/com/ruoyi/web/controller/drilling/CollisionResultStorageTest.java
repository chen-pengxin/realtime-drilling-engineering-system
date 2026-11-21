package com.ruoyi.web.controller.drilling;

import static org.junit.jupiter.api.Assertions.*;
import com.alibaba.fastjson.JSON;
import java.util.*;
import org.junit.jupiter.api.Test;

class CollisionResultStorageTest {
  @Test void summaryDoesNotDuplicateProfilesAndHistoryRestoresEverySample() {
    var profile=List.of(Map.of("md",109,"sf",1.1),Map.of("md",139,"sf",0.9));
    var pair=Map.<String,Object>of("neighbor_survey_version_id",143,"sf",0.9,"profile",profile);
    var input=Map.<String,Object>of("results",List.of(pair),"pairs",List.of(pair),"nearest_pair",pair,"risk_segments",List.of(Map.of("start_md",139,"end_md",139,"risk_level","RED")));
    var summary=CollisionResultStorage.summary(input);
    assertFalse(summary.containsKey("pairs"));
    assertFalse(((Map<?,?>)((List<?>)summary.get("results")).get(0)).containsKey("profile"));
    assertFalse(((Map<?,?>)summary.get("nearest_pair")).containsKey("profile"));
    assertEquals(profile,pair.get("profile"));
    var restored=CollisionResultStorage.restore(summary,List.of(Map.of("result_json",JSON.toJSONString(pair))));
    assertEquals(JSON.parse(JSON.toJSONString(input.get("results"))),JSON.parse(JSON.toJSONString(restored.get("results"))));
    assertEquals(input.get("risk_segments"),restored.get("risk_segments"));
    assertEquals(input,CollisionResultStorage.restore(input,List.of()));
  }
  @Test void aLargeWellNetworkSummaryStaysSmallWhilePairDetailsRemainComplete() {
    var rows=new ArrayList<Map<String,Object>>();
    var profile=new ArrayList<Map<String,Object>>();
    for(int i=0;i<4000;i++)profile.add(Map.of("md",i,"reference_point",List.of(i,0,i),"sf",1.25));
    for(int i=0;i<30;i++)rows.add(Map.of("neighbor_survey_version_id",i+1,"sf",1.25,"profile",profile));
    var result=Map.<String,Object>of("results",rows,"pairs",rows,"nearest_pair",rows.get(0));
    var summary=CollisionResultStorage.summary(result);
    assertTrue(JSON.toJSONBytes(summary).length<10000);
    var restored=CollisionResultStorage.restore(summary,rows.stream().map(pair->Map.<String,Object>of("result_json",JSON.toJSONString(pair))).toList());
    assertEquals(30,((List<?>)restored.get("results")).size());
    assertEquals(4000,((List<?>)((Map<?,?>)((List<?>)restored.get("results")).get(29)).get("profile")).size());
  }
}
