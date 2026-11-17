package com.ruoyi.web.controller.operations;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.session.OnlineSession;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.framework.security.DrillingAccountPolicy;
import com.ruoyi.framework.shiro.service.SysPasswordService;
import com.ruoyi.framework.shiro.session.OnlineSessionDAO;
import com.ruoyi.system.service.ISysUserOnlineService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.apache.shiro.session.InvalidSessionException;
import org.apache.shiro.session.Session;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/operations")
public class AccessMonitorController {
  private final OperationsSupport ops;
  private final OnlineSessionDAO sessions;
  private final ISysUserOnlineService online;
  private final SysPasswordService passwords;
  private final DrillingAccountPolicy policy;

  public AccessMonitorController(
      OperationsSupport ops,
      OnlineSessionDAO sessions,
      ISysUserOnlineService online,
      SysPasswordService passwords,
      DrillingAccountPolicy policy) {
    this.ops = ops;
    this.sessions = sessions;
    this.online = online;
    this.passwords = passwords;
    this.policy = policy;
  }

  private StringBuilder loginWhere(Map<String, String> p, List<Object> a) {
    StringBuilder w =
        new StringBuilder(
            "FROM sys_logininfor l LEFT JOIN sys_user u ON u.login_name=l.login_name WHERE 1=1");
    OperationsSupport.like(p, "account", "CONCAT(l.login_name,' ',COALESCE(u.user_name,''))", w, a);
    OperationsSupport.like(p, "ipaddr", "l.ipaddr", w, a);
    String status = p.get("status");
    if (status != null && !status.isEmpty()) {
      if (!Set.of("0", "1").contains(status)) throw new IllegalArgumentException("登录状态无效");
      w.append(" AND l.status=?");
      a.add(status);
    }
    OperationsSupport.dates(p, "l.login_time", w, a);
    return w;
  }

  private static final String LOGIN_SELECT =
      "SELECT l.info_id AS infoId,l.login_name AS loginName,u.user_name AS"
          + " userName,l.ipaddr,l.login_location AS"
          + " loginLocation,l.browser,l.os,l.status,l.msg,l.login_time AS loginTime";

  @GetMapping("/login-logs")
  public AjaxResult logins(@RequestParam Map<String, String> p) {
    ops.admin();
    List<Object> a = new ArrayList<>();
    String w = loginWhere(p, a).toString();
    return ops.pageQuery(LOGIN_SELECT, w, a, "ORDER BY l.info_id DESC", p);
  }

  @GetMapping("/login-logs/export")
  public ResponseEntity<byte[]> export(@RequestParam Map<String, String> p) {
    ops.admin();
    List<Object> a = new ArrayList<>();
    String w = loginWhere(p, a).toString();
    List<Map<String, Object>> rows =
        ops.jdbc.queryForList(
            LOGIN_SELECT + " " + w + " ORDER BY l.info_id DESC LIMIT 10000", a.toArray());
    return OperationsSupport.csv(
        "login-logs.csv",
        List.of("账号ID", "账号名称", "IP", "浏览器", "系统", "状态", "说明", "时间"),
        rows.stream()
            .map(
                r ->
                    Arrays.asList(
                        r.get("loginName"),
                        r.get("userName"),
                        r.get("ipaddr"),
                        r.get("browser"),
                        r.get("os"),
                        r.get("status"),
                        r.get("msg"),
                        r.get("loginTime")))
            .toList());
  }

  @PostMapping("/login-logs/unlock")
  public AjaxResult unlock(@RequestBody Map<String, Object> b) {
    ops.admin();
    String id = OperationsSupport.text(b, "account", 30, true);
    Long uid = policy.resolveUserId(id);
    if (uid == null) throw new IllegalArgumentException("账号不存在");
    String login =
        ops.jdbc.queryForObject(
            "SELECT login_name FROM sys_user WHERE user_id=? AND del_flag='0'", String.class, uid);
    passwords.clearLoginRecordCache(login);
    return AjaxResult.success("登录失败计数已清除");
  }

