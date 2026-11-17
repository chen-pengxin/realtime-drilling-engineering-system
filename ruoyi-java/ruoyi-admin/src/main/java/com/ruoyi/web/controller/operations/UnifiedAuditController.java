package com.ruoyi.web.controller.operations;

import com.ruoyi.common.core.domain.AjaxResult;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/operations/audit")
public class UnifiedAuditController {
  private final OperationsSupport ops;

  public UnifiedAuditController(OperationsSupport ops) {
    this.ops = ops;
  }

  private static final String UNION =
      """
      (SELECT CONVERT(CONCAT('ACCOUNT:',a.audit_id) USING utf8mb4) COLLATE utf8mb4_general_ci AS eventKey,
        CONVERT('ACCOUNT' USING utf8mb4) COLLATE utf8mb4_general_ci AS source,
        CONVERT(a.action USING utf8mb4) COLLATE utf8mb4_general_ci AS action,
        a.actor_id AS userId,
        CONVERT(COALESCE(u.login_name,'') USING utf8mb4) COLLATE utf8mb4_general_ci AS actorName,
        NULL AS fieldId,
        NULL AS wellId,
        NULL AS surveyVersionId,
        CONVERT(NULL USING utf8mb4) COLLATE utf8mb4_general_ci AS taskId,
        CONVERT(NULL USING utf8mb4) COLLATE utf8mb4_general_ci AS requestId,
        CONVERT(CAST(a.target_id AS CHAR) USING utf8mb4) COLLATE utf8mb4_general_ci AS resource,
        CONVERT('SUCCESS' USING utf8mb4) COLLATE utf8mb4_general_ci AS outcome,
        CONVERT(a.detail_json USING utf8mb4) COLLATE utf8mb4_general_ci AS detailJson,
        CONVERT('' USING utf8mb4) COLLATE utf8mb4_general_ci AS clientIp,
        0 AS durationMs,
        a.create_time AS createTime FROM drilling_account_audit a LEFT JOIN sys_user u ON u.user_id=a.actor_id
      UNION ALL SELECT CONVERT(CONCAT('BUSINESS:',a.audit_id) USING utf8mb4) COLLATE utf8mb4_general_ci AS eventKey,
        CONVERT('BUSINESS' USING utf8mb4) COLLATE utf8mb4_general_ci AS source,
        CONVERT(a.action USING utf8mb4) COLLATE utf8mb4_general_ci AS action,
        a.user_id AS userId,
        CONVERT(COALESCE(u.login_name,'') USING utf8mb4) COLLATE utf8mb4_general_ci AS actorName,
        a.field_id AS fieldId,
        NULL AS wellId,
        NULL AS surveyVersionId,
        CONVERT(NULL USING utf8mb4) COLLATE utf8mb4_general_ci AS taskId,
        CONVERT(a.request_id USING utf8mb4) COLLATE utf8mb4_general_ci AS requestId,
        CONVERT(CONCAT(a.resource_type,':',COALESCE(a.resource_id,'')) USING utf8mb4) COLLATE utf8mb4_general_ci AS resource,
        CONVERT('SUCCESS' USING utf8mb4) COLLATE utf8mb4_general_ci AS outcome,
        CONVERT(a.detail_json USING utf8mb4) COLLATE utf8mb4_general_ci AS detailJson,
        CONVERT(a.client_ip USING utf8mb4) COLLATE utf8mb4_general_ci AS clientIp,
        0 AS durationMs,
        a.create_time AS createTime FROM drilling_audit_log a LEFT JOIN sys_user u ON u.user_id=a.user_id
      UNION ALL SELECT CONVERT(CONCAT('REQUEST:',event_id) USING utf8mb4) COLLATE utf8mb4_general_ci AS eventKey,
        CONVERT('REQUEST' USING utf8mb4) COLLATE utf8mb4_general_ci AS source,
        CONVERT(action USING utf8mb4) COLLATE utf8mb4_general_ci AS action,
        actor_user_id AS userId,
        CONVERT(actor_name USING utf8mb4) COLLATE utf8mb4_general_ci AS actorName,
        field_id AS fieldId,
        well_id AS wellId,
        survey_version_id AS surveyVersionId,
        CONVERT(task_id USING utf8mb4) COLLATE utf8mb4_general_ci AS taskId,
        CONVERT(request_id USING utf8mb4) COLLATE utf8mb4_general_ci AS requestId,
        CONVERT(resource_path USING utf8mb4) COLLATE utf8mb4_general_ci AS resource,
        CONVERT(outcome USING utf8mb4) COLLATE utf8mb4_general_ci AS outcome,
        CONVERT(detail_json USING utf8mb4) COLLATE utf8mb4_general_ci AS detailJson,
        CONVERT(client_ip USING utf8mb4) COLLATE utf8mb4_general_ci AS clientIp,
        duration_ms AS durationMs,
        create_time AS createTime FROM drilling_system_audit
      UNION ALL SELECT CONVERT(CONCAT('LEGACY:',oper_id) USING utf8mb4) COLLATE utf8mb4_general_ci AS eventKey,
        CONVERT('LEGACY' USING utf8mb4) COLLATE utf8mb4_general_ci AS source,
        CONVERT(title USING utf8mb4) COLLATE utf8mb4_general_ci AS action,
        NULL AS userId,
        CONVERT(oper_name USING utf8mb4) COLLATE utf8mb4_general_ci AS actorName,
        NULL AS fieldId,
        NULL AS wellId,
        NULL AS surveyVersionId,
        CONVERT(NULL USING utf8mb4) COLLATE utf8mb4_general_ci AS taskId,
        CONVERT(NULL USING utf8mb4) COLLATE utf8mb4_general_ci AS requestId,
        CONVERT(oper_url USING utf8mb4) COLLATE utf8mb4_general_ci AS resource,
        CONVERT(IF(status=0,'SUCCESS','FAILURE') USING utf8mb4) COLLATE utf8mb4_general_ci AS outcome,
        CONVERT(JSON_OBJECT('businessType',business_type) USING utf8mb4) COLLATE utf8mb4_general_ci AS detailJson,
        CONVERT(oper_ip USING utf8mb4) COLLATE utf8mb4_general_ci AS clientIp,
        cost_time AS durationMs,
        oper_time AS createTime FROM sys_oper_log) events
      """;

