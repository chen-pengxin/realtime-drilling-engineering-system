package com.ruoyi.web.controller.drilling;

import com.alibaba.fastjson.JSON;
import java.util.*;

/** Task rows contain summaries; each well pair owns its full evaluation profile. */
public final class CollisionResultStorage {
  private CollisionResultStorage() {}

  public static Map<String,Object> summary(Map<String,Object> result) {
    var out=new LinkedHashMap<String,Object>(result);
    Object pairs=result.containsKey("results")?result.get("results"):result.get("pairs");
    out.remove("pairs");
    if(pairs instanceof List<?> rows)out.put("results",rows.stream().map(CollisionResultStorage::withoutProfile).toList());
    if(out.get("nearest_pair") instanceof Map<?,?> pair)out.put("nearest_pair",withoutProfile(pair));
    out.remove("profile");
    out.put("profiles_stored_separately",true);
    return out;
  }

  private static Object withoutProfile(Object value) {
    if(!(value instanceof Map<?,?> row))return value;
    var out=new LinkedHashMap<String,Object>();
    row.forEach((k,v)->{if(!"profile".equals(k))out.put(k.toString(),v);});
    if(row.get("profile") instanceof List<?> profile)out.put("profile_count",profile.size());
    return out;
  }

  public static Map<String,Object> restore(Map<String,Object> summary,List<Map<String,Object>> records) {
    if(!Boolean.TRUE.equals(summary.get("profiles_stored_separately")))return summary;
    var out=new LinkedHashMap<String,Object>(summary);
    var pairs=new ArrayList<Map<String,Object>>();
    for(var record:records){
      Object data=record.get("result_json");
      if(data instanceof String json)pairs.add(JSON.parseObject(json,Map.class));
      else if(data instanceof Map<?,?> map){var pair=new LinkedHashMap<String,Object>();map.forEach((k,v)->pair.put(k.toString(),v));pairs.add(pair);}
    }
    if(summary.containsKey("results"))out.put("results",pairs);
    else if(!pairs.isEmpty())out.putAll(pairs.get(0));
    out.remove("profiles_stored_separately");
    return out;
  }
}