  static String sessionKey(Object id) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(String.valueOf(id).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Collection<Session> active() {
    Map<Object, Session> found = new LinkedHashMap<>();
    for (Session s : sessions.getActiveSessions()) found.put(s.getId(), s);
    // The DAO also restores persisted sessions after a Java restart.
    for (Map<String, Object> r :
        ops.jdbc.queryForList("SELECT sessionId FROM sys_user_online WHERE status='on_line'")) {
      try {
        Session s = sessions.readSession(String.valueOf(r.get("sessionId")));
        found.put(s.getId(), s);
      } catch (InvalidSessionException ignored) {
      }
    }
    return found.values();
  }

  @GetMapping("/sessions")
  public AjaxResult listSessions(@RequestParam Map<String, String> p) {
    ops.admin();
    List<Map<String, Object>> rows = new ArrayList<>();
    long now = System.currentTimeMillis();
    for (Session s : active()) {
      if (!(s instanceof OnlineSession o)
          || o.getUserId() == null
          || o.getLoginName() == null
          || o.getLoginName().isBlank()
          || now - s.getLastAccessTime().getTime() >= s.getTimeout()) continue;
      String login = o.getLoginName();
      List<Map<String, Object>> users =
          ops.jdbc.queryForList(
              "SELECT user_name AS userName FROM sys_user WHERE user_id=? AND del_flag='0'",
              o.getUserId());
      String name = users.isEmpty() ? login : String.valueOf(users.get(0).get("userName"));
      if (!((login + " " + name).contains(p.getOrDefault("account", "")))
          || !Objects.toString(o.getHost(), "").contains(p.getOrDefault("ipaddr", ""))) continue;
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("sessionKey", sessionKey(s.getId()));
      r.put("loginName", login);
      r.put("userName", name);
      r.put("ipaddr", o.getHost());
      r.put("browser", o.getBrowser());
      r.put("os", o.getOs());
      r.put("startTime", s.getStartTimestamp());
      r.put("lastAccessTime", s.getLastAccessTime());
      r.put("timeoutMinutes", s.getTimeout() / 60000);
      r.put("current", String.valueOf(s.getId()).equals(ShiroUtils.getSessionId()));
      rows.add(r);
    }
    rows.sort(
        Comparator.comparing(r -> ((Date) r.get("lastAccessTime")), Comparator.reverseOrder()));
    int start = Math.min(rows.size(), (OperationsSupport.page(p) - 1) * OperationsSupport.size(p));
    AjaxResult result = AjaxResult.success();
    result.put(
        "rows", rows.subList(start, Math.min(rows.size(), start + OperationsSupport.size(p))));
    result.put("total", rows.size());
    return result;
  }

  @PostMapping("/sessions/{key}/revoke")
  public AjaxResult revoke(@PathVariable String key) {
    ops.admin();
    if (!key.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("会话标识无效");
    for (Session s : active())
      if (sessionKey(s.getId()).equals(key)) {
        if (String.valueOf(s.getId()).equals(ShiroUtils.getSessionId()))
          throw new IllegalArgumentException("不能强制退出当前会话，请使用退出登录");
        ops.jdbc.update(
            "INSERT INTO drilling_revoked_session(session_key,revoked_by,expires_at)"
                + " VALUES(?,?,TIMESTAMPADD(DAY,2,NOW())) ON DUPLICATE KEY UPDATE"
                + " revoked_by=VALUES(revoked_by),expires_at=VALUES(expires_at)",
            key,
            ShiroUtils.getUserId());
        sessions.delete(s);
        online.deleteOnlineById(String.valueOf(s.getId()));
        if (s instanceof OnlineSession o)
          online.removeUserCache(o.getLoginName(), String.valueOf(s.getId()));
        return AjaxResult.success("会话已退出");
      }
    return AjaxResult.success("该会话已下线");
  }
}
