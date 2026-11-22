package com.ruoyi.web.controller.operations;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.session.OnlineSession;
import com.ruoyi.common.enums.OnlineStatus;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.framework.security.DrillingAccountPolicy;
import com.ruoyi.framework.shiro.service.SysPasswordService;
import com.ruoyi.framework.shiro.session.OnlineSessionDAO;
import com.ruoyi.framework.security.DrillingSessionActivity;
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

  private static final String LOGIN_STATUS = "CASE WHEN l.status='0' AND l.msg='退出成功' THEN '2' ELSE l.status END";

  private StringBuilder loginWhere(Map<String, String> p, List<Object> a) {
    StringBuilder w =
        new StringBuilder(
            "FROM sys_logininfor l LEFT JOIN sys_user u ON u.login_name=l.login_name AND u.del_flag='0' WHERE 1=1");
    OperationsSupport.like(p, "account", "CONCAT(l.login_name,' ',COALESCE(u.user_name,''))", w, a);
    OperationsSupport.like(p, "ipaddr", "l.ipaddr", w, a);
    String status = p.get("status");
    if (status != null && !status.isEmpty()) {
      if (!Set.of("0", "1", "2").contains(status)) throw new IllegalArgumentException("登录状态无效");
      w.append(" AND ").append(LOGIN_STATUS).append("=?");
      a.add(status);
    }
    OperationsSupport.dates(p, "l.login_time", w, a);
    return w;
  }

  private static final String LOGIN_SELECT =
      "SELECT l.info_id AS infoId,l.login_name AS loginName,u.user_name AS"
          + " userName,l.ipaddr,l.login_location AS"
          + " loginLocation,l.browser,l.os," + LOGIN_STATUS + " AS status,u.login_name AS canonicalLogin,l.msg,l.login_time AS loginTime";

  @GetMapping("/login-logs")
  public AjaxResult logins(@RequestParam Map<String, String> p) {
    ops.admin();
    List<Object> a = new ArrayList<>();
    String w = loginWhere(p, a).toString();
    AjaxResult page = ops.pageQuery(LOGIN_SELECT, w, a, "ORDER BY l.info_id DESC", p);
    Map<String, Boolean> lockStates = new HashMap<>();
    for (Map<String,Object> row : (List<Map<String,Object>>) page.get("rows")) {
      String login = (String)row.get("canonicalLogin");
      row.put("failureLocked", login != null && lockStates.computeIfAbsent(login, passwords::isFailureLocked));
    }
    return page;
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
                        Map.of("0","登录成功","1","登录失败","2","退出登录").get(r.get("status")),
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
    if (!id.equals(login) || !passwords.unlockFailures(login))
      throw new IllegalArgumentException("该账号当前没有登录失败锁定");
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

  private Collection<Session> active(String loginName) {
    Map<Object, Session> found = new LinkedHashMap<>();
    for (Session s : sessions.getActiveSessions()) found.put(s.getId(), s);
    // The DAO also restores persisted sessions after a Java restart.
    String query = "SELECT sessionId FROM sys_user_online WHERE status='on_line'";
    List<String> ids = loginName == null
        ? ops.jdbc.queryForList(query, String.class)
        : ops.jdbc.queryForList(query + " AND login_name=?", String.class, loginName);
    for (String id : ids) {
      try {
        Session s = sessions.readSession(id);
        found.put(s.getId(), s);
      } catch (InvalidSessionException ignored) {
      }
    }
    return found.values();
  }

  private Map<String, Object> deviceDetails(OnlineSession session) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("sessionKey", sessionKey(session.getId()));
    row.put("ipaddr", session.getHost());
    row.put("browser", session.getBrowser());
    row.put("os", session.getOs());
    row.put("startTime", session.getStartTimestamp());
    row.put("lastAccessTime", new Date(DrillingSessionActivity.lastInput(session)));
    row.put("current", String.valueOf(session.getId()).equals(ShiroUtils.getSessionId()));
    return row;
  }

  private List<OnlineSession> myDevices() {
    Long userId = ShiroUtils.getUserId();
    long now = System.currentTimeMillis();
    long timeout = policy.sessionTimeoutMillis();
    return active(ShiroUtils.getLoginName()).stream()
        .filter(OnlineSession.class::isInstance)
        .map(OnlineSession.class::cast)
        .filter(s -> userId.equals(s.getUserId()) && s.getStatus() == OnlineStatus.on_line)
        .filter(s -> !DrillingSessionActivity.expired(s, timeout, now))
        .filter(s -> s.isValid() && (s.getTimeout() <= 0 || now - s.getLastAccessTime().getTime() < s.getTimeout()))
        .filter(s -> !policy.sessionRevoked(String.valueOf(s.getId())))
        .sorted(Comparator.comparingLong(DrillingSessionActivity::lastInput).reversed())
        .toList();
  }

  @GetMapping("/sessions/self")
  public AjaxResult devices() {
    return AjaxResult.success(myDevices().stream().map(this::deviceDetails).toList());
  }

  @PostMapping("/sessions/self/revoke-selected")
  public AjaxResult revokeSelected(@RequestBody List<String> keys) {
    if (keys.isEmpty() || keys.stream().anyMatch(k -> k == null || !k.matches("[a-f0-9]{64}")))
      throw new IllegalArgumentException("请选择要退出的登录设备");
    Set<String> selected = new HashSet<>(keys);
    if (selected.contains(sessionKey(ShiroUtils.getSessionId())))
      throw new IllegalArgumentException("当前登录无法在设备列表中退出");
    int count = 0;
    for (OnlineSession session : myDevices()) {
      if (selected.contains(sessionKey(session.getId()))) {
        terminate(session);
        count++;
      }
    }
    return AjaxResult.success(Map.of("count", count));
  }

  @GetMapping("/sessions")
  public AjaxResult listSessions(@RequestParam Map<String, String> p) {
    ops.admin();
    List<Map<String, Object>> rows = new ArrayList<>();
    long now = System.currentTimeMillis();
    long timeout = policy.sessionTimeoutMillis();
    for (Session s : active(null)) {
      if (!(s instanceof OnlineSession o)
          || o.getUserId() == null
          || o.getLoginName() == null
          || o.getLoginName().isBlank()
          || DrillingSessionActivity.expired(s, timeout, now)
          || !o.isValid() || o.getStatus() != OnlineStatus.on_line || policy.sessionRevoked(String.valueOf(s.getId()))
          || (s.getTimeout()>0 && now - s.getLastAccessTime().getTime() >= s.getTimeout())) continue;
      String login = o.getLoginName();
      List<Map<String, Object>> users =
          ops.jdbc.queryForList(
              "SELECT user_name AS userName FROM sys_user WHERE user_id=? AND del_flag='0'",
              o.getUserId());
      String name = users.isEmpty() ? login : String.valueOf(users.get(0).get("userName"));
      if (!((login + " " + name).contains(p.getOrDefault("account", "")))
          || !Objects.toString(o.getHost(), "").contains(p.getOrDefault("ipaddr", ""))) continue;
      Map<String, Object> r = deviceDetails(o);
      r.put("loginName", login);
      r.put("userName", name);
      r.put("timeoutMinutes", timeout / 60000);
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
    for (Session s : active(null))
      if (sessionKey(s.getId()).equals(key)) {
        if (String.valueOf(s.getId()).equals(ShiroUtils.getSessionId()))
          throw new IllegalArgumentException("不能强制退出当前会话，请使用退出登录");
        if (s instanceof OnlineSession o) com.ruoyi.common.utils.ServletUtils.getRequest().setAttribute("audit.targetAccount",o.getLoginName());
        terminate(s);
        return AjaxResult.success("会话已退出");
      }
    return AjaxResult.success("该会话已下线");
  }

  private void terminate(Session s) {
        ops.jdbc.update(
            "INSERT INTO drilling_revoked_session(session_key,revoked_by,expires_at)"
                + " VALUES(?,?,TIMESTAMPADD(DAY,2,NOW())) ON DUPLICATE KEY UPDATE"
                + " revoked_by=VALUES(revoked_by),expires_at=VALUES(expires_at)",
            sessionKey(s.getId()),
            ShiroUtils.getUserId());
        sessions.delete(s);
        online.deleteOnlineById(String.valueOf(s.getId()));
        if (s instanceof OnlineSession o)
          online.removeUserCache(o.getLoginName(), String.valueOf(s.getId()));
  }

  @PostMapping("/sessions/self/revoke-others")
  public AjaxResult revokeOthers() {
    int count=0;
    for(Session s:active(ShiroUtils.getLoginName())) {
      if(s instanceof OnlineSession o && Objects.equals(o.getUserId(),ShiroUtils.getUserId())
          && !String.valueOf(s.getId()).equals(ShiroUtils.getSessionId())) {
        terminate(s);
        count++;
      }
    }
    return AjaxResult.success(Map.of("count",count));
  }
}
