package com.ruoyi.web.controller.operations;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.quartz.domain.SysJob;
import com.ruoyi.quartz.service.ISysJobService;
import com.ruoyi.quartz.util.ScheduleUtils;
import java.util.*;
import org.quartz.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/operations")
public class OperationsJobsController {
  private final OperationsSupport ops;
  private final ISysJobService jobs;
  private final Scheduler scheduler;
  private final OperationsHealthService health;
  private static final Map<String, List<String>> DEFINITIONS = new LinkedHashMap<>();

  static {
    DEFINITIONS.put(
        "SERVICE_CHECK", List.of("服务健康检查", "drillingMaintenance.checkServices()", "0 0/5 * * * ?"));
    DEFINITIONS.put(
        "TEMP_CLEANUP",
        List.of("临时导出文件清理", "drillingMaintenance.cleanTemporaryFiles()", "0 0 3 * * ?"));
    DEFINITIONS.put(
        "SESSION_CLEANUP",
        List.of("过期会话整理", "drillingMaintenance.expireSessions()", "0 0/10 * * * ?"));
  }

  public OperationsJobsController(
      OperationsSupport ops,
      ISysJobService jobs,
      Scheduler scheduler,
      OperationsHealthService health) {
    this.ops = ops;
    this.jobs = jobs;
    this.scheduler = scheduler;
    this.health = health;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void initialize() throws Exception {
    for (var entry : DEFINITIONS.entrySet()) {
      List<String> d = entry.getValue();
      Integer n =
          ops.jdbc.queryForObject(
              "SELECT COUNT(*) FROM sys_job WHERE job_group='DRILLING' AND invoke_target=?",
              Integer.class,
              d.get(1));
      if (n == 0) {
        SysJob j = new SysJob();
        j.setJobName(d.get(0));
        j.setJobGroup("DRILLING");
        j.setInvokeTarget(d.get(1));
        j.setCronExpression(d.get(2));
        j.setMisfirePolicy("3");
        j.setConcurrent("1");
        j.setCreateBy("system");
        jobs.insertJob(j);
      }
    }
  }

  @GetMapping("/monitor")
  public AjaxResult monitor() throws Exception {
    ops.admin();
    return AjaxResult.success(health.snapshot());
  }

  private String code(SysJob j) {
    return DEFINITIONS.entrySet().stream()
        .filter(e -> e.getValue().get(1).equals(j.getInvokeTarget()))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("仅可管理预置维护任务"));
  }

  private SysJob job(Long id) {
    SysJob j = jobs.selectJobById(id);
    if (j == null || !"DRILLING".equals(j.getJobGroup()))
      throw new IllegalArgumentException("维护任务不存在");
    code(j);
    return j;
  }

  private boolean running(SysJob j) throws SchedulerException {
    return scheduler.getCurrentlyExecutingJobs().stream()
        .anyMatch(
            c ->
                c.getJobDetail()
                    .getKey()
                    .equals(ScheduleUtils.getJobKey(j.getJobId(), j.getJobGroup())));
  }

  @GetMapping("/jobs")
  public AjaxResult list() throws Exception {
    ops.admin();
    SysJob q = new SysJob();
    q.setJobGroup("DRILLING");
    List<Map<String, Object>> rows = new ArrayList<>();
    for (SysJob j : jobs.selectJobList(q)) {
      String code;
      try {
        code = code(j);
      } catch (IllegalArgumentException ignored) {
        continue;
      }
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("jobId", j.getJobId());
      r.put("jobName", j.getJobName());
      r.put("taskCode", code);
      r.put("cronExpression", j.getCronExpression());
      r.put("status", j.getStatus());
      r.put("running", running(j));
      CronExpression cron = new CronExpression(j.getCronExpression());
      cron.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
      r.put("nextRun", "0".equals(j.getStatus()) ? cron.getNextValidTimeAfter(new Date()) : null);
      List<Map<String, Object>> last =
          ops.jdbc.queryForList(
              "SELECT outcome,summary_json AS summaryJson,create_time AS createTime FROM"
                  + " drilling_maintenance_result WHERE task_code=? ORDER BY result_id DESC LIMIT"
                  + " 1",
              code);
      r.put("lastResult", last.isEmpty() ? null : last.get(0));
      rows.add(r);
    }
    return AjaxResult.success(rows);
  }

