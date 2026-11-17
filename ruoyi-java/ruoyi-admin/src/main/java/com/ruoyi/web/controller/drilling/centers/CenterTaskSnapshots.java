package com.ruoyi.web.controller.drilling.centers;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.utils.ShiroUtils;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class CenterTaskSnapshots {
  private final JdbcTemplate jdbc;

  public CenterTaskSnapshots(BusinessCenterSupport s) {
    jdbc = s.jdbc;
  }

  @jakarta.annotation.PostConstruct
  public void recoverInterrupted() {
    jdbc.update(
        "UPDATE drilling_center_execution SET"
            + " status='FAILED',error_message='服务重启前调用未完成，输入已保留',finish_time=CURRENT_TIMESTAMP(3)"
            + " WHERE status='RUNNING' AND source_id IS NULL");
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public long start(String type, Map<String, Object> input) {
    Long fieldId = Long.valueOf(input.get("field_id").toString());
    var configs =
        jdbc.queryForList(
            "SELECT * FROM drilling_field_config WHERE field_id=? AND status='0' AND"
                + " effective_to IS NULL ORDER BY config_version DESC LIMIT 1",
            fieldId);
    Object well = input.getOrDefault("reference_well_id", input.get("well_id"));
    GeneratedKeyHolder key = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO"
                      + " drilling_center_execution(field_id,well_id,task_type,input_json,config_snapshot_json,create_by)"
                      + " VALUES(?,?,?,?,?,?)",
                  Statement.RETURN_GENERATED_KEYS);
          ps.setObject(1, fieldId);
          ps.setObject(2, well);
          ps.setString(3, type);
          ps.setString(4, JSON.toJSONString(input));
          ps.setString(5, JSON.toJSONString(configs.isEmpty() ? null : configs.get(0)));
          ps.setString(6, ShiroUtils.getLoginName());
          return ps;
        },
        key);
    return key.getKey().longValue();
  }

  public void complete(long execution, String type, long source) {
    jdbc.update(
        "INSERT INTO"
            + " drilling_center_snapshot(task_type,source_id,field_id,input_json,config_snapshot_json,started_at,finished_at)"
            + " SELECT"
            + " task_type,?,field_id,input_json,config_snapshot_json,create_time,CURRENT_TIMESTAMP(3)"
            + " FROM drilling_center_execution WHERE execution_id=?",
        source,
        execution);
    jdbc.update(
        "UPDATE drilling_center_execution SET"
            + " source_id=?,status='LINKED',finish_time=CURRENT_TIMESTAMP(3) WHERE execution_id=?",
        source,
        execution);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void fail(long execution, String message) {
    jdbc.update(
        "UPDATE drilling_center_execution SET"
            + " status='FAILED',error_message=?,finish_time=CURRENT_TIMESTAMP(3) WHERE"
            + " execution_id=?",
        message == null ? "算法服务调用失败" : message.substring(0, Math.min(1000, message.length())),
        execution);
  }

  public void capture(String type, long source, Long fieldId, Object input) {
    var configs =
        jdbc.queryForList(
            "SELECT * FROM drilling_field_config WHERE field_id=? AND status='0' AND"
                + " effective_to IS NULL ORDER BY config_version DESC LIMIT 1",
            fieldId);
    capture(
        type,
        source,
        fieldId,
        input,
        configs.isEmpty() ? null : configs.get(0),
        new Timestamp(System.currentTimeMillis()));
  }

  public void capture(
      String type, long source, Long fieldId, Object input, Object config, Timestamp started) {
    jdbc.update(
        "INSERT IGNORE INTO"
            + " drilling_center_snapshot(task_type,source_id,field_id,input_json,config_snapshot_json,started_at,finished_at)"
            + " VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP(3))",
        type,
        source,
        fieldId,
        JSON.toJSONString(input),
        JSON.toJSONString(config),
        started);
  }
}
