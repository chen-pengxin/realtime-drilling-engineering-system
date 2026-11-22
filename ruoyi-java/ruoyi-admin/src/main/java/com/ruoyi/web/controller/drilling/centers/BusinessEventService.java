package com.ruoyi.web.controller.drilling.centers;

import com.alibaba.fastjson.JSON;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Collects actual persisted failures and quality defects; no synthetic demo alerts. */
@Service
public class BusinessEventService {
  private final BusinessCenterSupport s;
  private final TransactionTemplate tx;

  public BusinessEventService(BusinessCenterSupport s, PlatformTransactionManager manager) {
    this.s = s;
    tx = new TransactionTemplate(manager);
  }

  public void quality(
      Long fieldId, Long well, Long survey, String type, String origin, Object raw, String note) {
    tx.executeWithoutResult(
        status -> {
          s.jdbc.queryForList(
              "SELECT field_id FROM drilling_field WHERE field_id=? FOR UPDATE", fieldId);
          String key = hash(fieldId + ":" + origin);
          if (s.jdbc.queryForObject(
                  "SELECT COUNT(*) FROM drilling_center_origin WHERE origin_type='QUALITY' AND"
                      + " origin_key=?",
                  Integer.class,
                  key)
              > 0) return;
          Object original = raw;
          if (raw instanceof String json) {
            try {
              original = JSON.parse(json);
            } catch (Exception ignored) {
            }
          }
          Object md = null;
          try {
            if (original instanceof Map<?, ?> m) {
              Object value = m.get("md");
              if (value != null) {
                var number = new java.math.BigDecimal(value.toString());
                if (number.abs().compareTo(new java.math.BigDecimal("10000000000")) < 0)
                  md = number;
              }
            }
          } catch (Exception ignored) {
          }
          Map<String, Object> diagnostic = new LinkedHashMap<>();
          diagnostic.put("data", original);
          diagnostic.put("reason", note);
          s.jdbc.update(
              "INSERT INTO"
                  + " drilling_data_quality_issue(field_id,well_id,survey_version_id,issue_type,severity,status,md,raw_json,resolution_note,create_by)"
                  + " VALUES(?,?,?,?,'WARNING','ISOLATED',?,?,?,'system')",
              fieldId,
              well,
              survey,
              type,
              md,
              JSON.toJSONString(diagnostic),
              clip(note, 500));
          Long id = s.jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
          s.jdbc.update(
              "INSERT INTO drilling_center_origin(origin_type,origin_key,field_id,resource_id)"
                  + " VALUES('QUALITY',?,?,?)",
              key,
              fieldId,
              id);
          alert(
              fieldId,
              well,
              "DATA_QUALITY",
              "QUALITY:" + id,
              "数据质量问题：" + type,
              Map.of("issue_id", id, "issue_type", type));
        });
  }

  public void alert(
      Long fieldId, Long well, String code, String origin, String message, Object snapshot) {
    tx.executeWithoutResult(
        status -> {
          s.jdbc.queryForList(
              "SELECT field_id FROM drilling_field WHERE field_id=? FOR UPDATE", fieldId);
          String key = hash(fieldId + ":" + origin);
          if (s.jdbc.queryForObject(
                  "SELECT COUNT(*) FROM drilling_center_origin WHERE origin_type='ALARM' AND"
                      + " origin_key=?",
                  Integer.class,
                  key)
              > 0) return;
          s.jdbc.update(
              "INSERT INTO"
                  + " drilling_alarm(field_id,well_id,alarm_code,current_level,message,rule_snapshot_json,create_by)"
                  + " VALUES(?,?,?,'YELLOW',?,?,'system')",
              fieldId,
              well,
              code,
              clip(message, 1000),
              JSON.toJSONString(snapshot));
          Long id = s.jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
          s.jdbc.update(
              "INSERT INTO drilling_center_origin(origin_type,origin_key,field_id,resource_id)"
                  + " VALUES('ALARM',?,?,?)",
              key,
              fieldId,
              id);
        });
  }

  public void versionChange(
      Long fieldId, String actor, String action, String type, String key, Object detail) {
    s.jdbc.update(
        "INSERT INTO drilling_audit_log(field_id,action,resource_type,resource_id,detail_json)"
            + " VALUES(?,?,?,?,?)",
        fieldId,
        action,
        type,
        key,
        JSON.toJSONString(Map.of("actor", actor, "change", detail)));
  }

