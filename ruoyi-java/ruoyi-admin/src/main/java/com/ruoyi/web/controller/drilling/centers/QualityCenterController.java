package com.ruoyi.web.controller.drilling.centers;

import com.ruoyi.common.utils.ShiroUtils;
import java.util.*;
import org.apache.shiro.authz.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/drilling/centers/quality")
@RequiresAuthentication
public class QualityCenterController {
  public static final String SOURCE =
      """
      SELECT CAST(i.issue_id AS CHAR) record_key,i.issue_id record_id,i.field_id,i.well_id,w.well_name,
        i.issue_type record_type,i.issue_type title,i.severity,i.status,i.md,i.survey_version_id,
        i.raw_json,i.resolution_note,i.resolved_by,i.resolved_at,i.create_by operator,i.create_time created_at
        FROM drilling_data_quality_issue i LEFT JOIN drilling_well w ON w.well_id=i.well_id
      """;
  private final BusinessCenterSupport s;
  private final BusinessEventService events;

  public QualityCenterController(BusinessCenterSupport s, BusinessEventService events) {
    this.s = s;
    this.events = events;
  }

  @GetMapping
  @RequiresPermissions("drilling:quality:list")
  public Map<String, Object> list(
      @RequestParam Long fieldId, @RequestParam Map<String, String> p) {
    return s.page(
        SOURCE,
        fieldId,
        p,
        Map.of(
            "keyword",
            "CONCAT(q.title,' ',COALESCE(q.resolution_note,''))",
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
            "status",
            "q.status",
            "severity",
            "q.severity",
            "md",
            "q.md"));
  }

  @PostMapping("/check")
  @RequiresPermissions("drilling:quality:check")
  public Map<String, Object> check(@RequestParam Long fieldId) {
    s.write(fieldId);
    events.synchronize(fieldId);
    s.audit(fieldId, "CHECK", "DATA_QUALITY", null, Map.of());
    return Map.of("code", 200);
  }

  @PostMapping("/resolve")
  @RequiresPermissions("drilling:quality:resolve")
  @Transactional
  public Map<String, Object> resolve(
      @RequestParam Long fieldId, @RequestBody Map<String, Object> b) {
    s.write(fieldId);
    String note = BusinessCenterSupport.text(b, "note", 500, true);
    String status = BusinessCenterSupport.text(b, "status", 16, true);
    if (!Set.of("RESOLVED", "IGNORED").contains(status))
      throw new IllegalArgumentException("处理状态无效");
    var keys = BusinessCenterSupport.keys(b);
    for (String id : keys) {
      s.jdbc.queryForList(
          "SELECT issue_id FROM drilling_data_quality_issue WHERE field_id=? AND issue_id=? FOR"
              + " UPDATE",
          fieldId,
          id);
      s.row(SOURCE, fieldId, id, false);
    }
    for (String id : keys) {
      s.jdbc.update(
          "UPDATE drilling_data_quality_issue SET"
              + " status=?,resolution_note=?,resolved_by=?,resolved_at=NOW() WHERE field_id=? AND"
              + " issue_id=?",
          status,
          note,
          ShiroUtils.getLoginName(),
          fieldId,
          id);
      s.audit(fieldId, "RESOLVE", "QUALITY", id, Map.of("status", status, "note", note));
    }
    return Map.of("code", 200, "count", keys.size());
  }

  @PostMapping("/export")
  @RequiresPermissions("drilling:quality:export")
  public ResponseEntity<byte[]> export(
      @RequestParam Long fieldId, @RequestBody Map<String, Object> b) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (String id : BusinessCenterSupport.keys(b)) rows.add(s.row(SOURCE, fieldId, id, false));
    s.audit(fieldId, "EXPORT", "QUALITY", null, b);
    return BusinessCenterSupport.csv(rows, "data-quality.csv");
  }
}
