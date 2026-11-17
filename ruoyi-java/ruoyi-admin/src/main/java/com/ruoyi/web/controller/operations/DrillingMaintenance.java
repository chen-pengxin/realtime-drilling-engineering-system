package com.ruoyi.web.controller.operations;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.framework.shiro.session.OnlineSessionDAO;
import com.ruoyi.system.service.ISysUserOnlineService;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.shiro.session.Session;
import org.springframework.stereotype.Component;

/** Only these three fixed, non-algorithm maintenance jobs are exposed in the UI. */
@Component("drillingMaintenance")
public class DrillingMaintenance {
  private final OperationsSupport ops;
  private final OperationsHealthService health;
  private final OnlineSessionDAO sessions;
  private final ISysUserOnlineService online;

  public DrillingMaintenance(
      OperationsSupport ops,
      OperationsHealthService health,
      OnlineSessionDAO sessions,
      ISysUserOnlineService online) {
    this.ops = ops;
    this.health = health;
    this.sessions = sessions;
    this.online = online;
  }

  private void result(String code, String outcome, Object summary) {
    ops.jdbc.update(
        "INSERT INTO drilling_maintenance_result(task_code,outcome,summary_json) VALUES(?,?,?)",
        code,
        outcome,
        JSON.toJSONString(summary));
  }

  public void checkServices() {
    Map<String, Object> snapshot = health.services();
    boolean ok =
        snapshot.values().stream()
            .allMatch(v -> v instanceof Map<?, ?> m && "UP".equals(m.get("status")));
    result("SERVICE_CHECK", ok ? "SUCCESS" : "FAILURE", snapshot);
    if (!ok) throw new IllegalStateException("服务健康检查发现不可用服务，详见运行监控");
  }

  public void cleanTemporaryFiles() throws Exception {
    int days = ops.intSetting("drilling.maintenance.retentionDays", 30);
    Instant cutoff = Instant.now().minus(Duration.ofDays(days));
    Path dir = Path.of(RuoYiConfig.getDownloadPath()).toAbsolutePath().normalize();
    int removed = 0;
    // Engineering uploads, model archives and survey versions live outside download/ and are never
    // visited.
    if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(dir)) {
      try (var paths = Files.walk(dir, 3)) {
        for (Path p :
            paths
                .filter(
                    p ->
                        Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isSymbolicLink(p))
                .toList()) {
          if (Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)
              && Files.deleteIfExists(p)) removed++;
        }
      }
    }
    result("TEMP_CLEANUP", "SUCCESS", Map.of("removedFiles", removed, "retentionDays", days));
  }

  public void expireSessions() {
    long now = System.currentTimeMillis();
    int removed = 0;
    for (Session s : new ArrayList<>(sessions.getActiveSessions()))
      if (now - s.getLastAccessTime().getTime() >= s.getTimeout()) {
        sessions.delete(s);
        online.deleteOnlineById(String.valueOf(s.getId()));
        removed++;
      }
    // Remove only expired persisted records; live cached sessions take precedence over old sync
    // timestamps.
    Set<String> live = new HashSet<>();
    for (Session s : sessions.getActiveSessions()) live.add(String.valueOf(s.getId()));
    for (Map<String, Object> r :
        ops.jdbc.queryForList(
            "SELECT sessionId FROM sys_user_online WHERE"
                + " TIMESTAMPDIFF(MICROSECOND,last_access_time,NOW()) >= expire_time*1000")) {
      String id = String.valueOf(r.get("sessionId"));
      if (!live.contains(id)) {
        online.deleteOnlineById(id);
        removed++;
      }
    }
    ops.jdbc.update("DELETE FROM drilling_revoked_session WHERE expires_at<=NOW()");
    result("SESSION_CLEANUP", "SUCCESS", Map.of("removedSessions", removed));
  }
}