  public void synchronize(Long fieldId) {
    for (var e :
        s.jdbc.queryForList(
            "SELECT event_id,well_id,survey_version_id,status,raw_event_json,status_message FROM"
                + " drilling_realtime_ingest_event WHERE field_id=? AND status IN"
                + " ('INVALID','LATE','CONFLICT')",
            fieldId))
      quality(
          fieldId,
          number(e.get("well_id")),
          number(e.get("survey_version_id")),
          "LATE".equals(e.get("status"))
              ? "OUT_OF_ORDER"
              : "CONFLICT".equals(e.get("status")) ? "VERSION_CONFLICT" : "INVALID",
          "EVENT:" + e.get("event_id"),
          e.get("raw_event_json"),
          Objects.toString(e.get("status_message"), "接入记录已隔离"));
    for (var w :
        s.jdbc.queryForList(
            "SELECT w.well_id,v.survey_version_id,COALESCE(tv.trajectory_version_no,v.version_no) AS version_no FROM drilling_well w JOIN"
                + " drilling_survey_version v ON v.well_id=w.well_id AND v.is_current='1' LEFT JOIN drilling_trajectory_version tv ON tv.survey_version_id=v.survey_version_id WHERE"
                + " w.field_id=? AND w.status<>'ARCHIVED' AND NOT EXISTS(SELECT 1"
                + " FROM drilling_engineering_node en WHERE en.field_id=w.field_id"
                + " AND en.well_id=w.well_id AND en.node_type='WELL'"
                + " AND en.entity_kind='PLANNED_RELIEF' AND en.status='ACTIVE')",
            fieldId)) {
      Long well = number(w.get("well_id")), version = number(w.get("survey_version_id"));
      for (var bad :
          s.jdbc.queryForList(
              "SELECT station_id,md,inclination,azimuth,north,east,tvd FROM drilling_survey_station"
                  + " WHERE survey_version_id=? AND (md<0 OR inclination<0 OR inclination>180 OR"
                  + " azimuth<0 OR azimuth>=360 OR north IS NULL OR east IS NULL OR tvd IS NULL)",
              version))
        quality(
            fieldId,
            well,
            version,
            "INVALID_STATION",
            "STATION:" + bad.get("station_id"),
            bad,
            "测点角度、井深或坐标不完整/越界");
      for (var dup :
          s.jdbc.queryForList(
              "SELECT md,COUNT(*) station_count FROM drilling_survey_station WHERE"
                  + " survey_version_id=? GROUP BY md HAVING COUNT(*)>1",
              version))
        quality(
            fieldId,
            well,
            version,
            "DUPLICATE_MD",
            "DUPLICATE_MD:" + version + ":" + dup.get("md"),
            dup,
            "同一测斜版本存在重复井深");
    }
    for (var v :
        s.jdbc.queryForList(
            "SELECT w.well_id,COUNT(*) current_count FROM drilling_well w JOIN"
                + " drilling_survey_version v ON v.well_id=w.well_id AND v.is_current='1' WHERE"
                + " w.field_id=? AND NOT EXISTS(SELECT 1 FROM drilling_engineering_node en"
                + " WHERE en.field_id=w.field_id AND en.well_id=w.well_id AND en.node_type='WELL'"
                + " AND en.entity_kind='PLANNED_RELIEF' AND en.status='ACTIVE')"
                + " GROUP BY w.well_id HAVING COUNT(*)>1",
            fieldId))
      quality(
          fieldId,
          number(v.get("well_id")),
          null,
          "VERSION_CONFLICT",
          "CONFLICT:" + v.get("well_id"),
          v,
          "存在多个当前测斜版本，请核对后重新启用唯一版本");
    for (var issue :
        s.jdbc.queryForList(
            "SELECT issue_id,well_id,issue_type FROM drilling_data_quality_issue WHERE field_id=?"
                + " AND status='ISOLATED'",
            fieldId))
      alert(
          fieldId,
          number(issue.get("well_id")),
          "DATA_QUALITY",
          "QUALITY:" + issue.get("issue_id"),
          "数据质量问题：" + issue.get("issue_type"),
          Map.of("issue_id", issue.get("issue_id"), "issue_type", issue.get("issue_type")));
    for (var t :
        s.jdbc.queryForList(
            "SELECT * FROM ("
                + TaskHistoryController.SOURCE
                + ") q WHERE q.field_id=? AND q.status IN"
                + " ('FAILED','LOST','MODEL_NOT_CONFIGURED','INFEASIBLE')",
            fieldId))
      alert(
          fieldId,
          number(t.get("well_id")),
          (Set.of("TRAINING", "PREDICTION").contains(t.get("record_type"))
                  || "MODEL_NOT_CONFIGURED".equals(t.get("status")))
              ? "MODEL_FAILURE"
              : "TASK_FAILURE",
          "TASK:" + t.get("record_key"),
          t.get("title") + "：" + t.get("status"),
          Map.of("task_key", t.get("record_key"), "status", t.get("status")));
    for (var c :
        s.jdbc.queryForList(
            """
            SELECT connector_id,well_id,connector_name,status,last_error,last_heartbeat
              FROM drilling_realtime_connector c
             WHERE field_id=? AND (
               status IN ('ERROR','RECONNECTING') OR (
                 status='CONNECTED' AND TIMESTAMPDIFF(SECOND,last_heartbeat,NOW()) >
                   %s
               )
             )
            """.formatted(ConnectorPollingHealth.HEARTBEAT_LIMIT_SQL),
            fieldId))
      alert(
          fieldId,
          number(c.get("well_id")),
          "CONNECTION_FAILURE",
          "CONNECTOR:" + c.get("connector_id") + ":" + c.get("last_heartbeat"),
          "连接异常：" + c.get("connector_name"),
          Map.of("connector_id", c.get("connector_id"), "status", c.get("status")));
  }

  @Scheduled(initialDelay = 15000, fixedDelay = 30000)
  public void collect() {
    for (Long fieldId :
        s.jdbc.queryForList(
            "SELECT field_id FROM drilling_field WHERE status='0'", Long.class)) {
      try {
        synchronize(fieldId);
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn(
                "Business event collection failed for fieldId {} ({}): {}",
                fieldId,
                e.getClass().getSimpleName(),
                e.getMessage(),
                e);
      }
    }
  }

  public static Long number(Object x) {
    return x == null ? null : Long.valueOf(x.toString());
  }

  public static String clip(String s, int n) {
    return s == null ? "" : s.substring(0, Math.min(s.length(), n));
  }

  public static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
