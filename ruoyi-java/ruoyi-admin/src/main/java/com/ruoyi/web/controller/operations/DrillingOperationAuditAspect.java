package com.ruoyi.web.controller.operations;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.utils.ShiroUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.regex.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

/** Adds request-level provenance. Domain audits retain their detailed configuration snapshots. */
@Aspect
@Component
@Order(100)
public class DrillingOperationAuditAspect {
  private final OperationsSupport ops;
  private static final Set<String> FIELDS =
      Set.of(
          "fieldId",
          "field_id",
          "wellId",
          "well_id",
          "reference_well_id",
          "surveyVersionId",
          "survey_version_id",
          "reference_survey_version_id",
          "taskId",
          "task_id",
          "modelVersion",
          "model_version",
          "config_version");

  public DrillingOperationAuditAspect(OperationsSupport ops) {
    this.ops = ops;
  }

  @Around(
      "@within(org.springframework.web.bind.annotation.RestController) && (execution(public *"
          + " com.ruoyi.web.controller.drilling..*(..)) || execution(public *"
          + " com.ruoyi.web.controller.operations..*(..)))")
  public Object audit(ProceedingJoinPoint join) throws Throwable {
    var attributes = RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof ServletRequestAttributes a)) return join.proceed();
    HttpServletRequest req = a.getRequest();
    String path = req.getServletPath(), method = req.getMethod();
    if ("GET".equals(method) && !path.contains("export") && !path.contains("download"))
      return join.proceed();
    // Notice read receipts already have a dedicated, idempotent record.
    if (path.startsWith("/api/notices/")) return join.proceed();
    Long uid;
    String actor;
    try {
      uid = ShiroUtils.getUserId();
      actor = ShiroUtils.getLoginName();
    } catch (Exception e) {
      return join.proceed();
    }
    if (uid == null) return join.proceed();
    long start = System.nanoTime();
    Object result = null;
    Throwable failure = null;
    try {
      result = join.proceed();
      return result;
    } catch (Throwable e) {
      failure = e;
      throw e;
    } finally {
      try {
        Map<String, Object> context = new LinkedHashMap<>();
        for (String field : FIELDS) {
          String v = req.getParameter(field);
          if (v != null && v.length() <= 128) context.put(field, v);
        }
        for (Object arg : join.getArgs()) if (arg instanceof Map<?, ?> m) capture(m, context);
        Object payload = result instanceof ResponseEntity<?> r ? r.getBody() : result;
        if (payload instanceof String s && s.startsWith("{") && s.length() < 2000000) {
          try {
            payload = JSON.parseObject(s);
          } catch (Exception ignored) {
          }
        }
        if (payload instanceof Map<?, ?> m) {
          capture(m, context);
          if (m.get("data") instanceof Map<?, ?> d) capture(d, context);
        }
        String field = number(context, "fieldId", "field_id");
        Matcher match = Pattern.compile("/fields/(\\d+)").matcher(path);
        if (match.find()) field = match.group(1);
        String well = number(context, "wellId", "well_id", "reference_well_id");
        match = Pattern.compile("/wells/(\\d+)").matcher(path);
        if (match.find()) well = match.group(1);
        String version =
            number(context, "surveyVersionId", "survey_version_id", "reference_survey_version_id");
        boolean failed =
            failure != null
                || (result instanceof ResponseEntity<?> r && r.getStatusCode().isError())
                || (payload instanceof Map<?, ?> m
                    && m.get("code") instanceof Number n
                    && n.intValue() >= 400);
        String requestId = req.getHeader("X-Request-ID");
        if (requestId == null || !requestId.matches("[A-Za-z0-9-]{1,64}"))
          requestId = UUID.randomUUID().toString();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("context", context);
        if (failure != null) summary.put("errorType", failure.getClass().getSimpleName());
        ops.jdbc.update(
            "INSERT INTO"
                + " drilling_system_audit(actor_user_id,actor_name,action,field_id,well_id,survey_version_id,task_id,request_id,resource_path,http_method,outcome,detail_json,client_ip,duration_ms)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            uid,
            actor,
            (method + " " + path).substring(0, Math.min(255, (method + " " + path).length())),
            field,
            well,
            version,
            Objects.toString(context.getOrDefault("taskId", context.get("task_id")), null),
            requestId,
            path.substring(0, Math.min(500, path.length())),
            method,
            failed ? "FAILURE" : "SUCCESS",
            JSON.toJSONString(summary),
            req.getRemoteAddr(),
            (System.nanoTime() - start) / 1000000);
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Unable to persist operation audit", e);
      }
    }
  }

  private static void capture(Map<?, ?> source, Map<String, Object> dest) {
    for (String key : FIELDS) {
      Object v = source.get(key);
      if (v != null
          && (v instanceof Number || v instanceof String)
          && String.valueOf(v).length() <= 128) dest.put(key, v);
    }
  }

  private static String number(Map<String, Object> m, String... keys) {
    for (String key : keys) {
      String v = Objects.toString(m.get(key), "");
      if (v.matches("[1-9][0-9]{0,17}")) return v;
    }
    return null;
  }
}
