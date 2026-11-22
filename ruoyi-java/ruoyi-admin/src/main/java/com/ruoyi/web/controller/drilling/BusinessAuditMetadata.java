package com.ruoyi.web.controller.drilling;

import java.util.*;
import com.alibaba.fastjson.JSON;

/** Shared projection for domain events whose source may be an entire form or uploaded object. */
public final class BusinessAuditMetadata {
  private BusinessAuditMetadata() {}
  private static final Set<String> IDS = Set.of("fieldId","wellId","wellboreId","revisionId","documentId","nodeId",
      "versionId","dataVersionId","extractionId","sourceId","previousVersionId","parentVersionId","source","previous",
      "editionNo","well_id","wellbore_id","node_id","field_id","version_no","previous_version","config_version","file_id","version");
  private static final Set<String> CODES = Set.of("kind","type","status","format","revisionType","before","after");
  public static String json(Object data) {
    Map<String,Object> safe = new LinkedHashMap<>();
    if (data instanceof List<?> list) safe.put("count",list.size());
    if (data instanceof Map<?,?> map) {
      for (String key : IDS) {
        Object v = map.get(key);
        if (v != null && String.valueOf(v).matches("[0-9]{1,18}")) safe.put(key,v);
      }
      for (String key : CODES) {
        Object v = map.get(key);
        if (v instanceof String s && s.matches("[A-Za-z0-9_]{1,32}")) safe.put(key,s);
      }
      for (String key : List.of("activated","confirmed")) if (map.get(key) instanceof Boolean b) safe.put(key,b);
      if (map.get("ids") instanceof List<?> ids)
        safe.put("ids",ids.stream().map(String::valueOf).filter(id->id.matches("(?:[A-Z_]+:)?[0-9]{1,18}")).limit(100).toList());
    }
    return JSON.toJSONString(safe);
  }
}
