package com.ruoyi.web.controller.drilling;

import com.alibaba.fastjson.JSON;
import java.util.*;

/** Version-level survey-program metadata, separate from the common SF policy. */
final class SurveyVersionMetadata {
  private SurveyVersionMetadata() {}

  static Map<String,Object> uncertainty(Map<String,Object> reference, Map<String,Object> defaults) {
    var stored=SourceRegistryController.obj(reference.get("uncertainty"));
    var result=new LinkedHashMap<String,Object>();
    if(!stored.isEmpty()) {
      if(!Boolean.TRUE.equals(stored.get("confirmed")) || !(stored.get("settings") instanceof Map<?,?>) || stored.get("model_version")==null)
        throw new IllegalArgumentException("测斜版本的仪器误差参数尚未确认，请在版本导入中核对参数后保存新版本");
      for(String key:List.of("model_version","settings","parameter_source"))if(stored.get(key)!=null)result.put(key,stored.get(key));
      result.putIfAbsent("parameter_source","VERSION_CONFIRMED");
    } else {
      // Historical imports predate instrument budgets. Do not claim that a
      // default profile was measured or extracted from their source document.
      var fallback=NativeEngineSupport.modelPayload(defaults);
      result.put("model_version",fallback.get("model_version"));
      result.put("settings",fallback.get("settings"));
      result.put("parameter_source","FIELD_CONFIGURATION");
    }
    result.put("tool",SourceRegistryController.obj(reference.get("survey_tool")));
    return result;
  }

  static Map<String,Object> identity(Map<String,Object> reference) {
    var result=new TreeMap<String,Object>();
    result.put("survey_tool",reference.get("survey_tool"));
    var profile=SourceRegistryController.obj(reference.get("uncertainty"));
    // Reviewer and timestamp do not change a numerical version's identity.
    var budget=new TreeMap<String,Object>();
    for(String key:List.of("model_version","settings"))budget.put(key,profile.get(key));
    result.put("uncertainty",budget);
    return result;
  }

  static String canonical(Object value) {
    return JSON.toJSONString(sorted(value));
  }
  private static Object sorted(Object value) {
    if(value instanceof Map<?,?> map) {var out=new TreeMap<String,Object>();map.forEach((k,v)->out.put(String.valueOf(k),sorted(v)));return out;}
    if(value instanceof List<?> list)return list.stream().map(SurveyVersionMetadata::sorted).toList();
    return value;
  }
}