  private String where(Map<String, String> p, List<Object> args) {
    StringBuilder w = new StringBuilder("FROM " + UNION + " WHERE 1=1");
    OperationsSupport.like(p, "account", "actorName", w, args);
    OperationsSupport.like(p, "action", "action", w, args);
    for (String k : List.of("source", "outcome")) {
      String v = p.get(k);
      if (v != null && !v.isBlank()) {
        if (k.equals("source") && !Set.of("ACCOUNT", "BUSINESS", "REQUEST", "LEGACY").contains(v)
            || k.equals("outcome") && !Set.of("SUCCESS", "FAILURE").contains(v))
          throw new IllegalArgumentException("筛选值无效");
        w.append(" AND ").append(k).append("=?");
        args.add(v);
      }
    }
    if (p.containsKey("fieldId") && !p.get("fieldId").isBlank()) {
      w.append(" AND fieldId=?");
      args.add(OperationsSupport.integer(p.get("fieldId"), 1, 1, Integer.MAX_VALUE));
    }
    OperationsSupport.dates(p, "createTime", w, args);
    return w.toString();
  }

  @GetMapping
  public AjaxResult list(@RequestParam Map<String, String> p) {
    ops.admin();
    List<Object> a = new ArrayList<>();
    return ops.pageQuery("SELECT *", where(p, a), a, "ORDER BY createTime DESC,eventKey DESC", p);
  }

  @GetMapping("/export")
  public ResponseEntity<byte[]> export(@RequestParam Map<String, String> p) {
    ops.admin();
    List<Object> a = new ArrayList<>();
    List<Map<String, Object>> rows =
        ops.jdbc.queryForList(
            "SELECT * " + where(p, a) + " ORDER BY createTime DESC,eventKey DESC LIMIT 10000",
            a.toArray());
    return OperationsSupport.csv(
        "audit.csv",
        List.of("记录", "来源", "账号ID", "操作", "井场", "井", "测斜版本", "任务", "结果", "资源", "时间"),
        rows.stream()
            .map(
                r ->
                    Arrays.asList(
                        r.get("eventKey"),
                        r.get("source"),
                        r.get("actorName"),
                        r.get("action"),
                        r.get("fieldId"),
                        r.get("wellId"),
                        r.get("surveyVersionId"),
                        r.get("taskId"),
                        r.get("outcome"),
                        r.get("resource"),
                        r.get("createTime")))
            .toList());
  }
}
