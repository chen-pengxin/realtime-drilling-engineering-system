package com.ruoyi.web.controller.drilling.centers;

import com.ruoyi.common.utils.ShiroUtils;
import java.util.*;
import org.apache.shiro.authz.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/drilling/centers/alerts")
@RequiresAuthentication
public class AlarmCenterController {
  public static final String SOURCE =
      """
      SELECT CAST(a.alarm_id AS CHAR) record_key,a.alarm_id record_id,a.field_id,a.well_id,w.well_name,
        a.alarm_code record_type,a.current_level severity,a.status,a.message title,a.create_by operator,
        a.create_time created_at,a.acknowledged_by,a.acknowledged_at,a.resolved_by,a.resolved_at,
        a.task_id,a.result_id,a.rule_snapshot_json,(SELECT neighbor_well_id FROM anti_collision_result r WHERE r.result_id=a.result_id) neighbor_well_id
        FROM drilling_alarm a LEFT JOIN drilling_well w ON w.well_id=a.well_id
      """;
  private final BusinessCenterSupport s;

  public AlarmCenterController(BusinessCenterSupport s) {
    this.s = s;
  }

  @GetMapping
  @RequiresPermissions("drilling:alerts:list")
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
            "severity",
            "q.severity",
            "wellId",
            "q.well_id"),
        Map.of(
            "created_at",
            "q.created_at",
            "record_id",
            "q.record_id",
            "severity",
            "q.severity",
            "status",
            "q.status"));
  }

  @PostMapping("/{action}")
  @Transactional
  public Map<String, Object> batch(
      @RequestParam Long fieldId,
      @PathVariable String action,
      @RequestBody Map<String, Object> b) {
    if (!Set.of("ack", "resolve").contains(action)) throw new IllegalArgumentException("操作无效");
    ShiroUtils.getSubject().checkPermission("drilling:alerts:" + action);
    s.write(fieldId);
    var keys = BusinessCenterSupport.keys(b);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (String key : keys) {
      s.jdbc.queryForList(
          "SELECT alarm_id FROM drilling_alarm WHERE field_id=? AND alarm_id=? FOR UPDATE",
          fieldId,
          key);
      var r = s.row(SOURCE, fieldId, key, false);
      if (action.equals("ack") && r.get("status").equals("RESOLVED"))
        throw new IllegalArgumentException("已解除告警不能再次确认");
      rows.add(r);
    }
    for (var r : rows) {
      String id = r.get("record_key").toString();
      if (action.equals("ack"))
        s.jdbc.update(
            "UPDATE drilling_alarm SET"
                + " status='ACKNOWLEDGED',acknowledged_by=?,acknowledged_at=NOW() WHERE"
                + " field_id=? AND alarm_id=? AND status='OPEN'",
            ShiroUtils.getLoginName(),
            fieldId,
            id);
      else
        s.jdbc.update(
            "UPDATE drilling_alarm SET status='RESOLVED',resolved_by=?,resolved_at=NOW() WHERE"
                + " field_id=? AND alarm_id=? AND status IN ('OPEN','ACKNOWLEDGED')",
            ShiroUtils.getLoginName(),
            fieldId,
            id);
      s.audit(fieldId, action.toUpperCase(), "ALARM", id, Map.of("before", r.get("status")));
    }
    return Map.of("code", 200, "count", rows.size());
  }

  @PostMapping("/export")
  @RequiresPermissions("drilling:alerts:export")
  public ResponseEntity<byte[]> export(
      @RequestParam Long fieldId, @RequestBody Map<String, Object> b) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (String id : BusinessCenterSupport.keys(b)) rows.add(s.row(SOURCE, fieldId, id, false));
    s.audit(fieldId, "EXPORT", "ALARM", null, b);
    return BusinessCenterSupport.csv(rows, "alarms.csv");
  }
}
