package com.ruoyi.web.controller.drilling;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Engineering ownership is fixed when a directory object is created. */
final class EngineeringNodeHierarchy {
  private EngineeringNodeHierarchy() {}

  static Long creationParent(String type, Long fieldId, Map<String,Object> parent) {
    if (fieldId == null || fieldId <= 0) throw new IllegalArgumentException("请先选择所属井场");
    if ("INBOX".equals(type) && parent == null) return null;
    String expected = Map.of("WELL", "FIELD", "WELLBORE", "WELL", "PLAN", "WELLBORE", "SURVEY", "WELLBORE", "INBOX", "FIELD").get(type);
    if (expected == null || parent == null || !expected.equals(parent.get("node_type")))
      throw new IllegalArgumentException("请选择正确的所属区块、井场、井或井眼");
    if (!fieldId.equals(number(parent.get("field_id")))) throw new IllegalArgumentException("上级不属于所选井场");
    Long parentId = number(parent.get("node_id"));
    if (parentId == null || ("FIELD".equals(expected) ? parentId >= 0 : parentId <= 0))
      throw new IllegalArgumentException("所属工程目录无效");
    return "FIELD".equals(expected) ? null : parentId;
  }

  static Long updateParent(Map<String,Object> old, Map<String,Object> parent) {
    Long next = creationParent(Objects.toString(old.get("node_type"), ""), number(old.get("field_id")), parent);
    Long previous = number(old.get("parent_id"));
    if (previous != null && previous < 0) previous = null;
    if (!Objects.equals(previous, next)) throw new IllegalArgumentException("编辑资料不能更改所属工程层级");
    return next;
  }

  static String engineeringStatus(Object value, String fallback) {
    String status = value == null ? fallback : value.toString();
    if (!Set.of("UNPLANNED", "PLANNED", "DRILLING", "SUSPENDED", "COMPLETED", "ABANDONED", "UNKNOWN").contains(status))
      throw new IllegalArgumentException("井眼状态无效");
    return status;
  }

  private static Long number(Object value) {
    return value == null ? null : Long.valueOf(value.toString());
  }
}
