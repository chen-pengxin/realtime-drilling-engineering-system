package com.ruoyi.web.controller.drilling.centers;

import java.util.*;
import org.apache.shiro.authz.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/drilling/centers/versions")
@RequiresAuthentication
public class VersionCenterController {
  public static final String SOURCE =
      """
      SELECT CONCAT('CONFIG:',config_version) record_key,'CONFIG' record_type,
        config_version record_id,field_id,NULL well_id,'井场配置' title,config_version version_no,
        IF(status='0' AND effective_to IS NULL,'CURRENT','HISTORY') status,create_by COLLATE utf8mb4_general_ci operator,create_time created_at
        FROM drilling_field_config
      UNION ALL SELECT CONCAT('SURVEY:',v.survey_version_id),'SURVEY',v.survey_version_id,w.field_id,v.well_id,
        CONCAT(w.well_name,' / ',b.wellbore_name,' / ',IF(t.trajectory_kind='DESIGN','设计轨道','实钻测斜')) COLLATE utf8mb4_general_ci,t.trajectory_version_no,IF(h.survey_version_id=v.survey_version_id,'CURRENT','HISTORY'),v.create_by COLLATE utf8mb4_general_ci,v.create_time FROM drilling_survey_version v JOIN drilling_trajectory_version t ON t.survey_version_id=v.survey_version_id JOIN drilling_wellbore b ON b.wellbore_id=t.wellbore_id JOIN drilling_well w ON w.well_id=v.well_id LEFT JOIN drilling_trajectory_head h ON h.wellbore_id=t.wellbore_id AND h.trajectory_kind=t.trajectory_kind
      UNION ALL SELECT CONCAT('MODEL:',model_version_id),'MODEL',model_version_id,field_id,NULL,
        model_name COLLATE utf8mb4_general_ci,version_no,status COLLATE utf8mb4_general_ci,create_by COLLATE utf8mb4_general_ci,create_time FROM model_version
      UNION ALL SELECT CONCAT('PLAN:',plan_id),'PLAN',plan_id,field_id,well_id,
        CONCAT('再规划方案 #',plan_id),plan_id,status COLLATE utf8mb4_general_ci,create_by COLLATE utf8mb4_general_ci,create_time FROM trajectory_plan
      """;
  public static final String CHANGES =
      "SELECT CAST(audit_id AS CHAR) record_key,audit_id record_id,field_id,resource_type"
          + " record_type,action title,create_time created_at,user_id"
          + " operator,resource_id,detail_json FROM drilling_audit_log";
  private final BusinessCenterSupport s;

  public VersionCenterController(BusinessCenterSupport s) {
    this.s = s;
  }

  @GetMapping
  @RequiresPermissions("drilling:versions:list")
  public Map<String, Object> list(
      @RequestParam Long fieldId, @RequestParam Map<String, String> p) {
    return s.page(
        SOURCE,
        fieldId,
        p,
        Map.of(
            "keyword",
            "q.title",
            "type",
            "q.record_type",
            "status",
            "q.status",
            "wellId",
            "q.well_id"),
        Map.of(
            "created_at",
            "q.created_at",
            "record_id",
            "q.record_id",
            "version_no",
            "q.version_no",
            "status",
            "q.status"));
  }

  @GetMapping("/changes")
  @RequiresPermissions("drilling:versions:list")
  public Map<String, Object> changes(
      @RequestParam Long fieldId, @RequestParam Map<String, String> p) {
    return s.page(
        CHANGES,
        fieldId,
        p,
        Map.of(
            "keyword", "CONCAT(q.title,' ',COALESCE(q.detail_json,''))", "type", "q.record_type"),
        Map.of("created_at", "q.created_at", "record_id", "q.record_id"));
  }

  @GetMapping("/{key}")
  @RequiresPermissions("drilling:versions:list")
  public Map<String, Object> detail(@RequestParam Long fieldId, @PathVariable String key) {
    return details(s, fieldId, key);
  }