  @PutMapping("/jobs/{id}")
  public AjaxResult update(@PathVariable Long id, @RequestBody Map<String, Object> b)
      throws Exception {
    ops.admin();
    SysJob j = job(id);
    if (b.containsKey("invokeTarget") || b.containsKey("jobGroup"))
      throw new IllegalArgumentException("维护任务执行目标不可修改");
    String expr = OperationsSupport.text(b, "cronExpression", 100, true);
    if (!CronExpression.isValidExpression(expr)) throw new IllegalArgumentException("Cron表达式无效");
    CronExpression c = new CronExpression(expr);
    c.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
    Date first = c.getNextValidTimeAfter(new Date()),
        second = first == null ? null : c.getNextValidTimeAfter(first);
    if (first == null || second == null || second.getTime() - first.getTime() < 10000)
      throw new IllegalArgumentException("执行计划必须有后续时间，且相邻执行间隔至少10秒");
    j.setCronExpression(expr);
    j.setStatus(OperationsSupport.state(b));
    j.setUpdateBy(ShiroUtils.getLoginName());
    jobs.updateJob(j);
    return AjaxResult.success();
  }

  @PostMapping("/jobs/{id}/run")
  public AjaxResult run(@PathVariable Long id) throws Exception {
    ops.admin();
    SysJob j = job(id);
    if (running(j)) throw new IllegalArgumentException("任务正在执行，请稍后查看日志");
    if (!jobs.run(j)) throw new IllegalArgumentException("调度器中不存在此任务");
    return AjaxResult.success("任务已提交，执行结果请查看调度日志");
  }

  private String logsWhere(Map<String, String> p, List<Object> a) {
    StringBuilder w = new StringBuilder("FROM sys_job_log WHERE job_group='DRILLING'");
    String code = p.get("taskCode");
    if (code != null && !code.isBlank()) {
      if (!DEFINITIONS.containsKey(code)) throw new IllegalArgumentException("维护任务无效");
      w.append(" AND invoke_target=?");
      a.add(DEFINITIONS.get(code).get(1));
    }
    String s = p.get("status");
    if (s != null && !s.isBlank()) {
      if (!Set.of("0", "1").contains(s)) throw new IllegalArgumentException("状态无效");
      w.append(" AND status=?");
      a.add(s);
    }
    OperationsSupport.dates(p, "create_time", w, a);
    return w.toString();
  }

  private static final String LOGS =
      "SELECT job_log_id AS jobLogId,job_name AS jobName,status,job_message AS"
          + " jobMessage,exception_info AS exceptionInfo,create_time AS createTime";

  @GetMapping("/job-logs")
  public AjaxResult logs(@RequestParam Map<String, String> p) {
    ops.admin();
    List<Object> a = new ArrayList<>();
    return ops.pageQuery(LOGS, logsWhere(p, a), a, "ORDER BY job_log_id DESC", p);
  }

  @GetMapping("/job-logs/export")
  public ResponseEntity<byte[]> export(@RequestParam Map<String, String> p) {
    ops.admin();
    List<Object> a = new ArrayList<>();
    List<Map<String, Object>> r =
        ops.jdbc.queryForList(
            LOGS + " " + logsWhere(p, a) + " ORDER BY job_log_id DESC LIMIT 10000", a.toArray());
    return OperationsSupport.csv(
        "maintenance-logs.csv",
        List.of("任务", "状态", "说明", "时间"),
        r.stream()
            .map(
                x ->
                    Arrays.asList(
                        x.get("jobName"),
                        x.get("status"),
                        x.get("jobMessage"),
                        x.get("createTime")))
            .toList());
  }
}
