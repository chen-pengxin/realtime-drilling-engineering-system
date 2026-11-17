package com.ruoyi.web.controller.drilling.centers;

import java.util.*;
import org.apache.shiro.authz.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/drilling/centers/history")
@RequiresAuthentication
public class TaskHistoryController {
  public static final String SOURCE =
      """
      SELECT CONCAT('COLLISION:',task_id) record_key,'COLLISION' record_type,task_id record_id,field_id,reference_well_id well_id,
        CONCAT('防碰评价 #',task_id) title,status COLLATE utf8mb4_general_ci status,create_by COLLATE utf8mb4_general_ci operator,create_time created_at,finish_time finished_at FROM anti_collision_task
      UNION ALL SELECT CONCAT('PREDICTION:',prediction_id),'PREDICTION',prediction_id,field_id,well_id,
        CONCAT('姿态预测 #',prediction_id),status COLLATE utf8mb4_general_ci,create_by COLLATE utf8mb4_general_ci,create_time,update_time FROM prediction_task
      UNION ALL SELECT CONCAT('REPLANNING:',plan_id),'REPLANNING',plan_id,field_id,well_id,
        CONCAT('轨迹再规划 #',plan_id),status COLLATE utf8mb4_general_ci,create_by COLLATE utf8mb4_general_ci,create_time,finish_time FROM trajectory_plan
      UNION ALL SELECT CONCAT('TRAINING:',t.training_task_id),'TRAINING',t.training_task_id,COALESCE(t.field_id,d.field_id),NULL,
        CONCAT('模型更新 #',t.training_task_id),t.status COLLATE utf8mb4_general_ci,t.create_by COLLATE utf8mb4_general_ci,t.create_time,t.finish_time
        FROM training_task t JOIN training_dataset d ON d.dataset_id=t.dataset_id
      UNION ALL SELECT CONCAT('EXECUTION:',execution_id),task_type,execution_id,field_id,well_id,
        CONCAT('算法调用 #',execution_id),status COLLATE utf8mb4_general_ci,create_by COLLATE utf8mb4_general_ci,create_time,finish_time FROM drilling_center_execution WHERE source_id IS NULL
      """;
  private final BusinessCenterSupport s;

  public TaskHistoryController(BusinessCenterSupport s) {
    this.s = s;
  }

  @GetMapping
  @RequiresPermissions("drilling:history:list")
  public Map<String, Object> list(
      @RequestParam Long fieldId, @RequestParam Map<String, String> p) {
    return s.page(
        "SELECT q.*,IF(q.finished_at IS"
            + " NULL,NULL,TIMESTAMPDIFF(MICROSECOND,COALESCE(s.started_at,q.created_at),COALESCE(s.finished_at,q.finished_at))/1000000.0)"
            + " duration_seconds,JSON_EXTRACT(s.config_snapshot_json,'$.config_version')"
            + " config_version FROM ("
            + SOURCE
            + ") q LEFT JOIN drilling_center_snapshot s ON q.field_id=s.field_id AND"
            + " q.record_key=CONCAT(s.task_type,':',s.source_id)",
        fieldId,
        p,
        Map.of(
            "keyword",
            "CONCAT(q.title,' ',COALESCE(q.operator,''))",
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
            "status",
            "q.status",
            "finished_at",
            "q.finished_at",
            "duration_seconds",
            "q.duration_seconds"));
  }

  @GetMapping("/{key}")
  @RequiresPermissions("drilling:history:list")
  public Map<String, Object> detail(@RequestParam Long fieldId, @PathVariable String key) {
    return details(s, fieldId, key);
  }

  public static Map<String, Object> details(BusinessCenterSupport s, Long fieldId, String key) {
    Map<String, Object> summary = s.row(SOURCE, fieldId, key, false);
    String type = key.split(":")[0];
    String table =
        Map.of(
                "COLLISION",
                "anti_collision_task",
                "PREDICTION",
                "prediction_task",
                "REPLANNING",
                "trajectory_plan",
                "TRAINING",
                "training_task",
                "EXECUTION",
                "drilling_center_execution")
            .get(type);
    String id =
        Map.of(
                "COLLISION",
                "task_id",
                "PREDICTION",
                "prediction_id",
                "REPLANNING",
                "plan_id",
                "TRAINING",
                "training_task_id",
                "EXECUTION",
                "execution_id")
            .get(type);
    var raw =
        s.jdbc.queryForMap(
            "SELECT * FROM " + table + " WHERE " + id + "=? AND field_id=?",
            summary.get("record_id"),
            fieldId);
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("summary", summary);
    r.put("record", raw);
    var snapshots =
        s.jdbc.queryForList(
            "SELECT input_json,config_snapshot_json,started_at,finished_at FROM"
                + " drilling_center_snapshot WHERE task_type=? AND source_id=? AND field_id=?",
            type,
            summary.get("record_id"),
            fieldId);
    r.put("snapshot", snapshots.isEmpty() ? null : snapshots.get(0));
    if (type.equals("COLLISION")) {
      r.put(
          "surveys",
          s.jdbc.queryForList(
              "SELECT tw.well_id,w.well_name,tw.survey_version_id,COALESCE(tv.trajectory_version_no,v.version_no) AS version_no,tv.trajectory_kind,tv.wellbore_id,tw.role FROM"
                  + " anti_collision_task_well tw JOIN drilling_survey_version v ON"
                  + " v.survey_version_id=tw.survey_version_id LEFT JOIN drilling_trajectory_version tv ON tv.survey_version_id=v.survey_version_id JOIN drilling_well w ON"
                  + " w.well_id=tw.well_id WHERE tw.task_id=?",
              summary.get("record_id")));
      r.put(
          "riskResults",
          s.jdbc.queryForList(
              "SELECT * FROM anti_collision_result WHERE task_id=?", summary.get("record_id")));
    }
    if (type.equals("REPLANNING"))
      r.put(
          "segments",
          s.jdbc.queryForList(
              "SELECT * FROM trajectory_plan_segment WHERE plan_id=? ORDER BY segment_no",
              summary.get("record_id")));
    if (type.equals("TRAINING")) {
      r.put(
          "dataset",
          s.jdbc.queryForList(
              "SELECT dataset_id,dataset_name,version_no,file_id FROM training_dataset WHERE"
                  + " dataset_id=?",
              raw.get("dataset_id")));
      r.put(
          "template",
          s.jdbc.queryForList(
              "SELECT template_id,template_name,file_id FROM training_template WHERE template_id=?",
              raw.get("template_id")));
    }
    return r;
  }

  @PostMapping("/export")
  @RequiresPermissions("drilling:history:export")
  public ResponseEntity<byte[]> export(
      @RequestParam Long fieldId, @RequestBody Map<String, Object> b) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (String key : BusinessCenterSupport.keys(b)) rows.add(details(s, fieldId, key));
    s.audit(fieldId, "EXPORT", "TASK_HISTORY", null, Map.of("ids", b.get("ids")));
    return BusinessCenterSupport.json(rows, "task-history.json");
  }
}