  public static Map<String, Object> details(BusinessCenterSupport s, Long fieldId, String key) {
    var summary = s.row(SOURCE, fieldId, key, false);
    String type = summary.get("record_type").toString();
    Object id = summary.get("record_id");
    Map<String, Object> record;
    switch (type) {
      case "CONFIG":
        record =
            s.jdbc.queryForMap(
                "SELECT * FROM drilling_field_config WHERE field_id=? AND config_version=?",
                fieldId,
                id);
        break;
      case "SURVEY":
        record =
            s.jdbc.queryForMap(
                "SELECT * FROM drilling_survey_version WHERE survey_version_id=?", id);
        record.put(
            "stations",
            s.jdbc.queryForList(
                "SELECT md,inclination,azimuth,north,east,tvd,dogleg FROM drilling_survey_station"
                    + " WHERE survey_version_id=? ORDER BY md",
                id));
        break;
      case "MODEL":
        record =
            s.jdbc.queryForMap(
                "SELECT * FROM model_version WHERE model_version_id=? AND field_id=?",
                id,
                fieldId);
        break;
      case "PLAN":
        record =
            s.jdbc.queryForMap(
                "SELECT * FROM trajectory_plan WHERE plan_id=? AND field_id=?", id, fieldId);
        record.put(
            "segments",
            s.jdbc.queryForList(
                "SELECT * FROM trajectory_plan_segment WHERE plan_id=? ORDER BY segment_no", id));
        break;
      default:
        throw new IllegalArgumentException("版本类型无效");
    }
    String predicate =
        switch (type) {
          case "SURVEY" ->
              "(q.record_key IN (SELECT CONCAT('COLLISION:',task_id) FROM anti_collision_task_well"
                  + " WHERE survey_version_id="
                  + id
                  + ") OR q.record_key IN (SELECT CONCAT('PREDICTION:',prediction_id) FROM"
                  + " prediction_task WHERE survey_version_id="
                  + id
                  + ") OR q.record_key IN (SELECT CONCAT('REPLANNING:',plan_id) FROM"
                  + " trajectory_plan WHERE current_survey_version_id="
                  + id
                  + "))";
          case "MODEL" ->
              "(q.record_key IN (SELECT CONCAT('PREDICTION:',prediction_id) FROM prediction_task"
                  + " WHERE model_version='"
                  + id
                  + "') OR q.record_key IN (SELECT CONCAT('TRAINING:',training_task_id) FROM"
                  + " training_task WHERE model_version_id="
                  + id
                  + "))";
          case "CONFIG" ->
              "q.record_key IN (SELECT CONCAT(task_type,':',source_id) FROM"
                  + " drilling_center_snapshot WHERE field_id="
                  + fieldId
                  + " AND JSON_EXTRACT(config_snapshot_json,'$.config_version')="
                  + id
                  + ")";
          case "PLAN" -> "q.record_key='REPLANNING:" + id + "'";
          default -> "1=0";
        };
    var used =
        s.jdbc.queryForList(
            "SELECT q.* FROM ("
                + TaskHistoryController.SOURCE
                + ") q WHERE q.field_id=? AND "
                + predicate
                + " ORDER BY q.created_at DESC",
            fieldId);
    return Map.of("summary", summary, "record", record, "used_by", used);
  }

  @PostMapping("/compare")
  @RequiresPermissions("drilling:versions:list")
  public Map<String, Object> compare(
      @RequestParam Long fieldId, @RequestBody Map<String, Object> b) {
    var keys = BusinessCenterSupport.keys(b);
    if (keys.size() != 2) throw new IllegalArgumentException("请选择两个版本进行比较");
    var left = details(s, fieldId, keys.get(0));
    var right = details(s, fieldId, keys.get(1));
    var ls = BusinessCenterSupport.object(left.get("summary"));
    var rs = BusinessCenterSupport.object(right.get("summary"));
    if (!Objects.equals(ls.get("record_type"), rs.get("record_type"))
        || !Objects.equals(ls.get("well_id"), rs.get("well_id"))
        || (!"PLAN".equals(ls.get("record_type"))
            && !Objects.equals(ls.get("title"), rs.get("title"))))
      throw new IllegalArgumentException("请选择同一对象的两个版本");
    List<Map<String, Object>> diff = new ArrayList<>();
    flattenDiff("", left.get("record"), right.get("record"), diff);
    return Map.of("left", left, "right", right, "differences", diff);
  }

  private static void flattenDiff(
      String path, Object left, Object right, List<Map<String, Object>> out) {
    if (left instanceof String l && (l.startsWith("{") || l.startsWith("["))) {
      try {
        left = com.alibaba.fastjson.JSON.parse(l);
      } catch (Exception ignored) {
      }
    }
    if (right instanceof String r && (r.startsWith("{") || r.startsWith("["))) {
      try {
        right = com.alibaba.fastjson.JSON.parse(r);
      } catch (Exception ignored) {
      }
    }
    if (left instanceof Map<?, ?> || right instanceof Map<?, ?>) {
      Map<String, Object>
          l = left instanceof Map<?, ?> ? BusinessCenterSupport.object(left) : Map.of(),
          r = right instanceof Map<?, ?> ? BusinessCenterSupport.object(right) : Map.of();
      Set<String> keys = new TreeSet<>(l.keySet());
      keys.addAll(r.keySet());
      for (String k : keys)
        flattenDiff(path.isEmpty() ? k : path + "." + k, l.get(k), r.get(k), out);
    } else if (left instanceof List<?> || right instanceof List<?>) {
      List<?> l = left instanceof List<?> rows ? rows : List.of();
      List<?> r = right instanceof List<?> rows ? rows : List.of();
      for (int i = 0; i < Math.max(l.size(), r.size()); i++)
        flattenDiff(
            path + "[" + i + "]",
            i < l.size() ? l.get(i) : null,
            i < r.size() ? r.get(i) : null,
            out);
    } else if (!Objects.equals(left, right)) {
      Map<String, Object> d = new LinkedHashMap<>();
      d.put("field", path);
      d.put("before", left);
      d.put("after", right);
      out.add(d);
    }
  }

  @PostMapping("/export")
  @RequiresPermissions("drilling:versions:export")
  public ResponseEntity<byte[]> export(
      @RequestParam Long fieldId, @RequestBody Map<String, Object> b) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (String id : BusinessCenterSupport.keys(b)) rows.add(details(s, fieldId, id));
    s.audit(fieldId, "EXPORT", "VERSION", null, b);
    return BusinessCenterSupport.json(rows, "versions.json");
  }
}
