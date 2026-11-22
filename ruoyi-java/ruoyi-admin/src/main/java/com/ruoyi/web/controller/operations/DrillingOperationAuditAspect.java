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
  private final ManagementChangeAudit changes;
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

  public DrillingOperationAuditAspect(OperationsSupport ops, ManagementChangeAudit changes) {
    this.ops = ops;
    this.changes = changes;
  }

  @Around(
      "@within(org.springframework.web.bind.annotation.RestController) && !@annotation(org.springframework.web.bind.annotation.ExceptionHandler) && (execution(public *"
          + " com.ruoyi.web.controller.drilling..*(..)) || execution(public *"
          + " com.ruoyi.web.controller.operations..*(..)) || execution(public * com.ruoyi.web.controller.system.DrillingAccessController.*(..)))")
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
    var change = ManagementChangeAudit.resolve(method,path,uid);
    Map<String,Object> summary = new LinkedHashMap<>();
    long start = System.nanoTime();
    Object result = null;
    Throwable failure = null;
    try {
      result = change == null ? join.proceed() : changes.perform(change,method,join,summary);
      return result;
    } catch (Throwable e) {
      failure = e;
      throw e;
    } finally {
      try {
        record(join,req,path,method,uid,actor,start,result,failure,change,summary);
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Unable to persist operation audit ({})", e.getClass().getSimpleName());
      }
    }
  }

  private void record(ProceedingJoinPoint join, HttpServletRequest req, String path, String method, Long uid, String actor,
                      long start, Object result, Throwable failure, ManagementChangeAudit.Change change, Map<String,Object> summary) {
    Map<String, Object> context = new LinkedHashMap<>();
    for (String field : FIELDS) {
      String v = req.getParameter(field);
      if (v != null) capture(Map.of(field,v),context);
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
    boolean account = path.startsWith("/api/access/");
    // Successful account mutations already write a transactional domain event.
    if (account && !failed && change == null) return;
    if (path.equals("/api/operations/login-logs/unlock") || (account && failed)) {
      for (Object arg : join.getArgs()) if (arg instanceof Map<?,?> b)
        for (String key : List.of("loginName","account","roleKey","ids"))
          if (b.get(key) instanceof String value && value.matches("[\\p{L}0-9_.@-]{1,64}")) summary.put(key,value);
          else if ("ids".equals(key) && b.get(key) instanceof List<?> ids)
            summary.put(key,ids.stream().filter(Number.class::isInstance).toList());
    }
    if (account && change == null) {
      Matcher target = Pattern.compile("/api/access/(?:users|roles)/([0-9]+)").matcher(path);
      String targetId = target.find() ? target.group(1) : path.startsWith("/api/access/me/") ? uid.toString() : null;
      summary.put("resource", path);
      summary.put("method", method);
      summary.put("outcome","FAILURE");
      summary.put("clientIp",req.getRemoteAddr());
      if (failure != null) summary.put("errorType",failure.getClass().getSimpleName());
      ops.jdbc.update("INSERT INTO drilling_account_audit(actor_id,target_id,action,detail_json) VALUES(?,?,?,?)",
          uid,targetId,join.getSignature().getName(),JSON.toJSONString(summary));
      return;
    }
    String action = Objects.toString(summary.remove("action"), method + " " + path);
    if (path.equals("/api/operations/backups") || path.startsWith("/api/operations/backups/")) {
      action = path.endsWith("/restore") ? "恢复备份到独立副本" : "创建系统备份";
      if (req.getAttribute("drilling.backupDetail") instanceof Map<?,?> detail) {
        for (String key : List.of("operationId","backupId","recovery","error","warning"))
          if (detail.get(key) instanceof String text) summary.put(key,text);
      } else if (failed) summary.put("error","请求未能完成，请检查备份配置、编号和权限");
    }
    if (path.equals("/api/operations/login-logs/unlock")) action = "解除登录失败锁定";
    Matcher prediction = Pattern.compile("/api/drilling/predictions/([A-Za-z0-9_-]{1,100})/export").matcher(path);
    if (prediction.matches()) {
      action = "导出预测轨迹 CSV";
      context.put("taskId",prediction.group(1));
      if(req.getAttribute("audit.fieldId") instanceof Number n) field=n.toString();
      if(req.getAttribute("audit.wellId") instanceof Number n) well=n.toString();
    }
    if (path.matches("/api/operations/sessions/[a-f0-9]{64}/revoke")) {
      path = "/api/operations/sessions/{device}/revoke";
      action = "强制退出其他会话";
      summary.put("targetAccount",req.getAttribute("audit.targetAccount"));
    }
    summary.put("context", context);
    if (failure != null) summary.put("errorType", failure.getClass().getSimpleName());
    ops.jdbc.update(
        "INSERT INTO"
            + " drilling_system_audit(actor_user_id,actor_name,action,field_id,well_id,survey_version_id,task_id,request_id,resource_path,http_method,outcome,detail_json,client_ip,duration_ms)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        uid,
        actor,
        action.substring(0, Math.min(255, action.length())),
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
  }

  private static void capture(Map<?, ?> source, Map<String, Object> dest) {
    for (String key : FIELDS) {
      Object v = source.get(key);
      if (!(v instanceof Number || v instanceof String)) continue;
      String value = String.valueOf(v);
      boolean task = key.equals("taskId") || key.equals("task_id");
      if (task ? value.matches("[A-Za-z0-9_-]{1,100}") : value.matches("[0-9]{1,18}")) dest.put(key,v);
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
